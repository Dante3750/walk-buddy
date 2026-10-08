package com.walkbuddy.session

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.domain.Format
import com.walkbuddy.notify.LiveUpdate
import com.walkbuddy.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type location) that keeps the walk alive with the screen off.
 * All logic lives in [WalkSession]; this only holds the process and shows the ongoing notification.
 * Callers must hold the location permission before starting it (Android 14 enforces this).
 */
class WalkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = (application as WalkBuddyApplication).container.session
        if (intent?.action == ACTION_STOP) {
            session.endWalk()
            return START_NOT_STICKY
        }
        Notifications.ensureChannels(this)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WalkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val first = Notifications.walkOngoing(this, "Getting ready", stop)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, Notifications.ID_WALK, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(Notifications.ID_WALK, first)
        }
        job?.cancel()
        job = scope.launch {
            var lastText = ""
            var lastAt = 0L
            val widgetPrefs = getSharedPreferences("wb_widget", Context.MODE_PRIVATE)
            session.ui.collect { ui ->
                if (ui.phase == Phase.Idle || ui.phase == Phase.Summary) {
                    ServiceCompat.stopForeground(this@WalkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val text = when (ui.phase) {
                    Phase.Lobby -> if (ui.solo) "Ready to walk" else "Waiting for your buddy"
                    else -> {
                        val w = ui.walk
                        if (w == null) "Walking" else "Walking, ${Format.distance(w.myDistanceM)}"
                    }
                }
                val now = System.currentTimeMillis()
                val w = ui.walk
                val live = ui.phase == Phase.Walking && w != null && LiveUpdate.supported()
                if (text != lastText || (live && now - lastAt >= 5_000)) {
                    lastText = text
                    lastAt = now
                    val goal = widgetPrefs.getInt("goal", 6000).coerceAtLeast(500)
                    val steps = w?.myVerifiedSteps?.toInt() ?: 0
                    val pct = if (live) (steps * 100L / goal).toInt().coerceIn(0, 100) else null
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    nm?.notify(Notifications.ID_WALK, Notifications.walkOngoing(this@WalkService, text, stop, pct, if (live) "%,d".format(steps) else null))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.walkbuddy.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WalkService::class.java))
        }
    }
}
