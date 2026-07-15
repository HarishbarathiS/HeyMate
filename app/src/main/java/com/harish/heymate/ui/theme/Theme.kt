package com.harish.heymate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// HeyMate visual language: pure black canvas, white type, nothing extra.
val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)
val Surface = Color(0xFF0D0D0D)
val SurfaceHigh = Color(0xFF161616)
val Outline = Color(0xFF2A2A2A)
val TextSecondary = Color(0xFF9A9A9A)
val Success = Color(0xFF7CE38B)
val Danger = Color(0xFFFF6B6B)

private val HeyMateColors = darkColorScheme(
    primary = White,
    onPrimary = Black,
    secondary = TextSecondary,
    onSecondary = Black,
    background = Black,
    onBackground = White,
    surface = Surface,
    onSurface = White,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = Danger,
)

@Composable
fun HeyMateTheme(content: @Composable () -> Unit) {
    // Always dark — the app's identity is black-on-black minimalism.
    MaterialTheme(
        colorScheme = HeyMateColors,
        typography = HeyMateTypography,
        content = content,
    )
}
