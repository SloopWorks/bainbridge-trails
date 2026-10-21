package com.bainbridgetrails.ui.play.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_show_on_map
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.play.components.ChallengeCard
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/** ChallengeSheet (ux.md § 3.16): the card in detail (titleLarge heading, not a button); `ch-krakenweek` gets Show on map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeSheet(card: ChallengeCardModel, onShowOnMap: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.lg).padding(bottom = BtTheme.spacing.xl)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
        ) {
            ChallengeCard(card, onClick = null, detail = true)
            if (onShowOnMap != null) PrimaryButton(stringResource(Res.string.play_show_on_map), onShowOnMap, Modifier.fillMaxWidth())
        }
    }
}
