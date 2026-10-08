package com.walkbuddy.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.min
import kotlin.random.Random

/**
 * Everything the policy needs to know about the phone and the user right now. Plain values, so the decisions are unit-testable
 * and the Android side only has to report facts (see PowerMonitor in :app).
 */
data class PowerState(
    /** The app has a visible activity (started, not stopped). False with the screen off or the app in the background. */
    val screenVisible: Boolean = false,
    /** A partner or group walk is being tracked (location + live step feed are wanted). */
    val walkActive: Boolean = false,
    /** The user is actually moving (see [MotionGate]); only meaningful while [walkActive]. */
    val moving: Boolean = false,
    /** People in the walk, me included. 1 when alone. */
    val groupSize: Int = 1,
    /** Android's own Battery Saver (PowerManager.isPowerSaveMode). */
    val systemBatterySaver: Boolean = false,
    /** Walk Buddy's own "Battery saver mode" switch in Settings (extra-low rates). */
    val userBatterySaver: Boolean = false,
    val charging: Boolean = false,
)

enum class PowerMode(val label: String) {
    /** Normal rates. */
    Balanced("Balanced"),
    /** Android Battery Saver or Walk Buddy's saver is on: everything runs at the lowest sensible rate. */
    Saving("Saving battery"),
    /** On a charger: no need to be frugal, freshness wins, but nothing is made more expensive than Balanced. */
    Charging("Charging"),
}

/** How often and how precisely to ask for location. [intervalMs] is the minimum time between fixes, [minDistanceM] the minimum movement. */
data class LocationPlan(val intervalMs: Long, val minDistanceM: Float)

/**
 * The decisions, for one [PowerState]. Null means "do not do this at all" (no location updates without a walk, no accelerometer
 * listener in the background).
 */
data class SamplingPlan(
    val mode: PowerMode,
    /** Step sensor maxReportLatency in ms (0 = deliver immediately), or null = keep no listener registered. */
    val sensorLatencyMs: Long?,
    val location: LocationPlan?,
    /** Minimum time between location/step updates sent to the group or partner. */
    val sendIntervalMs: Long,
    /** Period of the walk's own bookkeeping loop (UI state and engine tick). */
    val tickMs: Long,
    /** How often the partner's "daily steps" ring value is re-sent. */
    val dailyBroadcastMs: Long,
    /** The ongoing walk notification is refreshed at most this often. */
    val walkNotifyMs: Long,
    /** The all-day step notification is refreshed when steps changed by this much AND [stepsNotifyMinMs] passed. */
    val stepsNotifyDelta: Int,
    val stepsNotifyMinMs: Long,
    /** The home-screen widget is refreshed at most this often. */
    val widgetMinMs: Long,
    /** The WorkManager safety net skips a run that comes sooner than this after the previous one. */
    val workerMinGapMs: Long,
)

object SamplingPolicy {
    /** A peer is shown as lost after [LinkHealth.LOST_AFTER_MS]; never send slower than a third of that. */
    const val MAX_SEND_INTERVAL_MS = 10_000L
    /** Largest hidden batching for the step counter. It is cumulative and kept by hardware, so nothing is lost; it only delays delivery. */
    const val MAX_SENSOR_LATENCY_MS = 600_000L

    fun mode(s: PowerState): PowerMode = when {
        s.systemBatterySaver || s.userBatterySaver -> PowerMode.Saving
        s.charging -> PowerMode.Charging
        else -> PowerMode.Balanced
    }

    fun plan(s: PowerState, kind: StepSensorKind?): SamplingPlan {
        val mode = mode(s)
        val saving = mode == PowerMode.Saving
        val charging = mode == PowerMode.Charging
        val tracking = s.walkActive
        val seen = s.screenVisible
        return SamplingPlan(
            mode = mode,
            sensorLatencyMs = sensorLatencyMs(s, kind, mode),
            location = if (!tracking) null else locationPlan(s, saving),
            sendIntervalMs = sendIntervalMs(s, saving),
            tickMs = when {
                seen -> if (saving) 2_000L else 1_000L
                else -> if (saving) 10_000L else 5_000L
            },
            dailyBroadcastMs = when {
                seen -> if (saving) 60_000L else 30_000L
                else -> if (saving) 300_000L else 120_000L
            },
            walkNotifyMs = when {
                saving -> 30_000L
                seen -> 10_000L
                else -> 15_000L
            },
            stepsNotifyDelta = when {
                seen -> 20
                saving -> 250
                else -> 100
            },
            stepsNotifyMinMs = when {
                seen -> 15_000L
                saving -> 15 * 60_000L
                else -> 5 * 60_000L
            },
            widgetMinMs = when {
                seen -> 60_000L
                saving -> 15 * 60_000L
                charging -> 2 * 60_000L
                else -> 5 * 60_000L
            },
            workerMinGapMs = if (saving) 45 * 60_000L else 15 * 60_000L,
        )
    }

    private fun sensorLatencyMs(s: PowerState, kind: StepSensorKind?, mode: PowerMode): Long? {
        val saving = mode == PowerMode.Saving
        return when (kind) {
            null -> null
            // No hardware counter: the accelerometer only works while the CPU is awake, so it is on for the screen or a walk and nothing else.
            StepSensorKind.Accelerometer -> if (s.screenVisible || s.walkActive) 0L else null
            StepSensorKind.Counter, StepSensorKind.Detector -> when {
                s.screenVisible -> 0L
                s.walkActive -> if (saving) 10_000L else 5_000L
                saving -> MAX_SENSOR_LATENCY_MS
                mode == PowerMode.Charging -> 60_000L
                else -> 300_000L
            }
        }
    }

    private fun locationPlan(s: PowerState, saving: Boolean): LocationPlan = when {
        saving -> if (s.moving) LocationPlan(8_000, 10f) else LocationPlan(20_000, 15f)
        s.moving -> if (s.screenVisible) LocationPlan(3_000, 3f) else LocationPlan(5_000, 5f)
        else -> if (s.screenVisible) LocationPlan(6_000, 6f) else LocationPlan(10_000, 10f)
    }

    private fun sendIntervalMs(s: PowerState, saving: Boolean): Long {
        var ms = when {
            s.moving -> if (s.screenVisible) 3_000L else 5_000L
            else -> 8_000L
        }
        if (s.groupSize > 10) ms = ms * 3 / 2
        if (saving) ms = ms * 3 / 2
        return min(ms, MAX_SEND_INTERVAL_MS)
    }
}

/**
 * "Am I moving?" with hysteresis, so a pause at a crossing or one noisy GPS speed does not flip the location rate back and forth.
 * Moving starts at [startMps]; it stops only after [stillAfterMs] below [stopMps]. No speed known means "keep the last answer".
 */
class MotionGate(
    private val startMps: Double = 0.6,
    private val stopMps: Double = 0.3,
    private val stillAfterMs: Long = 20_000,
) {
    var moving: Boolean = false
        private set
    private var slowSinceMs: Long? = null

    fun update(nowMs: Long, speedMps: Double?): Boolean {
        if (speedMps == null) return moving
        if (speedMps >= startMps) {
            moving = true
            slowSinceMs = null
        } else if (speedMps < stopMps) {
            val since = slowSinceMs ?: nowMs.also { slowSinceMs = it }
            if (moving && nowMs - since >= stillAfterMs) moving = false
        } else {
            // In between: neither clearly moving nor clearly stopped. Do not start the stop timer, do not stop.
            slowSinceMs = null
        }
        return moving
    }

    fun reset() { moving = false; slowSinceMs = null }
}

/**
 * Spaces out messages to the group or partner. [poll] is called every loop and says when the regular cadence is due;
 * [urgent] is for "a newcomer needs my position now" and is allowed at most once per [minGapMs]; one that is refused is remembered
 * and goes out on the next [poll], so bursts coalesce into a single message and nothing is lost.
 */
class SendGate(private val minGapMs: Long = 1_500) {
    private companion object { const val SLACK_MS = 300L }

    private var lastMs: Long? = null
    private var pending = false

    fun poll(nowMs: Long, intervalMs: Long): Boolean {
        val last = lastMs
        // A little slack: a loop that wakes every [intervalMs] can arrive a few ms early by the wall clock and must not slip a whole round.
        val due = last == null || nowMs - last >= intervalMs - SLACK_MS || nowMs < last
        val wanted = due || (pending && nowMs - last!! >= minGapMs)
        if (wanted) { lastMs = nowMs; pending = false }
        return wanted
    }

    fun urgent(nowMs: Long): Boolean {
        val last = lastMs
        if (last == null || nowMs - last >= minGapMs || nowMs < last) { lastMs = nowMs; pending = false; return true }
        pending = true
        return false
    }

    fun reset() { lastMs = null; pending = false }
}

/** Reconnect delays: exponential with "equal jitter" (half fixed, half random), so phones that lost the server together do not retry together. */
object Backoff {
    const val BASE_MS = 2_000L
    const val CAP_MS = 60_000L

    /** [attempt] starts at 1. The result is in (exp/2, exp], where exp = min(cap, base * 2^(attempt-1)). */
    fun delayMs(attempt: Int, random: Random = Random.Default, baseMs: Long = BASE_MS, capMs: Long = CAP_MS): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        val exp = min(capMs, baseMs shl shift)
        val half = exp / 2
        return half + (random.nextDouble() * half).toLong()
    }
}

/**
 * Decides when the all-day step notification is worth re-posting: only when the number moved enough AND enough time passed,
 * or on [force] (the app just became visible, or a new day started). Never posts an unchanged number.
 */
class StepNotifyThrottle {
    private var shown = -1
    private var atMs = 0L

    fun shouldPost(steps: Int, nowMs: Long, minDelta: Int, minIntervalMs: Long, force: Boolean = false): Boolean {
        val post = when {
            shown < 0 -> true
            steps == shown -> false
            steps < shown -> true // a new day (or a data reset): always show the lower number
            force -> true
            steps - shown < minDelta -> false
            nowMs - atMs < minIntervalMs && nowMs >= atMs -> false
            else -> true
        }
        if (post) { shown = steps; atMs = nowMs }
        return post
    }

    fun reset() { shown = -1; atMs = 0L }
}

/** Which clock hour a step batch belongs to; batches are merged only inside one hour so hourly and daily totals stay right. */
object StepBuckets {
    fun sameHour(aMs: Long, bMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val a = Instant.ofEpochMilli(aMs).atZone(zone)
        val b = Instant.ofEpochMilli(bMs).atZone(zone)
        return a.toLocalDate() == b.toLocalDate() && a.hour == b.hour
    }
}

/** Plain-language text for the Settings "Battery" card, generated from the same policy the app really runs, so it cannot drift from it. */
object BatteryCopy {
    fun modeLine(s: PowerState): String = when (SamplingPolicy.mode(s)) {
        PowerMode.Saving -> when {
            s.systemBatterySaver && s.userBatterySaver -> "Saving battery: Android Battery Saver and Walk Buddy's saver are both on."
            s.systemBatterySaver -> "Saving battery: Android Battery Saver is on, so Walk Buddy uses its lowest rates."
            else -> "Saving battery: Walk Buddy's own saver is on."
        }
        PowerMode.Charging -> "Charging: on a charger there is no need to save, so numbers refresh a little sooner."
        PowerMode.Balanced -> "Balanced: normal rates."
    }

    /** Lines describing what the current mode does, from the real plan (screen off, and a moving walk with one buddy). */
    fun rateLines(s: PowerState, kind: StepSensorKind?): List<String> {
        val idle = SamplingPolicy.plan(s.copy(screenVisible = false, walkActive = false, moving = false, groupSize = 1), kind)
        val walk = SamplingPolicy.plan(s.copy(screenVisible = false, walkActive = true, moving = true, groupSize = 2), kind)
        val lines = ArrayList<String>()
        lines += when (val l = idle.sensorLatencyMs) {
            null -> "Step counting: this phone has no step counter, so steps are counted only while the app is open or a walk is running."
            0L -> "Step counting: the step sensor reports immediately."
            else -> "Step counting: with the screen off, the step sensor holds its readings for up to ${minutes(l)} and hands them over together. Nothing is lost, the number just appears a little later."
        }
        val loc = walk.location
        if (loc != null) lines += "During a walk with the screen off: GPS about every ${seconds(loc.intervalMs)} while moving, slower when you stop. Never outside a walk."
        lines += "Walk and group updates: about every ${seconds(walk.sendIntervalMs)} while moving. The step notification updates about every ${idle.stepsNotifyDelta} steps."
        return lines
    }

    val TIPS: List<String> = listOf(
        "Walk Buddy never uses GPS just to count steps, and it holds no wake lock.",
        "Turn on Battery saver mode, or Android's own Battery Saver, for the lowest rates. Step counts stay exact.",
        "The biggest costs are GPS and the screen during a walk. Lock the phone while you walk; the walk keeps going.",
        "Group walks use the network too. A group of 10 or more is slowed a little automatically.",
        "Unrestricted battery use keeps step counting alive on phones that stop background apps, at a small cost. Leave it optimised if your steps keep counting.",
    )

    private fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m <= 1) "a minute" else "$m minutes"
    }

    private fun seconds(ms: Long): String = "${ms / 1000} seconds"
}
