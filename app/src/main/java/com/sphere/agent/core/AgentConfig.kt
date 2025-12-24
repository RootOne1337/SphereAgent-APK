package com.sphere.agent.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.sphere.agent.BuildConfig
import com.sphere.agent.data.SettingsRepository
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Remote Config - Enterprise конфигурация агента с GitHub
 * 
 * Формат соответствует sphere-config/agent-config.json:
 * - server: primary_url, fallback_urls, websocket_path
 * - connection: reconnect, heartbeat, timeouts
 * - stream: quality, fps, compression
 * - ota: auto-update settings
 * - features: enabled capabilities
 * - security: auth, certificates
 */
@Serializable
data class RemoteConfig(
    val version: String = "1.0",
    val server: ServerSettings = ServerSettings(),
    val connection: ConnectionSettings = ConnectionSettings(),
    val stream: StreamConfig = StreamConfig(),
    val ota: OtaSettings = OtaSettings(),
    val features: FeatureFlags = FeatureFlags(),
    val security: SecuritySettings = SecuritySettings(),
    // Legacy compatibility
    val server_url: String = "",
    val ws_url: String = "",
    val agent_version: String = BuildConfig.VERSION_NAME,
    val min_version: String = "1.0.0",
    val update_check_interval: Int = 3600,
    val heartbeat_interval: Int = 30,
    val fallback_urls: List<String> = emptyList()
)

@Serializable
data class ServerSettings(
    val primary_url: String = BuildConfig.DEFAULT_SERVER_URL,
    val fallback_urls: List<String> = listOf(
        "https://adb.leetpc.com",
        "http://10.0.2.2:8000",
        "http://192.168.1.100:8000"
    ),
    val websocket_path: String = "/api/v1/agent/ws",
    val api_version: String = "v1"
)

@Serializable
data class ConnectionSettings(
    val reconnect_delay_ms: Int = BuildConfig.RECONNECT_DELAY_MS,
    val max_reconnect_delay_ms: Int = BuildConfig.MAX_RECONNECT_DELAY_MS,
    val heartbeat_interval_ms: Int = BuildConfig.HEARTBEAT_INTERVAL_MS,
    val connection_timeout_ms: Int = BuildConfig.CONNECTION_TIMEOUT_MS,
    val max_retries: Int = 0,
    val backoff_multiplier: Double = 2.0
)

@Serializable
data class StreamConfig(
    val quality: Int = BuildConfig.DEFAULT_STREAM_QUALITY,
    val fps: Int = BuildConfig.DEFAULT_STREAM_FPS,
    val max_dimension: Int = 1280,
    val max_fps: Int = 60,
    val adaptive_quality: Boolean = true,
    val compression: String = "jpeg",
    // Legacy compatibility
    val default_quality: Int = BuildConfig.DEFAULT_STREAM_QUALITY,
    val default_fps: Int = BuildConfig.DEFAULT_STREAM_FPS
)

@Serializable
data class OtaSettings(
    val enabled: Boolean = BuildConfig.AUTO_UPDATE_ENABLED,
    val check_interval_hours: Int = BuildConfig.UPDATE_CHECK_INTERVAL_HOURS,
    val auto_download: Boolean = true,
    val auto_install: Boolean = false,
    val wifi_only: Boolean = false,
    val changelog_url: String = BuildConfig.CHANGELOG_URL
)

@Serializable
data class FeatureFlags(
    val shell_commands: Boolean = true,
    val file_transfer: Boolean = true,
    val app_management: Boolean = true,
    val screen_capture: Boolean = true,
    val touch_injection: Boolean = true,
    val metrics_collection: Boolean = true
)

@Serializable
data class SecuritySettings(
    val require_auth: Boolean = true,
    val pin_certificates: Boolean = false,
    val allowed_commands: List<String> = emptyList()
)

class AgentConfig(private val context: Context) {
    
    companion object {
        private const val TAG = "AgentConfig"
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
    
    private val settingsRepository = SettingsRepository(context)
    
    private val _config = MutableStateFlow(RemoteConfig())
    val config: StateFlow<RemoteConfig> = _config.asStateFlow()
    
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()
    
    // Уникальный ID устройства
    val deviceId: String by lazy {
        getOrCreateDeviceId()
    }
    
    // Информация об устройстве
    val deviceInfo: DeviceInfo by lazy {
        DeviceInfo(
            deviceId = deviceId,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND
        )
    }
    
    /**
     * Загрузка Remote Config с GitHub
     * Использует raw.githubusercontent.com для получения JSON
     */
    suspend fun loadRemoteConfig(): Result<RemoteConfig> = withContext(Dispatchers.IO) {
        try {
            SphereLog.d(TAG, "Loading remote config from GitHub...")
            
            // GitHub raw URL для конфига
            val configUrl = BuildConfig.REMOTE_CONFIG_URL
            
            val request = Request.Builder()
                .url(configUrl)
                .header("User-Agent", "SphereAgent/${BuildConfig.VERSION_NAME}")
                .header("X-Device-Id", deviceId)
                .header("Cache-Control", "no-cache")
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val remoteConfig = json.decodeFromString<RemoteConfig>(body)
                        _config.value = remoteConfig
                        _isLoaded.value = true
                        
                        // Кэшируем конфиг локально
                        settingsRepository.cacheConfig(body)
                        
                        SphereLog.i(TAG, "Remote config loaded: v${remoteConfig.version}")
                        return@withContext Result.success(remoteConfig)
                    }
                }
                SphereLog.w(TAG, "Failed to load from GitHub: ${response.code}")
            }
            
            // Если не удалось загрузить - пробуем из кэша
            loadFromCache()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error loading remote config", e)
            // Пробуем загрузить из кэша
            loadFromCache()
        }
    }
    
    private suspend fun loadFromCache(): Result<RemoteConfig> {
        return try {
            val cached = settingsRepository.getCachedConfig()
            if (cached != null) {
                val config = json.decodeFromString<RemoteConfig>(cached)
                _config.value = config
                _isLoaded.value = true
                Result.success(config)
            } else {
                // Используем дефолтный конфиг
                _isLoaded.value = true
                Result.success(_config.value)
            }
        } catch (e: Exception) {
            _isLoaded.value = true
            Result.success(_config.value)
        }
    }
    
    /**
     * Загрузка конфига напрямую с сервера SphereADB (fallback)
     */
    suspend fun loadServerConfig(serverUrl: String): Result<RemoteConfig> = withContext(Dispatchers.IO) {
        try {
            SphereLog.d(TAG, "Loading config from server: $serverUrl")
            
            val request = Request.Builder()
                .url("$serverUrl/api/v1/agent/config")
                .header("User-Agent", "SphereAgent/${BuildConfig.VERSION_NAME}")
                .header("X-Device-Id", deviceId)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val remoteConfig = json.decodeFromString<RemoteConfig>(body)
                        _config.value = remoteConfig
                        SphereLog.i(TAG, "Server config loaded")
                        return@withContext Result.success(remoteConfig)
                    }
                }
            }
            Result.failure(Exception("Failed to load server config"))
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error loading server config", e)
            Result.failure(e)
        }
    }
    
    /**
     * Получение списка серверов для подключения (с учётом fallback)
     * Формирует WebSocket URLs из HTTP URLs
     */
    fun getServerUrls(): List<String> {
        val config = _config.value
        val urls = mutableListOf<String>()
        
        // Основной сервер с новой структурой
        val primaryUrl = config.server.primary_url.ifEmpty { config.server_url }
        val wsPath = config.server.websocket_path
        
        if (primaryUrl.isNotEmpty()) {
            val wsUrl = convertToWsUrl(primaryUrl) + wsPath
            urls.add(wsUrl)
        }
        
        // Legacy ws_url если есть
        if (config.ws_url.isNotEmpty() && !urls.contains(config.ws_url)) {
            urls.add(config.ws_url)
        }
        
        // Fallback серверы из новой структуры
        config.server.fallback_urls.forEach { url ->
            val wsUrl = convertToWsUrl(url) + wsPath
            if (!urls.contains(wsUrl)) {
                urls.add(wsUrl)
            }
        }
        
        // Legacy fallback_urls
        config.fallback_urls.forEach { url ->
            val wsUrl = convertToWsUrl(url) + wsPath
            if (!urls.contains(wsUrl)) {
                urls.add(wsUrl)
            }
        }
        
        SphereLog.d(TAG, "Server URLs: $urls")
        return urls
    }
    
    /**
     * Конвертация HTTP URL в WebSocket URL
     */
    private fun convertToWsUrl(url: String): String {
        return url
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')
    }
    
    /**
     * Получение настроек подключения
     */
    fun getConnectionSettings(): ConnectionSettings {
        return _config.value.connection
    }
    
    /**
     * Получение настроек стрима
     */
    fun getStreamSettings(): StreamConfig {
        return _config.value.stream
    }
    
    /**
     * Получение настроек OTA
     */
    fun getOtaSettings(): OtaSettings {
        return _config.value.ota
    }
    
    /**
     * Проверка включённой фичи
     */
    fun isFeatureEnabled(feature: String): Boolean {
        val features = _config.value.features
        return when (feature) {
            "shell_commands" -> features.shell_commands
            "file_transfer" -> features.file_transfer
            "app_management" -> features.app_management
            "screen_capture" -> features.screen_capture
            "touch_injection" -> features.touch_injection
            "metrics_collection" -> features.metrics_collection
            else -> true
        }
    }
    
    private fun getOrCreateDeviceId(): String {
        // Сначала проверяем сохранённый ID
        val savedId = settingsRepository.getDeviceIdSync()
        if (savedId != null) return savedId
        
        // Генерируем новый уникальный ID
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val uniqueId = if (androidId != null && androidId != "9774d56d682e549c") {
            // Используем Android ID если он валидный
            UUID.nameUUIDFromBytes(androidId.toByteArray()).toString()
        } else {
            // Генерируем случайный UUID
            UUID.randomUUID().toString()
        }
        
        // Сохраняем
        settingsRepository.saveDeviceIdSync(uniqueId)
        return uniqueId
    }
}

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val manufacturer: String,
    val brand: String
)
