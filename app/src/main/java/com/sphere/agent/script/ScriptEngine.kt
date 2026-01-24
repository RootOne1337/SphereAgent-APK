package com.sphere.agent.script

import android.content.Context
import android.util.Log
import com.sphere.agent.service.CommandExecutor
import com.sphere.agent.service.CommandResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ScriptEngine - Движок выполнения скриптов автоматизации
 * 
 * Возможности:
 * - Загрузка скриптов с сервера (JSON формат)
 * - Параллельное выполнение нескольких скриптов
 * - Start / Stop / Pause / Resume для каждого скрипта
 * - Отслеживание прогресса (этап, статус)
 * - Отправка статуса на сервер в реальном времени
 * - Циклическое выполнение (loop mode)
 * - Условная логика (if/else)
 * - Переменные и состояние между шагами
 * 
 * Архитектура:
 * - Каждый скрипт выполняется в отдельной coroutine
 * - ScriptRunner управляет жизненным циклом одного скрипта
 * - ScriptEngine управляет всеми скриптами
 */
class ScriptEngine(
    private val context: Context,
    private val commandExecutor: CommandExecutor,
    private val onStatusUpdate: (ScriptStatus) -> Unit
) {
    companion object {
        private const val TAG = "ScriptEngine"
        const val MAX_CONCURRENT_SCRIPTS = 10
    }
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Активные скрипты: scriptId -> ScriptRunner
    private val runners = ConcurrentHashMap<String, ScriptRunner>()
    
    // Состояние всех скриптов
    private val _scriptsState = MutableStateFlow<Map<String, ScriptStatus>>(emptyMap())
    val scriptsState: StateFlow<Map<String, ScriptStatus>> = _scriptsState
    
    /**
     * Загрузить и запустить скрипт
     */
    fun startScript(script: Script, loopMode: Boolean = false): String {
        if (runners.size >= MAX_CONCURRENT_SCRIPTS) {
            throw IllegalStateException("Maximum concurrent scripts reached: $MAX_CONCURRENT_SCRIPTS")
        }
        
        val runId = UUID.randomUUID().toString().take(8)
        val runner = ScriptRunner(
            runId = runId,
            script = script,
            commandExecutor = commandExecutor,
            loopMode = loopMode,
            onUpdate = { status ->
                updateStatus(runId, status)
            }
        )
        
        runners[runId] = runner
        
        scope.launch {
            runner.start()
        }
        
        Log.i(TAG, "Started script '${script.name}' with runId=$runId, loopMode=$loopMode")
        return runId
    }
    
    /**
     * Остановить скрипт
     */
    fun stopScript(runId: String): Boolean {
        val runner = runners[runId] ?: return false
        runner.stop()
        runners.remove(runId)
        Log.i(TAG, "Stopped script runId=$runId")
        return true
    }
    
    /**
     * Пауза скрипта
     */
    fun pauseScript(runId: String): Boolean {
        val runner = runners[runId] ?: return false
        runner.pause()
        Log.i(TAG, "Paused script runId=$runId")
        return true
    }
    
    /**
     * Возобновить скрипт
     */
    fun resumeScript(runId: String): Boolean {
        val runner = runners[runId] ?: return false
        runner.resume()
        Log.i(TAG, "Resumed script runId=$runId")
        return true
    }
    
    /**
     * Получить статус скрипта
     */
    fun getScriptStatus(runId: String): ScriptStatus? {
        return runners[runId]?.status?.value
    }
    
    /**
     * Получить все активные скрипты
     */
    fun getActiveScripts(): List<ScriptStatus> {
        return runners.values.mapNotNull { it.status.value }
    }
    
    /**
     * Остановить все скрипты
     */
    fun stopAllScripts() {
        runners.keys.toList().forEach { runId ->
            stopScript(runId)
        }
        Log.i(TAG, "Stopped all scripts")
    }
    
    /**
     * Парсинг скрипта из JSON
     */
    fun parseScript(jsonString: String): Script {
        return json.decodeFromString(jsonString)
    }
    
    private fun updateStatus(runId: String, status: ScriptStatus) {
        val current = _scriptsState.value.toMutableMap()
        current[runId] = status
        _scriptsState.value = current
        
        // Уведомляем callback для отправки на сервер
        onStatusUpdate(status)
        
        // Очищаем завершённые скрипты через 5 минут
        if (status.state == ScriptState.COMPLETED || status.state == ScriptState.ERROR) {
            scope.launch {
                delay(5 * 60 * 1000)
                runners.remove(runId)
                val updated = _scriptsState.value.toMutableMap()
                updated.remove(runId)
                _scriptsState.value = updated
            }
        }
    }
    
    fun destroy() {
        stopAllScripts()
        scope.cancel()
    }
}

/**
 * Состояния скрипта
 */
enum class ScriptState {
    IDLE,       // Создан, не запущен
    RUNNING,    // Выполняется
    PAUSED,     // На паузе
    COMPLETED,  // Успешно завершён
    ERROR,      // Ошибка
    STOPPED     // Принудительно остановлен
}

/**
 * Статус выполнения скрипта
 */
@Serializable
data class ScriptStatus(
    val runId: String,
    val scriptId: String,
    val scriptName: String,
    val state: ScriptState,
    val currentStep: Int,
    val totalSteps: Int,
    val currentStepName: String,
    val progress: Float,            // 0.0 - 1.0
    val loopCount: Int = 0,         // Сколько раз выполнен цикл
    val loopMode: Boolean = false,
    val startedAt: Long,
    val updatedAt: Long,
    val error: String? = null,
    val variables: Map<String, String> = emptyMap()  // Переменные скрипта
)

/**
 * Скрипт автоматизации
 */
@Serializable
data class Script(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0",
    val author: String = "",
    val steps: List<ScriptStep>,
    val variables: Map<String, String> = emptyMap(),  // Начальные переменные
    val settings: ScriptSettings = ScriptSettings()
)

@Serializable
data class ScriptSettings(
    val defaultDelay: Long = 500,           // Задержка между шагами (мс)
    val retryOnError: Boolean = false,      // Повторять при ошибке
    val maxRetries: Int = 3,                // Макс. попыток
    val continueOnError: Boolean = false,   // Продолжать при ошибке
    val loopDelay: Long = 1000              // Задержка между циклами (мс)
)

/**
 * Шаг скрипта
 */
@Serializable
data class ScriptStep(
    val id: String = "",
    val name: String = "",
    val type: StepType,
    val params: Map<String, String> = emptyMap(),
    val delay: Long? = null,        // Override задержки после шага
    val condition: String? = null,  // Условие выполнения (JavaScript-like expression)
    val onError: String? = null     // Действие при ошибке: "continue", "stop", "goto:step_id"
)

/**
 * Типы шагов
 * 
 * v2.4.0: Добавлена полная поддержка XPath/UIAutomator2
 * v2.5.0: Добавлен XPATH_SMART с умной логикой
 */
@Serializable
enum class StepType {
    // Базовые команды
    TAP,                // Нажатие: x, y
    LONG_PRESS,         // Долгое нажатие: x, y, duration
    DOUBLE_TAP,         // Двойное нажатие: x, y
    SWIPE,              // Свайп: x1, y1, x2, y2, duration
    TEXT,               // Ввод текста: text
    KEY,                // Нажатие кнопки: keycode
    
    // Навигация
    HOME,               // Кнопка Home
    BACK,               // Кнопка Back
    RECENT,             // Recent apps
    
    // Shell команды
    SHELL,              // Выполнить shell команду: command
    LAUNCH_APP,         // Запуск приложения: package
    CLOSE_APP,          // Закрыть приложение: package
    
    // Ожидание
    WAIT,               // Ждать: duration (мс)
    WAIT_FOR_ELEMENT,   // Ждать элемент: text, timeout
    
    // ===== XPath / UIAutomator2 (v2.4.0) =====
    XPATH_TAP,          // Тап по XPath элементу: xpath, timeout
    XPATH_TEXT,         // Ввод текста в XPath элемент: xpath, text, timeout
    XPATH_WAIT,         // Ждать XPath элемент: xpath, timeout
    XPATH_EXISTS,       // Проверить существование: xpath, variable, timeout
    XPATH_SWIPE,        // Свайп от XPath элемента: xpath, direction, distance, timeout
    
    // Универсальный поиск элементов
    FIND_AND_TAP,       // Найти и тапнуть: by (text/id/desc), value, timeout
    FIND_AND_TEXT,      // Найти и ввести текст: by, value, text, timeout
    FIND_EXISTS,        // Проверить существование: by, value, variable, timeout
    
    // ===== XPATH_SMART (v2.5.0) - Умный XPath блок =====
    XPATH_SMART,        // Умный блок с behavior, retry, fallback
    
    // ===== XPATH_POOL (v2.6.0) - Пул элементов: видим → нажимаем =====
    XPATH_POOL,         // Проверяет все XPath в пуле, кликает первый найденный
    
    // Логика
    SET_VARIABLE,       // Установить переменную: name, value
    GET_TIME,           // Получить время: format (HH, mm, ss), variable
    LOG,                // Лог сообщение: message
    SCREENSHOT,         // Сделать скриншот: filename
    
    // Control flow
    IF,                 // Условие: condition, then_step, else_step
    LOOP,               // Цикл: count, steps
    GOTO,               // Переход: step_id
    STOP,               // Остановить скрипт
    
    // ===== ORCHESTRATION (v2.8.0) - Оркестрация и межскриптовое взаимодействие =====
    
    // Global Variables - общие переменные между скриптами
    SET_GLOBAL,         // Установить глобальную: name, value, namespace?, ttl_ms?
    GET_GLOBAL,         // Получить глобальную: name, variable, namespace?, default?
    DELETE_GLOBAL,      // Удалить глобальную: name, namespace?
    INCREMENT_GLOBAL,   // Атомарный инкремент: name, delta?, namespace?
    APPEND_TO_LIST,     // Добавить в список: name, value, namespace?
    PUT_TO_MAP,         // Добавить в map: name, map_key, map_value, namespace?
    
    // Events - межскриптовые события
    EMIT_EVENT,         // Отправить событие: event_type, payload?, target?
    WAIT_FOR_EVENT,     // Ожидать событие: event_pattern, timeout?, variable?
    SUBSCRIBE_EVENT,    // Подписаться на событие: event_pattern, handler_step_id?
    
    // Script Control - управление другими скриптами
    START_SCRIPT,       // Запустить другой скрипт: script_id, variables?, async?
    STOP_SCRIPT,        // Остановить скрипт: run_id
    WAIT_SCRIPT,        // Ждать завершения скрипта: run_id, timeout?
    
    // Triggers - триггеры
    REGISTER_TRIGGER,   // Регистрация триггера: name, event_pattern, action
    REMOVE_TRIGGER      // Удаление триггера: trigger_id
}

/**
 * Исполнитель одного скрипта
 * 
 * v2.4.0: Интегрирован XPathHelper для XPath/UIAutomator2 команд
 */
class ScriptRunner(
    private val runId: String,
    private val script: Script,
    private val commandExecutor: CommandExecutor,
    private val loopMode: Boolean,
    private val onUpdate: (ScriptStatus) -> Unit
) {
    companion object {
        private const val TAG = "ScriptRunner"
    }
    
    private var job: Job? = null
    private var isPaused = false
    private var isStopped = false
    
    private val variables = script.variables.toMutableMap()
    private var loopCount = 0
    
    // XPathHelper для XPath/UIAutomator2 команд (v2.4.0)
    private val xpathHelper = XPathHelper(commandExecutor)
    
    private val _status = MutableStateFlow<ScriptStatus?>(null)
    val status: StateFlow<ScriptStatus?> = _status
    
    suspend fun start() {
        isStopped = false
        isPaused = false
        
        updateStatus(ScriptState.RUNNING, 0, "Starting...")
        
        // v2.8.0: Emit script started event
        ScriptEventBus.emitScriptStarted(script.id, script.name, runId)
        
        job = CoroutineScope(Dispatchers.Default).launch {
            try {
                do {
                    executeScript()
                    
                    if (loopMode && !isStopped) {
                        loopCount++
                        updateStatus(ScriptState.RUNNING, 0, "Loop ${loopCount + 1} starting...")
                        delay(script.settings.loopDelay)
                    }
                } while (loopMode && !isStopped)
                
                if (!isStopped) {
                    updateStatus(ScriptState.COMPLETED, script.steps.size, "Completed")
                    // v2.8.0: Emit script completed event
                    ScriptEventBus.emitScriptCompleted(script.id, runId, mapOf(
                        "variables" to variables,
                        "loop_count" to loopCount
                    ))
                }
            } catch (e: CancellationException) {
                updateStatus(ScriptState.STOPPED, -1, "Stopped")
                // v2.8.0: Emit script stopped event
                ScriptEventBus.emitSync(ScriptEventBus.ScriptEvent(
                    type = ScriptEventBus.EventTypes.SCRIPT_STOPPED,
                    source = script.id,
                    payload = mapOf("run_id" to runId, "reason" to "cancelled")
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Script error", e)
                updateStatus(ScriptState.ERROR, -1, "Error", e.message)
                // v2.8.0: Emit script failed event
                ScriptEventBus.emitScriptFailed(script.id, runId, e.message ?: "Unknown error")
            } finally {
                // v2.8.0: Cleanup subscriptions for this script
                ScriptEventBus.unsubscribeAll(script.id)
            }
        }
    }
    
    private suspend fun executeScript() {
        var currentIndex = 0
        while (currentIndex < script.steps.size && !isStopped) {
            val step = script.steps[currentIndex]
            
            // Пауза
            while (isPaused && !isStopped) {
                delay(100)
            }
            if (isStopped) break
            
            // Проверка условия (уровень шага)
            if (step.condition != null && !evaluateCondition(step.condition)) {
                Log.d(TAG, "Step ${step.id} skipped: condition not met")
                currentIndex++
                continue
            }
            
            val stepName = step.name.ifEmpty { step.type.name }
            updateStatus(ScriptState.RUNNING, currentIndex, stepName)
            
            try {
                // Обработка управляющих конструкций прямо здесь
                when (step.type) {
                    StepType.GOTO -> {
                        val targetId = step.params["target_id"] ?: ""
                        val targetIndex = script.steps.indexOfFirst { it.id == targetId }
                        if (targetIndex != -1) {
                            Log.i(TAG, "GOTO: Jumping to step $targetId (index $targetIndex)")
                            currentIndex = targetIndex
                            continue // Пропускаем инкремент
                        } else {
                            Log.e(TAG, "GOTO failed: Target $targetId not found")
                        }
                    }
                    
                    StepType.IF -> {
                        val condition = step.params["condition"] ?: "true"
                        val thenId = step.params["then_id"] ?: ""
                        val elseId = step.params["else_id"] ?: ""
                        
                        val result = evaluateCondition(condition)
                        val targetId = if (result) thenId else elseId
                        
                        if (targetId.isNotEmpty()) {
                            val targetIndex = script.steps.indexOfFirst { it.id == targetId }
                            if (targetIndex != -1) {
                                Log.i(TAG, "IF '$condition' is $result, Jumping to $targetId")
                                currentIndex = targetIndex
                                continue
                            }
                        }
                    }
                    
                    else -> executeStep(step)
                }
                
                // Задержка после шага
                val delay = step.delay ?: script.settings.defaultDelay
                if (delay > 0) {
                    delay(delay)
                }
                
                currentIndex++
            } catch (e: Exception) {
                Log.e(TAG, "Step ${step.id} failed", e)
                
                when (step.onError ?: "stop") {
                    "continue" -> currentIndex++
                    "stop" -> throw e
                    else -> {
                        if (step.onError?.startsWith("goto:") == true) {
                            val targetId = step.onError.substringAfter("goto:")
                            val targetIndex = script.steps.indexOfFirst { it.id == targetId }
                            if (targetIndex != -1) {
                                currentIndex = targetIndex
                                continue
                            }
                        }
                        throw e
                    }
                }
            }
        }
    }
    
    private suspend fun executeStep(step: ScriptStep) {
        Log.d(TAG, "Executing step: ${step.type} - ${step.params}")
        
        val result: CommandResult = when (step.type) {
            StepType.TAP -> {
                val x = step.params["x"]?.toIntOrNull() ?: throw IllegalArgumentException("x required")
                val y = step.params["y"]?.toIntOrNull() ?: throw IllegalArgumentException("y required")
                commandExecutor.tap(x, y)
            }
            
            StepType.LONG_PRESS -> {
                val x = step.params["x"]?.toIntOrNull() ?: throw IllegalArgumentException("x required")
                val y = step.params["y"]?.toIntOrNull() ?: throw IllegalArgumentException("y required")
                val duration = step.params["duration"]?.toIntOrNull() ?: 800
                commandExecutor.longPress(x, y, duration)
            }
            
            StepType.SWIPE -> {
                val x1 = step.params["x1"]?.toIntOrNull() ?: throw IllegalArgumentException("x1 required")
                val y1 = step.params["y1"]?.toIntOrNull() ?: throw IllegalArgumentException("y1 required")
                val x2 = step.params["x2"]?.toIntOrNull() ?: throw IllegalArgumentException("x2 required")
                val y2 = step.params["y2"]?.toIntOrNull() ?: throw IllegalArgumentException("y2 required")
                val duration = step.params["duration"]?.toIntOrNull() ?: 300
                commandExecutor.swipe(x1, y1, x2, y2, duration)
            }
            
            StepType.TEXT -> {
                val text = resolveVariables(step.params["text"] ?: "")
                commandExecutor.inputText(text)
            }
            
            StepType.KEY -> {
                val keycode = step.params["keycode"]?.toIntOrNull() ?: throw IllegalArgumentException("keycode required")
                commandExecutor.keyEvent(keycode)
            }
            
            StepType.HOME -> commandExecutor.home()
            StepType.BACK -> commandExecutor.back()
            StepType.RECENT -> commandExecutor.recent()
            
            StepType.SHELL -> {
                val command = resolveVariables(step.params["command"] ?: "")
                commandExecutor.shell(command)
            }
            
            StepType.LAUNCH_APP -> {
                val packageName = step.params["package"] ?: throw IllegalArgumentException("package required")
                commandExecutor.shell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            }
            
            StepType.CLOSE_APP -> {
                val packageName = step.params["package"] ?: throw IllegalArgumentException("package required")
                commandExecutor.forceStopApp(packageName)
            }
            
            StepType.WAIT -> {
                val duration = step.params["duration"]?.toLongOrNull() ?: 1000
                delay(duration)
                CommandResult(success = true)
            }
            
            StepType.SET_VARIABLE -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val value = resolveVariables(step.params["value"] ?: "")
                variables[name] = value
                CommandResult(success = true)
            }
            
            StepType.GET_TIME -> {
                val format = step.params["format"] ?: "mm"
                val variableName = step.params["variable"] ?: "current_time"
                val calendar = java.util.Calendar.getInstance()
                val value = when (format.lowercase()) {
                    "hh" -> calendar.get(java.util.Calendar.HOUR_OF_DAY).toString()
                    "mm" -> calendar.get(java.util.Calendar.MINUTE).toString()
                    "ss" -> calendar.get(java.util.Calendar.SECOND).toString()
                    "full" -> System.currentTimeMillis().toString()
                    else -> calendar.get(java.util.Calendar.MINUTE).toString()
                }
                variables[variableName] = value
                Log.d(TAG, "GET_TIME: $variableName = $value")
                CommandResult(success = true, data = value)
            }
            
            StepType.LOG -> {
                val message = resolveVariables(step.params["message"] ?: "")
                Log.i(TAG, "[Script] $message")
                CommandResult(success = true, data = message)
            }
            
            StepType.SCREENSHOT -> {
                // TODO: Implement screenshot capture
                CommandResult(success = true)
            }
            
            StepType.STOP -> {
                isStopped = true
                CommandResult(success = true)
            }
            
            // ===== XPath / UIAutomator2 команды (v2.4.0) =====
            
            StepType.DOUBLE_TAP -> {
                val x = step.params["x"]?.toIntOrNull() ?: throw IllegalArgumentException("x required")
                val y = step.params["y"]?.toIntOrNull() ?: throw IllegalArgumentException("y required")
                // Двойной тап = два тапа с небольшой задержкой
                val result1 = commandExecutor.tap(x, y)
                if (result1.success) {
                    delay(100)
                    commandExecutor.tap(x, y)
                } else {
                    result1
                }
            }
            
            StepType.XPATH_TAP -> {
                val xpath = resolveVariables(step.params["xpath"] ?: throw IllegalArgumentException("xpath required"))
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                Log.i(TAG, "XPATH_TAP: $xpath (timeout: ${timeout}ms)")
                xpathHelper.tapByXPath(xpath, timeout)
            }
            
            StepType.XPATH_TEXT -> {
                val xpath = resolveVariables(step.params["xpath"] ?: throw IllegalArgumentException("xpath required"))
                val text = resolveVariables(step.params["text"] ?: "")
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                Log.i(TAG, "XPATH_TEXT: $xpath -> '$text' (timeout: ${timeout}ms)")
                xpathHelper.textByXPath(xpath, text, timeout)
            }
            
            StepType.XPATH_WAIT -> {
                val xpath = resolveVariables(step.params["xpath"] ?: throw IllegalArgumentException("xpath required"))
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                Log.i(TAG, "XPATH_WAIT: $xpath (timeout: ${timeout}ms)")
                val element = xpathHelper.waitForXPath(xpath, timeout)
                if (element.found) {
                    Log.i(TAG, "XPATH_WAIT: Element found at (${element.bounds?.centerX}, ${element.bounds?.centerY})")
                    CommandResult(success = true, data = "Element found")
                } else {
                    CommandResult(success = false, error = "Element not found: $xpath")
                }
            }
            
            StepType.XPATH_EXISTS -> {
                val xpath = resolveVariables(step.params["xpath"] ?: throw IllegalArgumentException("xpath required"))
                val variableName = step.params["variable"] ?: "element_exists"
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 3000L
                Log.i(TAG, "XPATH_EXISTS: $xpath -> $variableName (timeout: ${timeout}ms)")
                val element = xpathHelper.waitForXPath(xpath, timeout)
                variables[variableName] = if (element.found) "true" else "false"
                Log.i(TAG, "XPATH_EXISTS: $variableName = ${variables[variableName]}")
                CommandResult(success = true, data = variables[variableName])
            }
            
            StepType.XPATH_SWIPE -> {
                val xpath = resolveVariables(step.params["xpath"] ?: throw IllegalArgumentException("xpath required"))
                val direction = step.params["direction"] ?: "down"
                val distance = step.params["distance"]?.toIntOrNull() ?: 300
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                Log.i(TAG, "XPATH_SWIPE: $xpath -> $direction (distance: $distance, timeout: ${timeout}ms)")
                xpathHelper.swipeFromElement(xpath, direction, distance, timeout)
            }
            
            StepType.FIND_AND_TAP -> {
                val by = step.params["by"] ?: "text"
                val value = resolveVariables(step.params["value"] ?: throw IllegalArgumentException("value required"))
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                Log.i(TAG, "FIND_AND_TAP: $by='$value' (timeout: ${timeout}ms)")
                xpathHelper.tapElement(by, value, timeout)
            }
            
            StepType.FIND_AND_TEXT -> {
                val by = step.params["by"] ?: "text"
                val value = resolveVariables(step.params["value"] ?: throw IllegalArgumentException("value required"))
                val text = resolveVariables(step.params["text"] ?: "")
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                Log.i(TAG, "FIND_AND_TEXT: $by='$value' -> '$text' (timeout: ${timeout}ms)")
                val element = xpathHelper.waitForElement(by, value, timeout)
                if (!element.found || element.bounds == null) {
                    CommandResult(success = false, error = "Element not found: $by=$value")
                } else {
                    // Тап на элемент для фокуса
                    val tapResult = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                    if (tapResult.success) {
                        delay(300)
                        commandExecutor.inputText(text)
                    } else {
                        tapResult
                    }
                }
            }
            
            StepType.FIND_EXISTS -> {
                val by = step.params["by"] ?: "text"
                val value = resolveVariables(step.params["value"] ?: throw IllegalArgumentException("value required"))
                val variableName = step.params["variable"] ?: "element_exists"
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 3000L
                Log.i(TAG, "FIND_EXISTS: $by='$value' -> $variableName (timeout: ${timeout}ms)")
                val element = xpathHelper.waitForElement(by, value, timeout)
                variables[variableName] = if (element.found) "true" else "false"
                Log.i(TAG, "FIND_EXISTS: $variableName = ${variables[variableName]}")
                CommandResult(success = true, data = variables[variableName])
            }
            
            StepType.WAIT_FOR_ELEMENT -> {
                val text = resolveVariables(step.params["text"] ?: step.params["value"] ?: "")
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                Log.i(TAG, "WAIT_FOR_ELEMENT: text='$text' (timeout: ${timeout}ms)")
                if (text.isNotEmpty()) {
                    val element = xpathHelper.findByText(text)
                    if (element.found) {
                        CommandResult(success = true, data = "Element found")
                    } else {
                        // Ждём с таймаутом
                        val waitElement = xpathHelper.waitForElement("text", text, timeout)
                        if (waitElement.found) {
                            CommandResult(success = true, data = "Element found after wait")
                        } else {
                            CommandResult(success = false, error = "Element not found: $text")
                        }
                    }
                } else {
                    CommandResult(success = false, error = "text or value required")
                }
            }
            
            // ===== XPATH_SMART (v2.5.0) - Умный XPath блок с полной логикой =====
            StepType.XPATH_SMART -> {
                val xpath = resolveVariables(step.params["xpath"] ?: throw IllegalArgumentException("xpath required"))
                val behavior = step.params["behavior"] ?: "wait_and_tap"
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 30000L
                val retryCount = step.params["retry_count"]?.toIntOrNull() ?: 3
                val retryInterval = step.params["retry_interval"]?.toLongOrNull() ?: 1000L
                val isOptional = step.params["optional"] == "true"
                val skipOnNotFound = step.params["skip_on_not_found"] == "true"
                val logNotFound = step.params["log_not_found"] == "true"
                val useFallback = step.params["use_fallback"] == "true"
                val fallbackX = step.params["fallback_x"]?.toIntOrNull() ?: 0
                val fallbackY = step.params["fallback_y"]?.toIntOrNull() ?: 0
                val fallbackXpath = step.params["fallback_xpath"] ?: ""
                val description = step.params["description"] ?: xpath.take(30)
                
                Log.i(TAG, "XPATH_SMART: '$description' behavior=$behavior, timeout=${timeout}ms, optional=$isOptional")
                
                var elementFound = false
                var result: CommandResult = CommandResult(success = false, error = "Not executed")
                
                // Разные режимы поведения
                when (behavior) {
                    "wait_and_tap" -> {
                        // Ждать появления → тапнуть (обязательный элемент)
                        for (attempt in 1..retryCount) {
                            Log.d(TAG, "XPATH_SMART: Attempt $attempt/$retryCount for '$description'")
                            val element = xpathHelper.waitForXPath(xpath, timeout / retryCount)
                            if (element.found && element.bounds != null) {
                                Log.i(TAG, "XPATH_SMART: Found '$description' at (${element.bounds.centerX}, ${element.bounds.centerY})")
                                result = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                                elementFound = true
                                break
                            }
                            if (attempt < retryCount) {
                                Log.d(TAG, "XPATH_SMART: Retry in ${retryInterval}ms")
                                delay(retryInterval)
                            }
                        }
                        
                        if (!elementFound) {
                            // Пробуем fallback
                            if (useFallback && (fallbackX > 0 || fallbackY > 0)) {
                                Log.w(TAG, "XPATH_SMART: Element not found, using fallback ($fallbackX, $fallbackY)")
                                result = commandExecutor.tap(fallbackX, fallbackY)
                                elementFound = true
                            } else if (useFallback && fallbackXpath.isNotEmpty()) {
                                Log.w(TAG, "XPATH_SMART: Trying fallback xpath: $fallbackXpath")
                                result = xpathHelper.tapByXPath(fallbackXpath, timeout / 2)
                                elementFound = result.success
                            }
                        }
                        
                        if (!elementFound && !isOptional && !skipOnNotFound) {
                            if (logNotFound) Log.e(TAG, "XPATH_SMART: REQUIRED element not found: '$description'")
                            result = CommandResult(success = false, error = "Required element not found: $description")
                        } else if (!elementFound) {
                            if (logNotFound) Log.w(TAG, "XPATH_SMART: Optional element skipped: '$description'")
                            result = CommandResult(success = true, data = "skipped")
                            variables["_last_smart_result"] = "skipped"
                        } else {
                            variables["_last_smart_result"] = "success"
                        }
                    }
                    
                    "if_visible_tap" -> {
                        // Если виден → тапнуть, если нет → пропустить (опциональный)
                        val element = xpathHelper.waitForXPath(xpath, 3000L) // Короткий таймаут
                        if (element.found && element.bounds != null) {
                            Log.i(TAG, "XPATH_SMART: Visible, tapping '$description'")
                            result = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                            variables["_last_smart_result"] = "success"
                        } else {
                            Log.d(TAG, "XPATH_SMART: Not visible, skipping '$description'")
                            result = CommandResult(success = true, data = "skipped")
                            variables["_last_smart_result"] = "skipped"
                        }
                    }
                    
                    "wait_or_skip" -> {
                        // Ждать N сек, если не появился → пропустить
                        val element = xpathHelper.waitForXPath(xpath, timeout)
                        if (element.found && element.bounds != null) {
                            Log.i(TAG, "XPATH_SMART: Found after wait, tapping '$description'")
                            result = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                            variables["_last_smart_result"] = "success"
                        } else {
                            if (logNotFound) Log.w(TAG, "XPATH_SMART: Not found after ${timeout}ms, skipping '$description'")
                            result = CommandResult(success = true, data = "skipped")
                            variables["_last_smart_result"] = "skipped"
                        }
                    }
                    
                    "wait_or_fail" -> {
                        // Ждать N сек, если не появился → ошибка
                        val element = xpathHelper.waitForXPath(xpath, timeout)
                        if (element.found && element.bounds != null) {
                            result = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                            variables["_last_smart_result"] = "success"
                        } else {
                            Log.e(TAG, "XPATH_SMART: FAILED - Element not found after ${timeout}ms: '$description'")
                            result = CommandResult(success = false, error = "Element not found: $description")
                            variables["_last_smart_result"] = "failed"
                        }
                    }
                    
                    "retry_until_found" -> {
                        // Повторять поиск пока не найдёт (с интервалом)
                        val maxAttempts = (timeout / retryInterval).toInt().coerceAtLeast(1)
                        for (attempt in 1..maxAttempts) {
                            val element = xpathHelper.waitForXPath(xpath, retryInterval / 2)
                            if (element.found && element.bounds != null) {
                                Log.i(TAG, "XPATH_SMART: Found on attempt $attempt, tapping '$description'")
                                result = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                                elementFound = true
                                variables["_last_smart_result"] = "success"
                                break
                            }
                            if (attempt < maxAttempts) {
                                delay(retryInterval / 2)
                            }
                        }
                        if (!elementFound) {
                            if (skipOnNotFound || isOptional) {
                                Log.w(TAG, "XPATH_SMART: Not found after $maxAttempts attempts, skipping '$description'")
                                result = CommandResult(success = true, data = "skipped")
                                variables["_last_smart_result"] = "skipped"
                            } else {
                                result = CommandResult(success = false, error = "Element not found after $maxAttempts attempts: $description")
                                variables["_last_smart_result"] = "failed"
                            }
                        }
                    }
                    
                    "tap_or_fallback" -> {
                        // Попробовать xpath, если не найден → fallback на координаты
                        val element = xpathHelper.waitForXPath(xpath, timeout / 2)
                        if (element.found && element.bounds != null) {
                            Log.i(TAG, "XPATH_SMART: Found by xpath, tapping '$description'")
                            result = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                            variables["_last_smart_result"] = "success"
                        } else if (useFallback && (fallbackX > 0 || fallbackY > 0)) {
                            Log.w(TAG, "XPATH_SMART: Using fallback coordinates ($fallbackX, $fallbackY) for '$description'")
                            result = commandExecutor.tap(fallbackX, fallbackY)
                            variables["_last_smart_result"] = "fallback"
                        } else if (useFallback && fallbackXpath.isNotEmpty()) {
                            Log.w(TAG, "XPATH_SMART: Trying fallback xpath for '$description'")
                            result = xpathHelper.tapByXPath(fallbackXpath, timeout / 2)
                            variables["_last_smart_result"] = if (result.success) "fallback" else "failed"
                        } else {
                            if (skipOnNotFound || isOptional) {
                                result = CommandResult(success = true, data = "skipped")
                                variables["_last_smart_result"] = "skipped"
                            } else {
                                result = CommandResult(success = false, error = "Element not found and no fallback: $description")
                                variables["_last_smart_result"] = "failed"
                            }
                        }
                    }
                    
                    else -> {
                        Log.w(TAG, "XPATH_SMART: Unknown behavior '$behavior', defaulting to wait_and_tap")
                        val element = xpathHelper.waitForXPath(xpath, timeout)
                        if (element.found && element.bounds != null) {
                            result = commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                        } else if (skipOnNotFound || isOptional) {
                            result = CommandResult(success = true, data = "skipped")
                        } else {
                            result = CommandResult(success = false, error = "Element not found: $description")
                        }
                    }
                }
                
                result
            }

            // ===== XPATH_POOL (v2.7.0) - Пул элементов: видим → нажимаем =====
            StepType.XPATH_POOL -> {
                val poolJson = step.params["pool"] ?: "[]"
                val checkMode = step.params["check_mode"] ?: "first_found"
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 5000L
                val retryCount = step.params["retry_count"]?.toIntOrNull() ?: 3
                val retryInterval = step.params["retry_interval"]?.toLongOrNull() ?: 1000L
                val continueOnEmpty = step.params["continue_on_empty"] == "true"
                
                Log.i(TAG, "XPATH_POOL: Starting pool check, mode=$checkMode, retries=$retryCount")
                
                // Парсим JSON пул элементов
                data class PoolItem(val xpath: String, val label: String, val priority: Int)
                val poolItems = try {
                    val jsonArray = org.json.JSONArray(poolJson)
                    (0 until jsonArray.length()).map { i ->
                        val obj = jsonArray.getJSONObject(i)
                        PoolItem(
                            xpath = obj.getString("xpath"),
                            label = obj.optString("label", "element_$i"),
                            priority = obj.optInt("priority", 1)
                        )
                    }.sortedByDescending { it.priority }
                } catch (e: Exception) {
                    Log.e(TAG, "XPATH_POOL: Failed to parse pool JSON: $e")
                    emptyList()
                }
                
                if (poolItems.isEmpty()) {
                    Log.w(TAG, "XPATH_POOL: Pool is empty")
                    if (continueOnEmpty) {
                        variables["_pool_result"] = "empty"
                        CommandResult(success = true, data = "empty_pool")
                    } else {
                        CommandResult(success = false, error = "Pool is empty")
                    }
                } else {
                    var found = false
                    var clickedLabel = ""
                    
                    // Подготавливаем список пар (xpath, label) для оптимизированного поиска
                    val xpathPairs = poolItems
                        .filter { it.xpath.isNotBlank() }
                        .map { Pair(it.xpath, it.label) }
                    
                    for (attempt in 1..retryCount) {
                        Log.d(TAG, "XPATH_POOL: Attempt $attempt/$retryCount, checking ${xpathPairs.size} elements")
                        
                        // v2.7.0: ОПТИМИЗАЦИЯ - ОДИН UI dump для всех элементов!
                        // Раньше: 30 элементов = 30 dump'ов = 30-60 секунд
                        // Теперь: 30 элементов = 1 dump + 30 поисков в памяти = 1-2 секунды!
                        val (element, label) = xpathHelper.findFirstFromPool(xpathPairs)
                        
                        if (element.found && element.bounds != null) {
                            Log.i(TAG, "XPATH_POOL: ✓ Found '$label' at (${element.bounds.centerX}, ${element.bounds.centerY})")
                            commandExecutor.tap(element.bounds.centerX, element.bounds.centerY)
                            found = true
                            clickedLabel = label ?: "unknown"
                            break
                        }
                        
                        if (attempt < retryCount) {
                            Log.d(TAG, "XPATH_POOL: No element found, retrying in ${retryInterval}ms...")
                            delay(retryInterval)
                        }
                    }
                    
                    if (found) {
                        variables["_pool_result"] = "clicked"
                        variables["_pool_clicked"] = clickedLabel
                        Log.i(TAG, "XPATH_POOL: ✓ Clicked '$clickedLabel'")
                        CommandResult(success = true, data = "clicked:$clickedLabel")
                    } else {
                        variables["_pool_result"] = "empty"
                        Log.w(TAG, "XPATH_POOL: No elements found after $retryCount attempts")
                        if (continueOnEmpty) {
                            CommandResult(success = true, data = "no_elements_found")
                        } else {
                            CommandResult(success = false, error = "No elements in pool found")
                        }
                    }
                }
            }

            // ===== ORCHESTRATION (v2.8.0) - Глобальные переменные и события =====
            
            // SET_GLOBAL - Установить глобальную переменную
            StepType.SET_GLOBAL -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val value = resolveVariables(step.params["value"] ?: "")
                val namespace = step.params["namespace"] ?: GlobalVariables.DEFAULT_NAMESPACE
                val ttlMs = step.params["ttl_ms"]?.toLongOrNull()
                
                Log.i(TAG, "SET_GLOBAL: [$namespace:$name] = $value (ttl: ${ttlMs ?: "∞"}ms)")
                GlobalVariables.set(
                    key = name,
                    value = value,
                    namespace = namespace,
                    ttlMillis = ttlMs,
                    scriptId = script.id
                )
                variables["_global_set"] = "true"
                CommandResult(success = true, data = "$namespace:$name=$value")
            }
            
            // GET_GLOBAL - Получить глобальную переменную
            StepType.GET_GLOBAL -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val variableName = step.params["variable"] ?: name
                val namespace = step.params["namespace"] ?: GlobalVariables.DEFAULT_NAMESPACE
                val default = step.params["default"] ?: ""
                
                val value = GlobalVariables.getString(name, namespace, default)
                variables[variableName] = value
                Log.i(TAG, "GET_GLOBAL: [$namespace:$name] = $value -> \$$variableName")
                CommandResult(success = true, data = value)
            }
            
            // DELETE_GLOBAL - Удалить глобальную переменную
            StepType.DELETE_GLOBAL -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val namespace = step.params["namespace"] ?: GlobalVariables.DEFAULT_NAMESPACE
                
                val removed = GlobalVariables.remove(name, namespace)
                Log.i(TAG, "DELETE_GLOBAL: [$namespace:$name] removed=$removed")
                CommandResult(success = true, data = "deleted")
            }
            
            // INCREMENT_GLOBAL - Атомарный инкремент
            StepType.INCREMENT_GLOBAL -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val delta = step.params["delta"]?.toIntOrNull() ?: 1
                val namespace = step.params["namespace"] ?: GlobalVariables.DEFAULT_NAMESPACE
                val variableName = step.params["variable"] ?: "_increment_result"
                
                val newValue = GlobalVariables.increment(name, namespace, delta)
                variables[variableName] = newValue.toString()
                Log.i(TAG, "INCREMENT_GLOBAL: [$namespace:$name] += $delta = $newValue")
                CommandResult(success = true, data = newValue.toString())
            }
            
            // APPEND_TO_LIST - Добавить в глобальный список
            StepType.APPEND_TO_LIST -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val value = resolveVariables(step.params["value"] ?: "")
                val namespace = step.params["namespace"] ?: GlobalVariables.DEFAULT_NAMESPACE
                
                GlobalVariables.appendToList(name, value, namespace)
                Log.i(TAG, "APPEND_TO_LIST: [$namespace:$name] += $value")
                CommandResult(success = true, data = "appended")
            }
            
            // PUT_TO_MAP - Добавить в глобальный map
            StepType.PUT_TO_MAP -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val mapKey = step.params["map_key"] ?: throw IllegalArgumentException("map_key required")
                val mapValue = resolveVariables(step.params["map_value"] ?: "")
                val namespace = step.params["namespace"] ?: GlobalVariables.DEFAULT_NAMESPACE
                
                GlobalVariables.putToMap(name, mapKey, mapValue, namespace)
                Log.i(TAG, "PUT_TO_MAP: [$namespace:$name][$mapKey] = $mapValue")
                CommandResult(success = true, data = "put")
            }
            
            // EMIT_EVENT - Отправить событие в EventBus
            StepType.EMIT_EVENT -> {
                val eventType = step.params["event_type"] ?: throw IllegalArgumentException("event_type required")
                val target = step.params["target"] // null = broadcast
                val payloadJson = step.params["payload"] ?: "{}"
                
                val payload = try {
                    val jsonObj = org.json.JSONObject(payloadJson)
                    jsonObj.keys().asSequence().associateWith { key -> jsonObj.get(key) }
                } catch (e: Exception) {
                    mapOf("raw" to payloadJson)
                }
                
                Log.i(TAG, "EMIT_EVENT: $eventType -> ${target ?: "broadcast"}")
                ScriptEventBus.emitSync(ScriptEventBus.ScriptEvent(
                    type = eventType,
                    source = script.id,
                    target = target,
                    payload = payload
                ))
                CommandResult(success = true, data = "event_emitted:$eventType")
            }
            
            // WAIT_FOR_EVENT - Ожидать событие
            StepType.WAIT_FOR_EVENT -> {
                val pattern = step.params["event_pattern"] ?: throw IllegalArgumentException("event_pattern required")
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 30000L
                val variableName = step.params["variable"] ?: "_event_data"
                
                Log.i(TAG, "WAIT_FOR_EVENT: $pattern (timeout: ${timeout}ms)")
                
                val event = ScriptEventBus.waitForEvent(pattern, timeout)
                
                if (event != null) {
                    variables[variableName] = event.payload.toString()
                    variables["_event_type"] = event.type
                    variables["_event_source"] = event.source
                    Log.i(TAG, "WAIT_FOR_EVENT: Received ${event.type} from ${event.source}")
                    CommandResult(success = true, data = event.type)
                } else {
                    Log.w(TAG, "WAIT_FOR_EVENT: Timeout waiting for $pattern")
                    variables[variableName] = ""
                    CommandResult(success = false, error = "Timeout waiting for event: $pattern")
                }
            }
            
            // SUBSCRIBE_EVENT - Подписаться на событие (обработка в фоне)
            StepType.SUBSCRIBE_EVENT -> {
                val pattern = step.params["event_pattern"] ?: throw IllegalArgumentException("event_pattern required")
                val handlerStepId = step.params["handler_step_id"]
                
                Log.i(TAG, "SUBSCRIBE_EVENT: $pattern")
                
                val subscriptionId = ScriptEventBus.subscribe(
                    pattern = pattern,
                    scriptId = script.id
                ) { event ->
                    Log.d(TAG, "SUBSCRIBE_EVENT: Received ${event.type}")
                    // TODO: Jump to handler step or execute inline
                }
                
                variables["_subscription_id"] = subscriptionId
                CommandResult(success = true, data = subscriptionId)
            }
            
            // START_SCRIPT - Запустить другой скрипт
            StepType.START_SCRIPT -> {
                val targetScriptId = step.params["script_id"] ?: throw IllegalArgumentException("script_id required")
                val async = step.params["async"] == "true"
                val variablesJson = step.params["variables"] ?: "{}"
                
                Log.i(TAG, "START_SCRIPT: $targetScriptId (async=$async)")
                
                // Отправляем событие для запуска скрипта (обработается AgentService)
                ScriptEventBus.emitSync(ScriptEventBus.ScriptEvent(
                    type = ScriptEventBus.EventTypes.SYSTEM_START_SCRIPT,
                    source = script.id,
                    payload = mapOf(
                        "script_id" to targetScriptId,
                        "variables" to variablesJson,
                        "async" to async,
                        "triggered_by" to runId
                    )
                ))
                CommandResult(success = true, data = "start_requested:$targetScriptId")
            }
            
            // STOP_SCRIPT - Остановить скрипт по runId
            StepType.STOP_SCRIPT -> {
                val targetRunId = step.params["run_id"] ?: throw IllegalArgumentException("run_id required")
                
                Log.i(TAG, "STOP_SCRIPT: $targetRunId")
                
                ScriptEventBus.emitSync(ScriptEventBus.ScriptEvent(
                    type = ScriptEventBus.EventTypes.SYSTEM_STOP_SCRIPT,
                    source = script.id,
                    payload = mapOf("run_id" to targetRunId)
                ))
                CommandResult(success = true, data = "stop_requested:$targetRunId")
            }
            
            // WAIT_SCRIPT - Ждать завершения скрипта
            StepType.WAIT_SCRIPT -> {
                val targetRunId = step.params["run_id"] ?: throw IllegalArgumentException("run_id required")
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 300000L // 5 min default
                
                Log.i(TAG, "WAIT_SCRIPT: $targetRunId (timeout: ${timeout}ms)")
                
                val event = ScriptEventBus.waitForEvent(
                    pattern = "script.completed",
                    timeoutMs = timeout
                ) { it.payload["run_id"] == targetRunId }
                
                if (event != null) {
                    Log.i(TAG, "WAIT_SCRIPT: Script $targetRunId completed")
                    variables["_waited_script_result"] = event.payload.toString()
                    CommandResult(success = true, data = "script_completed:$targetRunId")
                } else {
                    Log.w(TAG, "WAIT_SCRIPT: Timeout waiting for $targetRunId")
                    CommandResult(success = false, error = "Timeout waiting for script: $targetRunId")
                }
            }
            
            // REGISTER_TRIGGER - Зарегистрировать триггер
            StepType.REGISTER_TRIGGER -> {
                val name = step.params["name"] ?: "trigger_${System.currentTimeMillis()}"
                val eventPattern = step.params["event_pattern"] ?: throw IllegalArgumentException("event_pattern required")
                val actionType = step.params["action_type"] ?: "emit_event"
                val actionValue = step.params["action_value"] ?: ""
                
                Log.i(TAG, "REGISTER_TRIGGER: $name on $eventPattern")
                
                val action = when (actionType) {
                    "start_script" -> ScriptEventBus.TriggerAction.StartScript(actionValue)
                    "emit_event" -> ScriptEventBus.TriggerAction.EmitEvent(actionValue)
                    "set_global" -> {
                        val parts = actionValue.split("=", limit = 2)
                        ScriptEventBus.TriggerAction.SetGlobalVariable(parts[0], parts.getOrNull(1))
                    }
                    else -> ScriptEventBus.TriggerAction.EmitEvent(actionValue)
                }
                
                val triggerId = ScriptEventBus.registerTrigger(ScriptEventBus.EventTrigger(
                    name = name,
                    eventPattern = eventPattern,
                    action = action
                ))
                
                variables["_trigger_id"] = triggerId
                CommandResult(success = true, data = triggerId)
            }
            
            // REMOVE_TRIGGER - Удалить триггер
            StepType.REMOVE_TRIGGER -> {
                val triggerId = step.params["trigger_id"] ?: throw IllegalArgumentException("trigger_id required")
                
                Log.i(TAG, "REMOVE_TRIGGER: $triggerId")
                ScriptEventBus.removeTrigger(triggerId)
                CommandResult(success = true, data = "removed:$triggerId")
            }

            else -> {
                Log.w(TAG, "Unknown step type: ${step.type}")
                CommandResult(success = false, error = "Unknown step type: ${step.type}")
            }
        }
        
        if (!result.success) {
            throw RuntimeException("Step failed: ${result.error}")
        }
    }
    
    private fun resolveVariables(text: String): String {
        var result = text
        variables.forEach { (key, value) ->
            result = result.replace("\${$key}", value)
            result = result.replace("{{$key}}", value)
        }
        return result
    }
    
    private fun evaluateCondition(condition: String): Boolean {
        // Простая проверка переменных: "variable == value" или "variable != value"
        val parts = condition.split(" ")
        if (parts.size >= 3) {
            val varName = parts[0]
            val op = parts[1]
            val value = parts.drop(2).joinToString(" ")
            
            val varValue = variables[varName] ?: ""
            
            return when (op) {
                "==" -> varValue == value
                "!=" -> varValue != value
                ">" -> (varValue.toIntOrNull() ?: 0) > (value.toIntOrNull() ?: 0)
                "<" -> (varValue.toIntOrNull() ?: 0) < (value.toIntOrNull() ?: 0)
                else -> true
            }
        }
        return true
    }
    
    fun stop() {
        isStopped = true
        job?.cancel()
    }
    
    fun pause() {
        isPaused = true
        updateStatus(ScriptState.PAUSED, -1, "Paused")
    }
    
    fun resume() {
        isPaused = false
        updateStatus(ScriptState.RUNNING, -1, "Resumed")
    }
    
    private fun updateStatus(state: ScriptState, stepIndex: Int, stepName: String, error: String? = null) {
        val currentStep = if (stepIndex >= 0) stepIndex else (_status.value?.currentStep ?: 0)
        val progress = if (script.steps.isNotEmpty()) currentStep.toFloat() / script.steps.size else 0f
        
        val status = ScriptStatus(
            runId = runId,
            scriptId = script.id,
            scriptName = script.name,
            state = state,
            currentStep = currentStep,
            totalSteps = script.steps.size,
            currentStepName = stepName,
            progress = progress,
            loopCount = loopCount,
            loopMode = loopMode,
            startedAt = _status.value?.startedAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            error = error,
            variables = variables.toMap()
        )
        
        _status.value = status
        onUpdate(status)
    }
}
