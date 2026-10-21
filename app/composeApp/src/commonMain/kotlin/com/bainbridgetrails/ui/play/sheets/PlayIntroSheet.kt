package com.bainbridgetrails.ui.play.sheets

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_not_now
import com.bainbridgetrails.resources.play_intro_1
import com.bainbridgetrails.resources.play_intro_2
import com.bainbridgetrails.resources.play_intro_3
import com.bainbridgetrails.resources.play_intro_start
import com.bainbridgetrails.resources.play_intro_title
import org.jetbrains.compose.resources.stringResource

/** The intro content (ux.md § 3.2): title, three bullets (pebble, △, chart), Start. Shared with the offer's morph. */
@Composable
fun PlayIntroContent(starting: Boolean, onStart: () -> Unit, onDismiss: () -> Unit) {
    val goal = LocalGoal.current
    OptInCard(
        title = stringResource(Res.string.play_intro_title),
        body = null,
        bullets = listOf(
            FxGlyph.SeaGlass to stringResource(goal.s(Res.string.play_intro_1)),
            FxGlyph.Marks to stringResource(Res.string.play_intro_2),
            FxGlyph.Pennant to stringResource(Res.string.play_intro_3),
        ),
        primary = stringResource(Res.string.play_intro_start),
        onPrimary = onStart,
        secondary = stringResource(Res.string.common_not_now),
        onSecondary = onDismiss,
        loading = starting,
    )
}

/**
 * PlayIntroSheet (PF1; ux.md § 3.2): modal, `surfaceContainerLow`. Scrim, swipe, back or Not now = [onDismiss]
 * (Play stays off, PF1-3); Start = [onStart] (the caller turns Play on and lands on the Map: `onPlayStarted`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayIntroSheet(starting: Boolean, onStart: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { if (!starting) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { PlayIntroContent(starting, onStart, onDismiss) }
}
