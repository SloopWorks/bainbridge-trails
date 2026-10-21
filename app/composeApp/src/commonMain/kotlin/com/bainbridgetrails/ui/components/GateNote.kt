package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage

/** Why a Play action is absent (docs/play/ux.md §2.3, §2.12). Each kind has its glyph; none is an error. */
enum class GateKind(val glyph: FxGlyph) {
    /** Outside daylight: `play_gate_dark`. */
    Dark(FxGlyph.GateMoon),
    /** Tide above the kraken limit / tide table missing: `play_gate_tide`, `play_gate_tide_unknown`. */
    Tide(FxGlyph.GateWave),
    /** Daylight-only note (What's On, About). */
    Daylight(FxGlyph.GateSun),
    /** Quiet zone: `play_gate_quiet`. */
    Quiet(FxGlyph.QuietRing),
    /** Safety line: `play_safety_shore`, (v2) owl / coyote / newt. */
    Safety(FxGlyph.GateShield),
}

/**
 * Inline reason row (docs/play/ux.md §2.12): `surfaceContainerHigh` small-shape box, glyph 18dp `onSurfaceVariant` +
 * [text] `bodyMedium` `onSurface`. Never error colors: Play CTAs are absent with a reason, never disabled.
 * Non-interactive; one spoken node (the text; the glyph is decorative).
 */
@Composable
fun GateNote(kind: GateKind, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FxGlyphImage(kind.glyph, size = 18.dp, ink = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
