package com.walkbuddy.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.walkbuddy.MainActivity
import com.walkbuddy.R

object Notifications {
    /** Mirrors the Haptics setting; read by every vibration in the app. */
    @Volatile var hapticsEnabled: Boolean = true

    const val CH_WALK = "walk"
    const val CH_STEPS = "steps"
    const val CH_NUDGE = "nudge"
    const val CH_REMIND = "remind"
    const val ID_WALK = 1001
    const val ID_STEPS = 1004
    private const val ID_NUDGE = 1002
    private const val ID_REMIND = 1003

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_WALK, "Walk in progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a walk is running so the phone keeps sharing."
        })
        nm.createNotificationChannel(NotificationChannel(CH_STEPS, "Step counting", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A quiet notice that keeps step counting alive all day, even when the app is closed. It shows today's steps."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_NUDGE, "Gentle nudges", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Occasional, calm hints during a walk together."
            enableVibration(false)
            setSound(null, null)
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Sitting breaks and walk dates."
        })
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun walkOngoing(context: Context, text: String, stopIntent: PendingIntent?, percent: Int? = null, shortText: String? = null): Notification {
        val b = NotificationCompat.Builder(context, CH_WALK)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
        if (stopIntent != null) b.addAction(0, context.getString(R.string.notif_end_walk), stopIntent)
        val n = b.build()
        return if (percent != null) LiveUpdate.style(context, n, percent, shortText) else n
    }

    /** The quiet, always-on step counting notification (the foreground service's). */
    fun stepsOngoing(context: Context, steps: Int): Notification =
        NotificationCompat.Builder(context, CH_STEPS)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle("%,d steps today".format(steps))
            .setContentText("Walk Buddy is counting quietly. Nothing leaves your phone.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(context))
            .build()

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun nudge(context: Context, text: String) = post(context, CH_NUDGE, ID_NUDGE, "A gentle hint", text, timeoutMs = 30_000)

    fun reminder(context: Context, title: String, text: String) = post(context, CH_REMIND, ID_REMIND, title, text, timeoutMs = 0)

    private fun post(context: Context, channel: String, id: Int, title: String, text: String, timeoutMs: Long) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        nm.notify(id, b.build())
    }

    /** A short, soft haptic. Silently does nothing without a vibrator. */
    @Suppress("DEPRECATION")
    fun haptic(context: Context, strong: Boolean = false) {
        if (!hapticsEnabled) return
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        val ms = if (strong) 250L else 120L
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
