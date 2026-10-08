package com.walkbuddy.rtc

import com.walkbuddy.domain.ServerConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * One OkHttp client for the whole app. Every WebSocket used to build its own client (own thread pool and connection pool), and a
 * reconnect built another; sharing the dispatcher and pool means fewer threads and no leftover idle clients.
 */
object Http {
    private val base: OkHttpClient by lazy { OkHttpClient() }

    /**
     * The relay / signaling socket. A ping every 40 s keeps the connection alive through the host's idle timeout (about a minute or more)
     * with less radio use than the 25 s it used before; a dead link is noticed within a ping plus the read timeout.
     */
    val webSocket: OkHttpClient by lazy {
        base.newBuilder()
            .pingInterval(40, TimeUnit.SECONDS)
            .connectTimeout(ServerConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(ServerConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }
}
