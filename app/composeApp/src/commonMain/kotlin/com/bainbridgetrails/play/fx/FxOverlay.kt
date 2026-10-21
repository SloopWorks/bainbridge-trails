package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.Position

/**
 * Play's OVERLAY band (engine PF-1/PF-4, PD-19), drawn by TrailMap's `fxOverlay` slot under the chrome: the one live
 * region, then one renderer overlay per live OVERLAY instance in draw order (band, priority, seq). With no live
 * instances (L2-2) only the empty live region is composed.
 */
@Composable
fun MapOverlayScope.FxOverlay(host: FxHostState) {
    FxLiveRegion(host.announcer)
    val items = host.overlayItems
    if (items.isEmpty()) return
    val ctx = host.overlayContext ?: return
    for (item in items) {
        val renderer = host.renderers[item.instance.spec.kind] ?: continue
        key(item.instance.seq) { with(renderer) { Overlay(item, ctx) } }
    }
}

/**
 * The placement every overlay part uses: `placedAt` (re-placed on presented map frames, not recomposed), hidden while
 * the camera moves by gesture and faded back after `CameraMoveEnded` (PD-19; read in the draw phase), and decorative
 * (`clearAndSetSemantics`: speech goes through [FxAnnouncer]).
 */
fun Modifier.fxPlacedAt(
    scope: MapOverlayScope,
    at: GeoPoint,
    ctx: FxOverlayContext,
    alignment: Alignment = Alignment.Center,
): Modifier = with(scope) {
    this@fxPlacedAt
        .placedAt(Position(longitude = at.lon, latitude = at.lat), alignment)
        .graphicsLayer { alpha = ctx.gestureAlpha() }
        .clearAndSetSemantics {}
}
