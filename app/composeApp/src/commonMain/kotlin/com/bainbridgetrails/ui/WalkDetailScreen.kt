package com.bainbridgetrails.ui

import com.bainbridgetrails.data.LocalGoal
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.AttributionMode
import com.bainbridgetrails.map.StatusLayers
import com.bainbridgetrails.map.TrailMap
import com.bainbridgetrails.map.boundsOf
import com.bainbridgetrails.map.fitTo
import com.bainbridgetrails.map.rememberTrailMapController
import com.bainbridgetrails.map.trailLines
import com.bainbridgetrails.record.Banner
import com.bainbridgetrails.record.IslandTile
import com.bainbridgetrails.record.WalkTrackLayer
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.ic_warning
import com.bainbridgetrails.resources.me_history
import com.bainbridgetrails.resources.results_approx_warning
import com.bainbridgetrails.resources.results_newly_completed
import com.bainbridgetrails.resources.results_no_progress
import com.bainbridgetrails.resources.results_no_progress_hint
import com.bainbridgetrails.resources.results_trails_header
import com.bainbridgetrails.resources.results_unverified
import com.bainbridgetrails.resources.results_verified
import com.bainbridgetrails.resources.walk_map_a11y
import com.bainbridgetrails.resources.walk_map_track_a11y
import com.bainbridgetrails.resources.walk_no_track_note
import com.bainbridgetrails.resources.walk_not_found
import com.bainbridgetrails.resources.walk_stats
import com.bainbridgetrails.resources.walk_track_note
import com.bainbridgetrails.tracking.WalkRecord
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.ResultTrailRow
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SkeletonBlock
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.util.DpPadding

private val WalkMapHeight = 240.dp

/**
 * Walk detail (P1-6, ux.md §2 `WalkDetail` "summary only"): the walk's results, read-only, from this phone. Date,
 * start time · duration · distance, island before → after, trails with before → after (tap → trail detail), and a
 * mini map: the walk's own route while the raw session file still exists (30-day retention), else the trails it
 * added to outlined. Nothing here is synced (D3).
 */
@Composable
fun WalkDetailScreen(
    graph: AppGraph.Installed,
    settings: AppSettings,
    sessionId: String,
    onBack: () -> Unit,
    onTrail: (String) -> Unit,
) {
    val history by graph.walks.history.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (graph.walks.history.value == null) graph.walks.refresh() }
    val walk = history?.walks?.firstOrNull { it.sessionId == sessionId }
    val zone = remember { TimeZone.currentSystemDefault() }
    val title = walk?.let { ListFormat.walkDate(it.startedAt, nowMs(), zone) } ?: stringResource(Res.string.me_history)
    ListScreen(title, onBack = onBack) {
        when {
            history == null -> item(key = "loading") { SkeletonBlock(0.dp, WalkMapHeight, Modifier.padding(horizontal = BtTheme.spacing.screenH).fillMaxWidth(), shape = MaterialTheme.shapes.large) }
            walk == null -> item(key = "missing") { EmptyState(stringResource(Res.string.walk_not_found)) }
            else -> item(key = "walk") {
                DatasetGate { data -> WalkDetailContent(graph, settings, data, walk, zone, onTrail) }
            }
        }
    }
}

@Composable
private fun WalkDetailContent(
    graph: AppGraph.Installed,
    settings: AppSettings,
    data: TrailData,
    walk: WalkRecord,
    zone: TimeZone,
    onTrail: (String) -> Unit,
) {
    val names = remember(data) { data.dataset.trails.associate { it.id to it.name } }
    val sections = remember(walk, names) { WalkDetailLogic.sections(walk) { names[it] } }
    // The raw track, while the session file still exists. Null = still reading; empty = pruned (or nothing drawable).
    val track by produceState<List<List<List<Double>>>?>(null, walk.sessionId) {
        value = graph.rawTrack(walk.sessionId)?.let { WalkDetailLogic.trackPieces(it) }.orEmpty()
    }
    val touched = remember(walk, data) { walk.trails.flatMap { data.dataset.trailLines(it.trailId) } }
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    val m = Modifier.padding(horizontal = BtTheme.spacing.screenH)

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                Res.string.walk_stats,
                WalkDetailLogic.startTime(walk.startedAt, zone),
                ListFormat.walkDuration(walk.durationMs),
                Format.distance(walk.distanceM, settings.units),
            ),
            m.padding(bottom = BtTheme.spacing.md),
            style = BtTheme.type.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val pieces = track
        if (pieces == null) {
            SkeletonBlock(0.dp, WalkMapHeight, m.fillMaxWidth(), shape = MaterialTheme.shapes.large)
        } else {
            WalkMap(settings, pieces, touched, snapshot?.segmentBuckets.orEmpty(), m)
        }
        Text(
            stringResource(if (pieces.isNullOrEmpty()) LocalGoal.current.s(Res.string.walk_no_track_note) else Res.string.walk_track_note),
            m.padding(top = BtTheme.spacing.sm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(m.padding(top = BtTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IslandTile(walk.islandBefore, walk.islandAfter)
            if (!walk.verified) Banner(stringResource(Res.string.results_unverified), Res.drawable.ic_warning, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            if (walk.approximateLocation) Banner(stringResource(LocalGoal.current.s(Res.string.results_approx_warning)), Res.drawable.ic_warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            if (sections.isEmpty) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(LocalGoal.current.s(Res.string.results_no_progress)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(LocalGoal.current.s(Res.string.results_no_progress_hint)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val status = { f: Double -> data.progress.status(f) }
            if (sections.completed.isNotEmpty()) {
                SectionHeader(stringResource(Res.string.results_newly_completed))
                for (t in sections.completed) TrailResultLink(t, status, onTrail)
            }
            if (sections.rest.isNotEmpty()) {
                SectionHeader(stringResource(LocalGoal.current.s(Res.string.results_trails_header)))
                for (t in sections.rest) TrailResultLink(t, status, onTrail)
            }
            if (walk.verified) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BtIcon(BtIcons.Verified, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(Res.string.results_verified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(BtTheme.spacing.lg))
        }
    }
}

/** [ResultTrailRow] that opens the trail (statuses from the stored before/after fractions). */
@Composable
private fun TrailResultLink(t: WalkTrailRow, status: (Double) -> TrailStatus, onTrail: (String) -> Unit) {
    ResultTrailRow(
        t.name, t.before, t.after, status(t.before), status(t.after),
        Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { onTrail(t.trailId) },
    )
}

/**
 * Non-interactive map fitted to the walk: coverage status as on the Map tab, plus the route ([track], on-device only)
 * or, once the route is pruned, the [touched] trails outlined.
 */
@Composable
private fun WalkMap(
    settings: AppSettings,
    track: List<List<List<Double>>>,
    touched: List<List<List<Double>>>,
    buckets: Map<TrailStatus, List<String>>,
    modifier: Modifier = Modifier,
) {
    val style = settings.effectiveMapStyle(isSystemInDarkTheme())
    val controller = rememberTrailMapController()
    val bounds = remember(track, touched) { boundsOf(track.ifEmpty { touched }) }
    LaunchedEffect(controller.mapState, bounds) {
        val map = controller.mapState ?: return@LaunchedEffect
        val b = bounds ?: return@LaunchedEffect
        map.fitTo(b, DpPadding(left = 32.dp, top = 32.dp, right = 32.dp, bottom = 32.dp), animate = false)
    }
    val a11y = stringResource(if (track.isNotEmpty()) Res.string.walk_map_track_a11y else Res.string.walk_map_a11y)
    Box(
        modifier.fillMaxWidth().height(WalkMapHeight).clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clearAndSetSemantics { contentDescription = a11y },
    ) {
        TrailMap(
            style,
            Modifier.fillMaxSize(),
            controller = controller,
            interactions = remember { MapInteractions(MapInteractions.None) {} },
            attribution = AttributionMode.InfoOnly,
            showScaleBar = false,
        ) {
            StatusLayers(buckets, style, highlight = if (track.isEmpty()) touched else emptyList(), reducedMotion = true)
            if (track.isNotEmpty()) WalkTrackLayer(track)
        }
    }
}
