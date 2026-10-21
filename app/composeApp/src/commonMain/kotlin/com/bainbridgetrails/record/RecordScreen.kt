package com.bainbridgetrails.record

import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.ui.play.components.rememberPlay
import com.bainbridgetrails.ui.play.map.PlayMarkerLogic
import com.bainbridgetrails.ui.play.record.FindMoment
import com.bainbridgetrails.ui.play.record.FindMomentModel
import com.bainbridgetrails.ui.play.record.FindsChip
import com.bainbridgetrails.ui.play.record.PlayNearbyStrip
import com.bainbridgetrails.ui.play.record.PlayRecordLogic
import com.bainbridgetrails.ui.play.record.ResultsPlayLogic
import com.bainbridgetrails.ui.play.record.ResultsPlaySectionView
import com.bainbridgetrails.ui.play.record.itemsById
import com.bainbridgetrails.play.config.TierName
import com.bainbridgetrails.resources.play_find_title_egg
import com.bainbridgetrails.resources.play_find_title_treasure
import com.bainbridgetrails.resources.play_kraken_repelled_you
import com.bainbridgetrails.resources.play_tier_agate
import com.bainbridgetrails.resources.play_tier_glass_float
import com.bainbridgetrails.resources.play_tier_sea_glass
import com.bainbridgetrails.resources.play_tier_ships_bell
import com.bainbridgetrails.data.LocalGoal
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bainbridgetrails.auth.isSignedIn
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.play.fx.FxHostInputs
import com.bainbridgetrails.play.fx.FxOverlay
import com.bainbridgetrails.play.fx.PlayMapContent
import com.bainbridgetrails.play.fx.WithFxRenderOptions
import com.bainbridgetrails.play.fx.fxHostInputs
import com.bainbridgetrails.play.fx.rememberFxHost
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.map.StatusLayers
import com.bainbridgetrails.map.TrailMap
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_done
import com.bainbridgetrails.resources.common_loading_trails
import com.bainbridgetrails.resources.common_retry
import com.bainbridgetrails.resources.ic_play_arrow
import com.bainbridgetrails.resources.ic_warning
import com.bainbridgetrails.resources.perm_location_allow
import com.bainbridgetrails.resources.perm_precise_allow
import com.bainbridgetrails.resources.perm_precise_body
import com.bainbridgetrails.resources.record_close_a11y
import com.bainbridgetrails.resources.record_distance
import com.bainbridgetrails.resources.record_finish
import com.bainbridgetrails.resources.record_finish_body
import com.bainbridgetrails.resources.record_finish_confirm
import com.bainbridgetrails.resources.record_finish_title
import com.bainbridgetrails.resources.record_follow_a11y
import com.bainbridgetrails.resources.record_gps_approx
import com.bainbridgetrails.resources.record_gps_ok
import com.bainbridgetrails.resources.record_gps_slow
import com.bainbridgetrails.resources.record_gps_waiting
import com.bainbridgetrails.resources.record_idle_hint
import com.bainbridgetrails.resources.record_idle_private
import com.bainbridgetrails.resources.record_idle_title
import com.bainbridgetrails.resources.record_idle_what
import com.bainbridgetrails.resources.record_keep_walking
import com.bainbridgetrails.resources.record_live_collapse_a11y
import com.bainbridgetrails.resources.record_live_empty
import com.bainbridgetrails.resources.record_live_expand_a11y
import com.bainbridgetrails.resources.record_live_header
import com.bainbridgetrails.resources.record_live_header_count
import com.bainbridgetrails.resources.record_live_provisional
import com.bainbridgetrails.resources.record_loading_failed
import com.bainbridgetrails.resources.record_matching
import com.bainbridgetrails.resources.record_minimize_a11y
import com.bainbridgetrails.resources.record_needs_location
import com.bainbridgetrails.resources.record_not_now
import com.bainbridgetrails.resources.record_open_settings
import com.bainbridgetrails.resources.record_recording
import com.bainbridgetrails.resources.record_settings
import com.bainbridgetrails.resources.record_start
import com.bainbridgetrails.resources.record_stopped_reason
import com.bainbridgetrails.resources.record_time
import com.bainbridgetrails.resources.record_unverified
import com.bainbridgetrails.resources.record_unverified_body
import com.bainbridgetrails.resources.record_unverified_title
import com.bainbridgetrails.resources.recovery_body
import com.bainbridgetrails.resources.recovery_discard
import com.bainbridgetrails.resources.recovery_finish
import com.bainbridgetrails.resources.recovery_title
import com.bainbridgetrails.sync.SyncState
import com.bainbridgetrails.tracking.Perm
import com.bainbridgetrails.tracking.RecordingController
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.ui.Format
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.LiveTrailRow
import com.bainbridgetrails.ui.components.LocateButton
import com.bainbridgetrails.ui.components.LocateMode
import com.bainbridgetrails.ui.components.MapIconButton
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.RecordingDot
import com.bainbridgetrails.ui.components.StateChip
import com.bainbridgetrails.ui.theme.BtExtraDark
import com.bainbridgetrails.ui.theme.BtExtraLight
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Clock
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.bainbridgetrails.map.boundsOf
import com.bainbridgetrails.map.chartInteractions
import com.bainbridgetrails.map.fitTo
import com.bainbridgetrails.map.rememberTrailMapController
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.util.DpPadding

private sealed interface Load {
    data object Loading : Load
    data class Ready(val controller: RecordingController) : Load
    data class Failed(val message: String) : Load
}

/** GPS "waiting" longer than this shows `record_gps_slow` (product.md F5 States). */
private const val GPS_SLOW_MS = 60_000L

/**
 * The Record flow (F5/F6, ux.md §3.5): a full-screen overlay over the shell. Idle → permissions → recording (live
 * provisional trails, TL-8) → Finish confirm → finalizing → results; crash recovery as a dialog.
 * [onClose] leaves the overlay: "close" when idle, "minimize" while recording (recording continues).
 */
@Composable
fun RecordScreen(
    settings: AppSettings,
    onClose: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    launch: SpikeLaunch = SpikeLaunch(),
) {
    val context = rememberPlatformContext()
    var attempt by remember { mutableStateOf(0) }
    val load by produceState<Load>(Load.Loading, context, attempt) {
        value = Load.Loading
        value = try {
            Load.Ready(RecordingGraph.controller(context).await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: "")
        }
    }
    val style = settings.effectiveMapStyle(isSystemInDarkTheme())
    when (val l = load) {
        is Load.Ready -> RecordContent(l.controller, settings, style, launch, context, onClose, onSignIn, modifier)
        else -> Box(modifier.fillMaxSize()) {
            TrailMap(style, Modifier.fillMaxSize(), initialCamera = launch.camera)
            TopControls(recording = false, onClose = onClose, chips = {}, locate = null)
            BottomCard(Modifier.align(Alignment.BottomCenter)) {
                if (l is Load.Failed) {
                    Text(stringResource(LocalGoal.current.s(Res.string.record_loading_failed)), style = MaterialTheme.typography.titleMedium)
                    PrimaryButton(stringResource(Res.string.common_retry), onClick = { attempt++ }, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BusyIndicator(24.dp)
                        Text(stringResource(LocalGoal.current.s(Res.string.common_loading_trails)), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordContent(
    controller: RecordingController,
    settings: AppSettings,
    style: MapStyle,
    launch: SpikeLaunch,
    context: PlatformContext,
    onClose: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier,
) {
    val state by controller.state.collectAsState()
    val notice by controller.notice.collectAsState()
    val requester = rememberPermissionRequester()
    var permissionQueue by remember { mutableStateOf<List<Perm>>(emptyList()) }
    val geofences = rememberGeofencePlatform()
    var showSettings by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var resultsSheetH by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val recording = state as? RecordingState.Recording
    // Play (ux.md §3.8): null while off; finds are provisional and sensed by the graph-level PlaySensing observer.
    val installed = remember { AppGraph.installedOrNull() }
    val play = if (installed != null) rememberPlay(installed, settings.play.enabled) else null
    val sensed = play?.sensed?.collectAsState()?.value.orEmpty()
    val sensedAtStart = remember(recording?.sessionId) { sensed }
    val findsThisWalk = if (recording != null) (sensed - sensedAtStart).size else 0
    var moment by remember { mutableStateOf<FindMomentModel?>(null) }
    val titles = PlayRecordLogic.FindTitles(
        stringResource(Res.string.play_find_title_treasure), stringResource(Res.string.play_find_title_egg), stringResource(Res.string.play_kraken_repelled_you),
    )
    val tierNames = mapOf(
        TierName.sea_glass to stringResource(Res.string.play_tier_sea_glass), TierName.agate to stringResource(Res.string.play_tier_agate),
        TierName.glass_float to stringResource(Res.string.play_tier_glass_float), TierName.ships_bell to stringResource(Res.string.play_tier_ships_bell),
    )
    LaunchedEffect(play) {
        play?.finds?.collectLatest { f ->
            moment = PlayRecordLogic.moment(f, play.itemsById()[f.occurrence.itemId], { tierNames[it] ?: it.name }, titles)
            delay(4_000)
            moment = null
        }
    }
    LaunchedEffect(recording != null) {
        while (recording != null) {
            controller.tick()
            delay(1_000)
        }
    }
    if (recording == null) confirmFinish = false
    // Back from Settings (or another app): a location grant clears the NeedsPermission panel.
    LifecycleResumeEffect(requester) {
        if (requester.status(Perm.LOCATION_WHILE_IN_USE) == PermStatus.GRANTED) {
            controller.onPermissions(Perm.entries.filterTo(mutableSetOf()) { requester.status(it) == PermStatus.GRANTED })
        }
        onPauseOrDispose { }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            controller.clearNotice()
        }
    }

    fun startWithPermissions(granted: Set<Perm>) {
        controller.onPermissions(granted)
        controller.start()
        follow = true
    }

    fun onStartTapped() {
        val ask = permissionsToAsk(requester, RecordingGraph.askedOptional)
        if (ask.isEmpty()) startWithPermissions(Perm.entries.filterTo(mutableSetOf()) { requester.status(it) == PermStatus.GRANTED })
        else permissionQueue = ask
    }

    fun done() {
        controller.dismiss()
        onClose()
    }

    // ux.md §2.3: finalizing → back disabled; results → same as Done; otherwise close / minimize.
    FlowBackHandler(enabled = permissionQueue.isEmpty()) {
        when (state) {
            is RecordingState.Finalizing, is RecordingState.Stopped -> Unit
            is RecordingState.Done -> done()
            else -> onClose()
        }
    }

    // The record map draws the same live Play scene (fog, ink, finds) as the Map tab (product § 5.3 Record row).
    val recordFerry = com.bainbridgetrails.ui.play.map.rememberFerryTimetable(play)
    val recordRegistry = remember(recordFerry) { com.bainbridgetrails.ui.play.map.playLiveRegistry(null, recordFerry) { AppGraph.playClock.now() } }
    Box(modifier.fillMaxSize()) {
        RecordMap(
            controller, style, launch,
            follow = follow && state !is RecordingState.Done,
            fitWalkAbove = if (state is RecordingState.Done) resultsSheetH else null,
            // Play gate (engine PF-8/PF-9): null when Play is off; ambient effects rest while a walk records.
            fxInputs = fxHostInputs(settings.play, recording = recording != null, renderers = recordRegistry),
            play = play,
        )

        val busy = state is RecordingState.Finalizing || state is RecordingState.Stopped
        TopControls(
            recording = recording != null,
            onClose = if (busy) null else if (state is RecordingState.Done) ::done else onClose,
            chips = {
                recording?.let { r -> RecordingChips(r) }
                FindsChip(findsThisWalk)
                notice?.let { StateChip(it) }
            },
            locate = if (recording != null || state == RecordingState.Idle) ({
                LocateButton(if (follow) LocateMode.Following else LocateMode.Idle, onClick = { follow = !follow })
            }) else null,
        )

        val bottom = Modifier.align(Alignment.BottomCenter)
        when (val s = state) {
            is RecordingState.Done -> BottomSheetSurface(bottom.onSizeChanged { resultsSheetH = with(density) { it.height.toDp() } }, maxFraction = 0.78f) {
                val graph = AppGraph.installedOrNull()
                val auth = graph?.auth?.state?.collectAsStateWithLifecycle()?.value
                val sync = graph?.sync?.state?.collectAsStateWithLifecycle()?.value
                val data = AppGraph.datasets.state.collectAsStateWithLifecycle().value as? Loadable.Ready
                val isRoads = com.bainbridgetrails.data.LocalGoal.current.isRoads
                // Roads milestones (product.md §5.3), from this walk's own summary numbers; nothing stored.
                val milestones = remember(s.summary, data, settings.units, isRoads) {
                    val d = data?.value
                    if (!isRoads || d == null) emptyList()
                    else com.bainbridgetrails.ui.walkMilestones(
                        d, s.summary, settings.units,
                        islandReached = AppGraph.Celebration.islandReached(s.summary.islandBefore, s.summary.islandAfter),
                    )
                }
                ResultsSheet(
                    summary = s.summary,
                    units = settings.units,
                    status = { f -> data?.value?.progress?.status(f) ?: TrailStatus.PARTWAY },
                    sync = sync?.toResultsSync(),
                    signedIn = auth?.isSignedIn == true,
                    recovered = RecordingGraph.recoveredSessionId == s.summary.sessionId,
                    onSignIn = { done(); onSignIn() },
                    onDone = ::done,
                    milestones = milestones,
                    islandTotalM = data?.value?.dataset?.islandTotalM ?: 0.0,
                    play = play?.let { repo ->
                        {
                            val st by repo.store.state.collectAsStateWithLifecycle()
                            val results by repo.claimResults.collectAsStateWithLifecycle()
                            val section = remember(st, results, s.summary.sessionId) {
                                val items = repo.itemsById()
                                ResultsPlayLogic.section(s.summary.sessionId, st.logbook, results[s.summary.sessionId], { items[it]?.copy?.title ?: it }, summaryShown = false)
                            }
                            section?.let { ResultsPlaySectionView(it) }
                        }
                    },
                )
            }
            is RecordingState.Recover -> AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(Res.string.recovery_title)) },
                text = { Text(stringResource(Res.string.recovery_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        RecordingGraph.recoveredSessionId = s.sessionId
                        RecordingGraph.scope.launch { controller.finishRecovered(s.sessionId) }
                    }) { Text(stringResource(Res.string.recovery_finish)) }
                },
                dismissButton = { TextButton(onClick = { controller.discardRecovered(s.sessionId) }) { Text(stringResource(Res.string.recovery_discard)) } },
            )
            is RecordingState.Finalizing, is RecordingState.Stopped -> BottomSheetSurface(bottom) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.screenH, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (s is RecordingState.Stopped) {
                        Banner(stringResource(Res.string.record_stopped_reason, s.reason), Res.drawable.ic_warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    }
                    BusyIndicator(48.dp)
                    Text(stringResource(LocalGoal.current.s(Res.string.record_matching)), style = MaterialTheme.typography.titleMedium)
                }
            }
            is RecordingState.Recording -> BottomSheetSurface(bottom, maxFraction = 0.7f) {
                RecordingSheet(s, controller, settings, context, onFinish = { confirmFinish = true }, nearby = play?.let { repo ->
                    {
                        val agenda by repo.agenda.collectAsState()
                        val config by repo.config.collectAsState()
                        val track by controller.track.collectAsState()
                        val here = track.lastOrNull()
                        val items = config?.config?.items
                        val rows = remember(agenda, here?.lat, here?.lon, sensed) {
                            val a = agenda
                            if (a == null || here == null || items == null || a.day.quietDay) emptyList()
                            else PlayRecordLogic.nearby(
                                PlayMarkerLogic.markers(a, items.associate { it.id to it.lore }, emptySet()),
                                GeoPoint(lat = here.lat, lon = here.lon), sensed,
                            ) { m -> m.tier?.let { tierNames[it] } ?: items.firstOrNull { it.id == m.itemId }?.copy?.title ?: m.itemId }
                        }
                        PlayNearbyStrip(rows, { Format.distance(it, settings.units) })
                    }
                })
            }
            RecordingState.Idle, is RecordingState.NeedsPermission -> BottomCard(bottom) {
                if (s is RecordingState.NeedsPermission) {
                    val approximate = requester.locationAccess() == LocationAccess.APPROXIMATE
                    val settingsOnly = requester.status(Perm.LOCATION_WHILE_IN_USE) == PermStatus.PERMANENTLY_DENIED
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(if (approximate) LocalGoal.current.s(Res.string.perm_precise_body) else Res.string.record_needs_location),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { if (settingsOnly) openAppSettings(context) else onStartTapped() }) {
                                Text(stringResource(if (settingsOnly) Res.string.record_open_settings else if (approximate) Res.string.perm_precise_allow else Res.string.perm_location_allow))
                            }
                            TextButton(onClick = { controller.dismiss() }) { Text(stringResource(Res.string.record_not_now)) }
                        }
                    }
                }
                IdleContent(onStart = ::onStartTapped, onSettings = if (geofences.supported) ({ showSettings = true }) else null)
            }
        }

        moment?.let { m ->
            Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 56.dp, start = 16.dp, end = 16.dp)) {
                FindMoment(m, onDismiss = { moment = null })
            }
        }

        if (showSettings) GeofenceSettings(geofences, onClose = { showSettings = false })

        if (confirmFinish && recording != null) {
            AlertDialog(
                onDismissRequest = { confirmFinish = false },
                title = { Text(stringResource(Res.string.record_finish_title)) },
                text = { Text(stringResource(LocalGoal.current.s(Res.string.record_finish_body))) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmFinish = false
                        RecordingGraph.scope.launch { controller.stop() }
                    }) { Text(stringResource(Res.string.record_finish_confirm)) }
                },
                dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text(stringResource(Res.string.record_keep_walking)) } },
            )
        }

        if (permissionQueue.isNotEmpty()) {
            PermissionFlow(
                queue = permissionQueue,
                requester = requester,
                onAsked = { if (!rationale(it).required) RecordingGraph.askedOptional += it },
                onOpenSettings = { openAppSettings(context) },
                onDone = { granted ->
                    permissionQueue = emptyList()
                    startWithPermissions(granted)
                },
            )
        }
    }
}

private fun SyncState.toResultsSync(): ResultsSync? = when (this) {
    SyncState.SignedOut -> null
    is SyncState.Idle -> if (pendingWalks > 0) ResultsSync.PENDING else ResultsSync.OK
    is SyncState.Syncing -> ResultsSync.RUNNING
    is SyncState.Pending -> ResultsSync.PENDING
    is SyncState.Error, is SyncState.Rejected -> ResultsSync.FAILED
}

/** Minimize/close chevron (top-left), state chips (top-center), follow toggle (top-right). */
@Composable
private fun TopControls(
    recording: Boolean,
    onClose: (() -> Unit)?,
    chips: @Composable () -> Unit,
    locate: (@Composable () -> Unit)?,
) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
        if (onClose != null) {
            MapIconButton(
                BtIcons.KeyboardArrowDown,
                stringResource(if (recording) Res.string.record_minimize_a11y else Res.string.record_close_a11y),
                onClose,
                Modifier.align(Alignment.TopStart),
            )
        }
        Row(
            Modifier.align(Alignment.TopCenter).padding(horizontal = 56.dp).heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) { chips() }
        // Below the map's compass (top-right) so they never overlap.
        if (locate != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 56.dp)) { locate() }
    }
}

@Composable
private fun RecordingChips(r: RecordingState.Recording) {
    var explain by remember { mutableStateOf(false) }
    when {
        r.waitingForGps -> StateChip(stringResource(Res.string.record_gps_waiting), leading = { BusyIndicator(16.dp) })
        !r.preciseLocation -> StateChip(stringResource(Res.string.record_gps_approx), icon = Res.drawable.ic_warning)
        else -> StateChip(stringResource(Res.string.record_gps_ok), leading = {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, PillShape))
        })
    }
    if (!r.activityAvailable || !r.stepsAvailable) {
        StateChip(stringResource(Res.string.record_unverified), icon = BtIcons.Info, onClick = { explain = true })
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(Res.string.record_unverified_title)) },
            text = { Text(stringResource(Res.string.record_unverified_body)) },
            confirmButton = { TextButton(onClick = { explain = false }) { Text(stringResource(Res.string.common_done)) } },
        )
    }
}

/** ux.md §3.5a: title, what is tracked, Start (64dp), privacy line. */
@Composable
private fun IdleContent(onStart: () -> Unit, onSettings: (() -> Unit)?) {
    Text(stringResource(Res.string.record_idle_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    Text(stringResource(LocalGoal.current.s(Res.string.record_idle_what)), style = MaterialTheme.typography.bodyMedium)
    PrimaryButton(stringResource(Res.string.record_start), onClick = onStart, modifier = Modifier.fillMaxWidth(), icon = Res.drawable.ic_play_arrow, large = true)
    Text(stringResource(Res.string.record_idle_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BtIcon(BtIcons.Lock, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.record_idle_private), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Geofence opt-in: debug builds only (rulings R2/R11).
        if (onSettings != null) TextButton(onClick = onSettings) { Text(stringResource(Res.string.record_settings)) }
    }
}

/** ux.md §3.5d RecordSheet: recording dot, time + distance, live provisional trails (TL-8), Finish. */
@Composable
private fun RecordingSheet(
    r: RecordingState.Recording,
    controller: RecordingController,
    settings: AppSettings,
    context: PlatformContext,
    onFinish: () -> Unit,
    nearby: (@Composable () -> Unit)? = null,
) {
    val live = remember(r.sessionId) { RecordingGraph.liveCoverage(context, r.sessionId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // TL-8: every 30 s, only while this screen is composed (Record visible) and the app is in the foreground.
    LaunchedEffect(live, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    live.refresh(controller.track.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Provisional only: the next round (or Finish) gets it right.
                }
                delay(LiveCoverage.INTERVAL_MS)
            }
        }
    }
    val rows by live.rows.collectAsState()
    // A status crossing mid-walk: light haptic (no celebration until Finish, ux.md §3.5d).
    val haptics = LocalHapticFeedback.current
    val seen = remember(r.sessionId) { HashMap<String, TrailStatus>() }
    LaunchedEffect(rows) {
        var crossed = false
        for (row in rows) {
            val prev = seen.put(row.trailId, row.status)
            if (prev != null && row.status.ordinal > prev.ordinal) crossed = true
        }
        if (crossed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    var expanded by rememberSaveable(r.sessionId) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.padding(horizontal = BtTheme.spacing.screenH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordingDot()
                Text(stringResource(Res.string.record_recording), style = MaterialTheme.typography.labelLarge, color = BtTheme.colors.recording)
            }
            Row(Modifier.fillMaxWidth()) {
                ElapsedStat(r.startedMs, Modifier.weight(1f))
                LiveStat(Format.distance(r.distanceM, settings.units), stringResource(Res.string.record_distance), Modifier.weight(1f))
            }
            if (r.waitingForGps && Clock.System.now().toEpochMilliseconds() - (r.lastFixMs ?: r.startedMs) > GPS_SLOW_MS) {
                Text(stringResource(Res.string.record_gps_slow), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Section header: tap to expand the sheet (peek shows the top two rows).
        // Nearby (ux.md §3.8) lives in the expanded sheet only, so a short list with Nearby can still expand.
        val canExpand = rows.size > 2 || nearby != null
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = BtTheme.spacing.screenH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (rows.isEmpty()) stringResource(LocalGoal.current.s(Res.string.record_live_header)) else stringResource(LocalGoal.current.s(Res.string.record_live_header_count), rows.size),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (canExpand) {
                val a11y = stringResource(if (expanded) LocalGoal.current.s(Res.string.record_live_collapse_a11y) else LocalGoal.current.s(Res.string.record_live_expand_a11y))
                BtIcon(BtIcons.ExpandMore, a11y, Modifier.rotate(if (expanded) 0f else 180f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(
            Modifier.weight(1f, fill = false).animateContentSize(if (BtTheme.motion.reduced) tween(0) else tween(300))
                .then(if (expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = BtTheme.spacing.screenH),
        ) {
            if (rows.isEmpty()) {
                Text(stringResource(LocalGoal.current.s(Res.string.record_live_empty)), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(Res.string.record_live_provisional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (row in if (expanded) rows else rows.take(2)) {
                    LiveTrailRow(row.name, row.before, row.after, row.status)
                }
            }
            // Play Nearby (ux.md §3.8): after the pure live list, expanded sheet only.
            if (expanded) nearby?.invoke()
        }
        PrimaryButton(
            stringResource(Res.string.record_finish),
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.screenH, vertical = 16.dp),
        )
    }
}

/**
 * The map and live track. Isolated so per-second and per-fix UI updates elsewhere don't recompose the map:
 * it only changes when [rememberThrottledTrack] hands it a new list (≤ every 5 s or 10 m).
 */
@Composable
private fun RecordMap(
    controller: RecordingController,
    style: MapStyle,
    launch: SpikeLaunch,
    follow: Boolean,
    fitWalkAbove: Dp? = null,
    fxInputs: FxHostInputs? = null,
    play: com.bainbridgetrails.play.PlayRepository? = null,
) {
    val track by controller.track.collectAsState()
    val drawn = rememberThrottledTrack(track)
    val at = if (follow) drawn.lastOrNull()?.let { Position(longitude = it.lon, latitude = it.lat) } else null
    // Results (ux.md §3.5f): frame the whole walk in the map left above the results sheet.
    val mapController = rememberTrailMapController()
    val map = mapController.mapState
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val reduced = BtTheme.motion.reduced
    LaunchedEffect(map, fitWalkAbove != null, fitWalkAbove?.let { it > 0.dp }) {
        val sheet = fitWalkAbove?.takeIf { it > 0.dp } ?: return@LaunchedEffect
        val bounds = boundsOf(listOf(track.map { listOf(it.lon, it.lat) })) ?: return@LaunchedEffect
        map?.fitTo(bounds, DpPadding(left = 40.dp, top = topInset + 72.dp, right = 40.dp, bottom = sheet + 24.dp), animate = !reduced)
    }
    val buckets = AppGraph.installedOrNull()?.progress?.snapshot?.collectAsState()?.value?.segmentBuckets.orEmpty()
    // Track colours follow the map style (a dark map in a light app still gets the dark-map track).
    val extra = if (style.isDark) BtExtraDark else BtExtraLight
    val fx = if (fxInputs != null) rememberFxHost(fxInputs, mapController) else null
    val fxOverlay: (@Composable MapOverlayScope.() -> Unit)? = remember(fx) { if (fx != null) ({ FxOverlay(fx) }) else null }
    if (fx != null) com.bainbridgetrails.ui.play.map.PlayMapEffectsSync(fx, play, buckets, ferry = false)
    // Taps reset FX's 20 s ambient idle stop (PF-2) like camera moves do; no Play = today's interactions.
    val interactions = remember(fx) { if (fx != null) chartInteractions(onTap = { fx.noteInteraction() }) else chartInteractions() }
    WithFxRenderOptions(fx) { renderOptions ->
        TrailMap(
            style, Modifier.fillMaxSize(), initialCamera = launch.camera, controlsTopPadding = 56.dp, follow = at, controller = mapController,
            interactions = interactions,
            fxOverlay = fxOverlay,
            renderOptions = renderOptions,
        ) {
            // Composition order = z-order (mapContentOrder); the live track stays above every FX layer.
            PlayMapContent(
                fx,
                status = { casingFx -> StatusLayers(buckets, dark = style.isDark, casingFx = casingFx) },
                location = { TrackLayer(drawn, extra.userTrack, extra.userTrackCasing) },
            )
        }
    }
}

/** Elapsed time with its own 1 s clock, so only this text recomposes every second (timer is not a live region). */
@Composable
private fun ElapsedStat(startedMs: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(startedMs) {
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    LiveStat(Format.duration((now - startedMs).coerceAtLeast(0)), stringResource(Res.string.record_time), modifier)
}

@Composable
private fun LiveStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = "$label $value" }) {
        Text(value, style = BtTheme.type.statLive)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Indeterminate spinner; a static ring under reduced motion (Compose indicators always rotate, ux.md §3.5e). */
@Composable
private fun BusyIndicator(size: androidx.compose.ui.unit.Dp) {
    if (BtTheme.motion.reduced) {
        val c = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(size)) {
            val w = this.size.minDimension / 12
            drawArc(c, -90f, 270f, false, style = Stroke(w), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w))
        }
    } else {
        CircularProgressIndicator(Modifier.size(size), strokeWidth = if (size > 24.dp) 4.dp else 2.dp)
    }
}

/** Floating idle card (ux.md §3.5a): 12dp from the edges, above the navigation bar. */
@Composable
private fun BottomCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(12.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** Bottom sheet surface (recording / finalizing / results): top-rounded, up to [maxFraction] of the screen height. */
@Composable
private fun BottomSheetSurface(modifier: Modifier = Modifier, maxFraction: Float = 0.6f, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val max = maxHeight * maxFraction
        Surface(
            Modifier.fillMaxWidth().heightIn(max = max),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                // Drag-handle look (decorative; the sheet has fixed detents in MVP).
                Box(
                    Modifier.padding(top = 10.dp).size(32.dp, 4.dp).align(Alignment.CenterHorizontally)
                        .background(MaterialTheme.colorScheme.outlineVariant, PillShape),
                )
                content()
            }
        }
    }
}
