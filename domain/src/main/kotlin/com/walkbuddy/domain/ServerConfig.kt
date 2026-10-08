package com.walkbuddy.domain

/**
 * The one and only Walk Buddy server (partner signaling and open groups). There is no setting for it and an invite can never
 * point the app elsewhere. To self-host, change [URL] (and the matching host in `docs`/README) and rebuild.
 */
object ServerConfig {
    const val URL = "wss://walk-buddy-server-sxpz.onrender.com"
    const val HEALTH_URL = "https://walk-buddy-server-sxpz.onrender.com/health"

    /** Host part of [URL]. */
    val HOST: String = URL.removePrefix("wss://")

    /** The https page base used for shareable web links (`/g/CODE`). */
    val WEB_BASE: String = "https://$HOST"

    /** The free host sleeps when idle; the first connection can take 30-50 s. */
    const val CONNECT_TIMEOUT_SEC = 75L
    const val WAKING_NOTE = "Waking up the server, this can take up to a minute..."
    const val RETRY_NOTE = "Still waking the server. Trying again..."

    /** True when [candidate] names the built-in server (any other host in an invite is ignored). */
    fun isBuiltIn(candidate: String?): Boolean {
        val c = candidate?.trim()?.trimEnd('/') ?: return false
        return c.equals(URL, ignoreCase = true)
    }
}
