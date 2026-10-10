package com.walkbuddy.session

import android.content.Context
import com.walkbuddy.domain.DecodeResult
import com.walkbuddy.domain.LinkAction
import com.walkbuddy.domain.LinkEnvelope
import com.walkbuddy.domain.LinkMode
import com.walkbuddy.domain.LinkQuality
import com.walkbuddy.domain.LinkRoute
import com.walkbuddy.domain.LinkWatchdog
import com.walkbuddy.domain.MessageCodec
import com.walkbuddy.domain.PeerMessage
import com.walkbuddy.domain.SeqGate
import com.walkbuddy.domain.SeqGen
import com.walkbuddy.domain.SignalingMessage
import com.walkbuddy.rtc.PeerLink
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The partner link as one thing for the session: a direct WebRTC data channel when the network allows it, and the server relay as a hot
 * standby the whole time. Messages take the direct path first, the relay carries a thin trickle of positions while the direct path is
 * healthy and everything small when it is not, and the receiving side uses each message once (sequence numbers) whichever path it came by.
 * A [LinkWatchdog] repairs the direct path with ICE restarts, backoff and jitter; nothing here ever blocks or ends the walk.
 *
 * All methods run on the session's thread (the main thread); WebRTC callbacks hop over with [scope].
 */
class PartnerTransport(
    context: Context,
    private val selfId: String,
    private val scope: CoroutineScope,
    /** Sends to the signaling server; false when the socket is not open. */
    private val signal: (SignalingMessage) -> Boolean,
    /** A de-duplicated message from a partner, whichever path it took. */
    private val onMessage: (from: String, msg: PeerMessage) -> Unit,
    /** Who is reachable, or the link mode, may have changed. */
    private val onChange: () -> Unit,
    /** A key saved before the app was killed, so the server gives this phone its old place back (alpha 2.0 "resume your walk"). */
    resumeKey: String? = null,
) {
    /** Lets the same phone take its place in the room back at once after a network change (see server/README.md). */
    val sessionKey: String = resumeKey ?: ByteArray(18).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private val link = PeerLink(
        context = context, selfId = selfId,
        sendSignal = { m -> signal(m) },
        onText = { from, text -> scope.launch { receive(from, text, viaRelay = false) } },
        onPeerConnected = { id, ok -> scope.launch { onP2p(id, ok) } },
    )
    private val seq = SeqGen()
    private val gate = SeqGate()
    private val quality = LinkQuality(freshMs = 45_000L)
    private val watchdog = LinkWatchdog()
    private val inRoom = LinkedHashSet<String>()
    private val relayQueue = ArrayDeque<Pair<String, String>>()
    private var pumping = false
    private var serverUp = false
    private var closed = false
    private var lastDirectHeardMs = 0L
    private var lastRelayPositionMs = 0L
    private var lastSentMs = 0L
    private var networkRestartPending = false

    /** People currently in the server room (the relay can reach them). */
    val roomPeers: Set<String> get() = inRoom

    fun isDirect(id: String) = link.isConnected(id)

    /** Reachable by either path. */
    fun reachable(id: String) = link.isConnected(id) || id in inRoom

    fun msSinceSend(now: Long) = now - lastSentMs

    var online: Boolean
        get() = quality.online
        set(v) { quality.online = v }

    fun mode(now: Long): LinkMode = quality.mode(now)

    // ---------- server events ----------

    fun onServer(up: Boolean, now: Long) {
        serverUp = up
        if (!up) { inRoom.clear(); relayQueue.clear() }
        quality.setServer(up, inRoom.isNotEmpty(), now)
        onChange()
    }

    /** We joined the room; [peers] were already there, so we place the calls. */
    fun onJoined(peers: List<String>, now: Long) {
        inRoom.clear(); inRoom.addAll(peers)
        quality.setServer(serverUp, inRoom.isNotEmpty(), now)
        peers.forEach { link.callPeer(it) }
        onChange()
    }

    fun onPeerJoined(id: String, now: Long) {
        inRoom.add(id)
        quality.setServer(serverUp, true, now)
        onChange()
    }

    /**
     * The server says [id] left its room. That is only about the server socket: a direct channel that still works is kept (the partner's
     * phone may just have lost its connection to the server). A direct connection that is not working is dropped; the partner calls again.
     */
    fun onPeerLeft(id: String, now: Long) {
        inRoom.remove(id)
        quality.setServer(serverUp, inRoom.isNotEmpty(), now)
        if (!link.isConnected(id)) link.peerLeft(id)
        onChange()
    }

    /** The partner said goodbye on purpose: forget everything about them. */
    fun forget(id: String) {
        inRoom.remove(id)
        gate.forget(id)
        link.peerLeft(id)
        onChange()
    }

    fun onSignal(m: SignalingMessage.Relay) = link.onRelay(m)

    // ---------- sending ----------

    /** Sends [msg] by the path(s) its kind needs. Returns how many routes took it (0 = nobody could be reached right now). */
    fun send(msg: PeerMessage, now: Long): Int {
        if (closed) return 0
        val type = msg.type
        val text = LinkEnvelope.stamp(MessageCodec.encode(msg), seq.next(now))
        val p2p = link.anyOpen()
        val healthy = p2p && now - lastDirectHeardMs <= 20_000L
        val serverReady = serverUp && inRoom.isNotEmpty()
        val plan = LinkRoute.plan(type, healthy, serverReady, now - lastRelayPositionMs)
        var n = 0
        // A direct path that looks open but has been silent is suspect: still try it, and let the relay carry the message too.
        if ((plan.direct || p2p) && link.broadcast(text) > 0) n++
        if (plan.relay) {
            if (type == "bye") signal(SignalingMessage.PairSend(text)) // the socket closes right after; do not leave it in the queue
            else enqueueRelay(type, text)
            if (type == "pos") lastRelayPositionMs = now
            n++
        }
        if (n > 0) lastSentMs = now
        return n
    }

    private fun enqueueRelay(type: String, text: String) {
        if (type == "pos") relayQueue.removeAll { it.first == "pos" } // only the newest position matters
        if (relayQueue.size >= 8) relayQueue.removeFirst()
        relayQueue.addLast(type to text)
        pump()
    }

    /** The server drops partner messages that come closer than ~120 ms apart, so the relay is fed at a gentle pace. */
    private fun pump() {
        if (pumping) return
        pumping = true
        scope.launch {
            try {
                while (!closed && relayQueue.isNotEmpty()) {
                    val (_, text) = relayQueue.removeFirst()
                    signal(SignalingMessage.PairSend(text))
                    delay(RELAY_GAP_MS)
                }
            } finally {
                pumping = false
            }
        }
    }

    // ---------- receiving ----------

    fun onRelayData(from: String?, data: String) {
        if (from == null) return
        receive(from, data, viaRelay = true)
    }

    private fun receive(from: String, text: String, viaRelay: Boolean) {
        if (closed) return
        val now = System.currentTimeMillis()
        val msg = (MessageCodec.decode(text) as? DecodeResult.Ok)?.message ?: return
        // A copy that has already arrived by the other path still proves this path works.
        quality.heard(viaRelay, now)
        if (!viaRelay) lastDirectHeardMs = now
        var changed = false
        if (viaRelay && inRoom.add(from)) { quality.setServer(serverUp, true, now); changed = true }
        if (!gate.accept(from, msg.type, LinkEnvelope.seqOf(text))) { if (changed) onChange(); return }
        onMessage(from, msg)
        if (changed) onChange()
    }

    // ---------- repair ----------

    private fun onP2p(id: String, ok: Boolean) {
        if (closed) return
        val now = System.currentTimeMillis()
        val open = link.anyOpen()
        quality.setP2pOpen(open, now)
        if (ok) lastDirectHeardMs = now
        watchdog.onP2p(link.notOpenPeers().isEmpty() && link.connectionIds().isNotEmpty(), now)
        onChange()
    }

    /** The phone's default network changed: the old direct path is probably gone even if the channel still looks open. */
    fun onNetworkChanged(now: Long) {
        networkRestartPending = true
        watchdog.onNetworkChanged(now)
    }

    /** Call every couple of seconds while the session is open. Cheap when everything is fine. */
    fun poll(now: Long) {
        if (closed) return
        quality.setServer(serverUp, inRoom.isNotEmpty(), now)
        when (watchdog.poll(now)) {
            LinkAction.None -> Unit
            is LinkAction.RestartIce -> {
                if (networkRestartPending) { networkRestartPending = false; link.restartAll() }
                else link.notOpenPeers().forEach { link.restartIce(it) }
                callMissing()
            }
            LinkAction.Recreate -> {
                link.notOpenPeers().forEach { link.recreate(it) }
                callMissing()
            }
        }
    }

    /** People in the server room we have no connection to at all: place the call. */
    private fun callMissing() {
        for (id in inRoom.toList()) if (!link.hasConnection(id)) link.callPeer(id)
    }

    fun close() {
        closed = true
        relayQueue.clear()
        link.close()
    }

    private companion object {
        const val RELAY_GAP_MS = 150L
    }
}
