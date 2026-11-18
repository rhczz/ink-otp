package io.github.rhczz.inkotp.ui.navigation

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.rhczz.inkotp.BuildConfig
import io.github.rhczz.inkotp.app.VaultViewModel
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.core.interchange.InterchangeCodec
import io.github.rhczz.inkotp.core.interchange.InterchangeFormat
import io.github.rhczz.inkotp.core.interchange.InterchangeException
import io.github.rhczz.inkotp.platform.*
import io.github.rhczz.inkotp.ui.components.*
import io.github.rhczz.inkotp.ui.model.UnlockUiState
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import io.github.rhczz.inkotp.ui.model.VaultListUiState
import io.github.rhczz.inkotp.ui.screens.*
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Screen { List, Detail, ManualAdd, Scan, Settings, Diagnostics, About }
private enum class PasswordTask { Change, Export, Import, Restore }

@Composable
internal fun InkApp(
    viewModel: VaultViewModel,
    security: DeviceSecurity,
    foreground: Boolean,
    privacyPolicy: PrivacyPolicy? = null,
    onWithdrawPrivacy: (() -> Boolean)? = null,
) {
    val session = viewModel.state
    val sessionToken = viewModel.sessionToken
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalInkColors.current
    var screen by remember { mutableStateOf(Screen.List) }
    val stack = remember { mutableStateListOf<Screen>() }
    var readingPrivacy by rememberSaveable { mutableStateOf(false) }
    var confirmingWithdrawal by rememberSaveable { mutableStateOf(false) }
    val openPrivacy: (() -> Unit)? = privacyPolicy?.let { { readingPrivacy = true } }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var addSheet by remember { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var setupPage by rememberSaveable { mutableStateOf(0) }
    var skipPassword by remember { mutableStateOf(false) }
    var passwordTask by rememberSaveable { mutableStateOf<PasswordTask?>(null) }
    var scannedInput by remember { mutableStateOf("") }
    var importUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var exportPickerToken by rememberSaveable { mutableStateOf<Long?>(null) }
    var importPickerToken by rememberSaveable { mutableStateOf<Long?>(null) }
    var importForRestore by rememberSaveable { mutableStateOf(false) }
    var imagePickerToken by rememberSaveable { mutableStateOf<Long?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    var fileJob by remember { mutableStateOf<Job?>(null) }
    var fileRequest by remember { mutableLongStateOf(0L) }
    var observedSessionToken by remember { mutableLongStateOf(sessionToken) }
    var message by remember { mutableStateOf<String?>(null) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var chooseExportFormat by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(InterchangeFormat.AEGIS_ENCRYPTED) }
    var importFormat by rememberSaveable { mutableStateOf<InterchangeFormat?>(null) }
    var exportReviewToken by rememberSaveable { mutableStateOf<Long?>(null) }

    fun cancelFileOperation() {
        fileRequest++
        fileJob?.cancel()
        fileJob = null
        fileBusy = false
    }
    fun launchFileOperation(token: Long, failure: String, cleanup: () -> Unit = {},
        forRestore: Boolean = false, operation: suspend () -> Unit,
    ) {
        if (!viewModel.isCurrentBackupRequest(token, forRestore)) { cleanup(); return }
        cancelFileOperation()
        val request = ++fileRequest
        fileBusy = true
        fileJob = scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (viewModel.isCurrentBackupRequest(token, forRestore)) viewModel.report(if (error is QrImageReadException || error is InterchangeException) error.message ?: failure else failure)
            } finally {
                if (fileRequest == request) { fileBusy = false; fileJob = null }
            }
        }.also { job -> job.invokeOnCompletion { cleanup() } }
    }
    fun navigate(next: Screen) { stack.add(screen); screen = next }
    fun back() { cancelFileOperation(); scannedInput = ""; screen = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else Screen.List }
    fun copy(item: VaultItemUi) {
        if (item.type == "totp" && viewModel.isCurrentSession(sessionToken)) security.copyCode(item.code, viewModel.state.settings.clipboardClearSeconds)
    }
    fun requirePassword(action: () -> Unit) {
        if (!viewModel.isCurrentSession(sessionToken)) return
        if (viewModel.state.passwordSet) action() else {
            viewModel.report("请先设置恢复口令，才能备份或恢复账号")
            passwordTask = PasswordTask.Change
        }
    }
    val createBackup = rememberLauncherForActivityResult(ExportDocumentContract()) { uri ->
        val token = exportPickerToken
        exportPickerToken = null
        val accepted = token != null && viewModel.endPicker(token)
        val ticket = if (accepted) viewModel.takeExport(token!!) else null
        if (uri != null && ticket != null) {
            launchFileOperation(ticket.sessionToken, "备份未能写入，请检查保存位置和空间后重试", cleanup = { ticket.bytes.fill(0) }) {
                BackupFiles.write(context, uri, ticket.bytes)
                if (viewModel.backupWritten(ticket)) {
                    message = if (ticket.format.plaintext) "明文迁移文件已保存。它包含可读取的账号密钥，请妥善管理并在迁移后自行清理不需要的副本。"
                        else "${ticket.format.displayName()}已保存，可用当前恢复口令打开。"
                }
            }
        } else {
            ticket?.bytes?.fill(0)
            token?.let(viewModel::discardExport)
            if (uri != null) viewModel.report("这次导出已失效，文件尚未写入。请解锁后重新导出备份。")
        }
    }
    fun launchExport(token: Long) {
        val description = viewModel.describeExport(token)
        if (description == null) { viewModel.report("导出已失效，请重新操作"); return }
        exportReviewToken = null
        exportPickerToken = viewModel.beginPicker()
        val filename = "inkotp-${LocalDate.now()}.${description.format.extension.trimStart('.')}"
        try { createBackup.launch(ExportDocumentRequest(filename, description.format.documentMime())) }
        catch (_: RuntimeException) {
            viewModel.endPicker(token); viewModel.discardExport(token); exportPickerToken = null
            viewModel.report("无法打开系统文件选择器，请检查系统文件应用后重新导出")
        }
    }

    val chooseBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val token = importPickerToken
        importPickerToken = null
        val forRestore = importForRestore
        val accepted = token != null && viewModel.endPicker(token)
        if (accepted && uri != null) {
            launchFileOperation(token!!, "文件无法读取或不是支持的验证器备份", forRestore = forRestore) {
                val bytes = BackupFiles.read(context, uri)
                var transferred = false
                try {
                    val format = withContext(Dispatchers.Default) { InterchangeCodec.detect(bytes) }
                    if (viewModel.isCurrentBackupRequest(token, forRestore)) {
                        if (format.requiresPassword) { importUri = uri; importFormat = format; passwordTask = PasswordTask.Import }
                        else { viewModel.previewImport(bytes, "", forRestore) { importUri = null; importFormat = null }; transferred = true }
                    }
                } finally { if (!transferred) bytes.fill(0) }
            }
        } else if (accepted && forRestore) viewModel.cancelImport()
        else if (uri != null) viewModel.report("这次文件选择已失效，请重新选择备份")
    }
    val chooseImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val token = imagePickerToken
        imagePickerToken = null
        val accepted = token != null && viewModel.endPicker(token)
        if (uri != null && accepted) {
            launchFileOperation(token!!, "图片无法读取，请重新选择或手动输入") {
                val input = readQrImage(context, uri)
                if (viewModel.isCurrentSession(token)) { scannedInput = input; navigate(Screen.ManualAdd) }
            }
        } else if (uri != null) viewModel.report("这次图片选择已失效，请解锁后重新选择")
    }
    fun gallery() {
        if (!viewModel.isCurrentSession(sessionToken)) return
        val token = viewModel.beginPicker()
        imagePickerToken = token
        try { chooseImage.launch("image/*") }
        catch (_: RuntimeException) { viewModel.endPicker(token); imagePickerToken = null; viewModel.report("无法打开系统图片选择器，请使用手动输入") }
    }
    fun selectBackup(forRestore: Boolean) {
        val current = viewModel.state
        if (!current.ready || current.busy || fileBusy || importPickerToken != null || current.unlocked == forRestore) return
        val token = viewModel.beginPicker(forRestore)
        importPickerToken = token
        importForRestore = forRestore
        try { chooseBackup.launch(arrayOf("*/*")) }
        catch (_: RuntimeException) {
            viewModel.endPicker(token)
            importPickerToken = null
            if (forRestore) viewModel.cancelImport()
            viewModel.report("无法打开系统文件选择器，请检查系统文件应用")
        }
    }
    fun importBackup() = requirePassword { selectBackup(forRestore = false) }
    fun cancelImportDecoding() {
        viewModel.cancelImport()
        passwordTask = null
        importUri = null
        importFormat = null
    }

    LaunchedEffect(session.ready, session.exists, session.unlocked, session.passwordSet, setup, setupPage) {
        if (session.ready && !session.exists) setup = true
        if (session.ready && session.exists && !session.unlocked) setup = false
        if (session.unlocked && session.exists && setup && setupPage < 2) setupPage = if (session.passwordSet) 2 else 3
    }
    LaunchedEffect(session.importNames, session.restoreAccountCount) {
        if (passwordTask == PasswordTask.Import && (session.importNames != null || session.restoreAccountCount != null)) {
            passwordTask = null
            importUri = null
            importFormat = null
        }
    }
    LaunchedEffect(session.error, session.busy, fileBusy) {
        if (session.error != null && session.backupRestoreActive && !session.busy && !fileBusy &&
            session.restoreAccountCount == null && importUri == null) viewModel.cancelImport()
    }
    LaunchedEffect(session.unlocked, session.restoreAccountCount) {
        if (session.unlocked && session.restoreAccountCount == null && passwordTask == PasswordTask.Restore) {
            passwordTask = null
            setup = false
        }
    }
    LaunchedEffect(sessionToken, session.unlocked, session.clearClipboardOnLock, session.systemAuthenticationRequired, session.backupRestoreActive) {
        if (session.systemAuthenticationRequired && setupPage == 2) setupPage = 3
        if (observedSessionToken != sessionToken || (!session.unlocked && !session.backupRestoreActive)) {
            if (session.clearClipboardOnLock) security.clearClipboard()
            cancelFileOperation()
            confirmingWithdrawal = false
            stack.clear(); screen = Screen.List; selectedId = null; query = ""; addSheet = false
            scannedInput = ""; passwordTask = null; importUri = null; importFormat = null; replaceConfirm = false; chooseExportFormat = false; exportReviewToken = null
            exportPickerToken = null; importPickerToken = null; imagePickerToken = null
            observedSessionToken = sessionToken
        }
    }
    LaunchedEffect(foreground, session.ready, session.busy, session.unlocked, session.systemAuthenticationRequired,
        session.biometricEnabled, session.deviceUnlockAvailable, session.systemRecoveryRequired, session.backupRestoreActive, sessionToken, readingPrivacy) {
        if (foreground && !readingPrivacy && session.ready && !session.busy && session.exists && !session.unlocked) {
            viewModel.requestAutomaticAuthentication(security)
        }
    }

    val secure = !BuildConfig.DEBUG || passwordTask != null || screen == Screen.ManualAdd || setup || !session.unlocked
    DisposableEffect(secure, security) {
        security.secureScreen(secure)
        onDispose { security.secureScreen(!BuildConfig.DEBUG) }
    }
    DisposableEffect(Unit) {
        onDispose {
            val current = viewModel.state
            if (fileBusy && importUri == null && current.backupRestoreActive &&
                !current.busy && current.restoreAccountCount == null) viewModel.cancelImport()
            fileRequest++
            fileJob?.cancel()
        }
    }
    BackHandler(enabled = !readingPrivacy && session.unlocked && !setup && stack.isNotEmpty() && !session.busy) { back() }
    BackHandler(enabled = readingPrivacy) { readingPrivacy = false }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            readingPrivacy && privacyPolicy != null -> PrivacyScreen(privacyPolicy, onBack = { readingPrivacy = false })
            !session.ready || (!session.unlocked && session.deviceUnlockAvailable &&
                !session.clearClipboardOnLock && session.error == null) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            setup && (!session.exists || session.unlocked) -> OnboardingScreen(page = setupPage,
                onNext = { setupPage = if (setupPage == 0) 1 else 3 },
                onBack = if (setupPage == 1 && !session.exists) ({ setupPage = 0 }) else null,
                onSkipPassword = { skipPassword = true }, onSetPassword = viewModel::initialize,
                onEnableSystemAuthentication = { viewModel.systemAuthentication(true, security) },
                onFinish = { setup = false; screen = Screen.List },
                onScan = { setup = false; screen = Screen.Scan }, onManual = { setup = false; screen = Screen.ManualAdd },
                passwordSet = session.passwordSet, systemAuthenticationAvailable = security.canUseSystemAuthentication(),
                onRestoreBackup = if (!session.exists) ({ selectBackup(forRestore = true) }) else null,
                onPrivacy = openPrivacy)
            !session.unlocked -> UnlockScreen(
                state = UnlockUiState(error = session.error, biometricAvailable = session.biometricEnabled && security.canUseBiometrics(), degraded = !session.passwordSet,
                    deviceUnlockAvailable = session.deviceUnlockAvailable, systemAuthenticationRequired = session.systemAuthenticationRequired,
                    systemRecoveryRequired = session.systemRecoveryRequired,
                    recoveryPasswordRequested = session.recoveryPasswordRequested),
                onUnlock = { viewModel.unlock(security) }, onPasswordSubmit = { viewModel.unlockPassword(it, security) },
                onRestoreBackup = { selectBackup(forRestore = true) })
            else -> when (screen) {
                Screen.List -> VaultListScreen(
                    state = VaultListUiState(items = session.items, query = query, backupReminder = session.backupReminder, clockWarning = session.clockWarning, passwordWarning = !session.passwordSet),
                    onQueryChange = { query = it }, onOpenItem = { selectedId = it.id; navigate(Screen.Detail) }, onAdd = { addSheet = true },
                    onSettings = { navigate(Screen.Settings) }, onOpenDiagnostics = { navigate(Screen.Diagnostics) }, onCopy = ::copy,
                    onLock = { security.clearClipboard(); viewModel.lock() })
                Screen.Detail -> {
                    val item = session.items.firstOrNull { it.id == selectedId }
                    if (item == null) LaunchedEffect(Unit) { back() } else ItemDetailScreen(item = item, secretBase32 = "", onBack = ::back,
                        onDelete = { viewModel.delete(item.id, ::back) }, onSave = { issuer, account -> viewModel.edit(item.id, issuer, account, ::back) },
                        onCopy = { copy(item) }, onMoveUp = { viewModel.move(item.id, -1) }, onMoveDown = { viewModel.move(item.id, 1) })
                }
                Screen.ManualAdd -> ManualAddScreen(onBack = ::back, initialInput = scannedInput,
                    onSave = { issuer, account, input -> viewModel.add(issuer, account, input) { scannedInput = ""; stack.clear(); screen = Screen.List } })
                Screen.Scan -> ScanScreen(onBack = ::back, onDetected = { scannedInput = it; screen = Screen.ManualAdd },
                    onGallery = ::gallery, onManual = { scannedInput = ""; screen = Screen.ManualAdd },
                    onPrivacy = openPrivacy)
                Screen.Settings -> SettingsScreen(state = session.settings, onBack = ::back,
                    onSystemAuthenticationChange = { viewModel.systemAuthentication(it, security) },
                    onLockTimeoutChange = { seconds -> viewModel.settings { it.copy(lockTimeoutSeconds = seconds) } },
                    onHideCodesChange = { hidden -> viewModel.settings { it.copy(hideCodesByDefault = hidden) } },
                    onClipboardChange = { seconds -> viewModel.settings { it.copy(clipboardClearSeconds = seconds) } },
                    onDiagnostics = { navigate(Screen.Diagnostics) }, onAbout = { navigate(Screen.About) },
                    onOnboarding = { message = "添加账号可使用列表右下角的加号。设置恢复口令后可导出加密备份，换机时可在首次启动选择从备份恢复。" },
                    onPassword = { passwordTask = PasswordTask.Change }, onExport = { requirePassword { chooseExportFormat = true } }, onImport = ::importBackup,
                    onLock = { security.clearClipboard(); viewModel.lock() },
                    onPrivacy = openPrivacy, onWithdrawPrivacy = onWithdrawPrivacy?.let { { confirmingWithdrawal = true } })
                Screen.Diagnostics -> DiagnosticsScreen(
                    state = viewModel.diagnostics(), onBack = ::back,
                    onRecalculate = viewModel::recalculateCodes, onRefreshResources = viewModel::refreshResources,
                )
                Screen.About -> AboutScreen(onBack = ::back, onPrivacy = openPrivacy)
            }
        }
        if (foreground && addSheet && session.unlocked) InkBottomSheet(onDismiss = { addSheet = false }) {
            AddSheetContent(onScan = { addSheet = false; navigate(Screen.Scan) }, onGallery = { addSheet = false; gallery() },
                onManual = { addSheet = false; scannedInput = ""; navigate(Screen.ManualAdd) })
        }
        if (!foreground) Box(Modifier.fillMaxSize().background(colors.surface))
        OperationBusyOverlay(
            busy = session.busy || fileBusy,
            operationKey = session.operationId to fileRequest,
            authenticationInProgress = security.isAuthenticationInProgress || !foreground,
            onCancel = if (session.importDecoding && !session.importCancelling) ::cancelImportDecoding else null,
            cancelling = session.importCancelling,
        )
    }
    if (foreground && session.unlocked && confirmingWithdrawal && onWithdrawPrivacy != null) {
        InkConfirmDialog(
            title = "撤回隐私同意？",
            message = "撤回后 InkOTP 会锁定并停止处理验证码数据，保留这台设备上的加密库和你自行保存的备份。再次同意后可以继续使用。",
            confirmText = "撤回同意",
            onConfirm = {
                confirmingWithdrawal = false
                if (!onWithdrawPrivacy()) message = "无法保存隐私选择，请稍后重试。"
            },
            onDismiss = { confirmingWithdrawal = false },
        )
    }
    if (foreground && skipPassword) InkConfirmDialog("暂不设置恢复口令？", "这台设备仍会加密保存账号，但换机或重装将无法恢复。你可以稍后在设置中补设恢复口令。", "暂时跳过",
        { skipPassword = false; viewModel.initialize(null) }, { skipPassword = false })
    if (foreground) passwordTask?.let { task ->
        VaultPasswordDialog(
            title = when (task) {
                PasswordTask.Change -> if (session.passwordSet) "修改恢复口令" else "设置恢复口令"
                PasswordTask.Export -> if (exportFormat.plaintext) "导出迁移文件" else "导出加密备份"
                PasswordTask.Import -> "打开加密备份"
                PasswordTask.Restore -> if (session.exists) "替换本机库并恢复？" else "设置新的本机恢复口令"
            },
            message = when (task) {
                PasswordTask.Change -> "口令至少 8 个字符，建议使用较长短语。修改不会更新旧备份，旧备份仍使用原口令。"
                PasswordTask.Export -> "所选格式：${exportFormat.displayName()}。请输入本机当前恢复口令，验证后准备导出。"
                PasswordTask.Import -> "已识别：${importFormat?.displayName() ?: "验证器备份"}。请输入创建这份备份时使用的口令。"
                PasswordTask.Restore -> if (session.exists)
                    "将用所选备份建立新的本机库。原库密文留在本应用的私有空间。此次操作会更新之前保留的副本。请设置新的本机恢复口令。"
                    else "为恢复后的本机库设置新恢复口令，至少 8 个字符。所选备份仍使用它原来的口令。"
            }, requireCurrent = task == PasswordTask.Change && session.passwordSet,
            newPassword = task == PasswordTask.Change || task == PasswordTask.Restore, busy = session.busy || fileBusy,
            confirmText = if (task != PasswordTask.Restore) "确认" else if (session.exists) "替换并恢复" else "恢复",
            onCancelBusy = if (task == PasswordTask.Import && session.importDecoding && !session.importCancelling) ::cancelImportDecoding else null,
            cancelling = task == PasswordTask.Import && session.importCancelling,
            onDismiss = {
                passwordTask = null
                importUri = null
                if (task == PasswordTask.Import && importForRestore) viewModel.cancelImport()
            },
            onConfirm = { current, password -> when (task) {
                PasswordTask.Change -> viewModel.setPassword(current, password, security) { passwordTask = null; message = "恢复口令已更新。旧备份仍使用原来的口令，建议重新导出备份。" }
                PasswordTask.Export -> viewModel.export(password, exportFormat) { token ->
                    if (!viewModel.isCurrentSession(token)) { viewModel.discardExport(token); return@export }
                    passwordTask = null
                    val description = viewModel.describeExport(token)
                    if (description?.warnings?.isNotEmpty() == true) exportReviewToken = token else launchExport(token)
                }
                PasswordTask.Import -> {
                    val uri = importUri
                    if (uri != null) {
                        val token = sessionToken
                        val forRestore = importForRestore
                        launchFileOperation(token, "备份无法读取或超过 12 MiB，请重新选择文件", forRestore = forRestore) {
                            val bytes = BackupFiles.read(context, uri)
                            if (viewModel.isCurrentBackupRequest(token, forRestore)) {
                                viewModel.previewImport(bytes, password, forRestore) { passwordTask = null; importUri = null; importFormat = null }
                            } else bytes.fill(0)
                        }
                    }
                }
                PasswordTask.Restore -> viewModel.confirmBackupRestore(password, security)
            } })
    }
    if (foreground && chooseExportFormat && session.unlocked) BackupFormatDialog(
        onConfirm = { format -> exportFormat = format; chooseExportFormat = false; passwordTask = PasswordTask.Export },
        onDismiss = { chooseExportFormat = false },
    )
    exportReviewToken?.let { token ->
        val description = viewModel.describeExport(token)
        if (foreground && description != null) InkConfirmDialog(
            title = if (description.format.plaintext) "导出含明文密钥的迁移文件？" else "确认备份兼容性",
            message = description.warnings.joinToString("\n\n"),
            confirmText = "了解并导出", onConfirm = { launchExport(token) },
            onDismiss = { exportReviewToken = null; viewModel.discardExport(token) },
        )
    }

    if (foreground && session.restoreAccountCount != null && passwordTask != PasswordTask.Restore) {
        InkConfirmDialog(
            title = "恢复 ${session.restoreAccountCount} 个账号",
            message = buildString {
                session.importFormat?.let { appendLine(InterchangeFormat.valueOf(it).displayName()) }
                session.importWarnings.forEach { appendLine(it) }
                append("确认账号数量和兼容提示后，设置新的本机恢复口令。")
            },
            confirmText = "继续设置口令",
            onConfirm = { if (!session.busy) passwordTask = PasswordTask.Restore },
            onDismiss = viewModel::cancelImport,
        )
    }
    if (foreground && session.importNames != null) AlertDialog(
        onDismissRequest = { if (!session.busy) viewModel.cancelImport() }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(AppTokens.Radius.sheet), containerColor = colors.surface,
        title = { Text("导入 ${session.importNames.size} 个账号", style = AppType.section) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${session.importConflicts} 个账号与本机重复。合并保留本机设置和恢复口令。", style = AppType.body)
            session.importFormat?.let { name ->
                Text("格式：${runCatching { InterchangeFormat.valueOf(name).displayName() }.getOrDefault(name)}", style = AppType.caption)
            }
            session.importWarnings.forEach { warning ->
                Spacer(Modifier.height(AppTokens.Spacing.s))
                Text(warning, style = AppType.caption, color = colors.warning)
            }
            Spacer(Modifier.height(AppTokens.Spacing.m))
            session.importNames.take(50).forEach { Text(it, style = AppType.caption) }
            if (session.importNames.size > 50) Text("其余 ${session.importNames.size - 50} 个账号也将导入", style = AppType.caption)
            Spacer(Modifier.height(AppTokens.Spacing.l))
            InkPrimaryButton("合并，重复时保留本机", { viewModel.confirmImport(MergeConflictPolicy.KEEP_LOCAL, false, security) }, enabled = !session.busy)
            InkSecondaryButton("合并，重复时使用备份", { viewModel.confirmImport(MergeConflictPolicy.OVERWRITE_LOCAL, false, security) }, enabled = !session.busy)
            InkSecondaryButton("合并，重复时两个都保留", { viewModel.confirmImport(MergeConflictPolicy.KEEP_BOTH, false, security) }, enabled = !session.busy)
            InkSecondaryButton("替换本机全部账号…", { replaceConfirm = true }, enabled = !session.busy)
        } }, confirmButton = {}, dismissButton = { TextButton(enabled = !session.busy, onClick = viewModel::cancelImport) { Text("取消") } })
    if (foreground && replaceConfirm) InkConfirmDialog("替换本机全部账号？", "本机现有账号将被这份备份替换。建议先导出本机备份。", "替换",
        { replaceConfirm = false; viewModel.confirmImport(MergeConflictPolicy.KEEP_LOCAL, true, security) }, { replaceConfirm = false }, destructive = true)
    val alert = session.error ?: session.notice ?: message
    if (foreground && alert != null && (session.unlocked || setup || session.backupRestoreActive || message != null)) AlertDialog(
        onDismissRequest = { viewModel.clearError(); viewModel.clearNotice(); message = null },
        containerColor = colors.surface, shape = RoundedCornerShape(AppTokens.Radius.sheet),
        title = { Text(if (session.error != null) "操作未完成" else "提示", style = AppType.section) },
        text = { Text(alert, style = AppType.body) },
        confirmButton = { TextButton(onClick = { viewModel.clearError(); viewModel.clearNotice(); message = null }) { Text("知道了") } })
}
