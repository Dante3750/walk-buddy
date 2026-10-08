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
    entities = [DayEntity::class, WalkEntity::class, SpotEntity::class, DateEntity::class, HourEntity::class, MoodEntity::class, BadgeEntity::class],
    version = 2,
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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "walkbuddy.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
