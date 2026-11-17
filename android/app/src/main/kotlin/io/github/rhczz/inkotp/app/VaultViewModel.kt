package io.github.rhczz.inkotp.app

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rhczz.inkotp.core.backup.BackupException
import io.github.rhczz.inkotp.core.backup.BackupMerge
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.core.crypto.SecretBytes
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSettings
import io.github.rhczz.inkotp.core.time.DriftDetector
import io.github.rhczz.inkotp.core.uri.OtpauthException
import io.github.rhczz.inkotp.core.vault.VaultException
import io.github.rhczz.inkotp.data.VaultRepository
import io.github.rhczz.inkotp.data.VaultAccessException
import io.github.rhczz.inkotp.data.VaultAccessError
import io.github.rhczz.inkotp.core.interchange.InterchangeCodec
import io.github.rhczz.inkotp.core.interchange.InterchangeFormat
import io.github.rhczz.inkotp.core.interchange.InterchangeException
import io.github.rhczz.inkotp.perf.OtpProjectionCache
import io.github.rhczz.inkotp.perf.ProjectionCacheStats
import io.github.rhczz.inkotp.perf.ResourceSampler
import io.github.rhczz.inkotp.perf.ResourceSnapshot
import io.github.rhczz.inkotp.perf.OperationKind
import io.github.rhczz.inkotp.perf.OperationTiming
import io.github.rhczz.inkotp.platform.BiometricAuthenticationException
import io.github.rhczz.inkotp.platform.DeviceSecurity
import io.github.rhczz.inkotp.ui.model.DiagnosticsUiState
import io.github.rhczz.inkotp.ui.model.SettingsUiState
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SessionUiState(
    val ready: Boolean = false,
    val exists: Boolean = false,
    val unlocked: Boolean = false,
    val busy: Boolean = false,
    val passwordSet: Boolean = false,
    val biometricEnabled: Boolean = false,
    val items: List<VaultItemUi> = emptyList(),
    val settings: SettingsUiState = SettingsUiState(biometricEnabled = false, passwordSet = false, lastBackupAt = null),
    val backupReminder: Boolean = true,
    val clockWarning: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val importNames: List<String>? = null,
    val importConflicts: Int = 0,
    val clearClipboardOnLock: Boolean = true,
    val systemAuthenticationRequired: Boolean = false,
    val systemRecoveryRequired: Boolean = false,
    val importFormat: String? = null,
    val importWarnings: List<String> = emptyList(),
    val resources: ResourceSnapshot? = null,
    val samplingResources: Boolean = false,
)

/** Export bytes owned by one unlocked session and one vault revision; may be explicitly selected plaintext. */
class ExportTicket internal constructor(val sessionToken: Long, val revision: String, val bytes: ByteArray,
    val format: InterchangeFormat = InterchangeFormat.INKOTP_2FAB, val warnings: List<String> = emptyList())

data class ExportDescription(val format: InterchangeFormat, val warnings: List<String>)

/** The only owner of a decrypted repository. UI state never contains OTP seeds. */
class VaultViewModel internal constructor(application: Application, private val repository: VaultRepository,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, VaultRepository(application))
    private val gate = Mutex()
    private val metadata = application.getSharedPreferences("vault_metadata", 0)
    private val drift = DriftDetector()
    @Volatile private var generation = 0L
    private var activeOperation: Job? = null
    private var pendingImport: VaultModel? = null
    private var pendingExport: ExportTicket? = null
    private var backgroundJob: Job? = null
    private var backgroundAt: Long? = null
    private var pickerInFlight = false
    private var pickerGeneration: Long? = null
    private var pickerDeadline: Long? = null
    private val projection = OtpProjectionCache()
    private var tickerJob: Job? = null
    private var resourceJob: Job? = null
    private var foreground = false
    private var systemAuthenticationDeadline: Long? = null
    private var automaticAuthenticationGeneration: Long? = null
    private var lastOperation: OperationTiming? = null
    internal val tickerActive: Boolean get() = tickerJob?.isActive == true
    internal suspend fun projectionStatistics(): ProjectionCacheStats = gate.withLock { projection.stats() }
    var state by mutableStateOf(SessionUiState())
        private set
    val sessionToken: Long get() = generation
    fun isCurrentSession(token: Long): Boolean = token == generation && state.unlocked

    init {
        perform(requireUnlocked = false, kind = OperationKind.INITIALIZE) {
            if (repository.exists() && !repository.systemAuthRequired() && !repository.hasPassword()) repository.unlockDevice()
        }
    }

    /** One foreground ticker; sleeping or locked vaults have no polling task. */
    private fun reconcileTicker() {
        if (!foreground || !state.unlocked || state.busy) { tickerJob?.cancel(); tickerJob = null; return }
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (foreground && state.unlocked && !state.busy) {
                val token = generation
                gate.withLock {
                    if (foreground && isCurrentSession(token) && !state.busy) {
                        val items = withContext(Dispatchers.Default) {
                            repository.model?.let { projection.project(it, wallClockMillis() / 1000) }.orEmpty()
                        }
                        if (foreground && isCurrentSession(token)) {
                            val sample = drift.sample(wallClockMillis(), SystemClock.elapsedRealtime())
                            state = state.copy(items = items, clockWarning = state.clockWarning || sample.drifted || !automaticTime())
                        }
                    }
                }
                delay((1000 - wallClockMillis() % 1000).coerceAtLeast(1))
            }
        }
    }

    private fun perform(requireUnlocked: Boolean = true, kind: OperationKind = OperationKind.SAVE, cleanup: () -> Unit = {}, operation: suspend (Long) -> Unit) {
        if (state.busy || (requireUnlocked && !state.unlocked)) { cleanup(); return }
        val token = generation
        state = state.copy(busy = true, error = null)
        reconcileTicker()
        val started = SystemClock.elapsedRealtime()
        activeOperation = viewModelScope.launch {
            gate.withLock {
                try {
                    if (token != generation) return@withLock
                    withContext(Dispatchers.IO) { operation(token) }
                    if (token == generation) refresh(token) else withContext(Dispatchers.IO) { repository.lock() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (token == generation) {
                        if (!state.unlocked) {
                            withContext(Dispatchers.IO) { repository.lock() }
                            refreshLockedMetadata(token)
                        } else {
                            // A metadata error after policy persistence must not retain an older grace period.
                            val required = withContext(Dispatchers.IO) {
                                try { repository.systemAuthRequired() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { true }
                            }
                            if (token == generation) state = state.copy(systemAuthenticationRequired = required,
                                settings = state.settings.copy(systemAuthenticationEnabled = required))
                        }
                        if (token == generation) state = state.copy(error = safeMessage(e), ready = true,
                            systemRecoveryRequired = state.systemRecoveryRequired ||
                                (e is VaultAccessException && e.code == VaultAccessError.SYSTEM_AUTH_RECOVERY_REQUIRED))
                    }
                } finally {
                    if (token != generation) withContext(NonCancellable + Dispatchers.IO) {
                        pendingImport?.let(repository::discardModel)
                        pendingImport = null
                        projection.clear()
                        repository.lock()
                    }
                    if (token == generation) {
                        lastOperation = OperationTiming(kind, (SystemClock.elapsedRealtime() - started).coerceAtLeast(0))
                        state = state.copy(busy = false)
                        if (systemAuthenticationDeadline != null && foreground) {
                            systemAuthenticationDeadline = null
                            backgroundJob?.cancel()
                        }
                        reconcileTicker()
                    }
                }
            }
        }.also { job -> job.invokeOnCompletion { cleanup() } }
    }

    private suspend fun refresh(token: Long) {
        val previous = state
        val snapshot = withContext(Dispatchers.IO) {
            val exists = repository.exists()
            val password = exists && repository.hasPassword()
            val system = exists && repository.systemAuthRequired()
            val biometric = exists && !system && repository.wrapMode() == "biometric"
            val model = repository.model
            val settings = model?.settings ?: VaultSettings()
            previous.copy(ready = true, exists = exists, unlocked = model != null, passwordSet = password,
                systemAuthenticationRequired = system, systemRecoveryRequired = false,
                biometricEnabled = biometric, items = model?.let { projection.project(it, wallClockMillis() / 1000) }.orEmpty(),
                settings = SettingsUiState(biometricEnabled = biometric, passwordSet = password, systemAuthenticationEnabled = system,
                    lockTimeoutSeconds = settings.lockTimeoutSeconds, hideCodesByDefault = settings.hideCodesByDefault,
                    clipboardClearSeconds = settings.clipboardClearSeconds, lastBackupAt = metadata.getString("lastBackupAt", null)),
                backupReminder = model != null && metadata.getString("backupRevision", null) != model.updatedAt,
                clockWarning = previous.clockWarning || !automaticTime())
        }
        if (token == generation) state = snapshot.copy(resources = state.resources, samplingResources = state.samplingResources)
    }

    fun initialize(password: String?) {
        password?.let { VaultActions.passwordError(it)?.let { error -> report(error); return } }
        val chars = password?.toCharArray()
        if (state.busy) { chars?.fill('\u0000'); return }
        perform(false, kind = OperationKind.INITIALIZE, cleanup = { chars?.fill('\u0000') }) { try { repository.initialize(chars) } finally { chars?.fill('\u0000') } }
    }

    fun unlockPassword(password: String) = unlockPassword(password, null)

    /** Activity recreation does not repeat a cancelled prompt; a new process gets a fresh attempt. */
    fun requestAutomaticSystemAuthentication(security: DeviceSecurity) {
        if (!foreground || !state.ready || state.busy || state.unlocked || !state.systemAuthenticationRequired ||
            automaticAuthenticationGeneration == generation) return
        automaticAuthenticationGeneration = generation
        unlock(security)
    }

    fun unlockPassword(password: String, security: DeviceSecurity?) {
        val chars = password.toCharArray()
        if (state.busy) { chars.fill('\u0000'); return }
        if (state.systemAuthenticationRequired && security == null) { chars.fill('\u0000'); report("请先完成系统身份验证"); return }
        perform(false, kind = OperationKind.UNLOCK, cleanup = { chars.fill('\u0000') }) { token ->
            try {
                if (repository.systemAuthRequired()) {
                    val request = repository.prepareSystemRecovery()
                    val proof = withContext(Dispatchers.Main) { security!!.authenticateSystem(request, "验证身份并重新绑定 InkOTP") }
                    checkGeneration(token)
                    repository.recoverSystemAuth(chars, proof)
                } else repository.unlockPassword(chars)
            } finally { chars.fill('\u0000') }
        }
    }

    fun unlock(security: DeviceSecurity) {
        if (state.systemAuthenticationRequired) {
            perform(false, kind = OperationKind.UNLOCK) { token ->
                val request = repository.prepareSystemUnlock()
                val proof = withContext(Dispatchers.Main) { security.authenticateSystem(request, "验证身份以打开 InkOTP") }
                checkGeneration(token)
                repository.unlockSystemAuth(proof)
            }
            return
        }
        if (!state.passwordSet) { perform(false) { repository.unlockDevice() }; return }
        if (!state.biometricEnabled) { report("请使用恢复口令解锁"); return }
        perform(false) { token ->
            val cipher = repository.prepareUnlock()
            val authenticated = withContext(Dispatchers.Main) { security.authenticate(cipher, "解锁 InkOTP") }
            checkGeneration(token)
            repository.unlock(authenticated)
        }
    }

    fun systemAuthentication(enabled: Boolean, security: DeviceSecurity) {
        if (enabled && !state.passwordSet) { report("请先设置恢复口令，以便手机验证方式变更后恢复账号"); return }
        if (!security.canUseSystemAuthentication()) { report("请先在手机系统设置中启用锁屏 PIN、密码或图案"); return }
        perform(kind = OperationKind.AUTHENTICATION) { token ->
            val request = if (enabled) repository.prepareSystemEnable() else repository.prepareSystemDisable()
            val proof = withContext(Dispatchers.Main) {
                security.authenticateSystem(request, if (enabled) "开启每次进入验证" else "关闭每次进入验证")
            }
            checkGeneration(token)
            if (enabled) repository.enableSystemAuth(proof) else repository.disableSystemAuth(proof)
            withContext(Dispatchers.Main) {
                checkGeneration(token)
                state = state.copy(systemAuthenticationRequired = enabled,
                    settings = state.settings.copy(systemAuthenticationEnabled = enabled))
            }
        }
    }

    fun biometric(enabled: Boolean, security: DeviceSecurity) {
        if (state.systemAuthenticationRequired) { report("每次进入验证已包含系统允许的生物识别；如需更改，请先关闭该设置"); return }
        if (enabled && !state.passwordSet) { report("请先设置恢复口令，再开启生物识别"); return }
        if (enabled && !security.canUseBiometrics()) { report("设备没有可用的强生物识别，请先在系统设置中录入指纹或受支持的人脸"); return }
        perform { token ->
            if (enabled) {
                val cipher = repository.prepareEnable()
                val authenticated = withContext(Dispatchers.Main) { security.authenticate(cipher, "开启生物识别解锁") }
                checkGeneration(token)
                repository.enable(authenticated)
            } else repository.disableBiometric()
        }
    }

    fun add(issuer: String, account: String, input: String, done: () -> Unit) = mutate(done) { VaultActions.add(it, issuer, account, input) }
    fun edit(id: String, issuer: String, account: String, done: () -> Unit) = mutate(done) { VaultActions.edit(it, id, issuer, account) }
    fun delete(id: String, done: () -> Unit) = mutate(done) { VaultActions.delete(it, id) }
    fun move(id: String, direction: Int) = mutate { VaultActions.move(it, id, direction) }

    private fun mutate(done: () -> Unit = {}, change: (VaultModel) -> VaultModel) = perform { token ->
        val current = repository.model ?: throw IllegalStateException()
        val candidate = change(current)
        try {
            repository.save(candidate)
            withContext(Dispatchers.Main) { if (token == generation) done() }
        } finally {
            candidate.items.filter { item -> current.items.none { it.secret === item.secret } }.forEach { SecretBytes.wipe(it.secret) }
        }
    }

    fun settings(update: (VaultSettings) -> VaultSettings) = mutate { it.copy(settings = update(it.settings), updatedAt = Instant.now().toString()) }

    fun setPassword(current: String, new: String, done: () -> Unit) {
        VaultActions.passwordError(new)?.let { report(it); return }
        val old = current.takeIf { state.passwordSet }?.toCharArray()
        val fresh = new.toCharArray()
        if (state.busy || !state.unlocked) { old?.fill('\u0000'); fresh.fill('\u0000'); return }
        perform(kind = OperationKind.CHANGE_PASSWORD, cleanup = { old?.fill('\u0000'); fresh.fill('\u0000') }) { token ->
            try { repository.setPassword(old, fresh); withContext(Dispatchers.Main) { if (token == generation) done() } }
            finally { old?.fill('\u0000'); fresh.fill('\u0000') }
        }
    }

    /** One bounded in-memory export; plaintext formats require explicit UI confirmation. */
    fun export(password: String, ready: (Long) -> Unit) = export(password, InterchangeFormat.INKOTP_2FAB, ready)

    fun export(password: String, format: InterchangeFormat, ready: (Long) -> Unit) {
        val chars = password.toCharArray()
        if (state.busy || !state.unlocked) { chars.fill('\u0000'); return }
        perform(kind = OperationKind.EXPORT, cleanup = { chars.fill('\u0000') }) { token ->
            try {
                val model = repository.model ?: throw IllegalStateException()
                val revision = model.updatedAt
                if (!repository.verifyPassword(chars.copyOf())) throw SafeInputException("恢复口令不正确，未导出文件")
                val canonical = Normalizer.normalize(password, Normalizer.Form.NFC).toCharArray()
                val result = try { InterchangeCodec.encode(model, format, if (format.requiresPassword) canonical else null) }
                    finally { canonical.fill('\u0000') }
                val bytes = result.bytes
                var retained = false
                try {
                    withContext(Dispatchers.Main) {
                        if (isCurrentSession(token)) {
                            pendingExport?.bytes?.fill(0)
                            pendingExport = ExportTicket(token, revision, bytes, format, result.warnings.map { it.message })
                            retained = true
                            ready(token)
                        }
                    }
                } finally { if (!retained) bytes.fill(0) }
            }
            finally { chars.fill('\u0000') }
        }
    }

    fun takeExport(token: Long): ExportTicket? {
        if (!isCurrentSession(token)) { discardExport(token); return null }
        val ticket = pendingExport?.takeIf { it.sessionToken == token } ?: return null
        pendingExport = null
        return ticket
    }

    fun describeExport(token: Long): ExportDescription? = pendingExport
        ?.takeIf { it.sessionToken == token && isCurrentSession(token) }
        ?.let { ExportDescription(it.format, it.warnings) }

    fun discardExport(token: Long) {
        pendingExport?.takeIf { it.sessionToken == token }?.let { it.bytes.fill(0); pendingExport = null }
    }

    fun backupWritten(ticket: ExportTicket): Boolean {
        if (!isCurrentSession(ticket.sessionToken)) return false
        metadata.edit().putString("lastBackupAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
            .putString("backupRevision", ticket.revision).apply()
        perform { }
        return true
    }

    fun previewImport(bytes: ByteArray, password: String, done: () -> Unit) {
        val chars = password.toCharArray()
        if (state.busy || !state.unlocked) { bytes.fill(0); chars.fill('\u0000'); return }
        perform(kind = OperationKind.IMPORT, cleanup = { bytes.fill(0); chars.fill('\u0000') }) { token ->
            try {
                val imported = InterchangeCodec.decode(bytes, chars.takeIf { it.isNotEmpty() })
                val remote = imported.model
                if (token != generation) { repository.discardModel(remote); throw CancellationException() }
                pendingImport?.let(repository::discardModel)
                pendingImport = remote
                val localKeys = repository.model!!.items.map(BackupMerge::keyOf).toSet()
                withContext(Dispatchers.Main) {
                    checkGeneration(token)
                    state = state.copy(importFormat = imported.format.name, importWarnings = imported.warnings.map { it.message }, importNames = remote.items.map { "${it.issuer ?: "账号"} · ${it.accountName}" },
                        importConflicts = remote.items.count { BackupMerge.keyOf(it) in localKeys })
                    done()
                }
            } finally { bytes.fill(0); chars.fill('\u0000') }
        }
    }

    fun confirmImport(policy: MergeConflictPolicy, replace: Boolean) = perform(kind = OperationKind.IMPORT) { token ->
        val remote = pendingImport ?: throw IllegalStateException()
        repository.importBackup(remote, policy, replace)
        repository.discardModel(remote)
        pendingImport = null
        withContext(Dispatchers.Main) { checkGeneration(token); state = state.copy(importNames = null, importWarnings = emptyList(), importFormat = null, notice = "导入完成，请继续使用本机恢复口令。系统验证策略保持开启时，下次进入可能需要用系统验证和恢复口令重新绑定。") }
    }

    fun cancelImport() {
        if (state.busy) return
        state = state.copy(importNames = null, importWarnings = emptyList(), importFormat = null)
        viewModelScope.launch { gate.withLock { pendingImport?.let(repository::discardModel); pendingImport = null } }
    }

    fun lock(clearClipboard: Boolean = true) {
        val token = ++generation
        activeOperation?.cancel()
        tickerJob?.cancel(); tickerJob = null
        resourceJob?.cancel(); resourceJob = null
        backgroundJob?.cancel()
        backgroundAt = null
        pickerInFlight = false
        pickerGeneration = null
        pickerDeadline = null
        systemAuthenticationDeadline = null
        pendingExport?.bytes?.fill(0)
        pendingExport = null
        state = state.copy(unlocked = false, busy = false, items = emptyList(), importNames = null, error = null,
            clearClipboardOnLock = clearClipboard, samplingResources = false, resources = null, importWarnings = emptyList(), importFormat = null)
        viewModelScope.launch {
            gate.withLock {
                if (token != generation) return@withLock
                withContext(Dispatchers.IO) {
                    pendingImport?.let(repository::discardModel)
                    pendingImport = null
                    projection.clear()
                    repository.lock()
                }
                refreshLockedMetadata(token)
            }
        }
    }

    private suspend fun refreshLockedMetadata(token: Long) {
        var exists = true
        var password = true
        var biometric = false
        var system = true
        var error: String? = null
        withContext(Dispatchers.IO) {
            try {
                exists = repository.exists()
                if (exists) {
                    system = repository.systemAuthRequired()
                    password = repository.hasPassword()
                    biometric = !system && repository.wrapMode() == "biometric"
                } else { password = false; system = false }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A damaged or unreadable existing file must never send the UI back to creation.
                error = "加密库暂时无法读取，现有文件不会被自动重建。请重试或使用备份恢复。"
            }
        }
        if (token == generation) {
            state = state.copy(ready = true, exists = exists, passwordSet = password, biometricEnabled = biometric,
                systemAuthenticationRequired = system, error = error,
                settings = state.settings.copy(passwordSet = password, biometricEnabled = biometric, systemAuthenticationEnabled = system))
        }
    }

    fun beginPicker(): Long {
        check(state.unlocked)
        pickerInFlight = true
        pickerGeneration = generation
        pickerDeadline = SystemClock.elapsedRealtime() + 60_000L
        return generation
    }

    /** Rejects stale results before changing any newer request's timer or session. */
    fun endPicker(token: Long): Boolean {
        if (!isCurrentSession(token) || !pickerInFlight || pickerGeneration != token) return false
        if (SystemClock.elapsedRealtime() >= (pickerDeadline ?: 0L)) {
            lock(clearClipboard = false)
            return false
        }
        pickerInFlight = false
        pickerGeneration = null
        pickerDeadline = null
        backgroundAt = null
        backgroundJob?.cancel()
        reconcileTicker()
        return true
    }

    fun onSystemAuthenticationBackground() {
        foreground = false
        reconcileTicker()
        resourceJob?.cancel()
        val now = SystemClock.elapsedRealtime()
        val deadline = systemAuthenticationDeadline ?: (now + 120_000L).also { systemAuthenticationDeadline = it }
        backgroundAt = null
        backgroundJob?.cancel()
        if (deadline <= now) { lock(clearClipboard = false); return }
        backgroundJob = viewModelScope.launch { delay(deadline - now); lock(clearClipboard = false) }
    }

    fun onBackground() {
        foreground = false
        reconcileTicker()
        resourceJob?.cancel()
        if (!state.unlocked) { if (state.busy) lock(clearClipboard = false); return }
        backgroundAt = SystemClock.elapsedRealtime()
        // A user initiated system picker is part of the active operation. Nothing is displayed behind it.
        val milliseconds = if (pickerInFlight) (pickerDeadline ?: 0L) - SystemClock.elapsedRealtime()
            else if (state.systemAuthenticationRequired) 0L else state.settings.lockTimeoutSeconds * 1000L
        if (milliseconds <= 0) { lock(clearClipboard = false); return }
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch { delay(milliseconds); lock(clearClipboard = false) }
    }

    fun onForeground() {
        foreground = true
        val systemDeadline = systemAuthenticationDeadline
        if (systemDeadline != null) {
            if (SystemClock.elapsedRealtime() >= systemDeadline) { lock(clearClipboard = false); return }
            if (state.busy) return
            systemAuthenticationDeadline = null
            backgroundJob?.cancel()
        }
        if (pickerInFlight) {
            if (SystemClock.elapsedRealtime() >= (pickerDeadline ?: 0L)) lock(clearClipboard = false)
            return
        }
        val leftAt = backgroundAt
        if (leftAt != null && (state.systemAuthenticationRequired ||
                SystemClock.elapsedRealtime() - leftAt >= state.settings.lockTimeoutSeconds * 1000L)) lock(clearClipboard = false)
        backgroundJob?.cancel()
        backgroundAt = null
        reconcileTicker()
    }

    fun diagnostics(): DiagnosticsUiState = DiagnosticsUiState(
        deviceTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        autoTime = automaticTime(), driftDetected = state.clockWarning,
        resources = state.resources?.let { resource -> listOf(
            "进程内存 PSS" to (resource.totalPssBytes?.let(::formatBytes) ?: "系统未提供"),
            "Java 堆使用" to "${formatBytes(resource.javaHeapUsedBytes)} / ${formatBytes(resource.javaHeapMaxBytes)}",
            "原生堆" to (resource.nativeHeapAllocatedBytes?.let(::formatBytes) ?: "系统未提供"),
            "私有文件逻辑大小" to (formatBytes(resource.privateStorageBytes) + if (resource.privateStorageComplete) "" else "（部分统计）"),
            "本次采样" to "${resource.sampleDurationMillis} ms / ${resource.scannedEntries} 个目录项",
        ) + listOfNotNull(resource.lastOperation?.let { "最近操作（含验证等待）" to "${operationLabel(it.kind)} · ${it.elapsedMillis} ms" }) }.orEmpty(),
        resourceSampling = state.samplingResources,
    )
    fun refreshClock() {
        drift.reset()
        state = state.copy(clockWarning = !automaticTime())
        refreshResources()
    }
    fun refreshResources() {
        if (!state.unlocked || resourceJob?.isActive == true) return
        val token = generation
        val timing = lastOperation
        state = state.copy(samplingResources = true)
        resourceJob = viewModelScope.launch {
            try {
                val sample = ResourceSampler.sample(getApplication(), timing)
                if (isCurrentSession(token)) state = state.copy(resources = sample)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isCurrentSession(token)) report("资源采样未完成，可稍后重试") }
            finally { if (token == generation) state = state.copy(samplingResources = false) }
        }
    }
    private fun formatBytes(value: Long): String = String.format(java.util.Locale.ROOT, "%.2f MiB", value / 1048576.0)
    private fun operationLabel(kind: OperationKind): String = when (kind) {
        OperationKind.INITIALIZE -> "打开或创建加密库"
        OperationKind.UNLOCK -> "解锁"
        OperationKind.SAVE -> "保存"
        OperationKind.CHANGE_PASSWORD -> "修改恢复口令"
        OperationKind.AUTHENTICATION -> "身份验证"
        OperationKind.EXPORT -> "导出"
        OperationKind.IMPORT -> "导入"
    }
    private suspend fun checkGeneration(token: Long) { coroutineContext.ensureActive(); if (token != generation) throw CancellationException() }
    fun report(message: String) { state = state.copy(error = message) }
    fun clearError() { state = state.copy(error = null) }
    fun clearNotice() { state = state.copy(notice = null) }
    private fun automaticTime(): Boolean = Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.AUTO_TIME, 0) == 1

    override fun onCleared() {
        generation++
        pendingExport?.bytes?.fill(0)
        pendingExport = null
        activeOperation?.cancel()
        tickerJob?.cancel(); resourceJob?.cancel()
        backgroundJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch { gate.withLock { pendingImport?.let(repository::discardModel); pendingImport = null; projection.clear(); repository.lock() } }
        super.onCleared()
    }

    private fun safeMessage(error: Exception): String = when (error) {
        is SafeInputException -> error.message ?: "输入无效，请检查后重试"
        is VaultAccessException -> error.message ?: "无法打开加密库，请重试"
        is OtpauthException -> "密钥或 otpauth 链接无效，请检查算法、位数、周期和密钥内容"
        is InterchangeException -> error.message ?: "不支持或损坏的备份文件，未修改现有数据"
        is BackupException -> "备份无法打开：口令不正确、文件损坏或格式不受支持。现有库未被替换。"
        is VaultException -> "加密库无法打开：口令不正确、文件损坏或格式不受支持。不会自动重建或清空。"
        is BiometricAuthenticationException -> error.message ?: "生物识别未完成，请重试或使用恢复口令"
        else -> "操作未完成，请检查输入、文件访问权限或设备状态后重试"
    }
}
