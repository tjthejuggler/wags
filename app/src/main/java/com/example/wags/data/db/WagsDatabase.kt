package com.example.wags.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.wags.data.db.dao.*
import com.example.wags.data.db.entity.*

@Database(
    entities = [
        DailyReadingEntity::class,
        ApneaRecordEntity::class,
        SessionLogEntity::class,
        RfAssessmentEntity::class,
        AccCalibrationEntity::class,
        MorningReadinessEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class WagsDatabase : RoomDatabase() {
    abstract fun dailyReadingDao(): DailyReadingDao
    abstract fun apneaRecordDao(): ApneaRecordDao
    abstract fun sessionLogDao(): SessionLogDao
    abstract fun rfAssessmentDao(): RfAssessmentDao
    abstract fun accCalibrationDao(): AccCalibrationDao
    abstract fun morningReadinessDao(): MorningReadinessDao

    companion object {
        /**
         * v1 → v2: Make HR columns nullable and add monitorId to session_logs.
         * SQLite does not support ALTER COLUMN, so we add the new nullable monitorId column
         * and leave existing HR columns as-is (Room treats missing nullable columns as null).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session_logs ADD COLUMN monitorId TEXT")
                // Existing rows will have NULL for monitorId — correct behaviour.
                // HR columns were NOT NULL in v1; we recreate the table to make them nullable.
                db.execSQL("""
                    CREATE TABLE session_logs_new (
                        sessionId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        sessionType TEXT NOT NULL,
                        monitorId TEXT,
                        avgHrBpm REAL,
                        hrSlopeBpmPerMin REAL,
                        startRmssdMs REAL,
                        endRmssdMs REAL,
                        lnRmssdSlope REAL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO session_logs_new
                    SELECT sessionId, timestamp, durationMs, sessionType, NULL,
                           avgHrBpm, hrSlopeBpmPerMin, startRmssdMs, endRmssdMs, lnRmssdSlope
                    FROM session_logs
                """.trimIndent())
                db.execSQL("DROP TABLE session_logs")
                db.execSQL("ALTER TABLE session_logs_new RENAME TO session_logs")
            }
        }

        /**
         * v2 → v3: Add morning_readiness table for Morning Readiness feature.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE morning_readiness (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        supineRmssdMs REAL NOT NULL,
                        supineLnRmssd REAL NOT NULL,
                        supineSdnnMs REAL NOT NULL,
                        supineRhr INTEGER NOT NULL,
                        standingRmssdMs REAL NOT NULL,
                        standingLnRmssd REAL NOT NULL,
                        standingSdnnMs REAL NOT NULL,
                        peakStandHr INTEGER NOT NULL,
                        thirtyFifteenRatio REAL NULL,
                        ohrrAt20sPercent REAL NULL,
                        ohrrAt60sPercent REAL NULL,
                        respiratoryRateBpm REAL NULL,
                        slowBreathingFlagged INTEGER NOT NULL,
                        hooperSleep INTEGER NULL,
                        hooperFatigue INTEGER NULL,
                        hooperSoreness INTEGER NULL,
                        hooperStress INTEGER NULL,
                        hooperTotal REAL NULL,
                        artifactPercentSupine REAL NOT NULL,
                        artifactPercentStanding REAL NOT NULL,
                        readinessScore INTEGER NOT NULL,
                        readinessColor TEXT NOT NULL,
                        hrvBaseScore INTEGER NOT NULL,
                        orthoMultiplier REAL NOT NULL,
                        cvPenaltyApplied INTEGER NOT NULL,
                        rhrLimiterApplied INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
    }
}
