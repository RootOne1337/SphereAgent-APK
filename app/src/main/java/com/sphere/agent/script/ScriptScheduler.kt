package com.sphere.agent.script

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.0.0: ScriptScheduler — планировщик запуска скриптов по расписанию
 *
 * Поддерживаемые типы расписаний:
 * - SCHEDULE_HOURLY: запуск каждый час в указанную минуту
 * - SCHEDULE_DAILY: запуск ежедневно в указанное время
 * - SCHEDULE_INTERVAL: запуск по интервалу (мс)
 * - SCHEDULE_CRON: cron-подобное выражение (упрощённое)
 * - SCHEDULE_POINTS: запуск в конкретные моменты времени
 *
 * Хранение расписаний в SharedPreferences для переживания перезапусков.
 * Использует coroutine-based polling вместо AlarmManager для совместимости с эмуляторами.
 */
object ScriptScheduler {
    private const val TAG = "ScriptScheduler"
    private const val PREFS_NAME = "script_schedules"
    private const val KEY_SCHEDULES = "schedules_json"
    
    // Минимальный интервал проверки расписаний (30 секунд)
    private const val CHECK_INTERVAL_MS = 30_000L
    
    // Минимальный интервал между запусками одного расписания (60 секунд)
    private const val MIN_SCHEDULE_INTERVAL_MS = 60_000L
    
    private var prefs: SharedPreferences? = null
    private var scriptEngine: ScriptEngine? = null
    private var scope: CoroutineScope? = null
    private var checkJob: Job? = null
    
    // Активные расписания: scheduleId -> ScheduleEntry
    private val schedules = ConcurrentHashMap<String, ScheduleEntry>()
    
    // Время последнего запуска каждого расписания
    private val lastExecutionTime = ConcurrentHashMap<String, Long>()
    
    /**
     * Запись расписания
     */
    data class ScheduleEntry(
        val id: String,
        val scriptId: String,
        val type: ScheduleType,
        val minute: Int = 0,           // Для HOURLY
        val hour: Int = 0,             // Для DAILY
        val intervalMs: Long = 0,      // Для INTERVAL
        val cronExpression: String = "",// Для CRON
        val timePoints: List<String> = emptyList(), // Для POINTS ("HH:mm")
        val variables: Map<String, String> = emptyMap(),
        val enabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis()
    )
    
    enum class ScheduleType {
        HOURLY, DAILY, INTERVAL, CRON, POINTS
    }
    
    /**
     * Инициализация планировщика
     */
    fun init(context: Context, engine: ScriptEngine) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        scriptEngine = engine
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        
        // Загружаем сохранённые расписания
        loadSchedules()
        
        // Запускаем проверку расписаний
        startCheckLoop()
        
        Log.i(TAG, "ScriptScheduler initialized, ${schedules.size} schedules loaded")
    }
    
    /**
     * Остановка планировщика
     */
    fun shutdown() {
        checkJob?.cancel()
        scope?.cancel()
        Log.i(TAG, "ScriptScheduler shutdown")
    }
    
    /**
     * Регистрация расписания из шага скрипта
     */
    fun registerSchedule(
        scriptId: String,
        type: ScheduleType,
        params: Map<String, String>
    ): String {
        val id = "sched_${UUID.randomUUID().toString().take(8)}"
        
        val entry = when (type) {
            ScheduleType.HOURLY -> ScheduleEntry(
                id = id,
                scriptId = scriptId,
                type = type,
                minute = params["minute"]?.toIntOrNull() ?: 0
            )
            ScheduleType.DAILY -> ScheduleEntry(
                id = id,
                scriptId = scriptId,
                type = type,
                hour = params["hour"]?.toIntOrNull() ?: 0,
                minute = params["minute"]?.toIntOrNull() ?: 0
            )
            ScheduleType.INTERVAL -> ScheduleEntry(
                id = id,
                scriptId = scriptId,
                type = type,
                intervalMs = params["interval_ms"]?.toLongOrNull()
                    ?: params["interval"]?.toLongOrNull()
                    ?: 3600000L // По умолчанию 1 час
            )
            ScheduleType.CRON -> ScheduleEntry(
                id = id,
                scriptId = scriptId,
                type = type,
                cronExpression = params["cron_expression"] ?: params["cron"] ?: "0 * * * *"
            )
            ScheduleType.POINTS -> {
                val timesStr = params["times"] ?: params["time_points"] ?: "[]"
                val times = try {
                    val arr = JSONArray(timesStr)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (e: Exception) {
                    timesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
                ScheduleEntry(
                    id = id,
                    scriptId = scriptId,
                    type = type,
                    timePoints = times
                )
            }
        }
        
        schedules[id] = entry
        saveSchedules()
        
        Log.i(TAG, "Schedule registered: $id type=${type.name} script=$scriptId")
        return id
    }
    
    /**
     * Удаление расписания
     */
    fun removeSchedule(scheduleId: String): Boolean {
        val removed = schedules.remove(scheduleId) != null
        if (removed) {
            lastExecutionTime.remove(scheduleId)
            saveSchedules()
            Log.i(TAG, "Schedule removed: $scheduleId")
        }
        return removed
    }
    
    /**
     * Удаление всех расписаний для скрипта
     */
    fun removeSchedulesForScript(scriptId: String) {
        val toRemove = schedules.filter { it.value.scriptId == scriptId }.keys
        toRemove.forEach { schedules.remove(it) }
        saveSchedules()
        Log.i(TAG, "Removed ${toRemove.size} schedules for script $scriptId")
    }
    
    /**
     * Получение всех расписаний
     */
    fun getSchedules(): List<ScheduleEntry> = schedules.values.toList()
    
    /**
     * Получение расписаний для скрипта
     */
    fun getSchedulesForScript(scriptId: String): List<ScheduleEntry> =
        schedules.values.filter { it.scriptId == scriptId }
    
    // ===================== Проверка и запуск =====================
    
    private fun startCheckLoop() {
        checkJob?.cancel()
        checkJob = scope?.launch {
            Log.i(TAG, "Schedule check loop started")
            while (isActive) {
                try {
                    checkSchedules()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking schedules: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }
    
    private suspend fun checkSchedules() {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        
        for ((id, entry) in schedules) {
            if (!entry.enabled) continue
            
            // Проверяем минимальный интервал между запусками
            val lastExec = lastExecutionTime[id] ?: 0L
            if (now - lastExec < MIN_SCHEDULE_INTERVAL_MS) continue
            
            val shouldRun = when (entry.type) {
                ScheduleType.HOURLY -> {
                    calendar.get(Calendar.MINUTE) == entry.minute &&
                    calendar.get(Calendar.SECOND) < (CHECK_INTERVAL_MS / 1000).toInt()
                }
                ScheduleType.DAILY -> {
                    calendar.get(Calendar.HOUR_OF_DAY) == entry.hour &&
                    calendar.get(Calendar.MINUTE) == entry.minute &&
                    calendar.get(Calendar.SECOND) < (CHECK_INTERVAL_MS / 1000).toInt()
                }
                ScheduleType.INTERVAL -> {
                    val interval = entry.intervalMs.coerceAtLeast(MIN_SCHEDULE_INTERVAL_MS)
                    now - lastExec >= interval
                }
                ScheduleType.CRON -> {
                    matchesCron(entry.cronExpression, calendar)
                }
                ScheduleType.POINTS -> {
                    val currentTime = String.format("%02d:%02d", 
                        calendar.get(Calendar.HOUR_OF_DAY), 
                        calendar.get(Calendar.MINUTE))
                    entry.timePoints.contains(currentTime) &&
                    calendar.get(Calendar.SECOND) < (CHECK_INTERVAL_MS / 1000).toInt()
                }
            }
            
            if (shouldRun) {
                lastExecutionTime[id] = now
                launchScript(entry)
            }
        }
    }
    
    /**
     * Упрощённый cron matcher: "minute hour dayOfMonth month dayOfWeek"
     * Поддерживает: *, конкретные значения, списки через запятую
     * Пример: "0 * * * *" — каждый час в 0 минут
     * Пример: "30 8,20 * * *" — в 8:30 и 20:30
     * Пример: "0 9 * * 1,2,3,4,5" — в 9:00 по будням
     */
    private fun matchesCron(expression: String, calendar: Calendar): Boolean {
        val parts = expression.trim().split("\\s+".toRegex())
        if (parts.size < 5) return false
        
        val minute = calendar.get(Calendar.MINUTE)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday
        
        return matchesCronField(parts[0], minute) &&
               matchesCronField(parts[1], hour) &&
               matchesCronField(parts[2], dayOfMonth) &&
               matchesCronField(parts[3], month) &&
               matchesCronField(parts[4], dayOfWeek) &&
               calendar.get(Calendar.SECOND) < (CHECK_INTERVAL_MS / 1000).toInt()
    }
    
    private fun matchesCronField(field: String, value: Int): Boolean {
        if (field == "*") return true
        
        // Поддержка списков: "1,5,10"
        return field.split(",").any { part ->
            val trimmed = part.trim()
            // Поддержка диапазонов: "1-5"
            if (trimmed.contains("-")) {
                val range = trimmed.split("-")
                val start = range[0].toIntOrNull() ?: return@any false
                val end = range[1].toIntOrNull() ?: return@any false
                value in start..end
            }
            // Поддержка шагов: "*/5"
            else if (trimmed.startsWith("*/")) {
                val step = trimmed.removePrefix("*/").toIntOrNull() ?: return@any false
                step > 0 && value % step == 0
            }
            else {
                trimmed.toIntOrNull() == value
            }
        }
    }
    
    private fun launchScript(entry: ScheduleEntry) {
        val engine = scriptEngine ?: run {
            Log.w(TAG, "ScriptEngine not available, cannot launch scheduled script")
            return
        }
        
        Log.i(TAG, "Launching scheduled script: ${entry.scriptId} (schedule=${entry.id}, type=${entry.type})")
        
        scope?.launch {
            try {
                // Запрашиваем скрипт у backend через ScriptEngine
                // ScriptEngine.startScheduledScript загружает скрипт из кеша или запрашивает у сервера
                engine.startScheduledScript(entry.scriptId, entry.variables)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch scheduled script ${entry.scriptId}: ${e.message}")
            }
        }
    }
    
    // ===================== Persistence =====================
    
    private fun saveSchedules() {
        try {
            val arr = JSONArray()
            for (entry in schedules.values) {
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("scriptId", entry.scriptId)
                    put("type", entry.type.name)
                    put("minute", entry.minute)
                    put("hour", entry.hour)
                    put("intervalMs", entry.intervalMs)
                    put("cronExpression", entry.cronExpression)
                    put("timePoints", JSONArray(entry.timePoints))
                    put("enabled", entry.enabled)
                    put("createdAt", entry.createdAt)
                    
                    val varsObj = JSONObject()
                    entry.variables.forEach { (k, v) -> varsObj.put(k, v) }
                    put("variables", varsObj)
                }
                arr.put(obj)
            }
            prefs?.edit()?.putString(KEY_SCHEDULES, arr.toString())?.apply()
            Log.d(TAG, "Saved ${schedules.size} schedules")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save schedules: ${e.message}")
        }
    }
    
    private fun loadSchedules() {
        try {
            val json = prefs?.getString(KEY_SCHEDULES, "[]") ?: "[]"
            val arr = JSONArray(json)
            
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = try { ScheduleType.valueOf(obj.getString("type")) } catch (e: Exception) { continue }
                
                val timePointsArr = obj.optJSONArray("timePoints")
                val timePoints = if (timePointsArr != null) {
                    (0 until timePointsArr.length()).map { timePointsArr.getString(it) }
                } else emptyList()
                
                val varsObj = obj.optJSONObject("variables")
                val variables = if (varsObj != null) {
                    val map = mutableMapOf<String, String>()
                    varsObj.keys().forEach { key -> map[key] = varsObj.getString(key) }
                    map
                } else emptyMap()
                
                val entry = ScheduleEntry(
                    id = obj.getString("id"),
                    scriptId = obj.getString("scriptId"),
                    type = type,
                    minute = obj.optInt("minute", 0),
                    hour = obj.optInt("hour", 0),
                    intervalMs = obj.optLong("intervalMs", 0),
                    cronExpression = obj.optString("cronExpression", ""),
                    timePoints = timePoints,
                    variables = variables,
                    enabled = obj.optBoolean("enabled", true),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                
                schedules[entry.id] = entry
            }
            
            Log.i(TAG, "Loaded ${schedules.size} schedules from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load schedules: ${e.message}")
        }
    }
}
