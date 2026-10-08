package com.walkbuddy.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Which sensor feeds the step count. Counter is the real thing; the others are fallbacks for phones without it. */
enum class StepSensorKind { Counter, Detector, Accelerometer }

/**
 * The last reading we accounted for. [counter] is the hardware cumulative counter (steps since boot) for [StepSensorKind.Counter],
 * or our own running total for the fallbacks. [bootMs] is the estimated wall-clock time of the last boot (now minus uptime) and is
 * only meaningful for the hardware counter.
 */
data class StepBaseline(val counter: Long, val tMs: Long, val bootMs: Long?, val kind: StepSensorKind)

/** Steps to credit to one local day. [hour] is the local hour (0..23) at the end of that day's slice, for the hour histogram. */
data class DaySteps(val epochDay: Long, val raw: Long, val verified: Long, val hour: Int)

enum class BaselineEvent { Started, Normal, Reboot, ClockBack }

data class StepUpdate(val baseline: StepBaseline, val parts: List<DaySteps>, val event: BaselineEvent) {
    val raw: Long get() = parts.sumOf { it.raw }
    val verified: Long get() = parts.sumOf { it.verified }
}

/**
 * Pure maths for always-on step counting. Android supplies readings, this decides what they mean:
 *  - The hardware counter counts steps since boot, even while the app is dead. The difference to the stored baseline is the delta.
 *  - A lower value than stored means the phone rebooted; the new value is the delta (steps since that boot).
 *  - A boot time that moved later than before (beyond [REBOOT_TOLERANCE_MS]) is also a reboot, even if the counter happens to be higher.
 *  - A delta covering several hours or crossing midnight is spread over the local days it spans (DST aware), summing exactly.
 *  - "Verified" caps the delta at a human maximum cadence for the elapsed time. Raw is never capped.
 */
object StepLedger {
    const val MAX_STEPS_PER_MIN = 200.0
    const val REBOOT_TOLERANCE_MS = 10 * 60_000L
    private const val MAX_SPLIT_DAYS = 400

    fun apply(
        prev: StepBaseline?,
        counter: Long,
        tMs: Long,
        bootMs: Long?,
        kind: StepSensorKind,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StepUpdate {
        val fresh = StepBaseline(counter.coerceAtLeast(0), tMs, if (kind == StepSensorKind.Counter) bootMs else null, kind)
        if (prev == null || prev.kind != kind) return StepUpdate(fresh, emptyList(), BaselineEvent.Started)

        val rebooted = kind == StepSensorKind.Counter && (
            counter < prev.counter ||
                (bootMs != null && prev.bootMs != null && bootMs - prev.bootMs > REBOOT_TOLERANCE_MS)
            )
        val raw = if (rebooted) counter.coerceAtLeast(0) else counter - prev.counter
        val event = if (rebooted) BaselineEvent.Reboot else BaselineEvent.Normal
        if (raw <= 0) return StepUpdate(fresh, emptyList(), event)

        // Steps after a reboot all happened since the boot, so the interval starts there.
        val startMs = if (rebooted && bootMs != null) maxOf(prev.tMs, bootMs) else prev.tMs
        val clockBack = tMs < startMs
        val verified = if (clockBack) {
            raw // the wall clock was set back, so elapsed time is unknowable: trust the sensor
        } else {
            val minutes = (tMs - startMs) / 60_000.0
            minOf(raw, (MAX_STEPS_PER_MIN * minutes).toLong().coerceAtLeast(10))
        }
        val parts = if (clockBack) {
            listOf(DaySteps(epochDay(tMs, zone), raw, verified, hourOf(tMs, zone)))
        } else {
            DaySplit.split(startMs, tMs, raw, verified, zone)
        }
        return StepUpdate(fresh, parts, if (clockBack && !rebooted) BaselineEvent.ClockBack else event)
    }

    fun epochDay(ms: Long, zone: ZoneId): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    fun hourOf(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).hour

    /** Boot time estimate from a wall-clock reading and uptime (SystemClock.elapsedRealtime). */
    fun bootMs(nowMs: Long, uptimeMs: Long): Long = nowMs - uptimeMs

    /** True when the two boot estimates are the same boot (clock drift and NTP corrections are tolerated). */
    fun sameBoot(a: Long, b: Long): Boolean = abs(a - b) <= REBOOT_TOLERANCE_MS

    internal fun maxDays() = MAX_SPLIT_DAYS
}

/** Splits an interval's steps across the local days it touches, proportionally to the time spent in each. Totals are exact. */
object DaySplit {
    fun split(startMs: Long, endMs: Long, raw: Long, verified: Long, zone: ZoneId): List<DaySteps> {
        val endDay = StepLedger.epochDay(endMs, zone)
        val startDay = StepLedger.epochDay(startMs, zone)
        if (endMs <= startMs || startDay >= endDay || endDay - startDay > StepLedger.maxDays()) {
            return listOf(DaySteps(endDay, raw, verified, StepLedger.hourOf(endMs, zone)))
        }
        // Slice boundaries: start, each local midnight in between, end.
        val bounds = ArrayList<Long>()
        bounds += startMs
        var d = startDay + 1
        while (d <= endDay) {
            val mid = java.time.LocalDate.ofEpochDay(d).atStartOfDay(zone).toInstant().toEpochMilli()
            if (mid > startMs && mid < endMs) bounds += mid
            d++
        }
        bounds += endMs
        val total = (endMs - startMs).toDouble()
        val n = bounds.size - 1
        val rawParts = LongArray(n)
        val verParts = LongArray(n)
        var rawLeft = raw
        var verLeft = verified
        for (i in 0 until n) {
            val share = (bounds[i + 1] - bounds[i]) / total
            if (i == n - 1) {
                rawParts[i] = rawLeft; verParts[i] = verLeft
            } else {
                rawParts[i] = (raw * share).toLong().coerceIn(0, rawLeft)
                verParts[i] = (verified * share).toLong().coerceIn(0, verLeft)
                rawLeft -= rawParts[i]; verLeft -= verParts[i]
            }
        }
        return (0 until n).mapNotNull { i ->
            if (rawParts[i] == 0L && verParts[i] == 0L) null
            else DaySteps(StepLedger.epochDay(bounds[i], zone), rawParts[i], verParts[i], StepLedger.hourOf(bounds[i + 1] - 1, zone))
        }
    }
}

/** What the user sees about step counting. Pure so the UI and the settings row agree. */
enum class StepCountingStatus { Counting, NeedsPermission, PermissionBlocked, NoSensor }

object StepHealth {
    /**
     * [permissionNeeded]: Android 10+ asks for physical activity before the step sensors report anything.
     * [blocked]: the system will no longer show the permission dialog (denied twice or "don't ask again"); only app settings can fix it.
     */
    fun status(kind: StepSensorKind?, permissionNeeded: Boolean, granted: Boolean, blocked: Boolean): StepCountingStatus = when {
        kind == null -> StepCountingStatus.NoSensor
        permissionNeeded && !granted -> if (blocked) StepCountingStatus.PermissionBlocked else StepCountingStatus.NeedsPermission
        else -> StepCountingStatus.Counting
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago", or "never". */
    fun ago(nowMs: Long, thenMs: Long?): String {
        if (thenMs == null || thenMs <= 0) return "never"
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} day${if (s / 86_400 == 1L) "" else "s"} ago"
        }
    }
}

/**
 * Accelerometer step detector for phones with no step counter or step detector. Needs a steady rhythm of at least three peaks
 * 0.25 to 1.0 s apart before it counts (the first three are credited when the third arrives), so shaking and fidgeting are ignored.
 * It only runs while the CPU is awake, which is why it is a last resort.
 */
class AccelStepDetector(
    private val thresholdMs2: Double = 1.2,
    private val minGapMs: Long = 250,
    private val maxGapMs: Long = 1_000,
) {
    private var gravity = 9.81
    private var smooth = 0.0
    private var prev = 0.0
    private var prevPrev = 0.0
    private var lastPeakMs = -1L
    private var chain = 0
    private var primed = 0
    private var tooFast = 0
    private var lastAnyPeakMs = -1L

    /** Feed one accelerometer sample; returns the number of steps to add now (0, 1, or 3 when a walking rhythm is first confirmed). */
    fun onSample(tMs: Long, x: Double, y: Double, z: Double): Int {
        val mag = Math.sqrt(x * x + y * y + z * z)
        gravity += 0.02 * (mag - gravity)
        smooth += 0.35 * ((mag - gravity) - smooth)
        val isPeak = primed >= 2 && prev > prevPrev && prev >= smooth && prev > thresholdMs2
        val peakT = tMs
        prevPrev = prev
        prev = smooth
        if (primed < 2) primed++
        if (!isPeak) return 0
        val sinceAny = if (lastAnyPeakMs >= 0) peakT - lastAnyPeakMs else Long.MAX_VALUE
        lastAnyPeakMs = peakT
        if (sinceAny < minGapMs) {
            // Too close to the previous bump: a double bump while walking, or shaking. Several in a row break the rhythm.
            if (++tooFast >= 3) { chain = 0; lastPeakMs = -1L; tooFast = 0 }
            return 0
        }
        tooFast = 0
        chain = if (lastPeakMs >= 0 && peakT - lastPeakMs <= maxGapMs) chain + 1 else 1
        lastPeakMs = peakT
        return when {
            chain == 3 -> 3
            chain > 3 -> 1
            else -> 0
        }
    }
}
