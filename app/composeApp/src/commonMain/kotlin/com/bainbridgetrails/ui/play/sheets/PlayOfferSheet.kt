package com.bainbridgetrails.ui.play.sheets

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
        Crossfade(accepted, animationSpec = tween(if (reduced) BtDuration.short3 else BtDuration.medium1)) { intro ->
            if (intro) {
                PlayIntroContent(starting, onStart, onDismiss)
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
