package com.walkbuddy.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.walkbuddy.domain.DayRecord
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.HourSteps
import com.walkbuddy.domain.MoodEntry
import com.walkbuddy.domain.SharedMode
import com.walkbuddy.domain.SharedWalkCodec
import com.walkbuddy.domain.SharedWalkRecord
import com.walkbuddy.domain.StepBaseline
import com.walkbuddy.domain.StepSensorKind
import com.walkbuddy.domain.WalkDate
import com.walkbuddy.domain.WalkRecord

@Entity(tableName = "days")
data class DayEntity(
    @PrimaryKey val epochDay: Long,
    val rawSteps: Int,
    val verifiedSteps: Int,
    val moderateMin: Int,
    val vigorousMin: Int,
    val distanceM: Double,
    val restDay: Boolean,
    @ColumnInfo(defaultValue = "0") val gentle: Boolean = false,
) {
    fun toDomain() = DayRecord(epochDay, rawSteps, verifiedSteps, moderateMin, vigorousMin, distanceM, restDay, gentle)
}

/** The last step-counter reading we accounted for (one row, id 1). Written in the same transaction as the day totals, so a crash can neither lose nor double-count steps. */
@Entity(tableName = "step_state")
data class StepStateEntity(
    @PrimaryKey val id: Int = 1,
    val counter: Long,
    val tMs: Long,
    val bootMs: Long?,
    val kind: String,
) {
    fun toBaseline() = runCatching { StepBaseline(counter, tMs, bootMs, StepSensorKind.valueOf(kind)) }.getOrNull()

    companion object {
        fun of(b: StepBaseline) = StepStateEntity(1, b.counter, b.tMs, b.bootMs, b.kind.name)
    }
}

@Entity(tableName = "hours", primaryKeys = ["epochDay", "hour"])
data class HourEntity(val epochDay: Long, val hour: Int, val steps: Int) {
    fun toDomain() = HourSteps(epochDay, hour, steps)
}

@Entity(tableName = "moods")
data class MoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val atMs: Long,
    val mood: Int,
    val note: String,
    val walkId: Long?,
) {
    fun toDomain() = MoodEntry(id, epochDay, atMs, mood, note, walkId)
}

@Entity(tableName = "badges")
data class BadgeEntity(@PrimaryKey val id: String, val unlockedMs: Long)

@Entity(tableName = "walks")
data class WalkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
) {
    fun toDomain() = WalkRecord(
        id, startMs, durationMs, distanceM, verifiedSteps, rawSteps, moderateMin, vigorousMin,
        hourOfDay, buddyCount, togetherPct, longestTogetherMs, epochDay,
    )
}

@Entity(tableName = "spots")
data class SpotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
) {
    fun toDomain() = FavoriteSpot(name, lat, lon)
}

@Entity(tableName = "walk_dates")
data class DateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val durationMin: Int,
    val weekly: Boolean,
    val title: String,
) {
    fun toDomain() = WalkDate(id, startMs, durationMin, weekly, title)
}

/** A finished shared walk (partner or group) for the "Walks together" history. Lane and sample lists are JSON text, see SharedWalkCodec. */
@Entity(tableName = "shared_walks")
data class SharedWalkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val durationMs: Long,
    val mode: String,
    val memberCount: Int,
    val title: String,
    val lanesJson: String,
    val samplesJson: String,
    val togetherPct: Int,
    val longestTogetherMs: Long,
    val maxGapM: Double,
    val route: String?,
    /** alpha 2.0: a private text note and an app-private photo file name (see WalkMedia). Both optional. */
    val note: String? = null,
    val photo: String? = null,
) {
    fun toDomain() = SharedWalkRecord(
        id = id, startMs = startMs, durationMs = durationMs, mode = SharedMode.fromName(mode), memberCount = memberCount, title = title,
        lanes = SharedWalkCodec.decodeLanes(lanesJson), samples = SharedWalkCodec.decodeSamples(samplesJson),
        togetherPct = togetherPct, longestTogetherMs = longestTogetherMs, maxGapM = maxGapM, routePolyline = route,
    )

    companion object {
        fun of(r: SharedWalkRecord) = SharedWalkEntity(
            id = r.id, startMs = r.startMs, durationMs = r.durationMs, mode = r.mode.name, memberCount = r.memberCount, title = r.title,
            lanesJson = SharedWalkCodec.encodeLanes(r.lanes), samplesJson = SharedWalkCodec.encodeSamples(r.samples),
            togetherPct = r.togetherPct, longestTogetherMs = r.longestTogetherMs, maxGapM = r.maxGapM, route = r.routePolyline,
        )
    }
}

/** A local, shared-goal challenge the person started (alpha 2.0). Progress is computed from the walks history, never stored. */
@Entity(tableName = "challenges")
data class ChallengeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: String,
    val startDay: Long,
    val endDay: Long,
    val completedMs: Long?,
) {
    fun toDomain() = com.walkbuddy.domain.ChallengeInstance(id, templateId, startDay, endDay, completedMs)
}
