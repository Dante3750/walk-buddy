package com.walkbuddy.domain

data class TogetherSnapshot(
    val walkMs: Long,
    val togetherMs: Long,
    val scorePct: Int,
    val currentStreakMs: Long,
    val longestStreakMs: Long,
)

/**
 * "Together score": share of walk time during which the whole group was within [radiusM].
 * Time with unknown distance (someone not connected) counts as walk time but not togetherness.
 * Gaps between updates longer than [maxGapMs] are clamped so a paused app does not distort the score.
 */
class TogetherTracker(var radiusM: Double = DEFAULT_RADIUS_M, private val maxGapMs: Long = 15_000) {
    private var lastT: Long? = null
    var walkMs = 0L; private set
    var togetherMs = 0L; private set
    var currentStreakMs = 0L; private set
    var longestStreakMs = 0L; private set

    /** [spreadM] is the largest pairwise distance in the group, null if not everyone is known. */
    fun update(tMs: Long, spreadM: Double?) {
        val prev = lastT
        lastT = tMs
        if (prev == null || tMs <= prev) {
            return
        }
        val dt = minOf(tMs - prev, maxGapMs)
        walkMs += dt
        // The interval is credited using the state at its end, which is when we learned it.
        val together = spreadM != null && spreadM <= radiusM
        if (together) {
            togetherMs += dt
            currentStreakMs += dt
            if (currentStreakMs > longestStreakMs) longestStreakMs = currentStreakMs
        } else {
            currentStreakMs = 0
        }
    }

    fun snapshot(): TogetherSnapshot {
        val pct = if (walkMs <= 0) 0 else Math.round(togetherMs * 100.0 / walkMs).toInt().coerceIn(0, 100)
        return TogetherSnapshot(walkMs, togetherMs, pct, currentStreakMs, longestStreakMs)
    }

    companion object {
        const val DEFAULT_RADIUS_M = 50.0
    }
}

enum class NudgeKind { CatchUp, EaseOff, PaceMatch }

data class Nudge(val kind: NudgeKind, val text: String)

data class NudgeConfig(
    /** Start counting a "gap" above this distance. */
    val farM: Double = 100.0,
    /** The gap must fall below this before another nudge can arm (hysteresis). */
    val nearM: Double = 60.0,
    /** Gap must persist this long before a nudge fires. */
    val sustainMs: Long = 20_000,
    val cooldownMs: Long = 3 * 60_000L,
    val maxPerWalk: Int = 4,
    /** Pace-sync mode: the faster partner gets the gentle nudge instead of the one behind. */
    val paceSync: Boolean = false,
    /** Quiet mode mutes every nudge for the whole walk. */
    val quiet: Boolean = false,
)

data class NudgeContext(
    val nowMs: Long,
    val gapM: Double?,
    /** True if I am the one further along the direction of travel. */
    val iAmAhead: Boolean?,
    val iAmMoving: Boolean,
    val buddyMoving: Boolean,
    val buddyConnected: Boolean,
    val buddyName: String,
)

/**
 * Decides when to show the local user a gentle nudge. Never spams: needs a sustained gap, hysteresis re-arm,
 * a cooldown, a per-walk cap, and stays silent when the buddy paused, disconnected or quiet mode is on.
 */
class NudgeEngine(private val config: NudgeConfig = NudgeConfig()) {
    private var gapSinceMs: Long? = null
    private var armed = true
    private var lastNudgeMs: Long? = null
    var count = 0
        private set

    fun evaluate(c: NudgeContext): Nudge? {
        val gap = c.gapM
        if (gap == null || !c.buddyConnected) { gapSinceMs = null; return null }
        if (gap <= config.nearM) { armed = true; gapSinceMs = null; return null }
        if (gap <= config.farM) { gapSinceMs = null; return null }
        // Far apart from here on.
        if (config.quiet || !armed || count >= config.maxPerWalk) return null
        if (!c.buddyMoving || !c.iAmMoving) { gapSinceMs = null; return null }
        val ahead = c.iAmAhead ?: return null
        val since = gapSinceMs ?: c.nowMs.also { gapSinceMs = it }
        if (c.nowMs - since < config.sustainMs) return null
        lastNudgeMs?.let { if (c.nowMs - it < config.cooldownMs) return null }

        val nudge = when {
            config.paceSync -> if (ahead) Nudge(NudgeKind.EaseOff, "${c.buddyName} is a little way back. An easier pace lets you walk together again.") else null
            else -> if (!ahead) Nudge(NudgeKind.CatchUp, "${c.buddyName} is a bit ahead. A slightly quicker step brings you back together.") else null
        } ?: return null
        armed = false
        lastNudgeMs = c.nowMs
        gapSinceMs = null
        count++
        return nudge
    }
}
