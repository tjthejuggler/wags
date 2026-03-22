package com.example.wags.di

import android.content.Context
import com.example.wags.data.ble.DevicePreferencesRepository
import com.example.wags.data.garmin.GarminApneaRepository
import com.example.wags.data.garmin.GarminManager
import com.example.wags.data.repository.ApneaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Hilt module providing Garmin Connect IQ integration dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object GarminModule {

    /**
     * Application-scoped CoroutineScope for long-running Garmin operations.
     * Uses SupervisorJob so individual failures don't cancel the whole scope.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Provides
    @Singleton
    fun provideGarminManager(
        @ApplicationContext context: Context,
        scope: CoroutineScope
    ): GarminManager = GarminManager(context, scope)

    @Provides
    @Singleton
    fun provideGarminApneaRepository(
        garminManager: GarminManager,
        apneaRepository: ApneaRepository,
        devicePrefs: DevicePreferencesRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        scope: CoroutineScope
    ): GarminApneaRepository = GarminApneaRepository(
        garminManager, apneaRepository, devicePrefs, ioDispatcher, scope
    )
}
