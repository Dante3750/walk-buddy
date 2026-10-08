package com.walkbuddy.steps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.notify.PeriodicSampler
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one background safety net: WorkManager, about every 30 minutes, only when the battery is not low (Android batches it with other
 * apps' work and defers it in Doze). It reads the cumulative hardware counter and stores the delta, so steps are recovered even if the
 * service was killed, refreshes the widget, runs the optional reminders, and nudges the service back to life when Android allows it.
 * Correctness never depends on how often this runs: the counter is cumulative and survives in hardware, so a late or skipped run only
 * delays when the number appears.
 */
class StepSampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? WalkBuddyApplication ?: return Result.success()
        withTimeoutOrNull(15_000) { PeriodicSampler.run(app) }
        return Result.success()
    }
}
