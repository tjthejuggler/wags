package com.example.wags.di

import com.example.wags.data.db.dao.ApneaRecordDao
import com.example.wags.data.db.dao.DailyReadingDao
import com.example.wags.data.db.dao.SessionLogDao
import com.example.wags.data.repository.ApneaRepository
import com.example.wags.data.repository.ReadinessRepository
import com.example.wags.data.repository.SessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
    fun provideApneaRepository(dao: ApneaRecordDao): ApneaRepository =
        ApneaRepository(dao)

    @Provides
    @Singleton
    fun provideSessionRepository(dao: SessionLogDao): SessionRepository =
        SessionRepository(dao)
}
