package com.walkbuddy.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The live compass arrow (alpha 2.0). The phone's rotation sensor is read only while the live screen is visible; this file is the
 * sensor-free part: smoothing, accuracy handling, what the arrow points at and how far to turn.
 */
object Angles {
    /** Smallest signed difference a - b in degrees, in (-180, 180]. */
    fun diff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    fun norm(a: Double): Double = ((a % 360.0) + 360.0) % 360.0
}

/** Low-pass filter for a compass heading. Works on the shortest way round, so 359 degrees to 1 degree is a step of 2, not 358. */
class CompassFilter(private val alpha: Double = 0.18, private val snapDeg: Double = 100.0) {
    private var value: Double? = null
    private var pendingBig = 0

    val heading: Double? get() = value

    fun reset() { value = null; pendingBig = 0 }

    /** Feed a raw heading (degrees, any range). Returns the smoothed heading in [0, 360). */
    fun update(rawDeg: Double): Double {
        if (!rawDeg.isFinite()) return value ?: 0.0
        val raw = Angles.norm(rawDeg)
        val cur = value
        if (cur == null) { value = raw; return raw }
        val d = Angles.diff(raw, cur)
        // A real quick turn (the person spun round) should not lag for seconds: a big jump seen twice in a row is followed at once.
        val next = if (abs(d) >= snapDeg) {
            pendingBig++
            if (pendingBig >= 2) { pendingBig = 0; raw } else cur
        } else {
            pendingBig = 0
            Angles.norm(cur + alpha * d)
        }
        value = next
        return next
    }
}

object CompassMath {
    /**
     * Azimuth (degrees clockwise from magnetic north) of the phone's top edge from the gravity and magnetic-field vectors, for a phone held
     * flat or upright in portrait. Used when there is no rotation-vector sensor. Returns null for a phone in free fall or a missing field.
     */
    fun azimuthDeg(gravity: FloatArray, magnetic: FloatArray): Double? {
        if (gravity.size < 3 || magnetic.size < 3) return null
        val ax = gravity[0].toDouble(); val ay = gravity[1].toDouble(); val az = gravity[2].toDouble()
        val ex = magnetic[0].toDouble(); val ey = magnetic[1].toDouble(); val ez = magnetic[2].toDouble()
        // H = E x A (east), normalised; M = A x H (north).
        var hx = ey * az - ez * ay
        var hy = ez * ax - ex * az
        var hz = ex * ay - ey * ax
        val normH = sqrt(hx * hx + hy * hy + hz * hz)
        if (normH < 0.1) return null
        hx /= normH; hy /= normH; hz /= normH
        val normA = sqrt(ax * ax + ay * ay + az * az)
        if (normA < 1e-6) return null
        val nax = ax / normA; val nay = ay / normA; val naz = az / normA
        val my = naz * hx - nax * hz
        return Angles.norm(Math.toDegrees(atan2(hy, my)))
    }

    /** Turn needed: positive = turn right (clockwise). */
    fun relativeDeg(targetBearingDeg: Double, headingDeg: Double): Double = Angles.diff(targetBearingDeg, headingDeg)

    /** SensorManager accuracy: 0 unreliable, 1 low, 2 medium, 3 high. Below medium the arrow asks the person to wave the phone in a figure of eight. */
    fun needsCalibration(accuracy: Int): Boolean = accuracy < 2

    /** Words for a turn, for a screen reader: "straight ahead", "turn slightly right", "turn around". */
    fun turnWords(relDeg: Double): String {
        val a = abs(relDeg)
        return when {
            a < 15 -> "straight ahead"
            a < 60 -> if (relDeg > 0) "slightly to your right" else "slightly to your left"
            a < 120 -> if (relDeg > 0) "to your right" else "to your left"
            a < 165 -> if (relDeg > 0) "behind you, to the right" else "behind you, to the left"
            else -> "behind you"
        }
    }
}

enum class ArrowTarget { Partner, Behind, Pin }

data class ArrowCandidate(val id: String, val name: String, val pos: LatLon?, val alongM: Double?)

data class ArrowPick(val target: ArrowTarget, val name: String?, val to: LatLon)

/** Decides what the arrow points at, falling back to something that exists when the wanted target is missing. */
object ArrowPicker {
    fun pick(wanted: ArrowTarget, others: List<ArrowCandidate>, pin: LatLon?): ArrowPick? {
        val placed = others.filter { it.pos != null }
        fun partner(): ArrowPick? = placed.firstOrNull()?.let { ArrowPick(ArrowTarget.Partner, it.name, it.pos!!) }
        fun behind(): ArrowPick? {
            val c = placed.filter { it.alongM != null }.minByOrNull { it.alongM!! } ?: return partner()
            return ArrowPick(ArrowTarget.Behind, c.name, c.pos!!)
        }
        fun pinPick(): ArrowPick? = pin?.let { ArrowPick(ArrowTarget.Pin, null, it) }
        val order = when (wanted) {
            ArrowTarget.Partner -> listOf(::partner, ::pinPick, ::behind)
            ArrowTarget.Behind -> listOf(::behind, ::pinPick, ::partner)
            ArrowTarget.Pin -> listOf(::pinPick, ::partner, ::behind)
        }
        for (f in order) f()?.let { return it }
        return null
    }

    /** The targets that can be chosen right now, in a stable order, so the picker only offers what exists. */
    fun available(others: List<ArrowCandidate>, pin: LatLon?, isGroup: Boolean): List<ArrowTarget> = buildList {
        if (others.any { it.pos != null }) add(if (isGroup) ArrowTarget.Behind else ArrowTarget.Partner)
        if (isGroup && others.any { it.pos != null }) add(ArrowTarget.Partner)
        if (pin != null) add(ArrowTarget.Pin)
    }.distinct()
}
