package com.sphere.agent.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.StatFs
import android.os.SystemClock
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile

/**
 * v2.26.0 ENTERPRISE: Health Metrics Collector
 * 
 * Собирает метрики здоровья устройства для мониторинга:
 * - CPU usage (%)
 * - Memory usage (used/total MB)
 * - Battery level (%) и статус зарядки
 * - Storage space (available/total GB)
 * - Network latency (ms к серверу)
 * - Uptime (секунды с последнего boot)
 * 
 * Метрики отправляются с heartbeat для мониторинга флота из 1000+ устройств.
 * Позволяет выявлять проблемные устройства до падения.
 */
class HealthMetricsCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "HealthMetrics"
        
        // Интервал сбора метрик (не чаще раза в 30 сек)
        private const val MIN_COLLECT_INTERVAL_MS = 30_000L
        
        // Пороги для предупреждений
        const val CPU_WARNING_THRESHOLD = 80 // %
        const val MEMORY_WARNING_THRESHOLD = 85 // %
        const val BATTERY_WARNING_THRESHOLD = 15 // %
        const val STORAGE_WARNING_THRESHOLD_MB = 500 // MB
    }
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    private var lastCollectTime = 0L
    private var cachedMetrics: HealthMetrics? = null
    
    // CPU tracking
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    
    private val _metrics = MutableStateFlow<HealthMetrics?>(null)
    val metrics: StateFlow<HealthMetrics?> = _metrics.asStateFlow()
    
    /**
     * Собирает текущие метрики здоровья устройства
     * С кешированием для избежания слишком частого сбора
     */
    fun collectMetrics(forceRefresh: Boolean = false): HealthMetrics {
        val now = System.currentTimeMillis()
        
        // Используем кеш если недавно собирали
        if (!forceRefresh && cachedMetrics != null && 
            (now - lastCollectTime) < MIN_COLLECT_INTERVAL_MS) {
            return cachedMetrics!!
        }
        
        val metrics = HealthMetrics(
            timestamp = now,
            cpuUsage = getCpuUsage(),
            memoryUsedMb = getMemoryUsedMb(),
            memoryTotalMb = getMemoryTotalMb(),
            memoryUsagePercent = getMemoryUsagePercent(),
            batteryLevel = getBatteryLevel(),
            batteryCharging = isBatteryCharging(),
            storageAvailableMb = getStorageAvailableMb(),
            storageTotalMb = getStorageTotalMb(),
            uptimeSeconds = getUptimeSeconds(),
            appMemoryMb = getAppMemoryMb(),
            warnings = collectWarnings()
        )
        
        cachedMetrics = metrics
        lastCollectTime = now
        _metrics.value = metrics
        
        // Логируем предупреждения
        if (metrics.warnings.isNotEmpty()) {
            SphereLog.w(TAG, "Health warnings: ${metrics.warnings.joinToString()}")
        }
        
        return metrics
    }
    
    /**
     * CPU usage в процентах (0-100)
     * Читает /proc/stat для точного измерения
     */
    private fun getCpuUsage(): Int {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()
            
            // Format: cpu  user nice system idle iowait irq softirq
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 5) return 0
            
            val user = parts[1].toLongOrNull() ?: 0
            val nice = parts[2].toLongOrNull() ?: 0
            val system = parts[3].toLongOrNull() ?: 0
            val idle = parts[4].toLongOrNull() ?: 0
            val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0 else 0
            val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0 else 0
            val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0 else 0
            
            val total = user + nice + system + idle + iowait + irq + softirq
            val idleTime = idle + iowait
            
            if (lastCpuTotal == 0L) {
                lastCpuTotal = total
                lastCpuIdle = idleTime
                return 0
            }
            
            val diffTotal = total - lastCpuTotal
            val diffIdle = idleTime - lastCpuIdle
            
            lastCpuTotal = total
            lastCpuIdle = idleTime
            
            if (diffTotal <= 0) return 0
            
            val usage = ((diffTotal - diffIdle) * 100 / diffTotal).toInt()
            usage.coerceIn(0, 100)
        } catch (e: Exception) {
            SphereLog.d(TAG, "Failed to read CPU usage: ${e.message}")
            0
        }
    }
    
    /**
     * Используемая память в MB
     */
    private fun getMemoryUsedMb(): Int {
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val usedBytes = memInfo.totalMem - memInfo.availMem
            (usedBytes / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Общая память в MB
     */
    private fun getMemoryTotalMb(): Int {
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            (memInfo.totalMem / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Процент использования памяти
     */
    private fun getMemoryUsagePercent(): Int {
        val total = getMemoryTotalMb()
        if (total == 0) return 0
        return (getMemoryUsedMb() * 100) / total
    }
    
    /**
     * Уровень батареи (0-100)
     */
    private fun getBatteryLevel(): Int {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            
            if (level >= 0 && scale > 0) {
                (level * 100) / scale
            } else {
                // Fallback: читаем напрямую (для эмуляторов)
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            }
        } catch (e: Exception) {
            100 // Assume full battery on error
        }
    }
    
    /**
     * Заряжается ли устройство
     */
    private fun isBatteryCharging(): Boolean {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || 
            status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            true // Assume charging on emulators
        }
    }
    
    /**
     * Доступное место на storage в MB
     */
    private fun getStorageAvailableMb(): Int {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            (availableBytes / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Общее место на storage в MB
     */
    private fun getStorageTotalMb(): Int {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            (totalBytes / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Uptime в секундах
     */
    private fun getUptimeSeconds(): Long {
        return SystemClock.elapsedRealtime() / 1000
    }
    
    /**
     * Память используемая приложением в MB
     */
    private fun getAppMemoryMb(): Int {
        return try {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            memoryInfo.totalPss / 1024 // PSS in KB -> MB
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Собирает предупреждения о критических состояниях
     */
    private fun collectWarnings(): List<String> {
        val warnings = mutableListOf<String>()
        
        if (getMemoryUsagePercent() > MEMORY_WARNING_THRESHOLD) {
            warnings.add("HIGH_MEMORY")
        }
        
        val battery = getBatteryLevel()
        if (battery < BATTERY_WARNING_THRESHOLD && !isBatteryCharging()) {
            warnings.add("LOW_BATTERY")
        }
        
        if (getStorageAvailableMb() < STORAGE_WARNING_THRESHOLD_MB) {
            warnings.add("LOW_STORAGE")
        }
        
        // CPU warning только если уже есть предыдущее измерение
        if (lastCpuTotal > 0 && getCpuUsage() > CPU_WARNING_THRESHOLD) {
            warnings.add("HIGH_CPU")
        }
        
        return warnings
    }
    
    /**
     * Конвертирует метрики в Map для JSON сериализации
     */
    fun toMap(): Map<String, Any> {
        val m = collectMetrics()
        return mapOf(
            "cpu_usage" to m.cpuUsage,
            "memory_used_mb" to m.memoryUsedMb,
            "memory_total_mb" to m.memoryTotalMb,
            "memory_percent" to m.memoryUsagePercent,
            "battery_level" to m.batteryLevel,
            "battery_charging" to m.batteryCharging,
            "storage_available_mb" to m.storageAvailableMb,
            "storage_total_mb" to m.storageTotalMb,
            "uptime_seconds" to m.uptimeSeconds,
            "app_memory_mb" to m.appMemoryMb,
            "warnings" to m.warnings,
            "timestamp" to m.timestamp
        )
    }
}

/**
 * Data class для метрик здоровья
 */
@Serializable
data class HealthMetrics(
    val timestamp: Long,
    val cpuUsage: Int,
    val memoryUsedMb: Int,
    val memoryTotalMb: Int,
    val memoryUsagePercent: Int,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val storageAvailableMb: Int,
    val storageTotalMb: Int,
    val uptimeSeconds: Long,
    val appMemoryMb: Int,
    val warnings: List<String> = emptyList()
)
