package com.walkbuddy.steps

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.data.Clock
import com.walkbuddy.domain.StepNotifyThrottle
import com.walkbuddy.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The always-on step counter: a low-priority foreground service (type health on Android 14+) that keeps one batched
 * TYPE_STEP_COUNTER listener registered. All maths and storage live in StepRecorder; this holds the process and shows today's steps.
 * It is independent of walks: it runs all day, and walks simply read the same feed.
 *
 * Power: no wake lock, no timers (the day rollover comes from the system's date-changed broadcasts, not a polling loop), and the
 * notification is re-posted only when the number moved enough AND enough time passed (see [StepNotifyThrottle]), or when the app
 * becomes visible, so a walk of 5000 steps is a handful of notification updates instead of thousands.
 */
class StepService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastShown: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as WalkBuddyApplication).container
        Notifications.ensureChannels(this)
        try {
            // startForegroundService() must always be answered with startForeground(); show the last known number, not a flash of zero.
            val first = Notifications.stepsOngoing(this, lastShown)
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, Notifications.ID_STEPS, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(Notifications.ID_STEPS, first)
            }
        } catch (_: Exception) {
            // Android refused (permission revoked in the meantime, or a background start restriction): the safety net carries on.
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (job?.isActive == true) return START_STICKY // already collecting; a repeated start must not restart the pipeline
        container.steps.acquire(OWNER)
        val throttle = StepNotifyThrottle()
        job = scope.launch {
            var wasVisible = false
            val nm = getSystemService(NotificationManager::class.java)
            val visible = container.power.state.map { it.screenVisible }.distinctUntilChanged()
            dayChanges(this@StepService)
                .flatMapLatest { day -> throttle.reset(); container.repository.verifiedStepsOn(day) }
                .combine(visible) { steps, vis -> steps to vis }
                .collect { (steps, vis) ->
                    val plan = container.power.plan()
                    // The moment the app becomes visible the number is refreshed, so the shade never shows a stale count next to the app's own.
                    val justOpened = vis && !wasVisible
                    wasVisible = vis
                    if (throttle.shouldPost(steps, System.currentTimeMillis(), plan.stepsNotifyDelta, plan.stepsNotifyMinMs, force = justOpened)) {
                        lastShown = steps
                        runCatching { nm?.notify(Notifications.ID_STEPS, Notifications.stepsOngoing(this@StepService, steps)) }
                        runCatching { StepWidgetSync.publish(container) }
                    }
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        (application as? WalkBuddyApplication)?.container?.steps?.release(OWNER)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val OWNER = "service"

        /** True while this process has the service started. A new process starts with false until the service comes back. */
        @Volatile var running: Boolean = false
            private set

        /**
         * Today's epoch day now, and again whenever the system says the date, time or time zone changed (midnight, DST, travel).
         * Replaces a loop that woke the CPU every minute just to ask what day it is.
         */
        private fun dayChanges(context: Context): Flow<Long> = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { trySend(Clock.epochDay()) }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            trySend(Clock.epochDay())
            awaitClose { runCatching { context.unregisterReceiver(receiver) } }
        }.distinctUntilChanged()
    }
}
