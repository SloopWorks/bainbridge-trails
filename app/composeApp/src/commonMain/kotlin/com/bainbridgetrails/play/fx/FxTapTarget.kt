package com.bainbridgetrails.play.fx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import com.bainbridgetrails.ui.theme.BtTheme
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.Position

/**
 * The one way an overlay effect takes a tap (engine PF-5): an explicit 48 dp target (`BtSpacing.playHit`) centred on
 * [at], `Role.Button` with [label], no visible drawing of its own (the effect draws). Everything else in the OVERLAY
 * band is pointer-transparent. While a gesture moves the camera the target hides with its effect (PD-19) and takes no
 * taps, so a pan never lands on it. A tap only opens a sheet ([onClick]); it never claims (product § 3.6).
 */
@Composable
fun MapOverlayScope.FxTapTarget(at: GeoPoint, label: String, ctx: FxOverlayContext, onClick: () -> Unit) {
    val size = BtTheme.spacing.playHit
    Box(
        Modifier
            .placedAt(Position(longitude = at.lon, latitude = at.lat), Alignment.Center)
            .size(size)
            .graphicsLayer { alpha = if (ctx.gestureAlpha() > 0f) 1f else 0f }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) { onClick(); true }
            }
            .clickable(onClickLabel = label, role = Role.Button, onClick = { if (ctx.gestureAlpha() > 0f) onClick() }),
    )
}
