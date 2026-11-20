package io.github.rhczz.inkotp.ui

import android.animation.ValueAnimator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.rhczz.inkotp.ui.demo.DemoData
import io.github.rhczz.inkotp.ui.model.VaultListUiState
import io.github.rhczz.inkotp.ui.screens.VaultListScreen
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.InkTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1180dp-xxhdpi")
class VaultListEntryMotionTest {
    @get:Rule val compose = createComposeRule()
    private val issuers = DemoData.items.take(3).map { checkNotNull(it.issuer) }
    private val extraIssuer = "Seventh account"
    private val items = DemoData.items + DemoData.items.first().copy(
        id = "demo-seventh", issuer = extraIssuer,
    )
    private val state = mutableStateOf(VaultListUiState(items = items, backupReminder = false))
    private var entries = 0

    @After fun restoreAnimatorScale() {
        animationScale(1f)
    }

    @Test fun cardsDropFarEnoughAndSettleInOrderWithoutRebound() {
        show()
        val start = positions()
        val unaffected = top(extraIssuer)
        advance(32)
        val firstMoves = positions()
        assertTrue(firstMoves[0] > start[0] + 1f)
        assertEquals(start[1], firstMoves[1], 0.5f)
        assertEquals(start[2], firstMoves[2], 0.5f)

        advance(64)
        val secondMoves = positions()
        assertTrue(secondMoves[1] > start[1] + 1f)
        assertEquals(start[2], secondMoves[2], 0.5f)

        val frames = mutableListOf(start, firstMoves, secondMoves)
        repeat(34) {
            advance(16)
            frames += positions()
        }
        val settled = frames.last()
        for (index in issuers.indices) {
            assertTrue(settled[index] - start[index] >= 40f)
            assertTrue(frames.zipWithNext().all { (first, next) -> next[index] >= first[index] - 0.5f })
            assertTrue(frames.all { frame -> frame[index] <= settled[index] + 0.5f })
        }
        assertEquals(unaffected, top(extraIssuer), 0.5f)
        assertEquals(1, entries)
    }

    @Test fun disabledSystemMotionDisplaysEveryCardAtItsFinalPosition() {
        show(scale = 0f)
        assertTrue(!ValueAnimator.areAnimatorsEnabled())
        val firstFrame = positions()
        advance(640)
        assertEquals(firstFrame, positions())
        assertEquals(1, entries)
    }

    @Test fun pendingCardHasNoGhostAndTimerAndSearchDoNotReplayEntry() {
        show()
        val initial = compose.onRoot().captureToImage().toPixelMap()
        advance(96)
        updateQuery(issuers[0], secondCardVisible = false)
        updateQuery("", secondCardVisible = true)
        val final = compose.onRoot().captureToImage().toPixelMap()
        val settled = positions()
        val x = with(compose.density) { 27.dp.roundToPx() }
        val y = with(compose.density) { (settled[1] + 40).dp.roundToPx() }
        assertEquals(AppTokens.Light.bg, initial[x, y])
        assertEquals(AppTokens.Light.surface, final[x, y])

        compose.runOnIdle { state.value = state.value.copy(items = state.value.items.map {
            it.copy(remainingSeconds = it.remainingSeconds - 1)
        }) }
        advance(16)
        assertEquals(settled, positions())

        advance(640)
        assertEquals(settled, positions())
        assertEquals(1, entries)
    }

    private fun show(scale: Float = 1f) {
        animationScale(scale)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            InkTheme(darkTheme = false) {
                VaultListScreen(state.value, {}, {}, {}, {}, {},
                    animateEntry = true, onEntry = { entries++ })
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun updateQuery(query: String, secondCardVisible: Boolean) {
        compose.runOnIdle { state.value = state.value.copy(query = query) }
        repeat(8) {
            advance(16)
            val present = compose.onAllNodesWithText(issuers[1], useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            if (present == secondCardVisible) return
        }
        throw AssertionError("Search result did not update in eight frames")
    }

    private fun positions() = issuers.map(::top)

    private fun top(issuer: String) =
        compose.onNodeWithText(issuer, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun animationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Unit>(
            ValueAnimator::class.java, "setDurationScale", ClassParameter.from(Float::class.javaPrimitiveType, scale),
        )
    }
}
