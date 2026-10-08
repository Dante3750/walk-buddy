package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    @Test fun histogramAveragesPerDayWithData() {
        val e = listOf(
            HourSteps(10, 7, 1000), HourSteps(11, 7, 2000), HourSteps(12, 7, 3000),
            HourSteps(10, 18, 500), HourSteps(11, 18, 500), HourSteps(12, 18, 500),
            HourSteps(-100, 7, 99999), // outside the window
        )
        val p = HourlyHistogram.build(e, 12)
        assertEquals(3, p.daysCovered); assertEquals(2000, p.perHour[7]); assertEquals(500, p.perHour[18]); assertEquals(7, p.bestHour)
        assertEquals(24, p.perHour.size)
    }

    @Test fun histogramNeedsAFewDays() {
        val p = HourlyHistogram.build(listOf(HourSteps(10, 7, 100), HourSteps(11, 7, 100)), 12)
        assertNull(p.bestHour)
        assertTrue(HourlyHistogram.bestText(p, false).contains("Keep walking"))
        assertEquals(0, HourlyHistogram.build(emptyList(), 5).max)
    }

    @Test fun hourLabels() {
        assertEquals("12 am", HourlyHistogram.hourLabel(0, false)); assertEquals("12 pm", HourlyHistogram.hourLabel(12, false))
        assertEquals("7 am", HourlyHistogram.hourLabel(7, false)); assertEquals("6 pm", HourlyHistogram.hourLabel(18, false))
        assertEquals("07:00", HourlyHistogram.hourLabel(7, true)); assertEquals("00:00", HourlyHistogram.hourLabel(24, true))
        val p = HourlyProfile(List(24) { if (it == 7) 100 else 1 }, 5, 7)
        assertEquals("Your best walking hour: 7 am to 8 am", HourlyHistogram.bestText(p, false))
    }

    @Test fun monthGridLevelsAndLayout() {
        assertEquals(0, MonthGrid.level(0, 6000)); assertEquals(1, MonthGrid.level(2999, 6000)); assertEquals(2, MonthGrid.level(5999, 6000))
        assertEquals(3, MonthGrid.level(6000, 6000)); assertEquals(3, MonthGrid.level(8999, 6000)); assertEquals(4, MonthGrid.level(9000, 6000))
        // October 2026 starts on a Thursday: three blanks with a Monday-first week.
        val first = java.time.LocalDate.of(2026, 10, 1).toEpochDay()
        val m = MonthGrid.build(2026, 10, mapOf(first to 6500, first + 1 to 100, first + 20 to 9999), { 6000 }, todayEpochDay = first + 5)
        assertEquals(3, m.leadingBlanks); assertEquals(31, m.cells.size)
        assertEquals(3, m.cells[0].level); assertEquals(1, m.cells[1].level)
        assertTrue(m.cells[5].isToday); assertTrue(m.cells[6].isFuture)
        assertEquals(0, m.cells[20].level) // the future never shows data
        assertEquals(2, m.daysWithSteps); assertEquals(1, m.goalDays)
    }

    private fun input(days: List<DayRecord> = emptyList(), walks: List<WalkRecord> = emptyList(), couple: Set<Long> = emptySet(), km: Double = 0.0, streak: Int = 0, moods: Int = 0) =
        BadgeInput(days, walks, { 6000 }, couple, km * 1000, streak, moods)

    private fun walk(hour: Int, buddies: Int = 0, day: Long = 1) =
        WalkRecord(day * 10 + hour, 0, 600_000, 900.0, 1200, 1200, 5, 0, hour, buddies, null, 0, day)

    @Test fun runs() {
        assertEquals(0, Runs.longest(emptySet())); assertEquals(3, Runs.longest(setOf(1, 2, 3, 7, 9))); assertEquals(1, Runs.longest(setOf(5)))
    }

    @Test fun nothingEarnedAtStart() {
        assertTrue(BadgeEngine.evaluate(input()).isEmpty())
    }

    @Test fun firstGoalAndTenK() {
        val e = BadgeEngine.evaluate(input(days = listOf(DayRecord(1, verifiedSteps = 6000), DayRecord(2, verifiedSteps = 10_500))))
        assertTrue(BadgeId.FirstGoal in e); assertTrue(BadgeId.TenK in e)
        assertFalse(BadgeId.FirstGoal in BadgeEngine.evaluate(input(days = listOf(DayRecord(1, verifiedSteps = 5999)))))
    }

    @Test fun togetherBadges() {
        assertTrue(BadgeId.FirstTogether in BadgeEngine.evaluate(input(walks = listOf(walk(8, 1)))))
        assertFalse(BadgeId.FirstTogether in BadgeEngine.evaluate(input(walks = listOf(walk(8, 0)))))
        assertTrue(BadgeId.TogetherWeek in BadgeEngine.evaluate(input(couple = (1L..7L).toSet())))
        assertFalse(BadgeId.TogetherWeek in BadgeEngine.evaluate(input(couple = setOf(1L, 2, 3, 4, 5, 6, 8))))
        val far = BadgeEngine.evaluate(input(km = 100.0))
        assertTrue(BadgeId.Marathon in far && BadgeId.HundredKm in far)
        val mid = BadgeEngine.evaluate(input(km = 50.0))
        assertTrue(BadgeId.Marathon in mid); assertFalse(BadgeId.HundredKm in mid)
    }

    @Test fun earlyBirdAndNightOwlNeedThree() {
        assertFalse(BadgeId.EarlyBird in BadgeEngine.evaluate(input(walks = listOf(walk(5), walk(6)))))
        assertTrue(BadgeId.EarlyBird in BadgeEngine.evaluate(input(walks = listOf(walk(5), walk(6), walk(6, day = 2)))))
        assertFalse(BadgeId.EarlyBird in BadgeEngine.evaluate(input(walks = listOf(walk(7), walk(8), walk(9)))))
        assertTrue(BadgeId.NightOwl in BadgeEngine.evaluate(input(walks = listOf(walk(21), walk(22), walk(23)))))
    }

    @Test fun comebackNeedsThreeQuietDaysBetweenGoals() {
        val met = { d: Long -> DayRecord(d, verifiedSteps = 7000) }
        assertTrue(BadgeId.Comeback in BadgeEngine.evaluate(input(days = listOf(met(1), met(5)))))
        assertFalse(BadgeId.Comeback in BadgeEngine.evaluate(input(days = listOf(met(1), met(4)))))
        // Rest days in the gap are not "quiet".
        val withRest = listOf(met(1), DayRecord(2, restDay = true), DayRecord(3, restDay = true), met(5))
        assertFalse(BadgeId.Comeback in BadgeEngine.evaluate(input(days = withRest)))
        assertFalse(BadgeId.Comeback in BadgeEngine.evaluate(input(days = listOf(met(1)))))
    }

    @Test fun streakAndMoodBadges() {
        val e = BadgeEngine.evaluate(input(streak = 30, moods = 7))
        assertTrue(BadgeId.Streak7 in e && BadgeId.Streak30 in e && BadgeId.MoodKeeper in e)
        assertFalse(BadgeId.Streak30 in BadgeEngine.evaluate(input(streak = 29)))
    }

    @Test fun newlyUnlockedOnlyReportsFreshOnesInOrder() {
        val earned = setOf(BadgeId.TenK, BadgeId.FirstGoal, BadgeId.Streak7)
        val fresh = BadgeEngine.newlyUnlocked(earned, setOf(BadgeId.FirstGoal))
        assertEquals(listOf(BadgeId.TenK, BadgeId.Streak7), fresh)
        assertTrue(BadgeEngine.newlyUnlocked(earned, earned).isEmpty())
    }

    @Test fun progressTextForLockedBadges() {
        val i = input(couple = setOf(1L, 2, 3), walks = listOf(walk(5)), km = 21.0)
        assertEquals(3 / 7.0, BadgeEngine.progress(BadgeId.TogetherWeek, i).fraction, 1e-9)
        assertEquals("1 of 3 early walks", BadgeEngine.progress(BadgeId.EarlyBird, i).text)
        assertEquals("21.0 of 100 km", BadgeEngine.progress(BadgeId.HundredKm, i).text)
        BadgeId.values().forEach { assertNotNull(BadgeEngine.progress(it, i)) } // every badge has a progress rule
        assertTrue(BadgeId.values().all { it.title.isNotBlank() && it.blurb.isNotBlank() && it.glyph.isNotBlank() })
    }

    @Test fun moodEnumAndNotes() {
        assertEquals(Mood.Good, Mood.fromScore(4)); assertNull(Mood.fromScore(9))
        assertEquals(5, Mood.values().size)
        assertEquals("hi there", MoodNote.clean("  hi\u0007 there \n"))
        assertEquals(MoodNote.MAX, MoodNote.clean("x".repeat(500)).length)
    }

    private fun moodDays(pairs: List<Pair<Int, Int>>): Pair<List<MoodEntry>, Map<Long, Int>> {
        val entries = pairs.mapIndexed { i, (steps, mood) -> MoodEntry(i.toLong(), 100L + i, 0, mood, "", null) }
        val steps = pairs.mapIndexed { i, (s, _) -> 100L + i to s }.toMap()
        return entries to steps
    }

    @Test fun moodInsightNeedsEnoughDays() {
        val (e, s) = moodDays(listOf(5000 to 3, 6000 to 4))
        val r = MoodInsights.compute(e, s)
        assertFalse(r.enoughData); assertNull(r.correlation); assertTrue(r.text.contains("2 of 7"))
    }

    @Test fun moodInsightPositiveLink() {
        val (e, s) = moodDays(listOf(2000 to 1, 3000 to 2, 4000 to 2, 5000 to 3, 6000 to 3, 8000 to 4, 9000 to 5, 10000 to 5))
        val r = MoodInsights.compute(e, s)
        assertTrue(r.enoughData); assertTrue(r.correlation!! > 0.9)
        assertTrue(r.avgOnActiveDays!! > r.avgOnQuietDays!!)
        assertTrue(r.text.contains("higher"))
        assertTrue(MoodInsights.CAVEAT.contains("not a cause"))
    }

    @Test fun moodInsightNegativeAndNoLink() {
        val (e, s) = moodDays(listOf(2000 to 5, 3000 to 5, 4000 to 4, 5000 to 4, 6000 to 3, 8000 to 2, 9000 to 2, 10000 to 1))
        assertTrue(MoodInsights.compute(e, s).text.contains("lower"))
        val (e2, s2) = moodDays(List(8) { 5000 + it * 100 to 3 }) // mood never changes: no variance
        val flat = MoodInsights.compute(e2, s2)
        assertNull(flat.correlation); assertTrue(flat.text.startsWith("No clear link"))
    }

    @Test fun moodInsightIgnoresDaysWithoutSteps() {
        val (e, s) = moodDays(List(8) { 0 to 3 })
        assertEquals(0, MoodInsights.compute(e, s).pairedDays)
    }

    @Test fun pearsonBasics() {
        assertEquals(1.0, MoodInsights.pearson(listOf(1.0, 2.0, 3.0), listOf(2.0, 4.0, 6.0))!!, 1e-9)
        assertEquals(-1.0, MoodInsights.pearson(listOf(1.0, 2.0, 3.0), listOf(6.0, 4.0, 2.0))!!, 1e-9)
        assertNull(MoodInsights.pearson(listOf(1.0), listOf(1.0)))
    }
}
