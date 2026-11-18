package io.github.rhczz.inkotp

import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import io.github.rhczz.inkotp.app.VaultViewModel
import io.github.rhczz.inkotp.platform.DeviceSecurity
import io.github.rhczz.inkotp.ui.navigation.InkApp
import io.github.rhczz.inkotp.ui.theme.InkTheme

class MainActivity : FragmentActivity() {
    private lateinit var vault: VaultViewModel
    private lateinit var security: DeviceSecurity
    private var foreground by mutableStateOf(false)
    private var touchInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vault = ViewModelProvider(this)[VaultViewModel::class.java]
        security = DeviceSecurity(this)
        security.secureScreen(!BuildConfig.DEBUG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        setContent { InkTheme { InkApp(vault, security, foreground) } }
    }

    override fun onResume() {
        super.onResume()
        if (::vault.isInitialized) vault.onForeground()
        foreground = true
        window.decorView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    override fun onPause() {
        foreground = false
        touchInProgress = false
        window.decorView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
        if (obscured || !foreground) {
            if (touchInProgress && action != MotionEvent.ACTION_DOWN) {
                val cancel = MotionEvent.obtain(event)
                try {
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                } finally {
                    cancel.recycle()
                }
            }
            touchInProgress = false
            return true
        }
        if (action == MotionEvent.ACTION_DOWN) touchInProgress = true
        if (!touchInProgress) return true
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchInProgress = false
        return super.dispatchTouchEvent(event)
    }

    override fun onStop() {
        if (!isChangingConfigurations && ::vault.isInitialized) {
            if (::security.isInitialized && security.isAuthenticationInProgress) vault.onSystemAuthenticationBackground()
            else vault.onBackground()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::security.isInitialized) security.close()
        super.onDestroy()
    }
}
