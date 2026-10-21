package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.util.MaplibreComposable

/** Receives a renderer's Layer-tier `globalState` values for one driver frame. Keys start with `fx.`. */
fun interface FxGlobalStateSink {
    fun put(key: String, value: Double)
}

/**
 * What one effect kind contributes (engine §2.2). One object per kind, not per instance: instances are data
 * ([FxLive]). Both parts default to nothing, so a kind can be Layer-only or Overlay-only.
 */
interface FxRenderer {
    /** Renderer key = snake_case effect id (effects.md § 3), e.g. "treasure_glint"; matches [FxSpec.kind]. */
    val kind: String

    /**
     * Layer-tier motion (engine PF-1 (2)): write this kind's `fx.<kind>.*` values for scene time [nowMs]. Called at
     * ≤ 30 Hz while the clock runs and the kind has live items; FxHost re-writes the last values after every
     * `StyleLoaded` (spike S1: a base-style load resets global state). Must not allocate.
     */
    fun writeGlobalState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {}

    /**
     * Map layers for every live instance of this kind, for [band] (called once per Layer-tier band; a kind may draw in
     * several). Inputs change only when the scene's instance set changes, never per frame: motion arrives through
     * `globalState`, paint transitions or feature state (PF-5). Layer ids come from [FxLayerIds]; layers declare no
     * `onClick` (taps go through MapTab `onTap`, spike R7).
     */
    @Composable
    @MaplibreComposable
    fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {}

    /**
     * Compose overlay for one OVERLAY-band instance, placed with [fxPlacedAt] (hidden during gesture camera moves,
     * PD-19). Read [FxOverlayContext.clock] and the camera only in draw / layout; check the zoom range there too.
     */
    @Composable
    fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {}
}

/** Effect kind → renderer. Registration order is the z-order of kinds inside one Layer-tier band. */
@Immutable
class FxRendererRegistry(renderers: List<FxRenderer>) {
    private val byKind: Map<String, FxRenderer> = LinkedHashMap<String, FxRenderer>().apply {
        renderers.forEach { r ->
            require(r.kind.isNotBlank()) { "blank renderer kind" }
            require(put(r.kind, r) == null) { "duplicate renderer kind ${r.kind}" }
        }
    }

    val kinds: List<String> = byKind.keys.toList()

    operator fun get(kind: String): FxRenderer? = byKind[kind]

    companion object {
        /** No effects yet (L2-2): the host composes nothing visible. */
        val Empty = FxRendererRegistry(emptyList())
    }
}

/** Stable inputs for [FxRenderer.Layers]: never the frame time (PF-5). */
@Immutable
class FxLayerContext(val palette: FxPalette, val reduced: Boolean)

/** Inputs for [FxRenderer.Overlay]. [clock] and [map] camera reads belong in draw / layout only. */
@Stable
class FxOverlayContext internal constructor(
    val map: MapState,
    val clock: FxClock,
    val palette: FxPalette,
    val reduced: Boolean,
    private val gestureEndedAtMs: () -> Long?,
) {
    /**
     * Overlay alpha for this draw (PD-19, [FxGesturePolicy]): 0 while the camera moves by gesture, then a fade back
     * after `CameraMoveEnded`. Draw / layout phase only.
     */
    fun gestureAlpha(): Float {
        val since = if (clock.mode == FxClockPolicy.Mode.STOPPED) null else gestureEndedAtMs()?.let { clock.frameMs - it }
        return FxGesturePolicy.overlayAlpha(map.isCameraMoving, map.cameraMoveReason is CameraMoveReason.Gesture, since)
    }
}
