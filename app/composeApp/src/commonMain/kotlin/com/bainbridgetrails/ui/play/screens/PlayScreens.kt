package com.bainbridgetrails.ui.play.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_challenge_island
import com.bainbridgetrails.resources.play_challenge_solo
import com.bainbridgetrails.resources.play_challenges_empty
import com.bainbridgetrails.resources.play_challenges_ended
import com.bainbridgetrails.resources.play_challenges_title
import com.bainbridgetrails.resources.play_log_empty
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
import com.bainbridgetrails.resources.play_show_on_map
import com.bainbridgetrails.ui.ListScreen
import com.bainbridgetrails.ui.components.EmptyState
import com.bainbridgetrails.ui.components.LoadingState
import com.bainbridgetrails.ui.components.PlayBar
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SkeletonBlock
import com.bainbridgetrails.ui.play.components.ChallengeCard
import com.bainbridgetrails.ui.play.components.LogbookRow
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.play.components.RankBadge
import com.bainbridgetrails.ui.play.components.WhatsOnRow
import com.bainbridgetrails.ui.play.components.flavourRes
import com.bainbridgetrails.ui.play.components.rememberPlay
import com.bainbridgetrails.ui.play.components.titleRes
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A Play screen's body once the repository runs; a loading state until then (screens are reachable only with Play on). */
@Composable
private fun WithPlay(graph: AppGraph.Installed, settings: AppSettings, title: String, onBack: () -> Unit, content: @Composable (PlayRepository) -> Unit) {
    val play = rememberPlay(graph, settings.play.enabled)
    if (play == null) {
        ListScreen(title, onBack = onBack) { item { LoadingState() } }
    } else {
        content(play)
    }
}

private val m = Modifier.padding(horizontal = 16.dp)

/** Logbook (ux.md § 3.13, PF11): totals, then days newest first, walks grouped; dates only. */
@Composable
fun LogbookScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onWalk: (String) -> Unit) {
    val title = stringResource(Res.string.play_log_title)
    WithPlay(graph, settings, title, onBack) { play ->
        val state by play.store.state.collectAsStateWithLifecycle()
        val history by graph.walks.history.collectAsStateWithLifecycle()
        var limit by remember { mutableIntStateOf(LogbookLogic.PAGE) }
        val today = IslandTime.dateOf(AppGraph.playClock.now())
        val model = remember(state.logbook, limit, today) { LogbookLogic.model(state.logbook.entries, today, limit) }
        val distances = remember(history) { history?.walks.orEmpty().associate { it.sessionId to it.distanceM } }
        val walkTemplate = stringResource(Res.string.play_log_walk_group, "%1\$s")
        ListScreen(title, onBack = onBack) {
            item(key = "totals") { LogbookTotalsCard(model.totals, Modifier.padding(horizontal = BtTheme.spacing.screenH)) }
            if (model.days.isEmpty()) item(key = "empty") { EmptyState(stringResource(Res.string.play_log_empty)) }
            for (day in model.days) {
                item(key = "d-${day.date}") {
                    Text(
                        LogbookLogic.dayLabel(day.date, today),
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm).padding(top = BtTheme.spacing.md).semantics { heading() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for ((gi, g) in day.groups.withIndex()) {
                    item(key = "g-${day.date}-$gi") {
                        when (g) {
                            is LogbookGroup.Walk -> GroupHeader(LogbookLogic.walkLabel(walkTemplate, distances[g.walkId], settings.units), onClick = if (g.walkId in distances) ({ onWalk(g.walkId) }) else null)
                            is LogbookGroup.WeekClose -> GroupHeader(stringResource(Res.string.play_log_week_close), onClick = null)
                            is LogbookGroup.Loose -> Unit
                        }
                    }
                    items(g.rows, key = { it.id }) { LogbookRow(it) }
                }
            }
            if (model.more) item(key = "more") { TextButton(onClick = { limit += LogbookLogic.PAGE }, modifier = m) { Text("…") } }
        }
    }
}

@Composable
private fun GroupHeader(text: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
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
        Row(Modifier.padding(top = BtTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
            RankBadge(t.rank)
            if (next != null) {
                PlayBar(t.fraction, next, Modifier.weight(1f))
                Text(next, style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Ranks (ux.md § 3.14, PF12): current header + bar, then the 11-rung ladder. */
@Composable
fun RanksScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit) {
    val title = stringResource(Res.string.play_ranks_title)
    WithPlay(graph, settings, title, onBack) { play ->
        val state by play.store.state.collectAsStateWithLifecycle()
        val totals = LogbookTotals.of(state.logbook.total, 0)
        ListScreen(title, onBack = onBack) {
            item(key = "head") {
                Column(m, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
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
                val spoken = stringResource(Res.string.play_rank_row_a11y, rt, PlayFormat.marks(rank.from), flavour) + if (st == RankRowState.Earned) ". $earned" else ""
                val current = st == RankRowState.Current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .background(if (current) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface)
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
fun WhatsOnScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onChallenges: () -> Unit, onLore: (String) -> Unit, onShowOnMap: () -> Unit) {
    val title = stringResource(Res.string.play_whats_on_title)
    WithPlay(graph, settings, title, onBack) { play ->
        val agenda by play.agenda.collectAsStateWithLifecycle()
        val config by play.config.collectAsStateWithLifecycle()
        val a = agenda
        val c = config?.config
        val goal = LocalGoal.current
        if (a == null || c == null) {
            ListScreen(title, onBack = onBack) { item { LoadingState() } }
            return@WithPlay
        }
        val now = AppGraph.playClock.now()
        val zone = remember { TimeZone.currentSystemDefault() }
        val model = remember(a, c) { WhatsOnLogic.model(a, c, play.goal, now) }
        val islandT = stringResource(Res.string.play_island_time, "%1\$s")
        ListScreen(title, onBack = onBack) {
            item(key = "week") {
                Column(m) {
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
            item(key = "today-h") { SectionHeader(stringResource(Res.string.play_whats_on_today), m.padding(top = BtTheme.spacing.md)) }
            if (model.today.isEmpty()) item(key = "empty") { EmptyState(stringResource(Res.string.play_whats_on_empty), compact = true) }
            items(model.today, key = { it.key }) { row ->
                val rowTitle = if (row.kind == WhatsOnKind.Treasures) pluralStringResource(Res.plurals.play_whats_on_treasures, row.count ?: 0, row.count ?: 0, row.found ?: 0) else row.title
                val window = when {
                    row.kind == WhatsOnKind.Riddle -> row.stars?.let { "★".repeat(it) }
                    else -> WhatsOnLogic.windowText(row.start, row.end, zone, now, islandT)
                }
                val onClick: (() -> Unit)? = when (row.kind) {
                    WhatsOnKind.Challenge -> onChallenges
                    WhatsOnKind.Memorial -> ({ onLore(row.itemId) })
                    else -> onShowOnMap
                }
                WhatsOnRow(row.kind, rowTitle, window, row.gate, listOfNotNull(rowTitle, window).joinToString(", "), onClick)
            }
            if (model.coming.isNotEmpty()) item(key = "coming-h") { SectionHeader(stringResource(Res.string.play_whats_on_coming), m.padding(top = BtTheme.spacing.md)) }
            items(model.coming, key = { "c-" + it.key }) { row ->
                val t = if (row.kind == WhatsOnKind.Treasures) stringResource(Res.string.play_legend_treasure) else row.title
                val day = WhatsOnLogic.dayText(row.start)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = "$day, $t" }.padding(horizontal = BtTheme.spacing.screenH), verticalAlignment = Alignment.CenterVertically) {
                    Text(day, Modifier.width(48.dp), style = BtTheme.type.numericLabel)
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Challenges (ux.md § 3.16, PF9): Just you / Whole island / Ended; card → ChallengeSheet. */
@Composable
fun ChallengesScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onShowOnMap: () -> Unit) {
    val title = stringResource(Res.string.play_challenges_title)
    WithPlay(graph, settings, title, onBack) { play ->
        val agenda by play.agenda.collectAsStateWithLifecycle()
        val config by play.config.collectAsStateWithLifecycle()
        val state by play.store.state.collectAsStateWithLifecycle()
        val server by produceState<com.bainbridgetrails.net.PlayChallengesDto?>(null, play) { value = play.challenges()?.getOrNull() }
        val c = config?.config
        if (c == null) {
            ListScreen(title, onBack = onBack) { item { LoadingState() } }
            return@WithPlay
        }
        val now = AppGraph.playClock.now()
        val model = remember(agenda, c, server, state.logbook) { ChallengesLogic.model(c, play.goal, agenda, server, state.logbook.entries, now) }
        var open by remember { mutableStateOf<ChallengeCardModel?>(null) }
        ListScreen(title, onBack = onBack) {
            if (model.isEmpty) item(key = "empty") { EmptyState(stringResource(Res.string.play_challenges_empty), compact = true) }
            for ((header, cards) in listOf(Res.string.play_challenge_solo to model.solo, Res.string.play_challenge_island to model.island, Res.string.play_challenges_ended to model.ended)) {
                if (cards.isEmpty()) continue
                item(key = "h-$header") { SectionHeader(stringResource(header), m.padding(top = BtTheme.spacing.sm)) }
                items(cards, key = { it.occKey }) { card ->
                    ChallengeCard(card, onClick = { open = card }, modifier = m.padding(bottom = BtTheme.spacing.md))
                }
            }
        }
        open?.let { card -> ChallengeSheet(card, onShowOnMap = if (card.itemId == "ch-krakenweek") ({ open = null; onShowOnMap() }) else null, onDismiss = { open = null }) }
    }
}

/** Island stories (ux.md § 3.17): count header, cards (art outline until met), remembrance last without art. */
@Composable
fun IslandStoriesScreen(graph: AppGraph.Installed, settings: AppSettings, onBack: () -> Unit, onLore: (String) -> Unit) {
    val title = stringResource(Res.string.play_stories_title)
    WithPlay(graph, settings, title, onBack) { play ->
        val config by play.config.collectAsStateWithLifecycle()
        val state by play.store.state.collectAsStateWithLifecycle()
        val model = remember(config, state.found) { StoriesLogic.model(config?.config, play.goal, state.found) }
        ListScreen(title, onBack = onBack) {
            item(key = "count") { Text(stringResource(Res.string.play_stories_count, model.met, model.total), m, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(model.cards, key = { it.item.id }) { card ->
                val tag = stringResource(storyTag(card))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { onLore(card.item.id) }
                        .clearAndSetSemantics { contentDescription = "${card.item.copy.title}, $tag"; role = Role.Button }
                        .padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
                ) {
                    if (!card.remembrance) {
                        FxGlyphImage(storyGlyph(card), Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(8.dp).alpha(if (card.met) 1f else 0.5f), size = 40.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(card.item.copy.title, style = MaterialTheme.typography.titleSmall)
                        Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
    WithPlay(graph, settings, title, onBack) { play ->
        val config by play.config.collectAsStateWithLifecycle()
        val item = config?.config?.items?.firstOrNull { it.id == itemId }
        ListScreen(item?.copy?.title ?: title, onBack = onBack) {
            if (item == null) {
                item { LoadingState() }
                return@ListScreen
            }
            item(key = "body") {
                Column(m, verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
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
