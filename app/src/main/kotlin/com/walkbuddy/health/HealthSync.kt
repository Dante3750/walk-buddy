package com.walkbuddy.health

import com.walkbuddy.data.Settings
import com.walkbuddy.data.SettingsStore
import com.walkbuddy.diag.AppLog
import com.walkbuddy.domain.ReconciledSteps
import com.walkbuddy.domain.StepMerge
import com.walkbuddy.domain.WalkExport
import com.walkbuddy.domain.WrittenWalk

/**
 * Health Connect, opt-in and off by default. Reads happen only when Today opens; a finished walk is written once (never twice, never
 * overlapping one already written). Steps from Health Connect are NOT added to Walk Buddy's own count: that would count the same steps
 * twice, because Health Connect often already holds the phone's steps. They are only compared, and shown as a note when clearly higher.
 */
object HealthSync {
    suspend fun writeWalkIfNew(bridge: HealthBridge, store: SettingsStore, s: Settings, startMs: Long, endMs: Long, steps: Long, distanceM: Double) {
        if (!s.healthConnectOn || !s.hcWrite) return
        val written = WalkExport.decode(s.hcWritten)
        if (!WalkExport.shouldWrite(startMs, endMs, written)) return
        if (bridge.writeWalk(startMs, endMs, steps, distanceM)) {
            store.setHcWritten(WalkExport.encode(WalkExport.remember(written, WrittenWalk(startMs, endMs))))
            AppLog.i("health", "walk written")
        } else {
            AppLog.w("health", "walk not written")
        }
    }

    /** Compares today's own steps with Health Connect's. Null when off, unavailable, not permitted or not clearly different. */
    suspend fun compare(bridge: HealthBridge, s: Settings, ownSteps: Int): ReconciledSteps? {
        if (!s.healthConnectOn || !bridge.isAvailable() || !bridge.hasPermissions()) return null
        val hc = runCatching { bridge.readTodaySteps() }.getOrNull()
        val r = StepMerge.reconcile(ownSteps, hc)
        return if (r.extraFromHealthConnect > 0) r else null
    }
}
