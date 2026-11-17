package io.github.rhczz.inkotp.platform

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fixed messages only: neither provider URIs nor decoded credentials enter errors. */
class QrImageReadException internal constructor(message: String) : Exception(message)

internal const val MAX_QR_IMAGE_BYTES = 16 * 1024 * 1024
private const val MAX_DECODED_EDGE = 2048
private const val MAX_SOURCE_EDGE = 32_768
private const val MAX_SOURCE_PIXELS = 100_000_000L
private const val MAX_CAMERA_PIXELS = 4_194_304L

/** Reads a user-selected image on a worker thread. No file or network URL is opened. */
suspend fun readQrImage(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
        throw QrImageReadException("请选择系统相册中的图片")
    }
    var encoded: ByteArray? = null
    try {
        ensureActive()
        encoded = context.contentResolver.openInputStream(uri)?.use(::readBoundedImage)
            ?: throw QrImageReadException("图片无法读取或格式不支持")
        ensureActive()
        decodeQrImage(encoded).also { ensureActive() }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: QrImageReadException) {
        throw exception
    } catch (_: OutOfMemoryError) {
        throw QrImageReadException("图片过大，请裁剪二维码后重试")
    } catch (_: Exception) {
        throw QrImageReadException("图片无法读取或格式不支持")
    } finally {
        encoded?.fill(0)
    }
}

/** Does not trust ContentProvider size metadata, which is optional and forgeable. */
internal fun readBoundedImage(input: InputStream): ByteArray {
    val chunk = ByteArray(8192)
    val output = ByteArrayOutputStream()
    try {
        var total = 0
        while (true) {
            val count = input.read(chunk, 0, minOf(chunk.size, MAX_QR_IMAGE_BYTES - total + 1))
            if (count == -1) break
            if (count == 0) {
                // Avoid spinning on a malformed provider's zero-length read.
                val next = input.read()
                if (next == -1) break
                if (++total > MAX_QR_IMAGE_BYTES) throw QrImageReadException("图片超过 16 MB，请裁剪后重试")
                output.write(next)
            } else {
                total += count
                if (total > MAX_QR_IMAGE_BYTES) throw QrImageReadException("图片超过 16 MB，请裁剪后重试")
                output.write(chunk, 0, count)
            }
        }
        return output.toByteArray()
    } finally {
        chunk.fill(0)
        output.close()
    }
}

internal fun imageSampleSize(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE ||
        width.toLong() * height > MAX_SOURCE_PIXELS
    ) {
        throw QrImageReadException("图片尺寸无效或过大，请裁剪二维码后重试")
    }
    var sample = 1
    while ((width + sample - 1) / sample > MAX_DECODED_EDGE ||
        (height + sample - 1) / sample > MAX_DECODED_EDGE
    ) {
        sample *= 2
    }
    return sample
}

internal fun decodeQrImage(encoded: ByteArray): String {
    if (encoded.size > MAX_QR_IMAGE_BYTES) throw QrImageReadException("图片超过 16 MB，请裁剪后重试")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw QrImageReadException("图片无法读取或格式不支持")
    val options = BitmapFactory.Options().apply {
        inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
    val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        ?: throw QrImageReadException("图片无法读取或格式不支持")
    var pixels: IntArray? = null
    try {
        // Check the actual decoder result as well as the untrusted header.
        if (bitmap.width > MAX_DECODED_EDGE || bitmap.height > MAX_DECODED_EDGE) {
            throw QrImageReadException("图片过大，请裁剪二维码后重试")
        }
        pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return decodeQrSource(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
            ?: throw QrImageReadException("未识别到二维码，请选择清晰的二维码图片")
    } finally {
        pixels?.fill(0)
        bitmap.eraseColor(0)
        bitmap.recycle()
    }
}

internal data class LuminanceFrame(val bytes: ByteArray, val width: Int, val height: Int)

/** The last Y row need not contain padding; read only the requested visible pixels. */
internal fun copyLuminancePlane(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropWidth: Int = width,
    cropHeight: Int = height,
): LuminanceFrame {
    require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0)
    require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0)
    require(cropLeft.toLong() + cropWidth <= width && cropTop.toLong() + cropHeight <= height)
    require(cropWidth.toLong() * cropHeight <= MAX_CAMERA_PIXELS)
    require((width - 1L) * pixelStride < rowStride)
    val base = buffer.position()
    val lastIndex = base.toLong() + (cropTop + cropHeight - 1L) * rowStride +
        (cropLeft + cropWidth - 1L) * pixelStride
    require(lastIndex < buffer.limit())
    val bytes = ByteArray(cropWidth * cropHeight)
    for (row in 0 until cropHeight) {
        val offset = base + (cropTop + row) * rowStride + cropLeft * pixelStride
        for (column in 0 until cropWidth) {
            bytes[row * cropWidth + column] = buffer.get(offset + column * pixelStride)
        }
    }
    return LuminanceFrame(bytes, cropWidth, cropHeight)
}

internal fun rotateLuminance(frame: LuminanceFrame, rotationDegrees: Int): LuminanceFrame {
    require(rotationDegrees in setOf(0, 90, 180, 270))
    if (rotationDegrees == 0) return frame
    val swapped = rotationDegrees == 90 || rotationDegrees == 270
    val width = if (swapped) frame.height else frame.width
    val height = if (swapped) frame.width else frame.height
    val rotated = ByteArray(frame.bytes.size)
    for (y in 0 until frame.height) for (x in 0 until frame.width) {
        val index = when (rotationDegrees) {
            90 -> x * width + (frame.height - 1 - y)
            180 -> (frame.height - 1 - y) * width + (frame.width - 1 - x)
            else -> (frame.width - 1 - x) * width + y
        }
        rotated[index] = frame.bytes[y * frame.width + x]
    }
    return LuminanceFrame(rotated, width, height)
}

internal fun decodeQrFrame(frame: LuminanceFrame): String? = decodeQrSource(
    PlanarYUVLuminanceSource(frame.bytes, frame.width, frame.height, 0, 0, frame.width, frame.height, false),
)

private fun decodeQrSource(source: LuminanceSource): String? {
    // QRCodeReader cannot recognize any other barcode format. Try inverted QR too.
    val reader = QRCodeReader()
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    for (candidate in listOf(source, source.invert())) {
        try {
            return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
        } catch (_: ReaderException) {
            // No QR in this frame: normal camera operation, never log its contents.
        } finally {
            reader.reset()
        }
    }
    return null
}
