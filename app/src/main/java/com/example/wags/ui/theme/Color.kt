package com.example.wags.ui.theme

import androidx.compose.ui.graphics.Color

// ── Monochrome greyscale palette ──────────────────────────────────────────────

// Backgrounds
val BackgroundDark   = Color(0xFF0A0A0A)   // near-black
val SurfaceDark      = Color(0xFF141414)   // dark surface
val SurfaceVariant   = Color(0xFF242424)   // slightly lighter surface

// Accent — formerly cyan, now light grey (high luminance)
val EcgCyan    = Color(0xFFD0D0D0)   // light grey (replaces cyan)
val EcgCyanDim = Color(0xFF707070)   // mid grey (replaces dim cyan)

// Status colors — all mapped to greyscale tones
val ReadinessGreen  = Color(0xFFE8E8E8)   // bright white-grey
val ReadinessOrange = Color(0xFFB0B0B0)   // mid-light grey
val ReadinessRed    = Color(0xFF888888)   // mid grey
val ReadinessBlue   = Color(0xFFB8B8B8)   // light-mid grey

// Coherence scale — greyscale steps
val CoherenceWhite  = Color(0xFFFFFFFF)
val CoherenceYellow = Color(0xFFD0D0D0)
val CoherencePink   = Color(0xFF909090)
val CoherenceBlue   = Color(0xFFB0B0B0)
val CoherenceGreen  = Color(0xFFE0E0E0)
val CoherenceOrange = Color(0xFFA0A0A0)
val CoherenceRed    = Color(0xFF707070)

// Text
val TextPrimary   = Color(0xFFE8E8E8)
val TextSecondary = Color(0xFF909090)
val TextDisabled  = Color(0xFF505050)

// Breathing pacer — two distinct grey tones for inhale/exhale
val PacerInhale = Color(0xFFD0D0D0)   // light grey (inhale)
val PacerExhale = Color(0xFF606060)   // dark grey (exhale)

// Apnea states — greyscale
val ApneaVentilation = Color(0xFFE0E0E0)
val ApneaHold        = Color(0xFF808080)
val ApneaRecovery    = Color(0xFFB0B0B0)

// Button backgrounds
val ButtonPrimary = Color(0xFF444444)
val ButtonDanger  = Color(0xFF333333)
val ButtonSuccess = Color(0xFF3A3A3A)
