package com.sphere.agent.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sphere.agent.MainActivity
import com.sphere.agent.R

/**
 * UpdateNotificationHelper - Уведомления об обновлениях
 * 
 * Показывает пользователю уведомления о доступных обновлениях
 * с возможностью быстрого действия (скачать/установить).
 */
class UpdateNotificationHelper(private val context: Context) {
    
    companion object {
        const val CHANNEL_ID = "sphere_updates"
        const val NOTIFICATION_ID = 9999
    }
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновления SphereAgent",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о доступных обновлениях"
                enableVibration(true)
            }
            
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Показать уведомление о доступном обновлении
     */
    fun showUpdateAvailable(version: VersionInfo) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "update")
            putExtra("version", version.version)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val changesText = version.changes.take(3).joinToString("\n• ", prefix = "• ")
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Доступно обновление ${version.version}")
            .setContentText(if (version.required) "Обязательное обновление" else "Нажмите для установки")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Версия ${version.version}\n\nИзменения:\n$changesText"))
            .setPriority(if (version.required) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_upload,
                "Обновить",
                pendingIntent
            )
            .build()
        
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Показать прогресс скачивания
     */
    fun showDownloadProgress(progress: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Скачивание обновления...")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
        
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Показать уведомление о готовности к установке
     */
    fun showReadyToInstall(version: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "install")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Обновление готово")
            .setContentText("SphereAgent $version готов к установке")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_upload,
                "Установить",
                pendingIntent
            )
            .build()
        
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Скрыть уведомление
     */
    fun dismiss() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
