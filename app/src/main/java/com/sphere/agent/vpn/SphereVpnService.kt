package com.sphere.agent.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.sphere.agent.util.SphereLog

/**
 * SphereVpnService v2.0.0 — встроенный VPN сервис для AmneziaWG
 *
 * Создаёт TUN устройство через Android VpnService API.
 * Управляется из VpnManager — НЕ требует внешних приложений.
 *
 * Жизненный цикл:
 * 1. VpnManager запускает сервис через startService()
 * 2. Сервис создаёт TUN через Builder.establish()
 * 3. TUN fd передаётся в GoBackend.awgTurnOn()
 * 4. При деактивации — awgTurnOff() + закрытие TUN fd
 */
class SphereVpnService : VpnService() {

    companion object {
        private const val TAG = "SphereVpnService"

        /** Синглтон для доступа из VpnManager */
        @Volatile
        var instance: SphereVpnService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SphereLog.i(TAG, "VPN Service создан")
    }

    override fun onDestroy() {
        instance = null
        SphereLog.i(TAG, "VPN Service уничтожен")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        return START_STICKY
    }

    /**
     * Создать TUN устройство для VPN туннеля
     *
     * @param config параметры для TUN (адреса, DNS, маршруты)
     * @return ParcelFileDescriptor TUN устройства или null при ошибке
     */
    fun createTun(config: VpnTunnelConfig): ParcelFileDescriptor? {
        try {
            val builder = Builder()
                .setSession("SphereAWG")
                .setMtu(config.mtu)
                .setBlocking(true)

            // Адреса интерфейса из [Interface] Address
            for (addr in config.addresses) {
                val parts = addr.split("/")
                val ip = parts[0].trim()
                val prefix = parts.getOrElse(1) { "32" }.trim().toInt()
                builder.addAddress(ip, prefix)
            }

            // DNS серверы из [Interface] DNS
            for (dns in config.dnsServers) {
                val trimmed = dns.trim()
                if (trimmed.isNotEmpty()) {
                    builder.addDnsServer(trimmed)
                }
            }

            // Маршруты из [Peer] AllowedIPs
            for (route in config.routes) {
                val parts = route.split("/")
                val ip = parts[0].trim()
                val prefix = parts.getOrElse(1) { "0" }.trim().toInt()
                builder.addRoute(ip, prefix)
            }

            // Исключаем наше приложение из VPN (чтобы WebSocket к серверу работал)
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                SphereLog.w(TAG, "Не удалось исключить приложение из VPN: ${e.message}")
            }

            val tun = builder.establish()
            if (tun != null) {
                SphereLog.i(TAG, "TUN устройство создано: addresses=${config.addresses}")
            } else {
                SphereLog.e(TAG, "Builder.establish() вернул null — нет VPN permission?")
            }
            return tun
        } catch (e: Exception) {
            SphereLog.e(TAG, "Ошибка создания TUN устройства", e)
            return null
        }
    }
}

/**
 * Конфигурация VPN туннеля для создания TUN устройства
 */
data class VpnTunnelConfig(
    val addresses: List<String>,    // ["10.103.233.2/24"]
    val dnsServers: List<String>,   // ["1.1.1.1", "8.8.8.8"]
    val routes: List<String>,       // ["0.0.0.0/0"]
    val mtu: Int = 1280,
)
