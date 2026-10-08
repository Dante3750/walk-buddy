package com.walkbuddy.rtc

import com.walkbuddy.domain.SignalingCodec
import com.walkbuddy.domain.SignalingMessage
import com.walkbuddy.domain.ServerConfig
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class SignalingState { Idle, Connecting, Connected, Failed }

/**
 * Thin OkHttp WebSocket wrapper for the Walk Buddy server (signaling and the fallback relay). Callbacks arrive on OkHttp threads;
 * the owner is expected to hop to its own thread. Each socket has a generation number, so late callbacks of a socket that was
 * replaced (for example after a network change) can never be mistaken for the state of the new one.
 */
class SignalingClient(
    private val handleMessage: (SignalingMessage) -> Unit,
    private val handleState: (SignalingState, String?) -> Unit,
) {
    private val client = Http.webSocket
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0

    /** Always connects to the built-in server ([ServerConfig.URL]). Any socket still open from before is dropped first. */
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
                SignalingCodec.decode(text)?.let(handleMessage)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) handleState(SignalingState.Failed, "Disconnected from the server")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) handleState(SignalingState.Failed, "Could not reach the server")
            }
        })
    }

    fun send(m: SignalingMessage): Boolean = socket?.send(SignalingCodec.encode(m)) ?: false

    @Synchronized
    fun close() {
        generation++ // anything the old socket still reports is ignored
        socket?.close(1000, "bye")
        socket = null
        handleState(SignalingState.Idle, null)
    }
}
