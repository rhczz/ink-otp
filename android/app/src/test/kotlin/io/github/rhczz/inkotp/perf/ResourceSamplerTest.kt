package io.github.rhczz.inkotp.perf

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceSamplerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun totalsRegularPrivateFilesWithoutFollowingLinksOrOpeningTheirContents() {
        val root = temporary.newFolder("private").toPath()
        Files.write(root.resolve("vault.bin"), ByteArray(123))
        val settings = Files.createDirectory(root.resolve("datastore"))
        Files.write(settings.resolve("settings.pb"), ByteArray(17))
        val outside = temporary.newFile("outside.bin").toPath()
        Files.write(outside, ByteArray(1000))
        Files.createSymbolicLink(root.resolve("outside-link"), outside)
        Files.createSymbolicLink(root.resolve("loop"), root)

        val result = scanPrivateStorage(root)

        assertEquals(140L, result.bytes)
        assertTrue(result.complete)
        assertEquals(6, result.scannedEntries)
    }

    @Test fun nodeAndDepthBudgetsReturnAnExplicitPartialTotal() {
        val root = temporary.newFolder("budget").toPath()
        val nested = Files.createDirectories(root.resolve("one/two/three"))
        Files.write(nested.resolve("data"), ByteArray(10))

        val limited = scanPrivateStorage(root, maxEntries = 2)
        assertEquals(2, limited.scannedEntries)
        assertFalse(limited.complete)
        val shallow = scanPrivateStorage(root, maxDepth = 1)
        assertEquals(0L, shallow.bytes)
        assertFalse(shallow.complete)
    }

    @Test fun missingDirectoryIsNotMisreportedAsACompleteEmptyDataDirectory() {
        assertFalse(scanPrivateStorage(temporary.root.toPath().resolve("missing")).complete)
    }

    @Test fun cancellationIsPropagatedRatherThanConvertedToAPartialSnapshot() {
        assertThrows(CancellationException::class.java) {
            scanPrivateStorage(temporary.root.toPath(), checkCancelled = { throw CancellationException("test") })
        }
    }
}
