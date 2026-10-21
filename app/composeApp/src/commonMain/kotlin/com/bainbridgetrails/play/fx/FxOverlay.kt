package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.bainbridgetrails.play.geo.GeoPoint
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

/**
 * Overlay part sizing (e2e run 1 F2): a part is a box placed in geographic units, and Compose constraints cannot
 * represent a side much past 2^18 px (iOS aborted on an 89 917 px kraken rig whose water point lay kilometres from the
 * stretch it gripped). Every variable-size part goes through [partDp]: its side is clamped to [MAX_PX] (drawing is not
 * clipped to the box, so a clamped part still draws whole), and a part more than [SKIP_FACTOR] × that is not composed
 * at all: its Layer-tier part (the ink casing) is the whole effect there.
 */
object FxOverlayExtent {
    /** Largest overlay part side, px. */
    const val MAX_PX = 4096f

    /** A part wanting more than `MAX_PX × SKIP_FACTOR` px (several viewports) is skipped, not shrunk. */
    const val SKIP_FACTOR = 4f

    private const val ISLAND_LAT = 47.63

    /** Side, px, of a part [metres] across at [zoom] (at [lat], [density] px per dp), clamped to [MAX_PX]. */
    fun overlayExtentPx(zoom: Double, metres: Double, lat: Double = ISLAND_LAT, density: Float = 1f): Float {
        val px = (FxProjection.metresToDp(metres, lat, zoom) * density).toFloat()
        return if (px.isFinite()) px.coerceIn(0f, MAX_PX) else MAX_PX
    }

    /** The side (dp) to compose for a part wanting [dp] at [density]; null = skip the part (Layer tier only). */
    fun partDp(dp: Float, density: Float): Float? {
        val px = dp * density
        if (!px.isFinite() || px > MAX_PX * SKIP_FACTOR) return null
        return if (px <= MAX_PX) dp else MAX_PX / density
    }
}
