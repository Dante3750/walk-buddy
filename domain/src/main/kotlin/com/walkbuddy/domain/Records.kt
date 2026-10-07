package com.walkbuddy.domain

/** One calendar day of own data. [epochDay] is days since 1970-01-01 in the user's local calendar. */
data class DayRecord(
    val epochDay: Long,
    val rawSteps: Int = 0,
    val verifiedSteps: Int = 0,
    val moderateMin: Int = 0,
    val vigorousMin: Int = 0,
    val distanceM: Double = 0.0,
    val restDay: Boolean = false,
) {
    /** Moderate-equivalent minutes: vigorous minutes count double, the usual convention in activity guidance. */
    val activeEquivMin: Int get() = moderateMin + 2 * vigorousMin
}

/** One finished walk. [buddyCount] excludes me; [togetherPct] is null for solo walks. */
data class WalkRecord(
    val id: Long,
    val startMs: Long,
    val durationMs: Long,
    val distanceM: Double,
    val verifiedSteps: Int,
    val rawSteps: Int,
    val moderateMin: Int,
    val vigorousMin: Int,
    val hourOfDay: Int,
    val buddyCount: Int,
    val togetherPct: Int?,
    val longestTogetherMs: Long,
    val epochDay: Long,
)
