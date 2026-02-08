package com.sphere.agent.script

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/**
 * ScriptLogSender - Enterprise сервис для отправки логов выполнения скриптов
 * 
 * v3.5.0 Enterprise Features:
 * - Batch отправка логов (оптимизация для 1000+ эмуляторов)
 * - Локальный буфер с автоматическим flush
 * - Приоритезация ошибок (отправляются немедленно)
 * - Graceful degradation при потере соединения
 * - Metрки для мониторинга
 * 
 * Архитектура:
 * - Логи накапливаются в буфере
 * - Flush каждые BATCH_SIZE записей или FLUSH_INTERVAL_MS
 * - При ошибках буфер хранит до MAX_BUFFER_SIZE записей
 * - При переподключении отправляет накопленные логи
 */
object ScriptLogSender {
    
    private const val TAG = "ScriptLogSender"
    
    // ==================== CONFIGURATION ====================
    
    /** Размер батча для отправки */
    private const val BATCH_SIZE = 50
    
    /** Интервал flush в мс (если батч не заполнен) */
    private const val FLUSH_INTERVAL_MS = 2000L
    
    /** Максимальный размер буфера (при потере соединения) */
    private const val MAX_BUFFER_SIZE = 1000
    
    /** Немедленная отправка ошибок */
    private const val IMMEDIATE_ERROR_SEND = true
    
    // ==================== DATA MODELS ====================
    
    @Serializable
    data class LogEntry(
        val step_index: Int = 0,
        val loop_index: Int = 1,
        val timestamp: Long = System.currentTimeMillis(),
        val level: String = "INFO",
        val action: String? = null,
        val message: String,
        val details: Map<String, String>? = null,
        val duration_ms: Long? = null,
        val is_success: Boolean = true,
        val error_type: String? = null,
        val element_info: Map<String, String>? = null
    )
    
    @Serializable
    data class LogBatch(
        val execution_id: String,
        val agent_id: String,
        val device_name: String?,
        val script_name: String?,
        val logs: List<LogEntry>
    )
    
    /**
     * v3.5.9: Wrapper для корректной сериализации script_log_batch
     * Решает проблему с сериализацией nested data class в Map<String, Any>
     */
    @Serializable
    data class ScriptLogBatchMessage(
        val type: String = "script_log_batch",
        val data: LogBatch
    )
    
    enum class LogLevel(val priority: Int) {
        DEBUG(0),
        INFO(1),
        WARNING(2),
        ERROR(3),
        CRITICAL(4)
    }
    
    // ==================== STATE ====================
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Буфер логов: executionId -> Queue<LogEntry>
    private val logBuffers = java.util.concurrent.ConcurrentHashMap<String, ConcurrentLinkedQueue<LogEntry>>()
    
    // Метаданные выполнений
    private data class ExecutionMeta(
        val agentId: String,
        val deviceName: String?,
        val scriptName: String?,
        val startTime: Long
    )
    private val executionMeta = java.util.concurrent.ConcurrentHashMap<String, ExecutionMeta>()
    
    // Соединение с сервером
    private var serverConnection: ScriptEventBus.ServerConnection? = null
    
    // Фоновые задачи
    private var flushJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    
    // Статистика
    private val logsQueued = AtomicInteger(0)
    private val logsSent = AtomicInteger(0)
    private val logsDropped = AtomicInteger(0)
    private val batchesSent = AtomicInteger(0)
    private val sendErrors = AtomicInteger(0)
    
    // ==================== LIFECYCLE ====================
    
    /**
     * Запуск сервиса логирования
     * v3.6.0: Полностью ленивый — flush job самоостанавливается без данных
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }
        
        flushJob = scope.launch {
            var emptyChecks = 0
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                if (logBuffers.isNotEmpty()) {
                    flushAllBuffers()
                    emptyChecks = 0
                } else {
                    emptyChecks++
                    // v3.6.0: Самоостановка после 30 сек без данных (15 * 2000ms)
                    if (emptyChecks >= 15) {
                        Log.d(TAG, "Flush job self-stopped (no data for 30s)")
                        isRunning.set(false)
                        break
                    }
                }
            }
        }
        
        Log.i(TAG, "ScriptLogSender started (batch=$BATCH_SIZE, interval=${FLUSH_INTERVAL_MS}ms)")
    }
    
    /**
     * Остановка сервиса с финальным flush
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        
        flushJob?.cancel()
        flushJob = null
        
        // Финальный flush
        scope.launch {
            flushAllBuffers()
        }
        
        Log.i(TAG, "ScriptLogSender stopped. Stats: queued=$logsQueued, sent=$logsSent, dropped=$logsDropped")
    }
    
    /**
     * Установить соединение с сервером
     */
    fun setServerConnection(connection: ScriptEventBus.ServerConnection?) {
        serverConnection = connection
        
        if (connection != null) {
            // При подключении сразу отправляем накопленные логи
            scope.launch {
                flushAllBuffers()
            }
        }
        
        Log.d(TAG, "Server connection ${if (connection != null) "SET" else "CLEARED"}")
    }
    
    // ==================== PUBLIC API ====================
    
    /**
     * Начать логирование для execution
     * Вызывается при старте скрипта
     * v3.6.0: Автоматически запускает flush job если ещё не запущен
     */
    fun startExecution(
        executionId: String,
        agentId: String,
        deviceName: String?,
        scriptName: String?
    ) {
        // v3.6.0: Ленивый запуск — flush job стартует только при первом скрипте
        if (!isRunning.get()) {
            start()
        }
        
        logBuffers[executionId] = ConcurrentLinkedQueue()
        executionMeta[executionId] = ExecutionMeta(
            agentId = agentId,
            deviceName = deviceName,
            scriptName = scriptName,
            startTime = System.currentTimeMillis()
        )
        
        Log.d(TAG, "Started logging for execution $executionId (script: $scriptName)")
        
        // Лог старта
        log(
            executionId = executionId,
            level = LogLevel.INFO,
            action = "SCRIPT_START",
            message = "Script '$scriptName' started",
            details = mapOf(
                "agent_id" to agentId,
                "device_name" to (deviceName ?: "unknown")
            )
        )
    }
    
    /**
     * Завершить логирование для execution
     * Отправляет оставшиеся логи
     */
    fun endExecution(executionId: String, success: Boolean, error: String? = null) {
        // Финальный лог
        log(
            executionId = executionId,
            level = if (success) LogLevel.INFO else LogLevel.ERROR,
            action = if (success) "SCRIPT_COMPLETED" else "SCRIPT_FAILED",
            message = if (success) "Script completed successfully" else "Script failed: $error",
            details = error?.let { mapOf("error" to it) }
        )
        
        // Flush оставшиеся логи
        scope.launch {
            flushBuffer(executionId)
            
            // Cleanup
            delay(5000) // Даём время на отправку
            logBuffers.remove(executionId)
            executionMeta.remove(executionId)
        }
        
        Log.d(TAG, "Ended logging for execution $executionId (success=$success)")
    }
    
    /**
     * Основной метод логирования
     */
    fun log(
        executionId: String,
        level: LogLevel = LogLevel.INFO,
        action: String? = null,
        message: String,
        stepIndex: Int = 0,
        loopIndex: Int = 1,
        durationMs: Long? = null,
        isSuccess: Boolean = true,
        errorType: String? = null,
        details: Map<String, String>? = null,
        elementInfo: Map<String, String>? = null
    ) {
        val buffer = logBuffers[executionId]
        if (buffer == null) {
            Log.w(TAG, "No buffer for execution $executionId, creating one")
            logBuffers[executionId] = ConcurrentLinkedQueue()
            logBuffers[executionId]?.let { log(executionId, level, action, message, stepIndex, loopIndex, durationMs, isSuccess, errorType, details, elementInfo) }
            return
        }
        
        // Проверка переполнения буфера
        if (buffer.size >= MAX_BUFFER_SIZE) {
            logsDropped.incrementAndGet()
            Log.w(TAG, "Buffer overflow for $executionId, dropping log")
            return
        }
        
        val entry = LogEntry(
            step_index = stepIndex,
            loop_index = loopIndex,
            timestamp = System.currentTimeMillis(),
            level = level.name,
            action = action,
            message = message,
            details = details,
            duration_ms = durationMs,
            is_success = isSuccess,
            error_type = errorType,
            element_info = elementInfo
        )
        
        buffer.offer(entry)
        logsQueued.incrementAndGet()
        
        // Немедленная отправка ошибок
        if (IMMEDIATE_ERROR_SEND && level >= LogLevel.ERROR) {
            scope.launch {
                flushBuffer(executionId)
            }
        }
        // Проверка размера батча
        else if (buffer.size >= BATCH_SIZE) {
            scope.launch {
                flushBuffer(executionId)
            }
        }
    }
    
    /**
     * Convenience методы для разных уровней
     */
    fun debug(executionId: String, message: String, action: String? = null, stepIndex: Int = 0) =
        log(executionId, LogLevel.DEBUG, action, message, stepIndex)
    
    fun info(executionId: String, message: String, action: String? = null, stepIndex: Int = 0) =
        log(executionId, LogLevel.INFO, action, message, stepIndex)
    
    fun warning(executionId: String, message: String, action: String? = null, stepIndex: Int = 0) =
        log(executionId, LogLevel.WARNING, action, message, stepIndex)
    
    fun error(executionId: String, message: String, action: String? = null, stepIndex: Int = 0, errorType: String? = null) =
        log(executionId, LogLevel.ERROR, action, message, stepIndex, errorType = errorType, isSuccess = false)
    
    fun critical(executionId: String, message: String, action: String? = null, stepIndex: Int = 0, errorType: String? = null) =
        log(executionId, LogLevel.CRITICAL, action, message, stepIndex, errorType = errorType, isSuccess = false)
    
    /**
     * Логирование шага скрипта
     */
    fun logStep(
        executionId: String,
        stepIndex: Int,
        stepType: String,
        stepName: String,
        success: Boolean,
        durationMs: Long,
        error: String? = null,
        details: Map<String, String>? = null
    ) {
        log(
            executionId = executionId,
            level = if (success) LogLevel.INFO else LogLevel.ERROR,
            action = stepType,
            message = if (success) "Step '$stepName' completed" else "Step '$stepName' failed: $error",
            stepIndex = stepIndex,
            durationMs = durationMs,
            isSuccess = success,
            errorType = if (!success) "StepExecutionError" else null,
            details = details
        )
    }
    
    // ==================== INTERNAL ====================
    
    /**
     * Flush одного буфера
     */
    private suspend fun flushBuffer(executionId: String) {
        val buffer = logBuffers[executionId] ?: return
        val meta = executionMeta[executionId] ?: return
        
        if (buffer.isEmpty()) return
        
        val connection = serverConnection
        if (connection == null) {
            Log.d(TAG, "No connection, keeping ${buffer.size} logs buffered for $executionId")
            return
        }
        
        // Собираем батч
        val logs = mutableListOf<LogEntry>()
        while (logs.size < BATCH_SIZE * 2) { // Берём больше чтобы не блокировать
            val log = buffer.poll() ?: break
            logs.add(log)
        }
        
        if (logs.isEmpty()) return
        
        // Формируем batch
        val batch = LogBatch(
            execution_id = executionId,
            agent_id = meta.agentId,
            device_name = meta.deviceName,
            script_name = meta.scriptName,
            logs = logs
        )
        
        // Отправляем
        try {
            // v3.5.9: Используем типизированный wrapper для корректной сериализации
            val wrapper = ScriptLogBatchMessage(data = batch)
            val message = json.encodeToString(wrapper)
            
            Log.i(TAG, "🔄 Sending script_log_batch: exec=$executionId, logs=${logs.size}")
            
            val sent = connection.sendMessage(message)
            
            if (sent) {
                logsSent.addAndGet(logs.size)
                batchesSent.incrementAndGet()
                Log.d(TAG, "Sent batch: ${logs.size} logs for $executionId")
            } else {
                // Возвращаем в буфер
                logs.forEach { buffer.offer(it) }
                sendErrors.incrementAndGet()
                Log.w(TAG, "Failed to send batch for $executionId, returned to buffer")
            }
        } catch (e: Exception) {
            // Возвращаем в буфер
            logs.forEach { buffer.offer(it) }
            sendErrors.incrementAndGet()
            Log.e(TAG, "Error sending batch: ${e.message}")
        }
    }
    
    /**
     * Flush всех буферов
     */
    private suspend fun flushAllBuffers() {
        logBuffers.keys.toList().forEach { executionId ->
            try {
                flushBuffer(executionId)
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing buffer $executionId: ${e.message}")
            }
        }
    }
    
    /**
     * Получить статистику
     */
    fun getStats(): Map<String, Any> = mapOf(
        "queued" to logsQueued.get(),
        "sent" to logsSent.get(),
        "dropped" to logsDropped.get(),
        "batches_sent" to batchesSent.get(),
        "send_errors" to sendErrors.get(),
        "active_executions" to logBuffers.size,
        "buffer_sizes" to logBuffers.mapValues { it.value.size }
    )
}
