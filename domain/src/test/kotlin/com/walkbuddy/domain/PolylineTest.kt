package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {
    @Test fun matchesGoogleReferenceExample() {
        // The worked example from Google's encoded polyline documentation.
        val pts = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(pts))
        val back = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, back.size)
        assertEquals(43.252, back[2].lat, 1e-5); assertEquals(-126.453, back[2].lon, 1e-5)
    }

    @Test fun roundTripsARouteWithinAMetre() {
        val pts = (0..300).map { LatLon(12.9716 + it * 0.00009, 77.5946 + Math.sin(it / 10.0) * 0.0004) }
        val back = Polyline.decode(Polyline.encode(pts))
        assertEquals(pts.size, back.size)
        for (i in pts.indices) assertTrue(Geo.haversine(pts[i], back[i]) < 1.5)
    }

    @Test fun damagedTextGivesWhatDecodedCleanly() {
        assertEquals(emptyList<LatLon>(), Polyline.decode(null))
        assertEquals(emptyList<LatLon>(), Polyline.decode(""))
        val ok = Polyline.encode(listOf(LatLon(1.0, 1.0), LatLon(1.001, 1.001)))
        assertEquals(1, Polyline.decode(ok.dropLast(1)).size) // truncated: the second point is lost, the first survives
        Polyline.decode("\u0000\u0001 !!!! ~~~~")
    }

    @Test fun simplifyKeepsTheShapeAndEndsAndHandlesLongTracks() {
        val straight = (0..1000).map { LatLon(12.9 + it * 0.00001, 77.5) }
        val s = Polyline.simplify(straight, 3.0)
        assertEquals(2, s.size)
        assertEquals(straight.first(), s.first()); assertEquals(straight.last(), s.last())
        val corner = (0..100).map { LatLon(12.9 + it * 0.00005, 77.5) } + (1..100).map { LatLon(12.9 + 100 * 0.00005, 77.5 + it * 0.00005) }
        val c = Polyline.simplify(corner, 3.0)
        assertTrue(c.size in 3..5)
        val big = (0..30_000).map { LatLon(12.9 + it * 0.000001, 77.5 + Math.sin(it / 40.0) * 0.0003) }
        val b = Polyline.simplify(big, 5.0, maxPoints = 800)
        assertTrue("${b.size}", b.size <= 800)
        assertEquals(2, Polyline.simplify(listOf(LatLon(0.0, 0.0), LatLon(1.0, 1.0))).size)
        assertTrue(Polyline.lengthM(b) > 0)
    }
}
