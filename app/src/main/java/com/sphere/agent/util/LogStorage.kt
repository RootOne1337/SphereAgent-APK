package com.sphere.agent.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LogStorage - локальное и персистентное хранилище логов.
 *
 * Требования:
 * - Логи доступны в UI даже после падения приложения.
 * - Логи можно скопировать в буфер обмена.
 */
object LogStorage {
    private const val MAX_LOGS = 1500
    private const val LOG_FILE_NAME = "sphereagent_logs.txt"
    private const val UI_FLUSH_DEBOUNCE_MS = 200L
    private const val MAX_TAIL_BYTES = 512 * 1024 // читаем хвост файла при старте (512KB)

    private val logs = ArrayDeque<String>(MAX_LOGS)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushScheduled = AtomicBoolean(false)

    private var logFile: File? = null

    private val _logsText = MutableStateFlow("")
    val logsText: StateFlow<String> = _logsText.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            logFile = File(context.filesDir, LOG_FILE_NAME)

            // Загружаем прошлые логи (если файл существует). Читаем только хвост, чтобы не фризить старт.
            try {
                val file = logFile ?: return
                if (file.exists() && file.length() > 0) {
                    val tail = readFileTail(file, MAX_TAIL_BYTES)
                    tail.lineSequence().forEach { line ->
                        if (line.isNotBlank()) {
                            logs.addLast(line)
                            trimIfNeededLocked()
                        }
                    }
                    _logsText.value = buildTextLocked()
                }
            } catch (_: Exception) {
                // Игнорируем: главное не падать из-за логов
            }
        }
    }

    fun addLog(level: String, tag: String, message: String) {
        val time = dateFormat.format(Date())
        val logLine = "[$time] $level/$tag: $message"

        // 1) Быстро кладём в память (под lock) без тяжёлых операций
        synchronized(lock) {
            logs.addLast(logLine)
            trimIfNeededLocked()
        }

        // 2) Пишем на диск в IO (не блокируем вызывающий поток)
        ioScope.launch {
            try {
                val file = logFile
                if (file != null) file.appendText(logLine + "\n")
            } catch (_: Exception) {
                // Игнорируем
            }
        }

        // 3) Обновляем текст для UI батчами (иначе будут фризы из-за joinToString)
        scheduleUiFlush()
    }

    fun getLogs(): String = logsText.value

    fun clear(context: Context? = null) {
        synchronized(lock) {
            logs.clear()
            _logsText.value = ""
            try {
                (logFile ?: context?.let { File(it.filesDir, LOG_FILE_NAME) })?.delete()
            } catch (_: Exception) {
                // Игнорируем
            }
        }
    }

    private fun scheduleUiFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                delay(UI_FLUSH_DEBOUNCE_MS)
                val text = synchronized(lock) { buildTextLocked() }
                _logsText.value = text
            } finally {
                flushScheduled.set(false)
            }
        }
    }

    private fun buildTextLocked(): String {
        if (logs.isEmpty()) return ""
        val sb = StringBuilder(logs.size * 64)
        for (line in logs) {
            sb.append(line).append('\n')
        }
        if (sb.isNotEmpty()) sb.setLength(sb.length - 1) // убрать последний \n
        return sb.toString()
    }

    private fun trimIfNeededLocked() {
        while (logs.size > MAX_LOGS) {
            logs.removeFirst()
        }
    }

    private fun readFileTail(file: File, maxBytes: Int): String {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = (len - maxBytes).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
