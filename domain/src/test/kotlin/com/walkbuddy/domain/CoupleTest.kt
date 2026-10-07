package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupleTest {
    @Test fun coupleModeIsExactlyTwo() {
        assertFalse(CoupleMode.applies(1)); assertTrue(CoupleMode.applies(2)); assertFalse(CoupleMode.applies(3))
    }

    @Test fun calendarIntentForOneOffAndWeekly() {
        val one = WalkDatePlanner.toCalendarIntent(WalkDate(1, 1_000_000, 45, false, "Sunday loop"))
        assertEquals(1_000_000 + 45 * 60_000L, one.endMs)
        assertNull(one.rrule)
        val weekly = WalkDatePlanner.toCalendarIntent(WalkDate(2, 5, 30, true, ""))
        assertEquals("FREQ=WEEKLY", weekly.rrule)
        assertEquals("Walk date", weekly.title)
        assertTrue(WalkDatePlanner.toCalendarIntent(WalkDate(3, 0, 100000)).endMs <= 480 * 60_000L)
    }

    @Test fun nextOccurrenceRollsWeeklyForward() {
        val w = WalkDatePlanner.WEEK_MS
        val d = WalkDate(1, 1000, weekly = true)
        assertEquals(1000L, WalkDatePlanner.nextOccurrence(d, 500))
        assertEquals(1000L + w, WalkDatePlanner.nextOccurrence(d, 2000))
        assertEquals(1000L + 3 * w, WalkDatePlanner.nextOccurrence(d, 1000 + 2 * w + 1))
        assertEquals(1000L + w, WalkDatePlanner.nextOccurrence(d, 1000 + w))
        assertNull(WalkDatePlanner.nextOccurrence(d.copy(weekly = false), 2000))
    }

    @Test fun upcomingIsSortedAndDropsPastOneOffs() {
        val list = listOf(WalkDate(1, 9000), WalkDate(2, 100), WalkDate(3, 5000))
        assertEquals(listOf(3L, 1L), WalkDatePlanner.upcoming(list, 1000).map { it.first.id })
    }

    @Test fun togetherStreakText() {
        val r = TogetherStreak.lastDays(setOf(10, 11, 13, 14, 2), todayEpochDay = 14)
        assertEquals(4, r.daysTogether)
        assertEquals("Walked together 4 of 7 days", r.text)
        assertTrue(TogetherStreak.lastDays(emptySet(), 14).text.contains("short one counts"))
    }

    @Test fun odometerMilestones() {
        val s0 = Odometer.state(0.0)
        assertTrue(s0.reached.isEmpty()); assertEquals("A full marathon", s0.next!!.name); assertEquals(0.0, s0.fractionToNext, 1e-9)
        val s = Odometer.state(100_000.0)
        assertEquals(listOf("A full marathon"), s.reached.map { it.name })
        assertEquals("Bengaluru to Mysuru by road", s.next!!.name)
        assertEquals(40.0, s.remainingToNextKm!!, 1e-6)
        assertTrue(s.fractionToNext in 0.0..1.0)
        val big = Odometer.state(5_000_000.0)
        assertNull(big.next); assertEquals(1.0, big.fractionToNext, 1e-9)
        assertEquals(Odometer.MILESTONES.size, big.reached.size)
    }

    @Test fun roadMilestonesAreMarkedApproximate() {
        val road = Odometer.MILESTONES.filter { "road" in it.name }
        assertTrue(road.isNotEmpty())
        assertTrue(road.all { it.approximate && it.label.contains("about") })
        assertEquals(listOf(Odometer.MILESTONES.sortedBy { it.km }), listOf(Odometer.MILESTONES))
        assertEquals(140.0, Odometer.MILESTONES.first { "Mysuru" in it.name }.km, 0.0)
    }

    @Test fun pingLimiterEnforcesGapAndHourlyCap() {
        val l = PingLimiter(minGapMs = 60_000, maxPerHour = 3)
        assertTrue(l.tryAcquire(0))
        assertFalse(l.tryAcquire(30_000))
        assertTrue(l.tryAcquire(60_000))
        assertTrue(l.tryAcquire(120_000))
        assertFalse("hourly cap", l.tryAcquire(200_000))
        assertTrue("window slides", l.tryAcquire(3_700_000))
    }

    @Test fun spotBookValidatesAndDedupes() {
        val b = SpotBook()
        assertTrue(b.add(FavoriteSpot("Lake loop", 12.97, 77.59)))
        assertFalse("near duplicate", b.add(FavoriteSpot("lake LOOP", 12.97001, 77.59001)))
        assertTrue("same name far away is a different place", b.add(FavoriteSpot("Lake loop", 13.5, 77.59)))
        assertFalse(b.add(FavoriteSpot("", 1.0, 1.0)))
        assertFalse(b.add(FavoriteSpot("Bad", 95.0, 1.0)))
        assertEquals(2, b.spots.size)
        assertTrue(b.remove("lake loop"))
        assertTrue(b.spots.isEmpty())
    }

    @Test fun spotBookHasACap() {
        val b = SpotBook(max = 3)
        for (i in 0 until 5) b.add(FavoriteSpot("S$i", 10.0 + i, 20.0))
        assertEquals(3, b.spots.size)
    }

    private val summary = WalkSummary(
        durationMs = 40 * 60_000L, distanceM = 3200.0, rawSteps = 4500, verifiedSteps = 4300, avgSpeedMps = 1.33, buddyCount = 1,
        togetherPct = 87, longestTogetherMs = 20 * 60_000L, moderateMin = 25, vigorousMin = 0, nudgesShown = 1, calories = CalorieRange(90, 150),
    )

    @Test fun highlightsAreStatsOnlyWithNoLocations() {
        val c = Highlights.build(summary, showCalories = false)
        val text = c.lines.joinToString("\n")
        assertTrue(text.contains("3.20 km")); assertTrue(text.contains("4300 steps")); assertTrue(text.contains("87%"))
        assertFalse(text.contains("kcal"))
        for (bad in listOf("latitude", "longitude", "map", "http", "geo:")) assertFalse(bad, bad in text.lowercase())
        assertEquals("A walk together", c.title)
    }

    @Test fun highlightsCaloriesOnlyWhenEnabledAndPresent() {
        assertTrue(Highlights.build(summary, true).lines.any { "kcal" in it && "estimate" in it })
        assertFalse(Highlights.build(summary.copy(calories = null), true).lines.any { "kcal" in it })
        val solo = Highlights.build(summary.copy(buddyCount = 0, togetherPct = null), false)
        assertEquals("A good walk", solo.title)
        assertFalse(solo.lines.any { "Together" in it })
    }

    @Test fun csvExportEscapesAndGuardsFormulas() {
        assertEquals("plain", CsvExport.cell("plain"))
        assertEquals("\"a,b\"", CsvExport.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.cell("say \"hi\""))
        assertEquals("'=SUM(A1)", CsvExport.cell("=SUM(A1)"))
        assertEquals("'@cmd", CsvExport.cell("@cmd"))
        val csv = CsvExport.days(listOf(DayRecord(5, 100, 90, 3, 1, 456.4, true), DayRecord(2, 10, 10)))
        val lines = csv.trim().split("\r\n")
        assertEquals("epoch_day,raw_steps,verified_steps,moderate_min,vigorous_min,distance_m,rest_day", lines[0])
        assertEquals("2,10,10,0,0,0,false", lines[1])
        assertEquals("5,100,90,3,1,456,true", lines[2])
        val spots = CsvExport.spots(listOf(FavoriteSpot("=evil", 1.0, 2.0)))
        assertTrue(spots.contains("'=evil"))
        assertNotNull(CsvExport.walks(emptyList()).lines().first())
    }
}
