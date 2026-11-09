package com.fitzy.flowstate.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Cinema Dark palette. Single source of truth; screens must not hardcode hex values. */
object FlowColors {
    val Background = Color(0xFF0C0D10)
    val Surface = Color(0xFF1A1C22)
    val Accent = Color(0xFFE9A23B)
    val UserScore = Color(0xFF4ADE80)
    val ImdbBadge = Color(0xFFF5C518)
    val OnDark = Color(0xFFE8EBF2)
}

private val CinemaDarkScheme = darkColorScheme(
    primary = FlowColors.Accent,
    onPrimary = FlowColors.Background,
    background = FlowColors.Background,
    onBackground = FlowColors.OnDark,
    surface = FlowColors.Surface,
    onSurface = FlowColors.OnDark,
)

@Composable
fun FlowStateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CinemaDarkScheme, content = content)
}
