package com.walkbuddy.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Track: a metro-platform picture of a shared walk. Each walker is a pawn on their own lane along one shared line; the line
 * scrolls past as you walk and "stations" mark the distance. All the geometry lives here (pure, unit tested) so the Compose layer only draws.
 *
 * Positions are in metres along the direction of travel, measured from MY start: I am at my own distance walked, and a buddy is where
 * they really are relative to me (their along-track offset), not just how far their own odometer says. Two people who started a few
 * metres apart but walk side by side therefore sit side by side.
 */
data class TrackWalker(
    val id: String,
    val name: String,
    val avatar: Avatar,
    /** Metres along the platform, null until we know where they are. */
    val xM: Double?,
    val steps: Int = 0,
    val isMe: Boolean = false,
    /** Not heard from for a while: drawn faded at their last known place. */
    val stale: Boolean = false,
    val lastSeenSec: Int? = null,
    val straggler: Boolean = false,
    val role: GroupRole? = null,
)

enum class TrackCue { None, Front, Back }

data class TrackLane(
    val walker: TrackWalker,
    /** 0..1 across the visible window (clamped to the edges when [offscreen] is not 0), null when the position is unknown. */
    val frac: Float?,
    /** -1 behind the left edge, +1 beyond the right edge, 0 visible. */
    val offscreen: Int,
    val cue: TrackCue,
)

data class Station(val m: Double, val frac: Float, val label: String, val passed: Boolean)

data class TrackFrame(
    val lanes: List<TrackLane>,
    val stations: List<Station>,
    val leftM: Double,
    val windowM: Double,
    /** Distance between the two (or, in a group, the front and the back); null with fewer than two known positions. */
    val gapM: Double?,
    val gapText: String,
    val together: Boolean,
    /** People left out of the lanes because there are more than [TrackLayout.MAX_LANES]. */
    val hidden: Int,
    /** A full sentence for screen readers. */
    val description: String,
)

/** Which part of the line is on screen. Keeps its size steady (no jumping between zoom levels) and only moves forward smoothly. */
class TrackCamera(private val minWindowM: Double = 120.0) {
    var windowM: Double = minWindowM
        private set
    var leftM: Double = -minWindowM * 0.3
        private set

    fun update(xs: List<Double>) {
        if (xs.isEmpty()) return
        val lo = xs.min(); val hi = xs.max()
        val spread = hi - lo
        val need = TrackLayout.niceWindow(max(minWindowM, spread * 1.7))
        windowM = when {
            need > windowM -> need
            spread < windowM * 0.3 && need < windowM -> need // zoom back in only when clearly much tighter
            else -> windowM
        }
        // Keep everybody in view. A tight group sits at about two thirds so the road ahead shows; a wide group is centred.
        leftM = if (spread >= windowM * 0.45) (lo + hi) / 2.0 - windowM / 2.0 else hi - windowM * 0.68
    }
}

object TrackLayout {
    const val TOGETHER_M = 12.0
    const val FRONT_CUE_M = 15.0
    const val BACK_CUE_M = 40.0
    const val MAX_LANES = 8

    private val WINDOWS = doubleArrayOf(120.0, 200.0, 300.0, 500.0, 800.0, 1_200.0, 2_000.0, 3_000.0, 5_000.0, 8_000.0, 12_000.0, 20_000.0, 40_000.0, 80_000.0)
    private val STATION_METRIC = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 25_000.0, 50_000.0)
    private val STATION_IMPERIAL = doubleArrayOf(
        15.24, 30.48, 76.2, 152.4, 304.8, 402.336, 804.672, 1_609.344, 3_218.688, 8_046.72, 16_093.44, 40_233.6,
    )

    fun niceWindow(m: Double): Double = WINDOWS.firstOrNull { it >= m } ?: (WINDOWS.last() * kotlin.math.ceil(m / WINDOWS.last()))

    /** The spacing between stations: the smallest nice value that leaves at most six stations on screen. */
    fun stationSpacing(windowM: Double, unit: UnitSystem): Double {
        val steps = if (unit == UnitSystem.Metric) STATION_METRIC else STATION_IMPERIAL
        return steps.firstOrNull { windowM / it <= 6.0 } ?: steps.last()
    }

    fun stationLabel(m: Double, unit: UnitSystem): String {
        if (m < 0.5) return "Start"
        return when (unit) {
            UnitSystem.Metric -> if (m >= 1000.0) trim(m / 1000.0) + " km" else Math.round(m).toString() + " m"
            UnitSystem.Imperial -> {
                val mi = m / Units.M_PER_MILE
                if (mi >= 0.2) trim(mi) + " mi" else Math.round(m / Units.M_PER_FOOT / 10.0).times(10).toString() + " ft"
            }
        }
    }

    private fun trim(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else String.format(java.util.Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    fun stations(leftM: Double, windowM: Double, leaderM: Double?, unit: UnitSystem): List<Station> {
        val s = stationSpacing(windowM, unit)
        if (!(s > 0)) return emptyList()
        val first = kotlin.math.ceil(max(leftM, 0.0) / s).toLong()
        val out = ArrayList<Station>()
        var k = first
        while (k * s <= leftM + windowM && out.size < 12) {
            val m = k * s
            out += Station(m, ((m - leftM) / windowM).toFloat(), stationLabel(m, unit), passed = leaderM != null && leaderM >= m)
            k++
        }
        return out
    }

    /**
     * Builds one frame. [realGapM] is the measured distance between the two people (straight line, from the GPS positions) and is
     * preferred for the text when there are exactly two walkers; otherwise the gap along the line is used.
     */
    fun frame(walkers: List<TrackWalker>, camera: TrackCamera, realGapM: Double?, unit: UnitSystem): TrackFrame {
        val me = walkers.firstOrNull { it.isMe }
        // Keep me, then the nearest people, when there are more than fit.
        val shown: List<TrackWalker> = if (walkers.size <= MAX_LANES) walkers else {
            val ref = me?.xM
            val others = walkers.filter { !it.isMe }
                .sortedBy { w -> if (ref != null && w.xM != null) abs(w.xM - ref) else Double.MAX_VALUE }
                .take(MAX_LANES - (if (me != null) 1 else 0))
            val keep = (listOfNotNull(me) + others).toSet()
            walkers.filter { it in keep }
        }
        val hidden = walkers.size - shown.size
        val located = shown.mapNotNull { it.xM }
        camera.update(located)
        val left = camera.leftM; val win = camera.windowM

        val lo = located.minOrNull(); val hi = located.maxOrNull()
        val gap: Double? = when {
            located.size < 2 -> null
            shown.size == 2 && realGapM != null && realGapM.isFinite() -> realGapM
            else -> hi!! - lo!!
        }
        val together = gap != null && gap <= TOGETHER_M
        // Cues follow the order along the line, not the straight-line gap (two people far apart sideways are not "ahead" of each other).
        val loV = lo ?: 0.0; val hiV = hi ?: 0.0
        val spreadX = hiV - loV
        val cues = located.size >= 2 && spreadX >= FRONT_CUE_M

        val lanes = shown.map { w ->
            val x = w.xM
            val f = if (x == null) null else ((x - left) / win)
            val off = when { f == null -> 0; f < 0.0 -> -1; f > 1.0 -> 1; else -> 0 }
            val cue = when {
                !cues || x == null -> TrackCue.None
                w.role == GroupRole.Leader -> TrackCue.Front
                w.straggler -> TrackCue.Back
                x == hiV -> TrackCue.Front
                x == loV && spreadX >= BACK_CUE_M -> TrackCue.Back
                else -> TrackCue.None
            }
            TrackLane(w, f?.toFloat()?.coerceIn(0.03f, 0.97f), off, cue)
        }

        val gapText = when {
            gap == null -> if (shown.size > 1) "Waiting for locations" else "Walking solo"
            together -> "Side by side"
            shown.size > 2 -> "Front to back ${Units.distance(gap, unit)}"
            else -> "${Units.distance(gap, unit)} apart"
        }
        val desc = buildString {
            append(gapText)
            if (hidden > 0) append(", and $hidden more not shown")
            for (l in lanes) {
                append(". ").append(if (l.walker.isMe) "You" else l.walker.name)
                when (l.cue) { TrackCue.Front -> append(" at the front"); TrackCue.Back -> append(" at the back"); TrackCue.None -> Unit }
                if (l.walker.stale) append(", not heard from for a while")
            }
        }
        return TrackFrame(lanes, stations(left, win, hi, unit), left, win, gap, gapText, together, hidden, desc)
    }

    /** "Last seen 12 s ago" wording for a partner who went quiet. */
    fun lastSeen(sec: Int?): String = when {
        sec == null -> "Not seen yet"
        sec < 5 -> "Just now"
        sec < 90 -> "Last seen ${sec} s ago"
        sec < 3600 -> "Last seen ${sec / 60} min ago"
        else -> "Last seen over an hour ago"
    }

    /** One lerp step toward [target], used by the UI loop at a low frame rate. Snaps when close, or at once when motion is reduced. */
    fun ease(current: Double, target: Double, rate: Double = 0.35, snapM: Double = 0.4, instant: Boolean = false): Double {
        if (instant || !current.isFinite() || abs(target - current) <= snapM) return target
        return current + (target - current) * min(1.0, max(0.0, rate))
    }
}
