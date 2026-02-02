package com.sphere.agent.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sphere.agent.SphereAgentApp
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.data.SettingsRepository
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.network.ConnectionState
import com.sphere.agent.network.DiscoveryState
import com.sphere.agent.network.ServerDiscoveryManager
import com.sphere.agent.service.AgentService
import com.sphere.agent.service.H264RootStreamService
import com.sphere.agent.util.SphereLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainViewModel - ViewModel для главного экрана
 * 
 * Управляет:
 * - Состоянием подключения
 * - Настройками сервера
 * - Статистикой
 * - Запуском/остановкой сервиса
 */

data class MainUiState(
    val isServiceRunning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val discoveryState: DiscoveryState = DiscoveryState.Idle,
    val deviceId: String = "",
    val deviceName: String = "",
    val serverUrl: String = "Автопоиск...",
    val serverSource: String = "",
    val isConfigLoaded: Boolean = false,
    val streamQuality: Int = 80,
    val streamFps: Int = 15,
    val hasPermissions: Boolean = false,
    val hasAccessibility: Boolean = false,
    val hasRoot: Boolean = false,  // ROOT доступ (если есть - Accessibility не нужен)
    val controlMode: String = "none",  // "root", "accessibility", "none"
    val errorMessage: String? = null,
    val stats: AgentStats = AgentStats()
)

data class AgentStats(
    val framesSent: Int = 0,
    val bytesTransferred: Long = 0,
    val uptime: Long = 0,
    val commandsExecuted: Int = 0
)

sealed class MainEvent {
    object StartService : MainEvent()
    object StopService : MainEvent()
    data class UpdateServerUrl(val url: String) : MainEvent()
    object RefreshConfig : MainEvent()
    object RequestPermissions : MainEvent()
    object OpenAccessibilitySettings : MainEvent()
    object RequestRoot : MainEvent()  // Запрос root прав
    object DismissError : MainEvent()
    data class UpdateQuality(val quality: Int) : MainEvent()
    data class UpdateFps(val fps: Int) : MainEvent()
    object RetryDiscovery : MainEvent()  // Повторный поиск сервера
}

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val discoveryManager: ServerDiscoveryManager
) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "MainViewModel"
    }
    
    // ВАЖНО: используем singleton-инстансы из Application (иначе UI и сервис живут в разных мирах)
    private val app: SphereAgentApp by lazy { getApplication<SphereAgentApp>() }
    private val agentConfig: AgentConfig get() = app.agentConfig
    private val connectionManager: ConnectionManager get() = app.connectionManager
    private val settingsRepository: SettingsRepository get() = app.settingsRepository
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val _effect = MutableSharedFlow<MainEffect>()
    val effect: SharedFlow<MainEffect> = _effect.asSharedFlow()
    
    init {
        try {
            initializeState()
            observeConnectionState()
            observeDiscoveryState()
            loadConfig()
            
            // Инициализируем удалённое логирование
            SphereLog.init(agentConfig)
            SphereLog.i(TAG, "SphereLog initialized for device: ${agentConfig.deviceId}")
            
            // Запускаем автодискавери при старте
            startAutoDiscovery()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Init failed", e)
            _uiState.update { it.copy(errorMessage = "Ошибка инициализации: ${e.message}") }
        }
    }
    
    /**
     * Запуск автоматического поиска сервера
     */
    private fun startAutoDiscovery() {
        viewModelScope.launch {
            try {
                SphereLog.i(TAG, "🔍 Запускаем автопоиск сервера...")
                _uiState.update { it.copy(serverUrl = "🔍 Поиск сервера...") }
                
                val server = discoveryManager.discoverServer()
                
                if (server != null) {
                    SphereLog.i(TAG, "✅ Сервер найден: ${server.httpUrl} (${server.source})")
                    _uiState.update { 
                        it.copy(
                            serverUrl = server.httpUrl,
                            serverSource = server.source.name
                        ) 
                    }
                    _effect.emit(MainEffect.ShowToast("Сервер найден: ${server.source}"))
                } else {
                    SphereLog.w(TAG, "❌ Сервер не найден")
                    _uiState.update { it.copy(serverUrl = "❌ Сервер не найден") }
                }
            } catch (e: Exception) {
                SphereLog.e(TAG, "Auto discovery failed", e)
                _uiState.update { it.copy(serverUrl = "Ошибка поиска") }
            }
        }
    }
    
    /**
     * Наблюдение за состоянием дискавери
     */
    private fun observeDiscoveryState() {
        viewModelScope.launch {
            try {
                discoveryManager.state.collectLatest { state ->
                    _uiState.update { it.copy(discoveryState = state) }
                    
                    when (state) {
                        is DiscoveryState.Found -> {
                            _uiState.update { 
                                it.copy(
                                    serverUrl = state.server.httpUrl,
                                    serverSource = state.server.source.name
                                )
                            }
                        }
                        is DiscoveryState.Error -> {
                            _uiState.update { it.copy(errorMessage = state.message) }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                SphereLog.e(TAG, "observeDiscoveryState failed", e)
            }
        }
    }
    
    private fun initializeState() {
        try {
            // Проверяем root доступ при старте
            viewModelScope.launch {
                val hasRoot = checkRootAccess()
                val hasAccessibility = com.sphere.agent.service.SphereAccessibilityService.isServiceEnabled()
                val controlMode = when {
                    hasRoot -> "root"
                    hasAccessibility -> "accessibility"
                    else -> "none"
                }
                
                _uiState.update { state ->
                    state.copy(
                        deviceId = agentConfig.deviceId,
                        deviceName = agentConfig.deviceInfo.deviceName,
                        hasRoot = hasRoot,
                        hasAccessibility = hasAccessibility,
                        controlMode = controlMode
                    )
                }
            }
            
            // Обновляем статус каждые 2 секунды
            viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    val hasRoot = _uiState.value.hasRoot  // root проверяем только при старте
                    val hasAccessibility = com.sphere.agent.service.SphereAccessibilityService.isServiceEnabled()
                    val controlMode = when {
                        hasRoot -> "root"
                        hasAccessibility -> "accessibility"
                        else -> "none"
                    }
                    _uiState.update { it.copy(
                        hasAccessibility = hasAccessibility,
                        controlMode = controlMode
                    )}
                }
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "initializeState failed", e)
        }
    }
    
    /**
     * Проверка root доступа
     * v3.5.1: Исправлено для предотвращения ANR - добавлен Dispatchers.IO и таймаут
     */
    private suspend fun checkRootAccess(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val result = reader.readLine()
            // v3.5.1: Таймаут 3 секунды для предотвращения ANR
            val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            result?.contains("uid=0") == true
        } catch (e: Exception) {
            SphereLog.d(TAG, "Root not available: ${e.message}")
            false
        }
    }
    
    private fun observeConnectionState() {
        try {
            viewModelScope.launch {
                try {
                    connectionManager.connectionState.collectLatest { state ->
                        _uiState.update { it.copy(connectionState = state) }

                        when (state) {
                            is ConnectionState.Connected -> {
                                _effect.emit(MainEffect.ShowToast("Connected to server"))
                            }
                            is ConnectionState.Error -> {
                                _uiState.update { it.copy(errorMessage = state.message) }
                            }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    SphereLog.e(TAG, "observeConnectionState collect failed", e)
                }
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "observeConnectionState failed", e)
        }
    }
    
    private fun loadConfig() {
        try {
            viewModelScope.launch {
                try {
                    val res = agentConfig.loadRemoteConfig()
                    SphereLog.i(TAG, "loadRemoteConfig result=${res.isSuccess}; primary=${agentConfig.config.value.server.primary_url}")

                    agentConfig.config.collectLatest { remoteConfig ->
                        _uiState.update { state ->
                            state.copy(
                                serverUrl = remoteConfig.server_url,
                                streamQuality = remoteConfig.stream.quality,
                                streamFps = remoteConfig.stream.fps,
                                isConfigLoaded = true
                            )
                        }
                    }
                } catch (e: Exception) {
                    SphereLog.e(TAG, "loadConfig collect failed", e)
                }
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "loadConfig failed", e)
        }
    }
    
    fun onEvent(event: MainEvent) {
        try {
            when (event) {
                is MainEvent.StartService -> startService()
                is MainEvent.StopService -> stopService()
                is MainEvent.UpdateServerUrl -> updateServerUrl(event.url)
                is MainEvent.RefreshConfig -> refreshConfig()
                is MainEvent.RequestPermissions -> requestPermissions()
                is MainEvent.OpenAccessibilitySettings -> openAccessibilitySettings()
                is MainEvent.RequestRoot -> requestRootAccess()
                is MainEvent.DismissError -> dismissError()
                is MainEvent.UpdateQuality -> updateQuality(event.quality)
                is MainEvent.UpdateFps -> updateFps(event.fps)
                is MainEvent.RetryDiscovery -> startAutoDiscovery()
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "onEvent failed: $event", e)
        }
    }
    
    /**
     * Запрос root доступа - выполняет su команду для появления диалога
     */
    private fun requestRootAccess() {
        viewModelScope.launch {
            try {
                SphereLog.i(TAG, "Requesting root access...")
                
                // Выполняем su команду - это вызовет диалог SuperSU/Magisk
                val hasRoot = checkRootAccess()
                
                _uiState.update { 
                    it.copy(
                        hasRoot = hasRoot,
                        controlMode = if (hasRoot) "root" else it.controlMode
                    ) 
                }
                
                if (hasRoot) {
                    _effect.emit(MainEffect.ShowToast("✓ ROOT access granted!"))
                } else {
                    _effect.emit(MainEffect.ShowToast("ROOT not available. Use Accessibility."))
                }
            } catch (e: Exception) {
                SphereLog.e(TAG, "requestRootAccess failed", e)
                _effect.emit(MainEffect.ShowToast("ROOT request failed"))
            }
        }
    }
    
    private fun startService() {
        viewModelScope.launch {
            try {
                // v3.2.0 ENTERPRISE: Всё управляется через AgentService!
                // AgentService автоматически запускает H.264 стрим после registered
                // Здесь просто запускаем AgentService если он не запущен
                
                if (!AgentService.isRunning) {
                    SphereLog.i(TAG, "=== Starting AgentService (auto-connect mode) ===")
                    AgentService.start(getApplication())
                }
                
                _uiState.update { it.copy(isServiceRunning = true) }
                _effect.emit(MainEffect.ShowToast("Agent reconnecting..."))
            } catch (e: Exception) {
                SphereLog.e(TAG, "startService failed", e)
                _effect.emit(MainEffect.ShowToast("Ошибка запуска: ${e.message}"))
            }
        }
    }
    
    private fun stopService() {
        viewModelScope.launch {
            try {
                // v3.2.0: Только H.264 stream pause (не останавливаем AgentService!)
                SphereLog.i(TAG, "Pausing H.264 stream...")
                if (H264RootStreamService.isRunning) {
                    H264RootStreamService.pause(getApplication())
                }
                _uiState.update { it.copy(isServiceRunning = false) }
                _effect.emit(MainEffect.ShowToast("Stream paused"))
            } catch (e: Exception) {
                SphereLog.e(TAG, "stopService failed", e)
            }
        }
    }
    
    private fun updateServerUrl(url: String) {
        viewModelScope.launch {
            try {
                settingsRepository.saveServerUrl(url)
                _uiState.update { it.copy(serverUrl = url) }
                
                // Переподключаемся к новому серверу
                if (_uiState.value.isServiceRunning) {
                    connectionManager.disconnect()
                    connectionManager.connect()
                }
            } catch (e: Exception) {
                SphereLog.e(TAG, "updateServerUrl failed", e)
            }
        }
    }
    
    private fun refreshConfig() {
        viewModelScope.launch {
            try {
                _effect.emit(MainEffect.ShowToast("Refreshing config..."))
                agentConfig.loadRemoteConfig()
            } catch (e: Exception) {
                SphereLog.e(TAG, "refreshConfig failed", e)
            }
        }
    }
    
    private fun requestPermissions() {
        viewModelScope.launch {
            try {
                _effect.emit(MainEffect.RequestMediaProjection)
            } catch (e: Exception) {
                SphereLog.e(TAG, "requestPermissions failed", e)
            }
        }
    }
    
    private fun openAccessibilitySettings() {
        viewModelScope.launch {
            try {
                _effect.emit(MainEffect.OpenAccessibilitySettings)
            } catch (e: Exception) {
                SphereLog.e(TAG, "openAccessibilitySettings failed", e)
            }
        }
    }
    
    private fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    private fun updateQuality(quality: Int) {
        _uiState.update { it.copy(streamQuality = quality) }
    }
    
    private fun updateFps(fps: Int) {
        _uiState.update { it.copy(streamFps = fps) }
    }
    
    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasPermissions = granted) }
        
        if (granted) {
            startService()
        } else {
            viewModelScope.launch {
                try {
                    _effect.emit(MainEffect.ShowToast("Permission denied"))
                } catch (e: Exception) {
                    SphereLog.e(TAG, "onPermissionResult emit failed", e)
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        try {
            connectionManager.shutdown()
            discoveryManager.release()
        } catch (e: Exception) {
            SphereLog.e(TAG, "onCleared failed", e)
        }
    }
}

sealed class MainEffect {
    data class ShowToast(val message: String) : MainEffect()
    object RequestMediaProjection : MainEffect()
    object OpenAccessibilitySettings : MainEffect()
    data class Navigate(val route: String) : MainEffect()
}
