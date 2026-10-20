package com.bainbridgetrails.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.DrawableResource

/**
 * Radio row (ux.md §3.10): optional [leading] (e.g. [MapStyleSwatch]), title + [description], radio at the end.
 * The whole row is one selectable node (Role.RadioButton), ≥56dp.
 */
@Composable
fun SettingRadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected, onClick = null)
    }
}

/**
 * Switch row: title + [description], M3 switch at the end. The whole row is one toggleable node (Role.Switch),
 * ≥56dp; [horizontalPadding] defaults to the screen margin (pass 0 inside an already padded column).
 */
@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    horizontalPadding: Dp = BtTheme.spacing.screenH,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = horizontalPadding, vertical = BtTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Navigation row: optional icon, title + [subtitle], trailing chevron (or [trailingIcon], e.g. open-in-new). */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: DrawableResource? = null,
    trailingIcon: DrawableResource? = BtIcons.ChevronRight,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = BtTheme.spacing.screenH, vertical = BtTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.lg),
    ) {
        if (icon != null) BtIcon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailingIcon != null) BtIcon(trailingIcon, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Map style choice for [MapStyleSwatch]; AUTO shows Topo and Dark halves. */
enum class SwatchStyle { AUTO, TOPO, LIGHT, DARK }

private class SwatchPalette(val water: Color, val land: Color, val waterLine: Color, val trail: Color, val done: Color, val partway: Color)

// docs/spikes/map-render/styles.md palette table (map colors, not theme tokens: the swatch previews the map).
private val TopoP = SwatchPalette(Color(0xFF9EBFC7), Color(0xFFECEFE6), Color(0xFF6F97A2), Color(0xFF6E6A64), Color(0xFF9B1FA8), Color(0xFFC98A00))
private val LightP = SwatchPalette(Color(0xFFFBFCFC), Color(0xFFE8EBE7), Color(0xFF9DB5BF), Color(0xFF66625C), Color(0xFF931B9F), Color(0xFFC08300))
private val DarkP = SwatchPalette(Color(0xFF0E1F27), Color(0xFF1D2925), Color(0xFF2E5564), Color(0xFFA9A49B), Color(0xFFD45CFF), Color(0xFFF0B43C))

/** 56×40 preview of a map style: water, a coast with one water line, a done trail and a partway trail. Decorative. */
@Composable
fun MapStyleSwatch(style: SwatchStyle, modifier: Modifier = Modifier, width: Dp = 56.dp, height: Dp = 40.dp) {
    val shape = MaterialTheme.shapes.small
    Box(modifier.size(width, height).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).clearAndSetSemantics {}) {
        Canvas(Modifier.size(width, height)) {
            fun draw(p: SwatchPalette, clipLeft: Float, clipRight: Float) {
                val w = size.width; val h = size.height
                drawRect(p.water, topLeft = Offset(clipLeft, 0f), size = androidx.compose.ui.geometry.Size(clipRight - clipLeft, h))
                val coast = Path().apply {
                    moveTo(w * 0.18f, h * 1.1f)
                    cubicTo(w * 0.10f, h * 0.55f, w * 0.38f, h * 0.10f, w * 0.70f, h * 0.18f)
                    cubicTo(w * 0.95f, h * 0.24f, w * 1.05f, h * 0.6f, w * 1.1f, h * 1.1f)
                    close()
                }
                clipRect(clipLeft, 0f, clipRight, h) {
                    drawPath(coast, p.waterLine, style = Stroke(1.dp.toPx() * 5))
                    drawPath(coast, p.water, style = Stroke(1.dp.toPx() * 3))
                    drawPath(coast, p.land)
                    val t1 = Path().apply { moveTo(w * 0.28f, h * 0.95f); quadraticTo(w * 0.40f, h * 0.50f, w * 0.62f, h * 0.42f) }
                    val t2 = Path().apply { moveTo(w * 0.62f, h * 0.42f); quadraticTo(w * 0.80f, h * 0.60f, w * 0.86f, h * 0.95f) }
                    drawPath(t1, p.done, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(t2, p.partway, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.5.dp.toPx()))))
                    drawCircle(p.trail, 1.5.dp.toPx(), Offset(w * 0.62f, h * 0.42f))
                }
            }
            when (style) {
                SwatchStyle.TOPO -> draw(TopoP, 0f, size.width)
                SwatchStyle.LIGHT -> draw(LightP, 0f, size.width)
                SwatchStyle.DARK -> draw(DarkP, 0f, size.width)
                SwatchStyle.AUTO -> {
                    draw(TopoP, 0f, size.width / 2)
                    draw(DarkP, size.width / 2, size.width)
                }
            }
        }
    }
}
