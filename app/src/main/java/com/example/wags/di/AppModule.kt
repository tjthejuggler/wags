package com.example.wags.di

import android.content.Context
import android.content.SharedPreferences
import com.example.wags.data.db.dao.ApneaSessionDao
import com.example.wags.data.db.dao.ContractionDao
import com.example.wags.data.db.dao.DailyReadingDao
import com.example.wags.data.db.dao.SessionLogDao
import com.example.wags.data.db.dao.TelemetryDao
import com.example.wags.data.repository.ApneaSessionRepository
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

    // ApneaRepository is @Singleton + @Inject constructor — Hilt injects it automatically.

    @Provides
    @Singleton
    fun provideSessionRepository(dao: SessionLogDao): SessionRepository =
        SessionRepository(dao)

    // MorningReadinessRepository is @Singleton + @Inject constructor — Hilt injects it automatically.

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

    /**
     * Dedicated SharedPreferences file for Habit-app IPC settings
     * (selected habit_id / habit_name).  Injected into
     * [com.example.wags.data.ipc.HabitIntegrationRepository] via
     * @Named("habit_prefs").
     */
    @Provides
    @Singleton
    @Named("habit_prefs")
    fun provideHabitPrefs(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("habit_integration_prefs", Context.MODE_PRIVATE)

    /**
     * Dedicated SharedPreferences file for Spotify OAuth tokens.
     * Injected into [com.example.wags.data.spotify.SpotifyAuthManager].
     */
    @Provides
    @Singleton
    @Named("spotify_prefs")
    fun provideSpotifyPrefs(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("spotify_prefs", Context.MODE_PRIVATE)
}
