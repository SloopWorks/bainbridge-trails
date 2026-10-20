package com.bainbridgetrails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.data.TrailData
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.map.RoadFacts
import com.bainbridgetrails.map.countedLengthMi
import com.bainbridgetrails.map.formatCovered
import com.bainbridgetrails.map.formatElevation
import com.bainbridgetrails.map.formatTrailLength
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.detail_community_title
import com.bainbridgetrails.resources.detail_covered_value
import com.bainbridgetrails.resources.detail_length
import com.bainbridgetrails.resources.detail_partly_counted
import com.bainbridgetrails.resources.detail_walked
import com.bainbridgetrails.resources.ic_layers
import com.bainbridgetrails.resources.ic_straighten
import com.bainbridgetrails.resources.ic_trending_up
import com.bainbridgetrails.resources.roads_badge_counts
import com.bainbridgetrails.resources.roads_badge_sheet_counts
import com.bainbridgetrails.resources.roads_detail_climb
import com.bainbridgetrails.resources.roads_detail_flat
import com.bainbridgetrails.resources.roads_detail_public_length
import com.bainbridgetrails.resources.roads_detail_road_ends
import com.bainbridgetrails.resources.roads_detail_speed
import com.bainbridgetrails.resources.roads_detail_speed_value
import com.bainbridgetrails.resources.roads_detail_surface
import com.bainbridgetrails.resources.roads_speed_a11y
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.CautionRow
import com.bainbridgetrails.ui.components.CommunityCard
import com.bainbridgetrails.ui.components.CommunityState
import com.bainbridgetrails.ui.components.NotCountedChip
import com.bainbridgetrails.ui.components.OwnershipBadge
import com.bainbridgetrails.ui.components.ProgressBar
import com.bainbridgetrails.ui.components.ProgressRing
import com.bainbridgetrails.ui.components.RoadEndRow
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SpeedSignGlyph
import com.bainbridgetrails.ui.components.StatTile
import com.bainbridgetrails.ui.components.StatTileRow
import com.bainbridgetrails.ui.components.StatusChip
import com.bainbridgetrails.ui.components.roadCategoryLabel
import com.bainbridgetrails.ui.components.roadOwnerLabel
import com.bainbridgetrails.ui.components.roadReasonText
import com.bainbridgetrails.ui.components.roadSurfaceLabel
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/** Climb under this is "Flat" (product.md §4 step 5, RP-15). */
private const val FLAT_M = 3.0

/** One facts-grid tile (docs/roads/ux.md §3.2): tiles 1–4 flow two per row, an odd last one spans both columns. */
private data class RoadTile(
    val value: String,
    val label: String,
    val icon: DrawableResource? = null,
    val leading: (@Composable () -> Unit)? = null,
    val a11y: String? = null,
)

/**
 * The road blocks of the detail screen (docs/roads/product.md §4 order, ux.md §3 visuals) under the name: "Type ·
 * Owner", the counts badge, progress (counted roads) or the reason card (uncounted), the safety line, the facts grid
 * (null fields hidden, never "—"), road ends with Directions, and Community for counted roads.
 */
@Composable
internal fun RoadDetailBody(
    data: TrailData,
    roadId: String,
    road: RoadFacts,
    fraction: Double,
    status: TrailStatus,
    units: Units,
    community: CommunityState,
    onRetryCommunity: () -> Unit,
    onDirections: (lat: Double, lon: Double, label: String) -> Unit,
    onShowOnMap: () -> Unit,
) {
    val goal = LocalGoal.current
    val reason = road.notCountedReason
    val counted = reason == null
    val countedMi = remember(roadId, data) { data.dataset.countedLengthMi(roadId) }
    val fullMi = remember(roadId, data) { data.dataset.trails.firstOrNull { it.id == roadId }?.computedLengthMi ?: countedMi }
    val partlyPublic = counted && fullMi - countedMi > 0.01
    var explain by remember { mutableStateOf(false) }

    val sub = listOfNotNull(roadCategoryLabel(road.category), road.owner?.let { roadOwnerLabel(it) }).joinToString(" · ")
    Text(sub, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OwnershipBadge(reason, onClick = { explain = true }, modifier = Modifier.padding(top = 12.dp))

    if (counted) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(status)
                    if (status == TrailStatus.COMPLETE) BtIcon(BtIcons.Verified, null, size = 20.dp, tint = BtTheme.colors.complete.accent)
                }
                val (covered, total) = formatCovered(fraction, countedMi, units)
                Text(
                    stringResource(Res.string.detail_walked, stringResource(Res.string.detail_covered_value, covered, total)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProgressRing(fraction, status, size = 72.dp, stroke = 6.dp)
        }
        ProgressBar(fraction, status, Modifier.padding(top = 12.dp))
        if (partlyPublic) {
            InfoCard(
                stringResource(goal.s(Res.string.detail_partly_counted)) + " " +
                    stringResource(Res.string.roads_detail_public_length, formatTrailLength(countedMi, units).substringBefore(' '), formatTrailLength(fullMi, units)),
                Modifier.padding(top = 12.dp),
            )
        }
    } else {
        NotCountedChip(Modifier.padding(top = 16.dp))
        InfoCard(roadReasonText(reason!!), Modifier.padding(top = 8.dp))
    }

    road.caution?.let { CautionRow(it, Modifier.padding(top = 12.dp)) }

    // Facts grid.
    val tiles = buildList {
        add(RoadTile(formatTrailLength(if (counted) countedMi else fullMi, units), stringResource(Res.string.detail_length), Res.drawable.ic_straighten))
        val climb = road.climbTotalM
        if (climb != null) {
            add(RoadTile(if (climb < FLAT_M) stringResource(Res.string.roads_detail_flat) else formatElevation(climb, units), stringResource(Res.string.roads_detail_climb), Res.drawable.ic_trending_up))
        }
        road.speedMph?.let { mph ->
            add(RoadTile(stringResource(Res.string.roads_detail_speed_value, mph), stringResource(Res.string.roads_detail_speed), leading = { SpeedSignGlyph(mph) }, a11y = stringResource(Res.string.roads_speed_a11y, mph)))
        }
        roadSurfaceLabel(road.surface)?.let { add(RoadTile(it, stringResource(Res.string.roads_detail_surface), Res.drawable.ic_layers)) }
    }
    Spacer(Modifier.height(BtTheme.spacing.sectionGap))
    val oneColumn = LocalDensity.current.fontScale >= 1.5f
    Column(verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
        val rows = if (oneColumn) tiles.map { listOf(it) } else tiles.chunked(2)
        for (row in rows) StatTileRow {
            for (t in row) StatTile(t.value, t.label, Modifier.weight(1f), icon = t.icon, leading = t.leading, a11y = t.a11y?.let { it })
        }
    }

    if (road.roadEnds.isNotEmpty()) {
        Spacer(Modifier.height(BtTheme.spacing.lg))
        SectionHeader(stringResource(Res.string.roads_detail_road_ends, road.roadEnds.size))
        val label = data.dataset.trails.firstOrNull { it.id == roadId }?.name.orEmpty()
        for (end in road.roadEnds) RoadEndRow(end, units, onShow = onShowOnMap, onDirections = { onDirections(end.lat, end.lon, label) })
    }

    if (counted) {
        Spacer(Modifier.height(BtTheme.spacing.lg))
        SectionHeader(stringResource(Res.string.detail_community_title))
        CommunityCard(community, onRetry = onRetryCommunity)
    }

    if (explain) CountsSheet(if (reason == null) null else roadReasonText(reason)) { explain = false }
}

@Composable
private fun InfoCard(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BtIcon(BtIcons.Info, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Why a road is (or isn't) in the goal: the badge's explainer (ux.md §3.2). [reason] null = it counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountsSheet(reason: String?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.xl).padding(bottom = BtTheme.spacing.xxl)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
        ) {
            if (reason == null) {
                Text(stringResource(Res.string.roads_badge_counts), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.roads_badge_sheet_counts), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(reason, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
