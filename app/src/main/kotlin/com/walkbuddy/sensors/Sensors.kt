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
import com.walkbuddy.domain.LocationPlan
import com.walkbuddy.domain.SamplingPolicy
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
        // Batched events arrive up to the longest report latency late (10 min in Battery Saver), so the tolerance must exceed that.
        return if (kotlin.math.abs(w - now) > SamplingPolicy.MAX_SENSOR_LATENCY_MS + 5 * 60_000L) now else minOf(w, now)
    }

    /**
     * Continuous events while collected. [latencyMs] says how long the hardware may hold events before waking the app: 0 delivers
     * immediately (app on screen), larger values batch them so the CPU sleeps (the counter is cumulative and kept by the sensor hub,
     * so nothing is lost, delivery is only later). null means "do not listen right now" (the accelerometer fallback in the background).
     * The listener is re-registered whenever the value changes. No wake lock is ever held.
     */
    fun events(latencyMs: Flow<Long?>): Flow<StepEvent> = callbackFlow {
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
            latencyMs.distinctUntilChanged().collect { latency ->
                manager.unregisterListener(listener)
                if (latency == null) return@collect
                val ok = when (k) {
                    // Accelerometer must stream; batching it would only delay the detector. The policy only allows it with the screen on or during a walk.
                    StepSensorKind.Accelerometer -> manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                    else -> manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, (latency * 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
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
                // An on-change sensor reports its current value right after registration, so the slowest rate is as fast as any.
                val ok = manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
                lastRegisterOk = ok
                if (!ok) cont.resume(StepEvent(StepSensorKind.Counter, -1, 0, 0)) // refused: surfaced as null below
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        }?.takeIf { it.value >= 0 }
    }
}

/**
 * Plain platform LocationManager (GPS and network providers). No Play Services. Caller must hold the location permission.
 * Updates exist only while [fixes] is collected, which the sessions do only during an active walk; there is no background location.
 */
class LocationSource(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Either the precise or the approximate location permission. Approximate alone still gives network based positions. */
    fun hasPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun hasPrecisePermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** True when the GPS or the network provider is switched on in the phone's settings. */
    fun anyProviderOn(): Boolean = gpsEnabled() || runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)

    /**
     * Fixes at the rate [plans] currently asks for (re-requested whenever the plan changes). GPS carries the walk; the network
     * provider is a slower backup (4x the interval, at least 15 s apart) for the first seconds and for tunnels, not a second GPS.
     *
     * Both providers are always requested: a provider that is switched off simply stays quiet and starts delivering the moment it is
     * switched on, so turning Location on during a walk works without restarting it. A provider the permission does not cover
     * (GPS with only approximate location) is skipped. A recent last known position is delivered first so the map is not blank.
     */
    @SuppressLint("MissingPermission")
    fun fixes(plans: Flow<LocationPlan>): Flow<Fix> = callbackFlow {
        fun toFix(location: Location) = Fix(
            tMs = location.time,
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
        )
        var current: LocationPlan? = null
        lateinit var listener: LocationListener

        fun request(plan: LocationPlan) {
            runCatching { manager.removeUpdates(listener) }
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!runCatching { manager.allProviders.contains(p) }.getOrDefault(false)) continue
                val network = p == LocationManager.NETWORK_PROVIDER
                val every = if (network) maxOf(plan.intervalMs * 4, 15_000L) else plan.intervalMs
                val distance = if (network) maxOf(plan.minDistanceM * 4, 25f) else plan.minDistanceM
                // SecurityException (no permission for that provider) and IllegalArgumentException (no such provider) are expected here.
                runCatching { manager.requestLocationUpdates(p, every, distance, listener, Looper.getMainLooper()) }
            }
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(toFix(location)) }

            override fun onProviderEnabled(provider: String) { current?.let { request(it) } }

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        // Something to show straight away if the phone knows where it was a moment ago.
        lastKnown()?.let { if (System.currentTimeMillis() - it.tMs in 0..120_000L) trySend(it) }
        val job = launch {
            plans.distinctUntilChanged().collect { plan ->
                current = plan
                request(plan)
            }
        }
        awaitClose { job.cancel(); runCatching { manager.removeUpdates(listener) } }
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
