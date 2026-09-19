package app.you.tube.core.util

import java.util.Locale
import kotlin.math.abs

object Formats {

    fun count(value: Long): String = when {
        value < 0 -> ""
        value < 1_000 -> value.toString()
        value < 1_000_000 -> trim(value / 1_000.0, 1_000.0, "K")
        value < 1_000_000_000 -> trim(value / 1_000_000.0, 1_000_000.0, "M")
        else -> trim(value / 1_000_000_000.0, 1_000_000_000.0, "B")
    }

    private fun trim(v: Double, divisor: Double, suffix: String): String {
        val rounded = if (v >= 100) kotlin.math.round(v) else kotlin.math.round(v * 10) / 10
        val s = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else String.format(Locale.US, "%.1f", rounded)
        return s + suffix
    }

    fun duration(totalSeconds: Long): String {
        if (totalSeconds <= 0) return "0:00"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun durationMs(ms: Long): String = duration(ms / 1000)

    /** Extract an 11-char video id from any YouTube watch URL shape. */
    fun videoIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore('?').substringBefore('&').takeIf { it.length == 11 }
            url.contains("v=") -> url.substringAfter("v=").substringBefore('&').substringBefore('?').takeIf { it.length == 11 }
            url.contains("/shorts/") -> url.substringAfter("/shorts/").substringBefore('?').substringBefore('&').takeIf { it.length == 11 }
            url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore('?').substringBefore('&').takeIf { it.length == 11 }
            else -> null
        }
    }

    fun channelIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.substringAfterLast('/').takeIf { it.startsWith("UC") && it.length == 24 }
    }

    fun compactBytes(bytes: Long): String {
        if (bytes < 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun nowMillis(): Long = System.currentTimeMillis()

    fun withinWindow(timestamps: List<Long>, windowMs: Long, now: Long): Int =
        timestamps.count { now - it <= windowMs }

    fun absDiff(a: Long, b: Long): Long = abs(a - b)
}
