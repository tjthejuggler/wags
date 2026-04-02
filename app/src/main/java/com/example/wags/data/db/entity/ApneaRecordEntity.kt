package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apnea_records")
data class ApneaRecordEntity(
    @PrimaryKey(autoGenerate = true) val recordId: Long = 0,
    val timestamp: Long,
    val durationMs: Long,
    val lungVolume: String,
    /** One of PrepType enum names: NO_PREP, RESONANCE, HYPER */
    val prepType: String,
    val minHrBpm: Float,
    val maxHrBpm: Float,
    val tableType: String?,
    /** Lowest SpO₂ percentage recorded during this hold (null if no oximeter was connected). */
    @ColumnInfo(defaultValue = "NULL")
    val lowestSpO2: Int? = null,
    /** One of TimeOfDay enum names: MORNING, DAY, NIGHT */
    @ColumnInfo(defaultValue = "DAY")
    val timeOfDay: String = "DAY",
    /**
     * Elapsed milliseconds from hold start to the user's first diaphragm contraction.
     * Null when the user did not tap the First Contraction button (or for older records).
     */
    @ColumnInfo(defaultValue = "NULL")
    val firstContractionMs: Long? = null,
    /**
     * Human-readable identifier of the HR/SpO₂ device used during this hold
     * (e.g. "Polar H10 (ABC123)", "Polar Verity (XYZ)", "Oximeter (AA:BB:CC:DD:EE:FF)").
     * Null when no device was connected.
     */
    @ColumnInfo(defaultValue = "NULL")
    val hrDeviceId: String? = null,
    /** One of Posture enum names: SITTING, LAYING */
    @ColumnInfo(defaultValue = "LAYING")
    val posture: String = "LAYING",
    /** One of AudioSetting enum names: SILENCE, MUSIC, MOVIE */
    @ColumnInfo(defaultValue = "SILENCE")
    val audio: String = "SILENCE",
    /** True when the user used guided hyperventilation before this hold. */
    @ColumnInfo(defaultValue = "0")
    val guidedHyper: Boolean = false,
    /** Relaxed exhale phase duration in seconds (guided hyperventilation). Null when not used. */
    @ColumnInfo(defaultValue = "NULL")
    val guidedRelaxedExhaleSec: Int? = null,
    /** Purge exhale phase duration in seconds (guided hyperventilation). Null when not used. */
    @ColumnInfo(defaultValue = "NULL")
    val guidedPurgeExhaleSec: Int? = null,
    /** Transition phase duration in seconds (guided hyperventilation). Null when not used. */
    @ColumnInfo(defaultValue = "NULL")
    val guidedTransitionSec: Int? = null
)
