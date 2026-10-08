package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEngineTest {
    private fun engine(cfg: GroupWalkConfig = GroupWalkConfig()) = GroupEngine("me", "Me", cfg, startMs = 0)

    /** Me walking north at 1.4 m/s; each other walker is given as (id, metres ahead of me, steps). Positions are what the server relays. */
    private fun step(e: GroupEngine, sec: Int, others: List<Triple<String, Double, Int>>): GroupState {
        val now = sec * 1000L
        val me = T.north(T.origin, 1.4 * sec)
        e.onSelfFix(Fix(now, me.lat, me.lon, 5.0, 1.4))
        e.onSelfSteps(now, 2L * sec)
        for ((id, ahead, steps) in others) {
            val p = T.north(me, ahead)
            e.onUpdate(now, id, GroupUpdate(now, p.lat, p.lon, 5.0, 1.4, steps, 110.0, 1.4 * sec))
        }
        return e.tick(now)
    }

    @Test fun aFiftyPersonWalkRunsAndSummarises() {
        val e = engine(GroupWalkConfig(goalSteps = 100_000))
        val others = (1 until 50).map { Triple("p$it", (it % 10) * 4.0 - 20.0, 0) }
        var st: GroupState? = null
        for (t in 1..120) st = step(e, t, others.map { it.copy(third = t * 2) })
        val s = st!!
        assertEquals(50, s.memberCount)
        assertEquals(49, s.members.size)
        assertEquals(true, s.togetherNow)
        assertTrue(s.together.scorePct >= 95)
        assertEquals(0, s.stragglerCount)
        assertNotNull(s.leaderId); assertNotNull(s.sweeperId)
        assertEquals(49 * 240L, s.goal.totalSteps - s.myVerifiedSteps) // 49 members at 240 steps, plus my own verified steps
        assertTrue(s.members.all { it.distanceM != null && it.distanceM!! < 60 })
        assertEquals(50, s.trails.size)
        val sum = e.finish(121_000)
        assertEquals(49, sum.buddyCount)
        assertTrue(sum.togetherPct!! >= 95)
    }

    @Test fun lateJoinerAppearsMidWalkAndIsPartOfTheScore() {
        val e = engine()
        for (t in 1..60) step(e, t, listOf(Triple("a", 10.0, t * 2), Triple("b", 15.0, t * 2)))
        // the server's roster snapshot names a newcomer before they have sent a position
        e.setRoster(listOf(RosterEntry("a", "Asha", true), RosterEntry("b", "Ben", false), RosterEntry("c", "Chi", false), RosterEntry("me", "Me", false)))
        var s = e.tick(61_000)
        val chi = s.members.first { it.id == "c" }
        assertEquals(BuddyStatus.Waiting, chi.status)
        assertEquals("Chi", chi.name)
        assertNull(chi.distanceM)
        assertEquals(4, s.memberCount)
        // then Chi's first update arrives
        for (t in 62..80) s = step(e, t, listOf(Triple("a", 10.0, t * 2), Triple("b", 15.0, t * 2), Triple("c", 5.0, 10 + t)))
        val c = s.members.first { it.id == "c" }
        assertEquals(BuddyStatus.Moving, c.status)
        assertNotNull(c.distanceM)
        assertEquals("Ben", s.members.first { it.id == "b" }.name)
    }

    @Test fun leavingMembersDisappearButTheirStepsStayInTheGroupTotal() {
        val e = engine(GroupWalkConfig(goalSteps = 10_000))
        var s = step(e, 1, listOf(Triple("a", 10.0, 800), Triple("b", 10.0, 600)))
        e.onMemberLeft("b")
        s = step(e, 2, listOf(Triple("a", 10.0, 900)))
        assertEquals(listOf("a"), s.members.map { it.id })
        assertEquals(2, s.memberCount)
        assertTrue(s.goal.totalSteps >= 900 + 600)
        assertNull(s.trails["b"])
        e.onMemberJoined("b", "Ben again")
        assertEquals("Ben again", e.tick(3000).members.first { it.id == "b" }.name)
    }

    @Test fun rosterSnapshotMarksMissingPeopleAsGone() {
        val e = engine()
        step(e, 1, listOf(Triple("a", 5.0, 1), Triple("b", 5.0, 1)))
        e.setRoster(listOf(RosterEntry("a", "A", true), RosterEntry("me", "Me", false)))
        assertEquals(listOf("a"), e.tick(2000).members.map { it.id })
    }

    @Test fun aStragglerIsFlaggedAndGetsShownAsFarFromTheGroup() {
        val e = engine()
        val pack = (1..8).map { Triple("p$it", it * 3.0, 100) }
        var s: GroupState? = null
        for (t in 1..40) s = step(e, t, pack + Triple("late", -400.0, 50))
        assertEquals(1, s!!.stragglerCount)
        assertTrue(s.members.first { it.id == "late" }.straggler)
        assertFalse(s.members.first { it.id == "p3" }.straggler)
        assertTrue(s.members.first { it.id == "late" }.relation.contains("behind"))
    }

    @Test fun stoppedHeardLongAgoBecomesConnectionLost() {
        val e = engine()
        step(e, 1, listOf(Triple("a", 5.0, 1)))
        val s = e.tick(1000L + LinkHealth.LOST_AFTER_MS + 1000)
        assertEquals(BuddyStatus.ConnectionLost, s.members.first().status)
        assertNull(s.members.first().pos)
    }

    @Test fun myOwnPositionIsExactOnMyScreenButBlurredOnTheWire() {
        val e = engine(GroupWalkConfig(precision = LocationPrecision.Approx500))
        val me = T.north(T.origin, 3.0)
        e.onSelfFix(Fix(1000, me.lat, me.lon, 5.0, 1.4))
        e.onSelfSteps(1000, 10)
        val u = e.selfUpdate(2000)
        val shared = u.pos!!
        assertTrue(Geo.haversine(me, shared) <= LocationPrecision.Approx500.maxErrorM + 1)
        assertTrue(Geo.haversine(me, shared) > 0.5)
        assertNull(u.accuracyM)
        assertEquals(me, e.myPosition)
        val exact = engine().also { it.onSelfFix(Fix(1000, me.lat, me.lon, 5.0, 1.4)) }.selfUpdate(2000)
        assertEquals(me, exact.pos)
        assertEquals(5.0, exact.accuracyM!!, 0.0)
    }

    @Test fun beforeTheFirstFixOnlyStepsAreShared() {
        val e = engine()
        e.onSelfSteps(1000, 5); e.onSelfSteps(2000, 25)
        e.tick(2000)
        val u = e.selfUpdate(3000)
        assertNull(u.pos)
        assertTrue(u.steps >= 0)
    }

    @Test fun someoneWithoutLocationStillCountsTowardsTheGoal() {
        val e = engine(GroupWalkConfig(goalSteps = 1000))
        e.onUpdate(1000, "nolocation", GroupUpdate(1000, null, null, steps = 700))
        val s = e.tick(1000)
        assertEquals(700, s.goal.totalSteps)
        assertNull(s.members.first().pos)
        assertEquals(BuddyStatus.Moving, s.members.first().status)
    }

    @Test fun groupSizeIsCapped() {
        val e = engine(GroupWalkConfig(maxMembers = 5))
        for (i in 0 until 20) e.onUpdate(1000, "p$i", GroupUpdate(1000, 12.0, 77.0, steps = 1))
        assertEquals(6, e.tick(1000).memberCount)
    }

    @Test fun lowAccuracyUpdatesDoNotMoveSomeoneOnTheMap() {
        val e = engine()
        e.onUpdate(1000, "a", GroupUpdate(1000, 12.0, 77.0, accuracyM = 5.0, steps = 1))
        e.onUpdate(2000, "a", GroupUpdate(2000, 13.0, 78.0, accuracyM = 300.0, steps = 2))
        assertEquals(LatLon(12.0, 77.0), e.tick(2000).members.first().pos)
    }

    @Test fun quietModeMutesGroupNudges() {
        val loud = engine(); val quiet = engine(GroupWalkConfig(walk = WalkConfig(nudge = NudgeConfig(farM = 150.0, nearM = 90.0, quiet = true))))
        val pack = (1..6).map { Triple("p$it", 300.0 + it * 3, 100) }
        var loudNudges = 0; var quietNudges = 0
        for (t in 1..120) {
            if (step(loud, t, pack).nudge != null) loudNudges++
            if (step(quiet, t, pack).nudge != null) quietNudges++
        }
        assertEquals(1, loudNudges); assertEquals(0, quietNudges)
        assertEquals(1, loud.finish(121_000).nudgesShown)
    }

    @Test fun configCanChangeMidWalk() {
        val e = engine()
        step(e, 1, listOf(Triple("a", 5.0, 1)))
        e.updateConfig(e.config.copy(precision = LocationPrecision.Approx100, goalSteps = 5000))
        assertEquals(5000, e.tick(2000).goal.goal)
        assertEquals(LocationPrecision.Approx100, e.config.precision)
    }
}
