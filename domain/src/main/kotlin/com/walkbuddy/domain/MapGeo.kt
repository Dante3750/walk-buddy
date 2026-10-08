package com.walkbuddy.domain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator (the projection of OpenStreetMap-style slippy maps), in 256-pixel tiles. */
object WebMercator {
    const val TILE = 256
    const val MAX_LAT = 85.05112878
    const val MIN_ZOOM = 2.0
    const val MAX_ZOOM = 19.0
    private const val EQUATOR_M = 40_075_016.686

    fun worldPx(zoom: Double): Double = TILE * 2.0.pow(zoom)

    fun x(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * worldPx(zoom)

    fun y(lat: Double, zoom: Double): Double {
        val l = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(l) + 1.0 / cos(l)) / PI) / 2.0 * worldPx(zoom)
    }

    fun lon(x: Double, zoom: Double): Double = x / worldPx(zoom) * 360.0 - 180.0

    fun lat(y: Double, zoom: Double): Double {
        val n = PI - 2.0 * PI * y / worldPx(zoom)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Ground metres covered by one screen pixel at this latitude and zoom. */
    fun metersPerPixel(lat: Double, zoom: Double): Double = cos(lat * PI / 180.0) * EQUATOR_M / worldPx(zoom)
}

data class ScreenPt(val x: Double, val y: Double)

/** A map camera: a centre, a (fractional) zoom and the size of the canvas. Immutable; moving it returns a new one. */
data class MapViewport(val center: LatLon, val zoom: Double, val widthPx: Int, val heightPx: Int) {
    private val cx get() = WebMercator.x(center.lon, zoom)
    private val cy get() = WebMercator.y(center.lat, zoom)

    fun toScreen(p: LatLon): ScreenPt =
        ScreenPt(WebMercator.x(p.lon, zoom) - cx + widthPx / 2.0, WebMercator.y(p.lat, zoom) - cy + heightPx / 2.0)

    fun fromScreen(x: Double, y: Double): LatLon =
        LatLon(WebMercator.lat(cy + (y - heightPx / 2.0), zoom), WebMercator.lon(cx + (x - widthPx / 2.0), zoom))

    fun metersPerPixel(): Double = WebMercator.metersPerPixel(center.lat, zoom)

    /** Drags the map by a finger movement of ([dxPx], [dyPx]). */
    fun panBy(dxPx: Double, dyPx: Double): MapViewport = copy(center = fromScreen(widthPx / 2.0 - dxPx, heightPx / 2.0 - dyPx).clampLat())

    /** Zooms by [factor] (2.0 = one level in) keeping the point under ([anchorX], [anchorY]) where it is. */
    fun zoomBy(factor: Double, anchorX: Double = widthPx / 2.0, anchorY: Double = heightPx / 2.0): MapViewport {
        if (factor <= 0.0 || !factor.isFinite()) return this
        val newZoom = (zoom + ln(factor) / ln(2.0)).coerceIn(WebMercator.MIN_ZOOM, WebMercator.MAX_ZOOM)
        val anchor = fromScreen(anchorX, anchorY)
        val moved = copy(zoom = newZoom)
        val a2 = moved.toScreen(anchor)
        return moved.panBy(anchorX - a2.x, anchorY - a2.y)
    }

    fun resized(w: Int, h: Int): MapViewport = copy(widthPx = w, heightPx = h)

    private fun LatLon.clampLat() = LatLon(lat.coerceIn(-WebMercator.MAX_LAT, WebMercator.MAX_LAT), ((lon + 540.0) % 360.0) - 180.0)

    companion object {
        /**
         * The camera that shows all [points] with [paddingPx] around them. A single point (or a tiny group) is shown
         * at least [minSpanM] wide so the map never zooms into a blur. With no points it looks at [fallback].
         */
        fun fit(
            points: List<LatLon>, widthPx: Int, heightPx: Int, paddingPx: Int = 48, minSpanM: Double = 150.0,
            fallback: LatLon = LatLon(20.0, 0.0),
        ): MapViewport {
            val w = max(widthPx, 1); val h = max(heightPx, 1)
            if (points.isEmpty()) return MapViewport(fallback, 3.0, w, h)
            val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
            val centre = LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2)
            val availW = max(w - 2 * paddingPx, 32).toDouble()
            val availH = max(h - 2 * paddingPx, 32).toDouble()
            var zoom = WebMercator.MAX_ZOOM
            // Largest zoom where the bounding box (and the minimum span) fits the padded canvas.
            val metersPerDegLat = 111_195.0
            val spanLatM = max((maxLat - minLat) * metersPerDegLat, minSpanM)
            val spanLonM = max((maxLon - minLon) * metersPerDegLat * cos(centre.lat * PI / 180.0), minSpanM)
            val mppNeeded = max(spanLonM / availW, spanLatM / availH)
            val worldMpp = cos(centre.lat * PI / 180.0) * 40_075_016.686 / WebMercator.TILE
            zoom = (ln(worldMpp / mppNeeded) / ln(2.0)).coerceIn(WebMercator.MIN_ZOOM, WebMercator.MAX_ZOOM)
            return MapViewport(centre, zoom, w, h)
        }
    }
}

/** A "nice" scale bar: a round length (1, 2 or 5 times a power of ten) that fits in [maxBarPx]. */
data class ScaleBarSpec(val meters: Double, val lengthPx: Double, val label: String)

object ScaleBar {
    fun niceMeters(maxMeters: Double): Double {
        if (!(maxMeters > 0.0) || !maxMeters.isFinite()) return 1.0
        val exp10 = floor(log10(maxMeters))
        val base = 10.0.pow(exp10)
        val m = maxMeters / base
        val nice = when {
            m >= 5 -> 5.0
            m >= 2 -> 2.0
            else -> 1.0
        }
        return nice * base
    }

    fun label(meters: Double, imperial: Boolean = false): String {
        if (imperial) {
            val feet = meters * 3.28084
            return if (feet >= 1000) {
                val miles = meters / 1609.344
                "${trim(miles)} mi"
            } else "${trim(feet)} ft"
        }
        return if (meters >= 1000) "${trim(meters / 1000)} km" else "${trim(meters)} m"
    }

    private fun trim(v: Double): String = if (v >= 10 || v == floor(v)) "${v.toLong()}" else "%.1f".format(java.util.Locale.ROOT, v)

    fun forViewport(vp: MapViewport, maxBarPx: Double = 120.0, imperial: Boolean = false): ScaleBarSpec {
        val mpp = vp.metersPerPixel()
        if (!(mpp > 0.0) || !mpp.isFinite()) return ScaleBarSpec(1.0, 0.0, "")
        val meters = if (imperial) niceImperial(mpp * maxBarPx) else niceMeters(mpp * maxBarPx)
        return ScaleBarSpec(meters, meters / mpp, label(meters, imperial))
    }

    /** Round length in feet or miles, returned in metres. */
    private fun niceImperial(maxMeters: Double): Double {
        val feet = maxMeters * 3.28084
        return if (feet < 1000) niceMeters(feet) / 3.28084 else niceMeters(maxMeters / 1609.344) * 1609.344
    }
}

/** Remembers the recent path of one person for the map. Points closer than [minMoveM] to the last one are skipped. */
class TrailBuffer(private val minMoveM: Double = 5.0, private val maxPoints: Int = 400) {
    private val pts = ArrayDeque<LatLon>()
    val points: List<LatLon> get() = pts.toList()
    val size: Int get() = pts.size

    fun add(p: LatLon): Boolean {
        if (!p.isValid) return false
        val last = pts.lastOrNull()
        if (last != null && Geo.haversine(last, p) < minMoveM) return false
        pts.addLast(p)
        while (pts.size > maxPoints) pts.removeFirst()
        return true
    }

    fun clear() = pts.clear()
}

/** Trails for many people at once (the map shows one per member). */
class TrailBook(private val minMoveM: Double = 5.0, private val maxPoints: Int = 300) {
    private val trails = LinkedHashMap<String, TrailBuffer>()

    fun add(id: String, p: LatLon) { trails.getOrPut(id) { TrailBuffer(minMoveM, maxPoints) }.add(p) }
    fun remove(id: String) { trails.remove(id) }
    fun clear() = trails.clear()
    fun snapshot(): Map<String, List<LatLon>> = trails.mapValues { it.value.points }
}

/** A shared meeting point. */
data class MeetingPin(val pos: LatLon, val label: String)

/** One recorded track point, for saving a route. */
data class TrackPoint(val tMs: Long, val lat: Double, val lon: Double)

/** Records the user's own route (opt-in, saved on the phone only) and writes it as GPX. */
class RouteRecorder(private val minMoveM: Double = 8.0, private val maxPoints: Int = 20_000) {
    private val pts = ArrayList<TrackPoint>()
    val points: List<TrackPoint> get() = pts
    val size: Int get() = pts.size

    fun add(tMs: Long, p: LatLon, accuracyM: Double? = null) {
        if (!p.isValid || pts.size >= maxPoints) return
        if (accuracyM != null && accuracyM > 40.0) return
        val last = pts.lastOrNull()
        if (last != null && Geo.haversine(LatLon(last.lat, last.lon), p) < minMoveM) return
        pts.add(TrackPoint(tMs, p.lat, p.lon))
    }

    fun lengthM(): Double {
        var d = 0.0
        for (i in 1 until pts.size) d += Geo.haversine(LatLon(pts[i - 1].lat, pts[i - 1].lon), LatLon(pts[i].lat, pts[i].lon))
        return d
    }
}

object Gpx {
    fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;")
            c == '"' -> append("&quot;"); c == '\'' -> append("&apos;")
            c.isISOControl() -> Unit
            else -> append(c)
        }
    }

    private fun iso(tMs: Long): String = java.time.Instant.ofEpochMilli(tMs).toString()

    /** GPX 1.1 with one track and one segment. Coordinates keep 6 decimals (about 0.1 m). */
    fun build(name: String, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Walk Buddy" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in points) {
            append("      <trkpt lat=\"").append("%.6f".format(java.util.Locale.ROOT, p.lat))
            append("\" lon=\"").append("%.6f".format(java.util.Locale.ROOT, p.lon)).append("\"><time>").append(iso(p.tMs)).append("</time></trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }
}

/** Which tiles an optional OpenStreetMap layer needs for a viewport. Pure maths; the app does the downloading. */
data class TileRef(val z: Int, val x: Int, val y: Int, val screenX: Double, val screenY: Double, val sizePx: Double)

object SlippyTiles {
    const val ATTRIBUTION = "© OpenStreetMap contributors"
    const val URL_TEMPLATE = "https://tile.openstreetmap.org/%d/%d/%d.png"
    const val MAX_TILES = 30

    fun url(z: Int, x: Int, y: Int): String = URL_TEMPLATE.format(java.util.Locale.ROOT, z, x, y)

    /** Integer tile zoom for a fractional map zoom: one level sharper than the map (never blurry), at most 19. */
    fun tileZoom(zoom: Double): Int = floor(zoom + 0.5).toInt().coerceIn(0, 19)

    fun tilesFor(vp: MapViewport): List<TileRef> {
        val z = tileZoom(vp.zoom)
        val scale = 2.0.pow(vp.zoom - z)
        val size = WebMercator.TILE * scale
        val n = 1 shl z
        val cxWorld = WebMercator.x(vp.center.lon, z.toDouble())
        val cyWorld = WebMercator.y(vp.center.lat, z.toDouble())
        val leftWorld = cxWorld - vp.widthPx / 2.0 / scale
        val topWorld = cyWorld - vp.heightPx / 2.0 / scale
        val x0 = floor(leftWorld / WebMercator.TILE).toInt()
        val x1 = floor((leftWorld + vp.widthPx / scale) / WebMercator.TILE).toInt()
        val y0 = max(0, floor(topWorld / WebMercator.TILE).toInt())
        val y1 = min(n - 1, floor((topWorld + vp.heightPx / scale) / WebMercator.TILE).toInt())
        val out = ArrayList<TileRef>()
        for (ty in y0..y1) for (tx in x0..x1) {
            if (out.size >= MAX_TILES) return out
            val wrapped = ((tx % n) + n) % n
            out += TileRef(z, wrapped, ty, (tx * WebMercator.TILE - leftWorld) * scale, (ty * WebMercator.TILE - topWorld) * scale, size)
        }
        return out
    }
}

/** Where the camera should look. */
enum class MapFollow { Free, Me, Group }

object MapCamera {
    const val FOLLOW_MIN_ZOOM = 14.0
    const val FOLLOW_ZOOM = 17.0

    /** The camera for [mode], or null when the user is panning freely (leave the camera alone). */
    fun target(mode: MapFollow, me: LatLon?, others: List<LatLon>, pin: LatLon?, vp: MapViewport): MapViewport? = when (mode) {
        MapFollow.Free -> null
        // A fresh map starts zoomed out on the whole world; "follow me" must come in to street level, not just recentre on a blank sea.
        MapFollow.Me -> me?.let { vp.copy(center = it, zoom = if (vp.zoom < FOLLOW_MIN_ZOOM) FOLLOW_ZOOM else vp.zoom) }
        MapFollow.Group -> {
            val all = buildList { me?.let(::add); addAll(others); pin?.let(::add) }
            if (all.isEmpty()) null else MapViewport.fit(all, vp.widthPx, vp.heightPx)
        }
    }
}

