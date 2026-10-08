package com.walkbuddy.steps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.domain.StepSensorKind
import com.walkbuddy.notify.PeriodicSampler
import java.util.concurrent.TimeUnit

/** Entry points that keep always-on step counting alive. Every call is idempotent and safe from a receiver or the background. */
object StepTracking {
    private const val WORK = "walkbuddy-step-safety-net-v2"
    private const val LEGACY_WORK = "walkbuddy-step-safety-net"
    private const val PREFS = "wb_steps"
    private const val KEY_ASKED = "asked_activity"
    private const val KEY_TIP_DISMISSED = "battery_tip_dismissed"

    /** ACTIVITY_RECOGNITION is a runtime permission from Android 10; before that the sensors work without asking. */
    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Whether the foreground service may run now. On Android 14+ a health service without the permission would crash on start. */
    fun canRunService(context: Context): Boolean {
        val c = (context.applicationContext as? WalkBuddyApplication)?.container ?: return false
        // Without a hardware counter or detector the only fallback is the accelerometer, which works only while the CPU is awake,
        // so a foreground service would just burn battery for nothing: the app (screen on) and walks listen by themselves.
        return c.steps.available && c.steps.kind != StepSensorKind.Accelerometer && permissionGranted(context)
    }

    /**
     * Makes sure steps keep being counted: the WorkManager safety net is scheduled (it survives the app being killed) and the
     * foreground service is started if Android allows it from here. Android 12+ refuses a background service start in many
     * situations (for example right after an app update); that is fine, the safety net still reads the hardware counter, and the next
     * time the app opens the service starts. Cheap to call often: the scheduling happens once per process and a running service is left alone.
     */
    fun ensureRunning(context: Context) {
        val app = context.applicationContext
        scheduleSafetyNet(app)
        if (!StepService.running) startService(app)
    }

    @Volatile private var scheduled = false

    /** One periodic job (about 30 min, battery not low) replaces the old WorkManager job (15 min) plus the 15 min alarm. */
    fun scheduleSafetyNet(app: Context) {
        runCatching { PeriodicSampler.cancelLegacyAlarm(app) }
        if (scheduled) return
        runCatching {
            val wm = WorkManager.getInstance(app)
            wm.cancelUniqueWork(LEGACY_WORK)
            val req = PeriodicWorkRequestBuilder<StepSampleWorker>(30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
            scheduled = true
        }
    }

    fun startService(context: Context): Boolean {
        val app = context.applicationContext
        if (!canRunService(app)) return false
        return try {
            ContextCompat.startForegroundService(app, Intent(app, StepService::class.java))
            true
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+), SecurityException, IllegalStateException: stay on the safety net.
            false
        }
    }

    /** Call right after the user grants the permission: restarts a listener that Android silently ignored while it was missing. */
    fun onPermissionGranted(context: Context) {
        val app = context.applicationContext
        (app as? WalkBuddyApplication)?.container?.steps?.restartListening()
        ensureRunning(app)
    }

    // ---- small UI helpers ----

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markAsked(c: Context) { prefs(c).edit().putBoolean(KEY_ASKED, true).apply() }

    fun wasAsked(c: Context): Boolean = prefs(c).getBoolean(KEY_ASKED, false)

    fun batteryTipDismissed(c: Context): Boolean = prefs(c).getBoolean(KEY_TIP_DISMISSED, false)

    fun dismissBatteryTip(c: Context) { prefs(c).edit().putBoolean(KEY_TIP_DISMISSED, true).apply() }

    fun ignoringBatteryOptimizations(c: Context): Boolean =
        (c.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(c.packageName) == true

    /** Opens the system list of battery-optimisation exemptions (no special permission needed), or the app's settings page as a fallback. */
    fun openBatterySettings(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            openAppSettings(c)
        }
    }

    fun openAppSettings(c: Context) {
        try {
            c.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", c.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // No settings app that can open this; the on-screen text explains the manual path.
        }
    }
}
