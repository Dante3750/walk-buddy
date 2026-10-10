package com.walkbuddy.domain

/**
 * Gentle pace coaching (alpha 2.0), off by default. It never scolds: after a pace difference has lasted a while it may suggest one small
 * thing, then keeps quiet for minutes. Quiet mode and quiet hours switch it off, and a sweeper (who walks at the back on purpose) is
 * never asked to speed up. Pure Kotlin so the rules are tested.
 */
enum class CoachMode {
    Off,
    /** Hints either way when my pace is clearly different from the others. */
    Gentle,
    /** "Walk at the slower person's pace": only the faster walker is asked to ease off. */
    SlowestPace;

    companion object {
        fun fromName(n: String?): CoachMode = values().firstOrNull { it.name == n } ?: Off
    }
}

data class CoachConfig(
    val mode: CoachMode = CoachMode.Off,
    val cooldownMs: Long = 4 * 60_000L,
    /** How different my pace must be, as a fraction of the reference pace. */
    val diffFraction: Double = 0.25,
    /** The difference must last this long before anything is said. */
    val sustainMs: Long = 40_000L,
    val minMovingMps: Double = 0.6,
    val maxPerWalk: Int = 6,
)

data class CoachInput(
    val nowMs: Long,
    val mySpeedMps: Double?,
    /** Speeds of the others who are connected and sharing; null for unknown. */
    val othersMps: List<Double?>,
    val iAmSweeper: Boolean,
    val quiet: Boolean,
    val quietHours: Boolean,
)

sealed class CoachAdvice {
    object None : CoachAdvice()

    /** I am clearly faster than [targetMps]: ease off a little. */
    data class EaseOff(val targetMps: Double) : CoachAdvice()

    /** I am clearly slower than [targetMps]: a small nudge to keep up, never a demand. */
    data class PickUp(val targetMps: Double) : CoachAdvice()
}

class PaceCoach(var config: CoachConfig = CoachConfig()) {
    private var since: Long? = null
    private var sinceKind = 0
    private var lastAdviceMs: Long? = null
    private var count = 0

    fun reset() { since = null; sinceKind = 0; lastAdviceMs = null; count = 0 }

    val adviceCount: Int get() = count

    fun update(i: CoachInput): CoachAdvice {
        if (config.mode == CoachMode.Off || i.quiet || i.quietHours || count >= config.maxPerWalk) { since = null; return CoachAdvice.None }
        val mine = i.mySpeedMps
        val moving = i.othersMps.filterNotNull().filter { it >= config.minMovingMps }
        if (mine == null || mine < config.minMovingMps || moving.isEmpty()) { since = null; return CoachAdvice.None }

        val kind: Int
        val target: Double
        when (config.mode) {
            CoachMode.SlowestPace -> {
                val slowest = moving.min()
                if (mine > slowest * (1 + config.diffFraction)) { kind = 1; target = slowest } else { since = null; return CoachAdvice.None }
            }
            else -> {
                val ref = median(moving)
                kind = when {
                    mine > ref * (1 + config.diffFraction) -> 1
                    mine < ref * (1 - config.diffFraction) && !i.iAmSweeper -> 2
                    else -> 0
                }
                target = ref
            }
        }
        if (kind == 0) { since = null; return CoachAdvice.None }
        val began = since
        if (began == null || sinceKind != kind) { since = i.nowMs; sinceKind = kind; return CoachAdvice.None }
        if (i.nowMs - began < config.sustainMs) return CoachAdvice.None
        val last = lastAdviceMs
        if (last != null && i.nowMs >= last && i.nowMs - last < config.cooldownMs) return CoachAdvice.None
        lastAdviceMs = i.nowMs
        count++
        since = null
        return if (kind == 1) CoachAdvice.EaseOff(target) else CoachAdvice.PickUp(target)
    }

    private fun median(l: List<Double>): Double {
        val s = l.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
