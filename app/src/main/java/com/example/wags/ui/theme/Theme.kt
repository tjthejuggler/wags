package com.example.wags.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WagsDarkColorScheme = darkColorScheme(
    primary = ButtonPrimary,
    onPrimary = TextPrimary,
    primaryContainer = SurfaceVariant,
    onPrimaryContainer = TextPrimary,
    secondary = ButtonSuccess,
    onSecondary = TextPrimary,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = ButtonDanger,
    onError = TextPrimary,
    outline = Color(0xFF606060),
    outlineVariant = TextDisabled
)

@Composable
fun WagsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WagsDarkColorScheme,
        typography = WagsTypography,
        content = content
    )
}
