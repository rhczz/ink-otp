package io.github.rhczz.inkotp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import io.github.rhczz.inkotp.ui.theme.AppTokens
import io.github.rhczz.inkotp.ui.theme.AppType
import io.github.rhczz.inkotp.ui.theme.AvatarPalette
import io.github.rhczz.inkotp.ui.theme.LocalInkDarkTheme

@Composable
fun IssuerAvatar(
    issuer: String?,
    modifier: Modifier = Modifier,
) {
    val colors = AvatarPalette.forIssuer(issuer, LocalInkDarkTheme.current)
    val letter = issuer?.trim()?.takeIf { it.isNotEmpty() }?.first()?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(AppTokens.Size.avatar)
            .clip(RoundedCornerShape(AppTokens.Radius.avatar))
            .background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = AppType.bodyStrong.copy(fontWeight = FontWeight.SemiBold),
            color = colors.content,
        )
    }
}
