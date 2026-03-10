package com.example.wags.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.wags.data.db.dao.*
import com.example.wags.data.db.entity.*

@Database(
    entities = [
        DailyReadingEntity::class,
        ApneaRecordEntity::class,
        SessionLogEntity::class,
        RfAssessmentEntity::class,
        AccCalibrationEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class WagsDatabase : RoomDatabase() {
    abstract fun dailyReadingDao(): DailyReadingDao
    abstract fun apneaRecordDao(): ApneaRecordDao
    abstract fun sessionLogDao(): SessionLogDao
    abstract fun rfAssessmentDao(): RfAssessmentDao
    abstract fun accCalibrationDao(): AccCalibrationDao
}
