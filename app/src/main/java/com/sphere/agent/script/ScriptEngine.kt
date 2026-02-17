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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val onStatusUpdate: (ScriptStatus) -> Unit,
    private val agentId: String = "",
    private val deviceName: String = ""
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
        
        // v3.5.2: Используем execution_id с сервера если есть, иначе генерируем свой
        val executionId = script.execution_id ?: UUID.randomUUID().toString()
        val runId = executionId.take(8)
        
        // v3.5.0: Запускаем логирование для этого выполнения
        ScriptLogSender.startExecution(
            executionId = executionId,
            agentId = agentId,
            deviceName = deviceName,
            scriptName = script.name
        )
        
        val runner = ScriptRunner(
            runId = runId,
            executionId = executionId,
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
        
        Log.i(TAG, "Started script '${script.name}' with runId=$runId, executionId=$executionId, loopMode=$loopMode")
        return runId
    }
    
    /**
     * v4.0.0: Запуск скрипта по расписанию из ScriptScheduler
     * Загружает скрипт из кеша или запрашивает у сервера
     */
    fun startScheduledScript(scriptId: String, variables: Map<String, String> = emptyMap()) {
        Log.i(TAG, "Starting scheduled script: $scriptId")
        
        // Ищем скрипт в кеше загруженных скриптов
        val cachedScript = scriptCache[scriptId]
        if (cachedScript != null) {
            val scriptWithVars = if (variables.isNotEmpty()) {
                cachedScript.copy(variables = cachedScript.variables + variables)
            } else cachedScript
            startScript(scriptWithVars)
        } else {
            Log.w(TAG, "Script $scriptId not in cache, requesting from server")
            // Отправляем запрос на сервер для получения скрипта
            // Используем специальный статус чтобы AgentService запросил скрипт
            onStatusUpdate(ScriptStatus(
                runId = "schedule_$scriptId",
                executionId = scriptId,
                scriptId = scriptId,
                scriptName = "scheduled_$scriptId",
                state = ScriptState.PENDING,
                currentStep = 0,
                totalSteps = 0,
                currentStepName = "schedule_request",
                progress = 0f,
                startedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                error = "schedule_request:$scriptId"
            ))
        }
    }
    
    /**
     * v4.0.0: Кеш скриптов для scheduled запусков
     */
    private val scriptCache = ConcurrentHashMap<String, Script>()
    
    /**
     * v4.0.0: Кешировать скрипт для scheduled запусков
     */
    fun cacheScript(scriptId: String, script: Script) {
        scriptCache[scriptId] = script
        Log.d(TAG, "Cached script: $scriptId (${script.name})")
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
    val executionId: String,          // v3.5.3: Full execution ID for backend
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
    val settings: ScriptSettings = ScriptSettings(),
    val execution_id: String? = null  // v3.5.2: Server execution ID for logs
)

@Serializable
data class ScriptSettings(
    val defaultDelay: Long = 500,           // Задержка между шагами (мс)
    val retryOnError: Boolean = false,      // Повторять при ошибке
    val maxRetries: Int = 3,                // Макс. попыток
    val continueOnError: Boolean = false,   // Продолжать при ошибке
    val loopDelay: Long = 1000,             // Задержка между циклами (мс)
    // v4.0.0: CPU Throttling настройки
    val cpuThresholdLow: Int = 15,          // Порог умеренной нагрузки (%)
    val cpuThresholdHigh: Int = 25,         // Порог высокой нагрузки (%)
    val cpuThresholdCritical: Int = 40,     // Порог критической нагрузки (%)
    val cpuThrottlingEnabled: Boolean = true // Включён ли CPU throttling
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
    WAIT_RANDOM,        // Случайное ожидание: minDelay, maxDelay (секунды)
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
    REMOVE_TRIGGER,     // Удаление триггера: trigger_id
    
    // ===== ENTERPRISE v2.28.0 - Полная поддержка Visual Editor =====
    
    // Расширенные переменные
    VAR_SET,            // Установить локальную переменную: name, value
    VAR_GET,            // Получить переменную: name, default?, variable
    MATH,               // Математические операции: operation (+,-,*,/,%), a, b, variable
    RANDOM,             // Случайное число: min, max, variable
    COUNTER,            // Счётчик: name, operation (inc/dec/reset), variable
    
    // Расширенный control flow
    WHILE,              // Цикл while: condition, max_iterations
    LOOP_FOREVER,       // Бесконечный цикл: delay_between
    BREAK,              // Выход из цикла
    CONTINUE,           // Следующая итерация цикла
    TRY_CATCH,          // Try-catch блок: try_steps, catch_steps
    RESTART_SCRIPT,     // Перезапуск текущего скрипта
    ASSERT,             // Проверка условия: condition, message
    
    // Уведомления и логирование
    NOTIFY,             // Отправить уведомление: title, message, type
    
    // Время и расписание
    TIME_CHECK,         // Проверка времени: hour_start, hour_end, variable
    DATE_CHECK,         // Проверка даты: date_start, date_end, variable
    WAIT_UNTIL_TIME,    // Ждать до времени: hour, minute
    SCHEDULE_HOURLY,    // Запуск каждый час: minute
    SCHEDULE_DAILY,     // Запуск ежедневно: hour, minute
    SCHEDULE_INTERVAL,  // Запуск по интервалу: interval_ms
    SCHEDULE_CRON,      // Cron выражение: cron_expression
    SCHEDULE_POINTS,    // Запуск в определённые моменты: times[]
    
    // Экран и стабильность
    WAIT_SCREEN_STABLE, // Ждать стабильности экрана: timeout, threshold
    
    // OCR (распознавание текста)
    OCR_WAIT,           // Ждать текст на экране: text, timeout, region?
    OCR_TAP,            // Найти и тапнуть по тексту: text, timeout, region?
    
    // Template matching (поиск изображения)
    TEMPLATE_WAIT,      // Ждать изображение: template_id, timeout, threshold
    TEMPLATE_TAP,       // Найти и тапнуть по изображению: template_id, timeout
    TEMPLATE_EXISTS,    // Проверить наличие изображения: template_id, variable
    
    // Pixel detection
    PIXEL_CHECK,        // Проверить цвет пикселя: x, y, expected_color, variable
    PIXEL_WAIT,         // Ждать цвет пикселя: x, y, expected_color, timeout
    PIXEL_GROUP,        // Проверить группу пикселей: pixels[], variable
    
    // Жесты
    PINCH,              // Pinch жест: x, y, scale (>1 zoom in, <1 zoom out)
    
    // Буфер обмена
    CLIPBOARD_SET,      // Установить текст в буфер: text
    CLIPBOARD_GET       // Получить текст из буфера: variable
}

/**
 * Исполнитель одного скрипта
 * 
 * v2.4.0: Интегрирован XPathHelper для XPath/UIAutomator2 команд
 * v3.5.0: Интегрирован ScriptLogSender для отправки логов на сервер
 */
class ScriptRunner(
    private val runId: String,
    private val executionId: String,
    private val script: Script,
    private val commandExecutor: CommandExecutor,
    private val loopMode: Boolean,
    private val onUpdate: (ScriptStatus) -> Unit
) {
    companion object {
        private const val TAG = "ScriptRunner"
    }
    
    private var job: Job? = null
    // v3.7.0: AtomicBoolean для thread safety между корутинами (pause/stop вызываются извне)
    private val isPaused = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    
    private val variables = script.variables.toMutableMap()
    private var loopCount = 0
    
    // XPathHelper для XPath/UIAutomator2 команд (v2.4.0)
    private val xpathHelper = XPathHelper(commandExecutor)
    
    // v4.0.0: CPU Throttler — адаптивное управление нагрузкой
    private val cpuThrottler = AdaptiveCpuThrottler(
        cpuThresholdLow = script.settings.cpuThresholdLow,
        cpuThresholdHigh = script.settings.cpuThresholdHigh,
        cpuThresholdCritical = script.settings.cpuThresholdCritical
    ).also { it.setEnabled(script.settings.cpuThrottlingEnabled) }
    
    private val _status = MutableStateFlow<ScriptStatus?>(null)
    val status: StateFlow<ScriptStatus?> = _status
    
    suspend fun start() {
        isStopped.set(false)
        isPaused.set(false)
        
        updateStatus(ScriptState.RUNNING, 0, "Starting...")
        
        // v2.8.0: Emit script started event
        ScriptEventBus.emitScriptStarted(script.id, script.name, runId)
        
        // v3.5.4 OPTIMIZATION: Привязываем scope к родительскому через SupervisorJob
        // Было: CoroutineScope(Dispatchers.Default).launch - полностью отвязанный scope
        // Стало: связан с родителем, но при отмене родителя отменится и наш job
        val parentJob = currentCoroutineContext()[Job]
        val scriptScope = CoroutineScope(Dispatchers.Default + SupervisorJob(parentJob))
        
        job = scriptScope.launch {
            try {
                do {
                    executeScript()
                    
                    if (loopMode && !isStopped.get()) {
                        loopCount++
                        updateStatus(ScriptState.RUNNING, 0, "Loop ${loopCount + 1} starting...")
                        delay(script.settings.loopDelay)
                    }
                } while (loopMode && !isStopped.get())
                
                if (!isStopped.get()) {
                    updateStatus(ScriptState.COMPLETED, script.steps.size, "Completed")
                    // v2.8.0: Emit script completed event
                    ScriptEventBus.emitScriptCompleted(script.id, runId, mapOf(
                        "variables" to variables,
                        "loop_count" to loopCount
                    ))
                    // v3.5.0: Завершаем логирование
                    ScriptLogSender.endExecution(executionId, success = true)
                }
            } catch (e: CancellationException) {
                updateStatus(ScriptState.STOPPED, -1, "Stopped")
                // v2.8.0: Emit script stopped event
                ScriptEventBus.emitSync(ScriptEventBus.ScriptEvent(
                    type = ScriptEventBus.EventTypes.SCRIPT_STOPPED,
                    source = script.id,
                    payload = mapOf("run_id" to runId, "reason" to "cancelled")
                ))
                // v3.5.0: Завершаем логирование (cancelled)
                ScriptLogSender.endExecution(executionId, success = false, error = "Cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Script error", e)
                updateStatus(ScriptState.ERROR, -1, "Error", e.message)
                // v2.8.0: Emit script failed event
                ScriptEventBus.emitScriptFailed(script.id, runId, e.message ?: "Unknown error")
                // v3.5.0: Завершаем логирование (error)
                ScriptLogSender.endExecution(executionId, success = false, error = e.message)
            } finally {
                // v2.8.0: Cleanup subscriptions for this script
                ScriptEventBus.unsubscribeAll(script.id)
            }
        }
    }
    
    /**
     * v3.7.0: Полная реализация control flow:
     * - WHILE: цикл с условием и max_iterations, end_step_id для конца блока
     * - LOOP_FOREVER: делегирует к WHILE с condition="true"
     * - BREAK: выход из текущего цикла (WHILE/LOOP_FOREVER)
     * - CONTINUE: переход к следующей итерации цикла
     * - TRY_CATCH: try блок до catch_step_id, при ошибке jump к catch_step_id
     * - RESTART_SCRIPT: перезапуск скрипта с начала
     *
     * Стек циклов (loopStack) хранит контексты вложенных циклов.
     */
    
    // v3.7.0: Контекст цикла для стека
    private data class LoopContext(
        val startIndex: Int,      // Индекс шага WHILE/LOOP_FOREVER
        val endIndex: Int,        // Индекс шага после конца блока
        val condition: String,    // Условие цикла ("true" для LOOP_FOREVER)
        val maxIterations: Int,   // Максимум итераций
        var currentIteration: Int = 0
    )
    
    // v3.7.0: Контекст try-catch
    private data class TryCatchContext(
        val tryStartIndex: Int,
        val catchIndex: Int,       // Индекс шага catch
        val endIndex: Int          // Индекс шага после catch блока
    )
    
    private suspend fun executeScript() {
        var currentIndex = 0
        val loopStack = ArrayDeque<LoopContext>()
        val tryCatchStack = ArrayDeque<TryCatchContext>()
        
        while (currentIndex < script.steps.size && !isStopped.get()) {
            val step = script.steps[currentIndex]
            
            // Пауза
            while (isPaused.get() && !isStopped.get()) {
                delay(100)
            }
            if (isStopped.get()) break
            
            // v3.7.0: Проверка _restart флага
            if (variables["_restart"] == "true") {
                variables.remove("_restart")
                loopStack.clear()
                tryCatchStack.clear()
                loopCount++
                Log.i(TAG, "RESTART_SCRIPT: Restarting from step 0 (loop $loopCount)")
                currentIndex = 0
                continue
            }
            
            // v3.7.0: Проверка _break флага
            if (variables["_break"] == "true") {
                variables.remove("_break")
                if (loopStack.isNotEmpty()) {
                    val ctx = loopStack.removeFirst()
                    Log.i(TAG, "BREAK: Exiting loop, jumping to index ${ctx.endIndex}")
                    currentIndex = ctx.endIndex
                    continue
                }
                // BREAK вне цикла — просто идём дальше
            }
            
            // v3.7.0: Проверка _continue флага
            if (variables["_continue"] == "true") {
                variables.remove("_continue")
                if (loopStack.isNotEmpty()) {
                    val ctx = loopStack.first()
                    ctx.currentIteration++
                    Log.i(TAG, "CONTINUE: Next iteration (${ctx.currentIteration}), jumping to index ${ctx.startIndex}")
                    currentIndex = ctx.startIndex
                    continue
                }
                // CONTINUE вне цикла — просто идём дальше
            }
            
            // Проверка условия (уровень шага)
            if (step.condition != null && !evaluateCondition(step.condition)) {
                Log.d(TAG, "Step ${step.id} skipped: condition not met")
                currentIndex++
                continue
            }
            
            val stepName = step.name.ifEmpty { step.type.name }
            updateStatus(ScriptState.RUNNING, currentIndex, stepName)
            
            // v3.5.0: Замер времени выполнения шага
            val stepStartTime = System.currentTimeMillis()
            
            try {
                // Обработка управляющих конструкций прямо здесь
                when (step.type) {
                    StepType.GOTO -> {
                        val targetId = step.params["target_id"] ?: ""
                        val targetIndex = script.steps.indexOfFirst { it.id == targetId }
                        if (targetIndex != -1) {
                            Log.i(TAG, "GOTO: Jumping to step $targetId (index $targetIndex)")
                            ScriptLogSender.logStep(
                                executionId = executionId,
                                stepIndex = currentIndex,
                                stepType = "GOTO",
                                stepName = "Jump to $targetId",
                                success = true,
                                durationMs = System.currentTimeMillis() - stepStartTime
                            )
                            currentIndex = targetIndex
                            continue
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
                        
                        ScriptLogSender.logStep(
                            executionId = executionId,
                            stepIndex = currentIndex,
                            stepType = "IF",
                            stepName = "Condition: $condition = $result",
                            success = true,
                            durationMs = System.currentTimeMillis() - stepStartTime,
                            details = mapOf("condition" to condition, "result" to result.toString())
                        )
                        
                        if (targetId.isNotEmpty()) {
                            val targetIndex = script.steps.indexOfFirst { it.id == targetId }
                            if (targetIndex != -1) {
                                Log.i(TAG, "IF '$condition' is $result, Jumping to $targetId")
                                currentIndex = targetIndex
                                continue
                            }
                        }
                    }
                    
                    // v3.7.0: WHILE — цикл с условием
                    StepType.WHILE -> {
                        val condition = step.params["condition"] ?: "true"
                        val maxIter = step.params["max_iterations"]?.toIntOrNull() ?: 10000
                        val endStepId = step.params["end_step_id"] ?: ""
                        
                        // Находим конец блока WHILE
                        val endIndex = if (endStepId.isNotEmpty()) {
                            val idx = script.steps.indexOfFirst { it.id == endStepId }
                            if (idx != -1) idx + 1 else script.steps.size
                        } else {
                            // Если end_step_id не указан — ищем следующий WHILE/LOOP_FOREVER на том же уровне или конец скрипта
                            currentIndex + 1 + (step.params["body_length"]?.toIntOrNull() ?: 1)
                        }
                        
                        // Проверяем условие входа в цикл
                        if (evaluateCondition(condition)) {
                            // Проверяем: если мы уже в этом цикле (стек), инкремент итерации
                            val existingCtx = loopStack.firstOrNull { it.startIndex == currentIndex }
                            if (existingCtx != null) {
                                existingCtx.currentIteration++
                                if (existingCtx.currentIteration >= existingCtx.maxIterations) {
                                    Log.i(TAG, "WHILE: Max iterations (${existingCtx.maxIterations}) reached, exiting")
                                    loopStack.removeFirst()
                                    currentIndex = existingCtx.endIndex
                                    continue
                                }
                            } else {
                                // Новый цикл — push в стек
                                loopStack.addFirst(LoopContext(
                                    startIndex = currentIndex,
                                    endIndex = endIndex,
                                    condition = condition,
                                    maxIterations = maxIter,
                                    currentIteration = 0
                                ))
                            }
                            Log.d(TAG, "WHILE: condition='$condition' true, iteration=${loopStack.first().currentIteration}")
                            // Входим в тело цикла — следующий шаг
                        } else {
                            // Условие false — пропускаем весь блок
                            val ctx = loopStack.firstOrNull { it.startIndex == currentIndex }
                            if (ctx != null) loopStack.removeFirst()
                            Log.d(TAG, "WHILE: condition='$condition' false, jumping to $endIndex")
                            currentIndex = endIndex
                            continue
                        }
                    }
                    
                    // v3.7.0: LOOP_FOREVER — бесконечный цикл (делегирует к WHILE)
                    StepType.LOOP_FOREVER -> {
                        val endStepId = step.params["end_step_id"] ?: ""
                        val delayBetween = step.params["delay_between"]?.toLongOrNull() ?: 1000L
                        
                        val endIndex = if (endStepId.isNotEmpty()) {
                            val idx = script.steps.indexOfFirst { it.id == endStepId }
                            if (idx != -1) idx + 1 else script.steps.size
                        } else {
                            currentIndex + 1 + (step.params["body_length"]?.toIntOrNull() ?: 1)
                        }
                        
                        val existingCtx = loopStack.firstOrNull { it.startIndex == currentIndex }
                        if (existingCtx != null) {
                            existingCtx.currentIteration++
                            // Задержка между итерациями
                            if (delayBetween > 0) delay(delayBetween)
                        } else {
                            loopStack.addFirst(LoopContext(
                                startIndex = currentIndex,
                                endIndex = endIndex,
                                condition = "true",
                                maxIterations = Int.MAX_VALUE,
                                currentIteration = 0
                            ))
                        }
                        Log.d(TAG, "LOOP_FOREVER: iteration=${loopStack.first().currentIteration}")
                    }
                    
                    // v3.7.0: BREAK — выход из цикла (устанавливает флаг, обрабатывается выше)
                    StepType.BREAK -> {
                        Log.i(TAG, "BREAK: Setting _break flag")
                        variables["_break"] = "true"
                        // Обработка в начале следующей итерации while loop
                    }
                    
                    // v3.7.0: CONTINUE — следующая итерация (устанавливает флаг, обрабатывается выше)
                    StepType.CONTINUE -> {
                        Log.i(TAG, "CONTINUE: Setting _continue flag")
                        variables["_continue"] = "true"
                    }
                    
                    // v3.7.0: TRY_CATCH — обработка ошибок
                    StepType.TRY_CATCH -> {
                        val catchStepId = step.params["catch_step_id"] ?: ""
                        val endStepId = step.params["end_step_id"] ?: ""
                        
                        val catchIndex = if (catchStepId.isNotEmpty()) {
                            script.steps.indexOfFirst { it.id == catchStepId }
                        } else -1
                        
                        val endIndex = if (endStepId.isNotEmpty()) {
                            val idx = script.steps.indexOfFirst { it.id == endStepId }
                            if (idx != -1) idx + 1 else script.steps.size
                        } else if (catchIndex != -1) {
                            catchIndex + (step.params["catch_length"]?.toIntOrNull() ?: 1)
                        } else {
                            currentIndex + 2
                        }
                        
                        if (catchIndex != -1) {
                            tryCatchStack.addFirst(TryCatchContext(
                                tryStartIndex = currentIndex,
                                catchIndex = catchIndex,
                                endIndex = endIndex
                            ))
                            Log.d(TAG, "TRY_CATCH: try block started, catch at $catchIndex, end at $endIndex")
                        } else {
                            Log.w(TAG, "TRY_CATCH: catch_step_id not found, skipping")
                        }
                    }
                    
                    // v3.7.0: RESTART_SCRIPT — устанавливает флаг перезапуска
                    StepType.RESTART_SCRIPT -> {
                        Log.i(TAG, "RESTART_SCRIPT: Setting _restart flag")
                        variables["_restart"] = "true"
                    }
                    
                    else -> {
                        executeStep(step)
                        // v3.5.0: Логируем успешное выполнение шага
                        val stepDuration = System.currentTimeMillis() - stepStartTime
                        ScriptLogSender.logStep(
                            executionId = executionId,
                            stepIndex = currentIndex,
                            stepType = step.type.name,
                            stepName = stepName,
                            success = true,
                            durationMs = stepDuration,
                            details = step.params.mapValues { it.value.take(100) }
                        )
                    }
                }
                
                // v3.7.0: Если текущий шаг — конец тела WHILE/LOOP_FOREVER, jump обратно к началу цикла
                if (loopStack.isNotEmpty()) {
                    val topLoop = loopStack.first()
                    // Если следующий шаг выходит за границы тела цикла — возвращаемся к началу
                    if (currentIndex + 1 >= topLoop.endIndex) {
                        currentIndex = topLoop.startIndex
                        continue
                    }
                }
                
                // v4.0.0: Адаптивная задержка с CPU throttling
                val stepDelay = step.delay ?: script.settings.defaultDelay
                cpuThrottler.checkAndThrottle(stepDelay)
                
                currentIndex++
            } catch (e: Exception) {
                Log.e(TAG, "Step ${step.id} failed", e)
                
                // v3.5.0: Логируем ошибку шага
                val stepDuration = System.currentTimeMillis() - stepStartTime
                ScriptLogSender.logStep(
                    executionId = executionId,
                    stepIndex = currentIndex,
                    stepType = step.type.name,
                    stepName = stepName,
                    success = false,
                    durationMs = stepDuration,
                    error = e.message,
                    details = step.params.mapValues { it.value.take(100) }
                )
                
                // v3.7.0: Проверяем TRY_CATCH стек — если в try блоке, jump к catch
                if (tryCatchStack.isNotEmpty()) {
                    val ctx = tryCatchStack.first()
                    if (currentIndex > ctx.tryStartIndex && currentIndex < ctx.catchIndex) {
                        // Мы в try блоке — jump к catch
                        variables["_error"] = e.message ?: "Unknown error"
                        Log.i(TAG, "TRY_CATCH: Error in try block, jumping to catch at ${ctx.catchIndex}")
                        tryCatchStack.removeFirst()
                        currentIndex = ctx.catchIndex
                        continue
                    }
                }
                
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
            
            StepType.WAIT_RANDOM -> {
                // Параметры могут быть: min/max (мс от backend), minDelay/maxDelay (сек от visual)
                val minDelayRaw = step.params["min"]?.toLongOrNull()
                    ?: step.params["minDelay"]?.toLongOrNull() 
                    ?: step.params["min_delay"]?.toLongOrNull() 
                    ?: 500
                val maxDelayRaw = step.params["max"]?.toLongOrNull()
                    ?: step.params["maxDelay"]?.toLongOrNull() 
                    ?: step.params["max_delay"]?.toLongOrNull() 
                    ?: 2000
                
                // Если значения маленькие (<100), считаем что это секунды - конвертируем в мс
                val minDelayMs = if (minDelayRaw < 100) minDelayRaw * 1000 else minDelayRaw
                val maxDelayMs = if (maxDelayRaw < 100) maxDelayRaw * 1000 else maxDelayRaw
                
                val actualDelay = (minDelayMs..maxDelayMs).random()
                Log.i(TAG, "WAIT_RANDOM: ${actualDelay}ms (range: ${minDelayMs}-${maxDelayMs}ms)")
                delay(actualDelay)
                CommandResult(success = true, data = "waited:${actualDelay}ms")
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
                // v4.0.0: Реализация через screencap
                val filename = resolveVariables(step.params["filename"] ?: "screenshot_${System.currentTimeMillis()}.png")
                val path = "/sdcard/Download/$filename"
                val result = commandExecutor.shell("screencap -p $path")
                if (result.success) {
                    variables["_last_screenshot"] = path
                    Log.i(TAG, "SCREENSHOT: Saved to $path")
                    CommandResult(success = true, data = path)
                } else {
                    Log.e(TAG, "SCREENSHOT: Failed - ${result.error}")
                    CommandResult(success = false, error = "Screenshot failed: ${result.error}")
                }
            }
            
            StepType.STOP -> {
                isStopped.set(true)
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
            
            // v4.0.0: SUBSCRIBE_EVENT - Подписаться на событие с jump to handler step
            StepType.SUBSCRIBE_EVENT -> {
                val pattern = step.params["event_pattern"] ?: throw IllegalArgumentException("event_pattern required")
                val handlerStepId = step.params["handler_step_id"]
                val handlerStepIndex = step.params["handler_step_index"]?.toIntOrNull()
                val savePayloadTo = step.params["save_payload_to"] ?: "_event_payload"
                
                Log.i(TAG, "SUBSCRIBE_EVENT: $pattern, handler=$handlerStepId/$handlerStepIndex")
                
                val subscriptionId = ScriptEventBus.subscribe(
                    pattern = pattern,
                    scriptId = script.id
                ) { event ->
                    Log.i(TAG, "SUBSCRIBE_EVENT: Received ${event.type}, payload=${event.payload}")
                    
                    // Сохраняем payload события в переменные (Map → JSON string)
                    variables[savePayloadTo] = try {
                        org.json.JSONObject(event.payload.filterValues { it != null } as Map<String, Any>).toString()
                    } catch (e: Exception) { event.payload.toString() }
                    variables["_event_type"] = event.type
                    variables["_event_source"] = event.source
                    
                    // Jump to handler step если указан
                    val targetIndex = handlerStepIndex
                        ?: handlerStepId?.let { id ->
                            script.steps.indexOfFirst { s -> s.id == id }
                        }
                    
                    if (targetIndex != null && targetIndex >= 0 && targetIndex < script.steps.size) {
                        Log.i(TAG, "SUBSCRIBE_EVENT: Jumping to handler step $targetIndex")
                        // Устанавливаем переменную-флаг для GOTO в основном цикле
                        variables["_event_goto_step"] = targetIndex.toString()
                    }
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

            // ===== ENTERPRISE v2.28.0 - Полная реализация Visual Editor =====
            
            // VAR_SET - Установить локальную переменную
            StepType.VAR_SET -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val value = resolveVariables(step.params["value"] ?: "")
                variables[name] = value
                Log.d(TAG, "VAR_SET: $name = $value")
                CommandResult(success = true, data = value)
            }
            
            // VAR_GET - Получить переменную
            StepType.VAR_GET -> {
                val name = step.params["name"] ?: throw IllegalArgumentException("name required")
                val default = step.params["default"] ?: ""
                val targetVar = step.params["variable"] ?: name
                val value = variables[name] ?: default
                variables[targetVar] = value
                Log.d(TAG, "VAR_GET: $name = $value -> $targetVar")
                CommandResult(success = true, data = value)
            }
            
            // MATH - Математические операции
            StepType.MATH -> {
                val operation = step.params["operation"] ?: "+"
                val a = resolveVariables(step.params["a"] ?: "0").toDoubleOrNull() ?: 0.0
                val b = resolveVariables(step.params["b"] ?: "0").toDoubleOrNull() ?: 0.0
                val variableName = step.params["variable"] ?: "_math_result"
                
                val result = when (operation) {
                    "+", "add" -> a + b
                    "-", "sub", "subtract" -> a - b
                    "*", "mul", "multiply" -> a * b
                    "/", "div", "divide" -> if (b != 0.0) a / b else 0.0
                    "%", "mod", "modulo" -> if (b != 0.0) a % b else 0.0
                    "pow", "power" -> Math.pow(a, b)
                    "min" -> minOf(a, b)
                    "max" -> maxOf(a, b)
                    "abs" -> kotlin.math.abs(a)
                    "round" -> kotlin.math.round(a)
                    "floor" -> kotlin.math.floor(a)
                    "ceil" -> kotlin.math.ceil(a)
                    else -> a + b
                }
                
                val resultStr = if (result == result.toLong().toDouble()) result.toLong().toString() else result.toString()
                variables[variableName] = resultStr
                Log.d(TAG, "MATH: $a $operation $b = $resultStr")
                CommandResult(success = true, data = resultStr)
            }
            
            // RANDOM - Случайное число
            StepType.RANDOM -> {
                val min = resolveVariables(step.params["min"] ?: "0").toLongOrNull() ?: 0L
                val max = resolveVariables(step.params["max"] ?: "100").toLongOrNull() ?: 100L
                val variableName = step.params["variable"] ?: "_random"
                
                val result = (min..max).random()
                variables[variableName] = result.toString()
                Log.d(TAG, "RANDOM: $min..$max -> $result")
                CommandResult(success = true, data = result.toString())
            }
            
            // COUNTER - Счётчик
            StepType.COUNTER -> {
                val name = step.params["name"] ?: "_counter"
                val operation = step.params["operation"] ?: "inc"
                val variableName = step.params["variable"] ?: name
                
                val current = variables[name]?.toLongOrNull() ?: 0L
                val newValue = when (operation) {
                    "inc", "increment", "++" -> current + 1
                    "dec", "decrement", "--" -> current - 1
                    "reset" -> 0L
                    else -> current + 1
                }
                
                variables[name] = newValue.toString()
                variables[variableName] = newValue.toString()
                Log.d(TAG, "COUNTER: $name $operation -> $newValue")
                CommandResult(success = true, data = newValue.toString())
            }
            
            // WHILE - Цикл while (обработка в executeScript, здесь заглушка)
            StepType.WHILE -> {
                Log.d(TAG, "WHILE: Handled in executeScript")
                CommandResult(success = true, data = "while_handled")
            }
            
            // LOOP_FOREVER - Бесконечный цикл
            StepType.LOOP_FOREVER -> {
                Log.d(TAG, "LOOP_FOREVER: Handled in executeScript")
                CommandResult(success = true, data = "loop_forever_handled")
            }
            
            // BREAK - Выход из цикла
            StepType.BREAK -> {
                Log.i(TAG, "BREAK: Exit loop")
                variables["_break"] = "true"
                CommandResult(success = true, data = "break")
            }
            
            // CONTINUE - Следующая итерация
            StepType.CONTINUE -> {
                Log.i(TAG, "CONTINUE: Next iteration")
                variables["_continue"] = "true"
                CommandResult(success = true, data = "continue")
            }
            
            // TRY_CATCH - Try-catch (обработка в executeScript)
            StepType.TRY_CATCH -> {
                Log.d(TAG, "TRY_CATCH: Handled in executeScript")
                CommandResult(success = true, data = "try_catch_handled")
            }
            
            // RESTART_SCRIPT - Перезапуск скрипта
            StepType.RESTART_SCRIPT -> {
                Log.i(TAG, "RESTART_SCRIPT: Restarting from beginning")
                variables["_restart"] = "true"
                CommandResult(success = true, data = "restart_requested")
            }
            
            // ASSERT - Проверка условия
            StepType.ASSERT -> {
                val condition = step.params["condition"] ?: "true"
                val message = step.params["message"] ?: "Assertion failed"
                
                val result = evaluateCondition(condition)
                if (result) {
                    Log.d(TAG, "ASSERT: '$condition' passed")
                    CommandResult(success = true, data = "assertion_passed")
                } else {
                    Log.e(TAG, "ASSERT: '$condition' FAILED - $message")
                    CommandResult(success = false, error = "Assertion failed: $message")
                }
            }
            
            // NOTIFY - Уведомление через Android Toast/Shell
            StepType.NOTIFY -> {
                val title = resolveVariables(step.params["title"] ?: "Script")
                val message = resolveVariables(step.params["message"] ?: "")
                val type = step.params["type"] ?: "info"
                
                Log.i(TAG, "NOTIFY: [$type] $title - $message")
                
                // v4.0.0: Уведомление через shell (работает без Context)
                // Используем am broadcast для показа Toast + запись в лог
                try {
                    val escapedTitle = title.replace("'", "\\'").replace("\"", "\\\"")
                    val escapedMsg = message.replace("'", "\\'").replace("\"", "\\\"")
                    // Toast через shell
                    commandExecutor.shell("am broadcast -a com.sphere.agent.NOTIFY --es title '$escapedTitle' --es message '$escapedMsg' --es type '$type'")
                } catch (e: Exception) {
                    Log.w(TAG, "NOTIFY: Shell notification failed: ${e.message}")
                }
                
                // Логируем в ScriptLogSender для отображения на backend
                ScriptLogSender.log(
                    executionId = executionId,
                    level = ScriptLogSender.LogLevel.INFO,
                    action = "NOTIFY",
                    message = "[$type] $title: $message"
                )
                CommandResult(success = true, data = "notified:$type")
            }
            
            // TIME_CHECK - Проверка времени
            StepType.TIME_CHECK -> {
                val hourStart = step.params["hour_start"]?.toIntOrNull() ?: 0
                val hourEnd = step.params["hour_end"]?.toIntOrNull() ?: 23
                val variableName = step.params["variable"] ?: "_time_in_range"
                
                val calendar = java.util.Calendar.getInstance()
                val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                val inRange = currentHour in hourStart..hourEnd
                
                variables[variableName] = inRange.toString()
                Log.d(TAG, "TIME_CHECK: $currentHour in $hourStart..$hourEnd = $inRange")
                CommandResult(success = true, data = inRange.toString())
            }
            
            // DATE_CHECK - Проверка даты
            StepType.DATE_CHECK -> {
                val variableName = step.params["variable"] ?: "_date_in_range"
                val dayOfWeek = step.params["day_of_week"]
                
                val calendar = java.util.Calendar.getInstance()
                val currentDay = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                
                val inRange = if (dayOfWeek != null) {
                    val expectedDays = dayOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }
                    currentDay in expectedDays
                } else {
                    true
                }
                
                variables[variableName] = inRange.toString()
                Log.d(TAG, "DATE_CHECK: day=$currentDay, inRange=$inRange")
                CommandResult(success = true, data = inRange.toString())
            }
            
            // WAIT_UNTIL_TIME - Ждать до времени
            StepType.WAIT_UNTIL_TIME -> {
                val hour = step.params["hour"]?.toIntOrNull() ?: 0
                val minute = step.params["minute"]?.toIntOrNull() ?: 0
                
                val calendar = java.util.Calendar.getInstance()
                val targetCal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0)
                    if (before(calendar)) {
                        add(java.util.Calendar.DAY_OF_MONTH, 1)
                    }
                }
                
                val waitMs = targetCal.timeInMillis - calendar.timeInMillis
                Log.i(TAG, "WAIT_UNTIL_TIME: $hour:$minute (waiting ${waitMs}ms)")
                
                if (waitMs > 0 && waitMs < 24 * 60 * 60 * 1000) { // Max 24 hours
                    delay(waitMs)
                }
                CommandResult(success = true, data = "waited_until:$hour:$minute")
            }
            
            // v4.0.0: SCHEDULE_* - Регистрация расписания через ScriptScheduler
            StepType.SCHEDULE_HOURLY -> {
                val scheduleId = ScriptScheduler.registerSchedule(
                    scriptId = script.id,
                    type = ScriptScheduler.ScheduleType.HOURLY,
                    params = step.params
                )
                variables["_schedule_id"] = scheduleId
                Log.i(TAG, "SCHEDULE_HOURLY: registered $scheduleId, minute=${step.params["minute"]}")
                CommandResult(success = true, data = scheduleId)
            }
            StepType.SCHEDULE_DAILY -> {
                val scheduleId = ScriptScheduler.registerSchedule(
                    scriptId = script.id,
                    type = ScriptScheduler.ScheduleType.DAILY,
                    params = step.params
                )
                variables["_schedule_id"] = scheduleId
                Log.i(TAG, "SCHEDULE_DAILY: registered $scheduleId, ${step.params["hour"]}:${step.params["minute"]}")
                CommandResult(success = true, data = scheduleId)
            }
            StepType.SCHEDULE_INTERVAL -> {
                val scheduleId = ScriptScheduler.registerSchedule(
                    scriptId = script.id,
                    type = ScriptScheduler.ScheduleType.INTERVAL,
                    params = step.params
                )
                variables["_schedule_id"] = scheduleId
                Log.i(TAG, "SCHEDULE_INTERVAL: registered $scheduleId, interval=${step.params["interval_ms"]}ms")
                CommandResult(success = true, data = scheduleId)
            }
            StepType.SCHEDULE_CRON -> {
                val scheduleId = ScriptScheduler.registerSchedule(
                    scriptId = script.id,
                    type = ScriptScheduler.ScheduleType.CRON,
                    params = step.params
                )
                variables["_schedule_id"] = scheduleId
                Log.i(TAG, "SCHEDULE_CRON: registered $scheduleId, cron=${step.params["cron_expression"]}")
                CommandResult(success = true, data = scheduleId)
            }
            StepType.SCHEDULE_POINTS -> {
                val scheduleId = ScriptScheduler.registerSchedule(
                    scriptId = script.id,
                    type = ScriptScheduler.ScheduleType.POINTS,
                    params = step.params
                )
                variables["_schedule_id"] = scheduleId
                Log.i(TAG, "SCHEDULE_POINTS: registered $scheduleId, times=${step.params["times"]}")
                CommandResult(success = true, data = scheduleId)
            }
            
            // v4.0.0: WAIT_SCREEN_STABLE - Ждать стабильности экрана через screencap hash
            StepType.WAIT_SCREEN_STABLE -> {
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                val threshold = step.params["threshold"]?.toFloatOrNull() ?: 0.95f
                val checkInterval = step.params["check_interval"]?.toLongOrNull() ?: 500L
                val requiredStableChecks = step.params["stable_checks"]?.toIntOrNull() ?: 3
                
                Log.i(TAG, "WAIT_SCREEN_STABLE: timeout=${timeout}ms, threshold=$threshold, interval=${checkInterval}ms")
                
                var stable = false
                var lastHash: String? = null
                var stableCount = 0
                val startTime = System.currentTimeMillis()
                val tmpPath = "/data/local/tmp/screen_stable_check.raw"
                
                while (System.currentTimeMillis() - startTime < timeout && !stable) {
                    delay(checkInterval)
                    
                    // Делаем screencap и считаем hash
                    val captureResult = commandExecutor.shell("screencap $tmpPath && md5sum $tmpPath")
                    if (captureResult.success) {
                        val currentHash = captureResult.data?.trim()?.split(" ")?.firstOrNull() ?: ""
                        
                        if (currentHash == lastHash && currentHash.isNotEmpty()) {
                            stableCount++
                            if (stableCount >= requiredStableChecks) {
                                stable = true
                            }
                        } else {
                            stableCount = 0
                        }
                        lastHash = currentHash
                    }
                }
                
                // Очистка временного файла
                commandExecutor.shell("rm -f $tmpPath")
                
                variables["_screen_stable"] = stable.toString()
                if (stable) {
                    Log.i(TAG, "WAIT_SCREEN_STABLE: Screen is stable after ${System.currentTimeMillis() - startTime}ms")
                    CommandResult(success = true, data = "stable")
                } else {
                    Log.w(TAG, "WAIT_SCREEN_STABLE: Timeout after ${timeout}ms")
                    CommandResult(success = false, error = "Screen not stable after ${timeout}ms")
                }
            }
            
            // OCR_WAIT - Ждать текст (OCR)
            StepType.OCR_WAIT -> {
                val text = resolveVariables(step.params["text"] ?: "")
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                
                Log.i(TAG, "OCR_WAIT: '$text' (timeout=${timeout}ms)")
                // Fallback to XPath text search
                val element = xpathHelper.waitForElement("text", text, timeout)
                if (element.found) {
                    variables["_ocr_found"] = "true"
                    CommandResult(success = true, data = "found")
                } else {
                    variables["_ocr_found"] = "false"
                    CommandResult(success = false, error = "Text not found: $text")
                }
            }
            
            // OCR_TAP - Тап по тексту (OCR)
            StepType.OCR_TAP -> {
                val text = resolveVariables(step.params["text"] ?: "")
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                
                Log.i(TAG, "OCR_TAP: '$text' (timeout=${timeout}ms)")
                xpathHelper.tapElement("text", text, timeout)
            }
            
            // TEMPLATE_* - Поиск по изображению (заглушки)
            StepType.TEMPLATE_WAIT, StepType.TEMPLATE_TAP, StepType.TEMPLATE_EXISTS -> {
                val templateId = step.params["template_id"] ?: step.params["template"] ?: ""
                val variableName = step.params["variable"] ?: "_template_found"
                
                Log.w(TAG, "${step.type}: Template matching not implemented, template=$templateId")
                variables[variableName] = "false"
                // TODO: Implement template matching with OpenCV
                CommandResult(success = true, data = "template_not_implemented")
            }
            
            // v4.0.0: PIXEL_CHECK - Проверить цвет пикселя через screencap
            StepType.PIXEL_CHECK -> {
                val x = step.params["x"]?.toIntOrNull() ?: 0
                val y = step.params["y"]?.toIntOrNull() ?: 0
                val expectedColor = step.params["expected_color"] ?: step.params["color"] ?: ""
                val tolerance = step.params["tolerance"]?.toIntOrNull() ?: 10
                val variableName = step.params["variable"] ?: "_pixel_match"
                
                Log.d(TAG, "PIXEL_CHECK: ($x, $y) expected=$expectedColor tolerance=$tolerance")
                
                val pixelResult = readPixelColor(x, y)
                if (pixelResult != null) {
                    val match = if (expectedColor.isNotEmpty()) {
                        colorsMatch(pixelResult, expectedColor, tolerance)
                    } else true
                    
                    variables[variableName] = match.toString()
                    variables["_pixel_color"] = pixelResult
                    Log.i(TAG, "PIXEL_CHECK: ($x,$y) actual=$pixelResult expected=$expectedColor match=$match")
                    CommandResult(success = true, data = if (match) "match" else "no_match")
                } else {
                    variables[variableName] = "false"
                    Log.e(TAG, "PIXEL_CHECK: Failed to read pixel at ($x,$y)")
                    CommandResult(success = false, error = "Failed to read pixel")
                }
            }
            
            // v4.0.0: PIXEL_WAIT - Ждать цвет пикселя через polling
            StepType.PIXEL_WAIT -> {
                val x = step.params["x"]?.toIntOrNull() ?: 0
                val y = step.params["y"]?.toIntOrNull() ?: 0
                val expectedColor = step.params["expected_color"] ?: step.params["color"] ?: ""
                val timeout = step.params["timeout"]?.toLongOrNull() ?: 10000L
                val tolerance = step.params["tolerance"]?.toIntOrNull() ?: 10
                val pollInterval = step.params["poll_interval"]?.toLongOrNull() ?: 500L
                
                Log.i(TAG, "PIXEL_WAIT: ($x,$y) expected=$expectedColor timeout=${timeout}ms")
                
                var found = false
                val startTime = System.currentTimeMillis()
                
                while (System.currentTimeMillis() - startTime < timeout && !found) {
                    val pixelColor = readPixelColor(x, y)
                    if (pixelColor != null && colorsMatch(pixelColor, expectedColor, tolerance)) {
                        found = true
                        variables["_pixel_color"] = pixelColor
                    } else {
                        delay(pollInterval)
                    }
                }
                
                variables["_pixel_match"] = found.toString()
                if (found) {
                    Log.i(TAG, "PIXEL_WAIT: Found matching color at ($x,$y) after ${System.currentTimeMillis() - startTime}ms")
                    CommandResult(success = true, data = "found")
                } else {
                    Log.w(TAG, "PIXEL_WAIT: Timeout waiting for color at ($x,$y)")
                    CommandResult(success = false, error = "Pixel color not matched after ${timeout}ms")
                }
            }
            
            // v4.0.0: PIXEL_GROUP - Проверить группу пикселей
            StepType.PIXEL_GROUP -> {
                val variableName = step.params["variable"] ?: "_pixel_group_match"
                val tolerance = step.params["tolerance"]?.toIntOrNull() ?: 10
                val pixelsJson = step.params["pixels"] ?: "[]"
                
                Log.d(TAG, "PIXEL_GROUP: checking pixel group")
                
                // Формат pixels: [{"x":100,"y":200,"color":"#FF0000"}, ...]
                try {
                    val pixelsArr = org.json.JSONArray(pixelsJson)
                    var allMatch = true
                    var matchCount = 0
                    
                    for (i in 0 until pixelsArr.length()) {
                        val pixel = pixelsArr.getJSONObject(i)
                        val px = pixel.getInt("x")
                        val py = pixel.getInt("y")
                        val expectedColor = pixel.getString("color")
                        
                        val actualColor = readPixelColor(px, py)
                        if (actualColor != null && colorsMatch(actualColor, expectedColor, tolerance)) {
                            matchCount++
                        } else {
                            allMatch = false
                        }
                    }
                    
                    variables[variableName] = allMatch.toString()
                    variables["_pixel_group_matched"] = matchCount.toString()
                    variables["_pixel_group_total"] = pixelsArr.length().toString()
                    
                    Log.i(TAG, "PIXEL_GROUP: $matchCount/${pixelsArr.length()} matched, allMatch=$allMatch")
                    CommandResult(success = true, data = if (allMatch) "all_match" else "partial_match:$matchCount/${pixelsArr.length()}")
                } catch (e: Exception) {
                    Log.e(TAG, "PIXEL_GROUP: Failed to parse pixels: ${e.message}")
                    variables[variableName] = "false"
                    CommandResult(success = false, error = "Invalid pixels format: ${e.message}")
                }
            }
            
            // PINCH - Pinch жест
            StepType.PINCH -> {
                val x = step.params["x"]?.toIntOrNull() ?: 540
                val y = step.params["y"]?.toIntOrNull() ?: 960
                val scale = step.params["scale"]?.toFloatOrNull() ?: 1.0f
                
                Log.i(TAG, "PINCH: ($x, $y) scale=$scale")
                
                // Simulate pinch with two-finger swipe
                val distance = if (scale > 1.0f) 200 else -200 // Zoom in vs zoom out
                val duration = 300
                
                // TODO: Implement proper multi-touch pinch
                // For now, use shell gesture
                val cmd = if (scale > 1.0f) {
                    "input swipe ${x-100} $y ${x-200} $y $duration & input swipe ${x+100} $y ${x+200} $y $duration"
                } else {
                    "input swipe ${x-200} $y ${x-100} $y $duration & input swipe ${x+200} $y ${x+100} $y $duration"
                }
                commandExecutor.shell(cmd)
            }
            
            // CLIPBOARD_SET - Установить буфер обмена
            StepType.CLIPBOARD_SET -> {
                val text = resolveVariables(step.params["text"] ?: "")
                Log.i(TAG, "CLIPBOARD_SET: '${text.take(50)}...'")
                
                // Use shell to set clipboard via am broadcast
                val escapedText = text.replace("'", "\\'")
                commandExecutor.shell("am broadcast -a clipper.set -e text '$escapedText'")
            }
            
            // CLIPBOARD_GET - Получить из буфера обмена
            StepType.CLIPBOARD_GET -> {
                val variableName = step.params["variable"] ?: "_clipboard"
                Log.i(TAG, "CLIPBOARD_GET: -> $variableName")
                
                // Use shell to get clipboard
                val result = commandExecutor.shell("am broadcast -a clipper.get")
                val clipboardText = result.data ?: ""
                variables[variableName] = clipboardText
                CommandResult(success = true, data = clipboardText)
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
    
    /**
     * v4.0.0: Чтение цвета пикселя через screencap + shell
     * Делает screencap в raw формат, читает пиксель по координатам
     * Возвращает цвет в формате "#RRGGBB" или null при ошибке
     */
    private suspend fun readPixelColor(x: Int, y: Int): String? {
        try {
            // Используем screencap -p (PNG) и конвертируем через shell
            val tmpPath = "/data/local/tmp/pixel_check_${runId}.png"
            val captureResult = commandExecutor.shell("screencap -p $tmpPath")
            if (!captureResult.success) return null
            
            // Читаем размеры изображения через identify или header
            // Используем dd + hexdump для чтения конкретного пикселя из PNG
            // Альтернативный подход: screencap в raw формат
            val rawPath = "/data/local/tmp/pixel_check_${runId}.raw"
            val rawResult = commandExecutor.shell("screencap $rawPath")
            if (!rawResult.success) {
                commandExecutor.shell("rm -f $tmpPath")
                return null
            }
            
            // Raw формат screencap: первые 12 байт — header (width:4, height:4, format:4)
            // Затем RGBA пиксели (4 байта на пиксель)
            // Читаем ширину из header
            val widthResult = commandExecutor.shell("dd if=$rawPath bs=1 count=4 2>/dev/null | od -A n -t u4 | tr -d ' '")
            val width = widthResult.data?.trim()?.toIntOrNull() ?: 1080
            
            // Смещение пикселя: 12 (header) + (y * width + x) * 4
            val offset = 12 + (y * width + x) * 4
            
            // Читаем 4 байта (RGBA) по смещению
            val pixelHex = commandExecutor.shell(
                "dd if=$rawPath bs=1 skip=$offset count=4 2>/dev/null | od -A n -t x1 | tr -d ' \\n'"
            )
            
            // Очистка
            commandExecutor.shell("rm -f $tmpPath $rawPath")
            
            val hex = pixelHex.data?.trim() ?: return null
            if (hex.length >= 6) {
                val r = hex.substring(0, 2)
                val g = hex.substring(2, 4)
                val b = hex.substring(4, 6)
                return "#${r}${g}${b}".uppercase()
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "readPixelColor($x, $y) failed: ${e.message}")
            return null
        }
    }
    
    /**
     * v4.0.0: Сравнение двух цветов с tolerance
     * Цвета в формате "#RRGGBB"
     * tolerance: максимальная разница по каждому каналу (0-255)
     */
    private fun colorsMatch(actual: String, expected: String, tolerance: Int = 10): Boolean {
        try {
            val a = actual.removePrefix("#")
            val e = expected.removePrefix("#")
            
            if (a.length < 6 || e.length < 6) return false
            
            val aR = a.substring(0, 2).toInt(16)
            val aG = a.substring(2, 4).toInt(16)
            val aB = a.substring(4, 6).toInt(16)
            
            val eR = e.substring(0, 2).toInt(16)
            val eG = e.substring(2, 4).toInt(16)
            val eB = e.substring(4, 6).toInt(16)
            
            return Math.abs(aR - eR) <= tolerance &&
                   Math.abs(aG - eG) <= tolerance &&
                   Math.abs(aB - eB) <= tolerance
        } catch (ex: Exception) {
            Log.w(TAG, "colorsMatch parse error: ${ex.message}")
            return false
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
    
    /**
     * v3.7.0: Расширенная evaluateCondition
     * Поддерживает: ==, !=, >, <, >=, <=, contains, startsWith, endsWith
     * Логические операторы: AND, OR (через split)
     * Литералы: "true", "false"
     * Числовые сравнения через toDouble
     */
    private fun evaluateCondition(condition: String): Boolean {
        val trimmed = condition.trim()
        
        // Литералы
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false
        
        // v3.7.0: OR — если хотя бы одно условие true
        if (trimmed.contains(" OR ")) {
            return trimmed.split(" OR ").any { evaluateCondition(it.trim()) }
        }
        
        // v3.7.0: AND — все условия должны быть true
        if (trimmed.contains(" AND ")) {
            return trimmed.split(" AND ").all { evaluateCondition(it.trim()) }
        }
        
        // Простое условие: "variable op value"
        val parts = trimmed.split(" ", limit = 3)
        if (parts.size >= 3) {
            val varName = parts[0]
            val op = parts[1]
            val value = resolveVariables(parts[2])
            
            val varValue = resolveVariables(variables[varName] ?: "")
            
            return when (op) {
                "==" -> varValue == value
                "!=" -> varValue != value
                ">" -> (varValue.toDoubleOrNull() ?: 0.0) > (value.toDoubleOrNull() ?: 0.0)
                "<" -> (varValue.toDoubleOrNull() ?: 0.0) < (value.toDoubleOrNull() ?: 0.0)
                ">=" -> (varValue.toDoubleOrNull() ?: 0.0) >= (value.toDoubleOrNull() ?: 0.0)
                "<=" -> (varValue.toDoubleOrNull() ?: 0.0) <= (value.toDoubleOrNull() ?: 0.0)
                "contains" -> varValue.contains(value, ignoreCase = true)
                "startsWith" -> varValue.startsWith(value, ignoreCase = true)
                "endsWith" -> varValue.endsWith(value, ignoreCase = true)
                else -> {
                    Log.w(TAG, "Unknown operator '$op' in condition: $condition")
                    true
                }
            }
        }
        
        // Если это имя переменной — проверяем что она "true"
        if (parts.size == 1) {
            val varValue = variables[trimmed] ?: trimmed
            return varValue.equals("true", ignoreCase = true)
        }
        
        return true
    }
    
    fun stop() {
        isStopped.set(true)
        job?.cancel()
    }
    
    fun pause() {
        isPaused.set(true)
        updateStatus(ScriptState.PAUSED, -1, "Paused")
    }
    
    fun resume() {
        isPaused.set(false)
        updateStatus(ScriptState.RUNNING, -1, "Resumed")
    }
    
    private fun updateStatus(state: ScriptState, stepIndex: Int, stepName: String, error: String? = null) {
        val currentStep = if (stepIndex >= 0) stepIndex else (_status.value?.currentStep ?: 0)
        val progress = if (script.steps.isNotEmpty()) currentStep.toFloat() / script.steps.size else 0f
        
        val status = ScriptStatus(
            runId = runId,
            executionId = executionId,
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
