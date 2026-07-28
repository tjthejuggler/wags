## ADR: Eucapnic Pacer Renderer — Active Session UI

**Date:** 2026-07-28

**Context:**
The EUCAPNIC_DIAPHRAGMATIC prep type needed an active pacing renderer to guide the user through diaphragmatic breathing during apnea preparation. The domain layer (EucapnicPacerEngine, EucapnicConfig, PacerState, EucapnicPhase) was already implemented. The UI layer needed three new components.

**Decision:**
Created three new files in `ui/apnea/`, following the established patterns from BreathingPacerCircle and BreathingViewModel:

1. **EucapnicPacerGauge** — Canvas-based composable rendering an expanding/contracting circle. Supports 4 phases (INHALE/TOP_PAUSE/EXHALE/BOTTOM_PAUSE) with depth scaling (15–50% breathDepthPercent mapped to 0.30–1.00 radius scale). Uses the same greyscale palette (PacerInhale/PacerExhale) as the resonance pacer.

2. **EucapnicPacerViewModel** — @HiltViewModel wrapping EucapnicPacerEngine. Runs a ~60 FPS tick loop via viewModelScope. Exposes StateFlows for phase, radius, remaining time, breath count, BPM. Handles pause/resume for lifecycle events. Fires WagsFeedback haptics on phase transitions and sessionEnd chime on completion.

3. **EucapnicPacerScreen** — Full-screen composable with info bar (remaining time, breaths, BPM), gauge, phase indicator chip, linear progress bar, and time text. Uses LockPortrait, KeepScreenOn, SessionBackHandler from SessionGuards. Observes ON_PAUSE/ON_RESUME lifecycle events to pause/resume the pacer.

**Consequences:**
- Purely additive: no existing files modified.
- Not yet wired into navigation or apnea session flow — integration is a separate task.
- The gauge depth scaling provides visual feedback on target breath depth.
- Haptic patterns reuse existing WagsFeedback methods (breathInhale/breathExhale/sessionEnd).

**Alternatives considered:**
- Reusing BreathingPacerCircle directly: rejected because it only supports 2 phases (inhale/exhale) and lacks depth scaling.
- Using a progress arc instead of a circle: rejected to maintain visual consistency with the existing resonance pacer.