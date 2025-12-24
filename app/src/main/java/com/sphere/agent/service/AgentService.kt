package com.sphere.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sphere.agent.MainActivity
import com.sphere.agent.R
import com.sphere.agent.SphereAgentApp
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.network.ServerCommand
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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
                SphereLog.e(TAG, "Failed to start service", e)
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
                SphereLog.e(TAG, "Failed to stop service", e)
            }
        }
    }
    
    private lateinit var agentConfig: AgentConfig
    private lateinit var connectionManager: ConnectionManager
    private lateinit var commandExecutor: CommandExecutor
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var commandJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        SphereLog.i(TAG, "AgentService created")
        
        try {
            val app = application as SphereAgentApp
            agentConfig = app.agentConfig
            connectionManager = app.connectionManager
            commandExecutor = CommandExecutor(this)
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to initialize", e)
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SphereLog.i(TAG, "onStartCommand: ${intent?.action}")
        
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
            SphereLog.e(TAG, "Failed to start foreground", e)
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
            SphereLog.e(TAG, "Failed to stop foreground", e)
        }
    }
    
    private fun initializeAgent() {
        scope.launch {
            try {
                SphereLog.i(TAG, "Loading remote config...")
                val rc = agentConfig.loadRemoteConfig()
                SphereLog.i(
                    TAG,
                    "Remote config result=${rc.isSuccess}; primary=${agentConfig.config.value.server.primary_url}; wsPath=${agentConfig.config.value.server.websocket_path}"
                )

                SphereLog.i(TAG, "Calling connectionManager.connect()")
                connectionManager.connect()

                // Единая обработка команд должна жить здесь (всегда онлайн),
                // иначе при отсутствии ScreenCaptureService команды из backend не выполняются.
                startCommandLoop()
            } catch (e: Exception) {
                SphereLog.e(TAG, "Failed to initialize agent", e)
            }
        }
    }

    private fun startCommandLoop() {
        if (commandJob?.isActive == true) return

        commandJob = scope.launch {
            connectionManager.commands.collectLatest { command ->
                handleCommand(command)
            }
        }
    }

    private suspend fun handleCommand(command: ServerCommand) {
        SphereLog.i(TAG, "Handling command: ${command.type}")

        val result: CommandResult = when (command.type) {
            "request_frame", "ping", "config_update" -> {
                // Эти типы уже обрабатывает ConnectionManager, тут не дублируем.
                return
            }
            "home" -> commandExecutor.home()
            "back" -> commandExecutor.back()
            "recent" -> commandExecutor.recent()
            "power" -> commandExecutor.power()
            "volume_up" -> commandExecutor.volumeUp()
            "volume_down" -> commandExecutor.volumeDown()
            "tap" -> {
                val x = command.intParam("x") ?: return
                val y = command.intParam("y") ?: return
                commandExecutor.tap(x, y)
            }
            "long_press" -> {
                val x = command.intParam("x") ?: return
                val y = command.intParam("y") ?: return
                val duration = command.intParam("duration") ?: 800
                commandExecutor.longPress(x, y, duration)
            }
            "swipe" -> {
                val x1 = command.intParam("x1", "x") ?: return
                val y1 = command.intParam("y1", "y") ?: return
                val x2 = command.intParam("x2") ?: return
                val y2 = command.intParam("y2") ?: return
                val duration = command.intParam("duration") ?: 300
                commandExecutor.swipe(x1, y1, x2, y2, duration)
            }
            "key" -> {
                val keyCode = command.intParam("keycode", "keyCode") ?: return
                commandExecutor.keyEvent(keyCode)
            }
            "text" -> {
                val text = command.stringParam("text") ?: return
                commandExecutor.inputText(text)
            }
            "shell" -> {
                val shellCommand = command.stringParam("command") ?: return
                commandExecutor.shell(shellCommand)
            }
            "start_stream" -> {
                val quality = command.intParam("quality")
                val fps = command.intParam("fps")

                if (!ScreenCaptureService.hasMediaProjectionResult()) {
                    CommandResult(false, null, "MediaProjection permission not granted (open app and allow screen capture)")
                } else {
                    ScreenCaptureService.startCapture(applicationContext, quality = quality, fps = fps)
                    CommandResult(true, "Stream started", null)
                }
            }
            "stop_stream" -> {
                ScreenCaptureService.stopCapture(applicationContext)
                CommandResult(true, "Stream stopped", null)
            }
            else -> CommandResult(false, null, "Unknown command: ${command.type}")
        }

        command.command_id?.let { cmdId ->
            connectionManager.sendCommandResult(
                commandId = cmdId,
                success = result.success,
                data = result.data,
                error = result.error
            )
        }
    }

    private fun ServerCommand.intParam(vararg keys: String): Int? {
        for (k in keys) {
            val fromTopLevel = when (k) {
                "x" -> x
                "y" -> y
                "x2" -> x2
                "y2" -> y2
                "duration" -> duration
                "quality" -> quality
                "fps" -> fps
                "keyCode", "keycode" -> keyCode
                else -> null
            }
            if (fromTopLevel != null) return fromTopLevel

            val fromParams = params?.get(k)?.jsonPrimitive?.intOrNull
            if (fromParams != null) return fromParams
        }
        return null
    }

    private fun ServerCommand.stringParam(vararg keys: String): String? {
        for (k in keys) {
            val fromTopLevel = when (k) {
                "command" -> command
                else -> null
            }
            if (!fromTopLevel.isNullOrBlank()) return fromTopLevel

            val el = params?.get(k) ?: continue
            val prim = el as? JsonPrimitive ?: continue
            val v = prim.content
            if (v.isNotBlank()) return v
        }
        return null
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
        SphereLog.i(TAG, "AgentService destroyed")
        try {
            connectionManager.disconnect()
            commandJob?.cancel()
            scope.cancel()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error during destroy", e)
        }
        super.onDestroy()
    }
}
