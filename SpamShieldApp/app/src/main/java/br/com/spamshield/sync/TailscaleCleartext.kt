package br.com.spamshield.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Cleartext HTTP is limited to Tailscale CGNAT 100.64.0.0/10, MagicDNS (*.ts.net) and loopback.
 * HTTPS is allowed on any host. Unparseable URLs or unsupported schemes fail closed.
 */
object TailscaleCleartext {
    const val RANGE = "100.64.0.0/10"

    fun isInRange(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]")
        if (clean.isEmpty()) return false
        if (clean.equals("localhost", ignoreCase = true) || clean == "127.0.0.1" || clean == "::1") return true
        if (clean.length > 7 && clean.endsWith(".ts.net", ignoreCase = true) && !clean.startsWith(".")) {
            val prefix = clean.dropLast(7)
            return prefix.split('.').all { label ->
                label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' }
            }
        }
        val parts = clean.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    fun requireAllowed(url: String) {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: throw IOException("Invalid or unsupported server URL")
        if (parsed.scheme == "https") return
        if (parsed.scheme == "http" && isInRange(parsed.host)) return
        throw IOException("Cleartext HTTP is limited to $RANGE")
    }
}
