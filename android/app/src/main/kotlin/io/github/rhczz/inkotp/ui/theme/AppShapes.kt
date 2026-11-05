package io.github.rhczz.inkotp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(AppTokens.Radius.avatar),
    small = RoundedCornerShape(AppTokens.Radius.control),
    medium = RoundedCornerShape(AppTokens.Radius.card),
    large = RoundedCornerShape(AppTokens.Radius.sheet),
    extraLarge = RoundedCornerShape(AppTokens.Radius.sheet),
)
