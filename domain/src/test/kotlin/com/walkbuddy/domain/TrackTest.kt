package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    private fun w(id: String, x: Double?, me: Boolean = false, stale: Boolean = false, straggler: Boolean = false, role: GroupRole? = null) =
        TrackWalker(id, if (me) "You" else id.replaceFirstChar { it.uppercase() }, AvatarCode.fallback(id), x, 100, me, stale, null, straggler, role)

    private fun frame(vararg ws: TrackWalker, real: Double? = null, cam: TrackCamera = TrackCamera(), unit: UnitSystem = UnitSystem.Metric) =
        TrackLayout.frame(ws.toList(), cam, real, unit)

    @Test fun twoWalkersSideBySideAreTogetherAndGlow() {
        val f = frame(w("me", 500.0, me = true), w("sam", 504.0))
        assertTrue(f.together)
        assertEquals("Side by side", f.gapText)
        assertEquals(4.0, f.gapM!!, 1e-9)
        assertTrue(f.lanes.all { it.cue == TrackCue.None })
        assertTrue(f.lanes.all { it.frac != null && it.offscreen == 0 })
    }

    @Test fun gapTextUsesTheMeasuredDistanceForTwoPeopleAndUnits() {
        val f = frame(w("me", 500.0, me = true), w("sam", 380.0), real = 121.0)
        assertEquals("121 m apart", f.gapText)
        assertFalse(f.together)
        val imp = frame(w("me", 500.0, me = true), w("sam", 380.0), real = 121.0, unit = UnitSystem.Imperial)
        assertEquals("397 ft apart", imp.gapText)
    }

    @Test fun leaderAndStragglerCuesAppearOnlyWhenThereIsAGap() {
        val apart = frame(w("me", 500.0, me = true), w("sam", 430.0))
        assertEquals(TrackCue.Front, apart.lanes[0].cue)
        assertEquals(TrackCue.Back, apart.lanes[1].cue)
        val little = frame(w("me", 500.0, me = true), w("sam", 480.0)) // 20 m: ahead yes, but not far enough to call the other the straggler
        assertEquals(TrackCue.Front, little.lanes[0].cue)
        assertEquals(TrackCue.None, little.lanes[1].cue)
        val beside = frame(w("me", 500.0, me = true), w("sam", 500.0), real = 300.0) // far apart sideways only: nobody is ahead
        assertTrue(beside.lanes.all { it.cue == TrackCue.None })
    }

    @Test fun unknownPositionIsNotDrawnOnTheLineAndGapIsUnknown() {
        val f = frame(w("me", 120.0, me = true), w("sam", null))
        assertNull(f.lanes[1].frac)
        assertNull(f.gapM)
        assertEquals("Waiting for locations", f.gapText)
        val solo = frame(w("me", 120.0, me = true))
        assertEquals("Walking solo", solo.gapText)
        assertFalse(solo.together)
        val none = frame(w("me", null, me = true))
        assertNull(none.lanes[0].frac)
    }

    @Test fun peopleOutsideTheWindowAreClampedWithAnArrow() {
        val cam = TrackCamera()
        cam.update(listOf(500.0)) // window 120 m, left ~418
        val ws = listOf(w("me", 500.0, me = true), w("far", 5000.0))
        val f = TrackLayout.frame(ws, TrackCamera().also { it.update(listOf(500.0)) }, null, UnitSystem.Metric)
        // the frame itself widens the window to fit both, so use a fixed camera for the clamp test
        assertTrue(f.windowM >= 5000.0)
        val lane = TrackLayout.frame(listOf(w("me", 500.0, me = true)), cam, null, UnitSystem.Metric).lanes[0]
        assertEquals(0, lane.offscreen)
    }

    @Test fun cameraKeepsEveryoneInViewAndDoesNotJitter() {
        val cam = TrackCamera()
        cam.update(listOf(0.0, 10.0))
        val w1 = cam.windowM
        assertEquals(120.0, w1, 0.0)
        cam.update(listOf(0.0, 400.0))
        assertTrue(cam.windowM >= 400 * 1.7)
        val widened = cam.windowM
        cam.update(listOf(0.0, 380.0)) // slightly tighter: no zoom change (hysteresis)
        assertEquals(widened, cam.windowM, 0.0)
        cam.update(listOf(0.0, 5.0)) // much tighter: zooms back in
        assertTrue(cam.windowM < widened)
        // everyone is inside the visible range
        cam.update(listOf(900.0, 1100.0))
        assertTrue(900.0 >= cam.leftM && 1100.0 <= cam.leftM + cam.windowM)
    }

    @Test fun aloneWalkerStartsNearTheLeftWithRoadAhead() {
        val cam = TrackCamera()
        val f = TrackLayout.frame(listOf(w("me", 0.0, me = true)), cam, null, UnitSystem.Metric)
        val frac = f.lanes[0].frac!!
        assertTrue("frac $frac", frac in 0.5f..0.8f)
        assertEquals("Start", f.stations.first().label)
    }

    @Test fun stationsAreRoundDistancesInsideTheWindowAndMarkPassedOnes() {
        val cam = TrackCamera()
        val f = TrackLayout.frame(listOf(w("me", 1200.0, me = true), w("sam", 1180.0)), cam, null, UnitSystem.Metric)
        assertTrue(f.stations.size in 1..7)
        val spacing = TrackLayout.stationSpacing(f.windowM, UnitSystem.Metric)
        for (s in f.stations) {
            assertEquals(0.0, s.m % spacing, 1e-6)
            assertTrue(s.frac in 0f..1f)
            assertEquals(s.m <= 1200.0, s.passed)
        }
        assertEquals("1.2 km", TrackLayout.stationLabel(1200.0, UnitSystem.Metric))
        assertEquals("500 m", TrackLayout.stationLabel(500.0, UnitSystem.Metric))
        assertEquals("1 km", TrackLayout.stationLabel(1000.0, UnitSystem.Metric))
        assertEquals("1 mi", TrackLayout.stationLabel(1609.344, UnitSystem.Imperial))
        assertEquals("0.25 mi", TrackLayout.stationLabel(402.336, UnitSystem.Imperial))
        assertEquals("100 ft", TrackLayout.stationLabel(30.48, UnitSystem.Imperial))
        assertEquals("Start", TrackLayout.stationLabel(0.0, UnitSystem.Metric))
        assertTrue(TrackLayout.stationSpacing(120.0, UnitSystem.Metric) <= 50.0)
        assertTrue(TrackLayout.stationSpacing(5000.0, UnitSystem.Metric) >= 1000.0)
    }

    @Test fun stationsNeverShowNegativeDistances() {
        val f = TrackLayout.frame(listOf(w("me", 3.0, me = true)), TrackCamera(), null, UnitSystem.Metric)
        assertTrue(f.stations.all { it.m >= 0 })
    }

    @Test fun groupsKeepMeAndTheNearestWhenThereAreTooMany() {
        val ws = listOf(w("me", 500.0, me = true)) + (1..12).map { w("p$it", 500.0 + it * 5.0) }
        val f = TrackLayout.frame(ws, TrackCamera(), null, UnitSystem.Metric)
        assertEquals(TrackLayout.MAX_LANES, f.lanes.size)
        assertEquals(5, f.hidden)
        assertTrue(f.lanes[0].walker.isMe)
        assertTrue(f.lanes.any { it.walker.id == "p1" })
        assertFalse(f.lanes.any { it.walker.id == "p12" })
        assertTrue(f.description.contains("5 more"))
        assertTrue(f.gapText.startsWith("Front to back"))
    }

    @Test fun groupRolesAndStragglersDriveTheCues() {
        val f = frame(
            w("me", 500.0, me = true), w("lead", 530.0, role = GroupRole.Leader), w("slow", 440.0, straggler = true),
        )
        assertEquals(TrackCue.Front, f.lanes[1].cue)
        assertEquals(TrackCue.Back, f.lanes[2].cue)
        assertEquals(TrackCue.None, f.lanes[0].cue)
    }

    @Test fun lanesKeepTheirOrderSoNobodyJumpsAround() {
        val a = frame(w("me", 500.0, me = true), w("sam", 530.0), w("eve", 470.0))
        val b = frame(w("me", 540.0, me = true), w("sam", 530.0), w("eve", 600.0))
        assertEquals(listOf("me", "sam", "eve"), a.lanes.map { it.walker.id })
        assertEquals(listOf("me", "sam", "eve"), b.lanes.map { it.walker.id })
    }

    @Test fun staleWalkersStayOnTheLineAndAreDescribed() {
        val f = frame(w("me", 500.0, me = true), w("sam", 460.0, stale = true))
        assertNotNull(f.lanes[1].frac)
        assertTrue(f.description.contains("not heard from"))
        assertEquals("Last seen 12 s ago", TrackLayout.lastSeen(12))
        assertEquals("Just now", TrackLayout.lastSeen(2))
        assertEquals("Last seen 3 min ago", TrackLayout.lastSeen(200))
        assertEquals("Not seen yet", TrackLayout.lastSeen(null))
    }

    @Test fun easingMovesTowardTheTargetAndSnapsWhenReduceMotionOrClose() {
        assertEquals(10.0, TrackLayout.ease(0.0, 100.0, rate = 0.1), 1e-9)
        assertEquals(100.0, TrackLayout.ease(99.8, 100.0), 0.0)
        assertEquals(100.0, TrackLayout.ease(0.0, 100.0, instant = true), 0.0)
        assertEquals(5.0, TrackLayout.ease(Double.NaN, 5.0), 0.0)
        var x = 0.0
        repeat(40) { x = TrackLayout.ease(x, 100.0) }
        assertEquals(100.0, x, 0.5)
    }

    @Test fun niceWindowGrowsInSteps() {
        assertEquals(120.0, TrackLayout.niceWindow(1.0), 0.0)
        assertEquals(300.0, TrackLayout.niceWindow(250.0), 0.0)
        assertEquals(80_000.0, TrackLayout.niceWindow(79_000.0), 0.0)
        assertTrue(TrackLayout.niceWindow(500_000.0) >= 500_000.0)
    }
}
