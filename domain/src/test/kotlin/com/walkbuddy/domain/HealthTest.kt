package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private fun days(today: Long, n: Int, steps: (Int) -> Int) = (1..n).map { DayRecord(today - it, verifiedSteps = steps(it), rawSteps = steps(it)) }

    @Test fun weekdayMath() {
        assertEquals(4, AdaptiveGoal.weekdayOf(0)) // 1970-01-01 Thursday
        assertEquals(1, AdaptiveGoal.weekdayOf(4)) // Monday
        assertEquals(7, AdaptiveGoal.weekdayOf(3)) // Sunday
        assertEquals(7, AdaptiveGoal.weekdayOf(-4)) // before the epoch still works
    }

    @Test fun goalIsMedianPlusTenPercent() {
        val today = 20_000L
        val plan = AdaptiveGoal.planFor(today, days(today, 14) { 6000 })
        assertEquals(6600, plan.goal)
        assertEquals(14, plan.basedOnDays)
    }

    @Test fun medianResistsOutliers() {
        val today = 20_000L
        val h = days(today, 13) { 5000 } + DayRecord(today - 14, verifiedSteps = 40_000)
        assertEquals(5500, AdaptiveGoal.planFor(today, h).goal)
    }

    @Test fun fewDaysFallBackToDefault() {
        assertEquals(AdaptiveGoal.DEFAULT_GOAL, AdaptiveGoal.planFor(100, days(100, 2) { 9000 }).goal)
        assertEquals(AdaptiveGoal.DEFAULT_GOAL, AdaptiveGoal.planFor(100, emptyList()).goal)
    }

    @Test fun goalIsClampedAndNeverExtreme() {
        assertEquals(AdaptiveGoal.MAX_GOAL, AdaptiveGoal.planFor(500, days(500, 14) { 20_000 }).goal)
        assertEquals(AdaptiveGoal.MIN_GOAL, AdaptiveGoal.planFor(500, days(500, 14) { 1_000 }).goal)
    }

    @Test fun restDaysAndZeroDaysDoNotDragTheGoalDown() {
        val today = 20_000L
        val base = days(today, 14) { 7000 }
        val withRest = base.mapIndexed { i, d -> if (i % 3 == 0) d.copy(verifiedSteps = 0, restDay = true) else d }
        assertEquals(7700, AdaptiveGoal.planFor(today, withRest).goal)
    }

    @Test fun restWeekdayIsFlaggedAndExcluded() {
        val sunday = 3L + 7 * 2000 // a Sunday
        val plan = AdaptiveGoal.planFor(sunday, days(sunday, 14) { d -> if (AdaptiveGoal.weekdayOf(sunday - d) == 7) 500 else 8000 }, restWeekdays = setOf(7))
        assertTrue(plan.isRestDay)
        assertEquals(8800, plan.goal)
    }

    @Test fun fixedGoalModeIsRespected() {
        assertEquals(8000, AdaptiveGoal.planFor(10, emptyList(), fixedGoal = 8000).goal)
    }

    @Test fun activeMinutesCountBriskAndVigorousOnly() {
        val c = ActiveMinuteCounter()
        c.add(80.0, 600); c.add(110.0, 600); c.add(140.0, 300); c.add(null, 100); c.add(110.0, -5)
        assertEquals(10, c.moderateMin)
        assertEquals(5, c.vigorousMin)
    }

    @Test fun weeklyGuidanceProgress() {
        val p = WeeklyGuidance.progress(moderateMin = 60, vigorousMin = 15)
        assertEquals(90, p.equivMin)
        assertEquals(0.6, p.fraction, 1e-9)
        assertEquals(60, p.remainingMin)
        assertFalse(p.met)
        assertTrue(WeeklyGuidance.progress(150, 0).met)
        assertEquals(1.0, WeeklyGuidance.progress(500, 0).fraction, 1e-9)
    }

    // ---- sitting ----
    private fun sit(m: SittingMonitor, fromMin: Int, toMin: Int, moving: Boolean, hour: Int = 14): List<Int> {
        val fired = mutableListOf<Int>()
        for (min in fromMin..toMin) if (m.onSample(min * 60_000L, moving, hour) != null) fired += min
        return fired
    }

    @Test fun remindsAfterAboutAnHourOfSitting() {
        val m = SittingMonitor()
        val fired = sit(m, 0, 59, false)
        assertTrue(fired.isEmpty())
        assertEquals(listOf(60), sit(m, 60, 85, false)) // then snoozed
    }

    @Test fun snoozeThenRemindsAgain() {
        val m = SittingMonitor()
        assertEquals(listOf(60, 90), sit(m, 0, 100, false))
    }

    @Test fun twoMinuteMovementResetsTheClock() {
        val m = SittingMonitor()
        sit(m, 0, 55, false)
        sit(m, 56, 58, true) // 2+ minutes of moving
        assertTrue(sit(m, 59, 110, false).none { it < 119 })
    }

    @Test fun briefMovementDoesNotReset() {
        val m = SittingMonitor()
        sit(m, 0, 50, false)
        m.onSample(51 * 60_000L, true, 14) // 1 sample of moving only
        assertEquals(listOf(60), sit(m, 52, 70, false))
    }

    @Test fun quietHoursAndWalksSuppressReminders() {
        val night = SittingMonitor()
        assertTrue(sit(night, 0, 200, false, hour = 23).isEmpty())
        val walking = SittingMonitor()
        for (min in 0..200) assertNull(walking.onSample(min * 60_000L, false, 14, walkInProgress = true))
    }

    // ---- streaks ----
    @Test fun simpleStreak() {
        val r = Streaks.compute(setOf(1, 2, 3, 4, 5), todayEpochDay = 5, firstEpochDay = 1)
        assertEquals(5, r.current); assertEquals(5, r.longest)
    }

    @Test fun todayNotYetMetDoesNotBreak() {
        val r = Streaks.compute(setOf(1, 2, 3), todayEpochDay = 4, firstEpochDay = 1)
        assertEquals(3, r.current)
    }

    @Test fun oneTokenBridgesAMissedDayButNotTwo() {
        val bridged = Streaks.compute(setOf(1, 2, 3, 5, 6), todayEpochDay = 6, firstEpochDay = 1)
        assertEquals(5, bridged.current)
        assertEquals(0, bridged.tokens)
        val broken = Streaks.compute(setOf(1, 2, 3, 6), todayEpochDay = 6, firstEpochDay = 1)
        assertEquals(1, broken.current)
        assertEquals(3, broken.longest)
    }

    @Test fun tokensAreEarnedEverySevenDaysCappedAtTwo() {
        val met = (1L..28L).toSet()
        val r = Streaks.compute(met, 28, 1)
        assertEquals(Streaks.MAX_TOKENS, r.tokens)
        assertEquals(28, r.current)
    }

    @Test fun restWeekdaysNeverBreakTheStreak() {
        val sundayBase = 3L // Sunday
        val met = (sundayBase + 1..sundayBase + 6).toSet() + (sundayBase + 8..sundayBase + 13).toSet()
        val r = Streaks.compute(met, sundayBase + 13, sundayBase + 1, restWeekdays = setOf(7))
        assertEquals(12, r.current)
        assertEquals(2, r.tokens) // 1 starting + 1 earned at day 7, none spent
    }

    // ---- team goals ----
    @Test fun teamGoalsAreCooperativeAndUnranked() {
        assertEquals(7L * (6000 + 7000), TeamGoals.suggestWeeklyTarget(listOf(6000, 7000)))
        val p = TeamGoals.progress(listOf(40_000, 20_000), 100_000)
        assertEquals(60_000, p.totalSteps)
        assertEquals(0.6, p.fraction, 1e-9)
        assertTrue(TeamGoals.progress(listOf(120_000), 100_000).message.contains("together", ignoreCase = true))
        val fields = TeamGoalProgress::class.java.declaredFields.map { it.name }
        assertTrue("no per-person data exposed: $fields", fields.none { it.contains("rank", true) || it.contains("leader", true) })
    }

    // ---- weekly report ----
    @Test fun weeklyReport() {
        val last = 100L
        val thisWeek = (94..100L).map { DayRecord(it, verifiedSteps = 7000, moderateMin = 10, vigorousMin = 2, distanceM = 5000.0) }
        val prev = (87..93L).map { DayRecord(it, verifiedSteps = 5000) }
        val walks = listOf(
            WalkRecord(1, 0, 40 * 60_000L, 3000.0, 4000, 4100, 25, 0, 7, 1, 80, 600_000, 95),
            WalkRecord(2, 0, 20 * 60_000L, 1500.0, 2000, 2000, 5, 0, 19, 1, 60, 300_000, 97),
            WalkRecord(3, 0, 20 * 60_000L, 1500.0, 2000, 2000, 5, 0, 8, 0, null, 0, 98),
        )
        val r = WeeklyReport.build(thisWeek + prev, walks, last)
        assertEquals(49_000, r.totalSteps)
        assertEquals(7000, r.avgStepsPerDay)
        assertEquals(Trend.Up, r.trend)
        assertEquals(40, r.trendPct)
        assertEquals(7 * 14, r.activeEquivMin)
        assertEquals(DayPart.Morning, r.bestDayPart)
        assertEquals(70, r.avgTogetherPct)
        assertEquals(3, r.walkCount)
        assertNotNull(r.guidance)
        assertEquals(35_000.0, r.distanceM, 0.1)
    }

    @Test fun weeklyReportWithoutDataIsGentle() {
        val r = WeeklyReport.build(emptyList(), emptyList(), 50)
        assertEquals(Trend.NoData, r.trend)
        assertNull(r.bestDayPart)
        assertNull(r.avgTogetherPct)
        assertEquals(0, r.totalSteps)
        val down = WeeklyReport.build((43..49L).map { DayRecord(it, verifiedSteps = 9000) } + (50L..56L).map { DayRecord(it, verifiedSteps = 3000) }, emptyList(), 56)
        assertEquals(Trend.Down, down.trend)
        assertTrue("never shames", listOf("lazy", "bad", "worse", "fail").none { it in down.trendText.lowercase() })
    }
}
