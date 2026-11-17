package io.github.rhczz.inkotp

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Actual app/repository saves, with only the file boundary deliberately held open. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h891dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class OperationFeedbackFlowTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val files = MemoryFiles()
    private val blocks = mutableListOf<PendingWrite>()
    private val repository = VaultRepository(files, MemoryEnvelopes(), NoHardwareKeys,
        kdf = FastKdf, kdfParams = KdfParams(8192, 1, 1))
    private var security: DeviceSecurity? = null
    private var host: ActivityController<FragmentActivity>? = null
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @After fun cleanup() {
        blocks.forEach { it.release.countDown() }
        store.clear()
        pumpUntil("repository cleanup") { repository.model == null }
        security?.close()
        host?.pause()?.stop()?.destroy()
    }

    @Test fun settingSaveRetainsBrightnessBlocksRepeatedTouchesAndWaitsBeforeShowingProgress() {
        val vm = showSettings()
        val background = cornerPixel()
        val write = blockNextWrite()
        compose.onNodeWithText("自动锁定").performClick()
        pumpUntil("setting save started") { write.started.count == 0L }
        compose.waitForIdle()

        assertTrue(vm.state.busy)
        compose.onNodeWithText("设置").assertIsDisplayed()
        assertEquals("A pending save must not dim the page", background, cornerPixel())
        compose.onAllNodes(progress).assertCountEquals(0)
        compose.onNodeWithText("自动锁定").performTouchInput { click() }
        compose.onNodeWithContentDescription("返回").performTouchInput { click() }
        assertEquals(0, vm.state.settings.lockTimeoutSeconds)
        compose.onNodeWithText("设置").assertIsDisplayed()

        write.release.countDown()
        pumpUntil("setting save finished") { !vm.state.busy }
        compose.waitForIdle()
        assertEquals(30, vm.state.settings.lockTimeoutSeconds)
        compose.onAllNodes(progress).assertCountEquals(0)
        assertEquals(background, cornerPixel())
    }

    @Test fun failedSaveLeavesTheOriginalSettingAndNoProgressOverlay() {
        val vm = showSettings()
        val write = blockNextWrite(fail = true)
        compose.onNodeWithText("自动锁定").performClick()
        pumpUntil("failing save started") { write.started.count == 0L }
        write.release.countDown()
        pumpUntil("failed save feedback") { !vm.state.busy && vm.state.error != null }
        compose.waitForIdle()

        assertEquals(0, vm.state.settings.lockTimeoutSeconds)
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("自动锁定").performClick()
        pumpUntil("retry saved") { !vm.state.busy && vm.state.settings.lockTimeoutSeconds == 30 }
    }

    @Test fun lockDuringSaveImmediatelyRemovesPendingProgressAndSettingsContent() {
        val vm = showSettings()
        val write = blockNextWrite()
        compose.onNodeWithText("自动锁定").performClick()
        pumpUntil("blocked save") { write.started.count == 0L }
        compose.runOnIdle { vm.lock() }
        compose.waitForIdle()

        assertFalse(vm.state.unlocked)
        compose.onNodeWithText("自动锁定").assertDoesNotExist()
        compose.onNodeWithText("解锁以查看验证码").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
        write.release.countDown()
        pumpUntil("locked cleanup") { repository.model == null }
        assertFalse(vm.state.unlocked)
    }

    private fun showSettings(): VaultViewModel {
        runBlocking { repository.initialize("public feedback test phrase".toCharArray()) }
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("vault_metadata", 0).edit().clear().commit()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VaultViewModel(application, repository) as T
        }
        val vm = ViewModelProvider(store, factory)[VaultViewModel::class.java]
        pumpUntil("initialized session") { vm.state.ready && vm.state.unlocked && !vm.state.busy }
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().also { host = it }
        val device = DeviceSecurity(controller.get()).also { security = it }
        compose.setContent {
            InkTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) { InkApp(vm, device, foreground = true) }
            }
        }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitForIdle()
        return vm
    }

    private fun cornerPixel() = compose.onRoot().captureToImage().toPixelMap()[1, 1]
    private fun blockNextWrite(fail: Boolean = false) = PendingWrite(fail).also { blocks += it; files.next.set(it) }

    private fun pumpUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for $description")
    }

    private class PendingWrite(val fail: Boolean) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
    private class MemoryFiles : VaultFiles {
        private var contents: ByteArray? = null
        val next = AtomicReference<PendingWrite?>()
        override fun exists() = contents != null
        override fun read() = requireNotNull(contents).copyOf()
        override fun write(bytes: ByteArray) {
            next.getAndSet(null)?.let {
                it.started.countDown()
                check(it.release.await(10, TimeUnit.SECONDS)) { "Test did not release save" }
                if (it.fail) throw IOException("Public test write failure")
            }
            contents = bytes.copyOf()
        }
    }
    private class MemoryEnvelopes : EnvelopeStore {
        private var value: DeviceEnvelope? = null
        override suspend fun read() = value
        override suspend fun write(value: DeviceEnvelope?) { this.value = value }
    }
    private object FastKdf : Kdf {
        override fun deriveKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(password.concatToString().toByteArray() + salt)
    }
    private object NoHardwareKeys : DeviceKeys {
        override fun encryptCipher(mode: String): Cipher = error("Password-only test")
        override fun decryptCipher(mode: String, iv: ByteArray): Cipher = error("Password-only test")
        override fun delete(mode: String) = Unit
    }
}
