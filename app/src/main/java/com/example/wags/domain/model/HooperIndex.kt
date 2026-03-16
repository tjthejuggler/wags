package com.example.wags.domain.model

/**
 * Hooper Wellness Index.
 *
 * Each dimension is now a continuous Float in [1.0, 5.0] (smooth gradient slider),
 * where 1 = worst and 5 = best.  The total therefore ranges from 4.0 (worst) to
 * 20.0 (best).  Stored in the DB as Float columns (existing Int columns are
 * backward-compatible because Kotlin rounds on read).
 */
data class HooperIndex(
    val sleep: Float,      // 1=Poor … 5=Excellent
    val fatigue: Float,    // 1=Very fatigued … 5=No fatigue
    val soreness: Float,   // 1=Very sore … 5=No soreness
    val stress: Float      // 1=Very stressed … 5=No stress
) {
    val total: Float get() = sleep + fatigue + soreness + stress
    val isLow: Boolean get() = total <= 10f   // ≤10 out of 20 = poor subjective state
    val isHigh: Boolean get() = total >= 16f  // ≥16 out of 20 = good subjective state
}
