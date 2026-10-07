package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkEngineTest {
    private val speed = 1.4

    /** Runs a two-person walk northwards at 1 Hz. [buddyOffset] gives metres the buddy is AHEAD (+) of me at each second. */
    private class Sim(val engine: WalkEngine) {
        var last: WalkState? = null
        val nudges = mutableListOf<Pair<Int, Nudge>>()
        fun step(sec: Int, buddyAheadM: Double, buddySends: Boolean = true, buddySpeed: Double = 1.4, buddyDist: Double = 1.4 * sec, quietBuddy: Boolean = false) {
            val now = sec * 1000L
            val me = T.north(T.origin, 1.4 * sec)
            engine.onSelfFix(Fix(now, me.lat, me.lon, 5.0, 1.4))
            engine.onSelfSteps(now, 2L * sec)
            if (buddySends) {
                val b = T.north(me, buddyAheadM)
                engine.onPeer(now, "b", PeerMessage.Position(now, b.lat, b.lon, 5.0, buddySpeed, sec * 2, 120.0, buddyDist))
            }
            val st = engine.tick(now)
            st.nudge?.let { nudges += sec to it }
            last = st
        }
    }

    private fun sim(config: WalkConfig = WalkConfig()): Sim {
        val e = WalkEngine("me", "Me", config, startMs = 0)
        e.onPeer(0, "b", PeerMessage.Hello("b", "Asha"))
        return Sim(e)
    }

    @Test fun walkingTogetherScoresHighAndShowsBuddy() {
        val s = sim()
        for (t in 1..120) s.step(t, buddyAheadM = 20.0)
        val st = s.last!!
        assertTrue("together ${st.together.scorePct}", st.together.scorePct >= 95)
        assertTrue(st.togetherNow == true)
        val card = st.buddies.single()
        assertEquals("Asha", card.name)
        assertEquals(20.0, card.distanceM!!, 2.0)
        assertTrue(card.alongM!! > 15)
        assertEquals("About 20 m ahead", card.relation)
        assertEquals(BuddyStatus.Moving, card.status)
        assertEquals(PaceZone.Brisk, card.zone)
        assertEquals(PaceZone.Brisk, st.myZone)
        assertEquals("first counter sample is the baseline", 238, st.myVerifiedSteps)
        assertTrue(s.nudges.isEmpty())
        assertTrue(st.myDistanceM in 160.0..172.0)
    }

    @Test fun behindIsReportedAsBehind() {
        val s = sim()
        for (t in 1..60) s.step(t, buddyAheadM = -35.0)
        assertEquals("About 35 m behind", s.last!!.buddies.single().relation)
    }

    @Test fun gapTriggersExactlyOneGentleNudgeForTheOneBehind() {
        val s = sim()
        for (t in 1..40) s.step(t, 10.0)
        for (t in 41..200) s.step(t, 160.0) // buddy pulls ahead and stays there
        assertEquals(1, s.nudges.size)
        val (at, n) = s.nudges.single()
        assertTrue("fired at $at", at in 60..70)
        assertEquals(NudgeKind.CatchUp, n.kind)
        assertTrue(s.last!!.together.scorePct < 40)
    }

    @Test fun quietModeMutesNudgesForTheWholeWalk() {
        val s = sim(WalkConfig(nudge = NudgeConfig(quiet = true)))
        for (t in 1..40) s.step(t, 10.0)
        for (t in 41..300) s.step(t, 200.0)
        assertTrue(s.nudges.isEmpty())
    }

    @Test fun quietModeCanBeToggledMidWalk() {
        val s = sim()
        for (t in 1..40) s.step(t, 10.0)
        s.engine.updateConfig(WalkConfig(nudge = NudgeConfig(quiet = true)))
        for (t in 41..200) s.step(t, 200.0)
        assertTrue(s.nudges.isEmpty())
    }

    @Test fun paceSyncNudgesTheFasterOneInstead() {
        // The buddy is behind me (I'm faster/ahead): in pace-sync mode I get the gentle nudge.
        val s = sim(WalkConfig(nudge = NudgeConfig(paceSync = true)))
        for (t in 1..40) s.step(t, -10.0)
        for (t in 41..120) s.step(t, -160.0)
        assertEquals(NudgeKind.EaseOff, s.nudges.first().second.kind)
    }

    @Test fun silenceBecomesACalmConnectionLostStateAndNoNudges() {
        val s = sim()
        for (t in 1..60) s.step(t, 20.0)
        for (t in 61..120) s.step(t, 20.0, buddySends = false)
        val card = s.last!!.buddies.single()
        assertEquals(BuddyStatus.ConnectionLost, card.status)
        assertTrue(card.statusText.contains("Asha"))
        assertNull(card.distanceM)
        assertTrue(s.nudges.isEmpty())
        // Last known position is trusted for 30 s, then the silent time stops counting as together.
        assertTrue("score ${s.last!!.together.scorePct}", s.last!!.together.scorePct in 65..85)
        // They come back.
        s.step(121, 20.0)
        assertEquals(BuddyStatus.Moving, s.last!!.buddies.single().status)
    }

    @Test fun buddyWhoStopsIsShownAsPausedNotAsAProblem() {
        val s = sim()
        for (t in 1..30) s.step(t, 20.0)
        for (t in 31..70) s.step(t, 20.0, buddySpeed = 0.0, buddyDist = 42.0)
        val card = s.last!!.buddies.single()
        assertEquals(BuddyStatus.Stopped, card.status)
        assertTrue(card.statusText.contains("paused"))
    }

    @Test fun byeMessageShowsDisconnectedCalmly() {
        val s = sim()
        for (t in 1..10) s.step(t, 20.0)
        s.engine.onPeer(11_000, "b", PeerMessage.Bye)
        assertEquals(BuddyStatus.ConnectionLost, s.engine.tick(11_000).buddies.single().status)
    }

    @Test fun paceSuggestionTargetsTheSlowest() {
        val s = sim()
        for (t in 1..60) s.step(t, 20.0, buddySpeed = 0.9, buddyDist = 0.9 * t)
        val sug = s.last!!.paceSuggestion
        assertNotNull(sug)
        assertEquals("b", sug!!.slowestId)
        assertEquals(0.9, sug.targetMps, 0.1)
    }

    @Test fun soloWalkHasNoTogetherScore() {
        val e = WalkEngine("me", "Me", startMs = 0)
        for (t in 1..60) {
            val me = T.north(T.origin, 1.4 * t)
            e.onSelfFix(Fix(t * 1000L, me.lat, me.lon, 5.0, 1.4)); e.onSelfSteps(t * 1000L, 2L * t); e.tick(t * 1000L)
        }
        val sum = e.finish(60_000)
        assertNull(sum.togetherPct)
        assertEquals(0, sum.buddyCount)
        assertEquals(118, sum.verifiedSteps)
    }

    @Test fun summaryCarriesTogetherAndCaloriesOnlyWhenEnabled() {
        val prof = BodyProfile(170.0, 70.0, Sex.Unspecified)
        val off = sim(WalkConfig(profile = prof, caloriesEnabled = false))
        val on = sim(WalkConfig(profile = prof, caloriesEnabled = true))
        for (t in 1..600) { off.step(t, 10.0); on.step(t, 10.0) }
        val a = off.engine.finish(600_000); val b = on.engine.finish(600_000)
        assertNull(a.calories)
        assertNotNull(b.calories)
        assertTrue(b.calories!!.lowKcal < b.calories!!.highKcal)
        assertEquals(1, b.buddyCount)
        assertTrue(b.togetherPct!! >= 95)
        assertTrue(b.longestTogetherMs >= 500_000)
        assertTrue(a.avgSpeedMps!! in 1.2..1.5)
        assertTrue(off.engine.ended)
    }

    @Test fun pingsAreRateLimitedBothWays() {
        val s = sim()
        s.step(1, 10.0)
        assertNotNull(s.engine.requestPing(1000))
        assertNull(s.engine.requestPing(2000))
        s.engine.onPeer(5_000, "b", PeerMessage.Ping(5000))
        s.engine.onPeer(6_000, "b", PeerMessage.Ping(6000))
        assertEquals("Asha", s.engine.takePing())
        assertNull("second ping within 30 s is dropped", s.engine.takePing())
    }

    @Test fun sharedSpotsAreQueuedForTheUserToAccept() {
        val s = sim()
        s.engine.onPeer(1000, "b", PeerMessage.Spot("Lake loop", 12.9, 77.6))
        val (from, spot) = s.engine.takeSpot()!!
        assertEquals("Asha", from); assertEquals("Lake loop", spot.name)
        assertNull(s.engine.takeSpot())
    }

    @Test fun atMostThreeBuddiesAndNoSelfEcho() {
        val e = WalkEngine("me", "Me", startMs = 0)
        for (id in listOf("a", "b", "c", "d")) e.onPeer(0, id, PeerMessage.Hello(id, id))
        e.onPeer(0, "me", PeerMessage.Hello("me", "Me"))
        assertEquals(listOf("a", "b", "c"), e.peerIds)
    }

    @Test fun lowAccuracyPeerPositionsAreIgnored() {
        val e = WalkEngine("me", "Me", startMs = 0)
        e.onSelfFix(Fix(1000, T.origin.lat, T.origin.lon, 5.0))
        e.onPeer(1000, "b", PeerMessage.Position(1000, 1.0, 1.0, 500.0, null, 0, null, null))
        assertNull(e.tick(1000).buddies.single().pos)
    }

    @Test fun selfBroadcastIsAvailableAfterFirstFix() {
        val e = WalkEngine("me", "Me", startMs = 0)
        assertNull(e.selfPosition(0))
        e.onSelfFix(Fix(1000, T.origin.lat, T.origin.lon, 5.0))
        val m = e.selfPosition(1000)!!
        assertEquals(T.origin.lat, m.lat, 1e-9)
        assertTrue(MessageCodec.decode(MessageCodec.encode(m)) is DecodeResult.Ok)
    }

    @Test fun relationWording() {
        assertEquals("Right beside you", WalkEngine.relationText(10.0, 3.0))
        assertEquals("Location not shared yet", WalkEngine.relationText(null, null))
        assertEquals("About 40 m away, side by side", WalkEngine.relationText(40.0, 5.0))
        assertFalse(WalkEngine.relationText(80.0, 80.0).contains("-"))
    }
}
