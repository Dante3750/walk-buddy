package com.walkbuddy.weather

import com.walkbuddy.AppContainer
import com.walkbuddy.diag.AppLog
import com.walkbuddy.domain.WalkWindow
import com.walkbuddy.domain.WalkWindows
import com.walkbuddy.domain.WeatherParser
import com.walkbuddy.domain.WeatherPrivacy
import com.walkbuddy.rtc.Http
import com.walkbuddy.rtc.getText
import java.time.LocalTime

/**
 * The opt-in "best time to walk today" suggestion (off by default). It runs only when Home is opened and the person switched it on:
 * no background polling, one request per three hours at most, and the only thing sent is the position rounded to 0.1 degree.
 * Offline or on any error it returns what is cached, or nothing, so the card just does not appear.
 */
object WeatherService {
    suspend fun suggestion(c: AppContainer, nowMs: Long = System.currentTimeMillis()): WalkWindow? {
        val s = c.settings.current()
        if (!s.weatherOn) return null
        val hour = LocalTime.now().hour
        if (s.weatherCache.isNotEmpty() && WeatherPrivacy.isFresh(s.weatherCacheMs, nowMs)) {
            return WeatherParser.parse(s.weatherCache)?.let { WalkWindows.best(it, hour) }
        }
        if (!c.locationSource.hasPermission()) return cached(s.weatherCache, hour)
        val fix = c.locationSource.lastKnown() ?: return cached(s.weatherCache, hour)
        val url = WeatherPrivacy.url(WeatherPrivacy.coarse(fix.pos))
        val body = Http.getText(url)
        val hours = WeatherParser.parse(body)
        if (body == null || hours == null) {
            AppLog.d("weather", "no forecast")
            return cached(s.weatherCache, hour)
        }
        c.settings.saveWeather(body, nowMs)
        return WalkWindows.best(hours, hour)
    }

    private fun cached(json: String, hour: Int): WalkWindow? = if (json.isEmpty()) null else WeatherParser.parse(json)?.let { WalkWindows.best(it, hour) }
}
