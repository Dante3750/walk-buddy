package com.walkbuddy.data

import androidx.room.withTransaction
import com.walkbuddy.domain.CsvExport
import com.walkbuddy.domain.DaySteps
import com.walkbuddy.domain.StepBaseline
import com.walkbuddy.domain.DayRecord
import com.walkbuddy.domain.BadgeId
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.HourSteps
import com.walkbuddy.domain.MoodEntry
import com.walkbuddy.domain.MoodNote
import com.walkbuddy.domain.SharedWalkRecord
import com.walkbuddy.domain.WalkDate
import com.walkbuddy.domain.WalkRecord
import com.walkbuddy.domain.WalkSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    val sharedWalks: Flow<List<SharedWalkRecord>> = dao.sharedWalks().map { l -> l.map { it.toDomain() } }
    val challenges: Flow<List<com.walkbuddy.domain.ChallengeInstance>> = dao.challenges().map { l -> l.map { it.toDomain() } }
    val coupleDistanceM: Flow<Double> = dao.coupleDistanceM()
    val coupleDays: Flow<Set<Long>> = dao.coupleWalkDays().map { it.toSet() }
    val hours: Flow<List<HourSteps>> = dao.hours().map { l -> l.map { it.toDomain() } }
    val moods: Flow<List<MoodEntry>> = dao.moods().map { l -> l.map { it.toDomain() } }
    val badges: Flow<Map<BadgeId, Long>> = dao.badges().map { l ->
        l.mapNotNull { e -> runCatching { BadgeId.valueOf(e.id) }.getOrNull()?.let { it to e.unlockedMs } }.toMap()
    }

    /** [hour] (0..23) also feeds the hour-by-hour histogram. */
    suspend fun addSteps(epochDay: Long, raw: Long, verified: Long, hour: Int? = null) {
        if (raw <= 0 && verified <= 0) return
        val d = dao.day(epochDay) ?: DayEntity(epochDay, 0, 0, 0, 0, 0.0, false)
        dao.upsertDay(d.copy(rawSteps = (d.rawSteps + raw).toInt(), verifiedSteps = (d.verifiedSteps + verified).toInt()))
        if (hour != null && verified > 0) {
            val v = verified.toInt()
            if (dao.bumpHour(epochDay, hour, v) == 0) dao.putHour(HourEntity(epochDay, hour, v))
        }
    }

    /** Day totals and the counter baseline in ONE transaction: a crash can neither lose nor double-count steps. */
    suspend fun applyStepUpdate(parts: List<DaySteps>, baseline: StepBaseline) {
        db.withTransaction {
            parts.forEach { addSteps(it.epochDay, it.raw, it.verified, it.hour) }
            dao.putStepState(StepStateEntity.of(baseline))
        }
    }

    suspend fun stepBaseline(): StepBaseline? = dao.stepState()?.toBaseline()

    val stepState: Flow<StepStateEntity?> = dao.stepStateFlow()

    fun verifiedStepsOn(epochDay: Long): Flow<Int> = dao.verifiedStepsFlow(epochDay).map { it ?: 0 }

    suspend fun setGentle(epochDay: Long, gentle: Boolean) {
        val d = dao.day(epochDay) ?: DayEntity(epochDay, 0, 0, 0, 0, 0.0, false)
        dao.upsertDay(d.copy(gentle = gentle))
    }

    suspend fun addMood(mood: Int, note: String, walkId: Long?, nowMs: Long = System.currentTimeMillis()): Long =
        dao.insertMood(MoodEntity(epochDay = Clock.epochDay(nowMs), atMs = nowMs, mood = mood.coerceIn(1, 5), note = MoodNote.clean(note), walkId = walkId))

    suspend fun deleteMood(id: Long) = dao.deleteMood(id)

    suspend fun recordBadges(ids: Collection<BadgeId>, nowMs: Long = System.currentTimeMillis()) {
        ids.forEach { dao.insertBadge(BadgeEntity(it.name, nowMs)) }
    }

    suspend fun setRestDay(epochDay: Long, rest: Boolean) {
        val d = dao.day(epochDay) ?: DayEntity(epochDay, 0, 0, 0, 0, 0.0, false)
        dao.upsertDay(d.copy(restDay = rest))
    }

    suspend fun day(epochDay: Long): DayRecord? = dao.day(epochDay)?.toDomain()

    suspend fun coupleDistanceNow(): Double = dao.coupleDistanceOnce()

    suspend fun datesOnce(): List<WalkDate> = dao.datesOnce().map { it.toDomain() }

    suspend fun allDays(): List<DayRecord> = dao.daysOnce().map { it.toDomain() }

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

    /** Stores a finished shared walk for the history. Returns its id. */
    suspend fun saveSharedWalk(r: SharedWalkRecord): Long = dao.insertSharedWalk(SharedWalkEntity.of(r.copy(id = 0)))

    suspend fun sharedWalk(id: Long): SharedWalkRecord? = dao.sharedWalk(id)?.toDomain()

    suspend fun sharedWalksOnce(): List<SharedWalkRecord> = dao.sharedWalksOnce().map { it.toDomain() }

    suspend fun deleteSharedWalk(id: Long) = dao.deleteSharedWalk(id)

    suspend fun clearSharedWalks() = dao.clearSharedWalks()

    /** The private note and photo file name of a shared walk (alpha 2.0). */
    suspend fun sharedExtras(id: Long): Pair<String?, String?> = dao.sharedWalk(id).let { it?.note to it?.photo }

    suspend fun setSharedNote(id: Long, note: String?) = dao.setSharedNote(id, com.walkbuddy.domain.WalkNotes.clean(note))

    suspend fun setSharedPhoto(id: Long, photo: String?) = dao.setSharedPhoto(id, photo)

    suspend fun photoFiles(): List<String> = dao.allPhotos()

    suspend fun startChallenge(templateId: String, today: Long = Clock.today()): Boolean {
        val current = dao.challenges().first().map { it.toDomain() }
        if (!com.walkbuddy.domain.Challenges.canStart(templateId, current, today)) return false
        val i = com.walkbuddy.domain.Challenges.newInstance(templateId, today) ?: return false
        dao.insertChallenge(ChallengeEntity(templateId = i.templateId, startDay = i.startDay, endDay = i.endDay, completedMs = null))
        return true
    }

    suspend fun completeChallenge(id: Long, ms: Long = System.currentTimeMillis()) = dao.completeChallenge(id, ms)

    suspend fun deleteChallenge(id: Long) = dao.deleteChallenge(id)

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
        append("\r\n# mood check-ins\r\n")
        append(CsvExport.moods(dao.moodsOnce().map { it.toDomain() }))
    }

    /** Delete-all: every table and every preference. */
    suspend fun deleteAll() {
        dao.clearDays(); dao.clearStepState(); dao.clearWalks(); dao.clearSpots(); dao.clearDates(); dao.clearHours(); dao.clearMoods(); dao.clearBadges(); dao.clearSharedWalks(); dao.clearChallenges()
        settings.clearAll()
    }
}
