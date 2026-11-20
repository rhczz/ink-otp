package io.github.rhczz.inkotp.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.rhczz.inkotp.core.interchange.InterchangeFormat
import io.github.rhczz.inkotp.ui.components.documentMime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExportDocumentContractTest {
    private val contract = ExportDocumentContract()

    @Test fun exportRequestsStandardSafDocumentCreationWithTheSelectedMimeAndFilename() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases = listOf(
            Triple(InterchangeFormat.AEGIS_ENCRYPTED, "json", "application/json"),
            Triple(InterchangeFormat.AEGIS_PLAINTEXT, "json", "application/json"),
            Triple(InterchangeFormat.TWOFAS_ENCRYPTED, "2fas", "application/octet-stream"),
            Triple(InterchangeFormat.TWOFAS_PLAINTEXT, "2fas", "application/octet-stream"),
            Triple(InterchangeFormat.OTPAUTH_TEXT, "txt", "text/plain"),
        )
        for ((format, extension, mime) in cases) {
            assertEquals(extension, format.extension)
            val intent = contract.createIntent(context,
                ExportDocumentRequest("公开测试备份-2026-11-17.${format.extension}", format.documentMime()))
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertEquals(mime, intent.type)
            assertEquals("公开测试备份-2026-11-17.$extension", intent.getStringExtra(Intent.EXTRA_TITLE))
            assertNull(intent.data)
        }
    }

    @Test fun successfulSelectionReturnsOnlyTheSystemGrantedDocumentUri() {
        val selected = Uri.parse("content://test.documents/document/public-fixture")
        assertEquals(selected, contract.parseResult(Activity.RESULT_OK, Intent().setData(selected)))
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent()))
    }

    @Test fun cancellationDiscardsEvenAnIntentThatContainsAStaleUri() {
        val stale = Intent().setData(Uri.parse("content://test.documents/document/cancelled"))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, stale))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
        assertNull(contract.parseResult(Activity.RESULT_FIRST_USER, stale))
    }
}
