package com.walkbuddy.domain

enum class ActivityState { Unknown, Still, Walking, Running, OnBicycle, InVehicle }

/**
 * Lightweight activity guess that needs no Google Play Services: GPS speed and step cadence only.
 * Not as smart as a platform recognizer, but good enough to keep car rides and cycling out of the step count.
 */
object ActivityClassifier {
    const val WALK_MAX_MPS = 3.5
    const val VEHICLE_MPS = 7.0

    fun classify(speedMps: Double?, cadenceSpm: Double?): ActivityState {
        val v = speedMps
        val c = cadenceSpm
        if (v != null && v > VEHICLE_MPS) return ActivityState.InVehicle
        if (v != null && v > WALK_MAX_MPS) {
            return if (c != null && c >= 60.0) ActivityState.Running else if (c != null && c < 20.0 && v > 5.5) ActivityState.InVehicle else ActivityState.OnBicycle
        }
        if (c == null) return if (v != null && v < 0.3) ActivityState.Still else ActivityState.Unknown
        return when {
            c >= 150.0 -> ActivityState.Running
            c >= 30.0 -> ActivityState.Walking
            c < 10.0 && (v == null || v < 0.5) -> ActivityState.Still
            else -> ActivityState.Unknown
        }
    }
}

data class StepTotals(val raw: Long, val verified: Long) {
    val ignored: Long get() = raw - verified
}

/**
 * "Honest steps". Feed it step-counter totals; it keeps raw and verified totals.
 * A delta is verified only if the activity is Walking/Running/Unknown (not Still, OnBicycle, InVehicle),
 * GPS speed has not been above [maxWalkSpeedMps] for [sustainMs], and the rate is humanly possible.
 */
class VerifiedStepsFilter(
    private val maxWalkSpeedMps: Double = 3.5,
    private val sustainMs: Long = 8_000,
    private val maxStepsPerSec: Double = 5.0,
) {
    private var lastCounter: Long? = null
    private var lastT: Long? = null
    private var fastSinceMs: Long? = null
    var raw = 0L; private set
    var verified = 0L; private set

    fun totals() = StepTotals(raw, verified)

    fun onSample(tMs: Long, counterTotal: Long, activity: ActivityState, gpsSpeedMps: Double?): Long {
        // Track sustained speed first so a long fast stretch is recognised even without step deltas.
        if (gpsSpeedMps != null && gpsSpeedMps > maxWalkSpeedMps) {
            if (fastSinceMs == null) fastSinceMs = tMs
        } else if (gpsSpeedMps != null) {
            fastSinceMs = null
        }
        val prevC = lastCounter
        val prevT = lastT
        lastCounter = counterTotal
        lastT = tMs
        if (prevC == null || prevT == null) return 0
        // Counter reset (reboot): the new value is steps since reset.
        val rawDelta = if (counterTotal >= prevC) counterTotal - prevC else counterTotal
        if (rawDelta <= 0) return 0
        raw += rawDelta
        val dtSec = ((tMs - prevT) / 1000.0).coerceAtLeast(1.0)
        val plausible = minOf(rawDelta.toDouble(), maxStepsPerSec * dtSec + 2).toLong()
        val fastFor = fastSinceMs?.let { tMs - it } ?: 0L
        val activityOk = activity == ActivityState.Walking || activity == ActivityState.Running || activity == ActivityState.Unknown
        val speedOk = fastFor < sustainMs
        val ok = activityOk && speedOk
        if (ok) verified += plausible
        return if (ok) plausible else 0
    }
}

data class StepDelta(val raw: Long, val verified: Long)

/**
 * Daily steps outside tracked walks, from occasional step-counter readings (for example every 15 minutes).
 * Without GPS or a recognizer we can only apply a plausibility check: a delta is verified if it implies at most
 * [maxSpm] steps per minute on average (nobody walks 400 steps a minute). State is plain numbers so the app can persist it.
 */
class DailyStepAccumulator(var lastCounter: Long? = null, var lastTMs: Long? = null, private val maxSpm: Double = 200.0) {
    fun onReading(tMs: Long, counter: Long): StepDelta {
        val pc = lastCounter
        val pt = lastTMs
        lastCounter = counter
        lastTMs = tMs
        if (pc == null || pt == null || tMs <= pt) return StepDelta(0, 0)
        val raw = if (counter >= pc) counter - pc else counter // counter resets on reboot
        if (raw <= 0) return StepDelta(0, 0)
        val minutes = (tMs - pt) / 60_000.0
        val cap = (maxSpm * minutes).toLong().coerceAtLeast(10)
        return StepDelta(raw, minOf(raw, cap))
    }

    /** Records a baseline without counting (used when a walk already counted the steps). */
    fun rebase(tMs: Long, counter: Long) { lastCounter = counter; lastTMs = tMs }
}
