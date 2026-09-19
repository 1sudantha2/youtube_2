package com.ultra.youtube.app.data.innertube

import java.security.MessageDigest

/**
 * Builds the `Authorization: SAPISIDHASH …` header YouTube expects on authenticated
 * InnerTube calls.
 *
 * The scheme is `SAPISIDHASH <timestamp>_<SHA1(timestamp + " " + SAPISID + " " + origin)>`
 * where `timestamp` is seconds since epoch and `origin` is the requesting origin.
 * Because the hash is derived from a cookie that already lives on the device, the
 * server can verify the request without ever seeing the raw SAPISID in the header.
 *
 * This object has no Android dependencies, so it is unit-testable on the JVM.
 */
object SapishHash {

    const val SCHEME = "SAPISIDHASH"

    /** Google's web origin; the InnerTube endpoint validates the header against it. */
    const val DEFAULT_ORIGIN = "https://www.youtube.com"

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * @param sapisid the raw `SAPISID` cookie value (no `SAPISID=` prefix)
     * @param timestampMs wall-clock milliseconds; divided by 1000 for the header
     * @param origin requesting origin, must match the one used for the request
     * @return `SAPISIDHASH 1700000000_ab12…`, or `null` when [sapisid] is blank
     */
    fun header(
        sapisid: String?,
        timestampMs: Long,
        origin: String = DEFAULT_ORIGIN,
    ): String? {
        if (sapisid.isNullOrBlank()) return null
        val seconds = timestampMs / 1000L
        return "$SCHEME ${seconds}_${hash("$seconds $sapisid $origin")}"
    }

    /** Lower-case hex SHA-1 of [input] in UTF-8. */
    fun hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val b = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }
}
