package com.walkbuddy.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, utc).toInstant().toEpochMilli()

    @Test fun reactionsAreAFixedPreset() {
        assertEquals(Reaction.Love, Reaction.fromId("love")); assertNull(Reaction.fromId("hello")); assertNull(Reaction.fromId(null))
        assertTrue(Reaction.values().size in 4..8)
        assertTrue(Reaction.values().map { it.id }.toSet().size == Reaction.values().size)
        assertTrue(Reaction.values().all { it.label.isNotBlank() && it.emoji.isNotBlank() })
    }

    @Test fun reactionLimiterBlocksSpam() {
        val l = Reactions.senderLimiter()
        assertTrue(l.tryAcquire(0)); assertFalse(l.tryAcquire(1_000)); assertTrue(l.tryAcquire(5_000))
        var sent = 0
        val l2 = Reactions.senderLimiter()
        for (i in 0 until 100) if (l2.tryAcquire(i * 5_000L)) sent++
        assertEquals(40, sent) // hourly cap
    }

    @Test fun reactionAndDailyMessagesRoundTrip() {
        val r = MessageCodec.decode(MessageCodec.encode(PeerMessage.React("proud"))) as DecodeResult.Ok
        assertEquals(PeerMessage.React("proud"), r.message)
        val d = MessageCodec.decode(MessageCodec.encode(PeerMessage.Daily(4200, 7000))) as DecodeResult.Ok
        assertEquals(PeerMessage.Daily(4200, 7000), d.message)
    }

    @Test fun hostileReactionAndDailyAreRejected() {
        assertTrue(MessageCodec.decode("""{"v":1,"t":"react","id":"<script>"}""") is DecodeResult.Rejected)
        assertTrue(MessageCodec.decode("""{"v":1,"t":"react"}""") is DecodeResult.Rejected)
        assertTrue(MessageCodec.decode("""{"v":1,"t":"day","steps":-5,"goal":6000}""") is DecodeResult.Rejected)
        assertTrue(MessageCodec.decode("""{"v":1,"t":"day","steps":100,"goal":3}""") is DecodeResult.Rejected)
        assertTrue(MessageCodec.decode("""{"v":1,"t":"day","steps":999999999,"goal":6000}""") is DecodeResult.Rejected)
    }

    @Test fun engineIgnoresNewMessagesSafely() {
        val e = WalkEngine("me", "Me", WalkConfig(), 0)
        e.onPeer(1000, "b", PeerMessage.React("love"))
        e.onPeer(2000, "b", PeerMessage.Daily(100, 6000))
        assertEquals(1, e.tick(3000).buddies.size)
    }

    @Test fun quietHoursWrapPastMidnight() {
        val q = QuietHours(true, 22, 7)
        assertTrue(q.isQuiet(23)); assertTrue(q.isQuiet(3)); assertFalse(q.isQuiet(7)); assertFalse(q.isQuiet(12)); assertTrue(q.isQuiet(22))
        assertFalse(QuietHours(false, 22, 7).isQuiet(23))
        val day = QuietHours(true, 13, 15)
        assertTrue(day.isQuiet(14)); assertFalse(day.isQuiet(15)); assertFalse(QuietHours(true, 5, 5).isQuiet(5))
    }

    @Test fun anniversaryCountsDownYearly() {
        val a = LocalDate.of(2023, 3, 14)
        val i = AnniversaryCountdown.info(a, "our day", LocalDate.of(2026, 3, 10))
        assertEquals(4, i.daysUntil); assertEquals(3, i.yearsTogether); assertEquals("4 days until our day (3 years)", i.text)
        assertEquals("Today is our day (3 years)", AnniversaryCountdown.info(a, "our day", LocalDate.of(2026, 3, 14)).text)
        assertEquals("Tomorrow is our day (3 years)", AnniversaryCountdown.info(a, "our day", LocalDate.of(2026, 3, 13)).text)
        val after = AnniversaryCountdown.info(a, "our day", LocalDate.of(2026, 3, 15))
        assertEquals(LocalDate.of(2027, 3, 14), after.date); assertEquals(364, after.daysUntil); assertEquals(4, after.yearsTogether)
        assertTrue(AnniversaryCountdown.info(a, "", LocalDate.of(2026, 3, 1)).text.contains("your day"))
    }

    @Test fun anniversaryLeapDayAndFutureDate() {
        val leap = LocalDate.of(2020, 2, 29)
        assertEquals(LocalDate.of(2027, 2, 28), AnniversaryCountdown.info(leap, "x", LocalDate.of(2027, 2, 1)).date)
        assertEquals(LocalDate.of(2028, 2, 29), AnniversaryCountdown.info(leap, "x", LocalDate.of(2028, 1, 1)).date)
        val trip = AnniversaryCountdown.info(LocalDate.of(2026, 12, 25), "the trip", LocalDate.of(2026, 12, 20))
        assertEquals(5, trip.daysUntil); assertNull(trip.yearsTogether); assertEquals("5 days until the trip", trip.text)
    }

    @Test fun parseDates() {
        assertEquals(LocalDate.of(2024, 1, 5), AnniversaryCountdown.parse("2024-01-05")); assertNull(AnniversaryCountdown.parse("nope")); assertNull(AnniversaryCountdown.parse(null))
        assertEquals(412L, AnniversaryCountdown.daysTogether(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 2, 16)))
        assertNull(AnniversaryCountdown.daysTogether(LocalDate.of(2030, 1, 1), LocalDate.of(2026, 2, 16)))
    }

    @Test fun anniversaryReminderFiresOnceAndRespectsQuietHours() {
        val a = LocalDate.of(2023, 3, 14) to "our day"
        val now = ms(2026, 3, 13, 10)
        val r = ReminderPlanner.due(now, utc, a, emptyList(), emptySet(), QuietHours())
        assertEquals(1, r.size); assertTrue(r[0].text.contains("Tomorrow"))
        assertTrue(ReminderPlanner.due(now, utc, a, emptyList(), setOf(r[0].key), QuietHours()).isEmpty())
        assertTrue(ReminderPlanner.due(ms(2026, 3, 13, 8), utc, a, emptyList(), emptySet(), QuietHours()).isEmpty()) // not before 9
        assertTrue(ReminderPlanner.due(now, utc, a, emptyList(), emptySet(), QuietHours(true, 9, 12)).isEmpty())
        assertTrue(ReminderPlanner.due(ms(2026, 3, 10, 10), utc, a, emptyList(), emptySet(), QuietHours()).isEmpty()) // 4 days away
        assertEquals("Today", ReminderPlanner.due(ms(2026, 3, 14, 10), utc, a, emptyList(), emptySet(), QuietHours())[0].title)
    }

    @Test fun walkDateReminderWindow() {
        val start = ms(2026, 5, 1, 18)
        val d = WalkDate(5, start, title = "Lake loop")
        assertEquals(1, ReminderPlanner.due(start - 20 * 60_000L, utc, null, listOf(d), emptySet(), QuietHours()).size)
        assertTrue(ReminderPlanner.due(start - 40 * 60_000L, utc, null, listOf(d), emptySet(), QuietHours()).isEmpty())
        assertTrue(ReminderPlanner.due(start + 60_000L, utc, null, listOf(d), emptySet(), QuietHours()).isEmpty()) // already started, one-off
        val weekly = d.copy(weekly = true)
        assertEquals(1, ReminderPlanner.due(start + WalkDatePlanner.WEEK_MS - 10 * 60_000L, utc, null, listOf(weekly), emptySet(), QuietHours()).size)
    }

    @Test fun celebrationFiresOncePerDay() {
        assertTrue(GoalCelebration.shouldCelebrate(5900, 6000, 6000, null, 10))
        assertFalse(GoalCelebration.shouldCelebrate(6000, 6100, 6000, null, 10)) // already past it
        assertFalse(GoalCelebration.shouldCelebrate(5900, 6000, 6000, 10, 10)) // already celebrated today
        assertTrue(GoalCelebration.shouldCelebrate(5900, 6000, 6000, 9, 10))
        assertTrue(GoalCelebration.shouldCelebrate(0, 7000, 6000, null, 10)) // first look after the goal was reached in the background
        assertFalse(GoalCelebration.shouldCelebrate(0, 100, 0, null, 10))
    }

    @Test fun confettiIsDeterministicAndFades() {
        val a = Confetti.spawn(60, 42); val b = Confetti.spawn(60, 42)
        assertEquals(a, b); assertEquals(60, a.size); assertTrue(a != Confetti.spawn(60, 43))
        assertTrue(a.all { it.colorIndex in 0 until 5 && it.size > 0f && it.delay in 0f..0.25f })
        val p = a[0]
        assertNull(Confetti.position(p, -1f)); assertNull(Confetti.position(p, Confetti.DURATION_S + 1f))
        val early = Confetti.position(p, p.delay + 0.1f)!!
        assertEquals(1f, early.alpha, 0f); assertTrue(early.x in 0.2f..0.8f); assertTrue(early.y < 0.5f)
        val late = Confetti.position(p, p.delay + Confetti.DURATION_S - 0.05f)!!
        assertTrue(late.alpha < 0.2f); assertTrue(late.y > early.y)
    }

    @Test fun demoDataIsBelievableAndStable() {
        val today = 20_000L
        val a = DemoData.build(today); val b = DemoData.build(today)
        assertEquals(a, b)
        assertEquals(DemoData.HISTORY_DAYS + 1, a.days.size)
        assertEquals(DemoData.TODAY_STEPS, a.days.last().verifiedSteps)
        assertTrue(a.days.all { it.verifiedSteps in 1500..14_000 })
        assertTrue(a.coupleDays.size in 10..40); assertTrue(a.coupleDistanceM > 20_000)
        assertTrue(a.walks.all { it.epochDay < today })
        val hist = HourlyHistogram.build(a.hours, today)
        assertTrue(hist.bestHour == 7 || hist.bestHour == 18)
        val insight = MoodInsights.compute(a.moods, a.days.associate { it.epochDay to it.verifiedSteps })
        assertTrue(insight.enoughData)
        assertNotNull(a.buddyName); assertTrue(a.buddyStepsToday < a.buddyGoal)
    }

    @Test fun recapSlides() {
        val today = 20_000L
        val demo = DemoData.build(today)
        val report = WeeklyReport.build(demo.days, demo.walks, today)
        val hourly = HourlyHistogram.build(demo.hours, today)
        val flame = StreakFlame.info(StreakResult(9, 9, 1), true, false, 12)
        val slides = WeeklyRecapBuilder.build(report, demo.days, today, 3, 12_300.0, hourly, flame, UnitSystem.Metric, false)
        assertEquals(listOf(SlideKind.Total, SlideKind.BestDay, SlideKind.Together, SlideKind.BestHour, SlideKind.Flame, SlideKind.Closing), slides.map { it.kind })
        assertEquals(Hero.thousands(report.totalSteps), slides[0].big)
        assertEquals("12.3 km", slides[2].big)
        assertTrue(slides.all { it.title.isNotBlank() && it.big.isNotBlank() })
        val empty = WeeklyRecapBuilder.build(WeeklyReport.build(emptyList(), emptyList(), today), emptyList(), today, 0, 0.0, null, null, UnitSystem.Metric, false)
        assertEquals(1, empty.size)
        val solo = WeeklyRecapBuilder.build(report, demo.days, today, 0, 0.0, null, null, UnitSystem.Imperial, false)
        assertEquals("A solo week", solo[2].title)
        assertEquals("Monday", WeeklyRecapBuilder.dayName(4))
    }

    @Test fun ourWeekCard() {
        val today = 20_000L
        val demo = DemoData.build(today)
        val report = WeeklyReport.build(demo.days, demo.walks, today)
        val card = OurWeek.build(report, demo.walks.filter { it.buddyCount >= 1 }, demo.coupleDays, today, "Sam", UnitSystem.Metric)
        assertTrue(card.lines[0].startsWith("Walked with Sam on "))
        assertTrue(card.headlineValue.endsWith("km")); assertEquals("walked together this week", card.headlineLabel)
        val none = OurWeek.build(report, emptyList(), emptySet(), today, null, UnitSystem.Imperial)
        assertEquals("Walked with your buddy on 0 of 7 days", none.lines[0]); assertEquals("0.0 mi", none.headlineValue)
    }

    @Test fun moodCsvExportGuardsFormulas() {
        val csv = CsvExport.moods(listOf(MoodEntry(1, 5, 20, 4, "=SUM(A1)", null), MoodEntry(2, 4, 10, 3, "nice, calm", 9)))
        val lines = csv.trim().split("\r\n")
        assertEquals("epoch_day,at_ms,mood,note", lines[0])
        assertEquals("4,10,3,\"nice, calm\"", lines[1])
        assertEquals("5,20,4,'=SUM(A1)", lines[2])
    }
}
