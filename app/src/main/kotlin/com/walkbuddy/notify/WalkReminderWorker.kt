package com.walkbuddy.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.walkbuddy.R
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.data.Clock
import com.walkbuddy.diag.AppLog
import com.walkbuddy.domain.ReminderTexts
import com.walkbuddy.domain.ReminderTone
import com.walkbuddy.domain.WalkReminders
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Walk reminders (alpha 2.0). One inexact WorkManager job is queued for the next reminder time; when it runs it shows the notice and queues
 * the following one. No alarms, no wake locks, nothing runs between reminders. Android may run it a little late in Doze; a reminder that is
 * more than three hours late is dropped instead of nagging.
 */
object WalkReminderScheduler {
    private const val WORK = "wb-walk-reminder"
    private const val WORK_SNOOZE = "wb-walk-reminder-snooze"
    const val KEY_DUE = "due"

    /** Queues the next reminder (replacing any queued one), or cancels the job when there is none. */
    suspend fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        val c = (app as? WalkBuddyApplication)?.container ?: return
        val s = c.settings.current()
        val now = System.currentTimeMillis()
        val skip = WalkReminders.pruneSkipDays(WalkReminders.decodeDays(s.reminderSkipDays), Clock.today())
        val next = WalkReminders.next(WalkReminders.decode(s.reminders), now, ZoneId.systemDefault(), skip)
        val wm = WorkManager.getInstance(app)
        if (next == null) { wm.cancelUniqueWork(WORK); return }
        val req = OneTimeWorkRequestBuilder<WalkReminderWorker>()
            .setInitialDelay((next.atMs - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(KEY_DUE, next.atMs).build())
            .build()
        wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    fun snooze(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        val at = WalkReminders.snoozeAt(nowMs)
        val req = OneTimeWorkRequestBuilder<WalkReminderWorker>()
            .setInitialDelay(at - nowMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(KEY_DUE, at).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(WORK_SNOOZE, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelSnooze(ctx: Context) { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK_SNOOZE) }
}

class WalkReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? WalkBuddyApplication ?: return Result.success()
        val s = app.container.settings.current()
        val due = inputData.getLong(WalkReminderScheduler.KEY_DUE, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val skip = WalkReminders.decodeDays(s.reminderSkipDays)
        if (s.onboardingDone && WalkReminders.shouldShow(due, now, ZoneId.systemDefault(), skip, s.quietHours)) {
            val partner = ReminderTexts.tone(s.reminderPartnerText, s.buddyName) == ReminderTone.Partner
            val name = s.buddyName.ifBlank { "" }
            if (partner) Notifications.walkReminder(app, app.getString(R.string.rem_title_partner, name), app.getString(R.string.rem_text_partner, name))
            else Notifications.walkReminder(app, app.getString(R.string.rem_title_solo), app.getString(R.string.rem_text_solo))
            AppLog.i("reminder", "shown")
        } else {
            AppLog.d("reminder", "not shown")
        }
        WalkReminderScheduler.reschedule(app)
        return Result.success()
    }
}

/** The three notification buttons: snooze 30 minutes, "can't today" (no more reminders today) and skip (just this one). */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? WalkBuddyApplication ?: return
        val action = intent.action ?: return
        if (action != ACTION_SNOOZE && action != ACTION_CANT && action != ACTION_SKIP) return
        Notifications.cancelWalkReminder(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_SNOOZE -> WalkReminderScheduler.snooze(app)
                    ACTION_CANT -> {
                        val s = app.container.settings.current()
                        val days = WalkReminders.pruneSkipDays(WalkReminders.decodeDays(s.reminderSkipDays), Clock.today()) + Clock.today()
                        app.container.settings.setReminderSkipDays(WalkReminders.encodeDays(days))
                        WalkReminderScheduler.cancelSnooze(app)
                        WalkReminderScheduler.reschedule(app)
                    }
                    else -> { WalkReminderScheduler.cancelSnooze(app) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.walkbuddy.action.REMINDER_SNOOZE"
        const val ACTION_CANT = "com.walkbuddy.action.REMINDER_CANT"
        const val ACTION_SKIP = "com.walkbuddy.action.REMINDER_SKIP"
    }
}
