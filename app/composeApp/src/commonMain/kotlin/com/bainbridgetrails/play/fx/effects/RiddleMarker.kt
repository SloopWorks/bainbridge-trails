package com.bainbridgetrails.play.fx.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxGlobalStateSink
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.fxGeoJson
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `riddle_marker` (effects §3.9, ux §2.2, plan §2): the question lantern as an `OVER_STATUS` pin (dot at island zoom,
 * 22 dp pin at z13–14, 28 dp from z14). Idle sway = a ±1.5 dp `icon-translate` with a paint transition, two steps per
 * 3.2 s period (`icon-rotate` is a layout property and cannot transition, effects §3.9); the step is advanced from
 * [writeGlobalState], so it follows the FxHost clock (stops on idle, reduced, recording, background) and returns to
 * centre at rest ([writeRestState]). The step is snapshot state, so the pin layers recompose every 1.6 s while swaying
 * (two style-property writes per period; accepted, UI review 2026-10-21). Lit lanterns (`#lit` ids, the riddle_light
 * hand-off) are static parts ([FxSpec.static]): the lit image plus an `UNDER_STATUS` lamp glow that flickers through
 * `globalState` only while the clock runs for something else. One source for both bands; one instance per registry.
 */
class RiddleMarker : FxRenderer {
    override val kind: String = KIND
    /** −1 left, 0 centre (rest), 1 right. */
    internal var sway by mutableIntStateOf(0)
        private set

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        if (live.none { !it.reduced && !it.instance.spec.isStatic }) return
        val step = if ((nowMs / SWAY_HALF_MS) % 2 == 0L) 1 else -1
        if (step != sway) sway = step
        out.put(KEY_FLICKER, 0.6 + 0.05 * sin(2 * PI * nowMs / 4_000.0))
    }

    /** Rest: the lantern hangs straight, the glow steady. */
    override fun writeRestState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        if (sway != 0) sway = 0
        out.put(KEY_FLICKER, REST_FLICKER)
    }

    @Composable
    override fun rememberSource(live: List<FxLive>, ctx: FxLayerContext): GeoJsonSource = rememberGeoJsonSource(
        GeoJsonData.JsonString(remember(live) { fxGeoJson(live) { l, sb -> sb.append("\"lit\":").append(isLit(l.instance.spec)) } }),
    )

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        if (band != FxBand.OVER_STATUS && band != FxBand.UNDER_STATUS) return
        if (source == null) return
        val p = ctx.palette
        val lit = feature["lit"].asBoolean(const(false))
        if (band == FxBand.UNDER_STATUS) {
            CircleLayer(
                id = GLOW, source = source, minZoom = 13f, filter = lit,
                translate = offset(0.dp, (-PIN_FIGURE_CY).dp),
                color = const(p.lamp), blur = const(1f), radius = const(28.dp),
                opacity = if (ctx.reduced) const(REST_FLICKER.toFloat()) else globalState(KEY_FLICKER).asNumber(const(REST_FLICKER.toFloat())),
            )
            return
        }
        CircleLayer(
            id = DOT, source = source, maxZoom = 13f,
            color = const(p.seaGlass), radius = const(3.dp), strokeColor = const(p.ink), strokeWidth = const(1.5.dp),
        )
        val unlitFig = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.LanternUnlit, p.ink, p.lamp) })
        val litFig = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.LanternLit, p.ink, p.lamp) })
        val pins = remember(p, unlitFig, litFig) {
            listOf(22f to 26f, 28f to 30f).map { (f, l) -> FxPin(unlitFig, p.ink, p.paper, null, f, l) to FxPin(litFig, p.ink, p.paper, null, f, l) }
        }
        val swayDp = if (ctx.reduced) 0f else SWAY_DP * sway
        val swayTween = TransitionOptions(duration = if (ctx.reduced) Duration.ZERO else SWAY_HALF_MS.milliseconds)
        pins.forEachIndexed { i, (unlit, litPin) ->
            SymbolLayer(
                id = if (i == 0) PIN_SMALL else PIN, source = source,
                minZoom = if (i == 0) 13f else 14f, maxZoom = if (i == 0) 14f else 24f,
                iconImage = switch(
                    condition(lit, image(litPin.painter, litPin.size)),
                    fallback = image(unlit.painter, unlit.size),
                ),
                iconOffset = offset(0.dp, unlit.offsetYDp.dp),
                iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
                iconTranslate = offset((if (i == 0) 0f else swayDp).dp, 0.dp), iconTranslateTransition = swayTween,
            )
        }
    }

    companion object {
        const val KIND = "riddle_marker"
        const val KEY_FLICKER = "fx.riddle_marker.flicker"
        const val SWAY_HALF_MS = 1_600L
        const val SWAY_DP = 1.5f
        const val REST_FLICKER = 0.6
        private val DOT = FxLayerIds.of(KIND, "dot")
        private val GLOW = FxLayerIds.of(KIND, "glow")
        private val PIN_SMALL = FxLayerIds.of(KIND, "pin-small")
        private val PIN = FxLayerIds.of(KIND, "pin")
        val layerIds: List<String> = listOf(GLOW, DOT, PIN_SMALL, PIN)
        private val timeline = loopTimeline(3_200)

        /** Unlit: AMBIENT, sways. Lit: a static part (no sway of its own; the glow rests at α .6). */
        fun spec(id: String, at: GeoPoint, lit: Boolean): FxSpec = if (lit) {
            FxSpec.static("$id#lit", KIND, FxAnchor.Point(at), 10.5..22.0, FxBand.OVER_STATUS)
        } else {
            FxSpec("$id#unlit", KIND, FxAnchor.Point(at), 10.5..22.0, FxBand.OVER_STATUS, FxPriority.AMBIENT, timeline, loop = true)
        }

        fun isLit(spec: FxSpec): Boolean = spec.id.endsWith("#lit")
    }
}
