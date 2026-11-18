package io.github.rhczz.inkotp.platform

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Stores only the locally accepted policy revision, outside backup and migration. */
internal class PrivacyChoiceStore(baseFile: File) {
    constructor(context: Context) : this(context.noBackupFilesDir.resolve("privacy-choice"))

    private val file = AtomicFile(baseFile)

    fun acceptedRevision(): String? = synchronized(lock) {
        read()?.takeUnless(String::isEmpty)
    }

    fun save(revision: String?): Boolean = synchronized(lock) {
        val value = revision.orEmpty()
        val stream = try {
            file.startWrite()
        } catch (_: IOException) {
            return@synchronized false
        }
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: IOException) {
            file.failWrite(stream)
            return@synchronized false
        }
        read() == value
    }

    private fun read(): String? = try {
        file.readFully().toString(Charsets.UTF_8)
    } catch (_: IOException) {
        null
    }

    private companion object {
        // AtomicFile does not serialize concurrent reads and writes.
        val lock = Any()
    }
}
