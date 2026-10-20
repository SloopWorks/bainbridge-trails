package com.bainbridgetrails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.trails_banner_dismiss
import com.bainbridgetrails.resources.trails_board_a11y
import com.bainbridgetrails.resources.trails_clear_filters
import com.bainbridgetrails.resources.trails_clear_search
import com.bainbridgetrails.resources.trails_count
import com.bainbridgetrails.resources.trails_count_complete
import com.bainbridgetrails.resources.trails_empty_body
import com.bainbridgetrails.resources.trails_empty_search_title
import com.bainbridgetrails.resources.trails_empty_title
import com.bainbridgetrails.resources.trails_filter_park
import com.bainbridgetrails.resources.trails_filter_park_all
import com.bainbridgetrails.resources.trails_filter_park_none
import com.bainbridgetrails.resources.trails_first_walk_banner
import com.bainbridgetrails.resources.trails_park_sheet_title
import com.bainbridgetrails.resources.trails_search_hint
import com.bainbridgetrails.resources.trails_sort_chip
import com.bainbridgetrails.resources.trails_sort_length
import com.bainbridgetrails.resources.trails_sort_name
import com.bainbridgetrails.resources.trails_sort_progress
import com.bainbridgetrails.resources.trails_title
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.BtTopBar
import com.bainbridgetrails.ui.components.DropdownChip
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.FilterChipRow
import com.bainbridgetrails.ui.components.SearchField
import com.bainbridgetrails.ui.components.SettingRadioRow
import com.bainbridgetrails.ui.components.SkeletonTrailRow
import com.bainbridgetrails.ui.components.TrailRow
import com.bainbridgetrails.ui.components.fillStaggerMs
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Trails-list search/filters/sort, kept for the process (F3-6: survives tab switches, not restarts). Me's "See all"
 * writes it before switching tabs.
 */
object TrailsListState {
    var query by mutableStateOf(TrailListQuery())
    var bannerDismissed by mutableStateOf(false)
}

private val TrailSort.label: StringResource
    get() = when (this) {
        TrailSort.NAME -> Res.string.trails_sort_name
        TrailSort.PROGRESS -> Res.string.trails_sort_progress
        TrailSort.LENGTH -> Res.string.trails_sort_length
    }

/** Trails tab (F3, ux.md §3.3): search, status chips, park + sort menus, count line, trail rows. */
@Composable
fun TrailsScreen(
    graph: AppGraph.Installed,
    settings: AppSettings,
    onTrail: (String) -> Unit,
    onLeaderboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    val goal = LocalGoal.current
    Column(modifier.fillMaxSize()) {
        BtTopBar(stringResource(goal.s(Res.string.trails_title))) {
            IconButton(onClick = onLeaderboard) { BtIcon(BtIcons.Trophy, stringResource(Res.string.trails_board_a11y)) }
        }
        DatasetGate(Modifier.weight(1f)) { data ->
            if (goal.isRoads) {
                // Roads (docs/roads/ux.md §4): grouped by type, abbreviation-tolerant search, Not counted group.
                val roads = remember(snapshot, data) { snapshot?.let { roadListItems(data, it) } }
                RoadsContent(roads, settings, onTrail)
                return@DatasetGate
            }
            val parkById = remember(data) { data.dataset.trails.associate { it.id to it.park } }
            val items = remember(snapshot, parkById) {
                snapshot?.trails?.map { TrailListItem(it.trailId, it.name, parkById[it.trailId], it.fraction, it.status, it.lengthMi) }
            }
            TrailsContent(items, settings, onTrail)
        }
    }
}

@Composable
private fun TrailsContent(items: List<TrailListItem>?, settings: AppSettings, onTrail: (String) -> Unit) {
    val state = TrailsListState
    val query = state.query
    // Debounced search (150 ms); the field itself updates immediately.
    var applied by remember { mutableStateOf(query.search) }
    LaunchedEffect(query.search) {
        if (query.search.isEmpty()) applied = "" else { delay(150); applied = query.search }
    }
    val effective = query.copy(search = applied)
    val shown = remember(items, effective) { items?.let { TrailListLogic.apply(it, effective) } }
    val parks = remember(items) { items?.let { TrailListLogic.parks(it) }.orEmpty() }
    var parkSheet by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = BtTheme.spacing.contentMaxWidth).fillMaxSize()) {
            SearchField(
                query.search,
                { state.query = state.query.copy(search = it) },
                stringResource(LocalGoal.current.s(Res.string.trails_search_hint)),
                Modifier.padding(horizontal = BtTheme.spacing.screenH),
            )
            FilterChipRow(
                selected = query.statuses,
                onToggle = { s -> state.query = state.query.copy(statuses = state.query.statuses.let { if (s in it) it - s else it + s }) },
                modifier = Modifier.padding(top = BtTheme.spacing.sm),
            ) {
                val parkLabel = when (val p = query.park) {
                    ParkFilter.All -> stringResource(Res.string.trails_filter_park)
                    ParkFilter.NoPark -> stringResource(Res.string.trails_filter_park_none)
                    is ParkFilter.Named -> p.park
                }
                DropdownChip(parkLabel, { parkSheet = true }, active = query.park != ParkFilter.All)
                Box {
                    DropdownChip(stringResource(Res.string.trails_sort_chip, stringResource(LocalGoal.current.s(query.sort.label))), { sortMenu = true }, active = query.sort != TrailSort.NAME)
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        for (s in TrailSort.entries) {
                            DropdownMenuItem(
                                text = { Text(stringResource(LocalGoal.current.s(s.label))) },
                                onClick = { state.query = state.query.copy(sort = s); sortMenu = false },
                                trailingIcon = { if (s == query.sort) BtIcon(BtIcons.Check, null, size = 20.dp, tint = MaterialTheme.colorScheme.primary) },
                            )
                        }
                    }
                }
            }
            if (items == null || shown == null) {
                Column(Modifier.padding(top = BtTheme.spacing.lg)) { repeat(8) { SkeletonTrailRow() } }
                return@Column
            }
            val complete = items.count { it.status == TrailStatus.COMPLETE }
            val count = stringResource(LocalGoal.current.s(Res.string.trails_count), shown.size, items.size)
            Text(
                if (complete > 0) stringResource(Res.string.trails_count_complete, count, complete) else count,
                Modifier.padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.md).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (!state.bannerDismissed && TrailListLogic.noProgressYet(items) && shown.isNotEmpty()) {
                    item(key = "banner") {
                        FirstWalkBanner { state.bannerDismissed = true }
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        val q = query.search.trim()
                        if (query.hasSearch && !query.hasFilters) {
                            EmptyState(
                                stringResource(LocalGoal.current.s(Res.string.trails_empty_search_title), q),
                                body = stringResource(Res.string.trails_empty_body),
                                action = stringResource(Res.string.trails_clear_search) to { state.query = state.query.copy(search = "") },
                            )
                        } else {
                            EmptyState(
                                stringResource(LocalGoal.current.s(Res.string.trails_empty_title)),
                                body = stringResource(Res.string.trails_empty_body),
                                action = stringResource(Res.string.trails_clear_filters) to { state.query = state.query.withoutFilters().copy(search = "") },
                            )
                        }
                    }
                }
                itemsIndexed(shown, key = { _, t -> t.trailId }) { i, t ->
                    val length = trailLengthText(t.lengthMi, settings.units)
                    TrailRow(
                        name = t.name,
                        subtitle = if (t.park != null) "${t.park} · $length" else length,
                        fraction = t.fraction,
                        status = t.status,
                        onClick = { onTrail(t.trailId) },
                        animationDelayMs = fillStaggerMs(i),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    if (parkSheet) {
        ParkSheet(parks, query.park, onPick = { state.query = state.query.copy(park = it); parkSheet = false }, onDismiss = { parkSheet = false })
    }
}

@Composable
private fun FirstWalkBanner(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.xs)
            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
            .padding(start = BtTheme.spacing.lg, top = BtTheme.spacing.md, bottom = BtTheme.spacing.xs, end = BtTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BtIcon(BtIcons.DirectionsWalk, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Column(Modifier.weight(1f).padding(start = BtTheme.spacing.md)) {
            Text(stringResource(LocalGoal.current.s(Res.string.trails_first_walk_banner)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.trails_banner_dismiss)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParkSheet(parks: List<String>, current: ParkFilter, onPick: (ParkFilter) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(
            stringResource(Res.string.trails_park_sheet_title),
            Modifier.padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        val options = listOf(ParkFilter.All) + parks.map { ParkFilter.Named(it) } + ParkFilter.NoPark
        LazyColumn(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
            items(options, key = { it.toString() }) { p ->
                val label = when (p) {
                    ParkFilter.All -> stringResource(Res.string.trails_filter_park_all)
                    ParkFilter.NoPark -> stringResource(Res.string.trails_filter_park_none)
                    is ParkFilter.Named -> p.park
                }
                SettingRadioRow(label, selected = p == current, onSelect = { onPick(p) })
            }
        }
    }
}
