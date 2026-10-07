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
import android.os.Looper
import com.walkbuddy.domain.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Hardware step counter (steps since boot). No Google Play Services involved. */
class StepSource(context: Context) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val available: Boolean get() = sensor != null

    /** Continuous readings while collected. Each value is the cumulative counter. */
    fun readings(): Flow<Long> = callbackFlow {
        val s = sensor
        if (s == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) trySend(event.values[0].toLong())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { manager.unregisterListener(listener) }
    }

    /** One reading (the sensor reports its current value right after registration), or null on timeout / no sensor. */
    suspend fun readOnce(timeoutMs: Long = 3_000): Long? {
        val s = sensor ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Long> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive && event.values.isNotEmpty()) cont.resume(event.values[0].toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        }
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
