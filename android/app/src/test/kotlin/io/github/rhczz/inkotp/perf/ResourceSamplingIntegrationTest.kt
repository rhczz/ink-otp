package io.github.rhczz.inkotp.perf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResourceSamplingIntegrationTest {
    @Test fun realContextSamplingCountsPrivateFilesAndReturnsOnlyAggregateResourceValues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Resolve the standard directory before the baseline so its creation is not counted as a fixture.
        val files = context.filesDir
        val before = ResourceSampler.sample(context)
        val fixture = files.resolve("resource-sampling-public-fixture")
        assertTrue(fixture.mkdirs())
        val marker = "public-resource-marker-never-a-real-secret"
        val first = fixture.resolve("public-one.bin")
        val second = fixture.resolve("public-two.bin")
        first.writeText(marker)
        second.writeBytes(ByteArray(73) { 7 })
        val timing = OperationTiming(OperationKind.SAVE, elapsedMillis = 17)
        try {
            val sample = ResourceSampler.sample(context, timing)

            assertEquals(timing, sample.lastOperation)
            assertTrue(sample.privateStorageComplete)
            assertTrue(sample.scannedEntries >= before.scannedEntries + 3)
            assertTrue(sample.privateStorageBytes >= before.privateStorageBytes + first.length() + second.length())
            assertTrue(sample.javaHeapUsedBytes >= 0)
            assertTrue(sample.javaHeapMaxBytes > 0)
            assertTrue(sample.sampleDurationMillis >= 0)
            sample.totalPssBytes?.let { assertTrue(it > 0) }
            sample.nativeHeapAllocatedBytes?.let { assertTrue(it >= 0) }
            val description = sample.toString()
            assertFalse(description.contains(context.dataDir.absolutePath))
            assertFalse(description.contains(first.name))
            assertFalse(description.contains(marker))
        } finally {
            fixture.deleteRecursively()
        }
    }
}
