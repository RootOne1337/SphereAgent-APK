package com.sphere.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H264RootStreamService - Реальный H.264 видеопоток через ROOT
 * 
 * v3.1.0 ENTERPRISE H.264 STREAMING:
 * - Использует screenrecord --output-format=h264 через ROOT
 * - Аппаратное H.264 кодирование (GPU/VPU)
 * - Минимальный трафик: ~150 Kbps vs 1.5 Mbps JPEG
 * - Минимальная задержка: 20-50ms
 * - На frontend декодируется через WebCodecs VideoDecoder
 * 
 * Архитектура:
 * ROOT shell → screenrecord → H.264 NAL units → WebSocket → Browser WebCodecs
 * 
 * КРИТИЧНО:
 * - Стрим ТОЛЬКО когда есть viewer (isPaused=false)
 * - Нет трафика когда нет viewers (isPaused=true)
 * - Один процесс screenrecord на устройство
 */
class H264RootStreamService : Service() {
    
    companion object {
        private const val TAG = "H264RootStream"
        private const val NOTIFICATION_ID = 1004
        
        private const val ACTION_START = "com.sphere.agent.H264_STREAM_START"
        private const val ACTION_STOP = "com.sphere.agent.H264_STREAM_STOP"
        private const val ACTION_RESUME = "com.sphere.agent.H264_STREAM_RESUME"
        private const val ACTION_PAUSE = "com.sphere.agent.H264_STREAM_PAUSE"
        
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_FPS = "fps"
        
        // Singleton instance
        @Volatile
        private var instance: H264RootStreamService? = null
        
        @Volatile
        var isRunning: Boolean = false
            private set
        
        // Приоритетные размеры стрима (меньше = меньше трафик + меньше latency)
        private const val DEFAULT_WIDTH = 720
        private const val DEFAULT_HEIGHT = 1280
        private const val DEFAULT_BITRATE = 800_000  // 800 Kbps
        private const val DEFAULT_FPS = 30
        
        /**
         * Запуск H.264 стрима (в режиме паузы)
         */
        fun start(context: Context, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT, 
                  bitrate: Int = DEFAULT_BITRATE, fps: Int = DEFAULT_FPS) {
            val intent = Intent(context, H264RootStreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_BITRATE, bitrate)
                putExtra(EXTRA_FPS, fps)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Остановка H.264 стрима
         */
        fun stop(context: Context) {
            val intent = Intent(context, H264RootStreamService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * Пауза стрима (убивает screenrecord процесс для экономии ресурсов)
         */
        fun pause(context: Context? = null) {
            if (context != null) {
                val intent = Intent(context, H264RootStreamService::class.java).apply {
                    action = ACTION_PAUSE
                }
                context.startService(intent)
            } else {
                instance?.pauseStream()
            }
            SphereLog.i(TAG, "H.264 stream PAUSE requested")
        }
        
        /**
         * Возобновление стрима (запускает screenrecord)
         */
        fun resume(context: Context? = null, width: Int? = null, height: Int? = null,
                   bitrate: Int? = null, fps: Int? = null) {
            if (context != null) {
                val intent = Intent(context, H264RootStreamService::class.java).apply {
                    action = ACTION_RESUME
                    width?.let { putExtra(EXTRA_WIDTH, it) }
                    height?.let { putExtra(EXTRA_HEIGHT, it) }
                    bitrate?.let { putExtra(EXTRA_BITRATE, it) }
                    fps?.let { putExtra(EXTRA_FPS, it) }
                }
                context.startService(intent)
            } else {
                instance?.resumeStream(width, height, bitrate, fps)
            }
        }
        
        /**
         * Проверка поддержки screenrecord H.264
         */
        fun checkH264Support(): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screenrecord --help 2>&1 | grep -q 'output-format'"))
                process.waitFor() == 0
            } catch (e: Exception) {
                SphereLog.w(TAG, "H.264 check failed: ${e.message}")
                false
            }
        }
        
        /**
         * Debug состояние
         */
        fun getDebugState(): Map<String, Any?> {
            val svc = instance
            return mapOf(
                "isRunning" to isRunning,
                "instanceExists" to (svc != null),
                "isPaused" to (svc?.isPaused?.get() ?: "N/A"),
                "isStreaming" to (svc?.isStreaming?.get() ?: "N/A"),
                "streamWidth" to (svc?.streamWidth ?: "N/A"),
                "streamHeight" to (svc?.streamHeight ?: "N/A"),
                "streamBitrate" to (svc?.streamBitrate ?: "N/A"),
                "streamFps" to (svc?.streamFps ?: "N/A"),
                "frameCount" to (svc?.frameCount ?: "N/A"),
                "bytesSent" to (svc?.bytesSent ?: "N/A")
            )
        }
    }
    
    private lateinit var connectionManager: ConnectionManager
    private lateinit var agentConfig: AgentConfig
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    private var screenrecordProcess: Process? = null
    
    private val isPaused = AtomicBoolean(true)  // Начинаем в паузе!
    private val isStreaming = AtomicBoolean(false)
    
    // Stream параметры
    private var streamWidth = DEFAULT_WIDTH
    private var streamHeight = DEFAULT_HEIGHT
    private var streamBitrate = DEFAULT_BITRATE
    private var streamFps = DEFAULT_FPS
    
    // Статистика
    private var frameCount = 0L
    private var bytesSent = 0L
    private var startTime = 0L
    
    override fun onCreate() {
        super.onCreate()
        SphereLog.i(TAG, "H264RootStreamService created")
        
        instance = this
        
        val app = application as SphereAgentApp
        connectionManager = app.connectionManager
        agentConfig = app.agentConfig
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                streamWidth = intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH)
                streamHeight = intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT)
                streamBitrate = intent.getIntExtra(EXTRA_BITRATE, DEFAULT_BITRATE)
                streamFps = intent.getIntExtra(EXTRA_FPS, DEFAULT_FPS)
                
                startForeground(NOTIFICATION_ID, createNotification())
                isRunning = true
                
                // НЕ запускаем стрим при старте! Только по RESUME
                SphereLog.i(TAG, "H.264 service started in PAUSED mode (${streamWidth}x${streamHeight}, ${streamBitrate/1000}Kbps, ${streamFps}fps)")
            }
            ACTION_RESUME -> {
                intent.getIntExtra(EXTRA_WIDTH, -1).let { if (it > 0) streamWidth = it }
                intent.getIntExtra(EXTRA_HEIGHT, -1).let { if (it > 0) streamHeight = it }
                intent.getIntExtra(EXTRA_BITRATE, -1).let { if (it > 0) streamBitrate = it }
                intent.getIntExtra(EXTRA_FPS, -1).let { if (it > 0) streamFps = it }
                
                resumeStream(streamWidth, streamHeight, streamBitrate, streamFps)
            }
            ACTION_PAUSE -> {
                pauseStream()
            }
            ACTION_STOP -> {
                stopStream()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
                SphereLog.i(TAG, "H.264 service stopped")
            }
        }
        
        return START_STICKY
    }
    
    /**
     * Запуск H.264 стрима через screenrecord
     */
    private fun resumeStream(width: Int? = null, height: Int? = null, 
                              bitrate: Int? = null, fps: Int? = null) {
        width?.let { streamWidth = it }
        height?.let { streamHeight = it }
        bitrate?.let { streamBitrate = it }
        fps?.let { streamFps = it }
        
        if (isStreaming.get()) {
            SphereLog.w(TAG, "Stream already running")
            return
        }
        
        isPaused.set(false)
        isStreaming.set(true)
        startTime = System.currentTimeMillis()
        frameCount = 0
        bytesSent = 0
        
        streamJob = scope.launch {
            startScreenrecordStream()
        }
        
        connectionManager.isCurrentlyStreaming = true
        SphereLog.i(TAG, "✅ H.264 stream RESUMED (${streamWidth}x${streamHeight}, ${streamBitrate/1000}Kbps)")
    }
    
    /**
     * Пауза H.264 стрима (убивает процесс)
     */
    private fun pauseStream() {
        isPaused.set(true)
        isStreaming.set(false)
        
        // Убиваем screenrecord процесс
        try {
            screenrecordProcess?.destroyForcibly()
            screenrecordProcess = null
        } catch (e: Exception) {
            SphereLog.w(TAG, "Error killing screenrecord: ${e.message}")
        }
        
        streamJob?.cancel()
        streamJob = null
        
        connectionManager.isCurrentlyStreaming = false
        
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val avgFps = if (elapsed > 0) frameCount / elapsed else 0.0
        val avgBitrate = if (elapsed > 0) (bytesSent * 8 / elapsed / 1000).toLong() else 0L
        
        SphereLog.i(TAG, "H.264 stream PAUSED. Stats: frames=$frameCount, bytes=$bytesSent, avgFps=${avgFps.toInt()}, avgKbps=$avgBitrate")
    }
    
    /**
     * Полная остановка стрима
     */
    private fun stopStream() {
        pauseStream()
    }
    
    /**
     * Основной цикл чтения H.264 данных из screenrecord
     */
    private suspend fun startScreenrecordStream() = withContext(Dispatchers.IO) {
        SphereLog.i(TAG, "Starting screenrecord H.264 stream...")
        
        try {
            // Формируем команду screenrecord
            // --output-format=h264 выводит сырой H.264 поток без контейнера
            // - (дефис) означает вывод в stdout
            val cmd = arrayOf(
                "su", "-c",
                "screenrecord " +
                "--output-format=h264 " +
                "--size ${streamWidth}x${streamHeight} " +
                "--bit-rate $streamBitrate " +
                "--time-limit 180 " +  // 3 минуты макс, потом перезапуск
                "-"
            )
            
            SphereLog.i(TAG, "Executing: ${cmd.joinToString(" ")}")
            
            val processBuilder = ProcessBuilder(*cmd)
            processBuilder.redirectErrorStream(false)  // Отдельный stderr
            
            screenrecordProcess = processBuilder.start()
            val inputStream = screenrecordProcess!!.inputStream
            
            // Буфер для чтения NAL units
            val buffer = ByteArray(65536)  // 64KB буфер
            var nalBuffer = ByteArray(0)
            var lastNalSendTime = System.currentTimeMillis()
            var consecutiveErrors = 0
            
            SphereLog.i(TAG, "screenrecord process started, reading H.264 stream...")
            
            while (isActive && isStreaming.get() && !isPaused.get()) {
                try {
                    val bytesRead = inputStream.read(buffer)
                    
                    if (bytesRead <= 0) {
                        // Процесс завершился или ошибка
                        if (bytesRead < 0) {
                            SphereLog.w(TAG, "screenrecord stream ended (EOF)")
                            
                            // Перезапуск если не на паузе
                            if (!isPaused.get() && isStreaming.get()) {
                                delay(500)
                                restartScreenrecord()
                            }
                            break
                        }
                        continue
                    }
                    
                    consecutiveErrors = 0
                    
                    // Добавляем в NAL буфер
                    nalBuffer += buffer.copyOf(bytesRead)
                    
                    // Парсим NAL units из буфера и отправляем
                    val nalUnits = extractNalUnits(nalBuffer)
                    
                    for ((nalData, remainingBuffer) in nalUnits) {
                        nalBuffer = remainingBuffer
                        
                        // Формируем пакет для отправки
                        // Формат: [flags(1)][timestamp(4)][NAL data]
                        val isKeyframe = isKeyframeNal(nalData)
                        val timestamp = (System.currentTimeMillis() - startTime).toInt()
                        
                        val packet = ByteBuffer.allocate(1 + 4 + nalData.size)
                        packet.put(if (isKeyframe) 0x01.toByte() else 0x00.toByte())
                        packet.putInt(timestamp)
                        packet.put(nalData)
                        
                        // Отправляем через WebSocket
                        val sent = connectionManager.sendBinaryFrame(packet.array())
                        if (sent) {
                            frameCount++
                            bytesSent += packet.capacity()
                            lastNalSendTime = System.currentTimeMillis()
                            
                            // Логируем каждый 30й кадр
                            if (frameCount % 30 == 1L) {
                                android.util.Log.i(TAG, "NAL sent #$frameCount: ${nalData.size} bytes, keyframe=$isKeyframe")
                            }
                        }
                    }
                    
                } catch (e: java.io.IOException) {
                    SphereLog.e(TAG, "IO error reading stream: ${e.message}")
                    consecutiveErrors++
                    if (consecutiveErrors > 5) {
                        SphereLog.e(TAG, "Too many errors, restarting screenrecord...")
                        delay(1000)
                        restartScreenrecord()
                        break
                    }
                    delay(100)
                }
            }
            
        } catch (e: Exception) {
            SphereLog.e(TAG, "H.264 stream error: ${e.message}", e)
        } finally {
            try {
                screenrecordProcess?.destroyForcibly()
                screenrecordProcess = null
            } catch (_: Exception) {}
        }
    }
    
    /**
     * Перезапуск screenrecord процесса
     */
    private suspend fun restartScreenrecord() {
        try {
            screenrecordProcess?.destroyForcibly()
        } catch (_: Exception) {}
        screenrecordProcess = null
        
        if (!isPaused.get() && isStreaming.get()) {
            SphereLog.i(TAG, "Restarting screenrecord...")
            startScreenrecordStream()
        }
    }
    
    /**
     * Извлечение NAL units из буфера
     * NAL unit начинается с 0x00 0x00 0x00 0x01 или 0x00 0x00 0x01
     */
    private fun extractNalUnits(buffer: ByteArray): List<Pair<ByteArray, ByteArray>> {
        val results = mutableListOf<Pair<ByteArray, ByteArray>>()
        var pos = 0
        var lastNalStart = -1
        
        while (pos < buffer.size - 4) {
            // Ищем start code: 0x00 0x00 0x00 0x01 или 0x00 0x00 0x01
            val is4ByteStartCode = buffer[pos] == 0.toByte() && 
                                    buffer[pos + 1] == 0.toByte() && 
                                    buffer[pos + 2] == 0.toByte() && 
                                    buffer[pos + 3] == 1.toByte()
            
            val is3ByteStartCode = buffer[pos] == 0.toByte() && 
                                    buffer[pos + 1] == 0.toByte() && 
                                    buffer[pos + 2] == 1.toByte()
            
            if (is4ByteStartCode || is3ByteStartCode) {
                val startCodeLen = if (is4ByteStartCode) 4 else 3
                
                if (lastNalStart >= 0) {
                    // Извлекаем предыдущий NAL unit
                    val nalData = buffer.copyOfRange(lastNalStart, pos)
                    results.add(nalData to buffer.copyOfRange(pos, buffer.size))
                }
                
                lastNalStart = pos
                pos += startCodeLen
            } else {
                pos++
            }
        }
        
        // Если есть неполный NAL в конце - оставляем в буфере
        if (lastNalStart >= 0 && results.isEmpty()) {
            // Ещё не нашли конец NAL - оставляем весь буфер
            return listOf(ByteArray(0) to buffer)
        }
        
        return if (results.isEmpty()) {
            listOf(ByteArray(0) to buffer)
        } else {
            results
        }
    }
    
    /**
     * Проверка, является ли NAL unit ключевым кадром (IDR)
     */
    private fun isKeyframeNal(nalData: ByteArray): Boolean {
        if (nalData.size < 5) return false
        
        // Находим первый байт после start code
        var pos = 0
        if (nalData[0] == 0.toByte() && nalData[1] == 0.toByte()) {
            pos = if (nalData[2] == 0.toByte() && nalData[3] == 1.toByte()) 4 else 3
        }
        
        if (pos >= nalData.size) return false
        
        // NAL unit type в младших 5 битах
        val nalType = nalData[pos].toInt() and 0x1F
        
        // 5 = IDR (ключевой кадр), 7 = SPS, 8 = PPS
        return nalType == 5 || nalType == 7 || nalType == 8
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, SphereAgentApp.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("SphereAgent H.264 Stream")
            .setContentText("Hardware H.264 streaming via ROOT")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        SphereLog.i(TAG, "H264RootStreamService destroyed")
        stopStream()
        instance = null
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }
}
