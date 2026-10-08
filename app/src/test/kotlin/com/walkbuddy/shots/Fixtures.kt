package com.walkbuddy.shots

import com.walkbuddy.data.GoalMode
import com.walkbuddy.data.Settings
import com.walkbuddy.data.SpotRow
import com.walkbuddy.domain.AnniversaryCountdown
import com.walkbuddy.domain.BadgeId
import com.walkbuddy.domain.BadgeProgress
import com.walkbuddy.domain.BuddyCard
import com.walkbuddy.domain.BuddyStatus
import com.walkbuddy.domain.ActivityState
import com.walkbuddy.domain.CalorieRange
import com.walkbuddy.domain.Catalogue
import com.walkbuddy.domain.DemoData
import com.walkbuddy.domain.Diet
import com.walkbuddy.domain.FavoriteSpot
import com.walkbuddy.domain.Highlights
import com.walkbuddy.domain.HourlyHistogram
import com.walkbuddy.domain.MonthGrid
import com.walkbuddy.domain.MonthlyPattern
import com.walkbuddy.domain.MoodInsights
import com.walkbuddy.domain.OurWeek
import com.walkbuddy.domain.PaceZone
import com.walkbuddy.domain.RefuelPlanner
import com.walkbuddy.domain.Reaction
import com.walkbuddy.domain.RingBuddy
import com.walkbuddy.domain.StreakFlame
import com.walkbuddy.domain.StreakResult
import com.walkbuddy.domain.TogetherSnapshot
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.domain.WalkDate
import com.walkbuddy.domain.WalkState
import com.walkbuddy.domain.WalkSummary
import com.walkbuddy.domain.WeeklyGuidance
import com.walkbuddy.domain.WeeklyRecapBuilder
import com.walkbuddy.domain.WeeklyReport
import com.walkbuddy.rtc.SignalingState
import com.walkbuddy.session.IncomingReaction
import com.walkbuddy.session.LobbyPeer
import com.walkbuddy.session.Phase
import com.walkbuddy.session.SessionUi
import com.walkbuddy.ui.BadgeRow
import com.walkbuddy.ui.FuelUi
import com.walkbuddy.ui.HomeUi
import com.walkbuddy.ui.TrendsUi
import java.time.LocalDate

/** Static, believable data for the screenshot tests. Everything is derived from the current date so the month grid is always real. */
object Fixtures {
    val todayDate: LocalDate = LocalDate.now()
    val today: Long = todayDate.toEpochDay()
    val bundle = DemoData.build(today)

    val settings = Settings(
        onboardingDone = true, displayName = "Aarav", peerId = "p1", heightCm = 176.0, weightKg = 72.0,
        anniversaryDate = todayDate.minusDays(412).toString(), anniversaryLabel = "our anniversary", buddyName = "Meera",
        goalMode = GoalMode.Adaptive, restWeekdays = setOf(7),
    )

    private val streak = StreakResult(9, 14, 2)

    fun home(steps: Int = 5247, goal: Int = 7000, walking: Boolean = false, demo: Boolean = false, buddySteps: Int = 6120, gentle: Boolean = false, calories: Boolean = false, buddyName: String = "Meera") = HomeUi(
        rawSteps = steps + 143, verifiedSteps = steps, goal = goal, baseGoal = goal, gentle = gentle, restDay = false,
        distanceM = steps * 0.72, activeMin = 41, activeIsEstimate = false,
        calories = if (calories) CalorieRange(180, 300) else null,
        guidance = WeeklyGuidance.progress(96, 12), streak = streak,
        flame = StreakFlame.info(streak, steps >= goal, false, 17),
        buddies = listOf(RingBuddy("b", buddyName, buddySteps, 8000)), unit = UnitSystem.Metric, name = "Aarav", demo = demo,
        countdown = "12 days until our anniversary", daysTogether = "Day 412 together", walking = walking,
    )

    val report: WeeklyReport = WeeklyReport.build(bundle.days, bundle.walks, today)

    val bars: List<Pair<Long, Int>> = (6 downTo 0).map { off ->
        (today - off) to (bundle.days.firstOrNull { it.epochDay == today - off }?.verifiedSteps ?: 0)
    }

    private val stepsByDay = bundle.days.associate { it.epochDay to it.verifiedSteps }

    val trends: TrendsUi = run {
        val moods = bundle.moods.sortedByDescending { it.atMs }.take(5).mapIndexed { i, m ->
            m.copy(note = listOf("Golden light by the lake", "", "Chai after, obviously", "Slow and easy", "Rain just stopped")[i])
        }
        TrendsUi(
            hourly = HourlyHistogram.build(bundle.hours, today),
            month = MonthGrid.build(todayDate.year, todayDate.monthValue, stepsByDay, { 7000 }, today),
            monthTitle = todayDate.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + todayDate.year,
            canGoForward = false,
            moodInsight = MoodInsights.compute(bundle.moods, stepsByDay),
            recentMoods = moods,
            ourWeek = OurWeek.build(report, bundle.walks.filter { it.buddyCount >= 1 }, bundle.coupleDays, today, "Meera", UnitSystem.Metric),
            unit = UnitSystem.Metric,
        )
    }

    val badges: List<BadgeRow> = BadgeId.values().mapIndexed { i, id ->
        val earned = i < 5 || i == 8
        BadgeRow(id, earned, if (earned) System.currentTimeMillis() - i * 86_400_000L * 9 else null, BadgeProgress(if (earned) 1.0 else (0.15 + (i % 4) * 0.2), if (earned) "Done" else "${(15 + (i % 4) * 20)}% there"))
    }

    val slides = WeeklyRecapBuilder.build(
        report, bundle.days, today, 4, 11_800.0, HourlyHistogram.build(bundle.hours, today),
        StreakFlame.info(streak, true, false, 12), UnitSystem.Metric, false,
    )

    val fuel: FuelUi = run {
        val pattern = MonthlyPattern(168_000, 26, null, 130, 0.42)
        FuelUi(pattern, CalorieRange(2100, 3500), true, RefuelPlanner.suggest(pattern, Diet.Vegetarian, Catalogue.bundled(), false, true, true))
    }

    val lobby = SessionUi(
        phase = Phase.Lobby, code = "K7M4QX", joinLink = "walkbuddy://join/K7M4QX", signaling = SignalingState.Connected,
        peers = listOf(LobbyPeer("p2", "Meera", true)), coupleMode = true, pingEnabled = true,
    )

    private val walkState = WalkState(
        nowMs = 0, elapsedMs = 1_472_000, myDistanceM = 1_940.0, myVerifiedSteps = 2_630, myRawSteps = 2_661, myCadenceSpm = 108.0,
        myZone = PaceZone.Brisk, mySpeedMps = 1.35, myActivity = ActivityState.Walking,
        buddies = listOf(
            BuddyCard("p2", "Meera", 14.0, 6.0, "A few steps ahead", PaceZone.Brisk, 2_540, 1.32, BuddyStatus.Moving, "Meera is walking beside you.", null),
        ),
        together = TogetherSnapshot(1_472_000, 1_250_000, 85, 420_000, 640_000), togetherNow = true, nudge = null, paceSuggestion = null,
    )

    val live = SessionUi(
        phase = Phase.Walking, code = "K7M4QX", signaling = SignalingState.Connected, walk = walkState, coupleMode = true, pingEnabled = true,
        buddyDaily = listOf(RingBuddy("p2", "Meera", 6_120, 8_000)),
        reaction = IncomingReaction("Meera", Reaction.Beautiful, 1),
    )

    val summary = run {
        val s = WalkSummary(
            durationMs = 2_340_000, distanceM = 3_120.0, rawSteps = 4_210, verifiedSteps = 4_160, avgSpeedMps = 1.33, buddyCount = 1,
            togetherPct = 86, longestTogetherMs = 1_080_000, moderateMin = 24, vigorousMin = 2, nudgesShown = 1, calories = null,
        )
        SessionUi(phase = Phase.Summary, summary = s, highlights = Highlights.build(s, false), coupleMode = true, walkId = 7)
    }

    val dates = listOf(
        WalkDate(1, System.currentTimeMillis() + 2 * 86_400_000L, 30, true, "Sunset loop"),
        WalkDate(2, System.currentTimeMillis() + 6 * 86_400_000L, 45, false, "Sunday market walk"),
    )
    val spots = listOf(SpotRow(1, FavoriteSpot("Lakeside path", 0.0, 0.0)), SpotRow(2, FavoriteSpot("Old banyan bench", 0.0, 0.0)))
}
