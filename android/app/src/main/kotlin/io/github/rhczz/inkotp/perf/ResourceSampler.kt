package io.github.rhczz.inkotp.perf

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.DirectoryIteratorException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class OperationKind { INITIALIZE, UNLOCK, SAVE, CHANGE_PASSWORD, AUTHENTICATION, EXPORT, IMPORT }

data class OperationTiming(val kind: OperationKind, val elapsedMillis: Long) {
    init { require(elapsedMillis >= 0) }
}

/** Aggregate measurements only; never includes names, paths, seeds or codes. */
data class ResourceSnapshot(
    val totalPssBytes: Long?,
    val javaHeapUsedBytes: Long,
    val javaHeapMaxBytes: Long,
    val nativeHeapAllocatedBytes: Long?,
    val privateStorageBytes: Long,
    val privateStorageComplete: Boolean,
    val scannedEntries: Int,
    val sampleDurationMillis: Long,
    val lastOperation: OperationTiming?,
)

object ResourceSampler {
    /** Explicit user refresh only. Directory access and PSS collection happen off main. */
    suspend fun sample(context: Context, lastOperation: OperationTiming? = null): ResourceSnapshot = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtimeNanos()
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val pss = try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss.toLong().takeIf { it > 0 }?.times(1024)
        } catch (_: RuntimeException) { null }
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
        val nativeHeap = try { Debug.getNativeHeapAllocatedSize().takeIf { it >= 0 } }
        catch (_: RuntimeException) { null }
        val disk = scanPrivateStorage(context.applicationContext.dataDir.toPath(), checkCancelled = { coroutine.ensureActive() })
        ResourceSnapshot(
            totalPssBytes = pss,
            javaHeapUsedBytes = heapUsed,
            javaHeapMaxBytes = runtime.maxMemory(),
            nativeHeapAllocatedBytes = nativeHeap,
            privateStorageBytes = disk.bytes,
            privateStorageComplete = disk.complete,
            scannedEntries = disk.scannedEntries,
            sampleDurationMillis = ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).coerceAtLeast(0),
            lastOperation = lastOperation,
        )
    }
}

internal data class PrivateStorageUsage(val bytes: Long, val complete: Boolean, val scannedEntries: Int)

/**
 * Logical bytes of regular files under the app-owned directory, including its
 * cache. Does not follow symlinks, read file contents, or materialize an entire
 * directory listing. Budget/depth failures are reported as partial totals.
 */
internal fun scanPrivateStorage(
    root: Path,
    maxEntries: Int = 4096,
    maxDepth: Int = 8,
    checkCancelled: () -> Unit = {},
): PrivateStorageUsage {
    require(maxEntries > 0 && maxDepth >= 0)
    var bytes = 0L
    var entries = 0
    var complete = true
    fun visit(path: Path, depth: Int) {
        checkCancelled()
        if (entries >= maxEntries || depth > maxDepth) { complete = false; return }
        entries++
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            when {
                attributes.isRegularFile -> {
                    val length = attributes.size().coerceAtLeast(0)
                    if (length > Long.MAX_VALUE - bytes) { bytes = Long.MAX_VALUE; complete = false }
                    else bytes += length
                }
                attributes.isDirectory -> Files.newDirectoryStream(path).use { children ->
                    val iterator = children.iterator()
                    while (iterator.hasNext()) {
                        if (entries >= maxEntries) { complete = false; break }
                        visit(iterator.next(), depth + 1)
                    }
                }
                // Symbolic links are deliberately excluded; their targets may be outside app data.
                else -> Unit
            }
        } catch (_: IOException) { complete = false }
        catch (_: DirectoryIteratorException) { complete = false }
        catch (_: SecurityException) { complete = false }
    }
    visit(root, 0)
    return PrivateStorageUsage(bytes, complete, entries)
}
