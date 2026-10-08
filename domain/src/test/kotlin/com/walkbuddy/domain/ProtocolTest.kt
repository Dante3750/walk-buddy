package com.walkbuddy.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun generatedCodesAreSixValidChars() {
        val r = Random(42)
        repeat(200) {
            val c = SessionCode.generate(r)
            assertEquals(6, c.length)
            assertTrue(c.all { it in SessionCode.ALPHABET })
        }
    }

    @Test fun alphabetHasNoLookAlikes() {
        for (ch in "01OI") assertTrue(ch !in SessionCode.ALPHABET)
        assertEquals(32, SessionCode.ALPHABET.length)
    }

    @Test fun normalizeAcceptsSpacesDashesAndLowercase() {
        assertEquals("ABC234", SessionCode.normalize(" abc-234 "))
        assertEquals("ABC234", SessionCode.normalize("ab c2 34"))
    }

    @Test fun normalizeRejectsBadCodes() {
        assertNull(SessionCode.normalize(null))
        assertNull(SessionCode.normalize("ABC12"))
        assertNull(SessionCode.normalize("ABC1234"))
        assertNull(SessionCode.normalize("ABCO34")) // O not allowed
        assertNull(SessionCode.normalize("ABC!34"))
    }

    @Test fun joinLinkRoundTripHasNoServer() {
        val link = JoinLink.build("K7M2QX")
        assertEquals("walkbuddy://join/K7M2QX", link)
        val t = JoinLink.parse(link)!!
        assertEquals("K7M2QX", t.code)
        assertNull(t.serverUrl)
        assertNull("a server in the link is ignored", JoinLink.parse("walkbuddy://join/K7M2QX?s=wss%3A%2F%2Fevil.example")!!.serverUrl)
    }

    @Test fun joinLinkWithoutServerAndBareCode() {
        assertEquals(JoinTarget("K7M2QX", null), JoinLink.parse("walkbuddy://join/K7M2QX"))
        assertEquals(JoinTarget("K7M2QX", null), JoinLink.parse("k7m-2qx"))
        assertEquals(JoinTarget("K7M2QX", null), JoinLink.parse("walkbuddy://join/k7m2qx/"))
    }

    @Test fun joinLinkRejectsGarbageAndUnsafeServers() {
        assertNull(JoinLink.parse("https://evil.example/join/K7M2QX"))
        assertNull(JoinLink.parse("walkbuddy://join/SHORT"))
        assertNull(JoinLink.parse(""))
        val t = JoinLink.parse("walkbuddy://join/K7M2QX?s=javascript%3Aalert(1)")!!
        assertNull("server hints are never honoured", t.serverUrl)
    }

    private fun roundTrip(m: PeerMessage): PeerMessage = (MessageCodec.decode(MessageCodec.encode(m)) as DecodeResult.Ok).message

    @Test fun allMessagesRoundTrip() {
        val pos = PeerMessage.Position(1_700_000_000_000, 12.97, 77.59, 6.5, 1.4, 1200, 112.0, 830.0)
        assertEquals(pos, roundTrip(pos))
        val minimal = PeerMessage.Position(5, 0.0, 0.0, null, null, 0, null, null)
        assertEquals(minimal, roundTrip(minimal))
        assertEquals(PeerMessage.Hello("peer-1", "Asha"), roundTrip(PeerMessage.Hello("peer-1", "Asha")))
        assertEquals(PeerMessage.Ping(99), roundTrip(PeerMessage.Ping(99)))
        assertEquals(PeerMessage.Spot("Lake loop", 12.9, 77.6), roundTrip(PeerMessage.Spot("Lake loop", 12.9, 77.6)))
        assertEquals(PeerMessage.Bye, roundTrip(PeerMessage.Bye))
    }

    @Test fun encodingCarriesVersionAndType() {
        val s = MessageCodec.encode(PeerMessage.Bye)
        assertTrue(s.contains("\"v\":1"))
        assertTrue(s.contains("\"t\":\"bye\""))
    }

    @Test fun decodeIsTolerantOfExtrasStringsAndNewerVersions() {
        val r = MessageCodec.decode("""{"v":7,"t":"pos","ts":"1700","lat":"12.5","lon":77.5,"steps":"10","future":{"a":1},"cad":null}""")
        val m = (r as DecodeResult.Ok).message as PeerMessage.Position
        assertEquals(12.5, m.lat, 1e-9)
        assertEquals(10, m.steps)
        assertNull(m.cadenceSpm)
    }

    @Test fun unknownTypeIsForwardCompatible() {
        val r = MessageCodec.decode("""{"v":2,"t":"reaction","emoji":"x"}""")
        assertEquals(PeerMessage.Unknown("reaction"), (r as DecodeResult.Ok).message)
    }

    private fun rejected(s: String?) = assertTrue("expected rejection for $s", MessageCodec.decode(s) is DecodeResult.Rejected)

    @Test fun invalidMessagesAreRejected() {
        rejected(null)
        rejected("")
        rejected("not json")
        rejected("[1,2,3]")
        rejected("""{"t":"bye"}""") // no version
        rejected("""{"v":0,"t":"bye"}""")
        rejected("""{"v":1}""")
        rejected("""{"v":1,"t":"pos","ts":1,"lat":91,"lon":0}""")
        rejected("""{"v":1,"t":"pos","ts":1,"lat":0,"lon":181}""")
        rejected("""{"v":1,"t":"pos","ts":1,"lat":"NaN","lon":0}""")
        rejected("""{"v":1,"t":"pos","lat":1,"lon":0}""") // no time
        rejected("""{"v":1,"t":"pos","ts":1,"lat":1,"lon":0,"steps":-5}""")
        rejected("""{"v":1,"t":"pos","ts":1,"lat":1,"lon":0,"steps":99999999}""")
        rejected("""{"v":1,"t":"hello","id":"bad id!","name":"x"}""")
        rejected("""{"v":1,"t":"spot","name":"","lat":1,"lon":1}""")
        rejected("x".repeat(MessageCodec.MAX_CHARS + 1))
    }

    @Test fun outOfRangeOptionalFieldsAreDroppedNotFatal() {
        val m = (MessageCodec.decode("""{"v":1,"t":"pos","ts":1,"lat":1,"lon":1,"spd":900,"cad":-4,"acc":-1}""") as DecodeResult.Ok).message as PeerMessage.Position
        assertNull(m.speedMps); assertNull(m.cadenceSpm); assertNull(m.accuracyM)
    }

    @Test fun namesAreSanitized() {
        val m = (MessageCodec.decode("""{"v":1,"t":"hello","id":"p1","name":"  Ri\u0007ya  ${"z".repeat(50)}"}""") as DecodeResult.Ok).message as PeerMessage.Hello
        assertTrue(m.name.length <= MessageCodec.MAX_NAME)
        assertTrue(m.name.none { it.isISOControl() })
        assertTrue(m.name.startsWith("Riya"))
        val fallback = (MessageCodec.decode("""{"v":1,"t":"hello","id":"p1"}""") as DecodeResult.Ok).message as PeerMessage.Hello
        assertEquals("Buddy", fallback.name)
    }

    @Test fun signalingRoundTrips() {
        val msgs = listOf(
            SignalingMessage.Join("K7M2QX", "p1"),
            SignalingMessage.Joined("p1", listOf("p2", "p3")),
            SignalingMessage.PeerJoined("p2"),
            SignalingMessage.PeerLeft("p2"),
            SignalingMessage.Relay("offer", "p1", "p2", "v=0..."),
            SignalingMessage.Relay("ice", null, "p2", "{\"candidate\":\"x\"}"),
            SignalingMessage.Error("room_full"),
        )
        for (m in msgs) assertEquals(m, SignalingCodec.decode(SignalingCodec.encode(m)))
    }

    @Test fun signalingRejectsBadInput() {
        assertNull(SignalingCodec.decode("nope"))
        assertNull(SignalingCodec.decode("""{"t":"join","code":"bad","peer":"p"}"""))
        assertNull(SignalingCodec.decode("""{"t":"offer","to":"p2"}"""))
        assertNull(SignalingCodec.decode("""{"t":"mystery"}"""))
        assertNotNull(SignalingCodec.decode("""{"t":"error"}"""))
    }
}
