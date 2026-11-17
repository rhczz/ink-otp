package io.github.rhczz.inkotp.perf

import io.github.rhczz.inkotp.app.VaultActions
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpType
import org.junit.Assert.*
import org.junit.Test

class OtpProjectionCacheTest {
    @Test fun fiveHundredAndOneThousandAccountsComputeOncePerPeriodAndStillCountDownEachSecond() {
        for (count in listOf(500, 1000)) {
            val model = projectionFixture(count)
            val cache = OtpProjectionCache()
            for (second in 0L until 30L) {
                val rows = cache.project(model, ALIGNED_EPOCH + second)
                assertEquals(30 - second.toInt(), rows.first().remainingSeconds)
                assertEquals(VaultActions.project(model, ALIGNED_EPOCH + second), rows)
            }
            assertEquals(count.toLong(), cache.stats().codeComputations)
            cache.project(model, ALIGNED_EPOCH + 30)
            assertEquals(count * 2L, cache.stats().codeComputations)
        }
    }

    @Test fun sameSecondReusesTheProjectionAndClockRollbackRecomputesTheCorrectCounter() {
        val model = projectionFixture(2)
        val cache = OtpProjectionCache()
        val first = cache.project(model, ALIGNED_EPOCH)
        assertSame(first, cache.project(model, ALIGNED_EPOCH))
        assertEquals(VaultActions.project(model, ALIGNED_EPOCH - 1), cache.project(model, ALIGNED_EPOCH - 1))
        assertEquals(4L, cache.stats().codeComputations)
        assertEquals(VaultActions.project(model, -1), cache.project(model, -1))
    }

    @Test fun differentPeriodsAlgorithmsDigitsAndUnsupportedHotpMatchTheExistingProjection() {
        val fixture = projectionFixture(4)
        val model = fixture.copy(items = fixture.items.mapIndexed { index, item ->
            when (index) {
                0 -> item.copy(period = 10, algorithm = OtpAlgorithm.SHA256, digits = 8)
                1 -> item.copy(period = 60, algorithm = OtpAlgorithm.SHA512)
                2 -> item.copy(type = OtpType.HOTP, counter = 0)
                else -> item.copy(period = 1)
            }
        })
        val cache = OtpProjectionCache()
        for (second in 0L until 65L) {
            assertEquals(VaultActions.project(model, ALIGNED_EPOCH + second), cache.project(model, ALIGNED_EPOCH + second))
        }
    }

    @Test fun replacingModelInvalidatesChangedSeedMetadataOrderingAndRemovedEntries() {
        val original = projectionFixture(3)
        val cache = OtpProjectionCache()
        cache.project(original, ALIGNED_EPOCH)
        // Deliberately keep updatedAt identical: timestamps are not reliable identity tokens.
        val changed = original.copy(items = original.items.drop(1).mapIndexed { index, item ->
            item.copy(secret = ByteArray(20) { 7 }, issuer = "changed", sortIndex = -index)
        }, settings = original.settings.copy(hideCodesByDefault = true))
        assertEquals(VaultActions.project(changed, ALIGNED_EPOCH), cache.project(changed, ALIGNED_EPOCH))
        assertEquals(2, cache.stats().cachedEntries)
        assertEquals(5L, cache.stats().codeComputations)
        assertFalse(cache.project(changed, ALIGNED_EPOCH).any { it.id == original.items.first().id })
    }

    @Test fun lockClearDropsAllCodesAndMetadataAndForcesRecalculationAfterUnlock() {
        val model = projectionFixture(3)
        val cache = OtpProjectionCache()
        cache.project(model, ALIGNED_EPOCH)
        cache.clear()
        assertEquals(0, cache.stats().cachedEntries)
        assertEquals(0, cache.stats().cachedCodeCharacters)
        assertEquals(0, cache.stats().metadataCharacters)
        assertEquals(VaultActions.project(model, ALIGNED_EPOCH), cache.project(model, ALIGNED_EPOCH))
        assertEquals(6L, cache.stats().codeComputations)
    }

    @Test fun cacheHasTheSameTenThousandItemLimitAsTheVaultSchema() {
        val cache = OtpProjectionCache()
        assertThrows(IllegalArgumentException::class.java) { cache.project(projectionFixture(10_001), ALIGNED_EPOCH) }
        assertEquals(0, cache.stats().cachedEntries)
    }

    private companion object { const val ALIGNED_EPOCH = 1_700_000_010L }
}

/** Public RFC 6238 seed only; this fixture never contains a real credential. */
internal fun projectionFixture(count: Int): VaultModel {
    val timestamp = "2026-11-17T00:00:00Z"
    return VaultModel(vaultId = "00000000-0000-4000-8000-000000000000", createdAt = timestamp, updatedAt = timestamp,
        items = List(count) { index ->
            VaultItem(id = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}", type = OtpType.TOTP,
                issuer = "Public benchmark", accountName = "public-$index@example.test",
                secret = "12345678901234567890".toByteArray(), sortIndex = index * 1000,
                createdAt = timestamp, updatedAt = timestamp)
        })
}
