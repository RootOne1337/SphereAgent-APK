package com.sphere.agent.data

import android.content.Context
import android.util.Log
import com.sphere.agent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * RemoteConfigManager - Управление удалённой конфигурацией с GitHub
 * 
 * Enterprise-grade решение для 500+ устройств:
 * - Загрузка конфига с GitHub raw URL
 * - Кэширование локально
 * - Fallback на BuildConfig значения
 * - Периодическое обновление
 */

@Serializable
data class RemoteConfig(
    val version: String = "1.0",
    val server: ServerConfig = ServerConfig(),
    val connection: ConnectionConfig = ConnectionConfig(),
    val stream: StreamConfig = StreamConfig(),
    val ota: OtaConfig = OtaConfig(),
    val features: FeaturesConfig = FeaturesConfig(),
    val security: SecurityConfig = SecurityConfig()
)

@Serializable
data class ServerConfig(
    val primary_url: String = BuildConfig.DEFAULT_SERVER_URL,
    val fallback_urls: List<String> = emptyList(),
    val websocket_path: String = "/api/v1/agent/ws",
    val api_version: String = "v1"
)

@Serializable
data class ConnectionConfig(
    val reconnect_delay_ms: Int = BuildConfig.RECONNECT_DELAY_MS,
    val max_reconnect_delay_ms: Int = BuildConfig.MAX_RECONNECT_DELAY_MS,
    val heartbeat_interval_ms: Int = BuildConfig.HEARTBEAT_INTERVAL_MS,
    val connection_timeout_ms: Int = BuildConfig.CONNECTION_TIMEOUT_MS,
    val max_retries: Int = 0,
    val backoff_multiplier: Double = 2.0
)

@Serializable
data class StreamConfig(
    val default_quality: Int = BuildConfig.DEFAULT_STREAM_QUALITY,
    val default_fps: Int = BuildConfig.DEFAULT_STREAM_FPS,
    val max_fps: Int = 60,
    val adaptive_quality: Boolean = true,
    val compression: String = "jpeg"
)

@Serializable
data class OtaConfig(
    val enabled: Boolean = BuildConfig.AUTO_UPDATE_ENABLED,
    val check_interval_hours: Int = BuildConfig.UPDATE_CHECK_INTERVAL_HOURS,
    val auto_download: Boolean = true,
    val auto_install: Boolean = false,
    val wifi_only: Boolean = false,
    val changelog_url: String = BuildConfig.CHANGELOG_URL
)

@Serializable
data class FeaturesConfig(
    val shell_commands: Boolean = true,
    val file_transfer: Boolean = true,
    val app_management: Boolean = true,
    val screen_capture: Boolean = true,
    val touch_injection: Boolean = true,
    val metrics_collection: Boolean = true
)

@Serializable
data class SecurityConfig(
    val require_auth: Boolean = true,
    val pin_certificates: Boolean = false,
    val allowed_commands: List<String> = emptyList()
)

class RemoteConfigManager(private val context: Context) {
    
    companion object {
        private const val TAG = "RemoteConfigManager"
        private const val PREFS_NAME = "remote_config"
        private const val KEY_CACHED_CONFIG = "cached_config"
        private const val KEY_LAST_FETCH = "last_fetch_time"
        private const val KEY_CONFIG_VERSION = "config_version"
        private const val CACHE_DURATION_MS = 30 * 60 * 1000L // 30 минут
    }
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        coerceInputValues = true
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _config = MutableStateFlow(getDefaultConfig())
    val config: StateFlow<RemoteConfig> = _config.asStateFlow()
    
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()
    
    /**
     * Инициализация - загрузка кэша, потом fetch
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            // Сначала загружаем кэшированный конфиг
            loadCachedConfig()?.let { cached ->
                _config.value = cached
                _isLoaded.value = true
                Log.d(TAG, "Loaded cached config v${cached.version}")
            }
            
            // Пытаемся получить свежий конфиг
            fetchRemoteConfig()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize config", e)
            _isLoaded.value = true // Используем дефолтный
        }
    }
    
    /**
     * Получение удалённого конфига с GitHub
     */
    suspend fun fetchRemoteConfig(): RemoteConfig? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching remote config from GitHub...")
            
            val request = Request.Builder()
                .url(BuildConfig.REMOTE_CONFIG_URL)
                .header("User-Agent", "SphereAgent/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch config: ${response.code}")
                return@withContext null
            }
            
            val body = response.body?.string() ?: return@withContext null
            val remoteConfig = json.decodeFromString<RemoteConfig>(body)
            
            // Сохраняем в кэш
            cacheConfig(body, remoteConfig.version)
            
            _config.value = remoteConfig
            _isLoaded.value = true
            
            Log.d(TAG, "Remote config loaded v${remoteConfig.version}")
            return@withContext remoteConfig
            
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching remote config", e)
            return@withContext null
        }
    }
    
    /**
     * Загрузка кэшированного конфига
     */
    private fun loadCachedConfig(): RemoteConfig? {
        return try {
            val cachedJson = prefs.getString(KEY_CACHED_CONFIG, null) ?: return null
            json.decodeFromString<RemoteConfig>(cachedJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached config", e)
            null
        }
    }
    
    /**
     * Сохранение конфига в кэш
     */
    private fun cacheConfig(configJson: String, version: String) {
        prefs.edit()
            .putString(KEY_CACHED_CONFIG, configJson)
            .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
            .putString(KEY_CONFIG_VERSION, version)
            .apply()
    }
    
    /**
     * Проверка, нужно ли обновить кэш
     */
    fun shouldRefresh(): Boolean {
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0)
        return System.currentTimeMillis() - lastFetch > CACHE_DURATION_MS
    }
    
    /**
     * Получение URL сервера с учётом fallback
     */
    fun getServerUrl(): String {
        return _config.value.server.primary_url.ifEmpty { 
            BuildConfig.DEFAULT_SERVER_URL 
        }
    }
    
    /**
     * Получение WebSocket URL
     */
    fun getWebSocketUrl(): String {
        val baseUrl = getServerUrl()
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        return "$baseUrl${_config.value.server.websocket_path}"
    }
    
    /**
     * Получение fallback URLs для reconnect
     */
    fun getFallbackUrls(): List<String> {
        return _config.value.server.fallback_urls
    }
    
    /**
     * Дефолтная конфигурация
     */
    private fun getDefaultConfig(): RemoteConfig {
        return RemoteConfig(
            version = "default",
            server = ServerConfig(
                primary_url = BuildConfig.DEFAULT_SERVER_URL,
                fallback_urls = listOf(),
                websocket_path = "/api/v1/agent/ws"
            ),
            connection = ConnectionConfig(
                reconnect_delay_ms = BuildConfig.RECONNECT_DELAY_MS,
                max_reconnect_delay_ms = BuildConfig.MAX_RECONNECT_DELAY_MS,
                heartbeat_interval_ms = BuildConfig.HEARTBEAT_INTERVAL_MS,
                connection_timeout_ms = BuildConfig.CONNECTION_TIMEOUT_MS
            ),
            stream = StreamConfig(
                default_quality = BuildConfig.DEFAULT_STREAM_QUALITY,
                default_fps = BuildConfig.DEFAULT_STREAM_FPS
            ),
            ota = OtaConfig(
                enabled = BuildConfig.AUTO_UPDATE_ENABLED,
                check_interval_hours = BuildConfig.UPDATE_CHECK_INTERVAL_HOURS
            )
        )
    }
}
