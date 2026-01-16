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
    
    // Логика
    SET_VARIABLE,       // Установить переменную: name, value
    LOG,                // Лог сообщение: message
    SCREENSHOT,         // Сделать скриншот: filename
    
    // Control flow
    IF,                 // Условие: condition, then_step, else_step
    LOOP,               // Цикл: count, steps
    GOTO,               // Переход: step_id
    STOP                // Остановить скрипт
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
                }
            } catch (e: CancellationException) {
                updateStatus(ScriptState.STOPPED, -1, "Stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Script error", e)
                updateStatus(ScriptState.ERROR, -1, "Error", e.message)
            }
        }
    }
    
    private suspend fun executeScript() {
        for ((index, step) in script.steps.withIndex()) {
            if (isStopped) break
            
            // Пауза
            while (isPaused && !isStopped) {
                delay(100)
            }
            if (isStopped) break
            
            // Проверка условия
            if (step.condition != null && !evaluateCondition(step.condition)) {
                Log.d(TAG, "Step ${step.id} skipped: condition not met")
                continue
            }
            
            val stepName = step.name.ifEmpty { step.type.name }
            updateStatus(ScriptState.RUNNING, index, stepName)
            
            try {
                executeStep(step)
                
                // Задержка после шага
                val delay = step.delay ?: script.settings.defaultDelay
                if (delay > 0) {
                    delay(delay)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Step ${step.id} failed", e)
                
                when (step.onError ?: "stop") {
                    "continue" -> continue
                    "stop" -> throw e
                    else -> {
                        if (step.onError?.startsWith("goto:") == true) {
                            // TODO: implement goto
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
