package com.walkbuddy.steps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.walkbuddy.WalkBuddyApplication
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Safety net, about every 15 minutes (WorkManager survives reboots and app updates on its own). Reads the cumulative hardware
 * counter and stores the delta since the last stored value, so steps are recovered even if the service was killed. It also
 * nudges the service back to life when Android allows it.
 */
class StepSampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? WalkBuddyApplication ?: return Result.success()
        withTimeoutOrNull(10_000) { app.container.steps.sampleNow() }
        StepWidgetSync.publish(app.container)
        if (!StepService.running) StepTracking.startService(applicationContext)
        return Result.success()
    }
}
