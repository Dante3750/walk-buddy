package com.walkbuddy.shots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.walkbuddy.domain.UnitSystem
import com.walkbuddy.ui.components.MoodCheckIn
import com.walkbuddy.ui.components.SectionCard
import com.walkbuddy.ui.screens.BadgesContent
import com.walkbuddy.ui.screens.CoupleActions
import com.walkbuddy.ui.screens.CoupleContent
import com.walkbuddy.ui.screens.FuelContent
import com.walkbuddy.ui.screens.HomeActions
import com.walkbuddy.ui.screens.HomeContent
import com.walkbuddy.ui.screens.LiveContent
import com.walkbuddy.ui.screens.LobbyContent
import com.walkbuddy.ui.screens.OnboardingContent
import com.walkbuddy.ui.screens.RecapContent
import com.walkbuddy.ui.screens.SettingsActions
import com.walkbuddy.ui.screens.SettingsContent
import com.walkbuddy.ui.screens.SummaryContent
import com.walkbuddy.ui.screens.TrendsContent
import com.walkbuddy.ui.screens.WalkActions
import app.cash.paparazzi.DeviceConfig
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.walkbuddy.ui.components.NavItem
import com.walkbuddy.ui.components.TabGlyph
import com.walkbuddy.ui.components.WbBottomBar
import com.walkbuddy.ui.components.WbRail
import org.junit.Rule
import org.junit.Test

/**
 * Renders every main screen with static data. The PNGs go to app/build/shots; CI publishes them to the
 * `screenshots` workflow artifact so the UI can be reviewed without a phone.
 */
class ScreensTest {
    @get:Rule val paparazzi = newPaparazzi()

    private val navItems = listOf(
        NavItem("Today", TabGlyph.Today), NavItem("Trends", TabGlyph.Trends), NavItem("Fuel", TabGlyph.Fuel),
        NavItem("Us", TabGlyph.Us), NavItem("Settings", TabGlyph.Settings),
    )

    private fun home(look: Look, h: com.walkbuddy.ui.HomeUi = Fixtures.home(), name: String = "home", height: Int = 3300) =
        paparazzi.shot(name, look, height) {
            HomeContent(h, "Good evening, Aarav", wide = false, locationGranted = true, burst = false, onBurstDone = {}, a = HomeActions())
        }

    @Test fun home_light() = home(Look.Light)
    @Test fun home_dark() = home(Look.Dark)
    @Test fun home_large() = home(Look.Large, height = 4200)
    @Test fun home_goal_light() = home(Look.Light, Fixtures.home(steps = 7420, buddySteps = 5800), "home_goal")
    @Test fun home_demo_dark() = home(Look.Dark, Fixtures.home(demo = true, walking = true), "home_demo")

    /** What you see when you open the app: Home above the fold with the real bottom navigation. */
    private fun fold(look: Look, h: com.walkbuddy.ui.HomeUi = Fixtures.home(), name: String = "fold") = paparazzi.shot(name, look, 2340) {
        androidx.compose.material3.Scaffold(bottomBar = { WbBottomBar(navItems, 0) {} }) { pad ->
            androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                HomeContent(h, "Good evening, Aarav", wide = false, locationGranted = true, burst = false, onBurstDone = {}, a = HomeActions())
            }
        }
    }
    @Test fun fold_light() = fold(Look.Light)
    @Test fun fold_dark() = fold(Look.Dark)
    @Test fun fold_large() = fold(Look.Large)
    @Test fun fold_long_text() = paparazzi.shot("fold_long", Look.Large, 2340) {
        androidx.compose.material3.Scaffold(bottomBar = { WbBottomBar(navItems, 0) {} }) { pad ->
            androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                HomeContent(
                    Fixtures.home(buddyName = "Lakshmi Narayanan", buddySteps = 4100), "Good evening, Priyadarshini Venkataraman",
                    wide = false, locationGranted = false, burst = false, onBurstDone = {}, a = HomeActions(),
                )
            }
        }
    }
    @Test fun tablet_light() = paparazzi.shot("tablet", Look.Light, 1600, DeviceConfig.NEXUS_10) {
        Row(Modifier.fillMaxSize()) {
            WbRail(navItems, 0) {}
            Box(Modifier.weight(1f)) {
                HomeContent(Fixtures.home(), "Good evening, Aarav", wide = true, locationGranted = true, burst = false, onBurstDone = {}, a = HomeActions())
            }
        }
    }

    /** The launcher icon as a circle mask would show it, the splash on light and black, and the app-bar glyphs. */
    @Test fun brand_light() = paparazzi.shot("brand", Look.Light, 1500) {
        androidx.compose.foundation.layout.Column(Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(216.dp).clip(androidx.compose.foundation.shape.CircleShape)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.walkbuddy.R.drawable.ic_launcher_background_art), null, Modifier.requiredSize(324.dp).align(androidx.compose.ui.Alignment.Center))
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.walkbuddy.R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(324.dp).align(androidx.compose.ui.Alignment.Center))
            }
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(170.dp).background(androidx.compose.ui.graphics.Color(0xFFFBF6F9))) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.walkbuddy.R.drawable.ic_splash), null, Modifier.fillMaxSize())
                }
                Box(Modifier.size(170.dp).background(androidx.compose.ui.graphics.Color.Black)) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.walkbuddy.R.drawable.ic_splash), null, Modifier.fillMaxSize())
                }
            }
        }
    }

    @Test fun live_light() = paparazzi.shot("live", Look.Light, 2800) {
        LiveContent(Fixtures.live, 5_247, 7_000, UnitSystem.Metric, false, WalkActions())
    }
    @Test fun live_dark() = paparazzi.shot("live", Look.Dark, 2800) {
        LiveContent(Fixtures.live, 5_247, 7_000, UnitSystem.Metric, false, WalkActions())
    }
    @Test fun live_large() = paparazzi.shot("live", Look.Large, 3600) {
        LiveContent(Fixtures.live, 5_247, 7_000, UnitSystem.Metric, false, WalkActions())
    }

    @Test fun lobby_light() = paparazzi.shot("lobby", Look.Light, 2400) { LobbyContent(Fixtures.lobby, WalkActions()) }
    @Test fun lobby_dark() = paparazzi.shot("lobby", Look.Dark, 2400) { LobbyContent(Fixtures.lobby, WalkActions()) }

    @Test fun summary_light() = paparazzi.shot("summary", Look.Light, 2400) { SummaryContent(Fixtures.summary, WalkActions()) }
    @Test fun summary_dark() = paparazzi.shot("summary", Look.Dark, 2400) { SummaryContent(Fixtures.summary, WalkActions()) }

    @Test fun recap_light() = paparazzi.shot("recap", Look.Light) { RecapContent(Fixtures.slides, {}, { _, _ -> }) }
    @Test fun recap_dark_together() = paparazzi.shot("recap_together", Look.Dark) { RecapContent(Fixtures.slides, {}, { _, _ -> }, initialPage = 2) }

    @Test fun badges_light() = paparazzi.shot("badges", Look.Light) { BadgesContent(Fixtures.badges, {}) }
    @Test fun badges_dark() = paparazzi.shot("badges", Look.Dark) { BadgesContent(Fixtures.badges, {}) }

    private fun trends(look: Look, height: Int = 4400) = paparazzi.shot("trends", look, height) {
        TrendsContent(Fixtures.report, Fixtures.bars, Fixtures.trends, "5/12", false, {}, {}, {}, {}, {})
    }
    @Test fun trends_light() = trends(Look.Light)
    @Test fun trends_dark() = trends(Look.Dark)

    @Test fun mood_light() = paparazzi.shot("mood", Look.Light, 1400) {
        Surface(Modifier.fillMaxSize()) { SectionCard("How are you feeling?", Modifier.padding(16.dp)) { MoodCheckIn(onSave = { _, _ -> }) } }
    }

    private fun couple(look: Look) = paparazzi.shot("couple", look, 4200) {
        CoupleContent(
            Fixtures.settings.anniversaryDate, "our anniversary", UnitSystem.Metric, Fixtures.dates, Fixtures.spots,
            31_400.0, Fixtures.bundle.coupleDays, false, Fixtures.today, System.currentTimeMillis(), CoupleActions(),
        )
    }
    @Test fun couple_light() = couple(Look.Light)
    @Test fun couple_dark() = couple(Look.Dark)

    @Test fun settings_light() = paparazzi.shot("settings", Look.Light, 6200) { SettingsContent(Fixtures.settings.copy(caloriesEnabled = true, foodEnabled = true), SettingsActions()) }
    @Test fun settings_dark() = paparazzi.shot("settings", Look.Dark, 6200) { SettingsContent(Fixtures.settings.copy(caloriesEnabled = true, foodEnabled = true), SettingsActions()) }

    @Test fun fuel_light() = paparazzi.shot("fuel", Look.Light, 3000) {
        FuelContent(Fixtures.settings.copy(caloriesEnabled = true, foodEnabled = true), Fixtures.fuel, false, {}, {})
    }

    private fun onboarding(page: Int, look: Look = Look.Light) = paparazzi.shot("onboarding$page", look, 2340) {
        OnboardingContent(
            page = page, name = if (page == 0) "Aarav" else "", onNameChange = {}, units = UnitSystem.Metric, onUnits = {}, heroSteps = 6_284,
            activityGranted = false, notificationsGranted = true, onAllowActivity = {}, onAllowNotifications = {}, onContinue = {}, onBack = {}, onFinish = {},
        )
    }
    @Test fun onboarding0_light() = onboarding(0)
    @Test fun onboarding0_dark() = onboarding(0, Look.Dark)
    @Test fun onboarding1_light() = onboarding(1)
    @Test fun onboarding2_light() = onboarding(2)
}
