package com.walkbuddy.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * History of walks done together (a partner walk or a group walk). Everything here is pure Kotlin: what gets recorded during the
 * walk ([SharedWalkRecorder]), what is stored ([SharedWalkRecord]), how it is grouped and summed for the list ([SharedHistory]) and how
 * it is exported ([SharedWalkExport]). Stored only on the phone, in Room.
 */
enum class SharedMode(val label: String) {
    Partner("Partner walk"), Group("Group walk");

    companion object {
        fun fromName(n: String?): SharedMode = values().firstOrNull { it.name == n } ?: Partner
    }
}

data class SharedLane(val id: String, val name: String, val avatarCode: Int?, val isMe: Boolean, val steps: Int, val distanceM: Double?) {
    val avatar: Avatar get() = AvatarCode.of(avatarCode, id)
}

/** One moment of the replay: seconds since the start and each lane's metres along the platform (null = unknown), in lane order. */
data class TrackSample(val tSec: Int, val xs: List<Int?>)

data class SharedWalkRecord(
    val id: Long,
    val startMs: Long,
    val durationMs: Long,
    val mode: SharedMode,
    /** People on the walk, me included. */
    val memberCount: Int,
    /** The partner's nickname, or the group's title. */
    val title: String,
    val lanes: List<SharedLane>,
    val samples: List<TrackSample>,
    val togetherPct: Int,
    val longestTogetherMs: Long,
    val maxGapM: Double,
    /** Encoded polyline of MY route, only when the user turned on route saving. */
    val routePolyline: String?,
) {
    val me: SharedLane? get() = lanes.firstOrNull { it.isMe }
    val others: List<SharedLane> get() = lanes.filter { !it.isMe }
    val myDistanceM: Double get() = me?.distanceM ?: 0.0
    val mySteps: Int get() = me?.steps ?: 0
    /** The partner's steps (partner walks) or everyone else's together (groups). */
    val othersSteps: Int get() = others.sumOf { it.steps }
    val timeTogetherMs: Long get() = durationMs * togetherPct / 100
    val endMs: Long get() = startMs + durationMs
}

/** What the live screens know about one person at one moment; the recorder keeps the history. */
data class RecLane(val id: String, val name: String, val avatarCode: Int?, val xM: Double?, val steps: Int, val distanceM: Double?, val isMe: Boolean)

/**
 * Collects the replay of a shared walk: a position for every lane every [intervalMs] (the sampling thins itself out for very long walks
 * so the stored text stays small), plus the largest gap seen at any moment. Nothing leaves the phone.
 */
class SharedWalkRecorder(
    val startMs: Long,
    val mode: SharedMode,
    private var intervalMs: Long = 15_000L,
    private val maxSamples: Int = 480,
    private val maxLanes: Int = TrackLayout.MAX_LANES,
) {
    private class Meta(var name: String, var av: Int?, var steps: Int, var dist: Double?, val isMe: Boolean)

    private val lanes = LinkedHashMap<String, Meta>()
    private val samples = ArrayList<Pair<Int, Map<String, Int?>>>()
    private var lastSampleMs: Long? = null
    var maxGapM: Double = 0.0
        private set

    val sampleCount: Int get() = samples.size
    val laneCount: Int get() = lanes.size

    fun onFrame(nowMs: Long, frame: List<RecLane>, gapM: Double?) {
        if (gapM != null && gapM.isFinite() && gapM > maxGapM) maxGapM = gapM
        absorb(frame)
        val last = lastSampleMs
        if (last == null || nowMs - last >= intervalMs) takeSample(nowMs, frame)
    }

    private fun absorb(frame: List<RecLane>) {
        for (l in frame) {
            val m = lanes[l.id]
            if (m == null) {
                if (lanes.size >= maxLanes && !l.isMe) continue
                lanes[l.id] = Meta(l.name, l.avatarCode, l.steps, l.distanceM, l.isMe)
            } else {
                m.name = l.name; m.steps = maxOf(m.steps, l.steps)
                if (l.avatarCode != null) m.av = l.avatarCode
                if (l.distanceM != null) m.dist = maxOf(m.dist ?: 0.0, l.distanceM)
            }
        }
    }

    private fun takeSample(nowMs: Long, frame: List<RecLane>) {
        lastSampleMs = nowMs
        val xs = HashMap<String, Int?>()
        for (l in frame) if (l.id in lanes) xs[l.id] = l.xM?.let { Math.round(it).toInt() }
        samples += (((nowMs - startMs) / 1000L).toInt().coerceAtLeast(0)) to xs
        if (samples.size > maxSamples) {
            // Too many for a long walk: keep every second one and sample half as often from now on.
            val kept = samples.filterIndexed { i, _ -> i % 2 == 0 || i == samples.lastIndex }
            samples.clear(); samples.addAll(kept)
            intervalMs *= 2
        }
    }

    /** Closes the record. [lastFrame] adds a final sample so the replay ends where the walk ended. */
    fun build(
        endMs: Long, title: String, memberCount: Int, togetherPct: Int, longestTogetherMs: Long, route: String?, lastFrame: List<RecLane>? = null, id: Long = 0,
    ): SharedWalkRecord {
        if (lastFrame != null) { absorb(lastFrame); takeSample(endMs, lastFrame) }
        val ordered = lanes.entries.sortedByDescending { it.value.isMe }
        val ids = ordered.map { it.key }
        val laneList = ordered.map { (k, m) -> SharedLane(k, m.name, m.av, m.isMe, m.steps, m.dist) }
        val sampleList = samples.map { (t, map) -> TrackSample(t, ids.map { map[it] }) }
        return SharedWalkRecord(
            id = id, startMs = startMs, durationMs = (endMs - startMs).coerceAtLeast(0), mode = mode,
            memberCount = memberCount.coerceAtLeast(laneList.size), title = title.trim().take(40), lanes = laneList, samples = sampleList,
            togetherPct = togetherPct.coerceIn(0, 100), longestTogetherMs = longestTogetherMs.coerceAtLeast(0), maxGapM = maxGapM,
            routePolyline = route?.takeIf { it.isNotEmpty() },
        )
    }
}

/** JSON for the two text columns, and for exports. Tolerant when reading: a damaged value gives an empty list, never a crash. */
object SharedWalkCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encodeLanes(lanes: List<SharedLane>): String = JsonArray(lanes.map { laneJson(it) }).toString()

    private fun laneJson(l: SharedLane) = buildJsonObject {
        put("id", l.id); put("name", l.name)
        l.avatarCode?.let { put("av", it) }
        put("me", l.isMe); put("steps", l.steps)
        l.distanceM?.let { put("dist", Math.round(it * 10.0) / 10.0) }
    }

    fun decodeLanes(text: String?): List<SharedLane> {
        val arr = runCatching { json.parseToJsonElement(text.orEmpty()) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            SharedLane(
                id = id, name = MessageCodec.cleanName(o.str("name"), MessageCodec.MAX_NAME) ?: "Walker", avatarCode = o.int("av"),
                isMe = o.str("me") == "true", steps = (o.int("steps") ?: 0).coerceIn(0, 2_000_000), distanceM = o.dbl("dist")?.takeIf { it >= 0 },
            )
        }.take(TrackLayout.MAX_LANES)
    }

    fun encodeSamples(samples: List<TrackSample>): String =
        JsonArray(samples.map { s -> JsonArray(listOf<kotlinx.serialization.json.JsonElement>(JsonPrimitive(s.tSec)) + s.xs.map { x -> if (x == null) JsonNull else JsonPrimitive(x) }) }).toString()

    fun decodeSamples(text: String?): List<TrackSample> {
        val arr = runCatching { json.parseToJsonElement(text.orEmpty()) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val row = e as? JsonArray ?: return@mapNotNull null
            val t = (row.firstOrNull() as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val xs = row.drop(1).map { v -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } }
            TrackSample(t, xs)
        }
    }
}

/** Playback of a stored walk: where each lane was at a given second (linear between samples, unknown stays unknown). */
object SharedReplay {
    fun positionsAt(r: SharedWalkRecord, tSec: Double): List<Double?> {
        val s = r.samples
        if (s.isEmpty()) return r.lanes.map { null }
        if (tSec <= s.first().tSec) return r.lanes.indices.map { s.first().xs.getOrNull(it)?.toDouble() }
        if (tSec >= s.last().tSec) return r.lanes.indices.map { s.last().xs.getOrNull(it)?.toDouble() }
        var hi = s.indexOfFirst { it.tSec >= tSec }
        if (hi <= 0) hi = 1
        val a = s[hi - 1]; val b = s[hi]
        val span = (b.tSec - a.tSec).toDouble()
        val f = if (span <= 0) 1.0 else (tSec - a.tSec) / span
        return r.lanes.indices.map { i ->
            val x0 = a.xs.getOrNull(i); val x1 = b.xs.getOrNull(i)
            when {
                x0 != null && x1 != null -> x0 + (x1 - x0) * f
                x1 != null && f >= 0.5 -> x1.toDouble()
                x0 != null && f < 0.5 -> x0.toDouble()
                else -> null
            }
        }
    }

    fun durationSec(r: SharedWalkRecord): Int = maxOf((r.durationMs / 1000L).toInt(), r.samples.lastOrNull()?.tSec ?: 0)

    /** The walkers to draw at [tSec], ready for [TrackLayout.frame]. */
    fun walkers(r: SharedWalkRecord, tSec: Double): List<TrackWalker> {
        val pos = positionsAt(r, tSec)
        return r.lanes.mapIndexed { i, l ->
            TrackWalker(l.id, if (l.isMe) "You" else l.name, l.avatar, pos[i], l.steps, isMe = l.isMe)
        }
    }
}

data class MonthGroup(val year: Int, val month: Int, val title: String, val items: List<SharedWalkRecord>, val distanceM: Double, val durationMs: Long)

data class HistoryTotals(val count: Int, val distanceM: Double, val durationMs: Long, val avgTogetherPct: Int, val partners: Int)

object SharedHistory {
    /** Newest first, grouped by calendar month in [zone]. */
    fun groupByMonth(records: List<SharedWalkRecord>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<MonthGroup> {
        val sorted = records.sortedByDescending { it.startMs }
        val groups = LinkedHashMap<Pair<Int, Int>, MutableList<SharedWalkRecord>>()
        for (r in sorted) {
            val d = Instant.ofEpochMilli(r.startMs).atZone(zone)
            groups.getOrPut(d.year to d.monthValue) { ArrayList() } += r
        }
        return groups.map { (ym, items) ->
            MonthGroup(
                ym.first, ym.second,
                java.time.Month.of(ym.second).getDisplayName(TextStyle.FULL, locale) + " " + ym.first,
                items, items.sumOf { it.myDistanceM }, items.sumOf { it.durationMs },
            )
        }
    }

    fun totals(records: List<SharedWalkRecord>): HistoryTotals {
        if (records.isEmpty()) return HistoryTotals(0, 0.0, 0L, 0, 0)
        val weighted = records.sumOf { it.togetherPct.toLong() * it.durationMs.coerceAtLeast(1) }
        val weight = records.sumOf { it.durationMs.coerceAtLeast(1) }
        val partners = records.filter { it.mode == SharedMode.Partner }.map { it.title.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().size
        return HistoryTotals(records.size, records.sumOf { it.myDistanceM }, records.sumOf { it.durationMs }, (weighted / weight).toInt(), partners)
    }

    fun dayTitle(r: SharedWalkRecord, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val d = Instant.ofEpochMilli(r.startMs).atZone(zone)
        return d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + d.dayOfMonth + " " + d.month.getDisplayName(TextStyle.SHORT, locale)
    }

    fun title(r: SharedWalkRecord): String = when (r.mode) {
        SharedMode.Partner -> if (r.title.isBlank()) "Walk together" else "With ${r.title}"
        SharedMode.Group -> (if (r.title.isBlank()) "Group walk" else r.title) + " (${r.memberCount} people)"
    }

    fun subtitle(r: SharedWalkRecord, unit: UnitSystem): String =
        listOf(Format.duration(r.durationMs), Units.distance(r.myDistanceM, unit), "${r.togetherPct}% together").joinToString("  ·  ")

    /** "mm:ss" or "h:mm:ss" for the replay clock. */
    fun clock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

object SharedWalkExport {
    fun json(records: List<SharedWalkRecord>): String {
        val walks = JsonArray(records.sortedBy { it.startMs }.map { r ->
            buildJsonObject {
                put("startMs", r.startMs); put("durationMs", r.durationMs); put("mode", r.mode.name); put("people", r.memberCount)
                put("title", r.title)
                put("myDistanceM", Math.round(r.myDistanceM * 10.0) / 10.0); put("mySteps", r.mySteps); put("othersSteps", r.othersSteps)
                put("togetherPct", r.togetherPct); put("longestTogetherMs", r.longestTogetherMs); put("maxGapM", Math.round(r.maxGapM * 10.0) / 10.0)
                put("lanes", Json.parseToJsonElement(SharedWalkCodec.encodeLanes(r.lanes)))
                put("samples", Json.parseToJsonElement(SharedWalkCodec.encodeSamples(r.samples)))
                r.routePolyline?.let { put("routePolyline5", it) }
            }
        })
        return buildJsonObject {
            put("app", "walk-buddy"); put("kind", "walks-together"); put("version", 1)
            put("walks", walks)
        }.toString()
    }

    /** A GPX track of MY route when route saving was on for that walk, else null. Times are spread evenly over the walk. */
    fun gpx(r: SharedWalkRecord): String? {
        val pts = Polyline.decode(r.routePolyline)
        if (pts.size < 2) return null
        val n = pts.size
        val track = pts.mapIndexed { i, p -> TrackPoint(r.startMs + r.durationMs * i / (n - 1), p.lat, p.lon) }
        return Gpx.build("Walk together " + Instant.ofEpochMilli(r.startMs).toString().take(10), track)
    }

    fun fileName(r: SharedWalkRecord, zone: ZoneId = ZoneId.systemDefault(), ext: String): String {
        val d = Instant.ofEpochMilli(r.startMs).atZone(zone)
        return String.format(Locale.US, "walk-together-%04d-%02d-%02d-%02d%02d.%s", d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, ext)
    }
}
