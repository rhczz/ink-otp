package io.github.rhczz.inkotp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.SnackbarHostState
import io.github.rhczz.inkotp.R
import io.github.rhczz.inkotp.ui.components.EmptyState
import io.github.rhczz.inkotp.ui.components.InkIcon
import io.github.rhczz.inkotp.ui.components.InkIconButton
import io.github.rhczz.inkotp.ui.components.InkSnackbarHost
import io.github.rhczz.inkotp.ui.components.ScreenScaffold
import io.github.rhczz.inkotp.ui.components.SearchField
import io.github.rhczz.inkotp.ui.components.VaultCodeCard
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import io.github.rhczz.inkotp.ui.model.VaultListUiState
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.LocalInkColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VaultListScreen(
    state: VaultListUiState,
    onQueryChange: (String) -> Unit,
    onOpenItem: (VaultItemUi) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onCopy: (VaultItemUi) -> Unit = {},
    onLock: () -> Unit = {},
) {
    val colors = LocalInkColors.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var searching by remember { mutableStateOf(false) }
    var copiedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedId) {
        if (copiedId != null) {
            delay(AppTokens.Motion.HIGHLIGHT_MS.toLong())
            copiedId = null
        }
    }

    val visibleItems = remember(state.items, state.query) {
        val query = state.query.trim()
        if (query.isEmpty()) {
            state.items
        } else {
            state.items.filter {
                it.issuer?.contains(query, ignoreCase = true) == true ||
                    it.accountName.contains(query, ignoreCase = true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenScaffold(
            title = "验证码",
            actions = {
                InkIconButton(icon = R.drawable.ic_lock, contentDescription = "立即锁定", onClick = onLock, tint = colors.ink)
                InkIconButton(
                    icon = R.drawable.ic_search,
                    contentDescription = "搜索",
                    onClick = { searching = !searching },
                    tint = colors.ink,
                )
                InkIconButton(
                    icon = R.drawable.ic_settings,
                    contentDescription = "设置",
                    onClick = onSettings,
                    tint = colors.ink,
                )
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (searching) {
                    SearchField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.padding(top = AppTokens.Spacing.m),
                    )
                }
                if (state.backupReminder) {
                    ReminderBanner(
                        text = "还没备份 · 换机或重装将丢失",
                        action = "去设置",
                        onClick = onSettings,
                        modifier = Modifier.padding(top = AppTokens.Spacing.m),
                    )
                }
                if (state.clockWarning) {
                    ReminderBanner(
                        text = "设备时间可能不正确，验证码可能对不上",
                        action = "诊断",
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.padding(top = AppTokens.Spacing.m),
                    )
                }
                if (state.passwordWarning) {
                    ReminderBanner(
                        text = "未设置恢复口令 · 数据无法恢复",
                        action = "设置",
                        onClick = onSettings,
                        modifier = Modifier.padding(top = AppTokens.Spacing.m),
                    )
                }
                if (visibleItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = if (state.query.isBlank()) R.drawable.ic_shield else R.drawable.ic_search,
                            title = if (state.query.isBlank()) "还没有账号" else "没有匹配的账号",
                            body = if (state.query.isBlank()) {
                                "扫描服务提供的二维码，或手动输入密钥。"
                            } else {
                                "换个关键词试试，或清除搜索。"
                            },
                            primaryText = if (state.query.isBlank()) "添加账号" else null,
                            onPrimary = if (state.query.isBlank()) onAdd else null,
                            secondaryText = if (state.query.isBlank()) "手动添加" else "清除搜索",
                            onSecondary = if (state.query.isBlank()) onAdd else ({ onQueryChange("") }),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = AppTokens.Spacing.l,
                            bottom = AppTokens.Size.bottomContentPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.m),
                    ) {
                        items(items = visibleItems, key = { it.id }) { item ->
                            VaultCodeCard(
                                item = item,
                                copied = copiedId == item.id,
                                onClick = {
                                    if (item.type != "totp") return@VaultCodeCard
                                    onCopy(item)
                                    copiedId = item.id
                                    scope.launch {
                                        snackbarHostState.showSnackbar("已复制 · 剩余 ${item.remainingSeconds} 秒")
                                    }
                                },
                                onLongClick = { onOpenItem(item) },
                                onMenu = { onOpenItem(item) },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = AppTokens.Spacing.xl, bottom = AppTokens.Spacing.xxl),
            shape = RoundedCornerShape(AppTokens.Radius.card),
            containerColor = colors.ink,
            contentColor = colors.surface,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = AppTokens.Elevation.flat,
                pressedElevation = AppTokens.Elevation.pressed,
                focusedElevation = AppTokens.Elevation.flat,
                hoveredElevation = AppTokens.Elevation.pressed,
            ),
        ) {
            InkIcon(R.drawable.ic_add, contentDescription = "添加账号", tint = colors.surface)
        }

        InkSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ReminderBanner(
    text: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalInkColors.current
    val shape = RoundedCornerShape(AppTokens.Radius.control)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(AppTokens.Border.hairline, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTokens.Spacing.m, vertical = AppTokens.Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkIcon(R.drawable.ic_info, contentDescription = null, size = AppTokens.IconSize.inline, tint = colors.inkSecondary)
        Spacer(Modifier.width(AppTokens.Spacing.s))
        Text(
            text = text,
            style = AppType.caption,
            color = colors.inkSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(AppTokens.Spacing.s))
        Text(
            text = action,
            style = AppType.label,
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(AppTokens.Radius.avatar))
                .background(colors.accentSoft)
                .padding(horizontal = AppTokens.Spacing.m, vertical = AppTokens.Spacing.xs),
        )
    }
}
