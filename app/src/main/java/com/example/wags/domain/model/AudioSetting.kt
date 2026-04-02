package com.example.wags.domain.model

enum class AudioSetting {
    SILENCE,
    MUSIC,
    MOVIE;

    fun displayName(): String = when (this) {
        SILENCE -> "Silence"
        MUSIC   -> "Music"
        MOVIE   -> "Movie"
    }
}
