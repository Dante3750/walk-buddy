package com.walkbuddy.health

import android.content.Context
import com.walkbuddy.BuildConfig

/**
 * Optional Health Connect integration (read steps, write an exercise session). Off by default.
 * Walk Buddy works fully without it; the interface keeps the rest of the app free of the dependency.
 */
interface HealthBridge {
    /** True only when this build includes Health Connect support AND the platform provider is installed. */
    suspend fun isAvailable(): Boolean

    suspend fun hasPermissions(): Boolean

    /** Permission strings for the system permission contract (empty when unsupported). */
    fun permissions(): Set<String>

    suspend fun readTodaySteps(): Long?

    suspend fun writeWalk(startMs: Long, endMs: Long, steps: Long, distanceM: Double): Boolean
}

object NoHealthBridge : HealthBridge {
    override suspend fun isAvailable() = false
    override suspend fun hasPermissions() = false
    override fun permissions(): Set<String> = emptySet()
    override suspend fun readTodaySteps(): Long? = null
    override suspend fun writeWalk(startMs: Long, endMs: Long, steps: Long, distanceM: Double) = false
}

object HealthBridges {
    /** Whether this build was compiled with Health Connect (-PhealthConnect=true). */
    val compiledIn: Boolean get() = BuildConfig.HEALTH_CONNECT

    /** Loads the real implementation by name so the default build never references the Health Connect library. */
    fun create(context: Context): HealthBridge {
        if (!BuildConfig.HEALTH_CONNECT) return NoHealthBridge
        return try {
            val cls = Class.forName("com.walkbuddy.health.HealthConnectBridge")
            cls.getConstructor(Context::class.java).newInstance(context.applicationContext) as HealthBridge
        } catch (e: Throwable) {
            NoHealthBridge
        }
    }
}
