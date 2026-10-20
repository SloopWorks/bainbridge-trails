package com.bainbridgetrails.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bainbridgetrails.analytics.AnalyticsEvents
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.map.StyleSpikeScreen
import com.bainbridgetrails.record.RecordScreen
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.nav_map
import com.bainbridgetrails.resources.nav_me
import com.bainbridgetrails.resources.nav_record
import com.bainbridgetrails.resources.nav_record_active_a11y
import com.bainbridgetrails.resources.nav_trails
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.ui.CelebrationScreen
import com.bainbridgetrails.ui.LeaderboardScreen
import com.bainbridgetrails.ui.MapTab
import com.bainbridgetrails.ui.MeScreen
import com.bainbridgetrails.ui.OnboardingScreen
import com.bainbridgetrails.ui.ProfileScreen
import com.bainbridgetrails.ui.SettingsScreen
import com.bainbridgetrails.ui.TrailDetailScreen
import com.bainbridgetrails.ui.TrailsScreen
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private class Tab(val route: Route, val label: StringResource, val glyph: String)

// Placeholder glyphs until the icon set lands with the UX spec (no icon dependency yet).
private val Tabs = listOf(
    Tab(MapRoute, Res.string.nav_map, "◎"),
    Tab(TrailsRoute, Res.string.nav_trails, "≡"),
    Tab(RecordRoute, Res.string.nav_record, "●"),
    Tab(MeRoute, Res.string.nav_me, "☺"),
)

/** Debug launch options (spike scripts) pick the first screen; a normal launch starts on Map. */
internal fun startRouteFor(launch: SpikeLaunch?): Route = when (launch?.screen) {
    null -> MapRoute
    SpikeLaunch.Screen.RECORD -> RecordRoute
    SpikeLaunch.Screen.STYLE -> StyleSpikeRoute
}

/** Tab switch: one copy of each tab, state saved/restored, Back from any tab returns to the start tab (F0 step 3). */
fun NavController.selectTab(route: Route) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun NavDestination?.isTab(tab: Route) = this?.hasRoute(tab::class) == true

/** Bottom-bar shell + NavHost. Screens get state from [graph]'s repositories; no ViewModels. */
@Composable
fun AppShell(graph: AppGraph.Installed, settings: AppSettings, launch: SpikeLaunch?) {
    val nav = rememberNavController()
    val start = startRouteFor(launch)
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val controller by rememberController(graph)
    val recordingState = controller?.state?.let { produceState<RecordingState>(it.value, it) { it.collect { s -> value = s } }.value }

    // F1: first run shows onboarding over Map (spike launches skip it).
    LaunchedEffect(Unit) {
        if (launch == null && !settings.onboardingDone) nav.navigate(OnboardingRoute)
    }
    LaunchedEffect(destination) {
        destination?.route?.let { AppGraph.analytics.track(AnalyticsEvents.SCREEN_VIEW, mapOf("route" to it.substringAfterLast('.').substringBefore('/').substringBefore('?'))) }
    }
    // F6: a walk that completes trails (or a debug tool) opens the celebration once per key.
    LaunchedEffect(Unit) {
        AppGraph.celebrations.collect { c ->
            if (AppGraph.celebrated.add(c.key)) nav.navigate(CelebrationRoute(c.key, c.trailIds))
        }
    }

    val showBar = Tabs.any { destination.isTab(it.route) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) NavigationBar {
                for (tab in Tabs) {
                    val active = tab.route == RecordRoute && recordingState is RecordingState.Recording
                    val label = stringResource(tab.label)
                    val a11y = if (active) stringResource(Res.string.nav_record_active_a11y) else label
                    NavigationBarItem(
                        selected = destination.isTab(tab.route),
                        onClick = { nav.selectTab(tab.route) },
                        icon = {
                            BadgedBox(badge = { if (active) Badge() }) { Text(tab.glyph) }
                        },
                        label = { Text(label) },
                        modifier = Modifier.semantics { contentDescription = a11y },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            composable<MapRoute> { MapTab(graph, settings, launch?.camera) }
            composable<TrailsRoute> { TrailsScreen(graph, settings, onTrail = { nav.navigate(TrailDetailRoute(it)) }) }
            composable<RecordRoute> { RecordScreen(launch ?: SpikeLaunch(), Modifier.fillMaxSize()) }
            composable<MeRoute> {
                MeScreen(
                    graph, settings,
                    onTrail = { nav.navigate(TrailDetailRoute(it)) },
                    onLeaderboard = { nav.navigate(LeaderboardRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    onProfile = { nav.navigate(ProfileRoute) },
                )
            }
            composable<TrailDetailRoute> { entry ->
                TrailDetailScreen(graph, settings, entry.toRoute<TrailDetailRoute>().trailId, onBack = { nav.popBackStack() }, onStartWalk = { nav.selectTab(RecordRoute) })
            }
            composable<LeaderboardRoute> { LeaderboardScreen(onBack = { nav.popBackStack() }) }
            composable<ProfileRoute> { ProfileScreen(onBack = { nav.popBackStack() }) }
            composable<SettingsRoute> {
                SettingsScreen(graph, settings, onBack = { nav.popBackStack() }, onShowIntro = { nav.navigate(OnboardingRoute) })
            }
            composable<OnboardingRoute> {
                OnboardingScreen(onDone = {
                    graph.settings.update { it.copy(onboardingDone = true) }
                    nav.selectTab(MapRoute)
                })
            }
            composable<CelebrationRoute> { entry ->
                val route = entry.toRoute<CelebrationRoute>()
                CelebrationScreen(
                    graph, route.trailIds,
                    onContinue = { nav.popBackStack() },
                    onViewTrail = { id -> nav.navigate(TrailDetailRoute(id)) { popUpTo<CelebrationRoute> { inclusive = true } } },
                )
            }
            composable<StyleSpikeRoute> { StyleSpikeScreen(launch ?: SpikeLaunch(), Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun rememberController(graph: AppGraph.Installed): State<RecordingController?> =
    produceState<RecordingController?>(null, graph) { value = runCatching { graph.controller().await() }.getOrNull() }
