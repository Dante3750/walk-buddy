package com.walkbuddy.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * "Best time to walk today" (alpha 2.0), an opt-in suggestion that is OFF by default. It asks Open-Meteo (a free forecast service that
 * needs no key and no account) for today's hourly forecast. Privacy by construction:
 *  - the position is rounded to 0.1 degree (about 11 km) before it is used, so the service only learns a town-sized area;
 *  - nothing is sent in the background: the app asks once when Home opens and keeps the answer for 3 hours;
 *  - offline or on any error the card simply does not appear.
 * This file is the pure part: the request text, reading the answer and choosing the window.
 */
object WeatherPrivacy {
    /** 0.1 degree is roughly 11 km of latitude. */
    fun coarse(v: Double): Double = Math.round(v * 10.0) / 10.0

    fun coarse(p: LatLon): LatLon = LatLon(coarse(p.lat), coarse(p.lon))

    const val CACHE_MS = 3 * 3_600_000L

    fun isFresh(fetchedMs: Long, nowMs: Long): Boolean = nowMs >= fetchedMs && nowMs - fetchedMs < CACHE_MS

    /** The only request the app ever makes for this feature. The query holds the rounded position and nothing else. */
    fun url(coarsePos: LatLon): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${fmt(coarsePos.lat)}&longitude=${fmt(coarsePos.lon)}" +
            "&hourly=temperature_2m,precipitation_probability,wind_speed_10m,uv_index&forecast_days=1&timezone=auto"

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}

data class HourWeather(val hour: Int, val tempC: Double, val rainPct: Int, val windKmh: Double, val uv: Double)

enum class WeatherReason { Pleasant, CoolAndDry, DryWindow, LeastRain, AvoidHeat, Fallback }

data class WalkWindow(val startHour: Int, val endHour: Int, val reason: WeatherReason, val tempC: Double, val rainPct: Int)

object WeatherParser {
    /** Reads Open-Meteo's hourly arrays. Returns null for anything unexpected; never throws. */
    fun parse(json: String?): List<HourWeather>? {
        if (json.isNullOrBlank() || json.length > 200_000) return null
        return try {
            val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
            val h = root["hourly"] as? JsonObject ?: return null
            val times = (h["time"] as? JsonArray)?.map { it.jsonPrimitive.contentOrNull } ?: return null
            fun arr(k: String): List<Double?> = (h[k] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()
            val temp = arr("temperature_2m"); val rain = arr("precipitation_probability"); val wind = arr("wind_speed_10m"); val uv = arr("uv_index")
            val out = ArrayList<HourWeather>()
            for (i in times.indices) {
                val t = times[i] ?: continue
                // "2026-10-10T07:00" in the place's own time zone.
                val hour = t.substringAfter('T', "").take(2).toIntOrNull() ?: continue
                if (hour !in 0..23) continue
                val tc = temp.getOrNull(i) ?: continue
                if (tc < -60 || tc > 60) continue
                out += HourWeather(hour, tc, (rain.getOrNull(i) ?: 0.0).toInt().coerceIn(0, 100), (wind.getOrNull(i) ?: 0.0).coerceIn(0.0, 300.0), (uv.getOrNull(i) ?: 0.0).coerceIn(0.0, 20.0))
            }
            out.distinctBy { it.hour }.sortedBy { it.hour }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}

object WalkWindows {
    private const val FIRST_HOUR = 6
    private const val LAST_HOUR = 21

    /** How pleasant an hour is for walking, 0 (not at all) to 100. */
    fun score(w: HourWeather): Int {
        var s = 100.0
        val t = w.tempC
        s -= when {
            t in 16.0..25.0 -> 0.0
            t < 16.0 -> (16.0 - t) * 3.5
            else -> (t - 25.0) * 6.0
        }
        s -= w.rainPct * 0.6
        if (w.windKmh > 25) s -= (w.windKmh - 25) * 1.2
        if (w.uv > 5) s -= (w.uv - 5) * 5.0
        return s.toInt().coerceIn(0, 100)
    }

    /**
     * The best walking hour from [nowHour] on (the current hour counts only until it is half over, which the caller expresses by passing
     * nowHour + 1 when it is late in the hour), widened by one hour when the neighbour is nearly as good.
     */
    fun best(hours: List<HourWeather>, nowHour: Int): WalkWindow? {
        val candidates = hours.filter { it.hour in maxOf(FIRST_HOUR, nowHour)..LAST_HOUR }
        if (candidates.isEmpty()) return null
        val top = candidates.maxByOrNull { score(it) } ?: return null
        if (score(top) < 35) {
            val dry = candidates.minByOrNull { it.rainPct } ?: top
            return WalkWindow(dry.hour, dry.hour + 1, WeatherReason.LeastRain, dry.tempC, dry.rainPct)
        }
        val next = candidates.firstOrNull { it.hour == top.hour + 1 }
        val end = if (next != null && score(next) >= score(top) - 8) top.hour + 2 else top.hour + 1
        val reason = when {
            top.rainPct <= 10 && top.tempC in 16.0..25.0 -> WeatherReason.Pleasant
            top.rainPct <= 10 && top.tempC < 16.0 -> WeatherReason.CoolAndDry
            top.rainPct <= 20 -> WeatherReason.DryWindow
            top.tempC > 25.0 -> WeatherReason.AvoidHeat
            else -> WeatherReason.Fallback
        }
        return WalkWindow(top.hour, end, reason, top.tempC, top.rainPct)
    }
}
