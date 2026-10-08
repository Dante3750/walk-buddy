package com.walkbuddy.session

import com.walkbuddy.domain.Backoff
import com.walkbuddy.rtc.NetworkWatcher
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reconnects a dropped WebSocket without ever spinning: exponential delays with jitter ([Backoff]), at most [maxAttempts] tries,
 * and while the phone has no network it simply waits for the system's "network available" callback instead of burning attempts
 * (and radio wake-ups) against a dead link. One pending retry at a time.
 */
class Reconnector(
    private val scope: CoroutineScope,
    private val network: NetworkWatcher,
    private val maxAttempts: Int,
    private val stillNeeded: () -> Boolean,
    private val reconnect: () -> Unit,
) {
    private var job: Job? = null

    /** Attempts used since the last successful connection. */
    var attempts: Int = 0
        private set

    val exhausted: Boolean get() = attempts >= maxAttempts

    fun onConnected() { attempts = 0; job?.cancel(); job = null }

    /** The connection failed. Schedules the next try unless the attempts are used up. */
    fun onFailed() {
        if (exhausted || !stillNeeded()) return
        val offline = !network.online.value
        if (!offline) attempts++
        job?.cancel()
        job = scope.launch {
            if (offline) {
                network.online.first { it }
                delay(300L + Random.nextLong(700)) // let the link settle, and spread out phones that come back together
            } else {
                delay(Backoff.delayMs(attempts))
            }
            if (stillNeeded()) reconnect()
        }
    }

    /** The network came back after the attempts ran out: start over once. */
    fun onNetworkBack() {
        if (exhausted && stillNeeded()) {
            attempts = 0
            onFailed()
        }
    }

    fun cancel() { job?.cancel(); job = null; attempts = 0 }
}
