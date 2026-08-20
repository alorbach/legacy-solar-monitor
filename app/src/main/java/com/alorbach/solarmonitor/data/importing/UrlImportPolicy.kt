package com.alorbach.solarmonitor.data.importing

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL import allowlist: HTTPS anywhere; HTTP only to private/loopback/link-local hosts.
 * Does not perform DNS lookup (avoids SSRF via attacker-controlled hostnames).
 */
object UrlImportPolicy {
    fun isAllowed(raw: String): Boolean {
        val url = raw.trim().toHttpUrlOrNull() ?: return false
        return isAllowed(url)
    }

    fun isAllowed(url: HttpUrl): Boolean = when (url.scheme.lowercase()) {
        "https" -> true
        "http" -> isPrivateOrLocalHost(url.host)
        else -> false
    }

    fun isPrivateOrLocalHost(host: String): Boolean {
        val trimmed = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (trimmed.isEmpty()) return false
        if (trimmed == "localhost" || trimmed.endsWith(".localhost")) return true
        parseIpv4(trimmed)?.let { return isPrivateIpv4(it) }
        return isPrivateIpv6(trimmed)
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = IntArray(4)
        for (index in 0 until 4) {
            val value = parts[index].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            nums[index] = value
        }
        return nums
    }

    private fun isPrivateIpv4(octets: IntArray): Boolean {
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
            a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) ||
            a == 0
    }

    private fun isPrivateIpv6(host: String): Boolean {
        if (':' !in host) return false
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        val mapped = host.substringAfterLast(":").takeIf { host.contains("::ffff:", ignoreCase = true) }
        if (mapped != null && '.' in mapped) {
            parseIpv4(mapped)?.let { return isPrivateIpv4(it) }
        }
        val first = host.substringBefore(':')
        val hextet = first.toIntOrNull(16) ?: return false
        // fe80::/10 link-local, fc00::/7 unique local
        return hextet in 0xfe80..0xfebf || hextet in 0xfc00..0xfdff
    }
}
