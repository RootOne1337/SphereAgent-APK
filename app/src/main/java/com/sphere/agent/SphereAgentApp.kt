package com.sphere.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.data.SettingsRepository
import com.sphere.agent.update.UpdateManager
import com.sphere.agent.update.UpdateState
import com.sphere.agent.update.UpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SphereAgent Application
 * Enterprise-grade Remote Device Control Agent
 * 
 * Особенности:
 * - Auto-connect при запуске
 * - Отказоустойчивость (fallback серверы)
 * - OTA обновления с GitHub
 * - Remote Config с GitHub raw
 * - Для 500+ устройств
 */
@HiltAndroidApp
class SphereAgentApp : Application() {
    
    companion object {
        private const val TAG = "SphereAgentApp"
        const val NOTIFICATION_CHANNEL_SERVICE = "sphere_agent_service"
        const val NOTIFICATION_CHANNEL_UPDATES = "sphere_agent_updates"
        const val NOTIFICATION_CHANNEL_ALERTS = "sphere_agent_alerts"
        
        lateinit var instance: SphereAgentApp
            private set
    }
    
    // Application scope для фоновых операций
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Lazy инициализация компонентов
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val agentConfig: AgentConfig by lazy { AgentConfig(this) }
    val connectionManager: ConnectionManager by lazy { ConnectionManager(this, agentConfig) }
    val updateManager: UpdateManager by lazy { UpdateManager(this) }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Глобальный обработчик крашей - логируем но не крашим
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            // Можно добавить отправку краш-репортов
        }
        
        // Создаём notification channels (безопасно)
        try {
            createNotificationChannels()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channels", e)
        }
        
        // Загружаем Remote Config и проверяем обновления
        try {
            applicationScope.launch {
                try {
                    // Загружаем конфигурацию с GitHub
                    Log.d(TAG, "Loading remote config...")
                    agentConfig.loadRemoteConfig()
                    
                    // Проверяем обновления если OTA включен
                    if (agentConfig.getOtaSettings().enabled) {
                        checkForUpdates()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load remote config", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch config loader", e)
        }
        
        // Планируем периодическую проверку обновлений
        try {
            if (BuildConfig.AUTO_UPDATE_ENABLED) {
                UpdateWorker.schedule(this)
                Log.d(TAG, "Update worker scheduled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule update worker", e)
        }
    }
    
    /**
     * Проверка обновлений при запуске
     */
    private suspend fun checkForUpdates() {
        try {
            Log.d(TAG, "Checking for updates...")
            val state = updateManager.checkForUpdates()
            
            when (state) {
                is UpdateState.UpdateAvailable -> {
                    Log.d(TAG, "Update available: ${state.version.version}")
                    
                    // Если обязательное обновление - сразу скачиваем
                    if (state.version.required && agentConfig.getOtaSettings().auto_download) {
                        Log.d(TAG, "Downloading required update...")
                        updateManager.downloadUpdate(state.version)
                    }
                }
                is UpdateState.UpToDate -> {
                    Log.d(TAG, "App is up to date (v${BuildConfig.VERSION_NAME})")
                }
                is UpdateState.Error -> {
                    Log.w(TAG, "Update check failed: ${state.message}")
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking updates", e)
        }
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Service channel (для foreground service)
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_SERVICE,
                "Agent Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает статус подключения агента"
                setShowBadge(false)
            }
            
            // Updates channel
            val updatesChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_UPDATES,
                "Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления об обновлениях"
            }
            
            // Alerts channel
            val alertsChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ALERTS,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Важные уведомления"
            }
            
            notificationManager.createNotificationChannels(
                listOf(serviceChannel, updatesChannel, alertsChannel)
            )
        }
    }
}
