package com.bainbridgetrails.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_back
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/** Section title (titleMedium, 48dp, heading semantics) with an optional trailing text action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = color)
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}

/**
 * Value + label tile (ux.md §4): `surfaceContainerLow`, value in `statLarge` (Fraunces), label `bodySmall`.
 * [emphasis] = `primaryContainer` (one hero tile per row at most).
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
    emphasis: Boolean = false,
) {
    val bg = if (emphasis) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (emphasis) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val sub = if (emphasis) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .heightIn(min = 96.dp)
            .fillMaxHeight()
            .background(bg, MaterialTheme.shapes.medium)
            .clearAndSetSemantics { contentDescription = "$value $label" }
            .padding(BtTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(BtTheme.spacing.xs, Alignment.Bottom),
    ) {
        if (icon != null) BtIcon(icon, null, size = 20.dp, tint = sub)
        Spacer(Modifier.weight(1f, fill = true))
        Text(value, style = BtTheme.type.statLarge, color = fg, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Row of [StatTile]s with equal heights (tallest wins) and 8dp gaps; give each tile `Modifier.weight(1f)`. */
@Composable
fun StatTileRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(BtTheme.spacing.sm), content = content)
}

/**
 * Screen top bar: optional back, left-aligned Fraunces title (headlineSmall), actions. Pads the status-bar inset
 * itself when [applyInsets].
 */
@Composable
fun BtTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    applyInsets: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (applyInsets) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = if (onBack != null) 4.dp else BtTheme.spacing.screenH, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { BtIcon(BtIcons.ArrowBack, stringResource(Res.string.common_back)) }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = if (onBack != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { actions() }
    }
}
