package com.walkbuddy.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
 * A cheap inexact alarm (about every 15 minutes) that samples the step counter once. It keeps today's
 * steps current when the app is closed and drives the optional sitting-break reminder. No exact-alarm permission needed.
 */
object PeriodicSampler {
    private const val INTERVAL_MS = 15 * 60 * 1000L

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 77, Intent(context, PeriodicReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, pending(context))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context))
    }
}

class PeriodicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WalkBuddyApplication
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(8_000) { sample(app) }
            } finally {
                PeriodicSampler.schedule(app)
                pendingResult.finish()
            }
        }
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
        // Moving = enough steps since the last ~15 minute sample to count as getting up.
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
        StepTracking.ensureRunning(context)
        val app = context.applicationContext as? WalkBuddyApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(8_000) { app.container.steps.sampleNow() }
            } finally {
                pending.finish()
            }
        }
    }
}
