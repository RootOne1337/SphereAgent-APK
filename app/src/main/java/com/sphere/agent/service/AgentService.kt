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
                
                // v3.5.4 OPTIMIZATION: Watchdog alarm ОТКЛЮЧЁН - избыточно!
                // Heartbeat (15 сек) + WorkManager (15 мин) уже отслеживают состояние.
                // scheduleWatchdog каждые 5 минут создавал лишнюю нагрузку.
                // scheduleWatchdog(context)
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
    
    // v3.9.0: VPN компоненты (AWG/WG)
    private var vpnManager: com.sphere.agent.vpn.VpnManager? = null
    private var vpnHealthMonitor: com.sphere.agent.vpn.VpnHealthMonitor? = null
    private var vpnKillSwitch: com.sphere.agent.vpn.VpnKillSwitch? = null
    
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandJob: Job? = null
    
    // v3.2.1: Защита от повторной инициализации
    @Volatile private var isAgentInitialized = false
    
    // v2.26.0 ENTERPRISE: Batch Status Updates
    // Агрегирует статусы скрипта близкие по времени (<500ms) в один пакет
    private val statusBatchBuffer = java.util.concurrent.ConcurrentLinkedQueue<ScriptStatus>()
    private var batchFlushJob: Job? = null
    // v3.5.4 OPTIMIZATION: Увеличен интервал для снижения нагрузки
    // Было: 500ms = 120 проверок/мин
    // Стало: 2000ms = 30 проверок/мин (4x меньше)
    private val BATCH_FLUSH_INTERVAL_MS = 2000L
    // v3.6.2: Size cap for batch buffer to prevent OOM during long disconnects (#36)
    private val MAX_BATCH_BUFFER_SIZE = 200  // Флаш каждые 2 секунды (было 500ms!)
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
            
            // v3.6.0: batchFlushJob теперь ленивый — запускается только при первом скрипте
            // startBatchFlushJob() — УБРАНО! Запускается в addStatusToBatch()
            
            // v3.9.0: Инициализация VPN компонентов (AWG/WG)
            initializeVpnComponents()
            
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
                    "execution_id" to status.executionId,  // v3.5.3: Full UUID for backend
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
                    "execution_id" to status.executionId,  // v3.5.3: Full UUID for backend
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
        // v3.6.2: Enforce size cap — drop oldest if buffer full (#36)
        while (statusBatchBuffer.size >= MAX_BATCH_BUFFER_SIZE) {
            statusBatchBuffer.poll()
        }
        
        // Добавляем в буфер
        statusBatchBuffer.add(status)
        
        // v3.6.0: Ленивый запуск flush job — только когда реально нужен
        if (batchFlushJob == null || batchFlushJob?.isActive != true) {
            startBatchFlushJob()
        }
        
        // Если буфер переполнен (>50 статусов) - форсируем flush
        if (statusBatchBuffer.size > 50) {
            scope.launch {
                flushStatusBatch()
            }
        }
    }
    
    /**
     * v3.6.0 OPTIMIZED: Полностью ленивый batch flush
     * 
     * НЕ запускается при onCreate! Стартует только при первом addStatusToBatch().
     * Автоматически завершается когда буфер пуст 30 секунд подряд.
     */
    private fun startBatchFlushJob() {
        batchFlushJob?.cancel()
        batchFlushJob = scope.launch {
            var emptyChecks = 0
            while (isActive) {
                delay(BATCH_FLUSH_INTERVAL_MS)
                if (statusBatchBuffer.isNotEmpty()) {
                    flushStatusBatch()
                    emptyChecks = 0
                } else {
                    emptyChecks++
                    // v3.6.0: Самоостановка после 15 пустых проверок (30 сек)
                    if (emptyChecks >= 15) {
                        SphereLog.d(TAG, "Batch flush job self-stopped (no data for 30s)")
                        break
                    }
                }
            }
        }
        SphereLog.d(TAG, "Batch flush job started (interval=${BATCH_FLUSH_INTERVAL_MS}ms)")
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
                            "execution_id" to status.executionId,  // v3.5.3: Full UUID for backend
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
     * v3.9.0: Инициализация VPN компонентов (AWG/WG)
     * 
     * Создаёт VpnManager, VpnHealthMonitor и VpnKillSwitch.
     * VPN НЕ активируется автоматически — только по команде vpn_config от сервера.
     */
    private fun initializeVpnComponents() {
        try {
            // VPN Manager — программное управление AWG/WG туннелем
            vpnManager = com.sphere.agent.vpn.VpnManager(this)
            
            // VPN Kill Switch — блокировка трафика при падении VPN
            vpnKillSwitch = com.sphere.agent.vpn.VpnKillSwitch().also {
                it.initialize(this)
            }
            
            // VPN Health Monitor — мониторинг здоровья VPN (каждые 30с)
            vpnHealthMonitor = com.sphere.agent.vpn.VpnHealthMonitor(
                vpnManager = vpnManager!!,
                onHealthReport = { report ->
                    // Отправляем health report на сервер через WebSocket
                    try {
                        val message = json.encodeToString(
                            mapOf(
                                "type" to "vpn_health_report",
                                "report" to report
                            )
                        )
                        connectionManager.sendMessage(message)
                    } catch (e: Exception) {
                        SphereLog.e(TAG, "Ошибка отправки VPN health report", e)
                    }
                }
            )
            
            SphereLog.i(TAG, "VPN компоненты инициализированы (VpnManager + HealthMonitor + KillSwitch)")
        } catch (e: Exception) {
            SphereLog.e(TAG, "Ошибка инициализации VPN компонентов", e)
        }
    }
    
    /**
     * v3.9.0: Обработка VPN команд от сервера
     * 
     * Команды:
     * - vpn_config: установка конфига + опциональная активация
     * - vpn_activate: активация VPN с текущим конфигом
     * - vpn_deactivate: деактивация VPN
     * - vpn_status: получение полного статуса VPN
     * - vpn_health: запрос немедленной проверки здоровья
     * - vpn_killswitch: включение/выключение kill-switch
     */
    private suspend fun handleVpnCommand(command: ServerCommand): CommandResult {
        val vm = vpnManager ?: return CommandResult(false, null, "VPN Manager не инициализирован")
        
        return when (command.type) {
            "vpn_config" -> {
                // Установка VPN конфига от сервера
                val configText = command.stringParam("config_text") ?: return CommandResult(false, null, "config_text обязателен")
                val configType = command.stringParam("config_type") ?: "awg"
                val activate = command.stringParam("activate")?.toBoolean() ?: true
                
                SphereLog.i(TAG, "VPN конфиг получен: type=$configType, activate=$activate, ${configText.length} символов")
                
                vm.setConfig(configText, configType)
                
                if (activate) {
                    val result = vm.activate()
                    val success = result["success"] == true
                    
                    // Запускаем health monitor при успешной активации
                    if (success) {
                        vpnHealthMonitor?.resetRecoverCounter()
                        vpnHealthMonitor?.start()
                    }
                    
                    val resultJson = json.encodeToString(result.mapValues { it.value?.toString() ?: "" })
                    CommandResult(success, resultJson, result["error"]?.toString())
                } else {
                    CommandResult(true, "VPN конфиг установлен (без активации)", null)
                }
            }
            
            "vpn_activate" -> {
                if (vm.configText.isEmpty()) {
                    return CommandResult(false, null, "VPN конфиг не установлен — сначала отправьте vpn_config")
                }
                
                val result = vm.activate()
                val success = result["success"] == true
                
                if (success) {
                    vpnHealthMonitor?.resetRecoverCounter()
                    vpnHealthMonitor?.start()
                    
                    // Включаем kill-switch если запрошено
                    val enableKillSwitch = command.stringParam("kill_switch")?.toBoolean() ?: false
                    if (enableKillSwitch) {
                        vpnKillSwitch?.enable()
                    }
                }
                
                val resultJson = json.encodeToString(result.mapValues { it.value?.toString() ?: "" })
                CommandResult(success, resultJson, result["error"]?.toString())
            }
            
            "vpn_deactivate" -> {
                // Выключаем kill-switch перед деактивацией VPN
                vpnKillSwitch?.disable()
                vpnHealthMonitor?.stop()
                
                val result = vm.deactivate()
                val resultJson = json.encodeToString(result.mapValues { it.value?.toString() ?: "" })
                CommandResult(true, resultJson, null)
            }
            
            "vpn_status" -> {
                val status = vm.getStatus().toMutableMap()
                status["kill_switch"] = vpnKillSwitch?.checkStatus() ?: emptyMap<String, Any?>()
                status["health_monitor_running"] = vpnHealthMonitor?.isRunning ?: false
                status["last_health_report"] = vpnHealthMonitor?.lastHealthReport ?: emptyMap<String, Any?>()
                
                val resultJson = json.encodeToString(status.mapValues { it.value?.toString() ?: "" })
                CommandResult(true, resultJson, null)
            }
            
            "vpn_health" -> {
                // Немедленная проверка здоровья
                val report = vpnHealthMonitor?.lastHealthReport ?: mapOf("error" to "Health monitor не запущен")
                val resultJson = json.encodeToString(report.mapValues { it.value?.toString() ?: "" })
                CommandResult(true, resultJson, null)
            }
            
            "vpn_killswitch" -> {
                val enable = command.stringParam("enable")?.toBoolean() ?: true
                val ks = vpnKillSwitch ?: return CommandResult(false, null, "KillSwitch не инициализирован")
                
                val success = if (enable) ks.enable() else ks.disable()
                val status = ks.checkStatus()
                val resultJson = json.encodeToString(status.mapValues { it.value?.toString() ?: "" })
                CommandResult(success, resultJson, if (!success) "Ошибка управления kill-switch" else null)
            }
            
            else -> CommandResult(false, null, "Неизвестная VPN команда: ${command.type}")
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
        
        // v3.6.0: ScriptLogSender запускается ЛЕНИВО — только при первом startExecution()
        // Был: ScriptLogSender.start() в initializeServerSync() — flush job крутился ВСЕГДА
        com.sphere.agent.script.ScriptLogSender.setServerConnection(serverConnection)
        // com.sphere.agent.script.ScriptLogSender.start() — УБРАНО! Запускается лениво
        
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
                // v3.6.0: Watchdog ПОЛНОСТЬЮ ОТКЛЮЧЁН — Heartbeat (15с) + WorkManager (15м) достаточно
                // scheduleWatchdog(this)
            }
            ACTION_WATCHDOG -> {
                // v3.6.0: Watchdog ОТКЛЮЧЁН — игнорируем старые alarms
                SphereLog.d(TAG, "Watchdog triggered (legacy) - ignoring, heartbeat handles this")
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
        // v3.2.1: Защита от повторной инициализации
        if (isAgentInitialized) {
            SphereLog.w(TAG, "Agent already initialized, skipping")
            return
        }
        isAgentInitialized = true
        
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

                // v3.2.0 ENTERPRISE: H.264 стрим запускается автоматически после registered
                // НЕ запускаем здесь - запустим после регистрации на сервере
                SphereLog.i(TAG, "H.264 stream will auto-start after registration")

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
    
    /**
     * v3.8.0: Стрим НЕ запускается автоматически!
     * Запуск ТОЛЬКО по команде start_stream от frontend/viewer.
     * Это экономит ресурсы на 1000+ эмуляторах — ноль overhead когда никто не смотрит.
     * Предыдущее поведение (auto-start в PAUSED) всё равно держало foreground service.
     */
    private suspend fun autoStartH264Stream() {
        // v3.8.0: НЕ запускаем foreground service заранее
        // Стрим стартует on-demand по команде start_stream
        SphereLog.i(TAG, "Stream service NOT auto-started (on-demand only v3.8.0)")
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
        
        // v3.2.0 ENTERPRISE: При успешной регистрации - автозапуск H.264 стрима!
        // 1000+ эмуляторов - никакого ручного START на каждом!
        if (command.type == "registered") {
            SphereLog.i(TAG, "✅ Agent registered on server")
            scope.launch {
                try {
                    // Синхронизация переменных
                    GlobalVariables.fullSyncFromServer()
                    SphereLog.i(TAG, "Full sync requested")
                    
                    // v3.6.0: H264 auto-start ОТКЛЮЧЁН!
                    // Был: autoStartH264Stream() — запускал FOREGROUND SERVICE даже без viewers
                    // Это создавало: +1 процесс, +notification, +память на КАЖДОМ эмуляторе
                    // Теперь: H264 стартует ТОЛЬКО по команде start_stream от frontend
                    SphereLog.i(TAG, "H.264 will start on-demand via start_stream command")
                } catch (e: Exception) {
                    SphereLog.e(TAG, "Failed during post-registration setup", e)
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

                    // v3.2.0 ENTERPRISE: ТОЛЬКО H.264 ROOT STREAMING!
                    // Никакого JPEG fallback - это вызывало путаницу и переключение режимов
                    // H.264 ROOT = hardware encoding, низкий трафик, стабильно
                    
                    // Рассчитываем bitrate из quality
                    val bitrate = when {
                        quality < 30 -> 200_000   // 200 Kbps - ultra low
                        quality < 50 -> 400_000   // 400 Kbps - low
                        quality < 70 -> 800_000   // 800 Kbps - medium
                        quality < 90 -> 1_500_000 // 1.5 Mbps - high
                        else -> 3_000_000         // 3 Mbps - ultra
                    }
                    
                    if (!connectionManager.hasRootAccess) {
                        SphereLog.e(TAG, "❌ No ROOT access - cannot stream!")
                        CommandResult(false, null, "ROOT access required for streaming")
                    } else {
                        SphereLog.i(TAG, "🎬 Starting scrcpy stream (bitrate=${bitrate/1000}Kbps, fps=$fps)...")
                        
                        // v3.7.1: Resume scrcpy-server stream
                        if (ScrcpyStreamService.isRunning) {
                            ScrcpyStreamService.resume(applicationContext, fps = fps, bitrate = bitrate)
                        } else {
                            ScrcpyStreamService.start(applicationContext, bitrate = bitrate, fps = fps)
                            delay(500)
                            ScrcpyStreamService.resume(applicationContext, fps = fps, bitrate = bitrate)
                        }
                        
                        delay(200)
                        connectionManager.isCurrentlyStreaming = true
                        SphereLog.i(TAG, "✅ scrcpy stream ACTIVE!")
                        CommandResult(true, "scrcpy stream started", null)
                    }
                }
                
                "stop_stream" -> {
                    // v3.7.1: Пауза scrcpy-server стрима
                    SphereLog.i(TAG, "⏸ Pausing scrcpy stream...")
                    
                    if (ScrcpyStreamService.isRunning) {
                        ScrcpyStreamService.pause(applicationContext)
                    }
                    // Также останавливаем legacy screenrecord если работает
                    if (H264RootStreamService.isRunning) {
                        H264RootStreamService.pause(applicationContext)
                    }
                    connectionManager.isCurrentlyStreaming = false
                    SphereLog.i(TAG, "✅ Stream PAUSED (no traffic)")
                    CommandResult(true, "Stream paused", null)
                }
                
                // v3.3.0 ENTERPRISE: Request keyframe to prevent stream freeze
                "request_keyframe" -> {
                    SphereLog.d(TAG, "🔑 Keyframe requested by viewer")
                    
                    // v3.7.1: Request keyframe (scrcpy auto via i-frame-interval)
                    if (ScrcpyStreamService.isRunning) {
                        ScrcpyStreamService.requestKeyframe()
                    }
                    if (H264RootStreamService.isRunning) {
                        H264RootStreamService.requestKeyframe()
                    }
                    if (ScreenCaptureService.isStreaming()) {
                        ScreenCaptureService.requestKeyframe()
                    }
                    
                    CommandResult(true, "Keyframe requested", null)
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
                    // v3.1.0: Возвращает полное состояние capture сервисов
                    val rootJpegState = RootScreenCaptureService.getDebugState()
                    val rootH264State = H264RootStreamService.getDebugState()
                    val scrcpyState = ScrcpyStreamService.getDebugState()
                    val mediaState = mapOf(
                        "hasMediaProjection" to ScreenCaptureService.hasMediaProjectionResult()
                    )
                    val connectionState = mapOf(
                        "isConnected" to connectionManager.isConnected,
                        "isCurrentlyStreaming" to connectionManager.isCurrentlyStreaming,
                        "hasRootAccess" to connectionManager.hasRootAccess
                    )
                    
                    val allState = mapOf(
                        "rootJpegCapture" to rootJpegState,
                        "rootH264Stream" to rootH264State,
                        "scrcpyStream" to scrcpyState,
                        "mediaProjection" to mediaState,
                        "connection" to connectionState,
                        "agentVersion" to com.sphere.agent.BuildConfig.VERSION_NAME
                    )
                    
                    SphereLog.i(TAG, "DEBUG_CAPTURE: $allState")
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
                
                // ===== VPN COMMANDS (v3.9.0 AWG/WG) =====
                "vpn_config", "vpn_activate", "vpn_deactivate", 
                "vpn_status", "vpn_health", "vpn_killswitch" -> {
                    handleVpnCommand(command)
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
        // v3.6.0 CRITICAL FIX: Restart alarm УДАЛЁН!
        // START_STICKY + WorkManager (15 мин) гарантируют перезапуск.
        // Alarm создавал crash → restart → crash loop при проблемах.
        SphereLog.w(TAG, "onTaskRemoved — relying on START_STICKY + WorkManager")
        super.onTaskRemoved(rootIntent)
    }
    
    override fun onDestroy() {
        // v3.6.0 CRITICAL FIX: Restart alarm УДАЛЁН!
        // START_STICKY автоматически перезапускает Foreground Service.
        // WorkManager (15 мин) — backup watchdog.
        // Alarm-перезапуск создавал: crash → restart 10s → crash → restart = бесконечный loop
        SphereLog.i(TAG, "AgentService destroyed — relying on START_STICKY + WorkManager")
        isRunning = false
        
        try {
            // v3.9.0: Graceful shutdown VPN компонентов
            vpnHealthMonitor?.destroy()
            vpnManager?.destroy()
            // Kill-switch снимаем при остановке агента
            kotlinx.coroutines.runBlocking {
                vpnKillSwitch?.disable()
            }
            
            ScriptEventBus.setServerConnection(null)
            GlobalVariables.setServerConnection(null)
            // v3.6.1: Shutdown singleton scopes to prevent zombie coroutines
            ScriptEventBus.shutdown()
            GlobalVariables.shutdown()
            com.sphere.agent.script.ScriptLogSender.stop()
            com.sphere.agent.script.ScriptLogSender.setServerConnection(null)
            // v3.6.2: shutdown() instead of disconnect() to release OkHttpClient (#33)
            connectionManager.shutdown()
            commandJob?.cancel()
            scope.cancel()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error during destroy", e)
        }
        
        super.onDestroy()
    }
}
