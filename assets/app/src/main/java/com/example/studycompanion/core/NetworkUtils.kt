package com.example.studycompanion.core

import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    data class LocalEndpoint(val ip: String, val interfaceName: String, val hotspot: Boolean)

    internal fun isCellularOrVpn(name: String): Boolean = listOf("rmnet", "ccmni", "pdp", "wwan", "v4-rmnet", "tun", "tap", "ppp", "ipsec").any { name.lowercase().startsWith(it) }
    internal fun isHotspotInterface(name: String): Boolean = listOf("ap", "softap", "swlan", "wlan1", "bridge", "wifi_br").any { name.lowercase().startsWith(it) }

    /** Interface names are hints for presentation only; auto pairing does not depend on them. */
    fun getLocalEndpoints(): List<LocalEndpoint> {
        val endpoints = mutableListOf<LocalEndpoint>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces() ?: return emptyList()) {
                if (!iface.isUp || iface.isLoopback || isCellularOrVpn(iface.name)) continue
                for (addr in iface.inetAddresses) {
                    val ip=addr.hostAddress ?: continue
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        endpoints.add(LocalEndpoint(ip,iface.name,isHotspotInterface(iface.name)))
                    }
                }
            }
        } catch (e: Exception) { Logger.e("NetworkUtils", "Error getting local endpoints", e) }
        return endpoints.distinctBy { it.ip }.sortedWith(compareByDescending<LocalEndpoint> { it.hotspot }.thenBy { it.interfaceName }.thenBy { it.ip })
    }

    fun pairingLine(host: String, port: Int, token: String): String? {
        val address=host.trim()
        val ipv4=address.split('.').let { p ->
            p.size==4 && p.all { it.length in 1..3 && it.all { c -> c in '0'..'9' } && !(it.length>1 && it.startsWith('0')) && (it.toIntOrNull() ?: -1) in 0..255 } &&
                (p[0].toIntOrNull() ?: 0) in 1..223 && p[0]!="127"
        }
        if ((address!="auto" && !ipv4) || port !in 1..65535 || token.length !in 1..127 || token.any { it.code !in 33..126 }) return null
        return "$address $port $token\n"
    }

    fun getLocalIpAddresses(): List<String> {
        return getLocalEndpoints().map { it.ip }
    }

    fun isPrivateIp(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1" || ip == "::1" || ip == "localhost") return true
        return try {
            val address = InetAddress.getByName(ip)
            if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
                true
            } else {
                val bytes = address.address
                if (bytes.size == 4) {
                    val b0 = bytes[0].toInt() and 0xFF
                    val b1 = bytes[1].toInt() and 0xFF
                    // 10.0.0.0/8
                    if (b0 == 10) return true
                    // 172.16.0.0/12
                    if (b0 == 172 && b1 in 16..31) return true
                    // 192.168.0.0/16
                    if (b0 == 192 && b1 == 168) return true
                    // 169.254.0.0/16
                    if (b0 == 169 && b1 == 254) return true
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
