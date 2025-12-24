package com.sphere.agent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.sphere.agent.MainActivity
import com.sphere.agent.R
import com.sphere.agent.SphereAgentApp
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.core.RemoteConfig
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScreenCaptureService - Foreground Service для захвата экрана
 * 
 * Функционал:
 * - MediaProjection API для захвата экрана
 * - Сжатие JPEG с настраиваемым качеством
 * - Ограничение FPS для оптимизации трафика
 * - Масштабирование до max_dimension
 * - Binary streaming через WebSocket
 */

class ScreenCaptureService : Service() {
    
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        
        private const val ACTION_START = "com.sphere.agent.START_CAPTURE"
        private const val ACTION_STOP = "com.sphere.agent.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        // Опциональные override-параметры стрима (по команде start_stream)
        private const val EXTRA_STREAM_QUALITY = "stream_quality"
        private const val EXTRA_STREAM_FPS = "stream_fps"
        
        private var mediaProjection: MediaProjection? = null
        private var resultCode: Int = Activity.RESULT_CANCELED
        private var resultData: Intent? = null
        
        /**
         * Сохранение результата MediaProjection permission
         */
        fun setMediaProjectionResult(code: Int, data: Intent?) {
            resultCode = code
            resultData = data
        }

        fun hasMediaProjectionResult(): Boolean {
            return resultCode == Activity.RESULT_OK && resultData != null
        }
        
        fun startCapture(context: Context, quality: Int? = null, fps: Int? = null) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                quality?.let { putExtra(EXTRA_STREAM_QUALITY, it) }
                fps?.let { putExtra(EXTRA_STREAM_FPS, it) }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopCapture(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    private lateinit var agentConfig: AgentConfig
    private lateinit var connectionManager: ConnectionManager
    
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isCapturing = AtomicBoolean(false)
    private val frameCount = AtomicInteger(0)
    
    // Настройки захвата
    private var captureWidth = 720
    private var captureHeight = 1280
    private var captureQuality = 80
    private var targetFps = 15
    private var lastFrameTime = 0L
    
    override fun onCreate() {
        super.onCreate()
        SphereLog.i(TAG, "Service created")

        // ВАЖНО: используем общие singleton-инстансы, иначе UI и сервис живут в разных мирах
        val app = application as SphereAgentApp
        agentConfig = app.agentConfig
        connectionManager = app.connectionManager

        SphereLog.i(TAG, "Service created. deviceId=${agentConfig.deviceId}")
        
        // Устанавливаем callback для запроса кадров
        connectionManager.onRequestScreenFrame = { captureFrame() }
        
        // Обновляем настройки при изменении конфига
        scope.launch {
            agentConfig.config.collectLatest { config ->
                updateCaptureSettings(config)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SphereLog.i(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                // Возможность переопределить качество/FPS для текущей сессии стрима
                if (intent.hasExtra(EXTRA_STREAM_QUALITY)) {
                    captureQuality = intent.getIntExtra(EXTRA_STREAM_QUALITY, captureQuality)
                }
                if (intent.hasExtra(EXTRA_STREAM_FPS)) {
                    targetFps = intent.getIntExtra(EXTRA_STREAM_FPS, targetFps)
                }
                
                startForeground(NOTIFICATION_ID, createNotification())

                // ВАЖНО: подключаемся к серверу при старте агента,
                // иначе UI показывает "Disconnected" даже при работающем сервисе.
                initializeAgent()
                startCapture(code, data)
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Запуск сервиса без параметров - просто подключаемся
                startForeground(NOTIFICATION_ID, createNotification())
                initializeAgent()
            }
        }
        
        return START_STICKY
    }
    
    private fun initializeAgent() {
        scope.launch {
            try {
                // Загружаем конфигурацию
                SphereLog.i(TAG, "Loading remote config...")
                agentConfig.loadRemoteConfig()

                // Подключаемся к серверу
                SphereLog.i(TAG, "Calling connectionManager.connect()")
                connectionManager.connect()
            } catch (e: Exception) {
                SphereLog.e(TAG, "Failed to initialize agent", e)
            }
        }
    }
    
    private fun startCapture(code: Int, data: Intent?) {
        if (code != Activity.RESULT_OK || data == null) {
            SphereLog.e(TAG, "Invalid MediaProjection result")
            return
        }
        
        if (isCapturing.getAndSet(true)) {
            SphereLog.w(TAG, "Already capturing")
            return
        }
        
        try {
            // Получаем размеры экрана
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            
            // Масштабируем до max_dimension
            val maxDim = agentConfig.config.value.stream.max_dimension
            val scale = minOf(
                maxDim.toFloat() / metrics.widthPixels,
                maxDim.toFloat() / metrics.heightPixels,
                1f
            )
            
            captureWidth = (metrics.widthPixels * scale).toInt()
            captureHeight = (metrics.heightPixels * scale).toInt()
            
            SphereLog.i(TAG, "Capture size: ${captureWidth}x${captureHeight} (scale: $scale)")
            
            // Создаём ImageReader
            imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
            )
            
            // Handler thread для ImageReader
            handlerThread = HandlerThread("ScreenCapture").apply { start() }
            handler = Handler(handlerThread!!.looper)
            
            imageReader?.setOnImageAvailableListener({ reader ->
                handleImage(reader)
            }, handler)
            
            // Создаём MediaProjection
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(code, data)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    SphereLog.i(TAG, "MediaProjection stopped")
                    stopCapture()
                }
            }, handler)
            
            // Создаём VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SphereAgentCapture",
                captureWidth,
                captureHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )
            
            SphereLog.i(TAG, "Screen capture started")
            
            // Обновляем статус стрима для диагностики
            connectionManager.isCurrentlyStreaming = true
            
            // Инициализируем агента
            initializeAgent()
            
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to start capture", e)
            isCapturing.set(false)
        }
    }
    
    private fun handleImage(reader: ImageReader) {
        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        
        if (image == null) return
        
        try {
            // FPS limiting
            val now = System.currentTimeMillis()
            val minFrameInterval = 1000L / targetFps
            
            if (now - lastFrameTime < minFrameInterval) {
                image.close()
                return
            }
            
            lastFrameTime = now
            
            // Конвертируем в JPEG
            val frame = imageToJpeg(image, captureQuality)
            image.close()
            
            if (frame != null && connectionManager.isConnected) {
                connectionManager.sendBinaryFrame(frame)
                frameCount.incrementAndGet()
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error processing image", e)
            try { image.close() } catch (_: Exception) {}
        }
    }
    
    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            // Crop padding
            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            if (croppedBitmap !== bitmap) {
                bitmap.recycle()
            }
            
            val outputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            croppedBitmap.recycle()
            
            outputStream.toByteArray()
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to convert image to JPEG", e)
            null
        }
    }
    
    /**
     * Захват одного кадра по запросу
     */
    private fun captureFrame(): ByteArray? {
        return try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val frame = imageToJpeg(image, captureQuality)
                image.close()
                frame
            } else {
                null
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to capture frame", e)
            null
        }
    }
    
    private fun stopCapture() {
        SphereLog.i(TAG, "Stopping capture")
        
        isCapturing.set(false)
        
        // Обновляем статус стрима
        connectionManager.isCurrentlyStreaming = false
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }
    
    private fun updateCaptureSettings(config: RemoteConfig) {
        captureQuality = config.stream.quality
        targetFps = config.stream.fps
        
        SphereLog.i(TAG, "Updated settings: quality=$captureQuality, fps=$targetFps")
    }
    
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // ВАЖНО: канал должен существовать на Android 8+, иначе startForeground может крэшить.
        return NotificationCompat.Builder(this, SphereAgentApp.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("SphereAgent Active")
            .setContentText("Remote device control enabled")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        SphereLog.i(TAG, "Service destroyed")
        stopCapture()
        // ВАЖНО: ConnectionManager общий (singleton) и живёт в AgentService.
        // Здесь его не отключаем, чтобы агент не пропадал из online.
        connectionManager.onRequestScreenFrame = null
        scope.cancel()
        super.onDestroy()
    }
}
