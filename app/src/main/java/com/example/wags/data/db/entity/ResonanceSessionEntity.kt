package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "resonance_sessions")
data class ResonanceSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val timestamp: Long,
    /** Breathing rate used during this session (BPM). */
    val breathingRateBpm: Float,
    /** I:E ratio used during this session. */
    val ieRatio: Float,
    /** Duration of the session in seconds. */
    val durationSeconds: Int,
    /** Total RR intervals collected. */
    val totalBeats: Int,
    /** Mean coherence ratio during the session. */
    val meanCoherenceRatio: Float,
    /** Max coherence ratio achieved. */
    val maxCoherenceRatio: Float,
    /** Time in high coherence zone (seconds). */
    val timeInHighCoherence: Int,
    /** Time in medium coherence zone (seconds). */
    val timeInMediumCoherence: Int,
    /** Time in low coherence zone (seconds). */
    val timeInLowCoherence: Int,
    /** Mean RMSSD during the session (ms). */
    val meanRmssdMs: Float,
    /** Mean SDNN during the session (ms). */
    val meanSdnnMs: Float,
    /** Artifact percentage (0-100). */
    val artifactPercent: Float,
    /** Total points earned. */
    val totalPoints: Float,
    /** JSON-encoded coherence history: array of float values. */
    @ColumnInfo(defaultValue = "")
    val coherenceHistoryJson: String = "",
    /** HR device identifier used. */
    @ColumnInfo(defaultValue = "NULL")
    val hrDeviceId: String? = null
)
