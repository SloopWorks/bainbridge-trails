package com.bainbridgetrails.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.coverage.ProgressSnapshot
import com.bainbridgetrails.map.countedLengthMi
import com.bainbridgetrails.map.formatTrailLength
import com.bainbridgetrails.map.road
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.roads_filter_type
import com.bainbridgetrails.resources.roads_group_collapse_a11y
import com.bainbridgetrails.resources.roads_group_expand_a11y
import com.bainbridgetrails.resources.roads_row_partly_public
import com.bainbridgetrails.resources.roads_sort_grouped
import com.bainbridgetrails.resources.trails_banner_dismiss
import com.bainbridgetrails.resources.trails_clear_filters
import com.bainbridgetrails.resources.trails_clear_search
import com.bainbridgetrails.resources.trails_count
import com.bainbridgetrails.resources.trails_count_complete
import com.bainbridgetrails.resources.trails_empty_body
import com.bainbridgetrails.resources.trails_empty_search_title
import com.bainbridgetrails.resources.trails_empty_title
import com.bainbridgetrails.resources.trails_first_walk_banner
import com.bainbridgetrails.resources.trails_search_hint
import com.bainbridgetrails.resources.trails_sort_chip
import com.bainbridgetrails.resources.trails_sort_length
import com.bainbridgetrails.resources.trails_sort_progress
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.CategoryHeader
import com.bainbridgetrails.ui.components.DropdownChip
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.NotCountedGlyph
import com.bainbridgetrails.ui.components.SearchField
import com.bainbridgetrails.ui.components.SkeletonTrailRow
import com.bainbridgetrails.ui.components.StatusFilterChip
import com.bainbridgetrails.ui.components.TrailRow
import com.bainbridgetrails.ui.components.fillStaggerMs
import com.bainbridgetrails.ui.components.rememberRoadTexts
import com.bainbridgetrails.ui.components.roadGroupLabel
import com.bainbridgetrails.ui.components.roadSurfaceLabel
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Roads-list search/filters/sort and the Not counted group's open state, kept for the process (as trails, F3-6). */
object RoadsListState {
    var query by mutableStateOf(RoadListQuery())
    var notCountedOpen by mutableStateOf(false)
    var bannerDismissed by mutableStateOf(false)
}

/** Builds the list rows from the dataset rows + snapshot (counted length; partly public flag; surface). */
internal fun roadListItems(data: TrailData, snapshot: ProgressSnapshot): List<RoadListItem> {
    val metas = data.dataset.trails.associateBy { it.id }
    return snapshot.trails.mapNotNull { p ->
        val meta = metas[p.trailId] ?: return@mapNotNull null
        val road = meta.road() ?: return@mapNotNull null
        val countedMi = data.dataset.countedLengthMi(meta.id)
        RoadListItem(
            id = meta.id, name = meta.name, category = road.category, counted = road.counted, reason = road.notCountedReason,
            fraction = p.fraction, status = p.status,
            lengthMi = if (road.counted) countedMi else meta.computedLengthMi,
            partlyPublic = road.counted && meta.computedLengthMi - countedMi > 0.01,
            surface = road.surface,
        )
    }
}

@Composable
private fun RoadSort.labelText(): String = when (this) {
    RoadSort.GROUPED -> stringResource(Res.string.roads_sort_grouped)
    RoadSort.PROGRESS -> stringResource(Res.string.trails_sort_progress)
    RoadSort.SHORTEST -> stringResource(LocalGoal.current.s(Res.string.trails_sort_length))
}

/**
 * Roads tab content (docs/roads/ux.md §4, product.md §7, RD-10): search, Type ▾ (multi-select groups), status chips,
 * Sort ▾, a live count line, then sticky category groups A–Z — Main roads, Collectors, Residential streets — and a
 * collapsed "Not counted" group last. A search or a ranking sort shows one flat list. 64dp rows with abbreviated
 * names (full names for screen readers).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RoadsContent(items: List<RoadListItem>?, settings: AppSettings, onRoad: (String) -> Unit) {
    val state = RoadsListState
    val goal = LocalGoal.current
    val query = state.query
    var applied by remember { mutableStateOf(query.search) }
    LaunchedEffect(query.search) {
        if (query.search.isEmpty()) applied = "" else { delay(150); applied = query.search }
    }
    val effective = query.copy(search = applied)
    val sections = remember(items, effective) { items?.let { RoadListLogic.sections(it, effective) } }
    val summary = remember(items, effective) { items?.let { RoadListLogic.summary(it, effective) } }
    val texts = rememberRoadTexts()
    var typeMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = BtTheme.spacing.contentMaxWidth).fillMaxSize()) {
            SearchField(
                query.search,
                { state.query = state.query.copy(search = it) },
                stringResource(goal.s(Res.string.trails_search_hint)),
                Modifier.padding(horizontal = BtTheme.spacing.screenH),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = BtTheme.spacing.sm).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    val typeLabel = when (query.groups.size) {
                        0 -> stringResource(Res.string.roads_filter_type)
                        1 -> roadGroupLabel(query.groups.first())
                        else -> stringResource(Res.string.roads_filter_type) + " · ${query.groups.size}"
                    }
                    DropdownChip(typeLabel, { typeMenu = true }, active = query.groups.isNotEmpty())
                    DropdownMenu(typeMenu, onDismissRequest = { typeMenu = false }) {
                        for (g in RoadGroup.entries) {
                            val on = g in query.groups
                            DropdownMenuItem(
                                text = { Text(roadGroupLabel(g)) },
                                onClick = { state.query = state.query.copy(groups = if (on) state.query.groups - g else state.query.groups + g) },
                                trailingIcon = { if (on) BtIcon(BtIcons.Check, null, size = 20.dp, tint = MaterialTheme.colorScheme.primary) },
                            )
                        }
                    }
                }
                for (s in TrailStatus.entries) StatusFilterChip(s, s in query.statuses, {
                    state.query = state.query.copy(statuses = state.query.statuses.let { if (s in it) it - s else it + s })
                })
                Box {
                    DropdownChip(stringResource(Res.string.trails_sort_chip, query.sort.labelText()), { sortMenu = true }, active = query.sort != RoadSort.GROUPED)
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        for (s in RoadSort.entries) {
                            DropdownMenuItem(
                                text = { Text(s.labelText()) },
                                onClick = { state.query = state.query.copy(sort = s); sortMenu = false },
                                trailingIcon = { if (s == query.sort) BtIcon(BtIcons.Check, null, size = 20.dp, tint = MaterialTheme.colorScheme.primary) },
                            )
                        }
                    }
                }
            }
            if (items == null || sections == null || summary == null) {
                Column(Modifier.padding(top = BtTheme.spacing.lg)) { repeat(10) { SkeletonTrailRow() } }
                return@Column
            }
            val count = stringResource(goal.s(Res.string.trails_count), summary.shown, summary.total)
            Text(
                if (summary.complete > 0) stringResource(Res.string.trails_count_complete, count, summary.complete) else count,
                Modifier.padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.md).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val listState = rememberLazyListState()
            // A new filter, sort or search starts at the top (a deep scroll into another list is meaningless).
            var lastQuery by remember { mutableStateOf(effective) }
            LaunchedEffect(effective) {
                if (effective != lastQuery) listState.scrollToItem(0)
                lastQuery = effective
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (!state.bannerDismissed && RoadListLogic.noProgressYet(items) && sections.isNotEmpty()) {
                    item(key = "banner") { RoadsBanner { state.bannerDismissed = true } }
                }
                if (sections.isEmpty()) {
                    item(key = "empty") {
                        val q = query.search.trim()
                        if (query.hasSearch && !query.hasFilters) {
                            EmptyState(
                                stringResource(goal.s(Res.string.trails_empty_search_title), q),
                                body = stringResource(Res.string.trails_empty_body),
                                action = stringResource(Res.string.trails_clear_search) to { state.query = state.query.copy(search = "") },
                            )
                        } else {
                            EmptyState(
                                stringResource(goal.s(Res.string.trails_empty_title)),
                                body = stringResource(Res.string.trails_empty_body),
                                action = stringResource(Res.string.trails_clear_filters) to { state.query = state.query.withoutFilters().copy(search = "") },
                            )
                        }
                    }
                }
                var index = 0
                for (section in sections) {
                    val group = section.group
                    val collapsible = group == RoadGroup.NOT_COUNTED
                    // An explicit Type filter on Not counted opens it.
                    val open = !collapsible || state.notCountedOpen || RoadGroup.NOT_COUNTED in query.groups
                    if (group != null) {
                        stickyHeader(key = "h-${group.name}") {
                            if (collapsible) {
                                CategoryHeader(
                                    roadGroupLabel(group), section.done, section.total,
                                    expanded = open,
                                    onToggle = { state.notCountedOpen = !open },
                                    toggleLabel = stringResource(if (open) Res.string.roads_group_collapse_a11y else Res.string.roads_group_expand_a11y),
                                    showCount = false,
                                )
                            } else {
                                CategoryHeader(roadGroupLabel(group), section.done, section.total)
                            }
                        }
                    }
                    if (!open) continue
                    val start = index
                    items(section.items, key = { "r-" + it.id }) { r ->
                        val length = formatTrailLength(r.lengthMi, settings.units)
                        val reason = r.reason
                        val subtitle = buildList {
                            add(if (reason != null) texts.reasonShort(reason) else texts.category(r.category))
                            add(length)
                            if (r.partlyPublic) add(stringResource(Res.string.roads_row_partly_public))
                            if (r.surface != null && r.surface != "paved") roadSurfaceLabel(r.surface)?.let { add(it) }
                        }.joinToString(" · ")
                        TrailRow(
                            name = RoadListLogic.displayName(r.name),
                            subtitle = subtitle,
                            fraction = r.fraction,
                            status = r.status,
                            onClick = { onRoad(r.id) },
                            animationDelayMs = fillStaggerMs(start),
                            modifier = Modifier.animateItem(),
                            dense = true,
                            spokenName = r.name,
                            leading = reason?.let { rr -> { NotCountedGlyph(rr) } },
                            statusText = if (reason != null) texts.reasonShort(reason) else null,
                        )
                    }
                    index += section.items.size
                }
            }
        }
    }
}

@Composable
private fun RoadsBanner(onDismiss: () -> Unit) {
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
