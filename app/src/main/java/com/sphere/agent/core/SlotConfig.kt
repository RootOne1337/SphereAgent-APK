package com.sphere.agent.core

import android.content.Context
import com.sphere.agent.data.SettingsRepository
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * v2.26.0 ENTERPRISE: Slot Configuration Manager
 * 
 * Управляет идентификацией "слота" эмулятора независимо от device_id.
 * 
 * Проблема: При клонировании эмулятора device_id меняется, но привязки
 * (аккаунты, прокси, группы) должны сохраняться.
 * 
 * Решение: Slot ID (стабильный) привязывается к:
 * - LDPlayer: ro.ld.player.index (0, 1, 2...)
 * - Memu: ro.memu.instance.id
 * - Nox: ro.nox.instance.id
 * - SD-карта: /sdcard/.sphere_slot (fallback)
 * - Ручная настройка в APK
 * 
 * Сервер хранит привязки slot_id → account/proxy/group
 * При регистрации агент отправляет slot_id, получает назначения.
 */
class SlotConfig(private val context: Context) {
    
    companion object {
        private const val TAG = "SlotConfig"
        
        // Файл на SD-карте для fallback
        private const val SD_SLOT_FILE = "/sdcard/.sphere_slot"
        private const val SD_ASSIGNMENT_FILE = "/sdcard/.sphere_assignment"
        
        // Emulator property names
        private val LDPLAYER_PROPS = listOf(
            "ro.ld.player.index",
            "ro.ld.adb.port"
        )
        private val MEMU_PROPS = listOf(
            "ro.memu.instance.id",
            "ro.memu.index"
        )
        private val NOX_PROPS = listOf(
            "ro.nox.instance.id",
            "ro.nox.player.index"
        )
    }
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    
    private val settingsRepository = SettingsRepository(context)
    
    // Cached slot info
    @Volatile private var cachedSlotId: String? = null
    @Volatile private var cachedSlotSource: SlotSource? = null
    @Volatile private var cachedAssignment: SlotAssignment? = null
    
    /**
     * Определяет Slot ID эмулятора.
     * 
     * Приоритет источников:
     * 1. LDPlayer props (ro.ld.player.index)
     * 2. Memu props (ro.memu.instance.id)
     * 3. Nox props (ro.nox.instance.id)
     * 4. SD-карта fallback (/sdcard/.sphere_slot)
     * 5. Ручная настройка в SharedPreferences
     * 6. Auto-assign на основе device_id
     * 
     * @param deviceId Текущий device_id (для auto fallback)
     * @return Pair<slotId, source>
     */
    fun detectSlotId(deviceId: String): Pair<String, SlotSource> {
        // Return cached if available
        if (cachedSlotId != null && cachedSlotSource != null) {
            return Pair(cachedSlotId!!, cachedSlotSource!!)
        }
        
        // 1. LDPlayer
        val ldIndex = getprop(LDPLAYER_PROPS[0])
        if (ldIndex.isNotBlank() && ldIndex != "unknown") {
            val slotId = "ld:$ldIndex"
            SphereLog.i(TAG, "Detected LDPlayer slot: $slotId")
            cacheSlot(slotId, SlotSource.LDPLAYER)
            return Pair(slotId, SlotSource.LDPLAYER)
        }
        
        // 2. Memu
        val memuId = getprop(MEMU_PROPS[0])
        if (memuId.isNotBlank() && memuId != "unknown") {
            val slotId = "memu:$memuId"
            SphereLog.i(TAG, "Detected Memu slot: $slotId")
            cacheSlot(slotId, SlotSource.MEMU)
            return Pair(slotId, SlotSource.MEMU)
        }
        
        // 3. Nox
        val noxId = getprop(NOX_PROPS[0])
        if (noxId.isNotBlank() && noxId != "unknown") {
            val slotId = "nox:$noxId"
            SphereLog.i(TAG, "Detected Nox slot: $slotId")
            cacheSlot(slotId, SlotSource.NOX)
            return Pair(slotId, SlotSource.NOX)
        }
        
        // 4. SD-карта fallback
        val sdSlot = readSdCardSlot()
        if (sdSlot != null) {
            SphereLog.i(TAG, "Slot from SD card: $sdSlot")
            cacheSlot(sdSlot, SlotSource.SDCARD)
            return Pair(sdSlot, SlotSource.SDCARD)
        }
        
        // 5. Ручная настройка
        val manualSlot = settingsRepository.getManualSlotId()
        if (manualSlot != null) {
            SphereLog.i(TAG, "Manual slot configured: $manualSlot")
            cacheSlot(manualSlot, SlotSource.MANUAL)
            return Pair(manualSlot, SlotSource.MANUAL)
        }
        
        // 6. Auto-assign
        val autoSlot = "auto:${deviceId.take(8)}"
        SphereLog.w(TAG, "No slot detected, using auto: $autoSlot")
        cacheSlot(autoSlot, SlotSource.AUTO)
        return Pair(autoSlot, SlotSource.AUTO)
    }
    
    /**
     * Получить свойство системы через getprop
     */
    private fun getprop(name: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", name))
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            result
        } catch (e: Exception) {
            SphereLog.d(TAG, "getprop $name failed: ${e.message}")
            ""
        }
    }
    
    /**
     * Читает slot_id с SD-карты
     */
    private fun readSdCardSlot(): String? {
        return try {
            val file = File(SD_SLOT_FILE)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                if (content.isNotBlank() && content.length >= 3) {
                    content
                } else null
            } else null
        } catch (e: Exception) {
            SphereLog.d(TAG, "Failed to read SD slot: ${e.message}")
            null
        }
    }
    
    /**
     * Сохраняет slot_id на SD-карту (для восстановления после пересоздания)
     */
    fun saveSlotToSdCard(slotId: String) {
        try {
            val file = File(SD_SLOT_FILE)
            file.writeText(slotId)
            SphereLog.d(TAG, "Saved slot to SD card: $slotId")
        } catch (e: Exception) {
            SphereLog.w(TAG, "Failed to save slot to SD: ${e.message}")
        }
    }
    
    /**
     * Ручная установка slot_id (из настроек APK)
     */
    fun setManualSlotId(slotId: String) {
        settingsRepository.setManualSlotId(slotId)
        cachedSlotId = slotId
        cachedSlotSource = SlotSource.MANUAL
        saveSlotToSdCard(slotId)  // Также сохраняем на SD
        SphereLog.i(TAG, "Manual slot ID set: $slotId")
    }
    
    private fun cacheSlot(slotId: String, source: SlotSource) {
        cachedSlotId = slotId
        cachedSlotSource = source
    }
    
    // ========================================================================
    // ASSIGNMENT HANDLING
    // ========================================================================
    
    /**
     * Сохраняет назначение (account, proxy, script) полученное от сервера
     */
    fun saveAssignment(assignment: SlotAssignment) {
        cachedAssignment = assignment
        
        // Сохраняем в SharedPreferences
        val jsonStr = json.encodeToString(assignment)
        settingsRepository.setSlotAssignment(jsonStr)
        
        // Также сохраняем на SD-карту как backup
        try {
            File(SD_ASSIGNMENT_FILE).writeText(jsonStr)
        } catch (e: Exception) {
            SphereLog.d(TAG, "Failed to save assignment to SD: ${e.message}")
        }
        
        SphereLog.i(TAG, "Assignment saved: account=${assignment.accountId}, proxy=${assignment.proxyId != null}")
    }
    
    /**
     * Получает сохранённое назначение
     */
    fun getAssignment(): SlotAssignment? {
        if (cachedAssignment != null) return cachedAssignment
        
        // Пробуем из SharedPreferences
        val jsonStr = settingsRepository.getSlotAssignment()
        if (jsonStr != null) {
            try {
                cachedAssignment = json.decodeFromString<SlotAssignment>(jsonStr)
                return cachedAssignment
            } catch (e: Exception) {
                SphereLog.w(TAG, "Failed to parse assignment: ${e.message}")
            }
        }
        
        // Fallback: SD-карта
        try {
            val file = File(SD_ASSIGNMENT_FILE)
            if (file.exists()) {
                val content = file.readText()
                cachedAssignment = json.decodeFromString<SlotAssignment>(content)
                return cachedAssignment
            }
        } catch (e: Exception) {
            SphereLog.d(TAG, "Failed to read assignment from SD: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Очищает кэш (для переподключения)
     */
    fun clearCache() {
        cachedSlotId = null
        cachedSlotSource = null
        cachedAssignment = null
    }
    
    /**
     * Получить полную информацию о слоте для логирования
     */
    fun getSlotInfo(deviceId: String): SlotInfo {
        val (slotId, source) = detectSlotId(deviceId)
        return SlotInfo(
            slotId = slotId,
            source = source,
            assignment = getAssignment(),
            deviceId = deviceId
        )
    }
}

/**
 * Источник определения slot_id
 */
enum class SlotSource {
    LDPLAYER,   // ro.ld.player.index
    MEMU,       // ro.memu.instance.id
    NOX,        // ro.nox.instance.id
    SDCARD,     // /sdcard/.sphere_slot
    MANUAL,     // Ручная настройка в APK
    AUTO        // Автоматически на основе device_id
}

/**
 * Назначение слота (от сервера)
 */
@Serializable
data class SlotAssignment(
    // Идентификация
    val slotId: String,
    val pcIdentifier: String? = null,
    
    // Назначенные ресурсы
    val accountId: String? = null,
    val accountUsername: String? = null,
    val accountSession: String? = null,  // Session token (не password!)
    
    val proxyId: String? = null,
    val proxyConfig: ProxyConfig? = null,
    
    val groupId: String? = null,
    val templateId: String? = null,
    
    // Автостарт
    val autoStartScriptId: String? = null,
    
    // Resume execution (если был disconnect)
    val resumeExecutionId: String? = null,
    val resumeStepIndex: Int? = null,
    val resumeVariables: Map<String, String>? = null,
    
    // Конфигурация
    val config: Map<String, String>? = null,
    
    // Timestamp
    val assignedAt: Long = System.currentTimeMillis()
)

/**
 * Конфигурация прокси
 */
@Serializable
data class ProxyConfig(
    val type: String,       // "socks5", "http", "none"
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null
)

/**
 * Полная информация о слоте (для диагностики)
 */
data class SlotInfo(
    val slotId: String,
    val source: SlotSource,
    val assignment: SlotAssignment?,
    val deviceId: String
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "slot_id" to slotId,
        "source" to source.name.lowercase(),
        "has_assignment" to (assignment != null),
        "account_username" to assignment?.accountUsername,
        "has_proxy" to (assignment?.proxyConfig != null),
        "device_id" to deviceId
    )
}
