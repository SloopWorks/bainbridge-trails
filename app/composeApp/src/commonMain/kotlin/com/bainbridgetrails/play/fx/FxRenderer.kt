package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonSource
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
     * [writeGlobalState] with the camera ([view]: zoom + visible bounds, null until the map reports one). FxHost calls
     * this one; override it to skip writes for parts out of view or below their zoom (a write re-renders the map).
     */
    fun writeGlobalState(live: List<FxLive>, nowMs: Long, view: FxView?, out: FxGlobalStateSink) =
        writeGlobalState(live, nowMs, out)

    /**
     * The rest frame (engine PF-2): FxHost calls this when the clock stops (idle, recording, background, reduced, power
     * save), when the instance set changes while stopped, and once per [FxClockPolicy.REDUCED_TICK_MS] under reduced
     * motion. Write the values every `fx.<kind>.*` key takes at rest (eyes open, no sparkle, no sway) so nothing freezes
     * mid-motion; a part that follows real time (the ferry) moves to "now". A HERO still in flight keeps its values.
     */
    fun writeRestState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {}

    /**
     * The kind's one GeoJSON source per map (PF-5): composed once by [PlayMapContent] and handed to every band's
     * [Layers], so the bands share one tiling and one feature-state set. Keyed on the instance set (never per frame).
     * null = the kind builds its own sources inside [Layers]. A kind drawing in the CASING band must change its source
     * only with the instance set.
     */
    @Composable
    fun rememberSource(live: List<FxLive>, ctx: FxLayerContext): GeoJsonSource? = null

    /**
     * Map layers for every live instance of this kind, for [band] (called once per Layer-tier band; a kind may draw in
     * several). Inputs change only when the scene's instance set changes, never per frame: motion arrives through
     * `globalState`, paint transitions or feature state (PF-5). Layer ids come from [FxLayerIds]; layers declare no
     * `onClick` (taps go through MapTab `onTap`, spike R7).
     */
    @Composable
    @MaplibreComposable
    fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {}

    /** [Layers] with this kind's hoisted [source] (from [rememberSource]); FxLayers calls this one. */
    @Composable
    @MaplibreComposable
    fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        Layers(band, live, ctx)
    }

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

/** Each kind's hoisted source ([FxRenderer.rememberSource]) for one map; equal when the same sources. */
@Immutable
class FxKindSources(private val byKind: Map<String, GeoJsonSource>) {
    operator fun get(kind: String): GeoJsonSource? = byKind[kind]

    override fun equals(other: Any?): Boolean = other is FxKindSources && other.byKind == byKind

    override fun hashCode(): Int = byKind.hashCode()

    companion object {
        val None = FxKindSources(emptyMap())
    }
}

/** Provided by [PlayMapContent] around every band (the CASING slot inside StatusLayers included). */
internal val LocalFxSources = compositionLocalOf { FxKindSources.None }

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
