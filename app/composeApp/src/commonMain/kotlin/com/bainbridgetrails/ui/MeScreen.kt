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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.bainbridgetrails.nav.DebugLaunch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.auth.AuthState
import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.me_edit_name
import com.bainbridgetrails.resources.me_hero_a11y
import com.bainbridgetrails.resources.me_hero_caption
import com.bainbridgetrails.resources.me_hero_caption_new
import com.bainbridgetrails.resources.me_history
import com.bainbridgetrails.resources.me_history_empty
import com.bainbridgetrails.resources.me_history_more
import com.bainbridgetrails.resources.me_history_open_a11y
import com.bainbridgetrails.resources.me_history_other_devices
import com.bainbridgetrails.resources.me_history_row_a11y
import com.bainbridgetrails.resources.me_history_trails
import com.bainbridgetrails.resources.me_history_unverified
import com.bainbridgetrails.resources.me_in_progress
import com.bainbridgetrails.resources.me_in_progress_count
import com.bainbridgetrails.resources.me_in_progress_empty
import com.bainbridgetrails.resources.me_leaderboard_as_of
import com.bainbridgetrails.resources.me_leaderboard_row
import com.bainbridgetrails.resources.me_leaderboard_row_signed_out
import com.bainbridgetrails.resources.me_leaderboard_row_unranked
import com.bainbridgetrails.resources.me_leaderboard_row_hidden
import com.bainbridgetrails.resources.me_leaderboard_see
import com.bainbridgetrails.resources.me_profile_a11y
import com.bainbridgetrails.resources.me_see_all
import com.bainbridgetrails.resources.me_settings_a11y
import com.bainbridgetrails.resources.me_signin
import com.bainbridgetrails.resources.me_signin_card_body
import com.bainbridgetrails.resources.me_signin_card_title
import com.bainbridgetrails.resources.me_stat_info_a11y
import com.bainbridgetrails.resources.me_stat_km_phone
import com.bainbridgetrails.resources.me_stat_miles_phone
import com.bainbridgetrails.resources.me_stat_trails
import com.bainbridgetrails.resources.me_stat_trails_value
import com.bainbridgetrails.resources.me_stat_walks
import com.bainbridgetrails.resources.me_title
import com.bainbridgetrails.resources.stat_island_info
import com.bainbridgetrails.resources.stat_miles_info
import com.bainbridgetrails.resources.stat_trails_info
import com.bainbridgetrails.resources.stat_walks_info
import com.bainbridgetrails.resources.me_stat_island
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.BtTopBar
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.IslandChart
import com.bainbridgetrails.ui.components.IslandGeometry
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SkeletonBlock
import com.bainbridgetrails.ui.components.StatTile
import com.bainbridgetrails.ui.components.StatTileRow
import com.bainbridgetrails.ui.components.TrailRow
import com.bainbridgetrails.ui.components.bySegment
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val HISTORY_PAGE = 20

/** Me tab (F7, ux.md §3.7): profile row, IslandChart hero, stat tiles, leaderboard card, in progress, walk history. */
@Composable
fun MeScreen(
    graph: AppGraph.Installed,
    settings: AppSettings,
    onTrail: (String) -> Unit,
    onLeaderboard: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    onWalk: (String) -> Unit,
    onMap: () -> Unit,
    onSeeAllInProgress: () -> Unit,
    modifier: Modifier = Modifier,
    /** Roads "By road type" row → the Roads list filtered to that group. */
    onRoadType: (RoadGroup) -> Unit = {},
) {
    val snapshot by graph.progress.snapshot.collectAsStateWithLifecycle()
    val history by graph.walks.history.collectAsStateWithLifecycle()
    val auth by graph.auth.state.collectAsStateWithLifecycle()
    val profile by graph.profile.profile.collectAsStateWithLifecycle()
    val board by graph.leaderboard.state.collectAsStateWithLifecycle()
    val sync by graph.sync.state.collectAsStateWithLifecycle()
    val signedIn = auth is AuthState.SignedIn
    LaunchedEffect(signedIn) { if (signedIn) graph.leaderboard.refresh() }
    var info by remember { mutableStateOf<StatInfo?>(null) }
    var milestonesOpen by remember { mutableStateOf(false) }
    val goal = LocalGoal.current

    Column(modifier.fillMaxSize()) {
        BtTopBar(stringResource(Res.string.me_title)) {
            IconButton(onClick = onSettings) { BtIcon(BtIcons.Settings, stringResource(Res.string.me_settings_a11y)) }
        }
        DatasetGate(Modifier.weight(1f)) { data ->
            val geometry by produceState<IslandGeometry?>(null, data) { value = IslandGeometry.forDataset(data.dataset) }
            val s = snapshot
            val walks = history?.walks.orEmpty()
            val stats = MeLogic.stats(s?.islandFraction ?: 0.0, s?.trailsCompleted ?: 0, data.progress.countedTrailCount, walks)
            val inProgress = remember(s) { MeLogic.inProgress(s?.trails.orEmpty()) }
            val rows = remember(walks, s) { MeLogic.walkRows(walks) { id -> s?.trail(id)?.name } }
            val segStatus = remember(s) { s?.segmentBuckets.orEmpty().bySegment() }
            var shownWalks by remember { mutableIntStateOf(HISTORY_PAGE) }
            val zone = remember { TimeZone.currentSystemDefault() }
            val now = remember(walks) { nowMs() }
            val m = Modifier.padding(horizontal = BtTheme.spacing.screenH)
            val roadsNumbers = remember(s, data) { if (goal.isRoads && s != null) RoadsMeNumbers.of(data, s) else null }
            val roadsLadder = remember(roadsNumbers, settings.units) {
                roadsNumbers?.let { Milestones.ladder(it.coveredM, settings.units, it.completed, it.counted, it.completedByCategory, it.countedByCategory) }
            }
            if (milestonesOpen && roadsLadder != null) MilestonesSheet(roadsLadder, settings.units) { milestonesOpen = false }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val listState = rememberLazyListState()
                LaunchedEffect(Unit) { if (DebugLaunch.scroll > 0) listState.scrollToItem(DebugLaunch.scroll) }
                LazyColumn(Modifier.fillMaxSize().widthIn(max = BtTheme.spacing.contentMaxWidth), state = listState) {
                    item(key = "profile") {
                        if (signedIn) {
                            ProfileHeaderRow(profile?.displayName.orEmpty(), onProfile, m)
                        } else {
                            SignInCard(onProfile, m.padding(bottom = BtTheme.spacing.md))
                        }
                    }
                    item(key = "hero") {
                        val g = geometry
                        if (s == null || g == null) {
                            SkeletonBlock(0.dp, 280.dp, m.fillMaxWidth(), shape = MaterialTheme.shapes.large)
                        } else if (roadsNumbers != null) {
                            RoadsHero(g, segStatus, roadsNumbers, settings.units, onMap, m)
                        } else {
                            IslandHero(g, segStatus, stats, onMap, m)
                        }
                    }
                    item(key = "tiles") {
                        Spacer(Modifier.height(BtTheme.spacing.sm))
                        StatTiles(stats, settings.units, onInfo = { info = it }, modifier = m)
                    }
                    if (roadsNumbers != null) {
                        // Roads retention (docs/roads/product.md §5.2, ux.md §5.3): next milestone, strip, by type.
                        item(key = "next-milestone") { NextMilestoneLine(roadsNumbers, settings.units, m.padding(top = BtTheme.spacing.md)) }
                        item(key = "milestones") { MilestoneStrip(Milestones.strip(roadsLadder!!), settings.units, onAll = { milestonesOpen = true }, Modifier.padding(top = BtTheme.spacing.sm)) }
                        item(key = "by-type") { ByTypeRows(roadsNumbers, settings.units, onRoadType, m.padding(top = BtTheme.spacing.sm)) }
                    }
                    item(key = "board") {
                        Spacer(Modifier.height(BtTheme.spacing.sm))
                        val you = board.board?.you
                        val subtitle = when {
                            !signedIn -> stringResource(Res.string.me_leaderboard_see)
                            profile?.me?.hiddenFromBoard == true -> stringResource(Res.string.me_leaderboard_row_hidden)
                            you != null -> stringResource(Res.string.me_leaderboard_row, you.rank, board.board?.totalRanked ?: you.rank) +
                                (if (board.error != null && board.updatedAt != null) " · " + stringResource(Res.string.me_leaderboard_as_of, ageText(board.updatedAt!!)) else "")
                            board.board != null -> stringResource(Res.string.me_leaderboard_row_unranked)
                            else -> null
                        }
                        CardRow(BtIcons.Trophy, stringResource(Res.string.me_leaderboard_row_signed_out), subtitle, onLeaderboard, m)
                        if (signedIn) SyncStatusLine(sync, onRetry = { graph.sync.syncNow() }, modifier = m.padding(top = BtTheme.spacing.sm))
                    }
                    item(key = "progress-h") {
                        SectionHeader(
                            if (inProgress.isEmpty()) stringResource(Res.string.me_in_progress) else stringResource(Res.string.me_in_progress_count, inProgress.size),
                            m.padding(top = BtTheme.spacing.md),
                            action = if (inProgress.size > 5) stringResource(Res.string.me_see_all) to onSeeAllInProgress else null,
                        )
                    }
                    if (inProgress.isEmpty()) {
                        item(key = "progress-empty") { EmptyState(stringResource(goal.s(Res.string.me_in_progress_empty)), compact = true) }
                    }
                    items(inProgress.take(5), key = { "p-" + it.trailId }) { t ->
                        TrailRow(t.name, trailLengthText(t.lengthMi, settings.units), t.fraction, t.status, onClick = { onTrail(t.trailId) })
                    }
                    item(key = "history-h") { SectionHeader(stringResource(Res.string.me_history), m.padding(top = BtTheme.spacing.md)) }
                    if (rows.isEmpty()) {
                        item(key = "history-empty") { EmptyState(stringResource(Res.string.me_history_empty), compact = true) }
                    }
                    items(rows.take(shownWalks), key = { "w-" + it.sessionId }) { w ->
                        WalkRow(w, ListFormat.walkDate(w.startedAt, now, zone), settings.units, onOpen = { onWalk(w.sessionId) })
                    }
                    if (rows.size > shownWalks) {
                        item(key = "history-more") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { shownWalks += HISTORY_PAGE }) { Text(stringResource(Res.string.me_history_more)) }
                            }
                        }
                    }
                    if (signedIn || (stats.islandFraction > 0 && rows.isEmpty())) {
                        item(key = "history-note") {
                            Text(
                                stringResource(Res.string.me_history_other_devices),
                                m.padding(top = BtTheme.spacing.sm),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(BtTheme.spacing.xxl)) }
                }
            }
        }
    }
    info?.let { StatInfoSheet(it) { info = null } }
}

private enum class StatInfo { ISLAND, TRAILS, MILES, WALKS }

@Composable
private fun ProfileHeaderRow(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a11y = stringResource(Res.string.me_profile_a11y)
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "$name. $a11y" }
            .padding(vertical = BtTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, PillShape), contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull { it.isLetter() }?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.me_edit_name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        BtIcon(BtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SignInCard(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large)
            .padding(start = BtTheme.spacing.lg, top = BtTheme.spacing.lg, end = BtTheme.spacing.sm, bottom = BtTheme.spacing.xs),
    ) {
        Text(stringResource(Res.string.me_signin_card_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            stringResource(Res.string.me_signin_card_body),
            Modifier.padding(top = BtTheme.spacing.xs, end = BtTheme.spacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSignIn) { Text(stringResource(Res.string.me_signin)) }
        }
    }
}

/** Hero card: the island in miniature on chart water, big island % bottom-left (ux.md §3.7). Tap → Map. */
@Composable
private fun IslandHero(geometry: IslandGeometry, segStatus: Map<String, com.bainbridgetrails.coverage.TrailStatus>, stats: MeStats, onMap: () -> Unit, modifier: Modifier = Modifier) {
    // Chart water: a wash of the secondary (Sound-teal) container over the page, so the paper-colored land reads.
    // Dark: night water below lichen-dark land, as on the Dark map (water #0E1F27 < land #1D2925).
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val water = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.secondaryContainer, if (dark) 0.3f else 0.55f)
    val land = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
    val pct = Format.percent(stats.islandFraction)
    val a11y = stringResource(Res.string.me_hero_a11y, pct, stats.trailsCompleted, stats.trailsTotal)
    // statHero is 64sp; cap growth at 1.3× so the number never truncates (ux.md §5.3).
    val scale = LocalDensity.current.fontScale
    val heroStyle = BtTheme.type.statHero.let { if (scale > 1.3f) it.copy(fontSize = (64 * 1.3f / scale).sp, lineHeight = (68 * 1.3f / scale).sp) else it }
    Box(
        modifier.fillMaxWidth().height(280.dp).background(water, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onMap)
            .semantics(mergeDescendants = true) { role = Role.Button },
    ) {
        IslandChart(
            geometry, segStatus, water, a11y,
            Modifier.fillMaxHeight().fillMaxWidth(0.62f).align(Alignment.CenterEnd).padding(vertical = 8.dp),
            land = land,
        )
        Column(Modifier.align(Alignment.BottomStart).padding(BtTheme.spacing.lg).fillMaxWidth(0.62f).clearAndSetSemantics {}) {
            Text(stringResource(Res.string.me_stat_island).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pct, style = heroStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
            Text(
                stringResource(if (stats.isNew) Res.string.me_hero_caption_new else Res.string.me_hero_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatTiles(stats: MeStats, units: Units, onInfo: (StatInfo) -> Unit, modifier: Modifier = Modifier) {
    val miles = Format.distance(stats.trailMetresWalked, units).substringBefore(' ')
    val milesLabel = stringResource(LocalGoal.current.s(if (units == Units.MILES) Res.string.me_stat_miles_phone else Res.string.me_stat_km_phone))
    val trailsValue = stringResource(Res.string.me_stat_trails_value, stats.trailsCompleted, stats.trailsTotal)
    val tiles = listOf(
        Triple(trailsValue, stringResource(LocalGoal.current.s(Res.string.me_stat_trails)), StatInfo.TRAILS),
        Triple(miles, milesLabel, StatInfo.MILES),
        Triple("${stats.walkCount}", stringResource(Res.string.me_stat_walks), StatInfo.WALKS),
    )
    val big = LocalDensity.current.fontScale >= 1.5f
    if (big) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            tiles.forEach { (v, l, i) -> InfoTile(v, l, i, onInfo, Modifier.fillMaxWidth()) }
        }
    } else {
        StatTileRow(modifier) { tiles.forEach { (v, l, i) -> InfoTile(v, l, i, onInfo, Modifier.weight(1f)) } }
    }
}

@Composable
private fun InfoTile(value: String, label: String, info: StatInfo, onInfo: (StatInfo) -> Unit, modifier: Modifier) {
    val a11y = stringResource(Res.string.me_stat_info_a11y, "$value $label")
    // StatTile layout (ux.md §4) with room for "9 / 114": the denominator after a slash is set smaller, and labels
    // may take three lines ("Trail miles walked on this phone").
    val main = value.substringBefore(" / ")
    val rest = value.substringAfter(" / ", "").let { if (it.isEmpty()) "" else " / $it" }
    Column(
        modifier.heightIn(min = 104.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .clickable { onInfo(info) }
            .clearAndSetSemantics { role = Role.Button; contentDescription = a11y }
            .padding(start = BtTheme.spacing.md, end = BtTheme.spacing.sm, top = BtTheme.spacing.sm, bottom = BtTheme.spacing.md),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BtIcon(BtIcons.Info, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(main)
                if (rest.isNotEmpty()) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant))
                    append(rest)
                    pop()
                }
            },
            style = BtTheme.type.statLarge,
            maxLines = 1,
            softWrap = false,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 3)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatInfoSheet(info: StatInfo, onDismiss: () -> Unit) {
    val completePct = (CoverageParams().completionThreshold * 100).roundToInt()
    val (title, body) = when (info) {
        StatInfo.ISLAND -> stringResource(Res.string.me_stat_island) to stringResource(Res.string.stat_island_info)
        StatInfo.TRAILS -> stringResource(LocalGoal.current.s(Res.string.me_stat_trails)) to stringResource(LocalGoal.current.s(Res.string.stat_trails_info), completePct)
        StatInfo.MILES -> stringResource(LocalGoal.current.s(Res.string.me_stat_miles_phone)) to stringResource(LocalGoal.current.s(Res.string.stat_miles_info))
        StatInfo.WALKS -> stringResource(Res.string.me_stat_walks) to stringResource(Res.string.stat_walks_info)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.xl).padding(bottom = BtTheme.spacing.xxl)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
        ) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Card-like navigation row on `surfaceContainerLow` (Me's leaderboard entry). */
@Composable
private fun CardRow(icon: org.jetbrains.compose.resources.DrawableResource, title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(horizontal = BtTheme.spacing.lg, vertical = BtTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, PillShape), contentAlignment = Alignment.Center) {
            BtIcon(icon, null, size = 22.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BtIcon(BtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Walk history row: "Sat, Oct 18" · duration · distance, then trails touched; Unverified tag when so. */
@Composable
private fun WalkRow(w: WalkRowData, date: String, units: Units, onOpen: () -> Unit) {
    val trails = pluralStringResource(Res.plurals.me_history_trails, w.trailCount, w.trailCount) +
        (if (w.trailNames.isEmpty()) "" else " · " + w.trailNames.joinToString(", ") + if (w.trailCount > w.trailNames.size) "…" else "")
    val duration = ListFormat.walkDuration(w.durationMs)
    val distance = Format.distance(w.distanceM, units)
    val unverified = stringResource(Res.string.me_history_unverified)
    val open = stringResource(Res.string.me_history_open_a11y)
    val a11y = stringResource(Res.string.me_history_row_a11y, date, Format.durationSpoken(w.durationMs), distance, trails) + if (!w.verified) ", $unverified" else ""
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = open, role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics { contentDescription = a11y; role = Role.Button; onClick(open) { onOpen(); true } }
            .padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape), contentAlignment = Alignment.Center) {
            BtIcon(BtIcons.DirectionsWalk, null, size = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                Text(date, style = MaterialTheme.typography.titleMedium)
                if (!w.verified) {
                    Text(
                        unverified,
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape).padding(horizontal = 8.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("$duration · $distance", style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(trails, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
