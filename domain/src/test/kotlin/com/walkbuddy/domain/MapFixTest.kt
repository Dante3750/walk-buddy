package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map showed nothing for many real situations. These pin the fixes: weak fixes are shown, buddies keep a last known place, the camera zooms in. */
class MapFixTest {
    private val o = T.origin

    // ---------- my position ----------

    @Test fun anApproximateFixIsShownAndSharedEvenThoughItNeverCountsForDistance() {
        val e = WalkEngine("me", "Me", startMs = 0)
        // Android "approximate location" or an indoor start: accuracy far above the 30 m distance filter
        assertEquals(FixVerdict.LowAccuracy, e.onSelfFix(Fix(1_000, o.lat, o.lon, 120.0)))
        assertNotNull(e.myPosition)
        val st = e.tick(2_000)
        assertNotNull("map has a dot", st.myPos)
        assertEquals(LocationQuality.Weak, st.locationQuality)
        assertEquals(120.0, st.myAccuracyM!!, 0.0)
        assertEquals("distance is untouched", 0.0, st.myDistanceM, 0.0)
        val shared = e.selfPosition(2_000)
        assertNotNull("and the buddy can see me", shared)
        assertEquals(120.0, shared!!.accuracyM!!, 0.0)
    }

    @Test fun beforeAnyFixThereIsHonestlyNothing() {
        val e = WalkEngine("me", "Me", startMs = 0)
        val st = e.tick(1_000)
        assertNull(st.myPos); assertNull(e.selfPosition(1_000))
        assertEquals(LocationQuality.None, st.locationQuality)
    }

    @Test fun fixesBeyondTheDisplayLimitAreIgnored() {
        val e = WalkEngine("me", "Me", startMs = 0)
        e.onSelfFix(Fix(1_000, o.lat, o.lon, 900.0))
        assertNull(e.myPosition)
    }

    @Test fun aGoodFixWinsOverANoisyWeakOneArrivingRightAfter() {
        val e = WalkEngine("me", "Me", startMs = 0)
        e.onSelfFix(Fix(1_000, o.lat, o.lon, 5.0))
        val far = T.north(o, 80.0)
        e.onSelfFix(Fix(2_000, far.lat, far.lon, 150.0)) // network fix a second later
        assertEquals(o.lat, e.myPosition!!.lat, 1e-9)
        assertEquals(LocationQuality.Good, e.tick(3_000).locationQuality)
    }

    @Test fun whenGpsGoesQuietAWeakerNewerFixTakesOver() {
        val e = WalkEngine("me", "Me", startMs = 0)
        e.onSelfFix(Fix(1_000, o.lat, o.lon, 5.0))
        val later = T.north(o, 60.0)
        e.onSelfFix(Fix(40_000, later.lat, later.lon, 90.0))
        assertEquals(later.lat, e.myPosition!!.lat, 1e-9)
        assertEquals(LocationQuality.Weak, e.tick(41_000).locationQuality)
    }

    @Test fun goodGpsKeepsDistanceAndQualityGood() {
        val e = WalkEngine("me", "Me", startMs = 0)
        for (i in 0..20) { val p = T.north(o, i * 5.0); e.onSelfFix(Fix(i * 3_000L, p.lat, p.lon, 6.0, 1.6)) }
        val st = e.tick(61_000)
        assertEquals(LocationQuality.Good, st.locationQuality)
        assertTrue(st.myDistanceM in 90.0..105.0)
    }

    // ---------- buddy ----------

    private fun pos(t: Long, p: LatLon, acc: Double? = 5.0, dist: Double? = 50.0) = PeerMessage.Position(t, p.lat, p.lon, acc, 1.3, 40, 110.0, dist)

    @Test fun aBuddyWhoGoesQuietKeepsALastKnownPlaceAndAge() {
        val e = WalkEngine("me", "Me", startMs = 0)
        e.onPeer(0, "b", PeerMessage.Hello("b", "Sam", 9))
        for (i in 0..10) { val m = T.north(o, i * 2.0); e.onSelfFix(Fix(i * 1000L, m.lat, m.lon, 4.0, 2.0)) }
        val b = T.north(o, 40.0)
        e.onPeer(10_000, "b", pos(10_000, b))
        val live = e.tick(11_000).buddies.single()
        assertNotNull(live.pos); assertEquals(1, live.lastHeardAgoSec)
        val lost = e.tick(60_000).buddies.single()
        assertEquals(BuddyStatus.ConnectionLost, lost.status)
        assertNull("the live position is gone, as before", lost.pos)
        assertNotNull("the last known one is kept", lost.lastPos)
        assertEquals(b.lat, lost.lastPos!!.lat, 1e-9)
        assertEquals(50, lost.lastHeardAgoSec)
        assertEquals(20.0, lost.lastDistanceM!!, 3.0)
        assertTrue("ahead of me along the direction of travel", lost.lastAlongM!! > 10)
        assertEquals(50.0, lost.walkedM!!, 0.0)
        assertEquals(9, lost.avatar)
    }

    @Test fun aBuddyWhoSaidByeHasNoLastPlace() {
        val e = WalkEngine("me", "Me", startMs = 0)
        e.onSelfFix(Fix(0, o.lat, o.lon, 5.0))
        e.onPeer(1_000, "b", pos(1_000, T.north(o, 10.0)))
        e.onPeer(2_000, "b", PeerMessage.Bye)
        assertNull(e.tick(3_000).buddies.single().lastPos)
    }

    @Test fun buddyPositionsAreAcceptedUpToTheDisplayLimit() {
        val e = WalkEngine("me", "Me", startMs = 0)
        e.onPeer(1_000, "b", pos(1_000, T.north(o, 10.0), acc = 180.0))
        e.onSelfFix(Fix(1_000, o.lat, o.lon, 5.0))
        assertNotNull("was dropped above 60 m before", e.tick(2_000).buddies.single().pos)
        val f = WalkEngine("me", "Me", startMs = 0)
        f.onPeer(1_000, "b", pos(1_000, T.north(o, 10.0), acc = 400.0))
        f.onSelfFix(Fix(1_000, o.lat, o.lon, 5.0))
        assertNull(f.tick(2_000).buddies.single().pos)
    }

    @Test fun groupMembersKeepTheirLastPlaceAndAvatar() {
        val e = GroupEngine("me", "Me", startMs = 0)
        e.onMemberJoined("a", "Asha", 21)
        e.onSelfFix(Fix(0, o.lat, o.lon, 5.0))
        val a = T.north(o, 30.0)
        e.onUpdate(1_000, "a", GroupUpdate(1_000, a.lat, a.lon, 150.0, 1.2, 10, 100.0, 77.0))
        val st = e.tick(2_000).members.single()
        assertNotNull(st.pos); assertEquals(21, st.avatar); assertEquals(77.0, st.walkedM!!, 0.0)
        val lost = e.tick(90_000).members.single()
        assertNull(lost.pos)
        assertNotNull(lost.lastPos)
        assertEquals(89, lost.lastHeardAgoSec)
        e.setRoster(listOf(RosterEntry("a", "Asha", false, 22)))
        assertEquals(22, e.tick(91_000).members.single().avatar)
    }

    @Test fun groupMyOwnWeakFixShows() {
        val e = GroupEngine("me", "Me", startMs = 0)
        e.onSelfFix(Fix(1_000, o.lat, o.lon, 200.0))
        val st = e.tick(2_000)
        assertNotNull(st.myPos); assertEquals(LocationQuality.Weak, st.locationQuality)
    }

    // ---------- camera ----------

    @Test fun followMeZoomsInFromTheWorldView() {
        val world = MapViewport(LatLon(20.0, 0.0), 3.0, 400, 600)
        val t = MapCamera.target(MapFollow.Me, o, emptyList(), null, world)!!
        assertEquals(MapCamera.FOLLOW_ZOOM, t.zoom, 0.0)
        assertEquals(o, t.center)
        val close = MapViewport(o, 18.0, 400, 600)
        assertEquals("a deliberate zoom is kept", 18.0, MapCamera.target(MapFollow.Me, o, emptyList(), null, close)!!.zoom, 0.0)
        assertNull(MapCamera.target(MapFollow.Me, null, emptyList(), null, world))
    }

    @Test fun fitHandlesOnePointIdenticalPointsAndTinySpans() {
        for (pts in listOf(listOf(o), listOf(o, o, o), listOf(o, T.north(o, 0.2)))) {
            val v = MapViewport.fit(pts, 400, 600)
            assertTrue(v.zoom.isFinite() && v.zoom in WebMercator.MIN_ZOOM..WebMercator.MAX_ZOOM)
            assertTrue(v.center.isValid)
            val s = v.toScreen(o)
            assertTrue(s.x.isFinite() && s.y.isFinite())
            assertTrue(s.x in 0.0..400.0 && s.y in 0.0..600.0)
        }
        assertEquals(3.0, MapViewport.fit(emptyList(), 0, 0).zoom, 0.0)
        val polar = MapViewport.fit(listOf(LatLon(89.9, 0.0), LatLon(89.9, 1.0)), 300, 300)
        assertTrue(polar.zoom.isFinite())
        val across = MapViewport.fit(listOf(LatLon(0.0, 179.9), LatLon(0.0, -179.9)), 300, 300)
        assertTrue("known limit: the antimeridian is not special-cased", across.zoom.isFinite())
    }

    // ---------- status copy ----------

    @Test fun statusExplainsWhyThereIsNoDot() {
        assertEquals(LocationStatus.NoPermission, LocationStatusLogic.of(false, true, LocationQuality.Good))
        assertEquals(LocationStatus.GpsOff, LocationStatusLogic.of(true, false, LocationQuality.None))
        assertEquals(LocationStatus.Searching, LocationStatusLogic.of(true, true, LocationQuality.None))
        assertEquals(LocationStatus.Weak, LocationStatusLogic.of(true, true, LocationQuality.Weak))
        assertEquals(LocationStatus.Ok, LocationStatusLogic.of(true, true, LocationQuality.Good))
        assertEquals(LocationStatus.Approximate, LocationStatusLogic.of(true, true, LocationQuality.None, precise = false))
        assertEquals(LocationStatus.Approximate, LocationStatusLogic.of(true, true, LocationQuality.Weak, precise = false))
        assertEquals(LocationStatus.Ok, LocationStatusLogic.of(true, true, LocationQuality.Good, precise = false))
        assertEquals(LocationStatus.GpsOff, LocationStatusLogic.of(true, false, LocationQuality.None, precise = false))
        assertEquals(LocationAction.AllowLocation, LocationStatusLogic.notice(LocationStatus.Approximate)!!.action)
        assertNull(LocationStatusLogic.notice(LocationStatus.Ok))
        assertEquals(LocationAction.AllowLocation, LocationStatusLogic.notice(LocationStatus.NoPermission)!!.action)
        assertEquals(LocationAction.OpenLocationSettings, LocationStatusLogic.notice(LocationStatus.GpsOff)!!.action)
        assertFalse(LocationStatusLogic.notice(LocationStatus.Searching, 10)!!.body == LocationStatusLogic.notice(LocationStatus.Searching, 90)!!.body)
        assertNull(LocationStatusLogic.notice(LocationStatus.Weak)!!.actionLabel)
    }
}
