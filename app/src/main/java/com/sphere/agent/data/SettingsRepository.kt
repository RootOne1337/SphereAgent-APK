package com.sphere.agent.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * SettingsRepository - Хранилище настроек агента
 * 
 * Использует DataStore для персистентного хранения:
 * - URL серверов
 * - Device ID
 * - Кэш конфигурации
 * - Токены авторизации
 * 
 * Также использует SharedPreferences как fallback для синхронных операций
 * для избежания крашей при инициализации
 */

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sphere_settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "SettingsRepository"
        private const val PREFS_NAME = "sphere_prefs"
        
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_WS_URL = stringPreferencesKey("ws_url")
        private val KEY_CONFIG_URL = stringPreferencesKey("config_url")
        private val KEY_CACHED_CONFIG = stringPreferencesKey("cached_config")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_LAST_CONNECTED_SERVER = stringPreferencesKey("last_connected_server")
    }
    
    // SharedPreferences как fallback для синхронных операций
    private val prefs: SharedPreferences by lazy {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SharedPreferences", e)
            throw e
        }
    }
    
    // === Flow-based getters ===
    
    val deviceId: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[KEY_DEVICE_ID] }
    
    val serverUrl: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[KEY_SERVER_URL] }
    
    val wsUrl: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[KEY_WS_URL] }
    
    val authToken: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[KEY_AUTH_TOKEN] }
    
    // === Suspend setters ===
    
    suspend fun saveDeviceId(id: String) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_DEVICE_ID] = id
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save device id to DataStore", e)
        }
        // Также сохраняем в SharedPreferences как backup
        try {
            prefs.edit().putString("device_id", id).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save device id to SharedPreferences", e)
        }
    }
    
    suspend fun saveServerUrl(url: String) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_SERVER_URL] = url
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save server url", e)
        }
    }
    
    suspend fun saveWsUrl(url: String) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_WS_URL] = url
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ws url", e)
        }
    }
    
    suspend fun saveAuthToken(token: String) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_AUTH_TOKEN] = token
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save auth token", e)
        }
    }
    
    suspend fun saveLastConnectedServer(url: String) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_LAST_CONNECTED_SERVER] = url
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save last connected server", e)
        }
    }
    
    suspend fun cacheConfig(configJson: String) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_CACHED_CONFIG] = configJson
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache config", e)
        }
    }
    
    // === Sync getters (SAFE - используют SharedPreferences) ===
    
    /**
     * Безопасный синхронный геттер Device ID
     * Использует SharedPreferences вместо runBlocking + DataStore
     */
    fun getDeviceIdSync(): String? {
        return try {
            prefs.getString("device_id", null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device id sync", e)
            null
        }
    }
    
    /**
     * Безопасный синхронный сеттер Device ID
     */
    fun saveDeviceIdSync(id: String) {
        try {
            prefs.edit().putString("device_id", id).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save device id sync", e)
        }
    }
    
    suspend fun getConfigUrl(): String? {
        return try {
            context.dataStore.data.first()[KEY_CONFIG_URL]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get config url", e)
            null
        }
    }
    
    suspend fun getCachedConfig(): String? {
        return try {
            context.dataStore.data.first()[KEY_CACHED_CONFIG]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cached config", e)
            null
        }
    }
    
    suspend fun getLastConnectedServer(): String? {
        return try {
            context.dataStore.data.first()[KEY_LAST_CONNECTED_SERVER]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get last connected server", e)
            null
        }
    }
    
    suspend fun getAuthTokenOnce(): String? {
        return try {
            context.dataStore.data.first()[KEY_AUTH_TOKEN]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get auth token", e)
            null
        }
    }
    
    suspend fun getServerUrlOnce(): String? {
        return try {
            context.dataStore.data.first()[KEY_SERVER_URL]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get server url", e)
            null
        }
    }
    
    // === Clear ===
    
    suspend fun clearAll() {
        try {
            context.dataStore.edit { preferences ->
                preferences.clear()
            }
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all", e)
        }
    }
    
    suspend fun clearAuthToken() {
        try {
            context.dataStore.edit { preferences ->
                preferences.remove(KEY_AUTH_TOKEN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear auth token", e)
        }
    }
}
