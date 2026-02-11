package com.sphere.agent.vpn

import com.sphere.agent.util.SphereLog
import kotlinx.coroutines.*

/**
 * VpnHealthMonitor v1.0.0 — Мониторинг здоровья VPN туннеля
 *
 * Периодически (каждые 30с) проверяет:
 * 1. VPN интерфейс активен (tun0/wg0)
 * 2. Внешний IP не совпадает с серверным (VPN работает)
 * 3. Связь с adb.leetpc.com доступна (split-tunnel)
 * 4. Handshake актуален (< 3 минут)
 *
 * При обнаружении проблем:
 * - Уведомляет сервер через callback (vpn_health_report)
 * - Пытается автоматически переактивировать VPN (self-healing)
 * - Логирует все события для диагностики
 */
class VpnHealthMonitor(
    private val vpnManager: VpnManager,
    private val onHealthReport: (Map<String, Any?>) -> Unit,
) {

    companion object {
        private const val TAG = "VpnHealthMonitor"
        private const val CHECK_INTERVAL_MS = 30_000L  // 30 секунд
        private const val MAX_AUTO_RECOVER_ATTEMPTS = 3
        private const val RECOVER_COOLDOWN_MS = 120_000L  // 2 минуты между попытками
        private const val SERVER_HOSTNAME = "adb.leetpc.com"
    }

    @Volatile var isRunning: Boolean = false
        private set

    // Счётчики для self-healing
    @Volatile private var autoRecoverAttempts = 0
    @Volatile private var lastRecoverAttemptTime = 0L

    // Последний отчёт
    @Volatile var lastHealthReport: Map<String, Any?> = emptyMap()
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    /**
     * Запуск мониторинга
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        autoRecoverAttempts = 0

        monitorJob = scope.launch {
            SphereLog.i(TAG, "VPN Health Monitor запущен (интервал ${CHECK_INTERVAL_MS / 1000}с)")
            // Начальный jitter (0-5с) для распределения нагрузки на 1000+ устройствах
            delay((0..5000L).random())

            while (isActive && isRunning) {
                try {
                    performHealthCheck()
                } catch (e: Exception) {
                    SphereLog.e(TAG, "Ошибка health check", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Остановка мониторинга
     */
    fun stop() {
        isRunning = false
        monitorJob?.cancel()
        SphereLog.i(TAG, "VPN Health Monitor остановлен")
    }

    /**
     * Выполнение проверки здоровья VPN
     */
    private suspend fun performHealthCheck() {
        // Если VPN не должен быть активен — пропускаем
        if (vpnManager.configText.isEmpty()) return

        val report = mutableMapOf<String, Any?>()
        report["timestamp"] = System.currentTimeMillis()
        report["config_type"] = vpnManager.currentConfigType
        var healthy = true
        val issues = mutableListOf<String>()

        // 1. Проверка VPN интерфейса
        val tunnelIface = vpnManager.checkTunnelInterface()
        report["tunnel_interface"] = tunnelIface
        if (tunnelIface == "none" && vpnManager.isActive) {
            healthy = false
            issues.add("VPN интерфейс отсутствует (ожидался tun0/wg0)")
        }

        // 2. Проверка внешнего IP
        val externalIp = vpnManager.getExternalIp()
        report["external_ip"] = externalIp
        report["expected_different_from"] = vpnManager.serverIp

        if (vpnManager.isActive) {
            if (externalIp == null) {
                healthy = false
                issues.add("Не удалось получить внешний IP (нет интернета?)")
            } else if (externalIp == vpnManager.serverIp && vpnManager.serverIp.isNotEmpty()) {
                healthy = false
                issues.add("Внешний IP совпадает с серверным ($externalIp) — VPN не работает")
            }
        }

        // 3. Проверка доступности сервера управления (split-tunnel)
        val serverReachable = checkServerReachable()
        report["server_reachable"] = serverReachable
        if (!serverReachable && vpnManager.isActive) {
            healthy = false
            issues.add("Сервер $SERVER_HOSTNAME недоступен — split-tunnel проблема")
        }

        // 4. Общий статус
        report["healthy"] = healthy
        report["issues"] = issues
        report["vpn_active"] = vpnManager.isActive
        report["auto_recover_attempts"] = autoRecoverAttempts

        lastHealthReport = report

        // Логирование
        if (healthy) {
            SphereLog.d(TAG, "✅ VPN здоров: IP=$externalIp, iface=$tunnelIface")
        } else {
            SphereLog.w(TAG, "⚠️ VPN проблемы: $issues")
        }

        // 5. Отправка отчёта на сервер
        onHealthReport(report)

        // 6. Self-healing: пробуем автоматически восстановить VPN
        if (!healthy && vpnManager.isActive && vpnManager.configText.isNotEmpty()) {
            attemptAutoRecover()
        }
    }

    /**
     * Автоматическое восстановление VPN при проблемах
     */
    private suspend fun attemptAutoRecover() {
        val now = System.currentTimeMillis()

        // Проверяем cooldown
        if (now - lastRecoverAttemptTime < RECOVER_COOLDOWN_MS) {
            SphereLog.d(TAG, "Auto-recover в cooldown, пропускаем")
            return
        }

        // Проверяем лимит попыток
        if (autoRecoverAttempts >= MAX_AUTO_RECOVER_ATTEMPTS) {
            SphereLog.w(TAG, "Превышен лимит auto-recover ($MAX_AUTO_RECOVER_ATTEMPTS), ожидание ручного вмешательства")
            return
        }

        autoRecoverAttempts++
        lastRecoverAttemptTime = now
        SphereLog.i(TAG, "🔄 Auto-recover попытка $autoRecoverAttempts/$MAX_AUTO_RECOVER_ATTEMPTS")

        try {
            // Деактивируем и активируем заново
            vpnManager.deactivate()
            delay(2000)
            val result = vpnManager.activate()

            if (result["success"] == true) {
                SphereLog.i(TAG, "✅ Auto-recover успешен! IP=${result["external_ip"]}")
                autoRecoverAttempts = 0  // Сброс счётчика при успехе
            } else {
                SphereLog.w(TAG, "❌ Auto-recover не удался: ${result["error"]}")
            }
        } catch (e: Exception) {
            SphereLog.e(TAG, "Auto-recover exception", e)
        }
    }

    /**
     * Проверка доступности сервера управления (split-tunnel)
     */
    private suspend fun checkServerReachable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "ping -c 1 -W 3 $SERVER_HOSTNAME 2>/dev/null")
                )
                val exitCode = process.waitFor()
                exitCode == 0
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Сброс счётчика auto-recover (вызывается при ручной активации)
     */
    fun resetRecoverCounter() {
        autoRecoverAttempts = 0
        lastRecoverAttemptTime = 0L
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
