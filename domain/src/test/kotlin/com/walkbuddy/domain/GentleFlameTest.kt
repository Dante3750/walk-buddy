package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GentleFlameTest {
    @Test fun gentleGoalIsLowerRoundedAndBounded() {
        assertEquals(3600, GentleDay.goal(6000))
        assertEquals(1800, GentleDay.goal(3000))
        assertEquals(1000, GentleDay.goal(1000)) // never above the normal goal
        assertEquals(6000, GentleDay.effectiveGoal(6000, false))
        assertEquals(3600, GentleDay.effectiveGoal(6000, true))
    }

    @Test fun gentleDaysStayOutOfGoalPlanning() {
        val h = listOf(DayRecord(1, verifiedSteps = 5000), DayRecord(2, verifiedSteps = 900, gentle = true))
        assertEquals(listOf(1L), GentleHistory.forGoalPlanning(h).map { it.epochDay })
    }

    @Test fun flameLevels() {
        assertEquals(FlameLevel.Spark, StreakFlame.levelFor(0))
        assertEquals(FlameLevel.Ember, StreakFlame.levelFor(2))
        assertEquals(FlameLevel.Flame, StreakFlame.levelFor(3))
        assertEquals(FlameLevel.Blaze, StreakFlame.levelFor(7))
        assertEquals(FlameLevel.Inferno, StreakFlame.levelFor(30))
    }

    @Test fun flameCopyAndRisk() {
        val none = StreakFlame.info(StreakResult(0, 0, 1), false, false, 10)
        assertEquals("Light your first flame", none.title); assertFalse(none.atRisk)
        val ev = StreakFlame.info(StreakResult(5, 5, 2), false, false, 19)
        assertTrue(ev.atRisk); assertTrue(ev.subtitle.contains("rest token"))
        val noTok = StreakFlame.info(StreakResult(5, 5, 0), false, false, 19)
        assertTrue(noTok.atRisk); assertFalse(noTok.subtitle.contains("token"))
        val rest = StreakFlame.info(StreakResult(5, 5, 1), false, true, 21)
        assertFalse(rest.atRisk); assertTrue(rest.subtitle.contains("Rest day"))
        val met = StreakFlame.info(StreakResult(1, 1, 2), true, false, 21)
        assertEquals("1 day streak", met.title); assertEquals("2 rest tokens ready", met.subtitle)
        assertFalse(StreakFlame.info(StreakResult(5, 5, 1), false, false, 10).atRisk) // morning is never "at risk"
    }
}
