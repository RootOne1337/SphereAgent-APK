package com.sphere.agent.di

import android.content.Context
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.data.SettingsRepository
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.service.CommandExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AppModule - Hilt модуль для dependency injection
 * 
 * Предоставляет:
 * - AgentConfig (Remote Config)
 * - SettingsRepository (DataStore)
 * - ConnectionManager (WebSocket)
 * - CommandExecutor (Shell commands)
 */

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository {
        return SettingsRepository(context)
    }
    
    @Provides
    @Singleton
    fun provideAgentConfig(
        @ApplicationContext context: Context
    ): AgentConfig {
        return AgentConfig(context)
    }
    
    @Provides
    @Singleton
    fun provideConnectionManager(
        @ApplicationContext context: Context,
        agentConfig: AgentConfig
    ): ConnectionManager {
        return ConnectionManager(context, agentConfig)
    }
    
    @Provides
    @Singleton
    fun provideCommandExecutor(
        @ApplicationContext context: Context
    ): CommandExecutor {
        return CommandExecutor(context)
    }
}
