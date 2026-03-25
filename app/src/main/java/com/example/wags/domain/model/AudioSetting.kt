package com.example.wags.domain.model

enum class AudioSetting {
    SILENCE,
    MUSIC;

    fun displayName(): String = when (this) {
        SILENCE -> "Silence"
        MUSIC   -> "Music"
    }
}
