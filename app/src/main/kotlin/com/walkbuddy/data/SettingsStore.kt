package com.walkbuddy.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.walkbuddy.domain.BodyProfile
import com.walkbuddy.domain.Diet
import com.walkbuddy.domain.LocationPrecision
import com.walkbuddy.domain.QuietHours
import com.walkbuddy.domain.Sex
import com.walkbuddy.domain.StepLength
import com.walkbuddy.domain.UnitSystem
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "walkbuddy_settings")

enum class GoalMode { Adaptive, Fixed }

data class Settings(
    val onboardingDone: Boolean = false,
    val displayName: String = "",
    val peerId: String = "",
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val sex: Sex = Sex.Unspecified,
    val goalMode: GoalMode = GoalMode.Adaptive,
    val fixedGoal: Int = 6000,
    val restWeekdays: Set<Int> = emptySet(),
    val caloriesEnabled: Boolean = false,
    val calibratedStepLengthM: Double? = null,
    val foodEnabled: Boolean = false,
    val diet: Diet = Diet.Vegetarian,
    val radiusM: Int = 50,
    val quietByDefault: Boolean = false,
    val paceSync: Boolean = false,
    val pingEnabled: Boolean = false,
    val sittingReminders: Boolean = false,
    val healthConnectOn: Boolean = false,
    // ---- alpha 1.1 ----
    val unitSystem: UnitSystem = UnitSystem.Metric,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
    val quietEnabled: Boolean = false,
    val quietFromHour: Int = 22,
    val quietToHour: Int = 7,
    val dynamicColor: Boolean = false,
    val anniversaryDate: String = "",
    val anniversaryLabel: String = "",
    val demoMode: Boolean = false,
    val celebratedDay: Long = -1,
    val buddyName: String = "",
    val buddySteps: Int = 0,
    val buddyGoal: Int = 0,
    val buddyDay: Long = -1,
    val remindersSent: String = "",
    val recapSeenDay: Long = -1,
    // ---- alpha 1.3 ----
    /** How precisely this phone shares its position in an open group (partner walks are always exact). */
    val groupPrecision: LocationPrecision = LocationPrecision.Exact,
    /** Opt-in OpenStreetMap tile layer under the offline map. Off by default: tile requests reveal the area you view. */
    val mapTiles: Boolean = false,
    /** Opt-in: keep my own route of each walk as a GPX file on this phone. */
    val saveRoutes: Boolean = false,
) {
    val profile: BodyProfile get() = BodyProfile(heightCm, weightKg, sex)
    val quietHours: QuietHours get() = QuietHours(quietEnabled, quietFromHour, quietToHour)

    /** Calibrated length, else from height, else a typical stride. */
    val stepLengthM: Double get() = calibratedStepLengthM ?: StepLength.fromHeight(heightCm, sex) ?: StepLength.DEFAULT_M
}

/** Plain app-private DataStore. Nothing here leaves the device except what the user explicitly shares in a session. */
class SettingsStore(private val context: Context) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val name = stringPreferencesKey("display_name")
        val peerId = stringPreferencesKey("peer_id")
        val height = floatPreferencesKey("height_cm")
        val weight = floatPreferencesKey("weight_kg")
        val sex = stringPreferencesKey("sex")
        val goalMode = stringPreferencesKey("goal_mode")
        val fixedGoal = intPreferencesKey("fixed_goal")
        val rest = stringPreferencesKey("rest_weekdays")
        val calories = booleanPreferencesKey("calories_enabled")
        val stepLen = floatPreferencesKey("step_length_m")
        val food = booleanPreferencesKey("food_enabled")
        val diet = stringPreferencesKey("diet")
        val radius = intPreferencesKey("radius_m")
        val quiet = booleanPreferencesKey("quiet_default")
        val paceSync = booleanPreferencesKey("pace_sync")
        val ping = booleanPreferencesKey("ping_enabled")
        val sitting = booleanPreferencesKey("sitting_reminders")
        val hc = booleanPreferencesKey("health_connect")
        val units = stringPreferencesKey("units")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val haptics = booleanPreferencesKey("haptics")
        val quietOn = booleanPreferencesKey("quiet_hours_on")
        val quietFrom = intPreferencesKey("quiet_from")
        val quietTo = intPreferencesKey("quiet_to")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val annivDate = stringPreferencesKey("anniv_date")
        val annivLabel = stringPreferencesKey("anniv_label")
        val demo = booleanPreferencesKey("demo_mode")
        val celebrated = longPreferencesKey("celebrated_day")
        val buddyName = stringPreferencesKey("buddy_name")
        val buddySteps = intPreferencesKey("buddy_steps")
        val buddyGoal = intPreferencesKey("buddy_goal")
        val buddyDay = longPreferencesKey("buddy_day")
        val remindersSent = stringPreferencesKey("reminders_sent")
        val recapSeen = longPreferencesKey("recap_seen_day")
        val groupPrecision = stringPreferencesKey("group_precision")
        val mapTiles = booleanPreferencesKey("map_tiles")
        val saveRoutes = booleanPreferencesKey("save_routes")
        // Background step bookkeeping
        val lastCounter = longPreferencesKey("last_counter")
        val lastCounterT = longPreferencesKey("last_counter_t")
        val sitState = stringPreferencesKey("sit_state")
    }

    private val data: Flow<Preferences> get() = context.settingsDataStore.data

    val settings: Flow<Settings> = data.map { p ->
        Settings(
            onboardingDone = p[K.onboarding] ?: false,
            displayName = p[K.name].orEmpty(),
            peerId = p[K.peerId].orEmpty(),
            heightCm = p[K.height]?.toDouble(),
            weightKg = p[K.weight]?.toDouble(),
            sex = runCatching { Sex.valueOf(p[K.sex] ?: "") }.getOrDefault(Sex.Unspecified),
            goalMode = runCatching { GoalMode.valueOf(p[K.goalMode] ?: "") }.getOrDefault(GoalMode.Adaptive),
            fixedGoal = p[K.fixedGoal] ?: 6000,
            restWeekdays = (p[K.rest] ?: "").split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet(),
            caloriesEnabled = p[K.calories] ?: false,
            calibratedStepLengthM = p[K.stepLen]?.toDouble(),
            foodEnabled = p[K.food] ?: false,
            diet = runCatching { Diet.valueOf(p[K.diet] ?: "") }.getOrDefault(Diet.Vegetarian),
            radiusM = p[K.radius] ?: 50,
            quietByDefault = p[K.quiet] ?: false,
            paceSync = p[K.paceSync] ?: false,
            pingEnabled = p[K.ping] ?: false,
            sittingReminders = p[K.sitting] ?: false,
            healthConnectOn = p[K.hc] ?: false,
            unitSystem = runCatching { UnitSystem.valueOf(p[K.units] ?: "") }.getOrDefault(UnitSystem.Metric),
            reduceMotion = p[K.reduceMotion] ?: false,
            haptics = p[K.haptics] ?: true,
            quietEnabled = p[K.quietOn] ?: false,
            quietFromHour = (p[K.quietFrom] ?: 22).coerceIn(0, 23),
            quietToHour = (p[K.quietTo] ?: 7).coerceIn(0, 23),
            dynamicColor = p[K.dynamic] ?: false,
            anniversaryDate = p[K.annivDate].orEmpty(),
            anniversaryLabel = p[K.annivLabel].orEmpty(),
            demoMode = p[K.demo] ?: false,
            celebratedDay = p[K.celebrated] ?: -1,
            buddyName = p[K.buddyName].orEmpty(),
            buddySteps = p[K.buddySteps] ?: 0,
            buddyGoal = p[K.buddyGoal] ?: 0,
            buddyDay = p[K.buddyDay] ?: -1,
            remindersSent = p[K.remindersSent].orEmpty(),
            recapSeenDay = p[K.recapSeen] ?: -1,
            groupPrecision = LocationPrecision.fromName(p[K.groupPrecision]),
            mapTiles = p[K.mapTiles] ?: false,
            saveRoutes = p[K.saveRoutes] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    /** A random id for this install, used only to tell peers apart inside a session. */
    suspend fun ensurePeerId(): String {
        val existing = data.first()[K.peerId]
        if (!existing.isNullOrBlank()) return existing
        val id = "p" + UUID.randomUUID().toString().replace("-", "").take(12)
        context.settingsDataStore.edit { it[K.peerId] = id }
        return id
    }


    suspend fun setOnboardingDone() = context.settingsDataStore.edit { it[K.onboarding] = true }.let { }
    suspend fun setName(v: String) = context.settingsDataStore.edit { it[K.name] = v.take(24) }.let { }
    suspend fun setBody(heightCm: Double?, weightKg: Double?, sex: Sex) {
        context.settingsDataStore.edit {
            if (heightCm == null) it.remove(K.height) else it[K.height] = heightCm.toFloat()
            if (weightKg == null) it.remove(K.weight) else it[K.weight] = weightKg.toFloat()
            it[K.sex] = sex.name
        }
    }
    suspend fun setGoal(mode: GoalMode, fixed: Int) {
        context.settingsDataStore.edit { it[K.goalMode] = mode.name; it[K.fixedGoal] = fixed }
    }
    suspend fun setRestWeekdays(days: Set<Int>) = context.settingsDataStore.edit { it[K.rest] = days.sorted().joinToString(",") }.let { }
    suspend fun setCalories(on: Boolean) = context.settingsDataStore.edit { it[K.calories] = on }.let { }
    suspend fun setStepLength(m: Double?) {
        context.settingsDataStore.edit {
            if (m == null) {
                it.remove(K.stepLen)
            } else {
                it[K.stepLen] = m.toFloat()
            }
        }
    }
    suspend fun setFood(on: Boolean) = context.settingsDataStore.edit { it[K.food] = on }.let { }
    suspend fun setDiet(d: Diet) = context.settingsDataStore.edit { it[K.diet] = d.name }.let { }
    suspend fun setRadius(m: Int) = context.settingsDataStore.edit { it[K.radius] = m.coerceIn(10, 500) }.let { }
    suspend fun setQuietDefault(on: Boolean) = context.settingsDataStore.edit { it[K.quiet] = on }.let { }
    suspend fun setPaceSync(on: Boolean) = context.settingsDataStore.edit { it[K.paceSync] = on }.let { }
    suspend fun setPing(on: Boolean) = context.settingsDataStore.edit { it[K.ping] = on }.let { }
    suspend fun setSitting(on: Boolean) = context.settingsDataStore.edit { it[K.sitting] = on }.let { }
    suspend fun setHealthConnect(on: Boolean) = context.settingsDataStore.edit { it[K.hc] = on }.let { }

    suspend fun setUnits(u: UnitSystem) = context.settingsDataStore.edit { it[K.units] = u.name }.let { }
    suspend fun setReduceMotion(on: Boolean) = context.settingsDataStore.edit { it[K.reduceMotion] = on }.let { }
    suspend fun setHaptics(on: Boolean) = context.settingsDataStore.edit { it[K.haptics] = on }.let { }
    suspend fun setQuietHours(on: Boolean, from: Int, to: Int) {
        context.settingsDataStore.edit { it[K.quietOn] = on; it[K.quietFrom] = from.coerceIn(0, 23); it[K.quietTo] = to.coerceIn(0, 23) }
    }
    suspend fun setDynamicColor(on: Boolean) = context.settingsDataStore.edit { it[K.dynamic] = on }.let { }
    suspend fun setAnniversary(isoDate: String, label: String) {
        context.settingsDataStore.edit { it[K.annivDate] = isoDate.trim().take(10); it[K.annivLabel] = label.trim().take(30) }
    }
    suspend fun setDemoMode(on: Boolean) = context.settingsDataStore.edit { it[K.demo] = on }.let { }
    suspend fun setCelebratedDay(day: Long) = context.settingsDataStore.edit { it[K.celebrated] = day }.let { }
    suspend fun setGroupPrecision(p: LocationPrecision) = context.settingsDataStore.edit { it[K.groupPrecision] = p.name }.let { }
    suspend fun setMapTiles(on: Boolean) = context.settingsDataStore.edit { it[K.mapTiles] = on }.let { }
    suspend fun setSaveRoutes(on: Boolean) = context.settingsDataStore.edit { it[K.saveRoutes] = on }.let { }
    suspend fun setRecapSeen(day: Long) = context.settingsDataStore.edit { it[K.recapSeen] = day }.let { }

    /** The buddy's progress as last seen during a walk. Only used to place their avatar on today's ring. */
    suspend fun saveBuddyDaily(name: String, steps: Int, goal: Int, day: Long) {
        context.settingsDataStore.edit {
            it[K.buddyName] = name.take(24); it[K.buddySteps] = steps; it[K.buddyGoal] = goal; it[K.buddyDay] = day
        }
    }

    suspend fun rememberReminders(keys: Collection<String>) {
        context.settingsDataStore.edit {
            val old = (it[K.remindersSent] ?: "").split('|').filter { k -> k.isNotBlank() }
            it[K.remindersSent] = (old + keys).takeLast(30).joinToString("|")
        }
    }

    // ---- background step bookkeeping ----
    suspend fun counterState(): Pair<Long?, Long?> {
        val p = data.first()
        return p[K.lastCounter] to p[K.lastCounterT]
    }

    suspend fun saveCounterState(counter: Long, tMs: Long) {
        context.settingsDataStore.edit { it[K.lastCounter] = counter; it[K.lastCounterT] = tMs }
    }

    suspend fun sitState(): LongArray? = data.first()[K.sitState]?.split(',')?.mapNotNull { it.toLongOrNull() }?.toLongArray()

    suspend fun saveSitState(s: LongArray) {
        context.settingsDataStore.edit { it[K.sitState] = s.joinToString(",") }
    }

    /** Delete-all: wipes every preference, including the install id. */
    suspend fun clearAll() {
        context.settingsDataStore.edit { it.clear() }
    }
}
