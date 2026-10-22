package com.bainbridgetrails.ui.play.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.play.config.ChallengeMetric
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_challenge_band
import com.bainbridgetrails.resources.play_challenge_band_middle
import com.bainbridgetrails.resources.play_challenge_band_starting
import com.bainbridgetrails.resources.play_challenge_band_top
import com.bainbridgetrails.resources.play_challenge_done
import com.bainbridgetrails.resources.play_challenge_ended
import com.bainbridgetrails.resources.play_challenge_island
import com.bainbridgetrails.resources.play_challenge_progress
import com.bainbridgetrails.resources.play_challenge_progress_climb
import com.bainbridgetrails.resources.play_challenge_progress_parks
import com.bainbridgetrails.resources.play_challenge_solo
import com.bainbridgetrails.resources.play_challenge_sweep_body
import com.bainbridgetrails.resources.play_challenge_sweep_title
import com.bainbridgetrails.resources.play_challenge_window
import com.bainbridgetrails.resources.play_kraken_island_progress
import com.bainbridgetrails.resources.play_log_line
import com.bainbridgetrails.resources.play_reward
import com.bainbridgetrails.ui.components.BtIcon
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.MetaChip
import com.bainbridgetrails.ui.components.PlayBar
import com.bainbridgetrails.ui.components.RewardChip
import com.bainbridgetrails.ui.play.screens.ChallengeCardModel
import com.bainbridgetrails.ui.play.screens.ChallengeCardText
import com.bainbridgetrails.ui.play.screens.ChallengeCopy
import com.bainbridgetrails.ui.play.screens.ChallengeState
import com.bainbridgetrails.ui.play.screens.ChallengesLogic
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The card strings from resources ([ChallengesLogic.text] builds the lines from them). */
@Composable
fun rememberChallengeCopy(): ChallengeCopy = ChallengeCopy(
    window = stringResource(Res.string.play_challenge_window, "%1\$s", "%2\$s"),
    progress = stringResource(Res.string.play_challenge_progress, "%1\$s", "%2\$s"),
    // Placeholders pass through as text: ChallengesLogic fills them.
    islandProgress = stringResource(Res.string.play_kraken_island_progress, "%1\$d", "%2\$d"),
    band = stringResource(Res.string.play_challenge_band, "%1\$s", "%2\$d"),
    bandTop = stringResource(Res.string.play_challenge_band_top),
    bandMiddle = stringResource(Res.string.play_challenge_band_middle),
    bandStarting = stringResource(Res.string.play_challenge_band_starting),
)

/**
 * [card]'s copy: [rememberChallengeCopy] plus, for a trails `climb` / `parks` card (trails ux § 7), its progress template
 * (`play_challenge_progress_parks` resolved with the target as quantity), through the goal like every trails key.
 */
@Composable
private fun rememberCardCopy(card: ChallengeCardModel): ChallengeCopy {
    val base = rememberChallengeCopy()
    val goal = LocalGoal.current
    return when (card.metric) {
        ChallengeMetric.climb -> base.copy(progressClimb = stringResource(goal.s(Res.string.play_challenge_progress_climb), "%1\$s", "%2\$s"))
        ChallengeMetric.parks -> base.copy(
            progressParks = pluralStringResource(goal.p(Res.plurals.play_challenge_progress_parks), card.target ?: 0, "%1\$d", "%2\$d"),
        )
        else -> base
    }
}

/** A card's title and body: `ch-sweep` uses the product keys (goal twins), others the pack copy. */
@Composable
fun challengeTitle(card: ChallengeCardModel): String =
    if (card.itemId == SWEEP) stringResource(Res.string.play_challenge_sweep_title) else card.title

@Composable
fun challengeBody(card: ChallengeCardModel): String =
    if (card.itemId == SWEEP && card.target != null) stringResource(LocalGoal.current.s(Res.string.play_challenge_sweep_body), card.target) else card.body

private const val SWEEP = "ch-sweep"

/**
 * ChallengeCard (ux.md § 2.5): `surfaceContainerLow`, medium shape, padding 16, min 120dp; kind chip + window (days,
 * never a countdown); title; goal copy; Play bar + progress text; reward + chevron. The whole card is one button.
 * Done: `seaGlassWash` background, Done chip, reward `+n` in `onSurface`. Ended: 60 % art, `play_challenge_ended`
 * replaces the window, no reward. Upcoming: no progress row. Island: shared progress and `{band, count}` only.
 * [onClick] null = the ChallengeSheet's detail: not a button, no chevron, its own nodes with the `titleLarge` title as
 * a heading ([detail]).
 */
@Composable
fun ChallengeCard(card: ChallengeCardModel, onClick: (() -> Unit)?, modifier: Modifier = Modifier, detail: Boolean = false) {
    // A climb target is spoken with its unit word ("492 feet"), never "ft" (trails ux § 1, § 7).
    val spokenTarget = card.climbTarget?.let { spokenValue(it) }
    val text: ChallengeCardText = ChallengesLogic.text(card, rememberCardCopy(card), spokenTarget)
    val title = challengeTitle(card)
    val body = challengeBody(card)
    val kind = stringResource(if (card.islandScope) Res.string.play_challenge_island else Res.string.play_challenge_solo)
    val done = card.state == ChallengeState.Done
    val ended = card.state == ChallengeState.Ended
    val window = if (ended) stringResource(Res.string.play_challenge_ended) else text.window
    val reward = card.reward?.let { PlayFormat.marks(it) }
    val rewardSpoken = reward?.let { if (done) stringResource(Res.string.play_log_line, it) else stringResource(Res.string.play_reward, it) }
    val doneLabel = stringResource(Res.string.play_challenge_done)
    val spoken = listOfNotNull(title, kind, window, text.progressSpoken, text.band, doneLabel.takeIf { done }, rewardSpoken).joinToString(". ")
    val motion = BtTheme.motion
    val bg by animateColorAsState(
        if (done) BtTheme.colors.play.seaGlassWash else MaterialTheme.colorScheme.surfaceContainerLow,
        motion.tweenOrSnap(BtDuration.medium1),
    )
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick).clearAndSetSemantics {
                        contentDescription = spoken
                        role = Role.Button
                    }
                } else {
                    Modifier
                },
            )
            .padding(BtTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm)) {
            FxGlyphImage(
                if (card.islandScope) FxGlyph.KrakenEye else FxGlyph.Pennant,
                Modifier.alpha(if (ended) 0.6f else 1f),
                size = 20.dp,
            )
            MetaChip(kind)
            if (done) MetaChip(doneLabel, icon = BtIcons.Check, iconTint = MaterialTheme.colorScheme.onSurface)
            Text(
                window,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        Text(
            title,
            if (detail) Modifier.semantics { heading() } else Modifier,
            style = if (detail) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val p = card.progress
        val t = card.target
        if (text.progress != null && p != null && t != null && t > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
                PlayBar(p.toFloat() / t, text.progressSpoken ?: text.progress, Modifier.weight(1f))
                if (!card.islandScope) Text(text.progress, style = BtTheme.type.numeric, color = MaterialTheme.colorScheme.onSurface)
            }
            if (card.islandScope) Text(text.progress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (text.band != null) Text(text.band, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (reward != null) RewardChip(reward, claimed = done) else Spacer(Modifier.heightIn(min = 32.dp))
            Spacer(Modifier.weight(1f))
            if (onClick != null) BtIcon(BtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
