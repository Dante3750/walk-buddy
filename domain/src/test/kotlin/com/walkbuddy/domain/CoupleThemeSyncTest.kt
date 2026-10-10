package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoupleThemeSyncTest {
    private val today = java.time.LocalDate.of(2026, 10, 14).toEpochDay() // Wednesday

    // ---------- couple streak ----------

    @Test fun noWalksYetIsGentle() {
        val i = CoupleStreak.compute(emptySet(), today)
        assertEquals(0, i.current); assertEquals(CoupleStreakMessage.NoWalksYet, i.message); assertEquals(1, i.tokens)
    }

    @Test fun consecutiveDaysBuildTheStreak() {
        val days = (today - 3..today).toSet()
        val i = CoupleStreak.compute(days, today)
        assertEquals(4, i.current); assertEquals(4, i.longest); assertTrue(i.walkedToday)
        assertEquals(CoupleStreakMessage.WalkedToday, i.message)
    }

    @Test fun todayNotYetWalkedNeverBreaksIt() {
        val i = CoupleStreak.compute((today - 4..today - 1).toSet(), today, hourOfDay = 9)
        assertEquals(4, i.current)
        assertEquals(CoupleStreakMessage.KeepGoing, i.message)
        assertEquals(CoupleStreakMessage.GentleEvening, CoupleStreak.compute((today - 4..today - 1).toSet(), today, hourOfDay = 20).message)
    }

    @Test fun aMissedDayIsCoveredByARestToken() {
        val days = (today - 5..today - 3).toSet() + (today - 1)
        val i = CoupleStreak.compute(days, today, hourOfDay = 9)
        assertEquals(4, i.current)
        assertEquals(0, i.tokens)
        assertEquals(today - 2, i.tokenUsedOn)
        assertEquals(CoupleStreakMessage.KeepGoing, i.message) // missed day was two days ago; no scolding today
    }

    @Test fun missedYesterdayWithATokenSaysSoKindly() {
        val i = CoupleStreak.compute((today - 4..today - 2).toSet(), today, hourOfDay = 9)
        assertEquals(CoupleStreakMessage.TokenUsedYesterday, i.message)
        assertEquals(3, i.current)
    }

    @Test fun withoutATokenTheCountStartsAgain() {
        // tokens: starts at 1; two separate misses in a row of streak use it up then break.
        val days = setOf(today - 9, today - 8, today - 6, today - 3, today - 2)
        val i = CoupleStreak.compute(days, today, hourOfDay = 9)
        assertTrue(i.brokeOn != null)
        val yesterdayBreak = CoupleStreak.compute(setOf(today - 6, today - 5, today - 3, today - 2), today, hourOfDay = 9)
        assertTrue(yesterdayBreak.current <= 2)
        val msg = CoupleStreak.compute(setOf(today - 5, today - 4, today - 3, today - 2), today, hourOfDay = 9)
        assertEquals(CoupleStreakMessage.TokenUsedYesterday, msg.message)
    }

    @Test fun startedAgainMessageAppearsTheDayAfterABreak() {
        // streak of 2, then two misses with only one token: the break lands on yesterday.
        val days = setOf(today - 4, today - 3)
        val i = CoupleStreak.compute(days, today, hourOfDay = 9)
        assertEquals(today - 1, i.brokeOn)
        assertEquals(CoupleStreakMessage.StartedAgain, i.message)
        assertEquals(0, i.current)
        assertEquals(2, i.longest)
    }

    @Test fun restWeekdaysNeverBreakAndAreNamedRestToday() {
        val wednesday = AdaptiveGoal.weekdayOf(today)
        val i = CoupleStreak.compute(setOf(today - 2, today - 1), today, restWeekdays = setOf(wednesday), hourOfDay = 9)
        assertEquals(CoupleStreakMessage.RestDay, i.message)
        assertEquals(2, i.current)
        // a rest weekday in the middle neither extends nor breaks
        val tuesday = AdaptiveGoal.weekdayOf(today - 1)
        val j = CoupleStreak.compute(setOf(today - 2, today), today, restWeekdays = setOf(tuesday))
        assertEquals(2, j.current)
    }

    @Test fun aTokenIsEarnedEverySevenSharedDays() {
        val days = (today - 6..today).toSet()
        assertEquals(2, CoupleStreak.compute(days, today).tokens)
        val many = (today - 29..today).toSet()
        assertEquals(Streaks.MAX_TOKENS, CoupleStreak.compute(many, today).tokens)
    }

    // ---------- themes and avatars ----------

    @Test fun themeIdsRoundTripAndDefaultToMetro() {
        for (t in TrackTheme.values()) assertEquals(t, TrackTheme.fromId(t.id))
        assertEquals(TrackTheme.Metro, TrackTheme.fromId(null))
        assertEquals(TrackTheme.Metro, TrackTheme.fromId("lava"))
    }

    @Test fun seasonalThemeFollowsTheMonth() {
        assertEquals(TrackScene.Spring, Seasons.sceneForMonth(4))
        assertEquals(TrackScene.Summer, Seasons.sceneForMonth(7))
        assertEquals(TrackScene.Autumn, Seasons.sceneForMonth(10))
        assertEquals(TrackScene.Winter, Seasons.sceneForMonth(12))
        assertEquals(TrackScene.Winter, Seasons.sceneForMonth(1))
        assertEquals(TrackScene.Winter, Seasons.sceneForMonth(2))
        assertEquals(TrackScene.Train, Seasons.scene(TrackTheme.Train, 7))
        assertEquals(TrackScene.Autumn, Seasons.scene(TrackTheme.Seasonal, 10))
        for (m in 1..12) assertNotNull(Seasons.sceneForMonth(m))
    }

    @Test fun accessoriesTravelInTheHighBitsAndOldAppsStillSeeThePlainWalker() {
        for (st in AvatarStyle.values()) for (c in 0 until AvatarPalette.SIZE) for (acc in AvatarAccessory.values()) {
            val a = Avatar(st, c, acc)
            val code = AvatarCode.encode(a)
            assertTrue(code in 0..AvatarCode.MAX)
            assertEquals(a, AvatarCode.decode(code))
            // what an alpha 1.8 app would read: bits 0-5 only
            assertEquals(Avatar(st, c), AvatarCode.decode(code and 63))
        }
        assertEquals(AvatarAccessory.Scarf, AvatarCode.decode(255)!!.accessory)
        assertEquals(AvatarAccessory.None, AvatarAccessory.fromCode(9))
    }

    // ---------- health sync ----------

    @Test fun stepsAreNeverAddedTogether() {
        val r = StepMerge.reconcile(6000, 6050)
        assertEquals(6000, r.steps); assertEquals(StepOrigin.Own, r.source)
        val same = StepMerge.reconcile(6000, 6000)
        assertEquals(6000, same.steps)
    }

    @Test fun healthConnectWinsOnlyWhenAnotherDeviceClearlyAddedSteps() {
        val r = StepMerge.reconcile(4000, 7500)
        assertEquals(7500, r.steps); assertEquals(StepOrigin.HealthConnect, r.source); assertEquals(3500, r.extraFromHealthConnect)
        assertEquals(StepOrigin.Own, StepMerge.reconcile(9000, 3000).source)
    }

    @Test fun badHealthConnectDataIsIgnored() {
        assertEquals(StepOrigin.Own, StepMerge.reconcile(500, null).source)
        assertEquals(StepOrigin.Own, StepMerge.reconcile(500, -5).source)
        assertEquals(StepOrigin.Own, StepMerge.reconcile(500, 900_000).source)
        assertEquals(0, StepMerge.reconcile(-20, null).steps)
    }

    @Test fun walksAreWrittenOnceAndNeverOverlap() {
        val written = listOf(WrittenWalk(1_000_000, 2_000_000))
        assertFalse(WalkExport.shouldWrite(1_000_000, 2_000_000, written))
        assertFalse(WalkExport.shouldWrite(1_500_000, 2_500_000, written))
        assertTrue(WalkExport.shouldWrite(2_000_000, 2_600_000, written))
        assertFalse(WalkExport.shouldWrite(5_000_000, 5_060_000, written)) // under two minutes
        assertEquals("walkbuddy-walk-42", WalkExport.clientId(42))
    }

    @Test fun writtenWalkMemoryIsBoundedAndRoundTrips() {
        var l = emptyList<WrittenWalk>()
        for (i in 1..100) l = WalkExport.remember(l, WrittenWalk(i * 10L, i * 10L + 5))
        assertEquals(40, l.size)
        assertEquals(l, WalkExport.decode(WalkExport.encode(l)))
        assertEquals(emptyList<WrittenWalk>(), WalkExport.decode("x-y,5-3,"))
    }

    // ---------- notes and photos ----------

    @Test fun noteIsCleanedAndCapped() {
        assertNull(WalkNotes.clean(null)); assertNull(WalkNotes.clean("  \n ")); assertNull(WalkNotes.clean("\u0000\u0001"))
        assertEquals("Lovely evening\n\nthe lake", WalkNotes.clean("  Lovely evening\n\n\n\n\nthe lake \u0007 "))
        assertEquals(WalkNotes.MAX_NOTE, WalkNotes.clean("x".repeat(2000))!!.length)
    }

    @Test fun photoIsScaledDownToTheLimit() {
        assertEquals(1, WalkNotes.sampleSize(1200, 800))
        assertEquals(2, WalkNotes.sampleSize(4000, 3000))
        assertEquals(4, WalkNotes.sampleSize(8000, 6000))
        assertEquals(1600 to 1200, WalkNotes.targetSize(4000, 3000))
        assertEquals(900 to 1600, WalkNotes.targetSize(1800, 3200))
        assertEquals(800 to 600, WalkNotes.targetSize(800, 600))
        assertEquals(1 to 1, WalkNotes.targetSize(0, 0))
    }

    @Test fun jpegQualityStepsDownUntilItFits() {
        assertNull(WalkNotes.nextQuality(85, 1_000_000))
        assertEquals(73, WalkNotes.nextQuality(85, 3_000_000))
        assertNull(WalkNotes.nextQuality(45, 9_000_000))
        assertEquals("walk-7.jpg", WalkNotes.photoFileName(7))
    }

    // ---------- languages ----------

    @Test fun languageTagsNormalise() {
        assertEquals("hi", AppLanguages.normalize("hi-IN"))
        assertEquals("en", AppLanguages.normalize(" EN "))
        assertEquals("", AppLanguages.normalize("fr"))
        assertEquals("", AppLanguages.normalize(null))
        assertTrue(AppLanguages.isSupported("") && AppLanguages.isSupported("hi"))
    }
}
