package com.walkbuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsTest {
    private fun walk(f: VerifiedStepsFilter, fromSec: Int, sec: Int, startSteps: Long, act: ActivityState, speed: Double?, perSec: Int = 2): Long {
        var steps = startSteps
        for (s in fromSec..fromSec + sec) { f.onSample(s * 1000L, steps, act, speed); steps += perSec }
        return steps
    }

    @Test fun walkingStepsAreVerified() {
        val f = VerifiedStepsFilter()
        walk(f, 0, 60, 1000, ActivityState.Walking, 1.4)
        assertEquals(f.totals().raw, f.totals().verified)
        assertEquals(120, f.totals().verified)
    }

    @Test fun vehicleAndBicycleStepsAreIgnoredButCountedRaw() {
        val f = VerifiedStepsFilter()
        var n = walk(f, 0, 30, 0, ActivityState.Walking, 1.4)
        val ver = f.totals().verified
        n = walk(f, 31, 30, n, ActivityState.InVehicle, 12.0)
        n = walk(f, 62, 30, n, ActivityState.OnBicycle, 4.5)
        assertTrue(f.totals().raw > ver)
        assertEquals(ver, f.totals().verified)
        assertTrue(f.totals().ignored > 0)
        walk(f, 93, 10, n, ActivityState.Walking, 1.3)
        assertTrue(f.totals().verified > ver)
    }

    @Test fun sustainedGpsSpeedAboveWalkingMeansNotWalking() {
        val f = VerifiedStepsFilter()
        walk(f, 0, 20, 0, ActivityState.Walking, 1.4) // 40 verified
        val before = f.totals().verified
        walk(f, 21, 30, 100, ActivityState.Walking, 5.0) // classifier says walking, GPS disagrees
        // only the first few seconds (< sustain window) can slip through
        // Only the first seconds (< the 8 s sustain window) can slip through; the rest of the fast stretch is ignored.
        assertTrue("verified grew by ${f.totals().verified - before}", f.totals().verified - before <= 30)
        assertTrue(f.totals().raw - f.totals().verified > 30)
        val mid = f.totals().verified
        walk(f, 52, 20, 300, ActivityState.Walking, 1.2) // back to walking pace
        assertTrue(f.totals().verified > mid)
    }

    @Test fun briefSpeedSpikeDoesNotDiscardSteps() {
        val f = VerifiedStepsFilter()
        walk(f, 0, 10, 0, ActivityState.Walking, 1.4)
        f.onSample(11_000, 22, ActivityState.Walking, 4.0) // single spike
        f.onSample(12_000, 24, ActivityState.Walking, 1.4)
        assertEquals(f.totals().raw, f.totals().verified)
    }

    @Test fun stillAndUnknownActivity() {
        val f = VerifiedStepsFilter()
        f.onSample(0, 0, ActivityState.Unknown, null)
        f.onSample(1000, 2, ActivityState.Unknown, null)
        assertEquals(2, f.totals().verified)
        f.onSample(2000, 4, ActivityState.Still, null)
        assertEquals(2, f.totals().verified)
        assertEquals(4, f.totals().raw)
    }

    @Test fun counterResetAfterRebootDoesNotGoNegative() {
        val f = VerifiedStepsFilter()
        f.onSample(0, 5000, ActivityState.Walking, 1.3)
        f.onSample(1000, 5002, ActivityState.Walking, 1.3)
        f.onSample(2000, 3, ActivityState.Walking, 1.3) // reset
        assertEquals(5, f.totals().raw)
    }

    @Test fun impossibleBurstsAreCapped() {
        val f = VerifiedStepsFilter()
        f.onSample(0, 0, ActivityState.Walking, 1.3)
        f.onSample(1000, 500, ActivityState.Walking, 1.3) // 500 steps in 1 s (shaking the phone)
        assertEquals(500, f.totals().raw)
        assertTrue(f.totals().verified <= 7)
    }

    @Test fun classifierRules() {
        assertEquals(ActivityState.InVehicle, ActivityClassifier.classify(15.0, 0.0))
        assertEquals(ActivityState.OnBicycle, ActivityClassifier.classify(5.0, 5.0))
        assertEquals(ActivityState.Running, ActivityClassifier.classify(4.0, 160.0))
        assertEquals(ActivityState.Walking, ActivityClassifier.classify(1.4, 110.0))
        assertEquals(ActivityState.Walking, ActivityClassifier.classify(null, 100.0))
        assertEquals(ActivityState.Still, ActivityClassifier.classify(0.0, 0.0))
        assertEquals(ActivityState.Unknown, ActivityClassifier.classify(null, null))
    }
}

class DailyStepsTest {
    @Test fun firstReadingOnlySetsABaseline() {
        val a = DailyStepAccumulator()
        assertEquals(StepDelta(0, 0), a.onReading(0, 5000))
        assertEquals(StepDelta(300, 300), a.onReading(15 * 60_000L, 5300))
    }

    @Test fun implausibleRatesAreRawButNotVerified() {
        val a = DailyStepAccumulator(1000, 0)
        val d = a.onReading(60_000, 6000) // 5000 steps in a minute
        assertEquals(5000, d.raw)
        assertEquals(200, d.verified)
    }

    @Test fun rebootResetAndStatePersistence() {
        val a = DailyStepAccumulator(9000, 0)
        assertEquals(StepDelta(40, 40), a.onReading(10 * 60_000L, 40))
        val restored = DailyStepAccumulator(a.lastCounter, a.lastTMs)
        assertEquals(StepDelta(10, 10), restored.onReading(20 * 60_000L, 50))
    }

    @Test fun rebaseSkipsStepsAlreadyCounted() {
        val a = DailyStepAccumulator(100, 0)
        a.rebase(60_000, 400)
        assertEquals(StepDelta(20, 20), a.onReading(120_000, 420))
    }

    @Test fun sittingMonitorStateRoundTrips() {
        val m = SittingMonitor()
        for (min in 0..40) m.onSample(min * 60_000L, false, 14)
        val s = m.snapshot()
        val m2 = SittingMonitor(); m2.restore(s)
        var fired = -1
        for (min in 41..80) if (m2.onSample(min * 60_000L, false, 14) != null && fired < 0) fired = min
        assertEquals(60, fired)
    }

    @Test fun coarseSamplingBreakResetsTheSittingClock() {
        val m = SittingMonitor()
        for (min in 0..45 step 15) m.onSample(min * 60_000L, false, 14)
        m.markBreak(50 * 60_000L)
        var fired = -1
        for (min in 51..130) if (m.onSample(min * 60_000L, false, 14) != null && fired < 0) fired = min
        assertEquals(110, fired)
    }

    @Test fun engineReportsStepDeltas() {
        val e = WalkEngine("me", "Me", startMs = 0)
        assertEquals(StepDelta(0, 0), e.onSelfSteps(1000, 100))
        assertEquals(StepDelta(2, 2), e.onSelfSteps(2000, 102))
    }
}
