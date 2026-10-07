package com.walkbuddy.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double) {
    val isValid: Boolean
        get() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
}

/** A raw location fix. [accuracyM] is the horizontal accuracy radius, [speedMps] is GPS speed when available. */
data class Fix(
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
) {
    val pos: LatLon get() = LatLon(lat, lon)
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    private fun rad(d: Double) = d * PI / 180.0

    /** Great-circle distance in metres (haversine). */
    fun haversine(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - min(1.0, h)))
    }

    /** Initial bearing from a to b in degrees [0, 360). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val dLon = rad(b.lon - a.lon)
        val y = sin(dLon) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Signed distance of [other] along my [headingDeg]: positive = ahead of me, negative = behind me.
     * Uses the component of the offset along the direction of travel, not raw distance.
     */
    fun alongTrackM(me: LatLon, headingDeg: Double, other: LatLon): Double {
        val d = haversine(me, other)
        if (d < 0.01) return 0.0
        val diff = rad(bearingDeg(me, other) - headingDeg)
        return d * cos(diff)
    }

    /** Largest pairwise distance in a group; null with fewer than two positions. */
    fun spreadM(positions: Collection<LatLon>): Double? {
        val l = positions.toList()
        if (l.size < 2) return null
        var best = 0.0
        for (i in l.indices) for (j in i + 1 until l.size) best = maxOf(best, haversine(l[i], l[j]))
        return best
    }
}

enum class FixVerdict { Accepted, LowAccuracy, OutOfOrder, ImpossibleSpeed, Invalid }

/**
 * Drops fixes that would corrupt distance: low accuracy, out-of-order, invalid, or implying a speed no walker reaches.
 * If many consecutive fixes are rejected for speed we re-anchor (the last accepted fix was probably the wrong one).
 */
class FixFilter(
    private val maxAccuracyM: Double = 30.0,
    private val maxSpeedMps: Double = 12.0,
    private val reanchorAfter: Int = 4,
) {
    var last: Fix? = null
        private set
    private var speedRejects = 0

    fun accept(fix: Fix): FixVerdict {
        if (!fix.pos.isValid) return FixVerdict.Invalid
        val acc = fix.accuracyM
        if (acc != null && (acc.isNaN() || acc > maxAccuracyM)) return FixVerdict.LowAccuracy
        val prev = last
        if (prev == null) {
            last = fix
            return FixVerdict.Accepted
        }
        if (fix.tMs <= prev.tMs) return FixVerdict.OutOfOrder
        val dt = (fix.tMs - prev.tMs) / 1000.0
        val v = Geo.haversine(prev.pos, fix.pos) / dt
        if (v > maxSpeedMps) {
            speedRejects++
            if (speedRejects >= reanchorAfter) {
                last = fix
                speedRejects = 0
                return FixVerdict.Accepted
            }
            return FixVerdict.ImpossibleSpeed
        }
        speedRejects = 0
        last = fix
        return FixVerdict.Accepted
    }
}

/** Accumulates walked distance from good fixes while ignoring stationary GPS wobble. */
class DistanceTracker(
    private val filter: FixFilter = FixFilter(),
    private val minMoveM: Double = 3.0,
) {
    var totalM: Double = 0.0
        private set
    private var anchor: LatLon? = null
    var lastAccepted: Fix? = null
        private set

    /** Returns the verdict; distance only grows when the fix moved clearly more than jitter. */
    fun add(fix: Fix): FixVerdict {
        val v = filter.accept(fix)
        if (v != FixVerdict.Accepted) return v
        lastAccepted = fix
        val a = anchor
        if (a == null) {
            anchor = fix.pos
            return v
        }
        val d = Geo.haversine(a, fix.pos)
        // Movement must exceed the fix's own uncertainty, otherwise it is indistinguishable from GPS wobble.
        val threshold = maxOf(minMoveM, fix.accuracyM ?: 0.0)
        if (d >= threshold) {
            totalM += d
            anchor = fix.pos
        }
        return v
    }
}

/** Direction of travel from recent movement; null until the walker has moved a few metres. */
class HeadingTracker(private val minMoveM: Double = 8.0) {
    var headingDeg: Double? = null
        private set
    private var anchor: LatLon? = null

    fun update(p: LatLon) {
        val a = anchor
        if (a == null) {
            anchor = p
            return
        }
        if (Geo.haversine(a, p) >= minMoveM) {
            headingDeg = Geo.bearingDeg(a, p)
            anchor = p
        }
    }
}
