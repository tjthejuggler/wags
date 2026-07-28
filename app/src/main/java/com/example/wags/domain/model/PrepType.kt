package com.example.wags.domain.model

enum class PrepType {
    NO_PREP,
    RESONANCE,
    HYPER,
    EUCAPNIC_DIAPHRAGMATIC;

    fun displayName(): String = when (this) {
        NO_PREP               -> "No Prep"
        RESONANCE             -> "Resonance"
        HYPER                 -> "Hyper"
        EUCAPNIC_DIAPHRAGMATIC -> "Eucapnic Diaphragmatic"
    }

    fun shortDisplayName(): String = when (this) {
        NO_PREP               -> "No Prep"
        RESONANCE             -> "Resonance"
        HYPER                 -> "Hyper"
        EUCAPNIC_DIAPHRAGMATIC -> "Eucapnic"
    }
}
