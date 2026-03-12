package com.example.wags.ui.apnea

// Help text constants for ApneaScreen info bubbles

internal const val CO2_HELP_TITLE = "CO₂ Tolerance Training (Hypercapnia)"
internal const val CO2_HELP_TEXT = """Purpose: Trains your body to tolerate rising CO₂ levels — the primary trigger for the urge to breathe.

Formula:
• Hold Time: T_hold = T_PB × hold%
• Initial Rest: R₁ = T_hold
• Min Rest: R_min (set by Difficulty)
• Rest Decrement: ΔR = (R₁ - R_min) / (N - 1)
• Round n Rest: R_n = R₁ - ((n-1) × ΔR)

Variables:
• T_PB = Your Personal Best in seconds
• N = Total rounds (set by Session Length)
• R_n = Rest duration for round n

Effect: Fixed hold time with progressively shorter rests forces your body to clear CO₂ less efficiently, building tolerance."""

internal const val O2_HELP_TITLE = "O₂ Deprivation Training (Hypoxia)"
internal const val O2_HELP_TEXT = """Purpose: Trains your body to function at lower oxygen levels — extends your aerobic capacity.

Formula:
• Rest Time: T_rest = fixed (120–150s, set by Difficulty)
• First Hold: H₁ = T_PB × 40%
• Max Hold: H_max = T_PB × max% (set by Difficulty)
• Hold Increment: ΔH = (H_max - H₁) / (N - 1)
• Round n Hold: H_n = H₁ + ((n-1) × ΔH)

Variables:
• T_PB = Your Personal Best in seconds
• N = Total rounds (set by Session Length)
• H_n = Hold duration for round n

Effect: Fixed rest with progressively longer holds pushes your oxygen limits safely."""

internal const val PB_HELP_TITLE = "Personal Best (PB)"
internal const val PB_HELP_CONTENT = """
Your longest breath-hold time. Used as the baseline for all table calculations.

All training tables are calculated as percentages of your PB:
• CO₂ Hold: T_hold = T_PB × 40–55% (set by Difficulty)
• O₂ First Hold: H₁ = T_PB × 40%
• O₂ Max Hold: H_max = T_PB × 70–85% (set by Difficulty)

Why percentages? Training at 40–85% of PB keeps you in the aerobic training zone, building adaptation without dangerous hypoxia.

Update your PB after each successful personal record attempt.
"""

internal const val LENGTH_DIFFICULTY_HELP_TITLE = "Session Length & Difficulty"
internal const val LENGTH_DIFFICULTY_HELP_CONTENT = """
Two independent axes control your training session:

SESSION LENGTH (number of rounds):
• Short (4): Quick session — good for warm-up or time-limited training
• Medium (8): Standard training session
• Long (12): Full volume session for peak training weeks

DIFFICULTY (PB percentage intensity):
• Easy: Conservative percentages — good for beginners or recovery days
  CO₂: 40% PB hold | Min Rest: 30s | O₂: 70% PB max hold
• Medium: Standard training percentages
  CO₂: 50% PB hold | Min Rest: 15s | O₂: 80% PB max hold
• Hard: Aggressive percentages — advanced athletes only
  CO₂: 55% PB hold | Min Rest: 10s | O₂: 85% PB max hold

Combine freely: e.g., "Long Easy" = 12 rounds at conservative intensity.
"""

internal const val PROGRESSIVE_O2_HELP_TITLE = "Progressive O₂ Training"
internal const val PROGRESSIVE_O2_HELP_TEXT = """Purpose: Simultaneously increases both hold and rest duration each round, building aerobic base and mental adaptation.

Formula:
• Hold_n = (30 + (n-1) × 15) seconds
• Rest_n = Hold_n (equal to hold)

Variables:
• n = Round number (1-indexed)
• Number of rounds set by Session Length (4 / 8 / 12)

Effect: Both hold and rest grow together, preventing excessive CO₂ buildup while extending hypoxic exposure progressively."""

internal const val MIN_BREATH_HELP_TITLE = "Minimum Breath (One-Breath) Training"
internal const val MIN_BREATH_HELP_TEXT = """Purpose: Removes the fixed rest timer — you control recovery by signaling when you've taken exactly one breath.

Logic:
• Hold ends → app waits indefinitely
• You take one full exhale + inhale
• Tap "One Breath Taken" → next hold begins immediately

Effect: Trains breath efficiency and mental readiness. Forces you to commit to the next hold with minimal recovery."""

internal const val WONKA_HELP_TITLE = "Wonka Tables (Contraction-Driven)"
internal const val WONKA_HELP_TEXT = """Purpose: Uses your body's own contraction signals as the training trigger, building awareness of your physiological limits.

Mode 1 — Till First Contraction:
• Timer counts up until you log your first contraction
• Round ends immediately at first contraction
• Trains you to identify your "cruising phase"

Mode 2 — Endurance (+X seconds):
• Timer counts up until first contraction (T_cruise)
• Then counts down X seconds of "struggle phase"
• Total Hold = T_cruise + X
• Formula: T_total = T_cruise + ΔT_endurance

Variables:
• T_cruise = Time from start to first contraction
• ΔT_endurance = User-defined endurance delta (default 45s)"""

internal const val TABLE_TRAINING_HELP_TITLE = "Table Training"
internal const val TABLE_TRAINING_HELP_TEXT = """Tables are structured breath-hold protocols that systematically stress either your CO₂ tolerance or O₂ capacity.

WHY TABLES?
Unstructured free holds improve willpower but plateau quickly. Tables apply progressive overload — the same principle used in strength training — to drive measurable physiological adaptation.

CO₂ TABLES (Hypercapnia):
• Fixed hold time, progressively shorter rests
• Forces your body to clear CO₂ less efficiently each round
• Builds tolerance to the urge-to-breathe signal
• Best for: extending your comfortable hold time

O₂ TABLES (Hypoxia):
• Fixed long rest, progressively longer holds
• Pushes your oxygen depletion limits safely
• Stimulates erythropoietin (EPO) production over time
• Best for: extending your maximum hold time

HOW TO USE:
1. Set your Personal Best (PB) — your longest comfortable hold
2. Choose Session Length (4 / 8 / 12 rounds)
3. Choose Difficulty (Easy / Medium / Hard)
4. All hold and rest durations are auto-calculated as % of your PB

Consistency over intensity: 3–4 table sessions per week outperforms occasional maximal efforts."""
