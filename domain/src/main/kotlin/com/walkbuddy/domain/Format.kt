package com.walkbuddy.domain

import java.util.Locale

/** Small, locale-stable formatters (always '.' decimals, so tests are deterministic). */
object Format {
    fun distance(meters: Double): String = when {
        meters.isNaN() || meters < 0 -> "-"
        meters < 1000 -> "${Math.round(meters)} m"
        else -> String.format(Locale.US, "%.2f km", meters / 1000.0)
    }

    fun duration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Pace as min/km ("12:30"), or "-" when not moving. */
    fun pace(speedMps: Double?): String {
        if (speedMps == null || speedMps < 0.2) return "-"
        val secPerKm = 1000.0 / speedMps
        if (secPerKm > 3600) return "-"
        val total = Math.round(secPerKm).toInt()
        return String.format(Locale.US, "%d:%02d /km", total / 60, total % 60)
    }

    fun percent(fraction: Double): String = "${Math.round((fraction * 100).coerceIn(0.0, 100.0))}%"
}
