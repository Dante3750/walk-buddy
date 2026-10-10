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
import com.walkbuddy.R
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.domain.Format
import com.walkbuddy.notify.LiveUpdate
import com.walkbuddy.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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
        val container = (application as WalkBuddyApplication).container
        val session = container.session
        val group = container.groupSession
        if (intent?.action == ACTION_STOP) {
            if (group.active) group.endWalk() else session.endWalk()
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
            var lastStage: Triple<Boolean, Phase, Boolean>? = null
            val widgetPrefs = getSharedPreferences("wb_widget", Context.MODE_PRIVATE)
            val unit = runCatching { container.settings.current().unitSystem }.getOrDefault(com.walkbuddy.domain.UnitSystem.Metric)
            combine(session.ui, group.ui) { s, g -> s to g }.collect { (ui, g) ->
                val groupOn = g.phase == GroupPhase.Active && !g.demo
                if ((ui.phase == Phase.Idle || ui.phase == Phase.Summary) && !groupOn) {
                    ServiceCompat.stopForeground(this@WalkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val gw = g.walk
                // "Meera is 300 m away", only for a walk with one buddy who has a position.
                val buddy = if (groupOn || ui.phase != Phase.Walking) null else ui.walk?.buddies?.firstOrNull { it.distanceM != null }
                val partnerLine = buddy?.distanceM?.let { d -> getString(R.string.walk_partner_away, buddy.name.ifBlank { getString(R.string.walk_buddy_fallback) }, com.walkbuddy.domain.Units.distance(d, unit)) }
                val widgetLine = if (ui.demo) null else partnerLine
                val text = when {
                    groupOn -> if (gw == null) "Joining the group" else "Group walk, ${gw.memberCount} people, ${Format.distance(gw.myDistanceM)}"
                    ui.phase == Phase.Lobby -> if (ui.solo) "Ready to walk" else "Waiting for your buddy"
                    else -> {
                        val w = ui.walk
                        if (w == null) "Walking"
                        else if (partnerLine != null) getString(R.string.walk_notif_partner, Format.distance(w.myDistanceM), partnerLine)
                        else "Walking, ${Format.distance(w.myDistanceM)}"
                    }
                }
                val now = System.currentTimeMillis()
                val w = ui.walk
                val mySteps = if (groupOn) gw?.myVerifiedSteps else w?.myVerifiedSteps
                val live = (groupOn || ui.phase == Phase.Walking) && mySteps != null && LiveUpdate.supported()
                // The text carries the distance, which changes every second: refresh at the power policy's pace (10 to 30 s), but at once
                // when the stage changes (joining, ready, walking), so the notification is never misleading.
                val stage = Triple(groupOn, ui.phase, gw == null)
                val due = lastAt == 0L || now < lastAt || now - lastAt >= container.power.plan().walkNotifyMs
                if (stage != lastStage || (due && (text != lastText || live))) {
                    lastStage = stage
                    com.walkbuddy.notify.WidgetBridge.publishPartner(this@WalkService, widgetLine)
                    lastText = text
                    lastAt = now
                    val goal = widgetPrefs.getInt("goal", 6000).coerceAtLeast(500)
                    val steps = mySteps?.toInt() ?: 0
                    val pct = if (live) (steps * 100L / goal).toInt().coerceIn(0, 100) else null
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    nm?.notify(Notifications.ID_WALK, Notifications.walkOngoing(this@WalkService, text, stop, pct, if (live) (buddy?.distanceM?.let { com.walkbuddy.domain.Units.distance(it, unit) } ?: "%,d".format(steps)) else null))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { com.walkbuddy.notify.WidgetBridge.publishPartner(this, null) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.walkbuddy.STOP"

        /**
         * Starts the walk service only when location permission is granted: Android 14 refuses a foreground service of type
         * location without it. Without the service the walk still works while the app is on screen.
         */
        fun start(context: Context) {
            val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WalkService::class.java)) }
        }
    }
}
