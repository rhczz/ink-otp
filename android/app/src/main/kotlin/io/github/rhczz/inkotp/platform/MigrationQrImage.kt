package io.github.rhczz.inkotp.platform

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val PNG_EDGE = 768

internal fun migrationQrMatrix(page: ByteArray, edge: Int): BitMatrix =
    QRCodeWriter().encode(page.decodeToString(), BarcodeFormat.QR_CODE, edge, edge,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))

internal fun encodeMigrationQrPng(page: ByteArray): ByteArray {
    val matrix = migrationQrMatrix(page, PNG_EDGE)
    val pixels = IntArray(matrix.width * matrix.height)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    try {
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        return ByteArrayOutputStream().use { png ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) throw IOException("Could not encode QR image")
            png.toByteArray()
        }
    } finally {
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.recycle()
        pixels.fill(0)
        matrix.clear()
    }
}
