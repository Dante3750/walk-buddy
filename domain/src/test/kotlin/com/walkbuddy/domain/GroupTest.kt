package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationBlurTest {
    private val p = LatLon(12.97163, 77.59461)

    @Test fun exactIsUntouched() = assertEquals(p, LocationBlur.apply(p, LocationPrecision.Exact))

    @Test fun blurStaysWithinTheStatedError() {
        for (prec in listOf(LocationPrecision.Approx100, LocationPrecision.Approx500)) {
            for (i in 0 until 200) {
                val q = LatLon(-60.0 + i * 0.6, -170.0 + i * 1.7)
                val b = LocationBlur.apply(q, prec)
                assertTrue("${prec} at $q moved ${Geo.haversine(q, b)}", Geo.haversine(q, b) <= prec.maxErrorM + 1.0)
                assertTrue(b.isValid)
            }
        }
    }

    @Test fun blurIsStableAndIdempotent() {
        val b = LocationBlur.apply(p, LocationPrecision.Approx100)
        assertEquals(b, LocationBlur.apply(b, LocationPrecision.Approx100))
        // standing still with GPS wobble of a few metres shows one fixed point (nothing to average away)
        val wobble = listOf(T.north(p, 2.0), T.east(p, -1.5), T.south(p, 1.0))
        val shared = wobble.map { LocationBlur.apply(it, LocationPrecision.Approx500) }.toSet()
        assertTrue(shared.size <= 2)
    }

    @Test fun coarserBlurMovesMore() {
        val far = LatLon(12.9801, 77.6012)
        val d100 = Geo.haversine(far, LocationBlur.apply(far, LocationPrecision.Approx100))
        assertTrue(d100 <= LocationPrecision.Approx100.maxErrorM + 1)
    }

    @Test fun polesAndAntimeridianStayValid() {
        for (q in listOf(LatLon(89.99, 179.99), LatLon(-89.99, -179.99), LatLon(0.0, 180.0), LatLon(90.0, 0.0))) {
            assertTrue(LocationBlur.apply(q, LocationPrecision.Approx500).isValid)
        }
    }

    @Test fun fromNameFallsBackToExact() {
        assertEquals(LocationPrecision.Approx100, LocationPrecision.fromName("Approx100"))
        assertEquals(LocationPrecision.Exact, LocationPrecision.fromName("nope"))
    }
}

class GroupGeometryTest {
    private fun line(vararg offsetsNorthM: Double) = offsetsNorthM.map { T.north(T.origin, it) }

    @Test fun centroidOfASquareIsItsCentre() {
        val pts = listOf(T.origin, T.north(T.origin, 100.0), T.east(T.origin, 100.0), T.east(T.north(T.origin, 100.0), 100.0))
        val c = GroupGeo.centroid(pts)!!
        assertEquals(70.7, Geo.haversine(T.origin, c), 1.0) // half the diagonal of a 100 m square
        assertTrue(Geo.haversine(c, T.east(T.north(T.origin, 50.0), 50.0)) < 1.0)
    }

    @Test fun centroidWorksAcrossTheAntimeridian() {
        val c = GroupGeo.centroid(listOf(LatLon(0.0, 179.9995), LatLon(0.0, -179.9995)))!!
        assertTrue(Math.abs(Math.abs(c.lon) - 180.0) < 0.001)
    }

    @Test fun centroidOfNothingAndOne() {
        assertNull(GroupGeo.centroid(emptyList()))
        assertTrue(Geo.haversine(T.origin, GroupGeo.centroid(listOf(T.origin))!!) < 0.01)
    }

    @Test fun robustCentroidIgnoresALoneStraggler() {
        val cluster = (0 until 9).map { T.east(T.north(T.origin, it * 2.0), it % 3 * 2.0) }
        val all = cluster + T.north(T.origin, 800.0)
        val plain = GroupGeo.centroid(all)!!
        val robust = GroupGeo.robustCentroid(all)!!
        assertTrue(Geo.haversine(robust, T.origin) < 30.0)
        assertTrue(Geo.haversine(plain, T.origin) > 60.0)
    }

    private fun m(id: String, p: LatLon?, speed: Double? = 1.3, status: BuddyStatus = BuddyStatus.Moving) = GroupMember(id, id, p, speed, status, isSelf = id == "me")

    @Test fun aTightGroupIsTogetherWithNoStragglers() {
        val ms = (0 until 6).map { m("m$it", T.north(T.origin, it * 5.0)) }
        val a = GroupAnalyzer.analyze(ms, 0.0)
        assertTrue(a.together); assertTrue(a.stragglers.isEmpty()); assertEquals(6, a.located)
        assertTrue(a.radiusM!! < 20.0)
    }

    @Test fun stragglersAreThoseFarFromTheCentre() {
        val ms = (0 until 8).map { m("m$it", T.north(T.origin, it * 6.0)) } + m("late", T.south(T.origin, 400.0))
        val a = GroupAnalyzer.analyze(ms, 0.0)
        assertEquals(listOf("late"), a.stragglers)
        assertTrue(a.views.getValue("late").straggler)
        assertFalse(a.views.getValue("m3").straggler)
        // 8 of 9 within the radius is still "together" with the default 80% quorum
        assertTrue(a.together)
    }

    @Test fun leaderIsAtTheFrontAndSweeperAtTheBack() {
        val ms = listOf(m("front", T.north(T.origin, 60.0)), m("mid", T.north(T.origin, 30.0)), m("back", T.north(T.origin, 0.0)), m("mid2", T.north(T.origin, 35.0)))
        val a = GroupAnalyzer.analyze(ms, 0.0) // walking north
        assertEquals("front", a.leaderId); assertEquals("back", a.sweeperId)
        assertEquals(GroupRole.Leader, a.views.getValue("front").role)
        assertEquals(GroupRole.Sweeper, a.views.getValue("back").role)
        assertEquals(60.0, a.lengthM!!, 1.0)
        // the same people walking south swap roles
        val s = GroupAnalyzer.analyze(ms, 180.0)
        assertEquals("back", s.leaderId); assertEquals("front", s.sweeperId)
    }

    @Test fun noRolesWithoutHeadingOrWithTooFewPeople() {
        val ms = listOf(m("a", T.north(T.origin, 0.0)), m("b", T.north(T.origin, 40.0)), m("c", T.north(T.origin, 80.0)))
        assertNull(GroupAnalyzer.analyze(ms, null).leaderId)
        assertNull(GroupAnalyzer.analyze(ms.take(2), 0.0).leaderId)
        assertNotNull(GroupAnalyzer.analyze(ms, 0.0).leaderId)
    }

    @Test fun aChosenSweeperIsNeverAStraggler() {
        val ms = (0 until 6).map { m("m$it", T.north(T.origin, it * 5.0)) } + m("sweep", T.south(T.origin, 300.0))
        val byPosition = GroupAnalyzer.analyze(ms, 0.0)
        assertEquals("sweep", byPosition.sweeperId) // last in line...
        assertFalse(byPosition.sweeperChosen)
        assertEquals(listOf("sweep"), byPosition.stragglers) // ...and far back, so still a straggler
        val chosen = GroupAnalyzer.analyze(ms, 0.0, GroupConfig(sweeperId = "sweep"))
        assertTrue(chosen.sweeperChosen)
        assertTrue(chosen.stragglers.isEmpty())
        val other = GroupAnalyzer.analyze(ms, 0.0, GroupConfig(sweeperId = "m0"))
        assertEquals("m0", other.sweeperId)
        assertEquals(listOf("sweep"), other.stragglers)
        val gone = GroupAnalyzer.analyze(ms, 0.0, GroupConfig(sweeperId = "nobody"))
        assertNotNull(gone.sweeperId); assertFalse(gone.sweeperChosen)
    }

    @Test fun lostAndWaitingMembersAreLeftOut() {
        val ms = listOf(m("a", T.origin), m("b", T.north(T.origin, 10.0)), m("lost", T.north(T.origin, 5000.0), status = BuddyStatus.ConnectionLost),
            m("wait", null, status = BuddyStatus.Waiting), m("nopos", null))
        val a = GroupAnalyzer.analyze(ms, 0.0)
        assertEquals(2, a.located); assertTrue(a.together); assertTrue(a.stragglers.isEmpty())
    }

    @Test fun blurSlackStopsFalseStragglers() {
        val ms = (0 until 5).map { m("m$it", T.north(T.origin, it * 5.0)) } + m("edge", T.north(T.origin, 190.0))
        assertTrue(GroupAnalyzer.analyze(ms, 0.0).stragglers.isNotEmpty())
        assertTrue(GroupAnalyzer.analyze(ms, 0.0, GroupConfig(slackM = LocationPrecision.Approx100.maxErrorM)).stragglers.isEmpty())
    }

    @Test fun emptyAndSingleGroups() {
        val e = GroupAnalyzer.analyze(emptyList(), null)
        assertNull(e.centre); assertFalse(e.together)
        val one = GroupAnalyzer.analyze(listOf(m("solo", T.origin)), 0.0)
        assertFalse(one.together); assertTrue(one.stragglers.isEmpty()); assertEquals(1, one.located)
    }

    @Test fun groupHeadingFollowsTheCentre() {
        val h = GroupHeadingTracker()
        h.update(T.origin); assertNull(h.headingDeg)
        h.update(T.north(T.origin, 10.0)); assertNull(h.headingDeg) // below the minimum move
        h.update(T.north(T.origin, 40.0))
        assertEquals(0.0, h.headingDeg!!, 1.0)
        h.update(null) // nobody located: heading kept
        assertNotNull(h.headingDeg)
    }
}

class GroupScoreAndGoalTest {
    @Test fun cohesionScoreCountsTogetherTimeOnly() {
        val c = GroupCohesionTracker()
        for (t in 0..60) c.update(t * 1000L, true)
        for (t in 61..120) c.update(t * 1000L, false)
        val s = c.snapshot()
        assertEquals(50, s.scorePct)
        assertEquals(60_000L, s.longestStreakMs)
        assertEquals(0L, s.currentStreakMs)
    }

    @Test fun unknownCountsAsWalkTimeNotTogetherness() {
        val c = GroupCohesionTracker()
        for (t in 0..30) c.update(t * 1000L, null)
        assertEquals(0, c.snapshot().scorePct)
        assertTrue(c.snapshot().walkMs > 0)
    }

    @Test fun collectiveGoalAddsEveryoneUpAndNeverShrinks() {
        val g = CollectiveSteps()
        g.record("a", 1000); g.record("b", 500); g.record("c", 200)
        assertEquals(1700, g.total())
        g.record("a", 900) // stale / lower report ignored
        assertEquals(1700, g.total())
        g.record("a", 1500)
        assertEquals(2200, g.total())
        val p = g.progress(10_000)
        assertEquals(0.22, p.fraction, 0.001); assertEquals(7800, p.remaining); assertFalse(p.reached); assertEquals(3, p.walkers)
        g.record("d", 9_000)
        assertTrue(g.progress(10_000).reached)
        assertEquals(1.0, g.progress(10_000).fraction, 0.0)
    }

    @Test fun noGoalMeansJustATotal() {
        val g = CollectiveSteps(); g.record("a", 10)
        val p = g.progress(0)
        assertFalse(p.reached); assertEquals(0.0, p.fraction, 0.0)
        assertTrue(CollectiveSteps.message(p).contains("10 steps"))
        g.record("a", -5); assertEquals(10, g.total())
    }

    @Test fun suggestedGoalsScaleWithPeopleAndRound() {
        assertEquals(3000, CollectiveSteps.suggestGoal(1))
        assertEquals(6000, CollectiveSteps.suggestGoal(2))
        assertEquals(30000, CollectiveSteps.suggestGoal(10))
        assertEquals(3000, CollectiveSteps.suggestGoal(0))
        assertTrue(CollectiveSteps.suggestGoal(7) % 500 == 0)
    }

    @Test fun goalMessagesAreWarmAndHaveNoRanking() {
        val g = CollectiveSteps(); g.record("a", 4000)
        assertTrue(CollectiveSteps.message(g.progress(8000)).contains("4000 of 8000"))
        g.record("b", 4500)
        assertTrue(CollectiveSteps.message(g.progress(8000)).contains("reached"))
    }
}

class GroupNudgeTest {
    private fun members(selfBehindM: Double? = null, leaderAheadM: Double? = null): List<GroupMember> {
        val base = (0 until 6).map { GroupMember("m$it", "m$it", T.north(T.origin, it * 4.0), 1.3, BuddyStatus.Moving) }
        val me = GroupMember("me", "Me", selfBehindM?.let { T.south(T.origin, it) } ?: leaderAheadM?.let { T.north(T.origin, 20.0) } ?: T.origin, 1.3, BuddyStatus.Moving, isSelf = true)
        return base + me
    }

    private fun run(engine: GroupNudgeEngine, ms: List<GroupMember>, fromSec: Int, toSec: Int): List<Pair<Int, Nudge>> {
        val out = mutableListOf<Pair<Int, Nudge>>()
        for (t in fromSec..toSec) {
            val a = GroupAnalyzer.analyze(ms, 0.0)
            engine.evaluate(t * 1000L, a, "me", true)?.let { out += t to it }
        }
        return out
    }

    @Test fun fallingBehindGetsOneGentleCatchUpHint() {
        val e = GroupNudgeEngine()
        val n = run(e, members(selfBehindM = 260.0), 0, 120)
        assertEquals(1, n.size)
        assertEquals(NudgeKind.CatchUp, n[0].second.kind)
        assertTrue(n[0].first >= 20) // only after the gap persisted
        assertTrue(n[0].second.text.contains("group"))
    }

    @Test fun beingTogetherNeverNudges() {
        assertTrue(run(GroupNudgeEngine(), members(), 0, 300).isEmpty())
    }

    @Test fun theFrontGetsAnEaseOffHintWhenSomeoneIsFarBack() {
        val front = GroupMember("me", "Me", T.north(T.origin, 400.0), 1.3, BuddyStatus.Moving, isSelf = true)
        val pack = (0 until 6).map { GroupMember("m$it", "m$it", T.north(T.origin, it * 4.0), 1.3, BuddyStatus.Moving) }
        val out = run(GroupNudgeEngine(), pack + front, 0, 100)
        assertEquals(1, out.size)
        assertEquals(NudgeKind.EaseOff, out[0].second.kind)
    }

    @Test fun aChosenSweeperIsNeverToldToHurry() {
        val ms = (0 until 6).map { GroupMember("m$it", "m$it", T.north(T.origin, 250.0 + it * 4.0), 1.3, BuddyStatus.Moving) } +
            GroupMember("me", "Me", T.origin, 1.3, BuddyStatus.Moving, isSelf = true)
        fun nudges(cfg: GroupConfig): Int {
            val e = GroupNudgeEngine()
            var n = 0
            for (t in 0..200) if (e.evaluate(t * 1000L, GroupAnalyzer.analyze(ms, 0.0, cfg), "me", true) != null) n++
            return n
        }
        assertEquals(1, nudges(GroupConfig()))              // last in line and far behind: a gentle catch-up hint
        assertEquals(0, nudges(GroupConfig(sweeperId = "me"))) // agreed to be the sweeper: silence
    }

    @Test fun quietPausedGroupAndCapAreRespected() {
        val quiet = GroupNudgeEngine(NudgeConfig(farM = 150.0, nearM = 90.0, quiet = true))
        assertTrue(run(quiet, members(selfBehindM = 260.0), 0, 200).isEmpty())
        val capped = GroupNudgeEngine(NudgeConfig(farM = 150.0, nearM = 90.0, cooldownMs = 1000, maxPerWalk = 2))
        val ms = members(selfBehindM = 260.0)
        val near = members()
        var fired = 0
        for (round in 0 until 6) {
            fired += run(capped, ms, round * 100, round * 100 + 40).size
            fired += run(capped, near, round * 100 + 41, round * 100 + 80).size // back together re-arms
        }
        assertEquals(2, fired)
        assertEquals(2, capped.count)
    }

    @Test fun blurSlackRaisesTheThreshold() {
        val ms = members(selfBehindM = 190.0)
        assertTrue(run(GroupNudgeEngine(), ms, 0, 100).isNotEmpty())
        assertTrue(run(GroupNudgeEngine(slackM = LocationPrecision.Approx100.maxErrorM), ms, 0, 100).isEmpty())
    }
}
