package com.walkbuddy.domain

/** Couple mode is for exactly two people. */
object CoupleMode {
    fun applies(totalPeople: Int) = totalPeople == 2
}

data class WalkDate(
    val id: Long,
    val startMs: Long,
    val durationMin: Int = 30,
    val weekly: Boolean = false,
    val title: String = "Walk date",
)

/** What the app hands to the system calendar (ACTION_INSERT intent), so no calendar permission is needed. */
data class CalendarIntentSpec(
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val rrule: String?,
    val description: String,
)

object WalkDatePlanner {
    const val WEEK_MS = 7 * 24 * 3_600_000L

    fun toCalendarIntent(d: WalkDate): CalendarIntentSpec = CalendarIntentSpec(
        title = d.title.ifBlank { "Walk date" }.take(60),
        beginMs = d.startMs,
        endMs = d.startMs + d.durationMin.coerceIn(5, 480) * 60_000L,
        rrule = if (d.weekly) "FREQ=WEEKLY" else null,
        description = "A walk together. Open Walk Buddy to start.",
    )

    /** Next start at or after [nowMs]; weekly dates roll forward by whole weeks. Null if a one-off date has passed. */
    fun nextOccurrence(d: WalkDate, nowMs: Long): Long? {
        if (d.startMs >= nowMs) return d.startMs
        if (!d.weekly) return null
        val weeks = (nowMs - d.startMs + WEEK_MS - 1) / WEEK_MS
        return d.startMs + weeks * WEEK_MS
    }

    fun upcoming(dates: List<WalkDate>, nowMs: Long): List<Pair<WalkDate, Long>> =
        dates.mapNotNull { d -> nextOccurrence(d, nowMs)?.let { d to it } }.sortedBy { it.second }
}

data class TogetherStreakInfo(val daysTogether: Int, val windowDays: Int, val text: String)

object TogetherStreak {
    /** [togetherDays] = epoch days on which a walk with the partner happened. */
    fun lastDays(togetherDays: Set<Long>, todayEpochDay: Long, windowDays: Int = 7): TogetherStreakInfo {
        val n = (0 until windowDays).count { (todayEpochDay - it) in togetherDays }
        val text = if (n == 0) "No shared walks in the last $windowDays days yet. A short one counts." else "Walked together $n of $windowDays days"
        return TogetherStreakInfo(n, windowDays, text)
    }
}

data class Milestone(val name: String, val km: Double, val approximate: Boolean = true) {
    val label: String get() = if (approximate) "$name (about ${km.toInt()} km)" else "$name (${km} km)"
}

data class OdometerState(val totalM: Double, val reached: List<Milestone>, val next: Milestone?, val remainingToNextKm: Double?, val fractionToNext: Double)

/** Shared "our distance" odometer. Road distances are approximate and stated as such. */
object Odometer {
    val MILESTONES: List<Milestone> = listOf(
        Milestone("A full marathon", 42.195, approximate = false),
        Milestone("Bengaluru to Mysuru by road", 140.0),
        Milestone("Mumbai to Pune by road", 150.0),
        Milestone("Chennai to Puducherry by road", 150.0),
        Milestone("Delhi to Agra by road", 230.0),
        Milestone("Delhi to Jaipur by road", 280.0),
        Milestone("Bengaluru to Chennai by road", 350.0),
        Milestone("Mumbai to Goa by road", 590.0),
        Milestone("Delhi to Mumbai by road", 1400.0),
    ).sortedBy { it.km }

    fun state(totalM: Double): OdometerState {
        val km = totalM.coerceAtLeast(0.0) / 1000.0
        val reached = MILESTONES.filter { km >= it.km }
        val next = MILESTONES.firstOrNull { km < it.km }
        val prevKm = reached.lastOrNull()?.km ?: 0.0
        val frac = if (next == null) 1.0 else ((km - prevKm) / (next.km - prevKm)).coerceIn(0.0, 1.0)
        return OdometerState(totalM, reached, next, next?.let { it.km - km }, frac)
    }
}

/** Opt-in "thinking of you" pings. Rate-limited on both the sending and the receiving side. */
class PingLimiter(private val minGapMs: Long = 60_000, private val maxPerHour: Int = 6) {
    private val sent = ArrayDeque<Long>()

    fun tryAcquire(nowMs: Long): Boolean {
        while (sent.isNotEmpty() && nowMs - sent.first() > 3_600_000L) sent.removeFirst()
        val last = sent.lastOrNull()
        if (last != null && nowMs - last < minGapMs) return false
        if (sent.size >= maxPerHour) return false
        sent.addLast(nowMs)
        return true
    }
}

data class FavoriteSpot(val name: String, val lat: Double, val lon: Double)

/** Locally stored favourite spots (shared to a partner only on request, during a session). */
class SpotBook(initial: List<FavoriteSpot> = emptyList(), private val max: Int = 50) {
    private val list = initial.toMutableList()
    val spots: List<FavoriteSpot> get() = list.toList()

    /** Returns false if invalid, a near-duplicate, or the book is full. */
    fun add(s: FavoriteSpot): Boolean {
        val name = MessageCodec.cleanName(s.name, MessageCodec.MAX_SPOT_NAME) ?: return false
        if (!LatLon(s.lat, s.lon).isValid || list.size >= max) return false
        val dup = list.any { it.name.equals(name, ignoreCase = true) && Geo.haversine(LatLon(it.lat, it.lon), LatLon(s.lat, s.lon)) < 25.0 }
        if (dup) return false
        list.add(FavoriteSpot(name, s.lat, s.lon))
        return true
    }

    fun remove(name: String) = list.removeAll { it.name.equals(name, ignoreCase = true) }
}

data class HighlightsCard(val title: String, val lines: List<String>)

data class WalkSummary(
    val durationMs: Long,
    val distanceM: Double,
    val rawSteps: Long,
    val verifiedSteps: Long,
    val avgSpeedMps: Double?,
    val buddyCount: Int,
    val togetherPct: Int?,
    val longestTogetherMs: Long,
    val moderateMin: Int,
    val vigorousMin: Int,
    val nudgesShown: Int,
    val calories: CalorieRange?,
)

object Highlights {
    /** Stats only: no map, no locations. Calories are included only when the caller passes an enabled range. */
    fun build(s: WalkSummary, showCalories: Boolean): HighlightsCard {
        val lines = mutableListOf<String>()
        lines += "${Format.distance(s.distanceM)} in ${Format.duration(s.durationMs)}"
        lines += "${s.verifiedSteps} steps"
        s.avgSpeedMps?.let { lines += "Average pace ${Format.pace(it)}" }
        if (s.togetherPct != null) {
            lines += "Together ${s.togetherPct}% of the walk"
            if (s.longestTogetherMs >= 60_000) lines += "Longest stretch side by side: ${Format.duration(s.longestTogetherMs)}"
        }
        if (s.moderateMin + s.vigorousMin > 0) lines += "${s.moderateMin + s.vigorousMin} active minutes"
        if (showCalories) s.calories?.let { lines += it.label() }
        val title = if (s.buddyCount > 0) "A walk together" else "A good walk"
        return HighlightsCard(title, lines)
    }
}
