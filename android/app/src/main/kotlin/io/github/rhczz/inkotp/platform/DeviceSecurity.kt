package io.github.rhczz.inkotp.platform

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.util.UUID
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Device APIs only. The host owns the vault session and decides when it locks. */
class DeviceSecurity(activity: FragmentActivity) {
    private var activity: FragmentActivity? = activity
    private val appContext = activity.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ownedClip: OwnedClip? = null
    private var clearTask: Runnable? = null
    private var activePrompt: BiometricPrompt? = null
    private var activeAuthentication: CancellableContinuation<*>? = null
    private var activeCredentialLauncher: ActivityResultLauncher<Intent>? = null
    private var closed = false
    var isAuthenticationInProgress by mutableStateOf(false)
        private set

    fun canUseBiometrics(): Boolean = !closed &&
        BiometricManager.from(appContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

    fun canUseSystemAuthentication(): Boolean {
        if (closed || appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) return false
        return Build.VERSION.SDK_INT < 30 || BiometricManager.from(appContext).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun authenticateSystem(request: SystemAuthRequest, title: String): SystemAuthProof =
        runAuthentication {
            if (!canUseSystemAuthentication()) throw BiometricAuthenticationException("请先在手机系统设置中启用锁屏 PIN、密码或图案。")
            if (Build.VERSION.SDK_INT >= 30) {
                val cipher = request.cipher ?: throw BiometricAuthenticationException("验证请求已失效，请重新操作。")
                SystemAuthProof(request, authenticateCipher(cipher, title, allowCredential = true))
            } else confirmDeviceCredential(request, title)
        }

    /** Both OS authentication paths own the same bounded, observable lifecycle exemption. */
    private suspend fun <T> runAuthentication(operation: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        if (closed) throw BiometricAuthenticationException("页面已关闭，请重新打开后解锁。")
        if (isAuthenticationInProgress || activeAuthentication != null) {
            throw BiometricAuthenticationException("已有身份验证正在进行，请先完成或取消。")
        }
        isAuthenticationInProgress = true
        try {
            withTimeout(120_000L) { operation() }
        } catch (_: TimeoutCancellationException) {
            throw BiometricAuthenticationException("系统身份验证已超时，请重试。", cancelled = true)
        } finally { isAuthenticationInProgress = false }
    }

    @Suppress("DEPRECATION")
    private suspend fun confirmDeviceCredential(request: SystemAuthRequest, title: String): SystemAuthProof =
        suspendCancellableCoroutine { continuation ->
            val host = activity
            val intent = host?.getSystemService(KeyguardManager::class.java)
                ?.createConfirmDeviceCredentialIntent(title, "请验证手机锁屏身份，InkOTP 不会读取或保存锁屏密码。")
            if (closed || host == null || intent == null || request.cipher != null) {
                continuation.resumeWithException(BiometricAuthenticationException("无法打开系统身份验证，请检查手机锁屏设置。"))
                return@suspendCancellableCoroutine
            }
            lateinit var launcher: ActivityResultLauncher<Intent>
            fun detach() {
                if (activeCredentialLauncher === launcher) {
                    activeCredentialLauncher = null
                    activeAuthentication = null
                }
                launcher.unregister()
            }
            // A unique request key prevents a late RESULT_OK from authorizing a newer request.
            launcher = host.activityResultRegistry.register(
                "inkotp-system-auth-${UUID.randomUUID()}", ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                detach()
                if (!continuation.isActive) return@register
                if (result.resultCode == Activity.RESULT_OK) continuation.resume(SystemAuthProof(request, null))
                else continuation.resumeWithException(BiometricAuthenticationException("已取消系统身份验证，账号仍保持锁定。", cancelled = true))
            }
            activeCredentialLauncher = launcher
            activeAuthentication = continuation
            continuation.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) detach() else mainHandler.post { detach() }
            }
            try { if (continuation.isActive) launcher.launch(intent) }
            catch (_: RuntimeException) {
                detach()
                if (continuation.isActive) continuation.resumeWithException(
                    BiometricAuthenticationException("无法启动系统身份验证，请稍后重试。"),
                )
            }
        }

    /** Returns only the Cipher authorized by a successful strong biometric prompt. */
    suspend fun authenticate(cipher: Cipher, title: String): Cipher = runAuthentication {
        authenticateCipher(cipher, title, allowCredential = false)
    }

    private suspend fun authenticateCipher(cipher: Cipher, title: String, allowCredential: Boolean): Cipher = withContext(Dispatchers.Main.immediate) {
        val host = activity
        if (closed || host == null || host.isFinishing || host.isDestroyed) {
            throw BiometricAuthenticationException("页面已关闭，请重新打开后解锁。")
        }
        if (!(if (allowCredential) canUseSystemAuthentication() else canUseBiometrics())) {
            throw BiometricAuthenticationException("强生物识别不可用，请使用恢复口令。")
        }
        if (activePrompt != null) {
            throw BiometricAuthenticationException("已有认证正在进行，请先完成或取消。")
        }
        suspendCancellableCoroutine { continuation ->
            lateinit var prompt: BiometricPrompt

            fun detach() {
                if (activePrompt === prompt) {
                    activePrompt = null
                    activeAuthentication = null
                }
            }

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    detach()
                    if (!continuation.isActive) return
                    val authorizedCipher = result.cryptoObject?.cipher
                    if (authorizedCipher == null || authorizedCipher !== cipher) {
                        continuation.resumeWithException(
                            BiometricAuthenticationException(if (allowCredential) {
                                "系统验证未授权本次密钥操作，请重试。"
                            } else "认证未授权密钥操作，请使用恢复口令。"),
                        )
                    } else {
                        continuation.resume(authorizedCipher)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    detach()
                    if (!continuation.isActive) return
                    val cancelled = errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    continuation.resumeWithException(
                        BiometricAuthenticationException(
                            message = if (allowCredential && cancelled) {
                                "已取消系统身份验证，账号仍保持锁定。"
                            } else if (allowCredential) {
                                "系统身份验证暂不可用，请重试或检查手机锁屏设置。"
                            } else if (cancelled) {
                                "已取消生物识别，可使用恢复口令。"
                            } else {
                                "生物识别暂不可用，请使用恢复口令。"
                            },
                            cancelled = cancelled,
                            recoveryPasswordRequested = !allowCredential && errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        ),
                    )
                }
            }
            prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), callback)
            activePrompt = prompt
            activeAuthentication = continuation
            continuation.invokeOnCancellation {
                val cancel = Runnable {
                    // An old cancellation must never cancel a newer prompt on the same Activity.
                    if (activePrompt === prompt) {
                        detach()
                        prompt.cancelAuthentication()
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    cancel.run()
                } else {
                    mainHandler.post(cancel)
                }
            }
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(if (allowCredential) "使用手机锁屏凭据或受支持的强生物识别" else "使用本机强生物识别授权密钥操作")
                .setAllowedAuthenticators(if (allowCredential) {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                } else BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (!allowCredential) builder.setNegativeButtonText("使用恢复口令")
            val info = builder.build()
            try {
                if (continuation.isActive) {
                    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
                }
            } catch (_: RuntimeException) {
                detach()
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        BiometricAuthenticationException("无法启动生物识别，请使用恢复口令。"),
                    )
                }
            }
        }
    }

    /** 0 disables the timer, 1 clears on the next main-loop turn, 2..300 are seconds. */
    @MainThread
    fun copyCode(value: String, clearAfterSeconds: Int) {
        requireMainThread()
        check(!closed) { "设备服务已关闭" }
        require(clearAfterSeconds in 0..300) { "不支持的剪贴板清理间隔" }
        require((value.length == 6 || value.length == 8) && value.all { it in '0'..'9' }) {
            "只能复制有效验证码"
        }
        val token = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("InkOTP 验证码", value).apply {
            description.extras = PersistableBundle().apply {
                // The platform recognizes this key on API 33+; metadata is harmless on older devices.
                putBoolean(SENSITIVE_EXTRA, true)
                putString(OWNER_EXTRA, token)
            }
        }
        clipboard.setPrimaryClip(clip)
        cancelClearTimer()
        ownedClip = null
        val description = findOwnedClip(token) ?: return
        val owner = OwnedClip(token, description.timestamp)
        ownedClip = owner
        if (clearAfterSeconds > 0) {
            val task = Runnable {
                if (ownedClip === owner) clearClipboard()
            }
            clearTask = task
            val delayMillis = if (clearAfterSeconds == 1) 0L else clearAfterSeconds * 1_000L
            mainHandler.postDelayed(task, delayMillis)
        }
    }

    /** Requires the same token and Android clipboard timestamp before requesting a clear. */
    @MainThread
    fun clearClipboard() {
        requireMainThread()
        cancelClearTimer()
        val owner = ownedClip ?: return
        ownedClip = null
        // Android can deny clipboard access while backgrounded. Clearing is best effort.
        try {
            if (findOwnedClip(owner.token)?.timestamp != owner.timestamp) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: SecurityException) {
            // Do not retry later: the user might have copied unrelated content in the meantime.
        }
    }

    private fun findOwnedClip(token: String): ClipDescription? = try {
        clipboard.primaryClipDescription?.takeIf { it.extras?.getString(OWNER_EXTRA) == token }
    } catch (_: SecurityException) {
        null
    }

    @MainThread
    fun secureScreen(enabled: Boolean) {
        requireMainThread()
        val window = activity?.window ?: return
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @MainThread
    fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        clearClipboard()
        val prompt = activePrompt
        activeAuthentication?.cancel()
        activeAuthentication = null
        activePrompt = null
        isAuthenticationInProgress = false
        prompt?.cancelAuthentication()
        activeCredentialLauncher?.unregister()
        activeCredentialLauncher = null
        mainHandler.removeCallbacksAndMessages(null)
        activity = null
    }

    private fun cancelClearTimer() {
        clearTask?.let(mainHandler::removeCallbacks)
        clearTask = null
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "设备服务必须在主线程调用" }
    }

    private class OwnedClip(val token: String, val timestamp: Long)

    private companion object {
        const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
        const val OWNER_EXTRA = "io.github.rhczz.inkotp.CLIP_OWNER"
    }
}

class BiometricAuthenticationException(
    message: String,
    val cancelled: Boolean = false,
    val recoveryPasswordRequested: Boolean = false,
) : IllegalStateException(message)

/** Kept only in memory; neither requests nor proofs belong in SavedState or backup files. */
class SystemAuthRequest internal constructor(
    val cipher: Cipher?,
    internal val issuedAtMillis: Long = SystemClock.elapsedRealtime(),
)

class SystemAuthProof internal constructor(
    internal val request: SystemAuthRequest,
    internal val authenticatedCipher: Cipher?,
    internal val authenticatedAtMillis: Long = SystemClock.elapsedRealtime(),
) {
    private var consumed = false
    internal fun consume(expected: SystemAuthRequest): Boolean = synchronized(this) {
        val now = SystemClock.elapsedRealtime()
        if (consumed || request !== expected || authenticatedCipher !== expected.cipher ||
            now - authenticatedAtMillis !in 0..15_000L || now - expected.issuedAtMillis !in 0..120_000L) return@synchronized false
        consumed = true
        true
    }
}
