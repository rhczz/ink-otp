package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.rhczz.inkotp.core.backup.MergeConflictPolicy
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
internal fun ImportReviewDialog(
    accounts: List<String>,
    conflicts: Int,
    source: String?,
    warnings: List<String>,
    busy: Boolean,
    onImport: (MergeConflictPolicy) -> Unit,
    onReplace: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = LocalInkColors.current
    var policy by remember(accounts) { mutableStateOf(MergeConflictPolicy.KEEP_LOCAL) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(AppTokens.Radius.sheet),
        containerColor = colors.surface,
        title = { Text("导入 ${accounts.size} 个账号", style = AppType.section) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.m),
            ) {
                source?.let { Text(it, style = AppType.caption, color = colors.inkSecondary) }
                Text("${conflicts} 个账号与本机重复。合并保留本机设置和恢复口令。", style = AppType.body)
                warnings.forEach { Text(it, style = AppType.caption, color = colors.warning) }
                Column(verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs)) {
                    accounts.take(50).forEach { Text(it, style = AppType.caption) }
                    if (accounts.size > 50) Text("另有 ${accounts.size - 50} 个账号将导入", style = AppType.caption)
                }
                if (conflicts > 0) {
                    Column(Modifier.selectableGroup()) {
                        MergeChoice("重复时保留本机", MergeConflictPolicy.KEEP_LOCAL, policy, !busy) { policy = it }
                        MergeChoice("重复时使用导入账号", MergeConflictPolicy.OVERWRITE_LOCAL, policy, !busy) { policy = it }
                        MergeChoice("重复时保留两个", MergeConflictPolicy.KEEP_BOTH, policy, !busy) { policy = it }
                    }
                }
                if (onReplace != null) InkTextButton("替换本机全部账号…", onClick = onReplace, enabled = !busy, color = colors.danger)
            }
        },
        confirmButton = { TextButton(onClick = { onImport(policy) }, enabled = !busy) { Text("合并导入") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun MergeChoice(
    title: String,
    option: MergeConflictPolicy,
    current: MergeConflictPolicy,
    enabled: Boolean,
    onSelect: (MergeConflictPolicy) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppTokens.Size.touchTarget)
            .selectable(selected = current == option, enabled = enabled, role = Role.RadioButton) { onSelect(option) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == option, onClick = null, enabled = enabled)
        Text(title, style = AppType.body, modifier = Modifier.padding(start = AppTokens.Spacing.m))
    }
}
