package com.downvid.downvid.ui

import java.util.Locale
import kotlin.math.roundToLong

/** "1.5 MB", "820 KB", "?" for unknown (negative) sizes. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    val digits = if (v >= 100 || i == 0) 0 else 1
    return String.format(Locale.US, "%.${digits}f %s", v, units[i])
}

fun formatBytes(bytes: Double): String = formatBytes(bytes.toLong())

/** "3:07", "1:02:05"; empty for unknown durations. */
fun formatDuration(seconds: Double): String {
    if (seconds <= 0) return ""
    val total = (seconds).roundToLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** "42s left", "3 min left", "1 h 5 min left". */
fun formatEta(seconds: Double): String {
    val s = seconds.roundToLong()
    return when {
        s < 60 -> "${s}s left"
        s < 3600 -> "${s / 60} min left"
        else -> "${s / 3600} h ${(s % 3600) / 60} min left"
    }
}

fun String?.nonBlank(): String? = this?.trim()?.ifEmpty { null }
