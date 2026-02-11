package com.sphere.agent.vpn

import android.content.Context
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*
import java.io.File

/**
 * VpnManager v1.0.0 — Программное управление AmneziaWG/WireGuard VPN
 *
 * Управляет VPN туннелем на Android эмуляторе с ROOT доступом.
 * Поддерживает два метода активации:
 * 1. WireGuard Android app (org.amnezia.awg / com.wireguard.android)
 * 2. wg-quick (если есть kernel module — редко на эмуляторах)
 *
 * Алгоритм активации:
 * 1. Записать конфиг на устройство (base64 → файл)
 * 2. Установить AWG/WG app если нет
 * 3. Скопировать конфиг в app data directory
 * 4. Дать разрешение ACTIVATE_VPN через appops
 * 5. Перезапустить app → конфиг подхватывается автоматически
 * 6. Активировать туннель через shell broadcast или UI automation
 * 7. Проверить внешний IP для подтверждения
 *
 * КРИТИЧНО: Эмуляторы (LDPlayer, Memu) НЕ имеют kernel WireGuard module.
 * Рабочий метод — ТОЛЬКО userspace через Android app.
 */
class VpnManager(private val context: Context) {

    companion object {
        private const val TAG = "VpnManager"

        // AmneziaWG пакет (приоритет)
        const val AWG_PACKAGE = "org.amnezia.awg"
        const val AWG_TUNNEL_NAME = "awg_sphere"

        // WireGuard пакет (fallback)
        const val WG_PACKAGE = "com.wireguard.android"
        const val WG_TUNNEL_NAME = "wg_sphere"

        // Пути конфигов
        const val CONFIG_DIR = "/sdcard/Download"
        const val AWG_CONFIG_PATH = "$CONFIG_DIR/$AWG_TUNNEL_NAME.conf"
        const val WG_CONFIG_PATH = "$CONFIG_DIR/$WG_TUNNEL_NAME.conf"

        // Таймауты
        private const val ACTIVATION_TIMEOUT_MS = 15_000L
        private const val IP_CHECK_TIMEOUT_MS = 10_000L
        private const val COMMAND_TIMEOUT_MS = 5_000L
    }

    // Текущее состояние VPN
    @Volatile var isActive: Boolean = false
        private set
    @Volatile var currentConfigType: String = "none"  // "awg", "wg", "none"
        private set
    @Volatile var currentExternalIp: String = ""
        private set
    @Volatile var lastActivatedAt: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var configText: String = ""
        private set

    // Серверный IP для проверки (НЕ должен совпадать с VPN IP)
    @Volatile var serverIp: String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Получить полный статус VPN для отправки на сервер
     */
    fun getStatus(): Map<String, Any?> {
        return mapOf(
            "vpn_active" to isActive,
            "vpn_config_type" to currentConfigType,
            "vpn_external_ip" to currentExternalIp,
            "vpn_last_activated" to lastActivatedAt,
            "vpn_last_error" to lastError,
            "vpn_has_config" to configText.isNotEmpty(),
            "vpn_tunnel_interface" to checkTunnelInterface(),
        )
    }

    // ========================================================================
    // КОНФИГУРАЦИЯ
    // ========================================================================

    /**
     * Установить VPN конфиг (получен от сервера через WebSocket)
     */
    fun setConfig(config: String, configType: String = "awg") {
        configText = config
        currentConfigType = configType
        SphereLog.i(TAG, "VPN конфиг установлен: type=$configType, ${config.length} символов")
    }

    /**
     * Записать конфиг в файл на устройстве
     */
    suspend fun writeConfigToDevice(): Boolean {
        if (configText.isEmpty()) {
            lastError = "Конфиг пустой"
            return false
        }

        return try {
            val configPath = if (currentConfigType == "awg") AWG_CONFIG_PATH else WG_CONFIG_PATH
            // base64 encoding для защиты от shell injection
            val b64 = android.util.Base64.encodeToString(
                configText.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val cmd = "echo '$b64' | base64 -d > $configPath && chmod 644 $configPath"
            val result = execShell(cmd)
            if (result.success) {
                SphereLog.i(TAG, "Конфиг записан: $configPath")
                true
            } else {
                lastError = "Ошибка записи конфига: ${result.error}"
                SphereLog.e(TAG, "Ошибка записи конфига: ${result.error}")
                false
            }
        } catch (e: Exception) {
            lastError = "Exception записи конфига: ${e.message}"
            SphereLog.e(TAG, "Exception записи конфига", e)
            false
        }
    }

    // ========================================================================
    // АКТИВАЦИЯ VPN
    // ========================================================================

    /**
     * Полный цикл активации VPN
     *
     * @return Map с результатом: {success, method, external_ip, error}
     */
    suspend fun activate(): Map<String, Any?> {
        SphereLog.i(TAG, "=== АКТИВАЦИЯ VPN (type=$currentConfigType) ===")
        lastError = null

        // 1. Записываем конфиг на устройство
        if (!writeConfigToDevice()) {
            return errorResult("Не удалось записать конфиг на устройство")
        }

        // 2. Определяем пакет VPN app
        val vpnPackage = when {
            isPackageInstalled(AWG_PACKAGE) -> AWG_PACKAGE
            isPackageInstalled(WG_PACKAGE) -> WG_PACKAGE
            else -> {
                SphereLog.w(TAG, "AWG/WG app не установлен")
                return errorResult("AWG/WG app не установлен — установите $AWG_PACKAGE или $WG_PACKAGE")
            }
        }
        val tunnelName = if (vpnPackage == AWG_PACKAGE) AWG_TUNNEL_NAME else WG_TUNNEL_NAME
        val configPath = if (vpnPackage == AWG_PACKAGE) AWG_CONFIG_PATH else WG_CONFIG_PATH

        SphereLog.i(TAG, "Используем VPN app: $vpnPackage")

        // 3. Копируем конфиг в app data directory
        val appConfigDir = "/data/data/$vpnPackage/files"
        val appConfigPath = "$appConfigDir/$tunnelName.conf"
        execShell("mkdir -p $appConfigDir")
        val copyResult = execShell("cp $configPath $appConfigPath && chown $(stat -c %u $appConfigDir) $appConfigPath && chmod 600 $appConfigPath")
        if (!copyResult.success) {
            SphereLog.w(TAG, "Не удалось скопировать конфиг в app data: ${copyResult.error}")
            // Не фатально — app может подхватить из /sdcard
        }

        // 4. Даём разрешение ACTIVATE_VPN
        execShell("appops set $vpnPackage ACTIVATE_VPN allow 2>/dev/null")
        execShell("appops set $vpnPackage ACTIVATE_PLATFORM_VPN allow 2>/dev/null")

        // 5. Создаём /dev/net/tun если нет
        execShell("[ -c /dev/net/tun ] || mknod /dev/net/tun c 10 200 && chmod 666 /dev/net/tun")

        // 6. Перезапускаем VPN app (подхватит конфиг)
        execShell("am force-stop $vpnPackage")
        delay(500)
        execShell("am start -n $vpnPackage/.activity.MainActivity 2>/dev/null || am start -n $vpnPackage/.MainActivity 2>/dev/null")
        delay(2000)

        // 7. Пробуем активировать через wg-quick (kernel mode) — обычно не работает на эмуляторах
        var method = "none"
        val wgQuickResult = execShell("wg-quick up $configPath 2>&1")
        if (wgQuickResult.success && (wgQuickResult.output?.contains("error") != true)) {
            method = "wg-quick"
            SphereLog.i(TAG, "VPN активирован через wg-quick")
        } else {
            // 8. Активация через UI automation (Android app)
            SphereLog.i(TAG, "wg-quick не работает, пробуем UI automation...")
            val uiResult = activateViaUiAutomation(vpnPackage)
            if (uiResult) {
                method = "android_app_ui"
                SphereLog.i(TAG, "VPN активирован через UI automation")
            } else {
                // 9. Fallback: broadcast intent
                val broadcastResult = activateViaBroadcast(vpnPackage, tunnelName)
                if (broadcastResult) {
                    method = "broadcast"
                    SphereLog.i(TAG, "VPN активирован через broadcast")
                }
            }
        }

        // 10. Проверяем внешний IP
        delay(3000)
        val externalIp = getExternalIp()
        currentExternalIp = externalIp ?: ""

        // Определяем успех: IP изменился и не совпадает с серверным
        val vpnSuccess = externalIp != null && externalIp != serverIp && method != "none"

        if (vpnSuccess) {
            isActive = true
            lastActivatedAt = System.currentTimeMillis()
            SphereLog.i(TAG, "✅ VPN АКТИВЕН: IP=$externalIp, method=$method")
        } else {
            isActive = false
            lastError = "VPN не подтверждён: IP=$externalIp, method=$method"
            SphereLog.w(TAG, "⚠️ VPN не подтверждён: IP=$externalIp, method=$method")
        }

        return mapOf(
            "success" to vpnSuccess,
            "method" to method,
            "external_ip" to currentExternalIp,
            "config_type" to currentConfigType,
            "error" to lastError,
        )
    }

    /**
     * Деактивация VPN
     */
    suspend fun deactivate(): Map<String, Any?> {
        SphereLog.i(TAG, "=== ДЕАКТИВАЦИЯ VPN ===")

        // wg-quick down
        execShell("wg-quick down $AWG_CONFIG_PATH 2>/dev/null")
        execShell("wg-quick down $WG_CONFIG_PATH 2>/dev/null")

        // Остановка VPN apps
        execShell("am force-stop $AWG_PACKAGE 2>/dev/null")
        execShell("am force-stop $WG_PACKAGE 2>/dev/null")

        // Удаление интерфейсов
        execShell("ip link delete wg0 2>/dev/null")
        execShell("ip link delete tun0 2>/dev/null")

        isActive = false
        currentExternalIp = ""
        lastError = null

        delay(1000)
        val ip = getExternalIp()
        SphereLog.i(TAG, "VPN деактивирован, текущий IP: $ip")

        return mapOf(
            "success" to true,
            "external_ip" to (ip ?: ""),
        )
    }

    // ========================================================================
    // UI AUTOMATION ДЛЯ VPN APP
    // ========================================================================

    /**
     * Активация VPN через UI automation (uiautomator dump + input tap)
     */
    private suspend fun activateViaUiAutomation(vpnPackage: String): Boolean {
        try {
            // Dump UI hierarchy
            execShell("uiautomator dump /sdcard/Download/ui_dump.xml 2>/dev/null")
            delay(1000)

            val dumpResult = execShell("cat /sdcard/Download/ui_dump.xml 2>/dev/null")
            val xml = dumpResult.output ?: return false

            // Ищем toggle/switch для VPN
            val switchPattern = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]".*?class="android\.widget\.(Switch|ToggleButton|CompoundButton)""")
            val match = switchPattern.find(xml)
            if (match != null) {
                val x1 = match.groupValues[1].toInt()
                val y1 = match.groupValues[2].toInt()
                val x2 = match.groupValues[3].toInt()
                val y2 = match.groupValues[4].toInt()
                val cx = (x1 + x2) / 2
                val cy = (y1 + y2) / 2

                SphereLog.i(TAG, "Найден VPN switch на ($cx, $cy)")
                execShell("input tap $cx $cy")
                delay(2000)

                // Подтверждаем VPN permission dialog если появился
                execShell("input keyevent 22 && input keyevent 66")  // Tab + Enter
                delay(1000)

                return true
            }

            SphereLog.w(TAG, "Switch не найден в UI dump")
            return false
        } catch (e: Exception) {
            SphereLog.e(TAG, "UI automation ошибка", e)
            return false
        }
    }

    /**
     * Активация через broadcast intent
     */
    private suspend fun activateViaBroadcast(vpnPackage: String, tunnelName: String): Boolean {
        // Метод для AWG
        if (vpnPackage == AWG_PACKAGE) {
            val r = execShell("am broadcast -a org.amnezia.awg.action.SET_TUNNEL_UP -n $vpnPackage/.BroadcastReceiver -e tunnel $tunnelName 2>/dev/null")
            delay(2000)
            return r.success
        }
        // Метод для WG
        val r = execShell("am broadcast -a com.wireguard.android.action.SET_TUNNEL_UP -n $vpnPackage/.BroadcastReceiver -e tunnel $tunnelName 2>/dev/null")
        delay(2000)
        return r.success
    }

    // ========================================================================
    // УТИЛИТЫ
    // ========================================================================

    /**
     * Проверка наличия VPN интерфейса (tun0, wg0, awg0)
     */
    fun checkTunnelInterface(): String {
        return try {
            val result = execShellSync("ip link show 2>/dev/null | grep -E 'tun0|wg0|awg0' | head -1")
            when {
                result.contains("tun0") -> "tun0"
                result.contains("wg0") -> "wg0"
                result.contains("awg0") -> "awg0"
                else -> "none"
            }
        } catch (e: Exception) {
            "error"
        }
    }

    /**
     * Получить внешний IP через curl
     */
    suspend fun getExternalIp(): String? {
        return try {
            val result = execShell("curl -s --connect-timeout 5 --max-time 8 https://api.ipify.org 2>/dev/null || curl -s --connect-timeout 5 --max-time 8 https://ifconfig.me 2>/dev/null")
            val ip = result.output?.trim()
            if (ip != null && ip.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) {
                ip
            } else {
                null
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "Ошибка получения внешнего IP", e)
            null
        }
    }

    /**
     * Проверить установлен ли пакет
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            val result = execShellSync("pm list packages $packageName 2>/dev/null")
            result.contains(packageName)
        } catch (e: Exception) {
            false
        }
    }

    // ========================================================================
    // SHELL EXECUTION
    // ========================================================================

    data class ShellResult(val success: Boolean, val output: String?, val error: String?)

    private suspend fun execShell(command: String): ShellResult {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = process.inputStream.bufferedReader().readText().trim()
                val error = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                ShellResult(exitCode == 0, output.ifEmpty { null }, error.ifEmpty { null })
            } catch (e: Exception) {
                ShellResult(false, null, e.message)
            }
        }
    }

    private fun execShellSync(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Освобождение ресурсов
     */
    fun destroy() {
        scope.cancel()
        SphereLog.i(TAG, "VpnManager destroyed")
    }
}
