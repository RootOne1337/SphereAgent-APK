package com.sphere.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import kotlinx.coroutines.cancel
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
    // v3.6.0: Dispatchers.IO вместо Default — shell/network операции не должны блокировать CPU pool
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
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
            AgentService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AgentService", e)
        }
        
        // ENTERPRISE: Планируем периодическую проверку здоровья сервиса
        // WorkManager гарантирует выполнение даже если приложение убито
        try {
            AgentWorker.schedule(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule AgentWorker", e)
        }
        
        // v3.5.4 OPTIMIZATION: JobScheduler ОТКЛЮЧЁН - избыточно с WorkManager!
        // WorkManager уже обеспечивает persisted jobs которые переживают reboot.
        // BootJobService дублировал функционал и создавал лишнюю нагрузку.
        // При необходимости можно включить обратно, но не рекомендуется.
        /*
        try {
            BootJobService.schedulePeriodicJob(this)
            Log.d(TAG, "BootJobService scheduled (persisted - survives reboot!)")
            SphereLog.i(TAG, "JobScheduler watchdog enabled (persisted)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule BootJobService", e)
            SphereLog.e(TAG, "Failed to schedule BootJobService", e)
        }
        */
        
        // v3.6.0 CRITICAL FIX: ROOT-based автозапуск ПОЛНОСТЬЮ ОТЛОЖЕН на 60 секунд!
        // БЫЛО: 20-30 su процессов прямо в onCreate → ANR на всех эмуляторах!
        // СТАЛО: Деферим на 60 сек после полной загрузки системы
        // RootAutoStart + RootInitInstaller — ОДНОРАЗОВАЯ настройка (проверяем флаг)
        applicationScope.launch {
            try {
                // Ждём 60 секунд — система должна полностью загрузиться
                kotlinx.coroutines.delay(60_000)
                
                // Проверяем не настроен ли уже (одноразовая операция!)
                val prefs = getSharedPreferences("sphere_root_setup", Context.MODE_PRIVATE)
                val isSetupDone = prefs.getBoolean("root_setup_done_v3.6", false)
                
                if (!isSetupDone) {
                    if (RootAutoStart.hasRootAccess()) {
                        Log.d(TAG, "First-time ROOT setup (deferred 60s)...")
                        RootAutoStart.setupEnterpriseAutoStart(this@SphereAgentApp)
                        
                        // Init script — ТОЛЬКО если ещё не установлен
                        if (!RootInitInstaller.isInitScriptInstalled()) {
                            RootInitInstaller.installInitScript(this@SphereAgentApp)
                        }
                        
                        // Помечаем что настройка выполнена — больше не повторяем!
                        prefs.edit().putBoolean("root_setup_done_v3.6", true).apply()
                        Log.d(TAG, "ROOT setup completed and flagged — won't repeat")
                    }
                } else {
                    Log.d(TAG, "ROOT setup already done, skipping")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Deferred ROOT setup failed (not critical)", e)
            }
        }
        
        // Загружаем Remote Config и проверяем обновления (ОТЛОЖЕНО на 30 сек)
        applicationScope.launch {
            try {
                kotlinx.coroutines.delay(30_000)
                Log.d(TAG, "Loading remote config (deferred 30s)...")
                agentConfig.loadRemoteConfig()
                
                if (agentConfig.getOtaSettings().enabled) {
                    checkForUpdates()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load remote config", e)
            }
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
    
    /**
     * v3.6.1: Очистка CoroutineScope при завершении процесса
     * На эмуляторах onTerminate вызывается при force-kill
     */
    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
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
