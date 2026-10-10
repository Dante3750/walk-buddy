package com.walkbuddy.domain

import kotlin.math.roundToInt

/**
 * Meeting-point navigation (alpha 2.0): for the meeting pin, how far each walker is, which way to go and how long it takes at their
 * current pace. Pure maths on positions that are already on the screen; nothing new is sent anywhere.
 */
data class MeetingLeg(
    val id: String,
    val name: String,
    val isMe: Boolean,
    val distanceM: Double,
    /** Compass bearing from this walker to the pin, 0 = north, clockwise. */
    val bearingDeg: Double,
    /** Seconds to arrive at their current pace, or at a typical pace when they are standing still. */
    val etaSec: Long?,
    /** True when [etaSec] uses a typical walking pace because the real pace is unknown or near zero. */
    val etaEstimated: Boolean,
    val arrived: Boolean,
)

object MeetingNav {
    /** Closer than this counts as "there". */
    const val ARRIVED_M = 20.0
    const val TYPICAL_WALK_MPS = 1.2
    const val MIN_PACE_MPS = 0.35
    const val MAX_PACE_MPS = 3.0
    private const val MAX_ETA_SEC = 3L * 3600

    fun leg(id: String, name: String, isMe: Boolean, from: LatLon?, pin: LatLon, speedMps: Double?): MeetingLeg? {
        if (from == null || !from.isValid || !pin.isValid) return null
        val d = Geo.haversine(from, pin)
        val arrived = d <= ARRIVED_M
        val real = speedMps != null && speedMps.isFinite() && speedMps >= MIN_PACE_MPS
        val v = if (real) speedMps!!.coerceAtMost(MAX_PACE_MPS) else TYPICAL_WALK_MPS
        val eta = if (arrived) 0L else (d / v).roundToInt().toLong().coerceAtMost(MAX_ETA_SEC)
        return MeetingLeg(id, name, isMe, d, if (d < 1.0) 0.0 else Geo.bearingDeg(from, pin), eta, !real && !arrived, arrived)
    }

    /** Me first, then everyone else nearest-first; walkers with no position are left out. */
    fun legs(me: MeetingLeg?, others: List<MeetingLeg?>): List<MeetingLeg> =
        listOfNotNull(me) + others.filterNotNull().sortedBy { it.distanceM }

    /** "about 6 min", "under a minute", "1 h 5 min". */
    fun etaWords(sec: Long?, estimated: Boolean): String {
        if (sec == null) return "-"
        val prefix = if (estimated) "about " else ""
        return when {
            sec <= 0 -> "there"
            sec < 60 -> "under a minute"
            sec < 3600 -> prefix + "${(sec + 30) / 60} min"
            else -> prefix + "${sec / 3600} h ${((sec % 3600) + 30) / 60} min"
        }
    }

    private val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Eight-point compass name for a bearing. */
    fun compassPoint(bearingDeg: Double): String {
        val b = ((bearingDeg % 360.0) + 360.0) % 360.0
        return points[((b + 22.5) / 45.0).toInt() % 8]
    }
}
