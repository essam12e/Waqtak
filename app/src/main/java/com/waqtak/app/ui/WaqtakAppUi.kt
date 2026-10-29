package com.waqtak.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.waqtak.app.R
import com.waqtak.app.graph
import com.waqtak.app.ui.screens.DrinksScreen
import com.waqtak.app.ui.screens.HistoryScreen
import com.waqtak.app.ui.screens.HomeScreen
import com.waqtak.app.ui.screens.OnboardingScreen
import com.waqtak.app.ui.screens.ReadinessScreen
import com.waqtak.app.ui.screens.ScheduleScreen
import com.waqtak.app.ui.screens.ScienceScreen
import com.waqtak.app.ui.screens.SettingsScreen
import com.waqtak.app.ui.screens.TimerScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SCHEDULE = "schedule"
    const val TIMER = "timer"
    const val DRINKS = "drinks"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val READINESS = "readiness"
    const val SCIENCE = "science"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Default.Home),
    Tab(Routes.SCHEDULE, R.string.nav_schedule, Icons.Default.CalendarMonth),
    Tab(Routes.DRINKS, R.string.nav_drinks, Icons.Default.LocalDrink),
    Tab(Routes.HISTORY, R.string.nav_history, Icons.Default.History),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Default.Settings),
)

fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(Routes.HOME) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun WaqtakAppUi(pendingRoute: MutableState<String?>) {
    val ctx = LocalContext.current
    val settings by ctx.graph.settings.flow.collectAsState(initial = null)
    val s = settings ?: return // first DataStore read is quick; avoid flashing onboarding
    val nav = rememberNavController()
    val start = androidx.compose.runtime.remember { if (s.onboardingDone) Routes.HOME else Routes.ONBOARDING }

    LaunchedEffect(pendingRoute.value, s.onboardingDone) {
        val r = pendingRoute.value
        if (r != null && s.onboardingDone) {
            nav.navigate(r) { launchSingleTop = true }
            pendingRoute.value = null
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (current != Routes.ONBOARDING && current != null) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label), maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.SCHEDULE) { ScheduleScreen() }
            composable(Routes.TIMER) { TimerScreen() }
            composable(Routes.DRINKS) { DrinksScreen() }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.READINESS) { ReadinessScreen() }
            composable(Routes.SCIENCE) { ScienceScreen() }
        }
    }
}
