package com.sphere.agent.vpn

import android.util.Base64

/**
 * VpnConfigParser v2.0.0 — парсинг и конвертация WireGuard/AWG конфигов
 *
 * Конвертирует INI формат (от WG Router) → UAPI формат (для libwg-go JNI).
 * Поддерживает AWG параметры обфускации (Jc, Jmin, Jmax, S1, S2, H1-H4).
 */

/**
 * Распарсенный WireGuard/AWG конфиг
 */
data class ParsedWgConfig(
    // [Interface]
    val privateKey: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int = 1280,
    val awgParams: Map<String, String> = emptyMap(),

    // [Peer]
    val publicKey: String,
    val endpoint: String,
    val allowedIps: List<String>,
    val persistentKeepalive: Int = 25,
)

object VpnConfigParser {

    /** AWG параметры обфускации — распознаются в [Interface] */
    private val AWG_PARAM_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")

    /**
     * Парсинг INI конфига WireGuard/AWG
     *
     * Формат входа:
     * ```
     * [Interface]
     * PrivateKey = base64...
     * Address = 10.0.0.2/24
     * DNS = 1.1.1.1, 8.8.8.8
     * Jc = 4
     * ...
     * [Peer]
     * PublicKey = base64...
     * Endpoint = 1.2.3.4:51820
     * AllowedIPs = 0.0.0.0/0
     * PersistentKeepalive = 25
     * ```
     */
    fun parse(configText: String): ParsedWgConfig {
        var section = ""
        var privateKey = ""
        var publicKey = ""
        var endpoint = ""
        val addresses = mutableListOf<String>()
        val dnsServers = mutableListOf<String>()
        val allowedIps = mutableListOf<String>()
        var mtu = 1280
        var keepalive = 25
        val awgParams = mutableMapOf<String, String>()

        for (line in configText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (trimmed.startsWith("[")) {
                section = trimmed.lowercase()
                continue
            }

            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()

            when (section) {
                "[interface]" -> when {
                    key.equals("PrivateKey", ignoreCase = true) -> privateKey = value
                    key.equals("Address", ignoreCase = true) -> {
                        addresses.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    key.equals("DNS", ignoreCase = true) -> {
                        dnsServers.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    key.equals("MTU", ignoreCase = true) -> mtu = value.toIntOrNull() ?: 1280
                    key.lowercase() in AWG_PARAM_KEYS -> awgParams[key.lowercase()] = value
                }
                "[peer]" -> when {
                    key.equals("PublicKey", ignoreCase = true) -> publicKey = value
                    key.equals("Endpoint", ignoreCase = true) -> endpoint = value
                    key.equals("AllowedIPs", ignoreCase = true) -> {
                        allowedIps.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    key.equals("PersistentKeepalive", ignoreCase = true) -> {
                        keepalive = value.toIntOrNull() ?: 25
                    }
                }
            }
        }

        return ParsedWgConfig(
            privateKey = privateKey,
            addresses = addresses,
            dnsServers = dnsServers,
            mtu = mtu,
            awgParams = awgParams,
            publicKey = publicKey,
            endpoint = endpoint,
            allowedIps = allowedIps,
            persistentKeepalive = keepalive,
        )
    }

    /**
     * Конвертация base64 ключа в hex для UAPI формата
     *
     * WireGuard UAPI требует ключи в hex, а конфиг-файлы используют base64.
     */
    fun base64ToHex(b64: String): String {
        val bytes = Base64.decode(b64.trim(), Base64.DEFAULT)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Генерация UAPI конфига для awgTurnOn()
     *
     * Формат выхода (передаётся в JNI):
     * ```
     * private_key=hex_key
     * jc=4
     * jmin=40
     * ...
     * replace_peers=true
     * public_key=hex_key
     * endpoint=1.2.3.4:51820
     * allowed_ip=0.0.0.0/0
     * persistent_keepalive_interval=25
     * ```
     */
    fun toUapiConfig(config: ParsedWgConfig): String {
        val sb = StringBuilder()

        // [Interface] → private_key + AWG параметры
        sb.appendLine("private_key=${base64ToHex(config.privateKey)}")

        // AWG обфускация (Jc, Jmin, Jmax, S1, S2, H1-H4)
        for ((key, value) in config.awgParams) {
            sb.appendLine("$key=$value")
        }

        // [Peer] → public_key, endpoint, allowed_ip, keepalive
        sb.appendLine("replace_peers=true")
        sb.appendLine("public_key=${base64ToHex(config.publicKey)}")

        if (config.endpoint.isNotEmpty()) {
            sb.appendLine("endpoint=${config.endpoint}")
        }

        // allowed_ip — по одной записи на строку (UAPI формат)
        for (ip in config.allowedIps) {
            sb.appendLine("allowed_ip=$ip")
        }

        if (config.persistentKeepalive > 0) {
            sb.appendLine("persistent_keepalive_interval=${config.persistentKeepalive}")
        }

        return sb.toString().trimEnd('\n')
    }

    /**
     * Создать VpnTunnelConfig из ParsedWgConfig для SphereVpnService
     */
    fun toTunnelConfig(config: ParsedWgConfig): VpnTunnelConfig {
        return VpnTunnelConfig(
            addresses = config.addresses,
            dnsServers = config.dnsServers,
            routes = config.allowedIps,
            mtu = config.mtu,
        )
    }
}
