package io.github.rhczz.inkotp.core.backup

import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.util.Hash
import java.util.Locale

enum class MergeConflictPolicy {
    KEEP_LOCAL,
    OVERWRITE_LOCAL,
    KEEP_BOTH,
}

data class MergeConflict(
    val local: VaultItem,
    val remote: VaultItem,
)

data class MergeResult(
    val items: List<VaultItem>,
    val conflicts: List<MergeConflict>,
)

data class ItemKey(
    val type: OtpType,
    val issuerLower: String,
    val accountLower: String,
    val secretHashHex: String,
)

object BackupMerge {

    fun keyOf(item: VaultItem): ItemKey = ItemKey(
        type = item.type,
        issuerLower = (item.issuer ?: "").lowercase(Locale.ROOT),
        accountLower = item.accountName.lowercase(Locale.ROOT),
        secretHashHex = Hash.sha256Hex(item.secret),
    )

    /**
     * Merge imported items into the local list using the fixed dedupe key
     * (design doc C-11 / §7.4). Conflicts are returned for the UI; the
     * supplied policy is applied immediately and the result is re-indexed.
     */
    fun merge(
        localItems: List<VaultItem>,
        remoteItems: List<VaultItem>,
        policy: MergeConflictPolicy = MergeConflictPolicy.KEEP_LOCAL,
    ): MergeResult {
        val result = localItems.toMutableList()
        val indexByKey = LinkedHashMap<ItemKey, Int>()
        result.forEachIndexed { index, item ->
            indexByKey.putIfAbsent(keyOf(item), index)
        }

        val conflicts = mutableListOf<MergeConflict>()
        for (remote in remoteItems) {
            val key = keyOf(remote)
            val existingIndex = indexByKey[key]
            if (existingIndex == null) {
                indexByKey[key] = result.size
                result += remote
                continue
            }
            val local = result[existingIndex]
            conflicts += MergeConflict(local = local, remote = remote)
            when (policy) {
                MergeConflictPolicy.KEEP_LOCAL -> Unit
                MergeConflictPolicy.OVERWRITE_LOCAL -> result[existingIndex] = remote
                MergeConflictPolicy.KEEP_BOTH -> {
                    val renamed = remote.withUniqueAccount(result)
                    indexByKey[keyOf(renamed)] = result.size
                    result += renamed
                }
            }
        }

        val reindexed = result.mapIndexed { index, item ->
            item.copy(sortIndex = (index + 1) * 1000)
        }
        return MergeResult(items = reindexed, conflicts = conflicts)
    }

    private fun VaultItem.withUniqueAccount(existing: List<VaultItem>): VaultItem {
        val used = existing.map { it.accountName }.toSet()
        var suffix = 2
        var candidate = "$accountName ($suffix)"
        while (candidate in used) {
            suffix++
            candidate = "$accountName ($suffix)"
        }
        return copy(accountName = candidate)
    }
}
