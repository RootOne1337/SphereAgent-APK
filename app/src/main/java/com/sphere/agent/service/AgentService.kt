package com.sphere.agent.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.sphere.agent.BuildConfig
import com.sphere.agent.MainActivity
import com.sphere.agent.R
import com.sphere.agent.SphereAgentApp
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.network.ServerCommand
import com.sphere.agent.script.ScriptEngine
import com.sphere.agent.script.ScriptStatus
import com.sphere.agent.script.ScriptEventBus
import com.sphere.agent.script.GlobalVariables
import com.sphere.agent.update.UpdateManager
import com.sphere.agent.update.UpdateState
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

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
        
        // ENTERPRISE: Сделано public для использования в BootContentProvider и BootJobService
        const val ACTION_START = "com.sphere.agent.START_SERVICE"
        private const val ACTION_STOP = "com.sphere.agent.STOP_SERVICE"
        private const val ACTION_WATCHDOG = "com.sphere.agent.WATCHDOG"
        
        // ENTERPRISE: Watchdog интервал - проверяем каждые 5 минут
        private const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L
        
        @Volatile
        var isRunning = false
            private set
        
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
                
                // ENTERPRISE: Устанавливаем watchdog alarm
                scheduleWatchdog(context)
            } catch (e: Exception) {
                SphereLog.e(TAG, "Failed to start service", e)
            }
        }
        
        /**
         * ENTERPRISE: Watchdog alarm - перезапуск если сервис упал
         */
        private fun scheduleWatchdog(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, AgentService::class.java).apply {
                    action = ACTION_WATCHDOG
                }
                val pendingIntent = PendingIntent.getService(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                // Отменяем предыдущий alarm
                alarmManager.cancel(pendingIntent)
                
                // Устанавливаем новый alarm
                // ENTERPRISE: Добавляем jitter чтобы устройства не дергались синхронно
                val jitterMs = Random.nextLong(0, 60_000L)
                val triggerTime = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS + jitterMs
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
                SphereLog.d(TAG, "Watchdog alarm scheduled for ${WATCHDOG_INTERVAL_MS/1000}s (jitter=${jitterMs}ms)")
            } catch (e: Exception) {
                SphereLog.e(TAG, "Failed to schedule watchdog", e)
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
    private lateinit var scriptEngine: ScriptEngine
    
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var commandJob: Job? = null
    
    // v2.26.0 ENTERPRISE: Batch Status Updates
    // Агрегирует статусы скрипта близкие по времени (<500ms) в один пакет
    private val statusBatchBuffer = java.util.concurrent.ConcurrentLinkedQueue<ScriptStatus>()
    private var batchFlushJob: Job? = null
    private val BATCH_FLUSH_INTERVAL_MS = 500L  // Флаш каждые 500ms
    @Volatile private var lastBatchFlushTime = 0L
    
    override fun onCreate() {
        super.onCreate()
        SphereLog.i(TAG, "AgentService created")
        
        try {
            val app = application as SphereAgentApp
            agentConfig = app.agentConfig
            connectionManager = app.connectionManager
            commandExecutor = CommandExecutor(this)
            
            // КРИТИЧНО: Callback для автоматического обновления ROOT статуса
            // Когда CommandExecutor определит ROOT - сразу обновляем ConnectionManager
            commandExecutor.onRootStatusChanged = { hasRoot ->
                SphereLog.i(TAG, "=== ROOT STATUS CHANGED: $hasRoot ===")
                connectionManager.hasRootAccess = hasRoot
                // Отправляем обновлённый heartbeat немедленно
                if (hasRoot) {
                    connectionManager.sendRootStatusUpdate(hasRoot)
                }
            }
            
            // Инициализация ScriptEngine для выполнения автоматизации
            scriptEngine = ScriptEngine(
                context = this,
                commandExecutor = commandExecutor,
                onStatusUpdate = { status ->
                    // v2.26.0 ENTERPRISE: Batch Status Updates
                    // Важные статусы (STARTED, COMPLETED, FAILED) отправляем сразу
                    // Промежуточные (RUNNING) батчим для уменьшения нагрузки
                    if (status.state.name in listOf("STARTED", "COMPLETED", "FAILED", "STOPPED")) {
                        // Критические статусы - немедленная отправка с jitter
                        scope.launch {
                            sendScriptStatusWithJitter(status)
                        }
                    } else {
                        // RUNNING статусы - батчим
                        addStatusToBatch(status)
                    }
                }
            )
            SphereLog.i(TAG, "ScriptEngine initialized")
            
            // v2.26.0: Запускаем batch flush job
            startBatchFlushJob()
            
            // v2.11.0: Инициализация ServerConnection для ScriptEventBus и GlobalVariables
            initializeServerSync()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to initialize", e)
            stopSelf()
        }
    }
    
    /**
     * Отправка статуса скрипта на сервер (синхронная версия)
     */
    private fun sendScriptStatus(status: ScriptStatus) {
        try {
            val message = json.encodeToString(
                mapOf(
                    "type" to "script_status",
                    "run_id" to status.runId,
                    "script_id" to status.scriptId,
                    "script_name" to status.scriptName,
                    "state" to status.state.name,
                    "current_step" to status.currentStep.toString(),
                    "total_steps" to status.totalSteps.toString(),
                    "step_name" to status.currentStepName,
                    "progress" to status.progress.toString(),
                    "loop_count" to status.loopCount.toString(),
                    "error" to (status.error ?: "")
                )
            )
            connectionManager.sendMessage(message)
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to send script status", e)
        }
    }
    
    /**
     * v2.26.0 ENTERPRISE: Отправка статуса скрипта с jitter
     * 
     * При 1000+ устройств, если все отправляют статус одновременно,
     * бэкенд получает spike нагрузки. Jitter (100-500ms) распределяет это.
     */
    private suspend fun sendScriptStatusWithJitter(status: ScriptStatus) {
        try {
            val message = json.encodeToString(
                mapOf(
                    "type" to "script_status",
                    "run_id" to status.runId,
                    "script_id" to status.scriptId,
                    "script_name" to status.scriptName,
                    "state" to status.state.name,
                    "current_step" to status.currentStep.toString(),
                    "total_steps" to status.totalSteps.toString(),
                    "step_name" to status.currentStepName,
                    "progress" to status.progress.toString(),
                    "loop_count" to status.loopCount.toString(),
                    "error" to (status.error ?: ""),
                    "timestamp" to System.currentTimeMillis().toString()
                )
            )
            connectionManager.sendMessageWithJitter(message)
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to send script status with jitter", e)
        }
    }
    
    /**
     * v2.26.0 ENTERPRISE: Добавление статуса в batch буфер
     * 
     * Промежуточные статусы (RUNNING) накапливаются и отправляются
     * одним пакетом каждые 500ms. Это уменьшает нагрузку на WebSocket
     * и бэкенд при быстром выполнении шагов скрипта.
     */
    private fun addStatusToBatch(status: ScriptStatus) {
        // Добавляем в буфер
        statusBatchBuffer.add(status)
        
        // Если буфер переполнен (>50 статусов) - форсируем flush
        if (statusBatchBuffer.size > 50) {
            scope.launch {
                flushStatusBatch()
            }
        }
    }
    
    /**
     * v2.26.0 ENTERPRISE: Запуск фонового job для периодического flush batch'а
     */
    private fun startBatchFlushJob() {
        batchFlushJob?.cancel()
        batchFlushJob = scope.launch {
            while (isActive) {
                delay(BATCH_FLUSH_INTERVAL_MS)
                flushStatusBatch()
            }
        }
        SphereLog.i(TAG, "Batch flush job started (interval=${BATCH_FLUSH_INTERVAL_MS}ms)")
    }
    
    /**
     * v2.26.0 ENTERPRISE: Отправка накопленных статусов одним пакетом
     * 
     * Агрегирует статусы из буфера и отправляет как batch_script_status.
     * Берёт только последний статус для каждого run_id (остальные устарели).
     */
    private suspend fun flushStatusBatch() {
        if (statusBatchBuffer.isEmpty()) return
        
        try {
            // Собираем все статусы из буфера
            val statuses = mutableListOf<ScriptStatus>()
            while (statusBatchBuffer.isNotEmpty()) {
                statusBatchBuffer.poll()?.let { statuses.add(it) }
            }
            
            if (statuses.isEmpty()) return
            
            // Группируем по run_id и берём только последний статус
            val latestByRunId = statuses
                .groupBy { it.runId }
                .mapValues { (_, list) -> list.last() }
                .values
                .toList()
            
            // Если только один статус - отправляем как обычный
            if (latestByRunId.size == 1) {
                sendScriptStatusWithJitter(latestByRunId.first())
                return
            }
            
            // Формируем batch сообщение
            val batchMessage = json.encodeToString(
                mapOf(
                    "type" to "batch_script_status",
                    "count" to latestByRunId.size.toString(),
                    "statuses" to latestByRunId.map { status ->
                        mapOf(
                            "run_id" to status.runId,
                            "script_id" to status.scriptId,
                            "script_name" to status.scriptName,
                            "state" to status.state.name,
                            "current_step" to status.currentStep.toString(),
                            "total_steps" to status.totalSteps.toString(),
                            "step_name" to status.currentStepName,
                            "progress" to status.progress.toString(),
                            "loop_count" to status.loopCount.toString(),
                            "error" to (status.error ?: "")
                        )
                    },
                    "timestamp" to System.currentTimeMillis().toString()
                )
            )
            
            connectionManager.sendMessageWithJitter(batchMessage)
            SphereLog.d(TAG, "Batch status sent: ${latestByRunId.size} statuses, original=${statuses.size}")
            
            lastBatchFlushTime = System.currentTimeMillis()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to flush status batch", e)
        }
    }
    
    /**
     * v2.11.0: Инициализация синхронизации EventBus и GlobalVariables с сервером
     */
    private fun initializeServerSync() {
        val serverConnection = object : ScriptEventBus.ServerConnection {
            override fun sendMessage(message: String): Boolean {
                return connectionManager.sendMessage(message)
            }
            
            override fun getDeviceId(): String {
                return agentConfig.deviceId
            }
        }
        
        // Та же реализация для GlobalVariables
        val globalVarsConnection = object : GlobalVariables.ServerConnection {
            override fun sendMessage(message: String): Boolean {
                return connectionManager.sendMessage(message)
            }
            
            override fun getDeviceId(): String {
                return agentConfig.deviceId
            }
        }
        
        ScriptEventBus.setServerConnection(serverConnection)
        GlobalVariables.setServerConnection(globalVarsConnection)
        
        SphereLog.i(TAG, "Server sync initialized for EventBus and GlobalVariables")
    }
    
    /**
     * v2.11.0: Обработка событий синхронизации от сервера
     */
    private fun handleServerSyncMessage(msgType: String, data: Map<String, Any?>) {
        when (msgType) {
            "global_var:value" -> {
                // Ответ на запрос переменной
                val namespace = data["namespace"] as? String ?: "default"
                val key = data["key"] as? String ?: return
                val value = data["value"]
                val correlationId = data["correlation_id"] as? String
                
                GlobalVariables.handleServerValue(namespace, key, value, correlationId)
            }
            
            "global_var:full_sync_response" -> {
                // Полная синхронизация переменных
                @Suppress("UNCHECKED_CAST")
                val syncData = data["data"] as? Map<String, Map<String, Any?>> ?: return
                GlobalVariables.handleFullSync(syncData)
            }
            
            "global_var:push" -> {
                // Push обновление от другого устройства
                val namespace = data["namespace"] as? String ?: "default"
                val key = data["key"] as? String ?: return
                val value = data["value"]
                
                GlobalVariables.handleServerUpdate(namespace, key, value)
            }
            
            "event:received" -> {
                // Событие от сервера (от другого устройства или системы)
                val eventType = data["event_type"] as? String ?: return
                val eventId = data["event_id"] as? String ?: java.util.UUID.randomUUID().toString()
                val source = data["source"] as? String ?: "server"
                val target = data["target"] as? String
                @Suppress("UNCHECKED_CAST")
                val payload = data["payload"] as? Map<String, Any?> ?: emptyMap()
                val timestamp = (data["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                
                ScriptEventBus.handleServerEvent(eventType, eventId, source, target, payload, timestamp)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SphereLog.i(TAG, "onStartCommand: ${intent?.action}")
        isRunning = true
        
        when (intent?.action) {
            ACTION_START -> {
                startForegroundSafe()
                initializeAgent()
                scheduleWatchdog(this)
            }
            ACTION_WATCHDOG -> {
                // ENTERPRISE: Watchdog сработал - проверяем и перезапускаем alarm
                SphereLog.i(TAG, "Watchdog triggered - service is alive")
                if (!connectionManager.isConnected) {
                    SphereLog.w(TAG, "Watchdog: connection lost, reconnecting...")
                    connectionManager.connect()
                }
                scheduleWatchdog(this)
            }
            ACTION_STOP -> {
                isRunning = false
                stopForegroundSafe()
                stopSelf()
            }
            else -> {
                // Запуск без action - просто запускаем сервис
                startForegroundSafe()
                initializeAgent()
                scheduleWatchdog(this)
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

                // ROOT проверяется автоматически в CommandExecutor через агрессивный фоновый checker
                // Callback onRootStatusChanged обновит connectionManager.hasRootAccess
                // Начальная проверка запускается сразу при создании CommandExecutor
                SphereLog.i(TAG, "ROOT checker is running in background...")
                
                // Делаем одну синхронную проверку перед connect
                val hasRoot = commandExecutor.checkRoot()
                connectionManager.hasRootAccess = hasRoot
                SphereLog.i(TAG, "Initial ROOT check: $hasRoot (will keep retrying if false)")

                // ENTERPRISE: RootScreenCaptureService запускаем только по требованию (start_stream)
                SphereLog.i(TAG, "RootScreenCaptureService будет запущен по требованию (start_stream)")

                // КРИТИЧНО: startCommandLoop() ПЕРЕД connect()!
                // Иначе команды могут прийти ДО того как subscription установлена
                // и будут потеряны (SharedFlow без replay теряет emit без подписчика)
                SphereLog.i(TAG, "Starting command loop BEFORE connect...")
                startCommandLoop()
                
                SphereLog.i(TAG, "Calling connectionManager.connect()")
                connectionManager.connect()
            } catch (e: Exception) {
                SphereLog.e(TAG, "Failed to initialize agent", e)
            }
        }
    }

    private fun startCommandLoop() {
        if (commandJob?.isActive == true) {
            SphereLog.w(TAG, "Command loop already running, skipping")
            return
        }

        SphereLog.i(TAG, "=== STARTING COMMAND LOOP ===")
        android.util.Log.i(TAG, "=== STARTING COMMAND LOOP ===")
        
        commandJob = scope.launch {
            SphereLog.i(TAG, "Command loop coroutine started, waiting for commands...")
            connectionManager.commands.collectLatest { command ->
                handleCommand(command)
            }
        }
    }

    private suspend fun handleCommand(command: ServerCommand) {
        SphereLog.i(TAG, "=== HANDLING COMMAND: ${command.type} ===")
        android.util.Log.i(TAG, "=== HANDLING COMMAND: ${command.type} params=${command.params} ===")

        // Служебные сообщения - НЕ команды, не отправляем result
        if (command.type in listOf("request_frame", "ping", "config_update", "heartbeat_ack", "pong")) {
            return
        }
        
        // v2.25.0: При успешной регистрации запускаем полную синхронизацию
        if (command.type == "registered") {
            SphereLog.i(TAG, "Agent registered, requesting full sync...")
            scope.launch {
                try {
                    GlobalVariables.fullSyncFromServer()
                    SphereLog.i(TAG, "Full sync requested")
                } catch (e: Exception) {
                    SphereLog.e(TAG, "Failed to request full sync", e)
                }
            }
            return
        }
        
        // v2.11.0: Сообщения синхронизации EventBus и GlobalVariables
        if (command.type.startsWith("global_var:") || command.type.startsWith("event:")) {
            @Suppress("UNCHECKED_CAST")
            val data = command.params as? Map<String, Any?> ?: emptyMap()
            handleServerSyncMessage(command.type, data)
            return  // Sync сообщения не требуют command_result
        }

        // Input-команды требуют приоритета (приостанавливаем стрим)
        val isInputCommand = command.type in listOf(
            "home", "back", "recent", "power", "volume_up", "volume_down",
            "tap", "long_press", "swipe", "key", "text"
        )
        
        if (isInputCommand) {
            connectionManager.setCommandInProgress(true)
        }

        val result: CommandResult = try {
            when (command.type) {
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
                    val quality = command.intParam("quality") ?: BuildConfig.DEFAULT_STREAM_QUALITY
                    val fps = command.intParam("fps") ?: BuildConfig.DEFAULT_STREAM_FPS
                    val compression = agentConfig.config.value.stream.compression.lowercase()

                    // v3.0.2 ENTERPRISE: SMART STREAM SELECTION
                    // Приоритет: H.264 (MediaProjection) > JPEG (ROOT) > Error
                    // Автоматический fallback для headless эмуляторов
                    
                    var streamResult: CommandResult? = null
                    
                    // ПРИОРИТЕТ 1: H.264 через MediaProjection (если есть permission)
                    if ((compression == "h264" || compression == "auto") && ScreenCaptureService.hasMediaProjectionResult()) {
                        SphereLog.i(TAG, "Trying H.264 stream (MediaProjection available)...")
                        ScreenCaptureService.startService(applicationContext)

                        val bitrate = when {
                            quality < 30 -> H264ScreenEncoder.QUALITY_ULTRA_LOW
                            quality < 50 -> H264ScreenEncoder.QUALITY_LOW
                            quality < 70 -> H264ScreenEncoder.QUALITY_MEDIUM
                            quality < 90 -> H264ScreenEncoder.QUALITY_HIGH
                            else -> H264ScreenEncoder.QUALITY_ULTRA
                        }

                        val started = ScreenCaptureService.startStream(
                            applicationContext,
                            bitrate = bitrate,
                            fps = fps
                        )

                        if (started) {
                            connectionManager.isCurrentlyStreaming = true
                            ScreenCaptureService.requestKeyframe()
                            streamResult = CommandResult(true, "Stream started (H.264)", null)
                        } else {
                            SphereLog.w(TAG, "H.264 failed, will try ROOT fallback")
                        }
                    }
                    
                    // ПРИОРИТЕТ 2: ROOT screencap (для эмуляторов и headless устройств)
                    if (streamResult == null) {
                        SphereLog.i(TAG, "Using ROOT capture (isRunning=${RootScreenCaptureService.isRunning}, hasRoot=${connectionManager.hasRootAccess})")
                        
                        if (RootScreenCaptureService.isRunning) {
                            RootScreenCaptureService.resume(applicationContext, quality, fps)
                        } else {
                            RootScreenCaptureService.start(applicationContext, quality, fps)
                            delay(800) // Ждём запуска
                            RootScreenCaptureService.resume(applicationContext, quality, fps)
                        }
                        connectionManager.isCurrentlyStreaming = true
                        delay(500)
                        
                        streamResult = CommandResult(true, "Stream started (ROOT capture, running=${RootScreenCaptureService.isRunning})", null)
                    }
                    
                    streamResult!!
                }
                
                // LEGACY SUPPORT: Старый код с явным JPEG режимом (НЕ ИСПОЛЬЗУЕТСЯ)
                "start_stream_legacy" -> {
                    val quality = command.intParam("quality") ?: BuildConfig.DEFAULT_STREAM_QUALITY
                    val fps = command.intParam("fps") ?: BuildConfig.DEFAULT_STREAM_FPS
                    RootScreenCaptureService.start(applicationContext, quality, fps)
                    delay(300)
                    RootScreenCaptureService.resume(applicationContext, quality, fps)
                    connectionManager.isCurrentlyStreaming = true
                    CommandResult(true, "Stream started (LEGACY)", null)
                }
                "stop_stream" -> {
                    // КРИТИЧНО: НЕ останавливаем capture полностью!
                    // Просто приостанавливаем отправку кадров
                    // Это экономит трафик когда нет viewers
                    
                    if (ScreenCaptureService.hasMediaProjectionResult()) {
                        ScreenCaptureService.pauseStream(applicationContext)
                    }
                    if (RootScreenCaptureService.isRunning) {
                        RootScreenCaptureService.pause(applicationContext)
                    }
                    connectionManager.isCurrentlyStreaming = false
                    CommandResult(true, "Stream paused", null)
                }
                
                // ===== CLIPBOARD COMMANDS =====
                "clipboard_set" -> {
                    val text = command.stringParam("text") ?: return
                    commandExecutor.setClipboard(text)
                }
                "clipboard_get" -> {
                    commandExecutor.getClipboard()
                }
                
                // ===== DEBUG COMMANDS =====
                "debug_capture" -> {
                    // v2.15.0: Возвращает полное состояние capture сервисов
                    val rootState = RootScreenCaptureService.getDebugState()
                    val mediaState = mapOf(
                        "hasMediaProjection" to ScreenCaptureService.hasMediaProjectionResult()
                    )
                    val connectionState = mapOf(
                        "isConnected" to connectionManager.isConnected,
                        "isCurrentlyStreaming" to connectionManager.isCurrentlyStreaming,
                        "hasRootAccess" to connectionManager.hasRootAccess
                    )
                    
                    val allState = mapOf(
                        "rootCapture" to rootState,
                        "mediaProjection" to mediaState,
                        "connection" to connectionState,
                        "agentVersion" to com.sphere.agent.BuildConfig.VERSION_NAME
                    )
                    
                    SphereLog.i(TAG, "DEBUG_CAPTURE: $allState")
                    // Преобразуем в JSON строку для result
                    val resultJson = org.json.JSONObject(allState).toString()
                    CommandResult(true, resultJson, null)
                }
                
                // ===== EXTENDED INPUT COMMANDS =====
                "key_combo" -> {
                    val keysStr = command.stringParam("keys") ?: return
                    val keys = keysStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (keys.isEmpty()) return
                    commandExecutor.keyCombo(keys)
                }
                "pinch" -> {
                    val cx = command.intParam("cx") ?: return
                    val cy = command.intParam("cy") ?: return
                    val startDistance = command.intParam("start_distance") ?: 200
                    val endDistance = command.intParam("end_distance") ?: 100
                    val duration = command.intParam("duration") ?: 500
                    commandExecutor.pinch(cx, cy, startDistance, endDistance, duration)
                }
                "rotate" -> {
                    val cx = command.intParam("cx") ?: return
                    val cy = command.intParam("cy") ?: return
                    val radius = command.intParam("radius") ?: 100
                    val startAngle = command.floatParam("start_angle") ?: 0f
                    val endAngle = command.floatParam("end_angle") ?: 90f
                    val duration = command.intParam("duration") ?: 500
                    commandExecutor.rotate(cx, cy, radius, startAngle, endAngle, duration)
                }
                
                // ===== FILE OPERATIONS =====
                "file_list" -> {
                    val path = command.stringParam("path") ?: "/sdcard"
                    commandExecutor.listFiles(path)
                }
                "file_read" -> {
                    val path = command.stringParam("path") ?: return
                    val base64 = command.stringParam("base64")?.toBoolean() ?: false
                    commandExecutor.readFile(path, base64)
                }
                "file_delete" -> {
                    val path = command.stringParam("path") ?: return
                    commandExecutor.deleteFile(path)
                }
                "mkdir" -> {
                    val path = command.stringParam("path") ?: return
                    commandExecutor.createDirectory(path)
                }
                
                // ===== LOGCAT =====
                "logcat" -> {
                    val lines = command.intParam("lines") ?: 100
                    val filter = command.stringParam("filter")
                    commandExecutor.getLogcat(lines, filter)
                }
                "logcat_clear" -> {
                    commandExecutor.clearLogcat()
                }
                
                // ===== UI / HIERARCHY =====
                "get_hierarchy" -> {
                    commandExecutor.getUiHierarchy()
                }
                "screenshot_base64" -> {
                    commandExecutor.screenshotBase64()
                }
                
                // ===== XPATH POOL v2.16.0 =====
                "xpath_pool" -> {
                    val xpathsRaw = command.params?.get("xpaths")
                    val xpaths = when (xpathsRaw) {
                        is List<*> -> xpathsRaw.filterIsInstance<String>()
                        is String -> xpathsRaw.split("\n").filter { it.isNotBlank() }
                        else -> emptyList()
                    }
                    if (xpaths.isEmpty()) {
                        CommandResult(false, null, "xpath_pool requires 'xpaths' list")
                    } else {
                        val timeout = command.intParam("timeout") ?: 5000
                        val retryCount = command.intParam("retry_count") ?: 3
                        val retryInterval = command.intParam("retry_interval") ?: 1000
                        commandExecutor.xpathPool(xpaths, timeout, retryCount, retryInterval)
                    }
                }
                
                // ===== EXTENDED APP COMMANDS =====
                "clear_app_data" -> {
                    val packageName = command.stringParam("package") ?: return
                    commandExecutor.clearAppData(packageName)
                }
                "list_packages" -> {
                    commandExecutor.listPackages()
                }
                "launch_app" -> {
                    val packageName = command.stringParam("package") ?: return
                    commandExecutor.launchApp(packageName)
                }
                "force_stop" -> {
                    val packageName = command.stringParam("package") ?: return
                    commandExecutor.forceStopApp(packageName)
                }
                
                // ===== DEVICE STATE =====
                "get_battery" -> {
                    commandExecutor.getBatteryLevel()
                }
                "get_network" -> {
                    commandExecutor.getNetworkInfo()
                }
                "get_device_info" -> {
                    commandExecutor.getDeviceInfo()
                }
                
                // ===== SCRIPT COMMANDS =====
                "start_script" -> {
                    val scriptJson = command.stringParam("script") ?: return
                    val loopMode = command.stringParam("loop")?.toBoolean() ?: false
                    
                    try {
                        val script = scriptEngine.parseScript(scriptJson)
                        val runId = scriptEngine.startScript(script, loopMode)
                        CommandResult(true, runId, null)
                    } catch (e: Exception) {
                        CommandResult(false, null, "Failed to start script: ${e.message}")
                    }
                }
                "stop_script" -> {
                    val runId = command.stringParam("run_id") ?: return
                    val success = scriptEngine.stopScript(runId)
                    CommandResult(success, if (success) "Script stopped" else "Script not found", null)
                }
                "pause_script" -> {
                    val runId = command.stringParam("run_id") ?: return
                    val success = scriptEngine.pauseScript(runId)
                    CommandResult(success, if (success) "Script paused" else "Script not found", null)
                }
                "resume_script" -> {
                    val runId = command.stringParam("run_id") ?: return
                    val success = scriptEngine.resumeScript(runId)
                    CommandResult(success, if (success) "Script resumed" else "Script not found", null)
                }
                "get_scripts_status" -> {
                    val statuses = scriptEngine.getActiveScripts()
                    val statusJson = json.encodeToString(statuses)
                    CommandResult(true, statusJson, null)
                }
                "stop_all_scripts" -> {
                    scriptEngine.stopAllScripts()
                    CommandResult(true, "All scripts stopped", null)
                }
                
                // ===== UPDATE COMMAND =====
                "update_agent" -> {
                    SphereLog.i(TAG, "Received update_agent command")
                    
                    scope.launch {
                        try {
                            val updateManager = UpdateManager(applicationContext)
                            
                            val state = updateManager.checkForUpdates(force = true)
                            
                            when (state) {
                                is UpdateState.UpdateAvailable -> {
                                    SphereLog.i(TAG, "Update available: ${state.version.version}")
                                    updateManager.downloadUpdate(state.version)
                                }
                                is UpdateState.UpToDate -> {
                                    SphereLog.i(TAG, "Already up to date")
                                }
                                is UpdateState.Error -> {
                                    SphereLog.e(TAG, "Update error: ${state.message}")
                                }
                                else -> {}
                            }
                        } catch (e: Exception) {
                            SphereLog.e(TAG, "Update command failed", e)
                        }
                    }
                    CommandResult(true, "Update check initiated", null)
                }
                
                else -> CommandResult(false, null, "Unknown command: ${command.type}")
            }
        } finally {
            // Снимаем приоритет команды
            if (isInputCommand) {
                connectionManager.setCommandInProgress(false)
            }
        }

        // Логируем результат выполнения команды
        SphereLog.i(TAG, "=== COMMAND RESULT: ${command.type} -> success=${result.success} data=${result.data} error=${result.error} ===")

        command.command_id?.let { cmdId ->
            SphereLog.i(TAG, "Sending result for command_id=$cmdId")
            connectionManager.sendCommandResult(
                commandId = cmdId,
                success = result.success,
                data = result.data,
                error = result.error
            )
        } ?: run {
            SphereLog.w(TAG, "No command_id in command, cannot send result!")
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
    
    private fun ServerCommand.floatParam(vararg keys: String): Float? {
        for (k in keys) {
            val fromParams = params?.get(k)?.jsonPrimitive?.floatOrNull
            if (fromParams != null) return fromParams
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
    
    /**
     * ENTERPRISE: Вызывается когда пользователь swipe-удаляет приложение из Recent Apps
     * Перезапускаем сервис через alarm!
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        SphereLog.w(TAG, "onTaskRemoved - scheduling restart!")
        
        // Устанавливаем alarm на перезапуск через 1 секунду
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, AgentService::class.java).apply {
                action = ACTION_START
            }
            val pendingIntent = PendingIntent.getService(
                this, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = SystemClock.elapsedRealtime() + 1000
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            SphereLog.i(TAG, "Restart alarm scheduled for 1s from now")
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to schedule restart", e)
        }
        
        super.onTaskRemoved(rootIntent)
    }
    
    override fun onDestroy() {
        SphereLog.i(TAG, "AgentService destroyed - scheduling restart")
        isRunning = false
        
        try {
            // v2.11.0: Очистка server sync
            ScriptEventBus.setServerConnection(null)
            GlobalVariables.setServerConnection(null)
            
            connectionManager.disconnect()
            commandJob?.cancel()
            scope.cancel()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error during destroy", e)
        }
        
        // ENTERPRISE: Перезапускаем сервис если он был убит системой
        // Устанавливаем alarm на перезапуск через 2 секунды
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, AgentService::class.java).apply {
                action = ACTION_START
            }
            val pendingIntent = PendingIntent.getService(
                this, 2, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val triggerTime = SystemClock.elapsedRealtime() + 2000
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            SphereLog.i(TAG, "Service restart scheduled for 2s after destroy")
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to schedule restart on destroy", e)
        }
        
        super.onDestroy()
    }
}
