package com.walkbuddy.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.walkbuddy.domain.AccelStepDetector
import com.walkbuddy.domain.Fix
import com.walkbuddy.domain.StepSensorKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One thing the step sensor said. For [StepSensorKind.Counter], [value] is the cumulative hardware counter (steps since boot).
 * For the fallbacks it is the number of steps to add. [wallMs] is when the steps happened (from the event timestamp, so
 * batched events still land in the right minute and the right day).
 */
data class StepEvent(val kind: StepSensorKind, val value: Long, val wallMs: Long, val bootMs: Long)

/**
 * The phone's step sensors, no Google Play Services. Prefers the hardware step counter (counts even while the app is dead),
 * then the step detector, then the accelerometer as a last resort. Listeners are batched (maxReportLatency) unless the app is on screen.
 */
class StepSource(context: Context) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val counter: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val detector: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accel: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val kind: StepSensorKind? = when {
        counter != null -> StepSensorKind.Counter
        detector != null -> StepSensorKind.Detector
        accel != null -> StepSensorKind.Accelerometer
        else -> null
    }

    val available: Boolean get() = kind != null

    /** Android 10+ keeps the step sensors silent until the physical-activity permission is granted. The accelerometer needs none. */
    val needsPermission: Boolean get() = Build.VERSION.SDK_INT >= 29 && (kind == StepSensorKind.Counter || kind == StepSensorKind.Detector)

    /** Result of the last registerListener call: false means the system refused (usually the missing permission). Null before the first try. */
    @Volatile var lastRegisterOk: Boolean? = null
        private set

    private fun bootMsNow(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Converts a sensor timestamp (elapsed realtime, ns) to wall clock; distrusts devices whose timestamps are on another base. */
    private fun wallOf(eventNs: Long, boot: Long, now: Long): Long {
        val w = boot + eventNs / 1_000_000L
        return if (kotlin.math.abs(w - now) > 5 * 60_000L) now else minOf(w, now)
    }

    /**
     * Continuous events while collected. [interactive] true (app on screen) delivers immediately; false batches for
     * [BATCH_LATENCY_US] so the CPU is woken rarely. The hardware keeps counting either way.
     */
    fun events(interactive: Flow<Boolean>): Flow<StepEvent> = callbackFlow {
        val k = kind
        val sensor = when (k) {
            StepSensorKind.Counter -> counter
            StepSensorKind.Detector -> detector
            StepSensorKind.Accelerometer -> accel
            null -> null
        }
        if (k == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val detect = AccelStepDetector()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.currentTimeMillis()
                val boot = bootMsNow()
                when (k) {
                    StepSensorKind.Counter ->
                        if (event.values.isNotEmpty()) trySend(StepEvent(k, event.values[0].toLong(), wallOf(event.timestamp, boot, now), boot))
                    StepSensorKind.Detector -> trySend(StepEvent(k, 1, wallOf(event.timestamp, boot, now), boot))
                    StepSensorKind.Accelerometer -> {
                        if (event.values.size < 3) return
                        val n = detect.onSample(now, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                        if (n > 0) trySend(StepEvent(k, n.toLong(), now, boot))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val job = launch {
            interactive.distinctUntilChanged().collect { live ->
                manager.unregisterListener(listener)
                val ok = when (k) {
                    // Accelerometer must stream; batching it would only delay the detector.
                    StepSensorKind.Accelerometer -> manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                    else -> manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, if (live) 0 else BATCH_LATENCY_US)
                }
                lastRegisterOk = ok
            }
        }
        awaitClose { job.cancel(); manager.unregisterListener(listener) }
    }

    /**
     * One reading of the hardware counter (an on-change sensor reports its current value right after registration),
     * or null on timeout, no counter, or no permission. Only meaningful for [StepSensorKind.Counter].
     */
    suspend fun readOnce(timeoutMs: Long = 3_000): StepEvent? {
        val s = counter ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<StepEvent> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive && event.values.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            val boot = bootMsNow()
                            cont.resume(StepEvent(StepSensorKind.Counter, event.values[0].toLong(), now, boot))
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                val ok = manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST)
                lastRegisterOk = ok
                if (!ok) cont.resume(StepEvent(StepSensorKind.Counter, -1, 0, 0)) // refused: surfaced as null below
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        }?.takeIf { it.value >= 0 }
    }

    companion object {
        /** How long the hardware may hold events before waking the app. A minute keeps the CPU asleep and still feels current. */
        const val BATCH_LATENCY_US = 60_000_000
    }
}

/** Plain platform LocationManager (GPS and network providers). No Play Services. Caller must hold the location permission. */
class LocationSource(context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun fixes(): Flow<Fix> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(
                    Fix(
                        tMs = location.time,
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                        speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                    )
                )
            }

            // Older Android versions (before 11) still call these; implementing them avoids AbstractMethodError.
            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) {
            runCatching { manager.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) }
        }
        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    /** Most recent known position from any provider, or null. Requires the location permission. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? {
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return Fix(best.time, best.latitude, best.longitude, if (best.hasAccuracy()) best.accuracy.toDouble() else null, null)
    }

    fun gpsEnabled(): Boolean = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
}
