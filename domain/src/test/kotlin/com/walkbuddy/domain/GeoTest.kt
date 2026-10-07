package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun haversineKnownDistances() {
        val london = LatLon(51.5074, -0.1278); val paris = LatLon(48.8566, 2.3522)
        assertEquals(343_500.0, Geo.haversine(london, paris), 3_500.0)
        assertEquals(0.0, Geo.haversine(london, london), 1e-6)
        assertEquals(111_195.0, Geo.haversine(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 200.0)
    }

    @Test fun bearingsAreCompassDirections() {
        val o = T.origin
        assertEquals(0.0, Geo.bearingDeg(o, T.north(o, 100.0)), 0.5)
        assertEquals(90.0, Geo.bearingDeg(o, T.east(o, 100.0)), 0.5)
        assertEquals(180.0, Geo.bearingDeg(o, T.south(o, 100.0)), 0.5)
    }

    @Test fun aheadAndBehindAlongDirectionOfTravel() {
        val me = T.origin
        assertEquals(100.0, Geo.alongTrackM(me, 0.0, T.north(me, 100.0)), 0.5)
        assertEquals(-100.0, Geo.alongTrackM(me, 0.0, T.south(me, 100.0)), 0.5)
        assertEquals(0.0, Geo.alongTrackM(me, 0.0, T.east(me, 100.0)), 1.0) // beside
        // Heading east: a point to the east is ahead.
        assertEquals(60.0, Geo.alongTrackM(me, 90.0, T.east(me, 60.0)), 0.5)
    }

    @Test fun spreadIsLargestPairwiseDistance() {
        val a = T.origin; val b = T.north(a, 30.0); val c = T.north(a, 80.0)
        assertEquals(80.0, Geo.spreadM(listOf(a, b, c))!!, 0.5)
        assertNull(Geo.spreadM(listOf(a)))
    }

    @Test fun filterDropsLowAccuracyOutOfOrderAndInvalid() {
        val f = FixFilter()
        val p = T.origin
        assertEquals(FixVerdict.Accepted, f.accept(Fix(1000, p.lat, p.lon, 5.0)))
        assertEquals(FixVerdict.LowAccuracy, f.accept(Fix(2000, p.lat, p.lon, 80.0)))
        assertEquals(FixVerdict.OutOfOrder, f.accept(Fix(500, p.lat, p.lon, 5.0)))
        assertEquals(FixVerdict.Invalid, f.accept(Fix(3000, 123.0, 0.0, 5.0)))
        assertEquals(FixVerdict.Accepted, f.accept(Fix(4000, p.lat, p.lon, null)))
    }

    @Test fun filterDropsTeleportsButReanchorsIfItPersists() {
        val f = FixFilter(reanchorAfter = 3)
        val p = T.origin; val far = T.north(p, 5_000.0)
        assertEquals(FixVerdict.Accepted, f.accept(Fix(0, p.lat, p.lon, 5.0)))
        assertEquals(FixVerdict.ImpossibleSpeed, f.accept(Fix(1000, far.lat, far.lon, 5.0)))
        assertEquals(FixVerdict.ImpossibleSpeed, f.accept(Fix(2000, far.lat, far.lon, 5.0)))
        assertEquals(FixVerdict.Accepted, f.accept(Fix(3000, far.lat, far.lon, 5.0))) // re-anchored
        assertEquals(FixVerdict.Accepted, f.accept(Fix(4000, far.lat, far.lon, 5.0)))
    }

    @Test fun distanceTrackerIgnoresStationaryJitter() {
        val d = DistanceTracker()
        val rnd = java.util.Random(7)
        for (i in 0 until 120) {
            val p = T.east(T.north(T.origin, rnd.nextGaussian() * 1.5), rnd.nextGaussian() * 1.5)
            d.add(Fix(i * 1000L, p.lat, p.lon, 8.0))
        }
        assertTrue("jitter must not add up: ${d.totalM}", d.totalM < 30.0)
    }

    @Test fun distanceTrackerSumsRealMovement() {
        val d = DistanceTracker()
        for (i in 0..100) {
            val p = T.north(T.origin, i * 1.4)
            d.add(Fix(i * 1000L, p.lat, p.lon, 5.0))
        }
        assertEquals(140.0, d.totalM, 6.0)
    }

    @Test fun distanceTrackerSkipsBadFixes() {
        val d = DistanceTracker()
        d.add(Fix(0, T.origin.lat, T.origin.lon, 5.0))
        val far = T.north(T.origin, 2000.0)
        assertEquals(FixVerdict.ImpossibleSpeed, d.add(Fix(1000, far.lat, far.lon, 5.0)))
        assertEquals(0.0, d.totalM, 0.001)
        assertNotNull(d.lastAccepted)
    }

    @Test fun headingTrackerNeedsRealMovement() {
        val h = HeadingTracker()
        h.update(T.origin)
        h.update(T.north(T.origin, 3.0))
        assertNull(h.headingDeg)
        h.update(T.north(T.origin, 12.0))
        assertEquals(0.0, h.headingDeg!!, 1.0)
        assertFalse(h.headingDeg!!.isNaN())
    }
}
