package com.walkbuddy.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Real Health Connect bridge. Only compiled with -PhealthConnect=true (see app/build.gradle.kts).
 * Written from memory of the connect-client 1.1.0-alpha API: UNVERIFIED until built.
 */
class HealthConnectBridge(private val context: Context) : HealthBridge {
    private val client: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null
    }

    private val wanted = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
    )

    override suspend fun isAvailable() = client != null

    override fun permissions(): Set<String> = wanted

    override suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return c.permissionController.getGrantedPermissions().containsAll(wanted)
    }

    override suspend fun readTodaySteps(): Long? {
        val c = client ?: return null
        if (!hasPermissions()) return null
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant()
        val res = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, Instant.now())))
        return res[StepsRecord.COUNT_TOTAL]
    }

    override suspend fun writeWalk(startMs: Long, endMs: Long, steps: Long, distanceM: Double): Boolean {
        val c = client ?: return false
        if (!hasPermissions()) return false
        val start = Instant.ofEpochMilli(startMs)
        val end = Instant.ofEpochMilli(endMs)
        val zone = ZoneId.systemDefault().rules.getOffset(start)
        return try {
            c.insertRecords(
                listOf(
                    ExerciseSessionRecord(
                        startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
                        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING, title = "Walk Buddy walk",
                        metadata = Metadata(clientRecordId = com.walkbuddy.domain.WalkExport.clientId(startMs)),
                    ),
                    DistanceRecord(
                        startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone,
                        distance = Length.meters(distanceM),
                    ),
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
