package io.github.rhczz.inkotp.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceSecurityTest {
    private lateinit var activity: FragmentActivity
    private lateinit var clipboard: ClipboardManager
    private lateinit var security: DeviceSecurity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        security = DeviceSecurity(activity)
    }

    @After
    fun tearDown() {
        security.close()
    }

    @Test
    fun copiedCodeIsMarkedSensitiveAndClearedAfterDeadline() {
        security.copyCode("123456", clearAfterSeconds = 30)

        assertEquals("123456", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        assertTrue(clipboard.hasPrimaryClip())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun timerDoesNotEraseContentCopiedFromAnotherApp() {
        security.copyCode("123456", clearAfterSeconds = 30)
        clipboard.setPrimaryClip(ClipData.newPlainText("other app", "a useful note"))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))

        assertEquals("a useful note", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun manualClearDoesNotEraseOtherContentEvenWhenItMatchesTheCode() {
        security.copyCode("123456", clearAfterSeconds = 30)
        clipboard.setPrimaryClip(ClipData.newPlainText("other app", "123456"))

        security.clearClipboard()

        assertEquals("123456", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun ownershipExpiresWhenAClipboardWriterReusesOurMetadata() {
        security.copyCode("123456", clearAfterSeconds = 15)
        val copiedMetadata = PersistableBundle(clipboard.primaryClipDescription!!.extras!!)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        clipboard.setPrimaryClip(ClipData.newPlainText("InkOTP 验证码", "a useful note").apply {
            description.extras = copiedMetadata
        })

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(14))

        assertEquals("a useful note", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun fifteenAndSixtySecondPresetsUseTheirOwnDeadlines() {
        listOf(15L, 60L).forEach { seconds ->
            security.copyCode("12345678", clearAfterSeconds = seconds.toInt())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds - 1))
            assertTrue(clipboard.hasPrimaryClip())

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(clipboard.hasPrimaryClip())
        }
    }

    @Test
    fun copyingAgainRestartsTheDeadlineForTheNewCode() {
        security.copyCode("123456", clearAfterSeconds = 30)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        security.copyCode("654321", clearAfterSeconds = 30)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals("654321", clipboard.primaryClip!!.getItemAt(0).text.toString())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun disabledTimerKeepsCodeUntilTheAppLocks() {
        security.copyCode("123456", clearAfterSeconds = 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue(clipboard.hasPrimaryClip())

        security.clearClipboard()

        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun immediatePolicyClearsOnTheNextMainLoopTurn() {
        security.copyCode("123456", clearAfterSeconds = 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun closingClearsOurClipboardAndCannotLaterEraseOtherContent() {
        security.copyCode("123456", clearAfterSeconds = 30)
        security.close()
        assertFalse(clipboard.hasPrimaryClip())
        clipboard.setPrimaryClip(ClipData.newPlainText("other app", "new note"))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))

        assertEquals("new note", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun secureScreenFlagCanBeSetAndRemovedByTheHostPolicy() {
        security.secureScreen(true)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

        security.secureScreen(false)

        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun invalidCopyRequestDoesNotOverwriteExistingClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("other app", "keep this note"))

        assertThrows(IllegalArgumentException::class.java) {
            security.copyCode("secret", clearAfterSeconds = 30)
        }

        assertEquals("keep this note", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
}
