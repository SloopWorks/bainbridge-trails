package com.bainbridgetrails.ui.play.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.TrailChallengeStatus
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_challenges_chip
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.pluralStringResource

/**
 * The Play row's compaction (ux.md §2.1 table "Compact", §3.5.1, §5.3). Row 2 may use the screen width − 32 (insets)
 * − 56dp (the trophy / locate column stays clear). By measurement: 1st step the Challenges chip compacts (count only),
 * 2nd step (still too wide) the Play chip drops its rank title. At font scale ≥ [FORCE_COMPACT_FONT_SCALE] both are
 * compact whatever the width; at ≥ [WRAP_FONT_SCALE] the Challenges chip also wraps under the Play chip. Pure.
 */
object PlayChipRow {
    const val FORCE_COMPACT_FONT_SCALE = 1.3f
    const val WRAP_FONT_SCALE = 1.5f

    /** Gap between the two chips (and between wrapped rows). */
    const val GAP_DP = 8f

    /** Start + end screen insets (2 × 16dp) and the right column kept clear (56dp). */
    const val RESERVED_DP = 32f + 56f

    data class Layout(val challengesCompact: Boolean, val playCompact: Boolean, val wrap: Boolean)

    /** Row 2's width on a [screenDp]-wide window. */
    fun rowWidthDp(screenDp: Float): Float = screenDp - RESERVED_DP

    /**
     * The first step that fits [availableDp] (row 2's width), from each chip's measured full / compact width;
     * [challengesFullDp] / [challengesCompactDp] null = no Challenges chip. Nothing fits even compact → wrap.
     */
    fun layoutFor(
        availableDp: Float,
        playFullDp: Float,
        playCompactDp: Float,
        challengesFullDp: Float?,
        challengesCompactDp: Float?,
        fontScale: Float,
    ): Layout {
        val hasChallenges = challengesFullDp != null && challengesCompactDp != null
        fun fits(play: Float, challenges: Float?) = play + (challenges?.let { GAP_DP + it } ?: 0f) <= availableDp
        if (fontScale < FORCE_COMPACT_FONT_SCALE) {
            if (fits(playFullDp, challengesFullDp)) return Layout(challengesCompact = false, playCompact = false, wrap = false)
            if (hasChallenges && fits(playFullDp, challengesCompactDp)) return Layout(challengesCompact = true, playCompact = false, wrap = false)
        }
        val wrap = hasChallenges && (fontScale >= WRAP_FONT_SCALE || !fits(playCompactDp, challengesCompactDp))
        return Layout(challengesCompact = hasChallenges, playCompact = true, wrap = wrap)
    }
}

/**
 * Row 2's chips laid out per [PlayChipRow.layoutFor]: measures [playChip] and [challengesChip] (null = none) full and
 * compact, unplaced and outside semantics, against this layout's max width, then composes [content] with the chosen
 * [PlayChipRow.Layout]. [fontScale] forces the compact / wrap steps (§5.3).
 */
@Composable
fun PlayChipRowLayout(
    fontScale: Float,
    playChip: @Composable (compact: Boolean) -> Unit,
    challengesChip: (@Composable (compact: Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (PlayChipRow.Layout) -> Unit,
) {
    val contentNow by rememberUpdatedState(content)
    val playNow by rememberUpdatedState(playChip)
    val challengesNow by rememberUpdatedState(challengesChip)
    // Stable slot lambdas: a remeasure (the chip's number rolling) never recomposes the probes or the row itself.
    val probes = remember {
        fun probe(chip: @Composable () -> Unit): @Composable () -> Unit = { Box(Modifier.clearAndSetSemantics { }) { chip() } }
        listOf(
            probe { playNow(false) },
            probe { playNow(true) },
            probe { challengesNow?.invoke(false) },
            probe { challengesNow?.invoke(true) },
        )
    }
    val slots = remember { HashMap<PlayChipRow.Layout, @Composable () -> Unit>() }
    val hasChallenges = challengesChip != null
    SubcomposeLayout(modifier) { constraints ->
        val loose = Constraints()
        fun width(i: Int): Float = subcompose(PROBE_SLOTS[i], probes[i]).maxOfOrNull { it.measure(loose).width }?.toDp()?.value ?: 0f
        val step = PlayChipRow.layoutFor(
            availableDp = if (constraints.hasBoundedWidth) constraints.maxWidth.toDp().value else Float.MAX_VALUE,
            playFullDp = width(0),
            playCompactDp = width(1),
            challengesFullDp = if (hasChallenges) width(2) else null,
            challengesCompactDp = if (hasChallenges) width(3) else null,
            fontScale = fontScale,
        )
        val row = slots.getOrPut(step) { { contentNow(step) } }
        val placeables = subcompose("row", row).map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w.coerceIn(constraints.minWidth, constraints.maxWidth), h.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

private val PROBE_SLOTS = listOf("play-full", "play-compact", "challenges-full", "challenges-compact")

/** The ChallengesChip's count (ux.md §2.1; trails ux § 7). */
object ChallengesChipLogic {
    /**
     * This week's challenges ([PlayMarkerLogic.challengesThisWeek]) without the trails `climb` / `parks` occurrences
     * whose status in [trail] is hidden (nothing left for this player, plan D5). Roads: [trail] is empty.
     */
    fun count(agenda: PlayAgenda, trail: Map<String, TrailChallengeStatus>): Int =
        PlayMarkerLogic.challengesThisWeek(agenda.copy(active = agenda.active.filter { trail[it.occurrence.key]?.hidden != true }))
}

/** ChallengesChip (ux.md §2.1): pennant + "2 challenges this week" ([compact]: the count only; a11y keeps the phrase). */
@Composable
fun ChallengesChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = pluralStringResource(Res.plurals.play_challenges_chip, count, count)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            // Clickable before the visual inset: the 48dp touch target is also the semantic bounds (ux.md §2.1).
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick { onClick(); true } }
            .padding(vertical = 4.dp)
            .shadow(3.dp, PillShape)
            .background(BtTheme.colors.mapChrome, PillShape)
            .clip(PillShape)
            .indication(interaction, ripple())
            .heightIn(min = 40.dp)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FxGlyphImage(FxGlyph.Pennant, size = 18.dp, ink = BtTheme.colors.play.ink)
        Text(if (compact) "$count" else label, style = MaterialTheme.typography.labelLarge, color = BtTheme.colors.onMapChrome, maxLines = 1)
    }
}
