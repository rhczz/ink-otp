package io.github.rhczz.inkotp

import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.app.VaultViewModel
import io.github.rhczz.inkotp.core.crypto.Kdf
import io.github.rhczz.inkotp.core.crypto.KdfParams
import io.github.rhczz.inkotp.data.DeviceEnvelope
import io.github.rhczz.inkotp.data.DeviceKeys
import io.github.rhczz.inkotp.data.EnvelopeStore
import io.github.rhczz.inkotp.data.VaultFiles
import io.github.rhczz.inkotp.data.VaultRepository
import io.github.rhczz.inkotp.platform.DeviceSecurity
import io.github.rhczz.inkotp.ui.navigation.InkApp
import io.github.rhczz.inkotp.ui.theme.InkTheme
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Exercises the actual app wiring; only storage, KDF cost and hardware keys are substituted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Robolectric #8460 oscillates Dialog/TextField constraints above its 320dp
// default width. Keep flow assertions here; phone-size visual checks have
// their own screenshot tests. https://github.com/robolectric/robolectric/issues/8460
@Config(sdk = [36], qualifiers = "w320dp-h891dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class InkAppFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val store = ViewModelStore()
    private val files = MemoryFiles()
    private val repository = VaultRepository(files, MemoryEnvelopes(), NoHardwareKeys,
        kdf = FlowTestKdf, kdfParams = KdfParams(8192, 1, 1))
    private var security: DeviceSecurity? = null
    private var securityHost: ActivityController<FragmentActivity>? = null

    @Test fun passwordDialogCanRenderAndAcceptARecoveryPhrase() {
        var supplied: String? = null
        compose.setContent {
            InkTheme(darkTheme = false) {
                io.github.rhczz.inkotp.ui.components.VaultPasswordDialog(
                    title = "公开测试口令", message = "测试恢复口令输入", onConfirm = { _, value -> supplied = value }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("公开测试口令").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput(PASSWORD)
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(PASSWORD, supplied) }
    }

    @After
    fun clearTheOnlyViewModelAndDeviceServices() {
        store.clear()
        pumpUntil("session cleanup") { repository.model == null }
        security?.close()
        securityHost?.pause()?.stop()?.destroy()
    }

    @Test
    fun firstSetupManualAddAndPasswordUnlockPreserveTheRealEncryptedAccount() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("vault_metadata", 0).edit().clear().commit()
        Settings.Global.putInt(application.contentResolver, Settings.Global.AUTO_TIME, 1)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VaultViewModel(application, repository) as T
        }
        val vm = ViewModelProvider(store, factory)[VaultViewModel::class.java]
        pumpUntil("initial vault lookup") { vm.state.ready && !vm.state.busy }
        // A separate plain FragmentActivity supplies clipboard/window services only.
        // The Compose rule hosts ComponentActivity and never creates MainActivity's VM.
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        securityHost = controller
        val device = DeviceSecurity(controller.get()).also { security = it }
        compose.setContent {
            InkTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) { InkApp(vm, device, foreground = true) }
            }
        }

        compose.onNodeWithText("继续").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput(PASSWORD)
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput(PASSWORD)
        compose.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        compose.onNode(hasText("设置恢复口令") and hasClickAction()).performClick()
        pumpUntil("password vault creation") { vm.state.unlocked && vm.state.passwordSet && !vm.state.busy }
        compose.waitForIdle()
        compose.onNodeWithText("只使用口令").performClick()
        compose.onNodeWithText("手动添加").performClick()

        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("RFC 6238")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput(ACCOUNT)
        compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput(PUBLIC_TEST_SEED)
        compose.onNodeWithText("保存").performScrollTo().performClick()
        pumpUntil("persisted account in list") { !vm.state.busy && vm.state.items.singleOrNull()?.accountName == ACCOUNT }
        compose.waitForIdle()

        compose.onNodeWithText(ACCOUNT).assertIsDisplayed()
        assertTrue(vm.state.items.single().code.matches(Regex("[0-9]{6}")))
        assertFalse(vm.state.biometricEnabled)
        val committed = files.read()
        val previousSessionSecret = repository.model!!.items.single().secret
        compose.onNodeWithContentDescription("立即锁定").performClick()
        pumpUntil("locked repository") { !vm.state.unlocked && repository.model == null }
        compose.waitForIdle()
        compose.onNodeWithText(ACCOUNT).assertDoesNotExist()
        compose.onNodeWithText("解锁以查看验证码").assertIsDisplayed()
        assertTrue(previousSessionSecret.all { it == 0.toByte() })
        assertNull(repository.model)

        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(PASSWORD)
        compose.onNodeWithText("解锁").performClick()
        pumpUntil("password decrypt and list restoration") { vm.state.unlocked && !vm.state.busy && vm.state.items.size == 1 }
        compose.waitForIdle()

        compose.onNodeWithText(ACCOUNT).assertIsDisplayed()
        assertEquals("RFC 6238", vm.state.items.single().issuer)
        assertArrayEquals("12345678901234567890".toByteArray(), repository.model!!.items.single().secret)
        assertArrayEquals(committed, files.read())

        // Follow the real navigation and password gate up to the plaintext
        // confirmation. Cancelling must discard the staged secret document.
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("导出备份").performScrollTo().performClick()
        compose.onNodeWithText("otpauth 迁移文本").performScrollTo().performClick()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("导出迁移文件").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput(PASSWORD)
        compose.onNodeWithText("确认").performClick()
        pumpUntil("plaintext export waiting for informed confirmation") {
            !vm.state.busy && vm.describeExport(vm.sessionToken) != null
        }
        compose.waitForIdle()
        compose.onNodeWithText("导出含明文密钥的迁移文件？").assertIsDisplayed()
        assertNotNull(vm.describeExport(vm.sessionToken))
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull(vm.describeExport(vm.sessionToken)) }
        assertArrayEquals(committed, files.read())
    }

    private fun pumpUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for $description")
    }

    private class MemoryFiles : VaultFiles {
        @Volatile private var contents: ByteArray? = null
        override fun exists() = contents != null
        override fun read() = requireNotNull(contents).copyOf()
        override fun write(bytes: ByteArray) { contents = bytes.copyOf() }
    }

    private class MemoryEnvelopes : EnvelopeStore {
        private var value: DeviceEnvelope? = null
        override suspend fun read() = value
        override suspend fun write(value: DeviceEnvelope?) { this.value = value }
    }

    /** Fast deterministic derivation for this UI test; production Argon2 is tested separately. */
    private object FlowTestKdf : Kdf {
        override fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(password.concatToString().toByteArray() + salt)
    }

    private object NoHardwareKeys : DeviceKeys {
        override fun encryptCipher(mode: String): Cipher = error("Password-only flow requested a hardware key")
        override fun decryptCipher(mode: String, iv: ByteArray): Cipher = error("Password-only flow requested a hardware key")
        override fun delete(mode: String) = Unit
    }

    private companion object {
        const val PASSWORD = "public integration passphrase"
        const val ACCOUNT = "rfc6238@example.test"
        // RFC 6238's public SHA1 secret, never a real account credential.
        const val PUBLIC_TEST_SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    }
}
