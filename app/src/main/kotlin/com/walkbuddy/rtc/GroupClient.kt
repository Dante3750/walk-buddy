package com.walkbuddy.rtc

import com.walkbuddy.domain.GroupClientMessage
import com.walkbuddy.domain.GroupCodec
import com.walkbuddy.domain.GroupServerMessage
import com.walkbuddy.domain.ServerConfig
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * OkHttp WebSocket to the Walk Buddy server for an open group. Unlike partner walks (phone to phone first), a group relays its small
 * location/step updates through the server. Callbacks arrive on OkHttp threads; the owner hops to its own thread. Each socket has a
 * generation number so a late callback of a replaced socket is ignored.
 */
class GroupClient(
    private val handleMessage: (GroupServerMessage) -> Unit,
    private val handleState: (SignalingState, String?) -> Unit,
) {
    private val client = Http.webSocket
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0

    /** Always connects to the built-in server ([ServerConfig.URL]). A socket still open from before is dropped first. */
    @Synchronized
    fun connect(url: String = ServerConfig.URL) {
        val old = socket
        socket = null
        val gen = ++generation
        runCatching { old?.cancel() }
        handleState(SignalingState.Connecting, null)
        val request = try {
            Request.Builder().url(url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")).build()
        } catch (e: IllegalArgumentException) {
            handleState(SignalingState.Failed, "That server address is not valid")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen == generation) handleState(SignalingState.Connected, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                GroupCodec.decode(text)?.let(handleMessage)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) handleState(SignalingState.Failed, "Disconnected from the server")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) handleState(SignalingState.Failed, "Could not reach the server")
            }
        })
    }

    fun send(m: GroupClientMessage): Boolean = socket?.send(GroupCodec.encode(m)) ?: false

    @Synchronized
    fun close() {
        generation++
        socket?.close(1000, "bye")
        socket = null
        handleState(SignalingState.Idle, null)
    }
}
