package com.walkbuddy.domain

import kotlin.random.Random

data class DemoBundle(
    val days: List<DayRecord>,
    val walks: List<WalkRecord>,
    val hours: List<HourSteps>,
    val moods: List<MoodEntry>,
    val coupleDays: Set<Long>,
    val coupleDistanceM: Double,
    val buddyName: String,
    val buddyStepsToday: Int,
    val buddyGoal: Int,
    val goal: Int,
)

/**
 * Believable sample data for demo mode: no buddy, no permissions and nothing written to the phone.
 * Deterministic for a given seed so previews and tests stay stable.
 */
object DemoData {
    const val HISTORY_DAYS = 45
    const val TODAY_STEPS = 5_247
    const val GOAL = 7_000

    fun build(todayEpochDay: Long, seed: Long = 7L): DemoBundle {
        val r = Random(seed)
        val days = ArrayList<DayRecord>()
        val walks = ArrayList<WalkRecord>()
        val hours = ArrayList<HourSteps>()
        val moods = ArrayList<MoodEntry>()
        val couple = LinkedHashSet<Long>()
        var coupleM = 0.0
        var walkId = 1L
        var moodId = 1L
        for (back in HISTORY_DAYS downTo 1) {
            val day = todayEpochDay - back
            val weekend = AdaptiveGoal.weekdayOf(day) >= 6
            val base = if (weekend) 8_800 else 6_400
            val steps = (base + r.nextInt(-2_600, 3_400)).coerceAtLeast(1_800)
            val together = r.nextInt(100) < 55
            val walkSteps = if (together) 3_200 + r.nextInt(1_800) else 1_200 + r.nextInt(1_500)
            val distance = steps * 0.72
            days += DayRecord(day, steps + 120, steps, moderateMin = 12 + r.nextInt(30), vigorousMin = r.nextInt(6), distanceM = distance)
            val morning = back % 3 != 0
            val walkHour = if (morning) 7 else 18
            val walkDist = walkSteps * 0.72
            walks += WalkRecord(
                id = walkId, startMs = (day * 86_400_000L) + walkHour * 3_600_000L, durationMs = (walkSteps / 105.0 * 60_000).toLong(),
                distanceM = walkDist, verifiedSteps = walkSteps, rawSteps = walkSteps + 30, moderateMin = 15 + r.nextInt(15), vigorousMin = 0,
                hourOfDay = walkHour, buddyCount = if (together) 1 else 0, togetherPct = if (together) 70 + r.nextInt(28) else null,
                longestTogetherMs = if (together) (8 + r.nextInt(25)) * 60_000L else 0, epochDay = day,
            )
            if (together) { couple += day; coupleM += walkDist }
            // Spread the day's steps over a believable rhythm.
            val shape = mapOf(7 to 0.18, 8 to 0.10, 9 to 0.05, 12 to 0.12, 13 to 0.06, 15 to 0.05, 17 to 0.07, 18 to 0.25, 19 to 0.12)
            for ((h, share) in shape) hours += HourSteps(day, h, (steps * share).toInt())
            val score = (3 + (steps - 6_000) / 2_500 + r.nextInt(-1, 2)).coerceIn(1, 5)
            if (r.nextInt(100) < 70) moods += MoodEntry(moodId++, day, walks.last().startMs + 3_600_000L, score, "", walkId)
            walkId++
        }
        days += DayRecord(todayEpochDay, TODAY_STEPS + 140, TODAY_STEPS, moderateMin = 22, vigorousMin = 0, distanceM = TODAY_STEPS * 0.72)
        return DemoBundle(
            days = days, walks = walks, hours = hours, moods = moods, coupleDays = couple, coupleDistanceM = coupleM,
            buddyName = "Sam", buddyStepsToday = 6_120, buddyGoal = 8_000, goal = GOAL,
        )
    }
}
