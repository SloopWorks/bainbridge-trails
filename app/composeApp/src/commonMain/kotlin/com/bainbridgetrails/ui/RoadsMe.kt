package com.bainbridgetrails.ui

import com.bainbridgetrails.map.segmentsById
import com.bainbridgetrails.tracking.SessionSummary
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.coverage.ProgressSnapshot
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.me_hero_caption
import com.bainbridgetrails.resources.me_hero_caption_new
import com.bainbridgetrails.resources.roads_me_by_type
import com.bainbridgetrails.resources.roads_me_hero_a11y_roads
import com.bainbridgetrails.resources.roads_me_hero_pct
import com.bainbridgetrails.resources.roads_me_milestones
import com.bainbridgetrails.resources.roads_me_milestones_all
import com.bainbridgetrails.resources.roads_me_next_milestone
import com.bainbridgetrails.resources.roads_me_type_row
import com.bainbridgetrails.resources.roads_me_type_row_a11y
import com.bainbridgetrails.resources.roads_milestone_earned_a11y
import com.bainbridgetrails.resources.roads_milestone_locked_a11y
import com.bainbridgetrails.resources.roads_milestone_next_a11y
import com.bainbridgetrails.resources.roads_milestone_progress
import com.bainbridgetrails.resources.roads_milestones_ladder_miles
import com.bainbridgetrails.resources.roads_milestones_ladder_roads
import com.bainbridgetrails.resources.roads_milestones_ladder_types
import com.bainbridgetrails.resources.roads_milestones_title
import com.bainbridgetrails.ui.components.ChartSpec
import com.bainbridgetrails.ui.components.IslandChart
import com.bainbridgetrails.ui.components.IslandGeometry
import com.bainbridgetrails.ui.components.MilestoneBadge
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.milestoneDistance
import com.bainbridgetrails.ui.components.milestoneMark
import com.bainbridgetrails.ui.components.milestoneTitle
import com.bainbridgetrails.ui.components.roadCategoryLabel
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/** Everything the roads Me sections show, derived from the snapshot + dataset rows (product.md §5.1). */
internal data class RoadsMeNumbers(
    val coveredM: Double,
    val islandFraction: Double,
    val completed: Int,
    val counted: Int,
    /** Category → complete / counted (counted roads only). */
    val completedByCategory: Map<String, Int>,
    val countedByCategory: Map<String, Int>,
    /** Category → covered fraction-weighted miles (for the a11y line). */
    val walkedMiByCategory: Map<String, Double>,
) {
    companion object {
        fun of(data: TrailData, s: ProgressSnapshot): RoadsMeNumbers {
            val metas = data.dataset.trails.associateBy { it.id }
            val segLen = data.dataset.segmentsById
            val done = HashMap<String, Int>()
            val total = HashMap<String, Int>()
            val walked = HashMap<String, Double>()
            for (p in s.trails) {
                val m = metas[p.trailId] ?: continue
                val cat = m.category ?: continue
                if (!m.counted) continue
                total[cat] = (total[cat] ?: 0) + 1
                if (p.status == TrailStatus.COMPLETE) done[cat] = (done[cat] ?: 0) + 1
                // Fraction is over the counted segments (RP-18), so weigh it by their length, not the display length.
                val segs = m.segmentIds.distinct().mapNotNull { segLen[it] }
                val countedM = segs.filter { it.included }.ifEmpty { segs }.sumOf { it.lengthM }
                walked[cat] = (walked[cat] ?: 0.0) + p.fraction * countedM / METRES_PER_MILE
            }
            return RoadsMeNumbers(s.coveredM, s.islandFraction, s.trailsCompleted, s.trailCount, done, total, walked)
        }
    }
}

/**
 * The milestones a finished walk crossed (product.md §5.3), highest first, from the walk's own [summary] numbers (read
 * under the coverage lock by the finalizer), never from a later snapshot that sync may have moved. Empty for trails.
 */
internal fun walkMilestones(
    data: TrailData,
    summary: SessionSummary,
    units: Units,
    islandReached: Boolean = false,
): List<Milestone> {
    val metas = data.dataset.trails.associateBy { it.id }
    val counted = summary.newlyCompleted.filter { metas[it]?.counted == true }
    return Milestones.crossed(
        WalkMilestoneInput(
            islandBefore = summary.islandBefore,
            islandAfter = summary.islandAfter,
            islandTotalM = data.dataset.islandTotalM,
            units = units,
            newlyCompleted = counted,
            completedAfter = summary.completedAfter,
            categoryOf = { metas[it]?.category },
            completedByCategory = summary.completedByCategory,
            countedByCategory = summary.countedByCategory,
            islandReached = islandReached,
            completedBefore = summary.completedBefore,
        ),
    )
}

private const val METRES_PER_MILE = 1609.344

/**
 * Roads Me hero (RD-9, docs/roads/ux.md §5.1): the island plat drawn with the roads [ChartSpec], road miles covered as
 * the hero number ("12.4 mi", unit set smaller), "of public road walked", then "7.6% of the island" under it.
 */
@Composable
internal fun RoadsHero(
    geometry: IslandGeometry,
    segStatus: Map<String, TrailStatus>,
    n: RoadsMeNumbers,
    units: Units,
    onMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val goal = LocalGoal.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val water = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, BtTheme.colors.waterLine, if (dark) 0.18f else 0.22f)
    val land = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface
    val distance = Format.distance(n.coveredM, units)
    val value = distance.substringBefore(' ')
    val unit = distance.substringAfter(' ')
    val pct = Format.percent(n.islandFraction)
    val a11y = stringResource(Res.string.roads_me_hero_a11y_roads, distance, pct, n.completed, n.counted)
    val scale = LocalDensity.current.fontScale
    val heroStyle = BtTheme.type.statHero.let { if (scale > 1.3f) it.copy(fontSize = (64 * 1.3f / scale).sp, lineHeight = (68 * 1.3f / scale).sp) else it }
    val isNew = n.coveredM <= 0.0
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
            spec = ChartSpec.ROADS,
        )
        Column(Modifier.align(Alignment.BottomStart).padding(BtTheme.spacing.lg).fillMaxWidth(0.62f).clearAndSetSemantics {}) {
            Text(
                buildAnnotatedString {
                    append(value)
                    pushStyle(SpanStyle(fontSize = heroStyle.fontSize * 0.4f, letterSpacing = 0.sp))
                    append(" $unit")
                    pop()
                },
                style = heroStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false,
            )
            Text(
                stringResource(if (isNew) Res.string.me_hero_caption_new else goal.s(Res.string.me_hero_caption)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isNew) {
                Text(
                    stringResource(Res.string.roads_me_hero_pct, pct),
                    Modifier.padding(top = 2.dp),
                    style = BtTheme.type.numeric,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Next milestone: 15 mi · 2.6 mi to go" (product.md §5.2). */
@Composable
internal fun NextMilestoneLine(n: RoadsMeNumbers, units: Units, modifier: Modifier = Modifier) {
    val d = Milestones.unitsOf(n.coveredM, units)
    val next = Milestones.nextDistance(d)
    val toGo = Format.oneDecimal(next - d) + if (units == Units.MILES) " mi" else " km"
    Text(
        stringResource(Res.string.roads_me_next_milestone, milestoneDistance(next, units), toGo),
        modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun badgeA11y(p: MilestoneProgress, units: Units): String {
    val title = milestoneTitle(p.milestone)
    return when (p.state) {
        MilestoneState.EARNED -> stringResource(Res.string.roads_milestone_earned_a11y, title)
        MilestoneState.NEXT -> stringResource(Res.string.roads_milestone_next_a11y, title, progressValue(p, units).first, progressValue(p, units).second)
        MilestoneState.LOCKED -> stringResource(Res.string.roads_milestone_locked_a11y, title)
    }
}

private fun progressValue(p: MilestoneProgress, units: Units): Pair<String, String> = when (p.milestone) {
    is Milestone.Distance -> Format.oneDecimal(p.current) to "${p.target.toInt()}"
    else -> "${p.current.toInt()}" to "${p.target.toInt()}"
}

/** Short badge title: "5 mi of road" style for distance, the ladder title otherwise. */
@Composable
private fun badgeTitle(p: MilestoneProgress): String = milestoneTitle(p.milestone)

/** One badge of the strip or the Milestones sheet. */
@Composable
private fun Badge(p: MilestoneProgress, units: Units, onClick: (() -> Unit)?) {
    val (cur, target) = progressValue(p, units)
    MilestoneBadge(
        title = badgeTitle(p),
        mark = milestoneMark(p.milestone),
        state = p.state,
        fraction = p.fraction,
        progressText = if (p.state == MilestoneState.NEXT) stringResource(Res.string.roads_milestone_progress, cur, target) else null,
        a11y = badgeA11y(p, units),
        onClick = onClick,
    )
}

/** Milestones header + strip (docs/roads/ux.md §5.3): last earned (max 2) then next 2; "All" opens the sheet. */
@Composable
internal fun MilestoneStrip(strip: List<MilestoneProgress>, units: Units, onAll: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionHeader(
            stringResource(Res.string.roads_me_milestones),
            Modifier.padding(horizontal = BtTheme.spacing.screenH),
            action = stringResource(Res.string.roads_me_milestones_all) to onAll,
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BtTheme.spacing.screenH),
            horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
        ) {
            items(strip, key = { it.milestone.toString() }) { p -> Badge(p, units, onAll) }
        }
    }
}

/** "By road type" (product.md §5.2): one row per type, x of N + a thin primary bar; tap → the list filtered to it. */
@Composable
internal fun ByTypeRows(n: RoadsMeNumbers, units: Units, onType: (RoadGroup) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionHeader(stringResource(Res.string.roads_me_by_type))
        for (cat in Milestones.TypeCategories) {
            val total = n.countedByCategory[cat] ?: continue
            if (total == 0) continue
            val done = n.completedByCategory[cat] ?: 0
            val label = roadCategoryLabel(cat)
            val walked = Format.lengthMi(n.walkedMiByCategory[cat] ?: 0.0, units)
            val a11y = stringResource(Res.string.roads_me_type_row_a11y, label, done, total, walked)
            Column(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(MaterialTheme.shapes.small)
                    .clickable { onType(RoadGroup.of(cat, true)) }
                    .clearAndSetSemantics { contentDescription = a11y; role = Role.Button }
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(Res.string.roads_me_type_row, done, total), style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val f = done.toFloat() / total
                Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)) {
                    if (f > 0f) Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).height(4.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                }
            }
        }
    }
}

/** All milestones by ladder (ux.md §5.3 MilestonesScreen), as a full-height sheet: Earned → Next → Locked. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MilestonesSheet(ladder: Map<String, List<MilestoneProgress>>, units: Units, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = BtTheme.spacing.screenH)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(bottom = BtTheme.spacing.xl),
        ) {
            Text(stringResource(Res.string.roads_milestones_title), Modifier.semantics { heading() }.padding(bottom = BtTheme.spacing.sm), style = MaterialTheme.typography.headlineSmall)
            val ladders = listOf(
                Milestones.LADDER_DISTANCE to Res.string.roads_milestones_ladder_miles,
                Milestones.LADDER_ROADS to Res.string.roads_milestones_ladder_roads,
                Milestones.LADDER_TYPES to Res.string.roads_milestones_ladder_types,
            )
            for ((key, title) in ladders) {
                val list = ladder[key].orEmpty()
                if (list.isEmpty()) continue
                SectionHeader(stringResource(title))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                    list.forEach { Badge(it, units, null) }
                }
                Spacer(Modifier.height(BtTheme.spacing.md))
            }
        }
    }
}
