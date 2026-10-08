package com.walkbuddy.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------------------------
// Location precision (optional blur)
// ---------------------------------------------------------------------------------------------------------------

/**
 * How precisely a phone shares its position with the group. Blurring snaps the position to the centre of a grid cell,
 * so a stationary person always shows the same point (random jitter could be averaged away) and the group still sees
 * roughly where everyone is. The phone keeps showing your own exact position to you.
 */
enum class LocationPrecision(val cellM: Double, val label: String) {
    Exact(0.0, "Exact"),
    Approx100(100.0, "About 100 m"),
    Approx500(500.0, "About 500 m");

    /** The farthest the shared point can be from the true one (half the cell diagonal). */
    val maxErrorM: Double get() = cellM * 0.7072

    companion object {
        fun fromName(n: String?): LocationPrecision = values().firstOrNull { it.name == n } ?: Exact
    }
}

object LocationBlur {
    private const val M_PER_DEG_LAT = 111_195.0

    fun apply(p: LatLon, precision: LocationPrecision): LatLon {
        if (precision == LocationPrecision.Exact || !p.isValid) return p
        val cell = precision.cellM
        val dLat = cell / M_PER_DEG_LAT
        val lat = ((floor(p.lat / dLat) + 0.5) * dLat).coerceIn(-90.0, 90.0)
        val cosLat = max(cos(Math.toRadians(lat)), 0.05)
        val dLon = cell / (M_PER_DEG_LAT * cosLat)
        val lon = ((floor(p.lon / dLon) + 0.5) * dLon).coerceIn(-180.0, 180.0)
        return LatLon(lat, lon)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Group geometry: centroid, spread, stragglers, leader and sweeper
// ---------------------------------------------------------------------------------------------------------------

enum class GroupRole(val label: String) { Leader("At the front"), Sweeper("At the back") }

data class GroupMember(
    val id: String,
    val name: String,
    val pos: LatLon?,
    val speedMps: Double? = null,
    val status: BuddyStatus = BuddyStatus.Moving,
    val isSelf: Boolean = false,
)

data class GroupConfig(
    /** The group counts as "together" when at least [quorum] of the located members are within this distance of the centre. */
    val radiusM: Double = 100.0,
    /** A member farther than this from the group's centre is a straggler. */
    val stragglerM: Double = 150.0,
    val quorum: Double = 0.8,
    /** Extra tolerance when positions are blurred (see [LocationPrecision.maxErrorM]). */
    val slackM: Double = 0.0,
    /** A member the host or the group chose to walk at the back on purpose; never flagged as a straggler. */
    val sweeperId: String? = null,
    /** Roles only make sense with at least this many located members. */
    val minForRoles: Int = 3,
)

data class MemberView(
    val id: String,
    val distFromCentreM: Double?,
    /** Signed metres along the group's direction of travel relative to the centre (positive = ahead). */
    val alongM: Double?,
    val straggler: Boolean,
    val role: GroupRole?,
)

data class GroupAnalysis(
    val centre: LatLon?,
    /** Distance from the centre to the farthest located member. */
    val radiusM: Double?,
    val located: Int,
    val withinRadius: Int,
    val together: Boolean,
    val views: Map<String, MemberView>,
    val stragglers: List<String>,
    val leaderId: String?,
    val sweeperId: String?,
    /** True when the sweeper was agreed on purpose (see [GroupConfig.sweeperId]) rather than just being last in line. */
    val sweeperChosen: Boolean,
    /** Distance between the front and the back of the group, along the direction of travel. */
    val lengthM: Double?,
    val groupMoving: Boolean,
)

object GroupGeo {
    /** Centre of a set of points, averaging 3-D unit vectors so it behaves across the antimeridian and near the poles. */
    fun centroid(points: List<LatLon>): LatLon? {
        if (points.isEmpty()) return null
        var x = 0.0; var y = 0.0; var z = 0.0
        for (p in points) {
            val la = Math.toRadians(p.lat); val lo = Math.toRadians(p.lon)
            x += cos(la) * cos(lo); y += cos(la) * sin(lo); z += sin(la)
        }
        val n = points.size
        x /= n; y /= n; z /= n
        val hyp = sqrt(x * x + y * y)
        if (hyp < 1e-12 && kotlin.math.abs(z) < 1e-12) return points.first()
        return LatLon(Math.toDegrees(atan2(z, hyp)), Math.toDegrees(atan2(y, x)))
    }

    /**
     * Like [centroid] but resistant to one or two people far from everyone else: with five or more points the
     * farthest fifth are ignored, so a straggler does not drag the "centre of the group" towards themselves.
     */
    fun robustCentroid(points: List<LatLon>): LatLon? {
        val c = centroid(points) ?: return null
        if (points.size < 5) return c
        val keep = points.size - Math.ceil(points.size * 0.2).toInt()
        val core = points.sortedBy { Geo.haversine(c, it) }.take(keep)
        return centroid(core)
    }
}

object GroupAnalyzer {
    /**
     * [headingDeg] is the group's direction of travel (see [GroupHeadingTracker]); without it there are no front/back
     * roles and no ahead/behind, only distances.
     */
    fun analyze(members: List<GroupMember>, headingDeg: Double?, cfg: GroupConfig = GroupConfig()): GroupAnalysis {
        val located = members.filter {
            it.pos != null && it.status != BuddyStatus.ConnectionLost && it.status != BuddyStatus.Waiting
        }
        val centre = GroupGeo.robustCentroid(located.map { it.pos!! })
        if (centre == null) {
            return GroupAnalysis(null, null, 0, 0, false, emptyMap(), emptyList(), null, null, false, null, false)
        }
        val dist = located.associate { it.id to Geo.haversine(centre, it.pos!!) }
        val along = if (headingDeg == null) emptyMap() else located.associate { it.id to Geo.alongTrackM(centre, headingDeg, it.pos!!) }

        val rolesOk = headingDeg != null && located.size >= cfg.minForRoles
        val explicitSweeper = cfg.sweeperId?.takeIf { id -> located.any { it.id == id } }
        val leaderId = if (rolesOk) located.filter { it.id != explicitSweeper }.maxByOrNull { along[it.id] ?: Double.NEGATIVE_INFINITY }?.id else null
        val sweeperId = when {
            !rolesOk -> null
            explicitSweeper != null -> explicitSweeper
            else -> located.filter { it.id != leaderId }.minByOrNull { along[it.id] ?: Double.POSITIVE_INFINITY }?.id
        }

        val limit = cfg.stragglerM + cfg.slackM
        // Only a sweeper who was chosen on purpose is exempt; whoever is simply last in line and far back is a straggler.
        val stragglers = located.filter { m ->
            m.id != explicitSweeper && (dist[m.id] ?: 0.0) > limit && located.size >= 2
        }.map { it.id }

        val withinRadius = dist.count { it.value <= cfg.radiusM + cfg.slackM }
        val together = located.size >= 2 && withinRadius >= Math.ceil(located.size * cfg.quorum).toInt()
        val views = located.associate { m ->
            m.id to MemberView(
                id = m.id, distFromCentreM = dist[m.id], alongM = along[m.id], straggler = m.id in stragglers,
                role = when (m.id) { leaderId -> GroupRole.Leader; sweeperId -> GroupRole.Sweeper; else -> null },
            )
        }
        val length = if (leaderId != null && sweeperId != null) (along[leaderId]!! - along[sweeperId]!!) else null
        val moving = located.count { (it.speedMps ?: 0.0) >= LinkHealth.STOPPED_SPEED_MPS }
        return GroupAnalysis(
            centre = centre, radiusM = dist.values.maxOrNull(), located = located.size, withinRadius = withinRadius,
            together = together, views = views, stragglers = stragglers, leaderId = leaderId, sweeperId = sweeperId,
            sweeperChosen = explicitSweeper != null && sweeperId == explicitSweeper, lengthM = length, groupMoving = moving * 2 >= located.size && moving > 0,
        )
    }
}

/** The group's direction of travel, from how its centre moves. Null until the centre has moved a fair distance. */
class GroupHeadingTracker(private val minMoveM: Double = 20.0) {
    private val tracker = HeadingTracker(minMoveM)
    val headingDeg: Double? get() = tracker.headingDeg
    fun update(centre: LatLon?) { if (centre != null) tracker.update(centre) }
}

// ---------------------------------------------------------------------------------------------------------------
// Together score for N people
// ---------------------------------------------------------------------------------------------------------------

/**
 * Share of walk time with the group together (at least the quorum within the radius of the group's centre).
 * Built on [TogetherTracker], so the clamping of long gaps and the streak logic are the same as for two people.
 */
class GroupCohesionTracker(maxGapMs: Long = 15_000) {
    private val tracker = TogetherTracker(radiusM = 1.0, maxGapMs = maxGapMs)

    /** [together] null means "not enough information" (counts as walk time, not as togetherness). */
    fun update(tMs: Long, together: Boolean?) = tracker.update(tMs, if (together == true) 0.0 else null)
    fun snapshot(): TogetherSnapshot = tracker.snapshot()
}

// ---------------------------------------------------------------------------------------------------------------
// Collective step goal
// ---------------------------------------------------------------------------------------------------------------

data class CollectiveProgress(val goal: Int, val totalSteps: Long, val fraction: Double, val remaining: Long, val reached: Boolean, val walkers: Int)

/**
 * Everyone's steps add up to one shared goal. Steps only ever go up, so someone leaving early does not shrink the total.
 * There is deliberately no ranking: the group sees the sum, not who contributed most.
 */
class CollectiveSteps {
    private val best = LinkedHashMap<String, Long>()

    fun record(id: String, steps: Long) {
        if (steps < 0) return
        val old = best[id]
        if (old == null || steps > old) best[id] = steps
    }

    fun total(): Long = best.values.sum()
    fun walkers(): Int = best.size

    fun progress(goal: Int): CollectiveProgress {
        val total = total()
        val frac = if (goal <= 0) 0.0 else (total.toDouble() / goal).coerceIn(0.0, 1.0)
        return CollectiveProgress(goal, total, frac, if (goal <= 0) 0 else max(0L, goal - total), goal > 0 && total >= goal, best.size)
    }

    companion object {
        /** A friendly default: about 3,000 steps per person, rounded to 500, never below 3,000. */
        fun suggestGoal(people: Int): Int {
            val raw = 3_000 * people.coerceAtLeast(1)
            return max(3_000, (raw + 250) / 500 * 500)
        }

        fun message(p: CollectiveProgress): String = when {
            p.goal <= 0 -> "Together the group has walked ${p.totalSteps} steps."
            p.reached -> "The group reached its goal of ${p.goal} steps together. Lovely."
            else -> "Together: ${p.totalSteps} of ${p.goal} steps."
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Nudges for a group
// ---------------------------------------------------------------------------------------------------------------

/**
 * Gentle prompts for a group, built on [NudgeEngine] so they have the same protections (sustained gap, hysteresis,
 * cooldown, per-walk cap, quiet mode). Two kinds:
 *  - if you are the one falling behind the group, a hint to pick up the pace a little;
 *  - if you are at the front and people (or the sweeper) are well back, a hint to ease off.
 * A sweeper chosen on purpose is never nudged to hurry: being at the back is their job.
 */
class GroupNudgeEngine(config: NudgeConfig = NudgeConfig(farM = 150.0, nearM = 90.0), slackM: Double = 0.0) {
    private val cfg = config.copy(farM = config.farM + slackM, nearM = config.nearM + slackM)
    private val catchUp = NudgeEngine(cfg.copy(paceSync = false))
    private val easeOff = NudgeEngine(cfg.copy(paceSync = true))
    val count: Int get() = catchUp.count + easeOff.count

    fun evaluate(nowMs: Long, a: GroupAnalysis, selfId: String, iAmMoving: Boolean): Nudge? {
        val me = a.views[selfId] ?: return null
        if (a.located < 2) return null
        val role = me.role
        val iAmChosenSweeper = a.sweeperChosen && role == GroupRole.Sweeper
        // 1. I am well behind the group (a chosen sweeper is at the back on purpose and is never told to hurry).
        if (!iAmChosenSweeper) {
            val alongBehind = (me.alongM ?: 0.0) < 0
            val n = catchUp.evaluate(
                NudgeContext(
                    nowMs = nowMs, gapM = if (alongBehind) me.distFromCentreM else null, iAmAhead = false,
                    iAmMoving = iAmMoving, buddyMoving = a.groupMoving, buddyConnected = true, buddyName = "The group",
                ),
            )
            if (n != null) return Nudge(NudgeKind.CatchUp, "The group is a little ahead. A slightly quicker step brings you back together.")
        }
        // 2. I am at the front and others are far back.
        if (role == GroupRole.Leader || (role == null && (me.alongM ?: 0.0) > 0)) {
            val back = a.views.values.filter { it.id != selfId && (it.straggler || (a.sweeperChosen && it.role == GroupRole.Sweeper)) }
            val gapToBack = back.mapNotNull { v -> v.alongM?.let { (me.alongM ?: 0.0) - it } }.maxOrNull()
            // Or I am the one who has drifted far ahead of everybody.
            val gapAhead = if (me.straggler && (me.alongM ?: 0.0) > 0) me.distFromCentreM else null
            val gap = listOfNotNull(gapToBack, gapAhead).maxOrNull()
            val n = easeOff.evaluate(
                NudgeContext(
                    nowMs = nowMs, gapM = gap, iAmAhead = true, iAmMoving = iAmMoving, buddyMoving = a.groupMoving,
                    buddyConnected = true, buddyName = "The group",
                ),
            )
            if (n != null) {
                val k = a.stragglers.count { it != selfId }
                if (k == 0 && gapAhead != null && gapToBack == null) {
                    return Nudge(NudgeKind.EaseOff, "You are a little ahead of the group. An easier pace lets everyone walk together again.")
                }
                val who = if (k == 1) "One person is" else if (k > 1) "$k people are" else "The back of the group is"
                return Nudge(NudgeKind.EaseOff, "$who a little way back. An easier pace lets everyone walk together again.")
            }
        }
        return null
    }
}
