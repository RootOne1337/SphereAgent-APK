package com.sphere.agent.vpn

import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*

/**
 * VpnKillSwitch v1.0.0 — Блокировка интернета при падении VPN
 *
 * Kill-switch гарантирует, что приложения (игры) НЕ могут выходить в интернет
 * с нативным IP устройства, если VPN туннель упал.
 *
 * Реализация через iptables на ROOT устройстве:
 * - Блокируем весь исходящий трафик по умолчанию
 * - Разрешаем трафик через VPN интерфейс (tun0/wg0)
 * - Разрешаем трафик к серверу управления (adb.leetpc.com) напрямую
 * - Разрешаем DNS
 * - Разрешаем трафик от SphereAgent (для WebSocket)
 *
 * ВАЖНО: Kill-switch активируется ТОЛЬКО когда VPN должен быть активен.
 * При деактивации VPN — kill-switch снимается.
 */
class VpnKillSwitch {

    companion object {
        private const val TAG = "VpnKillSwitch"

        // SphereAgent UID — разрешаем всегда (WebSocket к серверу)
        // Определяется динамически при инициализации
        private const val AGENT_PACKAGE = "com.sphere.agent"

        // Fallback IP сервера управления (используется если DNS resolve не удался)
        private const val DEFAULT_SERVER_IP = "212.220.204.72"  // adb.leetpc.com

        // Fallback VPN endpoint (используется если конфиг не предоставлен)
        private const val DEFAULT_WG_ENDPOINT_IP = "2.56.122.229"

        // Hostname сервера управления для DNS resolve
        private const val SERVER_HOSTNAME = "adb.leetpc.com"

        // Имя iptables chain для наших правил
        private const val CHAIN_NAME = "SPHERE_KILLSWITCH"
    }

    @Volatile var isEnabled: Boolean = false
        private set

    @Volatile var agentUid: Int = -1
        private set

    // Динамические IP — обновляются при enable() и updateIps()
    @Volatile var serverIp: String = DEFAULT_SERVER_IP
        private set

    @Volatile var wgEndpointIp: String = DEFAULT_WG_ENDPOINT_IP
        private set

    /**
     * Инициализация: определяем UID SphereAgent
     */
    fun initialize(context: android.content.Context) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(AGENT_PACKAGE, 0)
            agentUid = appInfo.uid
            SphereLog.i(TAG, "SphereAgent UID: $agentUid")
        } catch (e: Exception) {
            SphereLog.e(TAG, "Не удалось определить UID агента", e)
        }
        // DNS resolve сервера управления при инициализации
        resolveServerIp()
    }

    /**
     * Обновить IP адреса для kill-switch правил.
     * Вызывается при получении нового VPN конфига или смене сервера.
     *
     * @param newServerIp IP сервера управления (adb.leetpc.com)
     * @param newWgEndpointIp IP WireGuard endpoint из VPN конфига
     */
    fun updateIps(newServerIp: String? = null, newWgEndpointIp: String? = null) {
        if (!newServerIp.isNullOrBlank()) {
            serverIp = newServerIp
            SphereLog.i(TAG, "Server IP обновлён: $serverIp")
        }
        if (!newWgEndpointIp.isNullOrBlank()) {
            wgEndpointIp = newWgEndpointIp
            SphereLog.i(TAG, "WG Endpoint IP обновлён: $wgEndpointIp")
        }
        // Если kill-switch уже включён — перезагружаем правила с новыми IP
        if (isEnabled) {
            SphereLog.i(TAG, "Kill-switch активен, перезагружаем правила с новыми IP")
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                disable()
                enable()
            }
        }
    }

    /**
     * DNS resolve hostname сервера управления → актуальный IP.
     * Вызывается при инициализации и периодически.
     */
    fun resolveServerIp() {
        try {
            val addresses = java.net.InetAddress.getAllByName(SERVER_HOSTNAME)
            if (addresses.isNotEmpty()) {
                val resolved = addresses[0].hostAddress
                if (resolved != null && resolved != serverIp) {
                    SphereLog.i(TAG, "DNS resolve $SERVER_HOSTNAME → $resolved (было: $serverIp)")
                    serverIp = resolved
                }
            }
        } catch (e: Exception) {
            SphereLog.w(TAG, "DNS resolve $SERVER_HOSTNAME не удался: ${e.message}, используем $serverIp")
        }
    }

    /**
     * Включить kill-switch
     *
     * Блокирует весь исходящий трафик кроме:
     * - VPN интерфейс (tun0/wg0)
     * - Сервер управления (adb.leetpc.com)
     * - WG endpoint (для handshake)
     * - DNS запросы
     * - Трафик от SphereAgent (WebSocket)
     * - localhost
     */
    suspend fun enable(): Boolean {
        if (isEnabled) {
            SphereLog.d(TAG, "Kill-switch уже включён")
            return true
        }

        SphereLog.i(TAG, "=== ВКЛЮЧЕНИЕ KILL-SWITCH ===")

        return withContext(Dispatchers.IO) {
            try {
                // DNS resolve актуального IP сервера перед включением
                resolveServerIp()

                // 1. Создаём кастомный chain
                exec("iptables -N $CHAIN_NAME 2>/dev/null || iptables -F $CHAIN_NAME")

                // 2. Разрешаем localhost
                exec("iptables -A $CHAIN_NAME -o lo -j ACCEPT")

                // 3. Разрешаем трафик через VPN интерфейс
                exec("iptables -A $CHAIN_NAME -o tun0 -j ACCEPT")
                exec("iptables -A $CHAIN_NAME -o wg0 -j ACCEPT")
                exec("iptables -A $CHAIN_NAME -o awg0 -j ACCEPT")

                // 4. Разрешаем трафик к серверу управления (динамический IP)
                exec("iptables -A $CHAIN_NAME -d $serverIp -j ACCEPT")

                // 5. Разрешаем трафик к WG endpoint (динамический IP из конфига)
                exec("iptables -A $CHAIN_NAME -d $wgEndpointIp -j ACCEPT")

                // 6. Разрешаем DNS (порт 53 UDP/TCP)
                exec("iptables -A $CHAIN_NAME -p udp --dport 53 -j ACCEPT")
                exec("iptables -A $CHAIN_NAME -p tcp --dport 53 -j ACCEPT")

                // 7. Разрешаем трафик от SphereAgent (WebSocket к серверу)
                if (agentUid > 0) {
                    exec("iptables -A $CHAIN_NAME -m owner --uid-owner $agentUid -j ACCEPT")
                }

                // 8. Разрешаем ESTABLISHED/RELATED (ответы на разрешённые соединения)
                exec("iptables -A $CHAIN_NAME -m state --state ESTABLISHED,RELATED -j ACCEPT")

                // 9. Блокируем всё остальное (DROP)
                exec("iptables -A $CHAIN_NAME -j DROP")

                // 10. Вставляем chain в OUTPUT
                // Сначала удаляем старые ссылки
                exec("iptables -D OUTPUT -j $CHAIN_NAME 2>/dev/null")
                exec("iptables -I OUTPUT 1 -j $CHAIN_NAME")

                isEnabled = true
                SphereLog.i(TAG, "✅ Kill-switch включён (agent UID=$agentUid)")
                true
            } catch (e: Exception) {
                SphereLog.e(TAG, "Ошибка включения kill-switch", e)
                // Откатываем при ошибке
                disable()
                false
            }
        }
    }

    /**
     * Выключить kill-switch — разблокировать весь трафик
     */
    suspend fun disable(): Boolean {
        SphereLog.i(TAG, "=== ВЫКЛЮЧЕНИЕ KILL-SWITCH ===")

        return withContext(Dispatchers.IO) {
            try {
                // Удаляем ссылку из OUTPUT
                exec("iptables -D OUTPUT -j $CHAIN_NAME 2>/dev/null")

                // Очищаем и удаляем chain
                exec("iptables -F $CHAIN_NAME 2>/dev/null")
                exec("iptables -X $CHAIN_NAME 2>/dev/null")

                isEnabled = false
                SphereLog.i(TAG, "✅ Kill-switch выключен")
                true
            } catch (e: Exception) {
                SphereLog.e(TAG, "Ошибка выключения kill-switch", e)
                false
            }
        }
    }

    /**
     * Проверить текущий статус kill-switch через iptables
     */
    fun checkStatus(): Map<String, Any?> {
        return try {
            val rules = execSync("iptables -L $CHAIN_NAME -n 2>/dev/null")
            val inOutput = execSync("iptables -L OUTPUT -n 2>/dev/null | grep $CHAIN_NAME")

            mapOf(
                "enabled" to isEnabled,
                "chain_exists" to rules.isNotEmpty(),
                "chain_in_output" to inOutput.isNotEmpty(),
                "rules_count" to rules.lines().filter { it.isNotBlank() && !it.startsWith("Chain") && !it.startsWith("target") }.size,
                "agent_uid" to agentUid,
                "server_ip" to serverIp,
                "wg_endpoint_ip" to wgEndpointIp,
            )
        } catch (e: Exception) {
            mapOf("enabled" to isEnabled, "error" to e.message)
        }
    }

    private suspend fun exec(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                output
            } catch (e: Exception) {
                ""
            }
        }
    }

    private fun execSync(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
        }
    }
}
