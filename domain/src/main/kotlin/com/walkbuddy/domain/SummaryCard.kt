package com.walkbuddy.domain

/**
 * The shareable summary card for a finished walk together (alpha 2.0). This decides WHAT the picture may contain, so the privacy rules
 * are tested: the route is left out unless the person switches it on, and nothing on the card names a place. The Android side only
 * draws it.
 */
data class SummaryCardPlan(
    val title: String,
    val dateText: String,
    /** (label key, value text) pairs in display order. */
    val stats: List<Pair<String, String>>,
    /** Each walker's position at a few moments (0 = back, 1 = front), for the small Track strip. */
    val strip: List<List<Float?>>,
    val laneAvatars: List<Avatar>,
    /** My route scaled into the unit square, only when the person chose to include it. */
    val route: List<Pair<Float, Float>>?,
    val togetherPct: Int,
)

object SummaryCard {
    const val STRIP_STEPS = 9

    /** Scales route points into 0..1 keeping the shape (north up); at most [maxPoints] points. */
    fun outline(points: List<LatLon>, maxPoints: Int = 120): List<Pair<Float, Float>>? {
        if (points.size < 2) return null
        val pts = if (points.size > maxPoints) Polyline.simplify(points, epsilonM = 8.0, maxPoints = maxPoints) else points
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val cos = Math.cos(Math.toRadians((minLat + maxLat) / 2.0))
        val w = (maxLon - minLon) * cos
        val h = maxLat - minLat
        val span = maxOf(w, h)
        if (span <= 0.0) return null
        val offX = (span - w) / 2.0
        val offY = (span - h) / 2.0
        return pts.map { p -> (((p.lon - minLon) * cos + offX) / span).toFloat() to (1.0 - ((p.lat - minLat) + offY) / span).toFloat() }
    }

    fun plan(r: SharedWalkRecord, unit: UnitSystem, includeRoute: Boolean, title: String, dateText: String): SummaryCardPlan {
        val total = SharedReplay.durationSec(r).coerceAtLeast(1)
        val strip = (0 until STRIP_STEPS).map { i ->
            val t = total.toDouble() * i / (STRIP_STEPS - 1)
            val xs = SharedReplay.positionsAt(r, t)
            val known = xs.filterNotNull()
            val lo = known.minOrNull(); val hi = known.maxOrNull()
            xs.map { x -> if (x == null || lo == null || hi == null) null else if (hi - lo < 1.0) 0.5f else ((x - lo) / (hi - lo)).toFloat() }
        }
        val route = if (includeRoute) outline(Polyline.decode(r.routePolyline)) else null
        return SummaryCardPlan(
            title = title, dateText = dateText,
            stats = listOf(
                "distance" to Units.distance(r.myDistanceM, unit),
                "time" to Format.duration(r.durationMs),
                "steps" to r.mySteps.toString(),
                "together" to "${r.togetherPct}%",
            ),
            strip = strip, laneAvatars = r.lanes.map { it.avatar }, route = route, togetherPct = r.togetherPct,
        )
    }
}

/** App links used by About and "Check for updates". The app opens them in the browser; it never makes a request to them itself. */
object AppLinks {
    const val RELEASES = "https://github.com/Dante3750/walk-buddy/releases"
    const val REPO = "https://github.com/Dante3750/walk-buddy"
    const val README = "https://github.com/Dante3750/walk-buddy#readme"
    const val PRIVACY = "https://github.com/Dante3750/walk-buddy#privacy-and-honest-limitations"
    const val DONT_KILL_MY_APP = "https://dontkillmyapp.com/"
}

/** Languages the app is translated into. "" means follow the phone. */
data class AppLanguage(val tag: String, val nativeName: String)

object AppLanguages {
    val all: List<AppLanguage> = listOf(AppLanguage("", "System default"), AppLanguage("en", "English"), AppLanguage("hi", "हिन्दी"))

    fun isSupported(tag: String?): Boolean = tag != null && all.any { it.tag == tag }

    /** Anything unknown becomes "" (follow the phone). */
    fun normalize(tag: String?): String = tag?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() && isSupported(it) } ?: ""
}
