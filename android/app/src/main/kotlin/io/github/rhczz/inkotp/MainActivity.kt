package io.github.rhczz.inkotp

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vault = ViewModelProvider(this)[VaultViewModel::class.java]
        security = DeviceSecurity(this)
        security.secureScreen(!BuildConfig.DEBUG)
        setContent { InkTheme { InkApp(vault, security, foreground) } }
    }

    override fun onResume() {
        super.onResume()
        if (::vault.isInitialized) vault.onForeground()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        super.onPause()
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
