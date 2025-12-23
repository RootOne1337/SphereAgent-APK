package com.sphere.agent.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.data.SettingsRepository
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.network.ConnectionState
import com.sphere.agent.network.DiscoveryState
import com.sphere.agent.network.ServerDiscoveryManager
import com.sphere.agent.service.ScreenCaptureService
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
    
    // Lazy инициализация с защитой от ошибок
    private val agentConfig: AgentConfig? by lazy {
        try {
            AgentConfig(application)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AgentConfig", e)
            null
        }
    }
    
    private val connectionManager: ConnectionManager? by lazy {
        try {
            agentConfig?.let { ConnectionManager(application, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ConnectionManager", e)
            null
        }
    }
    
    private val settingsRepository: SettingsRepository? by lazy {
        try {
            SettingsRepository(application)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SettingsRepository", e)
            null
        }
    }
    
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
            agentConfig?.let { config ->
                com.sphere.agent.util.SphereLog.init(config)
                com.sphere.agent.util.SphereLog.i(TAG, "SphereLog initialized for device: ${config.deviceId}")
            }
            
            // Запускаем автодискавери при старте
            startAutoDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            _uiState.update { it.copy(errorMessage = "Ошибка инициализации: ${e.message}") }
        }
    }
    
    /**
     * Запуск автоматического поиска сервера
     */
    private fun startAutoDiscovery() {
        viewModelScope.launch {
            try {
                Log.i(TAG, "🔍 Запускаем автопоиск сервера...")
                _uiState.update { it.copy(serverUrl = "🔍 Поиск сервера...") }
                
                val server = discoveryManager.discoverServer()
                
                if (server != null) {
                    Log.i(TAG, "✅ Сервер найден: ${server.httpUrl} (${server.source})")
                    _uiState.update { 
                        it.copy(
                            serverUrl = server.httpUrl,
                            serverSource = server.source.name
                        ) 
                    }
                    _effect.emit(MainEffect.ShowToast("Сервер найден: ${server.source}"))
                } else {
                    Log.w(TAG, "❌ Сервер не найден")
                    _uiState.update { it.copy(serverUrl = "❌ Сервер не найден") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto discovery failed", e)
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
                Log.e(TAG, "observeDiscoveryState failed", e)
            }
        }
    }
    
    private fun initializeState() {
        try {
            agentConfig?.let { config ->
                _uiState.update { state ->
                    state.copy(
                        deviceId = config.deviceId,
                        deviceName = config.deviceInfo.deviceName
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "initializeState failed", e)
        }
    }
    
    private fun observeConnectionState() {
        try {
            connectionManager?.let { manager ->
                viewModelScope.launch {
                    try {
                        manager.connectionState.collectLatest { state ->
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
                        Log.e(TAG, "observeConnectionState collect failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "observeConnectionState failed", e)
        }
    }
    
    private fun loadConfig() {
        try {
            agentConfig?.let { config ->
                viewModelScope.launch {
                    try {
                        config.loadRemoteConfig()
                        
                        config.config.collectLatest { remoteConfig ->
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
                        Log.e(TAG, "loadConfig collect failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadConfig failed", e)
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
                is MainEvent.DismissError -> dismissError()
                is MainEvent.UpdateQuality -> updateQuality(event.quality)
                is MainEvent.UpdateFps -> updateFps(event.fps)
                is MainEvent.RetryDiscovery -> startAutoDiscovery()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onEvent failed: $event", e)
        }
    }
    
    private fun startService() {
        viewModelScope.launch {
            try {
                if (!_uiState.value.hasPermissions) {
                    _effect.emit(MainEffect.RequestMediaProjection)
                    return@launch
                }
                
                ScreenCaptureService.startCapture(getApplication())
                _uiState.update { it.copy(isServiceRunning = true) }
                _effect.emit(MainEffect.ShowToast("Service started"))
            } catch (e: Exception) {
                Log.e(TAG, "startService failed", e)
                _effect.emit(MainEffect.ShowToast("Ошибка запуска: ${e.message}"))
            }
        }
    }
    
    private fun stopService() {
        viewModelScope.launch {
            try {
                ScreenCaptureService.stopCapture(getApplication())
                _uiState.update { it.copy(isServiceRunning = false) }
                _effect.emit(MainEffect.ShowToast("Service stopped"))
            } catch (e: Exception) {
                Log.e(TAG, "stopService failed", e)
            }
        }
    }
    
    private fun updateServerUrl(url: String) {
        viewModelScope.launch {
            try {
                settingsRepository?.saveServerUrl(url)
                _uiState.update { it.copy(serverUrl = url) }
                
                // Переподключаемся к новому серверу
                if (_uiState.value.isServiceRunning) {
                    connectionManager?.disconnect()
                    connectionManager?.connect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "updateServerUrl failed", e)
            }
        }
    }
    
    private fun refreshConfig() {
        viewModelScope.launch {
            try {
                _effect.emit(MainEffect.ShowToast("Refreshing config..."))
                agentConfig?.loadRemoteConfig()
            } catch (e: Exception) {
                Log.e(TAG, "refreshConfig failed", e)
            }
        }
    }
    
    private fun requestPermissions() {
        viewModelScope.launch {
            try {
                _effect.emit(MainEffect.RequestMediaProjection)
            } catch (e: Exception) {
                Log.e(TAG, "requestPermissions failed", e)
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
                    Log.e(TAG, "onPermissionResult emit failed", e)
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        try {
            connectionManager?.shutdown()
            discoveryManager.release()
        } catch (e: Exception) {
            Log.e(TAG, "onCleared failed", e)
        }
    }
}

sealed class MainEffect {
    data class ShowToast(val message: String) : MainEffect()
    object RequestMediaProjection : MainEffect()
    data class Navigate(val route: String) : MainEffect()
}
