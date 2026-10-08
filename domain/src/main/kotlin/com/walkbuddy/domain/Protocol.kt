package com.walkbuddy.domain

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 6-character session code from an alphabet without look-alikes (no 0/O, 1/I). */
object SessionCode {
    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val LENGTH = 6

    fun generate(random: Random = Random.Default): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Uppercases and strips spaces/dashes; returns null if the result is not a valid code. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.uppercase()?.filter { it != ' ' && it != '-' } ?: return null
        return if (s.length == LENGTH && s.all { it in ALPHABET }) s else null
    }
}

data class JoinTarget(val code: String, val serverUrl: String?)

/** `walkbuddy://join/ABC234?s=wss%3A%2F%2Fexample.org` (server hint optional). */
object JoinLink {
    const val SCHEME = "walkbuddy"
    private const val PREFIX = "$SCHEME://join/"

    fun build(code: String, serverUrl: String? = null): String {
        val base = PREFIX + code
        return if (serverUrl.isNullOrBlank()) base else base + "?s=" + URLEncoder.encode(serverUrl, "UTF-8")
    }

    /** Accepts a full link or a bare code (with optional spaces/dashes). */
    fun parse(text: String?): JoinTarget? {
        val t = text?.trim() ?: return null
        if (!t.startsWith(PREFIX, ignoreCase = true)) {
            return SessionCode.normalize(t)?.let { JoinTarget(it, null) }
        }
        val rest = t.substring(PREFIX.length)
        val codePart = rest.substringBefore('?').trimEnd('/')
        val code = SessionCode.normalize(codePart) ?: return null
        val query = rest.substringAfter('?', "")
        var server: String? = null
        for (kv in query.split('&')) {
            if (kv.startsWith("s=")) {
                val v = runCatching { URLDecoder.decode(kv.substring(2), "UTF-8") }.getOrNull()
                if (v != null && (v.startsWith("wss://") || v.startsWith("ws://")) && v.length <= 200) server = v
            }
        }
        return JoinTarget(code, server)
    }
}

/** Messages exchanged phone-to-phone over the WebRTC data channel. */
sealed class PeerMessage {
    abstract val type: String

    data class Hello(val peerId: String, val name: String) : PeerMessage() { override val type get() = "hello" }

    data class Position(
        val tMs: Long,
        val lat: Double,
        val lon: Double,
        val accuracyM: Double?,
        val speedMps: Double?,
        val steps: Int,
        val cadenceSpm: Double?,
        val distanceM: Double?,
    ) : PeerMessage() { override val type get() = "pos" }

    /** "Thinking of you" ping. */
    data class Ping(val tMs: Long) : PeerMessage() { override val type get() = "ping" }

    data class Spot(val name: String, val lat: Double, val lon: Double) : PeerMessage() { override val type get() = "spot" }

    /** One of the preset warm reactions (see [Reaction]); no free text travels. */
    data class React(val id: String) : PeerMessage() { override val type get() = "react" }

    /** My steps today and my goal, so a buddy's avatar can sit at the right place on their ring. */
    data class Daily(val steps: Int, val goal: Int) : PeerMessage() { override val type get() = "day" }

    object Bye : PeerMessage() { override val type get() = "bye" }

    /** A well-formed message of a type this version does not know (forward compatibility). */
    data class Unknown(override val type: String) : PeerMessage()
}

sealed class DecodeResult {
    data class Ok(val message: PeerMessage) : DecodeResult()
    data class Rejected(val reason: String) : DecodeResult()
}

/**
 * Versioned JSON codec. Envelope: `{"v":1,"t":"pos", ...}`.
 * Decoding is tolerant (unknown fields ignored, numbers may arrive as strings, newer versions accepted)
 * but validating (ranges, sizes, finite numbers) so a buggy or hostile peer cannot poison the UI.
 */
object MessageCodec {
    const val VERSION = 1
    const val MAX_CHARS = 2048
    const val MAX_NAME = 24
    const val MAX_SPOT_NAME = 40
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(m: PeerMessage): String {
        val o = buildJsonObject {
            put("v", VERSION)
            put("t", m.type)
            when (m) {
                is PeerMessage.Hello -> { put("id", m.peerId); put("name", m.name) }
                is PeerMessage.Position -> {
                    put("ts", m.tMs); put("lat", m.lat); put("lon", m.lon)
                    m.accuracyM?.let { put("acc", it) }
                    m.speedMps?.let { put("spd", it) }
                    put("steps", m.steps)
                    m.cadenceSpm?.let { put("cad", it) }
                    m.distanceM?.let { put("dist", it) }
                }
                is PeerMessage.Ping -> put("ts", m.tMs)
                is PeerMessage.Spot -> { put("name", m.name); put("lat", m.lat); put("lon", m.lon) }
                is PeerMessage.React -> put("id", m.id)
                is PeerMessage.Daily -> { put("steps", m.steps); put("goal", m.goal) }
                PeerMessage.Bye -> Unit
                is PeerMessage.Unknown -> Unit
            }
        }
        return o.toString()
    }

    fun decode(text: String?): DecodeResult {
        if (text == null || text.isEmpty()) return DecodeResult.Rejected("empty")
        if (text.length > MAX_CHARS) return DecodeResult.Rejected("too large")
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return DecodeResult.Rejected("not json")
        }
        val o = root as? JsonObject ?: return DecodeResult.Rejected("not an object")
        val v = o.int("v") ?: return DecodeResult.Rejected("missing version")
        if (v < 1) return DecodeResult.Rejected("bad version")
        val t = o.str("t")?.take(32) ?: return DecodeResult.Rejected("missing type")
        return when (t) {
            "hello" -> {
                val id = cleanId(o.str("id")) ?: return DecodeResult.Rejected("bad id")
                DecodeResult.Ok(PeerMessage.Hello(id, cleanName(o.str("name"), MAX_NAME) ?: "Buddy"))
            }
            "pos" -> {
                val lat = o.dbl("lat"); val lon = o.dbl("lon")
                if (lat == null || lon == null || !LatLon(lat, lon).isValid) return DecodeResult.Rejected("bad position")
                val ts = o.long("ts") ?: return DecodeResult.Rejected("missing time")
                if (ts <= 0) return DecodeResult.Rejected("bad time")
                val steps = o.int("steps") ?: 0
                if (steps < 0 || steps > 1_000_000) return DecodeResult.Rejected("bad steps")
                DecodeResult.Ok(
                    PeerMessage.Position(
                        tMs = ts, lat = lat, lon = lon,
                        accuracyM = o.dbl("acc")?.takeIf { it in 0.0..10_000.0 },
                        speedMps = o.dbl("spd")?.takeIf { it in 0.0..100.0 },
                        steps = steps,
                        cadenceSpm = o.dbl("cad")?.takeIf { it in 0.0..400.0 },
                        distanceM = o.dbl("dist")?.takeIf { it in 0.0..1_000_000.0 },
                    )
                )
            }
            "ping" -> DecodeResult.Ok(PeerMessage.Ping(o.long("ts") ?: 0L))
            "spot" -> {
                val lat = o.dbl("lat"); val lon = o.dbl("lon")
                if (lat == null || lon == null || !LatLon(lat, lon).isValid) return DecodeResult.Rejected("bad spot")
                val name = cleanName(o.str("name"), MAX_SPOT_NAME) ?: return DecodeResult.Rejected("bad spot name")
                DecodeResult.Ok(PeerMessage.Spot(name, lat, lon))
            }
            "react" -> {
                val r = Reaction.fromId(o.str("id")?.take(24)) ?: return DecodeResult.Rejected("unknown reaction")
                DecodeResult.Ok(PeerMessage.React(r.id))
            }
            "day" -> {
                val steps = o.int("steps") ?: return DecodeResult.Rejected("bad steps")
                val goal = o.int("goal") ?: return DecodeResult.Rejected("bad goal")
                if (steps !in 0..300_000 || goal !in 500..100_000) return DecodeResult.Rejected("bad daily")
                DecodeResult.Ok(PeerMessage.Daily(steps, goal))
            }
            "bye" -> DecodeResult.Ok(PeerMessage.Bye)
            else -> DecodeResult.Ok(PeerMessage.Unknown(t))
        }
    }

    fun cleanName(raw: String?, max: Int): String? {
        val s = raw?.filter { !it.isISOControl() }?.trim()?.take(max) ?: return null
        return s.ifEmpty { null }
    }

    private fun cleanId(raw: String?): String? {
        val s = raw?.trim() ?: return null
        return if (s.length in 1..32 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }) s else null
    }
}

internal fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
internal fun JsonObject.str(key: String): String? = prim(key)?.content
internal fun JsonObject.dbl(key: String): Double? = prim(key)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
internal fun JsonObject.long(key: String): Long? = prim(key)?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
internal fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
internal fun JsonObject.elem(key: String): JsonElement? = this[key]

/**
 * Client <-> signaling-server protocol (see server/README.md). The server only relays offer/answer/ice.
 */
sealed class SignalingMessage {
    data class Join(val code: String, val peerId: String) : SignalingMessage()
    data class Joined(val you: String, val peers: List<String>) : SignalingMessage()
    data class PeerJoined(val peerId: String) : SignalingMessage()
    data class PeerLeft(val peerId: String) : SignalingMessage()
    /** kind is "offer", "answer" or "ice"; payload is an opaque string (SDP or candidate JSON). */
    data class Relay(val kind: String, val from: String?, val to: String, val payload: String) : SignalingMessage()
    data class Error(val code: String) : SignalingMessage()
}

object SignalingCodec {
    const val MAX_PAYLOAD = 16_384
    val RELAY_KINDS = setOf("offer", "answer", "ice")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(m: SignalingMessage): String = buildJsonObject {
        when (m) {
            is SignalingMessage.Join -> { put("t", "join"); put("code", m.code); put("peer", m.peerId) }
            is SignalingMessage.Joined -> {
                put("t", "joined"); put("you", m.you)
                put("peers", kotlinx.serialization.json.JsonArray(m.peers.map { JsonPrimitive(it) }))
            }
            is SignalingMessage.PeerJoined -> { put("t", "peer-joined"); put("peer", m.peerId) }
            is SignalingMessage.PeerLeft -> { put("t", "peer-left"); put("peer", m.peerId) }
            is SignalingMessage.Relay -> {
                put("t", m.kind); m.from?.let { put("from", it) }; put("to", m.to); put("data", m.payload)
            }
            is SignalingMessage.Error -> { put("t", "error"); put("code", m.code) }
        }
    }.toString()

    fun decode(text: String?): SignalingMessage? {
        if (text == null || text.length > MAX_PAYLOAD + 512) return null
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when (val t = o.str("t")) {
            "join" -> {
                val code = SessionCode.normalize(o.str("code")) ?: return null
                SignalingMessage.Join(code, o.str("peer") ?: return null)
            }
            "joined" -> {
                val peers = (o.elem("peers") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                SignalingMessage.Joined(o.str("you") ?: return null, peers)
            }
            "peer-joined" -> SignalingMessage.PeerJoined(o.str("peer") ?: return null)
            "peer-left" -> SignalingMessage.PeerLeft(o.str("peer") ?: return null)
            "offer", "answer", "ice" -> {
                val data = o.str("data") ?: return null
                if (data.length > MAX_PAYLOAD) return null
                SignalingMessage.Relay(t, o.str("from"), o.str("to") ?: return null, data)
            }
            "error" -> SignalingMessage.Error(o.str("code") ?: "unknown")
            else -> null
        }
    }
}
