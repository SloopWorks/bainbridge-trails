package com.bainbridgetrails.nav

import com.bainbridgetrails.data.LocalGoal
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.bainbridgetrails.play.spike.FxSpikeScreen
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
import com.bainbridgetrails.ui.WalkDetailScreen
import com.bainbridgetrails.ui.nowMs
import com.bainbridgetrails.ui.SettingsScreen
import com.bainbridgetrails.ui.TrailDetailScreen
import com.bainbridgetrails.ui.ShowcaseScreen
import com.bainbridgetrails.ui.TrailsScreen
import com.bainbridgetrails.ui.components.BarItem
import com.bainbridgetrails.ui.components.BtBottomBar
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.RecordingPill
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtEasing
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private class Tab(val route: Route, val label: StringResource, val icon: DrawableResource, val selectedIcon: DrawableResource)

private val StartTabs = listOf(
    Tab(MapRoute, Res.string.nav_map, BtIcons.Map, BtIcons.MapFilled),
    Tab(TrailsRoute, Res.string.nav_trails, BtIcons.Hiking, BtIcons.HikingFilled),
)
private val EndTabs = listOf(Tab(MeRoute, Res.string.nav_me, BtIcons.Person, BtIcons.PersonFilled))
private val BarRoutes = listOf<Route>(MapRoute, TrailsRoute, MeRoute)

/** The Record disc rises this far above the bar (ux.md §2.2); the pill keeps 12dp clear of it. */
private val RecordDiscRise = 16.dp

/** Debug launch options (spike scripts) pick the first screen; a normal launch starts on Map. */
internal fun startRouteFor(launch: SpikeLaunch?): Route = when (launch?.screen) {
    null -> MapRoute
    SpikeLaunch.Screen.RECORD -> RecordRoute
    SpikeLaunch.Screen.STYLE -> StyleSpikeRoute
    SpikeLaunch.Screen.SHOWCASE -> ShowcaseRoute
    SpikeLaunch.Screen.FX -> FxSpikeRoute
}

/** Opens the Record flow as a full-screen layer over the current screen (ux.md §2.5); never a tab of its own. */
fun NavController.openRecord() = navigate(RecordRoute) { launchSingleTop = true }

/**
 * Leaves the Record flow ("close" when idle, "minimize" while recording: the walk continues, the pill shows).
 * Returns to whatever was under it; Map when Record was the first screen (debug `screen=record` launches).
 */
fun NavController.closeRecord() {
    if (previousBackStackEntry == null) navigate(MapRoute) { popUpTo<RecordRoute> { inclusive = true } } else popBackStack()
}

/** Tab switch: one copy of each tab, state saved/restored, Back from any tab returns to the start tab (F0 step 3). */
fun NavController.selectTab(route: Route) {
    // Re-tapping the selected tab returns to its root (e.g. Trails → detail → tap Trails = the list), instead of
    // restoreState re-pushing the saved detail.
    val tabEntry = currentBackStack.value.lastOrNull { e -> BarRoutes.any { e.destination.isTab(it) } }
    if (tabEntry != null && tabEntry.destination.isTab(route) && currentDestination?.isTab(route) != true) {
        popBackStack(tabEntry.destination.id, inclusive = false)
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
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
    // Only what the shell decides on (bar label, pill shown, pending Record flow): a walk's every fix must not
    // recompose the whole shell. The pill collects the live state itself.
    val shell = controller?.let { c ->
        remember(c) { c.state.map(::shellRecording).distinctUntilChanged() }.collectAsState(shellRecording(c.state.value)).value
    } ?: ShellRecording()

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
    // F6: a walk that completes trails (or a debug tool) opens the celebration once per key. A finished walk's
    // celebration sits over the Record flow, so Continue reveals its results underneath (ux.md §3.5f).
    LaunchedEffect(Unit) {
        AppGraph.celebrations.collect { c ->
            if (!AppGraph.celebrated.add(c.key)) return@collect
            val done = runCatching { graph.controller().await() }.getOrNull()?.state?.value as? RecordingState.Done
            if (done?.summary?.sessionId == c.key && nav.currentDestination?.hasRoute(RecordRoute::class) != true) nav.openRecord()
            nav.navigate(CelebrationRoute(c.key, c.trailIds, c.island, c.preview))
        }
    }
    // F0 step 5 / TL-4: a recovered walk (Count it / Discard) or results finalised away from the Record flow
    // (notification Stop, launch auto-finalise) open the Record flow.
    val pendingKind = shell.pendingKind
    LaunchedEffect(pendingKind, destination?.route) {
        if (pendingKind == null || destination == null) return@LaunchedEffect
        val blocked = destination.isTab(RecordRoute) || destination.isTab(OnboardingRoute) || destination.hasRoute(CelebrationRoute::class)
        if (!blocked) nav.openRecord()
    }

    // Recording notification tapped: open the Record flow over whatever is showing (not over onboarding).
    val openRecordRequested by NavRequests.openRecord.collectAsState()
    LaunchedEffect(openRecordRequested, destination?.route) {
        if (!openRecordRequested || destination == null || destination.isTab(OnboardingRoute)) return@LaunchedEffect
        NavRequests.consumeOpenRecord()
        if (!destination.isTab(RecordRoute) && !destination.hasRoute(CelebrationRoute::class)) nav.openRecord()
    }

    // ux.md §3.4: the bar stays visible under Trail detail (its Start walk sits above it).
    val showBar = BarRoutes.any { destination.isTab(it) } || destination?.hasRoute(TrailDetailRoute::class) == true
    val reduced = BtTheme.motion.reduced
    val recording = shell.recording
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
                    onRecord = { nav.openRecord() },
                )
            }
            composable<TrailsRoute> { TrailsScreen(graph, settings, onTrail = { nav.navigate(TrailDetailRoute(it)) }, onLeaderboard = { nav.navigate(LeaderboardRoute) }) }
            composable<RecordRoute>(
                // ux.md §2.5: slides up over the shell (medium4, emphasizedDecelerate); minimizing slides it down.
                enterTransition = { if (reduced) fadeIn(tween(BtDuration.short3)) else slideInVertically(tween(BtDuration.medium4, easing = BtEasing.emphasizedDecelerate)) { it } },
                popExitTransition = { if (reduced) fadeOut(tween(BtDuration.short3)) else slideOutVertically(tween(BtDuration.medium4, easing = BtEasing.emphasizedAccelerate)) { it } },
                exitTransition = { fadeOut(tween(BtDuration.short3)) },
                popEnterTransition = { fadeIn(tween(BtDuration.short3)) },
            ) {
                RecordScreen(
                    settings,
                    onClose = { nav.closeRecord() },
                    onSignIn = { nav.navigate(ProfileRoute) },
                    modifier = Modifier.fillMaxSize(),
                    launch = launch ?: SpikeLaunch(),
                )
            }
            composable<MeRoute> {
                MeScreen(
                    graph, settings,
                    onTrail = { nav.navigate(TrailDetailRoute(it)) },
                    onLeaderboard = { nav.navigate(LeaderboardRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    onProfile = { nav.navigate(ProfileRoute) },
                    onWalk = { nav.navigate(WalkDetailRoute(it)) },
                    onMap = { nav.selectTab(MapRoute) },
                    onSeeAllInProgress = {
                        TrailsListState.query = TrailListQuery(statuses = setOf(TrailStatus.PARTWAY, TrailStatus.NEARLY_DONE))
                        com.bainbridgetrails.ui.RoadsListState.query = com.bainbridgetrails.ui.RoadListQuery(statuses = setOf(TrailStatus.PARTWAY, TrailStatus.NEARLY_DONE))
                        nav.selectTab(TrailsRoute)
                    },
                    onRoadType = { group ->
                        com.bainbridgetrails.ui.RoadsListState.query = com.bainbridgetrails.ui.RoadListQuery(groups = setOf(group))
                        nav.selectTab(TrailsRoute)
                    },
                )
            }
            composable<TrailDetailRoute> { entry ->
                TrailDetailScreen(graph, settings, entry.toRoute<TrailDetailRoute>().trailId, onBack = { nav.popBackStack() }, onStartWalk = { nav.openRecord() }, onShowOnMap = { nav.selectTab(MapRoute) })
            }
            composable<WalkDetailRoute> { entry ->
                WalkDetailScreen(graph, settings, entry.toRoute<WalkDetailRoute>().sessionId, onBack = { nav.popBackStack() }, onTrail = { nav.navigate(TrailDetailRoute(it)) })
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
            composable<CelebrationRoute>(
                enterTransition = { fadeIn(tween(BtDuration.short3)) },
                popExitTransition = { fadeOut(tween(BtDuration.short3)) },
            ) { entry ->
                val route = entry.toRoute<CelebrationRoute>()
                CelebrationScreen(
                    graph, route.sessionId, route.trailIds, island = route.island, preview = route.preview,
                    onContinue = { nav.popBackStack() },
                    onViewTrail = { id -> nav.navigate(TrailDetailRoute(id)) { popUpTo<CelebrationRoute> { inclusive = true } } },
                )
            }
            composable<StyleSpikeRoute> { StyleSpikeScreen(launch ?: SpikeLaunch(), Modifier.fillMaxSize()) }
            composable<FxSpikeRoute> { FxSpikeScreen(launch ?: SpikeLaunch(), Modifier.fillMaxSize()) }
            composable<ShowcaseRoute> { ShowcaseScreen(section = launch?.section) }
        }
        if (showBar) {
            // Roads (ux.md 1.3): the list tab shows a road, not a hiker.
            val roads = LocalGoal.current.isRoads
            fun Tab.item(label: String) = BarItem(
                label,
                if (roads && route == TrailsRoute) BtIcons.Road else icon,
                if (roads && route == TrailsRoute) BtIcons.RoadFilled else selectedIcon,
                destination.isTab(route),
                { nav.selectTab(route) },
            )
            BtBottomBar(
                start = StartTabs.map { it.item(stringResource(LocalGoal.current.s(it.label))) },
                end = EndTabs.map { it.item(stringResource(LocalGoal.current.s(it.label))) },
                recordLabel = stringResource(if (recording) Res.string.nav_recording else Res.string.nav_record),
                recording = recording,
                onRecord = { nav.openRecord() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // ux.md §2.5: while a walk records and the Record flow is minimized, the pill floats above the bar.
        val onRecordFlow = destination.isTab(RecordRoute) || destination?.hasRoute(CelebrationRoute::class) == true || destination.isTab(OnboardingRoute)
        val c = controller
        if (recording && c != null && destination != null && !onRecordFlow) {
            LiveRecordingPill(
                c,
                settings,
                onClick = { nav.openRecord() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (if (showBar) barPadding + RecordDiscRise else navInset) + 12.dp),
            )
        }
    }
}

/** What [AppShell] reads from the recording state; equal values skip recomposition. */
private data class ShellRecording(val recording: Boolean = false, val pendingKind: String? = null)

private fun shellRecording(s: RecordingState) = ShellRecording(
    recording = s is RecordingState.Recording,
    pendingKind = when (s) {
        is RecordingState.Recover -> "recover-" + s.sessionId
        is RecordingState.Done -> "done-" + s.summary.sessionId
        else -> null
    },
)

/** [RecordingPill] with its own 1 s clock and its own state collection (only the pill recomposes per second / fix). */
@Composable
private fun LiveRecordingPill(controller: RecordingController, settings: AppSettings, onClick: () -> Unit, modifier: Modifier) {
    val r = controller.state.collectAsState().value as? RecordingState.Recording ?: return
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(r.startedMs) {
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    RecordingPill((now - r.startedMs).coerceAtLeast(0), r.distanceM, settings.units, onClick, modifier)
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
        "fx" -> nav.navigate(FxSpikeRoute) // MapFx Stage 0 spike (docs/play/spikes/fx.md)
        "celebrate-island" -> AppGraph.requestCelebration(AppGraph.Celebration("debug-island-${nowMs()}", emptyList(), island = true, preview = true))
        "walk" -> graph.walks.refreshNow().walks.firstOrNull()?.let { nav.navigate(WalkDetailRoute(it.sessionId)) } // newest walk
        else -> when {
            screen.startsWith("trail:") -> nav.navigate(TrailDetailRoute(screen.substringAfter(':')))
            screen.startsWith("walk:") -> nav.navigate(WalkDetailRoute(screen.substringAfter(':')))
        }
    }
}
