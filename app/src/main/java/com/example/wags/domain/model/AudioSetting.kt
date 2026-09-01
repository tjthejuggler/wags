package com.example.wags.domain.model

enum class AudioSetting {
    SILENCE,
    MUSIC,
    MOVIE,
    GUIDED,
    /**
     * Real-time biofeedback sonification: live HR drives a selectable
     * instrument strike per heartbeat, and live SpO2 shapes a peaceful
     * background texture. The chosen instrument/texture is recorded on
     * each apnea record (biofeedbackHrSound / biofeedbackSpo2Texture).
     */
    BIOFEEDBACK;

    fun displayName(): String = when (this) {
        SILENCE     -> "Silence"
        MUSIC       -> "Music"
        MOVIE       -> "Movie"
        GUIDED      -> "Guided"
        BIOFEEDBACK -> "Biofeedback"
    }
}
