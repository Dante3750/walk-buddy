package com.walkbuddy.data

import com.walkbuddy.domain.DailyStepAccumulator
import com.walkbuddy.domain.StepDelta
import com.walkbuddy.sensors.StepSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps today's step totals current from the hardware step counter, both when idle (periodic samples)
 * and during walks (where the walk engine supplies the verified delta).
 */
class StepRecorder(private val source: StepSource, private val repo: AppRepository, private val settings: SettingsStore) {
    private val mutex = Mutex()
    private var acc: DailyStepAccumulator? = null

    val available: Boolean get() = source.available

    private suspend fun accumulator(): DailyStepAccumulator =
        acc ?: settings.counterState().let { (c, t) -> DailyStepAccumulator(c, t) }.also { acc = it }

    /** Sample the counter once and credit the plausible steps since the last sample to today. Returns the delta. */
    suspend fun recordIdle(nowMs: Long = System.currentTimeMillis()): StepDelta? {
        val counter = source.readOnce() ?: return null
        return mutex.withLock {
            val a = accumulator()
            val d = a.onReading(nowMs, counter)
            repo.addSteps(Clock.epochDay(nowMs), d.raw, d.verified)
            settings.saveCounterState(counter, nowMs)
            d
        }
    }

    /** During a walk: the engine already decided what is verified. */
    suspend fun recordWalkDelta(nowMs: Long, counter: Long, delta: StepDelta) {
        mutex.withLock {
            accumulator().rebase(nowMs, counter)
            repo.addSteps(Clock.epochDay(nowMs), delta.raw, delta.verified)
        }
    }

    suspend fun persistBaseline() {
        mutex.withLock {
            val a = acc ?: return
            val c = a.lastCounter ?: return
            val t = a.lastTMs ?: return
            settings.saveCounterState(c, t)
        }
    }

    fun invalidate() { acc = null }
}
