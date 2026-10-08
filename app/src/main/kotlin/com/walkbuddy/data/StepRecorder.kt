package com.walkbuddy.data

import com.walkbuddy.domain.BaselineEvent
import com.walkbuddy.domain.StepBaseline
import com.walkbuddy.domain.StepDelta
import com.walkbuddy.domain.StepLedger
import com.walkbuddy.domain.StepSensorKind
import com.walkbuddy.domain.StepUpdate
import com.walkbuddy.sensors.StepEvent
import com.walkbuddy.sensors.StepSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A cumulative step total at a moment. Walk sessions read their steps from this, never straight from the sensor. */
data class StepReading(val counter: Long, val wallMs: Long)

/**
 * The single owner of the step feed. Everything that wants steps (the always-on service, the periodic safety net,
 * walk sessions) goes through here, so a step is counted exactly once.
 *
 *  - [ingest] turns a sensor event into per-day increments with [StepLedger] and stores them together with the new baseline
 *    in one Room transaction. The UI reads the database; nothing is held only in memory.
 *  - [acquire]/[release] keep one batched sensor listener alive while anyone needs it.
 *  - [live] tells walk sessions about every new cumulative reading.
 */
class StepRecorder(private val source: StepSource, private val repo: AppRepository, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile private var baseline: StepBaseline? = null
    @Volatile private var loaded = false
    private var persistedAtMs = 0L

    private val owners = LinkedHashSet<String>()
    private var listenJob: Job? = null
    private val interactive = MutableStateFlow(false)

    private val _live = MutableSharedFlow<StepReading>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val live: SharedFlow<StepReading> = _live

    private val _listening = MutableStateFlow(false)

    /** True while a sensor listener is registered by this process. */
    val listening: StateFlow<Boolean> = _listening

    private val lastEventMs = MutableStateFlow<Long?>(null)

    /** When a step reading was last processed (this process, or the stored baseline if the process is new). */
    val lastSampleMs: Flow<Long?> = combine(lastEventMs, repo.stepState) { mem, db -> listOfNotNull(mem, db?.tMs).maxOrNull() }

    val kind: StepSensorKind? get() = source.kind
    val available: Boolean get() = source.available
    val needsPermission: Boolean get() = source.needsPermission

    /** Null until tried; false when Android refused the listener (typically because the activity permission is missing). */
    val registerOk: Boolean? get() = source.lastRegisterOk

    private suspend fun currentBaseline(): StepBaseline? {
        if (!loaded) {
            baseline = repo.stepBaseline() ?: settings.counterState().let { (c, t) ->
                // One-time import of the pre-1.6 baseline so an update does not lose the steps taken while it installed.
                if (c != null && t != null && source.kind == StepSensorKind.Counter) StepBaseline(c, t, null, StepSensorKind.Counter) else null
            }
            loaded = true
        }
        return baseline
    }

    /** Accounts for one sensor event. Safe to call from any thread; returns what was credited. */
    suspend fun ingest(e: StepEvent): StepUpdate = mutex.withLock {
        val prev = currentBaseline()
        val counter: Long
        val base: StepBaseline?
        if (e.kind == StepSensorKind.Counter) {
            counter = e.value
            base = prev
        } else {
            // Fallback sensors report "n steps now": keep our own running total as the counter.
            base = prev?.takeIf { it.kind == e.kind } ?: StepBaseline(0, e.wallMs, null, e.kind)
            counter = base.counter + e.value
        }
        val u = StepLedger.apply(base, counter, e.wallMs, e.bootMs, e.kind)
        val mustPersist = u.parts.isNotEmpty() || u.event != BaselineEvent.Normal || prev == null || e.wallMs - persistedAtMs > 60_000
        if (mustPersist) {
            repo.applyStepUpdate(u.parts, u.baseline)
            persistedAtMs = e.wallMs
        }
        baseline = u.baseline
        lastEventMs.value = e.wallMs
        _live.tryEmit(StepReading(u.baseline.counter, e.wallMs))
        u
    }

    /** Reads the hardware counter once and accounts for it (the safety net, and the start of every walk). Null without a counter or permission. */
    suspend fun sampleNow(): StepUpdate? {
        val e = source.readOnce() ?: return null
        return ingest(e)
    }

    /** Samples once and returns the steps credited since the previous reading. */
    suspend fun recordIdle(@Suppress("UNUSED_PARAMETER") nowMs: Long = System.currentTimeMillis()): StepDelta? =
        sampleNow()?.let { StepDelta(it.raw, it.verified) }

    /** Starts the batched listener if nobody has yet. [owner] names who needs it ("service", "walk", ...). */
    fun acquire(owner: String) {
        synchronized(owners) {
            owners += owner
            if (listenJob?.isActive != true) startListening()
        }
    }

    fun release(owner: String) {
        synchronized(owners) {
            owners -= owner
            if (owners.isEmpty()) {
                listenJob?.cancel()
                listenJob = null
            }
        }
    }

    /** Registers again, for example right after the user grants the activity permission (the first attempt was silently ignored). */
    fun restartListening() {
        synchronized(owners) {
            if (owners.isEmpty()) return
            listenJob?.cancel()
            startListening()
        }
    }

    /** True while the app is on screen: events are delivered right away instead of batched, so the hero counts live. */
    fun setInteractive(on: Boolean) { interactive.value = on }

    private fun startListening() {
        if (!source.available) return
        val raw = source.events(interactive)
        // The counter is cumulative, so dropping intermediate values loses nothing. Increments must all be kept.
        val flow = if (source.kind == StepSensorKind.Counter) raw.conflate() else raw.buffer(Channel.UNLIMITED)
        listenJob = scope.launch {
            _listening.value = true
            try {
                flow.collect { e ->
                    try {
                        ingest(e)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        // A failed write is retried by the next event, which carries the cumulative value again.
                    }
                }
            } finally {
                _listening.value = false
            }
        }
    }

    fun invalidate() {
        loaded = false
        baseline = null
        persistedAtMs = 0L
    }
}
