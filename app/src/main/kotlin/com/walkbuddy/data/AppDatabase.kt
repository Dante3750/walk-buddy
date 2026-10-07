package com.walkbuddy.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

    @Insert
    suspend fun insertDate(d: DateEntity): Long

    @Query("DELETE FROM walk_dates WHERE id = :id")
    suspend fun deleteDate(id: Long)

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
    entities = [DayEntity::class, WalkEntity::class, SpotEntity::class, DateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "walkbuddy.db").build()
    }
}
