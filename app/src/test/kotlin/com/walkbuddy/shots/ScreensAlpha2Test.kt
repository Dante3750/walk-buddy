package com.walkbuddy.shots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.Avatar
import com.walkbuddy.domain.AvatarAccessory
import com.walkbuddy.domain.AvatarStyle
import com.walkbuddy.domain.ChallengeInstance
import com.walkbuddy.domain.ChallengeProgress
import com.walkbuddy.domain.ChallengeState
import com.walkbuddy.domain.Challenges
import com.walkbuddy.domain.CheckId
import com.walkbuddy.domain.CheckResult
import com.walkbuddy.domain.CheckStatus
import com.walkbuddy.domain.CoupleStreakInfo
import com.walkbuddy.domain.CoupleStreakMessage
import com.walkbuddy.domain.FixAction
import com.walkbuddy.domain.SharedLane
import com.walkbuddy.domain.SharedMode
import com.walkbuddy.domain.SharedWalkRecord
import com.walkbuddy.domain.TrackScene
import com.walkbuddy.domain.TrackWalker
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.session.GroupUi
import com.walkbuddy.ui.components.LocalTrackScene
import com.walkbuddy.ui.components.TrackView
import com.walkbuddy.ui.screens.AboutContent
import com.walkbuddy.ui.screens.ChallengeActions
import com.walkbuddy.ui.screens.ChallengesContent
import com.walkbuddy.ui.screens.CoupleStreakCard
import com.walkbuddy.ui.screens.GroupActions
import com.walkbuddy.ui.screens.GroupLiveContent
import com.walkbuddy.ui.screens.SelfCheckActions
import com.walkbuddy.ui.screens.SelfCheckContent
import com.walkbuddy.ui.screens.WalkRow
import org.junit.Rule
import org.junit.Test

/** Screenshots for the alpha 2.0 screens. Static data only; the PNGs go to the `screenshots` artifact. */
class ScreensAlpha2Test {
    @get:Rule val paparazzi = newPaparazzi()

    private val checks = listOf(
        CheckResult(CheckId.ActivityPermission, CheckStatus.Pass),
        CheckResult(CheckId.StepSensor, CheckStatus.Pass),
        CheckResult(CheckId.LocationPermission, CheckStatus.Warn, FixAction.RequestLocation),
        CheckResult(CheckId.Notifications, CheckStatus.Fail, FixAction.RequestNotifications),
        CheckResult(CheckId.Battery, CheckStatus.Warn, FixAction.BatterySettings),
        CheckResult(CheckId.Server, CheckStatus.Pass, detail = "412 ms"),
        CheckResult(CheckId.Storage, CheckStatus.Pass),
    )

    @Test fun selfcheck_light() = paparazzi.shot("selfcheck", Look.Light, 2600) { SelfCheckContent(checks, SelfCheckActions()) }
    @Test fun selfcheck_dark() = paparazzi.shot("selfcheck", Look.Dark, 2600) { SelfCheckContent(checks, SelfCheckActions()) }

    @Test fun about_light() = paparazzi.shot("about", Look.Light) { AboutContent("2.0.0-alpha", 8, {}, {}, {}) }

    private fun progress(i: Int, tpl: Int, cur: Double, state: ChallengeState = ChallengeState.Active): ChallengeProgress {
        val t = Challenges.templates[tpl % Challenges.templates.size]
        val today = Fixtures.today
        return ChallengeProgress(
            ChallengeInstance(i.toLong(), t.id, today - 2, today + 4), t, cur,
            (cur / t.targetD).toFloat().coerceIn(0f, 1f), state, 4, false,
        )
    }

    private fun challenges(look: Look) = paparazzi.shot("challenges", look, 3000) {
        ChallengesContent(
            listOf(progress(1, 0, 6.2), progress(2, 1, 3.0, ChallengeState.Completed)),
            UnitSystem.Metric, Fixtures.today, ChallengeActions(),
        )
    }
    @Test fun challenges_light() = challenges(Look.Light)
    @Test fun challenges_dark() = challenges(Look.Dark)

    @Test fun couple_streak_light() = paparazzi.shot("couple_streak", Look.Light, 700) {
        CoupleStreakCard(CoupleStreakInfo(6, 11, 2, true, CoupleStreakMessage.WalkedToday, null, null), Modifier.padding(16.dp))
    }

    private val walkers = listOf(
        TrackWalker("me", "You", Avatar(AvatarStyle.Boy, 1, AvatarAccessory.Cap), 520.0, 5400, isMe = true),
        TrackWalker("a", "Meera", Avatar(AvatarStyle.Girl, 3, AvatarAccessory.Scarf), 610.0, 5900),
        TrackWalker("b", "Ravi", Avatar(AvatarStyle.Round, 4, AvatarAccessory.Backpack), 430.0, 4700),
        TrackWalker("c", "Tara", Avatar(AvatarStyle.Girl, 5, AvatarAccessory.Cap), 480.0, 5100, stale = true, lastSeenSec = 95),
    )

    private fun scene(s: TrackScene, look: Look) = paparazzi.shot("track_${s.name.lowercase()}", look, 900) {
        CompositionLocalProvider(LocalTrackScene provides s) {
            TrackView(walkers, 90.0, UnitSystem.Metric, Modifier.fillMaxWidth().height(380.dp).padding(8.dp), still = true)
        }
    }
    @Test fun track_metro_light() = scene(TrackScene.Metro, Look.Light)
    @Test fun track_metro_dark() = scene(TrackScene.Metro, Look.Dark)
    @Test fun track_train() = scene(TrackScene.Train, Look.Light)
    @Test fun track_trail() = scene(TrackScene.Trail, Look.Light)
    @Test fun track_nightsky_dark() = scene(TrackScene.NightSky, Look.Dark)
    @Test fun track_spring() = scene(TrackScene.Spring, Look.Light)
    @Test fun track_summer() = scene(TrackScene.Summer, Look.Light)
    @Test fun track_autumn() = scene(TrackScene.Autumn, Look.Light)
    @Test fun track_winter() = scene(TrackScene.Winter, Look.Light)

    @Test fun group_live_light() = paparazzi.shot("group_live", Look.Light, 2000) {
        GroupLiveContent(GroupUi(joined = true, iAmHost = true, code = "K7Q2XM", myId = "me"), UnitSystem.Metric, false, GroupActions(), lowPower = true)
    }

    private val record = SharedWalkRecord(
        id = 1, startMs = System.currentTimeMillis() - 86_400_000L, durationMs = 2_400_000L, mode = SharedMode.Partner, memberCount = 2,
        title = "Meera",
        lanes = listOf(
            SharedLane("me", "You", 0x45, true, 4800, 3100.0),
            SharedLane("m", "Meera", 0x0E, false, 5100, 3200.0),
        ),
        samples = emptyList(), togetherPct = 82, longestTogetherMs = 900_000L, maxGapM = 140.0, routePolyline = null,
    )

    @Test fun history_list_light() = paparazzi.shot("history_list", Look.Light, 1200) {
        Column(Modifier.padding(16.dp)) {
            WalkRow(record, UnitSystem.Metric) {}
            WalkRow(record.copy(id = 2, mode = SharedMode.Group, memberCount = 7, title = "Sunday club"), UnitSystem.Metric) {}
        }
    }
}
