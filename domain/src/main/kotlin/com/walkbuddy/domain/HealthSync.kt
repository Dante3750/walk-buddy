package com.walkbuddy.domain

/**
 * Health Connect sync rules (alpha 2.0), opt-in and off by default. The point of this file is that nothing is counted twice:
 *  - Walk Buddy's own counter and Health Connect often describe the SAME steps (Health Connect usually also reads this phone's sensor
 *    through other apps), so the two are never added. The day's steps are the larger of the two, and Health Connect only wins when
 *    another device (a watch, say) really did add steps this phone did not feel.
 *  - Walk Buddy writes finished walks as exercise sessions with a stable id, and never writes a walk that overlaps one it already wrote.
 */
enum class StepOrigin { Own, HealthConnect }

data class ReconciledSteps(val steps: Int, val source: StepOrigin, val extraFromHealthConnect: Int)

object StepMerge {
    /** A day with more than this many steps is treated as bad data from either side. */
    const val MAX_PLAUSIBLE = 150_000

    fun reconcile(own: Int, healthConnect: Long?): ReconciledSteps {
        val ownSafe = own.coerceIn(0, MAX_PLAUSIBLE)
        val hc = healthConnect
        if (hc == null || hc <= 0 || hc > MAX_PLAUSIBLE) return ReconciledSteps(ownSafe, StepOrigin.Own, 0)
        // A small difference is just two counters rounding differently: stay with our own number.
        val extra = hc.toInt() - ownSafe
        return if (extra > maxOf(150, ownSafe / 20)) ReconciledSteps(hc.toInt(), StepOrigin.HealthConnect, extra) else ReconciledSteps(ownSafe, StepOrigin.Own, 0)
    }
}

data class WrittenWalk(val startMs: Long, val endMs: Long)

object WalkExport {
    const val MIN_WALK_MS = 2 * 60_000L

    fun clientId(startMs: Long): String = "walkbuddy-walk-$startMs"

    /** False for a very short walk, for a walk already written, and for one that overlaps a written one. */
    fun shouldWrite(startMs: Long, endMs: Long, written: List<WrittenWalk>): Boolean {
        if (endMs - startMs < MIN_WALK_MS) return false
        return written.none { startMs < it.endMs && endMs > it.startMs }
    }

    fun remember(written: List<WrittenWalk>, w: WrittenWalk, keep: Int = 40): List<WrittenWalk> = (written + w).takeLast(keep)

    fun encode(l: List<WrittenWalk>): String = l.joinToString(",") { "${it.startMs}-${it.endMs}" }
    fun decode(text: String?): List<WrittenWalk> = text.orEmpty().split(',').mapNotNull {
        val p = it.split('-')
        val a = p.getOrNull(0)?.toLongOrNull(); val b = p.getOrNull(1)?.toLongOrNull()
        if (a != null && b != null && b > a) WrittenWalk(a, b) else null
    }
}
