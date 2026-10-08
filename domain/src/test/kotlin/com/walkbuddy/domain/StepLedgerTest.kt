package com.walkbuddy.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepLedgerTest {
    private val utc = ZoneId.of("UTC")
    private val ny = ZoneId.of("America/New_York")
    private val min = 60_000L

    private fun at(z: ZoneId, y: Int, m: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, m, d, h, mi).atZone(z).toInstant().toEpochMilli()

    private fun day(z: ZoneId, y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    private fun base(counter: Long, t: Long, boot: Long? = 0L) = StepBaseline(counter, t, boot, StepSensorKind.Counter)

    private fun step(prev: StepBaseline?, counter: Long, t: Long, boot: Long? = 0L, z: ZoneId = utc) =
        StepLedger.apply(prev, counter, t, boot, StepSensorKind.Counter, z)

    @Test fun firstReadingOnlySetsTheBaseline() {
        val u = step(null, 5000, 1_000_000)
        assertEquals(BaselineEvent.Started, u.event)
        assertTrue(u.parts.isEmpty())
        assertEquals(5000L, u.baseline.counter)
    }

    @Test fun deltaIsDifferenceToStoredBaseline() {
        val t0 = at(utc, 2026, 5, 10, 9)
        val u = step(base(1000, t0), 1250, t0 + 10 * min)
        assertEquals(250L, u.raw)
        assertEquals(250L, u.verified)
        assertEquals(1, u.parts.size)
        assertEquals(day(utc, 2026, 5, 10), u.parts[0].epochDay)
        assertEquals(9, u.parts[0].hour)
        assertEquals(1250L, u.baseline.counter)
    }

    @Test fun appDeadForHoursStillCountsWhatTheHardwareCounted() {
        // The process was killed all morning; the hardware counter kept going. The next read recovers every step.
        val t0 = at(utc, 2026, 5, 10, 7)
        val u = step(base(100, t0), 4100, t0 + 5 * 60 * min)
        assertEquals(4000L, u.raw)
        assertEquals(4000L, u.verified)
    }

    @Test fun repeatedReadingWithNoChangeAddsNothing() {
        val t0 = 10_000_000L
        val u = step(base(900, t0), 900, t0 + min)
        assertTrue(u.parts.isEmpty())
        assertEquals(t0 + min, u.baseline.tMs)
    }

    @Test fun lowerCounterMeansRebootAndNewValueIsTheDelta() {
        val t0 = at(utc, 2026, 5, 10, 8)
        // Rebooted 20 minutes ago; counter since then is 300.
        val boot = t0 + 40 * min
        val u = step(base(8000, t0, boot = 0L), 300, t0 + 60 * min, boot = boot)
        assertEquals(BaselineEvent.Reboot, u.event)
        assertEquals(300L, u.raw)
        assertEquals(300L, u.verified)
        assertEquals(300L, u.baseline.counter)
    }

    @Test fun rebootIsDetectedByBootTimeEvenIfCounterIsHigherNow() {
        val t0 = at(utc, 2026, 5, 10, 8)
        val boot = t0 + 30 * min
        // Walked 900 steps since the reboot, which is more than the old value of 500: a naive delta would be 400.
        val u = step(base(500, t0, boot = 0L), 900, t0 + 3 * 60 * min, boot = boot)
        assertEquals(BaselineEvent.Reboot, u.event)
        assertEquals(900L, u.raw)
    }

    @Test fun smallBootEstimateDriftIsNotAReboot() {
        val t0 = at(utc, 2026, 5, 10, 8)
        val u = step(base(500, t0, boot = 1_000L), 650, t0 + 20 * min, boot = 1_000L + 30_000)
        assertEquals(BaselineEvent.Normal, u.event)
        assertEquals(150L, u.raw)
    }

    @Test fun rebootTwiceInARowKeepsCounting() {
        var b: StepBaseline = step(null, 0, 0L, boot = 0L).baseline
        var total = 0L
        val readings = listOf(Triple(200L, 10 * min, 0L), Triple(50L, 60 * min, 40 * min), Triple(120L, 90 * min, 40 * min), Triple(10L, 200 * min, 190 * min))
        for ((c, t, bt) in readings) {
            val u = step(b, c, t, boot = bt); total += u.raw; b = u.baseline
        }
        assertEquals(200L + 50 + 70 + 10, total)
    }

    @Test fun midnightCrossingIsSplitByTimeAndSumsExactly() {
        val t0 = at(utc, 2026, 5, 10, 23, 0)
        val t1 = at(utc, 2026, 5, 11, 1, 0)
        val u = step(base(0, t0), 1001, t1)
        assertEquals(2, u.parts.size)
        assertEquals(day(utc, 2026, 5, 10), u.parts[0].epochDay)
        assertEquals(day(utc, 2026, 5, 11), u.parts[1].epochDay)
        assertEquals(1001L, u.raw)
        assertEquals(500L, u.parts[0].raw)
        assertEquals(501L, u.parts[1].raw)
    }

    @Test fun multiDayGapTouchesEveryDay() {
        val t0 = at(utc, 2026, 5, 10, 12)
        val t1 = at(utc, 2026, 5, 13, 12)
        val u = step(base(0, t0), 3000, t1)
        assertEquals(4, u.parts.size)
        assertEquals(3000L, u.raw)
        assertEquals((10..13).map { day(utc, 2026, 5, it) }, u.parts.map { it.epochDay })
        assertTrue(u.parts.all { it.raw > 0 })
    }

    @Test fun dstSpringForwardDayIsTwentyThreeHours() {
        // 2026-03-08 in New York has 23 hours. Interval: 22:00 on the 7th to 02:00 on the 9th = 2h + 23h + 2h = 27h.
        val t0 = at(ny, 2026, 3, 7, 22)
        val t1 = at(ny, 2026, 3, 9, 2)
        val u = step(base(0, t0), 2700, t1, z = ny)
        assertEquals(3, u.parts.size)
        assertEquals(2700L, u.raw)
        assertEquals(200L, u.parts[0].raw)
        assertEquals(2300L, u.parts[1].raw)
        assertEquals(200L, u.parts[2].raw)
    }

    @Test fun dstFallBackDayIsTwentyFiveHours() {
        val t0 = at(ny, 2026, 10, 31, 23)
        val t1 = at(ny, 2026, 11, 2, 1)
        val u = step(base(0, t0), 2700, t1, z = ny)
        // 1h + 25h + 1h = 27h
        assertEquals(listOf(100L, 2500L, 100L), u.parts.map { it.raw })
    }

    @Test fun timezoneChangeUsesTheCurrentZoneForTheDayKey() {
        val t = at(utc, 2026, 5, 10, 23, 30) // 23:30 UTC is already the 11th in Tokyo
        val tokyo = ZoneId.of("Asia/Tokyo")
        val u = step(base(0, t - 5 * min), 100, t, z = tokyo)
        assertEquals(day(tokyo, 2026, 5, 11), u.parts.single().epochDay)
    }

    @Test fun verifiedIsCappedByElapsedTimeButRawIsNot() {
        val t0 = 50_000_000L
        val u = step(base(0, t0), 5000, t0 + min) // 5000 steps in one minute is not humanly possible
        assertEquals(5000L, u.raw)
        assertEquals(200L, u.verified)
    }

    @Test fun verifiedCapHasAFloorForVeryShortIntervals() {
        val t0 = 50_000_000L
        val u = step(base(0, t0), 6, t0 + 1_000)
        assertEquals(6L, u.verified)
    }

    @Test fun clockSetBackStillCountsAndKeepsAllSteps() {
        val t0 = at(utc, 2026, 5, 10, 12)
        val u = step(base(100, t0), 400, t0 - 3 * 60 * min)
        assertEquals(BaselineEvent.ClockBack, u.event)
        assertEquals(300L, u.raw)
        assertEquals(300L, u.verified)
        assertEquals(t0 - 3 * 60 * min, u.baseline.tMs)
    }

    @Test fun changingSensorKindStartsOver() {
        val prev = StepBaseline(700, 1000, null, StepSensorKind.Detector)
        val u = StepLedger.apply(prev, 9000, 2000, 0L, StepSensorKind.Counter, utc)
        assertEquals(BaselineEvent.Started, u.event)
        assertTrue(u.parts.isEmpty())
    }

    @Test fun fallbackCountersIgnoreBootTime() {
        val prev = StepBaseline(10, 1_000_000, null, StepSensorKind.Detector)
        val u = StepLedger.apply(prev, 25, 1_000_000 + 5 * min, 99_999_999L, StepSensorKind.Detector, utc)
        assertEquals(15L, u.raw)
        assertNull(u.baseline.bootMs)
    }

    @Test fun statusPicksTheMostHelpfulMessage() {
        assertEquals(StepCountingStatus.NoSensor, StepHealth.status(null, true, false, false))
        assertEquals(StepCountingStatus.NeedsPermission, StepHealth.status(StepSensorKind.Counter, true, false, false))
        assertEquals(StepCountingStatus.PermissionBlocked, StepHealth.status(StepSensorKind.Counter, true, false, true))
        assertEquals(StepCountingStatus.Counting, StepHealth.status(StepSensorKind.Counter, true, true, true))
        assertEquals(StepCountingStatus.Counting, StepHealth.status(StepSensorKind.Counter, false, false, false))
    }

    @Test fun agoText() {
        val now = 10_000_000_000L
        assertEquals("never", StepHealth.ago(now, null))
        assertEquals("just now", StepHealth.ago(now, now - 20_000))
        assertEquals("5 min ago", StepHealth.ago(now, now - 5 * min))
        assertEquals("3 h ago", StepHealth.ago(now, now - 3 * 60 * min))
        assertEquals("1 day ago", StepHealth.ago(now, now - 30 * 60 * min))
        assertEquals("2 days ago", StepHealth.ago(now, now - 50 * 60 * min))
    }

    // ---- accelerometer fallback ----

    private fun runDetector(seconds: Int, hz: Double, ampl: Double, noise: Double = 0.0, rateHz: Int = 50): Int {
        val d = AccelStepDetector()
        var steps = 0
        val rnd = java.util.Random(7)
        val n = seconds * rateHz
        for (i in 0 until n) {
            val t = i * 1000L / rateHz
            val w = ampl * Math.sin(2 * Math.PI * hz * t / 1000.0)
            steps += d.onSample(t, 0.0, 0.0, 9.81 + w + noise * rnd.nextGaussian())
        }
        return steps
    }

    @Test fun accelDetectorCountsASteadyWalk() {
        val steps = runDetector(20, 2.0, 3.0) // 2 steps a second for 20 s = 40
        assertTrue("got $steps", steps in 34..42)
    }

    @Test fun accelDetectorIgnoresAPhoneLyingStill() {
        assertEquals(0, runDetector(30, 0.0, 0.0, noise = 0.15))
    }

    @Test fun accelDetectorIgnoresFastShaking() {
        assertEquals(0, runDetector(10, 8.0, 4.0))
    }

    @Test fun accelDetectorIgnoresASingleBump() {
        val d = AccelStepDetector()
        var steps = 0
        for (i in 0 until 500) {
            val t = i * 20L
            val bump = if (i in 100..104) 6.0 else 0.0
            steps += d.onSample(t, 0.0, 0.0, 9.81 + bump)
        }
        assertEquals(0, steps)
    }
}
