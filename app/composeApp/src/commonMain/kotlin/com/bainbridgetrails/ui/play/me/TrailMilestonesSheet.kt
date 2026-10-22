package com.bainbridgetrails.ui.play.me

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_rank_earned
import com.bainbridgetrails.resources.play_trail_ms_climb
import com.bainbridgetrails.resources.play_trail_ms_earned_a11y
import com.bainbridgetrails.resources.play_trail_ms_high
import com.bainbridgetrails.resources.play_trail_ms_high_line
import com.bainbridgetrails.resources.play_trail_ms_high_row_a11y
import com.bainbridgetrails.resources.play_trail_ms_miles
import com.bainbridgetrails.resources.play_trail_ms_park_finished
import com.bainbridgetrails.resources.play_trail_ms_park_not_started
import com.bainbridgetrails.resources.play_trail_ms_park_started
import com.bainbridgetrails.resources.play_trail_ms_parks
import com.bainbridgetrails.resources.play_trail_ms_parks_line
import com.bainbridgetrails.resources.play_trail_ms_so_far
import com.bainbridgetrails.resources.play_trail_ms_title
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.play.components.TrailValue
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/** Not-yet tiles draw their glyph at 60 % (ux § 5.2): earned = word + glyph alpha, never hue alone. */
private const val NOT_YET_ALPHA = 0.6f

/**
 * "Trail milestones" sheet (docs/play/trails/ux.md § 5.2; the roads `MilestonesSheet` shell): Climb (all 6 steps),
 * Trail miles (earned + next + 2 locked), Parks (one row per Play park → [onPark], the Trails list filtered to it) and
 * High ground (one row per eligible trail, steepest first → [onTrail], TrailDetail). The caller closes the sheet before
 * navigating. Trails + Play on only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrailMilestonesSheet(
    model: TrailMilestonesModel,
    units: Units,
    onPark: (String) -> Unit,
    onTrail: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val goal = LocalGoal.current
    val colors = rememberPlayMilestoneColors()
    val climb = remember(model, units) { TrailMilestonesLogic.climbLadder(model, units) }
    val miles = remember(model, units) { TrailMilestonesLogic.mileLadder(model, units) }
    val climbSoFar = remember(model, units) { TrailMilestonesLogic.climbSoFar(model, units) }
    val milesSoFar = remember(model, units) { TrailMilestonesLogic.milesSoFar(model, units) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = BtTheme.spacing.screenH)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)).padding(bottom = BtTheme.spacing.xl),
        ) {
            Text(
                stringResource(goal.s(Res.string.play_trail_ms_title)),
                Modifier.semantics { heading() }.padding(bottom = BtTheme.spacing.sm),
                style = MaterialTheme.typography.headlineSmall,
            )

            SectionHeader(stringResource(goal.s(Res.string.play_trail_ms_climb)))
            SoFarLine(climbSoFar)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                climb.forEach { key(it.key) { TrailBadgeView(it, null, colors) } }
            }
            Spacer(Modifier.height(BtTheme.spacing.md))

            SectionHeader(stringResource(goal.s(Res.string.play_trail_ms_miles)))
            SoFarLine(milesSoFar)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
                miles.forEach { key(it.key) { TrailBadgeView(it, null, colors) } }
            }
            Spacer(Modifier.height(BtTheme.spacing.md))

            SectionHeader(stringResource(goal.s(Res.string.play_trail_ms_parks)))
            Line(stringResource(goal.s(Res.string.play_trail_ms_parks_line), model.parksFinished, model.parks.size, model.parksStarted))
            model.parks.forEach { row -> key(row.park) { ParkRow(row, onPark) } }
            Spacer(Modifier.height(BtTheme.spacing.md))

            SectionHeader(stringResource(goal.s(Res.string.play_trail_ms_high)))
            Line(stringResource(goal.s(Res.string.play_trail_ms_high_line), model.highEarned, model.high.size))
            model.high.forEach { row -> key(row.trailId) { HighGroundRow(row, units, onTrail) } }
        }
    }
}

/** "So far: 1,207 ft" (`bodyMedium` `onSurfaceVariant`), spoken with the unit in full. */
@Composable
private fun SoFarLine(value: TrailValue) {
    val spoken = stringResource(LocalGoal.current.s(Res.string.play_trail_ms_so_far), spokenValue(value))
    Line(stringResource(LocalGoal.current.s(Res.string.play_trail_ms_so_far), value.text), Modifier.clearAndSetSemantics { contentDescription = spoken })
}

@Composable
private fun Line(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(bottom = BtTheme.spacing.sm),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A 32dp tile with a Play glyph in `play.ink`, at 60 % when not yet ([dim]). */
@Composable
private fun GlyphTile(glyph: FxGlyph, dim: Boolean) {
    Box(
        Modifier.size(BtTheme.spacing.playArtTileLog).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val ink = BtTheme.colors.play.ink
        FxGlyphImage(glyph, size = 20.dp, ink = if (dim) ink.copy(alpha = NOT_YET_ALPHA) else ink)
    }
}

/** A Play park: name, Finished / Started / Not yet, chevron; one merged button ("Gazzam Lake Preserve, Started"). */
@Composable
private fun ParkRow(row: TrailParkRow, onPark: (String) -> Unit) {
    val goal = LocalGoal.current
    val (state, color) = when (row.state) {
        ParkState.FINISHED -> stringResource(goal.s(Res.string.play_trail_ms_park_finished)) to MaterialTheme.colorScheme.onSurface
        ParkState.STARTED -> stringResource(goal.s(Res.string.play_trail_ms_park_started)) to MaterialTheme.colorScheme.onSurfaceVariant
        ParkState.NOT_STARTED -> stringResource(goal.s(Res.string.play_trail_ms_park_not_started)) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .clickable { onPark(row.park) }
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(vertical = BtTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        GlyphTile(FxGlyph.Marks, dim = row.state == ParkState.NOT_STARTED)
        Text(row.park, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(state, style = MaterialTheme.typography.labelMedium, color = color)
        BtIcon(BtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A high-ground trail: Summit tile, name, its climb (nearest), "Earned" or nothing ("Peter's Trail, 302 feet of climb, earned"). */
@Composable
private fun HighGroundRow(row: TrailHighRow, units: Units, onTrail: (String) -> Unit) {
    val goal = LocalGoal.current
    val climb = remember(row, units) { TrailMilestonesLogic.highClimb(row, units) }
    val base = stringResource(goal.s(Res.string.play_trail_ms_high_row_a11y), row.name, spokenValue(climb))
    val a11y = if (row.earned) stringResource(goal.s(Res.string.play_trail_ms_earned_a11y), base) else base
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .clickable { onTrail(row.trailId) }
            .clearAndSetSemantics { contentDescription = a11y; role = Role.Button }
            .padding(vertical = BtTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        GlyphTile(FxGlyph.Summit, dim = !row.earned)
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(climb.text, style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.earned) Text(stringResource(goal.s(Res.string.play_rank_earned)), style = MaterialTheme.typography.labelMedium)
    }
}
