package com.walkbuddy.domain

enum class BadgeId(val title: String, val blurb: String, val glyph: String) {
    FirstGoal("First goal", "Reach your daily step goal once.", "1"),
    FirstTogether("First walk together", "Complete a walk with your buddy.", "2"),
    TogetherWeek("A week side by side", "Walk together 7 days in a row.", "7"),
    TenK("Ten thousand", "10,000 steps in one day.", "10K"),
    Marathon("Marathon, together", "Walk 42.2 km together in total.", "42"),
    HundredKm("100 km together", "Walk 100 km together in total.", "100"),
    EarlyBird("Early bird", "Start 3 walks before 7 am.", "AM"),
    NightOwl("Night owl", "Start 3 walks after 9 pm.", "PM"),
    Comeback("Comeback", "Reach your goal after 3 or more quiet days.", "UP"),
    Streak7("Seven-day flame", "Keep a goal streak for 7 days.", "7d"),
    Streak30("Thirty-day flame", "Keep a goal streak for 30 days.", "30d"),
    MoodKeeper("Check-in habit", "Log how you feel after 7 walks.", "7x"),
}

/** Everything the badge rules look at. [goalFor] gives the (gentle-aware) goal of any past day. */
class BadgeInput(
    val days: List<DayRecord>,
    val walks: List<WalkRecord>,
    val goalFor: (Long) -> Int,
    val coupleDays: Set<Long>,
    val coupleDistanceM: Double,
    val longestStreak: Int,
    val moodCount: Int,
)

data class BadgeProgress(val fraction: Double, val text: String)

object Runs {
    /** Longest run of consecutive days in [days]. */
    fun longest(days: Set<Long>): Int {
        var best = 0
        for (d in days) {
            if ((d - 1) in days) continue
            var len = 1
            while ((d + len) in days) len++
            if (len > best) best = len
        }
        return best
    }
}

object BadgeEngine {
    const val EARLY_BEFORE_HOUR = 7
    const val NIGHT_FROM_HOUR = 21
    const val QUIET_DAYS_FOR_COMEBACK = 3

    private fun metDays(i: BadgeInput): List<Long> =
        i.days.filter { it.verifiedSteps > 0 && it.verifiedSteps >= i.goalFor(it.epochDay) }.map { it.epochDay }.sorted()

    private fun earlyWalks(i: BadgeInput) = i.walks.count { it.hourOfDay in 4 until EARLY_BEFORE_HOUR }
    private fun nightWalks(i: BadgeInput) = i.walks.count { it.hourOfDay >= NIGHT_FROM_HOUR }

    private fun comeback(i: BadgeInput): Boolean {
        val met = metDays(i)
        val byDay = i.days.associateBy { it.epochDay }
        for (k in 1 until met.size) {
            val a = met[k - 1]; val b = met[k]
            var quiet = 0
            for (d in (a + 1) until b) if (byDay[d]?.restDay != true) quiet++
            if (quiet >= QUIET_DAYS_FOR_COMEBACK) return true
        }
        return false
    }

    fun evaluate(i: BadgeInput): Set<BadgeId> {
        val out = LinkedHashSet<BadgeId>()
        if (metDays(i).isNotEmpty()) out += BadgeId.FirstGoal
        if (i.walks.any { it.buddyCount >= 1 } || i.coupleDays.isNotEmpty()) out += BadgeId.FirstTogether
        if (Runs.longest(i.coupleDays) >= 7) out += BadgeId.TogetherWeek
        if (i.days.any { it.verifiedSteps >= 10_000 }) out += BadgeId.TenK
        if (i.coupleDistanceM >= 42_195.0) out += BadgeId.Marathon
        if (i.coupleDistanceM >= 100_000.0) out += BadgeId.HundredKm
        if (earlyWalks(i) >= 3) out += BadgeId.EarlyBird
        if (nightWalks(i) >= 3) out += BadgeId.NightOwl
        if (comeback(i)) out += BadgeId.Comeback
        if (i.longestStreak >= 7) out += BadgeId.Streak7
        if (i.longestStreak >= 30) out += BadgeId.Streak30
        if (i.moodCount >= 7) out += BadgeId.MoodKeeper
        return out
    }

    /** Badges earned now that were not known before, in display order. */
    fun newlyUnlocked(earned: Set<BadgeId>, known: Set<BadgeId>): List<BadgeId> = BadgeId.values().filter { it in earned && it !in known }

    private fun ratio(have: Double, need: Double) = (have / need).coerceIn(0.0, 1.0)

    fun progress(id: BadgeId, i: BadgeInput): BadgeProgress = when (id) {
        BadgeId.FirstGoal -> BadgeProgress(if (metDays(i).isNotEmpty()) 1.0 else 0.0, "Reach today's goal")
        BadgeId.FirstTogether -> {
            val done = i.walks.any { it.buddyCount >= 1 } || i.coupleDays.isNotEmpty()
            BadgeProgress(if (done) 1.0 else 0.0, "Start a walk with your buddy")
        }
        BadgeId.TogetherWeek -> {
            val n = Runs.longest(i.coupleDays)
            BadgeProgress(ratio(n.toDouble(), 7.0), "$n of 7 days in a row")
        }
        BadgeId.TenK -> {
            val m = i.days.maxOfOrNull { it.verifiedSteps } ?: 0
            BadgeProgress(ratio(m.toDouble(), 10_000.0), "Best day so far: ${Hero.thousands(m)} steps")
        }
        BadgeId.Marathon -> BadgeProgress(ratio(i.coupleDistanceM, 42_195.0), "%.1f of 42.2 km".format(java.util.Locale.US, i.coupleDistanceM / 1000.0))
        BadgeId.HundredKm -> BadgeProgress(ratio(i.coupleDistanceM, 100_000.0), "%.1f of 100 km".format(java.util.Locale.US, i.coupleDistanceM / 1000.0))
        BadgeId.EarlyBird -> BadgeProgress(ratio(earlyWalks(i).toDouble(), 3.0), "${minOf(earlyWalks(i), 3)} of 3 early walks")
        BadgeId.NightOwl -> BadgeProgress(ratio(nightWalks(i).toDouble(), 3.0), "${minOf(nightWalks(i), 3)} of 3 late walks")
        BadgeId.Comeback -> BadgeProgress(if (comeback(i)) 1.0 else 0.0, "Rest, then come back to your goal")
        BadgeId.Streak7 -> BadgeProgress(ratio(i.longestStreak.toDouble(), 7.0), "${minOf(i.longestStreak, 7)} of 7 days")
        BadgeId.Streak30 -> BadgeProgress(ratio(i.longestStreak.toDouble(), 30.0), "${minOf(i.longestStreak, 30)} of 30 days")
        BadgeId.MoodKeeper -> BadgeProgress(ratio(i.moodCount.toDouble(), 7.0), "${minOf(i.moodCount, 7)} of 7 check-ins")
    }
}
