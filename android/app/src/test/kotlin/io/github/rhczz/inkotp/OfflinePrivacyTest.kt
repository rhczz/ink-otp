package io.github.rhczz.inkotp

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.text.HtmlCompat
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.app.VaultViewModel
import io.github.rhczz.inkotp.platform.PrivacyBlockKind
import io.github.rhczz.inkotp.platform.PrivacyChoiceStore
import io.github.rhczz.inkotp.platform.PrivacyPolicy
import java.security.MessageDigest
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

    @Test fun bundledPolicyMatchesEachWebsiteBlockAndKeepsItsApprovedRevision() {
        val raw = app.assets.open("privacy/index.html").bufferedReader().use { it.readText() }
        val sourceMain = "<main" + raw.substringAfter("<main").substringBefore("</main>") + "</main>"
        val revision = MessageDigest.getInstance("SHA-256").digest(sourceMain.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val policy = PrivacyPolicy.load(app)
        assertEquals(revision, policy.revision)

        val blockPattern = Regex("<(h[12]|p|dt|dd|li)(?:\\s[^>]*)?>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        val expectedBlocks = blockPattern.findAll(sourceMain).map { block ->
            HtmlCompat.fromHtml(block.groupValues[2], HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        }.toList()
        assertEquals(expectedBlocks, policy.blocks.map { it.text })
        assertEquals(PrivacyBlockKind.TITLE, policy.blocks.first().kind)
        assertTrue(policy.blocks.any { it.kind == PrivacyBlockKind.META && it.text.startsWith("修订日期") })

        val linkPattern = Regex("""<a href="((?:https://|mailto:)[^"]+)">([^<]+)</a>""")
        val expectedLinks = linkPattern.findAll(sourceMain).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val actualLinks = policy.blocks.flatMap { block ->
            block.links.map { it.url to block.text.substring(it.start, it.end) }
        }
        assertTrue(expectedLinks.isNotEmpty())
        assertEquals(expectedLinks, actualLinks)
    }

    @Test fun renderingCollapsesHtmlWhitespaceWithoutCorruptingChineseLinkOffsets() {
        val policy = PrivacyPolicy.parse("""
            <main id="main">
              <p>先看
                <a href="https://example.com/?x=1&amp;y=2"> 用户权利😀 </a> 后联系
                <a href="javascript:alert(1)">开发者</a>。
              </p>
            </main>
        """.trimIndent())
        val block = policy.blocks.single()
        assertEquals("先看 用户权利😀 后联系 开发者。", block.text)
        val link = block.links.single()
        assertEquals("用户权利😀", block.text.substring(link.start, link.end))
        assertEquals("https://example.com/?x=1&y=2", link.url)
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
            compose.onNodeWithText("InkOTP 在你的设备上生成两步验证码。", substring = true).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("未成年人及更新"))
            compose.onNodeWithText("未成年人及更新").assertIsDisplayed()
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
