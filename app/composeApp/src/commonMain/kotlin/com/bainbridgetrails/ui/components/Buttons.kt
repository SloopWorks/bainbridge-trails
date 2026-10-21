package com.bainbridgetrails.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.map_locate_a11y
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/** Filled `primary` pill button, 56dp (64dp when [large], e.g. Record "Start"). [loading] shows inline progress. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    large: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (large) 64.dp else 56.dp),
        enabled = enabled && !loading,
        shape = PillShape,
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) { ButtonContent(text, icon, loading, large) }
}

/** Outlined pill button, 56dp, same layout as [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled,
        shape = PillShape,
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) { ButtonContent(text, icon, false, false) }
}

@Composable
private fun ButtonContent(text: String, icon: DrawableResource?, loading: Boolean, large: Boolean) {
    if (loading) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        Spacer(Modifier.width(12.dp))
    } else if (icon != null) {
        BtIcon(icon, null, size = 20.dp)
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
}

/**
 * Neutral pill for GPS / Unverified state (ux.md §3.5). Never status-colored. [leading] = small icon/dot slot.
 * Clickable only with [onClick] (then ≥48dp touch target).
 */
@Composable
fun StateChip(text: String, modifier: Modifier = Modifier, icon: DrawableResource? = null, leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val m = if (onClick != null) modifier.minimumInteractiveComponentSize() else modifier
    Row(
        m.heightIn(min = 32.dp)
            .shadow(3.dp, PillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, PillShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        if (icon != null) BtIcon(icon, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
    }
}

/** 48dp round button floating over the map: `mapChrome` + 3dp shadow, icon `onMapChrome` (`primary` when [active]). */
@Composable
fun MapIconButton(icon: DrawableResource, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, size: Dp = 48.dp) {
    Box(
        modifier
            .size(size)
            .shadow(3.dp, PillShape)
            .background(BtTheme.colors.mapChrome, PillShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        BtIcon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else BtTheme.colors.onMapChrome)
    }
}

enum class LocateMode { Idle, Following, Unavailable }

@Composable
fun LocateButton(mode: LocateMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val icon = when (mode) {
        LocateMode.Idle -> BtIcons.MyLocation
        LocateMode.Following -> BtIcons.MyLocationFilled
        LocateMode.Unavailable -> BtIcons.LocationDisabled
    }
    MapIconButton(icon, stringResource(Res.string.map_locate_a11y), onClick, modifier, active = mode == LocateMode.Following)
}
