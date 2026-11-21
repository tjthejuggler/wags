package com.example.wags.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apnea_sessions")
data class ApneaSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val timestamp: Long,                    // epoch ms
    val tableType: String,                  // "CO2", "O2", "PROGRESSIVE_O2", "MIN_BREATH", "WONKA_FIRST", "WONKA_ENDURANCE", "FREE"
    val tableVariant: String,               // "SHORT", "MEDIUM", "LONG"
    val tableParamsJson: String,            // JSON of the generated table steps
    val pbAtSessionMs: Long,                // user's PB at time of session
    val totalSessionDurationMs: Long,
    val contractionTimestampsJson: String,  // JSON array of Long timestamps
    val maxHrBpm: Int?,
    val lowestSpO2: Int?,
    val roundsCompleted: Int,
    val totalRounds: Int,
    /**
     * Human-readable identifier of the HR/SpO₂ device used during this session
     * (e.g. "Polar H10 (ABC123)", "Polar Verity (XYZ)", "Oximeter (AA:BB:CC:DD:EE:FF)").
     * Null when no device was connected.
     */
    @ColumnInfo(defaultValue = "NULL")
    val hrDeviceId: String? = null,
    /**
     * IANA zone the device was in when this session was saved. Frozen at save
     * time so the session's local calendar day never changes when the user
     * travels. Null on pre-fix rows; resolved by the one-time backfill or the
     * read-time fallback (see LocalTimeZones).
     */
    @ColumnInfo(defaultValue = "NULL")
    val zoneId: String? = null
)
