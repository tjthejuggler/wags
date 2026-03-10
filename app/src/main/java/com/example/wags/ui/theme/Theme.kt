package com.example.wags.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WagsDarkColorScheme = darkColorScheme(
    primary = ButtonPrimary,
    onPrimary = Color.White,
    primaryContainer = EcgCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = ButtonSuccess,
    onSecondary = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = ButtonDanger,
    onError = Color.White,
    outline = Color.White,
    outlineVariant = TextSecondary
)

@Composable
fun WagsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WagsDarkColorScheme,
        typography = WagsTypography,
        content = content
    )
}
