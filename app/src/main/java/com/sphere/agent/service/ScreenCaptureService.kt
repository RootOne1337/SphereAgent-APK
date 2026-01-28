package com.sphere.agent.service

import android.app.*
import android.content.Context
import android.content.Intent
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenCaptureService - Enterprise H.264 Video Streaming Service
 * 
 * v3.0.0 ENTERPRISE STREAMING:
 * - Hardware-accelerated H.264 encoding via MediaCodec
 * - Zero-copy from VirtualDisplay → Surface → Encoder
 * - 90%+ bandwidth reduction vs JPEG screenshots
 * - On-demand streaming: starts ONLY when viewer connects
 * - Supports 1000+ devices without overload
 * 
 * Key Architecture:
 * 1. Service starts in foreground (for MediaProjection permission)
 * 2. Connects to WebSocket server
 * 3. Waits for "start_stream" command from server
 * 4. Starts H264 encoder ONLY when viewer is watching
 * 5. Stops encoder on "stop_stream" or viewer disconnect
 * 
 * NO ROOT REQUIRED - standard Android MediaProjection API
 */
class ScreenCaptureService : Service() {
    
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        
        private const val ACTION_START = "com.sphere.agent.START_CAPTURE"
        private const val ACTION_STOP = "com.sphere.agent.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        
        // v3.0.0: Stream command extras
        private const val EXTRA_STREAM_BITRATE = "stream_bitrate"
        private const val EXTRA_STREAM_FPS = "stream_fps"
        
        // MediaProjection state (preserved across service restarts)
        private var resultCode: Int = Activity.RESULT_CANCELED
        private var resultData: Intent? = null
        
        /**
         * Save MediaProjection permission result
         */
        fun setMediaProjectionResult(code: Int, data: Intent?) {
            resultCode = code
            resultData = data
            SphereLog.i(TAG, "MediaProjection result saved: code=\$code")
        }

        fun hasMediaProjectionResult(): Boolean {
            return resultCode == Activity.RESULT_OK && resultData != null
        }
        
        // Singleton instance for stream control
        @Volatile
        private var instance: ScreenCaptureService? = null
        
        /**
         * Start streaming (called when viewer connects)
         * 
         * @param context Application context
         * @param bitrate Video bitrate in bps (default 800Kbps)
         * @param fps Target FPS (default 15)
         */
        fun startStream(context: Context, bitrate: Int? = null, fps: Int? = null): Boolean {
            val svc = instance ?: return false
            
            SphereLog.i(TAG, "📺 startStream called: bitrate=\$bitrate, fps=\$fps")
            
            // Check if we have MediaProjection permission
            if (!hasMediaProjectionResult()) {
                SphereLog.w(TAG, "No MediaProjection permission - cannot start stream")
                return false
            }
            
            // Start encoder if not already running
            return svc.startH264Encoder(bitrate, fps)
        }
        
        /**
         * Stop streaming (called when viewer disconnects)
         */
        fun stopStream(context: Context) {
            val svc = instance ?: return
            SphereLog.i(TAG, "📺 stopStream called")
            svc.stopH264Encoder()
        }
        
        /**
         * Pause stream (no frames, but encoder alive)
         */
        fun pauseStream(context: Context) {
            instance?.h264Encoder?.pause()
            instance?.connectionManager?.isCurrentlyStreaming = false
            SphereLog.i(TAG, "Stream PAUSED")
        }
        
        /**
         * Resume stream (after pause)
         */
        fun resumeStream(context: Context, quality: Int? = null, fps: Int? = null): Boolean {
            val enc = instance?.h264Encoder ?: return false
            enc.resume()
            instance?.connectionManager?.isCurrentlyStreaming = true
            SphereLog.i(TAG, "Stream RESUMED")
            return true
        }
        
        /**
         * Request keyframe for new viewer
         */
        fun requestKeyframe() {
            instance?.h264Encoder?.requestKeyframe()
        }
        
        /**
         * Get SPS/PPS for new viewer initialization
         */
        fun getSpsPps(): ByteArray? {
            return instance?.h264Encoder?.getSpsPps()
        }
        
        /**
         * Check if stream is active
         */
        fun isStreaming(): Boolean {
            return instance?.h264Encoder?.isEncoderRunning ?: false
        }
        
        /**
         * Start foreground service
         */
        fun startService(context: Context) {
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
        
        /**
         * Stop service completely
         */
        fun stopService(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        // Legacy compatibility aliases
        fun startCapture(context: Context, quality: Int? = null, fps: Int? = null) {
            startService(context)
        }
        
        fun stopCapture(context: Context) {
            stopService(context)
        }
    }
    
    // Dependencies (shared singletons from Application)
    private lateinit var agentConfig: AgentConfig
    private lateinit var connectionManager: ConnectionManager
    
    // H.264 Encoder
    private var h264Encoder: H264ScreenEncoder? = null
    private var mediaProjection: MediaProjection? = null
    
    // Handler for callbacks
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // State
    private val isEncoderRunning = AtomicBoolean(false)
    
    // Capture settings (from config)
    private var captureWidth = 720
    private var captureHeight = 1280
    private var captureDpi = 160
    private var targetBitrate = H264ScreenEncoder.QUALITY_MEDIUM
    private var targetFps = 15
    
    override fun onCreate() {
        super.onCreate()
        SphereLog.i(TAG, "Service created")
        
        // Save instance
        instance = this
        
        // Get shared singletons
        val app = application as SphereAgentApp
        agentConfig = app.agentConfig
        connectionManager = app.connectionManager
        
        SphereLog.i(TAG, "Service created. deviceId=\${agentConfig.deviceId}")
        
        // Create handler thread
        handlerThread = HandlerThread("ScreenCaptureHandler").apply { start() }
        handler = Handler(handlerThread!!.looper)
        
        // Listen to config changes
        scope.launch {
            agentConfig.config.collectLatest { config ->
                updateCaptureSettings(config)
            }
        }
        
        // Periodic update check (every 6 hours)
        scope.launch {
            delay(60_000) // Wait 1 minute after start
            val updateManager = UpdateManager(applicationContext)
            updateManager.startPeriodicCheck(scope)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SphereLog.i(TAG, "onStartCommand: \${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                // Get MediaProjection result
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                // Store for later use
                if (code == Activity.RESULT_OK && data != null) {
                    resultCode = code
                    resultData = data
                }
                
                // Start foreground
                startForeground(NOTIFICATION_ID, createNotification())
                
                // Initialize agent (connect to server)
                initializeAgent()
                
                // Calculate screen dimensions for future encoding
                calculateCaptureDimensions()
                
                // v3.0.0: DON'T start encoding here!
                // Wait for "start_stream" command from server
                SphereLog.i(TAG, "Service ready. Waiting for start_stream command...")
            }
            
            ACTION_STOP -> {
                stopH264Encoder()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            
            else -> {
                // Service restart without parameters
                startForeground(NOTIFICATION_ID, createNotification())
                initializeAgent()
            }
        }
        
        return START_STICKY
    }
    
    /**
     * Initialize connection to server
     */
    private fun initializeAgent() {
        scope.launch {
            try {
                if (connectionManager.isConnected) {
                    SphereLog.i(TAG, "Already connected, skipping")
                    return@launch
                }
                
                SphereLog.i(TAG, "Loading remote config...")
                agentConfig.loadRemoteConfig()
                
                if (!connectionManager.isConnected) {
                    SphereLog.i(TAG, "Connecting to server...")
                    connectionManager.connect()
                }
            } catch (e: Exception) {
                SphereLog.e(TAG, "Failed to initialize agent", e)
            }
        }
    }
    
    /**
     * Calculate capture dimensions based on screen size
     */
    private fun calculateCaptureDimensions() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            
            // Scale to max dimension for streaming
            val maxDim = agentConfig.config.value.stream.max_dimension
            val scale = minOf(
                maxDim.toFloat() / metrics.widthPixels,
                maxDim.toFloat() / metrics.heightPixels,
                1f
            )
            
            captureWidth = (metrics.widthPixels * scale).toInt()
            captureHeight = (metrics.heightPixels * scale).toInt()
            captureDpi = metrics.densityDpi
            
            SphereLog.i(TAG, "Capture dimensions: \${captureWidth}x\${captureHeight} @ \${captureDpi}dpi (scale: \$scale)")
            
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to calculate capture dimensions", e)
            // Fallback to defaults
            captureWidth = 720
            captureHeight = 1280
            captureDpi = 160
        }
    }
    
    /**
     * Start H.264 encoder
     * 
     * Called when viewer connects and requests stream.
     */
    private fun startH264Encoder(bitrate: Int? = null, fps: Int? = null): Boolean {
        if (isEncoderRunning.getAndSet(true)) {
            SphereLog.w(TAG, "Encoder already running")
            // But update bitrate/fps if requested
            bitrate?.let { h264Encoder?.updateBitrate(it) }
            // Request keyframe for new viewer
            h264Encoder?.requestKeyframe()
            return true
        }
        
        // Check MediaProjection permission
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            SphereLog.e(TAG, "No MediaProjection permission")
            isEncoderRunning.set(false)
            return false
        }
        
        try {
            SphereLog.i(TAG, "🎬 Starting H.264 encoder...")
            
            // Create MediaProjection
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
            
            if (mediaProjection == null) {
                SphereLog.e(TAG, "Failed to create MediaProjection")
                isEncoderRunning.set(false)
                return false
            }
            
            // Apply bitrate/fps overrides
            val useBitrate = bitrate ?: targetBitrate
            val useFps = fps ?: targetFps
            
            // Create and start H264 encoder
            h264Encoder = H264ScreenEncoder(
                projection = mediaProjection!!,
                width = captureWidth,
                height = captureHeight,
                dpi = captureDpi,
                onEncodedData = { data, isKeyframe, timestamp ->
                    // Send H.264 NAL unit via WebSocket
                    sendH264Frame(data, isKeyframe, timestamp)
                },
                onError = { message, throwable ->
                    SphereLog.e(TAG, "Encoder error: \$message", throwable)
                    stopH264Encoder()
                }
            ).configure(
                bitrate = useBitrate,
                fps = useFps,
                iFrameInterval = 2
            )
            
            val started = h264Encoder!!.start()
            
            if (started) {
                connectionManager.isCurrentlyStreaming = true
                SphereLog.i(TAG, "✅ H.264 encoder started: \${captureWidth}x\${captureHeight} @ \${useBitrate/1000}Kbps, \$useFps FPS")
                return true
            } else {
                SphereLog.e(TAG, "Failed to start encoder")
                isEncoderRunning.set(false)
                return false
            }
            
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to start H264 encoder", e)
            isEncoderRunning.set(false)
            return false
        }
    }
    
    /**
     * Stop H.264 encoder
     * 
     * Called when viewer disconnects or stop_stream command received.
     */
    private fun stopH264Encoder() {
        if (!isEncoderRunning.getAndSet(false)) {
            return
        }
        
        SphereLog.i(TAG, "🛑 Stopping H.264 encoder...")
        
        try {
            h264Encoder?.stop()
            h264Encoder = null
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error stopping encoder", e)
        }
        
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error stopping MediaProjection", e)
        }
        
        connectionManager.isCurrentlyStreaming = false
        SphereLog.i(TAG, "✅ H.264 encoder stopped")
    }
    
    /**
     * Send H.264 frame via WebSocket
     * 
     * Frame format for H.264:
     * [1 byte: flags][4 bytes: timestamp][N bytes: NAL data]
     * 
     * Flags:
     * - bit 0: isKeyframe (1 = I-frame)
     * - bit 1: hasSPS (1 = includes SPS/PPS)
     */
    private fun sendH264Frame(data: ByteArray, isKeyframe: Boolean, timestampUs: Long) {
        // Only send if connected and streaming
        if (!connectionManager.isConnected || !connectionManager.isCurrentlyStreaming) {
            return
        }
        
        try {
            // Create frame header
            // Format: [1 byte flags][4 bytes timestamp][data]
            val flags = (if (isKeyframe) 0x01 else 0x00) or 
                       (if (isSpsPps(data)) 0x02 else 0x00)
            
            val timestampMs = (timestampUs / 1000).toInt()
            
            val frame = ByteArray(5 + data.size)
            frame[0] = flags.toByte()
            frame[1] = (timestampMs shr 24).toByte()
            frame[2] = (timestampMs shr 16).toByte()
            frame[3] = (timestampMs shr 8).toByte()
            frame[4] = timestampMs.toByte()
            System.arraycopy(data, 0, frame, 5, data.size)
            
            // Send via WebSocket
            connectionManager.sendBinaryFrame(frame)
            
        } catch (e: Exception) {
            SphereLog.e(TAG, "Error sending H264 frame", e)
        }
    }
    
    /**
     * Check if data is SPS/PPS
     */
    private fun isSpsPps(data: ByteArray): Boolean {
        if (data.size < 5) return false
        
        // Find start code offset
        val offset = when {
            data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1 -> 3
            data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1 -> 4
            else -> return false
        }
        
        if (offset >= data.size) return false
        
        val nalType = data[offset].toInt() and 0x1F
        return nalType == 7 || nalType == 8  // SPS or PPS
    }
    
    /**
     * Update capture settings from config
     */
    private fun updateCaptureSettings(config: RemoteConfig) {
        // Map quality (0-100) to bitrate
        targetBitrate = when {
            config.stream.quality < 30 -> H264ScreenEncoder.QUALITY_ULTRA_LOW
            config.stream.quality < 50 -> H264ScreenEncoder.QUALITY_LOW
            config.stream.quality < 70 -> H264ScreenEncoder.QUALITY_MEDIUM
            config.stream.quality < 90 -> H264ScreenEncoder.QUALITY_HIGH
            else -> H264ScreenEncoder.QUALITY_ULTRA
        }
        
        targetFps = config.stream.fps
        
        SphereLog.i(TAG, "Updated settings: bitrate=\${targetBitrate/1000}Kbps, fps=\$targetFps")
        
        // Update running encoder if any
        h264Encoder?.updateBitrate(targetBitrate)
    }
    
    /**
     * Create foreground notification
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
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
        
        stopH264Encoder()
        
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        
        instance = null
        scope.cancel()
        
        super.onDestroy()
    }
}
