package com.framecut.app.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** "1:23:45.6" / "3:07.2" — matches the web app's readouts. */
fun formatClock(millis: Long): String {
    if (millis < 0) return "–"
    val totalTenths = millis / 100
    val tenths = totalTenths % 10
    val totalSeconds = totalTenths / 10
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d.%d", hours, minutes, seconds, tenths)
    } else {
        String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%d KB", Math.round(bytes / 1e3))
}

private val rfc3339 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

// Rebuilt when the system locale changes, but not on every list row.
private var displayLocale: Locale? = null
private var display: SimpleDateFormat? = null

private fun displayFormat(): SimpleDateFormat {
    val locale = Locale.getDefault()
    val cached = display
    if (cached != null && displayLocale == locale) return cached
    val fresh = SimpleDateFormat("d MMM yyyy", locale)
    display = fresh
    displayLocale = locale
    return fresh
}

/** Drive returns RFC 3339; show a short local date, or nothing if unparseable. */
fun formatDriveDate(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val trimmed = iso.substringBefore('.').substringBefore('Z').take(19)
        displayFormat().format(rfc3339.parse(trimmed)!!)
    }.getOrDefault("")
}
