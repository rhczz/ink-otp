package io.github.rhczz.inkotp.platform

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver get() = shadowOf(context.contentResolver)

    @Test fun selectedContentDocumentRoundTripsBinaryBytesAndClosesBothStreams() = runBlocking {
        val uri = document("roundtrip")
        val expected = byteArrayOf(0, 1, 2, -1, 42, 0)
        val output = TrackingOutput()
        resolver.registerOutputStream(uri, output)

        BackupFiles.write(context, uri, expected)

        assertArrayEquals(expected, output.toByteArray())
        assertTrue(output.flushed)
        assertTrue(output.closed)
        val input = TrackingInput(output.toByteArray())
        resolver.registerInputStream(uri, input)
        assertArrayEquals(expected, BackupFiles.read(context, uri))
        assertTrue(input.closed)
    }

    @Test fun rejectsFileNetworkAndRelativeUrisBeforeOpeningAnyStream() {
        for (uri in listOf(Uri.parse("file:///public-test.2fab"), Uri.parse("https://example.invalid/public-test"), Uri.parse("public-test.2fab"))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { BackupFiles.read(context, uri) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { BackupFiles.write(context, uri, byteArrayOf(1)) } }
        }
    }

    @Test fun acceptsExactlyTwelveMiBAndRejectsTheNextByteWhileClosingBothInputs() = runBlocking {
        val uri = document("size-boundary")
        val expected = ByteArray(MAX_BYTES) { (it % 127).toByte() }
        val exact = TrackingInput(expected)
        resolver.registerInputStream(uri, exact)
        assertArrayEquals(expected, BackupFiles.read(context, uri))
        assertTrue(exact.closed)

        val oversized = TrackingInput(ByteArray(MAX_BYTES + 1))
        resolver.registerInputStream(uri, oversized)
        assertThrows(IOException::class.java) { runBlocking { BackupFiles.read(context, uri) } }
        assertTrue(oversized.closed)
    }

    @Test fun missingProviderStreamsBecomeIoFailures() {
        // The shadow's null supplier falls back to a dummy stream. Exercise the real
        // provider path instead: a crashed provider may return a null descriptor.
        val authority = "test.null.documents"
        val provider = NullDescriptorProvider()
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/document/public-null")

        assertThrows(IOException::class.java) { runBlocking { BackupFiles.read(context, uri) } }
        assertThrows(IOException::class.java) { runBlocking { BackupFiles.write(context, uri, byteArrayOf(1)) } }
    }

    private class NullDescriptorProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test fun readAndWriteIoFailuresPropagateAndStillCloseTheProviderStream() {
        val uri = document("provider-error")
        val input = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("public fixture read failure")
            override fun close() { closed = true }
        }
        resolver.registerInputStream(uri, input)
        val readFailure = assertThrows(IOException::class.java) { runBlocking { BackupFiles.read(context, uri) } }
        assertEquals("public fixture read failure", readFailure.message)
        assertTrue(input.closed)

        val output = object : OutputStream() {
            var closed = false
            override fun write(value: Int) { throw IOException("public fixture write failure") }
            override fun close() { closed = true }
        }
        resolver.registerOutputStream(uri, output)
        val writeFailure = assertThrows(IOException::class.java) { runBlocking { BackupFiles.write(context, uri, byteArrayOf(1)) } }
        assertEquals("public fixture write failure", writeFailure.message)
        assertTrue(output.closed)
    }

    private class TrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    private class TrackingOutput : ByteArrayOutputStream() {
        var closed = false
        var flushed = false
        override fun flush() { flushed = true; super.flush() }
        override fun close() { closed = true; super.close() }
    }

    private fun document(name: String) = Uri.parse("content://test.documents/document/$name")
    private companion object { const val MAX_BYTES = 12 * 1024 * 1024 }
}
