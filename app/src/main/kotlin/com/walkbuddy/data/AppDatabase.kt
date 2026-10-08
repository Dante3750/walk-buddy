package com.walkbuddy.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // days
    @Query("SELECT * FROM days ORDER BY epochDay")
    fun days(): Flow<List<DayEntity>>

    @Query("SELECT * FROM days ORDER BY epochDay")
    suspend fun daysOnce(): List<DayEntity>

    @Query("SELECT * FROM days WHERE epochDay = :day")
    suspend fun day(day: Long): DayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(d: DayEntity)

    // walks
    @Query("SELECT * FROM walks ORDER BY startMs DESC")
    fun walks(): Flow<List<WalkEntity>>

    @Query("SELECT * FROM walks ORDER BY startMs")
    suspend fun walksOnce(): List<WalkEntity>

    @Insert
    suspend fun insertWalk(w: WalkEntity): Long

    @Query("SELECT COALESCE(SUM(distanceM), 0.0) FROM walks WHERE buddyCount = 1")
    fun coupleDistanceM(): Flow<Double>

    @Query("SELECT COALESCE(SUM(distanceM), 0.0) FROM walks WHERE buddyCount = 1")
    suspend fun coupleDistanceOnce(): Double

    @Query("SELECT DISTINCT epochDay FROM walks WHERE buddyCount = 1")
    fun coupleWalkDays(): Flow<List<Long>>

    // spots
    @Query("SELECT * FROM spots ORDER BY id")
    fun spots(): Flow<List<SpotEntity>>

    @Query("SELECT * FROM spots ORDER BY id")
    suspend fun spotsOnce(): List<SpotEntity>

    @Insert
    suspend fun insertSpot(s: SpotEntity): Long

    @Query("DELETE FROM spots WHERE id = :id")
    suspend fun deleteSpot(id: Long)

    // walk dates
    @Query("SELECT * FROM walk_dates ORDER BY startMs")
    fun dates(): Flow<List<DateEntity>>

    @Query("SELECT * FROM walk_dates ORDER BY startMs")
    suspend fun datesOnce(): List<DateEntity>

    @Insert
    suspend fun insertDate(d: DateEntity): Long

    @Query("DELETE FROM walk_dates WHERE id = :id")
    suspend fun deleteDate(id: Long)

    // step counter baseline
    @Query("SELECT * FROM step_state WHERE id = 1")
    suspend fun stepState(): StepStateEntity?

    @Query("SELECT * FROM step_state WHERE id = 1")
    fun stepStateFlow(): Flow<StepStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putStepState(s: StepStateEntity)

    @Query("DELETE FROM step_state")
    suspend fun clearStepState()

    @Query("SELECT verifiedSteps FROM days WHERE epochDay = :day")
    fun verifiedStepsFlow(day: Long): Flow<Int?>

    // hours
    @Query("SELECT * FROM hours")
    fun hours(): Flow<List<HourEntity>>

    @Query("UPDATE hours SET steps = steps + :steps WHERE epochDay = :day AND hour = :hour")
    suspend fun bumpHour(day: Long, hour: Int, steps: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHour(h: HourEntity)

    // moods
    @Query("SELECT * FROM moods ORDER BY atMs DESC")
    fun moods(): Flow<List<MoodEntity>>

    @Query("SELECT * FROM moods ORDER BY atMs")
    suspend fun moodsOnce(): List<MoodEntity>

    @Insert
    suspend fun insertMood(m: MoodEntity): Long

    @Query("DELETE FROM moods WHERE id = :id")
    suspend fun deleteMood(id: Long)

    // badges
    @Query("SELECT * FROM badges")
    fun badges(): Flow<List<BadgeEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBadge(b: BadgeEntity)

    // shared walks (history of partner and group walks)
    @Query("SELECT * FROM shared_walks ORDER BY startMs DESC")
    fun sharedWalks(): Flow<List<SharedWalkEntity>>

    @Query("SELECT * FROM shared_walks ORDER BY startMs")
    suspend fun sharedWalksOnce(): List<SharedWalkEntity>

    @Query("SELECT * FROM shared_walks WHERE id = :id")
    suspend fun sharedWalk(id: Long): SharedWalkEntity?

    @Insert
    suspend fun insertSharedWalk(w: SharedWalkEntity): Long

    @Query("DELETE FROM shared_walks WHERE id = :id")
    suspend fun deleteSharedWalk(id: Long)

    @Query("DELETE FROM shared_walks")
    suspend fun clearSharedWalks()

    @Query("DELETE FROM hours")
    suspend fun clearHours()

    @Query("DELETE FROM moods")
    suspend fun clearMoods()

    @Query("DELETE FROM badges")
    suspend fun clearBadges()

    @Query("DELETE FROM days")
    suspend fun clearDays()

    @Query("DELETE FROM walks")
    suspend fun clearWalks()

    @Query("DELETE FROM spots")
    suspend fun clearSpots()

    @Query("DELETE FROM walk_dates")
    suspend fun clearDates()
}

@Database(
    entities = [DayEntity::class, WalkEntity::class, SpotEntity::class, DateEntity::class, HourEntity::class, MoodEntity::class, BadgeEntity::class, StepStateEntity::class, SharedWalkEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /** alpha to alpha 1.1: gentle days, hour-by-hour steps, mood check-ins and unlocked badges. Existing data is kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE days ADD COLUMN gentle INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS hours (epochDay INTEGER NOT NULL, hour INTEGER NOT NULL, steps INTEGER NOT NULL, PRIMARY KEY(epochDay, hour))")
                db.execSQL("CREATE TABLE IF NOT EXISTS moods (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, epochDay INTEGER NOT NULL, atMs INTEGER NOT NULL, mood INTEGER NOT NULL, note TEXT NOT NULL, walkId INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS badges (id TEXT NOT NULL, unlockedMs INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }

        /** alpha 1.5 to 1.6: the step-counter baseline moves into the database. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS step_state (id INTEGER NOT NULL, counter INTEGER NOT NULL, tMs INTEGER NOT NULL, bootMs INTEGER, kind TEXT NOT NULL, PRIMARY KEY(id))")
            }
        }

        /** alpha 1.7 to 1.8: the "Walks together" history. A new table only, so nothing existing is touched. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_SHARED_WALKS)
            }
        }

        /** Kept as a constant so a test can check it against the entity. Column types match what Room expects for SharedWalkEntity. */
        const val CREATE_SHARED_WALKS =
            "CREATE TABLE IF NOT EXISTS shared_walks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startMs INTEGER NOT NULL, " +
                "durationMs INTEGER NOT NULL, mode TEXT NOT NULL, memberCount INTEGER NOT NULL, title TEXT NOT NULL, " +
                "lanesJson TEXT NOT NULL, samplesJson TEXT NOT NULL, togetherPct INTEGER NOT NULL, longestTogetherMs INTEGER NOT NULL, " +
                "maxGapM REAL NOT NULL, route TEXT)"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "walkbuddy.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
