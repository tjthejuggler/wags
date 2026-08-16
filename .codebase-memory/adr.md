# ADR: Hyper time-lock UX refinement (round 2)

Date: 2026-08-16

## Context
Round 1 shipped the HYPER prep-type time lock with a lock-days stepper on the apnea screen, days-since badges with filled SurfaceVariant backgrounds, and an 🔓 emoji when unlocked. User feedback: (a) the lock-length setting belongs in app Settings, not on the apnea screen; (b) badges should have a thin grey border like the chips themselves; (c) the "locked" appearance while unlocked was confusing — root cause: the 9sp 🔓 emoji is illegible and reads as a closed padlock.

## Decision
1. **Lock badge renders only while actually locked** (`remaining > 0`): 🔒 + remaining days in the lower-right of the HYPER chip. When unlocked, NO icon is shown at all. Verified against device DB (HYPER last used 19.2 days ago, lockDays=7 → remaining=0); the round-1 math was correct, the emoji was the problem.
2. **Lock-length setting moved to app Settings**: new "Apnea" card in SettingsScreen with the "Days required between Hyper uses" −/Nd/+ stepper. SettingsViewModel injects HyperLockManager and exposes hyperLockDays via a MutableStateFlow combined into SettingsUiState; setter writes prefs through HyperLockManager.setLockDays. The stepper and hyperLockDays field were removed from ApneaScreen/ApneaViewModel. Propagation to the apnea screen happens via ApneaScreen's ON_RESUME → refreshDrillParams(), which re-reads prefs, and via the observeLastUsedPerSetting() collector.
3. **Badge styling**: days-since and lock badges use `.border(1.dp, TextSecondary, RoundedCornerShape(4.dp))` (thin grey outline matching the FilterChip aesthetic) instead of a filled SurfaceVariant background.

## Consequences
- ApneaUiState no longer carries hyperLockDays; only hyperRemainingLockDays remains (computed from prefs + DB).
- Single source of truth for lock length stays SharedPreferences("apnea_prefs", key=hyper_lock_days) via HyperLockManager.
- Emoji-based lock icon remains acceptable only in the locked state (🔒 with number is legible); unlocked state is icon-free by design.
