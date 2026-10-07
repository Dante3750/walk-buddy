package com.walkbuddy.rtc

import com.walkbuddy.domain.SignalingCodec
import com.walkbuddy.domain.SignalingMessage
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class SignalingState { Idle, Connecting, Connected, Failed }

/**
 * Thin OkHttp WebSocket wrapper for the tiny signaling server. Callbacks arrive on OkHttp threads;
 * the owner is expected to hop to its own thread.
 */
class SignalingClient(
    private val handleMessage: (SignalingMessage) -> Unit,
    private val handleState: (SignalingState, String?) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile private var closedByUs = false

    fun connect(url: String) {
        closedByUs = false
        handleState(SignalingState.Connecting, null)
        val request = try {
            Request.Builder().url(url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")).build()
        } catch (e: IllegalArgumentException) {
            handleState(SignalingState.Failed, "That server address is not valid")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handleState(SignalingState.Connected, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                SignalingCodec.decode(text)?.let(handleMessage)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUs) handleState(SignalingState.Failed, "Disconnected from the signaling server")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closedByUs) handleState(SignalingState.Failed, "Could not reach the signaling server")
            }
        })
    }

    fun send(m: SignalingMessage): Boolean = socket?.send(SignalingCodec.encode(m)) ?: false

    fun close() {
        closedByUs = true
        socket?.close(1000, "bye")
        socket = null
        handleState(SignalingState.Idle, null)
    }

    companion object {
        /** One-shot reachability check for Settings > Test connection. Returns null on success or a short message. */
        fun test(url: String, timeoutMs: Long = 6000, done: (String?) -> Unit) {
            val client = OkHttpClient.Builder().connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val req = try {
                Request.Builder().url(url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")).build()
            } catch (e: IllegalArgumentException) {
                done("That server address is not valid")
                return
            }
            var finished = false
            fun finish(msg: String?) { synchronized(this) { if (finished) return; finished = true }; done(msg) }
            client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1000, "test")
                    finish(null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish("Could not connect (${t.javaClass.simpleName})")
                }
            })
        }
    }
}
