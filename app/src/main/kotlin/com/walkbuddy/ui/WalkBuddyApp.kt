@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.walkbuddy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.walkbuddy.notify.PeriodicSampler
import com.walkbuddy.session.Phase
import com.walkbuddy.ui.screens.CoupleScreen
import com.walkbuddy.ui.screens.FuelScreen
import com.walkbuddy.ui.screens.HomeScreen
import com.walkbuddy.ui.screens.OnboardingScreen
import com.walkbuddy.ui.screens.SettingsScreen
import com.walkbuddy.ui.screens.WalkFlow
import com.walkbuddy.ui.screens.WeeklyScreen

private object Route {
    const val Home = "home"
    const val Week = "week"
    const val Fuel = "fuel"
    const val Us = "us"
    const val Settings = "settings"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Home", Icons.Default.Home),
    Tab(Route.Week, "Week", Icons.Default.DateRange),
    Tab(Route.Fuel, "Fuel", Icons.Default.ShoppingCart),
    Tab(Route.Us, "Us", Icons.Default.Favorite),
    Tab(Route.Settings, "Settings", Icons.Default.Settings),
)

@Composable
fun WalkBuddyApp(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
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
                    onFinish = {
                        vm.saveSettings { ensurePeerId(); setOnboardingDone() }
                        PeriodicSampler.schedule(ctx)
                    },
                )
            }
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
    val route = entry?.destination?.route ?: Route.Home
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(tabs.firstOrNull { it.route == route }?.label ?: "Walk Buddy") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Route.Home, modifier = Modifier.padding(pad)) {
            composable(Route.Home) { HomeScreen(vm) }
            composable(Route.Week) { WeeklyScreen(vm) }
            composable(Route.Fuel) { FuelScreen(vm) }
            composable(Route.Us) { CoupleScreen(vm) }
            composable(Route.Settings) { SettingsScreen(vm) }
        }
    }
}
