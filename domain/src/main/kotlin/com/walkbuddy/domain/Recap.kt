package com.walkbuddy.domain

enum class SlideKind { Total, BestDay, Together, BestHour, Flame, Closing }

data class RecapSlide(val kind: SlideKind, val title: String, val big: String, val caption: String)

object WeeklyRecapBuilder {
    private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun dayName(epochDay: Long): String = DAY_NAMES[AdaptiveGoal.weekdayOf(epochDay) - 1]

    /** Story-style slides for the 7 days ending at [lastEpochDay]. */
    fun build(
        report: WeeklyReport,
        days: List<DayRecord>,
        lastEpochDay: Long,
        togetherWalks: Int,
        togetherDistanceM: Double,
        hourly: HourlyProfile?,
        flame: FlameInfo?,
        unit: UnitSystem,
        is24h: Boolean,
    ): List<RecapSlide> {
        if (report.daysWithData == 0) {
            return listOf(RecapSlide(SlideKind.Closing, "A fresh week", "0", "Carry your phone and take a stroll. Your recap builds itself."))
        }
        val out = ArrayList<RecapSlide>()
        out += RecapSlide(
            SlideKind.Total, "This week you took", Hero.thousands(report.totalSteps),
            "steps, about ${Units.longDistance(report.distanceM, unit)}. ${report.trendText}",
        )
        val week = days.filter { it.epochDay in (lastEpochDay - 6)..lastEpochDay }
        val best = week.maxByOrNull { it.verifiedSteps }
        if (best != null && best.verifiedSteps > 0) {
            out += RecapSlide(SlideKind.BestDay, "Your best day was ${dayName(best.epochDay)}", Hero.thousands(best.verifiedSteps), "steps on a single day")
        }
        if (togetherWalks > 0) {
            out += RecapSlide(
                SlideKind.Together, if (togetherWalks == 1) "You walked together" else "You walked together $togetherWalks times",
                Units.longDistance(togetherDistanceM, unit), "side by side" + (report.avgTogetherPct?.let { ", together $it% of the time" } ?: ""),
            )
        } else {
            out += RecapSlide(SlideKind.Together, "A solo week", "0", "No walks together this week. A short one counts.")
        }
        val bh = hourly?.bestHour
        if (bh != null) {
            out += RecapSlide(SlideKind.BestHour, "You walk most around", HourlyHistogram.hourLabel(bh, is24h), "your best walking hour lately")
        }
        if (flame != null && flame.days > 0) {
            out += RecapSlide(SlideKind.Flame, "Your flame", "${flame.days}", if (flame.days == 1) "day streak" else "day streak. " + flame.subtitle)
        }
        out += RecapSlide(SlideKind.Closing, "Nicely done", "♥", closing(report))
        return out
    }

    private fun closing(r: WeeklyReport): String = when {
        r.guidance.met -> "You reached the commonly cited weekly activity guidance. Enjoy the rest."
        r.trend == Trend.Up -> "A little more movement than last week. Keep it easy."
        r.trend == Trend.Down -> "A quieter week. Rest is part of the rhythm."
        else -> "Every walk counts. See you out there."
    }
}

/** The couple's shared week as a small card (the buddy's own step total is theirs to share). */
data class OurWeekCard(val headlineValue: String, val headlineLabel: String, val lines: List<String>)

object OurWeek {
    fun build(
        report: WeeklyReport,
        coupleWalks: List<WalkRecord>,
        coupleDays: Set<Long>,
        lastEpochDay: Long,
        buddyName: String?,
        unit: UnitSystem,
    ): OurWeekCard {
        val first = lastEpochDay - 6
        val walks = coupleWalks.filter { it.epochDay in first..lastEpochDay && it.buddyCount >= 1 }
        val daysTogether = (first..lastEpochDay).count { it in coupleDays }
        val dist = walks.sumOf { it.distanceM }
        val who = buddyName?.takeIf { it.isNotBlank() } ?: "your buddy"
        val lines = ArrayList<String>()
        lines += "Walked with $who on $daysTogether of 7 days"
        lines += "${walks.size} " + (if (walks.size == 1) "walk" else "walks") + " together"
        report.avgTogetherPct?.let { lines += "Together $it% of the time" }
        walks.maxOfOrNull { it.longestTogetherMs }?.takeIf { it >= 60_000 }?.let { lines += "Longest stretch side by side: ${Format.duration(it)}" }
        lines += "You took ${Hero.thousands(report.totalSteps)} steps this week"
        return OurWeekCard(Units.longDistance(dist, unit), "walked together this week", lines)
    }
}
