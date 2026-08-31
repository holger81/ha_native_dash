package dev.holgerendt.hanative.data

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * The app only talks to the local network: Home Assistant, go2rtc, and HA-provided media URLs.
 * Android's network-security XML cannot express IP ranges, so the "private networks only"
 * policy from network_security_config is enforced here at every egress point instead.
 *
 * Hostnames are allowed when DNS resolves to a private address (LAN DDNS, *.local, etc.).
 * Public or unresolvable hosts are rejected.
 */
object NetworkGuard {

    private val hostCache = ConcurrentHashMap<String, Boolean>()

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
        return host.ifBlank { null }?.normalizeHost()
    }

    fun isPrivateHost(host: String): Boolean {
        val normalized = host.normalizeHost()
        if (normalized.isBlank()) return false
        hostCache[normalized]?.let { return it }
        val allowed = isPrivateHostUncached(normalized)
        hostCache[normalized] = allowed
        return allowed
    }

    /** Clears cached DNS results (e.g. after network change). */
    fun clearCache() {
        hostCache.clear()
    }

    fun hostRejectionReason(host: String?): String {
        val normalized = host?.normalizeHost().orEmpty()
        if (normalized.isBlank()) return "Enter a valid Home Assistant URL."
        if (isPrivateHost(normalized)) return ""
        if (normalized == "localhost" || normalized.endsWith(".local") ||
            normalized.all { it in '0'..'9' || it == '.' }
        ) {
            return "Home Assistant must use a private LAN address, not '$normalized'."
        }
        val resolved = resolveAddresses(normalized)
        return when {
            resolved.isEmpty() ->
                "Could not resolve '$normalized'. Use the LAN IP (e.g. http://192.168.x.x:8123) or check DNS on the panel."
            resolved.none(::isPrivateInetAddress) ->
                "'$normalized' resolves to the public internet (${resolved.firstOrNull()?.hostAddress}). Use a LAN hostname or private IP."
            else -> "Home Assistant must be on the local network, not '$normalized'."
        }
    }

    private fun String.normalizeHost(): String = trim().trimEnd('.').lowercase()

    private fun isPrivateHostUncached(host: String): Boolean {
        if (host == "localhost") return true
        if (host.endsWith(".local", ignoreCase = true)) return true
        if (host.contains(':')) return isPrivateIpv6(host)
        if (host.all { it in '0'..'9' || it == '.' }) return isPrivateIpv4(host)
        return resolveAddresses(host).any(::isPrivateInetAddress)
    }

    private fun resolveAddresses(host: String): List<InetAddress> {
        resolveViaOkHttp(host)?.takeIf { it.isNotEmpty() }?.let { return it }
        runCatching { InetAddress.getAllByName(host).toList() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return runCatching { listOf(InetAddress.getByName(host)) }.getOrDefault(emptyList())
    }

    /** OkHttp uses the platform DNS stack; more reliable on Android than raw InetAddress. */
    private fun resolveViaOkHttp(host: String): List<InetAddress>? = runCatching {
        Dns.SYSTEM.lookup(host)
    }.getOrNull()

    private fun isPrivateInetAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return true
        if (address.isLinkLocalAddress) return true
        when (address) {
            is Inet4Address -> {
                if (address.isSiteLocalAddress) return true
                return isPrivateIpv4(address.hostAddress ?: return false)
            }
            is Inet6Address -> {
                if (address.isSiteLocalAddress) return true
                val host = address.hostAddress ?: return false
                return isPrivateIpv6(host.substringBefore('%'))
            }
        }
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
