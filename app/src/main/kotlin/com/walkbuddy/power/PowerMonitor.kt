package com.walkbuddy.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.walkbuddy.data.SettingsStore
import com.walkbuddy.domain.LocationPlan
import com.walkbuddy.domain.PowerState
import com.walkbuddy.domain.SamplingPlan
import com.walkbuddy.domain.SamplingPolicy
import com.walkbuddy.domain.StepSensorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reports the facts [SamplingPolicy] needs (screen visible, walk, movement, group size, Android Battery Saver, charger, the user's
 * own saver switch) and nothing else. It only listens to three rare system broadcasts (Battery Saver changed, charger plugged and
 * unplugged); it deliberately does NOT listen to ACTION_BATTERY_CHANGED, which fires on every percent and would wake the process.
 * The receivers are registered in code, so they cost nothing while the process is not alive.
 */
class PowerMonitor(context: Context, settings: SettingsStore) {
    private val app = context.applicationContext
    private val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PowerState(systemBatterySaver = saverNow(), charging = chargingNow()))
    val state: StateFlow<PowerState> = _state.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> _state.update { it.copy(systemBatterySaver = saverNow()) }
                    Intent.ACTION_POWER_CONNECTED -> _state.update { it.copy(charging = true) }
                    Intent.ACTION_POWER_DISCONNECTED -> _state.update { it.copy(charging = false) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        scope.launch {
            settings.settings.map { it.batterySaver }.distinctUntilChanged().collect { on -> _state.update { it.copy(userBatterySaver = on) } }
        }
    }

    private fun saverNow(): Boolean = pm?.isPowerSaveMode == true

    private fun chargingNow(): Boolean {
        val i = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    fun setScreenVisible(on: Boolean) = _state.update { it.copy(screenVisible = on) }

    /** A tracked walk starts or ends. Ending also forgets movement and group size so the next walk starts from a clean slate. */
    fun setWalk(active: Boolean, groupSize: Int = 1) =
        _state.update { if (active) it.copy(walkActive = true, groupSize = groupSize.coerceAtLeast(1)) else it.copy(walkActive = false, moving = false, groupSize = 1) }

    fun setMoving(moving: Boolean) = _state.update { if (it.moving == moving) it else it.copy(moving = moving) }

    fun setGroupSize(n: Int) = _state.update { val g = n.coerceAtLeast(1); if (it.groupSize == g) it else it.copy(groupSize = g) }

    /** The current decisions (for loops that re-read them every round). */
    fun plan(kind: StepSensorKind? = null): SamplingPlan = SamplingPolicy.plan(_state.value, kind)

    /** Location requests to make, changing as the state changes. Only emits while a walk is active. */
    fun locationPlans(): Flow<LocationPlan> = _state.map { SamplingPolicy.plan(it, null).location }.filterNotNull().distinctUntilChanged()

    /** Sensor latency to register with, for the step feed. */
    fun sensorLatency(kind: StepSensorKind?): Flow<Long?> = _state.map { SamplingPolicy.plan(it, kind).sensorLatencyMs }.distinctUntilChanged()
}
