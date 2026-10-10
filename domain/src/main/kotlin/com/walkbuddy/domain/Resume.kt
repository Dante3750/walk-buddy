package com.walkbuddy.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Resuming a walk after Android killed the app (alpha 2.0). While a walk runs, a small snapshot is saved on the phone every little while:
 * what kind of walk it is, when it started, how far and how many steps so far, a thinned-out copy of my own route and, for a partner or
 * group walk, the code and session key needed to rejoin. On the next launch [ResumePolicy] decides whether to offer "Resume your walk?".
 * Everything here is pure Kotlin; the Android side only stores the text. The snapshot never leaves the phone and is deleted when the walk
 * ends, is discarded, or all data is deleted.
 */
enum class ResumeKind {
    Solo, Partner, Group;

    companion object {
        fun fromName(n: String?): ResumeKind? = values().firstOrNull { it.name == n }
    }
}

data class ResumeState(
    val kind: ResumeKind,
    val startMs: Long,
    val savedMs: Long,
    /** Partner or group code (null for a solo walk). */
    val code: String? = null,
    /** The session key (partner) or group key, so the server gives me my place back. */
    val key: String? = null,
    val nickname: String? = null,
    val host: Boolean = false,
    val precision: String? = null,
    val distanceM: Double = 0.0,
    val verifiedSteps: Long = 0,
    val rawSteps: Long = 0,
    /** Encoded polyline of my own route so far (thinned out), or null. */
    val route: String? = null,
    val pinLat: Double? = null,
    val pinLon: Double? = null,
    val pinLabel: String? = null,
) {
    val walkedMs: Long get() = (savedMs - startMs).coerceAtLeast(0)
}

object ResumeCodec {
    const val VERSION = 1
    private const val MAX_TEXT = 40_000

    fun encode(s: ResumeState): String = buildJsonObject {
        put("v", VERSION)
        put("kind", s.kind.name)
        put("start", s.startMs)
        put("saved", s.savedMs)
        s.code?.let { put("code", it) }
        s.key?.let { put("key", it) }
        s.nickname?.let { put("nick", it) }
        if (s.host) put("host", true)
        s.precision?.let { put("prec", it) }
        put("dist", s.distanceM)
        put("steps", s.verifiedSteps)
        put("raw", s.rawSteps)
        s.route?.let { put("route", it) }
        if (s.pinLat != null && s.pinLon != null) {
            put("pinLat", s.pinLat); put("pinLon", s.pinLon)
            s.pinLabel?.let { put("pinLabel", it) }
        }
    }.toString()

    /** Never throws: anything unreadable, from another version or too large gives null. */
    fun decode(text: String?): ResumeState? {
        if (text.isNullOrBlank() || text.length > MAX_TEXT) return null
        return try {
            val o = Json.parseToJsonElement(text) as? JsonObject ?: return null
            if (o["v"]?.jsonPrimitive?.longOrNull != VERSION.toLong()) return null
            val kind = ResumeKind.fromName(o["kind"]?.jsonPrimitive?.contentOrNull) ?: return null
            val start = o["start"]?.jsonPrimitive?.longOrNull ?: return null
            val saved = o["saved"]?.jsonPrimitive?.longOrNull ?: return null
            fun str(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ResumeState(
                kind = kind, startMs = start, savedMs = saved,
                code = str("code"), key = str("key"), nickname = str("nick")?.take(24),
                host = o["host"]?.jsonPrimitive?.booleanOrNull == true,
                precision = str("prec"),
                distanceM = (o["dist"]?.jsonPrimitive?.doubleOrNull ?: 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                verifiedSteps = (o["steps"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0),
                rawSteps = (o["raw"]?.jsonPrimitive?.longOrNull ?: 0L).coerceAtLeast(0),
                route = str("route"),
                pinLat = o["pinLat"]?.jsonPrimitive?.doubleOrNull, pinLon = o["pinLon"]?.jsonPrimitive?.doubleOrNull,
                pinLabel = str("pinLabel")?.take(40),
            )
        } catch (e: Exception) {
            null
        }
    }
}

sealed class ResumeDecision {
    /** Nothing saved, or a walk is already running. */
    object None : ResumeDecision()

    data class Offer(val state: ResumeState) : ResumeDecision()

    /** A snapshot exists but is not worth offering (too old, too short, incomplete): delete it. */
    data class Discard(val reason: String) : ResumeDecision()
}

object ResumePolicy {
    /** A walk that stopped being saved this long ago is over, whatever happened to it. */
    const val MAX_AGE_MS = 3 * 3_600_000L

    /** Walks shorter than this are not worth resuming. */
    const val MIN_WALKED_MS = 45_000L

    /** How often a running walk saves its snapshot, and how much it must have moved to save sooner. */
    const val SAVE_EVERY_MS = 20_000L
    const val SAVE_EVERY_M = 60.0

    const val MAX_ROUTE_POINTS = 300

    fun decide(state: ResumeState?, nowMs: Long, sessionActive: Boolean): ResumeDecision {
        if (state == null || sessionActive) return ResumeDecision.None
        if (state.savedMs > nowMs + 5 * 60_000L) return ResumeDecision.Discard("clock")
        if (nowMs - state.savedMs > MAX_AGE_MS) return ResumeDecision.Discard("old")
        if (state.walkedMs < MIN_WALKED_MS) return ResumeDecision.Discard("short")
        if (state.kind != ResumeKind.Solo && SessionCode.normalize(state.code) == null) return ResumeDecision.Discard("code")
        if (state.kind == ResumeKind.Group && state.key.isNullOrBlank()) return ResumeDecision.Discard("key")
        return ResumeDecision.Offer(state)
    }

    fun shouldSave(lastSaveMs: Long, lastSaveDistanceM: Double, nowMs: Long, distanceM: Double): Boolean {
        if (nowMs < lastSaveMs) return true
        if (nowMs - lastSaveMs >= SAVE_EVERY_MS) return true
        return distanceM - lastSaveDistanceM >= SAVE_EVERY_M
    }

    /** Thins my route to a size that is cheap to save every 20 seconds. */
    fun compactRoute(points: List<LatLon>): String? {
        if (points.size < 2) return null
        val thin = Polyline.simplify(points, epsilonM = 12.0, maxPoints = MAX_ROUTE_POINTS)
        return Polyline.encode(thin)
    }

    /**
     * The summary of a resumed walk covers the whole walk, not just the part after the restart: the walking time before the kill, the
     * distance and the steps are added to what the new engine measured.
     */
    fun carryInto(summary: WalkSummary, state: ResumeState): WalkSummary {
        val dur = summary.durationMs + state.walkedMs
        val dist = summary.distanceM + state.distanceM
        val avg = if (dur > 0 && dist > 0) dist / (dur / 1000.0) else summary.avgSpeedMps
        return summary.copy(
            durationMs = dur, distanceM = dist,
            verifiedSteps = summary.verifiedSteps + state.verifiedSteps, rawSteps = summary.rawSteps + state.rawSteps,
            avgSpeedMps = avg,
            moderateMin = summary.moderateMin + (state.walkedMs / 60_000L).toInt() / 2,
        )
    }
}
