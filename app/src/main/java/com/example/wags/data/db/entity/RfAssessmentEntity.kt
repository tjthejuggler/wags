package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rf_assessments")
data class RfAssessmentEntity(
    @PrimaryKey(autoGenerate = true) val assessmentId: Long = 0,
    val timestamp: Long,
    val protocolType: String,
    val optimalBpm: Float,
    val optimalIeRatio: Float,
    val compositeScore: Float,
    val isValid: Boolean,
    val leaderboardJson: String,
    @ColumnInfo(name = "accBreathingUsed", defaultValue = "0")
    val accBreathingUsed: Boolean = false,
    /**
     * Human-readable identifier of the HR device used during this assessment
     * (e.g. "Polar H10 (ABC123)", "Polar Verity (XYZ)").
     * Null when no device was connected.
     */
    @ColumnInfo(defaultValue = "NULL")
    val hrDeviceId: String? = null,

    // ── v16 columns: richer assessment data ──────────────────────────────────

    /** Peak-to-trough HR amplitude at resonance (BPM). */
    @ColumnInfo(defaultValue = "0")
    val peakToTroughBpm: Float = 0f,

    /** Maximum absolute LF power at resonance (ms²/Hz). */
    @ColumnInfo(defaultValue = "0")
    val maxLfPowerMs2: Float = 0f,

    /** Maximum coherence ratio achieved at resonance. */
    @ColumnInfo(defaultValue = "0")
    val maxCoherenceRatio: Float = 0f,

    /** Mean RMSSD during the assessment (ms). */
    @ColumnInfo(defaultValue = "0")
    val meanRmssdMs: Float = 0f,

    /** Mean SDNN during the assessment (ms). */
    @ColumnInfo(defaultValue = "0")
    val meanSdnnMs: Float = 0f,

    /** Duration of the assessment in seconds. */
    @ColumnInfo(defaultValue = "0")
    val durationSeconds: Int = 0,

    /** Total RR intervals collected. */
    @ColumnInfo(defaultValue = "0")
    val totalBeats: Int = 0,

    /** Artifact percentage (0-100). */
    @ColumnInfo(defaultValue = "0")
    val artifactPercent: Float = 0f,

    /**
     * JSON-encoded resonance curve data: array of {bpm, lfPower, ptAmp, coherence, rmssd}.
     * Used to render the resonance curve graph on the result screen.
     */
    @ColumnInfo(defaultValue = "")
    val resonanceCurveJson: String = "",

    /**
     * JSON-encoded HR waveform snapshot at resonance: array of {timeMs, hrBpm}.
     * 60-second epoch of instantaneous HR at the moment of peak resonance.
     */
    @ColumnInfo(defaultValue = "")
    val hrWaveformJson: String = "",

    /**
     * JSON-encoded power spectrum at resonance: array of {freqHz, powerMs2}.
     * Used to render the power spectrum overlay on the result screen.
     */
    @ColumnInfo(defaultValue = "")
    val powerSpectrumJson: String = ""
)
