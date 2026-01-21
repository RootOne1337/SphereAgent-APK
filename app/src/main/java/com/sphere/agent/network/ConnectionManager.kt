package com.sphere.agent.network

import android.content.Context
import android.util.Log
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.core.DeviceInfo
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
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        val is_streaming: Boolean = false
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
        val charging: Boolean = false
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
        // v2.7.0: Enterprise stability - быстрый reconnect + 1FPS support
        private const val MAX_RECONNECT_DELAY = 15_000L  // 15 секунд max
        private const val INITIAL_RECONNECT_DELAY = 500L  // 0.5 секунды
        private const val HEARTBEAT_INTERVAL = 15_000L  // 15 секунд
        private const val FAST_RECONNECT_ATTEMPTS = 5  // Первые 5 попыток без delay
        
        // v2.7.0: Специальные таймауты для перегруженных эмуляторов (1 FPS)
        private const val LOW_FPS_COMMAND_TIMEOUT = 60_000L  // 60 секунд на команду (было implicit)
        private const val LOW_FPS_RECONNECT_GRACE = 30_000L  // 30 секунд grace period перед reconnect
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
    
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    
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
        
        val hello = AgentMessage.Hello(
            device_id = info.deviceId,
            device_name = info.deviceName,
            device_model = info.deviceModel,
            android_version = info.androidVersion,
            agent_version = agentConfig.config.value.agent_version,
            has_accessibility = hasAccessibility,
            has_root = hasRootAccess,
            screen_width = screenWidth,
            screen_height = screenHeight,
            is_streaming = isCurrentlyStreaming
        )
        
        val message = json.encodeToString(hello)
        ws.send(message)
        Log.d(TAG, "Sent hello: accessibility=$hasAccessibility, root=$hasRootAccess, screen=${screenWidth}x${screenHeight}")
        SphereLog.i(TAG, "Hello sent: accessibility=$hasAccessibility, screen=${screenWidth}x${screenHeight}")
    }
    
    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                
                if (_connectionState.value is ConnectionState.Connected) {
                    val hasAccessibility = com.sphere.agent.service.SphereAccessibilityService.isServiceEnabled()
                    val heartbeat = AgentMessage.Heartbeat(
                        has_accessibility = hasAccessibility,
                        has_root = hasRootAccess,
                        is_streaming = isCurrentlyStreaming
                    )
                    val message = json.encodeToString(heartbeat)
                    ws.send(message)
                    Log.d(TAG, "Sent heartbeat: accessibility=$hasAccessibility, streaming=$isCurrentlyStreaming")
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
        
        // Если команда в процессе - пропускаем фрейм (приоритет командам!)
        if (commandInProgress) {
            return false
        }
        
        // Throttling по времени
        if (now - lastFrameSentTime < minFrameInterval) {
            return false
        }
        
        // Проверяем что WebSocket не перегружен
        if (pendingFrames >= maxPendingFrames) {
            return false
        }
        
        val ws = webSocket ?: return false
        
        pendingFrames++
        lastFrameSentTime = now
        
        val sent = ws.send(ByteString.of(*frame))
        
        if (sent) {
            // Уменьшаем счётчик после небольшой задержки (примерная оценка RTT)
            scope.launch {
                delay(100)
                if (pendingFrames > 0) pendingFrames--
            }
        } else {
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
     * Отправка произвольного JSON сообщения
     */
    fun sendMessage(message: String): Boolean {
        return webSocket?.send(message) ?: false
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
    
    private fun handleDisconnect() {
        heartbeatJob?.cancel()
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
            val delay = if (attempt <= FAST_RECONNECT_ATTEMPTS) {
                // Мгновенный retry для первых попыток (100-500ms)
                100L * attempt
            } else {
                // Потом exponential backoff
                minOf(
                    INITIAL_RECONNECT_DELAY * (1 shl minOf(attempt - FAST_RECONNECT_ATTEMPTS - 1, 4)),
                    MAX_RECONNECT_DELAY
                )
            }
            
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
