package com.walkbuddy.domain

import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingTest {
    private val hidden = PowerState()
    private val visible = PowerState(screenVisible = true)
    private val walkingHidden = PowerState(walkActive = true, moving = true, groupSize = 2)
    private val walkingVisible = walkingHidden.copy(screenVisible = true)

    private fun plan(s: PowerState, k: StepSensorKind? = StepSensorKind.Counter) = SamplingPolicy.plan(s, k)

    // ---- mode ----

    @Test fun modeFollowsSaversAndCharger() {
        assertEquals(PowerMode.Balanced, SamplingPolicy.mode(hidden))
        assertEquals(PowerMode.Charging, SamplingPolicy.mode(hidden.copy(charging = true)))
        assertEquals(PowerMode.Saving, SamplingPolicy.mode(hidden.copy(systemBatterySaver = true)))
        assertEquals(PowerMode.Saving, SamplingPolicy.mode(hidden.copy(userBatterySaver = true)))
        // A saver beats the charger: the user asked for it.
        assertEquals(PowerMode.Saving, SamplingPolicy.mode(hidden.copy(charging = true, userBatterySaver = true)))
    }

    // ---- step sensor ----

    @Test fun counterIsImmediateOnlyWhenVisible() {
        assertEquals(0L, plan(visible).sensorLatencyMs)
        assertEquals(0L, plan(walkingVisible).sensorLatencyMs)
    }

    @Test fun counterBatchesForMinutesWhenHidden() {
        assertEquals(300_000L, plan(hidden).sensorLatencyMs)
        assertEquals(600_000L, plan(hidden.copy(userBatterySaver = true)).sensorLatencyMs)
        assertEquals(600_000L, plan(hidden.copy(systemBatterySaver = true)).sensorLatencyMs)
        assertEquals(60_000L, plan(hidden.copy(charging = true)).sensorLatencyMs)
    }

    @Test fun duringAWalkHiddenBatchesAreShortButNeverZero() {
        assertEquals(5_000L, plan(walkingHidden).sensorLatencyMs)
        assertEquals(10_000L, plan(walkingHidden.copy(userBatterySaver = true)).sensorLatencyMs)
    }

    @Test fun latencyNeverExceedsTheCeilingAndFitsAnIntInMicroseconds() {
        val all = listOf(hidden, visible, walkingHidden, walkingVisible, hidden.copy(userBatterySaver = true), hidden.copy(charging = true))
        for (s in all) {
            val l = plan(s).sensorLatencyMs!!
            assertTrue(l <= SamplingPolicy.MAX_SENSOR_LATENCY_MS)
            assertTrue(l * 1000 <= Int.MAX_VALUE)
        }
    }

    @Test fun detectorBatchesLikeTheCounter() {
        assertEquals(300_000L, plan(hidden, StepSensorKind.Detector).sensorLatencyMs)
        assertEquals(0L, plan(visible, StepSensorKind.Detector).sensorLatencyMs)
    }

    @Test fun accelerometerFallbackRunsOnlyWithScreenOrWalk() {
        assertNull(plan(hidden, StepSensorKind.Accelerometer).sensorLatencyMs)
        assertNull(plan(hidden.copy(userBatterySaver = true, charging = true), StepSensorKind.Accelerometer).sensorLatencyMs)
        assertEquals(0L, plan(visible, StepSensorKind.Accelerometer).sensorLatencyMs)
        assertEquals(0L, plan(walkingHidden, StepSensorKind.Accelerometer).sensorLatencyMs)
    }

    @Test fun noSensorMeansNoListener() {
        assertNull(plan(visible, null).sensorLatencyMs)
    }

    // ---- location ----

    @Test fun noLocationWithoutAWalk() {
        assertNull(plan(hidden).location)
        assertNull(plan(visible).location)
        assertNull(plan(hidden.copy(moving = true, groupSize = 5)).location)
        assertNull(plan(hidden.copy(userBatterySaver = true)).location)
    }

    @Test fun movingWalkAsksEveryThreeToFiveSeconds() {
        assertEquals(LocationPlan(3_000, 3f), plan(walkingVisible).location)
        assertEquals(LocationPlan(5_000, 5f), plan(walkingHidden).location)
    }

    @Test fun stationaryBacksOff() {
        val still = walkingVisible.copy(moving = false)
        assertEquals(LocationPlan(6_000, 6f), plan(still).location)
        assertEquals(LocationPlan(10_000, 10f), plan(still.copy(screenVisible = false)).location)
    }

    @Test fun saverSlowsLocationFurther() {
        val moving = plan(walkingHidden.copy(userBatterySaver = true)).location!!
        val still = plan(walkingHidden.copy(userBatterySaver = true, moving = false)).location!!
        assertEquals(LocationPlan(8_000, 10f), moving)
        assertEquals(LocationPlan(20_000, 15f), still)
        assertTrue(moving.intervalMs > plan(walkingHidden).location!!.intervalMs)
    }

    @Test fun locationIntervalsOnlyGetLongerAsPowerGetsTighter() {
        val order = listOf(walkingVisible, walkingHidden, walkingHidden.copy(userBatterySaver = true))
        val ms = order.map { plan(it).location!!.intervalMs }
        assertEquals(ms.sorted(), ms)
    }

    // ---- sending ----

    @Test fun sendIntervalStaysWellInsideTheLostTimeout() {
        val all = listOf(
            walkingVisible, walkingHidden, walkingHidden.copy(moving = false), walkingHidden.copy(userBatterySaver = true, moving = false, groupSize = 40),
            walkingVisible.copy(groupSize = 50),
        )
        for (s in all) {
            val i = plan(s).sendIntervalMs
            assertTrue("interval $i", i <= SamplingPolicy.MAX_SEND_INTERVAL_MS)
            assertTrue(i * 3 <= LinkHealth.LOST_AFTER_MS)
        }
    }

    @Test fun sendSlowsWhenStationaryBackgroundedOrCrowdedOrSaving() {
        val base = plan(walkingVisible).sendIntervalMs
        assertEquals(3_000L, base)
        assertTrue(plan(walkingHidden).sendIntervalMs > base)
        assertTrue(plan(walkingVisible.copy(moving = false)).sendIntervalMs > base)
        assertTrue(plan(walkingVisible.copy(groupSize = 20)).sendIntervalMs > base)
        assertTrue(plan(walkingVisible.copy(systemBatterySaver = true)).sendIntervalMs > base)
    }

    // ---- UI / notification / widget rates ----

    @Test fun backgroundLoopsAreSlowerThanVisibleOnes() {
        val v = plan(walkingVisible)
        val h = plan(walkingHidden)
        assertEquals(1_000L, v.tickMs)
        assertEquals(5_000L, h.tickMs)
        assertTrue(h.dailyBroadcastMs > v.dailyBroadcastMs)
        assertTrue(h.widgetMinMs > v.widgetMinMs)
        assertTrue(h.stepsNotifyMinMs > v.stepsNotifyMinMs)
        assertTrue(h.stepsNotifyDelta > v.stepsNotifyDelta)
    }

    @Test fun saverIsNeverFasterThanBalancedAnywhere() {
        for (base in listOf(hidden, visible, walkingHidden, walkingVisible)) {
            val n = plan(base)
            val s = plan(base.copy(userBatterySaver = true))
            assertTrue(s.tickMs >= n.tickMs)
            assertTrue(s.sendIntervalMs >= n.sendIntervalMs)
            assertTrue(s.dailyBroadcastMs >= n.dailyBroadcastMs)
            assertTrue(s.walkNotifyMs >= n.walkNotifyMs)
            assertTrue(s.stepsNotifyDelta >= n.stepsNotifyDelta)
            assertTrue(s.stepsNotifyMinMs >= n.stepsNotifyMinMs)
            assertTrue(s.widgetMinMs >= n.widgetMinMs)
            assertTrue(s.workerMinGapMs >= n.workerMinGapMs)
            assertTrue((s.sensorLatencyMs ?: 0) >= (n.sensorLatencyMs ?: 0))
            val sl = s.location; val nl = n.location
            if (sl != null && nl != null) assertTrue(sl.intervalMs >= nl.intervalMs)
        }
    }

    // ---- MotionGate ----

    @Test fun motionStartsFastAndStopsSlowly() {
        val g = MotionGate()
        assertFalse(g.update(0, 0.1))
        assertTrue(g.update(1_000, 1.3))
        assertTrue(g.update(2_000, 0.1)) // a pause is not yet standing still
        assertTrue(g.update(15_000, 0.1))
        assertFalse(g.update(23_000, 0.1)) // 21 s below the stop speed
        assertTrue(g.update(24_000, 1.0))
    }

    @Test fun motionIgnoresUnknownSpeedAndMiddleSpeeds() {
        val g = MotionGate()
        g.update(0, 1.0)
        assertTrue(g.update(1_000, null))
        // 0.4 m/s is between the thresholds: it resets the stop timer instead of stopping.
        g.update(2_000, 0.1)
        g.update(10_000, 0.4)
        assertTrue(g.update(25_000, 0.2)) // timer restarted at 25 s
        assertFalse(g.update(46_000, 0.2))
    }

    // ---- SendGate ----

    @Test fun regularCadenceIsHonoured() {
        val g = SendGate()
        assertTrue(g.poll(0, 3_000))
        assertFalse(g.poll(1_000, 3_000))
        assertFalse(g.poll(2_600, 3_000))
        assertTrue(g.poll(2_990, 3_000)) // a hair early by the wall clock still counts as this round
    }

    @Test fun burstOfUrgentSendsCoalesces() {
        val g = SendGate(minGapMs = 1_500)
        assertTrue(g.urgent(0))
        assertFalse(g.urgent(200))
        assertFalse(g.urgent(400))
        // The refused ones are not lost: the next poll sends once the minimum gap has passed, then waits for the cadence again.
        assertFalse(g.poll(1_000, 8_000))
        assertTrue(g.poll(1_600, 8_000))
        assertFalse(g.poll(2_000, 8_000))
    }

    @Test fun clockGoingBackwardsDoesNotFreezeSending() {
        val g = SendGate()
        assertTrue(g.poll(10_000, 3_000))
        assertTrue(g.poll(5_000, 3_000))
    }

    // ---- Backoff ----

    @Test fun backoffGrowsAndIsCapped() {
        val r = Random(7)
        val waits = (1..12).map { Backoff.delayMs(it, r) }
        for ((i, w) in waits.withIndex()) {
            val exp = minOf(Backoff.CAP_MS, Backoff.BASE_MS shl i)
            assertTrue("attempt ${i + 1}: $w vs $exp", w in (exp / 2)..exp)
        }
        assertTrue(waits.all { it <= Backoff.CAP_MS })
        assertTrue(waits.last() >= Backoff.CAP_MS / 2)
    }

    @Test fun backoffHasJitterAndNeverBusyLoops() {
        val r = Random(1)
        val first = (1..200).map { Backoff.delayMs(1, r) }.toSet()
        assertTrue(first.size > 20)
        assertTrue(first.all { it >= Backoff.BASE_MS / 2 })
        // Even absurd attempt numbers stay finite and bounded.
        assertTrue(Backoff.delayMs(10_000, Random(3)) in (Backoff.CAP_MS / 2)..Backoff.CAP_MS)
        assertTrue(Backoff.delayMs(0, Random(3)) >= Backoff.BASE_MS / 2)
    }

    // ---- StepNotifyThrottle ----

    @Test fun notificationWaitsForEnoughStepsAndTime() {
        val t = StepNotifyThrottle()
        assertTrue(t.shouldPost(0, 0, 100, 300_000)) // first one always
        assertFalse(t.shouldPost(0, 10_000, 100, 300_000)) // unchanged
        assertFalse(t.shouldPost(40, 20_000, 100, 300_000)) // too few steps
        assertFalse(t.shouldPost(150, 60_000, 100, 300_000)) // enough steps, too soon
        assertTrue(t.shouldPost(150, 300_000, 100, 300_000))
        assertFalse(t.shouldPost(160, 900_000, 100, 300_000)) // time passed, too few steps
    }

    @Test fun notificationForceAndNewDay() {
        val t = StepNotifyThrottle()
        t.shouldPost(500, 0, 100, 300_000)
        assertFalse(t.shouldPost(500, 1_000, 100, 300_000, force = true)) // nothing changed, nothing to say
        assertTrue(t.shouldPost(510, 2_000, 100, 300_000, force = true)) // app opened: show the true number
        assertTrue(t.shouldPost(3, 3_000, 100, 300_000)) // midnight: a lower number is shown at once
    }

    @Test fun notificationResetShowsNextNumber() {
        val t = StepNotifyThrottle()
        t.shouldPost(10, 0, 100, 300_000)
        t.reset()
        assertTrue(t.shouldPost(10, 1, 100, 300_000))
    }

    // ---- StepBuckets ----

    @Test fun batchesMergeOnlyInsideOneHour() {
        val z = ZoneId.of("UTC")
        val h = 3_600_000L
        assertTrue(StepBuckets.sameHour(10 * h + 5, 10 * h + h - 1, z))
        assertFalse(StepBuckets.sameHour(10 * h + h - 1, 11 * h, z))
        assertFalse(StepBuckets.sameHour(0, 24 * h, z)) // same clock hour, different day
    }

    @Test fun policyIsTotal() {
        // Every combination yields a plan whose numbers are positive and sensible.
        val bools = listOf(false, true)
        for (v in bools) for (w in bools) for (m in bools) for (sys in bools) for (usr in bools) for (c in bools) for (n in listOf(1, 2, 12)) {
            val s = PowerState(v, w, m, n, sys, usr, c)
            for (k in listOf(null) + StepSensorKind.values()) {
                val p = SamplingPolicy.plan(s, k)
                assertTrue(p.tickMs in 1_000..10_000)
                assertTrue(p.sendIntervalMs in 1_000..SamplingPolicy.MAX_SEND_INTERVAL_MS)
                assertTrue(p.widgetMinMs > 0 && p.walkNotifyMs > 0 && p.workerMinGapMs > 0)
                assertEquals(w, p.location != null)
                if (p.location != null) assertTrue(p.location!!.intervalMs >= 3_000)
            }
        }
        assertNotNull(SamplingPolicy.plan(PowerState(), null))
    }
}

class BatteryCopyTest {
    @Test fun modeLineNamesTheReason() {
        assertTrue(BatteryCopy.modeLine(PowerState()).startsWith("Balanced"))
        assertTrue(BatteryCopy.modeLine(PowerState(charging = true)).startsWith("Charging"))
        assertTrue(BatteryCopy.modeLine(PowerState(systemBatterySaver = true)).contains("Android Battery Saver"))
        assertTrue(BatteryCopy.modeLine(PowerState(userBatterySaver = true)).contains("Walk Buddy's own saver"))
        assertTrue(BatteryCopy.modeLine(PowerState(systemBatterySaver = true, userBatterySaver = true)).contains("both"))
    }

    @Test fun rateLinesComeFromTheRealPlan() {
        val normal = BatteryCopy.rateLines(PowerState(), StepSensorKind.Counter).joinToString("\n")
        assertTrue(normal, normal.contains("5 minutes"))
        assertTrue(normal, normal.contains("5 seconds"))
        val saver = BatteryCopy.rateLines(PowerState(userBatterySaver = true), StepSensorKind.Counter).joinToString("\n")
        assertTrue(saver, saver.contains("10 minutes"))
        assertTrue(saver, saver.contains("8 seconds"))
    }

    @Test fun phoneWithoutACounterSaysSo() {
        val lines = BatteryCopy.rateLines(PowerState(), StepSensorKind.Accelerometer)
        assertTrue(lines.first().contains("no step counter"))
    }

    @Test fun tipsAreHonest() {
        assertTrue(BatteryCopy.TIPS.size >= 4)
        assertTrue(BatteryCopy.TIPS.none { it.contains("guarantee", ignoreCase = true) })
    }
}
