package dev.holgerendt.hanative.data

import java.net.InetAddress

/**
 * The app only talks to the local network: Home Assistant, go2rtc, and HA-provided media URLs.
 * Android's network-security XML cannot express IP ranges, so the "private networks only"
 * policy from network_security_config is enforced here at every egress point instead.
 *
 * Hostnames are allowed when DNS resolves to a private address (so LAN names that are not
 * `*.local` still work). Public / unresolved hosts are rejected.
 */
object NetworkGuard {

    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val authority = url.substring(schemeEnd + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val hostPart = if ('@' in authority) authority.substringAfterLast('@') else authority
        val host = when {
            hostPart.startsWith('[') -> hostPart.removePrefix("[").substringBefore(']')
            else -> hostPart.substringBefore(':')
        }
        return host.ifBlank { null }
    }

    fun isPrivateHost(host: String): Boolean {
        if (host.isBlank()) return false
        if (host == "localhost") return true
        if (host.endsWith(".local", ignoreCase = true)) return true
        if (host.contains(':')) return isPrivateIpv6(host)
        if (host.all { it in '0'..'9' || it == '.' }) return isPrivateIpv4(host)
        return resolvesToPrivateAddress(host)
    }

    private fun resolvesToPrivateAddress(host: String): Boolean =
        runCatching {
            InetAddress.getAllByName(host).any(::isPrivateInetAddress)
        }.getOrDefault(false)

    private fun isPrivateInetAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return true
        if (address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val host = address.hostAddress ?: return false
        return if (':' in host) isPrivateIpv6(host.substringBefore('%')) else isPrivateIpv4(host)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').mapNotNull { part ->
            if (part.length in 1..3) part.toIntOrNull()?.takeIf { it in 0..255 } else null
        }
        if (octets.size != 4) return false
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 127 || a == 0 -> true
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val groups = expandIpv6(host) ?: return false
        val first = groups[0]
        if (groups.all { it == 0L } && groups.last() == 1L) return true
        if (first and 0xFFC0L == 0xFE80L) return true
        if (first and 0xFE00L == 0xFC00L) return true
        return false
    }

    private fun expandIpv6(host: String): List<Long>? {
        val addr = host.substringBefore('%').lowercase()
        val halves = addr.split("::")
        if (halves.size > 2) return null
        val left = halves[0].split(':').filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull(16)?.takeIf { g -> g in 0L..0xFFFFL } }
        val right = if (halves.size == 2) {
            halves[1].split(':').filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull(16)?.takeIf { g -> g in 0L..0xFFFFL } }
        } else emptyList()
        if (halves.size == 2) {
            if (left.size + right.size >= 8) return null
            return left + List(8 - left.size - right.size) { 0L } + right
        }
        return if (left.size == 8) left else null
    }
}
