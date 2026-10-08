package com.walkbuddy.steps

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.data.Clock
import com.walkbuddy.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The always-on step counter: a low-priority foreground service (type health on Android 14+) that keeps one batched
 * TYPE_STEP_COUNTER listener registered. All maths and storage live in StepRecorder; this holds the process and shows today's steps.
 * It is independent of walks: it runs all day, and walks simply read the same feed.
 */
class StepService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as WalkBuddyApplication).container
        Notifications.ensureChannels(this)
        try {
            val first = Notifications.stepsOngoing(this, 0)
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, Notifications.ID_STEPS, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(Notifications.ID_STEPS, first)
            }
        } catch (_: Exception) {
            // Android refused (permission revoked in the meantime, or a background start restriction): the safety nets carry on.
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        container.steps.acquire(OWNER)
        job?.cancel()
        job = scope.launch {
            var lastShown = -1
            var lastAt = 0L
            val nm = getSystemService(NotificationManager::class.java)
            // Re-evaluate "today" every minute so the count rolls over at midnight (and after a timezone or DST change).
            flow { while (true) { emit(Clock.epochDay()); delay(60_000) } }
                .distinctUntilChanged()
                .flatMapLatest { day -> container.repository.verifiedStepsOn(day) }
                .collectLatest { steps ->
                    // Trailing throttle: at most one notification update per 15 s, but the latest number always lands.
                    val wait = if (lastShown < 0) 0L else (15_000 - (System.currentTimeMillis() - lastAt)).coerceAtLeast(0)
                    if (wait > 0) delay(wait)
                    if (steps != lastShown) {
                        lastShown = steps
                        runCatching { nm?.notify(Notifications.ID_STEPS, Notifications.stepsOngoing(this@StepService, steps)) }
                    }
                    lastAt = System.currentTimeMillis()
                    runCatching { StepWidgetSync.publish(container) }
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
    }
}
