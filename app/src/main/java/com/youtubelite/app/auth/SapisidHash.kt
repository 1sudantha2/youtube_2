package com.youtubelite.app.auth

import java.security.MessageDigest

/**
 * Computes Google's SAPISIDHASH authorization header:
 *
 *   Authorization: SAPISIDHASH <timestamp>_<SHA1("<timestamp> <SAPISID> <origin>")>
 */
object SapisidHash {

    fun generate(sapisid: String, origin: String = "https://www.youtube.com"): String {
        val ts = System.currentTimeMillis() / 1000L
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $origin".toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hex"
    }
}
