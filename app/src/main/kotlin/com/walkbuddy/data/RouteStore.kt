package com.walkbuddy.data

import android.content.Context
import com.walkbuddy.domain.Geo
import com.walkbuddy.domain.Gpx
import com.walkbuddy.domain.LatLon
import com.walkbuddy.domain.TrackPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RouteInfo(val fileName: String, val label: String, val modifiedMs: Long, val sizeBytes: Long)

/**
 * Opt-in local route files (GPX) in the app's private storage. Nothing here is uploaded or shared unless the user taps Share
 * on a route. Only the user's own track is stored, never anybody else's position.
 */
class RouteStore(private val context: Context) {
    private fun dir(): File = File(context.filesDir, "routes").also { it.mkdirs() }

    /** Writes a GPX file for a finished walk. Returns null when there are too few points to be a route. */
    fun save(startMs: Long, points: List<TrackPoint>): File? {
        if (points.size < 2) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(startMs))
        val name = "walk-$stamp.gpx"
        val label = "Walk " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(startMs))
        val f = File(dir(), name)
        return runCatching { f.writeText(Gpx.build(label, points), Charsets.UTF_8); f }.getOrNull()
    }

    fun list(): List<RouteInfo> =
        (dir().listFiles { f -> f.isFile && f.name.endsWith(".gpx") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { RouteInfo(it.name, labelFor(it), it.lastModified(), it.length()) }

    private fun labelFor(f: File): String =
        Regex("walk-(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})(\\d{2})\\.gpx").matchEntire(f.name)?.let {
            val (y, mo, d, h, mi) = it.destructured
            "Walk $d/$mo/$y $h:$mi"
        } ?: f.name

    fun file(name: String): File? = File(dir(), name).takeIf { it.isFile && it.parentFile == dir() && name.endsWith(".gpx") }

    fun delete(name: String): Boolean = file(name)?.delete() == true

    fun deleteAll() { dir().listFiles()?.forEach { it.delete() } }

    /** Length of a saved route, for display. */
    fun lengthM(name: String): Double? {
        val f = file(name) ?: return null
        val pts = Regex("lat=\"(-?[0-9.]+)\" lon=\"(-?[0-9.]+)\"").findAll(f.readText()).mapNotNull {
            val la = it.groupValues[1].toDoubleOrNull(); val lo = it.groupValues[2].toDoubleOrNull()
            if (la != null && lo != null) LatLon(la, lo) else null
        }.toList()
        var d = 0.0
        for (i in 1 until pts.size) d += Geo.haversine(pts[i - 1], pts[i])
        return d
    }
}
