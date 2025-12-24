/**
 * ServerDiscoveryManager - Zero-Config автодискавери сервера
 * 
 * Приоритет поиска:
 * 1. Remote Config с GitHub (всегда актуальный URL)
 * 2. Кэшированный URL (последний успешный)
 * 3. DNS-SD/mDNS (локальная сеть)
 * 4. Сканирование сети на порт 8000
 * 5. Предустановленные fallback URLs
 * 
 * Полная отказоустойчивость - приложение САМО находит сервер!
 */
package com.sphere.agent.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат дискавери сервера
 */
data class DiscoveredServer(
    val httpUrl: String,          // HTTP URL (https://...)
    val wsUrl: String,            // WebSocket URL (wss://...)
    val source: DiscoverySource,  // Откуда нашли
    val latencyMs: Long = 0,      // Задержка в мс
    val isSecure: Boolean = true  // HTTPS/WSS
)

/**
 * Источник обнаружения сервера
 */
enum class DiscoverySource {
    REMOTE_CONFIG,    // GitHub конфиг
    CACHED,           // Кэш (последний успешный)
    MDNS,             // mDNS/DNS-SD
    NETWORK_SCAN,     // Сканирование сети
    FALLBACK,         // Предустановленные URL
    MANUAL            // Ручной ввод
}

/**
 * Состояние дискавери
 */
sealed class DiscoveryState {
    object Idle : DiscoveryState()
    object Searching : DiscoveryState()
    data class Found(val server: DiscoveredServer) : DiscoveryState()
    data class Error(val message: String) : DiscoveryState()
}

@Singleton
class ServerDiscoveryManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ServerDiscovery"
        
        // Remote Config URL (GitHub - всегда доступен)
        private const val REMOTE_CONFIG_URL = 
            "https://raw.githubusercontent.com/RootOne1337/sphere-config/main/agent-config.json"
        
        // Предустановленные fallback URLs (в порядке приоритета)
        private val FALLBACK_URLS = listOf(
            "https://adb.leetpc.com",              // PROD домен (web + api)
            "https://sphereadb-api-v2.ru.tuna.am", // Tuna туннель (резерв)
            "http://10.0.2.2:8000",               // Android эмулятор → localhost
            "http://192.168.1.100:8000",          // Типичный LAN
            "http://192.168.0.100:8000",          // Альтернативный LAN
            "http://172.16.0.1:8000",             // Docker bridge
        )
        
        // Порт сервера для сканирования
        private const val SERVER_PORT = 8000
        
        // Таймауты
        private const val CONNECT_TIMEOUT_MS = 3000L
        private const val SCAN_TIMEOUT_MS = 1500L
        
        // mDNS сервис
        private const val SERVICE_TYPE = "_sphereadb._tcp."
        
        // SharedPrefs ключи
        private const val PREFS_NAME = "server_discovery"
        private const val KEY_CACHED_URL = "cached_server_url"
        private const val KEY_CACHED_WS_URL = "cached_ws_url"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()
    
    private val _discoveredServer = MutableStateFlow<DiscoveredServer?>(null)
    val discoveredServer: StateFlow<DiscoveredServer?> = _discoveredServer.asStateFlow()
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    /**
     * Запускает полный цикл автодискавери
     * Пробует все источники по приоритету пока не найдёт рабочий сервер
     */
    suspend fun discoverServer(): DiscoveredServer? {
        _state.value = DiscoveryState.Searching
        Log.i(TAG, "🔍 Запуск автодискавери сервера...")
        
        // 1. Remote Config (GitHub)
        Log.d(TAG, "📡 Проверяем Remote Config...")
        tryRemoteConfig()?.let { server ->
            onServerFound(server)
            return server
        }
        
        // 2. Кэшированный URL
        Log.d(TAG, "💾 Проверяем кэш...")
        tryCachedServer()?.let { server ->
            onServerFound(server)
            return server
        }
        
        // 3. mDNS/DNS-SD
        Log.d(TAG, "📻 Ищем через mDNS...")
        tryMdnsDiscovery()?.let { server ->
            onServerFound(server)
            return server
        }
        
        // 4. Сканирование локальной сети
        Log.d(TAG, "🔎 Сканируем локальную сеть...")
        tryNetworkScan()?.let { server ->
            onServerFound(server)
            return server
        }
        
        // 5. Fallback URLs
        Log.d(TAG, "🔄 Пробуем fallback URLs...")
        tryFallbackUrls()?.let { server ->
            onServerFound(server)
            return server
        }
        
        // Ничего не нашли
        Log.e(TAG, "❌ Сервер не найден!")
        _state.value = DiscoveryState.Error("Сервер не найден. Проверьте подключение к сети.")
        return null
    }
    
    /**
     * Проверяет конкретный URL на доступность сервера
     */
    suspend fun checkServer(httpUrl: String): DiscoveredServer? = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val healthUrl = "${httpUrl.trimEnd('/')}/api/v1/health"
            
            val request = Request.Builder()
                .url(healthUrl)
                .get()
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val latency = System.currentTimeMillis() - startTime
                    val wsUrl = httpUrl
                        .replace("https://", "wss://")
                        .replace("http://", "ws://")
                        .trimEnd('/') + "/api/v1/agent/ws"
                    
                    Log.i(TAG, "✅ Сервер доступен: $httpUrl (${latency}ms)")
                    
                    return@withContext DiscoveredServer(
                        httpUrl = httpUrl,
                        wsUrl = wsUrl,
                        source = DiscoverySource.MANUAL,
                        latencyMs = latency,
                        isSecure = httpUrl.startsWith("https")
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Сервер недоступен: $httpUrl - ${e.message}")
        }
        null
    }
    
    // ==================== Методы дискавери ====================
    
    /**
     * Получает URL из Remote Config на GitHub
     */
    private suspend fun tryRemoteConfig(): DiscoveredServer? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(REMOTE_CONFIG_URL)
                .get()
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val serverBlock = json.optJSONObject("server")
                    val primaryUrl = serverBlock?.optString("primary_url")
                    val wsUrl = serverBlock?.optString("ws_url")
                    
                    if (!primaryUrl.isNullOrEmpty()) {
                        // Проверяем что сервер реально доступен
                        val server = checkServerWithSource(
                            httpUrl = primaryUrl,
                            wsUrl = wsUrl ?: "",
                            source = DiscoverySource.REMOTE_CONFIG
                        )
                        if (server != null) {
                            Log.i(TAG, "✅ Найден через Remote Config: $primaryUrl")
                            return@withContext server
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote Config недоступен: ${e.message}")
        }
        null
    }
    
    /**
     * Проверяет кэшированный URL
     */
    private suspend fun tryCachedServer(): DiscoveredServer? {
        val cachedUrl = prefs.getString(KEY_CACHED_URL, null) ?: return null
        val cachedWsUrl = prefs.getString(KEY_CACHED_WS_URL, null) ?: ""
        
        return checkServerWithSource(
            httpUrl = cachedUrl,
            wsUrl = cachedWsUrl,
            source = DiscoverySource.CACHED
        )
    }
    
    /**
     * Ищет сервер через mDNS/DNS-SD
     */
    private suspend fun tryMdnsDiscovery(): DiscoveredServer? = withContext(Dispatchers.IO) {
        try {
            val foundServer = CompletableDeferred<DiscoveredServer?>()
            
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "mDNS discovery started")
                }
                
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "mDNS service found: ${serviceInfo.serviceName}")
                    
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "mDNS resolve failed: $errorCode")
                        }
                        
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val host = si.host?.hostAddress ?: return
                            val port = si.port
                            val httpUrl = "http://$host:$port"
                            
                            scope.launch {
                                checkServerWithSource(httpUrl, "", DiscoverySource.MDNS)?.let {
                                    foundServer.complete(it)
                                }
                            }
                        }
                    })
                }
                
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    foundServer.complete(null)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            
            // Ждём максимум 5 секунд
            withTimeoutOrNull(5000) {
                foundServer.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery failed: ${e.message}")
            null
        } finally {
            stopMdnsDiscovery()
        }
    }
    
    /**
     * Сканирует локальную сеть на порт 8000
     */
    private suspend fun tryNetworkScan(): DiscoveredServer? = withContext(Dispatchers.IO) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo ?: return@withContext null
            
            // Получаем IP шлюза и маску
            val gatewayIp = intToIp(dhcpInfo.gateway)
            val myIp = intToIp(dhcpInfo.ipAddress)
            
            Log.d(TAG, "Сканируем сеть: gateway=$gatewayIp, my=$myIp")
            
            // Определяем подсеть
            val subnet = gatewayIp.substringBeforeLast(".") + "."
            
            // Сканируем параллельно (первые 50 IP быстрее)
            val scanJobs = (1..254).map { i ->
                async {
                    val ip = "$subnet$i"
                    if (isPortOpen(ip, SERVER_PORT, SCAN_TIMEOUT_MS.toInt())) {
                        val httpUrl = "http://$ip:$SERVER_PORT"
                        checkServerWithSource(httpUrl, "", DiscoverySource.NETWORK_SCAN)
                    } else null
                }
            }
            
            // Возвращаем первый найденный
            scanJobs.forEach { job ->
                job.await()?.let { server ->
                    scanJobs.forEach { it.cancel() }
                    Log.i(TAG, "✅ Найден сканированием: ${server.httpUrl}")
                    return@withContext server
                }
            }
            
            null
        } catch (e: Exception) {
            Log.w(TAG, "Network scan failed: ${e.message}")
            null
        }
    }
    
    /**
     * Пробует предустановленные fallback URLs
     */
    private suspend fun tryFallbackUrls(): DiscoveredServer? {
        for (url in FALLBACK_URLS) {
            checkServerWithSource(url, "", DiscoverySource.FALLBACK)?.let { server ->
                Log.i(TAG, "✅ Найден через fallback: ${server.httpUrl}")
                return server
            }
        }
        return null
    }
    
    // ==================== Вспомогательные методы ====================
    
    /**
     * Проверяет сервер и возвращает DiscoveredServer с указанным источником
     */
    private suspend fun checkServerWithSource(
        httpUrl: String,
        wsUrl: String,
        source: DiscoverySource
    ): DiscoveredServer? = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val healthUrl = "${httpUrl.trimEnd('/')}/api/v1/health"
            
            val request = Request.Builder()
                .url(healthUrl)
                .get()
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val latency = System.currentTimeMillis() - startTime
                    val finalWsUrl = wsUrl.ifEmpty {
                        httpUrl
                            .replace("https://", "wss://")
                            .replace("http://", "ws://")
                            .trimEnd('/') + "/api/v1/agent/ws"
                    }
                    
                    return@withContext DiscoveredServer(
                        httpUrl = httpUrl,
                        wsUrl = finalWsUrl,
                        source = source,
                        latencyMs = latency,
                        isSecure = httpUrl.startsWith("https")
                    )
                }
            }
        } catch (e: Exception) {
            // Тихо игнорируем - сервер недоступен
        }
        null
    }
    
    /**
     * Проверяет открыт ли порт
     */
    private fun isPortOpen(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeout)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Конвертирует int IP в строку
     */
    private fun intToIp(ip: Int): String {
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }
    
    /**
     * Вызывается когда сервер найден
     */
    private fun onServerFound(server: DiscoveredServer) {
        Log.i(TAG, "🎯 Сервер выбран: ${server.httpUrl} (${server.source}, ${server.latencyMs}ms)")
        
        // Кэшируем для быстрого старта в следующий раз
        prefs.edit()
            .putString(KEY_CACHED_URL, server.httpUrl)
            .putString(KEY_CACHED_WS_URL, server.wsUrl)
            .apply()
        
        _discoveredServer.value = server
        _state.value = DiscoveryState.Found(server)
    }
    
    /**
     * Останавливает mDNS discovery
     */
    private fun stopMdnsDiscovery() {
        try {
            discoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            // Игнорируем
        }
        discoveryListener = null
    }
    
    /**
     * Очищает кэш
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        _discoveredServer.value = null
        _state.value = DiscoveryState.Idle
    }
    
    /**
     * Освобождает ресурсы
     */
    fun release() {
        stopMdnsDiscovery()
        scope.cancel()
    }
}
