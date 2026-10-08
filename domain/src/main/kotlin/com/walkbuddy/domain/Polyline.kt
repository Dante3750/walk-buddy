package com.walkbuddy.domain

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Google's encoded-polyline format (precision 5, about 1 m) for storing a walked route as one short string, plus a simplifier
 * so an hour of GPS points becomes a few hundred. Pure maths; the route never leaves the phone unless the user exports it.
 */
object Polyline {
    fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var pLat = 0L; var pLon = 0L
        for (p in points) {
            if (!p.isValid) continue
            val lat = (p.lat * 1e5).roundToLong(); val lon = (p.lon * 1e5).roundToLong()
            put(sb, lat - pLat); put(sb, lon - pLon)
            pLat = lat; pLon = lon
        }
        return sb.toString()
    }

    private fun put(sb: StringBuilder, delta: Long) {
        var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (v >= 0x20) {
            sb.append((((v and 0x1f) or 0x20) + 63).toInt().toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }

    /** Returns the points; a damaged or truncated string yields the points that decoded cleanly. */
    fun decode(text: String?): List<LatLon> {
        if (text.isNullOrEmpty()) return emptyList()
        val out = ArrayList<LatLon>()
        var i = 0; var lat = 0L; var lon = 0L
        fun next(): Long? {
            var result = 0L; var shift = 0
            while (true) {
                if (i >= text.length || shift > 60) return null
                val b = text[i++].code - 63
                if (b < 0 || b > 63) return null
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < text.length) {
            val dLat = next() ?: break
            val dLon = next() ?: break
            lat += dLat; lon += dLon
            val p = LatLon(lat / 1e5, lon / 1e5)
            if (p.isValid) out += p
        }
        return out
    }

    /** Ramer-Douglas-Peucker with an explicit stack (an hour of points must not overflow the call stack). Keeps first and last. */
    fun simplify(points: List<LatLon>, epsilonM: Double = 6.0, maxPoints: Int = 1500): List<LatLon> {
        if (points.size <= 2) return points
        var eps = epsilonM
        var result = rdp(points, eps)
        while (result.size > maxPoints && eps < 500.0) { eps *= 1.6; result = rdp(points, eps) }
        return result
    }

    private fun rdp(points: List<LatLon>, epsM: Double): List<LatLon> {
        val n = points.size
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            if (b <= a + 1) continue
            var worst = -1.0; var idx = -1
            for (k in a + 1 until b) {
                val d = distToSegmentM(points[k], points[a], points[b])
                if (d > worst) { worst = d; idx = k }
            }
            if (worst > epsM && idx > 0) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Flat-earth distance from [p] to the segment [a]-[b], in metres (fine for a walk). */
    fun distToSegmentM(p: LatLon, a: LatLon, b: LatLon): Double {
        val mPerDegLat = 111_195.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians((a.lat + b.lat) / 2))
        val ax = a.lon * mPerDegLon; val ay = a.lat * mPerDegLat
        val bx = b.lon * mPerDegLon; val by = b.lat * mPerDegLat
        val px = p.lon * mPerDegLon; val py = p.lat * mPerDegLat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 1e-9) 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    fun lengthM(points: List<LatLon>): Double {
        var d = 0.0
        for (i in 1 until points.size) d += Geo.haversine(points[i - 1], points[i])
        return d
    }

}
