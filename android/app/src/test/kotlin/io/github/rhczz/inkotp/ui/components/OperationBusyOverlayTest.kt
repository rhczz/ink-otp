package io.github.rhczz.inkotp.ui.components

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import io.github.rhczz.inkotp.ui.theme.InkTheme
import io.github.rhczz.inkotp.ui.theme.LocalInkColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h891dp-xxhdpi")
class OperationBusyOverlayTest {
    @get:Rule val compose = createComposeRule()
    private val busy = mutableStateOf(false)
    private val authentication = mutableStateOf(false)
    private val operation = mutableStateOf(0L)
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private lateinit var dispatcher: OnBackPressedDispatcher
    private var clicks = 0
    private var backs = 0

    @Test fun shortOperationPreservesLightPage() = shortOperation(dark = false)
    @Test fun shortOperationPreservesDarkPage() = shortOperation(dark = true)

    private fun shortOperation(dark: Boolean) {
        show(dark)
        val background = cornerPixel()
        start()
        advance(250)
        compose.onAllNodes(progress).assertCountEquals(0)
        assertEquals(background, cornerPixel())

        update { busy.value = false }
        advance(1000)
        compose.onAllNodes(progress).assertCountEquals(0)
        assertEquals(background, cornerPixel())
    }

    @Test fun longOperationUsesLocalProgressAndEveryNewOperationRestartsTheDelay() {
        show()
        val background = cornerPixel()
        start()
        advance(320)
        compose.onAllNodes(progress).assertCountEquals(1)
        assertEquals(background, cornerPixel())

        // Even consecutive operations whose busy=true frames coalesce get a fresh delay.
        update { operation.value++ }
        advance(250)
        compose.onAllNodes(progress).assertCountEquals(0)
        advance(80)
        compose.onAllNodes(progress).assertCountEquals(1)

        update { busy.value = false }
        advance(500)
        compose.onAllNodes(progress).assertCountEquals(0)
        assertEquals(background, cornerPixel())
    }

    @Test fun systemAuthenticationSuppressesAppProgressAndRestartsDelayOnReturn() {
        show()
        start()
        advance(320)
        compose.onAllNodes(progress).assertCountEquals(1)
        update { authentication.value = true }
        advance(500)
        compose.onAllNodes(progress).assertCountEquals(0)

        update { authentication.value = false }
        advance(250)
        compose.onAllNodes(progress).assertCountEquals(0)
        advance(80)
        compose.onAllNodes(progress).assertCountEquals(1)
        update { busy.value = false }
        advance(500)
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun touchesAndBackAreBlockedImmediatelyAndReleasedWhenTheOperationEnds() {
        show()
        compose.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        compose.runOnIdle { dispatcher.onBackPressed() }
        assertEquals(1, clicks)
        assertEquals(1, backs)

        start()
        compose.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onAllNodes(progress).assertCountEquals(0)
        assertEquals(1, clicks)
        assertEquals(1, backs)

        update { busy.value = false }
        advance(16)
        compose.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        compose.runOnIdle { dispatcher.onBackPressed() }
        assertEquals(2, clicks)
        assertEquals(2, backs)
    }

    private fun show(dark: Boolean = false) {
        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            InkTheme(darkTheme = dark) {
                BackHandler { backs++ }
                Box(Modifier.fillMaxSize().background(LocalInkColors.current.bg)) {
                    Text("保持原页面", Modifier.fillMaxSize().clickable { clicks++ })
                    OperationBusyOverlay(busy.value, operation.value, authentication.value)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun start() {
        update { operation.value++; busy.value = true }
    }

    private fun update(change: () -> Unit) {
        // Fully commit the new hit-test tree and launch its effect before measuring the delay.
        compose.mainClock.autoAdvance = true
        compose.runOnIdle(change)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        // Effects and state writes at the deadline need a subsequent composition/layout frame.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun cornerPixel() = compose.onRoot().captureToImage().toPixelMap()[1, 1]
}
