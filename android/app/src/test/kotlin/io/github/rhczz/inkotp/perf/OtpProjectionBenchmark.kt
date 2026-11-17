package io.github.rhczz.inkotp.perf

import io.github.rhczz.inkotp.app.VaultActions
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import java.io.File
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.ceil

/**
 * Opt-in host JVM trend benchmark; no @Test and no Android timing assertions.
 * Runs through the actual production projection/HMAC implementations. Only
 * aggregate numbers and public runtime metadata may be written to the report.
 */
object OtpProjectionBenchmark {
    @Volatile private var consumed: List<VaultItemUi> = emptyList()
    private const val EPOCH = 1_700_000_010L // aligned to a 30-second counter
    private const val SAMPLES = 300

    @JvmStatic
    fun main(args: Array<String>) {
        // Warm the provider and JIT before either account-count comparison.
        val warmModel = projectionFixture(1000)
        repeat(400) { consumed = VaultActions.project(warmModel, EPOCH + it % 30) }
        val allocation = AllocationCounter.create()
        val results = listOf(500, 1000).map { count ->
            val model = projectionFixture(count)
            val cache = OtpProjectionCache()
            repeat(150) {
                consumed = VaultActions.project(model, EPOCH + it % 30)
                consumed = cache.project(model, EPOCH + it % 30)
            }
            val baseline = measure("uncached_per_second", allocation) { index -> VaultActions.project(model, EPOCH + index % 30) }
            val cached = measure("cached_countdown", allocation) { index -> cache.project(model, EPOCH + index % 30) }
            val boundary = measure("cached_period_boundary", allocation) { index -> cache.project(model, EPOCH + (index + 1) * 30L) }
            cache.clear()
            val beforeWindow = cache.stats().codeComputations
            repeat(30) { consumed = cache.project(model, EPOCH + it) }
            val stats = cache.stats()
            val windowComputations = stats.codeComputations - beforeWindow
            check(windowComputations == count.toLong()) { "extra OTP computations in one period" }
            val result = buildString {
                append("{\"accounts\":$count,\"windowSeconds\":30,")
                append("\"uncachedExpectedHmacsPerWindow\":${count * 30},\"cachedMeasuredCodeComputationsPerWindow\":$windowComputations,")
                append("\"cachedEntries\":${stats.cachedEntries},\"cachedCodeCharacters\":${stats.cachedCodeCharacters},\"metadataCharacters\":${stats.metadataCharacters},")
                append("\"indexAndCounterArrayPayloadBytes\":${count * 12},\"maxEntries\":10000,")
                append("\"measurements\":[${baseline.json()},${cached.json()},${boundary.json()}]}")
            }
            cache.clear()
            model.items.forEach { it.secret.fill(0) }
            result
        }
        consumed = emptyList()
        warmModel.items.forEach { it.secret.fill(0) }
        val report = buildString {
            append("{\"measurementKind\":\"host JVM trend only; not Android or phone measurements\",")
            append("\"javaVersion\":${quote(System.getProperty("java.version").orEmpty())},\"vm\":${quote(System.getProperty("java.vm.name").orEmpty())},")
            append("\"os\":${quote(System.getProperty("os.name").orEmpty())},\"architecture\":${quote(System.getProperty("os.arch").orEmpty())},")
            append("\"allocationMeasurement\":${quote(if (allocation == null) "unsupported" else "current-thread allocated bytes; not retained heap or process PSS")},")
            append("\"memoryLimitNote\":\"cache stores at most 10000 UI rows and numeric indices/counters; exact retained heap is not measured; no strong seed or model references\",")
            append("\"cases\":[${results.joinToString(",")}]}")
        }
        args.firstOrNull()?.let { path ->
            val output = File(path)
            output.parentFile?.mkdirs()
            output.writeText(report + "\n")
        }
        println(report)
    }

    private fun measure(name: String, allocation: AllocationCounter?, action: (Int) -> List<VaultItemUi>): Measurement {
        val nanos = LongArray(SAMPLES)
        val allocatedBefore = allocation?.read()
        repeat(SAMPLES) { index ->
            val start = System.nanoTime()
            consumed = action(index)
            nanos[index] = System.nanoTime() - start
        }
        val allocatedAfter = allocation?.read()
        nanos.sort()
        val bytesPerOperation = if (allocatedBefore != null && allocatedAfter != null && allocatedAfter >= allocatedBefore) {
            (allocatedAfter - allocatedBefore) / SAMPLES
        } else null
        return Measurement(name, nanos[(ceil(SAMPLES * 0.50).toInt() - 1)], nanos[(ceil(SAMPLES * 0.95).toInt() - 1)], bytesPerOperation)
    }

    private data class Measurement(val name: String, val p50Nanos: Long, val p95Nanos: Long, val allocatedBytes: Long?) {
        fun json(): String = String.format(Locale.ROOT,
            "{\"scenario\":%s,\"samples\":%d,\"p50Ms\":%.6f,\"p95Ms\":%.6f,\"allocatedBytesPerProjection\":%s}",
            quote(name), SAMPLES, p50Nanos / 1_000_000.0, p95Nanos / 1_000_000.0, allocatedBytes?.toString() ?: "null")
    }

    // Reflection keeps host-only management APIs out of the Android compile API.
    private class AllocationCounter(private val bean: Any, private val readBytes: Method) {
        @Suppress("DEPRECATION") // getId is available in the Android compile API; this runner executes on the host JVM.
        fun read(): Long? = try { (readBytes.invoke(bean, Thread.currentThread().id) as Long).takeIf { it >= 0 } }
        catch (_: ReflectiveOperationException) { null }
        companion object {
            fun create(): AllocationCounter? = try {
                val bean = requireNotNull(Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null))
                val type = Class.forName("com.sun.management.ThreadMXBean")
                if (type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) null else {
                    if (type.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true) {
                        type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType!!).invoke(bean, true)
                    }
                    AllocationCounter(bean, type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType!!))
                }
            } catch (_: ReflectiveOperationException) { null }
            catch (_: SecurityException) { null }
        }
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
