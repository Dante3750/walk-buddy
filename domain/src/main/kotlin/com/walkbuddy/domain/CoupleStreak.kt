package com.walkbuddy.domain

/**
 * The couple streak (alpha 2.0): the days in a row the two of you walked together, with the same kind rules as the personal streak.
 * Rest weekdays never break it, and rest tokens (1 earned per 7 shared-walk days, at most 2) quietly cover a missed day. Today never
 * breaks anything: the day is not over. The words are chosen from [CoupleStreakMessage] so they can be translated and kept gentle.
 */
enum class CoupleStreakMessage {
    /** Nothing yet: invite, never pressure. */
    NoWalksYet,
    WalkedToday,
    RestDay,
    /** A day was missed and a rest token covered it. */
    TokenUsedYesterday,
    /** A day was missed, no token left: the count started again and that is fine. */
    StartedAgain,
    /** Streak is alive, today still open. */
    KeepGoing,
    /** Evening and today still open, with a streak to keep. */
    GentleEvening,
}

data class CoupleStreakInfo(
    val current: Int,
    val longest: Int,
    val tokens: Int,
    val walkedToday: Boolean,
    val message: CoupleStreakMessage,
    val tokenUsedOn: Long?,
    val brokeOn: Long?,
)

object CoupleStreak {
    fun compute(togetherDays: Set<Long>, today: Long, restWeekdays: Set<Int> = emptySet(), hourOfDay: Int = 12): CoupleStreakInfo {
        val first = togetherDays.minOrNull() ?: return CoupleStreakInfo(0, 0, 1, false, CoupleStreakMessage.NoWalksYet, null, null)
        var streak = 0; var longest = 0; var tokens = 1; var count = 0
        var tokenUsedOn: Long? = null; var brokeOn: Long? = null
        var day = first
        while (day <= today) {
            when {
                day in togetherDays -> {
                    streak++; count++
                    if (count % 7 == 0) tokens = minOf(Streaks.MAX_TOKENS, tokens + 1)
                    if (streak > longest) longest = streak
                }
                AdaptiveGoal.weekdayOf(day) in restWeekdays -> Unit
                day == today -> Unit
                tokens > 0 && streak > 0 -> { tokens--; tokenUsedOn = day }
                streak > 0 -> { streak = 0; brokeOn = day }
                else -> Unit
            }
            day++
        }
        val walkedToday = today in togetherDays
        val restToday = AdaptiveGoal.weekdayOf(today) in restWeekdays
        val msg = when {
            walkedToday -> CoupleStreakMessage.WalkedToday
            restToday -> CoupleStreakMessage.RestDay
            tokenUsedOn == today - 1 -> CoupleStreakMessage.TokenUsedYesterday
            brokeOn == today - 1 -> CoupleStreakMessage.StartedAgain
            streak > 0 && hourOfDay >= 18 -> CoupleStreakMessage.GentleEvening
            streak > 0 -> CoupleStreakMessage.KeepGoing
            else -> CoupleStreakMessage.NoWalksYet
        }
        return CoupleStreakInfo(streak, longest, tokens, walkedToday, msg, tokenUsedOn, brokeOn)
    }
}
