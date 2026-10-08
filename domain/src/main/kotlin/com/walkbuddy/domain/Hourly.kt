package com.walkbuddy.domain

data class HourSteps(val epochDay: Long, val hour: Int, val steps: Int)

data class HourlyProfile(
    /** Average steps in each hour of the day (24 values) over days that have data. */
    val perHour: List<Int>,
    val daysCovered: Int,
    val bestHour: Int?,
) {
    val max: Int get() = perHour.maxOrNull() ?: 0
}

object HourlyHistogram {
    const val MIN_DAYS = 3

    fun build(entries: List<HourSteps>, todayEpochDay: Long, windowDays: Int = 30): HourlyProfile {
        val inWindow = entries.filter { it.epochDay in (todayEpochDay - windowDays + 1)..todayEpochDay && it.hour in 0..23 && it.steps > 0 }
        val days = inWindow.map { it.epochDay }.toSet().size
        val sums = IntArray(24)
        for (e in inWindow) sums[e.hour] += e.steps
        val avg = if (days == 0) List(24) { 0 } else sums.map { Math.round(it.toDouble() / days).toInt() }
        val max = avg.maxOrNull() ?: 0
        val best = if (days >= MIN_DAYS && max > 0) avg.indexOf(max) else null
        return HourlyProfile(avg, days, best)
    }

    fun hourLabel(hour: Int, is24h: Boolean): String {
        val h = ((hour % 24) + 24) % 24
        if (is24h) return String.format(java.util.Locale.US, "%02d:00", h)
        val suffix = if (h < 12) "am" else "pm"
        val twelve = if (h % 12 == 0) 12 else h % 12
        return "$twelve $suffix"
    }

    fun bestText(p: HourlyProfile, is24h: Boolean): String {
        val b = p.bestHour ?: return "Keep walking for a few days and your best hour shows up here."
        return "Your best walking hour: ${hourLabel(b, is24h)} to ${hourLabel(b + 1, is24h)}"
    }
}
