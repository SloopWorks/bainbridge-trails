package com.bainbridgetrails.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.ProgressSnapshot
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.data.valueOrNull
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.AttributionMode
import com.bainbridgetrails.map.FOLLOW_MIN_ZOOM
import com.bainbridgetrails.map.IslandBounds
import com.bainbridgetrails.map.IslandCamera
import com.bainbridgetrails.map.MapFocus
import com.bainbridgetrails.map.ScreenPoint
import com.bainbridgetrails.map.SegmentHit
import com.bainbridgetrails.map.SelectedTrail
import com.bainbridgetrails.map.StatusLayers
import com.bainbridgetrails.map.TAP_HIT_SIZE_DP
import com.bainbridgetrails.map.TapResult
import com.bainbridgetrails.map.TappableTrailLayerIds
import com.bainbridgetrails.map.TrailFacts
import com.bainbridgetrails.map.TrailFactsRepository
import com.bainbridgetrails.map.TrailMap
import com.bainbridgetrails.map.TrailMapController
import com.bainbridgetrails.map.boundsOf
import com.bainbridgetrails.map.chartInteractions
import com.bainbridgetrails.map.distanceOutsideM
import com.bainbridgetrails.map.distanceToPolyline
import com.bainbridgetrails.map.fitTo
import com.bainbridgetrails.map.formatTrailLength
import com.bainbridgetrails.map.mercatorOffsetDp
import com.bainbridgetrails.map.moveTo
import com.bainbridgetrails.map.rankTap
import com.bainbridgetrails.map.rememberTrailMapController
import com.bainbridgetrails.map.segmentsById
import com.bainbridgetrails.map.trailLines
import com.bainbridgetrails.record.PermStatus
import com.bainbridgetrails.record.openAppSettings
import com.bainbridgetrails.record.rememberPermissionRequester
import com.bainbridgetrails.record.rememberPlatformContext
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.detail_go_to_recording
import com.bainbridgetrails.resources.detail_start_walk
import com.bainbridgetrails.resources.map_a11y
import com.bainbridgetrails.resources.map_leaderboard_pill_signed_out
import com.bainbridgetrails.resources.map_loading_trails
import com.bainbridgetrails.resources.map_locate_denied
import com.bainbridgetrails.resources.map_locate_unavailable
import com.bainbridgetrails.resources.map_not_now
import com.bainbridgetrails.resources.map_off_island
import com.bainbridgetrails.resources.map_open_settings
import com.bainbridgetrails.resources.map_search_hint
import com.bainbridgetrails.resources.nav_trails
import com.bainbridgetrails.resources.perm_locate_allow
import com.bainbridgetrails.resources.perm_locate_body
import com.bainbridgetrails.resources.perm_locate_title
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.LocateButton
import com.bainbridgetrails.ui.components.LocateMode
import com.bainbridgetrails.ui.components.MapIconButton
import com.bainbridgetrails.ui.components.StatusLegend
import com.bainbridgetrails.ui.components.TrailChooserSheet
import com.bainbridgetrails.ui.components.TrailPeekSheet
import com.bainbridgetrails.ui.components.TrailSummary
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Position

/** "Off the island" = farther than this from the island bounds (ux.md §3.2). */
private const val OFF_ISLAND_M = 3_000.0

/** How long Locate waits for a first fix before saying it can't find you. */
private const val LOCATE_TIMEOUT_MS = 12_000L

/**
 * Map tab (ux.md §3.2, §6; product F2): full-bleed map with per-segment status layers, tap → peek / chooser sheet,
 * selected-trail halo with the rest dimmed, legend, locate-me and leaderboard shortcut (the recording pill is the shell's). The map
 * shows at once (bundled tiles); statuses arrive when the dataset and coverage have loaded.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapTab(
    graph: AppGraph.Installed,
    settings: AppSettings,
    camera: CameraPosition?,
    modifier: Modifier = Modifier,
    onTrail: (String) -> Unit = {},
    onLeaderboard: () -> Unit = {},
    onSearch: () -> Unit = {},
    onRecord: () -> Unit = {},
) {
    val datasetState by AppGraph.datasets.state.collectAsStateWithLifecycle()
    val data = datasetState.valueOrNull
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    val facts by produceState<Map<String, TrailFacts>>(emptyMap()) { value = runCatching { TrailFactsRepository.all() }.getOrDefault(emptyMap()) }
    val recordingController by produceState<RecordingController?>(null, graph) { value = runCatching { graph.controller().await() }.getOrNull() }
    // Only "is a walk recording" matters here (Start-walk label); a fix or a tick must not recompose the map screen.
    val recording = recordingController?.let { c ->
        remember(c) { c.state.map { it is RecordingState.Recording }.distinctUntilChanged() }.collectAsStateWithLifecycle(c.state.value is RecordingState.Recording).value
    } ?: false
    val style = settings.effectiveMapStyle(isSystemInDarkTheme())
    val reduced = BtTheme.motion.reduced
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = rememberPlatformContext()
    val requester = rememberPermissionRequester()
    val controller = rememberTrailMapController()

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var choices by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var legendOpen by rememberSaveable { mutableStateOf(false) }
    var locating by rememberSaveable { mutableStateOf(false) }
    var following by remember { mutableStateOf(false) }
    var askLocate by remember { mutableStateOf(false) }
    var stackHeight by remember { mutableStateOf(0.dp) }
    var mapHeight by remember { mutableStateOf(0.dp) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val locationGranted = requester.status(Perm.LOCATION_WHILE_IN_USE) == PermStatus.GRANTED
    val locationState = rememberLocationState(enabled = locating && locationGranted)

    fun summary(id: String): TrailSummary? {
        val meta = data?.dataset?.trails?.firstOrNull { it.id == id } ?: return null
        val p = snapshot?.trail(id)
        val f = facts[id]
        val length = formatTrailLength(f?.lengthMi ?: meta.displayLengthMi, settings.units)
        return TrailSummary(id, meta.name, listOfNotNull(f?.park, length).joinToString(" · "), p?.fraction ?: 0.0, p?.status ?: TrailStatus.NOT_STARTED)
    }

    fun clearSelection() {
        selectedId = null
        choices = null
    }

    fun select(id: String, map: MapState?) {
        choices = null
        selectedId = id
        following = false
        val d = data ?: return
        val bounds = boundsOf(d.dataset.trailLines(id)) ?: return
        scope.launch {
            // Wait a frame for the sheet to measure so the fit leaves room for it.
            delay(16)
            map?.fitTo(
                bounds,
                DpPadding(left = 48.dp, top = topInset + 72.dp, right = 48.dp, bottom = maxOf(stackHeight, 220.dp) + 24.dp),
                animate = !reduced,
            )
        }
    }

    fun onTap(e: ClickEvent) {
        val map = controller.mapState ?: return
        val d = data ?: return
        scope.launch {
            val half = (TAP_HIT_SIZE_DP / 2).dp
            val o = e.screenOffset
            val rect = DpRect(o.x - half, o.y - half, o.x + half, o.y + half)
            val features = runCatching { map.queryRenderedFeatures(rect, TappableTrailLayerIds) }.getOrDefault(emptyList())
            val segs = d.dataset.segmentsById
            val tapGeo = e.position ?: map.positionFromScreenLocation(o) ?: return@launch
            val zoom = map.cameraPosition.zoom
            val tap = ScreenPoint(0.0, 0.0)
            // Pure math off Main: vertices are projected relative to the tap (north-up, no tilt).
            val hits = withContext(Dispatchers.Default) { features.mapNotNull { f ->
                val segId = (f.properties?.get("id") as? JsonPrimitive)?.content ?: return@mapNotNull null
                val trailIds = segs[segId]?.trailIds.orEmpty()
                if (trailIds.isEmpty()) return@mapNotNull null
                val lines: List<List<Position>> = when (val g = f.geometry) {
                    is LineString -> listOf(g.coordinates)
                    is MultiLineString -> g.coordinates
                    else -> emptyList()
                }
                val dist = lines.minOfOrNull { line ->
                    distanceToPolyline(tap, line.map { p -> mercatorOffsetDp(tapGeo.longitude, tapGeo.latitude, p.longitude, p.latitude, zoom) })
                } ?: TAP_HIT_SIZE_DP
                SegmentHit(trailIds, dist)
            } }
            val names = d.dataset.trails.associate { it.id to it.name }
            when (val r = rankTap(hits) { names[it].orEmpty() }) {
                TapResult.None -> clearSelection()
                is TapResult.Select -> select(r.trailId, map)
                is TapResult.Choose -> {
                    selectedId = null
                    choices = r.trailIds
                    // Keep the tapped spot visible above the chooser once it has measured.
                    delay(350)
                    val free = mapHeight - stackHeight
                    if (o.y > free - 48.dp) map.panBy(DpOffset(0.dp, free / 2 - o.y))
                }
            }
        }
    }

    // First open: fit the whole island (ux.md §6.3) between the search row and the bottom controls.
    var islandFitted by rememberSaveable { mutableStateOf(camera != null) }
    LaunchedEffect(controller.mapState) {
        val map = controller.mapState ?: return@LaunchedEffect
        if (islandFitted || MapFocus.trailId.value != null) return@LaunchedEffect
        islandFitted = true
        map.fitTo(IslandBounds, DpPadding(left = 8.dp, top = topInset + 64.dp, right = 8.dp, bottom = 56.dp), animate = false, minZoom = 10.5)
    }

    // "Show on map" from Trail detail.
    val focus by MapFocus.trailId.collectAsStateWithLifecycle()
    LaunchedEffect(focus, data, controller.mapState) {
        val id = focus ?: return@LaunchedEffect
        if (data == null || controller.mapState == null) return@LaunchedEffect
        MapFocus.consume()
        select(id, controller.mapState)
    }

    // Locate: follow the fix while following; first fix decides "off the island".
    val fix = locationState.lastLocation?.position
    LaunchedEffect(locating, following, fix, controller.mapState) {
        val map = controller.mapState ?: return@LaunchedEffect
        if (!locating || !following) return@LaunchedEffect
        val p = fix ?: run {
            delay(LOCATE_TIMEOUT_MS)
            following = false
            val r = snackbar.showSnackbar(getString(Res.string.map_locate_unavailable), getString(Res.string.map_open_settings), duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) openAppSettings(context)
            return@LaunchedEffect
        }
        if (distanceOutsideM(p, IslandBounds) > OFF_ISLAND_M) {
            following = false
            snackbar.showSnackbar(getString(Res.string.map_off_island))
            return@LaunchedEffect
        }
        val zoom = maxOf(map.cameraPosition.zoom, FOLLOW_MIN_ZOOM)
        map.moveTo(CameraPosition(center = p, zoom = zoom), animate = !reduced)
    }

    fun startLocate() {
        locating = true
        following = true
        clearSelection()
    }

    fun onLocateTapped() {
        when (requester.status(Perm.LOCATION_WHILE_IN_USE)) {
            PermStatus.GRANTED -> if (following) following = false else startLocate()
            PermStatus.PERMANENTLY_DENIED -> scope.launch {
                val r = snackbar.showSnackbar(getString(Res.string.map_locate_denied), getString(Res.string.map_open_settings), duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) openAppSettings(context)
            }
            PermStatus.DENIED -> askLocate = true // never auto-requested (F2 step 4): rationale first
        }
    }

    val startWalk = rememberStartWalk(graph, onRecord)

    val sheetOpen = selectedId != null || choices != null
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = sheetOpen || legendOpen,
        onBackCompleted = { if (legendOpen) legendOpen = false else clearSelection() },
    )

    val selected: SelectedTrail? = remember(selectedId, data, snapshot) {
        val id = selectedId ?: return@remember null
        val d = data ?: return@remember null
        SelectedTrail(id, d.dataset.trailLines(id), snapshot?.trail(id)?.status ?: TrailStatus.NOT_STARTED)
    }
    val tapHandler by rememberUpdatedState<(ClickEvent) -> Unit>(::onTap)
    val interactions = remember { chartInteractions(onTap = { e -> tapHandler(e) }, onUserPan = { following = false }) }
    val lastSummary = remember { mutableStateOf<TrailSummary?>(null) }

    val completed = snapshot?.trailsCompleted ?: 0
    val total = data?.dataset?.trails?.size ?: 0
    val mapA11y = stringResource(Res.string.map_a11y, completed, total)
    val trailsLabel = stringResource(Res.string.nav_trails)
    val animatedStack by animateDpAsState(stackHeight, tween(if (reduced) 0 else 200))

    Box(modifier.fillMaxSize().onSizeChanged { mapHeight = with(density) { it.height.toDp() } }) {
        TrailMap(
            style,
            Modifier.fillMaxSize().semantics {
                contentDescription = mapA11y
                customActions = listOf(CustomAccessibilityAction(trailsLabel) { onSearch(); true })
            },
            initialCamera = camera ?: IslandCamera,
            controlsTopPadding = 64.dp,
            controller = controller,
            interactions = interactions,
            attribution = AttributionMode.Timed,
            chromePadding = PaddingValues(bottom = animatedStack),
        ) {
            StatusLayers(snapshot?.segmentBuckets.orEmpty(), style, selected, reduced)
            if (locating && locationGranted) {
                LocationIndicatorLayer(
                    id = "app-user-location",
                    locationState = locationState,
                    accuracyRadiusColor = BtTheme.colors.userTrack.copy(alpha = 0.12f),
                    accuracyRadiusBorderColor = BtTheme.colors.userTrack.copy(alpha = 0.5f),
                    bearingAccuracyColor = const(BtTheme.colors.userTrack.copy(alpha = 0.3f)),
                )
            }
        }

        // Top: search chip (→ Trails) + leaderboard shortcut.
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = BtTheme.spacing.screenH, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                SearchChip(enabled = data != null, onClick = onSearch, modifier = Modifier.weight(1f))
                MapIconButton(BtIcons.Trophy, stringResource(Res.string.map_leaderboard_pill_signed_out), onLeaderboard)
            }
            AnimatedVisibility(datasetState is Loadable.Loading || (data != null && snapshot == null), enter = fadeIn(), exit = fadeOut()) {
                LoadingChip()
            }
        }

        // Bottom: legend + locate row, then the sheet.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.screenH).padding(bottom = 64.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                StatusLegend(style, legendOpen, { legendOpen = !legendOpen })
                Spacer(Modifier.weight(1f))
                LocateButton(if (following) LocateMode.Following else LocateMode.Idle, ::onLocateTapped)
            }
            Column(Modifier.fillMaxWidth().onSizeChanged { stackHeight = with(density) { it.height.toDp() } }) {
                val selectedSummary = selectedId?.let { summary(it) }
                AnimatedVisibility(
                    selectedSummary != null,
                    enter = slideInVertically(tween(if (reduced) 0 else 300)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(if (reduced) 0 else 200)) { it } + fadeOut(),
                ) {
                    val s = selectedSummary ?: lastSummary.value
                    if (selectedSummary != null) lastSummary.value = selectedSummary
                    if (s != null) {
                        TrailPeekSheet(
                            s,
                            onStart = startWalk,
                            onDetails = { onTrail(s.id) },
                            onDismiss = ::clearSelection,
                            startLabel = stringResource(if (recording) Res.string.detail_go_to_recording else Res.string.detail_start_walk),
                        )
                    }
                }
                val choiceSummaries = choices?.mapNotNull { summary(it) }
                AnimatedVisibility(
                    !choiceSummaries.isNullOrEmpty(),
                    enter = slideInVertically(tween(if (reduced) 0 else 300)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(if (reduced) 0 else 200)) { it } + fadeOut(),
                ) {
                    TrailChooserSheet(choiceSummaries.orEmpty(), onPick = { select(it, controller.mapState) }, onDismiss = ::clearSelection)
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = stackHeight + 64.dp))
    }

    if (askLocate) {
        AlertDialog(
            onDismissRequest = { askLocate = false },
            title = { Text(stringResource(Res.string.perm_locate_title)) },
            text = { Text(stringResource(Res.string.perm_locate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askLocate = false
                    scope.launch {
                        when (requester.request(Perm.LOCATION_WHILE_IN_USE)) {
                            PermStatus.GRANTED -> startLocate()
                            PermStatus.PERMANENTLY_DENIED -> {
                                val r = snackbar.showSnackbar(getString(Res.string.map_locate_denied), getString(Res.string.map_open_settings), duration = SnackbarDuration.Long)
                                if (r == SnackbarResult.ActionPerformed) openAppSettings(context)
                            }
                            PermStatus.DENIED -> Unit
                        }
                    }
                }) { Text(stringResource(Res.string.perm_locate_allow)) }
            },
            dismissButton = { TextButton(onClick = { askLocate = false }) { Text(stringResource(Res.string.map_not_now)) } },
        )
    }
}

/** "Find a trail" pill (ux.md §3.2 MapSearchChip): opens the Trails tab. Disabled until the dataset is ready. */
@Composable
private fun SearchChip(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.map_search_hint)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .shadow(3.dp, PillShape)
            .background(BtTheme.colors.mapChrome, PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tint = BtTheme.colors.onMapChrome.copy(alpha = if (enabled) 0.8f else 0.4f)
        BtIcon(BtIcons.Search, null, size = 20.dp, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** Small non-blocking "Loading trails…" chip under the search row (product F2 states). */
@Composable
private fun LoadingChip() {
    Row(
        Modifier.shadow(2.dp, PillShape).background(BtTheme.colors.mapChrome, PillShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(stringResource(Res.string.map_loading_trails), style = MaterialTheme.typography.labelLarge, color = BtTheme.colors.onMapChrome)
    }
}
