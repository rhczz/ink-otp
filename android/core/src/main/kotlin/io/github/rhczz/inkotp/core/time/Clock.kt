package io.github.rhczz.inkotp.core.time

interface Clock {
    fun epochSeconds(): Long

    fun monotonicMillis(): Long
}

data class DriftSample(
    val drifted: Boolean,
    val wallDeltaMillis: Long,
    val monotonicDeltaMillis: Long,
)

/**
 * Detects wall-clock jumps by comparing the wall-clock delta with the
 * monotonic-clock delta. A threshold of 2 s matches design doc §8.4.
 */
class DriftDetector(
    private val thresholdMillis: Long = 2_000,
) {
    private var baseWallMillis: Long? = null
    private var baseMonotonicMillis: Long? = null

    fun reset() {
        baseWallMillis = null
        baseMonotonicMillis = null
    }

    fun sample(wallMillis: Long, monotonicMillis: Long): DriftSample {
        val baseWall = baseWallMillis
        val baseMono = baseMonotonicMillis
        if (baseWall == null || baseMono == null) {
            baseWallMillis = wallMillis
            baseMonotonicMillis = monotonicMillis
            return DriftSample(false, 0, 0)
        }
        val wallDelta = wallMillis - baseWall
        val monoDelta = monotonicMillis - baseMono
        return DriftSample(
            drifted = kotlin.math.abs(wallDelta - monoDelta) > thresholdMillis,
            wallDeltaMillis = wallDelta,
            monotonicDeltaMillis = monoDelta,
        )
    }
}
