package com.walkbuddy.domain

import kotlin.math.max

/** Adaptive daily step goal: 14-day median plus ~10%, rest-day aware, gentle and capped. */
object AdaptiveGoal {
    const val DEFAULT_GOAL = 6000
    const val MIN_GOAL = 3000
    const val MAX_GOAL = 10000
    const val GROWTH = 1.10
    const val MIN_DAYS = 3

    data class GoalPlan(val goal: Int, val isRestDay: Boolean, val basedOnDays: Int)

    fun median(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * [history] is own past days (today excluded). Rest days and days with 0 steps (phone not carried)
     * are left out of the median so a rest day never drags the goal down. [restWeekdays] uses 1=Mon..7=Sun.
     */
    fun planFor(
        todayEpochDay: Long,
        history: List<DayRecord>,
        restWeekdays: Set<Int> = emptySet(),
        fixedGoal: Int? = null,
    ): GoalPlan {
        val rest = weekdayOf(todayEpochDay) in restWeekdays
        if (fixedGoal != null) return GoalPlan(fixedGoal.coerceIn(500, 50_000), rest, 0)
        val window = history.filter { it.epochDay < todayEpochDay && it.epochDay >= todayEpochDay - 14 }
        val usable = window.filter { !it.restDay && weekdayOf(it.epochDay) !in restWeekdays && it.verifiedSteps > 0 }
        if (usable.size < MIN_DAYS) return GoalPlan(DEFAULT_GOAL, rest, usable.size)
        val med = median(usable.map { it.verifiedSteps })
        val raw = med * GROWTH
        val rounded = (Math.round(raw / 100.0) * 100).toInt()
        return GoalPlan(rounded.coerceIn(MIN_GOAL, MAX_GOAL), rest, usable.size)
    }

    /** 1 = Monday .. 7 = Sunday. 1970-01-01 (epoch day 0) was a Thursday. */
    fun weekdayOf(epochDay: Long): Int = (((epochDay + 3) % 7 + 7) % 7).toInt() + 1
}

/** Active minutes tracked from cadence, compared with the commonly cited 150 min/week guidance. */
class ActiveMinuteCounter {
    var moderateSec = 0L; private set
    var vigorousSec = 0L; private set

    fun add(cadenceSpm: Double?, seconds: Int) {
        if (seconds <= 0 || cadenceSpm == null) return
        when (PaceZone.fromCadence(cadenceSpm)) {
            PaceZone.Brisk -> moderateSec += seconds
            PaceZone.Vigorous -> vigorousSec += seconds
            else -> Unit
        }
    }

    val moderateMin: Int get() = (moderateSec / 60).toInt()
    val vigorousMin: Int get() = (vigorousSec / 60).toInt()
}

object WeeklyGuidance {
    const val TARGET_MIN = 150

    data class Progress(val equivMin: Int, val fraction: Double, val remainingMin: Int, val met: Boolean)

    fun progress(moderateMin: Int, vigorousMin: Int): Progress {
        val equiv = moderateMin + 2 * vigorousMin
        return Progress(equiv, (equiv.toDouble() / TARGET_MIN).coerceIn(0.0, 1.0), max(0, TARGET_MIN - equiv), equiv >= TARGET_MIN)
    }
}

sealed class SitAction {
    data class Remind(val text: String) : SitAction()
}

/** Gentle sitting-break reminders: about 60 min still, then a 2 min stand/walk. Snoozes and respects quiet hours. */
class SittingMonitor(
    private val sedentaryLimitMs: Long = 60 * 60_000L,
    private val breakMs: Long = 2 * 60_000L,
    private val snoozeMs: Long = 30 * 60_000L,
    private val quietFromHour: Int = 22,
    private val quietToHour: Int = 7,
) {
    private var stillSince: Long? = null
    private var movingSince: Long? = null
    private var lastRemindMs: Long? = null

    fun onSample(nowMs: Long, moving: Boolean, hourOfDay: Int, walkInProgress: Boolean = false): SitAction? {
        if (walkInProgress) { stillSince = nowMs; movingSince = null; return null }
        if (stillSince == null) stillSince = nowMs
        if (moving) {
            val ms = movingSince ?: nowMs.also { movingSince = it }
            if (nowMs - ms >= breakMs) { stillSince = nowMs; lastRemindMs = null }
            return null
        }
        movingSince = null
        val since = stillSince ?: nowMs
        if (nowMs - since < sedentaryLimitMs) return null
        if (isQuietHour(hourOfDay)) return null
        lastRemindMs?.let { if (nowMs - it < snoozeMs) return null }
        lastRemindMs = nowMs
        return SitAction.Remind("You have been sitting for a while. A 2 minute stand or stroll is a nice reset.")
    }

    /** For coarse sampling (e.g. every 15 min): a sample that shows real walking counts as a completed break. */
    fun markBreak(nowMs: Long) { stillSince = nowMs; movingSince = null; lastRemindMs = null }

    /** Persistable state: [stillSince, movingSince, lastRemind], -1 meaning "none". */
    fun snapshot(): LongArray = longArrayOf(stillSince ?: -1, movingSince ?: -1, lastRemindMs ?: -1)

    fun restore(s: LongArray) {
        if (s.size < 3) return
        stillSince = s[0].takeIf { it >= 0 }
        movingSince = s[1].takeIf { it >= 0 }
        lastRemindMs = s[2].takeIf { it >= 0 }
    }

    private fun isQuietHour(h: Int) = if (quietFromHour > quietToHour) (h >= quietFromHour || h < quietToHour) else (h in quietFromHour until quietToHour)
}

data class StreakResult(val current: Int, val longest: Int, val tokens: Int)

/**
 * Daily-goal streaks with grace: configured rest weekdays never break a streak, and up to [MAX_TOKENS] rest tokens
 * (earned: 1 per 7 goal days) bridge a missed day. Today being unmet never breaks anything, the day is not over.
 */
object Streaks {
    const val MAX_TOKENS = 2

    fun compute(metDays: Set<Long>, todayEpochDay: Long, firstEpochDay: Long, restWeekdays: Set<Int> = emptySet()): StreakResult {
        var streak = 0; var longest = 0; var tokens = 1; var metCount = 0
        var day = firstEpochDay
        while (day <= todayEpochDay) {
            if (day in metDays) {
                streak++; metCount++
                if (metCount % 7 == 0) tokens = minOf(MAX_TOKENS, tokens + 1)
                if (streak > longest) longest = streak
            } else if (AdaptiveGoal.weekdayOf(day) in restWeekdays) {
                // rest day: neither extends nor breaks
            } else if (day == todayEpochDay) {
                // still in progress
            } else if (tokens > 0 && streak > 0) {
                tokens--
            } else {
                streak = 0
            }
            day++
        }
        return StreakResult(streak, longest, tokens)
    }
}

data class TeamGoalProgress(val totalSteps: Long, val target: Long, val fraction: Double, val message: String)

/** Shared step goals for a group. Deliberately exposes only the group total: no per-person ranking. */
object TeamGoals {
    fun suggestWeeklyTarget(memberDailyGoals: List<Int>): Long = memberDailyGoals.sumOf { it.toLong() } * 7

    fun progress(memberSteps: List<Long>, target: Long): TeamGoalProgress {
        val total = memberSteps.sum()
        val f = if (target <= 0) 0.0 else (total.toDouble() / target).coerceIn(0.0, 1.0)
        val msg = when {
            target <= 0 -> "Set a shared goal together."
            total >= target -> "Goal reached together. Nicely done."
            f >= 0.75 -> "Almost there as a team."
            f >= 0.4 -> "Good progress, together."
            else -> "A fresh start. Every walk counts."
        }
        return TeamGoalProgress(total, target, f, msg)
    }
}
