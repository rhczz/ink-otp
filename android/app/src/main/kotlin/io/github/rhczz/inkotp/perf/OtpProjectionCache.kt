package io.github.rhczz.inkotp.perf

import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.otp.OtpEngine
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import java.lang.ref.WeakReference
import java.util.Locale

data class ProjectionCacheStats(
    val cachedEntries: Int,
    val codeComputations: Long,
    val modelInvalidations: Long,
    val cachedCodeCharacters: Int,
    val metadataCharacters: Int,
)

/**
 * Owned by the session; project/clear/stats must run under its existing gate.
 * Models are immutable snapshots (as produced by VaultRepository). No item,
 * model, OTP parameter, Mac or seed is strongly retained by this cache.
 * A weak identity token avoids trusting timestamps or retaining old secrets.
 */
class OtpProjectionCache {
    private var source = WeakReference<VaultModel>(null)
    private var sourceIndices = IntArray(0)
    private var counters = LongArray(0)
    private var rows: List<VaultItemUi> = emptyList()
    private var lastEpoch: Long? = null
    private var computations = 0L
    private var invalidations = 0L

    fun project(model: VaultModel, epochSeconds: Long): List<VaultItemUi> {
        require(model.items.size <= MAX_ENTRIES) { "too many projection entries" }
        if (source.get() !== model) {
            clear()
            source = WeakReference(model)
            sourceIndices = model.items.indices.sortedBy { model.items[it].sortIndex }.toIntArray()
            counters = LongArray(sourceIndices.size) { Long.MIN_VALUE }
            invalidations++
        }
        val epoch = epochSeconds.coerceAtLeast(0)
        if (lastEpoch == epoch) return rows
        var changedCounters: LongArray? = null
        val next = ArrayList<VaultItemUi>(sourceIndices.size)
        sourceIndices.forEachIndexed { position, sourceIndex ->
            val item = model.items[sourceIndex]
            val supported = item.type == OtpType.TOTP
            if (supported) OtpEngine.validatePeriod(item.period)
            val counter = if (supported) epoch / item.period else Long.MIN_VALUE
            val previous = rows.getOrNull(position)
            val code = if (!supported) "—" else if (previous == null || counter != counters[position]) {
                computations++
                val value = OtpEngine.hotp(item.otpParameters(), counter)
                val updated = changedCounters ?: counters.copyOf().also { changedCounters = it }
                updated[position] = counter
                value
            } else previous.code
            val remaining = if (supported) (item.period - epoch % item.period).toInt() else 0
            next += if (previous != null) previous.copy(code = code, remainingSeconds = remaining) else VaultItemUi(
                id = item.id, issuer = item.issuer, accountName = item.accountName, code = code,
                digits = item.digits, remainingSeconds = remaining, period = item.period,
                hidden = model.settings.hideCodesByDefault, type = item.type.name.lowercase(Locale.ROOT), algorithm = item.algorithm.name,
            )
        }
        // Publish together: a failed computation cannot pair an old code with a new counter.
        changedCounters?.let { counters = it }
        rows = next
        lastEpoch = epoch
        return rows
    }

    fun clear() {
        source.clear()
        sourceIndices = IntArray(0)
        counters = LongArray(0)
        rows = emptyList()
        lastEpoch = null
    }

    fun stats() = ProjectionCacheStats(
        cachedEntries = rows.size,
        codeComputations = computations,
        modelInvalidations = invalidations,
        cachedCodeCharacters = rows.sumOf { it.code.length },
        metadataCharacters = rows.sumOf { it.id.length + (it.issuer?.length ?: 0) + it.accountName.length + it.type.length + it.algorithm.length },
    )

    private companion object { const val MAX_ENTRIES = 10_000 }
}
