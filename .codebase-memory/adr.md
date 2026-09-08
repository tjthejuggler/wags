# ADR: Apnea trophy tier colouring

## Context
Apnea trophies are plain 🏆 emoji text previously rendered greyscale via Modifier.grayscale(). Requirement: colour every trophy group by its trophy count, using dull/greyed-out versions of tier colours.

## Decision
- Tier order (1–6): red, orange, green, blue, pink, yellow — hex values in trophyTierColor() in app/src/main/java/com/example/wags/ui/common/GrayscaleEmoji.kt.
- Modifier.trophyTint(count) applies a hand-built 4x5 ColorMatrix: rec.709 luminance per output channel scaled by normalised per-channel multipliers (avg=1, strength=0.85). Do NOT compose matrices with ColorMatrix.timesAssign() — multiplication-order ambiguity produced broken/neutral results.
- Applied at all trophy render sites: NewPersonalBestDialog, ApneaScreen cards + drill summaries, ApneaRecordDetailScreen trophies + PB badges, SectionHeader (trophyCount param, emoji override for ⚙️), FreeHoldActiveScreen live PB + next-PB countdown, RecordForecastDialog, PipResultCard (trophyCount param), history-screen Trophies tab icon (6 tiny yellow trophies, 9sp).
- Tuning knobs: hexes in trophyTierColor(), strength constant in trophyTint().