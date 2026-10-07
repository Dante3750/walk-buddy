package com.walkbuddy.data

import com.walkbuddy.domain.CsvExport
import com.walkbuddy.domain.DayRecord
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.WalkDate
import com.walkbuddy.domain.WalkRecord
import com.walkbuddy.domain.WalkSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SpotRow(val id: Long, val spot: FavoriteSpot)

object Clock {
    fun epochDay(ms: Long = System.currentTimeMillis()): Long =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    fun hourOfDay(ms: Long = System.currentTimeMillis()): Int =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).hour

    fun today(): Long = LocalDate.now().toEpochDay()
}

/** Everything lives in a local Room database. Nothing here talks to a network. */
class AppRepository(private val db: AppDatabase, val settings: SettingsStore) {
    private val dao = db.dao()

    val days: Flow<List<DayRecord>> = dao.days().map { l -> l.map { it.toDomain() } }
    val walks: Flow<List<WalkRecord>> = dao.walks().map { l -> l.map { it.toDomain() } }
    val spots: Flow<List<SpotRow>> = dao.spots().map { l -> l.map { SpotRow(it.id, it.toDomain()) } }
    val dates: Flow<List<WalkDate>> = dao.dates().map { l -> l.map { it.toDomain() } }
    val coupleDistanceM: Flow<Double> = dao.coupleDistanceM()
    val coupleDays: Flow<Set<Long>> = dao.coupleWalkDays().map { it.toSet() }

    suspend fun addSteps(epochDay: Long, raw: Long, verified: Long) {
        if (raw <= 0 && verified <= 0) return
        val d = dao.day(epochDay) ?: DayEntity(epochDay, 0, 0, 0, 0, 0.0, false)
        dao.upsertDay(d.copy(rawSteps = (d.rawSteps + raw).toInt(), verifiedSteps = (d.verifiedSteps + verified).toInt()))
    }

    suspend fun setRestDay(epochDay: Long, rest: Boolean) {
        val d = dao.day(epochDay) ?: DayEntity(epochDay, 0, 0, 0, 0, 0.0, false)
        dao.upsertDay(d.copy(restDay = rest))
    }

    suspend fun day(epochDay: Long): DayRecord? = dao.day(epochDay)?.toDomain()

    /** Steps are added to the day incrementally during the walk; here we add distance and active minutes and store the walk. */
    suspend fun saveWalk(s: WalkSummary, startMs: Long): Long {
        val day = Clock.epochDay(startMs)
        val id = dao.insertWalk(
            WalkEntity(
                startMs = startMs, durationMs = s.durationMs, distanceM = s.distanceM, verifiedSteps = s.verifiedSteps.toInt(),
                rawSteps = s.rawSteps.toInt(), moderateMin = s.moderateMin, vigorousMin = s.vigorousMin,
                hourOfDay = Clock.hourOfDay(startMs), buddyCount = s.buddyCount, togetherPct = s.togetherPct,
                longestTogetherMs = s.longestTogetherMs, epochDay = day,
            )
        )
        val d = dao.day(day) ?: DayEntity(day, 0, 0, 0, 0, 0.0, false)
        dao.upsertDay(d.copy(moderateMin = d.moderateMin + s.moderateMin, vigorousMin = d.vigorousMin + s.vigorousMin, distanceM = d.distanceM + s.distanceM))
        return id
    }

    suspend fun addSpot(s: FavoriteSpot): Boolean {
        val existing = dao.spotsOnce().map { it.toDomain() }
        val book = com.walkbuddy.domain.SpotBook(existing)
        if (!book.add(s)) return false
        dao.insertSpot(SpotEntity(name = book.spots.last().name, lat = s.lat, lon = s.lon))
        return true
    }

    suspend fun deleteSpot(id: Long) = dao.deleteSpot(id)

    suspend fun addDate(d: WalkDate) {
        dao.insertDate(DateEntity(startMs = d.startMs, durationMin = d.durationMin, weekly = d.weekly, title = d.title))
    }

    suspend fun deleteDate(id: Long) = dao.deleteDate(id)

    /** One text file with three CSV sections. */
    suspend fun exportCsv(): String = buildString {
        append("# walk-buddy export (your own data)\r\n# days\r\n")
        append(CsvExport.days(dao.daysOnce().map { it.toDomain() }))
        append("\r\n# walks\r\n")
        append(CsvExport.walks(dao.walksOnce().map { it.toDomain() }))
        append("\r\n# favorite spots\r\n")
        append(CsvExport.spots(dao.spotsOnce().map { it.toDomain() }))
    }

    /** Delete-all: every table and every preference. */
    suspend fun deleteAll() {
        dao.clearDays(); dao.clearWalks(); dao.clearSpots(); dao.clearDates()
        settings.clearAll()
    }
}
