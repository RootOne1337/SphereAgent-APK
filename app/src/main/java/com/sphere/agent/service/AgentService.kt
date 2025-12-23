package com.sphere.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sphere.agent.MainActivity
import com.sphere.agent.R
import com.sphere.agent.SphereAgentApp
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.network.ConnectionManager
import kotlinx.coroutines.*

/**
 * AgentService - Foreground Service для поддержания соединения с сервером
 * 
 * Функционал:
 * - Постоянное подключение к WebSocket
 * - Обработка команд от сервера
 * - Отправка статуса устройства
 * - Работа в фоне
 * 
 * Совместимость: Android 7.0+ (API 24)
 */
class AgentService : Service() {
    
    companion object {
        private const val TAG = "AgentService"
        private const val NOTIFICATION_ID = 1002
        
        private const val ACTION_START = "com.sphere.agent.START_SERVICE"
        private const val ACTION_STOP = "com.sphere.agent.STOP_SERVICE"
        
        /**
         * Запуск сервиса
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, AgentService::class.java).apply {
                    action = ACTION_START
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }
        
        /**
         * Остановка сервиса
         */
        fun stop(context: Context) {
            try {
                val intent = Intent(context, AgentService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }
    
    private lateinit var agentConfig: AgentConfig
    private lateinit var connectionManager: ConnectionManager
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AgentService created")
        
        try {
            val app = application as SphereAgentApp
            agentConfig = app.agentConfig
            connectionManager = app.connectionManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                startForegroundSafe()
                initializeAgent()
            }
            ACTION_STOP -> {
                stopForegroundSafe()
                stopSelf()
            }
            else -> {
                // Запуск без action - просто запускаем сервис
                startForegroundSafe()
                initializeAgent()
            }
        }
        
        return START_STICKY
    }
    
    /**
     * Безопасный запуск foreground service с обработкой ошибок
     */
    private fun startForegroundSafe() {
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            // На старых версиях Android просто продолжаем работать
        }
    }
    
    /**
     * Безопасная остановка foreground service
     */
    private fun stopForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground", e)
        }
    }
    
    private fun initializeAgent() {
        scope.launch {
            try {
                // Загружаем конфигурацию
                agentConfig.loadRemoteConfig()
                
                // Подключаемся к серверу
                connectionManager.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize agent", e)
            }
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = try {
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } catch (e: Exception) {
            null
        }
        
        return NotificationCompat.Builder(this, SphereAgentApp.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("SphereAgent")
            .setContentText("Сервис активен")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                pendingIntent?.let { setContentIntent(it) }
            }
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d(TAG, "AgentService destroyed")
        try {
            connectionManager.disconnect()
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error during destroy", e)
        }
        super.onDestroy()
    }
}
