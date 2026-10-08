package com.walkbuddy.domain

import java.util.Locale

enum class UnitSystem { Metric, Imperial }

/** Kilometres or miles, chosen in Settings. All math stays in metres; only the label changes. */
object Units {
    const val M_PER_MILE = 1609.344
    const val M_PER_FOOT = 0.3048

    data class Amount(val value: String, val unit: String) {
        override fun toString() = "$value $unit"
    }

    fun distanceAmount(meters: Double, system: UnitSystem): Amount {
        val m = if (meters.isNaN() || meters < 0) 0.0 else meters
        return when (system) {
            UnitSystem.Metric ->
                if (m < 1000) Amount(Math.round(m).toString(), "m") else Amount(String.format(Locale.US, "%.2f", m / 1000.0), "km")
            UnitSystem.Imperial -> {
                val mi = m / M_PER_MILE
                if (mi < 0.1) Amount(Math.round(m / M_PER_FOOT).toString(), "ft") else Amount(String.format(Locale.US, "%.2f", mi), "mi")
            }
        }
    }

    fun distance(meters: Double, system: UnitSystem): String = distanceAmount(meters, system).toString()

    /** Always in km or mi with one decimal, for totals such as "our distance". */
    fun longDistance(meters: Double, system: UnitSystem): String {
        val m = meters.coerceAtLeast(0.0)
        return when (system) {
            UnitSystem.Metric -> String.format(Locale.US, "%.1f km", m / 1000.0)
            UnitSystem.Imperial -> String.format(Locale.US, "%.1f mi", m / M_PER_MILE)
        }
    }

    fun pace(speedMps: Double?, system: UnitSystem): String {
        if (system == UnitSystem.Metric) return Format.pace(speedMps)
        if (speedMps == null || speedMps < 0.2) return "-"
        val secPerMile = M_PER_MILE / speedMps
        if (secPerMile > 7200) return "-"
        val total = Math.round(secPerMile).toInt()
        return String.format(Locale.US, "%d:%02d /mi", total / 60, total % 60)
    }

    fun unitName(system: UnitSystem) = if (system == UnitSystem.Metric) "km" else "mi"
}
