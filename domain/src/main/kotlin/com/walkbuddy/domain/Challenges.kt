package com.walkbuddy.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Walk challenges (alpha 2.0): shared goals for walks done together, kept on the phone. A challenge is a template (what to reach and over
 * which period) plus the dates it was started for. Progress is worked out from the saved "Walks together" history, so nothing new is
 * recorded and nothing is compared between people: a group challenge is one number for everyone, never a ranking.
 */
enum class ChallengeKind { DistanceKm, TogetherWalks, GroupSteps }

enum class ChallengePeriod { Week, Month }

data class ChallengeTemplate(val id: String, val kind: ChallengeKind, val period: ChallengePeriod, val target: Int) {
    val targetD: Double get() = target.toDouble()
}

/** One walk from the history, reduced to what challenges need. */
data class ChallengeWalk(val epochDay: Long, val distanceM: Double, val steps: Long, val members: Int)

/** A challenge that was started: its template and the (inclusive) first and last epoch day of its period. */
data class ChallengeInstance(val id: Long, val templateId: String, val startDay: Long, val endDay: Long, val completedMs: Long? = null)

enum class ChallengeState { Active, Completed, Missed }

data class ChallengeProgress(
    val instance: ChallengeInstance,
    val template: ChallengeTemplate,
    val current: Double,
    val fraction: Float,
    val state: ChallengeState,
    val daysLeft: Int,
    /** True the moment the target is reached and the celebration has not been recorded yet. */
    val justCompleted: Boolean,
)

object Challenges {
    const val MAX_ACTIVE = 3

    val templates: List<ChallengeTemplate> = listOf(
        ChallengeTemplate("km10_week", ChallengeKind.DistanceKm, ChallengePeriod.Week, 10),
        ChallengeTemplate("walks3_week", ChallengeKind.TogetherWalks, ChallengePeriod.Week, 3),
        ChallengeTemplate("steps30k_week", ChallengeKind.GroupSteps, ChallengePeriod.Week, 30_000),
        ChallengeTemplate("km40_month", ChallengeKind.DistanceKm, ChallengePeriod.Month, 40),
        ChallengeTemplate("walks7_month", ChallengeKind.TogetherWalks, ChallengePeriod.Month, 7),
        ChallengeTemplate("steps100k_month", ChallengeKind.GroupSteps, ChallengePeriod.Month, 100_000),
    )

    fun template(id: String): ChallengeTemplate? = templates.firstOrNull { it.id == id }

    /** First and last epoch day of the week (Monday to Sunday) or calendar month that contains [today]. */
    fun window(period: ChallengePeriod, today: Long): Pair<Long, Long> {
        val d = LocalDate.ofEpochDay(today)
        return when (period) {
            ChallengePeriod.Week ->
                d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay() to d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).toEpochDay()
            ChallengePeriod.Month -> {
                val ym = YearMonth.from(d)
                ym.atDay(1).toEpochDay() to ym.atEndOfMonth().toEpochDay()
            }
        }
    }

    fun value(t: ChallengeTemplate, walks: List<ChallengeWalk>, startDay: Long, endDay: Long): Double {
        val inside = walks.filter { it.epochDay in startDay..endDay && it.members >= 2 }
        return when (t.kind) {
            ChallengeKind.DistanceKm -> inside.sumOf { it.distanceM } / 1000.0
            ChallengeKind.TogetherWalks -> inside.size.toDouble()
            ChallengeKind.GroupSteps -> inside.sumOf { it.steps }.toDouble()
        }
    }

    fun progress(i: ChallengeInstance, walks: List<ChallengeWalk>, today: Long): ChallengeProgress? {
        val t = template(i.templateId) ?: return null
        val v = value(t, walks, i.startDay, i.endDay)
        val reached = v >= t.targetD
        val state = when {
            reached -> ChallengeState.Completed
            today > i.endDay -> ChallengeState.Missed
            else -> ChallengeState.Active
        }
        return ChallengeProgress(
            instance = i, template = t, current = v, fraction = (v / t.targetD).coerceIn(0.0, 1.0).toFloat(), state = state,
            daysLeft = (i.endDay - today + 1).toInt().coerceAtLeast(0), justCompleted = reached && i.completedMs == null,
        )
    }

    /** Can a new challenge of this template be started now? One of each template per period, and at most [MAX_ACTIVE] at once. */
    fun canStart(templateId: String, instances: List<ChallengeInstance>, today: Long): Boolean {
        val t = template(templateId) ?: return false
        val (s, e) = window(t.period, today)
        val activeNow = instances.filter { it.endDay >= today && it.completedMs == null }
        if (activeNow.size >= MAX_ACTIVE) return false
        return instances.none { it.templateId == templateId && it.startDay == s && it.endDay == e }
    }

    fun newInstance(templateId: String, today: Long): ChallengeInstance? {
        val t = template(templateId) ?: return null
        val (s, e) = window(t.period, today)
        return ChallengeInstance(0, templateId, s, e)
    }

    /** Active ones first (soonest to end), then the history, newest first. */
    fun ordered(list: List<ChallengeProgress>): List<ChallengeProgress> =
        list.sortedWith(compareBy<ChallengeProgress> { it.state != ChallengeState.Active }.thenBy { if (it.state == ChallengeState.Active) it.instance.endDay else -it.instance.endDay })

    fun toWalks(records: List<SharedWalkRecord>, zone: java.time.ZoneId): List<ChallengeWalk> = records.map { r ->
        ChallengeWalk(
            epochDay = java.time.Instant.ofEpochMilli(r.startMs).atZone(zone).toLocalDate().toEpochDay(),
            distanceM = r.myDistanceM, steps = r.lanes.sumOf { it.steps.toLong() }, members = r.memberCount,
        )
    }
}
