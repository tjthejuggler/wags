package com.example.wags.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WagsDarkColorScheme = darkColorScheme(
    primary = EcgCyan,
    onPrimary = BackgroundDark,
    primaryContainer = EcgCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = PacerInhale,
    onSecondary = BackgroundDark,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = ReadinessRed,
    onError = Color.White
)

@Composable
fun WagsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WagsDarkColorScheme,
        typography = WagsTypography,
        content = content
    )
}
