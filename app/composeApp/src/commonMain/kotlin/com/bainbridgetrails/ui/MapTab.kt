@file:OptIn(ExperimentalLayoutApi::class)

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
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.bainbridgetrails.play.fx.FxPoliteness
import com.bainbridgetrails.ui.play.map.PlayChipRow
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
import com.bainbridgetrails.map.ROAD_END_LAYER
import com.bainbridgetrails.map.StatusLayerSpec
import com.bainbridgetrails.map.allRoadEnds
import com.bainbridgetrails.map.road
import com.bainbridgetrails.map.countedLengthMi
import com.bainbridgetrails.data.LocalGoal
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
import com.bainbridgetrails.play.geo.GeoPoint
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
import com.bainbridgetrails.ui.components.rememberRoadTexts
import com.bainbridgetrails.play.fx.FxOverlay
import com.bainbridgetrails.play.fx.PlayMapContent
import com.bainbridgetrails.play.fx.WithFxRenderOptions
import com.bainbridgetrails.play.fx.fxHostInputs
import com.bainbridgetrails.play.fx.rememberFxHost
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.play.fx.FxHostState
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.resources.play_kraken_coast
import com.bainbridgetrails.ui.play.components.rememberPlay
import com.bainbridgetrails.ui.play.components.titleRes
import com.bainbridgetrails.ui.play.map.ChallengesChip
import com.bainbridgetrails.ui.play.map.EncounterLogic
import com.bainbridgetrails.ui.play.map.EncounterModel
import com.bainbridgetrails.ui.play.map.EncounterSheet
import com.bainbridgetrails.ui.play.map.EncounterVariant
import com.bainbridgetrails.ui.play.map.MapTapAction
import com.bainbridgetrails.ui.play.map.MapTapLogic
import com.bainbridgetrails.ui.play.map.MarkerKind
import com.bainbridgetrails.ui.play.map.PlayHud
import com.bainbridgetrails.ui.play.map.PlayLegendGroup
import com.bainbridgetrails.ui.play.map.PlayMapSync
import com.bainbridgetrails.ui.play.map.PlayMarkerLogic
import com.bainbridgetrails.ui.play.map.PlayMarkers
import com.bainbridgetrails.ui.play.map.QuietBanner
import com.bainbridgetrails.ui.play.map.RiddleAnswerPicker
import com.bainbridgetrails.ui.play.map.RiddleBlock
import com.bainbridgetrails.ui.play.map.RiddleLogic
import com.bainbridgetrails.ui.play.map.RiddleOutcome
import com.bainbridgetrails.ui.play.map.RiddlePick
import com.bainbridgetrails.ui.play.map.RiddlePickBar
import com.bainbridgetrails.ui.play.map.WalkTarget
import com.bainbridgetrails.ui.play.map.playLiveRegistry
import com.bainbridgetrails.ui.play.map.rememberFerryTimetable
import com.bainbridgetrails.ui.play.map.PlayMapEffectsSync
import com.bainbridgetrails.ui.play.map.PlayMapFocus
import com.bainbridgetrails.ui.play.map.PlayFocusLogic
import com.bainbridgetrails.ui.play.map.PlayMarker
import com.bainbridgetrails.play.fx.effects.ClaimPopState
import com.bainbridgetrails.play.fx.effects.ClaimPopOverlay
import com.bainbridgetrails.play.fx.effects.claimPopTarget
import com.bainbridgetrails.ui.play.PlayWake
import com.bainbridgetrails.ui.play.PlayWakeArt
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.map.IslandBounds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import com.bainbridgetrails.ui.play.map.rememberEncounterCopy
import com.bainbridgetrails.ui.play.map.rememberRiddleCopy
import com.bainbridgetrails.ui.play.map.riddleTarget
import com.bainbridgetrails.play.rules.Ranks
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
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/** "Off the island" = farther than this from the island bounds (ux.md §3.2). */
private const val OFF_ISLAND_M = 3_000.0

/** How long Locate waits for a first fix before saying it can't find you. */
private const val LOCATE_TIMEOUT_MS = 12_000L

/** A road-end pin wins a tap within this distance (docs/roads/ux.md §2.6). */
private const val ROAD_END_TAP_DP = 16.0

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
    /** Play chip → Logbook. */
    onPlayLogbook: () -> Unit = {},
    /** Challenges chip → Challenges. */
    onPlayChallenges: () -> Unit = {},
    /** "The island wakes up" (ux.md § 2.11) is due: drawn over this map at its island camera; [onWakeDone] when over. */
    wake: Boolean = false,
    onWakeDone: () -> Unit = {},
) {
    val datasetState by AppGraph.datasets.state.collectAsStateWithLifecycle()
    val data = datasetState.valueOrNull
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    val facts by produceState<Map<String, TrailFacts>>(emptyMap()) { value = runCatching { TrailFactsRepository.all() }.getOrDefault(emptyMap()) }
    val roadEnds = remember(facts) { facts.allRoadEnds() }
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
    // Play (ux.md §3.5): null while off; nothing below composes or queries anything then (PF0-1).
    val play = rememberPlay(graph, settings.play.enabled)
    val playOn = settings.play.enabled
    val playAgenda = play?.agenda?.collectAsStateWithLifecycle()?.value
    val playState = play?.store?.state?.collectAsStateWithLifecycle()?.value
    val playConfig = play?.config?.collectAsStateWithLifecycle()?.value?.config
    // P2-10: the marker renderer, its registry and the sheet copy exist only while Play is on.
    val playMarkerArt = remember(playOn) { PlayMapAlloc.markersOrNull(playOn) }
    val ferry = rememberFerryTimetable(play)
    // One renderer per kind (KrakenAttack carries the ink casing, FrogRock the stamp + hop); rebuilt only with the timetable.
    val playRegistry = remember(playMarkerArt, ferry) { playMarkerArt?.let { art -> playLiveRegistry(art, ferry) { AppGraph.playClock.now() } } }
    val playMarkers = remember(playAgenda, playState?.solved, playConfig, data) {
        if (playAgenda == null || playConfig == null) emptyList()
        else PlayMarkerLogic.markers(
            playAgenda, playConfig.items.associate { it.id to it.lore }, playState?.solved?.keys.orEmpty(),
            // The kraken marker sits on its attack's water point (the lurk / pool anchor, ux.md §2.2).
            water = data?.let { d ->
                com.bainbridgetrails.ui.play.map.PlayMapEffects.waterPoints(playAgenda) { id ->
                    d.dataset.segmentsById[id]?.coords?.map { GeoPoint(lon = it[0], lat = it[1]) }
                }
            }.orEmpty(),
        )
    }
    var playSelected by rememberSaveable { mutableStateOf<String?>(null) }
    var riddlePickFor by remember { mutableStateOf<String?>(null) }
    var riddleListFor by remember { mutableStateOf<String?>(null) }
    var riddleWrong by remember(playSelected) { mutableStateOf(false) }
    // Each wrong answer bumps this: the EncounterSheet's lantern shake + reject haptic (ux.md §3.5.4; reset per selection).
    var riddleShake by remember(playSelected) { mutableStateOf(0) }
    // Play gate (engine PF-9, plan L2-2): Play off → no FX host, scene, clock, sources or layers are created.
    val fxInputs = if (playRegistry != null) fxHostInputs(settings.play, recording, playRegistry) else fxHostInputs(settings.play, recording)
    val fx = if (fxInputs != null) rememberFxHost(fxInputs, controller) else null
    val fxOverlay: (@Composable MapOverlayScope.() -> Unit)? = remember(fx) { if (fx != null) ({ FxOverlay(fx) }) else null }
    val krakenStretches = playAgenda?.active?.firstOrNull { it.occurrence.type == com.bainbridgetrails.play.config.ItemType.obstacle }?.occurrence?.placements?.map { it.ref }.orEmpty()
    val coast = remember(krakenStretches, playConfig) { playConfig?.let { c -> PlayMarkerLogic.coastOf(krakenStretches, c.pools.mapValues { it.value.members }) } }
    val coastLabel = coast?.let { stringResource(Res.string.play_kraken_coast, it) }
    if (playMarkerArt != null) {
        PlayMapSync(fx, playMarkerArt, playMarkers, playSelected, coastLabel)
        PlayMapEffectsSync(fx, play, snapshot?.segmentBuckets.orEmpty(), ferry = ferry != null, holdIntros = wake)
    }

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

    val spec = StatusLayerSpec.current
    val roadText = rememberRoadTexts()
    fun summary(id: String): TrailSummary? {
        val meta = data?.dataset?.trails?.firstOrNull { it.id == id } ?: return null
        val p = snapshot?.trail(id)
        val f = facts[id]
        val road = meta.road()
        if (road != null) {
            // Roads (docs/roads/ux.md §3.3): "Residential street · 0.7 mi" (counted length); uncounted say why.
            val length = formatTrailLength(data.dataset.countedLengthMi(id), settings.units)
            val reason = road.notCountedReason
            return TrailSummary(
                id, meta.name, listOf(roadText.category(road.category), length).joinToString(" · "),
                p?.fraction ?: 0.0, p?.status ?: TrailStatus.NOT_STARTED,
                notCountedReason = reason?.let(roadText::reason),
            )
        }
        val length = formatTrailLength(f?.lengthMi ?: meta.displayLengthMi, settings.units)
        return TrailSummary(id, meta.name, listOfNotNull(f?.park, length).joinToString(" · "), p?.fraction ?: 0.0, p?.status ?: TrailStatus.NOT_STARTED)
    }

    fun clearSelection() {
        selectedId = null
        choices = null
        playSelected = null
    }

    fun select(id: String, map: MapState?) {
        choices = null
        playSelected = null
        selectedId = id
        following = false
        val d = data ?: return
        val bounds = boundsOf(d.dataset.trailLines(id)) ?: return
        scope.launch {
            // Wait a frame for the sheet to measure so the fit leaves room for it.
            delay(16)
            map?.fitTo(
                bounds,
                DpPadding(left = 48.dp, top = topInset + if (playOn) 128.dp else 72.dp, right = 48.dp, bottom = maxOf(stackHeight, 220.dp) + 24.dp),
                animate = !reduced,
                maxZoom = spec.fitZoomRange.endInclusive,
                minZoom = spec.fitZoomRange.start,
            )
        }
    }

    fun nearestTrail(lat: Double, lon: Double): String? {
        val d = data ?: return null
        return d.dataset.trails.minByOrNull { t ->
            d.dataset.trailLines(t.id).minOfOrNull { line -> line.minOf { p -> (p[0] - lon) * (p[0] - lon) * 0.45 + (p[1] - lat) * (p[1] - lat) } } ?: Double.MAX_VALUE
        }?.id
    }

    fun walkThere(t: WalkTarget) {
        when (t) {
            is WalkTarget.Feature -> onTrail(t.id)
            is WalkTarget.Point -> nearestTrail(t.lat, t.lon)?.let(onTrail)
        }
    }

    fun answerRiddle(itemId: String, pick: RiddlePick) {
        val repo = play ?: return
        val config = playConfig ?: return
        val item = config.items.firstOrNull { it.id == itemId } ?: return
        val today = playAgenda?.day?.date ?: IslandTime.dateOf(kotlin.time.Clock.System.now())
        riddlePickFor = null
        riddleListFor = null
        scope.launch {
            var wrong = false
            repo.store.update { st ->
                when (val o = RiddleLogic.answer(st, item, pick, config, { facts[it]?.park }, today, { "stamp-$itemId" })) {
                    RiddleOutcome.Wrong -> { wrong = true; st }
                    is RiddleOutcome.Solved -> o.state
                }
            }
            riddleWrong = wrong
            if (wrong) riddleShake++
            playSelected = itemId.let { id -> playMarkers.firstOrNull { it.itemId == id }?.key }
        }
    }

    fun onTap(e: ClickEvent) {
        // FX layers never take taps themselves (spike R7): this handler is their only route, after the pure query.
        fx?.noteInteraction()
        val map = controller.mapState ?: return
        val d = data ?: return
        scope.launch {
            val half = (TAP_HIT_SIZE_DP / 2).dp
            val o = e.screenOffset
            val rect = DpRect(o.x - half, o.y - half, o.x + half, o.y + half)
            val layers = if (spec.drawRoadEnds) spec.tappableLayerIds + ROAD_END_LAYER else spec.tappableLayerIds
            val features = runCatching { map.queryRenderedFeatures(rect, layers) }
                .onFailure { com.bainbridgetrails.log.AppLog.w("map", "tap query failed: ${it.message}") }
                .getOrDefault(emptyList())
            val segs = d.dataset.segmentsById
            val tapGeo = e.position ?: map.positionFromScreenLocation(o) ?: return@launch
            val zoom = map.cameraPosition.zoom
            val tap = ScreenPoint(0.0, 0.0)
            // Road-end pins win within 16dp (docs/roads/ux.md §2.6): select the pin's road.
            val pin = features.mapNotNull { f ->
                val roadId = (f.properties?.get("road_id") as? JsonPrimitive)?.content ?: return@mapNotNull null
                val pt = (f.geometry as? Point)?.coordinates ?: return@mapNotNull null
                val off = mercatorOffsetDp(tapGeo.longitude, tapGeo.latitude, pt.longitude, pt.latitude, zoom)
                roadId to kotlin.math.hypot(off.x, off.y)
            }.filter { it.second <= ROAD_END_TAP_DP }.minByOrNull { it.second }
            if (pin != null) {
                select(pin.first, map)
                return@launch
            }
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
            val r = rankTap(hits) { names[it].orEmpty() }
            // Riddle pick mode (ux.md §3.5.4 step 0): every tap is an answer until the pick bar closes.
            riddlePickFor?.let { id ->
                answerRiddle(id, RiddlePick(featureIds = hits.flatMap { it.trailIds }.distinct(), at = GeoPoint(lat = tapGeo.latitude, lon = tapGeo.longitude)))
                return@launch
            }
            // Play only on a pure miss (PF3-5, PD-12): `fx-marker-hit`, never a casing or a stamp.
            val playHits = if (MapTapLogic.queriesPlay(r, fx != null)) {
                runCatching { map.queryRenderedFeatures(rect, PlayMarkers.hitLayerIds.toSet()) }.getOrDefault(emptyList()).mapNotNull { f ->
                    val props = f.properties?.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() } ?: return@mapNotNull null
                    val visible = if (props["c"] == "1") zoom < 13.0 else (props["z"]?.toDoubleOrNull() ?: 0.0) <= zoom
                    if (visible) MapTapLogic.hitOf(props) else null
                }
            } else {
                emptyList()
            }
            when (val action = MapTapLogic.decide(r, playHits)) {
                is MapTapAction.OpenEncounter -> {
                    clearSelection()
                    playSelected = action.key
                    return@launch
                }
                is MapTapAction.ZoomTo -> {
                    clearSelection()
                    map.moveTo(CameraPosition(center = Position(longitude = action.lon, latitude = action.lat), zoom = action.zoom), animate = !reduced)
                    return@launch
                }
                else -> Unit
            }
            when (r) {
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
        map.fitTo(IslandBounds, DpPadding(left = 8.dp, top = topInset + if (playOn) 128.dp else 64.dp, right = 8.dp, bottom = 56.dp), animate = false, minZoom = 10.5)
    }

    // "Show on map" from Trail detail.
    val focus by MapFocus.trailId.collectAsStateWithLifecycle()
    LaunchedEffect(focus, data, controller.mapState) {
        val id = focus ?: return@LaunchedEffect
        if (data == null || controller.mapState == null) return@LaunchedEffect
        MapFocus.consume()
        select(id, controller.mapState)
    }

    // "Show on map" from What's On / Challenges (ux.md § 2.8, § 3.16): select the occurrence, open its sheet, fit it.
    val playFocus by PlayMapFocus.key.collectAsStateWithLifecycle()
    LaunchedEffect(playFocus, playMarkers, controller.mapState) {
        val key = playFocus ?: return@LaunchedEffect
        val map = controller.mapState ?: return@LaunchedEffect
        if (!playOn) { PlayMapFocus.consume(); return@LaunchedEffect }
        if (playMarkers.isEmpty()) return@LaunchedEffect // agenda not ready yet: the markers change re-runs this
        PlayMapFocus.consume()
        val t = PlayFocusLogic.target(key, playMarkers) ?: return@LaunchedEffect
        clearSelection()
        playSelected = t.select
        following = false
        delay(16)
        if (t.isPoint) {
            map.moveTo(CameraPosition(center = Position(longitude = t.centerLon, latitude = t.centerLat), zoom = maxOf(map.cameraPosition.zoom, PlayFocusLogic.POINT_ZOOM)), animate = !reduced)
        } else {
            map.fitTo(
                com.bainbridgetrails.map.GeoBounds(west = t.west, south = t.south, east = t.east, north = t.north),
                DpPadding(left = 48.dp, top = topInset + 128.dp, right = 48.dp, bottom = maxOf(stackHeight, 220.dp) + 24.dp),
                animate = !reduced, minZoom = 10.5,
            )
        }
    }
    // Analytics (product § 7.4; gated: nothing while Play is off, PF0-4).
    LaunchedEffect(playSelected) {
        val key = playSelected ?: return@LaunchedEffect
        val m = playMarkers.firstOrNull { it.key == key } ?: return@LaunchedEffect
        graph.playAnalytics.track(com.bainbridgetrails.analytics.PlayAnalytics.CONTENT_OPENED, mapOf("type" to m.kind.name.lowercase(), "content_id" to m.itemId))
    }
    LaunchedEffect(playAgenda?.day?.date, playAgenda?.day?.quietDay) {
        if (playAgenda?.day?.quietDay == true) graph.playAnalytics.track(com.bainbridgetrails.analytics.PlayAnalytics.QUIET_DAY)
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

    val sheetOpen = selectedId != null || choices != null || playSelected != null
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = sheetOpen || legendOpen || riddlePickFor != null || riddleListFor != null,
        onBackCompleted = {
            when {
                riddleListFor != null -> riddleListFor = null
                riddlePickFor != null -> riddlePickFor = null
                legendOpen -> legendOpen = false
                else -> clearSelection()
            }
        },
    )
    val encounterCopy = if (playOn) rememberEncounterCopy() else null
    val riddleCopy = if (playOn) rememberRiddleCopy() else null
    val claimResults = play?.claimResults?.collectAsStateWithLifecycle()?.value
    val encounter: EncounterModel? = remember(playSelected, playAgenda, playConfig, playState, claimResults, data, coast, encounterCopy) {
        val key = playSelected ?: return@remember null
        val copy = encounterCopy ?: return@remember null
        val agenda = playAgenda ?: return@remember null
        val active = agenda.active.firstOrNull { a -> a.occurrence.key == key || a.occurrence.placements.any { it.key == key } } ?: return@remember null
        val item = playConfig?.items?.firstOrNull { it.id == active.occurrence.itemId } ?: return@remember null
        val wall = kotlin.time.Clock.System.now()
        val now = if (IslandTime.dateOf(wall) == agenda.day.date) wall else agenda.day.sun.noon
        val shares = HashMap<String, Double>()
        claimResults?.values?.forEach { cr -> cr.stretchShares.forEach { (k, v) -> shares[k] = maxOf(shares[k] ?: 0.0, v) } }
        val foundOn = playState?.logbook?.entries?.firstOrNull { it.contentId == key }?.date
        EncounterLogic.model(
            item, active, key, now, agenda.day.sun, copy,
            names = { id -> data?.dataset?.trails?.firstOrNull { it.id == id }?.name }, shares = shares, foundOn = foundOn, coast = coast,
        )
    }

    val selected: SelectedTrail? = remember(selectedId, data, snapshot) {
        val id = selectedId ?: return@remember null
        val d = data ?: return@remember null
        SelectedTrail(id, d.dataset.trailLines(id), snapshot?.trail(id)?.status ?: TrailStatus.NOT_STARTED)
    }
    val tapHandler by rememberUpdatedState<(ClickEvent) -> Unit>(::onTap)
    val interactions = remember { chartInteractions(onTap = { e -> tapHandler(e) }, onUserPan = { following = false }) }
    val lastSummary = remember { mutableStateOf<TrailSummary?>(null) }

    val completed = snapshot?.trailsCompleted ?: 0
    val total = snapshot?.trailCount ?: 0
    val mapA11y = stringResource(LocalGoal.current.s(Res.string.map_a11y), completed, total)
    val trailsLabel = stringResource(LocalGoal.current.s(Res.string.nav_trails))
    val animatedStack by animateDpAsState(stackHeight, tween(if (reduced) 0 else 200))
    var claimPop by remember { mutableStateOf<ClaimPopState?>(null) }
    // § 5.1: when the wake ends, screen-reader focus lands on the Play chip.
    val playChipFocus = remember { FocusRequester() }

    Box(modifier.fillMaxSize().onSizeChanged { mapHeight = with(density) { it.height.toDp() } }) {
        WithFxRenderOptions(fx) { renderOptions ->
            TrailMap(
                style,
                Modifier.fillMaxSize().semantics {
                    contentDescription = mapA11y
                    customActions = listOf(CustomAccessibilityAction(trailsLabel) { onSearch(); true })
                },
                initialCamera = camera ?: IslandCamera,
                controlsTopPadding = if (playOn) 120.dp else 64.dp,
                controller = controller,
                interactions = interactions,
                attribution = AttributionMode.Timed,
                chromePadding = PaddingValues(bottom = animatedStack),
                fxOverlay = fxOverlay,
                renderOptions = renderOptions,
            ) {
                // Composition order = z-order (mapContentOrder): FX bands around StatusLayers, the puck above them all.
                PlayMapContent(
                    fx,
                    status = { casingFx ->
                        StatusLayers(
                            snapshot?.segmentBuckets.orEmpty(), style, selected, reduced, spec = spec, roadEnds = roadEnds,
                            casingFx = casingFx,
                        )
                    },
                    location = if (locating && locationGranted) {
                        {
                            LocationIndicatorLayer(
                                id = "app-user-location",
                                locationState = locationState,
                                accuracyRadiusColor = BtTheme.colors.userTrack.copy(alpha = 0.12f),
                                accuracyRadiusBorderColor = BtTheme.colors.userTrack.copy(alpha = 0.5f),
                                bearingAccuracyColor = const(BtTheme.colors.userTrack.copy(alpha = 0.3f)),
                            )
                        }
                    } else {
                        null
                    },
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
            // Row 2 (ux.md §3.5.1): Play chip + Challenges chip start-aligned at inset + 64; a quiet day shows the banner.
            if (playOn) {
                val agenda = playAgenda
                if (agenda?.day?.quietDay == true) {
                    QuietBanner(
                        onRead = { playSelected = playMarkers.firstOrNull { it.kind == MarkerKind.REMEMBRANCE }?.key },
                        day = agenda.day.date.toString(),
                        announce = remember(fx) { fx?.announcer?.let { a -> { text: String -> a.say(text, FxPoliteness.POLITE) } } },
                    )
                } else {
                    val total = playState?.logbook?.total
                    // § 5.3: at font scale ≥ 1.5 both chips go compact and the row wraps.
                    val compact = PlayChipRow.compact(density.fontScale)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 56.dp),
                    ) {
                        val chipModifier = Modifier.focusRequester(playChipFocus)
                        if (total != null && agenda != null) {
                            val pop = remember { ClaimPopState(total) }
                            SideEffect { claimPop = pop }
                            // claim_pop (effects § 3.12): claimed marks fly into the chip; the first value is the baseline.
                            LaunchedEffect(total) { if (total > pop.total) pop.pop(total - pop.total, null, reduced) }
                            LivePlayHud(pop, stringResource(Ranks.of(total).titleRes), onPlayLogbook, compact, chipModifier)
                        } else {
                            PlayHud(null, stringResource(Ranks.of(0).titleRes), onPlayLogbook, chipModifier, compact = compact)
                        }
                        val count = agenda?.let { PlayMarkerLogic.challengesThisWeek(it) } ?: 0
                        if (count > 0) ChallengesChip(count, onPlayChallenges, compact = compact)
                    }
                }
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playOn && legendOpen) PlayLegendGroup()
                    StatusLegend(style, legendOpen, { legendOpen = !legendOpen })
                }
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
                // The EncounterSheet takes the peek's place in the same stack (ux.md §3.5.5): one sheet at a time.
                if (riddlePickFor != null) {
                    RiddlePickBar(onCancel = { riddlePickFor = null }, Modifier.padding(horizontal = 8.dp))
                }
                AnimatedVisibility(
                    encounter != null && riddlePickFor == null,
                    enter = slideInVertically(tween(if (reduced) 0 else 300)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(if (reduced) 0 else 200)) { it } + fadeOut(),
                ) {
                    val m = encounter
                    if (m != null) {
                        val item = playConfig?.items?.firstOrNull { it.id == m.itemId }
                        EncounterSheet(
                            m,
                            onDismiss = ::clearSelection,
                            onWalkThere = ::walkThere,
                            onStretchFocus = { row ->
                                val bounds = row.featureId?.let { id -> data?.let { boundsOf(it.dataset.trailLines(id)) } }
                                if (bounds != null) scope.launch {
                                    controller.mapState?.fitTo(bounds, DpPadding(left = 48.dp, top = topInset + 128.dp, right = 48.dp, bottom = stackHeight + 24.dp), animate = !reduced)
                                }
                            },
                            minShare = item?.resolve?.minShare ?: 0.6,
                            announce = remember(fx) { fx?.announcer?.let { a -> { text: String -> a.say(text) } } },
                            shake = riddleShake,
                            riddle = if (m.variant == EncounterVariant.RIDDLE && item != null && playState != null && riddleCopy != null) {
                                {
                                    val claimed = playMarkers.firstOrNull { it.key == m.key }?.found == true
                                    val stars = item.stars ?: 1
                                    val ui = RiddleLogic.ui(RiddleLogic.stage(playState, item, claimed), riddleWrong, riddleCopy, claimedMarks = stars * 40)
                                    val target = playConfig?.let { riddleTarget(item.resolve.answer, it) }
                                    RiddleBlock(ui, stars, onPickMap = { riddlePickFor = item.id }, onPickList = { riddleListFor = item.id }, onWalkThere = target?.let { t -> { walkThere(t) } })
                                }
                            } else {
                                null
                            },
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
        if (playOn) claimPop?.let { ClaimPopOverlay(it, Modifier.fillMaxSize(), announcer = fx?.announcer) }
        if (wake) {
            PlayWakeOnMap(
                controller, playAgenda, playMarkers, fx,
                onDone = {
                    onWakeDone()
                    // § 5.1: focus moves from the wake heading to the Play chip (absent on a quiet day: no-op).
                    runCatching { playChipFocus.requestFocus() }
                },
            )
        }

        riddleListFor?.let { id ->
            val options = remember(data, playConfig) {
                val c = playConfig
                if (c == null) emptyList() else RiddleLogic.options(data?.dataset?.trails?.map { it.id to it.name }.orEmpty(), c)
            }
            RiddleAnswerPicker(options, onPick = { answerRiddle(id, it) }, onClose = { riddleListFor = null })
        }
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
    val label = stringResource(LocalGoal.current.s(Res.string.map_search_hint))
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
        Text(stringResource(LocalGoal.current.s(Res.string.map_loading_trails)), style = MaterialTheme.typography.labelLarge, color = BtTheme.colors.onMapChrome)
    }
}

/** The Play chip fed by [pop]: its number rolls as claimed marks land (only this chip recomposes per frame). */
@Composable
private fun LivePlayHud(pop: ClaimPopState, rankTitle: String, onClick: () -> Unit, compact: Boolean, modifier: Modifier) {
    PlayHud(pop.chipValue(pop.frame.longValue), rankTitle, onClick, modifier.claimPopTarget(pop), compact = compact)
}

/**
 * "The island wakes up" over the live map (ux.md § 2.11, § 3.1–3.3): waits ≤ 2 s for the agenda, skips on a quiet
 * day (§ 3.1), moves the map to the island camera, projects the coast and the present items' glyphs for that camera
 * and plays [PlayWake]; [onDone] when it ends (or is skipped).
 */
@Composable
private fun PlayWakeOnMap(controller: TrailMapController, agenda: PlayAgenda?, markers: List<PlayMarker>, fx: FxHostState?, onDone: () -> Unit) {
    val fxNow by rememberUpdatedState(fx)
    // Motion on: the island camera move is long1 (ux.md § 6.3); reduced: instant.
    val reducedNow by rememberUpdatedState(BtTheme.motion.reduced)
    val agendaNow by rememberUpdatedState(agenda)
    val markersNow by rememberUpdatedState(markers)
    val done by rememberUpdatedState(onDone)
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val art by produceState<PlayWakeArt?>(null, controller) {
        val map = snapshotFlow { controller.mapState }.first { it != null }!!
        withTimeoutOrNull(2_000) { snapshotFlow { agendaNow }.first { it != null } }
        if (agendaNow?.day?.quietDay == true) { done(); return@produceState }
        withTimeoutOrNull(1_000) { snapshotFlow { markersNow }.first { it.isNotEmpty() } }
        map.fitTo(IslandBounds, DpPadding(left = 8.dp, top = topInset + 128.dp, right = 8.dp, bottom = 56.dp), animate = !reducedNow, minZoom = 10.5)
        delay(32)
        val cam = map.cameraPosition
        val items = markersNow.map { GeoPoint(lon = it.lon, lat = it.lat) }
        value = PlayWakeArt.load(items, GeoPoint(lon = cam.center.longitude, lat = cam.center.latitude), cam.zoom)
    }
    // The assertive line goes through Play's one announcer (effects § 1.10), never a live region of the wake's own.
    art?.let { PlayWake(it, onDone = { done() }, announce = { text -> fxNow?.announcer?.say(text, FxPoliteness.ASSERTIVE) }) }
}

/**
 * The map's Play-only allocations (branch review round 1 P2-10, PD-1): with Play off no [PlayMarkers] renderer (nor its
 * registry, nor the sheet copy) is built. [markersCreated] counts constructions for `PlayOffNothingCreatedTest`.
 */
internal object PlayMapAlloc {
    var markersCreated = 0
        private set

    fun markersOrNull(playOn: Boolean): PlayMarkers? = if (playOn) PlayMarkers().also { markersCreated++ } else null

    /** RecordScreen's live registry (AC 9 P2): built only while Play is on. */
    var recordRegistriesCreated = 0
        private set

    fun recordRegistryOrNull(playOn: Boolean, ferry: com.bainbridgetrails.play.time.FerryTimetable?): com.bainbridgetrails.play.fx.FxRendererRegistry? =
        if (playOn) com.bainbridgetrails.ui.play.map.playLiveRegistry(null, ferry) { AppGraph.playClock.now() }.also { recordRegistriesCreated++ } else null
}
