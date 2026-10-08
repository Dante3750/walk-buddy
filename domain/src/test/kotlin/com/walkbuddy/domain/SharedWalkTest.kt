package com.walkbuddy.domain

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedWalkTest {
    private val utc = ZoneId.of("UTC")

    private fun lane(id: String, x: Double?, steps: Int = 0, dist: Double? = null, me: Boolean = false, av: Int? = null) =
        RecLane(id, if (me) "You" else id, av, x, steps, dist, me)

    private fun record(start: Long = 1_700_000_000_000L, dur: Long = 600_000, mode: SharedMode = SharedMode.Partner, title: String = "Sam", pct: Int = 80, dist: Double = 1000.0, id: Long = 0): SharedWalkRecord {
        val r = SharedWalkRecorder(start, mode)
        r.onFrame(start, listOf(lane("me", 0.0, 0, 0.0, true), lane("sam", 0.0, 0, 0.0)), 0.0)
        r.onFrame(start + dur, listOf(lane("me", dist, 1300, dist, true), lane("sam", dist - 20, 1250, dist - 20.0)), 20.0)
        return r.build(start + dur, title, 2, pct, 300_000, null, id = id)
    }

    // ---------- recorder ----------

    @Test fun recorderCollectsLanesSamplesAndTheLargestGap() {
        val start = 1_000_000L
        val r = SharedWalkRecorder(start, SharedMode.Partner, intervalMs = 10_000)
        r.onFrame(start, listOf(lane("sam", 0.0), lane("me", 0.0, me = true)), 5.0)
        r.onFrame(start + 4_000, listOf(lane("sam", 5.0), lane("me", 6.0, me = true)), 140.0) // too soon for a sample, but the gap counts
        r.onFrame(start + 12_000, listOf(lane("sam", 30.0, 20), lane("me", 40.0, 25, 40.0, true)), 10.0)
        val rec = r.build(start + 20_000, "  Sam  ", 2, 85, 12_000, null, lastFrame = listOf(lane("sam", 60.0, 50, 60.0), lane("me", 70.0, 55, 70.0, true)))
        assertEquals("me first", listOf("me", "sam"), rec.lanes.map { it.id })
        assertEquals(140.0, rec.maxGapM, 0.0)
        assertEquals("Sam", rec.title)
        assertEquals(3, rec.samples.size)
        assertEquals(listOf(0, 0), rec.samples[0].xs)
        assertEquals(listOf(40, 30), rec.samples[1].xs)
        assertEquals(20, rec.samples.last().tSec)
        assertEquals(55, rec.me!!.steps)
        assertEquals(70.0, rec.myDistanceM, 0.0)
        assertEquals(50, rec.othersSteps)
        assertEquals(20_000L, rec.durationMs)
        assertEquals(85, rec.togetherPct)
    }

    @Test fun laneThatAppearsLaterIsPaddedWithUnknownEarlier() {
        val r = SharedWalkRecorder(0, SharedMode.Group, intervalMs = 1_000)
        r.onFrame(0, listOf(lane("me", 0.0, me = true)), null)
        r.onFrame(2_000, listOf(lane("me", 5.0, me = true), lane("late", 2.0)), 3.0)
        val rec = r.build(3_000, "Club", 2, 50, 0, null)
        assertEquals(0, rec.samples[0].xs[0])
        assertEquals(2, rec.lanes.size)
        assertNull(rec.samples[0].xs[1])
        assertEquals(2, rec.samples[1].xs[1])
    }

    @Test fun recorderThinsOutOnVeryLongWalksAndCapsLanes() {
        val r = SharedWalkRecorder(0, SharedMode.Partner, intervalMs = 1_000, maxSamples = 50)
        for (i in 0..400) r.onFrame(i * 1_000L, listOf(lane("me", i.toDouble(), me = true)), null)
        assertTrue("samples ${r.sampleCount}", r.sampleCount <= 50)
        val rec = r.build(400_000, "", 1, 0, 0, null)
        assertTrue(rec.samples.map { it.tSec }.zipWithNext().all { (a, b) -> a < b })
        assertEquals(0, rec.samples.first().tSec)
        val many = SharedWalkRecorder(0, SharedMode.Group)
        many.onFrame(0, listOf(lane("me", 0.0, me = true)) + (1..20).map { lane("p$it", it.toDouble()) }, null)
        assertEquals(TrackLayout.MAX_LANES, many.laneCount)
        assertTrue("me is never dropped", many.build(1, "", 21, 0, 0, null).lanes[0].isMe)
    }

    @Test fun buildClampsBadValues() {
        val rec = SharedWalkRecorder(10_000, SharedMode.Partner).build(5_000, "x".repeat(100), 1, 250, -5, "")
        assertEquals(0L, rec.durationMs)
        assertEquals(100, rec.togetherPct)
        assertEquals(0L, rec.longestTogetherMs)
        assertEquals(40, rec.title.length)
        assertNull(rec.routePolyline)
    }

    // ---------- codec ----------

    @Test fun lanesAndSamplesRoundTrip() {
        val rec = record()
        val lanes = SharedWalkCodec.decodeLanes(SharedWalkCodec.encodeLanes(rec.lanes))
        assertEquals(rec.lanes.map { it.id to it.isMe }, lanes.map { it.id to it.isMe })
        assertEquals(rec.myDistanceM, lanes.first { it.isMe }.distanceM!!, 0.1)
        val withNulls = listOf(TrackSample(0, listOf(0, null)), TrackSample(15, listOf(12, 9)), TrackSample(30, listOf(null, null)))
        assertEquals(withNulls, SharedWalkCodec.decodeSamples(SharedWalkCodec.encodeSamples(withNulls)))
    }

    @Test fun damagedStoredTextNeverThrows() {
        for (bad in listOf(null, "", "not json", "{}", "[1,2", "[[\"a\"]]", "[{\"x\":1}]")) {
            SharedWalkCodec.decodeLanes(bad)
            SharedWalkCodec.decodeSamples(bad)
        }
        assertEquals(emptyList<TrackSample>(), SharedWalkCodec.decodeSamples("not json"))
        assertEquals(listOf(TrackSample(5, listOf(1))), SharedWalkCodec.decodeSamples("[[5,1],[\"x\"],{}]"))
    }

    // ---------- replay ----------

    @Test fun replayInterpolatesBetweenSamplesAndClampsAtTheEnds() {
        val rec = record().copy(samples = listOf(TrackSample(0, listOf(0, 10)), TrackSample(10, listOf(100, null)), TrackSample(20, listOf(200, 150))))
        assertEquals(listOf(0.0, 10.0), SharedReplay.positionsAt(rec, -5.0))
        assertEquals(50.0, SharedReplay.positionsAt(rec, 5.0)[0]!!, 1e-9)
        assertEquals("unknown at one end uses the known end by half", 10.0, SharedReplay.positionsAt(rec, 4.0)[1]!!, 1e-9)
        assertEquals(200.0, SharedReplay.positionsAt(rec, 99.0)[0]!!, 1e-9)
        assertEquals(listOf<Double?>(null, null), SharedReplay.positionsAt(rec.copy(samples = emptyList()), 3.0))
        val ws = SharedReplay.walkers(rec, 5.0)
        assertEquals("You", ws.first { it.isMe }.name)
        assertEquals(2, ws.size)
        assertTrue(SharedReplay.durationSec(rec) >= 20)
    }

    // ---------- history ----------

    @Test fun groupsByMonthNewestFirstWithTotals() {
        val oct1 = 1_759_320_000_000L // 2025-10-01 12:00 UTC
        val rs = listOf(
            record(start = oct1, dist = 2000.0, id = 1),
            record(start = oct1 + 5 * 86_400_000L, dist = 3000.0, id = 2),
            record(start = oct1 - 10 * 86_400_000L, dist = 1000.0, id = 3, title = "Mia"), // September
        )
        val g = SharedHistory.groupByMonth(rs, utc, Locale.US)
        assertEquals(listOf("October 2025", "September 2025"), g.map { it.title })
        assertEquals(listOf(2L, 1L), g[0].items.map { it.id })
        assertEquals(5000.0, g[0].distanceM, 0.0)
        val t = SharedHistory.totals(rs)
        assertEquals(3, t.count)
        assertEquals(6000.0, t.distanceM, 0.0)
        assertEquals(2, t.partners)
        assertEquals(80, t.avgTogetherPct)
        assertEquals(HistoryTotals(0, 0.0, 0, 0, 0), SharedHistory.totals(emptyList()))
    }

    @Test fun monthBoundaryFollowsTheTimeZone() {
        val r = record(start = 1_759_276_800_000L + 3_600_000L) // 2025-10-01 00:00 UTC + 1 h
        assertEquals("October 2025", SharedHistory.groupByMonth(listOf(r), utc, Locale.US)[0].title)
        assertEquals("September 2025", SharedHistory.groupByMonth(listOf(r), ZoneId.of("America/Los_Angeles"), Locale.US)[0].title)
    }

    @Test fun titlesAndSubtitles() {
        val p = record(title = "Sam", pct = 91, dist = 3200.0, dur = 42 * 60_000L)
        assertEquals("With Sam", SharedHistory.title(p))
        assertEquals("Walk together", SharedHistory.title(p.copy(title = " ")))
        assertEquals("Club (7 people)", SharedHistory.title(p.copy(mode = SharedMode.Group, title = "Club", memberCount = 7)))
        assertEquals("Group walk (3 people)", SharedHistory.title(p.copy(mode = SharedMode.Group, title = "", memberCount = 3)))
        val sub = SharedHistory.subtitle(p, UnitSystem.Metric)
        assertTrue(sub, sub.contains("3.20 km") && sub.contains("91% together"))
        assertEquals("Tue 14 Nov", SharedHistory.dayTitle(record(start = 1_700_000_000_000L), utc, Locale.US))
        assertEquals("0:05", SharedHistory.clock(5)); assertEquals("1:05:09", SharedHistory.clock(3909)); assertEquals("0:00", SharedHistory.clock(-4))
        assertEquals(SharedMode.Group, SharedMode.fromName("Group")); assertEquals(SharedMode.Partner, SharedMode.fromName("???"))
    }

    // ---------- export ----------

    @Test fun jsonExportHasEveryWalkAndTheReplay() {
        val rs = listOf(record(id = 1), record(start = 1_600_000_000_000L, id = 2))
        val j = SharedWalkExport.json(rs)
        val o = kotlinx.serialization.json.Json.parseToJsonElement(j) as kotlinx.serialization.json.JsonObject
        assertEquals("walk-buddy", (o["app"] as kotlinx.serialization.json.JsonPrimitive).content)
        val walks = o["walks"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, walks.size)
        assertTrue("sorted oldest first", ((walks[0] as kotlinx.serialization.json.JsonObject)["startMs"] as kotlinx.serialization.json.JsonPrimitive).content.toLong() < 1_700_000_000_000L)
        assertFalse(j.contains("routePolyline5"))
    }

    @Test fun gpxOnlyWhenARouteWasSaved() {
        val pts = listOf(LatLon(12.9716, 77.5946), LatLon(12.9726, 77.5956), LatLon(12.9736, 77.5966))
        val rec = record().copy(routePolyline = Polyline.encode(pts))
        val gpx = SharedWalkExport.gpx(rec)
        assertNotNull(gpx)
        assertEquals(3, Regex("<trkpt").findAll(gpx!!).count())
        assertTrue(gpx.contains("<gpx"))
        assertNull(SharedWalkExport.gpx(record()))
        assertNull(SharedWalkExport.gpx(record().copy(routePolyline = Polyline.encode(pts.take(1)))))
        assertEquals("walk-together-2023-11-14-2213.json", SharedWalkExport.fileName(record(start = 1_700_000_000_000L), utc, "json"))
    }
}
