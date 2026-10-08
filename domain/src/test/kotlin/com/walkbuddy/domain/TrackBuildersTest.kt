package com.walkbuddy.domain

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class TrackBuildersTest {
    private fun card(id: String, along: Double?, lastAlong: Double? = null, heard: Int? = 1, av: Int? = null) = BuddyCard(
        id = id, name = id, distanceM = along?.let { Math.abs(it) }, alongM = along, relation = "", zone = null, steps = 10, speedMps = 1.0,
        status = BuddyStatus.Moving, statusText = "", pos = null, lastAlongM = lastAlong, lastHeardAgoSec = heard, avatar = av,
    )

    private fun state(vararg b: BuddyCard) = WalkState(
        nowMs = 0, elapsedMs = 1000, myDistanceM = 500.0, myVerifiedSteps = 600, myRawSteps = 600, myCadenceSpm = null, myZone = null,
        mySpeedMps = 1.0, myActivity = ActivityState.Walking, buddies = b.toList(),
        together = TogetherSnapshot(0, 0, 0, 0, 0), togetherNow = null, nudge = null, paceSuggestion = null,
    )

    @Test fun mePlacedAtOdometerAndBuddyRelative() {
        val w = TrackBuilders.forPartner(state(card("b", 30.0)), "me", Avatar.Default)
        assertEquals(500.0, w[0].xM!!, 1e-9)
        assertTrue(w[0].isMe)
        assertEquals(530.0, w[1].xM!!, 1e-9)
        assertFalse(w[1].stale)
    }

    @Test fun quietBuddyKeepsLastPlaceAndFades() {
        val w = TrackBuilders.forPartner(state(card("b", null, lastAlong = -20.0, heard = 95)), "me", Avatar.Default)
        assertEquals(480.0, w[1].xM!!, 1e-9)
        assertTrue(w[1].stale)
        assertEquals(95, w[1].lastSeenSec)
    }

    @Test fun unknownPlaceStaysUnknown() {
        val w = TrackBuilders.forPartner(state(card("b", null, null, heard = null)), "me", Avatar.Default)
        assertNull(w[1].xM)
    }

    @Test fun avatarCodeIsUsedOrFallsBack() {
        val code = AvatarCode.encode(Avatar(AvatarStyle.Girl, 5))
        val w = TrackBuilders.forPartner(state(card("b", 1.0, av = code), card("c", 2.0)), "me", Avatar.Default)
        assertEquals(AvatarStyle.Girl, w[1].avatar.style)
        assertEquals(AvatarCode.fallback("c"), w[2].avatar)
    }

    @Test fun partnerGapOnlyForExactlyOneBuddy() {
        assertEquals(30.0, TrackBuilders.partnerGap(state(card("b", 30.0)))!!, 1e-9)
        assertNull(TrackBuilders.partnerGap(state()))
        assertNull(TrackBuilders.partnerGap(state(card("b", 3.0), card("c", 4.0))))
    }
}

class SharedDemoTest {
    @Test fun demoWalksAreWellFormedAndRoundTrip() {
        val now = 1_700_000_000_000L
        val all = SharedDemo.build(now)
        assertEquals(3, all.size)
        for (r in all) {
            assertTrue(r.id < 0)
            assertTrue(r.lanes.size >= 2)
            assertTrue(r.me != null)
            assertTrue(r.samples.size > 10)
            assertTrue(r.maxGapM > 0.0)
            // the same text columns the database stores
            val lanes = SharedWalkCodec.decodeLanes(SharedWalkCodec.encodeLanes(r.lanes))
            val samples = SharedWalkCodec.decodeSamples(SharedWalkCodec.encodeSamples(r.samples))
            assertEquals(r.lanes.map { it.id }, lanes.map { it.id })
            assertEquals(r.samples.size, samples.size)
        }
        assertEquals(SharedMode.Group, all[1].mode)
        assertTrue(all.map { it.startMs }.zipWithNext().all { (a, b) -> a > b })
    }
}
