package com.walkbuddy.domain

import kotlin.random.Random

/**
 * How the partner link is doing, in words a walker understands. The app has two ways to reach a partner:
 * a direct WebRTC data channel between the phones (preferred) and the relay on the Walk Buddy server (hot standby).
 */
enum class LinkMode(val label: String) {
    /** Nobody to talk to yet (the lobby, or the partner has not appeared). */
    Waiting("Waiting for your buddy"),
    Direct("Direct"),
    ViaServer("Via server"),
    Reconnecting("Reconnecting..."),
    Offline("Offline");

    /** One calm sentence for the small indicator's description. */
    val explanation: String
        get() = when (this) {
            Waiting -> "Your buddy has not connected yet."
            Direct -> "Phone to phone. Updates take the shortest path."
            ViaServer -> "A direct connection is not possible right now, so updates pass through the server. Nothing is stored there."
            Reconnecting -> "The link dropped. Your walk is safe on this phone and updates resume as soon as a path is found."
            Offline -> "This phone has no network. Your walk keeps being recorded here."
        }
}

/**
 * A running number put on every partner message so the same update arriving on both paths (direct and via the server) is used once,
 * and a late copy of an older update never overwrites a newer one. It starts at the clock, so a phone that restarts mid-walk still counts up.
 */
class SeqGen {
    private var last = 0L

    @Synchronized
    fun next(nowMs: Long): Long {
        last = maxOf(last + 1, nowMs)
        return last
    }
}

object LinkEnvelope {
    /** Adds the sequence number as the first field of a JSON object text. */
    fun stamp(json: String, seq: Long): String = when {
        json.length < 2 || json[0] != '{' -> json
        json == "{}" -> "{\"q\":$seq}"
        else -> "{\"q\":$seq," + json.substring(1)
    }

    /** The sequence number [stamp] put in front, or null for an older app version that sends none. */
    fun seqOf(text: String): Long? {
        if (!text.startsWith("{\"q\":")) return null
        var i = 5
        val start = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == start || i - start > 18) return null
        return text.substring(start, i).toLongOrNull()
    }
}

/**
 * Accepts each partner message once. Duplicates (the same message over both paths) and late older copies of the same kind are refused.
 * Kept per sender and message type so a quick reaction can never hide a position that is still on its way over the other path.
 */
class SeqGate {
    private val last = HashMap<String, Long>()

    /** [seq] null (an older app) is always accepted. */
    fun accept(peer: String, type: String, seq: Long?): Boolean {
        if (seq == null) return true
        val key = "$peer|$type"
        val l = last[key]
        if (l != null && seq <= l) return false
        last[key] = seq
        return true
    }

    fun forget(peer: String) { last.keys.removeAll { it.startsWith("$peer|") } }
    fun clear() = last.clear()
}

/**
 * Works out the single label for the link indicator from what the app knows. Pure and clock-driven, so it is easy to test.
 * "Fresh" means we heard from the partner on that path recently enough that it is plainly working.
 */
class LinkQuality(private val freshMs: Long = 25_000L) {
    var online: Boolean = true
    var p2pOpen: Boolean = false
        private set
    var serverUp: Boolean = false
        private set
    var partnerInRoom: Boolean = false
        private set
    var everConnected: Boolean = false
        private set
    private var lastDirectMs: Long? = null
    private var lastRelayMs: Long? = null
    private var relayReadyMs: Long? = null

    fun setP2pOpen(open: Boolean, nowMs: Long) {
        p2pOpen = open
        if (open) { everConnected = true; lastDirectMs = nowMs }
    }

    /** The socket to the server is up; [partnerHere] says the partner is in the same room right now. */
    fun setServer(up: Boolean, partnerHere: Boolean, nowMs: Long) {
        val wasReady = serverUp && partnerInRoom
        serverUp = up
        partnerInRoom = up && partnerHere
        if (partnerInRoom && !wasReady) { relayReadyMs = nowMs; everConnected = true }
        if (!partnerInRoom) relayReadyMs = null
    }

    fun heard(viaRelay: Boolean, nowMs: Long) {
        everConnected = true
        if (viaRelay) lastRelayMs = nowMs else lastDirectMs = nowMs
    }

    fun mode(nowMs: Long): LinkMode {
        if (!online) return LinkMode.Offline
        val directFresh = p2pOpen && lastDirectMs?.let { nowMs - it <= freshMs } == true
        if (directFresh) return LinkMode.Direct
        val relayFresh = serverUp && partnerInRoom &&
            ((lastRelayMs?.let { nowMs - it <= freshMs } == true) || (relayReadyMs?.let { nowMs - it <= freshMs } == true))
        if (relayFresh) return LinkMode.ViaServer
        return if (everConnected) LinkMode.Reconnecting else LinkMode.Waiting
    }
}

sealed class LinkAction {
    object None : LinkAction()
    /** Ask the same peer connection to look for a new path (ICE restart). Only the side that placed the call performs it. */
    data class RestartIce(val attempt: Int) : LinkAction()
    /** Several restarts did not help: throw the peer connection away and call again from scratch. */
    object Recreate : LinkAction()
}

/**
 * Decides when to try to repair a direct link that is down. No tight loops: a grace period first (a brief dip often heals itself),
 * then restarts with exponential backoff and jitter, and every few failed restarts a clean new connection. A network change
 * (Wi-Fi to mobile data) asks for an immediate restart even when the old link still looks open, because its path is already gone.
 * The server relay carries the walk in the meantime, so this never needs to give up.
 */
class LinkWatchdog(
    private val graceMs: Long = 6_000,
    private val connectGraceMs: Long = 20_000,
    private val recreateEvery: Int = 4,
    private val baseMs: Long = 4_000,
    private val capMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    private var open = false
    private var everOpen = false
    private var downSince: Long? = null
    private var nextAt = 0L
    private var attempts = 0
    private var pendingNetworkAt: Long? = null

    val attemptCount: Int get() = attempts

    fun onP2p(isOpen: Boolean, nowMs: Long) {
        if (isOpen) {
            open = true; everOpen = true; downSince = null; attempts = 0; nextAt = 0L
        } else {
            if (open || downSince == null) downSince = nowMs
            open = false
        }
    }

    /** The phone's default network changed. A short random delay lets the new network settle and spreads out two phones that switch together. */
    fun onNetworkChanged(nowMs: Long) {
        pendingNetworkAt = nowMs + 400L + random.nextLong(800)
        attempts = 0
        nextAt = 0L
    }

    fun poll(nowMs: Long): LinkAction {
        pendingNetworkAt?.let { at ->
            if (nowMs >= at) {
                pendingNetworkAt = null
                attempts = 1
                nextAt = nowMs + Backoff.delayMs(attempts, random, baseMs, capMs)
                if (!open && downSince == null) downSince = nowMs
                return LinkAction.RestartIce(attempts)
            }
            return LinkAction.None
        }
        if (open) return LinkAction.None
        val since = downSince ?: run { downSince = nowMs; nowMs }
        val grace = if (everOpen) graceMs else connectGraceMs
        if (nowMs - since < grace) return LinkAction.None
        if (nowMs < nextAt) return LinkAction.None
        attempts++
        nextAt = nowMs + Backoff.delayMs(attempts, random, baseMs, capMs)
        return if (attempts % recreateEvery == 0) LinkAction.Recreate else LinkAction.RestartIce(attempts)
    }
}

/**
 * Chooses which path(s) a partner message takes. The direct data channel is preferred. The server relay is a hot standby:
 * while the direct path works it carries only a thin trickle of positions (so a sudden drop loses almost nothing); while the direct
 * path is down it carries everything small. Bulky or optional things never use it.
 */
object LinkRoute {
    /** Position updates over the relay while the direct link is healthy (a standby heartbeat). */
    const val STANDBY_POSITION_MS = 15_000L

    data class Plan(val direct: Boolean, val relay: Boolean)

    /** Types that are tiny and matter if the direct path is down. */
    private val ESSENTIAL = setOf("hello", "pos", "day", "pin", "unpin", "bye")

    fun plan(type: String, p2pOpen: Boolean, serverReady: Boolean, msSinceLastRelayPosition: Long): Plan {
        if (!p2pOpen) return Plan(direct = false, relay = serverReady && (type in ESSENTIAL || type == "react" || type == "ping"))
        val standby = serverReady && type == "pos" && msSinceLastRelayPosition >= STANDBY_POSITION_MS
        val essentialToo = serverReady && (type == "hello" || type == "bye" || type == "pin" || type == "unpin")
        return Plan(direct = true, relay = standby || essentialToo)
    }
}
