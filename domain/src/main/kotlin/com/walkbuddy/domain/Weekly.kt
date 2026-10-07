package com.walkbuddy.domain

enum class DayPart(val label: String) {
    Morning("Morning"), Afternoon("Afternoon"), Evening("Evening"), Night("Night");

    companion object {
        fun of(hour: Int): DayPart = when (hour) {
            in 5..11 -> Morning
            in 12..16 -> Afternoon
            in 17..20 -> Evening
            else -> Night
        }
    }
}

enum class Trend { Up, Steady, Down, NoData }

data class WeeklyReport(
    val totalSteps: Int,
    val avgStepsPerDay: Int,
    val daysWithData: Int,
    val trend: Trend,
    val trendPct: Int?,
    val trendText: String,
    val activeEquivMin: Int,
    val guidance: WeeklyGuidance.Progress,
    val bestDayPart: DayPart?,
    val walkCount: Int,
    val avgTogetherPct: Int?,
    val distanceM: Double,
) {
    companion object {
        /** [days] should cover the 7 days ending at [lastEpochDay] plus (optionally) the 7 before for the trend. */
        fun build(days: List<DayRecord>, walks: List<WalkRecord>, lastEpochDay: Long): WeeklyReport {
            val firstThis = lastEpochDay - 6
            val thisWeek = days.filter { it.epochDay in firstThis..lastEpochDay }
            val prevWeek = days.filter { it.epochDay in (firstThis - 7)..(firstThis - 1) }
            val total = thisWeek.sumOf { it.verifiedSteps }
            val withData = thisWeek.count { it.verifiedSteps > 0 }
            val avg = if (withData == 0) 0 else total / withData
            val prevTotal = prevWeek.sumOf { it.verifiedSteps }
            val (trend, pct) = when {
                prevTotal <= 0 || total <= 0 -> Trend.NoData to null
                else -> {
                    val p = Math.round((total - prevTotal) * 100.0 / prevTotal).toInt()
                    when {
                        p >= 5 -> Trend.Up to p
                        p <= -5 -> Trend.Down to p
                        else -> Trend.Steady to p
                    }
                }
            }
            val trendText = when (trend) {
                Trend.Up -> "A little more movement than last week."
                Trend.Steady -> "About the same as last week."
                Trend.Down -> "A quieter week than last. Rest is part of it."
                Trend.NoData -> "Not enough data for a comparison yet."
            }
            val weekWalks = walks.filter { it.epochDay in firstThis..lastEpochDay }
            val mod = thisWeek.sumOf { it.moderateMin }
            val vig = thisWeek.sumOf { it.vigorousMin }
            val byPart = weekWalks.groupBy { DayPart.of(it.hourOfDay) }
                .mapValues { (_, w) -> w.sumOf { it.moderateMin + it.vigorousMin + (it.durationMs / 60_000).toInt() / 4 } }
            val best = byPart.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
            val together = weekWalks.mapNotNull { it.togetherPct }
            return WeeklyReport(
                totalSteps = total, avgStepsPerDay = avg, daysWithData = withData, trend = trend, trendPct = pct, trendText = trendText,
                activeEquivMin = mod + 2 * vig, guidance = WeeklyGuidance.progress(mod, vig), bestDayPart = best,
                walkCount = weekWalks.size, avgTogetherPct = if (together.isEmpty()) null else Math.round(together.average()).toInt(),
                distanceM = thisWeek.sumOf { it.distanceM },
            )
        }
    }
}
