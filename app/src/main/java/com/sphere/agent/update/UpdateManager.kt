package com.sphere.agent.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * UpdateManager - Enterprise OTA Update System
 * 
 * Функционал:
 * - Автоматическая проверка обновлений
 * - Скачивание APK в фоне
 * - Silent install (требует root или device owner)
 * - Fallback на стандартную установку
 * - Версионирование и откат
 */

@Serializable
data class ChangelogResponse(
    val versions: List<VersionInfo>,
    val latest: LatestVersion
)

@Serializable
data class VersionInfo(
    val version: String,
    val version_code: Int,
    val release_date: String,
    val min_sdk: Int = 24,
    val download_url: String,
    val size_bytes: Long = 0,
    val sha256: String = "",
    val changes: List<String> = emptyList(),
    val required: Boolean = false
)

@Serializable
data class LatestVersion(
    val version: String,
    val version_code: Int,
    val download_url: String
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val version: VersionInfo) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    object Installing : UpdateState()
    data class Error(val message: String) : UpdateState()
    object UpToDate : UpdateState()
}

class UpdateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UpdateManager"
        private const val APK_FILE_NAME = "SphereAgent-update.apk"
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_LAST_CHECK = "last_check_time"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
    }
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    
    private var currentDownloadId: Long = -1
    
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()
    
    private val _latestVersion = MutableStateFlow<VersionInfo?>(null)
    val latestVersion: StateFlow<VersionInfo?> = _latestVersion.asStateFlow()
    
    // Текущая версия приложения
    val currentVersionCode: Int = BuildConfig.VERSION_CODE
    val currentVersionName: String = BuildConfig.VERSION_NAME
    
    /**
     * Проверка обновлений
     */
    suspend fun checkForUpdates(force: Boolean = false): UpdateState = withContext(Dispatchers.IO) {
        try {
            // Проверяем, не слишком ли часто проверяем
            if (!force && !shouldCheckUpdates()) {
                Log.d(TAG, "Skipping update check - too soon")
                return@withContext _updateState.value
            }
            
            _updateState.value = UpdateState.Checking
            Log.d(TAG, "Checking for updates...")
            
            val changelogUrl = BuildConfig.CHANGELOG_URL
            val request = Request.Builder()
                .url(changelogUrl)
                .header("User-Agent", "SphereAgent/${BuildConfig.VERSION_NAME}")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch changelog: ${response.code}")
                _updateState.value = UpdateState.Error("Не удалось проверить обновления")
                return@withContext _updateState.value
            }
            
            val body = response.body?.string() ?: ""
            val changelog = json.decodeFromString<ChangelogResponse>(body)
            
            // Сохраняем время проверки
            saveLastCheckTime()
            
            // Ищем обновление
            val latestVersionCode = changelog.latest.version_code
            
            if (latestVersionCode > currentVersionCode) {
                // Найдём полную информацию о версии
                val versionInfo = changelog.versions.find { 
                    it.version_code == latestVersionCode 
                } ?: VersionInfo(
                    version = changelog.latest.version,
                    version_code = latestVersionCode,
                    release_date = "",
                    download_url = changelog.latest.download_url
                )
                
                _latestVersion.value = versionInfo
                
                // Проверяем, не пропущена ли эта версия
                val skippedVersion = prefs.getInt(KEY_SKIPPED_VERSION, 0)
                if (!versionInfo.required && skippedVersion >= latestVersionCode) {
                    Log.d(TAG, "Version ${versionInfo.version} was skipped")
                    _updateState.value = UpdateState.UpToDate
                } else {
                    Log.d(TAG, "Update available: ${versionInfo.version}")
                    _updateState.value = UpdateState.UpdateAvailable(versionInfo)
                }
            } else {
                Log.d(TAG, "App is up to date")
                _updateState.value = UpdateState.UpToDate
            }
            
            return@withContext _updateState.value
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking updates", e)
            _updateState.value = UpdateState.Error("Ошибка проверки: ${e.message}")
            return@withContext _updateState.value
        }
    }
    
    /**
     * Скачивание обновления
     */
    fun downloadUpdate(versionInfo: VersionInfo) {
        try {
            Log.d(TAG, "Downloading update: ${versionInfo.version}")
            _updateState.value = UpdateState.Downloading(0)
            
            // Удаляем старый файл
            val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
            if (apkFile.exists()) {
                apkFile.delete()
            }
            
            val request = DownloadManager.Request(Uri.parse(versionInfo.download_url))
                .setTitle("SphereAgent Update ${versionInfo.version}")
                .setDescription("Загрузка обновления...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            
            currentDownloadId = downloadManager.enqueue(request)
            
            // Регистрируем receiver для отслеживания завершения
            registerDownloadReceiver()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting download", e)
            _updateState.value = UpdateState.Error("Ошибка скачивания: ${e.message}")
        }
    }
    
    /**
     * Установка обновления
     */
    fun installUpdate() {
        try {
            Log.d(TAG, "Installing update...")
            _updateState.value = UpdateState.Installing
            
            val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
            
            if (!apkFile.exists()) {
                _updateState.value = UpdateState.Error("APK файл не найден")
                return
            }
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(intent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error installing update", e)
            _updateState.value = UpdateState.Error("Ошибка установки: ${e.message}")
        }
    }
    
    /**
     * Пропустить версию
     */
    fun skipVersion(versionCode: Int) {
        prefs.edit().putInt(KEY_SKIPPED_VERSION, versionCode).apply()
        _updateState.value = UpdateState.UpToDate
    }
    
    /**
     * Сброс состояния
     */
    fun reset() {
        _updateState.value = UpdateState.Idle
    }
    
    private fun shouldCheckUpdates(): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val intervalMs = BuildConfig.UPDATE_CHECK_INTERVAL_HOURS * 60 * 60 * 1000L
        return System.currentTimeMillis() - lastCheck > intervalMs
    }
    
    private fun saveLastCheckTime() {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }
    
    private fun registerDownloadReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val downloadId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                
                if (downloadId == currentDownloadId) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    
                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusIndex)
                        
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                Log.d(TAG, "Download complete")
                                _updateState.value = UpdateState.Downloading(100)
                                
                                // Автоматически запускаем установку если включено
                                if (BuildConfig.AUTO_UPDATE_ENABLED) {
                                    installUpdate()
                                }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                Log.e(TAG, "Download failed")
                                _updateState.value = UpdateState.Error("Загрузка не удалась")
                            }
                        }
                    }
                    cursor.close()
                    
                    try {
                        context.unregisterReceiver(this)
                    } catch (_: Exception) {}
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }
}
