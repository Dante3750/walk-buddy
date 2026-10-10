package com.walkbuddy.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeReminderTest {
    private val today = LocalDate.of(2026, 10, 14).toEpochDay() // a Wednesday

    // ---------- challenges ----------

    @Test fun weekRunsMondayToSunday() {
        val (s, e) = Challenges.window(ChallengePeriod.Week, today)
        assertEquals(LocalDate.of(2026, 10, 12).toEpochDay(), s)
        assertEquals(LocalDate.of(2026, 10, 18).toEpochDay(), e)
    }

    @Test fun monthWindowHandlesLeapFebruary() {
        val d = LocalDate.of(2028, 2, 10).toEpochDay()
        val (s, e) = Challenges.window(ChallengePeriod.Month, d)
        assertEquals(LocalDate.of(2028, 2, 1).toEpochDay(), s)
        assertEquals(LocalDate.of(2028, 2, 29).toEpochDay(), e)
    }

    private fun walk(dayOffset: Long, km: Double, steps: Long = 3000, members: Int = 2) = ChallengeWalk(today + dayOffset, km * 1000, steps, members)

    @Test fun distanceChallengeSumsOnlyWalksInsideTheWindowAndTogether() {
        val inst = Challenges.newInstance("km10_week", today)!!
        val walks = listOf(walk(-1, 3.0), walk(0, 4.0), walk(1, 2.5), walk(-8, 9.0), walk(0, 5.0, members = 1))
        val p = Challenges.progress(inst, walks, today)!!
        assertEquals(9.5, p.current, 1e-6)
        assertEquals(ChallengeState.Active, p.state)
        assertEquals(0.95f, p.fraction, 1e-4f)
        assertEquals(5, p.daysLeft)
        assertFalse(p.justCompleted)
    }

    @Test fun completionIsDetectedOnceAndNotCelebratedTwice() {
        val inst = Challenges.newInstance("walks3_week", today)!!
        val walks = listOf(walk(-2, 1.0), walk(-1, 1.0), walk(0, 1.0))
        val p = Challenges.progress(inst, walks, today)!!
        assertEquals(ChallengeState.Completed, p.state)
        assertTrue(p.justCompleted)
        assertEquals(1f, p.fraction, 0f)
        val done = Challenges.progress(inst.copy(completedMs = 5L), walks, today)!!
        assertFalse(done.justCompleted)
    }

    @Test fun groupStepsChallengeUsesEveryonesStepsAsOneNumber() {
        val inst = Challenges.newInstance("steps30k_week", today)!!
        val p = Challenges.progress(inst, listOf(walk(0, 1.0, steps = 12_000), walk(-1, 1.0, steps = 20_000, members = 8)), today)!!
        assertEquals(32_000.0, p.current, 0.0)
        assertEquals(ChallengeState.Completed, p.state)
    }

    @Test fun missedAfterThePeriodEnds() {
        val inst = Challenges.newInstance("km40_month", today)!!
        val later = inst.endDay + 1
        assertEquals(ChallengeState.Missed, Challenges.progress(inst, listOf(walk(0, 1.0)), later)!!.state)
    }

    @Test fun unknownTemplateGivesNothing() {
        assertNull(Challenges.progress(ChallengeInstance(1, "nope", 0, 1), emptyList(), 0))
        assertNull(Challenges.newInstance("nope", today))
        assertFalse(Challenges.canStart("nope", emptyList(), today))
    }

    @Test fun cannotStartTheSameChallengeTwiceOrMoreThanThree() {
        val a = Challenges.newInstance("km10_week", today)!!.copy(id = 1)
        assertFalse(Challenges.canStart("km10_week", listOf(a), today))
        assertTrue(Challenges.canStart("walks7_month", listOf(a), today))
        val three = listOf(a, Challenges.newInstance("walks3_week", today)!!.copy(id = 2), Challenges.newInstance("km40_month", today)!!.copy(id = 3))
        assertFalse(Challenges.canStart("walks7_month", three, today))
        // finished ones do not count against the limit
        assertTrue(Challenges.canStart("walks7_month", three.map { it.copy(completedMs = 1) }, today))
    }

    @Test fun nextWeekAllowsTheSameChallengeAgain() {
        val a = Challenges.newInstance("km10_week", today)!!
        assertTrue(Challenges.canStart("km10_week", listOf(a), today + 7))
    }

    @Test fun orderedPutsActiveFirst() {
        val active = Challenges.progress(Challenges.newInstance("km10_week", today)!!.copy(id = 1), emptyList(), today)!!
        val missed = Challenges.progress(ChallengeInstance(2, "km10_week", today - 20, today - 14), emptyList(), today)!!
        assertEquals(listOf(1L, 2L), Challenges.ordered(listOf(missed, active)).map { it.instance.id })
    }

    @Test fun everyTemplateHasAPositiveTargetAndUniqueId() {
        assertEquals(Challenges.templates.size, Challenges.templates.map { it.id }.toSet().size)
        assertTrue(Challenges.templates.all { it.target > 0 })
    }

    // ---------- reminders ----------

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int, z: ZoneId = zone) = java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, z).toInstant().toEpochMilli()
    private val evening = ReminderSlot(1, setOf(1, 2, 3, 4, 5), 18 * 60 + 30)
    private val weekend = ReminderSlot(2, setOf(6, 7), 7 * 60)

    @Test fun nextFireIsTodayWhenStillAhead() {
        val n = WalkReminders.next(listOf(evening), ms(2026, 10, 14, 10, 0), zone)!!
        assertEquals(ms(2026, 10, 14, 18, 30), n.atMs)
        assertEquals(1, n.slotId)
    }

    @Test fun nextFireRollsToTheNextMatchingDay() {
        val fri = WalkReminders.next(listOf(evening), ms(2026, 10, 16, 19, 0), zone)!!
        assertEquals(ms(2026, 10, 19, 18, 30), fri.atMs) // Friday evening has passed, next is Monday
        val both = WalkReminders.next(listOf(evening, weekend), ms(2026, 10, 16, 19, 0), zone)!!
        assertEquals(ms(2026, 10, 17, 7, 0), both.atMs)
        assertEquals(2, both.slotId)
    }

    @Test fun disabledAndEmptyGiveNothing() {
        assertNull(WalkReminders.next(emptyList(), 0, zone))
        assertNull(WalkReminders.next(listOf(evening.copy(enabled = false)), 0, zone))
        assertNull(WalkReminders.next(listOf(evening.copy(weekdays = emptySet())), 0, zone))
    }

    @Test fun cantTodaySkipsTheWholeDay() {
        val d = LocalDate.of(2026, 10, 14).toEpochDay()
        val n = WalkReminders.next(listOf(evening), ms(2026, 10, 14, 10, 0), zone, skipDays = setOf(d))!!
        assertEquals(ms(2026, 10, 15, 18, 30), n.atMs)
    }

    @Test fun skipOnlyThisOccurrence() {
        val d = LocalDate.of(2026, 10, 14).toEpochDay()
        val n = WalkReminders.next(listOf(evening, weekend), ms(2026, 10, 14, 10, 0), zone, skipOnce = setOf(1 to d))!!
        assertEquals(ms(2026, 10, 15, 18, 30), n.atMs)
    }

    @Test fun springForwardGapDoesNotCrash() {
        val ny = ZoneId.of("America/New_York")
        val slot = ReminderSlot(1, WalkReminders.everyDay, 2 * 60 + 30) // 02:30 does not exist on 2026-03-08
        val n = WalkReminders.next(listOf(slot), ms(2026, 3, 8, 0, 10, ny), ny)!!
        assertTrue(n.atMs > ms(2026, 3, 8, 0, 10, ny))
        assertTrue(n.atMs < ms(2026, 3, 8, 6, 0, ny))
    }

    @Test fun snoozeIsHalfAnHour() {
        assertEquals(1_000L + 30 * 60_000L, WalkReminders.snoozeAt(1_000L))
    }

    @Test fun staleAndQuietAndSkippedRemindersAreNotShown() {
        val due = ms(2026, 10, 14, 18, 30)
        val quiet = QuietHours(false)
        assertTrue(WalkReminders.shouldShow(due, due + 5 * 60_000, zone, emptySet(), quiet))
        assertFalse(WalkReminders.shouldShow(due, due + 4 * 3_600_000L, zone, emptySet(), quiet)) // Doze delayed it too long
        assertFalse(WalkReminders.shouldShow(due, due - 10 * 60_000L, zone, emptySet(), quiet)) // too early
        assertFalse(WalkReminders.shouldShow(due, due + 1000, zone, setOf(LocalDate.of(2026, 10, 14).toEpochDay()), quiet))
        assertFalse(WalkReminders.shouldShow(due, due + 1000, zone, emptySet(), QuietHours(true, 18, 20)))
    }

    @Test fun slotsRoundTripThroughText() {
        val slots = listOf(evening, weekend.copy(enabled = false))
        assertEquals(slots, WalkReminders.decode(WalkReminders.encode(slots)))
        assertEquals(emptyList<ReminderSlot>(), WalkReminders.decode("junk;1:9:5000:1;::;"))
        assertEquals(3, WalkReminders.nextId(slots + ReminderSlot(2, setOf(1), 0)))
        assertEquals(6, (1..9).map { ReminderSlot(it, setOf(1), 0) }.let { WalkReminders.decode(WalkReminders.encode(it)) }.size + 1)
    }

    @Test fun skipDaysArePrunedAndRoundTrip() {
        val days = setOf(10L, 20L, 30L)
        assertEquals(setOf(20L, 30L), WalkReminders.pruneSkipDays(days, 15))
        assertEquals(days, WalkReminders.decodeDays(WalkReminders.encodeDays(days)))
    }

    @Test fun partnerToneNeedsAPartnerName() {
        assertEquals(ReminderTone.Partner, ReminderTexts.tone(true, "Meera"))
        assertEquals(ReminderTone.Solo, ReminderTexts.tone(true, " "))
        assertEquals(ReminderTone.Solo, ReminderTexts.tone(false, "Meera"))
        assertNotNull(WalkReminders.everyDay)
    }
}
