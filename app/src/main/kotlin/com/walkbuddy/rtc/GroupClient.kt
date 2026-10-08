package com.walkbuddy.rtc

import com.walkbuddy.domain.GroupClientMessage
import com.walkbuddy.domain.GroupCodec
import com.walkbuddy.domain.GroupServerMessage
import com.walkbuddy.domain.ServerConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * OkHttp WebSocket to the Walk Buddy server for an open group. Unlike partner walks (phone to phone), a group relays its small
 * location/step updates through the server. Callbacks arrive on OkHttp threads; the owner hops to its own thread.
 */
class GroupClient(
    private val handleMessage: (GroupServerMessage) -> Unit,
    private val handleState: (SignalingState, String?) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(ServerConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(ServerConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile private var closedByUs = false

    /** Always connects to the built-in server ([ServerConfig.URL]). */
    fun connect(url: String = ServerConfig.URL) {
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
                GroupCodec.decode(text)?.let(handleMessage)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUs) handleState(SignalingState.Failed, "Disconnected from the server")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closedByUs) handleState(SignalingState.Failed, "Could not reach the server")
            }
        })
    }

    fun send(m: GroupClientMessage): Boolean = socket?.send(GroupCodec.encode(m)) ?: false

    fun close() {
        closedByUs = true
        socket?.close(1000, "bye")
        socket = null
        handleState(SignalingState.Idle, null)
    }
}
