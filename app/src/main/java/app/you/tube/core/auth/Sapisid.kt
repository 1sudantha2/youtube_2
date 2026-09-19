package app.you.tube.core.auth

import java.security.MessageDigest

/**
 * SAPISIDHASH generator — the authorization scheme YouTube's web client uses
 * for authenticated InnerTube requests made with account cookies.
 *
 * Header format:
 *   Authorization: SAPISIDHASH <timestamp_seconds>_<SHA1(timestamp + " " + SAPISID + " " + origin)>
 *
 * where origin is "https://www.youtube.com".
 */
object Sapisid {

    const val ORIGIN = "https://www.youtube.com"

    fun hash(sapisid: String, timeSeconds: Long = System.currentTimeMillis() / 1000): String {
        val payload = "$timeSeconds $sapisid $ORIGIN"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = buildString(40) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
        }
        return "${timeSeconds}_$hex"
    }

    fun authorizationHeader(sapisid: String): String = "SAPISIDHASH ${hash(sapisid)}"

    private val HEX = "0123456789abcdef".toCharArray()
}
