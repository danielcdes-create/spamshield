package br.com.spamshield.sync

import java.io.IOException
import java.net.URI

/**
 * Cleartext HTTP is limited to Tailscale CGNAT 100.64.0.0/10.
 * HTTPS is allowed on any host.
 */
object TailscaleCleartext {
    const val RANGE = "100.64.0.0/10"

    fun isInRange(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1") return true
        if (host.endsWith(".ts.net", ignoreCase = true)) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    fun requireAllowed(url: String) {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return
        val scheme = parsed.scheme?.lowercase() ?: return
        if (scheme == "https") return
        if (scheme == "http" && isInRange(parsed.host ?: "")) return
        throw IOException("Cleartext HTTP is limited to $RANGE")
    }
}
