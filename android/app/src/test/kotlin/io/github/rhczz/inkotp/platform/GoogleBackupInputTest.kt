package io.github.rhczz.inkotp.platform

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.rhczz.inkotp.core.uri.GoogleMigrationException
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GoogleBackupInputTest {
    private val first = "otpauth-migration://offline?data=AA%3D%3D"
    private val second = "otpauth-migration://offline?data=AQ%3D%3D"
    private val setupQr = "otpauth://totp/Public:Example?secret=JBSWY3DPEHPK3PXP"

    @Test fun detectsMigrationLinksInUtf8TextWithoutTakingOverOrdinaryBackups() {
        val text = "\uFEFF \r\n  $first  \r\n\r\n$second\n"
        assertEquals(listOf(first, second), googleLinksFromBackup(text.toByteArray()))
        assertNull(googleLinksFromBackup(setupQr.toByteArray()))
        assertNull(googleLinksFromBackup("""{"schemaVersion":4,"services":[]}""".toByteArray()))
    }

    @Test fun rejectsMixedAndInvalidLinesBeforeReturningAnyMigrationPage() {
        assertThrows(GoogleMigrationException::class.java) {
            googleLinksFromBackup("$first\n$setupQr".toByteArray())
        }
        assertThrows(GoogleMigrationException::class.java) {
            googleLinksFromBackup(first.toByteArray() + byteArrayOf(0xff.toByte()))
        }
        assertThrows(GoogleMigrationException::class.java) {
            googleLinksFromBackup(("$first\n".repeat(501)).toByteArray())
        }
    }

    @Test fun recognizesMigrationQrImageByContentAndRejectsAnIndividualSetupImage() {
        assertEquals(listOf(first), googleLinksFromBackup(qrImage(first)))
        assertThrows(GoogleMigrationException::class.java) {
            googleLinksFromBackup(qrImage(setupQr))
        }
    }

    private fun qrImage(content: String): ByteArray {
        val size = 320
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(size * size) { i ->
                if (bits[i % size, i / size]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
