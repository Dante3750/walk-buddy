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

/** Result of the self-check "can I reach the server" probe: a plain GET of /health through the app's existing client. */
data class HealthProbe(val code: Int?, val ms: Long?, val error: String?)

suspend fun Http.probeHealth(): HealthProbe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val t0 = System.currentTimeMillis()
    try {
        val client = webSocket.newBuilder().pingInterval(0, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).build()
        val req = okhttp3.Request.Builder().url(ServerConfig.HEALTH_URL).get().build()
        client.newCall(req).execute().use { HealthProbe(it.code, System.currentTimeMillis() - t0, null) }
    } catch (e: Exception) {
        HealthProbe(null, null, e.javaClass.simpleName)
    }
}

/** A short, plain GET for the opt-in weather suggestion. No cookies, no headers beyond OkHttp's own, 15 s limit. Returns null on any problem. */
suspend fun Http.getText(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val client = webSocket.newBuilder().pingInterval(0, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
        client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) {
        null
    }
}
