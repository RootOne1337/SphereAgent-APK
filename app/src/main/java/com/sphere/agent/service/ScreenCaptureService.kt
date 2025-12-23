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
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.sphere.agent.R
import com.sphere.agent.core.AgentConfig
import com.sphere.agent.core.RemoteConfig
import com.sphere.agent.network.ConnectionManager
import com.sphere.agent.network.ServerCommand
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
        private const val CHANNEL_ID = "sphere_service"
        
        private const val ACTION_START = "com.sphere.agent.START_CAPTURE"
        private const val ACTION_STOP = "com.sphere.agent.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        
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
        
        fun startCapture(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
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
    private lateinit var commandExecutor: CommandExecutor
    
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
        Log.d(TAG, "Service created")
        
        agentConfig = AgentConfig(this)
        connectionManager = ConnectionManager(this, agentConfig)
        commandExecutor = CommandExecutor(this)
        
        // Устанавливаем callback для запроса кадров
        connectionManager.onRequestScreenFrame = { captureFrame() }
        
        // Слушаем команды от сервера
        scope.launch {
            connectionManager.commands.collectLatest { command ->
                handleCommand(command)
            }
        }
        
        // Обновляем настройки при изменении конфига
        scope.launch {
            agentConfig.config.collectLatest { config ->
                updateCaptureSettings(config)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                startForeground(NOTIFICATION_ID, createNotification())
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
            // Загружаем конфигурацию
            agentConfig.loadRemoteConfig()
            
            // Подключаемся к серверу
            connectionManager.connect()
        }
    }
    
    private fun startCapture(code: Int, data: Intent?) {
        if (code != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid MediaProjection result")
            return
        }
        
        if (isCapturing.getAndSet(true)) {
            Log.d(TAG, "Already capturing")
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
            
            Log.d(TAG, "Capture size: ${captureWidth}x${captureHeight} (scale: $scale)")
            
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
                    Log.d(TAG, "MediaProjection stopped")
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
            
            Log.d(TAG, "Screen capture started")
            
            // Инициализируем агента
            initializeAgent()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
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
            Log.e(TAG, "Error processing image", e)
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
            Log.e(TAG, "Failed to convert image to JPEG", e)
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
            Log.e(TAG, "Failed to capture frame", e)
            null
        }
    }
    
    private fun stopCapture() {
        Log.d(TAG, "Stopping capture")
        
        isCapturing.set(false)
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        
        connectionManager.disconnect()
    }
    
    private fun updateCaptureSettings(config: RemoteConfig) {
        captureQuality = config.stream.quality
        targetFps = config.stream.fps
        
        Log.d(TAG, "Updated settings: quality=$captureQuality, fps=$targetFps")
    }
    
    private suspend fun handleCommand(command: ServerCommand) {
        Log.d(TAG, "Handling command: ${command.type}")
        
        val result = when (command.type) {
            "tap" -> {
                val x = command.x ?: return
                val y = command.y ?: return
                commandExecutor.tap(x, y)
            }
            "swipe" -> {
                val x1 = command.x ?: return
                val y1 = command.y ?: return
                val x2 = command.x2 ?: return
                val y2 = command.y2 ?: return
                val duration = command.duration ?: 300
                commandExecutor.swipe(x1, y1, x2, y2, duration)
            }
            "key" -> {
                val keyCode = command.keyCode ?: return
                commandExecutor.keyEvent(keyCode)
            }
            "shell" -> {
                val shellCommand = command.command ?: return
                commandExecutor.shell(shellCommand)
            }
            "home" -> commandExecutor.home()
            "back" -> commandExecutor.back()
            "recent" -> commandExecutor.recent()
            "settings_quality" -> {
                command.quality?.let { captureQuality = it }
                CommandResult(true, "Quality set to $captureQuality")
            }
            "settings_fps" -> {
                command.fps?.let { targetFps = it }
                CommandResult(true, "FPS set to $targetFps")
            }
            else -> CommandResult(false, null, "Unknown command: ${command.type}")
        }
        
        // Отправляем результат
        command.command_id?.let { cmdId ->
            connectionManager.sendCommandResult(
                commandId = cmdId,
                success = result.success,
                data = result.data,
                error = result.error
            )
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
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
        Log.d(TAG, "Service destroyed")
        stopCapture()
        connectionManager.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}

data class CommandResult(
    val success: Boolean,
    val data: String? = null,
    val error: String? = null
)
