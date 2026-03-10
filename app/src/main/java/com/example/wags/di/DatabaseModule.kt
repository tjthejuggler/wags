package com.example.wags.di

import android.content.Context
import androidx.room.Room
import com.example.wags.data.db.WagsDatabase
import com.example.wags.data.db.dao.AccCalibrationDao
import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.DailyReadingDao
import com.example.wags.data.db.dao.RfAssessmentDao
import com.example.wags.data.db.dao.SessionLogDao
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
        Room.databaseBuilder(context, WagsDatabase::class.java, "wags.db").build()

    @Provides fun provideDailyReadingDao(db: WagsDatabase): DailyReadingDao = db.dailyReadingDao()
    @Provides fun provideApneaRecordDao(db: WagsDatabase): ApneaRecordDao = db.apneaRecordDao()
    @Provides fun provideSessionLogDao(db: WagsDatabase): SessionLogDao = db.sessionLogDao()
    @Provides fun provideRfAssessmentDao(db: WagsDatabase): RfAssessmentDao = db.rfAssessmentDao()
    @Provides fun provideAccCalibrationDao(db: WagsDatabase): AccCalibrationDao = db.accCalibrationDao()
}
