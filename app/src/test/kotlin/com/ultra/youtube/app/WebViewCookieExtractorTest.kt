package com.ultra.youtube.app

import com.ultra.youtube.app.data.auth.WebViewCookieExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie parsing is pure string handling, so it runs on the JVM without Robolectric.
 * `decodeLoginInfo` is deliberately not covered here: it needs `android.util.Base64`.
 */
class WebViewCookieExtractorTest {

    @Test
    fun `parses a normal cookie header`() {
        val session = WebViewCookieExtractor.parseCookieHeader(
            "SID=abc; SAPISID=xyz; HSID=111; VISITOR_INFO1_LIVE=v1",
        )
        assertNotNull(session)
        assertEquals("abc", session!!.sid)
        assertEquals("xyz", session.sapishid)
        assertTrue(session.isAuthenticated)
        assertEquals(4, session.cookies.size)
    }

    @Test
    fun `tolerates missing spaces and stray semicolons`() {
        val session = WebViewCookieExtractor.parseCookieHeader("SID=abc;SAPISID=xyz;; ;")
        assertNotNull(session)
        assertEquals("abc", session!!.sid)
        assertEquals("xyz", session.sapishid)
    }

    @Test
    fun `keeps values that contain equals signs`() {
        val session = WebViewCookieExtractor.parseCookieHeader("SID=a=b=c; SAPISID=x")
        assertEquals("a=b=c", session!!.sid)
    }

    @Test
    fun `keeps empty values but drops nameless pairs`() {
        val session = WebViewCookieExtractor.parseCookieHeader("PREF=; =orphan; SAPISID=x; SID=y")
        assertNotNull(session)
        assertEquals("", session!!.cookies["PREF"])
        assertFalse(session.cookies.containsKey(""))
        assertTrue(session.isAuthenticated)
    }

    @Test
    fun `falls back to the secure sapisid cookie`() {
        val session = WebViewCookieExtractor.parseCookieHeader("SID=a; __Secure-3PAPISID=s3")
        assertEquals("s3", session!!.sapishid)
        assertTrue(session.isAuthenticated)
    }

    @Test
    fun `without SID and SAPISID the session is not authenticated`() {
        val session = WebViewCookieExtractor.parseCookieHeader("VISITOR_INFO1_LIVE=v; PREF=x")
        assertNotNull(session)
        assertFalse(session!!.isAuthenticated)
    }

    @Test
    fun `rebuilds a canonical header`() {
        val session = WebViewCookieExtractor.parseCookieHeader("  SID=abc ;  SAPISID=xyz ")
        assertEquals("SID=abc; SAPISID=xyz", session!!.header)
    }

    @Test
    fun `null and blank input return null`() {
        assertNull(WebViewCookieExtractor.parseCookieHeader(null))
        assertNull(WebViewCookieExtractor.parseCookieHeader(""))
        assertNull(WebViewCookieExtractor.parseCookieHeader("   "))
        assertNull(WebViewCookieExtractor.parseCookieHeader(";;;"))
    }

    @Test
    fun `required cookie names are the ones the header is built from`() {
        assertEquals(listOf("SID", "SAPISID"), WebViewCookieExtractor.REQUIRED_COOKIES)
        assertTrue(WebViewCookieExtractor.SESSION_COOKIE_WHITELIST.containsAll(
            WebViewCookieExtractor.REQUIRED_COOKIES,
        ))
    }
}
