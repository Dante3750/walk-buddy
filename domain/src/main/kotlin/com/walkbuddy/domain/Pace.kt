package com.walkbuddy.domain

/** Cadence-based intensity. Thresholds follow the commonly cited ~100 steps/min (moderate) and ~130 (vigorous) guides. */
enum class PaceZone(val label: String) {
    Standing("Standing"), Easy("Easy"), Brisk("Brisk"), Vigorous("Vigorous");

    companion object {
        const val BRISK_SPM = 100.0
        const val VIGOROUS_SPM = 130.0
        const val MOVING_SPM = 40.0

        fun fromCadence(spm: Double?): PaceZone? = when {
            spm == null || spm.isNaN() -> null
            spm < MOVING_SPM -> Standing
            spm < BRISK_SPM -> Easy
            spm < VIGOROUS_SPM -> Brisk
            else -> Vigorous
        }
    }
}

/** Speed over a sliding time window from cumulative-distance samples. */
class RollingPace(private val windowMs: Long = 45_000) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(tMs: Long, cumulativeM: Double) {
        val last = samples.lastOrNull()
        if (last != null && tMs <= last.first) return
        samples.addLast(tMs to cumulativeM)
        while (samples.size > 1 && tMs - samples.first().first > windowMs) samples.removeFirst()
    }

    /** m/s, or null with fewer than ~5 s of data. */
    fun speedMps(): Double? {
        if (samples.size < 2) return null
        val dt = (samples.last().first - samples.first().first) / 1000.0
        if (dt < 5.0) return null
        return ((samples.last().second - samples.first().second) / dt).coerceAtLeast(0.0)
    }
}

/** Cadence (steps per minute) from step totals over a sliding window. */
class CadenceTracker(private val windowMs: Long = 30_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun add(tMs: Long, totalSteps: Long) {
        val last = samples.lastOrNull()
        if (last != null && tMs <= last.first) return
        samples.addLast(tMs to totalSteps)
        while (samples.size > 1 && tMs - samples.first().first > windowMs) samples.removeFirst()
    }

    fun spm(): Double? {
        if (samples.size < 2) return null
        val dt = (samples.last().first - samples.first().first) / 60_000.0
        if (dt < 5.0 / 60.0) return null
        val d = samples.last().second - samples.first().second
        return (d / dt).coerceAtLeast(0.0)
    }
}

data class PaceSuggestion(val targetMps: Double, val slowestId: String, val fasterIds: List<String>) {
    val targetPace: String get() = Format.pace(targetMps)
}

object PaceMatch {
    /** Below this speed a buddy is considered resting, not "slow". */
    const val RESTING_MPS = 0.4
    /** Only suggest when the fastest is clearly faster than the slowest. */
    const val MIN_GAP_RATIO = 1.15

    /** Suggest everyone aims for the slowest moving buddy's rolling pace. */
    fun suggest(paces: Map<String, Double?>): PaceSuggestion? {
        val moving = paces.mapNotNull { (id, v) -> if (v != null && v >= RESTING_MPS) id to v else null }
        if (moving.size < 2) return null
        val slowest = moving.minBy { it.second }
        val faster = moving.filter { it.second >= slowest.second * MIN_GAP_RATIO }.map { it.first }
        if (faster.isEmpty()) return null
        return PaceSuggestion(slowest.second, slowest.first, faster)
    }
}

enum class BuddyStatus { Moving, Stopped, ConnectionLost, Waiting }

object LinkHealth {
    const val QUIET_AFTER_MS = 12_000L
    const val LOST_AFTER_MS = 30_000L
    const val STOPPED_SPEED_MPS = 0.3

    fun isLost(nowMs: Long, lastHeardMs: Long?): Boolean = lastHeardMs == null || nowMs - lastHeardMs >= LOST_AFTER_MS

    fun status(nowMs: Long, lastHeardMs: Long?, speedMps: Double?, everHeard: Boolean = lastHeardMs != null): BuddyStatus = when {
        !everHeard -> BuddyStatus.Waiting
        isLost(nowMs, lastHeardMs) -> BuddyStatus.ConnectionLost
        speedMps != null && speedMps < STOPPED_SPEED_MPS -> BuddyStatus.Stopped
        else -> BuddyStatus.Moving
    }

    /** Calm, non-alarming wording. */
    fun copy(name: String, s: BuddyStatus): String = when (s) {
        BuddyStatus.Moving -> "$name is on the move"
        BuddyStatus.Stopped -> "$name has paused. No rush."
        BuddyStatus.ConnectionLost -> "We lost $name for a moment. Waiting for them to reconnect."
        BuddyStatus.Waiting -> "Waiting for $name to join"
    }
}
