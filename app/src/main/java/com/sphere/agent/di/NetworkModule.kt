/**
 * NetworkModule - Hilt DI модуль для сетевых компонентов
 * 
 * Предоставляет:
 * - ServerDiscoveryManager (автодискавери)
 * - ConnectionManager (WebSocket)
 * - OkHttpClient (HTTP)
 */
package com.sphere.agent.di

import android.content.Context
import com.sphere.agent.network.ServerDiscoveryManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Без таймаута для WebSocket
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideServerDiscoveryManager(
        @ApplicationContext context: Context
    ): ServerDiscoveryManager {
        return ServerDiscoveryManager(context)
    }
}
