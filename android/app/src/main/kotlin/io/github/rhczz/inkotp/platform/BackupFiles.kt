package io.github.rhczz.inkotp.platform

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BackupFiles {
    private const val MAX_BYTES = 12 * 1024 * 1024

    suspend fun read(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_BYTES) throw IOException("Backup is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally { buffer.fill(0); output.close() }
        } ?: throw IOException("Cannot read backup")
    }

    suspend fun write(context: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        context.contentResolver.openOutputStream(uri, "wt")?.use { output -> output.write(bytes); output.flush() }
            ?: throw IOException("Cannot write backup")
    }
}
