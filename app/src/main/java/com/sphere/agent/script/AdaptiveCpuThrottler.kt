package com.sphere.agent.script

import android.util.Log
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdaptiveCpuThrottler v1.0.0 — Адаптивное управление нагрузкой CPU
 *
 * Мониторит загрузку CPU через /proc/stat и автоматически увеличивает
 * задержку между шагами скрипта при превышении порогов.
 *
 * Пороги (настраиваемые):
 * - CPU > cpuThresholdLow (15%)  → delay +50%
 * - CPU > cpuThresholdHigh (25%) → delay +200% + yield
 * - CPU > cpuThresholdCritical (40%) → пауза 2 секунды
 *
 * Минимальный delay между шагами: 100ms (даже если скрипт указал 0)
 *
 * @author SphereADB Enterprise
 * @version 1.0.0
 */
class AdaptiveCpuThrottler(
    private val cpuThresholdLow: Int = 15,
    private val cpuThresholdHigh: Int = 25,
    private val cpuThresholdCritical: Int = 40,
    private val minDelayMs: Long = 100,
    private val monitorIntervalMs: Long = 2000
) {
    companion object {
        private const val TAG = "CpuThrottler"
    }

    private val enabled = AtomicBoolean(true)

    // Последние значения CPU из /proc/stat
    @Volatile
    private var lastCpuTotal: Long = 0
    @Volatile
    private var lastCpuIdle: Long = 0
    @Volatile
    private var currentCpuPercent: Float = 0f

    // Статистика
    @Volatile
    var totalThrottleEvents: Int = 0
        private set
    @Volatile
    var totalPauseEvents: Int = 0
        private set

    /**
     * Считать текущую загрузку CPU из /proc/stat
     *
     * Формат первой строки:
     * cpu  user nice system idle iowait irq softirq steal guest guest_nice
     */
    private fun readCpuUsage(): Float {
        try {
            val line = File("/proc/stat").bufferedReader().readLine() ?: return 0f
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") return 0f

            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
            val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L
            val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L
            val steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L

            val totalCpu = user + nice + system + idle + iowait + irq + softirq + steal
            val totalIdle = idle + iowait

            if (lastCpuTotal == 0L) {
                lastCpuTotal = totalCpu
                lastCpuIdle = totalIdle
                return 0f
            }

            val diffTotal = totalCpu - lastCpuTotal
            val diffIdle = totalIdle - lastCpuIdle

            lastCpuTotal = totalCpu
            lastCpuIdle = totalIdle

            if (diffTotal <= 0) return 0f

            val cpuPercent = ((diffTotal - diffIdle).toFloat() / diffTotal.toFloat()) * 100f
            currentCpuPercent = cpuPercent
            return cpuPercent
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать /proc/stat: ${e.message}")
            return 0f
        }
    }

    /**
     * Проверить CPU и применить throttling после каждого шага скрипта.
     *
     * @param baseDelay Базовая задержка шага (из настроек скрипта)
     * @return Фактическая задержка, применённая после шага
     */
    suspend fun checkAndThrottle(baseDelay: Long): Long {
        if (!enabled.get()) {
            val effectiveDelay = maxOf(baseDelay, minDelayMs)
            delay(effectiveDelay)
            return effectiveDelay
        }

        val cpuPercent = readCpuUsage()
        var effectiveDelay = maxOf(baseDelay, minDelayMs)

        when {
            cpuPercent > cpuThresholdCritical -> {
                // Критическая нагрузка — пауза 2 секунды
                totalPauseEvents++
                Log.w(TAG, "CPU CRITICAL: ${cpuPercent.toInt()}% > $cpuThresholdCritical% — пауза 2с")
                ScriptLogSender.logThrottleEvent(
                    "critical_pause",
                    cpuPercent,
                    2000L
                )
                delay(2000)
                return 2000
            }
            cpuPercent > cpuThresholdHigh -> {
                // Высокая нагрузка — delay +200% + yield
                totalThrottleEvents++
                effectiveDelay = maxOf(effectiveDelay * 3, 500)
                Log.i(TAG, "CPU HIGH: ${cpuPercent.toInt()}% > $cpuThresholdHigh% — delay=${effectiveDelay}ms")
                ScriptLogSender.logThrottleEvent(
                    "high_throttle",
                    cpuPercent,
                    effectiveDelay
                )
                // yield для других корутин
                kotlinx.coroutines.yield()
                delay(effectiveDelay)
                return effectiveDelay
            }
            cpuPercent > cpuThresholdLow -> {
                // Умеренная нагрузка — delay +50%
                totalThrottleEvents++
                effectiveDelay = maxOf((effectiveDelay * 1.5).toLong(), 200)
                Log.d(TAG, "CPU MODERATE: ${cpuPercent.toInt()}% > $cpuThresholdLow% — delay=${effectiveDelay}ms")
                delay(effectiveDelay)
                return effectiveDelay
            }
            else -> {
                // Нормальная нагрузка — минимальный delay
                delay(effectiveDelay)
                return effectiveDelay
            }
        }
    }

    /**
     * Получить текущий процент CPU
     */
    fun getCpuPercent(): Float = currentCpuPercent

    /**
     * Включить/выключить throttling
     */
    fun setEnabled(value: Boolean) {
        enabled.set(value)
        Log.i(TAG, "Throttling ${if (value) "включён" else "выключен"}")
    }

    /**
     * Получить статистику throttling
     */
    fun getStats(): Map<String, Any> = mapOf(
        "enabled" to enabled.get(),
        "current_cpu_percent" to currentCpuPercent,
        "threshold_low" to cpuThresholdLow,
        "threshold_high" to cpuThresholdHigh,
        "threshold_critical" to cpuThresholdCritical,
        "min_delay_ms" to minDelayMs,
        "total_throttle_events" to totalThrottleEvents,
        "total_pause_events" to totalPauseEvents
    )
}
