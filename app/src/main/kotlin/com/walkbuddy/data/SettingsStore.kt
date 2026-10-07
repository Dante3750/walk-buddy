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
import com.walkbuddy.domain.Sex
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
    val serverUrl: String = "",
    val radiusM: Int = 50,
    val quietByDefault: Boolean = false,
    val paceSync: Boolean = false,
    val pingEnabled: Boolean = false,
    val sittingReminders: Boolean = false,
    val healthConnectOn: Boolean = false,
) {
    val profile: BodyProfile get() = BodyProfile(heightCm, weightKg, sex)
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
        val server = stringPreferencesKey("server_url")
        val radius = intPreferencesKey("radius_m")
        val quiet = booleanPreferencesKey("quiet_default")
        val paceSync = booleanPreferencesKey("pace_sync")
        val ping = booleanPreferencesKey("ping_enabled")
        val sitting = booleanPreferencesKey("sitting_reminders")
        val hc = booleanPreferencesKey("health_connect")
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
            serverUrl = p[K.server].orEmpty(),
            radiusM = p[K.radius] ?: 50,
            quietByDefault = p[K.quiet] ?: false,
            paceSync = p[K.paceSync] ?: false,
            pingEnabled = p[K.ping] ?: false,
            sittingReminders = p[K.sitting] ?: false,
            healthConnectOn = p[K.hc] ?: false,
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
    suspend fun setServer(url: String) = context.settingsDataStore.edit { it[K.server] = url.trim() }.let { }
    suspend fun setRadius(m: Int) = context.settingsDataStore.edit { it[K.radius] = m.coerceIn(10, 500) }.let { }
    suspend fun setQuietDefault(on: Boolean) = context.settingsDataStore.edit { it[K.quiet] = on }.let { }
    suspend fun setPaceSync(on: Boolean) = context.settingsDataStore.edit { it[K.paceSync] = on }.let { }
    suspend fun setPing(on: Boolean) = context.settingsDataStore.edit { it[K.ping] = on }.let { }
    suspend fun setSitting(on: Boolean) = context.settingsDataStore.edit { it[K.sitting] = on }.let { }
    suspend fun setHealthConnect(on: Boolean) = context.settingsDataStore.edit { it[K.hc] = on }.let { }

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
