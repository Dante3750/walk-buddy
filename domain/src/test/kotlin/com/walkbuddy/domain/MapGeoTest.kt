package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGeoTest {
    private val vp = MapViewport(T.origin, 16.0, 800, 600)

    @Test fun mercatorRoundTripsAndKnownValues() {
        assertEquals(0.0, WebMercator.x(-180.0, 0.0), 1e-9)
        assertEquals(256.0, WebMercator.x(180.0, 0.0), 1e-9)
        assertEquals(128.0, WebMercator.y(0.0, 0.0), 1e-9)
        for (p in listOf(LatLon(12.97, 77.59), LatLon(-33.86, 151.2), LatLon(64.1, -21.9))) {
            val z = 14.3
            assertEquals(p.lat, WebMercator.lat(WebMercator.y(p.lat, z), z), 1e-9)
            assertEquals(p.lon, WebMercator.lon(WebMercator.x(p.lon, z), z), 1e-9)
        }
        assertEquals(156543.03, WebMercator.metersPerPixel(0.0, 0.0), 1.0)
    }

    @Test fun viewportCentreIsTheMiddleOfTheCanvasAndRoundTrips() {
        val c = vp.toScreen(vp.center)
        assertEquals(400.0, c.x, 1e-6); assertEquals(300.0, c.y, 1e-6)
        val p = T.east(T.north(T.origin, 120.0), 80.0)
        val s = vp.toScreen(p)
        val back = vp.fromScreen(s.x, s.y)
        assertTrue(Geo.haversine(p, back) < 0.01)
        assertTrue(s.x > 400 && s.y < 300) // north-east of centre is up and to the right
    }

    @Test fun metresPerPixelMatchesScreenDistance() {
        val p = T.east(T.origin, 100.0)
        val a = vp.toScreen(T.origin); val b = vp.toScreen(p)
        assertEquals(100.0, Math.abs(b.x - a.x) * vp.metersPerPixel(), 1.0)
    }

    @Test fun panMovesTheMapWithTheFinger() {
        val moved = vp.panBy(100.0, 0.0) // finger drags right: the map follows, so the centre moves west
        assertTrue(moved.center.lon < vp.center.lon)
        val before = vp.toScreen(T.origin).x
        val after = moved.toScreen(T.origin).x
        assertEquals(100.0, after - before, 0.01)
    }

    @Test fun zoomKeepsThePointUnderTheFingerStill() {
        val anchor = vp.fromScreen(650.0, 120.0)
        val z = vp.zoomBy(2.0, 650.0, 120.0)
        assertEquals(17.0, z.zoom, 1e-9)
        val s = z.toScreen(anchor)
        assertEquals(650.0, s.x, 0.01); assertEquals(120.0, s.y, 0.01)
        assertEquals(19.0, vp.zoomBy(1e9).zoom, 0.0)
        assertEquals(2.0, vp.zoomBy(1e-9).zoom, 0.0)
        assertEquals(vp, vp.zoomBy(0.0))
    }

    @Test fun fitShowsEveryPointInsideThePadding() {
        val pts = listOf(T.origin, T.north(T.origin, 900.0), T.east(T.origin, 600.0), T.south(T.origin, 300.0))
        val f = MapViewport.fit(pts, 400, 700, paddingPx = 40)
        for (p in pts) {
            val s = f.toScreen(p)
            assertTrue("x ${s.x}", s.x in 39.0..361.0)
            assertTrue("y ${s.y}", s.y in 39.0..661.0)
        }
        // and it is as zoomed in as it can be: one more zoom level would clip something
        val tighter = f.copy(zoom = f.zoom + 1)
        assertTrue(pts.any { val s = tighter.toScreen(it); s.x !in 40.0..360.0 || s.y !in 40.0..660.0 })
    }

    @Test fun fitOnOnePointOrNoPointsIsSensible() {
        val one = MapViewport.fit(listOf(T.origin), 400, 600)
        assertTrue(one.zoom in 15.0..19.0) // about 150 m across, not a blur
        assertEquals(one.center, T.origin)
        val none = MapViewport.fit(emptyList(), 400, 600, fallback = LatLon(1.0, 2.0))
        assertEquals(LatLon(1.0, 2.0), none.center)
        val tiny = MapViewport.fit(listOf(T.origin, T.north(T.origin, 3.0)), 400, 600)
        assertEquals(one.zoom, tiny.zoom, 0.5)
    }

    @Test fun niceScaleBarNumbers() {
        assertEquals(1.0, ScaleBar.niceMeters(1.4), 0.0)
        assertEquals(2.0, ScaleBar.niceMeters(2.9), 0.0)
        assertEquals(5.0, ScaleBar.niceMeters(7.0), 0.0)
        assertEquals(100.0, ScaleBar.niceMeters(100.0), 0.0)
        assertEquals(200.0, ScaleBar.niceMeters(380.0), 0.0)
        assertEquals(500.0, ScaleBar.niceMeters(999.0), 0.0)
        assertEquals(1000.0, ScaleBar.niceMeters(1000.0), 0.0)
        assertEquals(0.5, ScaleBar.niceMeters(0.7), 0.0)
        assertEquals(1.0, ScaleBar.niceMeters(-3.0), 0.0)
        assertEquals("200 m", ScaleBar.label(200.0))
        assertEquals("1 km", ScaleBar.label(1000.0))
        assertEquals("2.5 km", ScaleBar.label(2500.0))
    }

    @Test fun scaleBarFitsAndIsAccurate() {
        for (z in listOf(5.0, 10.0, 14.5, 16.0, 18.7)) {
            val spec = ScaleBar.forViewport(vp.copy(zoom = z), 120.0)
            assertTrue("z=$z len=${spec.lengthPx}", spec.lengthPx in 30.0..120.0)
            assertEquals(spec.meters, spec.lengthPx * vp.copy(zoom = z).metersPerPixel(), 1e-6)
        }
        val imp = ScaleBar.forViewport(vp, 120.0, imperial = true)
        assertTrue(imp.label.endsWith("ft") || imp.label.endsWith("mi"))
        assertTrue(imp.lengthPx <= 120.0)
    }

    @Test fun trailsSkipJitterAndAreCapped() {
        val t = TrailBuffer(minMoveM = 5.0, maxPoints = 10)
        assertTrue(t.add(T.origin))
        assertFalse(t.add(T.north(T.origin, 2.0)))
        assertTrue(t.add(T.north(T.origin, 8.0)))
        assertFalse(t.add(LatLon(200.0, 0.0)))
        for (i in 1..30) t.add(T.north(T.origin, 10.0 + i * 10.0))
        assertEquals(10, t.size)
        assertEquals(T.north(T.origin, 310.0).lat, t.points.last().lat, 1e-9)
    }

    @Test fun trailBookKeepsOnePerPerson() {
        val b = TrailBook()
        b.add("a", T.origin); b.add("a", T.north(T.origin, 20.0)); b.add("b", T.origin)
        assertEquals(setOf("a", "b"), b.snapshot().keys)
        assertEquals(2, b.snapshot().getValue("a").size)
        b.remove("a"); assertNull(b.snapshot()["a"])
        b.clear(); assertTrue(b.snapshot().isEmpty())
    }

    @Test fun routeRecorderBuildsAGpxFile() {
        val r = RouteRecorder(minMoveM = 8.0)
        r.add(1_700_000_000_000, T.origin)
        r.add(1_700_000_001_000, T.north(T.origin, 3.0))            // too close
        r.add(1_700_000_002_000, T.north(T.origin, 50.0), 90.0)     // too inaccurate
        r.add(1_700_000_003_000, T.north(T.origin, 50.0), 6.0)
        assertEquals(2, r.size)
        assertEquals(50.0, r.lengthM(), 1.0)
        val gpx = Gpx.build("Evening <walk> & \"co\"", r.points)
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<name>Evening &lt;walk&gt; &amp; &quot;co&quot;</name>"))
        assertTrue(gpx.contains("<trkpt lat=\"12.971600\" lon=\"77.594600\"><time>2023-11-14T22:13:20Z</time></trkpt>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test fun tilesCoverTheViewportAndWrapTheirIndexes() {
        val tiles = SlippyTiles.tilesFor(vp)
        assertTrue(tiles.size in 4..SlippyTiles.MAX_TILES)
        val z = SlippyTiles.tileZoom(vp.zoom)
        assertTrue(tiles.all { it.z == z && it.x in 0 until (1 shl z) && it.y in 0 until (1 shl z) })
        assertEquals(tiles.size, tiles.map { it.x to it.y }.toSet().size)
        assertTrue(tiles.minOf { it.screenX } <= 0.0 && tiles.maxOf { it.screenX + it.sizePx } >= 800.0)
        assertTrue(tiles.minOf { it.screenY } <= 0.0 && tiles.maxOf { it.screenY + it.sizePx } >= 600.0)
        // the tile that holds the centre sits where the centre is
        val centre = tiles.first { it.screenX <= 400 && it.screenX + it.sizePx > 400 && it.screenY <= 300 && it.screenY + it.sizePx > 300 }
        val tx = Math.floor(WebMercator.x(T.origin.lon, z.toDouble()) / 256.0).toInt()
        assertEquals(tx, centre.x)
        val wrap = SlippyTiles.tilesFor(MapViewport(LatLon(0.0, 179.999), 5.0, 800, 600))
        assertTrue(wrap.any { it.x == 0 } && wrap.any { it.x == 31 })
        assertTrue(SlippyTiles.tilesFor(MapViewport(LatLon(0.0, 0.0), 19.0, 4000, 4000)).size <= SlippyTiles.MAX_TILES)
    }

    @Test fun tileUrlAndAttribution() {
        assertEquals("https://tile.openstreetmap.org/16/23456/15123.png", SlippyTiles.url(16, 23456, 15123))
        assertTrue(SlippyTiles.ATTRIBUTION.contains("OpenStreetMap"))
        assertEquals(16, SlippyTiles.tileZoom(16.2)); assertEquals(17, SlippyTiles.tileZoom(16.5)); assertEquals(19, SlippyTiles.tileZoom(30.0))
    }

    @Test fun cameraFollowsMeFitsTheGroupOrLeavesYouAlone() {
        val me = T.origin; val others = listOf(T.north(T.origin, 500.0), T.east(T.origin, 400.0))
        assertNull(MapCamera.target(MapFollow.Free, me, others, null, vp))
        assertEquals(me, MapCamera.target(MapFollow.Me, me, others, null, vp)!!.center)
        assertNull(MapCamera.target(MapFollow.Me, null, others, null, vp))
        val g = MapCamera.target(MapFollow.Group, me, others, T.south(T.origin, 200.0), vp)!!
        for (p in others + me) assertTrue(g.toScreen(p).let { it.x in 0.0..800.0 && it.y in 0.0..600.0 })
        assertNull(MapCamera.target(MapFollow.Group, null, emptyList(), null, vp))
        assertNotNull(MeetingPin(me, "Gate"))
    }
}
