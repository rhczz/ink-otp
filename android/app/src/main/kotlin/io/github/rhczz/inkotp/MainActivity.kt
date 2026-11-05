package io.github.rhczz.inkotp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.rhczz.inkotp.ui.navigation.InkApp
import io.github.rhczz.inkotp.ui.theme.InkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InkTheme {
                InkApp()
            }
        }
    }
}
