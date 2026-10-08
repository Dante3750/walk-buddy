package com.walkbuddy.domain

data class GroupWalkConfig(
    val walk: WalkConfig = WalkConfig(),
    val group: GroupConfig = GroupConfig(),
    val precision: LocationPrecision = LocationPrecision.Exact,
    val goalSteps: Int = 0,
    val maxMembers: Int = 60,
)

/** One other person in the group, as the roster and the map show them. */
data class GroupMemberCard(
    val id: String,
    val name: String,
    val pos: LatLon?,
    /** Metres from me; null while either of us has no location. */
    val distanceM: Double?,
    /** Signed metres along the group's direction of travel relative to me (positive = ahead of me). */
    val alongM: Double?,
    val relation: String,
    val steps: Int,
    val speedMps: Double?,
    val status: BuddyStatus,
    val statusText: String,
    val straggler: Boolean,
    val role: GroupRole?,
)

data class GroupState(
    val nowMs: Long,
    val elapsedMs: Long,
    val myDistanceM: Double,
    val myVerifiedSteps: Long,
    val myRawSteps: Long,
    val mySpeedMps: Double?,
    val myCadenceSpm: Double?,
    val myPos: LatLon?,
    /** People currently in the group, me included. */
    val memberCount: Int,
    val members: List<GroupMemberCard>,
    val centre: LatLon?,
    val groupRadiusM: Double?,
    val lengthM: Double?,
    val myRole: GroupRole?,
    val iAmStraggler: Boolean,
    val leaderId: String?,
    val sweeperId: String?,
    val stragglerCount: Int,
    val together: TogetherSnapshot,
    val togetherNow: Boolean?,
    val nudge: Nudge?,
    val goal: CollectiveProgress,
    val trails: Map<String, List<LatLon>>,
)

/**
 * Pure-Kotlin core of an open group walk for N people. Composition, not inheritance: a [WalkEngine] does everything
 * about ME (fix filtering, distance, verified steps, cadence), and this class adds the group: a roster of members
 * with their latest updates, the centre, stragglers, leader and sweeper, the group together score, the collective
 * step goal, group nudges and trails for the map. Late joiners need nothing special: a member simply appears with
 * their first update, and [setRoster] gives names for people who have not sent a position yet.
 */
class GroupEngine(
    val selfId: String,
    val selfName: String,
    config: GroupWalkConfig = GroupWalkConfig(),
    private val startMs: Long,
) {
    private class Peer(var name: String) {
        var lastHeardMs: Long? = null
        var left = false
        var pos: LatLon? = null
        var speedMps: Double? = null
        var steps = 0
    }

    var config: GroupWalkConfig = config
        private set
    private val inner = WalkEngine(selfId, selfName, config.walk, startMs)
    private val peers = LinkedHashMap<String, Peer>()
    private val heading = GroupHeadingTracker()
    private val cohesion = GroupCohesionTracker()
    private var nudges = GroupNudgeEngine(config.walk.nudge, config.group.slackM)
    private val collective = CollectiveSteps()
    private val trails = TrailBook(minMoveM = 5.0, maxPoints = 150)
    private var nudgeCount = 0
    private var lastSteps = 0L
    var ended = false
        private set

    /** My latest accepted position (exact). */
    val myPosition: LatLon? get() = inner.myPosition

    fun updateConfig(c: GroupWalkConfig) {
        val nudgeChanged = c.walk.nudge != config.walk.nudge || c.group.slackM != config.group.slackM
        config = c
        inner.updateConfig(c.walk)
        if (nudgeChanged) nudges = GroupNudgeEngine(c.walk.nudge, c.group.slackM)
    }

    fun onSelfFix(fix: Fix): FixVerdict {
        val v = inner.onSelfFix(fix)
        if (v == FixVerdict.Accepted) trails.add(selfId, fix.pos)
        return v
    }

    fun onSelfSteps(tMs: Long, counterTotal: Long): StepDelta = inner.onSelfSteps(tMs, counterTotal)

    /** The server's roster snapshot (on joining, including mid-walk). People not in it are marked as gone. */
    fun setRoster(entries: List<RosterEntry>) {
        val ids = entries.map { it.peerId }.toSet()
        for (e in entries) {
            if (e.peerId == selfId) continue
            val p = peerFor(e.peerId) ?: continue
            p.name = e.name
            p.left = false
        }
        for ((id, p) in peers) if (id !in ids) { p.left = true; p.pos = null }
    }

    fun onMemberJoined(id: String, name: String) {
        if (id == selfId) return
        peerFor(id)?.let { it.name = name; it.left = false }
    }

    fun onMemberLeft(id: String) {
        peers[id]?.let { it.left = true; it.pos = null }
        trails.remove(id)
    }

    private fun peerFor(id: String): Peer? {
        peers[id]?.let { return it }
        if (peers.size >= config.maxMembers) return null
        return Peer("Walker").also { peers[id] = it }
    }

    fun onUpdate(nowMs: Long, id: String, u: GroupUpdate) {
        if (id == selfId) return
        val p = peerFor(id) ?: return
        p.lastHeardMs = nowMs
        p.left = false
        val pos = u.pos
        if (pos != null && (u.accuracyM ?: 0.0) <= 60.0) {
            p.pos = pos
            trails.add(id, pos)
        } else if (pos == null) p.pos = null
        p.speedMps = u.speedMps
        p.steps = u.steps
        collective.record(id, u.steps.toLong())
    }

    /** What to send to the group now: my position (blurred if so configured) and verified steps. Steps-only before the first fix. */
    fun selfUpdate(nowMs: Long): GroupUpdate {
        val p = inner.selfPosition(nowMs)
        if (p == null) {
            return GroupUpdate(nowMs, null, null, steps = lastSteps.toInt().coerceIn(0, 1_000_000))
        }
        val shared = LocationBlur.apply(LatLon(p.lat, p.lon), config.precision)
        val blurred = config.precision != LocationPrecision.Exact
        return GroupUpdate(
            tMs = nowMs, lat = shared.lat, lon = shared.lon,
            // A blurred position must not carry the true fix accuracy (receivers would ignore or over-trust it).
            accuracyM = if (blurred) null else p.accuracyM,
            speedMps = p.speedMps, steps = p.steps, cadenceSpm = p.cadenceSpm, distanceM = p.distanceM,
        )
    }

    fun tick(nowMs: Long): GroupState {
        val st = inner.tick(nowMs)
        lastSteps = st.myVerifiedSteps
        collective.record(selfId, st.myVerifiedSteps)
        val me = inner.myPosition
        val mySpeed = st.mySpeedMps

        val selfMember = GroupMember(
            id = selfId, name = selfName, pos = me, speedMps = mySpeed, isSelf = true,
            status = if (me == null) BuddyStatus.Waiting else if ((mySpeed ?: 1.0) < LinkHealth.STOPPED_SPEED_MPS) BuddyStatus.Stopped else BuddyStatus.Moving,
        )
        val live = peers.filter { !it.value.left }
        val statuses = live.mapValues { (_, p) -> LinkHealth.status(nowMs, p.lastHeardMs, p.speedMps) }
        val others = live.map { (id, p) ->
            val s = statuses.getValue(id)
            GroupMember(id, p.name, if (s == BuddyStatus.ConnectionLost) null else p.pos, p.speedMps, s)
        }
        val analysis = GroupAnalyzer.analyze(listOf(selfMember) + others, heading.headingDeg, config.group)
        heading.update(analysis.centre)
        val known = analysis.located >= 2
        cohesion.update(nowMs, if (known) analysis.together else null)
        val nudge = if (config.walk.nudge.quiet) null else nudges.evaluate(nowMs, analysis, selfId, (mySpeed ?: 0.0) >= LinkHealth.STOPPED_SPEED_MPS)
        if (nudge != null) nudgeCount++

        val head = heading.headingDeg
        val cards = live.map { (id, p) ->
            val s = statuses.getValue(id)
            val pos = if (s == BuddyStatus.ConnectionLost) null else p.pos
            val d = if (me != null && pos != null) Geo.haversine(me, pos) else null
            val along = if (me != null && pos != null && head != null) Geo.alongTrackM(me, head, pos) else null
            val view = analysis.views[id]
            GroupMemberCard(
                id = id, name = p.name, pos = pos, distanceM = d, alongM = along, relation = WalkEngine.relationText(d, along),
                steps = p.steps, speedMps = p.speedMps, status = s, statusText = LinkHealth.copy(p.name, s),
                straggler = view?.straggler == true, role = view?.role,
            )
        }
        val myView = analysis.views[selfId]
        return GroupState(
            nowMs = nowMs, elapsedMs = nowMs - startMs, myDistanceM = st.myDistanceM, myVerifiedSteps = st.myVerifiedSteps,
            myRawSteps = st.myRawSteps, mySpeedMps = mySpeed, myCadenceSpm = st.myCadenceSpm, myPos = me,
            memberCount = 1 + live.size, members = cards, centre = analysis.centre, groupRadiusM = analysis.radiusM,
            lengthM = analysis.lengthM, myRole = myView?.role, iAmStraggler = myView?.straggler == true,
            leaderId = analysis.leaderId, sweeperId = analysis.sweeperId, stragglerCount = analysis.stragglers.size,
            together = cohesion.snapshot(), togetherNow = if (known) analysis.together else null, nudge = nudge,
            goal = collective.progress(config.goalSteps), trails = trails.snapshot(),
        )
    }

    fun finish(nowMs: Long): WalkSummary {
        ended = true
        val base = inner.finish(nowMs)
        val snap = cohesion.snapshot()
        val others = peers.size
        return base.copy(
            buddyCount = others, togetherPct = if (others > 0) snap.scorePct else null,
            longestTogetherMs = snap.longestStreakMs, nudgesShown = nudgeCount,
        )
    }
}
