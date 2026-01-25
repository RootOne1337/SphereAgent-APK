package com.sphere.agent.core

import android.annotation.SuppressLint
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
    val fallback_urls: List<String> = emptyList(),
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
        
        // WebSocket path - ВСЕГДА должен быть правильным
        val wsPath = config.server.websocket_path.ifEmpty { "/api/v1/agent/ws" }
        
        // 1. Приоритет: явный ws_url из конфига (если есть)
        if (config.ws_url.isNotEmpty()) {
            urls.add(config.ws_url)
        }
        
        // 2. Основной сервер с новой структурой
        val primaryUrl = config.server.primary_url.ifEmpty { config.server_url }
        if (primaryUrl.isNotEmpty()) {
            val wsUrl = convertToWsUrl(primaryUrl) + wsPath
            if (!urls.contains(wsUrl)) {
                urls.add(wsUrl)
            }
        }
        
        // 3. Fallback: BuildConfig DEFAULT если ничего не сработало
        if (urls.isEmpty()) {
            val defaultWs = convertToWsUrl(BuildConfig.DEFAULT_SERVER_URL) + wsPath
            urls.add(defaultWs)
        }
        
        // 4. Fallback серверы из новой структуры
        config.server.fallback_urls.forEach { url ->
            val wsUrl = convertToWsUrl(url) + wsPath
            if (!urls.contains(wsUrl)) {
                urls.add(wsUrl)
            }
        }
        
        // 5. Legacy fallback_urls
        config.fallback_urls.forEach { url ->
            val wsUrl = convertToWsUrl(url) + wsPath
            if (!urls.contains(wsUrl)) {
                urls.add(wsUrl)
            }
        }
        
        SphereLog.i(TAG, "Server URLs resolved: $urls")
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
    
    /**
     * Получение или создание уникального Device ID
     * 
     * v2.7.0 ENTERPRISE: Детекция клонированных эмуляторов
     * 
     * Проблема: При клонировании эмуляторов (LDPlayer, Memu, Nox)
     * копируются данные приложения включая сохранённый device_id.
     * Все клоны получают одинаковый ID → бэкенд видит их как одно устройство.
     * 
     * Решение: Создаём "отпечаток окружения" на основе:
     * 1. ANDROID_ID - уникален для каждого эмулятора/клона
     * 2. Build.SERIAL - серийный номер (deprecated но работает)
     * 3. /proc/sys/kernel/random/boot_id - уникален после каждой загрузки
     * 4. MAC адрес (если доступен)
     * 
     * Если отпечаток не совпадает с сохранённым - это клон, генерируем новый ID.
     */
    private fun getOrCreateDeviceId(): String {
        // Создаём отпечаток текущего окружения
        val currentFingerprint = generateEnvironmentFingerprint()
        
        // Получаем сохранённые данные
        val savedId = settingsRepository.getDeviceIdSync()
        val savedFingerprint = settingsRepository.getEnvironmentFingerprintSync()
        
        // Проверяем: это клон? (fingerprint изменился)
        if (savedId != null && savedFingerprint != null) {
            if (savedFingerprint == currentFingerprint) {
                // Тот же экземпляр - используем сохранённый ID
                return savedId
            } else {
                // ДЕТЕКЦИЯ КЛОНА: fingerprint не совпадает!
                SphereLog.w(TAG, "⚠️ CLONE DETECTED: Environment fingerprint changed!")
                SphereLog.w(TAG, "   Old: $savedFingerprint")
                SphereLog.w(TAG, "   New: $currentFingerprint")
                SphereLog.i(TAG, "   Generating new unique device ID for this clone...")
                // Продолжаем генерацию нового ID
            }
        }
        
        // v2.26.0 ENTERPRISE: Пробуем восстановить ID с SD-карты (fallback)
        if (savedId == null) {
            val sdCardId = tryRestoreDeviceIdFromSdCard()
            if (sdCardId != null) {
                // Сохраняем восстановленный ID
                settingsRepository.saveDeviceIdSync(sdCardId)
                settingsRepository.saveEnvironmentFingerprintSync(currentFingerprint)
                SphereLog.i(TAG, "Device ID restored from SD card: ${sdCardId.take(8)}...")
                return sdCardId
            }
        }
        
        // Генерируем новый уникальный ID для этого экземпляра
        val uniqueId = generateUniqueDeviceId(currentFingerprint)
        
        // Сохраняем ID и fingerprint
        settingsRepository.saveDeviceIdSync(uniqueId)
        settingsRepository.saveEnvironmentFingerprintSync(currentFingerprint)
        
        // v2.26.0: Также сохраняем на SD-карту как backup
        saveDeviceIdToSdCard(uniqueId)
        
        SphereLog.i(TAG, "Device ID created/updated: ${uniqueId.take(8)}...")
        return uniqueId
    }
    
    /**
     * Генерация отпечатка окружения для детекции клонов
     * Использует множество источников для максимальной уникальности
     */
    @SuppressLint("HardwareIds")
    private fun generateEnvironmentFingerprint(): String {
        val components = mutableListOf<String>()
        
        // 1. ANDROID_ID - главный идентификатор (уникален для каждого эмулятора)
        try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (androidId != null && androidId != "9774d56d682e549c" && androidId.length > 4) {
                components.add("aid:$androidId")
            }
        } catch (e: Exception) {
            SphereLog.w(TAG, "Failed to get ANDROID_ID: ${e.message}")
        }
        
        // 2. Build fingerprint (hardware info)
        components.add("bf:${Build.FINGERPRINT.hashCode()}")
        
        // 3. Build.SERIAL (deprecated but works on emulators)
        @Suppress("DEPRECATION")
        try {
            val serial = Build.SERIAL
            if (serial != null && serial != Build.UNKNOWN && serial.length > 2) {
                components.add("ser:$serial")
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        // 4. /sys/class/dmi/id/product_uuid - уникален для VM (в отличие от boot_id стабилен)
        // НЕ используем boot_id - он меняется при перезагрузке!
        try {
            val dmiPaths = listOf(
                "/sys/class/dmi/id/product_uuid",
                "/sys/class/dmi/id/product_serial",
                "/sys/devices/virtual/dmi/id/product_uuid"
            )
            for (path in dmiPaths) {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    val uuid = file.readText().trim()
                    if (uuid.isNotEmpty() && uuid.length > 8) {
                        components.add("dmi:${uuid.take(12)}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Не критично - DMI может быть недоступен
        }
        
        // 4b. Emulator instance directory (стабильно, уникально для каждого клона)
        try {
            val dataDir = context.dataDir.absolutePath
            // LDPlayer хранит клоны в разных директориях: /data/data/... но user-id разный
            val userId = android.os.Process.myUserHandle().hashCode()
            components.add("uid:$userId")
        } catch (e: Exception) {
            // Не критично
        }
        
        // 5. MAC адрес Wi-Fi (если доступен)
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.name == "wlan0" || ni.name == "eth0") {
                    val mac = ni.hardwareAddress
                    if (mac != null && mac.isNotEmpty()) {
                        val macStr = mac.joinToString(":") { String.format("%02X", it) }
                        components.add("mac:${macStr.hashCode()}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // Не критично
        }
        
        // 6. Размер экрана + DPI (дополнительный маркер)
        try {
            val metrics = context.resources.displayMetrics
            components.add("dpi:${metrics.densityDpi}x${metrics.widthPixels}x${metrics.heightPixels}")
        } catch (e: Exception) {
            // Не критично
        }
        
        // v2.26.0 ENTERPRISE: LDPlayer/Memu/Nox специфичные свойства
        // Эти свойства уникальны для каждого клона эмулятора
        addEmulatorSpecificFingerprint(components)
        
        // Комбинируем в один fingerprint
        val combined = components.joinToString("|")
        return UUID.nameUUIDFromBytes(combined.toByteArray()).toString().take(16)
    }
    
    /**
     * v2.26.0 ENTERPRISE: Добавление специфичных для эмулятора идентификаторов
     * 
     * LDPlayer, Memu, Nox и другие эмуляторы сохраняют уникальные свойства в build.prop
     * которые меняются при клонировании. Это критически важно для детекции клонов!
     */
    private fun addEmulatorSpecificFingerprint(components: MutableList<String>) {
        try {
            // Читаем build.prop для LDPlayer/Memu специфичных свойств
            val buildPropPaths = listOf(
                "/system/build.prop",
                "/vendor/build.prop",
                "/data/local/tmp/build.prop"
            )
            
            val ldplayerProps = listOf(
                "ro.product.model_for_ld",        // LDPlayer instance model
                "ro.product.name_for_ld",         // LDPlayer instance name
                "ro.serialno",                     // Serial number (часто уникален)
                "ro.boot.serialno",                // Boot serial
                "ro.ld.player.index",              // LDPlayer instance index (0, 1, 2...)
                "ro.ld.adb.port",                  // LDPlayer ADB port (уникален)
                "persist.sys.timezone",            // Timezone (может быть настроен)
                "ro.build.display.id",             // Display ID
                "ro.emu.instance.id",              // Generic emulator instance ID
                "ro.memu.instance.id",             // Memu instance ID
                "ro.nox.instance.id"               // Nox instance ID
            )
            
            for (propPath in buildPropPaths) {
                try {
                    val file = java.io.File(propPath)
                    if (!file.exists()) continue
                    
                    val content = file.readText()
                    for (prop in ldplayerProps) {
                        val regex = "$prop=(.+)".toRegex()
                        val match = regex.find(content)
                        if (match != null) {
                            val value = match.groupValues[1].trim()
                            if (value.isNotBlank() && value != "unknown") {
                                components.add("emu:${prop.takeLast(10)}=${value.hashCode()}")
                                SphereLog.d(TAG, "Found emulator prop: $prop = $value")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Файл недоступен - нормально для некоторых эмуляторов
                }
            }
            
            // Также пробуем через getprop (shell)
            try {
                val runtime = Runtime.getRuntime()
                for (prop in ldplayerProps.take(5)) { // Только важные для скорости
                    val process = runtime.exec(arrayOf("getprop", prop))
                    val value = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    
                    if (value.isNotBlank() && value != "unknown" && value.length > 1) {
                        components.add("gp:${prop.takeLast(8)}=${value.hashCode()}")
                    }
                }
            } catch (e: Exception) {
                // getprop не доступен
            }
            
        } catch (e: Exception) {
            SphereLog.w(TAG, "Failed to read emulator props: ${e.message}")
        }
    }
    
    /**
     * Генерация уникального Device ID
     * Использует fingerprint + случайность для гарантии уникальности
     */
    private fun generateUniqueDeviceId(fingerprint: String): String {
        val timeComponent = System.currentTimeMillis().toString(36)
        val randomComponent = UUID.randomUUID().toString().take(8)
        val fingerprintComponent = fingerprint.take(8)
        
        // Формат: fp-XXXXXXXX-time-random
        val combined = "$fingerprintComponent-$timeComponent-$randomComponent"
        return UUID.nameUUIDFromBytes(combined.toByteArray()).toString()
    }
    
    /**
     * v2.26.0 ENTERPRISE: Сохранение ID на SD-карту как fallback
     * 
     * При сбросе данных приложения ID теряется. SD-карта обычно выживает
     * при переустановке APK на эмуляторах.
     */
    private fun saveDeviceIdToSdCard(deviceId: String) {
        try {
            val sdcardPath = "/sdcard/.sphere_id"
            val file = java.io.File(sdcardPath)
            file.writeText(deviceId)
            SphereLog.d(TAG, "Saved device ID to SD card backup")
        } catch (e: Exception) {
            // SD-карта может быть недоступна - это нормально
            SphereLog.d(TAG, "Could not save to SD card: ${e.message}")
        }
    }
    
    /**
     * v2.26.0 ENTERPRISE: Попытка восстановить ID с SD-карты
     */
    private fun tryRestoreDeviceIdFromSdCard(): String? {
        return try {
            val sdcardPath = "/sdcard/.sphere_id"
            val file = java.io.File(sdcardPath)
            if (file.exists()) {
                val id = file.readText().trim()
                if (id.length >= 32) { // UUID минимум 32 символа
                    SphereLog.i(TAG, "Restored device ID from SD card backup")
                    id
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Информация об устройстве
 */
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val manufacturer: String,
    val brand: String
)
