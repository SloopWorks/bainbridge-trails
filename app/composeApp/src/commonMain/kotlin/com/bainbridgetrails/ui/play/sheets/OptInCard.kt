package com.bainbridgetrails.ui.play.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.ui.components.PrimaryButton
import com.bainbridgetrails.ui.theme.BtTheme

/** Which illustration an [OptInCard] draws (ux.md § 2.10). */
enum class OptInArt { Island, Fog }

/**
 * OptInCard (ux.md § 2.10): the content block of the offer, intro and off sheets. Illustration 160dp Canvas from tokens
 * (decorative), title `headlineSmall` Fraunces (heading, read first), body `bodyLarge` `onSurfaceVariant`, optional
 * bullets with 24dp ink glyphs, PrimaryButton 56dp full width ([loading] while the start balance computes), TextButton
 * 48dp. Screen readers read title then bullets (PF1-4).
 */
@Composable
fun OptInCard(
    title: String,
    body: String?,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    bullets: List<Pair<FxGlyph, String>> = emptyList(),
    art: OptInArt = OptInArt.Island,
    loading: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.xl).padding(bottom = BtTheme.spacing.lg)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.md),
    ) {
        OptInIllustration(art)
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, maxLines = 3)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((glyph, text) in bullets) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.md)) {
                FxGlyphImage(glyph, size = 24.dp)
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
        }
        PrimaryButton(primary, onPrimary, Modifier.fillMaxWidth().padding(top = BtTheme.spacing.sm), loading = loading)
        TextButton(onClick = onSecondary, enabled = !loading, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            Text(secondary)
        }
    }
}

/** The island with glints and a tentacle curl (offer/intro) or under fog (off). Tokens only; decorative. */
@Composable
private fun OptInIllustration(art: OptInArt) {
    val ink = BtTheme.colors.play.ink
    val glass = BtTheme.colors.play.seaGlass
    val fog = BtTheme.colors.play.fog
    val land = MaterialTheme.colorScheme.surfaceContainerHighest
    val water = BtTheme.colors.waterLine
    Canvas(Modifier.fillMaxWidth().height(160.dp).clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val island = Path().apply {
            moveTo(cx - h * 0.18f, h * 0.12f)
            cubicTo(cx + h * 0.10f, h * 0.02f, cx + h * 0.30f, h * 0.30f, cx + h * 0.22f, h * 0.55f)
            cubicTo(cx + h * 0.16f, h * 0.80f, cx - h * 0.02f, h * 0.95f, cx - h * 0.20f, h * 0.86f)
            cubicTo(cx - h * 0.36f, h * 0.70f, cx - h * 0.34f, h * 0.30f, cx - h * 0.18f, h * 0.12f)
            close()
        }
        for (i in 1..3) {
            val o = i * 10.dp.toPx()
            drawLine(water, Offset(cx - h * 0.6f - o, h * 0.2f * i), Offset(cx - h * 0.45f - o, h * 0.2f * i), 1.dp.toPx(), StrokeCap.Round)
            drawLine(water, Offset(cx + h * 0.45f + o, h * 0.25f * i), Offset(cx + h * 0.6f + o, h * 0.25f * i), 1.dp.toPx(), StrokeCap.Round)
        }
        drawPath(island, land)
        drawPath(island, ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        if (art == OptInArt.Island) {
            for ((x, y) in listOf(-0.08f to 0.35f, 0.08f to 0.6f, -0.15f to 0.7f)) drawCircle(glass, 3.dp.toPx(), Offset(cx + h * x, h * y))
            val curl = Path().apply {
                moveTo(cx + h * 0.30f, h * 0.70f)
                cubicTo(cx + h * 0.42f, h * 0.60f, cx + h * 0.40f, h * 0.42f, cx + h * 0.32f, h * 0.45f)
            }
            drawPath(curl, ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        } else {
            drawRect(fog)
        }
    }
}
