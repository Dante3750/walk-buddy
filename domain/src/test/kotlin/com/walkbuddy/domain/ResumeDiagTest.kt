package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeDiagTest {
    private val partner = ResumeState(
        kind = ResumeKind.Partner, startMs = 1_000_000L, savedMs = 1_000_000L + 600_000L, code = "K7M2QX", key = "sess-key-abcdef", nickname = "Asha",
        distanceM = 812.5, verifiedSteps = 1034, rawSteps = 1050, route = "_p~iF~ps|U", pinLat = 12.97, pinLon = 77.59, pinLabel = "Chai",
    )

    // ---------- resume ----------

    @Test fun stateRoundTripsThroughText() {
        val back = ResumeCodec.decode(ResumeCodec.encode(partner))
        assertEquals(partner, back)
    }

    @Test fun decodeNeverThrowsOnGarbage() {
        for (bad in listOf(null, "", "   ", "not json", "[]", "{}", """{"v":2,"kind":"Solo","start":1,"saved":2}""", """{"v":1,"kind":"Nope","start":1,"saved":2}""", """{"v":1,"kind":"Solo"}""", "x".repeat(50_000))) {
            assertNull("input: ${bad?.take(20)}", ResumeCodec.decode(bad))
        }
    }

    @Test fun decodeClampsNegativeAndNonsenseNumbers() {
        val s = ResumeCodec.decode("""{"v":1,"kind":"Solo","start":10,"saved":20,"dist":-5,"steps":-3,"raw":-1}""")
        assertNotNull(s)
        assertEquals(0.0, s!!.distanceM, 0.0)
        assertEquals(0L, s.verifiedSteps)
        assertEquals(0L, s.rawSteps)
    }

    @Test fun offersAFreshWalk() {
        val d = ResumePolicy.decide(partner, partner.savedMs + 60_000, sessionActive = false)
        assertTrue(d is ResumeDecision.Offer)
    }

    @Test fun neverOffersWhileASessionIsRunning() {
        assertEquals(ResumeDecision.None, ResumePolicy.decide(partner, partner.savedMs + 1000, sessionActive = true))
        assertEquals(ResumeDecision.None, ResumePolicy.decide(null, 5, sessionActive = false))
    }

    @Test fun discardsOldShortAndIncompleteSnapshots() {
        val old = ResumePolicy.decide(partner, partner.savedMs + ResumePolicy.MAX_AGE_MS + 1, false)
        assertEquals("old", (old as ResumeDecision.Discard).reason)
        val short = partner.copy(savedMs = partner.startMs + 10_000)
        assertEquals("short", (ResumePolicy.decide(short, short.savedMs + 1000, false) as ResumeDecision.Discard).reason)
        val noCode = partner.copy(code = "oops")
        assertEquals("code", (ResumePolicy.decide(noCode, partner.savedMs + 1000, false) as ResumeDecision.Discard).reason)
        val group = partner.copy(kind = ResumeKind.Group, key = null)
        assertEquals("key", (ResumePolicy.decide(group, partner.savedMs + 1000, false) as ResumeDecision.Discard).reason)
        val future = partner.copy(savedMs = 10_000_000_000L)
        assertEquals("clock", (ResumePolicy.decide(future, 1_000L, false) as ResumeDecision.Discard).reason)
    }

    @Test fun soloNeedsNoCode() {
        val solo = ResumeState(ResumeKind.Solo, 0, 120_000)
        assertTrue(ResumePolicy.decide(solo, 130_000, false) is ResumeDecision.Offer)
    }

    @Test fun saveCadence() {
        assertTrue(ResumePolicy.shouldSave(0, 0.0, 25_000, 0.0))
        assertFalse(ResumePolicy.shouldSave(0, 0.0, 10_000, 10.0))
        assertTrue(ResumePolicy.shouldSave(0, 0.0, 10_000, 100.0))
        assertTrue(ResumePolicy.shouldSave(50_000, 0.0, 10_000, 0.0)) // clock went backwards
    }

    @Test fun routeIsCompactedAndEncoded() {
        val pts = (0 until 2000).map { T.north(T.origin, it * 2.0) }
        val enc = ResumePolicy.compactRoute(pts)
        assertNotNull(enc)
        assertTrue(Polyline.decode(enc).size <= ResumePolicy.MAX_ROUTE_POINTS)
        assertNull(ResumePolicy.compactRoute(listOf(T.origin)))
    }

    @Test fun carryAddsTheEarlierPartToTheSummary() {
        val s = WalkSummary(600_000, 500.0, 600, 580, 1.0, 1, 90, 120_000, 5, 0, 1, null)
        val c = ResumePolicy.carryInto(s, partner)
        assertEquals(1_200_000L, c.durationMs)
        assertEquals(1312.5, c.distanceM, 0.001)
        assertEquals(580L + 1034, c.verifiedSteps)
        assertEquals(600L + 1050, c.rawSteps)
        assertTrue(c.avgSpeedMps!! > 1.0)
    }

    // ---------- diagnostics ----------

    @Test fun redactsCoordinatesCodesIdsLinksAndEmails() {
        val raw = "fix at 12.971612,77.594601 code K7M2QX peer p0123456789ab link walkbuddy://join/K7M2QX mail a.b@example.com ip 10.1.2.3 key=abcdef123"
        val r = LogRedactor.redact(raw)
        for (secret in listOf("12.9716", "77.5946", "K7M2QX", "p0123456789ab", "walkbuddy://", "example.com", "10.1.2.3", "abcdef123")) assertFalse("$secret in: $r", r.contains(secret))
        assertTrue(r.contains("fix at"))
    }

    @Test fun redactsLongKeysAndUuids() {
        val r = LogRedactor.redact("session 3f2504e0-4f89-11d3-9a0c-0305e82c3301 and AbCdEfGhIjKlMnOpQrStUvWx12 end")
        assertFalse(r.contains("3f2504e0"))
        assertFalse(r.contains("AbCdEfGhIjKlMnOpQrStUvWx12"))
        assertTrue(r.endsWith("end"))
    }

    @Test fun keepsOrdinaryText() {
        assertEquals("walk started, steps 120, link direct", LogRedactor.redact("walk started, steps 120, link direct"))
    }

    @Test fun ringLogCapsLinesBytesAndLineLength() {
        val log = RingLog(maxLines = 5, maxBytes = 10_000, maxLineLen = 30)
        repeat(20) { log.add(it * 1000L, LogLevel.Info, "t", "line number $it " + "x".repeat(100)) }
        assertEquals(5, log.size())
        assertTrue(log.snapshot().all { it.length < 80 })
        assertTrue(log.snapshot().last().contains("line number 19"))
        val small = RingLog(maxLines = 1000, maxBytes = 300, maxLineLen = 100)
        repeat(50) { small.add(0, LogLevel.Warn, "t", "a message of some length $it") }
        assertTrue(small.dump().length <= 400)
    }

    @Test fun ringLogRedactsOnWriteAndOnLoad() {
        val log = RingLog()
        log.add(0, LogLevel.Error, "net", "joined K7M2QX at 12.971612")
        assertFalse(log.dump().contains("K7M2QX"))
        val again = RingLog()
        again.load("0.00:00:00 I/x: old line with code K7M2QX and 77.594601")
        assertFalse(again.dump().contains("K7M2QX"))
        assertFalse(again.dump().contains("77.5946"))
    }

    @Test fun exportHasHeaderAndRedactedFacts() {
        val log = RingLog()
        log.add(1000, LogLevel.Info, "app", "hello")
        val text = log.export(listOf("Version 2.0.0", "Server https://example.org/health"))
        assertTrue(text.startsWith("Walk Buddy diagnostics"))
        assertTrue(text.contains("Version 2.0.0"))
        assertFalse(text.contains("example.org"))
        assertTrue(text.contains("I/app: hello"))
    }
}
