package com.example.wags.di

import android.content.Context
import android.content.SharedPreferences
import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.ApneaSessionDao
import com.example.wags.data.db.dao.ContractionDao
import com.example.wags.data.db.dao.DailyReadingDao
import com.example.wags.data.db.dao.FreeHoldTelemetryDao
import com.example.wags.data.db.dao.MorningReadinessDao
import com.example.wags.data.db.dao.SessionLogDao
import com.example.wags.data.db.dao.TelemetryDao
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ApneaSessionRepository
import com.example.wags.data.repository.MorningReadinessRepository
import com.example.wags.data.repository.ReadinessRepository
import com.example.wags.data.repository.SessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideReadinessRepository(dao: DailyReadingDao): ReadinessRepository =
        ReadinessRepository(dao)

    @Provides
    @Singleton
    fun provideApneaRepository(
        dao: ApneaRecordDao,
        telemetryDao: FreeHoldTelemetryDao
    ): ApneaRepository = ApneaRepository(dao, telemetryDao)

    @Provides
    @Singleton
    fun provideSessionRepository(dao: SessionLogDao): SessionRepository =
        SessionRepository(dao)

    @Provides
    @Singleton
    fun provideMorningReadinessRepository(dao: MorningReadinessDao): MorningReadinessRepository =
        MorningReadinessRepository(dao)

    @Provides
    @Singleton
    fun provideApneaSessionRepository(
        apneaSessionDao: ApneaSessionDao,
        contractionDao: ContractionDao,
        telemetryDao: TelemetryDao,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): ApneaSessionRepository =
        ApneaSessionRepository(apneaSessionDao, contractionDao, telemetryDao, ioDispatcher)

    @Provides
    @Singleton
    @Named("apnea_prefs")
    fun provideApneaPrefs(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("apnea_prefs", Context.MODE_PRIVATE)
}
