package com.example.wags.domain.usecase.readiness

enum class MorningReadinessState {
    IDLE,           // Not started
    INIT,           // State 1: BLE validation, instruct user to lie down
    SUPINE_HRV,     // State 2: 120s supine recording (raw data collection)
    STAND_PROMPT,   // State 3: Audio + haptic prompt to stand
    STANDING,       // State 4: 120s standing recording (peak HR + all standing data)
    QUESTIONNAIRE,  // State 5: Hooper Index questionnaire
    CALCULATING,    // State 6: Processing all metrics from raw data
    COMPLETE,       // Final: results ready
    ERROR           // Terminal error state
}
