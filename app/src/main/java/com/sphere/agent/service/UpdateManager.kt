package com.sphere.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.sphere.agent.BuildConfig
import com.sphere.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UpdateManager - Automatic APK updates for fleet management
 * 
 * Функции:
 * - Периодическая проверка новых версий (каждые 6 часов)
 * - Скачивание APK в background
 * - Уведомление пользователя о доступном обновлении
 * - Запуск установки через Intent
 * 
 * Для 500+ устройств: при публикации новой версии все устройства
 * автоматически скачают и покажут установщик
 */
@Singleton
class UpdateManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
        private const val UPDATE_CHANNEL_ID = "sphere_updates"
        private const val UPDATE_NOTIFICATION_ID = 1337
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var isChecking = false

    init {
        createNotificationChannel()
    }

    /**
     * Запустить периодическую проверку обновлений
     */
    fun startPeriodicCheck(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    checkForUpdates()
                } catch (e: Exception) {
                    Log.e(TAG, "Update check failed", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Проверить наличие новой версии
     */
    suspend fun checkForUpdates() {
        if (isChecking) return
        isChecking = true

        try {
            // Получаем URL сервера из SharedPreferences
            val serverUrl = getServerUrl() ?: run {
                Log.w(TAG, "No server URL configured")
                return
            }

            val versionUrl = "$serverUrl/api/v1/agent/updates/version"
            Log.d(TAG, "Checking updates from: $versionUrl")

            val request = Request.Builder()
                .url(versionUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Update check failed: ${response.code}")
                return
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val latestVersion = json.optString("version", "")
            val downloadUrl = json.optString("url", "")
            val changelog = json.optString("changelog", "New features and improvements")
            val forceUpdate = json.optBoolean("force_update", false)

            val currentVersion = BuildConfig.VERSION_NAME
            Log.d(TAG, "Current: $currentVersion, Latest: $latestVersion")

            if (latestVersion.isNotEmpty() && isNewerVersion(latestVersion, currentVersion)) {
                Log.i(TAG, "Update available: $latestVersion (current: $currentVersion)")
                
                // Скачиваем APK
                val fullUrl = if (downloadUrl.startsWith("http")) {
                    downloadUrl
                } else {
                    "$serverUrl$downloadUrl"
                }
                
                val apkFile = downloadApk(fullUrl, latestVersion)
                if (apkFile != null) {
                    // v2.2.0: Тихая установка для Enterprise
                    if (BuildConfig.AUTO_UPDATE_ENABLED && hasRootAccess()) {
                        Log.i(TAG, "Attempting silent install...")
                        if (silentInstallViaRoot(apkFile.absolutePath)) {
                            Log.i(TAG, "Silent update successful!")
                            return
                        }
                    }
                    
                    showUpdateNotification(latestVersion, changelog, apkFile, forceUpdate)
                }
            } else {
                Log.d(TAG, "Already on latest version")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking updates", e)
        } finally {
            isChecking = false
        }
    }

    /**
     * Проверка наличия ROOT доступа
     */
    private fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            exitCode == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Тихая установка через ROOT
     */
    private fun silentInstallViaRoot(apkPath: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", "pm install -r -d \"$apkPath\""
            ))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Скачать APK файл
     */
    private suspend fun downloadApk(url: String, version: String): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading APK from: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: ${response.code}")
                return@withContext null
            }

            // Сохраняем во внутреннее хранилище
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            val apkFile = File(cacheDir, "sphere_agent_$version.apk")

            response.body?.byteStream()?.use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "APK downloaded: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            apkFile

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK", e)
            null
        }
    }

    /**
     * Показать уведомление о доступном обновлении
     */
    private suspend fun showUpdateNotification(
        version: String,
        changelog: String,
        apkFile: File,
        forceUpdate: Boolean
    ) = withContext(Dispatchers.Main) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Intent для установки APK
        val installIntent = createInstallIntent(apkFile)

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Замените на свою иконку
            .setContentTitle("SphereAgent Update Available")
            .setContentText("Version $version is ready to install")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Version $version\n$changelog\n\nTap to install."
            ))
            .setPriority(if (forceUpdate) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    installIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
        Log.i(TAG, "Update notification shown for version $version")
    }

    /**
     * Создать Intent для установки APK
     */
    private fun createInstallIntent(apkFile: File): Intent {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Для Android 7.0+ используем FileProvider
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Проверить, новее ли версия (простое сравнение строк)
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrNull(i) ?: 0
                val c = currentParts.getOrNull(i) ?: 0
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
            false
        }
    }

    /**
     * Получить URL сервера из конфигурации
     */
    private fun getServerUrl(): String? {
        // Используем BuildConfig для получения дефолтного URL
        return try {
            com.sphere.agent.BuildConfig.DEFAULT_SERVER_URL.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting server URL", e)
            null
        }
    }

    /**
     * Создать notification channel для обновлений
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SphereAgent Updates"
            val descriptionText = "Notifications for available app updates"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(UPDATE_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
