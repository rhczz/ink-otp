package io.github.rhczz.inkotp.core.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriftDetectorTest {

    @Test
    fun firstSampleEstablishesBaseline() {
        val detector = DriftDetector()
        val sample = detector.sample(wallMillis = 1_000_000, monotonicMillis = 500_000)
        assertFalse(sample.drifted)
        assertEquals(0, sample.wallDeltaMillis)
        assertEquals(0, sample.monotonicDeltaMillis)
    }

    @Test
    fun consistentClocksDoNotDrift() {
        val detector = DriftDetector()
        detector.sample(1_000_000, 500_000)
        val sample = detector.sample(1_060_000, 560_000)
        assertFalse(sample.drifted)
        assertEquals(60_000, sample.wallDeltaMillis)
        assertEquals(60_000, sample.monotonicDeltaMillis)
    }

    @Test
    fun wallClockJumpIsDetected() {
        val detector = DriftDetector()
        detector.sample(1_000_000, 500_000)
        val sample = detector.sample(1_300_000, 560_000)
        assertTrue(sample.drifted)
        assertEquals(300_000, sample.wallDeltaMillis)
        assertEquals(60_000, sample.monotonicDeltaMillis)
    }

    @Test
    fun thresholdIsStrictlyGreaterThanTwoSeconds() {
        val detector = DriftDetector()
        detector.sample(1_000_000, 500_000)
        assertFalse(detector.sample(1_002_000, 500_000).drifted)
        assertTrue(detector.sample(1_002_001, 500_000).drifted)
    }

    @Test
    fun resetStartsANewBaseline() {
        val detector = DriftDetector()
        detector.sample(1_000_000, 500_000)
        detector.sample(2_000_000, 500_000)
        detector.reset()
        val sample = detector.sample(3_000_000, 1_000_000)
        assertFalse(sample.drifted)
    }
}
