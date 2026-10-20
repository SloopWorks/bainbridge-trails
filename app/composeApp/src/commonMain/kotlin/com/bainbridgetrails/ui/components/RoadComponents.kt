package com.bainbridgetrails.ui.components

import com.bainbridgetrails.data.LocalGoal
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.map.RoadCaution
import com.bainbridgetrails.map.RoadEnd
import com.bainbridgetrails.map.RoadOwner
import com.bainbridgetrails.map.RoadReason
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.ic_directions
import com.bainbridgetrails.resources.ic_warning
import com.bainbridgetrails.resources.roads_badge_counts
import com.bainbridgetrails.resources.roads_badge_lane
import com.bainbridgetrails.resources.roads_badge_private
import com.bainbridgetrails.resources.roads_badge_unknown
import com.bainbridgetrails.resources.roads_cat_arterial
import com.bainbridgetrails.resources.roads_cat_collector
import com.bainbridgetrails.resources.roads_cat_lane
import com.bainbridgetrails.resources.roads_cat_residential
import com.bainbridgetrails.resources.roads_cat_state_route
import com.bainbridgetrails.resources.roads_caution_fast
import com.bainbridgetrails.resources.roads_caution_state_route
import com.bainbridgetrails.resources.roads_detail_road_end
import com.bainbridgetrails.resources.roads_group_collectors
import com.bainbridgetrails.resources.roads_group_count
import com.bainbridgetrails.resources.roads_group_header_a11y
import com.bainbridgetrails.resources.roads_group_main
import com.bainbridgetrails.resources.roads_group_not_counted
import com.bainbridgetrails.resources.roads_group_residential
import com.bainbridgetrails.resources.roads_milestone_cat_arterial
import com.bainbridgetrails.resources.roads_milestone_cat_collector
import com.bainbridgetrails.resources.roads_milestone_cat_residential
import com.bainbridgetrails.resources.roads_milestone_cat_state_route
import com.bainbridgetrails.resources.roads_milestone_miles
import com.bainbridgetrails.resources.roads_milestone_roads
import com.bainbridgetrails.resources.roads_not_counted_lane
import com.bainbridgetrails.resources.roads_not_counted_private
import com.bainbridgetrails.resources.roads_not_counted_unknown
import com.bainbridgetrails.resources.roads_owner_city
import com.bainbridgetrails.resources.roads_owner_county
import com.bainbridgetrails.resources.roads_owner_parks
import com.bainbridgetrails.resources.roads_owner_school
import com.bainbridgetrails.resources.roads_owner_state
import com.bainbridgetrails.resources.roads_reason_lane
import com.bainbridgetrails.resources.roads_reason_private
import com.bainbridgetrails.resources.roads_reason_unknown
import com.bainbridgetrails.resources.roads_road_end_directions_a11y
import com.bainbridgetrails.resources.roads_road_end_supporting
import com.bainbridgetrails.resources.roads_surface_dirt
import com.bainbridgetrails.resources.roads_surface_gravel
import com.bainbridgetrails.resources.roads_surface_paved
import com.bainbridgetrails.resources.roads_surface_unpaved
import com.bainbridgetrails.resources.celebrate_island_eyebrow
import com.bainbridgetrails.resources.status_not_counted
import com.bainbridgetrails.ui.Milestone
import com.bainbridgetrails.ui.MilestoneState
import com.bainbridgetrails.ui.RoadGroup
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.roundToLong

// ---- labels (docs/roads/product.md §3) -------------------------------------------------------------------------

@Composable
fun roadCategoryLabel(category: String?): String = stringResource(
    when (category) {
        "state_route" -> Res.string.roads_cat_state_route
        "arterial" -> Res.string.roads_cat_arterial
        "collector" -> Res.string.roads_cat_collector
        "lane", "driveway" -> Res.string.roads_cat_lane
        else -> Res.string.roads_cat_residential
    },
)

@Composable
fun roadGroupLabel(group: RoadGroup): String = stringResource(
    when (group) {
        RoadGroup.MAIN -> Res.string.roads_group_main
        RoadGroup.COLLECTORS -> Res.string.roads_group_collectors
        RoadGroup.RESIDENTIAL -> Res.string.roads_group_residential
        RoadGroup.NOT_COUNTED -> Res.string.roads_group_not_counted
    },
)

@Composable
fun roadOwnerLabel(owner: RoadOwner): String = stringResource(
    when (owner) {
        RoadOwner.CITY -> Res.string.roads_owner_city
        RoadOwner.STATE -> Res.string.roads_owner_state
        RoadOwner.COUNTY -> Res.string.roads_owner_county
        RoadOwner.PARKS -> Res.string.roads_owner_parks
        RoadOwner.SCHOOL -> Res.string.roads_owner_school
    },
)

/** Surface label, or null for no data (the tile is hidden, never "—"). */
@Composable
fun roadSurfaceLabel(surface: String?): String? = when (surface) {
    "paved" -> stringResource(Res.string.roads_surface_paved)
    "gravel" -> stringResource(Res.string.roads_surface_gravel)
    "dirt" -> stringResource(Res.string.roads_surface_dirt)
    "unpaved" -> stringResource(Res.string.roads_surface_unpaved)
    else -> null
}

/** One-line reason an uncounted road is out of the goal (detail, preview). */
@Composable
fun roadReasonText(reason: RoadReason): String = stringResource(
    when (reason) {
        RoadReason.PRIVATE -> Res.string.roads_reason_private
        RoadReason.UNKNOWN -> Res.string.roads_reason_unknown
        RoadReason.LANE -> Res.string.roads_reason_lane
    },
)

/** Short kind of an uncounted road (list row supporting text). */
@Composable
fun roadReasonShort(reason: RoadReason): String = stringResource(
    when (reason) {
        RoadReason.PRIVATE -> Res.string.roads_not_counted_private
        RoadReason.UNKNOWN -> Res.string.roads_not_counted_unknown
        RoadReason.LANE -> Res.string.roads_not_counted_lane
    },
)

/** "5 mi" / "10 km": a whole distance step. */
fun milestoneDistance(value: Int, units: Units): String = "$value ${if (units == Units.MILES) "mi" else "km"}"

/** A milestone's title ("5 mi of road walked", "Your first road", "Every arterial, done"). */
@Composable
fun milestoneTitle(m: Milestone): String = when (m) {
    is Milestone.Distance -> stringResource(Res.string.roads_milestone_miles, milestoneDistance(m.value, m.units))
    is Milestone.Roads -> pluralStringResource(Res.plurals.roads_milestone_roads, m.count, m.count)
    is Milestone.TypeComplete -> stringResource(
        when (m.category) {
            "state_route" -> Res.string.roads_milestone_cat_state_route
            "arterial" -> Res.string.roads_milestone_cat_arterial
            "collector" -> Res.string.roads_milestone_cat_collector
            else -> Res.string.roads_milestone_cat_residential
        },
    )
    Milestone.Island -> stringResource(LocalGoal.current.s(Res.string.celebrate_island_eyebrow))
}

/** The short medallion text: "5", "10", a category initial, or a check for the island. */
fun milestoneMark(m: Milestone): String = when (m) {
    is Milestone.Distance -> "${m.value}"
    is Milestone.Roads -> "${m.count}"
    is Milestone.TypeComplete -> when (m.category) {
        "state_route" -> "SR"
        "arterial" -> "A"
        "collector" -> "C"
        else -> "R"
    }
    Milestone.Island -> "✓"
}

/** Road labels resolved once in composition, for non-composable callers (summaries, sorting, a11y strings). */
class RoadTexts(private val categories: Map<String, String>, private val reasons: Map<RoadReason, String>, private val shortReasons: Map<RoadReason, String>) {
    fun category(c: String?): String = categories[c] ?: categories.getValue("residential")
    fun reason(r: RoadReason): String = reasons.getValue(r)
    fun reasonShort(r: RoadReason): String = shortReasons.getValue(r)
}

@Composable
fun rememberRoadTexts(): RoadTexts {
    val cats = listOf("state_route", "arterial", "collector", "residential", "lane", "driveway").associateWith { roadCategoryLabel(it) }
    val reasons = RoadReason.entries.associateWith { roadReasonText(it) }
    val short = RoadReason.entries.associateWith { roadReasonShort(it) }
    return androidx.compose.runtime.remember(cats, reasons, short) { RoadTexts(cats, reasons, short) }
}

// ---- components (docs/roads/ux.md §3–§5) ---------------------------------------------------------------------

/**
 * Whether a road counts (ux.md §3.2 OwnershipBadge): a neutral 28dp pill, never a status color. Counted = spruce
 * secondary container + check; uncounted = neutral with the map's own muted pattern as the leading swatch.
 */
@Composable
fun OwnershipBadge(reason: RoadReason?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val counted = reason == null
    val bg = if (counted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (counted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val text = stringResource(
        when (reason) {
            null -> Res.string.roads_badge_counts
            RoadReason.PRIVATE -> Res.string.roads_badge_private
            RoadReason.UNKNOWN -> Res.string.roads_badge_unknown
            RoadReason.LANE -> Res.string.roads_badge_lane
        },
    )
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(PillShape)
            .background(bg, PillShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { if (onClick != null) role = Role.Button }
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (counted) {
            BtIcon(BtIcons.Check, null, size = 16.dp, tint = fg)
        } else {
            Canvas(Modifier.size(16.dp, 6.dp)) {
                val dash = if (reason == RoadReason.PRIVATE) floatArrayOf(4.dp.toPx(), 3.dp.toPx()) else floatArrayOf(0.01f, 4.dp.toPx())
                drawLine(fg, Offset(1.5.dp.toPx(), size.height / 2), Offset(size.width - 1.5.dp.toPx(), size.height / 2), 2.5.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(dash))
            }
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onClick != null) BtIcon(BtIcons.Info, null, size = 16.dp, tint = fg.copy(alpha = 0.7f))
    }
}

/** "Not counted" pill in the status-chip slot of an uncounted road: neutral, with the dotted not-counted glyph. */
@Composable
fun NotCountedChip(modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.status_not_counted)
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.heightIn(min = 28.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape)
            .clearAndSetSemantics { contentDescription = label }
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NotCountedGlyph(RoadReason.UNKNOWN, size = 14.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** Safety line (ux.md §3.2 CautionRow): tertiary container, warning glyph, never error red or a status color. */
@Composable
fun CautionRow(caution: RoadCaution, modifier: Modifier = Modifier) {
    val text = when (caution) {
        RoadCaution.StateRoute -> stringResource(Res.string.roads_caution_state_route)
        is RoadCaution.Fast -> stringResource(Res.string.roads_caution_fast, caution.mph)
    }
    Row(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BtIcon(Res.drawable.ic_warning, null, size = 18.dp, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

private const val FEET_PER_METRE = 3.28084

/** "28 m" / "90 ft", rounded to 5 m / 10 ft. */
fun shoreDistance(metres: Double, units: Units): String = when (units) {
    Units.MILES -> "${maxOf(10L, (metres * FEET_PER_METRE / 10).roundToLong() * 10)} ft"
    Units.KILOMETRES -> "${maxOf(5L, (metres / 5).roundToLong() * 5)} m"
}

/**
 * Road end at the shore (ux.md §3.2 RoadEndRow): 56dp row, decorative water glyph, "Road end at the water" /
 * "28 m to shore"; row tap → [onShow] (map), trailing 48dp Directions button → [onDirections].
 */
@Composable
fun RoadEndRow(end: RoadEnd, units: Units, onShow: () -> Unit, onDirections: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(Res.string.roads_detail_road_end)
    val supporting = stringResource(Res.string.roads_road_end_supporting, shoreDistance(end.shoreDistanceM, units))
    val dirA11y = stringResource(Res.string.roads_road_end_directions_a11y)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onShow)
                .semantics(mergeDescendants = true) { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
        ) {
            RoadEndGlyph(Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDirections, modifier = Modifier.size(48.dp)) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, PillShape), contentAlignment = Alignment.Center) {
                BtIcon(Res.drawable.ic_directions, dirA11y, size = 20.dp, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** Three short wavy water lines under a road stub (decorative). */
@Composable
fun RoadEndGlyph(modifier: Modifier = Modifier) {
    val water = BtTheme.colors.waterLine
    val road = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        drawLine(road, Offset(w / 2, 1.dp.toPx()), Offset(w / 2, h * 0.42f), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(road, 2.6.dp.toPx(), Offset(w / 2, h * 0.42f))
        for (i in 0 until 3) {
            val y = h * (0.62f + 0.14f * i)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.12f, y)
                quadraticTo(w * 0.31f, y - 2.dp.toPx(), w * 0.5f, y)
                quadraticTo(w * 0.69f, y + 2.dp.toPx(), w * 0.88f, y)
            }
            drawPath(path, water.copy(alpha = 1f - 0.25f * i), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Speed-limit sign (ux.md §3.2): rounded-rect outline with the number; decorative, the tile text carries meaning. */
@Composable
fun SpeedSignGlyph(mph: Int, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(24.dp, 28.dp).clearAndSetSemantics {}
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
            .border(1.5.dp, ink, MaterialTheme.shapes.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        Text("$mph", style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
    }
}

/**
 * Leading glyph of an uncounted road row (ux.md §4.3): a 40dp circle drawn with the map's pattern for why it is out
 * of the goal (private = muted dash, unconfirmed/lane = dots), in the not-counted color. No ring, no chip.
 */
@Composable
fun NotCountedGlyph(reason: RoadReason, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val c = BtTheme.colors.notCounted
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val sw = 2.5.dp.toPx()
        val r = this.size.minDimension / 2 - sw
        val circ = (2 * PI * r).toFloat()
        val effect = if (reason == RoadReason.PRIVATE) {
            val n = 12
            PathEffect.dashPathEffect(floatArrayOf(circ / n * 0.55f, circ / n * 0.45f))
        } else {
            PathEffect.dashPathEffect(floatArrayOf(0.01f, circ / 16))
        }
        drawCircle(c, r, style = Stroke(sw, cap = StrokeCap.Round, pathEffect = effect))
    }
}

/**
 * Sticky list group header (ux.md §4.2 CategoryHeader): 40dp, `surfaceContainerLow`, title + "2 of 32", heading
 * semantics. With [onToggle] it is a 48dp toggle with a chevron (the collapsed Not counted group).
 */
@Composable
fun CategoryHeader(
    title: String,
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    toggleLabel: String? = null,
    showCount: Boolean = true,
) {
    val a11y = stringResource(Res.string.roads_group_header_a11y, title, done, total)
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (onToggle != null) 48.dp else 40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onToggle != null) Modifier.clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle) else Modifier)
            .clearAndSetSemantics {
                contentDescription = if (showCount) a11y else title
                heading()
                if (expanded != null) stateDescription = toggleLabel.orEmpty()
                if (onToggle != null) role = Role.Button
            }
            .padding(horizontal = BtTheme.spacing.screenH, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            if (showCount) stringResource(Res.string.roads_group_count, done, total) else "$total",
            style = BtTheme.type.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded != null) {
            BtIcon(BtIcons.ExpandMore, null, Modifier.rotateIf(expanded), size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.rotateIf(up: Boolean): Modifier = if (up) this.rotate(180f) else this

/**
 * Survey-marker medallion (ux.md §5.3 MilestoneBadge), 64dp: Earned = primary container fill, primary outer ring,
 * engraved inner ring, mark in Fraunces; Next = dotted outline-variant track with a primary arc for [fraction];
 * Locked = dotted ring, faded mark. Milestones use `primary`, never status hues. [sweep] animates the earned ring
 * (0..1) for the results card stamp.
 */
@Composable
fun MilestoneMedallion(mark: String, state: MilestoneState, fraction: Double, modifier: Modifier = Modifier, size: Dp = 64.dp, sweep: Float = 1f) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val track = MaterialTheme.colorScheme.outlineVariant
    val low = MaterialTheme.colorScheme.surfaceContainerLow
    Box(modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val outer = 2.dp.toPx()
            val r = this.size.minDimension / 2 - outer
            val dotted = PathEffect.dashPathEffect(floatArrayOf(0.01f, 5.dp.toPx()))
            when (state) {
                MilestoneState.EARNED -> {
                    drawCircle(container, r)
                    drawArc(primary, -90f, 360f * sweep, false, Offset(outer, outer), Size(r * 2, r * 2), style = Stroke(outer * 0.9f + 0.6.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(onContainer.copy(alpha = 0.4f), r - 6.dp.toPx(), style = Stroke(1.dp.toPx()))
                }
                MilestoneState.NEXT -> {
                    drawCircle(low, r)
                    drawCircle(track, r, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dotted))
                    if (fraction > 0) drawArc(primary, -90f, (360 * fraction).toFloat(), false, Offset(outer, outer), Size(r * 2, r * 2), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                MilestoneState.LOCKED -> {
                    drawCircle(track, r, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dotted))
                }
            }
        }
        val color = when (state) {
            MilestoneState.EARNED -> onContainer
            MilestoneState.NEXT -> MaterialTheme.colorScheme.onSurface
            MilestoneState.LOCKED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
        val style = if (mark.length > 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
        Text(mark, style = style, color = color, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Medallion + 2-line title (+ "3.2 / 5" when next), 96dp wide, whole column clickable (ux.md §5.3). */
@Composable
fun MilestoneBadge(
    title: String,
    mark: String,
    state: MilestoneState,
    fraction: Double,
    progressText: String?,
    a11y: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(96.dp).heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .clearAndSetSemantics { contentDescription = a11y; if (onClick != null) role = Role.Button }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MilestoneMedallion(mark, state, fraction)
        Text(title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (progressText != null) Text(progressText, style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Road-grid empty-state glyph (ux.md §7, EmptyMotif.RoadGrid): a "#" of right-of-way lines, one edge dotted. */
@Composable
fun RoadGridGlyph(modifier: Modifier = Modifier) {
    val c = BtTheme.colors.waterLine
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        val a = 0.34f
        val b = 0.66f
        drawLine(c, Offset(w * a, h * 0.12f), Offset(w * a, h * 0.88f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.12f, h * a), Offset(w * 0.88f, h * a), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.12f, h * b), Offset(w * 0.88f, h * b), sw, StrokeCap.Round)
        drawLine(c, Offset(w * b, h * 0.12f), Offset(w * b, h * a), sw, StrokeCap.Round)
        drawLine(c, Offset(w * b, h * b), Offset(w * b, h * 0.88f), sw, StrokeCap.Round)
        // The centre cell's right edge: the one stretch not walked yet.
        drawLine(c, Offset(w * b, h * a), Offset(w * b, h * b), sw, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(0.01f, 5.dp.toPx())))
    }
}
