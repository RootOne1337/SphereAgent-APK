/**
 * HttpPollingFallback - HTTP Polling когда WebSocket недоступен
 * 
 * РЕЗЕРВНЫЙ КАНАЛ СВЯЗИ #2: HTTP Long Polling
 * 
 * Когда WebSocket соединение не работает более 2 минут,
 * агент переключается на HTTP polling:
 * 1. Polling /api/v1/agent/poll/{agent_id} каждые 10 секунд
 * 2. Получает накопленные команды
 * 3. Отправляет статус обратно
 * 
 * Преимущества:
 * - Работает через прокси/firewall которые блокируют WS
 * - Проще дебажить
 * - Меньше state на сервере
 */
package com.sphere.agent.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Команда полученная через polling
 */
data class PolledCommand(
    val id: String,
    val type: String,
    val data: JSONObject,
    val timestamp: Long
)

@Singleton
class HttpPollingFallback @Inject constructor() {
    
    companion object {
        private const val TAG = "HttpPolling"
        
        // Интервалы polling
        private const val POLL_INTERVAL_MS = 10_000L // 10 секунд
        private const val POLL_TIMEOUT_MS = 30_000L  // 30 секунд (long poll)
        
        // Через сколько времени после потери WS переключаемся на polling
        private const val FALLBACK_DELAY_MS = 2 * 60 * 1000L // 2 минуты
        
        // Content type
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private var pollingJob: Job? = null
    private var isPollingActive = AtomicBoolean(false)
    private var serverUrl: String = ""
    private var agentId: String = ""
    
    // WebSocket status tracking
    private var wsLastConnectedAt: Long = System.currentTimeMillis()
    private var isWsConnected = true
    
    // Callback для обработки команд
    private var onCommandReceived: ((PolledCommand) -> Unit)? = null
    
    // Flow для статуса
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    
    /**
     * Инициализирует fallback polling
     */
    fun initialize(serverUrl: String, agentId: String, onCommand: (PolledCommand) -> Unit) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.agentId = agentId
        this.onCommandReceived = onCommand
        Log.i(TAG, "Initialized with server: $serverUrl, agent: $agentId")
    }
    
    /**
     * Уведомляет о статусе WebSocket соединения
     */
    fun onWebSocketStateChanged(connected: Boolean) {
        val wasConnected = isWsConnected
        isWsConnected = connected
        
        if (connected) {
            wsLastConnectedAt = System.currentTimeMillis()
            
            // WS восстановился - останавливаем polling
            if (isPollingActive.get()) {
                Log.i(TAG, "✅ WebSocket restored, stopping HTTP polling")
                stopPolling()
            }
        } else if (wasConnected) {
            // WS упал - запускаем таймер на переключение
            Log.w(TAG, "⚠️ WebSocket disconnected, will switch to polling in ${FALLBACK_DELAY_MS/1000}s")
            schedulePollingStart()
        }
    }
    
    /**
     * Планирует запуск polling после задержки
     */
    private fun schedulePollingStart() {
        scope.launch {
            delay(FALLBACK_DELAY_MS)
            
            // Проверяем что WS всё ещё не работает
            if (!isWsConnected) {
                Log.w(TAG, "🔄 WebSocket still down, switching to HTTP polling")
                startPolling()
            }
        }
    }
    
    /**
     * Запускает HTTP polling
     */
    fun startPolling() {
        if (isPollingActive.get()) {
            Log.d(TAG, "Polling already active")
            return
        }
        
        if (serverUrl.isEmpty() || agentId.isEmpty()) {
            Log.e(TAG, "Cannot start polling: not initialized")
            return
        }
        
        isPollingActive.set(true)
        _isActive.value = true
        
        pollingJob = scope.launch {
            Log.i(TAG, "📡 HTTP Polling started")
            
            while (isActive && isPollingActive.get()) {
                try {
                    pollServer()
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}")
                }
                
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Останавливает HTTP polling
     */
    fun stopPolling() {
        isPollingActive.set(false)
        _isActive.value = false
        pollingJob?.cancel()
        pollingJob = null
        Log.i(TAG, "HTTP Polling stopped")
    }
    
    /**
     * Выполняет один цикл polling
     */
    private suspend fun pollServer() = withContext(Dispatchers.IO) {
        try {
            val pollUrl = "$serverUrl/api/v1/agent/poll/$agentId"
            
            // Отправляем статус и получаем команды
            val statusJson = JSONObject().apply {
                put("agent_id", agentId)
                put("timestamp", System.currentTimeMillis())
                put("status", "polling")
                put("ws_connected", false)
            }
            
            val request = Request.Builder()
                .url(pollUrl)
                .post(statusJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("X-Agent-Id", agentId)
                .header("X-Polling-Mode", "true")
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext
                    val json = JSONObject(body)
                    
                    // Обрабатываем команды
                    val commands = json.optJSONArray("commands")
                    if (commands != null && commands.length() > 0) {
                        Log.i(TAG, "📬 Received ${commands.length()} commands via polling")
                        processCommands(commands)
                    }
                    
                    // Проверяем есть ли новый server URL
                    json.optString("new_server_url", "").takeIf { it.isNotEmpty() }?.let { newUrl ->
                        Log.i(TAG, "🔄 Server URL changed to: $newUrl")
                        serverUrl = newUrl.trimEnd('/')
                    }
                    
                } else if (response.code == 404) {
                    // Endpoint не существует на сервере - это нормально для старых версий
                    Log.d(TAG, "Poll endpoint not available (404)")
                } else {
                    Log.w(TAG, "Poll failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll request failed: ${e.message}")
        }
    }
    
    /**
     * Обрабатывает полученные команды
     */
    private fun processCommands(commands: JSONArray) {
        for (i in 0 until commands.length()) {
            try {
                val cmdJson = commands.getJSONObject(i)
                val command = PolledCommand(
                    id = cmdJson.getString("id"),
                    type = cmdJson.getString("type"),
                    data = cmdJson.optJSONObject("data") ?: JSONObject(),
                    timestamp = cmdJson.optLong("timestamp", System.currentTimeMillis())
                )
                
                Log.d(TAG, "Processing command: ${command.type}")
                onCommandReceived?.invoke(command)
                
                // Подтверждаем получение
                acknowledgeCommand(command.id)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing command: ${e.message}")
            }
        }
    }
    
    /**
     * Подтверждает получение команды
     */
    private fun acknowledgeCommand(commandId: String) {
        scope.launch {
            try {
                val ackUrl = "$serverUrl/api/v1/agent/poll/$agentId/ack"
                val ackJson = JSONObject().apply {
                    put("command_id", commandId)
                    put("status", "received")
                    put("timestamp", System.currentTimeMillis())
                }
                
                val request = Request.Builder()
                    .url(ackUrl)
                    .post(ackJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                
                httpClient.newCall(request).execute().close()
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to ack command $commandId: ${e.message}")
            }
        }
    }
    
    /**
     * Принудительно отправляет сообщение через HTTP (когда WS недоступен)
     */
    suspend fun sendMessage(type: String, data: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$serverUrl/api/v1/agent/message"
            val messageJson = JSONObject().apply {
                put("agent_id", agentId)
                put("type", type)
                put("data", data)
                put("timestamp", System.currentTimeMillis())
            }
            
            val request = Request.Builder()
                .url(url)
                .post(messageJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("X-Agent-Id", agentId)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}")
            false
        }
    }
    
    /**
     * Освобождает ресурсы
     */
    fun release() {
        stopPolling()
        scope.cancel()
    }
}
