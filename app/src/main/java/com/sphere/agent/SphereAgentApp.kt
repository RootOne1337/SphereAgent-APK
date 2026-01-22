package com.sphere.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.data.SettingsRepository
import com.sphere.agent.service.AgentService
import com.sphere.agent.service.BootJobService
import com.sphere.agent.update.UpdateManager
import com.sphere.agent.update.UpdateState
import com.sphere.agent.update.UpdateWorker
import com.sphere.agent.util.LogStorage
import com.sphere.agent.util.RootAutoStart
import com.sphere.agent.util.RootInitInstaller
import com.sphere.agent.util.SphereLog
import com.sphere.agent.worker.AgentWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SphereAgent Application
 * Enterprise-grade Remote Device Control Agent
 * 
 * Особенности:
 * - Auto-connect при запуске
 * - ENTERPRISE FAULT TOLERANCE: WorkManager + AlarmManager watchdog
 * - Отказоустойчивость (fallback серверы)
 * - OTA обновления с GitHub
 * - Remote Config с GitHub raw
 * - Для 1000+ устройств
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
    
    // Singleton зависимости (Hilt)
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var agentConfig: AgentConfig
    @Inject lateinit var connectionManager: ConnectionManager

    val updateManager: UpdateManager by lazy { UpdateManager(this) }
    
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Инициализируем локальное хранилище логов (переживает крэш)
        LogStorage.init(this)
        
        // Глобальный обработчик крашей - пишем в LogStorage, чтобы можно было скопировать в UI
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            SphereLog.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            try {
                LogStorage.addLog("FATAL", TAG, "Uncaught exception in thread ${thread.name}: ${throwable.message}\n${throwable.stackTraceToString()}")
            } catch (_: Exception) {
                Log.e(TAG, "Failed to persist crash log", throwable)
            }
        }
        
        // Создаём notification channels (безопасно)
        try {
            createNotificationChannels()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channels", e)
        }
        
        // Инициализируем SphereLog для отправки логов на сервер
        SphereLog.init(agentConfig)
        
        // КРИТИЧНО: Запускаем AgentService для обработки команд!
        // Без этого команды (tap, swipe, key) НЕ ВЫПОЛНЯЮТСЯ!
        try {
            Log.d(TAG, "Starting AgentService...")
            SphereLog.i(TAG, "Starting AgentService for command processing")
            AgentService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AgentService", e)
            SphereLog.e(TAG, "Failed to start AgentService", e)
        }
        
        // ENTERPRISE: Планируем периодическую проверку здоровья сервиса
        // WorkManager гарантирует выполнение даже если приложение убито
        try {
            AgentWorker.schedule(this)
            Log.d(TAG, "AgentWorker scheduled for health monitoring")
            SphereLog.i(TAG, "Enterprise health monitoring enabled (every 15 min)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule AgentWorker", e)
            SphereLog.e(TAG, "Failed to schedule AgentWorker", e)
        }
        
        // ENTERPRISE: Планируем JobScheduler - он переживает reboot с persisted=true!
        try {
            BootJobService.schedulePeriodicJob(this)
            Log.d(TAG, "BootJobService scheduled (persisted - survives reboot!)")
            SphereLog.i(TAG, "JobScheduler watchdog enabled (persisted)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule BootJobService", e)
            SphereLog.e(TAG, "Failed to schedule BootJobService", e)
        }
        
        // ENTERPRISE ROOT: Настраиваем ROOT-based автозапуск
        // Отключаем battery optimization, делаем app persistent
        applicationScope.launch {
            try {
                if (RootAutoStart.hasRootAccess()) {
                    Log.d(TAG, "ROOT detected - setting up enterprise auto-start...")
                    RootAutoStart.setupEnterpriseAutoStart(this@SphereAgentApp)
                    
                    // КРИТИЧНО: Устанавливаем init.d скрипт для гарантированного автозапуска!
                    // Это работает ВСЕГДА, даже если BootReceiver не срабатывает
                    if (!RootInitInstaller.isInitScriptInstalled()) {
                        Log.d(TAG, "Installing ROOT init script for guaranteed boot...")
                        val installed = RootInitInstaller.installInitScript(this@SphereAgentApp)
                        if (installed) {
                            Log.d(TAG, "ROOT init script installed successfully!")
                            SphereLog.i(TAG, "ROOT init script installed - guaranteed auto-start on boot!")
                        } else {
                            Log.w(TAG, "Failed to install ROOT init script")
                        }
                    } else {
                        Log.d(TAG, "ROOT init script already installed")
                    }
                } else {
                    Log.d(TAG, "No ROOT access - using standard auto-start")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup ROOT auto-start", e)
            }
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
                    SphereLog.e(TAG, "Failed to load remote config", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch config loader", e)
            SphereLog.e(TAG, "Failed to launch config loader", e)
        }
        
        // Планируем периодическую проверку обновлений
        try {
            if (BuildConfig.AUTO_UPDATE_ENABLED) {
                UpdateWorker.schedule(this)
                Log.d(TAG, "Update worker scheduled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule update worker", e)
            SphereLog.e(TAG, "Failed to schedule update worker", e)
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
