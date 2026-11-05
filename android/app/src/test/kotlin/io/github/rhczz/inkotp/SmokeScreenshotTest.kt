package io.github.rhczz.inkotp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.rhczz.inkotp.ui.PlaceholderScreen
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M0 smoke test: proves Compose renders on the JVM with Roborazzi.
 * Real design-baseline screenshots arrive in M2.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SmokeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun placeholderLightRenders() {
        compose.setContent {
            InkTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PlaceholderScreen()
                }
            }
        }
        compose.onRoot().captureRoboImage("build/roborazzi/smoke-light.png")
    }

    @Test
    fun placeholderDarkRenders() {
        compose.setContent {
            InkTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PlaceholderScreen()
                }
            }
        }
        compose.onRoot().captureRoboImage("build/roborazzi/smoke-dark.png")
    }
}
