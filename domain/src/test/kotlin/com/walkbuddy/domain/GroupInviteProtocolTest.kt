package com.walkbuddy.domain

import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteTest {
    @Test fun buildAndParseRoundTripWithoutServer() {
        val link = GroupLink.build("K7M2QX")
        assertEquals("walkbuddy://group/K7M2QX", link)
        assertEquals(Invite.Group("K7M2QX", null), GroupLink.parse(link))
    }

    @Test fun secureServerIsWrittenAsABareHost() {
        val link = GroupLink.build("K7M2QX", "wss://walk.example.org")
        assertEquals("walkbuddy://group/K7M2QX?s=walk.example.org", link)
        assertEquals(Invite.Group("K7M2QX", "wss://walk.example.org"), GroupLink.parse(link))
    }

    @Test fun plainServerKeepsItsSchemeAndPort() {
        val link = GroupLink.build("K7M2QX", "ws://192.168.1.5:8080")
        assertEquals("walkbuddy://group/K7M2QX?s=ws://192.168.1.5:8080", link)
        assertEquals("ws://192.168.1.5:8080", GroupLink.parse(link)?.serverUrl)
    }

    @Test fun oddServerAddressesAreEncodedAndStillRoundTrip() {
        val s = "wss://example.org/path/ws"
        val link = GroupLink.build("K7M2QX", s)
        assertTrue(link.contains("%2F"))
        assertEquals(s, GroupLink.parse(link)?.serverUrl)
    }

    @Test fun parseIsForgivingAboutCaseSpacesAndTrailingSlash() {
        assertEquals("K7M2QX", GroupLink.parse("  WalkBuddy://group/k7m2qx/ ")?.code)
        assertEquals("K7M2QX", GroupLink.parse("walkbuddy://group/K7M-2QX")?.code)
    }

    @Test fun webInviteLinkFromTheServerLandingPage() {
        assertEquals("https://walk.example.org/g/K7M2QX", GroupLink.webLink("K7M2QX", "wss://walk.example.org"))
        assertEquals("http://10.0.0.2:8080/g/K7M2QX", GroupLink.webLink("K7M2QX", "ws://10.0.0.2:8080"))
        assertNull(GroupLink.webLink("K7M2QX", "https://nope"))
        assertEquals(Invite.Group("K7M2QX", "wss://walk.example.org"), GroupLink.parse("https://walk.example.org/g/k7m2qx"))
        assertEquals(Invite.Group("K7M2QX", "ws://10.0.0.2:8080"), GroupLink.parse("http://10.0.0.2:8080/g/K7M2QX/"))
    }

    @Test fun rejectsGarbage() {
        for (bad in listOf(null, "", "K7M2QX", "walkbuddy://group/", "walkbuddy://group/ABC", "walkbuddy://group/K7M2Q0", "https://x.org/other/K7M2QX",
            "javascript:alert(1)", "walkbuddy://group/K7M2QX?s=evil host", "walkbuddy://group/K7M2QX?s=" + "a".repeat(300))) {
            val g = GroupLink.parse(bad)
            if (bad != null && bad.startsWith("walkbuddy://group/K7M2QX?s=")) assertNull("server must be dropped for $bad", g?.serverUrl)
            else assertNull("should reject $bad", g)
        }
    }

    @Test fun invitesRouteBetweenPartnerAndGroup() {
        assertTrue(Invites.parse("walkbuddy://group/K7M2QX") is Invite.Group)
        val p = Invites.parse(JoinLink.build("ABC234", "wss://s.example.org"))
        assertEquals(Invite.Partner("ABC234", "wss://s.example.org"), p)
        assertNull(Invites.parse("K7M2QX")) // a bare code is ambiguous
        assertEquals("K7M2QX", Invites.bareCode(" k7m-2qx "))
        assertNull(Invites.bareCode("hello"))
    }

    @Test fun linksFitInTheQrEncoderAndTheCodeIsReadableBack() {
        val link = GroupLink.build("K7M2QX", "wss://walk.example.org")
        assertTrue(GroupLink.fitsQr(link))
        val q = QrEncoder.encode(link)
        assertTrue(q.size in 21..37)
        // A very long server does not fit; the UI then shows the code only.
        assertFalse(GroupLink.fitsQr(GroupLink.build("K7M2QX", "wss://" + "a".repeat(60) + ".example.org/" + "b".repeat(20))))
    }

    @Test fun groupKeysAreLongRandomAndAlphanumeric() {
        val a = GroupKey.generate(Random(1)); val b = GroupKey.generate(Random(2))
        assertEquals(24, a.length)
        assertTrue(a.all { it.isLetterOrDigit() })
        assertTrue(a != b)
        assertEquals(8, GroupKey.generate(Random(3), 2).length) // never shorter than the server accepts
    }
}

class GroupProtocolTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun createAndJoinEncodeTheServerFields() {
        val c = obj(GroupCodec.encode(GroupClientMessage.Create("p1", "key-abcdefgh", "Asha", approval = true, ttlMin = 90, goalSteps = 20000, title = "Lake loop")))
        assertEquals("group-create", c["t"]!!.jsonPrimitive.content)
        assertEquals("true", c["approval"]!!.jsonPrimitive.content)
        assertEquals("90", c["ttlMin"]!!.jsonPrimitive.content)
        val j = obj(GroupCodec.encode(GroupClientMessage.Join("K7M2QX", "p2", "key-abcdefgh", "Ben")))
        assertEquals("group-join", j["t"]!!.jsonPrimitive.content)
        assertEquals("K7M2QX", j["code"]!!.jsonPrimitive.content)
    }

    @Test fun updateOmitsLocationWhenNotShared() {
        val withPos = obj(GroupCodec.encode(GroupClientMessage.Update(GroupUpdate(1_700_000_000_000, 12.9, 77.5, 8.0, 1.2, 340, 100.0, 1200.0))))
        val d = withPos["d"]!!.jsonObject
        assertEquals(setOf("ts", "lat", "lon", "acc", "spd", "steps", "cad", "dist"), d.keys)
        val noPos = obj(GroupCodec.encode(GroupClientMessage.Update(GroupUpdate(5L, null, null, steps = 42))))["d"]!!.jsonObject
        assertEquals(setOf("ts", "steps"), noPos.keys)
    }

    @Test fun hostMessages() {
        assertEquals("kick", obj(GroupCodec.encode(GroupClientMessage.Kick("x")))["t"]!!.jsonPrimitive.content)
        assertEquals("close-room", obj(GroupCodec.encode(GroupClientMessage.CloseRoom))["t"]!!.jsonPrimitive.content)
        val s = obj(GroupCodec.encode(GroupClientMessage.ChangeSettings(approval = false, goalSteps = 5000)))
        assertEquals(setOf("t", "approval", "goalSteps"), s.keys)
        val pin = obj(GroupCodec.encode(GroupClientMessage.Pin(12.9, 77.5, "Gate 2")))
        assertEquals("Gate 2", pin["label"]!!.jsonPrimitive.content)
    }

    @Test fun decodesTheJoinSnapshot() {
        val m = GroupCodec.decode(
            """{"t":"group-joined","code":"K7M2QX","you":"p2","host":"p1","roster":[{"peer":"p1","name":"Asha","host":true},{"peer":"p2","name":"Ben","host":false}],
               "settings":{"approval":true,"goalSteps":30000,"title":"Lake loop","max":50},"expiresInSec":3600}""",
        ) as GroupServerMessage.Joined
        assertEquals("K7M2QX", m.code)
        assertEquals(2, m.roster.size)
        assertTrue(m.roster[0].host)
        assertEquals(GroupSettings(true, 30000, "Lake loop", 50), m.settings)
        assertEquals(3600, m.expiresInSec)
    }

    @Test fun decodesUpdatesAndRejectsBadOnes() {
        val ok = GroupCodec.decode("""{"t":"upd","from":"p1","d":{"lat":12.9,"lon":77.5,"steps":10,"ts":99,"spd":1.1}}""") as GroupServerMessage.Upd
        assertEquals("p1", ok.from)
        assertEquals(LatLon(12.9, 77.5), ok.update.pos)
        val steps = GroupCodec.decode("""{"t":"upd","from":"p1","d":{"steps":7}}""") as GroupServerMessage.Upd
        assertNull(steps.update.pos)
        for (bad in listOf(
            """{"t":"upd","from":"p1","d":{"lat":200,"lon":0,"steps":1}}""",
            """{"t":"upd","from":"p1","d":{"lat":10,"steps":1}}""",
            """{"t":"upd","from":"p1","d":{"lat":1,"lon":1,"steps":-5}}""",
            """{"t":"upd","d":{"lat":1,"lon":1}}""",
            """{"t":"upd","from":"p1","d":5}""",
        )) assertNull(bad, GroupCodec.decode(bad))
    }

    @Test fun decodesControlMessagesAndIgnoresUnknown() {
        assertEquals(GroupServerMessage.Pending, GroupCodec.decode("""{"t":"pending"}"""))
        assertEquals(GroupServerMessage.Kicked, GroupCodec.decode("""{"t":"kicked"}"""))
        assertEquals(GroupServerMessage.RoomClosed, GroupCodec.decode("""{"t":"room-closed"}"""))
        assertEquals(GroupServerMessage.RoomExpired, GroupCodec.decode("""{"t":"room-expired"}"""))
        assertEquals(GroupServerMessage.HostAway, GroupCodec.decode("""{"t":"host-away"}"""))
        assertEquals(GroupServerMessage.JoinRequest("p9", "Zed"), GroupCodec.decode("""{"t":"join-request","peer":"p9","name":"Zed"}"""))
        assertEquals(GroupServerMessage.MemberLeft("p9", "kicked"), GroupCodec.decode("""{"t":"member-left","peer":"p9","reason":"kicked"}"""))
        assertEquals(GroupServerMessage.PinSet(12.9, 77.5, "Gate 2"), GroupCodec.decode("""{"t":"pin","lat":12.9,"lon":77.5,"label":"Gate 2"}"""))
        assertEquals(GroupServerMessage.Error("room_full"), GroupCodec.decode("""{"t":"error","code":"room_full"}"""))
        assertNull(GroupCodec.decode("""{"t":"mystery"}"""))
        assertNull(GroupCodec.decode("not json"))
        assertNull(GroupCodec.decode(null))
        assertNull(GroupCodec.decode("""{"t":"pin","lat":99,"lon":1}"""))
        assertNull(GroupCodec.decode("x".repeat(GroupCodec.MAX_CHARS + 1)))
    }

    @Test fun namesAreCleanedOnTheWayIn() {
        val m = GroupCodec.decode("""{"t":"member-joined","peer":"p3","name":"  Al\u0000ice  ","host":false}""") as GroupServerMessage.MemberJoined
        assertEquals("Alice", m.name)
    }

    @Test fun errorCopyIsCalmAndNeverEmpty() {
        for (c in listOf("no_such_room", "room_full", "wrong_mode", "removed", "id_taken", "too_many_joins", "bad_code", "weird")) assertTrue(GroupCopy.error(c).isNotBlank())
        assertTrue(GroupCopy.error("weird").contains("weird"))
    }

    @Test fun partnerMapPinMessagesRoundTrip() {
        val pin = MessageCodec.decode(MessageCodec.encode(PeerMessage.Pin(12.9, 77.5, "Café"))) as DecodeResult.Ok
        assertEquals(PeerMessage.Pin(12.9, 77.5, "Café"), pin.message)
        assertEquals(PeerMessage.Unpin, (MessageCodec.decode(MessageCodec.encode(PeerMessage.Unpin)) as DecodeResult.Ok).message)
        assertTrue(MessageCodec.decode("""{"v":1,"t":"pin","lat":999,"lon":1}""") is DecodeResult.Rejected)
    }

    @Test fun updateFromPositionKeepsTheFields() {
        val u = GroupUpdate.from(PeerMessage.Position(10L, 1.0, 2.0, 5.0, 1.0, 20, 90.0, 33.0))
        assertEquals(LatLon(1.0, 2.0), u.pos); assertEquals(20, u.steps); assertNotNull(u.distanceM)
    }
}
