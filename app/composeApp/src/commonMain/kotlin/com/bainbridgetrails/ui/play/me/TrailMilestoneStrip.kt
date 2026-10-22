package com.bainbridgetrails.ui.play.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_log_climb_what
import com.bainbridgetrails.resources.play_log_mile_what
import com.bainbridgetrails.resources.play_trail_ms_all
import com.bainbridgetrails.resources.play_trail_ms_earned_a11y
import com.bainbridgetrails.resources.play_trail_ms_high_title
import com.bainbridgetrails.resources.play_trail_ms_locked_a11y
import com.bainbridgetrails.resources.play_trail_ms_next_a11y
import com.bainbridgetrails.resources.play_trail_ms_parks_title
import com.bainbridgetrails.resources.play_trail_ms_progress
import com.bainbridgetrails.resources.play_trail_ms_title
import com.bainbridgetrails.ui.MilestoneState
import com.bainbridgetrails.ui.components.MilestoneBadge
import com.bainbridgetrails.ui.components.MilestoneColors
import com.bainbridgetrails.ui.components.SectionHeader
import com.bainbridgetrails.ui.components.SkeletonBlock
import com.bainbridgetrails.ui.play.components.TrailValue
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Me › Trail milestones strip (docs/play/trails/ux.md § 5.1): header "Trail milestones" with "All" → the sheet, then
 * always four badges (Climb, Trail miles, Parks finished, High-ground trails; [TrailMilestonesLogic.strip]). [model]
 * null = the derived tables are still loading: four skeleton badges. Trails + Play on only (the caller guards on
 * `!LocalGoal.current.isRoads`; the model is already null on roads).
 */
@Composable
fun TrailMilestoneStrip(model: TrailMilestonesModel?, units: com.bainbridgetrails.data.Units, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val goal = LocalGoal.current
    Column(modifier) {
        SectionHeader(
            stringResource(goal.s(Res.string.play_trail_ms_title)),
            Modifier.padding(horizontal = BtTheme.spacing.screenH),
            action = stringResource(goal.s(Res.string.play_trail_ms_all)) to onAll,
        )
        if (model == null) {
            Row(
                Modifier.padding(horizontal = BtTheme.spacing.screenH),
                horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
            ) { repeat(4) { SkeletonBadge() } }
        } else {
            val badges = remember(model, units) { TrailMilestonesLogic.strip(model, units) }
            LazyRow(
                contentPadding = PaddingValues(horizontal = BtTheme.spacing.screenH),
                horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
            ) {
                items(badges, key = { it.ladder.name }) { b -> TrailBadgeView(b, onAll) }
            }
        }
    }
}

/** The Play medallion colors (ux § 5.1): tertiary-container EARNED, sea-glass NEXT arc. */
@Composable
internal fun rememberPlayMilestoneColors(): MilestoneColors {
    val scheme = MaterialTheme.colorScheme
    val play = BtTheme.colors.play
    return remember(scheme, play) { MilestoneColors.play(scheme, play) }
}

/** One trails badge (strip or sheet): title, "so far / step" when NEXT, one merged a11y node with spoken units. */
@Composable
internal fun TrailBadgeView(b: TrailBadge, onClick: (() -> Unit)?, colors: MilestoneColors = rememberPlayMilestoneColors()) {
    val goal = LocalGoal.current
    val title = badgeTitle(b, spoken = false)
    val spokenTitle = badgeTitle(b, spoken = true)
    val progress = b.progress
    val a11y = when (b.state) {
        MilestoneState.EARNED -> stringResource(goal.s(Res.string.play_trail_ms_earned_a11y), spokenTitle)
        MilestoneState.LOCKED -> stringResource(goal.s(Res.string.play_trail_ms_locked_a11y), spokenTitle)
        MilestoneState.NEXT -> stringResource(
            goal.s(Res.string.play_trail_ms_next_a11y),
            spokenTitle,
            progress?.first.orEmpty(),
            b.value?.let { spokenValue(it) } ?: progress?.second.orEmpty(),
        )
    }
    MilestoneBadge(
        title = title,
        mark = b.mark,
        state = b.state,
        fraction = b.fraction,
        progressText = progress?.let { stringResource(goal.s(Res.string.play_trail_ms_progress), it.first, it.second) },
        a11y = a11y,
        onClick = onClick,
        colors = colors,
    )
}

/** "820 ft climbed" / "10 mi of trail walked" / "Parks finished" / "High-ground trails"; [spoken] = units in full. */
@Composable
private fun badgeTitle(b: TrailBadge, spoken: Boolean): String {
    val goal = LocalGoal.current
    val value = b.value?.let { if (spoken) spokenValue(it) else it.text }.orEmpty()
    return when (b.ladder) {
        TrailLadder.CLIMB -> stringResource(goal.s(Res.string.play_log_climb_what), value)
        TrailLadder.MILES -> stringResource(goal.s(Res.string.play_log_mile_what), value)
        TrailLadder.PARKS -> stringResource(goal.s(Res.string.play_trail_ms_parks_title))
        TrailLadder.HIGH -> stringResource(goal.s(Res.string.play_trail_ms_high_title))
    }
}

/** A value with its unit spoken in full ("820 feet"; ux § 1): TalkBack / VoiceOver do not expand "ft" reliably. */
@Composable
internal fun spokenValue(v: TrailValue): String = pluralStringResource(LocalGoal.current.p(v.unit.spoken), v.pluralQuantity, v.number)

/** Loading badge (ux § 5.1): a 64dp circle and a 72×12dp line; [SkeletonBlock] drops its shimmer under reduced motion. */
@Composable
private fun SkeletonBadge() {
    Column(
        Modifier.width(96.dp).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SkeletonBlock(64.dp, 64.dp, shape = CircleShape)
        SkeletonBlock(72.dp, 12.dp)
    }
}
