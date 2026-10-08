package com.walkbuddy.rtc

import android.content.Context
import com.walkbuddy.domain.SignalingMessage
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Mesh of WebRTC DATA CHANNELS (no audio, no video tracks) between up to 4 phones, with STUN only (no TURN, see the README).
 * Signaling goes through [SignalingClient]; once a channel is open, walk data flows phone to phone. The server relay is the standby
 * that [com.walkbuddy.session.WalkSession] uses while this path is down.
 *
 * Repair, driven from outside by the session's watchdog: [restartIce] looks for a new network path on the same connection (what a
 * Wi-Fi to mobile switch needs), [recreate] throws the connection away and calls again. A peer is "open" only while its data channel
 * is open AND ICE is not disconnected/failed, so a half dead path is not trusted.
 *
 * Who offers: the newcomer places the first call. Either side may offer an ICE restart; if both do at once the phone with the smaller
 * id keeps its offer and the other one yields (the library rolls back its own offer when it accepts the remote one).
 *
 * Callbacks arrive on WebRTC threads; the owner hops to its own thread. Disposing is only ever done from the owner's calls, never
 * from inside an observer callback.
 */
class PeerLink(
    context: Context,
    private val selfId: String,
    private val sendSignal: (SignalingMessage) -> Unit,
    private val onText: (fromId: String, text: String) -> Unit,
    private val onPeerConnected: (peerId: String, connected: Boolean) -> Unit,
) {
    private class Remote(val id: String, val pc: PeerConnection) {
        @Volatile var channel: DataChannel? = null
        @Volatile var channelOpen = false
        @Volatile var ice: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW
        @Volatile var open = false
        @Volatile var dead = false
        @Volatile var remoteSet = false
        @Volatile var fingerprint: String? = null
        val pendingIce = ArrayList<IceCandidate>()
    }

    private val remotes = ConcurrentHashMap<String, Remote>()
    private val factory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    /** Several independent public STUN servers: one blocked or slow server must not stop the phones from learning their public address. */
    private val iceServers = listOf(
        PeerConnection.IceServer.builder(
            listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
                "stun:stun2.l.google.com:19302",
                "stun:stun3.l.google.com:19302",
                "stun:stun4.l.google.com:19302",
            )
        ).createIceServer(),
        PeerConnection.IceServer.builder(listOf("stun:stun.cloudflare.com:3478")).createIceServer(),
        PeerConnection.IceServer.builder(listOf("stun:global.stun.twilio.com:3478")).createIceServer(),
    )

    private fun config() = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        iceTransportsType = PeerConnection.IceTransportsType.ALL
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        // Keep gathering so a new network (Wi-Fi to mobile) produces new candidates without a full restart.
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        iceCandidatePoolSize = 1
    }

    private inner class Obs : PeerConnection.Observer {
        @Volatile var rr: Remote? = null

        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            val r = rr ?: return
            if (candidate == null || r.dead) return
            val json = JSONObject()
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("sdp", candidate.sdp)
            sendSignal(SignalingMessage.Relay("ice", selfId, r.id, json.toString()))
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            val r = rr ?: return
            if (r.dead || state == null) return
            r.ice = state
            recompute(r)
        }

        override fun onDataChannel(dc: DataChannel?) {
            val r = rr ?: return
            if (r.dead || dc == null) return
            attachChannel(r, dc)
        }
    }

    private fun recompute(r: Remote) {
        if (r.dead) return
        val now = r.channelOpen && r.ice != PeerConnection.IceConnectionState.DISCONNECTED &&
            r.ice != PeerConnection.IceConnectionState.FAILED && r.ice != PeerConnection.IceConnectionState.CLOSED
        if (now != r.open) {
            r.open = now
            onPeerConnected(r.id, now)
        }
    }

    private fun attachChannel(r: Remote, dc: DataChannel) {
        r.channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (r.dead || r.channel !== dc) return
                r.channelOpen = dc.state() == DataChannel.State.OPEN
                recompute(r)
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (r.dead || buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onText(r.id, String(bytes, Charsets.UTF_8))
            }
        })
        // The channel may already be open by the time the observer is registered.
        r.channelOpen = dc.state() == DataChannel.State.OPEN
        recompute(r)
    }

    private fun newRemote(peerId: String): Remote? {
        val obs = Obs()
        val pc = factory.createPeerConnection(config(), obs) ?: return null
        val r = Remote(peerId, pc)
        obs.rr = r
        remotes[peerId] = r
        return r
    }

    private fun dispose(r: Remote) {
        r.dead = true
        runCatching { r.channel?.unregisterObserver() }
        runCatching { r.channel?.close() }
        runCatching { r.channel?.dispose() }
        runCatching { r.pc.close() }
        runCatching { r.pc.dispose() }
    }

    private fun fingerprintOf(sdp: String): String? =
        sdp.lineSequence().firstOrNull { it.startsWith("a=fingerprint:") }?.trim()

    /** Place a call (we are the newcomer). If a connection already exists and is not open, look for a new path on it instead. */
    fun callPeer(peerId: String) {
        val existing = remotes[peerId]
        if (existing != null) {
            if (!existing.open) restartIce(peerId)
            return
        }
        val r = newRemote(peerId) ?: return
        val dc = r.pc.createDataChannel("walk", DataChannel.Init())
        if (dc != null) attachChannel(r, dc)
        offer(r, iceRestart = false)
    }

    private fun offer(r: Remote, iceRestart: Boolean) {
        val c = MediaConstraints()
        if (iceRestart) c.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        r.pc.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null || r.dead) return
                r.pc.setLocalDescription(object : SimpleSdp() {
                    override fun onSetSuccess() {
                        if (!r.dead) sendSignal(SignalingMessage.Relay("offer", selfId, r.id, sdp.description))
                    }
                }, sdp)
            }
        }, c)
    }

    /** Looks for a new network path on the same connection. Returns false when there is nothing to restart or an offer is already in flight. */
    fun restartIce(peerId: String): Boolean {
        val r = remotes[peerId] ?: return false
        if (r.dead) return false
        if (r.pc.signalingState() != PeerConnection.SignalingState.STABLE) return false
        offer(r, iceRestart = true)
        return true
    }

    /** Restart every connection whose path may be gone (a network change). */
    fun restartAll() {
        for (id in remotes.keys.toList()) restartIce(id)
    }

    /** Throw the connection away and call again from scratch. The other phone notices the new identity in the offer and does the same. */
    fun recreate(peerId: String) {
        remotes.remove(peerId)?.let { old ->
            val wasOpen = old.open
            dispose(old)
            if (wasOpen) onPeerConnected(peerId, false)
        }
        callPeer(peerId)
    }

    fun onRelay(m: SignalingMessage.Relay) {
        val from = m.from ?: return
        when (m.kind) {
            "offer" -> {
                var r = remotes[from]
                val fp = fingerprintOf(m.payload)
                if (r != null && r.fingerprint != null && fp != null && r.fingerprint != fp) {
                    // The other phone started over with a brand new connection: do the same.
                    remotes.remove(from)
                    val wasOpen = r.open
                    dispose(r)
                    if (wasOpen) onPeerConnected(from, false)
                    r = null
                }
                if (r != null && r.pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER && selfId < from) {
                    return // both offered at once: the smaller id keeps its offer
                }
                val remote = r ?: newRemote(from) ?: return
                remote.fingerprint = fp ?: remote.fingerprint
                remote.pc.setRemoteDescription(object : SimpleSdp() {
                    override fun onSetSuccess() {
                        if (remote.dead) return
                        remote.remoteSet = true
                        flushIce(remote)
                        remote.pc.createAnswer(object : SimpleSdp() {
                            override fun onCreateSuccess(sdp: SessionDescription?) {
                                if (sdp == null || remote.dead) return
                                remote.pc.setLocalDescription(object : SimpleSdp() {
                                    override fun onSetSuccess() {
                                        if (!remote.dead) sendSignal(SignalingMessage.Relay("answer", selfId, from, sdp.description))
                                    }
                                }, sdp)
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, m.payload))
            }
            "answer" -> {
                val r = remotes[from] ?: return
                if (r.pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
                r.fingerprint = fingerprintOf(m.payload) ?: r.fingerprint
                r.pc.setRemoteDescription(object : SimpleSdp() {
                    override fun onSetSuccess() {
                        if (r.dead) return
                        r.remoteSet = true
                        flushIce(r)
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, m.payload))
            }
            "ice" -> {
                val r = remotes[from] ?: return
                val c = runCatching {
                    val o = JSONObject(m.payload)
                    IceCandidate(o.optString("sdpMid"), o.optInt("sdpMLineIndex"), o.getString("sdp"))
                }.getOrNull() ?: return
                synchronized(r.pendingIce) {
                    if (r.remoteSet) runCatching { r.pc.addIceCandidate(c) } else r.pendingIce.add(c)
                }
            }
        }
    }

    private fun flushIce(r: Remote) {
        synchronized(r.pendingIce) {
            for (c in r.pendingIce) runCatching { r.pc.addIceCandidate(c) }
            r.pendingIce.clear()
        }
    }

    fun peerLeft(peerId: String) {
        remotes.remove(peerId)?.let { r ->
            val wasOpen = r.open
            dispose(r)
            if (wasOpen) onPeerConnected(peerId, false)
        }
    }

    fun hasConnection(peerId: String) = remotes.containsKey(peerId)
    fun isConnected(peerId: String) = remotes[peerId]?.open == true
    fun connectedPeers(): List<String> = remotes.values.filter { it.open }.map { it.id }
    fun anyOpen(): Boolean = remotes.values.any { it.open }
    fun connectionIds(): List<String> = remotes.keys.toList()
    fun notOpenPeers(): List<String> = remotes.values.filter { !it.open }.map { it.id }

    /** Sends to every open channel. Returns how many peers received it. */
    fun broadcast(text: String): Int {
        var n = 0
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (r in remotes.values) {
            val dc = r.channel ?: continue
            if (!r.open) continue
            // A backed-up channel is a dying one: do not queue stale positions behind it.
            if (runCatching { dc.bufferedAmount() }.getOrDefault(0L) > 64_000L) continue
            if (runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) }.getOrDefault(false)) n++
        }
        return n
    }

    fun close() {
        val all = remotes.values.toList()
        remotes.clear()
        all.forEach { dispose(it) }
        // Disposing the factory waits for the WebRTC threads; keep it off the caller's (main) thread.
        Thread { runCatching { factory.dispose() } }.start()
    }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
