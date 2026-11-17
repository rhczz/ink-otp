package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.AppTextField
import io.github.rhczz.inkotp.ui.components.CodeText
import io.github.rhczz.inkotp.ui.components.CountdownLabel
import io.github.rhczz.inkotp.ui.components.InkCard
import io.github.rhczz.inkotp.ui.components.InkConfirmDialog
import io.github.rhczz.inkotp.ui.components.InkDivider
import io.github.rhczz.inkotp.ui.components.InkIconButton
import io.github.rhczz.inkotp.ui.components.InkPrimaryButton
import io.github.rhczz.inkotp.ui.components.InkSecondaryButton
import io.github.rhczz.inkotp.ui.components.InkTextButton
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.components.SectionHeader
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors

@Composable
fun ItemDetailScreen(
    item: VaultItemUi,
    secretBase32: String,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onSave: (issuer: String, account: String) -> Unit,
    onCopy: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    val colors = LocalInkColors.current
    val isHotp = item.type == "hotp"
    var editing by remember(item.id) { mutableStateOf(false) }
    var issuer by remember(item.id, item.issuer) { mutableStateOf(item.issuer.orEmpty()) }
    var account by remember(item.id, item.accountName) { mutableStateOf(item.accountName) }
    var confirmDelete by remember(item.id) { mutableStateOf(false) }

    ScreenScaffold(
        title = "条目详情",
        onBack = onBack,
        actions = {
            InkIconButton(
                icon = R.drawable.ic_edit,
                contentDescription = if (editing) "保存" else "编辑",
                onClick = {
                    if (editing) {
                        onSave(issuer, account)
                        editing = false
                    } else {
                        editing = true
                    }
                },
                tint = colors.ink,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppTokens.Size.bottomContentPadding),
        ) {
            Spacer(Modifier.height(AppTokens.Spacing.l))
            InkCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.issuer ?: item.accountName,
                            style = AppType.bodyStrong,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.accountName,
                            style = AppType.caption,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!isHotp) {
                        CountdownLabel(remainingSeconds = item.remainingSeconds, period = item.period)
                    }
                }
                Spacer(Modifier.height(AppTokens.Spacing.m))
                if (isHotp) {
                    Text(
                        text = "此版本暂不生成 HOTP 验证码，账号信息会保留。",
                        style = AppType.body,
                        color = colors.inkSecondary,
                    )
                } else {
                    CodeText(
                        code = item.code,
                        digits = item.digits,
                        hidden = item.hidden,
                        style = AppType.codeDetail,
                        color = if (item.remainingSeconds <= 5) colors.warning else colors.ink,
                    )
                }
            }

            if (editing) {
                SectionHeader("编辑")
                AppTextField(value = issuer, onValueChange = { issuer = it }, label = "服务名")
                Spacer(Modifier.height(AppTokens.Spacing.l))
                AppTextField(value = account, onValueChange = { account = it }, label = "账号")
                Spacer(Modifier.height(AppTokens.Spacing.l))
                InkPrimaryButton(
                    text = "保存修改",
                    onClick = {
                        onSave(issuer, account)
                        editing = false
                    },
                )
                Spacer(Modifier.height(AppTokens.Spacing.s))
                InkSecondaryButton(text = "取消", onClick = { editing = false })
            } else {
                SectionHeader("账号信息")
                DetailRow(label = "服务名", value = item.issuer ?: "—")
                InkDivider()
                DetailRow(label = "账号", value = item.accountName)
                InkDivider()
                DetailRow(label = "类型", value = if (item.type == "hotp") "HOTP" else "TOTP")
                InkDivider()
                DetailRow(label = "算法", value = item.algorithm)
                InkDivider()
                DetailRow(label = "位数", value = "${item.digits} 位")
                if (!isHotp) {
                    InkDivider()
                    DetailRow(label = "周期", value = "${item.period} 秒")
                }

                SectionHeader("操作")
                InkPrimaryButton(text = "复制验证码", onClick = onCopy, enabled = !isHotp)
                Spacer(Modifier.height(AppTokens.Spacing.s))
                InkSecondaryButton(text = "向上移动", onClick = onMoveUp)
                Spacer(Modifier.height(AppTokens.Spacing.s))
                InkSecondaryButton(text = "向下移动", onClick = onMoveDown)
                Spacer(Modifier.height(AppTokens.Spacing.s))
                InkTextButton(
                    text = "删除此账号",
                    onClick = { confirmDelete = true },
                    color = colors.danger,
                )
            }
        }
    }

    if (confirmDelete) {
        InkConfirmDialog(
            title = "删除这个账号？",
            message = "删除后只能通过备份恢复。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = LocalInkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppTokens.Size.settingRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = AppType.body, color = colors.inkSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = AppType.body,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
