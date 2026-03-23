package com.example.wags.domain.model

enum class Posture {
    SITTING,
    LAYING;

    fun displayName(): String = when (this) {
        SITTING -> "Sitting"
        LAYING  -> "Laying"
    }
}
