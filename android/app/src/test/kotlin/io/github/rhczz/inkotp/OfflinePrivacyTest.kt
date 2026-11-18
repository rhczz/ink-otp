package io.github.rhczz.inkotp

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.app.VaultViewModel
import io.github.rhczz.inkotp.platform.PrivacyChoiceStore
import io.github.rhczz.inkotp.platform.PrivacyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class OfflinePrivacyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun prepare() {
        shadowOf(app).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        assertTrue(PrivacyChoiceStore(app).save(null))
    }

    @Test fun bundledPolicyRetainsAllCanonicalContentAndBlocksSubresources() {
        val raw = app.assets.open("privacy/index.html").bufferedReader().use { it.readText() }
        val sourceMain = raw.substringAfter("<main").substringBefore("</main>") + "</main>"
        val policy = PrivacyPolicy.load(app)
        assertEquals(sourceMain, policy.document.substringAfter("<main").substringBefore("</main>") + "</main>")
        assertEquals(64, policy.revision.length)
        assertFalse(policy.document.contains("<script"))
        assertFalse(policy.document.contains("<link "))
    }

    @Test fun aFailedUpdateKeepsThePreviousChoice() {
        val base = app.noBackupFilesDir.resolve("test-privacy-choice")
        val choice = PrivacyChoiceStore(base)
        assertTrue(choice.save("previous"))
        val blocker = app.noBackupFilesDir.resolve("test-privacy-choice.new")
        assertTrue(blocker.mkdir())
        blocker.resolve("occupied").writeText("test")
        assertFalse(choice.save(null))
        assertEquals("previous", PrivacyChoiceStore(base).acceptedRevision())
    }

    @Test fun anOlderAcceptanceDoesNotOpenTheVault() {
        assertTrue(PrivacyChoiceStore(app).save("older-policy"))
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            compose.onNodeWithText("查看完整隐私声明").assertIsDisplayed()
            assertNull(ReflectionHelpers.getField<VaultViewModel?>(controller.get(), "vault"))
        }
    }

    @Test fun refusalAndPolicyViewingDoNotStartVaultAndAcceptancePersists() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            compose.onNodeWithText("查看完整隐私声明").performScrollTo().performClick()
            compose.onNodeWithText("隐私声明").assertIsDisplayed()
            assertNull(ReflectionHelpers.getField<VaultViewModel?>(activity, "vault"))
            activity.onBackPressedDispatcher.onBackPressed()
            compose.onNodeWithText("不同意").performScrollTo().performClick()
            compose.onNodeWithText("重新选择").performScrollTo().assertIsDisplayed()
            assertNull(ReflectionHelpers.getField<VaultViewModel?>(activity, "vault"))
            compose.onNodeWithText("重新选择").performClick()
            compose.onNodeWithText("同意并继续").performScrollTo().performClick()
            compose.waitUntil { ReflectionHelpers.getField<VaultViewModel?>(activity, "vault") != null }
            assertEquals(PrivacyPolicy.load(app).revision, PrivacyChoiceStore(app).acceptedRevision())
        }
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertNotNull(ReflectionHelpers.getField<VaultViewModel?>(controller.get(), "vault"))
        }
    }
}
