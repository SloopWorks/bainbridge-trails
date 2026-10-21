package com.bainbridgetrails.ui.play.sheets

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_not_now
import com.bainbridgetrails.resources.play_offer_accept
import com.bainbridgetrails.resources.play_offer_body
import com.bainbridgetrails.resources.play_offer_title
import com.bainbridgetrails.ui.theme.BtDuration
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/**
 * The one-time offer (PF2; ux.md § 3.3): offer content; Accept crossfades (medium1) to the intro content in the same
 * sheet; Start = [onStart]. Scrim, swipe, back or Not now = [onDismiss] (dismissed forever, PF2-3). Shown by the shell
 * after the first finished walk's results close (L2-10 owns the trigger).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayOfferSheet(starting: Boolean, onStart: () -> Unit, onDismiss: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    val reduced = BtTheme.motion.reduced
    ModalBottomSheet(
        onDismissRequest = { if (!starting) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val fadeMs = if (reduced) BtDuration.short3 else BtDuration.medium1
        Crossfade(accepted, animationSpec = tween(fadeMs)) { intro ->
            if (intro) {
                // The crossfade drops screen-reader focus with the offer card: move it to the intro title (§ 5.1).
                val introTitle = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    delay(fadeMs.toLong())
                    runCatching { introTitle.requestFocus() }
                }
                PlayIntroContent(starting, onStart, onDismiss, titleFocus = introTitle)
            } else {
                OptInCard(
                    title = stringResource(Res.string.play_offer_title),
                    body = stringResource(Res.string.play_offer_body),
                    primary = stringResource(Res.string.play_offer_accept),
                    onPrimary = { accepted = true },
                    secondary = stringResource(Res.string.common_not_now),
                    onSecondary = onDismiss,
                )
            }
        }
    }
}
