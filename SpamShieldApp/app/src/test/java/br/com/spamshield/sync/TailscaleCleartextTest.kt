package br.com.spamshield.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TailscaleCleartextTest {

    @Test
    fun `isInRange accepts Tailscale CGNAT 100_64_0_0 slash 10 boundaries, MagicDNS and loopback`() {
        assertTrue(TailscaleCleartext.isInRange("100.64.0.0"))
        assertTrue(TailscaleCleartext.isInRange("100.83.253.15"))
        assertTrue(TailscaleCleartext.isInRange("100.101.23.111"))
        assertTrue(TailscaleCleartext.isInRange("100.127.255.255"))
        assertTrue(TailscaleCleartext.isInRange("localhost"))
        assertTrue(TailscaleCleartext.isInRange("LOCALHOST"))
        assertTrue(TailscaleCleartext.isInRange("127.0.0.1"))
        assertTrue(TailscaleCleartext.isInRange("::1"))
        assertTrue(TailscaleCleartext.isInRange("[::1]"))
        assertTrue(TailscaleCleartext.isInRange("gbp360.tail1093e7.ts.net"))
        assertTrue(TailscaleCleartext.isInRange("Node-01.TailNet.TS.NET"))
    }

    @Test
    fun `isInRange rejects addresses outside 100_64_0_0 slash 10 and malformed MagicDNS labels`() {
        assertFalse(TailscaleCleartext.isInRange("100.63.255.255"))
        assertFalse(TailscaleCleartext.isInRange("100.128.0.0"))
        assertFalse(TailscaleCleartext.isInRange("192.168.1.10"))
        assertFalse(TailscaleCleartext.isInRange("10.0.0.1"))
        assertFalse(TailscaleCleartext.isInRange("100.83.253.256"))
        assertFalse(TailscaleCleartext.isInRange("100.83.253"))
        assertFalse(TailscaleCleartext.isInRange("100.83.abc.1"))
        assertFalse(TailscaleCleartext.isInRange(".ts.net"))
        assertFalse(TailscaleCleartext.isInRange("ts.net"))
        assertFalse(TailscaleCleartext.isInRange("bad..ts.net"))
        assertFalse(TailscaleCleartext.isInRange("evil-ts.net"))
        assertFalse(TailscaleCleartext.isInRange("bad_label.ts.net"))
        assertFalse(TailscaleCleartext.isInRange(""))
        assertFalse(TailscaleCleartext.isInRange("   "))
    }

    @Test
    fun `requireAllowed permits HTTPS anywhere and HTTP only inside Tailscale or loopback`() {
        TailscaleCleartext.requireAllowed("https://api.example.com:8443/v1")
        TailscaleCleartext.requireAllowed("http://100.83.253.15:8080")
        TailscaleCleartext.requireAllowed("http://100.101.23.111:8080/")
        TailscaleCleartext.requireAllowed("http://gbp360.tail1093e7.ts.net:8080")
        TailscaleCleartext.requireAllowed("http://127.0.0.1:8080")
        TailscaleCleartext.requireAllowed("http://[::1]:8080")
    }

    @Test
    fun `requireAllowed fails closed on public cleartext HTTP, malformed URLs with spaces and non-HTTP schemes`() {
        assertThrows(IOException::class.java) {
            TailscaleCleartext.requireAllowed("http://example.com:8080")
        }
        assertThrows(IOException::class.java) {
            TailscaleCleartext.requireAllowed("http://100.63.255.255:8080")
        }
        assertThrows(IOException::class.java) {
            TailscaleCleartext.requireAllowed("http://100.128.0.1:8080")
        }
        // Regression for BUG-03: java.net.URI failed open on unencoded spaces while OkHttp still parsed the URL.
        assertThrows(IOException::class.java) {
            TailscaleCleartext.requireAllowed("http://example.com:8080/path with space")
        }
        assertThrows(IOException::class.java) {
            TailscaleCleartext.requireAllowed("ftp://100.83.253.15:8080")
        }
        assertThrows(IOException::class.java) {
            TailscaleCleartext.requireAllowed("")
        }
        assertThrows(IOException::class.java) {
            TailscaleCleartext.requireAllowed("not a valid url")
        }
    }
}
