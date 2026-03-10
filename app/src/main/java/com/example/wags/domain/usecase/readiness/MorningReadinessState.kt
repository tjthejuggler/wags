package com.example.wags.domain.usecase.readiness

enum class MorningReadinessState {
    IDLE,           // Not started
    INIT,           // State 1: BLE validation, instruct user to lie down
    SUPINE_REST,    // State 2: 60s stabilization (data discarded)
    SUPINE_HRV,     // State 3: 120s supine recording
    STAND_PROMPT,   // State 4: Audio + haptic prompt to stand
    STAND_CAPTURE,  // State 5: 60s orthostatic capture (peak HR, beat 15/30)
    STAND_HRV,      // State 6: 120s standing HRV recording
    QUESTIONNAIRE,  // State 7: Hooper Index questionnaire
    CALCULATING,    // State 8: Processing all metrics
    COMPLETE,       // Final: results ready
    ERROR           // Terminal error state
}
