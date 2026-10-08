package com.walkbuddy.domain

import kotlin.math.abs
import kotlin.math.sqrt

enum class Mood(val score: Int, val label: String, val emoji: String) {
    Rough(1, "Rough", "😞"),
    Low(2, "A bit low", "😕"),
    Okay(3, "Okay", "🙂"),
    Good(4, "Good", "😊"),
    Great(5, "Great", "🤩");

    companion object {
        fun fromScore(score: Int): Mood? = values().firstOrNull { it.score == score }
    }
}

/** A post-walk check-in. Stored only on this phone, never shared with a buddy. */
data class MoodEntry(
    val id: Long,
    val epochDay: Long,
    val atMs: Long,
    val mood: Int,
    val note: String,
    val walkId: Long?,
)

object MoodNote {
    const val MAX = 80
    fun clean(raw: String): String = raw.filter { !it.isISOControl() }.trim().take(MAX)
}

data class MoodInsight(
    val enoughData: Boolean,
    val pairedDays: Int,
    val correlation: Double?,
    val avgOnActiveDays: Double?,
    val avgOnQuietDays: Double?,
    val text: String,
)

/**
 * Honest mood-vs-steps insight: a plain correlation over days that have both a check-in and steps.
 * Needs at least [MIN_DAYS] days, and always says that a pattern is not a cause.
 */
object MoodInsights {
    const val MIN_DAYS = 7
    const val CAVEAT = "A pattern is not a cause. Many things shape a good day, so treat this as curiosity, not advice."

    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 2) return null
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in x.indices) {
            val dx = x[i] - mx; val dy = y[i] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return null
        return sxy / sqrt(sxx * syy)
    }

    fun compute(entries: List<MoodEntry>, stepsByDay: Map<Long, Int>): MoodInsight {
        val perDay = entries.groupBy { it.epochDay }.mapValues { (_, l) -> l.map { it.mood }.average() }
        val paired = perDay.mapNotNull { (day, mood) -> stepsByDay[day]?.takeIf { it > 0 }?.let { Triple(day, it.toDouble(), mood) } }
        if (paired.size < MIN_DAYS) {
            return MoodInsight(false, paired.size, null, null, null, "Check in after a few more walks (${paired.size} of $MIN_DAYS days so far) and a pattern may appear.")
        }
        val r = pearson(paired.map { it.second }, paired.map { it.third })
        val sortedSteps = paired.map { it.second }.sorted()
        val median = if (sortedSteps.size % 2 == 1) sortedSteps[sortedSteps.size / 2] else (sortedSteps[sortedSteps.size / 2 - 1] + sortedSteps[sortedSteps.size / 2]) / 2.0
        val active = paired.filter { it.second > median }.map { it.third }
        val quiet = paired.filter { it.second <= median }.map { it.third }
        val avgA = active.takeIf { it.isNotEmpty() }?.average()
        val avgQ = quiet.takeIf { it.isNotEmpty() }?.average()
        val text = when {
            r == null || abs(r) < 0.2 || avgA == null || avgQ == null -> "No clear link between your steps and your mood so far."
            r > 0 -> "On days with more steps you tended to rate your mood a little higher (%.1f against %.1f on quieter days).".format(java.util.Locale.US, avgA, avgQ)
            else -> "On days with more steps you tended to rate your mood a little lower (%.1f against %.1f on quieter days). Tired days happen, and rest is part of it.".format(java.util.Locale.US, avgA, avgQ)
        }
        return MoodInsight(true, paired.size, r, avgA, avgQ, text)
    }
}
