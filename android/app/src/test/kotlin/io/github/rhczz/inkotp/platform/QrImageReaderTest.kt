package io.github.rhczz.inkotp.platform

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class QrImageReaderTest {
    @Test
    fun copiesCroppedYPlaneWithPaddingAndPixelStrideWithoutChangingBufferPosition() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 1, 0, 2, 0, 3, 0, 88, 4, 0, 5, 0, 6))
        buffer.position(1)

        val frame = copyLuminancePlane(buffer, 3, 2, 7, 2, 1, 0, 2, 2)

        assertArrayEquals(byteArrayOf(2, 3, 5, 6), frame.bytes)
        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(1, buffer.position())
    }

    @Test
    fun rejectsTruncatedOrOutOfBoundsCameraPlane() {
        assertThrows(IllegalArgumentException::class.java) {
            copyLuminancePlane(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 2, 2, 2, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyLuminancePlane(ByteBuffer.allocate(4), 2, 2, 2, 1, 1, 0, 2, 2)
        }
    }

    @Test
    fun rotatesNonSquareCameraFramesInAllSensorOrientations() {
        val frame = LuminanceFrame(byteArrayOf(1, 2, 3, 4, 5, 6), 3, 2)

        val clockwise = rotateLuminance(frame, 90)
        assertEquals(2, clockwise.width)
        assertEquals(3, clockwise.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), clockwise.bytes)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), rotateLuminance(frame, 180).bytes)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), rotateLuminance(frame, 270).bytes)
    }

    @Test
    fun limitsDecodedDimensionsAndRejectsInvalidImageHeaders() {
        assertEquals(1, imageSampleSize(2048, 2048))
        assertEquals(2, imageSampleSize(2049, 2048))
        assertEquals(4, imageSampleSize(8000, 6000))
        assertThrows(QrImageReadException::class.java) { imageSampleSize(-1, 100) }
        assertThrows(QrImageReadException::class.java) { imageSampleSize(Int.MAX_VALUE, 100) }
        assertThrows(QrImageReadException::class.java) { imageSampleSize(20_000, 20_000) }
    }

    @Test
    fun boundsEncodedImageEvenIfProviderHasNoSizeMetadata() {
        val exact = ByteArray(MAX_QR_IMAGE_BYTES)
        assertEquals(MAX_QR_IMAGE_BYTES, readBoundedImage(ByteArrayInputStream(exact)).size)
        assertThrows(QrImageReadException::class.java) {
            readBoundedImage(ByteArrayInputStream(ByteArray(MAX_QR_IMAGE_BYTES + 1)))
        }
    }

    @Test
    fun decodesRealQrBitmapAndRejectsNonImageInputWithoutDisclosingData() {
        val content = "otpauth://totp/Test:sample?secret=JBSWY3DPEHPK3PXP&issuer=Test"
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 320, 320)
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(320 * 320) { index ->
            if (matrix[index % 320, index / 320]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        bitmap.setPixels(pixels, 0, 320, 0, 0, 320, 320)
        val encoded = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()

        assertEquals(content, decodeQrImage(encoded))
        val failure = assertThrows(QrImageReadException::class.java) { decodeQrImage(content.toByteArray()) }
        assertEquals("图片无法读取或格式不支持", failure.message)
    }
}
