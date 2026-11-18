package io.github.rhczz.inkotp

import android.Manifest
import android.app.Application
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenPrivacyTest {
    @Before
    fun grantManifestPermissionInRobolectric() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
    }

    @Test
    fun obscuredGesturesAreBlockedAndActiveGestureIsCancelled() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val delivered = mutableListOf<Int>()
            activity.setContentView(View(activity).apply {
                setOnTouchListener { _, event ->
                    delivered += event.actionMasked
                    true
                }
            })
            controller.visible()

            activity.touch(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
            activity.touch(MotionEvent.ACTION_UP)
            activity.touch(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
            activity.touch(MotionEvent.ACTION_UP)
            assertEquals(emptyList<Int>(), delivered)

            activity.touch(MotionEvent.ACTION_DOWN)
            activity.touch(MotionEvent.ACTION_MOVE, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
            activity.touch(MotionEvent.ACTION_UP)
            activity.touch(MotionEvent.ACTION_DOWN)
            activity.touch(MotionEvent.ACTION_UP)
            assertEquals(
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
                delivered,
            )
        }
    }

    @Test
    fun accessibilityContentIsHiddenWhilePausedAndExposedAgainOnResume() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val decor = controller.get().window.decorView
            controller.pause()
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, decor.importantForAccessibility)
            controller.resume()
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, decor.importantForAccessibility)
        }
    }

    private fun MainActivity.touch(action: Int, flags: Int = 0) {
        val event = MotionEvent.obtain(
            0L, 0L, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(MotionEvent.PointerCoords().apply { x = 24f; y = 180f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
        )
        try {
            dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
