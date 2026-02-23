package com.sphere.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
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
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScrcpyStreamService - Лёгкий H.264 стрим через scrcpy-server
 * 
 * v3.7.1: Замена тяжёлого screenrecord на scrcpy-server (69KB):
 * - Использует SurfaceControl (скрытое API Android) для захвата экрана
 * - Аппаратное H.264 кодирование через MediaCodec (GPU/VPU)
 * - Фреймированный протокол — без ручного NAL парсинга!
 * - I-frame каждые 2 секунды без перезапуска процесса
 * - Работает без перезапусков (один процесс на весь сеанс)
 * - На 10x легче чем screenrecord по CPU/памяти
 * 
 * Архитектура:
 * scrcpy-server (app_process) → SurfaceControl → MediaCodec (GPU) →
 *   LocalSocket → Agent (read framed packets) → WebSocket → Browser WebCodecs
 * 
 * Протокол пакета scrcpy-server:
 *   [PTS: 8 bytes] [SIZE: 4 bytes] [H.264 DATA: SIZE bytes]
 *   PTS bit63 = config packet (SPS/PPS)
 *   PTS bit62 = keyframe (IDR)
 */
class ScrcpyStreamService : Service() {
    
    companion object {
        private const val TAG = "ScrcpyStream"
        private const val NOTIFICATION_ID = 1005
        
        private const val ACTION_START = "com.sphere.agent.SCRCPY_STREAM_START"
        private const val ACTION_STOP = "com.sphere.agent.SCRCPY_STREAM_STOP"
        private const val ACTION_RESUME = "com.sphere.agent.SCRCPY_STREAM_RESUME"
        private const val ACTION_PAUSE = "com.sphere.agent.SCRCPY_STREAM_PAUSE"
        
        private const val EXTRA_MAX_SIZE = "max_size"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_FPS = "fps"
        
        private const val SCRCPY_SERVER_ASSET = "scrcpy-server.jar"
        private const val SCRCPY_DEPLOY_PATH = "/data/local/tmp/scrcpy-server"
        
        // Оптимизированные параметры для эмуляторов LDPlayer
        private const val DEFAULT_MAX_SIZE = 540   // Ограничение по max dimension
        private const val DEFAULT_BITRATE = 500_000 // 500 Kbps — минимум для чёткой картинки
        private const val DEFAULT_FPS = 15          // 15 fps — баланс качество/нагрузка
        private const val IFRAME_INTERVAL = 2       // Keyframe каждые 2 секунды (без restart!)
        
        // Таймауты
        // v3.13.4: Увеличен таймаут до 10 секунд для медленных эмуляторов
        private const val SOCKET_CONNECT_TIMEOUT_MS = 15000L
        private const val SOCKET_CONNECT_RETRY_MS = 200L
        private const val PROCESS_START_GRACE_MS = 350L
        private const val AUTO_RESTART_DELAY_MS = 1000L
        
        @Volatile
        private var instance: ScrcpyStreamService? = null
        
        @Volatile
        var isRunning: Boolean = false
            private set
        
        /**
         * Запуск сервиса стрима (в режиме паузы)
         */
        fun start(context: Context, maxSize: Int = DEFAULT_MAX_SIZE,
                  bitrate: Int = DEFAULT_BITRATE, fps: Int = DEFAULT_FPS) {
            val intent = Intent(context, ScrcpyStreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MAX_SIZE, maxSize)
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
         * Остановка сервиса
         */
        fun stop(context: Context) {
            val intent = Intent(context, ScrcpyStreamService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * Пауза стрима (убивает scrcpy-server для экономии ресурсов)
         */
        fun pause(context: Context? = null) {
            if (context != null) {
                val intent = Intent(context, ScrcpyStreamService::class.java).apply {
                    action = ACTION_PAUSE
                }
                context.startService(intent)
            } else {
                instance?.pauseStream()
            }
            SphereLog.i(TAG, "Scrcpy stream PAUSE requested")
        }
        
        /**
         * Возобновление стрима
         */
        fun resume(context: Context? = null, maxSize: Int? = null,
                   bitrate: Int? = null, fps: Int? = null) {
            if (context != null) {
                val intent = Intent(context, ScrcpyStreamService::class.java).apply {
                    action = ACTION_RESUME
                    maxSize?.let { putExtra(EXTRA_MAX_SIZE, it) }
                    bitrate?.let { putExtra(EXTRA_BITRATE, it) }
                    fps?.let { putExtra(EXTRA_FPS, it) }
                }
                context.startService(intent)
            } else {
                instance?.resumeStream(maxSize, bitrate, fps)
            }
        }
        
        /**
         * Запрос keyframe — с scrcpy-server не нужен!
         * I-frame генерируется автоматически каждые IFRAME_INTERVAL секунд
         */
        fun requestKeyframe() {
            SphereLog.d(TAG, "Keyframe request (auto via i-frame-interval=${IFRAME_INTERVAL}s)")
        }
        
        /**
         * Проверка поддержки scrcpy-server
         */
        fun checkSupport(): Boolean {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "which app_process"))
                val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return false
                }
                process.exitValue() == 0
            } catch (e: Exception) {
                SphereLog.w(TAG, "scrcpy support check failed: ${e.message}")
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
                "type" to "scrcpy-server",
                "isPaused" to (svc?.isPaused?.get() ?: "N/A"),
                "isStreaming" to (svc?.isStreaming?.get() ?: "N/A"),
                "maxSize" to (svc?.maxSize ?: "N/A"),
                "bitrate" to (svc?.bitrate ?: "N/A"),
                "fps" to (svc?.fps ?: "N/A"),
                "frameCount" to (svc?.frameCount ?: "N/A"),
                "bytesSent" to (svc?.bytesSent ?: "N/A")
            )
        }
    }
    
    // Зависимости
    private lateinit var connectionManager: ConnectionManager
    private lateinit var agentConfig: AgentConfig
    
    // Корутины
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    
    // Процесс и сокет scrcpy-server
    private var scrcpyProcess: Process? = null
    private var videoSocket: LocalSocket? = null
    
    // Флаги состояния
    private val isPaused = AtomicBoolean(true)  // Начинаем в паузе
    private val isStreaming = AtomicBoolean(false)
    
    // Параметры стрима
    private var maxSize = DEFAULT_MAX_SIZE
    private var bitrate = DEFAULT_BITRATE
    private var fps = DEFAULT_FPS
    
    // Статистика
    private var frameCount = 0L
    private var bytesSent = 0L
    private var startTime = 0L
    
    // SPS/PPS кэш для отправки с keyframe
    private var cachedConfig: ByteArray? = null
    
    // v3.8.0: Реиспользуемые буферы — избегаем GC давление
    // Header buffer для чтения PTS+size (12 bytes)
    private val headerBuf = ByteArray(12)
    // Packet buffer — растёт по мере необходимости, не пересоздаётся каждый кадр
    private var packetBuf = ByteArray(65536)
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        val app = application as SphereAgentApp
        connectionManager = app.connectionManager
        agentConfig = app.agentConfig
        
        SphereLog.i(TAG, "ScrcpyStreamService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                maxSize = intent.getIntExtra(EXTRA_MAX_SIZE, DEFAULT_MAX_SIZE)
                bitrate = intent.getIntExtra(EXTRA_BITRATE, DEFAULT_BITRATE)
                fps = intent.getIntExtra(EXTRA_FPS, DEFAULT_FPS)
                
                startForeground(NOTIFICATION_ID, createNotification())
                isRunning = true
                
                SphereLog.i(TAG, "Scrcpy service started in PAUSED mode (max=$maxSize, ${bitrate/1000}Kbps, ${fps}fps)")
            }
            ACTION_RESUME -> {
                intent.getIntExtra(EXTRA_MAX_SIZE, -1).let { if (it > 0) maxSize = it }
                intent.getIntExtra(EXTRA_BITRATE, -1).let { if (it > 0) bitrate = it }
                intent.getIntExtra(EXTRA_FPS, -1).let { if (it > 0) fps = it }
                
                resumeStream(maxSize, bitrate, fps)
            }
            ACTION_PAUSE -> pauseStream()
            ACTION_STOP -> {
                stopStream()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
                SphereLog.i(TAG, "Scrcpy service stopped")
            }
        }
        
        return START_STICKY
    }
    
    /**
     * Деплой scrcpy-server.jar из assets на устройство
     * Копирует в /data/local/tmp/scrcpy-server через su для доступа
     */
    private fun deployScrcpyServer(): Boolean {
        try {
            // Проверяем, задеплоен ли уже (проверка через su т.к. файл в /data/local/tmp)
            val checkProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f $SCRCPY_DEPLOY_PATH && echo EXISTS"))
            val checkResult = checkProcess.inputStream.bufferedReader().readText().trim()
            checkProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            
            if (checkResult == "EXISTS") {
                SphereLog.d(TAG, "scrcpy-server already deployed")
                return true
            }
            
            // Копируем из assets через промежуточный файл в app data
            val tempFile = File(filesDir, SCRCPY_SERVER_ASSET)
            assets.open(SCRCPY_SERVER_ASSET).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Копируем в /data/local/tmp/ через su
            val deployProcess = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", "cp ${tempFile.absolutePath} $SCRCPY_DEPLOY_PATH && chmod 644 $SCRCPY_DEPLOY_PATH"
            ))
            val finished = deployProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                deployProcess.destroyForcibly()
                SphereLog.e(TAG, "Deploy timeout")
                return false
            }
            
            // Чистим временный файл
            tempFile.delete()
            
            SphereLog.i(TAG, "scrcpy-server deployed to $SCRCPY_DEPLOY_PATH")
            return true
        } catch (e: Exception) {
            SphereLog.e(TAG, "Failed to deploy scrcpy-server: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Возобновление стрима — запускает scrcpy-server и читает пакеты
     */
    private fun resumeStream(maxSize: Int? = null, bitrate: Int? = null, fps: Int? = null) {
        maxSize?.let { this.maxSize = it }
        bitrate?.let { this.bitrate = it }
        fps?.let { this.fps = it }
        
        if (isStreaming.get()) {
            SphereLog.w(TAG, "Stream already running")
            return
        }
        
        isPaused.set(false)
        isStreaming.set(true)
        startTime = System.currentTimeMillis()
        frameCount = 0
        bytesSent = 0
        cachedConfig = null
        
        streamJob = scope.launch {
            startScrcpyStream()
        }
        
        connectionManager.isCurrentlyStreaming = true
        SphereLog.i(TAG, "✅ Scrcpy stream RESUMED (max=${this.maxSize}, ${this.bitrate/1000}Kbps, ${this.fps}fps)")
    }
    
    /**
     * Пауза стрима — убивает scrcpy-server процесс
     */
    private fun pauseStream() {
        isPaused.set(true)
        isStreaming.set(false)
        
        cleanup()
        
        connectionManager.isCurrentlyStreaming = false
        
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val avgFps = if (elapsed > 0) frameCount / elapsed else 0.0
        val avgKbps = if (elapsed > 0) (bytesSent * 8 / elapsed / 1000).toLong() else 0L
        
        SphereLog.i(TAG, "Scrcpy stream PAUSED. frames=$frameCount, avgFps=${avgFps.toInt()}, avgKbps=$avgKbps")
    }
    
    /**
     * Полная остановка стрима
     */
    private fun stopStream() {
        pauseStream()
    }
    
    /**
     * Очистка ресурсов: закрываем сокет, убиваем процесс
     */
    private fun cleanup() {
        try { videoSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        
        try { scrcpyProcess?.destroyForcibly() } catch (_: Exception) {}
        scrcpyProcess = null
        
        // Убиваем все scrcpy-server процессы на всякий случай
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f scrcpy.Server"))
        } catch (_: Exception) {}
        
        streamJob?.cancel()
        streamJob = null
    }
    
    /**
     * Основной цикл стриминга через scrcpy-server
     * 
     * Протокол:
     * 1. Запуск scrcpy-server через app_process
     * 2. Подключение к LocalSocket (abstract namespace)
     * 3. Чтение codec metadata (12 bytes): codec_id:4 + width:4 + height:4
     * 4. Чтение пакетов: PTS:8 + size:4 + data
     * 5. Отправка в WebSocket в формате: flags:1 + timestamp:4 + h264_data
     */
    private suspend fun startScrcpyStream() = withContext(Dispatchers.IO) {
        SphereLog.i(TAG, "Starting scrcpy-server stream...")
        
        // 1. Деплоим scrcpy-server
        if (!deployScrcpyServer()) {
            SphereLog.e(TAG, "Failed to deploy scrcpy-server!")
            isStreaming.set(false)
            return@withContext
        }
        
        // v3.13.1: Используем меньший диапазон для scid чтобы избежать NumberFormatException в scrcpy-server
        // scrcpy-server парсит scid как int, но может иметь проблемы с большими значениями
        val scid = (Math.random() * 100000).toInt() + 1
        val socketName = "scrcpy_${String.format("%08x", scid)}"
        
        try {
            // 2. Запускаем scrcpy-server
            // tunnel_forward=true — сервер слушает на сокете, агент подключается (forward tunnel)
            // tunnel_forward=false — сервер подключается к клиенту (reverse tunnel)
            // v3.13.4: Упрощённые параметры scrcpy-server для совместимости с LDPlayer
            // Убраны video_codec_options которые могут вызывать проблемы на некоторых эмуляторах
            val cmd = "CLASSPATH=$SCRCPY_DEPLOY_PATH app_process / com.genymobile.scrcpy.Server 2.4 " +
                "tunnel_forward=true " +
                "audio=false " +
                "video=true " +
                "control=false " +
                "cleanup=false " +
                "max_size=$maxSize " +
                "video_bit_rate=$bitrate " +
                "max_fps=$fps " +
                "video_codec=h264 " +
                "send_device_meta=false " +
                "send_dummy_byte=false " +
                "send_codec_meta=false " +
                "scid=$scid"
            
            SphereLog.i(TAG, "Executing scrcpy-server (scid=$scid, socket=$socketName)")
            // v3.13.6: Универсальный запуск — сначала sh, затем fallback на su.
            // На части прошивок/эмуляторов app_process из app uid блокируется, и нужен su.
            val launchVariants = listOf(
                arrayOf("sh", "-c", cmd),
                arrayOf("su", "-c", cmd)
            )

            var launchError: String? = null
            for (variant in launchVariants) {
                val launchMode = variant[0]
                try {
                    val process = Runtime.getRuntime().exec(variant)
                    delay(PROCESS_START_GRACE_MS)

                    if (!process.isAlive) {
                        val exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
                        val stderr = runCatching {
                            process.errorStream.bufferedReader().readText().take(300)
                        }.getOrElse { "unreadable" }
                        launchError = "mode=$launchMode, exit=$exitCode, stderr=$stderr"
                        SphereLog.w(TAG, "scrcpy-server exited immediately: $launchError")
                        continue
                    }

                    scrcpyProcess = process
                    SphereLog.i(TAG, "scrcpy-server started via $launchMode")
                    break
                } catch (e: Exception) {
                    launchError = "mode=$launchMode, error=${e.message}"
                    SphereLog.w(TAG, "Failed to launch scrcpy-server via $launchMode: ${e.message}")
                }
            }

            if (scrcpyProcess == null) {
                SphereLog.e(TAG, "Failed to start scrcpy-server. lastError=$launchError")
                cleanup()
                isStreaming.set(false)
                connectionManager.isCurrentlyStreaming = false
                return@withContext
            }
            
            // v3.13.4: Запускаем чтение stderr в отдельном потоке для диагностики
            val process = scrcpyProcess
            if (process != null) {
                Thread {
                    try {
                        val stderr = process.errorStream.bufferedReader().readText()
                        if (stderr.isNotBlank()) {
                            SphereLog.e(TAG, "scrcpy-server stderr: ${stderr.take(500)}")
                        }
                    } catch (_: Exception) { }
                }.start()
            }
            
            // 3. Подключаемся к LocalSocket (ждём до 5 секунд)
            var connected = false
            val connectDeadline = System.currentTimeMillis() + SOCKET_CONNECT_TIMEOUT_MS
            var lastConnectError: String? = null
            
            while (System.currentTimeMillis() < connectDeadline && !connected && isStreaming.get()) {
                val namespaces = listOf(
                    LocalSocketAddress.Namespace.ABSTRACT,
                    LocalSocketAddress.Namespace.FILESYSTEM
                )

                for (namespace in namespaces) {
                    if (connected) break
                    try {
                        val socket = LocalSocket()
                        socket.connect(LocalSocketAddress(socketName, namespace))
                        videoSocket = socket
                        connected = true
                        SphereLog.i(TAG, "✅ Connected to scrcpy socket '$socketName' (ns=$namespace)")
                    } catch (e: Exception) {
                        lastConnectError = "ns=$namespace, error=${e.message}"
                    }
                }

                if (!connected) {
                    // Если процесс уже умер — прекращаем ожидание сокета раньше таймаута.
                    if (scrcpyProcess?.isAlive == false) {
                        val exitCode = runCatching { scrcpyProcess?.exitValue() }.getOrNull()
                        lastConnectError = "process_exited, exit_code=$exitCode"
                        break
                    }
                    delay(SOCKET_CONNECT_RETRY_MS)
                }
            }
            
            if (!connected) {
                val processAlive = scrcpyProcess?.isAlive ?: false
                val exitCode = runCatching { scrcpyProcess?.exitValue() }.getOrNull()
                SphereLog.e(
                    TAG,
                    "Failed to connect to scrcpy socket '$socketName'. " +
                        "process_alive=$processAlive, exit_code=$exitCode, last_error=$lastConnectError"
                )
                cleanup()
                isStreaming.set(false)
                connectionManager.isCurrentlyStreaming = false
                return@withContext
            }
            
            // 4. Читаем видеопоток
            val input = DataInputStream(videoSocket!!.inputStream)
            
            // Codec metadata (12 bytes): codec_id:4 + width:4 + height:4
            val codecId = input.readInt()
            val videoWidth = input.readInt()
            val videoHeight = input.readInt()
            SphereLog.i(TAG, "✅ Codec metadata: id=$codecId, ${videoWidth}x${videoHeight}")
            
            // v3.8.0: Оптимизированный цикл чтения пакетов
            // — Реиспользуемый packetBuf вместо new ByteArray на каждый кадр
            // — Прямая запись в ByteBuffer без промежуточных копий (config+data)
            // — Минимальный logging (только каждый 300-й кадр)
            while (isActive && isStreaming.get() && !isPaused.get()) {
                // Header: PTS (8 bytes) + size (4 bytes)
                input.readFully(headerBuf, 0, 12)
                val pts = ((headerBuf[0].toLong() and 0xFF) shl 56) or
                          ((headerBuf[1].toLong() and 0xFF) shl 48) or
                          ((headerBuf[2].toLong() and 0xFF) shl 40) or
                          ((headerBuf[3].toLong() and 0xFF) shl 32) or
                          ((headerBuf[4].toLong() and 0xFF) shl 24) or
                          ((headerBuf[5].toLong() and 0xFF) shl 16) or
                          ((headerBuf[6].toLong() and 0xFF) shl 8) or
                          (headerBuf[7].toLong() and 0xFF)
                val packetSize = ((headerBuf[8].toInt() and 0xFF) shl 24) or
                                 ((headerBuf[9].toInt() and 0xFF) shl 16) or
                                 ((headerBuf[10].toInt() and 0xFF) shl 8) or
                                 (headerBuf[11].toInt() and 0xFF)
                
                // Защита от невалидных пакетов
                if (packetSize <= 0 || packetSize > 2 * 1024 * 1024) {
                    if (packetSize > 0) input.skipBytes(packetSize)
                    continue
                }
                
                // Растим буфер только если нужно (не пересоздаём каждый кадр!)
                if (packetBuf.size < packetSize) {
                    packetBuf = ByteArray(packetSize + 4096) // +запас
                }
                input.readFully(packetBuf, 0, packetSize)
                
                // Флаги из PTS
                val isConfig = (pts ushr 63) and 1L == 1L
                val isKeyframe = (pts ushr 62) and 1L == 1L
                
                if (isConfig) {
                    // SPS/PPS — копируем только config (маленький, ~30 bytes)
                    cachedConfig = packetBuf.copyOfRange(0, packetSize)
                    continue
                }
                
                // Формируем WebSocket пакет: flags(1) + timestamp(4) + h264_data
                val timestamp = (System.currentTimeMillis() - startTime).toInt()
                val config = if (isKeyframe) cachedConfig else null
                val configLen = config?.size ?: 0
                val totalSize = 1 + 4 + configLen + packetSize
                
                val packet = ByteBuffer.allocate(totalSize)
                packet.put(if (isKeyframe) (if (config != null) 0x03 else 0x01).toByte() else 0x00.toByte())
                packet.putInt(timestamp)
                if (config != null) packet.put(config)
                packet.put(packetBuf, 0, packetSize)
                
                val sent = connectionManager.sendBinaryFrame(packet.array())
                if (sent) {
                    frameCount++
                    bytesSent += totalSize
                    // v3.8.0: Логируем только каждый 300-й кадр (минимум IO)
                    if (frameCount % 300 == 0L) {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        SphereLog.d(TAG, "#$frameCount: ${if (isKeyframe) "K" else "D"} ${packetSize}B, fps=${(frameCount/elapsed).toInt()}")
                    }
                }
            }
            
        } catch (e: java.io.EOFException) {
            SphereLog.w(TAG, "scrcpy stream ended (EOF)")
        } catch (e: Exception) {
            SphereLog.e(TAG, "scrcpy stream error: ${e.message}", e)
        } finally {
            cleanup()
            
            // Auto-restart если всё ещё стримим (через отдельную корутину чтобы избежать рекурсии)
            if (!isPaused.get() && isStreaming.get()) {
                SphereLog.i(TAG, "Auto-restarting scrcpy stream in ${AUTO_RESTART_DELAY_MS}ms...")
                scheduleStreamRestart()
            }
        }
    }
    
    /**
     * Планирует перезапуск стрима в отдельной корутине (избегает рекурсию)
     */
    private fun scheduleStreamRestart() {
        scope.launch {
            delay(AUTO_RESTART_DELAY_MS)
            if (!isPaused.get() && isStreaming.get()) {
                startScrcpyStream()
            }
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, SphereAgentApp.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("SphereAgent Stream")
            .setContentText("Lightweight H.264 via scrcpy-server")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        SphereLog.i(TAG, "ScrcpyStreamService destroyed")
        stopStream()
        instance = null
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }
}
