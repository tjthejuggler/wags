# ADR: Eucapnic config persistence + seed-or-mirror sync pattern

Date: 2026-08-16
Status: Accepted

## Context
Two bugs in apnea session screens with EUCAPNIC_DIAPHRAGMATIC prep type:
1. After completing eucapnic pacer prep in free hold flow, the START button still read "EUCAPNIC" and re-entered the pacer instead of switching to HOLD mode. The `eucapnic_prep_completed` flag set by EucapnicPacerScreen on `previousBackStackEntry.savedStateHandle` was consumed via `savedStateHandle.getStateFlow()` collector in FreeHoldActiveViewModel init — StateFlow propagation after popBackStack proved unreliable at runtime.
2. Eucapnic config reset to defaults after returning from pacer. Root causes: (a) EucapnicConfigViewModel kept config only in memory (`MutableStateFlow(EucapnicConfig())` = defaults per instance); (b) every session screen had an unconditional `LaunchedEffect(prepType, eucapnicConfig)` pushing ECVM config into the session VM on every recomposition, clobbering user-edited config with defaults when returning from the pacer.

## Decision
1. **EucapnicConfigViewModel** now persists config to SharedPreferences (`apnea_prefs`, keys prefixed `eucapnic_`) on every mutation via private `setConfig()`; restores on construction; exposes public `updateConfig()` for mirroring.
2. **Seed-or-mirror pattern** in all session screens (FreeHoldActiveScreen, MinBreathScreen, ProgressiveO2Screen, ApneaTableScreen, ProgressiveO2ActiveScreen): `LaunchedEffect` seeds session VM with ECVM config only when session VM has none; otherwise mirrors session VM config back to ECVM when they diverge. Data-class equality terminates the mirror loop.
3. **Deterministic result consumption**: FreeHoldActiveScreen reads `navController.currentBackStackEntry?.savedStateHandle?.get<Boolean>("eucapnic_prep_completed")` in a `LaunchedEffect(Unit)` instead of relying on StateFlow collection timing.
4. FreeHoldActiveScreen uses live `state.currentPrepType` (editable via settings dialog) rather than static `prepType` nav arg as the sync gate.

## Consequences
- Eucapnic config survives process death and navigation; user edits in session settings dialogs propagate back to the shared ECVM.
- Min Breath / Progressive O2 active screens auto-start sessions (no START-button flag needed); they only needed the config clobber fix.
- ProgressiveO2ActiveScreen previously had NO ECVM wiring despite having a settings dialog; it now participates in seed-or-mirror.
- Build requires JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 (Android Studio snap JBR is JDK 25, too new for Gradle 8.13).