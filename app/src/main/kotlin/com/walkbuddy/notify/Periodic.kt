package com.walkbuddy.notify

import android.content.BroadcastReceiver
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.data.Clock
import com.walkbuddy.data.Goals
import com.walkbuddy.domain.AnniversaryCountdown
import com.walkbuddy.domain.ReminderPlanner
import com.walkbuddy.domain.SitAction
import com.walkbuddy.domain.SittingMonitor
import com.walkbuddy.steps.StepService
import com.walkbuddy.steps.StepTracking
import com.walkbuddy.steps.StepWidgetSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The slow, once-in-a-while background check. It used to be an exact-ish alarm (a CPU wake-up every 15 minutes) PLUS a WorkManager job
 * doing the same thing; now it is only the WorkManager job (see [com.walkbuddy.steps.StepSampleWorker]), which Android can batch with
 * other apps' work and defer in Doze. [cancelLegacyAlarm] removes the alarm that alpha 1.6 and older scheduled.
 */
object PeriodicSampler {
    private const val LEGACY_RECEIVER = "com.walkbuddy.notify.PeriodicReceiver"
    private const val PREFS = "wb_steps"
    private const val KEY_LEGACY_GONE = "legacy_alarm_cancelled"

    /** One-time (guarded by a flag), idempotent: cancels the old 15 minute alarm if an older version left it armed. */
    fun cancelLegacyAlarm(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LEGACY_GONE, false)) return
        runCatching {
            val intent = Intent().setComponent(ComponentName(context.packageName, LEGACY_RECEIVER))
            val pi = PendingIntent.getBroadcast(context, 77, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
                pi.cancel()
            }
        }
        prefs.edit().putBoolean(KEY_LEGACY_GONE, true).apply()
    }

    /**
     * What the background check does: store the delta from the hardware counter, refresh the widget, and drive the optional
     * reminders. A run that comes sooner than the power policy's minimum gap (longer in Battery Saver) does nothing.
     */
    suspend fun run(app: WalkBuddyApplication) {
        val c = app.container
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_check", 0L)
        if (now >= last && now - last < c.power.plan().workerMinGapMs) return
        prefs.edit().putLong("last_check", now).apply()
        sample(app)
    }

    /** Anniversary and walk-date reminders: each fires once, and never during quiet hours. */
    private suspend fun sendDueReminders(c: com.walkbuddy.AppContainer, s: com.walkbuddy.data.Settings, now: Long) {
        val anniv = AnniversaryCountdown.parse(s.anniversaryDate)?.let { it to s.anniversaryLabel }
        val dates = c.repository.datesOnce()
        val sent = s.remindersSent.split('|').filter { it.isNotBlank() }.toSet()
        val due = ReminderPlanner.due(now, java.time.ZoneId.systemDefault(), anniv, dates, sent, s.quietHours)
        if (due.isEmpty()) return
        due.forEach { Notifications.reminder(c.appContext, it.title, it.text) }
        c.settings.rememberReminders(due.map { it.key })
    }

    private suspend fun sample(app: WalkBuddyApplication) {
        val c = app.container
        val s = c.settings.current()
        val now = System.currentTimeMillis()
        // Steps first, whatever else is going on: the hardware counter kept counting while we were dead, so read it and store the delta.
        // (A walk reads the same feed, so there is nothing to skip while one is running.)
        val delta = c.steps.recordIdle(now)
        // The service stores steps continuously, so "steps since the last check" comes from today's total, not from the delta above.
        val day = Clock.epochDay(now)
        val total = c.repository.day(day)?.verifiedSteps ?: 0
        val sit = app.getSharedPreferences("wb_steps", Context.MODE_PRIVATE)
        val sinceLast = if (sit.getLong("sit_day", -1) == day) total - sit.getInt("sit_steps", total) else total
        sit.edit().putLong("sit_day", day).putInt("sit_steps", total).apply()
        runCatching { StepWidgetSync.publish(c) }
        if (!StepService.running) StepTracking.startService(app)
        if (!s.onboardingDone) return
        sendDueReminders(c, s, now)
        if (delta == null || !s.sittingReminders) return
        if (s.quietHours.isQuiet(Clock.hourOfDay(now))) return
        val monitor = SittingMonitor()
        c.settings.sitState()?.let { monitor.restore(it) }
        // Moving = enough steps since the last background check (15 to 60 minutes ago) to count as getting up.
        val moving = sinceLast >= 120
        val action = if (moving) { monitor.markBreak(now); null } else monitor.onSample(now, false, Clock.hourOfDay(now))
        c.settings.saveSitState(monitor.snapshot())
        if (action is SitAction.Remind) Notifications.reminder(app, "Time to stretch your legs", action.text)
    }
}

/**
 * After a reboot or an app update: re-arm the safety nets and try to start the step service. Android 12+ may refuse
 * a foreground start from here (especially after an update); that is tolerated and the safety nets keep reading the counter.
 * After a reboot the hardware counter restarted from zero; StepLedger detects that and counts the new value as the delta.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only the two system broadcasts we asked for; anything else (the component is not exported, this is belt and braces) is ignored.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        StepTracking.ensureRunning(context)
        val app = context.applicationContext as? WalkBuddyApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runCatching { WalkReminderScheduler.reschedule(app) }
                withTimeoutOrNull(8_000) { app.container.steps.sampleNow() }
            } finally {
                pending.finish()
            }
        }
    }
}
