package com.bainbridgetrails.ui.play.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.data.LocalGoal
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_about_claim
import com.bainbridgetrails.resources.play_about_marks
import com.bainbridgetrails.resources.play_about_privacy
import com.bainbridgetrails.resources.play_about_safety
import com.bainbridgetrails.resources.play_about_title
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/** "How Play works" (ux.md § 3.4): scrollable modal, title + four short sections with 24dp glyphs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayAboutSheet(onDismiss: () -> Unit) {
    val goal = LocalGoal.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = BtTheme.spacing.xl).padding(bottom = BtTheme.spacing.xxl)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg),
        ) {
            Text(stringResource(Res.string.play_about_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            for ((glyph, text) in listOf(
                FxGlyph.Marks to stringResource(goal.s(Res.string.play_about_marks)),
                FxGlyph.LanternUnlit to stringResource(Res.string.play_about_claim),
                FxGlyph.GateShield to stringResource(Res.string.play_about_safety),
                FxGlyph.QuietRing to stringResource(Res.string.play_about_privacy),
            )) {
                Row(horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
                    FxGlyphImage(glyph, size = 24.dp)
                    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
