package com.sphere.agent.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import com.sphere.agent.service.AgentService

/**
 * ENTERPRISE: Boot Content Provider
 * 
 * ContentProvider.onCreate() вызывается ОЧЕНЬ РАНО - даже раньше Application.onCreate()!
 * Это ГАРАНТИРУЕТ что наш код выполнится при любом обращении к приложению.
 * 
 * Android вызывает ContentProvider при:
 * - Запуске приложения
 * - BOOT_COMPLETED (если есть BroadcastReceiver)
 * - Любом Intent к приложению
 * - WorkManager scheduled task
 * - AlarmManager alarm
 * 
 * Это самый надёжный способ автозапуска!
 */
class BootContentProvider : ContentProvider() {
    
    companion object {
        private const val TAG = "BootContentProvider"
        
        @Volatile
        private var initialized = false
    }
    
    override fun onCreate(): Boolean {
        Log.d(TAG, "=== BootContentProvider.onCreate() ===")
        Log.d(TAG, "This is called VERY EARLY in app lifecycle!")
        
        if (initialized) {
            Log.d(TAG, "Already initialized, skipping")
            return true
        }
        
        initialized = true
        
        // Запускаем сервис в отдельном потоке чтобы не блокировать onCreate
        Thread {
            try {
                Log.d(TAG, "Starting AgentService from ContentProvider...")
                
                // Небольшая задержка для стабильности
                Thread.sleep(2000)
                
                val context = context ?: return@Thread
                
                // Запускаем AgentService
                val intent = Intent(context, AgentService::class.java).apply {
                    action = AgentService.ACTION_START
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
                Log.d(TAG, "AgentService start command sent from ContentProvider!")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AgentService from ContentProvider", e)
                
                // Retry через 5 секунд
                try {
                    Thread.sleep(5000)
                    val context = context ?: return@Thread
                    val intent = Intent(context, AgentService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    Log.d(TAG, "AgentService started on retry from ContentProvider")
                } catch (e2: Exception) {
                    Log.e(TAG, "Retry also failed", e2)
                }
            }
        }.start()
        
        return true
    }
    
    // Остальные методы - заглушки, нам нужен только onCreate()
    
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null
    
    override fun getType(uri: Uri): String? = null
    
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
