package com.walkbuddy.domain

import kotlin.math.abs

data class WalkConfig(
    val radiusM: Double = TogetherTracker.DEFAULT_RADIUS_M,
    val nudge: NudgeConfig = NudgeConfig(),
    val profile: BodyProfile = BodyProfile(),
    val caloriesEnabled: Boolean = false,
    val stepLengthOverrideM: Double? = null,
)

data class BuddyCard(
    val id: String,
    val name: String,
    val distanceM: Double?,
    /** Signed metres along my direction of travel (positive = ahead of me). */
    val alongM: Double?,
    val relation: String,
    val zone: PaceZone?,
    val steps: Int,
    val speedMps: Double?,
    val status: BuddyStatus,
    val statusText: String,
    val pos: LatLon?,
    /** Where they were when we last heard from them. Kept after the link drops so the map and the Track can show a faded last-known place. */
    val lastPos: LatLon? = null,
    val lastDistanceM: Double? = null,
    val lastAlongM: Double? = null,
    /** Seconds since the last message (any path); null before the first. */
    val lastHeardAgoSec: Int? = null,
    /** How far they have walked this walk (their own odometer), when they said. */
    val walkedM: Double? = null,
    /** Their chosen avatar code (see [AvatarCode]); null for an older app. */
    val avatar: Int? = null,
)

/** How well the phone knows where I am. */
enum class LocationQuality { None, Weak, Good }

data class WalkState(
    val nowMs: Long,
    val elapsedMs: Long,
    val myDistanceM: Double,
    val myVerifiedSteps: Long,
    val myRawSteps: Long,
    val myCadenceSpm: Double?,
    val myZone: PaceZone?,
    val mySpeedMps: Double?,
    val myActivity: ActivityState,
    val buddies: List<BuddyCard>,
    val together: TogetherSnapshot,
    val togetherNow: Boolean?,
    val nudge: Nudge?,
    val paceSuggestion: PaceSuggestion?,
    /** My latest accepted position, for the map. */
    val myPos: LatLon? = null,
    /** Accuracy radius of that position, when the phone said. */
    val myAccuracyM: Double? = null,
    val locationQuality: LocationQuality = LocationQuality.None,
)

/**
 * Pure-Kotlin core of a live walk. The Android service feeds it sensor data and decoded peer messages once a
 * second and renders the returned [WalkState]. Nothing here touches the network or stores locations.
 */
class WalkEngine(
    val selfId: String,
    val selfName: String,
    var config: WalkConfig = WalkConfig(),
    private val startMs: Long,
) {
    private class Peer(var name: String) {
        var lastHeardMs: Long? = null
        var left = false
        var pos: LatLon? = null
        var accuracyM: Double? = null
        var speedMps: Double? = null
        var steps = 0
        var cadence: Double? = null
        var walkedM: Double? = null
        var avatar: Int? = null
        val pace = RollingPace()
    }

    private val peers = LinkedHashMap<String, Peer>()
    private val distance = DistanceTracker()
    private val heading = HeadingTracker()
    private val pace = RollingPace()
    private val cadence = CadenceTracker()
    private val steps = VerifiedStepsFilter()
    private val active = ActiveMinuteCounter()
    private var together = TogetherTracker(config.radiusM)
    private var nudges = NudgeEngine(config.nudge)
    private var lastFixSpeed: Double? = null
    private var lastTickMs: Long = startMs
    private var activity = ActivityState.Unknown
    private val pingIn = PingLimiter(minGapMs = 30_000, maxPerHour = 12)
    private val pingOut = PingLimiter()
    private val pendingPings = ArrayDeque<String>()
    private val pendingSpots = ArrayDeque<Pair<String, FavoriteSpot>>()
    private var nudgeCount = 0
    var ended = false
        private set

    val peerIds: List<String> get() = peers.keys.toList()

    /**
     * A usable but weak fix (up to [DISPLAY_MAX_ACCURACY_M]): enough to show on the map and to share, never used for distance.
     * Without it, an "approximate location" grant, an indoor start or a cold GPS shows nothing at all until a 30 m fix arrives.
     */
    private var weakFix: Fix? = null

    /** The best position we have: the latest good fix, or a newer weak one when the good ones stopped coming (GPS lost). */
    private val bestFix: Fix?
        get() {
            val good = distance.lastAccepted
            val weak = weakFix
            return when {
                good == null -> weak
                weak != null && weak.tMs - good.tMs > WEAK_TAKES_OVER_MS -> weak
                else -> good
            }
        }

    /** My latest position (exact, never blurred); null until the first fix of any quality. */
    val myPosition: LatLon? get() = bestFix?.pos

    /** Apply new settings mid-walk (radius, quiet mode, pace-sync...). Counters keep their history. */
    fun updateConfig(c: WalkConfig) {
        val radiusChanged = c.radiusM != config.radiusM
        val nudgeChanged = c.nudge != config.nudge
        config = c
        if (radiusChanged) together.radiusM = c.radiusM
        if (nudgeChanged) nudges = NudgeEngine(c.nudge)
    }

    fun onSelfFix(fix: Fix): FixVerdict {
        val v = distance.add(fix)
        if (v == FixVerdict.Accepted) {
            heading.update(fix.pos)
            pace.add(fix.tMs, distance.totalM)
            lastFixSpeed = fix.speedMps ?: pace.speedMps()
        } else if (v == FixVerdict.LowAccuracy && fix.pos.isValid && (fix.accuracyM ?: 0.0) <= DISPLAY_MAX_ACCURACY_M) {
            val w = weakFix
            if (w == null || fix.tMs >= w.tMs) weakFix = fix
        }
        return v
    }

    /** Returns the raw and verified step increase caused by this reading. */
    fun onSelfSteps(tMs: Long, counterTotal: Long): StepDelta {
        val before = steps.totals()
        steps.onSample(tMs, counterTotal, activity, lastFixSpeed)
        val after = steps.totals()
        // Cadence tracks raw movement so the zone reflects how fast you really walk.
        cadence.add(tMs, steps.totals().raw)
        activity = ActivityClassifier.classify(lastFixSpeed ?: pace.speedMps(), cadence.spm())
        return StepDelta(after.raw - before.raw, after.verified - before.verified)
    }

    fun onPeer(nowMs: Long, fromId: String, msg: PeerMessage) {
        if (fromId == selfId) return
        if (peers.size >= 3 && fromId !in peers) return
        val p = peers.getOrPut(fromId) { Peer("Buddy") }
        p.lastHeardMs = nowMs
        if (msg !is PeerMessage.Bye) p.left = false
        when (msg) {
            is PeerMessage.Hello -> { p.name = msg.name; p.avatar = msg.avatar ?: p.avatar }
            is PeerMessage.Position -> {
                if ((msg.accuracyM ?: 0.0) <= DISPLAY_MAX_ACCURACY_M) {
                    p.pos = LatLon(msg.lat, msg.lon)
                    p.accuracyM = msg.accuracyM
                }
                p.steps = msg.steps
                p.cadence = msg.cadenceSpm
                if (msg.distanceM != null) p.walkedM = msg.distanceM
                if (msg.distanceM != null) {
                    p.pace.add(msg.tMs, msg.distanceM)
                    p.speedMps = p.pace.speedMps() ?: msg.speedMps
                } else p.speedMps = msg.speedMps
            }
            is PeerMessage.Ping -> if (pingIn.tryAcquire(nowMs)) pendingPings.addLast(p.name)
            is PeerMessage.Spot -> pendingSpots.addLast(p.name to FavoriteSpot(msg.name, msg.lat, msg.lon))
            PeerMessage.Bye -> { p.left = true; p.pos = null }
            is PeerMessage.React, is PeerMessage.Daily, is PeerMessage.Pin, PeerMessage.Unpin, is PeerMessage.Unknown -> Unit
        }
    }

    fun takePing(): String? = pendingPings.removeFirstOrNull()
    fun takeSpot(): Pair<String, FavoriteSpot>? = pendingSpots.removeFirstOrNull()

    /** Returns a ping message to send, or null if rate-limited. */
    fun requestPing(nowMs: Long): PeerMessage.Ping? = if (pingOut.tryAcquire(nowMs)) PeerMessage.Ping(nowMs) else null

    /** My current broadcast. Null until the first accepted fix. */
    fun selfPosition(nowMs: Long): PeerMessage.Position? {
        val f = bestFix ?: return null
        val t = steps.totals()
        return PeerMessage.Position(
            tMs = nowMs, lat = f.lat, lon = f.lon, accuracyM = f.accuracyM, speedMps = pace.speedMps() ?: lastFixSpeed,
            steps = t.verified.toInt().coerceIn(0, 1_000_000), cadenceSpm = cadence.spm(), distanceM = distance.totalM,
        )
    }

    fun tick(nowMs: Long): WalkState {
        val dtSec = ((nowMs - lastTickMs) / 1000).toInt().coerceIn(0, 10)
        lastTickMs = nowMs
        val cad = cadence.spm()
        active.add(cad, dtSec)
        val fixNow = bestFix
        val me = fixNow?.pos
        val head = heading.headingDeg
        val mySpeed = pace.speedMps()

        val cards = peers.map { (id, p) ->
            val status = if (p.left) BuddyStatus.ConnectionLost else LinkHealth.status(nowMs, p.lastHeardMs, p.speedMps)
            val pos = if (status == BuddyStatus.ConnectionLost) null else p.pos
            val d = if (me != null && pos != null) Geo.haversine(me, pos) else null
            val along = if (me != null && pos != null && head != null) Geo.alongTrackM(me, head, pos) else null
            // The last known place stays available (faded on the map and the Track) so a quiet link never makes your buddy vanish.
            val last = if (p.left) null else p.pos
            val lastD = if (me != null && last != null) Geo.haversine(me, last) else null
            val lastAlong = if (me != null && last != null && head != null) Geo.alongTrackM(me, head, last) else null
            BuddyCard(
                id = id, name = p.name, distanceM = d, alongM = along, relation = relationText(d, along),
                zone = PaceZone.fromCadence(p.cadence), steps = p.steps, speedMps = p.speedMps, status = status,
                statusText = LinkHealth.copy(p.name, status), pos = pos,
                lastPos = last, lastDistanceM = lastD, lastAlongM = lastAlong,
                lastHeardAgoSec = p.lastHeardMs?.let { ((nowMs - it) / 1000L).toInt().coerceAtLeast(0) },
                walkedM = p.walkedM, avatar = p.avatar,
            )
        }

        val live = cards.filter { it.status == BuddyStatus.Moving || it.status == BuddyStatus.Stopped }
        var togetherNow: Boolean? = null
        if (cards.isNotEmpty()) {
            val allKnown = me != null && cards.all { it.pos != null }
            val spread = if (allKnown) Geo.spreadM(listOf(me!!) + cards.map { it.pos!! }) else null
            together.update(nowMs, spread)
            togetherNow = spread?.let { it <= config.radiusM }
        }

        var nudge: Nudge? = null
        val far = live.filter { it.distanceM != null }.maxByOrNull { it.distanceM!! }
        if (far != null) {
            nudge = nudges.evaluate(
                NudgeContext(
                    nowMs = nowMs, gapM = far.distanceM, iAmAhead = far.alongM?.let { it < 0 },
                    iAmMoving = (mySpeed ?: 0.0) >= LinkHealth.STOPPED_SPEED_MPS,
                    buddyMoving = far.status == BuddyStatus.Moving, buddyConnected = true, buddyName = far.name,
                )
            )
            if (nudge != null) nudgeCount++
        }

        val paces = buildMap<String, Double?> {
            put(selfId, mySpeed)
            for ((id, p) in peers) put(id, p.speedMps)
        }
        val suggestion = if (cards.isNotEmpty()) PaceMatch.suggest(paces) else null

        val t = steps.totals()
        return WalkState(
            nowMs = nowMs, elapsedMs = nowMs - startMs, myDistanceM = distance.totalM, myVerifiedSteps = t.verified, myRawSteps = t.raw,
            myCadenceSpm = cad, myZone = PaceZone.fromCadence(cad), mySpeedMps = mySpeed, myActivity = activity, buddies = cards,
            together = together.snapshot(), togetherNow = togetherNow, nudge = nudge, paceSuggestion = suggestion,
            myPos = me, myAccuracyM = fixNow?.accuracyM, locationQuality = qualityOf(fixNow),
        )
    }

    fun finish(nowMs: Long): WalkSummary {
        ended = true
        val dur = nowMs - startMs
        val t = steps.totals()
        val snap = together.snapshot()
        val hadBuddies = peers.isNotEmpty()
        val cal = if (config.caloriesEnabled)
            CalorieEstimator.estimate(config.profile, dur, distance.totalM, t.verified, config.stepLengthOverrideM) else null
        return WalkSummary(
            durationMs = dur, distanceM = distance.totalM, rawSteps = t.raw, verifiedSteps = t.verified,
            avgSpeedMps = if (dur > 0 && distance.totalM > 0) distance.totalM / (dur / 1000.0) else null,
            buddyCount = peers.size, togetherPct = if (hadBuddies) snap.scorePct else null, longestTogetherMs = snap.longestStreakMs,
            moderateMin = active.moderateMin, vigorousMin = active.vigorousMin, nudgesShown = nudgeCount, calories = cal,
        )
    }

    /** Accuracy only: with a minimum-distance filter a standing-still walker gets no new fixes, so fix age says nothing about quality. */
    private fun qualityOf(f: Fix?): LocationQuality = when {
        f == null -> LocationQuality.None
        (f.accuracyM ?: 0.0) > FixFilter().maxAccuracyM -> LocationQuality.Weak
        else -> LocationQuality.Good
    }

    companion object {
        /** Fixes up to this radius are shown and shared; distance still needs [FixFilter]'s stricter accuracy. */
        const val DISPLAY_MAX_ACCURACY_M = 250.0
        private const val WEAK_TAKES_OVER_MS = 15_000L

        fun relationText(distanceM: Double?, alongM: Double?): String = when {
            distanceM == null -> "Location not shared yet"
            distanceM <= 15 -> "Right beside you"
            alongM == null -> "About ${Math.round(distanceM)} m away"
            abs(alongM) < 15 -> "About ${Math.round(distanceM)} m away, side by side"
            alongM > 0 -> "About ${Math.round(alongM)} m ahead"
            else -> "About ${Math.round(-alongM)} m behind"
        }
    }
}
