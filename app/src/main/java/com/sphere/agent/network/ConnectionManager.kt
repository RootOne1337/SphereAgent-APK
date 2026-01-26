package com.sphere.agent.network

import android.content.Context
import android.util.Log
import com.sphere.agent.BuildConfig
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.core.DeviceInfo
import com.sphere.agent.core.HealthMetricsCollector
import com.sphere.agent.core.SlotConfig
import com.sphere.agent.core.SlotAssignment
import com.sphere.agent.data.SettingsRepository
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * ConnectionManager - Управление WebSocket соединением
 * 
 * Функционал:
 * - Автоматическое подключение к серверу
 * - Reconnect с exponential backoff
 * - Fallback на резервные серверы
 * - Heartbeat (ping-pong)
 * - Binary streaming для экрана
 * - JSON команды
 */

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val serverUrl: String) : ConnectionState()
    data class Connected(val serverUrl: String) : ConnectionState()
    data class Error(val message: String, val throwable: Throwable? = null) : ConnectionState()
}

@Serializable
sealed class AgentMessage {
    @Serializable
    data class Hello(
        val type: String = "hello",
        val device_id: String,
        val device_name: String,
        val device_model: String,
        val android_version: String,
        val agent_version: String,
        val capabilities: List<String> = listOf("screen_capture", "touch", "swipe", "key_event", "shell"),
        // Расширенная диагностика для enterprise управления
        val has_accessibility: Boolean = false,
        val has_root: Boolean = false,
        val screen_width: Int = 0,
        val screen_height: Int = 0,
        val is_streaming: Boolean = false,
        // v2.26.0 ENTERPRISE: Slot Assignment System
        val slot_id: String? = null,          // "ld:0", "memu:1", "auto:abc123"
        val slot_source: String? = null       // "ldplayer", "memu", "nox", "sdcard", "manual", "auto"
    ) : AgentMessage()
    
    @Serializable
    data class Heartbeat(
        val type: String = "heartbeat",
        val timestamp: Long = System.currentTimeMillis(),
        // Enterprise статусы для обновления в реальном времени
        val has_accessibility: Boolean = false,
        val has_root: Boolean = false,
        val is_streaming: Boolean = false,
        val battery: Int = 100,
        val charging: Boolean = false,
        // v2.26.0 ENTERPRISE: Health Metrics для мониторинга флота
        val cpu_usage: Int = 0,
        val memory_used_mb: Int = 0,
        val memory_total_mb: Int = 0,
        val memory_percent: Int = 0,
        val storage_available_mb: Int = 0,
        val uptime_seconds: Long = 0,
        val app_memory_mb: Int = 0,
        val health_warnings: List<String> = emptyList()
    ) : AgentMessage()
    
    @Serializable
    data class CommandResult(
        val type: String = "command_result",
        val command_id: String,
        val success: Boolean,
        val data: String? = null,
        val error: String? = null
    ) : AgentMessage()
}

@Serializable
data class ServerCommand(
    val type: String,
    val command_id: String? = null,
    // Совместимость с backend: команды приходят как {type, command_id, params:{...}}
    val params: Map<String, JsonElement>? = null,
    val action: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val duration: Int? = null,
    val keyCode: Int? = null,
    val command: String? = null,
    val quality: Int? = null,
    val fps: Int? = null
)

class ConnectionManager(
    private val context: Context,
    private val agentConfig: AgentConfig
) {
    companion object {
        private const val TAG = "ConnectionManager"
        // v2.27.0: ENTERPRISE Ultra-Stability - агрессивный reconnect для фарма
        private const val MAX_RECONNECT_DELAY = 10_000L  // 10 секунд max (было 15)
        private const val INITIAL_RECONNECT_DELAY = 300L  // 0.3 секунды (было 0.5)
        private const val HEARTBEAT_INTERVAL = 15_000L  // 15 секунд
        private const val FAST_RECONNECT_ATTEMPTS = 10  // Первые 10 попыток без delay (было 5)
        
        // v2.27.0: Connection Watchdog - проверяет и восстанавливает соединение
        private const val CONNECTION_WATCHDOG_INTERVAL = 30_000L  // Проверка каждые 30 сек
        private const val PING_TIMEOUT_MS = 10_000L  // Таймаут на ping проверку
        
        // v2.7.0: Специальные таймауты для перегруженных эмуляторов (1 FPS)
        private const val LOW_FPS_COMMAND_TIMEOUT = 60_000L  // 60 секунд на команду
        private const val LOW_FPS_RECONNECT_GRACE = 30_000L  // 30 секунд grace period
        
        // v2.26.0: ENTERPRISE Offline Buffer - сохраняем сообщения при disconnect
        private const val OFFLINE_BUFFER_MAX_SIZE = 100  // Максимум 100 сообщений в буфере
        private const val OFFLINE_BUFFER_TTL_MS = 5 * 60 * 1000L  // 5 минут TTL для сообщений
    }
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        encodeDefaults = true
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)  // v2.6.0: Быстрее таймаут (было 30)
        .readTimeout(0, TimeUnit.SECONDS)  // Без таймаута для WebSocket
        .writeTimeout(15, TimeUnit.SECONDS)  // v2.6.0: Быстрее (было 30)
        .pingInterval(20, TimeUnit.SECONDS)  // v2.6.0: Включаем OkHttp ping для keep-alive!
        .retryOnConnectionFailure(true)  // v2.6.0: Авто-retry
        .build()
    
    private val settingsRepository = SettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // v2.26.0 ENTERPRISE: Health Metrics Collector
    private val healthMetrics = HealthMetricsCollector(context)
    
    // v2.26.0 ENTERPRISE: Slot Configuration
    private val slotConfig = SlotConfig(context)
    
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null  // v2.27.0: Connection Watchdog
    
    private val isConnecting = AtomicBoolean(false)
    private val connectionMutex = Mutex()  // v2.0.4: Mutex против параллельных connect
    private var reconnectJob: Job? = null  // v2.0.4: Отменяемый reconnect job
    private val shouldReconnect = AtomicBoolean(true)
    private val reconnectAttempt = AtomicInteger(0)
    private val currentServerIndex = AtomicInteger(0)
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // replay = 1 - если команда придёт до подписки, она будет сохранена и обработана
    private val _commands = MutableSharedFlow<ServerCommand>(replay = 1, extraBufferCapacity = 64)
    val commands: SharedFlow<ServerCommand> = _commands.asSharedFlow()
    
    private val _screenDataCallback = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    val screenData: SharedFlow<ByteArray> = _screenDataCallback.asSharedFlow()
    
    // Callback для отправки экрана
    var onRequestScreenFrame: (() -> ByteArray?)? = null
    
    // Состояние устройства для диагностики
    @Volatile var hasRootAccess: Boolean = false
    @Volatile var isCurrentlyStreaming: Boolean = false
    
    // Throttling фреймов - чтобы не забивать WebSocket
    @Volatile private var lastFrameSentTime: Long = 0
    @Volatile private var pendingFrames: Int = 0
    // v2.7.0: Enterprise стабильность + 1FPS support
    // При 1 FPS системе нужно больше времени на обработку
    private val maxPendingFrames = 1  // Максимум 1 несент фрейм
    private val minFrameInterval = 100L  // 100ms между фреймами = 10 FPS стабильных
    
    // v2.7.0: Детекция медленной системы
    @Volatile private var lastCommandTime: Long = 0
    @Volatile private var slowSystemDetected: Boolean = false
    private val slowSystemThreshold = 5000L  // Если команда > 5 секунд - система медленная
    
    // Приоритет командам - пауза стрима при отправке команды
    @Volatile private var commandInProgress: Boolean = false
    
    // v2.26.0: ENTERPRISE Offline Buffer - буферизация сообщений при disconnect
    private data class BufferedMessage(
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val priority: Int = 0  // 0 = normal, 1 = high (script_status)
    )
    private val offlineBuffer = java.util.concurrent.ConcurrentLinkedQueue<BufferedMessage>()
    @Volatile private var offlineBufferDropped = 0  // Счётчик потерянных сообщений
    
    // v2.26.0: Jitter для script_status (распределение нагрузки при 1000+ устройств)
    private val jitterRandom = java.util.Random()
    
    /**
     * Подключение к серверу
     */
    fun connect() {
        if (isConnecting.getAndSet(true)) {
            Log.d(TAG, "Already connecting, skipping")
            SphereLog.w(TAG, "Already connecting, skipping")
            return
        }
        
        shouldReconnect.set(true)
        reconnectAttempt.set(0)
        currentServerIndex.set(0)
        
        scope.launch {
            connectToNextServer()
        }
    }
    
    private suspend fun connectToNextServer() {
        val serverUrls = agentConfig.getServerUrls()
        
        if (serverUrls.isEmpty()) {
            _connectionState.value = ConnectionState.Error("No servers configured")
            isConnecting.set(false)
            return
        }
        
        val serverIndex = currentServerIndex.get() % serverUrls.size
        val serverUrl = serverUrls[serverIndex]

        Log.d(TAG, "Connecting to server: $serverUrl (attempt ${reconnectAttempt.get() + 1})")
        SphereLog.i(TAG, "Connecting to server: $serverUrl (attempt ${reconnectAttempt.get() + 1})")
        _connectionState.value = ConnectionState.Connecting(serverUrl)
        
        try {
            val token = settingsRepository.getAuthTokenOnce() ?: agentConfig.deviceId
            
            // Исправляем формирование URL: если в serverUrl уже есть путь, просто добавляем токен
            val wsUrl = if (serverUrl.endsWith("/")) {
                "$serverUrl$token"
            } else {
                "$serverUrl/$token"
            }
            
            Log.d(TAG, "Final WebSocket URL: $wsUrl")
            SphereLog.i(TAG, "Final WebSocket URL: $wsUrl")
            
            val request = Request.Builder()
                .url(wsUrl)
                .header("User-Agent", "SphereAgent/${agentConfig.config.value.agent_version}")
                .header("X-Device-Id", agentConfig.deviceId)
                .build()
            
            webSocket = httpClient.newWebSocket(request, createWebSocketListener())
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            SphereLog.e(TAG, "Connection failed", e)
            handleConnectionError(e)
        }
    }
    
    private fun createWebSocketListener() = object : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            SphereLog.i(TAG, "WebSocket connected: ${response.request.url}")
            isConnecting.set(false)
            reconnectAttempt.set(0)
            
            val serverUrl = response.request.url.toString()
            _connectionState.value = ConnectionState.Connected(serverUrl)
            
            // Сохраняем успешный сервер
            scope.launch {
                settingsRepository.saveLastConnectedServer(serverUrl)
            }
            
            // Отправляем приветствие
            sendHelloMessage(webSocket)
            
            // Запускаем heartbeat
            startHeartbeat(webSocket)
            
            // v2.27.0: Запускаем Connection Watchdog для автовосстановления
            startConnectionWatchdog()
            
            // v2.26.0: Flush offline buffer при восстановлении соединения
            flushOfflineBuffer(webSocket)
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received text message: ${text.take(200)}")
            SphereLog.d(TAG, "Received text message: ${text.take(200)}")
            
            try {
                val command = json.decodeFromString<ServerCommand>(text)
                
                scope.launch {
                    _commands.emit(command)
                }
                
                // Обработка специальных команд
                when (command.type) {
                    "request_frame" -> {
                        onRequestScreenFrame?.invoke()?.let { frame ->
                            sendBinaryFrame(frame)
                        }
                    }
                    "ping" -> {
                        sendPong(webSocket, command.command_id)
                    }
                    "config_update" -> {
                        scope.launch {
                            agentConfig.loadRemoteConfig()
                        }
                    }
                    // v2.26.0 ENTERPRISE: Обработка регистрации с assignment
                    "registered" -> {
                        handleRegisteredMessage(text)
                    }
                    // v2.26.0: Slot assignment update (динамическое изменение)
                    "slot_assignment" -> {
                        handleSlotAssignmentUpdate(text)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse command", e)
                SphereLog.e(TAG, "Failed to parse command", e)
            }
        }
        
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Binary frame от сервера (редко используется)
            Log.d(TAG, "Received binary message: ${bytes.size} bytes")
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            SphereLog.w(TAG, "WebSocket closed: $code $reason")
            
            when (code) {
                1001 -> {
                    // v2.0.4: Connection replaced - НЕ reconnect
                    Log.d(TAG, "Connection replaced - NOT reconnecting (code 1001)")
                    _connectionState.value = ConnectionState.Disconnected
                    return
                }
                4003 -> {
                    // v2.0.4: Already connected - долгая задержка перед reconnect
                    Log.d(TAG, "Already connected on server - waiting 30s before retry")
                    SphereLog.w(TAG, "Already connected (code 4003) - waiting 30s")
                    _connectionState.value = ConnectionState.Disconnected
                    // Планируем reconnect через 30 секунд
                    scope.launch {
                        delay(30_000)
                        if (shouldReconnect.get()) {
                            reconnectAttempt.set(0)
                            isConnecting.set(false)
                            connectToNextServer()
                        }
                    }
                    return
                }
            }
            handleDisconnect()
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            val respInfo = response?.let { "HTTP ${it.code} ${it.message} url=${it.request.url}" } ?: "no_response"
            SphereLog.e(TAG, "WebSocket failure: $respInfo", t)
            handleConnectionError(t)
        }
    }
    
    private fun sendHelloMessage(ws: WebSocket) {
        val info = agentConfig.deviceInfo
        
        // Получаем реальные размеры экрана
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Проверяем статус accessibility
        val hasAccessibility = com.sphere.agent.service.SphereAccessibilityService.isServiceEnabled()
        
        // v2.26.0 ENTERPRISE: Определяем slot_id для привязки аккаунтов/прокси
        val (slotId, slotSource) = slotConfig.detectSlotId(info.deviceId)
        SphereLog.i(TAG, "Slot detected: $slotId (source: ${slotSource.name})")
        
        val hello = AgentMessage.Hello(
            device_id = info.deviceId,
            device_name = info.deviceName,
            device_model = info.deviceModel,
            android_version = info.androidVersion,
            // КРИТИЧНО: Версия ВСЕГДА из APK, не из remote config!
            agent_version = BuildConfig.VERSION_NAME,
            has_accessibility = hasAccessibility,
            has_root = hasRootAccess,
            screen_width = screenWidth,
            screen_height = screenHeight,
            is_streaming = isCurrentlyStreaming,
            // v2.26.0: Slot Assignment
            slot_id = slotId,
            slot_source = slotSource.name.lowercase()
        )
        
        val message = json.encodeToString(hello)
        ws.send(message)
        Log.d(TAG, "Sent hello: slot=$slotId, accessibility=$hasAccessibility, root=$hasRootAccess")
        SphereLog.i(TAG, "Hello sent: slot=$slotId, screen=${screenWidth}x${screenHeight}")
    }
    
    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // ENTERPRISE: Jitter чтобы распределить heartbeat по времени
            val initialJitter = Random.nextLong(0, 5_000L)
            delay(initialJitter)
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                
                if (_connectionState.value is ConnectionState.Connected) {
                    val hasAccessibility = com.sphere.agent.service.SphereAccessibilityService.isServiceEnabled()
                    
                    // v2.26.0 ENTERPRISE: Собираем health metrics
                    val metrics = healthMetrics.collectMetrics()
                    
                    val heartbeat = AgentMessage.Heartbeat(
                        has_accessibility = hasAccessibility,
                        has_root = hasRootAccess,
                        is_streaming = isCurrentlyStreaming,
                        battery = metrics.batteryLevel,
                        charging = metrics.batteryCharging,
                        // Health metrics
                        cpu_usage = metrics.cpuUsage,
                        memory_used_mb = metrics.memoryUsedMb,
                        memory_total_mb = metrics.memoryTotalMb,
                        memory_percent = metrics.memoryUsagePercent,
                        storage_available_mb = metrics.storageAvailableMb,
                        uptime_seconds = metrics.uptimeSeconds,
                        app_memory_mb = metrics.appMemoryMb,
                        health_warnings = metrics.warnings
                    )
                    val message = json.encodeToString(heartbeat)
                    ws.send(message)
                    
                    // Логируем с предупреждениями если есть
                    if (metrics.warnings.isNotEmpty()) {
                        Log.w(TAG, "Heartbeat with warnings: ${metrics.warnings}")
                    } else {
                        Log.d(TAG, "Sent heartbeat: accessibility=$hasAccessibility, cpu=${metrics.cpuUsage}%, mem=${metrics.memoryUsagePercent}%")
                    }
                }
            }
        }
    }
    
    private fun sendPong(ws: WebSocket, commandId: String?) {
        val pong = mapOf(
            "type" to "pong",
            "command_id" to commandId,
            "timestamp" to System.currentTimeMillis()
        )
        ws.send(json.encodeToString(pong))
    }
    
    /**
     * Отправка бинарного кадра экрана с throttling
     * 
     * Оптимизации:
     * - Не отправляем фреймы чаще minFrameInterval
     * - Пропускаем фреймы если команда в процессе (приоритет командам)
     * - Ограничиваем очередь несент фреймов
     */
    fun sendBinaryFrame(frame: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        
        // v2.15.0: Детальное логирование для отладки
        val wsExists = webSocket != null
        
        // Если команда в процессе - пропускаем фрейм (приоритет командам!)
        if (commandInProgress) {
            SphereLog.d(TAG, "sendBinaryFrame SKIP: commandInProgress=true")
            return false
        }
        
        // Throttling по времени
        if (now - lastFrameSentTime < minFrameInterval) {
            // Не логируем throttling - слишком часто
            return false
        }
        
        // Проверяем что WebSocket не перегружен
        if (pendingFrames >= maxPendingFrames) {
            SphereLog.d(TAG, "sendBinaryFrame SKIP: pendingFrames=$pendingFrames >= max=$maxPendingFrames")
            return false
        }
        
        val ws = webSocket
        if (ws == null) {
            SphereLog.w(TAG, "sendBinaryFrame SKIP: webSocket is NULL!")
            return false
        }
        
        pendingFrames++
        lastFrameSentTime = now
        
        val sent = ws.send(ByteString.of(*frame))
        
        if (sent) {
            // v2.15.0: Логируем каждый 10й успешный frame
            if (pendingFrames % 10 == 1) {
                SphereLog.i(TAG, "Frame SENT (size=${frame.size}, pending=$pendingFrames)")
            }
            // Уменьшаем счётчик после небольшой задержки (примерная оценка RTT)
            scope.launch {
                delay(100)
                if (pendingFrames > 0) pendingFrames--
            }
        } else {
            SphereLog.w(TAG, "Frame send FAILED (ws.send returned false)")
            pendingFrames--
        }
        
        return sent
    }
    
    /**
     * Устанавливает флаг "команда в процессе" - приостанавливает стрим
     */
    fun setCommandInProgress(inProgress: Boolean) {
        commandInProgress = inProgress
    }
    
    /**
     * Отправка результата команды с приоритетом
     */
    fun sendCommandResult(commandId: String, success: Boolean, data: String? = null, error: String? = null) {
        SphereLog.i(TAG, "=== SENDING COMMAND RESULT: cmdId=$commandId success=$success data=$data error=$error ===")
        
        // Приоритет команде - приостанавливаем стрим
        commandInProgress = true
        
        val result = AgentMessage.CommandResult(
            command_id = commandId,
            success = success,
            data = data,
            error = error
        )
        
        val sent = webSocket?.send(json.encodeToString(result)) ?: false
        SphereLog.i(TAG, "Command result sent: $sent (websocket=${webSocket != null})")
        
        // Разблокируем стрим после отправки
        scope.launch {
            delay(50)  // Небольшая задержка чтобы ответ точно ушёл
            commandInProgress = false
        }
    }
    
    /**
     * Отправка произвольного JSON сообщения с поддержкой Offline Buffer
     * 
     * v2.26.0 ENTERPRISE:
     * - При отсутствии соединения сохраняем в буфер
     * - При восстановлении соединения отправляем буфер
     * - TTL 5 минут для сообщений в буфере
     */
    fun sendMessage(message: String, priority: Int = 0): Boolean {
        val ws = webSocket
        
        // Если подключены - отправляем сразу
        if (ws != null && _connectionState.value is ConnectionState.Connected) {
            val sent = ws.send(message)
            if (sent) return true
        }
        
        // Не подключены или отправка не удалась - буферизируем
        bufferMessage(message, priority)
        return false
    }
    
    /**
     * v2.26.0: Отправка с jitter для распределения нагрузки при массовых операциях
     * Используется для script_status при 1000+ устройствах
     */
    suspend fun sendMessageWithJitter(message: String, minJitterMs: Long = 100, maxJitterMs: Long = 500): Boolean {
        // Случайная задержка 100-500ms
        val jitter = minJitterMs + jitterRandom.nextLong() % (maxJitterMs - minJitterMs + 1)
        delay(jitter)
        return sendMessage(message, priority = 1)
    }
    
    /**
     * v2.26.0: Буферизация сообщения при disconnect
     */
    private fun bufferMessage(message: String, priority: Int) {
        // Проверяем размер буфера
        if (offlineBuffer.size >= OFFLINE_BUFFER_MAX_SIZE) {
            // Удаляем старые сообщения с низким приоритетом
            val removed = offlineBuffer.poll()
            if (removed != null) {
                offlineBufferDropped++
                SphereLog.w(TAG, "Offline buffer full, dropped message (total dropped: $offlineBufferDropped)")
            }
        }
        
        offlineBuffer.add(BufferedMessage(message, System.currentTimeMillis(), priority))
        SphereLog.d(TAG, "Message buffered (buffer size: ${offlineBuffer.size})")
    }
    
    /**
     * v2.26.0: Flush буфера при восстановлении соединения
     */
    private fun flushOfflineBuffer(ws: WebSocket) {
        val now = System.currentTimeMillis()
        var sent = 0
        var expired = 0
        
        // Сортируем по приоритету (высокий приоритет первым)
        val messages = offlineBuffer.toList().sortedByDescending { it.priority }
        offlineBuffer.clear()
        
        for (buffered in messages) {
            // Проверяем TTL
            if (now - buffered.timestamp > OFFLINE_BUFFER_TTL_MS) {
                expired++
                continue
            }
            
            if (ws.send(buffered.message)) {
                sent++
            } else {
                // Возвращаем в буфер если не удалось отправить
                offlineBuffer.add(buffered)
            }
        }
        
        if (sent > 0 || expired > 0) {
            SphereLog.i(TAG, "Offline buffer flushed: sent=$sent, expired=$expired, remaining=${offlineBuffer.size}")
        }
    }
    
    /**
     * Немедленная отправка обновления ROOT статуса на сервер
     * Вызывается когда CommandExecutor подтверждает ROOT
     */
    fun sendRootStatusUpdate(hasRoot: Boolean) {
        hasRootAccess = hasRoot
        
        val hasAccessibility = com.sphere.agent.service.SphereAccessibilityService.isServiceEnabled()
        val update = mapOf(
            "type" to "status_update",
            "has_root" to hasRoot,
            "has_accessibility" to hasAccessibility,
            "is_streaming" to isCurrentlyStreaming,
            "timestamp" to System.currentTimeMillis()
        )
        
        try {
            val message = json.encodeToString(update)
            webSocket?.send(message)
            Log.i(TAG, "Sent ROOT status update: has_root=$hasRoot")
            SphereLog.i(TAG, "ROOT status update sent: $hasRoot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ROOT status update", e)
        }
    }
    
    // ========================================================================
    // v2.26.0 ENTERPRISE: Slot Assignment Handlers
    // ========================================================================
    
    // Callback для уведомления о полученном assignment
    var onAssignmentReceived: ((SlotAssignment) -> Unit)? = null
    
    /**
     * Обработка ответа "registered" с assignment от сервера
     * 
     * Формат:
     * {
     *   "type": "registered",
     *   "agent_id": "...",
     *   "slot_id": "ld:5",
     *   "assignment": {
     *     "account_id": "uuid",
     *     "account_username": "@user5",
     *     "proxy_id": "uuid",
     *     "proxy_config": {"type": "socks5", "host": "...", "port": ...},
     *     "auto_start_script": "uuid",
     *     "resume_execution": {...}
     *   }
     * }
     */
    private fun handleRegisteredMessage(messageJson: String) {
        try {
            val jsonElement = json.parseToJsonElement(messageJson)
            val jsonObject = jsonElement.jsonObject
            
            val slotId = jsonObject["slot_id"]?.jsonPrimitive?.contentOrNull
            val assignmentObj = jsonObject["assignment"]?.jsonObject
            
            if (slotId != null) {
                SphereLog.i(TAG, "✓ Registered with slot: $slotId")
                
                // Сохраняем slot_id на SD-карту для будущего восстановления
                slotConfig.saveSlotToSdCard(slotId)
            }
            
            if (assignmentObj != null) {
                val assignment = parseAssignment(slotId ?: "", assignmentObj)
                
                // Сохраняем локально
                slotConfig.saveAssignment(assignment)
                
                SphereLog.i(TAG, "📋 Assignment received: account=${assignment.accountUsername}, " +
                    "proxy=${assignment.proxyConfig != null}, autoStart=${assignment.autoStartScriptId != null}")
                
                // Уведомляем подписчиков (например AgentService для запуска auto-start)
                onAssignmentReceived?.invoke(assignment)
            } else {
                SphereLog.w(TAG, "No assignment in registered message - slot may be unassigned")
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to parse registered message", e)
        }
    }
    
    /**
     * Обработка динамического обновления assignment
     * (когда админ переназначает аккаунт/прокси на лету)
     */
    private fun handleSlotAssignmentUpdate(messageJson: String) {
        try {
            val jsonElement = json.parseToJsonElement(messageJson)
            val jsonObject = jsonElement.jsonObject
            
            val slotId = jsonObject["slot_id"]?.jsonPrimitive?.contentOrNull ?: return
            val assignmentObj = jsonObject["assignment"]?.jsonObject ?: return
            
            val assignment = parseAssignment(slotId, assignmentObj)
            slotConfig.saveAssignment(assignment)
            
            SphereLog.i(TAG, "🔄 Assignment updated: $slotId → ${assignment.accountUsername}")
            
            onAssignmentReceived?.invoke(assignment)
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to parse slot_assignment update", e)
        }
    }
    
    /**
     * Парсинг assignment из JSON
     */
    private fun parseAssignment(slotId: String, obj: kotlinx.serialization.json.JsonObject): SlotAssignment {
        val proxyObj = obj["proxy_config"]?.jsonObject
        val proxyConfig = if (proxyObj != null) {
            com.sphere.agent.core.ProxyConfig(
                type = proxyObj["type"]?.jsonPrimitive?.contentOrNull ?: "none",
                host = proxyObj["host"]?.jsonPrimitive?.contentOrNull,
                port = proxyObj["port"]?.jsonPrimitive?.intOrNull,
                username = proxyObj["username"]?.jsonPrimitive?.contentOrNull,
                password = proxyObj["password"]?.jsonPrimitive?.contentOrNull
            )
        } else null
        
        val resumeVars: Map<String, String>? = obj["resume_variables"]?.jsonObject?.let { varsObj ->
            val result = mutableMapOf<String, String>()
            for ((key, value) in varsObj.entries) {
                result[key] = value.jsonPrimitive.contentOrNull ?: ""
            }
            result.toMap()
        }
        
        return SlotAssignment(
            slotId = slotId,
            pcIdentifier = obj["pc_identifier"]?.jsonPrimitive?.contentOrNull,
            accountId = obj["account_id"]?.jsonPrimitive?.contentOrNull,
            accountUsername = obj["account_username"]?.jsonPrimitive?.contentOrNull,
            accountSession = obj["account_session"]?.jsonPrimitive?.contentOrNull,
            proxyId = obj["proxy_id"]?.jsonPrimitive?.contentOrNull,
            proxyConfig = proxyConfig,
            groupId = obj["group_id"]?.jsonPrimitive?.contentOrNull,
            templateId = obj["template_id"]?.jsonPrimitive?.contentOrNull,
            autoStartScriptId = obj["auto_start_script"]?.jsonPrimitive?.contentOrNull,
            resumeExecutionId = obj["resume_execution_id"]?.jsonPrimitive?.contentOrNull,
            resumeStepIndex = obj["resume_step_index"]?.jsonPrimitive?.intOrNull,
            resumeVariables = resumeVars
        )
    }
    
    // ========================================================================
    // v2.27.0 ENTERPRISE: Connection Watchdog - автовосстановление соединения
    // ========================================================================
    
    /**
     * Connection Watchdog - периодически проверяет соединение и восстанавливает
     * 
     * Запускается при успешном подключении, работает в фоне:
     * - Каждые 30 секунд проверяет состояние WebSocket
     * - Если disconnect > 10 сек без reconnect - принудительный reconnect
     * - Логирует состояние для отладки
     */
    private fun startConnectionWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            SphereLog.i(TAG, "🐕 Connection Watchdog started (interval=${CONNECTION_WATCHDOG_INTERVAL/1000}s)")
            // ENTERPRISE: Jitter чтобы watchdog не срабатывал синхронно
            val initialJitter = Random.nextLong(0, 10_000L)
            delay(initialJitter)
            
            while (isActive) {
                delay(CONNECTION_WATCHDOG_INTERVAL)
                
                val currentState = _connectionState.value
                val ws = webSocket
                
                when {
                    currentState is ConnectionState.Connected && ws != null -> {
                        // Всё хорошо, логируем состояние
                        SphereLog.d(TAG, "🐕 Watchdog: Connected OK")
                    }
                    
                    currentState is ConnectionState.Disconnected -> {
                        // Отключены и не переподключаемся? Принудительный reconnect!
                        if (!isConnecting.get() && shouldReconnect.get()) {
                            SphereLog.w(TAG, "🐕 Watchdog: Disconnected without reconnect! Forcing reconnect...")
                            reconnectAttempt.set(0)  // Сброс счётчика для быстрого reconnect
                            isConnecting.set(true)
                            connectToNextServer()
                        }
                    }
                    
                    currentState is ConnectionState.Error -> {
                        // Ошибка? Принудительный reconnect!
                        if (!isConnecting.get() && shouldReconnect.get()) {
                            SphereLog.w(TAG, "🐕 Watchdog: Error state detected: ${currentState.message}. Forcing reconnect...")
                            reconnectAttempt.set(0)
                            isConnecting.set(true)
                            connectToNextServer()
                        }
                    }
                    
                    currentState is ConnectionState.Connecting -> {
                        // Подключаемся, ждём
                        SphereLog.d(TAG, "🐕 Watchdog: Currently connecting to ${currentState.serverUrl}")
                    }
                }
            }
        }
    }
    
    /**
     * v2.27.0: Принудительный reconnect (вызывается из NetworkReceiver)
     */
    fun forceReconnect() {
        SphereLog.w(TAG, "⚡ Force reconnect requested")
        
        // Закрываем текущее соединение если есть
        webSocket?.close(1000, "Force reconnect")
        webSocket = null
        
        // Сброс состояния
        isConnecting.set(false)
        reconnectAttempt.set(0)
        
        // Переподключаемся
        scope.launch {
            delay(500)  // Небольшая пауза
            connect()
        }
    }
    
    private fun handleDisconnect() {
        heartbeatJob?.cancel()
        watchdogJob?.cancel()  // v2.27.0: Останавливаем watchdog
        _connectionState.value = ConnectionState.Disconnected
        
        if (shouldReconnect.get()) {
            scheduleReconnect()
        }
    }
    
    private fun handleConnectionError(t: Throwable) {
        heartbeatJob?.cancel()
        isConnecting.set(false)
        
        _connectionState.value = ConnectionState.Error(
            message = t.message ?: "Unknown error",
            throwable = t
        )
        
        if (shouldReconnect.get()) {
            scheduleReconnect()
        }
    }
    
    private fun scheduleReconnect() {
        // v2.0.4: Отменяем предыдущий reconnect job если он ещё активен
        reconnectJob?.cancel()
        
        reconnectJob = scope.launch {
            val attempt = reconnectAttempt.incrementAndGet()
            
            // v2.6.0: Enterprise fast reconnect
            // Первые FAST_RECONNECT_ATTEMPTS попыток - без задержки!
            val baseDelay = if (attempt <= FAST_RECONNECT_ATTEMPTS) {
                // Мгновенный retry для первых попыток (100-500ms)
                100L * attempt
            } else {
                // Потом exponential backoff
                minOf(
                    INITIAL_RECONNECT_DELAY * (1 shl minOf(attempt - FAST_RECONNECT_ATTEMPTS - 1, 4)),
                    MAX_RECONNECT_DELAY
                )
            }
            // ENTERPRISE: небольшой jitter чтобы развести массовые reconnect
            val jitterMs = Random.nextLong(0, 500L)
            val delay = baseDelay + jitterMs
            
            Log.d(TAG, "Scheduling reconnect in ${delay}ms (attempt $attempt)")
            SphereLog.w(TAG, "⚡ Fast reconnect in ${delay}ms (attempt $attempt)")
            delay(delay)
            
            // v2.0.4: Используем mutex чтобы гарантировать только ОДНО подключение
            if (shouldReconnect.get()) {
                connectionMutex.withLock {
                    // Проверяем снова под lock
                    if (!isConnecting.get() && shouldReconnect.get()) {
                        isConnecting.set(true)
                        connectToNextServer()
                    } else {
                        Log.d(TAG, "Skipping reconnect - already connecting or shouldReconnect=false")
                    }
                }
            }
        }
    }
    
    /**
     * Отключение от сервера
     */
    fun disconnect() {
        shouldReconnect.set(false)
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }
    
    /**
     * Переподключение к серверу
     */
    fun reconnect() {
        Log.d(TAG, "Reconnect requested")
        disconnect()
        scope.launch {
            delay(1000) // Небольшая задержка перед переподключением
            connect()
        }
    }
    
    /**
     * Полное завершение
     */
    fun shutdown() {
        disconnect()
        scope.cancel()
    }
    
    /**
     * Проверка подключения
     */
    val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected
}
