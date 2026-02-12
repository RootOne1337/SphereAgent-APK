package com.sphere.agent.vpn

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*
import org.amnezia.awg.GoBackend

/**
 * VpnManager v2.0.0 — Встроенный AmneziaWG VPN (без внешних приложений)
 *
 * Управляет VPN туннелем НАПРЯМУЮ через встроенную библиотеку libwg-go.so.
 * Не зависит от внешних WireGuard/AWG приложений — полностью автономный.
 *
 * Архитектура:
 * 1. SphereVpnService создаёт TUN устройство через Android VpnService API
 * 2. GoBackend (JNI → libwg-go.so) управляет WireGuard/AWG протоколом
 * 3. VpnConfigParser конвертирует INI конфиг → UAPI формат для JNI
 * 4. ROOT используется для автогранта VPN permission (без UI диалога)
 *
 * Поддержка AWG обфускации: Jc, Jmin, Jmax, S1, S2, H1-H4
 */
class VpnManager(private val context: Context) {

    companion object {
        private const val TAG = "VpnManager"
        private const val TUNNEL_NAME = "awg_sphere"
        private const val IP_CHECK_TIMEOUT_MS = 10_000L
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

    // Хэндл активного туннеля (-1 = неактивен)
    @Volatile private var tunnelHandle: Int = -1

    // TUN файловый дескриптор
    @Volatile private var tunFd: ParcelFileDescriptor? = null

    // Распарсенный конфиг
    @Volatile private var parsedConfig: ParsedWgConfig? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Получить полный статус VPN для отправки на сервер
     */
    fun getStatus(): Map<String, Any?> {
        val awgVersion = try { GoBackend.awgVersion() } catch (e: Exception) { "n/a" }
        return mapOf(
            "vpn_active" to isActive,
            "vpn_config_type" to currentConfigType,
            "vpn_external_ip" to currentExternalIp,
            "vpn_last_activated" to lastActivatedAt,
            "vpn_last_error" to lastError,
            "vpn_has_config" to configText.isNotEmpty(),
            "vpn_tunnel_interface" to checkTunnelInterface(),
            "vpn_embedded" to true,
            "vpn_awg_version" to awgVersion,
            "vpn_tunnel_handle" to tunnelHandle,
        )
    }

    // ========================================================================
    // КОНФИГУРАЦИЯ
    // ========================================================================

    /**
     * Установить VPN конфиг (получен от сервера через WebSocket)
     *
     * @param config текст конфига в INI формате (WireGuard/AWG)
     * @param configType тип конфига: "awg" или "wg"
     */
    fun setConfig(config: String, configType: String = "awg") {
        configText = config
        currentConfigType = configType
        parsedConfig = VpnConfigParser.parse(config)
        val hasAwg = parsedConfig?.awgParams?.isNotEmpty() == true
        SphereLog.i(TAG, "VPN конфиг установлен: type=$configType, ${config.length} символов, AWG обфускация=$hasAwg")
    }

    // ========================================================================
    // АКТИВАЦИЯ VPN (ВСТРОЕННЫЙ AWG)
    // ========================================================================

    /**
     * Полный цикл активации VPN через встроенный GoBackend
     *
     * @return Map с результатом: {success, method, external_ip, error}
     */
    suspend fun activate(): Map<String, Any?> {
        SphereLog.i(TAG, "=== АКТИВАЦИЯ VPN v2.0 (ВСТРОЕННЫЙ AWG) ===")
        lastError = null

        val config = parsedConfig
        if (config == null || configText.isEmpty()) {
            lastError = "Конфиг не установлен — вызовите setConfig() сначала"
            return mapOf("success" to false, "error" to lastError)
        }

        // 1. Деактивируем предыдущий туннель если есть
        if (tunnelHandle != -1) {
            SphereLog.i(TAG, "Деактивируем предыдущий туннель (handle=$tunnelHandle)")
            try { GoBackend.awgTurnOff(tunnelHandle) } catch (e: Exception) { /* игнорируем */ }
            tunnelHandle = -1
        }
        closeTunFd()

        // 2. Создаём /dev/net/tun если нет (ROOT)
        execShell("[ -c /dev/net/tun ] || (mkdir -p /dev/net && mknod /dev/net/tun c 10 200) && chmod 666 /dev/net/tun")

        // 3. Выдаём VPN permission + consent нашему приложению через ROOT (без UI диалога)
        val pkg = context.packageName
        execShell("appops set $pkg ACTIVATE_VPN allow 2>/dev/null")
        execShell("appops set $pkg ACTIVATE_PLATFORM_VPN allow 2>/dev/null")
        // Критично: устанавливаем наше приложение как prepared VPN (обход VPN consent диалога)
        execShell("settings put secure always_on_vpn_app $pkg 2>/dev/null")
        execShell("settings put secure always_on_vpn_lockdown 0 2>/dev/null")
        // Альтернативный метод для Android 9+ (LDPlayer)
        execShell("cmd connectivity set-vpn-profile $pkg/.vpn.SphereVpnService 2>/dev/null")
        SphereLog.i(TAG, "VPN permission + consent выданы через ROOT: $pkg")

        // 4. Запускаем SphereVpnService
        try {
            val intent = Intent(context, SphereVpnService::class.java)
            context.startService(intent)
            // Ждём пока сервис инициализируется
            var waitMs = 0
            while (SphereVpnService.instance == null && waitMs < 5000) {
                delay(100)
                waitMs += 100
            }
        } catch (e: Exception) {
            lastError = "Не удалось запустить VPN Service: ${e.message}"
            SphereLog.e(TAG, lastError!!, e)
            return mapOf("success" to false, "error" to lastError)
        }

        val vpnService = SphereVpnService.instance
        if (vpnService == null) {
            lastError = "VPN Service не инициализирован (timeout 5s)"
            return mapOf("success" to false, "error" to lastError)
        }

        // 5. Проверяем VPN consent и создаём TUN устройство
        val prepareIntent = android.net.VpnService.prepare(context)
        if (prepareIntent != null) {
            SphereLog.w(TAG, "VPN consent не дан, пытаемся обойти через ROOT...")
            // Повторная попытка bypass consent
            execShell("settings put secure always_on_vpn_app ${context.packageName}")
            execShell("settings put secure always_on_vpn_lockdown 0")
            delay(1000)
            val retryIntent = android.net.VpnService.prepare(context)
            if (retryIntent != null) {
                SphereLog.w(TAG, "VPN consent всё ещё нужен — пробуем startActivity")
                try {
                    retryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(retryIntent)
                    // Автоматически принимаем диалог через ROOT UI automation
                    delay(2000)
                    execShell("input keyevent 22 && sleep 0.3 && input keyevent 66")
                    delay(1000)
                } catch (e: Exception) {
                    SphereLog.e(TAG, "Не удалось запустить VPN consent activity: ${e.message}")
                }
            }
        }

        // 5a. Создаём TUN устройство через VpnService
        val tunnelConfig = VpnConfigParser.toTunnelConfig(config)
        val tun = vpnService.createTun(tunnelConfig)
        if (tun == null) {
            lastError = "Не удалось создать TUN устройство (VPN consent не получен)"
            SphereLog.e(TAG, lastError!!)
            return mapOf("success" to false, "error" to lastError)
        }
        tunFd = tun

        // 6. Генерируем UAPI конфиг для JNI
        val uapiConfig = VpnConfigParser.toUapiConfig(config)
        SphereLog.i(TAG, "UAPI конфиг сгенерирован (${uapiConfig.length} символов)")

        // 7. Запускаем AWG туннель через JNI (GoBackend.awgTurnOn)
        try {
            val fd = tun.detachFd()
            SphereLog.i(TAG, "TUN fd=$fd, запускаем awgTurnOn...")
            tunnelHandle = GoBackend.awgTurnOn(TUNNEL_NAME, fd, uapiConfig)

            if (tunnelHandle < 0) {
                lastError = "awgTurnOn вернул ошибку: handle=$tunnelHandle"
                SphereLog.e(TAG, lastError!!)
                closeTunFd()
                return mapOf("success" to false, "error" to lastError, "method" to "embedded_awg")
            }
            SphereLog.i(TAG, "AWG туннель запущен: handle=$tunnelHandle")

            // 7a. КРИТИЧНО: защищаем WG UDP сокеты через VpnService.protect()
            // Без этого пакеты к WG серверу маршрутизируются через TUN → routing loop
            // Повторяем protect несколько раз (Go backend может создать сокет с задержкой)
            for (attempt in 1..3) {
                val socketV4 = GoBackend.awgGetSocketV4(tunnelHandle)
                val socketV6 = GoBackend.awgGetSocketV6(tunnelHandle)
                SphereLog.i(TAG, "protect attempt $attempt: socketV4=$socketV4, socketV6=$socketV6")
                if (socketV4 >= 0) {
                    val ok = vpnService.protect(socketV4)
                    SphereLog.i(TAG, "protect(socketV4=$socketV4) = $ok")
                }
                if (socketV6 >= 0) {
                    val ok = vpnService.protect(socketV6)
                    SphereLog.i(TAG, "protect(socketV6=$socketV6) = $ok")
                }
                if (socketV4 >= 0 || socketV6 >= 0) break
                delay(500) // Ждём создания сокета Go backend'ом
            }
        } catch (e: Exception) {
            lastError = "JNI ошибка awgTurnOn: ${e.message}"
            SphereLog.e(TAG, lastError!!, e)
            closeTunFd()
            return mapOf("success" to false, "error" to lastError, "method" to "embedded_awg")
        }

        // 8. Ждём WG handshake после protect (WG ретраит каждые 5с)
        SphereLog.i(TAG, "Ожидаем WG handshake после protect...")
        var handshakeOk = false
        for (i in 1..4) {
            delay(5000)
            // Проверяем handshake через UAPI: last_handshake_time_sec > 0 = success
            try {
                val uapiState = GoBackend.awgGetConfig(tunnelHandle)
                val hsMatch = Regex("""last_handshake_time_sec=(\d+)""").find(uapiState ?: "")
                val hsSec = hsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
                SphereLog.i(TAG, "WG handshake check #$i: last_handshake_time_sec=$hsSec")
                if (hsSec > 0) {
                    handshakeOk = true
                    break
                }
            } catch (e: Exception) {
                SphereLog.w(TAG, "Ошибка чтения UAPI конфига: ${e.message}")
            }
        }

        // 9. Проверяем внешний IP
        val externalIp = getExternalIp()
        currentExternalIp = externalIp ?: ""

        // Определяем успех: handshake завершён ИЛИ IP изменился
        val ipChanged = externalIp != null && externalIp != serverIp
        val vpnSuccess = handshakeOk || ipChanged

        if (vpnSuccess) {
            isActive = true
            lastActivatedAt = System.currentTimeMillis()
            val hasAwg = config.awgParams.isNotEmpty()
            SphereLog.i(TAG, "VPN АКТИВЕН: handshake=$handshakeOk, IP=$externalIp, handle=$tunnelHandle, AWG=$hasAwg")
        } else {
            isActive = false
            lastError = "VPN не подтверждён: handshake=$handshakeOk, IP=$externalIp"
            SphereLog.w(TAG, "VPN не подтверждён: handshake=$handshakeOk, IP=$externalIp, serverIp=$serverIp")
        }

        return mapOf(
            "success" to vpnSuccess,
            "method" to "embedded_awg",
            "external_ip" to currentExternalIp,
            "config_type" to currentConfigType,
            "tunnel_handle" to tunnelHandle,
            "awg_params" to config.awgParams,
            "error" to lastError,
        )
    }

    /**
     * Деактивация VPN
     */
    suspend fun deactivate(): Map<String, Any?> {
        SphereLog.i(TAG, "=== ДЕАКТИВАЦИЯ VPN ===")

        // Останавливаем AWG туннель через JNI
        if (tunnelHandle != -1) {
            try {
                GoBackend.awgTurnOff(tunnelHandle)
                SphereLog.i(TAG, "AWG туннель остановлен (handle=$tunnelHandle)")
            } catch (e: Exception) {
                SphereLog.e(TAG, "Ошибка awgTurnOff: ${e.message}", e)
            }
            tunnelHandle = -1
        }

        // Убираем always_on_vpn
        execShell("settings delete secure always_on_vpn_app 2>/dev/null")

        // Закрываем TUN fd
        closeTunFd()

        // Останавливаем VPN Service
        try {
            val intent = Intent(context, SphereVpnService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            SphereLog.w(TAG, "Ошибка остановки VPN Service: ${e.message}")
        }

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
    // УТИЛИТЫ
    // ========================================================================

    /**
     * Закрыть TUN файловый дескриптор
     */
    private fun closeTunFd() {
        try {
            tunFd?.close()
        } catch (e: Exception) { /* игнорируем */ }
        tunFd = null
    }

    /**
     * Проверка наличия VPN интерфейса
     */
    fun checkTunnelInterface(): String {
        return try {
            val result = execShellSync("ip link show 2>/dev/null | grep -oE '(tun|wg|awg)[0-9]+' | head -1")
            result.ifEmpty { "none" }
        } catch (e: Exception) {
            "error"
        }
    }

    /**
     * Получить внешний IP через curl
     *
     * Root (UID 0) обходит VPN через ip rule bypass (table 51820).
     * Для проверки VPN IP запускаем curl от UID 2000 (shell) — он маршрутизируется через TUN.
     * Если VPN неактивен — используем обычный root curl.
     */
    suspend fun getExternalIp(): String? {
        return try {
            val cmd = if (tunnelHandle >= 0) {
                // VPN активен: curl от UID 2000 (shell) идёт через VPN TUN
                "su 2000 -c 'curl -s --connect-timeout 5 --max-time 10 https://api.ipify.org' 2>/dev/null || " +
                "su 2000 -c 'curl -s --connect-timeout 5 --max-time 10 https://ifconfig.me' 2>/dev/null || " +
                "curl -s --connect-timeout 5 --max-time 8 https://api.ipify.org 2>/dev/null"
            } else {
                // VPN неактивен: обычный root curl
                "curl -s --connect-timeout 5 --max-time 8 https://api.ipify.org 2>/dev/null || " +
                "curl -s --connect-timeout 5 --max-time 8 https://ifconfig.me 2>/dev/null"
            }
            val result = execShell(cmd)
            val ip = result.output?.trim()
            if (ip != null && ip.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) {
                ip
            } else {
                SphereLog.w(TAG, "getExternalIp: ответ не содержит IP: '$ip'")
                null
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "Ошибка получения внешнего IP", e)
            null
        }
    }

    // ========================================================================
    // SHELL EXECUTION (ROOT)
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
        if (tunnelHandle != -1) {
            try { GoBackend.awgTurnOff(tunnelHandle) } catch (_: Exception) {}
            tunnelHandle = -1
        }
        closeTunFd()
        scope.cancel()
        SphereLog.i(TAG, "VpnManager destroyed")
    }
}
