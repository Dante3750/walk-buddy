package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavCompassTest {
    private val pin = T.north(T.origin, 600.0)

    // ---------- meeting navigation ----------

    @Test fun legUsesRealPaceWhenMoving() {
        val l = MeetingNav.leg("me", "You", true, T.origin, pin, 1.5)!!
        assertEquals(600.0, l.distanceM, 3.0)
        assertEquals(0.0, l.bearingDeg, 1.0)
        assertEquals(400.0, l.etaSec!!.toDouble(), 8.0)
        assertFalse(l.etaEstimated)
        assertFalse(l.arrived)
    }

    @Test fun standingStillFallsBackToATypicalPaceAndSaysSo() {
        val l = MeetingNav.leg("a", "Asha", false, T.origin, pin, 0.0)!!
        assertTrue(l.etaEstimated)
        assertEquals(600.0 / MeetingNav.TYPICAL_WALK_MPS, l.etaSec!!.toDouble(), 10.0)
        assertTrue(MeetingNav.etaWords(l.etaSec, l.etaEstimated).startsWith("about "))
    }

    @Test fun absurdSpeedsAreCapped() {
        val l = MeetingNav.leg("a", "A", false, T.origin, pin, 40.0)!!
        assertEquals(600.0 / MeetingNav.MAX_PACE_MPS, l.etaSec!!.toDouble(), 10.0)
    }

    @Test fun arrivedWithinTwentyMetres() {
        val l = MeetingNav.leg("a", "A", false, T.north(pin, -12.0), pin, 1.2)!!
        assertTrue(l.arrived)
        assertEquals(0L, l.etaSec)
        assertEquals("there", MeetingNav.etaWords(l.etaSec, l.etaEstimated))
    }

    @Test fun noPositionNoLeg() {
        assertNull(MeetingNav.leg("a", "A", false, null, pin, 1.0))
        assertNull(MeetingNav.leg("a", "A", false, LatLon(200.0, 0.0), pin, 1.0))
    }

    @Test fun legsListsMeFirstThenNearest() {
        val me = MeetingNav.leg("me", "You", true, T.origin, pin, 1.0)
        val near = MeetingNav.leg("n", "Near", false, T.north(T.origin, 400.0), pin, 1.0)
        val far = MeetingNav.leg("f", "Far", false, T.south(T.origin, 300.0), pin, 1.0)
        val l = MeetingNav.legs(me, listOf(far, null, near))
        assertEquals(listOf("me", "n", "f"), l.map { it.id })
    }

    @Test fun etaWordsAndCompassPoints() {
        assertEquals("under a minute", MeetingNav.etaWords(30, false))
        assertEquals("6 min", MeetingNav.etaWords(360, false))
        assertEquals("1 h 5 min", MeetingNav.etaWords(3900, false))
        assertEquals("-", MeetingNav.etaWords(null, false))
        assertEquals("N", MeetingNav.compassPoint(359.0))
        assertEquals("E", MeetingNav.compassPoint(90.0))
        assertEquals("SW", MeetingNav.compassPoint(225.0))
        assertEquals("NW", MeetingNav.compassPoint(-45.0))
    }

    // ---------- compass ----------

    @Test fun angleDifferenceTakesTheShortWay() {
        assertEquals(2.0, Angles.diff(1.0, 359.0), 1e-9)
        assertEquals(-2.0, Angles.diff(359.0, 1.0), 1e-9)
        assertEquals(180.0, Angles.diff(180.0, 0.0), 1e-9)
        assertEquals(10.0, Angles.diff(730.0, 720.0), 1e-9)
        assertEquals(350.0, Angles.norm(-10.0), 1e-9)
    }

    @Test fun filterSmoothsNoiseAcrossNorth() {
        val f = CompassFilter(alpha = 0.2)
        f.update(358.0)
        val a = f.update(2.0)
        assertTrue("moved the short way: $a", a > 358.0 || a < 5.0)
        var h = 0.0
        repeat(200) { h = f.update(if (it % 2 == 0) 10.0 else 350.0) }
        assertTrue("stays near north: $h", Angles.diff(h, 0.0).let { kotlin.math.abs(it) } < 12.0)
    }

    @Test fun filterFollowsAQuickSpinAfterTwoReadings() {
        val f = CompassFilter(alpha = 0.1, snapDeg = 90.0)
        f.update(0.0)
        val first = f.update(180.0)
        assertEquals(0.0, first, 1e-9)
        val second = f.update(180.0)
        assertEquals(180.0, second, 1e-9)
    }

    @Test fun filterIgnoresNaN() {
        val f = CompassFilter()
        f.update(90.0)
        assertEquals(90.0, f.update(Double.NaN), 1e-9)
    }

    @Test fun azimuthFromGravityAndFieldFacingNorthEastSouth() {
        // Phone flat on a table (gravity +z). Field points to magnetic north with a downward dip.
        val g = floatArrayOf(0f, 0f, 9.81f)
        val northField = floatArrayOf(0f, 20f, -40f)
        assertEquals(0.0, CompassMath.azimuthDeg(g, northField)!!, 1.0)
        // Top edge pointing east: the field now appears to come from the phone's left (-x).
        assertEquals(90.0, CompassMath.azimuthDeg(g, floatArrayOf(-20f, 0f, -40f))!!, 1.0)
        assertEquals(180.0, CompassMath.azimuthDeg(g, floatArrayOf(0f, -20f, -40f))!!, 1.0)
        assertEquals(270.0, CompassMath.azimuthDeg(g, floatArrayOf(20f, 0f, -40f))!!, 1.0)
    }

    @Test fun azimuthRejectsBadVectors() {
        assertNull(CompassMath.azimuthDeg(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 20f, -40f)))
        assertNull(CompassMath.azimuthDeg(floatArrayOf(0f, 0f, 9.8f), floatArrayOf(0f, 0f, 0f)))
        assertNull(CompassMath.azimuthDeg(floatArrayOf(1f), floatArrayOf(1f, 2f, 3f)))
    }

    @Test fun relativeTurnAndWords() {
        assertEquals(90.0, CompassMath.relativeDeg(100.0, 10.0), 1e-9)
        assertEquals(-20.0, CompassMath.relativeDeg(350.0, 10.0), 1e-9)
        assertEquals("straight ahead", CompassMath.turnWords(5.0))
        assertEquals("slightly to your left", CompassMath.turnWords(-30.0))
        assertEquals("to your right", CompassMath.turnWords(90.0))
        assertEquals("behind you", CompassMath.turnWords(180.0))
    }

    @Test fun calibrationHintBelowMedium() {
        assertTrue(CompassMath.needsCalibration(0)); assertTrue(CompassMath.needsCalibration(1))
        assertFalse(CompassMath.needsCalibration(2)); assertFalse(CompassMath.needsCalibration(3))
    }

    // ---------- arrow target ----------

    private val asha = ArrowCandidate("a", "Asha", T.north(T.origin, 50.0), 50.0)
    private val ben = ArrowCandidate("b", "Ben", T.south(T.origin, 120.0), -120.0)
    private val noPos = ArrowCandidate("c", "Cy", null, null)

    @Test fun partnerTargetIsTheOtherPerson() {
        val p = ArrowPicker.pick(ArrowTarget.Partner, listOf(asha), null)!!
        assertEquals(ArrowTarget.Partner, p.target); assertEquals("Asha", p.name)
    }

    @Test fun behindTargetIsTheFurthestBehind() {
        val p = ArrowPicker.pick(ArrowTarget.Behind, listOf(asha, ben, noPos), null)!!
        assertEquals(ArrowTarget.Behind, p.target); assertEquals("Ben", p.name)
    }

    @Test fun pinTargetFallsBackWhenThereIsNoPin() {
        assertEquals(ArrowTarget.Pin, ArrowPicker.pick(ArrowTarget.Pin, listOf(asha), pin)!!.target)
        assertEquals(ArrowTarget.Partner, ArrowPicker.pick(ArrowTarget.Pin, listOf(asha), null)!!.target)
        assertNull(ArrowPicker.pick(ArrowTarget.Partner, listOf(noPos), null))
        assertEquals(ArrowTarget.Pin, ArrowPicker.pick(ArrowTarget.Partner, listOf(noPos), pin)!!.target)
    }

    @Test fun availableTargetsOnlyListWhatExists() {
        assertEquals(emptyList<ArrowTarget>(), ArrowPicker.available(listOf(noPos), null, false))
        assertEquals(listOf(ArrowTarget.Partner, ArrowTarget.Pin), ArrowPicker.available(listOf(asha), pin, false))
        assertEquals(listOf(ArrowTarget.Behind, ArrowTarget.Partner, ArrowTarget.Pin), ArrowPicker.available(listOf(asha, ben), pin, true))
        assertNotNull(ArrowPicker.pick(ArrowTarget.Behind, listOf(asha), null)) // no alongM spread: still points somewhere
    }
}
