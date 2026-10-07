package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherTest {
    @Test fun scoreIsShareOfTimeWithinRadius() {
        val t = TogetherTracker(radiusM = 50.0)
        t.update(0, 10.0)
        for (s in 1..60) t.update(s * 1000L, 20.0)
        for (s in 61..100) t.update(s * 1000L, 120.0)
        val snap = t.snapshot()
        assertEquals(100_000, snap.walkMs)
        assertEquals(60_000, snap.togetherMs)
        assertEquals(60, snap.scorePct)
        assertEquals(60_000, snap.longestStreakMs)
        assertEquals(0, snap.currentStreakMs)
    }

    @Test fun radiusIsConfigurable() {
        val tight = TogetherTracker(radiusM = 10.0)
        val loose = TogetherTracker(radiusM = 100.0)
        for (s in 0..30) { tight.update(s * 1000L, 30.0); loose.update(s * 1000L, 30.0) }
        assertEquals(0, tight.snapshot().scorePct)
        assertEquals(100, loose.snapshot().scorePct)
    }

    @Test fun longestStreakKeepsTheBestRun() {
        val t = TogetherTracker()
        var now = 0L
        fun run(sec: Int, d: Double?) { repeat(sec) { now += 1000; t.update(now, d) } }
        t.update(0, 5.0)
        run(20, 5.0); run(5, 200.0); run(40, 5.0); run(5, null); run(10, 5.0)
        assertEquals(40_000, t.snapshot().longestStreakMs)
        assertEquals(10_000, t.snapshot().currentStreakMs)
    }

    @Test fun unknownDistanceCountsAsWalkTimeButNotTogether() {
        val t = TogetherTracker()
        t.update(0, null)
        for (s in 1..10) t.update(s * 1000L, null)
        assertEquals(10_000, t.snapshot().walkMs)
        assertEquals(0, t.snapshot().scorePct)
    }

    @Test fun longGapsAreClampedSoPausesDoNotSkewTheScore() {
        val t = TogetherTracker()
        t.update(0, 5.0)
        t.update(10 * 60_000L, 5.0)
        assertEquals(15_000, t.snapshot().walkMs)
    }

    @Test fun emptyWalkScoreIsZero() = assertEquals(0, TogetherTracker().snapshot().scorePct)
}

class NudgeTest {
    private fun ctx(t: Long, gap: Double?, ahead: Boolean? = false, me: Boolean = true, buddy: Boolean = true, conn: Boolean = true) =
        NudgeContext(t * 1000, gap, ahead, me, buddy, conn, "Asha")

    @Test fun nothingWhileCloseOrInTheHysteresisBand() {
        val e = NudgeEngine()
        for (t in 0..120L) assertNull(e.evaluate(ctx(t, 30.0)))
        for (t in 121..240L) assertNull(e.evaluate(ctx(t, 80.0))) // between near and far
    }

    @Test fun firesOnlyAfterGapIsSustained() {
        val e = NudgeEngine()
        assertNull(e.evaluate(ctx(0, 150.0)))
        assertNull(e.evaluate(ctx(10, 150.0)))
        assertNull(e.evaluate(ctx(19, 150.0)))
        val n = e.evaluate(ctx(21, 150.0))
        assertNotNull(n)
        assertEquals(NudgeKind.CatchUp, n!!.kind)
        assertTrue(n.text.contains("Asha"))
    }

    @Test fun aShortSpikeResetsTheTimer() {
        val e = NudgeEngine()
        assertNull(e.evaluate(ctx(0, 150.0)))
        assertNull(e.evaluate(ctx(15, 90.0))) // back in the band, timer resets
        assertNull(e.evaluate(ctx(16, 150.0)))
        assertNull(e.evaluate(ctx(30, 150.0)))
        assertNotNull(e.evaluate(ctx(37, 150.0)))
    }

    @Test fun doesNotSpamWhileGapPersists() {
        val e = NudgeEngine()
        var fired = 0
        for (t in 0..600L) if (e.evaluate(ctx(t, 200.0)) != null) fired++
        assertEquals(1, fired)
    }

    @Test fun rearmsOnlyAfterGettingNearAndRespectsCooldown() {
        val e = NudgeEngine()
        for (t in 0..25L) e.evaluate(ctx(t, 150.0)) // fires at ~21
        assertEquals(1, e.count)
        for (t in 26..29L) e.evaluate(ctx(t, 40.0)) // near: re-arms
        for (t in 100..130L) assertNull("cooldown (3 min) still active", e.evaluate(ctx(t, 150.0)))
        var fired = false
        for (t in 131..260L) if (e.evaluate(ctx(t, 150.0)) != null) fired = true
        assertTrue(fired)
        assertEquals(2, e.count)
    }

    @Test fun capsNudgesPerWalk() {
        val e = NudgeEngine(NudgeConfig(cooldownMs = 0, sustainMs = 1000, maxPerWalk = 2))
        var fired = 0
        var t = 0L
        repeat(10) {
            repeat(5) { t++; if (e.evaluate(ctx(t, 150.0)) != null) fired++ }
            repeat(2) { t++; e.evaluate(ctx(t, 20.0)) }
        }
        assertEquals(2, fired)
    }

    @Test fun quietModeMutesEverything() {
        val e = NudgeEngine(NudgeConfig(quiet = true))
        for (t in 0..600L) assertNull(e.evaluate(ctx(t, 300.0)))
    }

    @Test fun silentWhenBuddyPausedOrDisconnectedOrIAmStopped() {
        for (c in listOf({ t: Long -> ctx(t, 300.0, buddy = false) }, { t: Long -> ctx(t, 300.0, conn = false) }, { t: Long -> ctx(t, 300.0, me = false) }, { t: Long -> ctx(t, null) }, { t: Long -> ctx(t, 300.0, ahead = null) })) {
            val e = NudgeEngine()
            for (t in 0..120L) assertNull(e.evaluate(c(t)))
        }
    }

    @Test fun defaultModeNudgesTheOneBehindNotTheLeader() {
        val e = NudgeEngine()
        for (t in 0..120L) assertNull("leader gets nothing in default mode", e.evaluate(ctx(t, 200.0, ahead = true)))
    }

    @Test fun paceSyncNudgesTheFasterPartnerOnly() {
        val ahead = NudgeEngine(NudgeConfig(paceSync = true))
        var got: Nudge? = null
        for (t in 0..60L) ahead.evaluate(ctx(t, 200.0, ahead = true))?.let { got = it }
        assertEquals(NudgeKind.EaseOff, got!!.kind)
        val behind = NudgeEngine(NudgeConfig(paceSync = true))
        for (t in 0..120L) assertNull(behind.evaluate(ctx(t, 200.0, ahead = false)))
    }

    @Test fun nudgeWordingIsCalm() {
        val e = NudgeEngine()
        var n: Nudge? = null
        for (t in 0..40L) e.evaluate(ctx(t, 200.0))?.let { n = it }
        val text = n!!.text.lowercase()
        for (bad in listOf("!", "hurry", "slow", "lazy", "behind schedule", "fail", "warning")) assertTrue("'$bad' in: $text", bad !in text)
    }
}

class PaceTest {
    @Test fun zonesFromCadence() {
        assertEquals(PaceZone.Standing, PaceZone.fromCadence(10.0))
        assertEquals(PaceZone.Easy, PaceZone.fromCadence(85.0))
        assertEquals(PaceZone.Brisk, PaceZone.fromCadence(100.0))
        assertEquals(PaceZone.Brisk, PaceZone.fromCadence(129.9))
        assertEquals(PaceZone.Vigorous, PaceZone.fromCadence(130.0))
        assertNull(PaceZone.fromCadence(null))
    }

    @Test fun rollingPaceMatchesConstantSpeedAndForgetsOldData() {
        val p = RollingPace(windowMs = 30_000)
        assertNull(p.speedMps())
        for (s in 0..120) p.add(s * 1000L, if (s <= 60) s * 1.5 else 90.0 + (s - 60) * 0.5)
        assertEquals(0.5, p.speedMps()!!, 0.05)
    }

    @Test fun cadenceFromStepCounts() {
        val c = CadenceTracker()
        for (s in 0..40) c.add(s * 1000L, s * 2L)
        assertEquals(120.0, c.spm()!!, 3.0)
    }

    @Test fun paceMatchTargetsSlowestMovingBuddy() {
        val s = PaceMatch.suggest(mapOf("me" to 1.6, "her" to 1.1, "him" to 1.5))!!
        assertEquals("her", s.slowestId)
        assertEquals(1.1, s.targetMps, 1e-9)
        assertEquals(setOf("me", "him"), s.fasterIds.toSet())
    }

    @Test fun paceMatchIgnoresRestingAndSimilarPaces() {
        assertNull(PaceMatch.suggest(mapOf("me" to 1.4, "her" to 0.1)))
        assertNull(PaceMatch.suggest(mapOf("me" to 1.4, "her" to 1.3)))
        assertNull(PaceMatch.suggest(mapOf("me" to 1.4)))
        assertNull(PaceMatch.suggest(mapOf("me" to null, "her" to 1.0)))
    }

    @Test fun connectionStates() {
        assertEquals(BuddyStatus.Waiting, LinkHealth.status(1000, null, null))
        assertEquals(BuddyStatus.Moving, LinkHealth.status(5000, 4000, 1.3))
        assertEquals(BuddyStatus.Stopped, LinkHealth.status(5000, 4000, 0.05))
        assertEquals(BuddyStatus.ConnectionLost, LinkHealth.status(60_000, 4000, 1.3))
    }

    @Test fun statusCopyIsCalm() {
        for (s in BuddyStatus.values()) {
            val t = LinkHealth.copy("Sam", s)
            assertTrue(t.contains("Sam"))
            assertTrue("no alarmist wording: $t", listOf("!", "error", "failed", "disconnected", "lost contact").none { it in t.lowercase() })
        }
    }
}
