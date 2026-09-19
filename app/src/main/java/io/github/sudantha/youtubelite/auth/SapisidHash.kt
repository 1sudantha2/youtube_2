package io.github.sudantha.youtubelite.auth

import java.security.MessageDigest

object SapisidHash {
    const val ORIGIN = "https://www.youtube.com"
    private val allowed = setOf("SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO", "__Secure-1PAPISID", "__Secure-3PAPISID", "__Secure-1PSID", "__Secure-3PSID")
    fun cookies(raw: String): Map<String, String> = raw.split(';').mapNotNull { part ->
        val key = part.substringBefore('=').trim()
        val value = part.substringAfter('=', "").trim()
        if (key in allowed && value.isNotBlank() && '\r' !in value && '\n' !in value) key to value else null
    }.toMap()
    fun header(sapisid: String, timestamp: Long = System.currentTimeMillis() / 1000): String {
        val bytes = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $ORIGIN".toByteArray(Charsets.UTF_8))
        val digest = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }
}
