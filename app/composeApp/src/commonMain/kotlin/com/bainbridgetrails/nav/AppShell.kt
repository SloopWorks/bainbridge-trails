package com.bainbridgetrails.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.bainbridgetrails.auth.isSignedIn
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.map.StyleSpikeScreen
import com.bainbridgetrails.record.RecordScreen
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.nav_map
import com.bainbridgetrails.resources.nav_me
import com.bainbridgetrails.resources.nav_record
import com.bainbridgetrails.resources.nav_recording
import com.bainbridgetrails.resources.nav_trails
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.ui.AttributionScreen
import com.bainbridgetrails.ui.CelebrationScreen
import com.bainbridgetrails.ui.PrivacyScreen
import com.bainbridgetrails.ui.TrailListQuery
import com.bainbridgetrails.ui.TrailsListState
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.ui.LeaderboardScreen
import com.bainbridgetrails.ui.MapTab
import com.bainbridgetrails.ui.MeScreen
import com.bainbridgetrails.ui.OnboardingScreen
import com.bainbridgetrails.ui.ProfileScreen
import com.bainbridgetrails.ui.SettingsScreen
import com.bainbridgetrails.ui.TrailDetailScreen
import com.bainbridgetrails.ui.ShowcaseScreen
import com.bainbridgetrails.ui.TrailsScreen
import com.bainbridgetrails.ui.components.BarItem
import com.bainbridgetrails.ui.components.BtBottomBar
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private class Tab(val route: Route, val label: StringResource, val icon: DrawableResource, val selectedIcon: DrawableResource)

private val StartTabs = listOf(
    Tab(MapRoute, Res.string.nav_map, BtIcons.Map, BtIcons.MapFilled),
    Tab(TrailsRoute, Res.string.nav_trails, BtIcons.Hiking, BtIcons.HikingFilled),
)
private val EndTabs = listOf(Tab(MeRoute, Res.string.nav_me, BtIcons.Person, BtIcons.PersonFilled))
private val BarRoutes = listOf<Route>(MapRoute, TrailsRoute, RecordRoute, MeRoute)

/** Debug launch options (spike scripts) pick the first screen; a normal launch starts on Map. */
internal fun startRouteFor(launch: SpikeLaunch?): Route = when (launch?.screen) {
    null -> MapRoute
    SpikeLaunch.Screen.RECORD -> RecordRoute
    SpikeLaunch.Screen.STYLE -> StyleSpikeRoute
    SpikeLaunch.Screen.SHOWCASE -> ShowcaseRoute
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
        val debugScreen = DebugLaunch.screen
        if (debugScreen != null) {
            openDebugScreen(nav, graph, debugScreen)
            return@LaunchedEffect
        }
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

    // ux.md §3.4: the bar stays visible under Trail detail (its Start walk sits above it).
    val showBar = BarRoutes.any { destination.isTab(it) } || destination?.hasRoute(TrailDetailRoute::class) == true
    val recording = recordingState is RecordingState.Recording
    // Content stops at the bar's top edge (80dp + nav inset); the Record disc rises over it.
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barPadding = if (showBar) BtTheme.spacing.bottomBarHeight + navInset else 0.dp
    Box(Modifier.fillMaxSize()) {
        NavHost(
            nav,
            startDestination = start,
            modifier = Modifier.fillMaxSize().padding(bottom = barPadding).consumeWindowInsets(PaddingValues(bottom = barPadding)),
        ) {
            composable<MapRoute> {
                MapTab(
                    graph, settings, launch?.camera,
                    onTrail = { nav.navigate(TrailDetailRoute(it)) },
                    onLeaderboard = { nav.navigate(LeaderboardRoute) },
                    onSearch = { nav.selectTab(TrailsRoute) },
                    onRecord = { nav.selectTab(RecordRoute) },
                )
            }
            composable<TrailsRoute> { TrailsScreen(graph, settings, onTrail = { nav.navigate(TrailDetailRoute(it)) }, onLeaderboard = { nav.navigate(LeaderboardRoute) }) }
            composable<RecordRoute> { RecordScreen(launch ?: SpikeLaunch(), Modifier.fillMaxSize()) }
            composable<MeRoute> {
                MeScreen(
                    graph, settings,
                    onTrail = { nav.navigate(TrailDetailRoute(it)) },
                    onLeaderboard = { nav.navigate(LeaderboardRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    onProfile = { nav.navigate(ProfileRoute) },
                    onMap = { nav.selectTab(MapRoute) },
                    onSeeAllInProgress = {
                        TrailsListState.query = TrailListQuery(statuses = setOf(TrailStatus.PARTWAY, TrailStatus.NEARLY_DONE))
                        nav.selectTab(TrailsRoute)
                    },
                )
            }
            composable<TrailDetailRoute> { entry ->
                TrailDetailScreen(graph, settings, entry.toRoute<TrailDetailRoute>().trailId, onBack = { nav.popBackStack() }, onStartWalk = { nav.selectTab(RecordRoute) }, onShowOnMap = { nav.selectTab(MapRoute) })
            }
            composable<LeaderboardRoute> { LeaderboardScreen(graph, onBack = { nav.popBackStack() }, onSignIn = { nav.navigate(ProfileRoute) }) }
            composable<ProfileRoute> { ProfileScreen(graph, onBack = { nav.popBackStack() }) }
            composable<SettingsRoute> {
                SettingsScreen(
                    graph, settings,
                    onBack = { nav.popBackStack() },
                    onShowIntro = { nav.navigate(OnboardingRoute) },
                    onAccount = { nav.navigate(ProfileRoute) },
                    onAttribution = { nav.navigate(AttributionRoute) },
                    onPrivacy = { nav.navigate(PrivacyRoute) },
                )
            }
            composable<AttributionRoute> { AttributionScreen(onBack = { nav.popBackStack() }) }
            composable<PrivacyRoute> { PrivacyScreen(onBack = { nav.popBackStack() }) }
            composable<OnboardingRoute> {
                OnboardingScreen(onDone = {
                    graph.settings.update { it.copy(onboardingDone = true) }
                    // Back to Map (F1 step 2). Onboarding is pushed over Map (first run) or over Settings (Show intro).
                    if (!nav.popBackStack<MapRoute>(inclusive = false)) {
                        nav.navigate(MapRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
                    }
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
            composable<ShowcaseRoute> { ShowcaseScreen(section = launch?.section) }
        }
        if (showBar) {
            fun Tab.item(label: String) = BarItem(label, icon, selectedIcon, destination.isTab(route), { nav.selectTab(route) })
            BtBottomBar(
                start = StartTabs.map { it.item(stringResource(it.label)) },
                end = EndTabs.map { it.item(stringResource(it.label)) },
                recordLabel = stringResource(if (recording) Res.string.nav_recording else Res.string.nav_record),
                recording = recording,
                onRecord = { nav.selectTab(RecordRoute) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun rememberController(graph: AppGraph.Installed): State<RecordingController?> =
    produceState<RecordingController?>(null, graph) { value = runCatching { graph.controller().await() }.getOrNull() }

/** Debug binaries: open the screen named by [DebugLaunch.screen] (simulator screenshots; no tap tooling there). */
private suspend fun openDebugScreen(nav: NavController, graph: AppGraph.Installed, screen: String) {
    if (screen != "onboarding") graph.settings.updateNow { it.copy(onboardingDone = true) }
    if (DebugLaunch.devSignIn && !graph.auth.load().isSignedIn) graph.auth.signInWithDev()
    when (screen) {
        "trails" -> nav.selectTab(TrailsRoute)
        "me" -> nav.selectTab(MeRoute)
        "leaderboard" -> nav.navigate(LeaderboardRoute)
        "profile" -> nav.navigate(ProfileRoute)
        "settings" -> nav.navigate(SettingsRoute)
        "attribution" -> nav.navigate(AttributionRoute)
        "privacy" -> nav.navigate(PrivacyRoute)
        "onboarding" -> nav.navigate(OnboardingRoute)
    }
}
