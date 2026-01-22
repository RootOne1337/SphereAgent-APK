package com.sphere.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sphere.agent.MainActivity
import com.sphere.agent.R
import com.sphere.agent.SphereAgentApp
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RootScreenCaptureService - Захват экрана через ROOT (screencap)
 * 
 * КРИТИЧНО ДЛЯ ENTERPRISE:
 * - Работает БЕЗ MediaProjection permission!
 * - Не требует UI interaction после boot/OTA
 * - Использует su + screencap для захвата
 * - Идеально для 500+ headless эмуляторов
 * 
 * Логика:
 * - Сервер шлёт start_stream → агент начинает capture loop
 * - Сервер шлёт stop_stream → агент останавливает loop
 * - Трафик ТОЛЬКО когда есть viewers!
 */
class RootScreenCaptureService : Service() {
    
    companion object {
        private const val TAG = "RootScreenCapture"
        private const val NOTIFICATION_ID = 1003
        
        private const val ACTION_START = "com.sphere.agent.ROOT_CAPTURE_START"
        private const val ACTION_STOP = "com.sphere.agent.ROOT_CAPTURE_STOP"
        private const val ACTION_RESUME = "com.sphere.agent.ROOT_CAPTURE_RESUME"
        private const val ACTION_PAUSE = "com.sphere.agent.ROOT_CAPTURE_PAUSE"
        
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_FPS = "fps"
        
        // Singleton instance
        @Volatile
        private var instance: RootScreenCaptureService? = null
        
        @Volatile
        var isRunning: Boolean = false
            private set
        
        /**
         * Запуск ROOT capture (в режиме паузы!)
         */
        fun start(context: Context, quality: Int = 85, fps: Int = 15) {
            val intent = Intent(context, RootScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_FPS, fps)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Остановка ROOT capture
         */
        fun stop(context: Context) {
            val intent = Intent(context, RootScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * Пауза стрима (без остановки сервиса) - ЧЕРЕЗ INTENT!
         */
        fun pause(context: Context? = null) {
            if (context != null) {
                val intent = Intent(context, RootScreenCaptureService::class.java).apply {
                    action = ACTION_PAUSE
                }
                context.startService(intent)
            } else {
                instance?.isPaused?.set(true)
            }
            SphereLog.i(TAG, "ROOT capture PAUSE requested")
        }
        
        /**
         * Возобновление стрима - ЧЕРЕЗ INTENT для надёжности!
         */
        fun resume(context: Context? = null, quality: Int? = null, fps: Int? = null) {
            if (context != null) {
                val intent = Intent(context, RootScreenCaptureService::class.java).apply {
                    action = ACTION_RESUME
                    quality?.let { putExtra(EXTRA_QUALITY, it) }
                    fps?.let { putExtra(EXTRA_FPS, it) }
                }
                context.startService(intent)
                SphereLog.i(TAG, "ROOT capture RESUME via Intent (q=$quality, fps=$fps)")
            } else {
                // Fallback на прямой вызов
                instance?.let { svc ->
                    quality?.let { svc.captureQuality = it }
                    fps?.let { svc.targetFps = it }
                    svc.isPaused.set(false)
                    SphereLog.i(TAG, "ROOT capture RESUMED direct (q=$quality, fps=$fps)")
                } ?: SphereLog.w(TAG, "resume() called but instance is null!")
            }
        }
        
        /**
         * v2.15.0: Получить debug-состояние сервиса для диагностики
         */
        fun getDebugState(): Map<String, Any?> {
            val svc = instance
            return mapOf(
                "isRunning" to isRunning,
                "instanceExists" to (svc != null),
                "isPaused" to (svc?.isPaused?.get() ?: "N/A"),
                "isCapturing" to (svc?.isCapturing?.get() ?: "N/A"),
                "captureJobActive" to (svc?.captureJob?.isActive ?: "N/A"),
                "captureQuality" to (svc?.captureQuality ?: "N/A"),
                "targetFps" to (svc?.targetFps ?: "N/A"),
                "connectionManagerExists" to (svc?.let { it::connectionManager.isInitialized } ?: "N/A"),
                "isConnected" to (svc?.let { if (it::connectionManager.isInitialized) it.connectionManager.isConnected else "not init" } ?: "N/A")
            )
        }
    }
    
    private lateinit var connectionManager: ConnectionManager
    private lateinit var agentConfig: AgentConfig
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    
    private val isPaused = AtomicBoolean(true) // Начинаем в паузе - трафик только по запросу!
    private val isCapturing = AtomicBoolean(false)
    
    private var captureQuality = 85  // Высокое качество JPEG (было 70)
    private var targetFps = 15       // Больше FPS для плавности (было 10)
    
    // Путь для временного скриншота
    private val screenshotPath = "/data/local/tmp/sphere_screen.png"
    
    override fun onCreate() {
        super.onCreate()
        SphereLog.i(TAG, "RootScreenCaptureService created")
        
        instance = this
        
        val app = application as SphereAgentApp
        connectionManager = app.connectionManager
        agentConfig = app.agentConfig
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                captureQuality = intent.getIntExtra(EXTRA_QUALITY, 70)
                targetFps = intent.getIntExtra(EXTRA_FPS, 10)
                
                startForeground(NOTIFICATION_ID, createNotification())
                startCaptureLoop()
                isRunning = true
                
                // НЕ снимаем паузу при старте! Пауза снимается по RESUME!
                // isPaused остаётся true - трафик пойдёт только по start_stream
                SphereLog.i(TAG, "ROOT capture started in PAUSED mode (q=$captureQuality, fps=$targetFps)")
            }
            ACTION_RESUME -> {
                // RESUME через Intent - гарантированно работает!
                intent.getIntExtra(EXTRA_QUALITY, -1).let { if (it > 0) captureQuality = it }
                intent.getIntExtra(EXTRA_FPS, -1).let { if (it > 0) targetFps = it }
                isPaused.set(false)
                SphereLog.i(TAG, "ROOT capture RESUMED via Intent (q=$captureQuality, fps=$targetFps, connected=${connectionManager.isConnected}, isCapturing=${isCapturing.get()}, jobActive=${captureJob?.isActive})")
                
                // v2.15.0: Если capture loop не запущен - запускаем!
                if (captureJob == null || captureJob?.isActive != true) {
                    SphereLog.w(TAG, "Capture loop was NOT running! Starting it now...")
                    startCaptureLoop()
                }
            }
            ACTION_PAUSE -> {
                isPaused.set(true)
                SphereLog.i(TAG, "ROOT capture PAUSED via Intent")
            }
            ACTION_STOP -> {
                stopCaptureLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
                SphereLog.i(TAG, "ROOT capture stopped")
            }
        }
        
        return START_STICKY
    }
    
    private fun startCaptureLoop() {
        if (captureJob?.isActive == true) {
            SphereLog.w(TAG, "Capture loop already running")
            return
        }
        
        isCapturing.set(true)
        
        captureJob = scope.launch {
            SphereLog.i(TAG, "Starting ROOT capture loop (isPaused=${isPaused.get()}, connected=${connectionManager.isConnected})")
            
            val frameInterval = 1000L / targetFps
            var lastFrameTime = 0L
            var consecutiveErrors = 0
            var frameCount = 0
            var lastLogTime = System.currentTimeMillis()
            
            while (isActive && isCapturing.get()) {
                try {
                    // v2.15.0: Логируем каждые 5 секунд статус - используем Log.i для гарантии!
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > 5000) {
                        android.util.Log.i(TAG, "LOOP: isPaused=${isPaused.get()}, connected=${connectionManager.isConnected}, frames=$frameCount")
                        lastLogTime = now
                    }
                    
                    // Проверяем паузу - если на паузе, не отправляем кадры
                    if (isPaused.get()) {
                        delay(100)
                        continue
                    }
                    
                    // Проверяем подключение
                    if (!connectionManager.isConnected) {
                        android.util.Log.w(TAG, "NOT CONNECTED, waiting...")
                        delay(500)
                        continue
                    }
                    
                    // FPS limiting
                    val currentTime = System.currentTimeMillis()
                    val elapsed = currentTime - lastFrameTime
                    if (elapsed < frameInterval) {
                        delay(frameInterval - elapsed)
                    }
                    
                    // Захват через ROOT
                    val frame = captureScreenRoot()
                    
                    if (frame != null) {
                        val sent = connectionManager.sendBinaryFrame(frame)
                        if (sent) {
                            frameCount++
                            lastFrameTime = System.currentTimeMillis()
                            consecutiveErrors = 0
                            // v2.15.0: Логируем каждый 30й отправленный фрейм
                            if (frameCount % 30 == 1) {
                                android.util.Log.i(TAG, "FRAME SENT #$frameCount (size=${frame.size})")
                            }
                        } else {
                            // v2.15.0: КРИТИЧНО - логируем КАЖДЫЙ неотправленный фрейм!
                            android.util.Log.e(TAG, "FRAME NOT SENT! size=${frame.size}, connected=${connectionManager.isConnected}")
                        }
                    } else {
                        android.util.Log.w(TAG, "Capture returned NULL")
                        consecutiveErrors++
                        if (consecutiveErrors > 10) {
                            SphereLog.e(TAG, "Too many capture errors, pausing...")
                            delay(1000)
                            consecutiveErrors = 0
                        }
                    }
                    
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SphereLog.e(TAG, "Capture loop error: ${e.message}")
                    delay(500)
                }
            }
            
            SphereLog.i(TAG, "ROOT capture loop ended")
        }
    }
    
    private fun stopCaptureLoop() {
        isCapturing.set(false)
        captureJob?.cancel()
        captureJob = null
        
        // Удаляем временный файл
        try {
            File(screenshotPath).delete()
        } catch (_: Exception) {}
    }
    
    /**
     * Захват экрана через ROOT screencap
     * Возвращает JPEG байты или null при ошибке
     */
    private suspend fun captureScreenRoot(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Делаем скриншот через su
            // КРИТИЧНО v2.15.0: Добавляем chmod 644 чтобы приложение могло прочитать файл!
            // Файл создаётся от ROOT с правами 600, поэтому нужно изменить права
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p $screenshotPath && chmod 644 $screenshotPath"))
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                android.util.Log.w(TAG, "screencap via su failed: $exitCode, trying sh...")
                // Пробуем без su (на некоторых эмуляторах работает)
                val process2 = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $screenshotPath && chmod 644 $screenshotPath"))
                if (process2.waitFor() != 0) {
                    android.util.Log.e(TAG, "screencap via sh also failed!")
                    return@withContext null
                }
            }
            
            // Читаем PNG файл
            val file = File(screenshotPath)
            if (!file.exists()) {
                android.util.Log.e(TAG, "Screenshot file does not exist!")
                return@withContext null
            }
            if (file.length() == 0L) {
                android.util.Log.e(TAG, "Screenshot file is empty!")
                return@withContext null
            }
            
            // Декодируем PNG → JPEG (без масштабирования для качества)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1 // Полное разрешение для качества
            }
            
            val bitmap = BitmapFactory.decodeFile(screenshotPath, options)
            if (bitmap == null) {
                android.util.Log.e(TAG, "BitmapFactory.decodeFile returned NULL! file.length=${file.length()}")
                return@withContext null
            }
            
            // Конвертируем в JPEG
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, captureQuality, outputStream)
            bitmap.recycle()
            
            outputStream.toByteArray()
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ROOT capture error: ${e.message}")
            null
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, SphereAgentApp.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("SphereAgent ROOT Capture")
            .setContentText("Streaming via ROOT (no permission needed)")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        SphereLog.i(TAG, "RootScreenCaptureService destroyed")
        stopCaptureLoop()
        instance = null
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }
}
