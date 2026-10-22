package com.bainbridgetrails.ui.play.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.net.getOrNull
import com.bainbridgetrails.play.PlayRepository
import com.bainbridgetrails.play.TrailChallengeStatus
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.schedule.Occurrence
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_challenge_island
import com.bainbridgetrails.resources.play_challenge_solo
import com.bainbridgetrails.resources.play_challenges_empty
import com.bainbridgetrails.resources.play_challenges_ended
import com.bainbridgetrails.resources.play_challenges_title
import com.bainbridgetrails.resources.play_content_gone
import com.bainbridgetrails.resources.play_goal_item
import com.bainbridgetrails.resources.play_goal_items
import com.bainbridgetrails.resources.play_log_empty
import com.bainbridgetrails.resources.play_log_more
import com.bainbridgetrails.resources.play_log_next_rank
import com.bainbridgetrails.resources.play_log_title
import com.bainbridgetrails.resources.play_log_total
import com.bainbridgetrails.resources.play_log_walk_group
import com.bainbridgetrails.resources.play_log_week
import com.bainbridgetrails.resources.play_log_week_close
import com.bainbridgetrails.resources.play_rank_earned
import com.bainbridgetrails.resources.play_rank_from
import com.bainbridgetrails.resources.play_rank_row_a11y
import com.bainbridgetrails.resources.play_ranks_current
import com.bainbridgetrails.resources.play_ranks_next
import com.bainbridgetrails.resources.play_ranks_title
import com.bainbridgetrails.resources.play_riddle_stars_a11y
import com.bainbridgetrails.resources.play_screen_off
import com.bainbridgetrails.resources.play_sources
import com.bainbridgetrails.resources.play_stories_count
import com.bainbridgetrails.resources.play_stories_title
import com.bainbridgetrails.resources.play_tag_history
import com.bainbridgetrails.resources.play_tag_remembrance
import com.bainbridgetrails.resources.play_tag_tall_tale
import com.bainbridgetrails.resources.play_island_time
import com.bainbridgetrails.resources.play_legend_treasure
import com.bainbridgetrails.resources.play_whats_on_coming
import com.bainbridgetrails.resources.play_whats_on_daylight
import com.bainbridgetrails.resources.play_whats_on_empty
import com.bainbridgetrails.resources.play_whats_on_ferry
import com.bainbridgetrails.resources.play_whats_on_title
import com.bainbridgetrails.resources.play_whats_on_today
import com.bainbridgetrails.resources.play_whats_on_treasures
import com.bainbridgetrails.resources.play_whats_on_week_of
import com.bainbridgetrails.ui.ListScreen
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.LoadingState
import com.bainbridgetrails.ui.components.PlayBar
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SkeletonBlock
import com.bainbridgetrails.ui.play.components.ChallengeCard
import com.bainbridgetrails.ui.play.components.LogbookRow
import com.bainbridgetrails.ui.play.components.PlayAvailability
import com.bainbridgetrails.ui.play.components.PlayBarWithLabel
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.play.components.RankBadge
import com.bainbridgetrails.ui.play.components.WhatsOnRow
import com.bainbridgetrails.ui.play.components.flavourRes
import com.bainbridgetrails.ui.play.components.rememberPlayAvailability
import com.bainbridgetrails.ui.play.components.titleRes
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A Play screen's body once the repository runs ([rememberPlayAvailability]: the settings StateFlow + the Play gate).
 * Starting: [loading]. Off: when Play turns off while the screen is open it leaves ([onBack]); entered with Play off
 * (a deep link) it shows the `play_screen_off` EmptyState. Nothing here creates Play (PD-1).
 */
@Composable
private fun WithPlay(
    graph: AppGraph.Installed,
    title: String,
    onBack: () -> Unit,
    loading: LazyListScope.() -> Unit = { item(key = "loading") { LoadingState() } },
    content: @Composable (PlayRepository) -> Unit,
) {
    val availability by rememberPlayAvailability(graph)
    val a = availability
    val off = a is PlayAvailability.Off
    var seenOn by remember { mutableStateOf(false) }
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(off) {
        if (!off) seenOn = true else if (seenOn) back()
    }
    when (a) {
        is PlayAvailability.Ready -> content(a.play)
        PlayAvailability.Starting -> ListScreen(title, onBack = onBack, content = loading)
        PlayAvailability.Off -> ListScreen(title, onBack = onBack) { item(key = "off") { EmptyState(stringResource(Res.string.play_screen_off)) } }
    }
}

/**
 * The trails `climb` / `parks` challenge statuses of [agenda]'s challenge occurrences ([ChallengesLogic.trailOccurrences]
 * → `PlayRepository.trailChallenges`, plan D5), re-read when the agenda's challenges or the [logbook] change; empty on
 * roads; null until read for the current occurrences (screens show their loading state, so a hidden card never
 * flashes; a Logbook change keeps the previous statuses while it re-reads).
 */
@Composable
fun rememberTrailChallenges(play: PlayRepository, agenda: PlayAgenda?, logbook: Logbook?): Map<String, TrailChallengeStatus>? {
    val occurrences = remember(agenda) { agenda?.let(ChallengesLogic::trailOccurrences).orEmpty() }
    val read by produceState<Pair<List<Occurrence>, Map<String, TrailChallengeStatus>>?>(null, play, occurrences, logbook) {
        value = occurrences to play.trailChallenges(occurrences)
    }
    return read?.takeIf { it.first == occurrences }?.second
}

/** Screen-margin padding (`BtTheme.spacing.screenH`). */
private val screenPad: Modifier
    @Composable get() = Modifier.padding(horizontal = BtTheme.spacing.screenH)

/** Logbook loading (ux.md § 3.13): the totals skeleton + 6 skeleton rows. */
private fun LazyListScope.logbookSkeleton() {
    item(key = "sk-totals") {
        Box(Modifier.padding(horizontal = BtTheme.spacing.screenH)) {
            SkeletonBlock(0.dp, 128.dp, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        }
    }
    items(6, key = { "sk-$it" }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
        ) {
            SkeletonBlock(BtTheme.spacing.playArtTileLog, BtTheme.spacing.playArtTileLog, shape = CircleShape)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.xs)) {
                SkeletonBlock(160.dp, 14.dp)
                SkeletonBlock(104.dp, 12.dp)
            }
            SkeletonBlock(32.dp, 14.dp)
        }
    }
}

/** Logbook (ux.md § 3.13, PF11): totals, then days newest first (sticky day headers), walks grouped; dates only. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogbookScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onWalk: (String) -> Unit) {
    val title = stringResource(Res.string.play_log_title)
    WithPlay(graph, title, onBack, loading = { logbookSkeleton() }) { play ->
        val loaded by produceState(false, play) {
            play.store.load()
            value = true
        }
        if (!loaded) {
            ListScreen(title, onBack = onBack) { logbookSkeleton() }
            return@WithPlay
        }
        val state by play.store.state.collectAsStateWithLifecycle()
        val history by graph.walks.history.collectAsStateWithLifecycle()
        var limit by remember { mutableIntStateOf(LogbookLogic.PAGE) }
        val today = IslandTime.dateOf(AppGraph.playClock.now())
        val model = remember(state.logbook, limit, today, settings.units) { LogbookLogic.model(state.logbook.entries, today, limit, settings.units) }
        val distances = remember(history) { history?.walks.orEmpty().associate { it.sessionId to it.distanceM } }
        val walkTemplate = stringResource(Res.string.play_log_walk_group, "%1\$s")
        ListScreen(title, onBack = onBack) {
            item(key = "totals") { LogbookTotalsCard(model.totals, Modifier.padding(horizontal = BtTheme.spacing.screenH)) }
            if (model.days.isEmpty()) item(key = "empty") { EmptyState(stringResource(Res.string.play_log_empty)) }
            for (day in model.days) {
                stickyHeader(key = "d-${day.date}") {
                    Text(
                        LogbookLogic.dayLabel(day.date, today),
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm).padding(top = BtTheme.spacing.md).semantics { heading() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (g in day.groups) {
                    // Keyed by identity (the walk, the day's week close), so paging and new entries keep scroll state.
                    when (g) {
                        is LogbookGroup.Walk -> item(key = "w-${day.date}-${g.walkId}") {
                            GroupHeader(LogbookLogic.walkLabel(walkTemplate, distances[g.walkId], settings.units), onClick = if (g.walkId in distances) ({ onWalk(g.walkId) }) else null)
                        }
                        is LogbookGroup.WeekClose -> item(key = "wk-${day.date}") { GroupHeader(stringResource(Res.string.play_log_week_close), onClick = null) }
                        is LogbookGroup.Loose -> Unit
                    }
                    items(g.rows, key = { it.id }) { LogbookRow(it) }
                }
            }
            if (model.more) {
                item(key = "more") {
                    TextButton(onClick = { limit += LogbookLogic.PAGE }, modifier = screenPad.heightIn(min = BtTheme.spacing.minTouch)) {
                        Text(stringResource(Res.string.play_log_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = BtTheme.spacing.minTouch)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).semantics { role = Role.Button } else Modifier)
            .padding(horizontal = BtTheme.spacing.screenH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (onClick != null) com.bainbridgetrails.ui.components.BtIcon(com.bainbridgetrails.ui.components.BtIcons.ChevronRight, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** LogbookTotals: one merged node ("2,291 marks. 185 this week. Rank Salal Scout, 709 to Madrona Wanderer."). */
@Composable
fun LogbookTotalsCard(t: LogbookTotals, modifier: Modifier = Modifier) {
    val total = stringResource(Res.string.play_log_total, PlayFormat.marks(t.total))
    val week = stringResource(Res.string.play_log_week, PlayFormat.marks(t.week))
    val rank = stringResource(t.rank.titleRes)
    val next = t.next?.let { n -> stringResource(Res.string.play_log_next_rank, PlayFormat.marks(t.toNext ?: 0), stringResource(n.titleRes)) }
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .clearAndSetSemantics { contentDescription = listOfNotNull(total, week, rank, next).joinToString(". ") }
            .padding(BtTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(PlayFormat.marks(t.total), style = BtTheme.type.marksTotal)
            Text(" " + total.removePrefix(PlayFormat.marks(t.total)).trim(), style = MaterialTheme.typography.bodyMedium)
        }
        Text(week, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (next != null) {
            PlayBarWithLabel(t.fraction, next, Modifier.padding(top = BtTheme.spacing.sm), leading = { RankBadge(t.rank) })
        } else {
            RankBadge(t.rank, Modifier.padding(top = BtTheme.spacing.sm))
        }
    }
}

/** Ranks (ux.md § 3.14, PF12): current header + bar, then the 11-rung ladder. */
@Composable
fun RanksScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit) {
    val title = stringResource(Res.string.play_ranks_title)
    WithPlay(graph, title, onBack) { play ->
        val state by play.store.state.collectAsStateWithLifecycle()
        val totals = LogbookTotals.of(state.logbook.total, 0)
        ListScreen(title, onBack = onBack) {
            item(key = "head") {
                Column(screenPad, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                    Text(stringResource(Res.string.play_ranks_current, stringResource(totals.rank.titleRes)), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    totals.next?.let { n ->
                        val line = stringResource(Res.string.play_ranks_next, PlayFormat.marks(totals.toNext ?: 0), stringResource(n.titleRes))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PlayBar(totals.fraction, line)
                    }
                }
            }
            items(RanksLogic.rows(totals.total), key = { it.first.number }) { (rank, st) ->
                val rt = stringResource(rank.titleRes)
                val flavour = stringResource(rank.flavourRes)
                val earned = stringResource(Res.string.play_rank_earned)
                val currentLine = stringResource(Res.string.play_ranks_current, rt)
                val row = stringResource(Res.string.play_rank_row_a11y, rt, PlayFormat.marks(rank.from), flavour)
                val spoken = when (st) {
                    RankRowState.Earned -> "$row. $earned"
                    RankRowState.Current -> "$row. $currentLine"
                    RankRowState.Future -> row
                }
                val current = st == RankRowState.Current
                val bar = MaterialTheme.colorScheme.tertiary
                Row(
                    Modifier.fillMaxWidth().heightIn(min = BtTheme.spacing.rowHeight)
                        .background(if (current) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface)
                        // ux § 2.7: the current rung carries a 2dp `tertiary` bar on its leading edge.
                        .then(if (current) Modifier.drawBehind { drawRect(bar, size = Size(2.dp.toPx(), size.height)) } else Modifier)
                        .clearAndSetSemantics { contentDescription = spoken }
                        .padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
                ) {
                    val ink = if (st == RankRowState.Future) MaterialTheme.colorScheme.onSurfaceVariant else BtTheme.colors.play.ink
                    FxGlyphImage(FxGlyph.Marks, Modifier.alpha(if (st == RankRowState.Future) 0.6f else 1f), size = 32.dp, ink = ink, lamp = ink, ticks = FxArt.ticksForRank(rank.number))
                    Column(Modifier.weight(1f)) {
                        val fg = if (st == RankRowState.Future) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        Text(rt, style = MaterialTheme.typography.titleMedium, color = fg)
                        Text(stringResource(Res.string.play_rank_from, PlayFormat.marks(rank.from)), style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(flavour, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (st == RankRowState.Earned) Text(earned, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** What's On (ux.md § 3.15): the agenda as rows; island time with the suffix when the phone differs. */
@Composable
fun WhatsOnScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onChallenges: () -> Unit, onLore: (String) -> Unit, onShowOnMap: (String?) -> Unit) {
    val title = stringResource(Res.string.play_whats_on_title)
    WithPlay(graph, title, onBack) { play ->
        val agenda by play.agenda.collectAsStateWithLifecycle()
        val config by play.config.collectAsStateWithLifecycle()
        val state by play.store.state.collectAsStateWithLifecycle()
        val a = agenda
        val c = config?.config
        val trail = rememberTrailChallenges(play, a, state.logbook)
        if (a == null || c == null || trail == null) {
            ListScreen(title, onBack = onBack) { item { LoadingState() } }
            return@WithPlay
        }
        val now = AppGraph.playClock.now()
        val zone = remember { TimeZone.currentSystemDefault() }
        val model = remember(a, c, trail) { WhatsOnLogic.model(a, c, play.goal, now, trail) }
        val islandT = stringResource(Res.string.play_island_time, "%1\$s")
        ListScreen(title, onBack = onBack) {
            item(key = "week") {
                Column(screenPad) {
                    Text(stringResource(Res.string.play_whats_on_week_of, PlayFormat.monthDay(model.weekOf)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val dawn = model.dawn
                    val dusk = model.dusk
                    val parts = listOfNotNull(
                        if (dawn != null && dusk != null) stringResource(Res.string.play_whats_on_daylight, PlayFormat.time(dawn), PlayFormat.time(dusk)) else null,
                        model.nextFerry?.let { stringResource(Res.string.play_whats_on_ferry, PlayFormat.time(it)) },
                    )
                    if (parts.isNotEmpty()) Text(WhatsOnLogic.withIslandSuffix(parts.joinToString(" · "), zone, now, islandT), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "today-h") { SectionHeader(stringResource(Res.string.play_whats_on_today), screenPad.padding(top = BtTheme.spacing.md)) }
            if (model.today.isEmpty()) item(key = "empty") { EmptyState(stringResource(Res.string.play_whats_on_empty), compact = true) }
            items(model.today, key = { it.key }) { row ->
                val rowTitle = if (row.kind == WhatsOnKind.Treasures) pluralStringResource(Res.plurals.play_whats_on_treasures, row.count ?: 0, row.count ?: 0, row.found ?: 0) else row.title
                val stars = row.stars.takeIf { row.kind == WhatsOnKind.Riddle }
                val window = if (stars != null) WhatsOnLogic.starsText(stars) else WhatsOnLogic.windowText(row.start, row.end, zone, now, islandT)
                val starsSpoken = stars?.let { pluralStringResource(Res.plurals.play_riddle_stars_a11y, it, it) }
                val onClick: (() -> Unit)? = when (row.kind) {
                    WhatsOnKind.Challenge -> onChallenges
                    WhatsOnKind.Memorial -> ({ onLore(row.itemId) })
                    // ux § 2.8: the Map tab with this occurrence selected and its EncounterSheet open.
                    else -> ({ onShowOnMap(row.key) })
                }
                WhatsOnRow(row.kind, rowTitle, window, row.gate, WhatsOnLogic.spoken(rowTitle, window, starsSpoken), onClick)
            }
            if (model.coming.isNotEmpty()) item(key = "coming-h") { SectionHeader(stringResource(Res.string.play_whats_on_coming), screenPad.padding(top = BtTheme.spacing.md)) }
            items(model.coming, key = { "c-" + it.key }) { row ->
                val t = if (row.kind == WhatsOnKind.Treasures) stringResource(Res.string.play_legend_treasure) else row.title
                val day = WhatsOnLogic.dayText(row.start)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = BtTheme.spacing.minTouch).clearAndSetSemantics { contentDescription = "$day, $t" }.padding(horizontal = BtTheme.spacing.screenH),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
                ) {
                    // min, not fixed: a large font grows the day column instead of clipping it.
                    Text(day, Modifier.widthIn(min = 40.dp), style = BtTheme.type.numericLabel)
                    Text(t, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Challenges (ux.md § 3.16, PF9): Just you / Whole island / Ended; card → ChallengeSheet. */
@Composable
fun ChallengesScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onShowOnMap: (String?) -> Unit) {
    val title = stringResource(Res.string.play_challenges_title)
    WithPlay(graph, title, onBack) { play ->
        val agenda by play.agenda.collectAsStateWithLifecycle()
        val config by play.config.collectAsStateWithLifecycle()
        val state by play.store.state.collectAsStateWithLifecycle()
        val goal = LocalGoal.current
        val nouns = GoalNouns(
            item = stringResource(goal.s(Res.string.play_goal_item)),
            items = stringResource(goal.s(Res.string.play_goal_items)),
        )
        val server by produceState<com.bainbridgetrails.net.PlayChallengesDto?>(null, play) { value = play.challenges()?.getOrNull() }
        val c = config?.config
        val trail = rememberTrailChallenges(play, agenda, state.logbook)
        if (c == null || trail == null) {
            ListScreen(title, onBack = onBack) { item { LoadingState() } }
            return@WithPlay
        }
        val now = AppGraph.playClock.now()
        val model = remember(agenda, c, server, state.logbook, nouns, trail, settings.units) {
            ChallengesLogic.model(c, play.goal, agenda, server, state.logbook.entries, now, nouns, trail, settings.units)
        }
        var open by remember { mutableStateOf<ChallengeCardModel?>(null) }
        ListScreen(title, onBack = onBack) {
            if (model.isEmpty) item(key = "empty") { EmptyState(stringResource(Res.string.play_challenges_empty), compact = true) }
            for ((header, cards) in listOf(Res.string.play_challenge_solo to model.solo, Res.string.play_challenge_island to model.island, Res.string.play_challenges_ended to model.ended)) {
                if (cards.isEmpty()) continue
                item(key = "h-$header") { SectionHeader(stringResource(header), screenPad.padding(top = BtTheme.spacing.sm)) }
                items(cards, key = { it.occKey }) { card ->
                    ChallengeCard(card, onClick = { open = card }, modifier = screenPad.padding(bottom = BtTheme.spacing.md))
                }
            }
        }
        open?.let { card ->
            ChallengeSheet(
                card,
                // ux § 3.16: the Map with this week's KrakenAttackSheet open.
                onShowOnMap = if (card.itemId == "ch-krakenweek") ({
                    open = null
                    onShowOnMap(agenda?.active?.firstOrNull { it.occurrence.type == com.bainbridgetrails.play.config.ItemType.obstacle }?.occurrence?.key)
                }) else null,
                onDismiss = { open = null },
            )
        }
    }
}

/**
 * Island stories (ux.md § 3.17): count header; a 2-column grid of 156dp cards (≥ 360dp wide and font scale < 1.5),
 * else a list; not-yet-met art in ink outline only (cards are readable anytime); remembrance last under its tag, no art.
 */
@Composable
fun IslandStoriesScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onLore: (String) -> Unit) {
    val title = stringResource(Res.string.play_stories_title)
    WithPlay(graph, title, onBack) { play ->
        val config by play.config.collectAsStateWithLifecycle()
        val state by play.store.state.collectAsStateWithLifecycle()
        val model = remember(config, state.found) { StoriesLogic.model(config?.config, play.goal, state.found) }
        val (stories, remembrance) = remember(model) { model.cards.partition { !it.remembrance } }
        val bigText = LocalDensity.current.fontScale >= 1.5f
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val grid = maxWidth >= 360.dp && !bigText
            ListScreen(title, onBack = onBack) {
                item(key = "count") {
                    Text(stringResource(Res.string.play_stories_count, model.met, model.total), screenPad.padding(bottom = BtTheme.spacing.sm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (grid) {
                    items(stories.chunked(2), key = { it.first().item.id }) { pair ->
                        Row(screenPad.padding(bottom = BtTheme.spacing.md), horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
                            for (card in pair) StoryGridCard(card, { onLore(card.item.id) }, Modifier.weight(1f))
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    items(stories, key = { it.item.id }) { card -> StoryListRow(card) { onLore(card.item.id) } }
                }
                if (remembrance.isNotEmpty()) {
                    item(key = "remembrance-h") { SectionHeader(stringResource(Res.string.play_tag_remembrance), screenPad.padding(top = BtTheme.spacing.sm)) }
                    items(remembrance, key = { it.item.id }) { card -> StoryListRow(card) { onLore(card.item.id) } }
                }
            }
        }
    }
}

/** The story's art tile: met = full art on `surfaceContainerHigh`; not yet met = ink outline only (no lamp, no fill). */
@Composable
private fun StoryArt(card: StoryCard, tile: androidx.compose.ui.unit.Dp) {
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier.size(tile).then(
            if (card.met) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (card.met) {
            FxGlyphImage(storyGlyph(card), size = tile - BtTheme.spacing.lg)
        } else {
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            FxGlyphImage(storyGlyph(card), size = tile - BtTheme.spacing.lg, ink = ink, lamp = Color.Transparent)
        }
    }
}

@Composable
private fun StoryGridCard(card: StoryCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tag = stringResource(storyTag(card))
    Column(
        modifier.heightIn(min = 156.dp).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = "${card.item.copy.title}, $tag"; role = Role.Button }
            .padding(BtTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
    ) {
        StoryArt(card, BtTheme.spacing.playArtTileSheet)
        Text(card.item.copy.title, style = MaterialTheme.typography.titleSmall, maxLines = 3)
        Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The list form (narrow screens, large text) and remembrance cards (no art). */
@Composable
private fun StoryListRow(card: StoryCard, onClick: () -> Unit) {
    val tag = stringResource(storyTag(card))
    Row(
        Modifier.fillMaxWidth().heightIn(min = BtTheme.spacing.rowHeight).clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = "${card.item.copy.title}, $tag"; role = Role.Button }
            .padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        if (!card.remembrance) StoryArt(card, BtTheme.spacing.playArtTileSheet)
        Column(Modifier.weight(1f)) {
            Text(card.item.copy.title, style = MaterialTheme.typography.titleSmall)
            Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun storyTag(card: StoryCard) = when (card.item.lore) {
    "remembrance" -> Res.string.play_tag_remembrance
    "kraken" -> Res.string.play_tag_tall_tale
    else -> Res.string.play_tag_history
}

private fun storyGlyph(card: StoryCard) = when (card.item.lore) {
    "kraken" -> FxGlyph.KrakenEye
    "frog" -> FxGlyph.Frog
    "ferry" -> FxGlyph.Ferry
    else -> FxGlyph.ShipsBell
}

/** Lore card (ux.md § 3.17): the lore content as a pushed screen (art 120dp, sources expanded). */
@Composable
fun LoreCardScreen(graph: AppGraph.Installed, settings: AppSettings, itemId: String, onBack: () -> Unit) {
    val title = stringResource(Res.string.play_stories_title)
    WithPlay(graph, title, onBack) { play ->
        val config by play.config.collectAsStateWithLifecycle()
        val pack = config?.config
        val item = pack?.items?.firstOrNull { it.id == itemId }
        ListScreen(item?.copy?.title ?: title, onBack = onBack) {
            if (item == null) {
                // No pack yet: loading. A pack without this card (removed, killed, another goal's): gone for now.
                if (pack == null) item(key = "loading") { LoadingState() } else item(key = "gone") { EmptyState(stringResource(Res.string.play_content_gone)) }
                return@ListScreen
            }
            item(key = "body") {
                Column(screenPad, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
                    if (item.lore != "remembrance") FxGlyphImage(storyGlyph(StoryCard(item, true)), Modifier.align(Alignment.CenterHorizontally), size = 120.dp)
                    Text(stringResource(storyTag(StoryCard(item, true))), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.copy.body, style = MaterialTheme.typography.bodyLarge)
                    if (item.sources.isNotEmpty()) {
                        Text(stringResource(Res.string.play_sources), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                        for (s in item.sources) Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
