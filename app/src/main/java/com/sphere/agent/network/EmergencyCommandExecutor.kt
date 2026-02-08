/**
 * EmergencyCommandExecutor - Выполнение экстренных команд из Remote Config
 * 
 * РЕЗЕРВНЫЙ КАНАЛ СВЯЗИ #1: GitHub/CDN Remote Config
 * 
 * Когда WebSocket недоступен, APK периодически проверяет agent-config.json
 * на наличие экстренных команд и выполняет их.
 * 
 * Поддерживаемые команды:
 * - force_server_url: Принудительная смена сервера
 * - force_update: Принудительное обновление APK
 * - force_reconnect: Переподключение к серверу
 * - restart_agent: Перезапуск агента
 * - clear_cache: Очистка кэша
 * - execute_script: Выполнение скрипта по ID
 * - broadcast_message: Показать сообщение пользователю
 */
package com.sphere.agent.network

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Экстренная команда
 */
data class EmergencyCommand(
    val id: String,
    val action: String,
    val params: Map<String, Any> = emptyMap(),
    val targetAgents: List<String> = emptyList(), // Пустой = все агенты
    val priority: Int = 0,
    val expiresAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Статус выполнения команды
 */
enum class CommandStatus {
    PENDING,
    EXECUTING,
    COMPLETED,
    FAILED,
    EXPIRED,
    SKIPPED
}

@Singleton
class EmergencyCommandExecutor @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "EmergencyCmd"
        
        // Множественные источники конфига (отказоустойчивость)
        private val CONFIG_SOURCES = listOf(
            // GitHub Raw (основной)
            "https://raw.githubusercontent.com/RootOne1337/sphere-config/main/agent-config.json",
            // jsDelivr CDN (быстрый, кэширует)
            "https://cdn.jsdelivr.net/gh/RootOne1337/sphere-config@main/agent-config.json",
            // Statically CDN (резерв)
            "https://cdn.statically.io/gh/RootOne1337/sphere-config/main/agent-config.json",
            // GitHack CDN (еще один резерв)
            "https://rawcdn.githack.com/RootOne1337/sphere-config/main/agent-config.json"
        )
        
        // Интервал проверки когда WS работает (редко)
        private const val CHECK_INTERVAL_NORMAL_MS = 30 * 60 * 1000L // 30 минут
        
        // Интервал проверки когда WS НЕ работает (часто)
        private const val CHECK_INTERVAL_EMERGENCY_MS = 60 * 1000L // 1 минута
        
        // SharedPrefs
        private const val PREFS_NAME = "emergency_commands"
        private const val KEY_EXECUTED_COMMANDS = "executed_command_ids"
        private const val KEY_LAST_CHECK = "last_check_time"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private var isWebSocketConnected = false
    private var checkJob: Job? = null
    
    // Callbacks для выполнения команд (устанавливаются AgentService)
    private var onForceReconnect: (() -> Unit)? = null
    private var onForceUpdate: ((String) -> Unit)? = null
    private var onExecuteScript: ((String) -> Unit)? = null
    private var onRestartAgent: (() -> Unit)? = null
    
    /**
     * Запускает мониторинг экстренных команд
     */
    fun start() {
        Log.i(TAG, "🚨 Emergency Command Executor started")
        startPeriodicCheck()
    }
    
    /**
     * Останавливает мониторинг
     */
    fun stop() {
        checkJob?.cancel()
        scope.cancel()
    }
    
    /**
     * Устанавливает статус WebSocket соединения
     * Влияет на частоту проверки команд
     */
    fun setWebSocketConnected(connected: Boolean) {
        val wasConnected = isWebSocketConnected
        isWebSocketConnected = connected
        
        // Если соединение упало - проверяем сразу и часто
        if (wasConnected && !connected) {
            Log.w(TAG, "⚠️ WebSocket disconnected! Switching to emergency mode")
            scope.launch {
                checkEmergencyCommands()
            }
            startPeriodicCheck() // Перезапускаем с новым интервалом
        }
    }
    
    /**
     * Устанавливает callbacks для выполнения команд
     */
    fun setCallbacks(
        onForceReconnect: () -> Unit,
        onForceUpdate: (String) -> Unit,
        onExecuteScript: (String) -> Unit,
        onRestartAgent: () -> Unit
    ) {
        this.onForceReconnect = onForceReconnect
        this.onForceUpdate = onForceUpdate
        this.onExecuteScript = onExecuteScript
        this.onRestartAgent = onRestartAgent
    }
    
    /**
     * Запускает периодическую проверку
     */
    private fun startPeriodicCheck() {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                val interval = if (isWebSocketConnected) {
                    CHECK_INTERVAL_NORMAL_MS
                } else {
                    CHECK_INTERVAL_EMERGENCY_MS
                }
                
                delay(interval)
                checkEmergencyCommands()
            }
        }
    }
    
    /**
     * Проверяет и выполняет экстренные команды из всех источников
     */
    suspend fun checkEmergencyCommands(): List<EmergencyCommand> {
        Log.d(TAG, "📡 Checking emergency commands...")
        
        val config = fetchConfigFromAnyCDN() ?: return emptyList()
        
        val commands = parseEmergencyCommands(config)
        if (commands.isEmpty()) {
            Log.d(TAG, "No emergency commands")
            return emptyList()
        }
        
        val executedIds = getExecutedCommandIds()
        val newCommands = commands.filter { cmd ->
            // Фильтруем: не выполненные, не просроченные, для нашего агента
            !executedIds.contains(cmd.id) &&
            (cmd.expiresAt == 0L || cmd.expiresAt > System.currentTimeMillis()) &&
            (cmd.targetAgents.isEmpty() || cmd.targetAgents.contains(getAgentId()))
        }
        
        if (newCommands.isEmpty()) {
            Log.d(TAG, "No new commands to execute")
            return emptyList()
        }
        
        Log.i(TAG, "🚨 Found ${newCommands.size} new emergency commands!")
        
        // Выполняем команды по приоритету
        newCommands.sortedByDescending { it.priority }.forEach { cmd ->
            executeCommand(cmd)
        }
        
        return newCommands
    }
    
    /**
     * Получает конфиг из любого доступного CDN
     */
    private suspend fun fetchConfigFromAnyCDN(): JSONObject? {
        for (url in CONFIG_SOURCES) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        Log.d(TAG, "✅ Config fetched from: $url")
                        return JSONObject(body)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch from $url: ${e.message}")
            }
        }
        
        Log.e(TAG, "❌ All config sources failed!")
        return null
    }
    
    /**
     * Парсит экстренные команды из конфига
     */
    private fun parseEmergencyCommands(config: JSONObject): List<EmergencyCommand> {
        val commands = mutableListOf<EmergencyCommand>()
        
        try {
            val emergencyBlock = config.optJSONObject("emergency") ?: return emptyList()
            val commandsArray = emergencyBlock.optJSONArray("commands") ?: return emptyList()
            
            for (i in 0 until commandsArray.length()) {
                val cmdJson = commandsArray.getJSONObject(i)
                
                val params = mutableMapOf<String, Any>()
                cmdJson.optJSONObject("params")?.let { paramsJson ->
                    paramsJson.keys().forEach { key ->
                        params[key] = paramsJson.get(key)
                    }
                }
                
                val targetAgents = mutableListOf<String>()
                cmdJson.optJSONArray("target_agents")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        targetAgents.add(arr.getString(j))
                    }
                }
                
                commands.add(EmergencyCommand(
                    id = cmdJson.getString("id"),
                    action = cmdJson.getString("action"),
                    params = params,
                    targetAgents = targetAgents,
                    priority = cmdJson.optInt("priority", 0),
                    expiresAt = cmdJson.optLong("expires_at", 0),
                    createdAt = cmdJson.optLong("created_at", System.currentTimeMillis())
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing commands: ${e.message}")
        }
        
        return commands
    }
    
    /**
     * Выполняет экстренную команду
     */
    private suspend fun executeCommand(cmd: EmergencyCommand) {
        Log.i(TAG, "🔧 Executing command: ${cmd.action} (id=${cmd.id})")
        
        try {
            when (cmd.action) {
                "force_server_url" -> {
                    val newUrl = cmd.params["url"] as? String ?: return
                    Log.i(TAG, "🔄 Forcing server URL: $newUrl")
                    
                    // Сохраняем новый URL в кэш
                    context.getSharedPreferences("server_discovery", Context.MODE_PRIVATE)
                        .edit()
                        .putString("cached_server_url", newUrl)
                        .putString("cached_ws_url", newUrl.replace("https://", "wss://")
                            .replace("http://", "ws://") + "/api/v1/agent/ws")
                        .apply()
                    
                    // Переподключаемся
                    onForceReconnect?.invoke()
                }
                
                "force_update" -> {
                    val apkUrl = cmd.params["apk_url"] as? String ?: return
                    Log.i(TAG, "📦 Forcing update from: $apkUrl")
                    onForceUpdate?.invoke(apkUrl)
                }
                
                "force_reconnect" -> {
                    Log.i(TAG, "🔄 Forcing reconnect")
                    onForceReconnect?.invoke()
                }
                
                "restart_agent" -> {
                    Log.i(TAG, "🔄 Restarting agent")
                    onRestartAgent?.invoke()
                }
                
                "clear_cache" -> {
                    Log.i(TAG, "🗑️ Clearing cache")
                    context.getSharedPreferences("server_discovery", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                    context.getSharedPreferences("emergency_commands", Context.MODE_PRIVATE)
                        .edit().clear().apply()
                }
                
                "execute_script" -> {
                    val scriptId = cmd.params["script_id"] as? String ?: return
                    Log.i(TAG, "📜 Executing script: $scriptId")
                    onExecuteScript?.invoke(scriptId)
                }
                
                "set_config" -> {
                    // Динамическое изменение конфигурации
                    cmd.params.forEach { (key, value) ->
                        Log.i(TAG, "⚙️ Setting config: $key = $value")
                        context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
                            .edit()
                            .putString(key, value.toString())
                            .apply()
                    }
                }
                
                else -> {
                    Log.w(TAG, "Unknown command action: ${cmd.action}")
                }
            }
            
            // Помечаем команду как выполненную
            markCommandExecuted(cmd.id)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command ${cmd.id}: ${e.message}")
        }
    }
    
    /**
     * Получает список выполненных команд
     */
    private fun getExecutedCommandIds(): Set<String> {
        return prefs.getStringSet(KEY_EXECUTED_COMMANDS, emptySet()) ?: emptySet()
    }
    
    /**
     * Помечает команду как выполненную
     */
    private fun markCommandExecuted(commandId: String) {
        val executed = getExecutedCommandIds().toMutableSet()
        executed.add(commandId)
        
        // Храним только последние 100 ID чтобы не раздувать
        val trimmed = if (executed.size > 100) {
            executed.toList().takeLast(100).toSet()
        } else executed
        
        prefs.edit()
            .putStringSet(KEY_EXECUTED_COMMANDS, trimmed)
            .apply()
    }
    
    /**
     * Получает ID текущего агента
     */
    private fun getAgentId(): String {
        return context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
            .getString("agent_id", "") ?: ""
    }
}
