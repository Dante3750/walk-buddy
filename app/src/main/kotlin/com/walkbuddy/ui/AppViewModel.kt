package com.walkbuddy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.walkbuddy.AppContainer
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.data.Clock
import com.walkbuddy.data.GoalMode
import com.walkbuddy.data.Settings
import com.walkbuddy.data.SpotRow
import com.walkbuddy.domain.AdaptiveGoal
import com.walkbuddy.domain.CalorieEstimator
import com.walkbuddy.domain.CalorieRange
import com.walkbuddy.domain.Catalogue
import com.walkbuddy.domain.DayRecord
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.FoodItem
import com.walkbuddy.domain.MonthlyPattern
import com.walkbuddy.domain.ProfileCheck
import com.walkbuddy.domain.RefuelIdeas
import com.walkbuddy.domain.RefuelPlanner
import com.walkbuddy.domain.StepLength
import com.walkbuddy.domain.StreakResult
import com.walkbuddy.domain.Streaks
import com.walkbuddy.domain.WalkDate
import com.walkbuddy.domain.WalkRecord
import com.walkbuddy.domain.WeeklyGuidance
import com.walkbuddy.domain.WeeklyReport
import com.walkbuddy.rtc.SignalingClient
import com.walkbuddy.session.SessionUi
import com.walkbuddy.session.WalkService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUi(
    val rawSteps: Int,
    val verifiedSteps: Int,
    val goal: Int,
    val restDay: Boolean,
    val guidance: WeeklyGuidance.Progress,
    val streak: StreakResult,
)

data class FuelUi(
    val pattern: MonthlyPattern,
    val calories: CalorieRange?,
    val profileOk: Boolean,
    val ideas: RefuelIdeas,
)

class AppViewModel(private val c: AppContainer, private val appContext: android.content.Context) : ViewModel() {
    val settings: StateFlow<Settings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val session: StateFlow<SessionUi> = c.session.ui
    val spots: StateFlow<List<SpotRow>> = c.repository.spots.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dates: StateFlow<List<WalkDate>> = c.repository.dates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val coupleDistanceM: StateFlow<Double> = c.repository.coupleDistanceM.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)
    val coupleDays: StateFlow<Set<Long>> = c.repository.coupleDays.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val walks: StateFlow<List<WalkRecord>> = c.repository.walks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val days: StateFlow<List<DayRecord>> = c.repository.days.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A walkbuddy:// link opened from outside the app, waiting for the user to confirm. */
    val pendingJoin = MutableStateFlow<String?>(null)

    private val catalogue: List<FoodItem> by lazy { Catalogue.bundled() }

    val home: StateFlow<HomeUi?> = combine(days, settings) { d, s -> if (s == null) null else buildHome(d, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val weekly: StateFlow<WeeklyReport> = combine(days, walks) { d, w -> WeeklyReport.build(d, w, Clock.today()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WeeklyReport.build(emptyList(), emptyList(), Clock.today()))

    /** Steps for the last 7 days, oldest first (for the chart). */
    val weekBars: StateFlow<List<Pair<Long, Int>>> = days.combine(settings) { d, _ ->
        val today = Clock.today()
        (6 downTo 0).map { off -> (today - off) to (d.firstOrNull { it.epochDay == today - off }?.verifiedSteps ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hotDay = MutableStateFlow(false)

    val fuel: StateFlow<FuelUi?> = combine(days, walks, settings, hotDay) { d, w, s, hot ->
        if (s == null) null else buildFuel(d, w, s, hot)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun planFor(day: Long, history: List<DayRecord>, s: Settings) =
        AdaptiveGoal.planFor(day, history, s.restWeekdays, if (s.goalMode == GoalMode.Fixed) s.fixedGoal else null)

    private fun buildHome(d: List<DayRecord>, s: Settings): HomeUi {
        val today = Clock.today()
        val rec = d.firstOrNull { it.epochDay == today }
        val plan = planFor(today, d, s)
        val week = d.filter { it.epochDay in (today - 6)..today }
        val met = d.filter { it.epochDay < today || it.verifiedSteps > 0 }
            .filter { it.verifiedSteps >= planFor(it.epochDay, d, s).goal }.map { it.epochDay }.toSet()
        val streak = Streaks.compute(met, today, d.minOfOrNull { it.epochDay } ?: today, s.restWeekdays)
        return HomeUi(
            rawSteps = rec?.rawSteps ?: 0, verifiedSteps = rec?.verifiedSteps ?: 0, goal = plan.goal,
            restDay = plan.isRestDay || rec?.restDay == true,
            guidance = WeeklyGuidance.progress(week.sumOf { it.moderateMin }, week.sumOf { it.vigorousMin }),
            streak = streak,
        )
    }

    private fun buildFuel(d: List<DayRecord>, w: List<WalkRecord>, s: Settings, hot: Boolean): FuelUi {
        val today = Clock.today()
        val month = d.filter { it.epochDay in (today - 29)..today && it.verifiedSteps > 0 }
        val monthWalks = w.filter { it.epochDay in (today - 29)..today }
        val walkMin = monthWalks.sumOf { it.durationMs / 60_000.0 }
        val activeMin = monthWalks.sumOf { it.moderateMin + it.vigorousMin }
        val pattern = MonthlyPattern(
            totalSteps = month.sumOf { it.verifiedSteps.toLong() },
            daysCovered = month.size,
            avgCadenceSpm = null,
            activeEquivMinPerWeek = (d.filter { it.epochDay in (today - 27)..today }.sumOf { it.activeEquivMin } / 4.0).toInt(),
            briskShare = if (walkMin <= 0) 0.0 else (activeMin / walkMin).coerceIn(0.0, 1.0),
        )
        val profileOk = ProfileCheck.isPlausible(s.profile)
        var low = 0; var high = 0; var any = false
        if (profileOk && s.profile.weightKg != null) {
            for (walk in monthWalks) {
                val r = CalorieEstimator.estimate(s.profile, walk.durationMs, walk.distanceM, walk.verifiedSteps.toLong(), s.calibratedStepLengthM)
                if (r != null) { low += r.lowKcal; high += r.highKcal; any = true }
            }
        }
        val ideas = RefuelPlanner.suggest(
            pattern = pattern, diet = s.diet, catalogue = catalogue, hotDay = hot,
            afterLongOrBriskWalk = pattern.briskShare >= 0.3 || monthWalks.any { it.durationMs >= 45 * 60_000L },
            profileOk = profileOk,
        )
        return FuelUi(pattern, if (any) CalorieRange(low, high) else null, profileOk, ideas)
    }

    // ---------- actions ----------

    fun startLobby(codeOrLink: String?, solo: Boolean = false) {
        c.session.openLobby(codeOrLink, null, solo)
        WalkService.start(appContext)
    }

    fun startWalking() = c.session.startWalking()
    fun endWalk() = c.session.endWalk()
    fun leaveLobby() = c.session.leave()
    fun finishSummary() = c.session.finishSummary()
    fun setQuiet(on: Boolean) = c.session.setQuiet(on)
    fun sendPing(): Boolean = c.session.sendPing()
    fun dismissBanner() = c.session.dismissBanner()
    fun shareSpot(s: FavoriteSpot) = c.session.shareSpot(s)
    fun acceptSpot(save: Boolean) = c.session.acceptSpotOffer(save)

    fun saveSettings(block: suspend com.walkbuddy.data.SettingsStore.() -> Unit) {
        viewModelScope.launch { c.settings.block() }
    }

    fun setRestToday(rest: Boolean) = viewModelScope.launch { c.repository.setRestDay(Clock.today(), rest) }.let { }

    fun addDate(d: WalkDate) = viewModelScope.launch { c.repository.addDate(d) }.let { }
    fun deleteDate(id: Long) = viewModelScope.launch { c.repository.deleteDate(id) }.let { }
    fun deleteSpot(id: Long) = viewModelScope.launch { c.repository.deleteSpot(id) }.let { }

    /** Saves the last known location under [name]. Calls [done] with a message for the user. */
    fun saveSpotHere(name: String, done: (String) -> Unit) {
        viewModelScope.launch {
            val fix = c.locationSource.lastKnown()
            if (fix == null) { done("No location yet. Open a map or step outside and try again."); return@launch }
            val ok = c.repository.addSpot(FavoriteSpot(name, fix.lat, fix.lon))
            done(if (ok) "Saved" else "That name is empty or the spot is already saved")
        }
    }

    fun calibrateStepLength(done: (String) -> Unit) {
        val last = walks.value.firstOrNull { it.distanceM >= 120 && it.verifiedSteps >= 200 }
        val len = last?.let { StepLength.calibrate(it.distanceM, it.verifiedSteps.toLong()) }
        if (len == null) { done("Needs a recent walk of at least 120 m and 200 steps with GPS."); return }
        viewModelScope.launch { c.settings.setStepLength(len); done("Step length set to ${"%.2f".format(len)} m") }
    }

    fun testServer(url: String, done: (String?) -> Unit) = SignalingClient.test(url, done = done)

    suspend fun exportText(): String = c.repository.exportCsv()

    fun deleteEverything(done: () -> Unit) {
        viewModelScope.launch {
            c.session.leave()
            c.repository.deleteAll()
            c.steps.invalidate()
            done()
        }
    }

    fun healthAvailable(done: (Boolean) -> Unit) { viewModelScope.launch { done(c.health.isAvailable()) } }
    fun healthPermissions(): Set<String> = c.health.permissions()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WalkBuddyApplication
                AppViewModel(app.container, app)
            }
        }
    }
}
