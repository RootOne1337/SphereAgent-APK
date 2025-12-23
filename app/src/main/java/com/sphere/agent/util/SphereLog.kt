package com.sphere.agent.util

import android.util.Log
import com.sphere.agent.core.AgentConfig
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * SphereLog - Умный логгер с отправкой на сервер
 * 
 * Позволяет собирать логи с устройства и отправлять их на бэкенд
 * для удалённой отладки.
 */
object SphereLog {
    private const val TAG = "SphereLog"
    private const val MAX_BUFFER_SIZE = 500
    private const val SEND_INTERVAL_MS = 10_000L // 10 секунд
    
    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private var agentConfig: AgentConfig? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendJob: Job? = null
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    @Serializable
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val threadName: String = Thread.currentThread().name,
        val throwable: String? = null
    )
    
    @Serializable
    data class LogBatch(
        val logs: List<LogEntry>
    )

    fun init(config: AgentConfig) {
        this.agentConfig = config
        startSendingLoop()
    }

    fun d(tag: String, message: String, t: Throwable? = null) {
        Log.d(tag, message, t)
        addLog("DEBUG", tag, message, t)
    }

    fun i(tag: String, message: String, t: Throwable? = null) {
        Log.i(tag, message, t)
        addLog("INFO", tag, message, t)
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        Log.w(tag, message, t)
        addLog("WARN", tag, message, t)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(tag, message, t)
        addLog("ERROR", tag, message, t)
    }

    private fun addLog(level: String, tag: String, message: String, t: Throwable?) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = t?.stackTraceToString()
        )
        
        logBuffer.add(entry)
        
        // Ограничиваем размер буфера
        if (logBuffer.size > MAX_BUFFER_SIZE) {
            logBuffer.poll()
        }
    }

    private fun startSendingLoop() {
        sendJob?.cancel()
        sendJob = scope.launch {
            while (isActive) {
                delay(SEND_INTERVAL_MS)
                sendLogs()
            }
        }
    }

    private suspend fun sendLogs() {
        val config = agentConfig ?: return
        if (logBuffer.isEmpty()) return
        
        val serverUrl = config.config.value.server.primary_url.ifEmpty { 
            config.config.value.server_url 
        }.ifEmpty { "https://sphereadb.ru.tuna.am" }
        
        val logsToSend = mutableListOf<LogEntry>()
        while (logBuffer.isNotEmpty() && logsToSend.size < 100) {
            logBuffer.poll()?.let { logsToSend.add(it) }
        }
        
        if (logsToSend.isEmpty()) return
        
        try {
            val batch = LogBatch(logsToSend)
            val body = json.encodeToString(batch).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/v1/agent/logs")
                .post(body)
                .header("X-Device-Id", config.deviceId)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to send logs: ${response.code}")
                    // Возвращаем логи в буфер (упрощенно - в конец)
                    logBuffer.addAll(logsToSend)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sending logs: ${e.message}")
            logBuffer.addAll(logsToSend)
        }
    }
}
