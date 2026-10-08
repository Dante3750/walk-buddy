@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.walkbuddy.R
import com.walkbuddy.ui.components.NavItem
import com.walkbuddy.ui.components.TabGlyph
import com.walkbuddy.ui.components.WbBottomBar
import com.walkbuddy.ui.components.WbRail
import com.walkbuddy.notify.PeriodicSampler
import com.walkbuddy.session.GroupPhase
import com.walkbuddy.session.Phase
import com.walkbuddy.ui.screens.GroupCreateScreen
import com.walkbuddy.ui.screens.GroupFlow
import com.walkbuddy.ui.screens.GroupJoinScreen
import com.walkbuddy.ui.screens.ScanScreen
import com.walkbuddy.ui.screens.BadgesScreen
import com.walkbuddy.ui.screens.CoupleScreen
import com.walkbuddy.ui.screens.FuelScreen
import com.walkbuddy.ui.screens.HomeScreen
import com.walkbuddy.ui.screens.OnboardingScreen
import com.walkbuddy.ui.screens.RecapScreen
import com.walkbuddy.ui.screens.SettingsScreen
import com.walkbuddy.ui.screens.TrendsScreen
import com.walkbuddy.ui.screens.WalkFlow
import kotlinx.serialization.Serializable

/** Type-safe navigation routes (Navigation Compose 2.8): plain serializable objects instead of strings. */
@Serializable data object HomeRoute
@Serializable data object TrendsRoute
@Serializable data object FuelRoute
@Serializable data object UsRoute
@Serializable data object SettingsRoute
@Serializable data object BadgesRoute
@Serializable data object RecapRoute
@Serializable data object GroupCreateRoute
@Serializable data class GroupJoinRoute(val initial: String = "")
@Serializable data object ScanRoute

private class Tab(val route: Any, val label: Int, val glyph: TabGlyph)

private val tabs = listOf(
    Tab(HomeRoute, R.string.tab_today, TabGlyph.Today),
    Tab(TrendsRoute, R.string.tab_trends, TabGlyph.Trends),
    Tab(FuelRoute, R.string.tab_fuel, TabGlyph.Fuel),
    Tab(UsRoute, R.string.tab_us, TabGlyph.Us),
    Tab(SettingsRoute, R.string.tab_settings, TabGlyph.Settings),
)

@Composable
fun WalkBuddyApp(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val s = settings

    when {
        s == null -> Scaffold { pad ->
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        !s.onboardingDone -> Scaffold { pad ->
            Box(Modifier.padding(pad)) {
                OnboardingScreen(
                    onName = { n -> vm.saveSettings { setName(n) } },
                    onUnits = { u -> vm.saveSettings { setUnits(u) } },
                    onFinish = { demo ->
                        vm.saveSettings { ensurePeerId(); if (demo) setDemoMode(true); setOnboardingDone() }
                        PeriodicSampler.schedule(ctx)
                    },
                )
            }
        }
        group.phase != GroupPhase.Idle -> {
            Scaffold { pad -> Box(Modifier.padding(pad)) { GroupFlow(vm, group) } }
        }
        session.phase != Phase.Idle -> {
            BackHandler(enabled = true) { if (session.phase == Phase.Lobby) vm.leaveLobby() }
            Scaffold { pad -> Box(Modifier.padding(pad)) { WalkFlow(vm, session) } }
        }
        else -> MainScaffold(vm)
    }
}

@Composable
private fun MainScaffold(vm: AppViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val width = rememberWidthClass()
    val snackbar = remember { SnackbarHostState() }
    val pendingAction by vm.pendingAction.collectAsStateWithLifecycle()

    // Badge unlocks: a snackbar is announced by TalkBack by itself, so milestones are never visual-only.
    LaunchedEffect(Unit) {
        vm.badgeEvents.collect { ids ->
            val first = ids.first().title
            snackbar.showSnackbar(if (ids.size == 1) "New badge: $first" else "New badge: $first, and ${ids.size - 1} more")
        }
    }
    LaunchedEffect(pendingAction) {
        if (pendingAction == "recap") {
            vm.pendingAction.value = null
            nav.navigate(RecapRoute)
        }
    }

    val showNav = tabs.any { t -> dest?.hierarchy?.any { it.hasRoute(t.route::class) } == true }

    fun go(route: Any) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val content: @Composable (Modifier) -> Unit = { mod ->
        NavHost(nav, startDestination = HomeRoute, modifier = mod) {
            composable<HomeRoute> {
                HomeScreen(
                    vm, wide = width != WidthClass.Compact,
                    onScan = { nav.navigate(ScanRoute) },
                    onCreateGroup = { nav.navigate(GroupCreateRoute) },
                    onJoinGroup = { nav.navigate(GroupJoinRoute()) },
                )
            }
            composable<GroupCreateRoute> {
                LeaveWhenGroupStarts(vm) { nav.popBackStack(HomeRoute, false) }
                GroupCreateScreen(vm, onBack = { nav.popBackStack() })
            }
            composable<GroupJoinRoute> { e ->
                LeaveWhenGroupStarts(vm) { nav.popBackStack(HomeRoute, false) }
                GroupJoinScreen(
                    vm, initial = e.toRoute<GroupJoinRoute>().initial,
                    onScan = { nav.navigate(ScanRoute) { launchSingleTop = true } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<ScanRoute> {
                ScanScreen(
                    onInvite = { text -> nav.popBackStack(HomeRoute, false); vm.pendingJoin.value = text },
                    onTypeInstead = { nav.popBackStack(); nav.navigate(GroupJoinRoute()) { launchSingleTop = true } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<TrendsRoute> {
                TrendsScreen(vm, onBadges = { nav.navigate(BadgesRoute) }, onRecap = { nav.navigate(RecapRoute) })
            }
            composable<FuelRoute> { FuelScreen(vm) }
            composable<UsRoute> { CoupleScreen(vm) }
            composable<SettingsRoute> { SettingsScreen(vm) }
            composable<BadgesRoute> { BadgesScreen(vm, onBack = { nav.popBackStack() }) }
            composable<RecapRoute> { RecapScreen(vm, onBack = { nav.popBackStack() }) }
        }
    }

    val navItems = tabs.map { NavItem(stringResource(it.label), it.glyph) }
    val selectedIndex = tabs.indexOfFirst { t -> dest?.hierarchy?.any { it.hasRoute(t.route::class) } == true }

    if (width == WidthClass.Compact) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (showNav) WbBottomBar(navItems, selectedIndex) { go(tabs[it].route) } },
        ) { pad -> content(Modifier.padding(pad)) }
    } else {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            Row(Modifier.padding(pad).fillMaxSize(), horizontalArrangement = Arrangement.Start) {
                if (showNav) WbRail(navItems, selectedIndex) { go(tabs[it].route) }
                content(Modifier.weight(1f))
            }
        }
    }
}

/** The setup screens are replaced by the live group screens; drop them from the back stack so Back never returns to them. */
@Composable
private fun LeaveWhenGroupStarts(vm: AppViewModel, leave: () -> Unit) {
    val group by vm.group.collectAsStateWithLifecycle()
    LaunchedEffect(group.phase) { if (group.phase != GroupPhase.Idle) leave() }
}
