package com.walkbuddy.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarLinkTest {
    // ---------- avatar ----------

    @Test fun avatarCodeRoundTripsForEveryStyleAndColour() {
        for (st in AvatarStyle.values()) for (c in 0 until AvatarPalette.SIZE) {
            val a = Avatar(st, c)
            val code = AvatarCode.encode(a)
            assertTrue(code in 0..AvatarCode.MAX)
            assertEquals(a, AvatarCode.decode(code))
        }
    }

    @Test fun avatarDecodeIsTolerant() {
        assertNull(AvatarCode.decode(null))
        assertNull(AvatarCode.decode(-1))
        assertNull(AvatarCode.decode(256))
        assertEquals(AvatarStyle.Round, AvatarCode.decode(3)!!.style) // style 3 does not exist
    }

    @Test fun avatarColourWrapsIntoPalette() {
        assertEquals(3, AvatarCode.decode((15 shl 2) or 1)!!.colorIndex)
        assertEquals(AvatarStyle.Boy, AvatarCode.decode((15 shl 2) or 1)!!.style)
    }

    @Test fun fallbackAvatarIsSteadyPerIdAndInRange() {
        val a = AvatarCode.fallback("abc"); val b = AvatarCode.fallback("abc")
        assertEquals(a, b)
        assertEquals(AvatarStyle.Round, a.style)
        for (id in listOf("", "x", "a-long-peer-id-1234567890", "ÄÖ")) assertTrue(AvatarCode.fallback(id).colorIndex in 0 until AvatarPalette.SIZE)
        assertEquals(Avatar(AvatarStyle.Girl, 4), AvatarCode.of(AvatarCode.encode(Avatar(AvatarStyle.Girl, 4)), "z"))
        assertEquals(AvatarCode.fallback("z"), AvatarCode.of(null, "z"))
    }

    // ---------- envelope, sequence numbers ----------

    @Test fun seqGenNeverRepeatsEvenWithinOneMillisecondOrWhenTheClockGoesBack() {
        val g = SeqGen()
        val a = g.next(1_000); val b = g.next(1_000); val c = g.next(500)
        assertTrue(a < b && b < c)
        assertEquals(1_000L, a)
    }

    @Test fun stampPutsTheNumberFirstAndSeqOfReadsItBack() {
        val j = MessageCodec.encode(PeerMessage.Ping(5))
        val s = LinkEnvelope.stamp(j, 1_700_000_000_123L)
        assertTrue(s.startsWith("{\"q\":1700000000123,"))
        assertEquals(1_700_000_000_123L, LinkEnvelope.seqOf(s))
        // the stamped text still decodes as the same message
        assertEquals(PeerMessage.Ping(5), (MessageCodec.decode(s) as DecodeResult.Ok).message)
        assertEquals("{\"q\":7}", LinkEnvelope.stamp("{}", 7))
        assertEquals("not json", LinkEnvelope.stamp("not json", 7))
        assertNull(LinkEnvelope.seqOf(j))
        assertNull(LinkEnvelope.seqOf("{\"q\":}"))
        assertNull(LinkEnvelope.seqOf("{\"q\":12345678901234567890123,"))
    }

    @Test fun everyMessageTypeSurvivesStamping() {
        val all = listOf(
            PeerMessage.Hello("a", "Asha", 9), PeerMessage.Position(1_700_000_000_000, 12.9, 77.5, 5.0, 1.2, 100, 110.0, 340.0),
            PeerMessage.Ping(3), PeerMessage.Spot("Lake", 12.9, 77.5), PeerMessage.React("love"), PeerMessage.Daily(3000, 6000),
            PeerMessage.Pin(12.9, 77.5, "Chai"), PeerMessage.Unpin, PeerMessage.Bye,
        )
        for (m in all) {
            val stamped = LinkEnvelope.stamp(MessageCodec.encode(m), 42)
            val d = MessageCodec.decode(stamped)
            assertTrue("$m -> $d", d is DecodeResult.Ok)
            assertEquals(42L, LinkEnvelope.seqOf(stamped))
        }
    }

    @Test fun seqGateAcceptsEachMessageOnceAndDropsOlderCopiesPerType() {
        val g = SeqGate()
        assertTrue(g.accept("p", "pos", 100))
        assertFalse("same message over the second path", g.accept("p", "pos", 100))
        assertFalse("older copy arriving late", g.accept("p", "pos", 90))
        assertTrue(g.accept("p", "pos", 101))
        assertTrue("a reaction is independent of positions", g.accept("p", "react", 95))
        assertTrue("another sender is independent", g.accept("q", "pos", 50))
        assertTrue("no number (older app) is always accepted", g.accept("p", "pos", null))
        assertTrue(g.accept("p", "pos", null))
        g.forget("p")
        assertTrue(g.accept("p", "pos", 1)) // restarted peer
    }

    // ---------- link mode ----------

    @Test fun linkModeFollowsWhatIsWorking() {
        val q = LinkQuality()
        assertEquals(LinkMode.Waiting, q.mode(0))
        q.setServer(up = true, partnerHere = true, nowMs = 1_000)
        assertEquals("server path just came up", LinkMode.ViaServer, q.mode(2_000))
        q.setP2pOpen(true, 3_000)
        assertEquals(LinkMode.Direct, q.mode(4_000))
        // direct goes silent: after 25 s it is not "Direct" any more, the server path (heard recently) takes over
        q.heard(viaRelay = true, nowMs = 40_000)
        assertEquals(LinkMode.ViaServer, q.mode(45_000))
        q.heard(viaRelay = false, nowMs = 46_000)
        assertEquals(LinkMode.Direct, q.mode(47_000))
        // both paths silent
        assertEquals(LinkMode.Reconnecting, q.mode(120_000))
        q.online = false
        assertEquals(LinkMode.Offline, q.mode(120_000))
        q.online = true
        q.setServer(up = false, partnerHere = false, nowMs = 121_000)
        q.setP2pOpen(false, 121_000)
        assertEquals(LinkMode.Reconnecting, q.mode(122_000))
    }

    @Test fun serverPathNeedsThePartnerInTheRoom() {
        val q = LinkQuality()
        q.setServer(up = true, partnerHere = false, nowMs = 0)
        assertEquals(LinkMode.Waiting, q.mode(1_000))
        q.setServer(up = true, partnerHere = true, nowMs = 2_000)
        assertEquals(LinkMode.ViaServer, q.mode(3_000))
        q.setServer(up = true, partnerHere = false, nowMs = 4_000)
        assertEquals("they left and nothing was ever direct", LinkMode.Reconnecting, q.mode(5_000))
    }

    // ---------- watchdog ----------

    private fun dog() = LinkWatchdog(random = Random(1))

    @Test fun watchdogWaitsThroughTheGraceThenBacksOffAndRecreates() {
        val w = dog()
        w.onP2p(true, 0)
        assertEquals(LinkAction.None, w.poll(1_000))
        w.onP2p(false, 10_000) // dropped
        assertEquals(LinkAction.None, w.poll(12_000)) // grace 6 s
        val first = w.poll(17_000)
        assertTrue(first is LinkAction.RestartIce && first.attempt == 1)
        assertEquals("backoff holds the next try back", LinkAction.None, w.poll(17_500))
        var now = 17_500L
        val seen = ArrayList<LinkAction>()
        repeat(40) { now += 5_000; val a = w.poll(now); if (a != LinkAction.None) seen += a }
        assertTrue(seen.any { it is LinkAction.Recreate })
        assertTrue("never tighter than the backoff base", seen.size < 20)
        w.onP2p(true, now)
        assertEquals(LinkAction.None, w.poll(now + 100_000))
        assertEquals(0, w.attemptCount)
    }

    @Test fun initialConnectGetsALongerGrace() {
        val w = dog()
        assertEquals(LinkAction.None, w.poll(0))
        assertEquals(LinkAction.None, w.poll(15_000))
        assertTrue(w.poll(21_000) is LinkAction.RestartIce)
    }

    @Test fun networkChangeRestartsSoonEvenWhileTheOldLinkLooksOpen() {
        val w = dog()
        w.onP2p(true, 0)
        w.onNetworkChanged(50_000)
        assertEquals(LinkAction.None, w.poll(50_100))
        val a = w.poll(52_000)
        assertTrue(a is LinkAction.RestartIce)
        assertEquals("only once", LinkAction.None, w.poll(52_100))
    }

    @Test fun relayPlanKeepsAThinStandbyWhileDirectWorksAndCarriesEverythingSmallWhenItDoesNot() {
        // direct healthy: nothing extra except the standby trickle and rare essentials
        assertEquals(LinkRoute.Plan(true, false), LinkRoute.plan("pos", true, true, 1_000))
        assertEquals(LinkRoute.Plan(true, true), LinkRoute.plan("pos", true, true, LinkRoute.STANDBY_POSITION_MS))
        assertEquals(LinkRoute.Plan(true, true), LinkRoute.plan("hello", true, true, 0))
        assertEquals(LinkRoute.Plan(true, false), LinkRoute.plan("react", true, true, 99_999))
        assertEquals(LinkRoute.Plan(true, false), LinkRoute.plan("pos", true, false, 99_999))
        // direct down: everything small goes to the server if it is there
        for (t in listOf("pos", "hello", "day", "pin", "unpin", "bye", "react", "ping")) assertEquals(LinkRoute.Plan(false, true), LinkRoute.plan(t, false, true, 0))
        assertEquals("a shared spot is not worth the relay", LinkRoute.Plan(false, false), LinkRoute.plan("spot", false, true, 0))
        assertEquals(LinkRoute.Plan(false, false), LinkRoute.plan("pos", false, false, 0))
    }

    // ---------- codec additions ----------

    @Test fun helloCarriesTheAvatarAndOlderHellosStillDecode() {
        val h = PeerMessage.Hello("id1", "Asha", AvatarCode.encode(Avatar(AvatarStyle.Girl, 5)))
        val back = (MessageCodec.decode(MessageCodec.encode(h)) as DecodeResult.Ok).message as PeerMessage.Hello
        assertEquals(h, back)
        val old = (MessageCodec.decode("""{"v":1,"t":"hello","id":"x","name":"Ben"}""") as DecodeResult.Ok).message as PeerMessage.Hello
        assertNull(old.avatar)
        val bad = (MessageCodec.decode("""{"v":1,"t":"hello","id":"x","name":"Ben","av":9999}""") as DecodeResult.Ok).message as PeerMessage.Hello
        assertNull(bad.avatar)
    }

    @Test fun signalingCarriesTheSessionKeyAndPairData() {
        val j = SignalingCodec.encode(SignalingMessage.Join("K7M2QX", "me", "session-key-0123456789"))
        assertTrue(j.contains("\"key\":\"session-key-0123456789\""))
        assertFalse(SignalingCodec.encode(SignalingMessage.Join("K7M2QX", "me")).contains("key"))
        val send = SignalingCodec.encode(SignalingMessage.PairSend("{\"v\":1,\"t\":\"bye\"}"))
        assertTrue(send.contains("\"t\":\"pdata\""))
        val got = SignalingCodec.decode("""{"t":"pdata","from":"alice","data":"{\"v\":1,\"t\":\"bye\"}"}""")
        assertEquals(SignalingMessage.PairData("alice", "{\"v\":1,\"t\":\"bye\"}"), got)
        assertNull(SignalingCodec.decode("""{"t":"pdata","from":"alice"}"""))
        assertNull(SignalingCodec.decode("{\"t\":\"pdata\",\"data\":\"" + "x".repeat(3000) + "\"}"))
        assertNotNull(SignalingCodec.decode("""{"t":"pdata","data":"x"}"""))
    }

    @Test fun groupMessagesCarryAvatarCodes() {
        val create = GroupCodec.encode(GroupClientMessage.Create("p", "k".repeat(10), "Host", avatar = 21))
        assertTrue(create.contains("\"av\":21"))
        assertFalse(GroupCodec.encode(GroupClientMessage.Join("K7M2QX", "p", "k".repeat(10), "Bea")).contains("av"))
        val joined = GroupCodec.decode("""{"t":"group-joined","code":"K7M2QX","you":"me","host":"h","roster":[{"peer":"h","name":"Host","host":true,"av":21},{"peer":"b","name":"Bea","host":false}],"settings":{},"expiresInSec":10}""")
            as GroupServerMessage.Joined
        assertEquals(21, joined.roster[0].avatar)
        assertNull(joined.roster[1].avatar)
        val mj = GroupCodec.decode("""{"t":"member-joined","peer":"c","name":"Cy","host":false,"av":6}""") as GroupServerMessage.MemberJoined
        assertEquals(6, mj.avatar)
    }
}
