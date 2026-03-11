package com.example.wags.domain.model

enum class PrepType {
    NO_PREP,
    RESONANCE,
    HYPER;

    fun displayName(): String = when (this) {
        NO_PREP   -> "No Prep"
        RESONANCE -> "Resonance"
        HYPER     -> "Hyper"
    }
}
