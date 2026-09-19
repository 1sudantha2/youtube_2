package io.github.sudantha.youtubelite

import io.github.sudantha.youtubelite.auth.SapisidHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SapisidHashTest {
    @Test
    fun `header matches reference sha1 digest`() {
        // sha1("1234567890 c-test_sapisid_0 https://www.youtube.com")
        assertEquals("SAPISIDHASH 1234567890_3c8c7e0b8a9907f8c289bbdbebe2f0ca798e60a1",
            SapisidHash.header("c-test_sapisid_0", 1_234_567_890L))
    }

    @Test
    fun `header uses seconds timestamp format`() {
        val header = SapisidHash.header("x", 100L)
        assertTrue(header.startsWith("SAPISIDHASH 100_"))
        assertEquals("SAPISIDHASH 100_".length + 40, header.length) // SHA-1 hex digest
    }

    @Test
    fun `cookie extraction keeps only safe session cookies`() {
        val raw = "SID=a%3D1; HSID=b; SSID=c; APISID=d; SAPISID=e; LOGIN_INFO=f; " +
            "__Secure-3PAPISID=g; JS_TOKEN=h; __Secure-3PSID%20%0A=i; a=b"
        val cookies = SapisidHash.cookies(raw)
        assertEquals(setOf("SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO", "__Secure-3PAPISID"),
            cookies.keys)
        assertEquals("e", cookies["SAPISID"])
        assertFalse(cookies.containsKey("JS_TOKEN"))
        assertFalse(cookies.values.any { '\r' in it || '\n' in it })
    }

    @Test
    fun `header injection via cookie value is impossible`() {
        // Literal CRLF in a cookie line must be rejected as a session cookie.
        val raw = "SAPISID=evil\r\nX-Injected: 1"
        assertTrue(SapisidHash.cookies(raw).isEmpty())
    }
}
