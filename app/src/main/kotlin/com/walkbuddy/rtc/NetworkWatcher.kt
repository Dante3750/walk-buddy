package com.walkbuddy.rtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Is there a network?" from the system's own callback instead of retrying blindly. Registered only while a partner or group session
 * is open ([start] / [stop]); a registered default-network callback costs nothing until the connection actually changes.
 */
class NetworkWatcher(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val _online = MutableStateFlow(onlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits when the phone's default network switched to another one (Wi-Fi to mobile data, a new Wi-Fi, back from offline). Not for the first one seen. */
    val changes: SharedFlow<Unit> = _changes
    @Volatile private var current: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun onlineNow(): Boolean {
        val m = cm ?: return true // cannot tell: behave as before and just try
        val caps = runCatching { m.getNetworkCapabilities(m.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Synchronized
    fun start() {
        val m = cm ?: return
        if (callback != null) return
        _online.value = onlineNow()
        current = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
                val before = current
                current = network
                if (before != network) _changes.tryEmit(Unit)
            }
            override fun onLost(network: Network) {
                if (current == network) current = null
                _online.value = onlineNow()
            }
        }
        callback = cb
        runCatching { m.registerDefaultNetworkCallback(cb) }.onFailure { callback = null }
    }

    @Synchronized
    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }
}
