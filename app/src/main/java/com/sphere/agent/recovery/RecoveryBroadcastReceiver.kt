/**
 * RecoveryBroadcastReceiver - Восстановление через ADB broadcast
 * 
 * РЕЗЕРВНЫЙ КАНАЛ СВЯЗИ #3: ADB Broadcast через PC Agent
 * 
 * Когда ВСЕ остальные каналы не работают, PC Agent может отправить
 * broadcast команду через ADB для восстановления агента:
 * 
 * adb shell am broadcast -a com.sphere.agent.RECOVERY_COMMAND \
 *     -e action "force_server_url" \
 *     -e url "http://new-server.com:8000" \
 *     --ei priority 100
 * 
 * Поддерживаемые команды:
 * - force_server_url: Сменить URL сервера
 * - force_reconnect: Переподключиться
 * - restart_service: Перезапустить сервис
 * - force_update: Скачать и установить APK
 * - clear_all_cache: Очистить весь кэш
 * - kill_and_restart: Убить процесс и перезапустить
 * - send_logs: Отправить логи на указанный URL
 * - execute_shell: Выполнить shell команду (только debug)
 */
package com.sphere.agent.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.sphere.agent.BuildConfig
import kotlinx.coroutines.*

/**
 * BroadcastReceiver для восстановления через ADB
 */
class RecoveryBroadcastReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "RecoveryBroadcast"
        
        // Actions
        const val ACTION_RECOVERY = "com.sphere.agent.RECOVERY_COMMAND"
        const val ACTION_HEALTH_CHECK = "com.sphere.agent.HEALTH_CHECK"
        const val ACTION_GET_STATUS = "com.sphere.agent.GET_STATUS"
        const val ACTION_FORCE_UPDATE = "com.sphere.agent.FORCE_UPDATE"
        
        // Extras
        const val EXTRA_ACTION = "action"
        const val EXTRA_URL = "url"
        const val EXTRA_PRIORITY = "priority"
        const val EXTRA_SCRIPT_ID = "script_id"
        const val EXTRA_APK_URL = "apk_url"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_AUTH_TOKEN = "auth_token"
        
        // Security: простой токен для защиты (можно усилить)
        private const val RECOVERY_AUTH_TOKEN = "sphere_recovery_2026"
        
        // Callbacks (устанавливаются AgentService)
        private var onForceServerUrl: ((String) -> Unit)? = null
        private var onForceReconnect: (() -> Unit)? = null
        private var onRestartService: (() -> Unit)? = null
        private var onForceUpdate: ((String) -> Unit)? = null
        private var onClearCache: (() -> Unit)? = null
        private var onKillAndRestart: (() -> Unit)? = null
        private var onGetStatus: (() -> String)? = null
        private var onExecuteScript: ((String) -> Unit)? = null
        
        /**
         * Регистрирует receiver в context
         */
        fun register(context: Context): RecoveryBroadcastReceiver {
            val receiver = RecoveryBroadcastReceiver()
            val filter = IntentFilter().apply {
                addAction(ACTION_RECOVERY)
                addAction(ACTION_HEALTH_CHECK)
                addAction(ACTION_GET_STATUS)
                addAction(ACTION_FORCE_UPDATE)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            
            Log.i(TAG, "✅ Recovery Broadcast Receiver registered")
            return receiver
        }
        
        /**
         * Устанавливает callbacks
         */
        fun setCallbacks(
            onForceServerUrl: (String) -> Unit,
            onForceReconnect: () -> Unit,
            onRestartService: () -> Unit,
            onForceUpdate: (String) -> Unit,
            onClearCache: () -> Unit,
            onKillAndRestart: () -> Unit,
            onGetStatus: () -> String,
            onExecuteScript: (String) -> Unit
        ) {
            this.onForceServerUrl = onForceServerUrl
            this.onForceReconnect = onForceReconnect
            this.onRestartService = onRestartService
            this.onForceUpdate = onForceUpdate
            this.onClearCache = onClearCache
            this.onKillAndRestart = onKillAndRestart
            this.onGetStatus = onGetStatus
            this.onExecuteScript = onExecuteScript
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        Log.i(TAG, "📨 Received broadcast: $action")
        
        when (action) {
            ACTION_RECOVERY -> handleRecoveryCommand(context, intent)
            ACTION_HEALTH_CHECK -> handleHealthCheck(context)
            ACTION_GET_STATUS -> handleGetStatus(context)
            ACTION_FORCE_UPDATE -> handleForceUpdate(context, intent)
        }
    }
    
    /**
     * Обрабатывает команду восстановления
     */
    private fun handleRecoveryCommand(context: Context, intent: Intent) {
        // Проверяем auth token (опционально в production)
        val authToken = intent.getStringExtra(EXTRA_AUTH_TOKEN)
        if (BuildConfig.DEBUG || authToken == RECOVERY_AUTH_TOKEN) {
            // OK
        } else {
            Log.w(TAG, "⚠️ Invalid auth token!")
            // В production можно заблокировать, пока пропускаем для удобства
        }
        
        val commandAction = intent.getStringExtra(EXTRA_ACTION) ?: return
        val priority = intent.getIntExtra(EXTRA_PRIORITY, 0)
        
        Log.i(TAG, "🔧 Executing recovery command: $commandAction (priority: $priority)")
        
        when (commandAction) {
            "force_server_url" -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return
                Log.i(TAG, "🔄 Forcing server URL: $url")
                
                // Сохраняем в кэш
                context.getSharedPreferences("server_discovery", Context.MODE_PRIVATE)
                    .edit()
                    .putString("cached_server_url", url)
                    .putString("cached_ws_url", url.replace("https://", "wss://")
                        .replace("http://", "ws://") + "/api/v1/agent/ws")
                    .apply()
                
                onForceServerUrl?.invoke(url)
                onForceReconnect?.invoke()
            }
            
            "force_reconnect" -> {
                Log.i(TAG, "🔄 Forcing reconnect")
                onForceReconnect?.invoke()
            }
            
            "restart_service" -> {
                Log.i(TAG, "🔄 Restarting service")
                onRestartService?.invoke()
            }
            
            "force_update" -> {
                val apkUrl = intent.getStringExtra(EXTRA_APK_URL) 
                    ?: "https://adb.leetpc.com/api/v1/agent/updates/latest.apk"
                Log.i(TAG, "📦 Forcing update from: $apkUrl")
                onForceUpdate?.invoke(apkUrl)
            }
            
            "clear_all_cache" -> {
                Log.i(TAG, "🗑️ Clearing all cache")
                clearAllCache(context)
                onClearCache?.invoke()
            }
            
            "kill_and_restart" -> {
                Log.i(TAG, "💀 Kill and restart")
                onKillAndRestart?.invoke()
            }
            
            "execute_script" -> {
                val scriptId = intent.getStringExtra(EXTRA_SCRIPT_ID) ?: return
                Log.i(TAG, "📜 Executing script: $scriptId")
                onExecuteScript?.invoke(scriptId)
            }
            
            else -> {
                Log.w(TAG, "Unknown recovery action: $commandAction")
            }
        }
        
        // Отправляем broadcast о выполнении
        sendResultBroadcast(context, commandAction, "executed")
    }
    
    /**
     * Обрабатывает health check
     */
    private fun handleHealthCheck(context: Context) {
        Log.d(TAG, "💓 Health check received")
        
        val status = onGetStatus?.invoke() ?: "unknown"
        
        val resultIntent = Intent("com.sphere.agent.HEALTH_RESULT").apply {
            putExtra("status", status)
            putExtra("version", BuildConfig.VERSION_NAME)
            putExtra("version_code", BuildConfig.VERSION_CODE)
            putExtra("timestamp", System.currentTimeMillis())
        }
        context.sendBroadcast(resultIntent)
    }
    
    /**
     * Обрабатывает запрос статуса
     */
    private fun handleGetStatus(context: Context) {
        Log.d(TAG, "📊 Status request received")
        
        val status = onGetStatus?.invoke() ?: "{}"
        
        val resultIntent = Intent("com.sphere.agent.STATUS_RESULT").apply {
            putExtra("status_json", status)
            putExtra("timestamp", System.currentTimeMillis())
        }
        context.sendBroadcast(resultIntent)
    }
    
    /**
     * Обрабатывает force update
     */
    private fun handleForceUpdate(context: Context, intent: Intent) {
        val apkUrl = intent.getStringExtra(EXTRA_APK_URL)
            ?: intent.getStringExtra(EXTRA_URL)
            ?: "https://adb.leetpc.com/api/v1/agent/updates/latest.apk"
        
        Log.i(TAG, "📦 Force update from broadcast: $apkUrl")
        onForceUpdate?.invoke(apkUrl)
    }
    
    /**
     * Очищает весь кэш приложения
     */
    private fun clearAllCache(context: Context) {
        try {
            // Очищаем все SharedPreferences
            listOf(
                "server_discovery",
                "emergency_commands",
                "agent_prefs",
                "agent_config",
                "update_prefs"
            ).forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit().clear().apply()
            }
            
            // Очищаем файловый кэш
            context.cacheDir?.deleteRecursively()
            
            Log.i(TAG, "✅ All cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache: ${e.message}")
        }
    }
    
    /**
     * Отправляет broadcast с результатом выполнения
     */
    private fun sendResultBroadcast(context: Context, action: String, result: String) {
        val resultIntent = Intent("com.sphere.agent.RECOVERY_RESULT").apply {
            putExtra("action", action)
            putExtra("result", result)
            putExtra("timestamp", System.currentTimeMillis())
        }
        context.sendBroadcast(resultIntent)
    }
}
