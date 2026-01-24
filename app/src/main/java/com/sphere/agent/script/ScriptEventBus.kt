package com.sphere.agent.script

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Enterprise ScriptEventBus - шина событий между скриптами
 * 
 * Возможности v2.11.0:
 * - Emit/Listen для событий между скриптами
 * - Wildcard подписки (script.*, *.completed)
 * - Очереди событий с приоритетами
 * - Триггеры (on_script_completed, on_error, on_condition)
 * - История событий для отладки
 * - Timeout ожидания событий
 * - Фильтрация по источнику/получателю
 * - SERVER SYNC: Синхронизация событий с сервером
 */
object ScriptEventBus {
    
    private const val TAG = "ScriptEventBus"
    
    // Корутины для асинхронной обработки
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // ===================== SERVER SYNC =====================
    
    /**
     * Интерфейс для отправки событий на сервер
     * Реализуется в AgentService и передаётся сюда
     */
    interface ServerConnection {
        fun sendMessage(message: String): Boolean
        fun getDeviceId(): String
    }
    
    private var serverConnection: ServerConnection? = null
    private var syncEnabled: Boolean = true
    private val pendingServerEvents = ArrayDeque<ScriptEvent>(100)
    private const val MAX_PENDING_EVENTS = 100
    
    /**
     * Установить подключение к серверу
     * Вызывается из AgentService при подключении
     */
    fun setServerConnection(connection: ServerConnection?) {
        serverConnection = connection
        Log.d(TAG, "Server connection ${if (connection != null) "CONNECTED" else "DISCONNECTED"}")
        
        // При подключении отправляем накопленные события
        if (connection != null && pendingServerEvents.isNotEmpty()) {
            scope.launch {
                flushPendingEvents()
            }
        }
    }
    
    /**
     * Включить/выключить синхронизацию с сервером
     */
    fun setSyncEnabled(enabled: Boolean) {
        syncEnabled = enabled
        Log.d(TAG, "Server sync ${if (enabled) "ENABLED" else "DISABLED"}")
    }
    
    /**
     * Отправить событие на сервер
     */
    private fun syncEventToServer(event: ScriptEvent) {
        if (!syncEnabled) return
        
        val connection = serverConnection
        if (connection == null) {
            // Сохраняем в буфер для отложенной отправки
            synchronized(pendingServerEvents) {
                if (pendingServerEvents.size >= MAX_PENDING_EVENTS) {
                    pendingServerEvents.removeFirst()
                }
                pendingServerEvents.addLast(event)
            }
            Log.d(TAG, "Event queued for sync: ${event.type} (pending: ${pendingServerEvents.size})")
            return
        }
        
        try {
            val json = buildString {
                append("{")
                append("\"type\":\"event:emit\",")
                append("\"event_id\":\"${event.id}\",")
                append("\"event_type\":\"${event.type}\",")
                append("\"source\":\"${event.source}\",")
                if (event.target != null) append("\"target\":\"${event.target}\",")
                append("\"device_id\":\"${connection.getDeviceId()}\",")
                append("\"timestamp\":${event.timestamp},")
                append("\"priority\":${event.priority},")
                append("\"payload\":${payloadToJson(event.payload)}")
                append("}")
            }
            
            val sent = connection.sendMessage(json)
            if (sent) {
                Log.d(TAG, "Event synced to server: ${event.type}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync event: ${e.message}")
        }
    }
    
    /**
     * Отправить накопленные события
     */
    private suspend fun flushPendingEvents() {
        val events = synchronized(pendingServerEvents) {
            val copy = pendingServerEvents.toList()
            pendingServerEvents.clear()
            copy
        }
        
        Log.d(TAG, "Flushing ${events.size} pending events to server")
        events.forEach { event ->
            syncEventToServer(event)
        }
    }
    
    /**
     * Обработать событие от сервера
     * Вызывается из AgentService при получении "event:received" 
     */
    fun handleServerEvent(
        eventType: String,
        eventId: String,
        source: String,
        target: String?,
        payload: Map<String, Any?>,
        timestamp: Long
    ) {
        Log.d(TAG, "Received server event: $eventType from $source")
        
        // Создаём событие с флагом что оно от сервера (не нужно sync back)
        val event = ScriptEvent(
            id = eventId,
            type = eventType,
            source = source,
            target = target,
            payload = payload,
            timestamp = timestamp,
            metadata = mapOf("from_server" to true)
        )
        
        // Локально эмитим без синхронизации обратно
        scope.launch {
            emitLocal(event)
        }
    }
    
    /**
     * Локальный emit без синхронизации (для серверных событий)
     */
    private suspend fun emitLocal(event: ScriptEvent) {
        // Добавляем в историю
        synchronized(eventHistory) {
            if (eventHistory.size >= MAX_HISTORY) {
                eventHistory.removeFirst()
            }
            eventHistory.addLast(event)
        }
        
        // Отправляем в flow
        _events.emit(event)
        
        // Уведомляем ожидающие каналы
        waitingChannels.values.forEach { channel ->
            channel.trySend(event)
        }
        
        // Проверяем триггеры
        checkTriggers(event)
    }
    
    /**
     * Конвертация payload в JSON строку
     */
    private fun payloadToJson(payload: Map<String, Any?>): String {
        if (payload.isEmpty()) return "{}"
        
        return buildString {
            append("{")
            payload.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":")
                append(valueToJson(value))
            }
            append("}")
        }
    }
    
    private fun valueToJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${value.replace("\"", "\\\"").replace("\n", "\\n")}\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> {
                val map = value as Map<String, Any?>
                payloadToJson(map)
            }
            is List<*> -> {
                "[${value.joinToString(",") { valueToJson(it) }}]"
            }
            else -> "\"$value\""
        }
    }
    
    // Flow для событий
    private val _events = MutableSharedFlow<ScriptEvent>(
        replay = 10,  // Последние 10 событий для новых подписчиков
        extraBufferCapacity = 100
    )
    val events: SharedFlow<ScriptEvent> = _events.asSharedFlow()
    
    // Активные подписчики: subscriptionId -> subscription
    private val subscriptions = ConcurrentHashMap<String, EventSubscription>()
    
    // Очередь ожидающих событий (для WAIT_FOR_EVENT)
    private val waitingChannels = ConcurrentHashMap<String, Channel<ScriptEvent>>()
    
    // История событий (для отладки, последние 1000)
    private val eventHistory = ArrayDeque<ScriptEvent>(1000)
    private const val MAX_HISTORY = 1000
    
    // Зарегистрированные триггеры
    private val triggers = ConcurrentHashMap<String, EventTrigger>()
    
    // ===================== DATA CLASSES =====================
    
    /**
     * Событие скрипта
     */
    data class ScriptEvent(
        val id: String = UUID.randomUUID().toString(),
        val type: String,                    // Тип события (например: "script.completed", "user.action")
        val source: String,                  // ID источника (scriptId или "system")
        val target: String? = null,          // ID получателя (null = broadcast)
        val payload: Map<String, Any?> = emptyMap(),  // Данные события
        val priority: Int = 0,               // Приоритет (выше = важнее)
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Подписка на события
     */
    data class EventSubscription(
        val id: String = UUID.randomUUID().toString(),
        val pattern: String,                 // Паттерн типа события (поддерживает wildcards: *, **)
        val scriptId: String,                // ID подписчика
        val handler: suspend (ScriptEvent) -> Unit,
        val filter: ((ScriptEvent) -> Boolean)? = null,  // Дополнительный фильтр
        val once: Boolean = false,           // Отписаться после первого события
        val createdAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Триггер - автоматическое действие при событии
     */
    data class EventTrigger(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val eventPattern: String,
        val condition: ((ScriptEvent) -> Boolean)? = null,
        val action: TriggerAction,
        val enabled: Boolean = true,
        val executionCount: Int = 0,
        val maxExecutions: Int? = null,  // null = unlimited
        val cooldownMs: Long = 0,
        val lastExecutedAt: Long? = null
    )
    
    /**
     * Действие триггера
     */
    sealed class TriggerAction {
        data class StartScript(
            val scriptId: String,
            val variables: Map<String, Any?> = emptyMap()
        ) : TriggerAction()
        
        data class EmitEvent(
            val eventType: String,
            val payload: Map<String, Any?> = emptyMap()
        ) : TriggerAction()
        
        data class SetGlobalVariable(
            val key: String,
            val value: Any?,
            val namespace: String = "global"
        ) : TriggerAction()
        
        data class Custom(
            val handler: suspend (ScriptEvent) -> Unit
        ) : TriggerAction()
    }
    
    // ===================== EMIT =====================
    
    /**
     * Отправить событие
     */
    suspend fun emit(event: ScriptEvent) {
        Log.d(TAG, "EMIT: ${event.type} from ${event.source} -> ${event.target ?: "broadcast"}")
        
        // Добавляем в историю
        synchronized(eventHistory) {
            if (eventHistory.size >= MAX_HISTORY) {
                eventHistory.removeFirst()
            }
            eventHistory.addLast(event)
        }
        
        // Отправляем в flow
        _events.emit(event)
        
        // Уведомляем ожидающие каналы
        waitingChannels.values.forEach { channel ->
            channel.trySend(event)
        }
        
        // Проверяем триггеры
        checkTriggers(event)
        
        // v2.11.0: Синхронизация с сервером (если не с сервера пришло)
        if (event.metadata["from_server"] != true) {
            syncEventToServer(event)
        }
    }
    
    /**
     * Отправить событие (синхронная версия)
     */
    fun emitSync(event: ScriptEvent) {
        scope.launch {
            emit(event)
        }
    }
    
    /**
     * Быстрая отправка события
     */
    suspend fun emit(
        type: String,
        source: String,
        target: String? = null,
        payload: Map<String, Any?> = emptyMap(),
        priority: Int = 0
    ) {
        emit(ScriptEvent(
            type = type,
            source = source,
            target = target,
            payload = payload,
            priority = priority
        ))
    }
    
    // ===================== SUBSCRIBE =====================
    
    /**
     * Подписаться на события
     */
    fun subscribe(
        pattern: String,
        scriptId: String,
        filter: ((ScriptEvent) -> Boolean)? = null,
        once: Boolean = false,
        handler: suspend (ScriptEvent) -> Unit
    ): String {
        val subscription = EventSubscription(
            pattern = pattern,
            scriptId = scriptId,
            handler = handler,
            filter = filter,
            once = once
        )
        
        subscriptions[subscription.id] = subscription
        
        // Запускаем обработку в корутине
        scope.launch {
            events.collect { event ->
                if (matchesPattern(event.type, pattern) &&
                    (event.target == null || event.target == scriptId) &&
                    (filter == null || filter(event))) {
                    
                    try {
                        handler(event)
                    } catch (e: Exception) {
                        Log.e(TAG, "Handler error for ${subscription.id}: ${e.message}")
                    }
                    
                    if (once) {
                        unsubscribe(subscription.id)
                    }
                }
            }
        }
        
        Log.d(TAG, "SUBSCRIBE: $scriptId -> $pattern (id: ${subscription.id})")
        return subscription.id
    }
    
    /**
     * Отписаться
     */
    fun unsubscribe(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
        Log.d(TAG, "UNSUBSCRIBE: $subscriptionId")
    }
    
    /**
     * Отписать все подписки скрипта
     */
    fun unsubscribeAll(scriptId: String) {
        val toRemove = subscriptions.filter { it.value.scriptId == scriptId }.keys
        toRemove.forEach { subscriptions.remove(it) }
        Log.d(TAG, "UNSUBSCRIBE ALL for script: $scriptId (${toRemove.size} subscriptions)")
    }
    
    // ===================== WAIT FOR EVENT =====================
    
    /**
     * Ожидать события с таймаутом
     */
    suspend fun waitForEvent(
        pattern: String,
        timeoutMs: Long = 30000,
        filter: ((ScriptEvent) -> Boolean)? = null
    ): ScriptEvent? {
        val channelId = UUID.randomUUID().toString()
        val channel = Channel<ScriptEvent>(Channel.BUFFERED)
        waitingChannels[channelId] = channel
        
        Log.d(TAG, "WAIT_FOR_EVENT: $pattern (timeout: ${timeoutMs}ms)")
        
        try {
            return withTimeoutOrNull(timeoutMs) {
                channel.receiveAsFlow()
                    .filter { event ->
                        matchesPattern(event.type, pattern) &&
                        (filter == null || filter(event))
                    }
                    .first()
            }
        } finally {
            waitingChannels.remove(channelId)
            channel.close()
        }
    }
    
    // ===================== TRIGGERS =====================
    
    /**
     * Зарегистрировать триггер
     */
    fun registerTrigger(trigger: EventTrigger): String {
        triggers[trigger.id] = trigger
        Log.d(TAG, "REGISTER TRIGGER: ${trigger.name} on ${trigger.eventPattern}")
        return trigger.id
    }
    
    /**
     * Удалить триггер
     */
    fun removeTrigger(triggerId: String) {
        triggers.remove(triggerId)
    }
    
    /**
     * Проверить и выполнить триггеры
     */
    private suspend fun checkTriggers(event: ScriptEvent) {
        triggers.values
            .filter { it.enabled }
            .filter { matchesPattern(event.type, it.eventPattern) }
            .filter { trigger ->
                // Проверяем cooldown
                if (trigger.cooldownMs > 0 && trigger.lastExecutedAt != null) {
                    val elapsed = System.currentTimeMillis() - trigger.lastExecutedAt
                    if (elapsed < trigger.cooldownMs) return@filter false
                }
                // Проверяем max executions
                if (trigger.maxExecutions != null && trigger.executionCount >= trigger.maxExecutions) {
                    return@filter false
                }
                // Проверяем condition
                trigger.condition == null || trigger.condition.invoke(event)
            }
            .forEach { trigger ->
                executeTrigger(trigger, event)
            }
    }
    
    /**
     * Выполнить триггер
     */
    private suspend fun executeTrigger(trigger: EventTrigger, event: ScriptEvent) {
        Log.d(TAG, "EXECUTE TRIGGER: ${trigger.name}")
        
        // Обновляем статистику
        triggers[trigger.id] = trigger.copy(
            executionCount = trigger.executionCount + 1,
            lastExecutedAt = System.currentTimeMillis()
        )
        
        when (val action = trigger.action) {
            is TriggerAction.StartScript -> {
                // Эмитим событие для запуска скрипта
                emit(ScriptEvent(
                    type = "system.start_script",
                    source = "trigger:${trigger.id}",
                    payload = mapOf(
                        "script_id" to action.scriptId,
                        "variables" to action.variables,
                        "triggered_by" to event.id
                    )
                ))
            }
            is TriggerAction.EmitEvent -> {
                emit(ScriptEvent(
                    type = action.eventType,
                    source = "trigger:${trigger.id}",
                    payload = action.payload + mapOf("triggered_by" to event.id)
                ))
            }
            is TriggerAction.SetGlobalVariable -> {
                GlobalVariables.set(
                    key = action.key,
                    value = action.value,
                    namespace = action.namespace,
                    scriptId = "trigger:${trigger.id}"
                )
            }
            is TriggerAction.Custom -> {
                try {
                    action.handler(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Custom trigger error: ${e.message}")
                }
            }
        }
    }
    
    // ===================== BUILT-IN EVENTS =====================
    
    /**
     * Стандартные типы событий
     */
    object EventTypes {
        const val SCRIPT_STARTED = "script.started"
        const val SCRIPT_COMPLETED = "script.completed"
        const val SCRIPT_FAILED = "script.failed"
        const val SCRIPT_PAUSED = "script.paused"
        const val SCRIPT_RESUMED = "script.resumed"
        const val SCRIPT_STOPPED = "script.stopped"
        const val STEP_COMPLETED = "script.step.completed"
        const val STEP_FAILED = "script.step.failed"
        const val VARIABLE_CHANGED = "script.variable.changed"
        const val USER_INPUT_REQUIRED = "script.user_input"
        const val CONDITION_MET = "script.condition.met"
        const val ERROR = "script.error"
        const val SYSTEM_START_SCRIPT = "system.start_script"
        const val SYSTEM_STOP_SCRIPT = "system.stop_script"
        const val CUSTOM = "custom"
    }
    
    /**
     * Emit script started
     */
    suspend fun emitScriptStarted(scriptId: String, scriptName: String, runId: String) {
        emit(ScriptEvent(
            type = EventTypes.SCRIPT_STARTED,
            source = scriptId,
            payload = mapOf(
                "script_id" to scriptId,
                "script_name" to scriptName,
                "run_id" to runId
            )
        ))
    }
    
    /**
     * Emit script completed
     */
    suspend fun emitScriptCompleted(
        scriptId: String, 
        runId: String, 
        result: Map<String, Any?> = emptyMap()
    ) {
        emit(ScriptEvent(
            type = EventTypes.SCRIPT_COMPLETED,
            source = scriptId,
            payload = mapOf(
                "script_id" to scriptId,
                "run_id" to runId,
                "result" to result
            )
        ))
    }
    
    /**
     * Emit script failed
     */
    suspend fun emitScriptFailed(
        scriptId: String, 
        runId: String, 
        error: String,
        step: Int? = null
    ) {
        emit(ScriptEvent(
            type = EventTypes.SCRIPT_FAILED,
            source = scriptId,
            payload = mapOf(
                "script_id" to scriptId,
                "run_id" to runId,
                "error" to error,
                "step" to step
            )
        ))
    }
    
    // ===================== PATTERN MATCHING =====================
    
    /**
     * Проверить соответствие паттерну
     * Поддерживает:
     * - * - один сегмент (script.* -> script.completed, но не script.step.completed)
     * - ** - любое количество сегментов (script.** -> всё что начинается с script.)
     * - *.completed - все события completed
     */
    fun matchesPattern(eventType: String, pattern: String): Boolean {
        if (pattern == "*" || pattern == "**") return true
        if (pattern == eventType) return true
        
        val patternParts = pattern.split(".")
        val eventParts = eventType.split(".")
        
        var pi = 0
        var ei = 0
        
        while (pi < patternParts.size && ei < eventParts.size) {
            when (patternParts[pi]) {
                "**" -> {
                    // ** matches everything remaining
                    if (pi == patternParts.size - 1) return true
                    // Try to match rest of pattern with remaining event parts
                    for (i in ei..eventParts.size) {
                        if (matchesPattern(
                            eventParts.drop(i).joinToString("."),
                            patternParts.drop(pi + 1).joinToString(".")
                        )) return true
                    }
                    return false
                }
                "*" -> {
                    // * matches single segment
                    pi++
                    ei++
                }
                eventParts[ei] -> {
                    pi++
                    ei++
                }
                else -> return false
            }
        }
        
        return pi == patternParts.size && ei == eventParts.size
    }
    
    // ===================== HISTORY & DEBUG =====================
    
    /**
     * Получить историю событий
     */
    fun getHistory(limit: Int = 100, filter: String? = null): List<ScriptEvent> {
        return synchronized(eventHistory) {
            eventHistory
                .filter { filter == null || matchesPattern(it.type, filter) }
                .takeLast(limit)
        }
    }
    
    /**
     * Очистить историю
     */
    fun clearHistory() {
        synchronized(eventHistory) {
            eventHistory.clear()
        }
    }
    
    /**
     * Статистика
     */
    fun stats(): Map<String, Any> {
        return mapOf(
            "subscriptions" to subscriptions.size,
            "triggers" to triggers.size,
            "waiting_channels" to waitingChannels.size,
            "history_size" to eventHistory.size,
            "subscription_patterns" to subscriptions.values.map { it.pattern }.distinct(),
            "trigger_names" to triggers.values.map { it.name },
            "server_connected" to (serverConnection != null),
            "sync_enabled" to syncEnabled,
            "pending_events" to pendingServerEvents.size
        )
    }
    
    /**
     * Очистить всё (при остановке)
     */
    fun shutdown() {
        subscriptions.clear()
        triggers.clear()
        waitingChannels.values.forEach { it.close() }
        waitingChannels.clear()
        pendingServerEvents.clear()
        serverConnection = null
        scope.cancel()
        Log.d(TAG, "EventBus shutdown")
    }
}
