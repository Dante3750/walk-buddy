package com.walkbuddy.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.walkbuddy.domain.DayRecord
import com.walkbuddy.domain.FavoriteSpot
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
) {
    fun toDomain() = DayRecord(epochDay, rawSteps, verifiedSteps, moderateMin, vigorousMin, distanceM, restDay)
}

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
