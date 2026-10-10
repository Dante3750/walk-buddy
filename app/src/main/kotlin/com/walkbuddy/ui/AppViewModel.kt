package com.walkbuddy.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.walkbuddy.AppContainer
import com.walkbuddy.WalkBuddyApplication
import com.walkbuddy.data.Clock
import com.walkbuddy.data.Goals
import com.walkbuddy.data.Settings
import com.walkbuddy.data.SpotRow
import com.walkbuddy.domain.AdaptiveGoal
import com.walkbuddy.domain.AnniversaryCountdown
import com.walkbuddy.domain.BadgeEngine
import com.walkbuddy.domain.BadgeId
import com.walkbuddy.domain.BadgeInput
import com.walkbuddy.domain.BadgeProgress
import com.walkbuddy.domain.CalorieEstimator
import com.walkbuddy.domain.CalorieRange
import com.walkbuddy.domain.Catalogue
import com.walkbuddy.domain.DayRecord
import com.walkbuddy.domain.DemoBundle
import com.walkbuddy.domain.DemoData
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.FlameInfo
import com.walkbuddy.domain.FoodItem
import com.walkbuddy.domain.GentleDay
import com.walkbuddy.domain.Hero
import com.walkbuddy.domain.HourSteps
import com.walkbuddy.domain.HourlyHistogram
import com.walkbuddy.domain.HourlyProfile
import com.walkbuddy.domain.MonthGrid
import com.walkbuddy.domain.MonthGridModel
import com.walkbuddy.domain.MonthlyPattern
import com.walkbuddy.domain.MoodEntry
import com.walkbuddy.domain.MoodInsight
import com.walkbuddy.domain.MoodInsights
import com.walkbuddy.domain.OurWeek
import com.walkbuddy.domain.OurWeekCard
import com.walkbuddy.domain.ProfileCheck
import com.walkbuddy.domain.RecapSlide
import com.walkbuddy.domain.Reaction
import com.walkbuddy.domain.RefuelIdeas
import com.walkbuddy.domain.RefuelPlanner
import com.walkbuddy.domain.RingBuddy
import com.walkbuddy.domain.StepLength
import com.walkbuddy.domain.StreakFlame
import com.walkbuddy.domain.StreakResult
import com.walkbuddy.domain.Streaks
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.domain.WalkDate
import com.walkbuddy.domain.WalkRecord
import com.walkbuddy.domain.WeeklyGuidance
import com.walkbuddy.domain.WeeklyRecapBuilder
import com.walkbuddy.domain.WeeklyReport
import com.walkbuddy.notify.Notifications
import com.walkbuddy.notify.WidgetBridge
import com.walkbuddy.session.CreateGroupOptions
import com.walkbuddy.session.GroupUi
import com.walkbuddy.session.SessionUi
import com.walkbuddy.session.WalkService
import com.walkbuddy.steps.StepTracking
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class HomeUi(
    val rawSteps: Int,
    val verifiedSteps: Int,
    val goal: Int,
    val baseGoal: Int,
    val gentle: Boolean,
    val restDay: Boolean,
    val distanceM: Double,
    val activeMin: Int,
    val activeIsEstimate: Boolean,
    val calories: CalorieRange?,
    val guidance: WeeklyGuidance.Progress,
    val streak: StreakResult,
    val flame: FlameInfo,
    val buddies: List<RingBuddy>,
    val unit: UnitSystem,
    val name: String,
    val demo: Boolean,
    val countdown: String?,
    val daysTogether: String?,
    val walking: Boolean,
)

@Immutable
data class FuelUi(
    val pattern: MonthlyPattern,
    val calories: CalorieRange?,
    val profileOk: Boolean,
    val ideas: RefuelIdeas,
)

@Immutable
data class TrendsUi(
    val hourly: HourlyProfile,
    val month: MonthGridModel,
    val monthTitle: String,
    val canGoForward: Boolean,
    val moodInsight: MoodInsight,
    val recentMoods: List<MoodEntry>,
    val ourWeek: OurWeekCard,
    val unit: UnitSystem,
)

@Immutable
data class BadgeRow(val id: BadgeId, val earned: Boolean, val unlockedMs: Long?, val progress: BadgeProgress)

@Immutable
data class UsUi(val unit: UnitSystem, val coupleDistanceM: Double, val buddyName: String)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(private val c: AppContainer, private val appContext: android.content.Context) : ViewModel() {
    val settings: StateFlow<Settings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val session: StateFlow<SessionUi> = c.session.ui
    val group: StateFlow<GroupUi> = c.groupSession.ui
    val spots: StateFlow<List<SpotRow>> = c.repository.spots.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dates: StateFlow<List<WalkDate>> = c.repository.dates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Demo mode swaps every data source for believable sample data. Nothing is written while it is on. */
    private val demo: StateFlow<DemoBundle?> = settings.map { it?.demoMode == true }.distinctUntilChanged()
        .map { on -> if (on) DemoData.build(Clock.today()) else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Steps added on top of the demo's "today" so the ring visibly moves. */
    private val demoExtra = MutableStateFlow(0)
    private val clockTick = MutableStateFlow(0)

    /** True while the activity is started. Background-only work (demo animation, widget push, badge checks) waits for it. */
    private val fg = MutableStateFlow(false)

    private val dbDays = c.repository.days.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val dbWalks = c.repository.walks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val dbHours = c.repository.hours.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val dbMoods = c.repository.moods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val dbCoupleDays = c.repository.coupleDays.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val dbCoupleDistance = c.repository.coupleDistanceM.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)
    private val dbBadges = c.repository.badges.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val days: StateFlow<List<DayRecord>> = combine(dbDays, demo) { d, dm -> dm?.days ?: d }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val walks: StateFlow<List<WalkRecord>> = combine(dbWalks, demo) { w, dm -> dm?.walks?.sortedByDescending { it.startMs } ?: w }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val hours: StateFlow<List<HourSteps>> = combine(dbHours, demo) { h, dm -> dm?.hours ?: h }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val moods: StateFlow<List<MoodEntry>> = combine(dbMoods, demo) { m, dm -> dm?.moods?.sortedByDescending { it.atMs } ?: m }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val coupleDays: StateFlow<Set<Long>> = combine(dbCoupleDays, demo) { d, dm -> dm?.coupleDays ?: d }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val coupleDistanceM: StateFlow<Double> = combine(dbCoupleDistance, demo) { d, dm -> dm?.coupleDistanceM ?: d }.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    /** A walkbuddy:// link opened from outside the app, waiting for the user to confirm. */
    val pendingJoin = MutableStateFlow<String?>(null)

    /** Set by launcher shortcuts and the quick settings tile: "start_solo" or "recap". */
    val pendingAction = MutableStateFlow<String?>(null)

    private val catalogue: List<FoodItem> by lazy { Catalogue.bundled() }

    val home: StateFlow<HomeUi?> = combine(days, settings, demo, demoExtra, c.session.ui) { d, s, dm, extra, ses ->
        if (s == null) null else buildHome(d, s, dm, extra, ses.phase == com.walkbuddy.session.Phase.Walking)
    }.combine(clockTick) { h, _ -> h }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val weekly: StateFlow<WeeklyReport> = combine(days, walks, clockTick) { d, w, _ -> WeeklyReport.build(d, w, Clock.today()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeeklyReport.build(emptyList(), emptyList(), Clock.today()))

    /** Steps for the last 7 days, oldest first (for the chart). */
    val weekBars: StateFlow<List<Pair<Long, Int>>> = combine(days, clockTick) { d, _ ->
        val today = Clock.today()
        (6 downTo 0).map { off -> (today - off) to (d.firstOrNull { it.epochDay == today - off }?.verifiedSteps ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hotDay = MutableStateFlow(false)

    val fuel: StateFlow<FuelUi?> = combine(days, walks, settings, hotDay) { d, w, s, hot ->
        if (s == null) null else buildFuel(d, w, s, hot)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 0 = this month, -1 = last month, and so on. */
    val monthOffset = MutableStateFlow(0)

    val trends: StateFlow<TrendsUi?> = combine(days, hours, moods, walks, coupleDays) { d, h, m, w, cd -> TrendInputs(d, h, m, w, cd) }
        .combine(monthOffset) { t, off -> t to off }
        .combine(settings) { (t, off), s -> if (s == null) null else buildTrends(t, off, s, demo.value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private class TrendInputs(val days: List<DayRecord>, val hours: List<HourSteps>, val moods: List<MoodEntry>, val walks: List<WalkRecord>, val coupleDays: Set<Long>)

    val recap: StateFlow<List<RecapSlide>> = combine(days, walks, hours, settings, coupleDays) { d, w, h, s, cd ->
        if (s == null) emptyList() else buildRecap(d, w, h, s, cd)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val badges: StateFlow<List<BadgeRow>> = combine(days, walks, moods, coupleDays, settings) { d, w, m, cd, s ->
        if (s == null) emptyList() else buildBadges(d, w, m, cd, coupleDistanceM.value, s)
    }.combine(dbBadges) { rows, stored ->
        rows.map { r -> if (demo.value != null) r else r.copy(unlockedMs = stored[r.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _badgeEvents = MutableSharedFlow<List<BadgeId>>(extraBufferCapacity = 4)

    /** Fires once when badges unlock, so the UI can celebrate and announce them to TalkBack. */
    val badgeEvents: SharedFlow<List<BadgeId>> = _badgeEvents.asSharedFlow()

    init {
        // Haptics preference is also read by non-UI code (nudges, pings).
        viewModelScope.launch { settings.collect { s -> if (s != null) Notifications.hapticsEnabled = s.haptics } }

        // Demo: the ring keeps moving so the hero feels alive.
        viewModelScope.launch {
            demo.map { it != null }.distinctUntilChanged().collectLatest { on ->
                demoExtra.value = 0
                // Only animates while the app is on screen; there is nothing to look at otherwise.
                if (on) fg.collectLatest { visible ->
                    while (visible) {
                        delay(2_000)
                        val walking = c.session.ui.value.demo
                        demoExtra.update { (it + if (walking) 30 else 12).coerceAtMost(9_000) }
                    }
                }
            }
        }

        // Keep the home-screen widget in step with the app.
        viewModelScope.launch {
            // The service and the background job keep the widget current when the app is closed; this is for when it is open.
            fg.flatMapLatest { visible -> if (visible) home else emptyFlow() }.collect { h ->
                if (h != null && !h.demo) WidgetBridge.publish(appContext, h.verifiedSteps, h.goal, h.name)
            }
        }

        // Badge unlocks: evaluated against the real database only, and only once every table has been read.
        viewModelScope.launch {
            // Checked while the app is open (a badge earned in the background is recognised and celebrated the next time it opens).
            val snapshots = combine(c.repository.days, c.repository.walks, c.repository.moods, c.repository.coupleDays, c.repository.badges) { d, w, m, cd, stored ->
                BadgeSnapshot(d, w, m, cd, stored.keys)
            }
            fg.flatMapLatest { visible -> if (visible) snapshots else emptyFlow() }.combine(settings) { snap, s -> snap to s }.collect { (snap, s) ->
                if (s == null || s.demoMode) return@collect
                val dist = c.repository.coupleDistanceNow()
                val earned = BadgeEngine.evaluate(badgeInput(snap.days, snap.walks, snap.moods, snap.coupleDays, dist, s))
                val fresh = BadgeEngine.newlyUnlocked(earned, snap.stored)
                if (fresh.isNotEmpty()) {
                    c.repository.recordBadges(fresh)
                    _badgeEvents.tryEmit(fresh)
                }
            }
        }
    }

    private class BadgeSnapshot(val days: List<DayRecord>, val walks: List<WalkRecord>, val moods: List<MoodEntry>, val coupleDays: Set<Long>, val stored: Set<BadgeId>)

    // ---------- builders ----------

    private fun badgeInput(d: List<DayRecord>, w: List<WalkRecord>, m: List<MoodEntry>, cd: Set<Long>, dist: Double, s: Settings): BadgeInput {
        val today = Clock.today()
        val met = metDays(d, s)
        val streak = Streaks.compute(met, today, d.minOfOrNull { it.epochDay } ?: today, s.restWeekdays)
        return BadgeInput(d, w, { day -> Goals.goalFor(day, d, s) }, cd, dist, streak.longest, m.size)
    }

    private fun metDays(d: List<DayRecord>, s: Settings): Set<Long> {
        val today = Clock.today()
        return d.filter { it.epochDay < today || it.verifiedSteps > 0 }
            .filter { it.verifiedSteps > 0 && it.verifiedSteps >= Goals.goalFor(it.epochDay, d, s) }.map { it.epochDay }.toSet()
    }

    private fun buildHome(d0: List<DayRecord>, s: Settings, dm: DemoBundle?, extra: Int, walking: Boolean): HomeUi {
        val today = Clock.today()
        val d = if (dm != null) d0.map { if (it.epochDay == today) it.copy(verifiedSteps = it.verifiedSteps + extra, rawSteps = it.rawSteps + extra) else it } else d0
        val rec = d.firstOrNull { it.epochDay == today }
        val plan = Goals.plan(today, d, s)
        val gentle = rec?.gentle == true
        val goal = if (dm != null) DemoData.GOAL else GentleDay.effectiveGoal(plan.goal, gentle)
        val steps = rec?.verifiedSteps ?: 0
        val week = d.filter { it.epochDay in (today - 6)..today }
        val met = metDays(d, s) + (if (steps > 0 && steps >= goal) setOf(today) else emptySet())
        val streak = Streaks.compute(met, today, d.minOfOrNull { it.epochDay } ?: today, s.restWeekdays)
        val restToday = plan.isRestDay || rec?.restDay == true
        val flame = StreakFlame.info(streak, steps >= goal && steps > 0, restToday, Clock.hourOfDay())
        val recordedActive = (rec?.moderateMin ?: 0) + (rec?.vigorousMin ?: 0)
        val distance = Hero.distanceM(steps, rec?.distanceM ?: 0.0, s.stepLengthM)
        val activeMin = Hero.activeMinutes(steps, recordedActive)
        val kcal = if (!s.caloriesEnabled || steps < 200) null else
            CalorieEstimator.estimate(s.profile, (steps / 105.0 * 60_000).toLong(), null, steps.toLong(), s.stepLengthM)
        val buddies = when {
            dm != null -> listOf(RingBuddy("demo", dm.buddyName, dm.buddyStepsToday + extra / 2, dm.buddyGoal))
            s.buddyDay == today && s.buddyGoal > 0 -> listOf(RingBuddy("buddy", s.buddyName.ifBlank { "Buddy" }, s.buddySteps, s.buddyGoal))
            else -> emptyList()
        }
        val anniv = AnniversaryCountdown.parse(s.anniversaryDate)
        val countdown = anniv?.let { AnniversaryCountdown.info(it, s.anniversaryLabel, LocalDate.now()).text }
        val together = anniv?.let { a -> AnniversaryCountdown.daysTogether(a, LocalDate.now())?.let { "Day ${Hero.thousands(it.toInt())} together" } }
        return HomeUi(
            rawSteps = rec?.rawSteps ?: 0, verifiedSteps = steps, goal = goal, baseGoal = plan.goal, gentle = gentle, restDay = restToday,
            distanceM = distance, activeMin = activeMin, activeIsEstimate = Hero.activeIsEstimate(recordedActive), calories = kcal,
            guidance = WeeklyGuidance.progress(week.sumOf { it.moderateMin }, week.sumOf { it.vigorousMin }),
            streak = streak, flame = flame, buddies = buddies, unit = s.unitSystem, name = s.displayName, demo = dm != null,
            countdown = countdown, daysTogether = together, walking = walking || (dm != null && extra > 0 && extra % 24 != 0),
        )
    }

    private fun buildTrends(t: TrendInputs, offset: Int, s: Settings, dm: DemoBundle?): TrendsUi {
        val today = Clock.today()
        val ym = YearMonth.now().plusMonths(offset.toLong())
        val stepsByDay = t.days.associate { it.epochDay to it.verifiedSteps }
        val month = MonthGrid.build(ym.year, ym.monthValue, stepsByDay, { day -> Goals.goalFor(day, t.days, s) }, today)
        val title = ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + ym.year
        val hourly = HourlyHistogram.build(t.hours, today)
        val insight = MoodInsights.compute(t.moods, stepsByDay)
        val report = WeeklyReport.build(t.days, t.walks, today)
        val buddyName = dm?.buddyName ?: s.buddyName
        val ourWeek = OurWeek.build(report, t.walks.filter { it.buddyCount >= 1 }, t.coupleDays, today, buddyName, s.unitSystem)
        return TrendsUi(hourly, month, title, offset < 0, insight, t.moods.take(5), ourWeek, s.unitSystem)
    }

    private fun buildRecap(d: List<DayRecord>, w: List<WalkRecord>, h: List<HourSteps>, s: Settings, cd: Set<Long>): List<RecapSlide> {
        val today = Clock.today()
        val report = WeeklyReport.build(d, w, today)
        val weekWalks = w.filter { it.epochDay in (today - 6)..today && it.buddyCount >= 1 }
        val metSet = metDays(d, s)
        val streak = Streaks.compute(metSet, today, d.minOfOrNull { it.epochDay } ?: today, s.restWeekdays)
        val flame = StreakFlame.info(streak, today in metSet, false, 12)
        return WeeklyRecapBuilder.build(
            report, d, today, weekWalks.size, weekWalks.sumOf { it.distanceM }, HourlyHistogram.build(h, today), flame, s.unitSystem,
            android.text.format.DateFormat.is24HourFormat(appContext),
        )
    }

    private fun buildBadges(d: List<DayRecord>, w: List<WalkRecord>, m: List<MoodEntry>, cd: Set<Long>, dist: Double, s: Settings): List<BadgeRow> {
        val input = badgeInput(d, w, m, cd, dist, s)
        val earned = BadgeEngine.evaluate(input)
        return BadgeId.values().map { BadgeRow(it, it in earned, null, BadgeEngine.progress(it, input)) }
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

    private val isDemo: Boolean get() = demo.value != null

    fun onResume() { clockTick.update { it + 1 } }

    // ---- always-on step counting ----

    val stepKind get() = c.steps.kind
    val stepsNeedPermission get() = c.steps.needsPermission
    val stepsListening: StateFlow<Boolean> = c.steps.listening
    val stepsLastSample: StateFlow<Long?> = c.steps.lastSampleMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val stepsRegisterOk get() = c.steps.registerOk

    /**
     * App on screen: hold a listener of our own (so the hero counts live even if Android would not start the service), deliver
     * events immediately instead of batched, catch up with one reading, and make sure the all-day service and safety nets are alive.
     */
    fun onForeground(on: Boolean) {
        fg.value = on
        // The power policy sees this and switches the step sensor between immediate delivery (on screen) and minutes of batching (off).
        c.power.setScreenVisible(on)
        if (on) {
            c.steps.acquire("ui")
            StepTracking.ensureRunning(appContext)
            viewModelScope.launch { c.steps.sampleNow() }
        } else {
            c.steps.release("ui")
        }
    }

    /** What the power policy currently sees (screen, savers, charger), for the Battery card and for dropping decorative motion. */
    val powerState: StateFlow<com.walkbuddy.domain.PowerState> = c.power.state

    fun stepsPermissionGranted() = StepTracking.onPermissionGranted(appContext)

    fun startLobby(codeOrLink: String?, solo: Boolean = false) {
        c.session.openLobby(codeOrLink, solo)
        WalkService.start(appContext)
    }

    // ---- partner map ----
    fun setPin(pos: com.walkbuddy.domain.LatLon) = c.session.setPin(pos)
    fun clearPin() = c.session.clearPin()

    // ---- open groups ----
    fun createGroup(opts: CreateGroupOptions) {
        saveSettings { setName(opts.nickname); setGroupPrecision(opts.precision) }
        c.groupSession.create(opts)
        WalkService.start(appContext)
    }

    fun joinGroup(codeOrLink: String, nickname: String, precision: com.walkbuddy.domain.LocationPrecision) {
        saveSettings { setName(nickname); setGroupPrecision(precision) }
        c.groupSession.join(codeOrLink, nickname, precision)
        WalkService.start(appContext)
    }

    /** Couple streak with rest tokens (alpha 2.0), from the days the two of you walked together. */
    val coupleStreak: StateFlow<com.walkbuddy.domain.CoupleStreakInfo> = combine(coupleDays, settings, clockTick) { cd, st, _ ->
        com.walkbuddy.domain.CoupleStreak.compute(cd, Clock.today(), st?.restWeekdays.orEmpty(), java.time.LocalTime.now().hour)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, com.walkbuddy.domain.CoupleStreak.compute(emptySet(), Clock.today()))

    // ---- Health Connect (opt-in): compare, never add ----
    private val _hcNote = MutableStateFlow<com.walkbuddy.domain.ReconciledSteps?>(null)
    val hcNote: StateFlow<com.walkbuddy.domain.ReconciledSteps?> = _hcNote.asStateFlow()

    /** Called when Today opens. A low-battery-friendly single read; nothing runs in the background for this. */
    fun refreshHealth() {
        viewModelScope.launch {
            val s = c.settings.current()
            val own = home.value?.verifiedSteps ?: 0
            _hcNote.value = if (isDemo) null else com.walkbuddy.health.HealthSync.compare(c.health, s, own)
        }
    }

    // ---- weather suggestion (opt-in) ----
    private val _weather = MutableStateFlow<com.walkbuddy.domain.WalkWindow?>(null)
    val weather: StateFlow<com.walkbuddy.domain.WalkWindow?> = _weather.asStateFlow()

    /** Called when Home opens. Does nothing unless the person switched the suggestion on. */
    fun refreshWeather() {
        viewModelScope.launch { _weather.value = if (isDemo) null else com.walkbuddy.weather.WeatherService.suggestion(c) }
    }

    // ---- resume a walk Android ended (alpha 2.0) ----

    /** The interrupted walk to offer, or null. Only offered while nothing else is running, and never a stale or tiny one. */
    val resumeOffer: StateFlow<com.walkbuddy.domain.ResumeState?> = combine(c.settings.settings, c.session.ui, c.groupSession.ui) { st, ses, grp ->
        val state = com.walkbuddy.domain.ResumeCodec.decode(st.resumeJson.ifEmpty { null })
        val active = ses.phase != com.walkbuddy.session.Phase.Idle || grp.phase != com.walkbuddy.session.GroupPhase.Idle
        when (val d = com.walkbuddy.domain.ResumePolicy.decide(state, System.currentTimeMillis(), active)) {
            is com.walkbuddy.domain.ResumeDecision.Offer -> d.state
            is com.walkbuddy.domain.ResumeDecision.Discard -> { if (state != null) viewModelScope.launch { c.settings.setResume("") }; null }
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun resumeWalk(s: com.walkbuddy.domain.ResumeState) {
        when (s.kind) {
            com.walkbuddy.domain.ResumeKind.Group -> c.groupSession.resumeGroup(s)
            else -> c.session.resumeWalk(s)
        }
        WalkService.start(appContext)
    }

    fun discardResume() { viewModelScope.launch { c.settings.setResume("") } }

    fun startDemoGroup() = c.groupSession.startDemo()
    fun groupEnd(closeForEveryone: Boolean) = c.groupSession.endWalk(closeForEveryone)
    fun groupCancel() = c.groupSession.cancel()
    fun groupFinish() = c.groupSession.finishSummary()
    fun groupDismissBanner() = c.groupSession.dismissBanner()
    fun groupSharing(on: Boolean) = c.groupSession.setSharing(on)
    fun groupQuiet(on: Boolean) = c.groupSession.setQuiet(on)
    fun groupSweeper(on: Boolean) = c.groupSession.setSweeper(on)
    fun groupApprove(id: String) = c.groupSession.approve(id)
    fun groupDeny(id: String) = c.groupSession.deny(id)
    fun groupKick(id: String) = c.groupSession.kick(id)
    fun groupApprovalRequired(on: Boolean) = c.groupSession.setApproval(on)
    fun groupGoal(steps: Int) = c.groupSession.setGoal(steps)
    fun groupSetPin(pos: com.walkbuddy.domain.LatLon, label: String = "") = c.groupSession.setPin(pos, label)
    fun groupClearPin() = c.groupSession.clearPin()

    // ---- saved routes (opt-in) ----
    fun routes(): List<com.walkbuddy.data.RouteInfo> = c.routes.list()
    fun deleteRoute(name: String) { c.routes.delete(name) }
    fun routeFile(name: String): java.io.File? = c.routes.file(name)

    fun startDemoWalk() = c.session.startDemoWalk()
    fun startWalking() = c.session.startWalking()
    fun endWalk() = c.session.endWalk()
    fun leaveLobby() = c.session.leave()
    fun finishSummary() = c.session.finishSummary()
    fun setQuiet(on: Boolean) = c.session.setQuiet(on)
    fun sendPing(): Boolean = c.session.sendPing()
    fun sendReaction(r: Reaction): Boolean = c.session.sendReaction(r)
    fun dismissBanner() = c.session.dismissBanner()
    fun dismissReaction() = c.session.dismissReaction()
    fun shareSpot(s: FavoriteSpot) = c.session.shareSpot(s)
    fun acceptSpot(save: Boolean) = c.session.acceptSpotOffer(save)

    // ---- Walks together: the saved history of partner and group walks ----

    private val dbShared = c.repository.sharedWalks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Newest first. In demo mode sample walks stand in for the real ones. */
    val sharedWalks: StateFlow<List<com.walkbuddy.domain.SharedWalkRecord>> = combine(dbShared, demo) { d, dm ->
        if (dm != null) com.walkbuddy.domain.SharedDemo.build(System.currentTimeMillis()) else d
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteSharedWalk(id: Long) {
        if (!isDemo && id > 0) viewModelScope.launch {
            com.walkbuddy.data.WalkMedia.delete(appContext, c.repository.sharedExtras(id).second)
            c.repository.deleteSharedWalk(id)
        }
    }
    fun clearSharedWalks() {
        if (!isDemo) viewModelScope.launch {
            com.walkbuddy.data.WalkMedia.deleteAll(appContext)
            c.repository.clearSharedWalks()
        }
    }

    /** Note and photo of one history walk (alpha 2.0), loaded on demand. */
    suspend fun walkExtras(id: Long): Pair<String?, String?> = if (isDemo || id <= 0) null to null else c.repository.sharedExtras(id)

    fun saveWalkNote(id: Long, note: String?) { if (!isDemo && id > 0) viewModelScope.launch { c.repository.setSharedNote(id, note) } }

    fun attachWalkPhoto(id: Long, uri: android.net.Uri, done: (String?) -> Unit) {
        if (isDemo || id <= 0) { done(null); return }
        viewModelScope.launch {
            val old = c.repository.sharedExtras(id).second
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.walkbuddy.data.WalkMedia.save(appContext, id, uri) }
            if (name != null) { if (old != null && old != name) com.walkbuddy.data.WalkMedia.delete(appContext, old); c.repository.setSharedPhoto(id, name) }
            done(name)
        }
    }

    fun removeWalkPhoto(id: Long) {
        if (isDemo || id <= 0) return
        viewModelScope.launch {
            com.walkbuddy.data.WalkMedia.delete(appContext, c.repository.sharedExtras(id).second)
            c.repository.setSharedPhoto(id, null)
        }
    }

    // ---- Challenges (alpha 2.0): local shared goals, progress derived from the walks history ----

    val challengeProgress: StateFlow<List<com.walkbuddy.domain.ChallengeProgress>> = combine(c.repository.challenges, dbShared) { inst, walks ->
        val cw = com.walkbuddy.domain.Challenges.toWalks(walks, java.time.ZoneId.systemDefault())
        val today = Clock.today()
        com.walkbuddy.domain.Challenges.ordered(inst.mapNotNull { com.walkbuddy.domain.Challenges.progress(it, cw, today) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Fired once per challenge when its target is first reached. */
    private val _challengeDone = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val challengeDone: SharedFlow<String> = _challengeDone.asSharedFlow()

    init {
        viewModelScope.launch {
            challengeProgress.collect { list ->
                list.filter { it.justCompleted }.forEach { p ->
                    c.repository.completeChallenge(p.instance.id)
                    _challengeDone.tryEmit(p.template.id)
                }
            }
        }
    }

    fun startChallenge(templateId: String, done: (Boolean) -> Unit = {}) {
        viewModelScope.launch { done(c.repository.startChallenge(templateId)) }
    }

    /** Saves the walk reminders, then queues the next one (one inexact WorkManager job). */
    fun setReminders(slots: List<com.walkbuddy.domain.ReminderSlot>) {
        viewModelScope.launch {
            c.settings.setReminders(com.walkbuddy.domain.WalkReminders.encode(slots))
            com.walkbuddy.notify.WalkReminderScheduler.reschedule(appContext)
        }
    }

    fun deleteChallenge(id: Long) { viewModelScope.launch { c.repository.deleteChallenge(id) } }

    /** My walker on the Track. Sent to buddies as one small number next to my nickname. */
    fun setAvatar(a: com.walkbuddy.domain.Avatar) = saveSettings { setAvatar(a) }

    /** Location was just allowed or switched on during a walk: start listening for positions again. */
    fun locationAvailable() { c.session.onLocationAvailable(); c.groupSession.onLocationAvailable() }

    fun saveSettings(block: suspend com.walkbuddy.data.SettingsStore.() -> Unit) {
        viewModelScope.launch { c.settings.block() }
    }

    fun setDemoMode(on: Boolean) {
        if (!on && c.session.ui.value.demo) c.session.leave()
        saveSettings { setDemoMode(on) }
    }

    /** Demo only: jump to just past the goal so the celebration can be seen right away. */
    fun demoReachGoal() {
        val h = home.value ?: return
        if (isDemo) demoExtra.update { it + (h.goal - h.verifiedSteps + 40).coerceAtLeast(0) }
    }

    fun setRestToday(rest: Boolean) { if (!isDemo) viewModelScope.launch { c.repository.setRestDay(Clock.today(), rest) } }

    fun setGentleToday(gentle: Boolean) { if (!isDemo) viewModelScope.launch { c.repository.setGentle(Clock.today(), gentle) } }

    private val demoCelebrated = MutableStateFlow(false)

    /** True once today's goal celebration has been shown (persisted for real data, in memory for the demo). */
    val celebratedToday: StateFlow<Boolean> = combine(settings, demoCelebrated, clockTick) { s, dc, _ ->
        dc || (s != null && s.celebratedDay == Clock.today())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun markCelebrated() {
        if (isDemo) demoCelebrated.value = true else saveSettings { setCelebratedDay(Clock.today()) }
    }

    fun addMood(mood: Int, note: String, walkId: Long?) {
        if (isDemo) return
        viewModelScope.launch { c.repository.addMood(mood, note, walkId) }
    }

    fun deleteMood(id: Long) { if (!isDemo) viewModelScope.launch { c.repository.deleteMood(id) } }

    fun shiftMonth(delta: Int) { monthOffset.update { (it + delta).coerceAtMost(0).coerceAtLeast(-24) } }

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
        val last = dbWalks.value.firstOrNull { it.distanceM >= 120 && it.verifiedSteps >= 200 }
        val len = last?.let { StepLength.calibrate(it.distanceM, it.verifiedSteps.toLong()) }
        if (len == null) { done("Needs a recent walk of at least 120 m and 200 steps with GPS."); return }
        viewModelScope.launch { c.settings.setStepLength(len); done("Step length set to ${"%.2f".format(len)} m") }
    }


    suspend fun exportText(): String = c.repository.exportCsv()

    fun deleteEverything(done: () -> Unit) {
        viewModelScope.launch {
            c.session.leave()
            c.groupSession.cancel()
            c.routes.deleteAll()
            com.walkbuddy.data.WalkMedia.deleteAll(appContext)
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
