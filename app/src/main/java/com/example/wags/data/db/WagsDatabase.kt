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
        MorningReadinessEntity::class,
        ApneaSessionEntity::class,
        ContractionEntity::class,
        TelemetryEntity::class,
        FreeHoldTelemetryEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class WagsDatabase : RoomDatabase() {
    abstract fun dailyReadingDao(): DailyReadingDao
    abstract fun apneaRecordDao(): ApneaRecordDao
    abstract fun sessionLogDao(): SessionLogDao
    abstract fun rfAssessmentDao(): RfAssessmentDao
    abstract fun accCalibrationDao(): AccCalibrationDao
    abstract fun morningReadinessDao(): MorningReadinessDao
    abstract fun apneaSessionDao(): ApneaSessionDao
    abstract fun contractionDao(): ContractionDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun freeHoldTelemetryDao(): FreeHoldTelemetryDao

    companion object {
        /**
         * v1 → v2: Make HR columns nullable and add monitorId to session_logs.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session_logs ADD COLUMN monitorId TEXT")
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

        /**
         * v3 → v4:
         * - Creates rf_assessments (was missing from prior migrations — fixes schema mismatch crash)
         * - Creates acc_calibrations (was missing from prior migrations — fixes schema mismatch crash)
         * - Creates apnea_sessions (new)
         * - Creates contractions (new, FK → apnea_sessions)
         * - Creates telemetry (new, FK → apnea_sessions)
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `rf_assessments` (
                        `assessmentId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `protocolType` TEXT NOT NULL,
                        `optimalBpm` REAL NOT NULL,
                        `optimalIeRatio` REAL NOT NULL,
                        `compositeScore` REAL NOT NULL,
                        `isValid` INTEGER NOT NULL,
                        `leaderboardJson` TEXT NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `acc_calibrations` (
                        `calibrationId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `posture` TEXT NOT NULL,
                        `withHold` INTEGER NOT NULL,
                        `inhaleDeltaThreshold` REAL NOT NULL,
                        `exhaleDeltaThreshold` REAL NOT NULL,
                        `holdDebounceCount` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `apnea_sessions` (
                        `sessionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `tableType` TEXT NOT NULL,
                        `tableVariant` TEXT NOT NULL,
                        `tableParamsJson` TEXT NOT NULL,
                        `pbAtSessionMs` INTEGER NOT NULL,
                        `totalSessionDurationMs` INTEGER NOT NULL,
                        `contractionTimestampsJson` TEXT NOT NULL,
                        `maxHrBpm` INTEGER,
                        `lowestSpO2` INTEGER,
                        `roundsCompleted` INTEGER NOT NULL,
                        `totalRounds` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `contractions` (
                        `contractionId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `roundNumber` INTEGER NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `elapsedInRoundMs` INTEGER NOT NULL,
                        `phase` TEXT NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `apnea_sessions`(`sessionId`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contractions_sessionId` ON `contractions` (`sessionId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `telemetry` (
                        `telemetryId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `spO2` INTEGER,
                        `heartRateBpm` INTEGER,
                        `source` TEXT NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `apnea_sessions`(`sessionId`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_telemetry_sessionId` ON `telemetry` (`sessionId`)")
            }
        }

        /**
         * v4 → v5: Add accBreathingUsed column to rf_assessments.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN accBreathingUsed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6: Replace hyperventilationPrep (INTEGER/BOOLEAN) with prepType (TEXT) in apnea_records.
         * Old rows with hyperventilationPrep = 1 become HYPER; 0 becomes NO_PREP.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create replacement table with prepType column
                db.execSQL("""
                    CREATE TABLE apnea_records_new (
                        recordId   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp  INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        lungVolume TEXT    NOT NULL,
                        prepType   TEXT    NOT NULL,
                        minHrBpm   REAL    NOT NULL,
                        maxHrBpm   REAL    NOT NULL,
                        tableType  TEXT
                    )
                """.trimIndent())
                // 2. Copy rows, mapping old boolean to enum name
                db.execSQL("""
                    INSERT INTO apnea_records_new
                        (recordId, timestamp, durationMs, lungVolume, prepType, minHrBpm, maxHrBpm, tableType)
                    SELECT
                        recordId, timestamp, durationMs, lungVolume,
                        CASE WHEN hyperventilationPrep = 1 THEN 'HYPER' ELSE 'NO_PREP' END,
                        minHrBpm, maxHrBpm, tableType
                    FROM apnea_records
                """.trimIndent())
                // 3. Swap tables
                db.execSQL("DROP TABLE apnea_records")
                db.execSQL("ALTER TABLE apnea_records_new RENAME TO apnea_records")
            }
        }

        /**
         * v6 → v7: Add free_hold_telemetry table for per-sample HR/SpO2 during free holds.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `free_hold_telemetry` (
                        `id`           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recordId`     INTEGER NOT NULL,
                        `timestampMs`  INTEGER NOT NULL,
                        `heartRateBpm` INTEGER,
                        `spO2`         INTEGER,
                        FOREIGN KEY(`recordId`) REFERENCES `apnea_records`(`recordId`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_free_hold_telemetry_recordId` ON `free_hold_telemetry` (`recordId`)")
            }
        }

        /**
         * v7 → v8: Add lowestSpO2 column to apnea_records.
         * Populated from oximeter data when a pulse oximeter is connected during a free hold.
         * NULL for older records that pre-date oximeter support.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apnea_records ADD COLUMN lowestSpO2 INTEGER DEFAULT NULL")
            }
        }

        /**
         * v8 → v9: Add timeOfDay column to apnea_records.
         * Existing records default to 'DAY' so they remain accessible under the Day setting.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apnea_records ADD COLUMN timeOfDay TEXT NOT NULL DEFAULT 'DAY'")
            }
        }
    }
}
