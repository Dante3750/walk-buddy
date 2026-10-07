package com.walkbuddy.rtc

import android.content.Context
import com.walkbuddy.domain.SignalingMessage
import java.nio.ByteBuffer
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
 * Mesh of WebRTC DATA CHANNELS (no audio, no video tracks) between up to 4 phones, with STUN only.
 * Signaling goes through [SignalingClient]; once a channel is open, all walk data flows phone to phone.
 *
 * NOTE: written against the org.webrtc API as published by io.getstream:stream-webrtc-android. Unverified until CI compiles it.
 * Callbacks may arrive on WebRTC threads; the owner hops to its own thread.
 */
class PeerLink(
    context: Context,
    private val selfId: String,
    private val sendSignal: (SignalingMessage) -> Unit,
    private val onText: (fromId: String, text: String) -> Unit,
    private val onPeerConnected: (peerId: String, connected: Boolean) -> Unit,
) {
    private class Remote(val id: String, val pc: PeerConnection) {
        var channel: DataChannel? = null
        var open = false
        val pendingIce = ArrayList<IceCandidate>()
        var remoteSet = false
    }

    private val remotes = HashMap<String, Remote>()
    private val factory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    private abstract inner class PcObserver(val peerId: String) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            val json = JSONObject()
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("sdp", candidate.sdp)
            sendSignal(SignalingMessage.Relay("ice", selfId, peerId, json.toString()))
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED ||
                state == PeerConnection.IceConnectionState.DISCONNECTED
            ) {
                remotes[peerId]?.let { if (it.open) { it.open = false; onPeerConnected(peerId, false) } }
            }
        }
    }

    private fun newRemote(peerId: String): Remote? {
        remotes[peerId]?.let { return it }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, observer(peerId)) ?: return null
        return Remote(peerId, pc).also { remotes[peerId] = it }
    }

    private fun attachChannel(r: Remote, dc: DataChannel) {
        r.channel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val isOpen = dc.state() == DataChannel.State.OPEN
                if (isOpen != r.open) {
                    r.open = isOpen
                    onPeerConnected(r.id, isOpen)
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onText(r.id, String(bytes, Charsets.UTF_8))
            }
        })
    }

    private fun observer(peerId: String): PcObserver = object : PcObserver(peerId) {
        override fun onDataChannel(dc: DataChannel?) {
            val r = remotes[peerId] ?: return
            if (dc != null) attachChannel(r, dc)
        }
    }

    /** We are the newcomer: create the data channel and send an offer. */
    fun callPeer(peerId: String) {
        if (remotes.containsKey(peerId)) return
        val r = newRemote(peerId) ?: return
        val dc = r.pc.createDataChannel("walk", DataChannel.Init())
        if (dc != null) attachChannel(r, dc)
        r.pc.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                r.pc.setLocalDescription(SimpleSdp(), sdp)
                sendSignal(SignalingMessage.Relay("offer", selfId, peerId, sdp.description))
            }
        }, MediaConstraints())
    }

    fun onRelay(m: SignalingMessage.Relay) {
        val from = m.from ?: return
        when (m.kind) {
            "offer" -> {
                val r = newRemote(from) ?: return
                r.pc.setRemoteDescription(object : SimpleSdp() {
                    override fun onSetSuccess() {
                        r.remoteSet = true
                        flushIce(r)
                        r.pc.createAnswer(object : SimpleSdp() {
                            override fun onCreateSuccess(sdp: SessionDescription?) {
                                if (sdp == null) return
                                r.pc.setLocalDescription(SimpleSdp(), sdp)
                                sendSignal(SignalingMessage.Relay("answer", selfId, from, sdp.description))
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, m.payload))
            }
            "answer" -> {
                val r = remotes[from] ?: return
                r.pc.setRemoteDescription(object : SimpleSdp() {
                    override fun onSetSuccess() {
                        r.remoteSet = true
                        flushIce(r)
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, m.payload))
            }
            "ice" -> {
                val r = newRemote(from) ?: return
                val c = runCatching {
                    val o = JSONObject(m.payload)
                    IceCandidate(o.optString("sdpMid"), o.optInt("sdpMLineIndex"), o.getString("sdp"))
                }.getOrNull() ?: return
                if (r.remoteSet) r.pc.addIceCandidate(c) else r.pendingIce.add(c)
            }
        }
    }

    private fun flushIce(r: Remote) {
        for (c in r.pendingIce) r.pc.addIceCandidate(c)
        r.pendingIce.clear()
    }

    fun peerLeft(peerId: String) {
        remotes.remove(peerId)?.let { r ->
            runCatching { r.channel?.close() }
            runCatching { r.pc.close() }
            if (r.open) onPeerConnected(peerId, false)
        }
    }

    fun isConnected(peerId: String) = remotes[peerId]?.open == true
    fun connectedPeers(): List<String> = remotes.values.filter { it.open }.map { it.id }

    /** Sends to every open channel. Returns how many peers received it. */
    fun broadcast(text: String): Int {
        var n = 0
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (r in remotes.values) {
            val dc = r.channel ?: continue
            if (!r.open) continue
            if (dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))) n++
        }
        return n
    }

    fun close() {
        for (id in remotes.keys.toList()) peerLeft(id)
        runCatching { factory.dispose() }
    }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
