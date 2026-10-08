package com.walkbuddy.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Room settings the host controls. [goalSteps] 0 means "no collective goal". */
data class GroupSettings(val approval: Boolean = false, val goalSteps: Int = 0, val title: String = "", val max: Int = 50)

data class RosterEntry(val peerId: String, val name: String, val host: Boolean)

/** One small update relayed by the server. lat/lon are null for a member who is not sharing location. */
data class GroupUpdate(
    val tMs: Long?,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double? = null,
    val speedMps: Double? = null,
    val steps: Int = 0,
    val cadenceSpm: Double? = null,
    val distanceM: Double? = null,
) {
    val pos: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else null

    companion object {
        fun from(p: PeerMessage.Position) = GroupUpdate(p.tMs, p.lat, p.lon, p.accuracyM, p.speedMps, p.steps, p.cadenceSpm, p.distanceM)
    }
}

/** Phone to server. */
sealed class GroupClientMessage {
    data class Create(
        val peerId: String, val key: String, val name: String,
        val approval: Boolean = false, val ttlMin: Int = 240, val goalSteps: Int = 0, val title: String = "",
    ) : GroupClientMessage()

    data class Join(val code: String, val peerId: String, val key: String, val name: String) : GroupClientMessage()
    data class Update(val update: GroupUpdate) : GroupClientMessage()
    object Leave : GroupClientMessage()
    data class Approve(val peerId: String) : GroupClientMessage()
    data class Deny(val peerId: String) : GroupClientMessage()
    data class Kick(val peerId: String) : GroupClientMessage()
    object CloseRoom : GroupClientMessage()
    data class ChangeSettings(val approval: Boolean? = null, val goalSteps: Int? = null, val title: String? = null) : GroupClientMessage()
    data class Pin(val lat: Double, val lon: Double, val label: String) : GroupClientMessage()
    object Unpin : GroupClientMessage()
}

/** Server to phone. */
sealed class GroupServerMessage {
    data class Joined(
        val code: String, val you: String, val host: String,
        val roster: List<RosterEntry>, val settings: GroupSettings, val expiresInSec: Int,
    ) : GroupServerMessage()

    data class MemberJoined(val peerId: String, val name: String, val host: Boolean) : GroupServerMessage()
    data class MemberLeft(val peerId: String, val reason: String) : GroupServerMessage()
    data class Upd(val from: String, val update: GroupUpdate) : GroupServerMessage()
    data class SettingsChanged(val settings: GroupSettings) : GroupServerMessage()
    data class PinSet(val lat: Double, val lon: Double, val label: String) : GroupServerMessage()
    object PinCleared : GroupServerMessage()
    object Pending : GroupServerMessage()
    data class JoinRequest(val peerId: String, val name: String) : GroupServerMessage()
    data class JoinCancelled(val peerId: String) : GroupServerMessage()
    object Denied : GroupServerMessage()
    object Kicked : GroupServerMessage()
    object HostAway : GroupServerMessage()
    object HostBack : GroupServerMessage()
    object RoomClosed : GroupServerMessage()
    object RoomExpired : GroupServerMessage()
    data class Error(val code: String) : GroupServerMessage()
}

/** JSON codec for the open-group part of the server protocol (see server/README.md). Tolerant on input, strict on values. */
object GroupCodec {
    const val MAX_CHARS = 8_192
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(m: GroupClientMessage): String = buildJsonObject {
        when (m) {
            is GroupClientMessage.Create -> {
                put("t", "group-create"); put("peer", m.peerId); put("key", m.key); put("name", m.name)
                put("approval", m.approval); put("ttlMin", m.ttlMin); put("goalSteps", m.goalSteps); put("title", m.title)
            }
            is GroupClientMessage.Join -> { put("t", "group-join"); put("code", m.code); put("peer", m.peerId); put("key", m.key); put("name", m.name) }
            is GroupClientMessage.Update -> {
                put("t", "upd")
                put("d", buildJsonObject {
                    val u = m.update
                    u.tMs?.let { put("ts", it) }
                    if (u.lat != null && u.lon != null) { put("lat", u.lat); put("lon", u.lon) }
                    u.accuracyM?.let { put("acc", it) }
                    u.speedMps?.let { put("spd", it) }
                    put("steps", u.steps)
                    u.cadenceSpm?.let { put("cad", it) }
                    u.distanceM?.let { put("dist", it) }
                })
            }
            GroupClientMessage.Leave -> put("t", "leave")
            is GroupClientMessage.Approve -> { put("t", "approve"); put("peer", m.peerId) }
            is GroupClientMessage.Deny -> { put("t", "deny"); put("peer", m.peerId) }
            is GroupClientMessage.Kick -> { put("t", "kick"); put("peer", m.peerId) }
            GroupClientMessage.CloseRoom -> put("t", "close-room")
            is GroupClientMessage.ChangeSettings -> {
                put("t", "settings")
                m.approval?.let { put("approval", it) }
                m.goalSteps?.let { put("goalSteps", it) }
                m.title?.let { put("title", it) }
            }
            is GroupClientMessage.Pin -> { put("t", "pin"); put("lat", m.lat); put("lon", m.lon); put("label", m.label) }
            GroupClientMessage.Unpin -> put("t", "unpin")
        }
    }.toString()

    /** Returns null for anything that is not a well-formed server message of a known type. */
    fun decode(text: String?): GroupServerMessage? {
        if (text == null || text.length > MAX_CHARS) return null
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when (o.str("t")) {
            "group-joined" -> {
                val roster = (o.elem("roster") as? JsonArray)?.mapNotNull { e ->
                    val r = e as? JsonObject ?: return@mapNotNull null
                    val id = r.str("peer") ?: return@mapNotNull null
                    RosterEntry(id, MessageCodec.cleanName(r.str("name"), MessageCodec.MAX_NAME) ?: "Walker", r.str("host") == "true")
                }.orEmpty().take(200)
                GroupServerMessage.Joined(
                    code = SessionCode.normalize(o.str("code")) ?: return null,
                    you = o.str("you") ?: return null,
                    host = o.str("host") ?: return null,
                    roster = roster,
                    settings = settings(o.elem("settings") as? JsonObject),
                    expiresInSec = (o.int("expiresInSec") ?: 0).coerceAtLeast(0),
                )
            }
            "member-joined" -> GroupServerMessage.MemberJoined(
                o.str("peer") ?: return null, MessageCodec.cleanName(o.str("name"), MessageCodec.MAX_NAME) ?: "Walker", o.str("host") == "true",
            )
            "member-left" -> GroupServerMessage.MemberLeft(o.str("peer") ?: return null, o.str("reason")?.take(16) ?: "left")
            "upd" -> {
                val d = o.elem("d") as? JsonObject ?: return null
                val lat = d.dbl("lat"); val lon = d.dbl("lon")
                if ((lat == null) != (lon == null)) return null
                if (lat != null && lon != null && !LatLon(lat, lon).isValid) return null
                val steps = d.int("steps") ?: 0
                if (steps !in 0..1_000_000) return null
                GroupServerMessage.Upd(
                    from = o.str("from") ?: return null,
                    update = GroupUpdate(
                        tMs = d.long("ts")?.takeIf { it > 0 }, lat = lat, lon = lon,
                        accuracyM = d.dbl("acc")?.takeIf { it in 0.0..10_000.0 },
                        speedMps = d.dbl("spd")?.takeIf { it in 0.0..100.0 },
                        steps = steps,
                        cadenceSpm = d.dbl("cad")?.takeIf { it in 0.0..400.0 },
                        distanceM = d.dbl("dist")?.takeIf { it in 0.0..1_000_000.0 },
                    ),
                )
            }
            "settings" -> GroupServerMessage.SettingsChanged(settings(o.elem("settings") as? JsonObject))
            "pin" -> {
                val lat = o.dbl("lat"); val lon = o.dbl("lon")
                if (lat == null || lon == null || !LatLon(lat, lon).isValid) return null
                GroupServerMessage.PinSet(lat, lon, MessageCodec.cleanName(o.str("label"), MessageCodec.MAX_SPOT_NAME).orEmpty())
            }
            "unpin" -> GroupServerMessage.PinCleared
            "pending" -> GroupServerMessage.Pending
            "join-request" -> GroupServerMessage.JoinRequest(o.str("peer") ?: return null, MessageCodec.cleanName(o.str("name"), MessageCodec.MAX_NAME) ?: "Walker")
            "join-cancelled" -> GroupServerMessage.JoinCancelled(o.str("peer") ?: return null)
            "denied" -> GroupServerMessage.Denied
            "kicked" -> GroupServerMessage.Kicked
            "host-away" -> GroupServerMessage.HostAway
            "host-back" -> GroupServerMessage.HostBack
            "room-closed" -> GroupServerMessage.RoomClosed
            "room-expired" -> GroupServerMessage.RoomExpired
            "error" -> GroupServerMessage.Error(o.str("code")?.take(32) ?: "unknown")
            else -> null
        }
    }

    private fun settings(o: JsonObject?): GroupSettings {
        if (o == null) return GroupSettings()
        return GroupSettings(
            approval = o.str("approval") == "true",
            goalSteps = (o.int("goalSteps") ?: 0).coerceIn(0, 10_000_000),
            title = MessageCodec.cleanName(o.str("title"), 40).orEmpty(),
            max = (o.int("max") ?: 50).coerceIn(2, 500),
        )
    }
}

/** Copy for group errors, in the app's calm tone. */
object GroupCopy {
    fun error(code: String): String = when (code) {
        "no_such_room" -> "That group was not found. It may have ended, or the code has a typo."
        "room_full" -> "That group is full."
        "wrong_mode" -> "That code belongs to a different kind of walk."
        "removed" -> "The host removed you from this group."
        "id_taken" -> "This phone is already in that group."
        "too_many_joins" -> "Too many tries. Wait a minute and try again."
        "too_many_rooms" -> "Too many groups started from this network. Try again later."
        "too_many_pending" -> "Lots of people are asking to join. Try again in a moment."
        "server_busy" -> "The server is busy. Try again in a moment."
        "bad_code" -> "That code does not look right."
        else -> "Could not join the group ($code)."
    }

    const val ROOM_CLOSED = "The host ended this group walk."
    const val ROOM_EXPIRED = "This group walk timed out."
    const val KICKED = "The host removed you from this group."
    const val DENIED = "The host did not accept your request."
}
