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
        FreeHoldTelemetryEntity::class,
        MeditationAudioEntity::class,
        MeditationSessionEntity::class,
        MorningReadinessTelemetryEntity::class
    ],
    version = 18,
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
    abstract fun meditationAudioDao(): MeditationAudioDao
    abstract fun meditationSessionDao(): MeditationSessionDao
    abstract fun morningReadinessTelemetryDao(): MorningReadinessTelemetryDao

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

        /**
         * v9 → v10: Add firstContractionMs column to apnea_records.
         * Stores the elapsed milliseconds from hold start to the user's first diaphragm
         * contraction tap. NULL for older records and holds where the button was never tapped.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apnea_records ADD COLUMN firstContractionMs INTEGER DEFAULT NULL")
            }
        }

        /**
         * v10 → v11: Add hrDeviceId column to every table that records HR or SpO₂ data.
         * Stores a human-readable label for the device used (e.g. "Polar H10 · ABC123").
         * NULL for older records where no device info was captured.
         *
         * Tables updated:
         *   - apnea_records
         *   - apnea_sessions
         *   - morning_readiness
         *   - daily_readings
         *   - rf_assessments
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apnea_records      ADD COLUMN hrDeviceId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE apnea_sessions     ADD COLUMN hrDeviceId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE morning_readiness  ADD COLUMN hrDeviceId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE daily_readings     ADD COLUMN hrDeviceId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE rf_assessments     ADD COLUMN hrDeviceId TEXT DEFAULT NULL")
            }
        }

        /**
         * v11 → v12: Add meditation_audios and meditation_sessions tables.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `meditation_audios` (
                        `audioId`   INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fileName`  TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL DEFAULT '',
                        `isNone`    INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `meditation_sessions` (
                        `sessionId`         INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `audioId`           INTEGER,
                        `timestamp`         INTEGER NOT NULL,
                        `durationMs`        INTEGER NOT NULL,
                        `monitorId`         TEXT,
                        `avgHrBpm`          REAL,
                        `hrSlopeBpmPerMin`  REAL,
                        `startRmssdMs`      REAL,
                        `endRmssdMs`        REAL,
                        `lnRmssdSlope`      REAL,
                        FOREIGN KEY(`audioId`) REFERENCES `meditation_audios`(`audioId`) ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_meditation_sessions_audioId` ON `meditation_sessions` (`audioId`)")

                // Insert the permanent "None" sentinel row
                db.execSQL("""
                    INSERT INTO meditation_audios (fileName, sourceUrl, isNone)
                    VALUES ('None', '', 1)
                """.trimIndent())
            }
        }

        /**
         * v12 → v13: Add youtubeTitle and youtubeChannel columns to meditation_audios.
         * Both default to NULL so existing rows are unaffected until the user re-saves a URL.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meditation_audios ADD COLUMN youtubeTitle TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE meditation_audios ADD COLUMN youtubeChannel TEXT DEFAULT NULL")
            }
        }

        /**
         * v13 → v14: Add morning readiness telemetry support.
         *
         * Changes:
         *  1. Add `standTimestampMs` (nullable INTEGER) to morning_readiness.
         *     Stores the Unix epoch ms when the user was told to stand (orthostatic marker).
         *     NULL for all pre-existing rows.
         *  2. Create `morning_readiness_telemetry` table for per-beat HR/HRV data.
         *     Rows are cascade-deleted when the parent morning_readiness row is deleted.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE morning_readiness ADD COLUMN standTimestampMs INTEGER DEFAULT NULL")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `morning_readiness_telemetry` (
                        `id`              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `readingId`       INTEGER NOT NULL,
                        `timestampMs`     INTEGER NOT NULL,
                        `hrBpm`           INTEGER NOT NULL,
                        `rollingRmssdMs`  REAL    NOT NULL,
                        `phase`           TEXT    NOT NULL,
                        FOREIGN KEY(`readingId`) REFERENCES `morning_readiness`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_morning_readiness_telemetry_readingId` ON `morning_readiness_telemetry` (`readingId`)")
            }
        }

        /**
         * v14 → v15: Change Hooper sub-score columns from INTEGER to REAL.
         *
         * The Hooper questionnaire now uses a continuous Float slider (1.0–5.0)
         * instead of discrete integer steps. SQLite does not support ALTER COLUMN,
         * so we rebuild the morning_readiness table with REAL columns for
         * hooperSleep, hooperFatigue, hooperSoreness, and hooperStress.
         *
         * Existing INTEGER values are preserved — SQLite stores them as-is and
         * Room will read them as Float (e.g. 3 → 3.0).
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create replacement table with REAL hooper columns
                db.execSQL("""
                    CREATE TABLE `morning_readiness_new` (
                        `id`                       INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp`                INTEGER NOT NULL,
                        `supineRmssdMs`            REAL    NOT NULL,
                        `supineLnRmssd`            REAL    NOT NULL,
                        `supineSdnnMs`             REAL    NOT NULL,
                        `supineRhr`                INTEGER NOT NULL,
                        `standingRmssdMs`          REAL    NOT NULL,
                        `standingLnRmssd`          REAL    NOT NULL,
                        `standingSdnnMs`           REAL    NOT NULL,
                        `peakStandHr`              INTEGER NOT NULL,
                        `thirtyFifteenRatio`       REAL,
                        `ohrrAt20sPercent`         REAL,
                        `ohrrAt60sPercent`         REAL,
                        `respiratoryRateBpm`       REAL,
                        `slowBreathingFlagged`     INTEGER NOT NULL,
                        `hooperSleep`              REAL,
                        `hooperFatigue`            REAL,
                        `hooperSoreness`           REAL,
                        `hooperStress`             REAL,
                        `hooperTotal`              REAL,
                        `artifactPercentSupine`    REAL    NOT NULL,
                        `artifactPercentStanding`  REAL    NOT NULL,
                        `readinessScore`           INTEGER NOT NULL,
                        `readinessColor`           TEXT    NOT NULL,
                        `hrvBaseScore`             INTEGER NOT NULL,
                        `orthoMultiplier`          REAL    NOT NULL,
                        `cvPenaltyApplied`         INTEGER NOT NULL,
                        `rhrLimiterApplied`        INTEGER NOT NULL,
                        `hrDeviceId`               TEXT DEFAULT NULL,
                        `standTimestampMs`         INTEGER DEFAULT NULL
                    )
                """.trimIndent())

                // Copy all existing rows — INTEGER hooper values are implicitly cast to REAL
                db.execSQL("""
                    INSERT INTO `morning_readiness_new`
                    SELECT
                        id, timestamp,
                        supineRmssdMs, supineLnRmssd, supineSdnnMs, supineRhr,
                        standingRmssdMs, standingLnRmssd, standingSdnnMs,
                        peakStandHr,
                        thirtyFifteenRatio, ohrrAt20sPercent, ohrrAt60sPercent,
                        respiratoryRateBpm, slowBreathingFlagged,
                        CAST(hooperSleep    AS REAL),
                        CAST(hooperFatigue  AS REAL),
                        CAST(hooperSoreness AS REAL),
                        CAST(hooperStress   AS REAL),
                        hooperTotal,
                        artifactPercentSupine, artifactPercentStanding,
                        readinessScore, readinessColor,
                        hrvBaseScore, orthoMultiplier,
                        cvPenaltyApplied, rhrLimiterApplied,
                        hrDeviceId, standTimestampMs
                    FROM `morning_readiness`
                """.trimIndent())

                db.execSQL("DROP TABLE `morning_readiness`")
                db.execSQL("ALTER TABLE `morning_readiness_new` RENAME TO `morning_readiness`")
            }
        }

        /**
         * v15 → v16: Add richer assessment data columns to rf_assessments.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN peakToTroughBpm REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN maxLfPowerMs2 REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN maxCoherenceRatio REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN meanRmssdMs REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN meanSdnnMs REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN totalBeats INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN artifactPercent REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN resonanceCurveJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN hrWaveformJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE rf_assessments ADD COLUMN powerSpectrumJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v16 → v17: Make standing HRV columns and peakStandHr nullable in morning_readiness.
         *
         * Previously these were NOT NULL with default 0, which meant sessions where the user
         * skipped the standing phase would store fabricated zero-values that polluted history
         * charts and cross-day comparisons. Now they are NULL when standing was skipped.
         *
         * SQLite does not support ALTER COLUMN, so we recreate the table.
         * Existing rows keep their current numeric values (non-null); only new skip-standing
         * sessions will write NULL.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE `morning_readiness_new` (
                        `id`                       INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `timestamp`                INTEGER NOT NULL,
                        `supineRmssdMs`            REAL    NOT NULL,
                        `supineLnRmssd`            REAL    NOT NULL,
                        `supineSdnnMs`             REAL    NOT NULL,
                        `supineRhr`                INTEGER NOT NULL,
                        `standingRmssdMs`          REAL    DEFAULT NULL,
                        `standingLnRmssd`          REAL    DEFAULT NULL,
                        `standingSdnnMs`           REAL    DEFAULT NULL,
                        `peakStandHr`              INTEGER DEFAULT NULL,
                        `thirtyFifteenRatio`       REAL,
                        `ohrrAt20sPercent`         REAL,
                        `ohrrAt60sPercent`         REAL,
                        `respiratoryRateBpm`       REAL,
                        `slowBreathingFlagged`     INTEGER NOT NULL,
                        `hooperSleep`              REAL,
                        `hooperFatigue`            REAL,
                        `hooperSoreness`           REAL,
                        `hooperStress`             REAL,
                        `hooperTotal`              REAL,
                        `artifactPercentSupine`    REAL    NOT NULL,
                        `artifactPercentStanding`  REAL    NOT NULL,
                        `readinessScore`           INTEGER NOT NULL,
                        `readinessColor`           TEXT    NOT NULL,
                        `hrvBaseScore`             INTEGER NOT NULL,
                        `orthoMultiplier`          REAL    NOT NULL,
                        `cvPenaltyApplied`         INTEGER NOT NULL,
                        `rhrLimiterApplied`        INTEGER NOT NULL,
                        `hrDeviceId`               TEXT    DEFAULT NULL,
                        `standTimestampMs`         INTEGER DEFAULT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO `morning_readiness_new`
                    SELECT
                        id, timestamp,
                        supineRmssdMs, supineLnRmssd, supineSdnnMs, supineRhr,
                        standingRmssdMs, standingLnRmssd, standingSdnnMs,
                        peakStandHr,
                        thirtyFifteenRatio, ohrrAt20sPercent, ohrrAt60sPercent,
                        respiratoryRateBpm, slowBreathingFlagged,
                        hooperSleep, hooperFatigue, hooperSoreness, hooperStress, hooperTotal,
                        artifactPercentSupine, artifactPercentStanding,
                        readinessScore, readinessColor,
                        hrvBaseScore, orthoMultiplier,
                        cvPenaltyApplied, rhrLimiterApplied,
                        hrDeviceId, standTimestampMs
                    FROM `morning_readiness`
                """.trimIndent())

                db.execSQL("DROP TABLE `morning_readiness`")
                db.execSQL("ALTER TABLE `morning_readiness_new` RENAME TO `morning_readiness`")
            }
        }

        /**
         * v17 → v18: Add posture column to apnea_records.
         * Existing records default to 'LAYING' so all prior free holds are
         * treated as laying-posture holds.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apnea_records ADD COLUMN posture TEXT NOT NULL DEFAULT 'LAYING'")
            }
        }
    }
}
