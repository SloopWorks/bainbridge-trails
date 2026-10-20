package com.bainbridgetrails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import org.maplibre.compose.interaction.ClickResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.map.road
import com.bainbridgetrails.map.RoadEnd
import com.bainbridgetrails.map.StatusLayerSpec
import androidx.compose.ui.unit.Dp
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.AttributionMode
import com.bainbridgetrails.map.IslandCredit
import com.bainbridgetrails.map.MapFocus
import com.bainbridgetrails.map.SelectedTrail
import com.bainbridgetrails.map.StatusLayers
import com.bainbridgetrails.map.TrailFacts
import com.bainbridgetrails.map.TrailFactsRepository
import com.bainbridgetrails.map.TrailMap
import com.bainbridgetrails.map.boundsOf
import com.bainbridgetrails.map.directionsUri
import com.bainbridgetrails.map.fitTo
import com.bainbridgetrails.map.formatCovered
import com.bainbridgetrails.map.formatTrailLength
import com.bainbridgetrails.map.islandCredit
import com.bainbridgetrails.map.rememberTrailMapController
import com.bainbridgetrails.map.trailLines
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.detail_back
import com.bainbridgetrails.resources.detail_community_title
import com.bainbridgetrails.resources.detail_counts
import com.bainbridgetrails.resources.detail_covered_value
import com.bainbridgetrails.resources.detail_directions
import com.bainbridgetrails.resources.detail_directions_a11y
import com.bainbridgetrails.resources.detail_go_to_recording
import com.bainbridgetrails.resources.detail_length
import com.bainbridgetrails.resources.detail_length_official_note
import com.bainbridgetrails.resources.detail_map_a11y
import com.bainbridgetrails.resources.detail_not_counted
import com.bainbridgetrails.resources.detail_not_found
import com.bainbridgetrails.resources.detail_official_map
import com.bainbridgetrails.resources.detail_partly_counted
import com.bainbridgetrails.resources.detail_start_walk
import com.bainbridgetrails.resources.detail_trailhead_row
import com.bainbridgetrails.resources.detail_trailheads
import com.bainbridgetrails.resources.detail_walked
import com.bainbridgetrails.resources.ic_directions
import com.bainbridgetrails.resources.ic_place
import com.bainbridgetrails.resources.ic_straighten
import com.bainbridgetrails.resources.map_error_open_link
import com.bainbridgetrails.tracking.RecordingState
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.CommunityCard
import com.bainbridgetrails.ui.components.CommunityState
import com.bainbridgetrails.ui.components.ElevationSummary
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.MapIconButton
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.ProgressBar
import com.bainbridgetrails.ui.components.ProgressRing
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.StatTile
import com.bainbridgetrails.ui.components.StatTileRow
import com.bainbridgetrails.ui.components.StatusChip
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.util.DpPadding

private val MiniMapHeight = 260.dp

/** Roads are short: a lower mini map (docs/roads/ux.md §3.1). */
private val RoadMiniMapHeight = 200.dp

/**
 * Trail detail (ux.md §3.4, product F4): fitted mini map with the trail highlighted, Fraunces header, status +
 * progress, facts grid, trailheads with Directions, official map link, community stats and a sticky Start walk.
 */
@Composable
fun TrailDetailScreen(
    graph: AppGraph.Installed,
    settings: AppSettings,
    trailId: String,
    onBack: () -> Unit,
    onStartWalk: () -> Unit,
    onShowOnMap: () -> Unit = {},
) {
    DatasetGate { data ->
        val meta = data.dataset.trails.firstOrNull { it.id == trailId }
        if (meta == null) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(Res.string.detail_not_found), action = stringResource(Res.string.detail_back) to onBack)
            }
        } else {
            TrailDetailContent(graph, settings, data, trailId, meta.name, onBack, onStartWalk, onShowOnMap)
        }
    }
}

@Composable
private fun TrailDetailContent(
    graph: AppGraph.Installed,
    settings: AppSettings,
    data: TrailData,
    trailId: String,
    name: String,
    onBack: () -> Unit,
    onStartWalk: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    val facts by produceState<TrailFacts?>(null, trailId) { value = runCatching { TrailFactsRepository.all()[trailId] }.getOrNull() }
    val stats by graph.trailStats.state.collectAsStateWithLifecycle()
    val recordingController by produceState<com.bainbridgetrails.tracking.RecordingController?>(null, graph) {
        value = runCatching { graph.controller().await() }.getOrNull()
    }
    val recording = recordingController?.state?.collectAsStateWithLifecycle()?.value is RecordingState.Recording
    LaunchedEffect(Unit) { graph.trailStats.get() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val uri = LocalUriHandler.current

    val p = snapshot?.trail(trailId)
    val status = p?.status ?: TrailStatus.NOT_STARTED
    val fraction = p?.fraction ?: 0.0
    val lengthMi = facts?.lengthMi ?: data.dataset.trails.first { it.id == trailId }.displayLengthMi
    val credit = remember(trailId, data) { data.dataset.islandCredit(trailId) }
    val lines = remember(trailId, data) { data.dataset.trailLines(trailId) }
    // Roads (docs/roads/ux.md §3): road blocks replace the trail facts, chosen by the row's data.
    val meta = remember(trailId, data) { data.dataset.trails.first { it.id == trailId } }
    val road = meta.road(facts?.roadEnds.orEmpty())
    val roadProfile = road != null
    val startWalk = rememberStartWalk(graph, onStartWalk)
    val linkError = { scope.launch { snackbar.showSnackbar(getString(Res.string.map_error_open_link)) } }

    fun open(link: String) {
        try {
            uri.openUri(link)
        } catch (e: Exception) {
            linkError()
        }
    }

    val community: CommunityState = run {
        val s = stats
        when {
            s.stats != null -> s.forTrail(trailId).let { CommunityState.Loaded(it?.completedCount, it?.medianPct?.div(100.0)) }
            s.error is ApiError.Offline -> CommunityState.Offline
            s.error != null -> CommunityState.Error
            else -> CommunityState.Loading
        }
    }

    Box(Modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        val density = androidx.compose.ui.platform.LocalDensity.current
        // Past the mini map the floating buttons become a solid bar with the trail name (no text under icons).
        val mapHeight = if (roadProfile) RoadMiniMapHeight else MiniMapHeight
        val collapsed = with(density) { scroll.value.toDp() } > mapHeight - 64.dp
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            MiniMap(settings, trailId, name, lines, status, snapshot?.segmentBuckets.orEmpty(), mapHeight, road?.roadEnds.orEmpty()) {
                MapFocus.request(trailId)
                onShowOnMap()
            }
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = BtTheme.spacing.screenH),
            ) {
                Spacer(Modifier.height(20.dp))
                Text(name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (road != null) {
                    RoadDetailBody(
                        data, trailId, road, fraction, status, settings.units,
                        community = community,
                        onRetryCommunity = { scope.launch { graph.trailStats.get(force = true) } },
                        onDirections = { lat, lon, label -> open(directionsUri(lat, lon, label)) },
                        onShowOnMap = { MapFocus.request(trailId); onShowOnMap() },
                    )
                    Spacer(Modifier.height(140.dp))
                    return@Column
                }
                val sub = listOfNotNull(facts?.park, facts?.manager?.takeIf { it != facts?.park }).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(sub, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Status + progress.
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip(status)
                            if (status == TrailStatus.COMPLETE) BtIcon(BtIcons.Verified, null, size = 20.dp, tint = BtTheme.colors.complete.accent)
                        }
                        val (covered, total) = formatCovered(fraction, lengthMi, settings.units)
                        Text(
                            stringResource(Res.string.detail_walked, stringResource(Res.string.detail_covered_value, covered, total)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProgressRing(fraction, status, size = 72.dp, stroke = 6.dp)
                }
                ProgressBar(fraction, status, Modifier.padding(top = 12.dp))

                if (credit != IslandCredit.FULL) {
                    CreditNote(credit, Modifier.padding(top = 12.dp))
                }

                // Facts grid.
                Spacer(Modifier.height(BtTheme.spacing.sectionGap))
                StatTileRow {
                    StatTile(
                        formatTrailLength(lengthMi, settings.units),
                        stringResource(if (facts?.isOfficialLength == true) Res.string.detail_length_official_note else Res.string.detail_length),
                        Modifier.weight(1f),
                        icon = Res.drawable.ic_straighten,
                    )
                    ElevationSummary(facts?.gainM, settings.units, Modifier.weight(1f))
                }

                val heads = facts?.trailheads.orEmpty()
                if (heads.isNotEmpty()) {
                    Spacer(Modifier.height(BtTheme.spacing.lg))
                    SectionHeader(stringResource(Res.string.detail_trailheads, heads.size))
                    heads.forEachIndexed { i, th ->
                        val label = th.name ?: stringResource(Res.string.detail_trailhead_row, i + 1)
                        TrailheadRow(label) { open(directionsUri(th.lat, th.lon, label)) }
                    }
                }

                facts?.officialMapUrl?.let { url ->
                    Spacer(Modifier.height(BtTheme.spacing.sm))
                    LinkRow(stringResource(Res.string.detail_official_map), hostOf(url)) { open(url) }
                }

                Spacer(Modifier.height(BtTheme.spacing.lg))
                SectionHeader(stringResource(Res.string.detail_community_title))
                CommunityCard(community, onRetry = { scope.launch { graph.trailStats.get(force = true) } })

                Spacer(Modifier.height(140.dp))
            }
        }

        // Back + "show on map": floating over the mini map, a solid bar once it scrolls away.
        val barBg by androidx.compose.animation.animateColorAsState(
            if (collapsed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        )
        Row(
            Modifier.fillMaxWidth()
                .then(if (collapsed) Modifier.shadow(2.dp) else Modifier)
                .background(barBg)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapIconButton(BtIcons.ArrowBack, stringResource(Res.string.detail_back), onBack)
            androidx.compose.animation.AnimatedVisibility(collapsed, Modifier.weight(1f), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Text(name, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!collapsed) Spacer(Modifier.weight(1f))
            MapIconButton(BtIcons.Map, stringResource(Res.string.detail_map_a11y, name), { MapFocus.request(trailId); onShowOnMap() })
        }

        // Sticky Start walk on a surface fade (none for an uncounted road: it isn't part of the goal).
        val surface = MaterialTheme.colorScheme.background
        if (road?.counted != false) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to surface.copy(alpha = 0f), 0.35f to surface.copy(alpha = 0.94f), 1f to surface))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = BtTheme.spacing.screenH)
                // Clear of the Record disc, which rises above the bottom bar.
                .padding(top = 28.dp, bottom = BtTheme.spacing.recordFabRise + 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            PrimaryButton(
                stringResource(if (recording) Res.string.detail_go_to_recording else Res.string.detail_start_walk),
                if (recording) onStartWalk else startWalk,
                Modifier.fillMaxWidth().widthIn(max = BtTheme.spacing.contentMaxWidth),
                icon = BtIcons.DirectionsWalk,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 112.dp))
    }
}

/** Non-interactive map fitted to the trail (selected style, others dimmed). Tap → Map tab focused on the trail. */
@Composable
private fun MiniMap(
    settings: AppSettings,
    trailId: String,
    name: String,
    lines: List<List<List<Double>>>,
    status: TrailStatus,
    buckets: Map<TrailStatus, List<String>>,
    height: Dp = MiniMapHeight,
    roadEnds: List<RoadEnd> = emptyList(),
    onOpen: () -> Unit,
) {
    val style = settings.effectiveMapStyle(androidx.compose.foundation.isSystemInDarkTheme())
    val controller = rememberTrailMapController()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val reduced = BtTheme.motion.reduced
    val spec = StatusLayerSpec.current
    // A road end can sit just past the road's own geometry: keep the pin in frame.
    val bounds = remember(lines, roadEnds) { boundsOf(lines + roadEnds.map { listOf(listOf(it.lon, it.lat)) }) }
    val openLatest by rememberUpdatedState(onOpen)
    val interactions = remember {
        MapInteractions(MapInteractions.None) {
            callbacks {
                click {
                    onEvent {
                        openLatest()
                        ClickResult.Consume
                    }
                }
            }
        }
    }
    LaunchedEffect(controller.mapState, bounds) {
        val map = controller.mapState ?: return@LaunchedEffect
        val b = bounds ?: return@LaunchedEffect
        map.fitTo(
            b, DpPadding(left = 64.dp, top = top + 24.dp, right = 64.dp, bottom = 20.dp), animate = false,
            maxZoom = spec.fitZoomRange.endInclusive, minZoom = spec.fitZoomRange.start,
        )
    }
    val a11y = stringResource(Res.string.detail_map_a11y, name)
    Box(
        Modifier.fillMaxWidth().height(height + top)
            .clearAndSetSemantics {
                contentDescription = a11y
                role = Role.Button
                onClick { onOpen(); true }
            },
    ) {
        TrailMap(
            style,
            Modifier.fillMaxSize(),
            controller = controller,
            interactions = interactions,
            attribution = AttributionMode.InfoOnly,
            showScaleBar = false,
        ) {
            StatusLayers(
                buckets,
                style,
                SelectedTrail(trailId, lines, status),
                reduced,
                spec = spec,
                roadEnds = roadEnds,
            )
        }
    }
}

@Composable
private fun CreditNote(credit: IslandCredit, modifier: Modifier = Modifier) {
    val text = stringResource(
        when (credit) {
            IslandCredit.NONE -> Res.string.detail_not_counted
            IslandCredit.PARTIAL -> Res.string.detail_partly_counted
            IslandCredit.FULL -> Res.string.detail_counts
        },
    )
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BtIcon(BtIcons.Info, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrailheadRow(label: String, onDirections: () -> Unit) {
    val a11y = stringResource(Res.string.detail_directions_a11y, label)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onDirections)
            .clearAndSetSemantics { contentDescription = a11y; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        BtIcon(Res.drawable.ic_place, null, size = 22.dp, tint = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(
            Modifier.background(MaterialTheme.colorScheme.secondaryContainer, PillShape).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BtIcon(Res.drawable.ic_directions, null, size = 16.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(stringResource(Res.string.detail_directions), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).semantics(mergeDescendants = true) { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        BtIcon(BtIcons.OpenInNew, null, size = 22.dp, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BtIcon(BtIcons.ChevronRight, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "www.biparks.org/…" → "biparks.org". */
internal fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').removePrefix("www.").ifEmpty { url }
