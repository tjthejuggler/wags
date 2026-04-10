package com.example.wags.di

import android.content.Context
import androidx.room.Room
import com.example.wags.data.db.WagsDatabase
import com.example.wags.data.db.dao.AccCalibrationDao
import com.example.wags.data.db.dao.AdviceDao
import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.ApneaSessionDao
import com.example.wags.data.db.dao.ApneaSongLogDao
import com.example.wags.data.db.dao.ResonanceSessionDao
import com.example.wags.data.db.dao.ContractionDao
import com.example.wags.data.db.dao.DailyReadingDao
import com.example.wags.data.db.dao.FreeHoldTelemetryDao
import com.example.wags.data.db.dao.MeditationAudioDao
import com.example.wags.data.db.dao.MeditationSessionDao
import com.example.wags.data.db.dao.MeditationTelemetryDao
import com.example.wags.data.db.dao.MorningReadinessDao
import com.example.wags.data.db.dao.MorningReadinessTelemetryDao
import com.example.wags.data.db.dao.RapidHrSessionDao
import com.example.wags.data.db.dao.RapidHrTelemetryDao
import com.example.wags.data.db.dao.RfAssessmentDao
import com.example.wags.data.db.dao.SessionLogDao
import com.example.wags.data.db.dao.TelemetryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideWagsDatabase(@ApplicationContext context: Context): WagsDatabase =
        Room.databaseBuilder(context, WagsDatabase::class.java, "wags.db")
            .addMigrations(
                WagsDatabase.MIGRATION_1_2,
                WagsDatabase.MIGRATION_2_3,
                WagsDatabase.MIGRATION_3_4,
                WagsDatabase.MIGRATION_4_5,
                WagsDatabase.MIGRATION_5_6,
                WagsDatabase.MIGRATION_6_7,
                WagsDatabase.MIGRATION_7_8,
                WagsDatabase.MIGRATION_8_9,
                WagsDatabase.MIGRATION_9_10,
                WagsDatabase.MIGRATION_10_11,
                WagsDatabase.MIGRATION_11_12,
                WagsDatabase.MIGRATION_12_13,
                WagsDatabase.MIGRATION_13_14,
                WagsDatabase.MIGRATION_14_15,
                WagsDatabase.MIGRATION_15_16,
                WagsDatabase.MIGRATION_16_17,
                WagsDatabase.MIGRATION_17_18,
                WagsDatabase.MIGRATION_18_19,
                WagsDatabase.MIGRATION_19_20,
                WagsDatabase.MIGRATION_20_21,
                WagsDatabase.MIGRATION_21_22,
                WagsDatabase.MIGRATION_22_23,
                WagsDatabase.MIGRATION_23_24,
                WagsDatabase.MIGRATION_24_25,
                WagsDatabase.MIGRATION_25_26,
                WagsDatabase.MIGRATION_26_27
            )
            .build()

    @Provides fun provideDailyReadingDao(db: WagsDatabase): DailyReadingDao = db.dailyReadingDao()
    @Provides fun provideApneaRecordDao(db: WagsDatabase): ApneaRecordDao = db.apneaRecordDao()
    @Provides fun provideSessionLogDao(db: WagsDatabase): SessionLogDao = db.sessionLogDao()
    @Provides fun provideRfAssessmentDao(db: WagsDatabase): RfAssessmentDao = db.rfAssessmentDao()
    @Provides fun provideAccCalibrationDao(db: WagsDatabase): AccCalibrationDao = db.accCalibrationDao()
    @Provides fun provideMorningReadinessDao(db: WagsDatabase): MorningReadinessDao = db.morningReadinessDao()
    @Provides fun provideApneaSessionDao(db: WagsDatabase): ApneaSessionDao = db.apneaSessionDao()
    @Provides fun provideContractionDao(db: WagsDatabase): ContractionDao = db.contractionDao()
    @Provides fun provideTelemetryDao(db: WagsDatabase): TelemetryDao = db.telemetryDao()
    @Provides fun provideFreeHoldTelemetryDao(db: WagsDatabase): FreeHoldTelemetryDao = db.freeHoldTelemetryDao()
    @Provides fun provideMeditationAudioDao(db: WagsDatabase): MeditationAudioDao = db.meditationAudioDao()
    @Provides fun provideMeditationSessionDao(db: WagsDatabase): MeditationSessionDao = db.meditationSessionDao()
    @Provides fun provideMeditationTelemetryDao(db: WagsDatabase): MeditationTelemetryDao = db.meditationTelemetryDao()
    @Provides fun provideMorningReadinessTelemetryDao(db: WagsDatabase): MorningReadinessTelemetryDao = db.morningReadinessTelemetryDao()
    @Provides fun provideAdviceDao(db: WagsDatabase): AdviceDao = db.adviceDao()
    @Provides fun provideApneaSongLogDao(db: WagsDatabase): ApneaSongLogDao = db.apneaSongLogDao()
    @Provides fun provideResonanceSessionDao(db: WagsDatabase): ResonanceSessionDao = db.resonanceSessionDao()
    @Provides fun provideRapidHrSessionDao(db: WagsDatabase): RapidHrSessionDao = db.rapidHrSessionDao()
    @Provides fun provideRapidHrTelemetryDao(db: WagsDatabase): RapidHrTelemetryDao = db.rapidHrTelemetryDao()
}
