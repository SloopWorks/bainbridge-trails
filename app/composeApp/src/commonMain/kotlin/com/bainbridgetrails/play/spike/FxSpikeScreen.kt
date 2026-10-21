package com.bainbridgetrails.play.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.map.MapAssets
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.map.StatusLayers
import org.maplibre.compose.interaction.MapInteractions
import com.bainbridgetrails.map.mercatorOffsetDp
import com.bainbridgetrails.map.rememberMapDataDir
import com.bainbridgetrails.map.renderStyleTemplate
import com.bainbridgetrails.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.TransitionOptions
import kotlin.time.Duration
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/*
 * DEBUG SPIKE (MapFx Stage 0, docs/play/engine.md §5 S1–S4, R7, R8; results in docs/play/spikes/fx.md).
 * Reachable only through debug launch options (`screen=fx`, Android extras / iOS key=value args, or `-btScreen fx`),
 * exactly like StyleSpikeScreen. Throwaway: not part of the engine.
 */

private const val TAG = "FxSpike"

/** Android: Surface (default) or Texture render mode; iOS: standard. */
internal expect fun fxSpikeUiOptions(texture: Boolean): org.maplibre.compose.map.MapUiOptions

/** Grand Forest East Trail segment seg-006175 (counted; drawn COMPLETE by StatusLayers here). */
private val TrailCoords = doubleArrayOf(
    -122.543132, 47.651596, -122.543225, 47.651596, -122.543311, 47.651542, -122.543359, 47.65148, -122.543379, 47.651364,
    -122.543399, 47.651299, -122.543441, 47.651247, -122.543527, 47.651239, -122.543608, 47.651246, -122.54365, 47.651274,
    -122.543648, 47.651291, -122.543692, 47.651318, -122.543751, 47.651313, -122.543781, 47.651299, -122.54382, 47.651251,
    -122.543901, 47.651215, -122.544001, 47.651187, -122.544065, 47.65116, -122.544082, 47.651144, -122.544121, 47.651105,
    -122.544174, 47.651094, -122.544203, 47.651082, -122.544209, 47.651027, -122.544243, 47.651004, -122.544374, 47.651003,
    -122.544455, 47.651037, -122.544533, 47.651047, -122.544551, 47.651063, -122.544592, 47.651067, -122.54466, 47.65105,
    -122.544716, 47.651055, -122.544752, 47.65105, -122.54479, 47.651018, -122.544822, 47.651001, -122.544838, 47.65097,
    -122.544866, 47.650956, -122.544908, 47.650956, -122.544989, 47.65094, -122.545023, 47.650949, -122.545046, 47.650964,
    -122.545104, 47.650976, -122.545119, 47.651009, -122.545141, 47.651036, -122.545235, 47.651028, -122.545274, 47.651043,
    -122.545274, 47.651084, -122.545203, 47.65114, -122.545189, 47.651196, -122.545197, 47.651254, -122.545206, 47.651287,
    -122.545232, 47.651302, -122.545265, 47.651301, -122.545403, 47.651335, -122.545443, 47.65136, -122.545492, 47.651392,
    -122.545529, 47.651426, -122.545569, 47.651446, -122.545604, 47.65145, -122.545694, 47.651483, -122.54575, 47.651492,
    -122.545784, 47.651509, -122.545858, 47.651523, -122.545891, 47.651564, -122.545875, 47.65162, -122.545885, 47.65166,
    -122.545956, 47.651691, -122.545988, 47.651697, -122.546054, 47.651696, -122.546102, 47.651711, -122.546171, 47.651748,
    -122.5462, 47.651754, -122.546252, 47.651787, -122.546303, 47.6518,
)
private const val TRAIL_SEGMENT_ID = "seg-006175"

/** S4 anchors: first and last trail vertex (~240 m apart). */
private val P1 = Position(longitude = TrailCoords[0], latitude = TrailCoords[1])
private val P2 = Position(longitude = TrailCoords[TrailCoords.size - 2], latitude = TrailCoords[TrailCoords.size - 1])

/** S1 point (south of the trail) and S2 points (north, numeric top-level ids 1..3). */
private val S1Point = Position(longitude = -122.5447, latitude = 47.6504)
private val S2Points = listOf(
    Position(longitude = -122.5438, latitude = 47.6522),
    Position(longitude = -122.5447, latitude = 47.6522),
    Position(longitude = -122.5456, latitude = 47.6522),
)
/** R8: an animated-opacity circle. */
private val R8Point = Position(longitude = -122.5460, latitude = 47.6504)

private val FxCamera = CameraPosition(center = Position(longitude = -122.5447, latitude = 47.6512), zoom = 16.0)

private val Magenta = Color(0xFFFF00FF)
private val Cyan = Color(0xFF00FFFF)

private fun trailJson(): String = buildString {
    append("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
    for (i in TrailCoords.indices step 2) {
        if (i > 0) append(',')
        append('[').append(TrailCoords[i]).append(',').append(TrailCoords[i + 1]).append(']')
    }
    append("]}}]}")
}

private fun pointsJson(points: List<Position>, ids: Boolean): String = buildString {
    append("""{"type":"FeatureCollection","features":[""")
    points.forEachIndexed { i, p ->
        if (i > 0) append(',')
        append("""{"type":"Feature",""")
        if (ids) append(""""id":""").append(i + 1).append(',')
        append(""""properties":{"n":""").append(i + 1).append("""},"geometry":{"type":"Point","coordinates":[""")
        append(p.longitude).append(',').append(p.latitude).append("]}}")
    }
    append("]}")
}

/** Mutable spike counters; plain fields (main thread only), not snapshot state. */
private class FxProbe {
    var frames = 0L
    var renderNs = 0L
    var encodeNs = 0L
    var drawCalls = 0L
    var maxRenderNs = 0L
    var styleLoads = 0
    var needsRepaint = 0L
    var placementChanged = 0L
    val modes = HashMap<String, Int>()
    var r8Recompositions = 0L
    var r8PropertyChanges = 0L
    var layerClicks = 0
    var r7Pass = false
    var mapClicks = 0
    var s2Source: GeoJsonSource? = null
    var overlayCoords: LayoutCoordinates? = null
    var overlayDraws = 0L
    var measuring = false
    var maxAnchorErrDp = 0.0
    var maxRingErrDp = 0.0
    var maxZoomSkew = 0.0
    var errSamples = 0L
    var sumAnchorErr = 0.0
    var sumRingErr = 0.0

    fun resetDrift() {
        maxAnchorErrDp = 0.0; maxRingErrDp = 0.0; maxZoomSkew = 0.0; errSamples = 0; sumAnchorErr = 0.0; sumRingErr = 0.0
    }

    fun snapshot() = Snap(frames, renderNs, encodeNs, drawCalls)
    data class Snap(val frames: Long, val renderNs: Long, val encodeNs: Long, val drawCalls: Long)
}

private fun log(msg: String) = AppLog.i(TAG, msg)

/** Debug spike screen for the MapFx Stage 0 spikes. `fxAuto=1` runs the scripted sequence once (logs tag FxSpike). */
@Composable
fun FxSpikeScreen(launch: SpikeLaunch = SpikeLaunch(), modifier: Modifier = Modifier) {
    var style by remember { mutableStateOf(launch.style) }
    val dataDir = rememberMapDataDir()
    val json by produceState<String?>(null, style, dataDir) {
        val urls = MapAssets.install(dataDir)
        value = withContext(Dispatchers.IO) { renderStyleTemplate(Res.readBytes(style.assetPath).decodeToString(), urls) }
    }
    val probe = remember { FxProbe() }
    val r8Opacity = remember { mutableFloatStateOf(1f) }
    var hud by remember { mutableStateOf("loading…") }
    var k3 by remember { mutableStateOf(false) }
    var found2 by remember { mutableStateOf(false) }
    var s1Anim by remember { mutableStateOf(false) }
    var r8Anim by remember { mutableStateOf(false) }
    var r7Pass by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Color(0xFFECEFE6))) {
        val j = json ?: return@Box
        val camera = if (launch.camera.zoom == 12.0) FxCamera else launch.camera
        val state = rememberMapState(baseStyle = BaseStyle.Json(j), initialCameraPosition = camera) {
            FxSpikeLayers(style, probe, r8Opacity, noTween = launch.fxNoTween)
        }
        val scope = rememberCoroutineScope()
        // R7: same shape as chartInteractions(onTap) (map-level click returns Consume); `r7Pass` flips it to Pass.
        val interactions = remember {
            MapInteractions {
                camera {
                    rotate { enabled = false }
                    pitch { enabled = false }
                }
                callbacks {
                    click {
                        onEvent { e ->
                            probe.mapClicks++
                            log("R7 map-level click fired at ${e.position} pass=${probe.r7Pass} (mapClicks=${probe.mapClicks} layerClicks=${probe.layerClicks})")
                            if (probe.r7Pass) ClickResult.Pass else ClickResult.Consume
                        }
                    }
                }
            }
        }
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = state,
            interactions = interactions,
            uiOptions = fxSpikeUiOptions(launch.fxTexture),
            renderOptions = launch.fxMaxFps?.let { fps -> RenderOptions(RenderOptions.Standard) { maximumFps = fps } } ?: RenderOptions.Standard,
            overlay = { FxSpikeOverlay(state, probe) },
        )

        // Map events → frame stats (S1/R8 frame cost).
        LaunchedEffect(state) {
            state.events.collect { e ->
                when (e) {
                    is MapEvent.FrameRendered -> {
                        val s = e.stats ?: return@collect
                        probe.frames++
                        val r = s.renderingTime.inWholeNanoseconds
                        probe.renderNs += r
                        probe.maxRenderNs = max(probe.maxRenderNs, r)
                        probe.encodeNs += s.encodingTime.inWholeNanoseconds
                        probe.drawCalls += s.drawCallCount
                        if (s.needsRepaint) probe.needsRepaint++
                        if (s.placementChanged) probe.placementChanged++
                        val m = s.mode.toString()
                        probe.modes[m] = (probe.modes[m] ?: 0) + 1
                    }
                    is MapEvent.StyleLoaded -> {
                        probe.styleLoads++
                        log("StyleLoaded #${probe.styleLoads}")
                    }
                    else -> Unit
                }
            }
        }
        // HUD, 2 Hz.
        LaunchedEffect(state) {
            var last = probe.snapshot()
            while (true) {
                delay(500)
                val now = probe.snapshot()
                val f = now.frames - last.frames
                val avgR = if (f > 0) (now.renderNs - last.renderNs) / f / 1e6 else 0.0
                val avgE = if (f > 0) (now.encodeNs - last.encodeNs) / f / 1e6 else 0.0
                hud = "fps≈${f * 2}  render=${avgR.fmt()}ms enc=${avgE.fmt()}ms  loads=${probe.styleLoads}\n" +
                    "drift max anchor=${probe.maxAnchorErrDp.fmt()}dp ring=${probe.maxRingErrDp.fmt()}dp  clicks L${probe.layerClicks}/M${probe.mapClicks}"
                last = now
            }
        }
        // S1 animated: globalState k = 2 + sin, ~30 Hz.
        LaunchedEffect(s1Anim) {
            if (!s1Anim) return@LaunchedEffect
            animateGlobalK(state, probe, Long.MAX_VALUE)
        }
        LaunchedEffect(r8Anim) {
            if (!r8Anim) { r8Opacity.floatValue = 1f; return@LaunchedEffect }
            animateR8(state, probe, r8Opacity, Long.MAX_VALUE)
        }
        if (launch.fxAuto) LaunchedEffect(state) { autoScript(state, probe, r8Opacity, setStyle = { style = it }) }

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SpikeButton(if (r7Pass) "R7 pass" else "R7 cons") { r7Pass = !r7Pass; probe.r7Pass = r7Pass }
                SpikeButton("cam") { scope.launch { s4CameraRun(state, probe) } }
                SpikeButton(if (k3) "k=3" else "k=1") {
                    k3 = !k3
                    state.style.globalState.setProperty("k", JsonPrimitive(if (k3) 3 else 1))
                    log("S1 button: setProperty(k=${if (k3) 3 else 1})")
                }
                SpikeButton(if (s1Anim) "S1 anim ■" else "S1 anim ▶") { s1Anim = !s1Anim }
                SpikeButton(if (found2) "found2 ✓" else "found2") {
                    found2 = !found2
                    setFound(state, probe, found2)
                }
                SpikeButton(style.label) { style = if (style.isDark) MapStyle.TOPO else MapStyle.DARK }
                SpikeButton(if (r8Anim) "R8 ■" else "R8 ▶") { r8Anim = !r8Anim }
                SpikeButton("order") { scope.launch { logLayerOrder(state, "button") } }
            }
            Box(Modifier.weight(1f))
            Text(
                hud,
                Modifier.background(Color(0xCCFFFFFF), RoundedCornerShape(6.dp)).padding(6.dp),
                fontSize = 11.sp,
                color = Color.Black,
            )
        }
    }
}

@Composable
private fun SpikeButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.background(Color(0xEEFFFFFF), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 13.sp,
        color = Color.Black,
    )
}

private fun Double.fmt(): String = ((this * 100).toLong() / 100.0).toString()

/** Map style content: S3 bands around StatusLayers, S1 / S2 / R8 / S4 reference circles. */
@Composable
private fun FxSpikeLayers(style: MapStyle, probe: FxProbe, r8Opacity: MutableFloatState, noTween: Boolean) {
    // fxNoTween=1: no paint transition on the animated properties (default style transition is 300 ms, which keeps
    // MapLibre rendering every display frame while any write is tweening).
    val tween = if (noTween) TransitionOptions(duration = Duration.ZERO) else null
    val trail = rememberGeoJsonSource(GeoJsonData.JsonString(remember { trailJson() }))
    // S3 band UNDER_STATUS: composed before StatusLayers, same anchor.
    Anchor.Below("trail-label") {
        LineLayer(id = "fx-s3-under", source = trail, color = const(Color(0xFFFF2020)), width = const(20.dp))
    }
    StatusLayers(mapOf(TrailStatus.COMPLETE to listOf(TRAIL_SEGMENT_ID)), style)
    // S3 band OVER_STATUS: separate Anchor.Below block after StatusLayers, plus a nested same-anchor block inside it.
    Anchor.Below("trail-label") {
        LineLayer(id = "fx-s3-over", source = trail, color = const(Color(0xFF10C010)), width = const(6.dp))
        Anchor.Below("trail-label") {
            LineLayer(id = "fx-s3-nested", source = trail, color = const(Color(0xFFFFE000)), width = const(2.dp))
        }
    }
    // S3 band OVER_LABELS: Anchor.Top, then a second Anchor.Top (proxy for LocationIndicatorLayer composed later).
    Anchor.Top {
        LineLayer(id = "fx-s3-top", source = trail, color = const(Color(0xFF2050FF)), width = const(1.dp), offset = const(5.dp))
    }
    Anchor.Top {
        LineLayer(id = "fx-s3-top2", source = trail, color = const(Color(0xFF000000)), width = const(1.dp), offset = const((-5).dp))
    }

    // S1: radius = 6dp * globalState("k").
    val s1 = rememberGeoJsonSource(GeoJsonData.JsonString(remember { pointsJson(listOf(S1Point), ids = false) }))
    CircleLayer(
        id = "fx-s1", source = s1,
        color = const(Color(0xFFFF8C00)),
        radius = const(6.dp) * globalState("k").asNumber(const(1f)),
        radiusTransition = tween,
        strokeColor = const(Color.Black), strokeWidth = const(1.dp),
    )
    // S2: opacity from feature-state "found"; R7: layer onClick.
    val s2 = rememberGeoJsonSource(GeoJsonData.JsonString(remember { pointsJson(S2Points, ids = true) }))
    probe.s2Source = s2
    CircleLayer(
        id = "fx-s2", source = s2,
        color = const(Color(0xFF008040)),
        radius = const(11.dp),
        opacity = switch(condition(feature.state("found").asBoolean(const(false)), const(0.15f)), fallback = const(1f)),
        strokeColor = const(Color.Black), strokeWidth = const(2.dp),
        strokeOpacity = switch(condition(feature.state("found").asBoolean(const(false)), const(0.15f)), fallback = const(1f)),
        onClick = { features ->
            probe.layerClicks++
            log("R7 layer onClick fired: ids=${features.map { f -> f.id }} (layerClicks=${probe.layerClicks} mapClicks=${probe.mapClicks})")
            ClickResult.Consume
        },
    )
    // R8: opacity = const(animated snapshot state) — recomposes the style composition per write.
    val r8 = rememberGeoJsonSource(GeoJsonData.JsonString(remember { pointsJson(listOf(R8Point), ids = false) }))
    val op = r8Opacity.floatValue
    SideEffect { probe.r8Recompositions++ }
    CircleLayer(id = "fx-r8", source = r8, color = const(Color(0xFF8000FF)), radius = const(12.dp), opacity = const(op), opacityTransition = tween)
    // S4 reference: MapLibre circles (pure cyan) at the two overlay anchor points.
    val s4 = rememberGeoJsonSource(GeoJsonData.JsonString(remember { pointsJson(listOf(P1, P2), ids = false) }))
    Anchor.Top {
        CircleLayer(id = "fx-s4-ref", source = s4, color = const(Cyan), radius = const(5.dp))
    }
}

/** S4 overlay: a cross at P1 placed with placedAt, and a ring at P2 projected from the P1 anchor (Web Mercator). */
@Composable
private fun org.maplibre.compose.overlay.MapOverlayScope.FxSpikeOverlay(state: MapState, probe: FxProbe) {
    Box(
        Modifier
            .placedAt(P1, Alignment.Center)
            .size(2.dp)
            .onGloballyPositioned { probe.overlayCoords = it }
            .drawBehind {
                val vp = state.viewport ?: return@drawBehind
                val zoom = vp.cameraPosition.zoom
                val c = center
                val arm = 12.dp.toPx()
                val sw = 2.dp.toPx()
                val gap = 7.dp.toPx() // arms leave the reference disc (5dp) unoccluded for centroid measurement
                drawLine(Magenta, c + Offset(-arm, 0f), c + Offset(-gap, 0f), sw)
                drawLine(Magenta, c + Offset(gap, 0f), c + Offset(arm, 0f), sw)
                drawLine(Magenta, c + Offset(0f, -arm), c + Offset(0f, -gap), sw)
                drawLine(Magenta, c + Offset(0f, gap), c + Offset(0f, arm), sw)
                val o = mercatorOffsetDp(P1.longitude, P1.latitude, P2.longitude, P2.latitude, zoom)
                val ring = c + Offset(o.x.dp.toPx(), o.y.dp.toPx())
                drawCircle(Magenta, radius = 12.dp.toPx(), center = ring, style = Stroke(sw))
                probe.overlayDraws++
                if (probe.measuring) {
                    val coords = probe.overlayCoords ?: return@drawBehind
                    val n1 = state.screenLocationFromPosition(P1) ?: return@drawBehind
                    val n2 = state.screenLocationFromPosition(P2) ?: return@drawBehind
                    val anchorRoot = coords.positionInRoot() + c
                    val anchorErr = hypot(anchorRoot.x / density - n1.x.value, anchorRoot.y / density - n1.y.value).toDouble()
                    val ringErr = hypot(o.x - (n2.x - n1.x).value, o.y - (n2.y - n1.y).value)
                    probe.maxAnchorErrDp = max(probe.maxAnchorErrDp, anchorErr)
                    probe.maxRingErrDp = max(probe.maxRingErrDp, ringErr)
                    probe.maxZoomSkew = max(probe.maxZoomSkew, abs(zoom - state.cameraPosition.zoom))
                    probe.sumAnchorErr += anchorErr
                    probe.sumRingErr += ringErr
                    probe.errSamples++
                }
            },
    )
}

private fun setFound(state: MapState, probe: FxProbe, found: Boolean) {
    val src = probe.s2Source ?: return log("S2 no source yet")
    val result = runCatching {
        val h = state.style.sources[src] ?: error("sources[src] = null")
        if (found) h.setFeatureState("2", buildJsonObject { put("found", true) }) else h.removeFeatureState("2", "found")
    }
    log("S2 setFeatureState(\"2\", found=$found) → ${result.exceptionOrNull()?.let { "FAILED $it" } ?: "ok"}")
}

private suspend fun logLayerOrder(state: MapState, label: String) {
    val ids = state.style.layers.map { it.id }
    val interesting = ids.withIndex().filter { (_, id) ->
        id.startsWith("fx-") || id == "trail-label" || id == "place-label" || id.startsWith("app-trail-") || id == "water-label"
    }
    log("S3 layer order [$label] total=${ids.size}: " + interesting.joinToString(" < ") { (i, id) -> "$i:$id" })
}

private suspend fun animateGlobalK(state: MapState, probe: FxProbe, durationMs: Long): Pair<Int, Long> {
    val mark = TimeSource.Monotonic.markNow()
    var writes = 0
    val f0 = probe.frames
    while (mark.elapsedNow().inWholeMilliseconds < durationMs) {
        delay(33)
        withFrameNanos {
            val t = mark.elapsedNow().inWholeMilliseconds / 2000.0
            val k = 2.0 + sin(2 * PI * t)
            state.style.globalState.setProperty("k", JsonPrimitive(k))
            writes++
        }
    }
    return writes to (probe.frames - f0)
}

private suspend fun animateR8(state: MapState, probe: FxProbe, r8: MutableFloatState, durationMs: Long): Triple<Int, Long, Long> {
    val mark = TimeSource.Monotonic.markNow()
    var writes = 0
    var lastProp: String? = null
    var polls = 0
    while (mark.elapsedNow().inWholeMilliseconds < durationMs) {
        withFrameNanos {
            val t = mark.elapsedNow().inWholeMilliseconds / 1000.0
            r8.floatValue = (0.55 + 0.45 * sin(2 * PI * t)).toFloat()
            writes++
        }
        if (durationMs != Long.MAX_VALUE) {
            val p = runCatching { state.style.layers["fx-r8"]?.getProperty("circle-opacity")?.toString() }.getOrNull()
            polls++
            if (p != null && p != lastProp) { probe.r8PropertyChanges++; lastProp = p }
        }
    }
    return Triple(writes, probe.r8Recompositions, probe.r8PropertyChanges)
}

private val Linear = CameraAnimation.Ease { duration = 4000.milliseconds; easing = CubicBezier(0.0, 0.0, 1.0, 1.0) }

private suspend fun s4CameraRun(state: MapState, probe: FxProbe) {
    probe.resetDrift()
    probe.measuring = true
    val d0 = probe.overlayDraws
    val f0 = probe.frames
    val t = TimeSource.Monotonic.markNow()
    log("S4 PHASE anim-zoom-out start")
    state.animateCamera(CameraUpdate(center = Position(longitude = -122.5440, latitude = 47.6515), zoom = 14.5), Linear)
    log("S4 PHASE anim-zoom-in start")
    state.animateCamera(CameraUpdate(center = Position(longitude = -122.5450, latitude = 47.6512), zoom = 16.3), Linear)
    log("S4 PHASE anim-back start")
    state.animateCamera(CameraUpdate(center = FxCamera.center, zoom = FxCamera.zoom), Linear)
    probe.measuring = false
    val ms = t.elapsedNow().inWholeMilliseconds
    val n = probe.errSamples.coerceAtLeast(1)
    log(
        "S4 RESULT ${ms}ms overlayDraws=${probe.overlayDraws - d0} mapFrames=${probe.frames - f0} samples=${probe.errSamples} " +
            "anchorErr max=${probe.maxAnchorErrDp.fmt()}dp mean=${(probe.sumAnchorErr / n).fmt()}dp " +
            "ringErr max=${probe.maxRingErrDp.fmt()}dp mean=${(probe.sumRingErr / n).fmt()}dp zoomSkew max=${probe.maxZoomSkew}",
    )
}

private fun MapState.dpOf(p: Position): DpOffset? = screenLocationFromPosition(p)

private suspend fun frameRate(probe: FxProbe, ms: Long): String {
    val a = probe.snapshot()
    delay(ms)
    val b = probe.snapshot()
    val f = b.frames - a.frames
    val s = ms / 1000.0
    val r = if (f > 0) (b.renderNs - a.renderNs) / f / 1e6 else 0.0
    val e = if (f > 0) (b.encodeNs - a.encodeNs) / f / 1e6 else 0.0
    return "frames=$f (${(f / s).fmt()}/s) render=${r.fmt()}ms enc=${e.fmt()}ms drawCalls/frame=${if (f > 0) (b.drawCalls - a.drawCalls) / f else 0}"
}

/** Scripted run (fxAuto=1): phases logged as "PHASE <name>" so a host script can screenshot at each. */
private suspend fun autoScript(state: MapState, probe: FxProbe, r8: MutableFloatState, setStyle: (MapStyle) -> Unit) {
    snapshotFlow { state.style.layers.any { it.id == "fx-s3-top2" } }.first { it }
    delay(2500)
    log("PHASE ready")
    log("POS s2-feature1=${state.dpOf(S2Points[0])} s1=${state.dpOf(S1Point)} p1=${state.dpOf(P1)} p2=${state.dpOf(P2)}")
    logLayerOrder(state, "topo initial")
    log("IDLE ${frameRate(probe, 2000)}")

    // S1 single write.
    val loads0 = probe.styleLoads
    val f0 = probe.frames
    state.style.globalState.setProperty("k", JsonPrimitive(3))
    val tw = TimeSource.Monotonic.markNow()
    // first frame after the write
    while (probe.frames == f0 && tw.elapsedNow().inWholeMilliseconds < 2000) withFrameNanos { }
    log("S1 write k=3: first FrameRendered after ${tw.elapsedNow().inWholeMilliseconds}ms; frames in window=${probe.frames - f0}")
    log("S1 after-write ${frameRate(probe, 1000)} styleLoadsDelta=${probe.styleLoads - loads0}")
    log("S1 globalState.get()=${runCatching { state.style.globalState.get() }.getOrElse { "ERR $it" }}")

    // S2.
    setFound(state, probe, true)
    delay(600)
    val src = probe.s2Source
    if (src != null) for (id in listOf("1", "2", "3")) {
        log("S2 getFeatureState($id)=${runCatching { state.style.sources[src]?.getFeatureState(id) }.getOrElse { "ERR $it" }}")
    }
    log("PHASE s1s2-static")
    delay(3000)

    // S1 animated (30 Hz writes).
    log("PHASE s1-anim")
    val loads1 = probe.styleLoads
    val nr0 = probe.needsRepaint; val pc00 = probe.placementChanged; probe.modes.clear()
    val a = probe.snapshot()
    val (writes, frames) = animateGlobalK(state, probe, 5000)
    val b = probe.snapshot()
    val fr = (b.frames - a.frames).coerceAtLeast(1)
    log(
        "S1 ANIM writes=$writes (${writes / 5.0}/s) mapFrames=$frames (${frames / 5.0}/s) render=${((b.renderNs - a.renderNs) / fr / 1e6).fmt()}ms " +
            "enc=${((b.encodeNs - a.encodeNs) / fr / 1e6).fmt()}ms maxRender=${(probe.maxRenderNs / 1e6).fmt()}ms styleLoadsDelta=${probe.styleLoads - loads1} " +
            "needsRepaint=${probe.needsRepaint - nr0} placementChanged=${probe.placementChanged - pc00} modes=${probe.modes}",
    )
    state.style.globalState.setProperty("k", JsonPrimitive(3))
    delay(1500)

    // R8.
    log("PHASE r8")
    val rc0 = probe.r8Recompositions
    val pc0 = probe.r8PropertyChanges
    val c = probe.snapshot()
    val nr1 = probe.needsRepaint; val pc1 = probe.placementChanged; probe.modes.clear()
    val (r8Writes, _, _) = animateR8(state, probe, r8, 5000)
    val d = probe.snapshot()
    log(
        "R8 writes=$r8Writes (${r8Writes / 5.0}/s) styleRecompositions=${probe.r8Recompositions - rc0} (${(probe.r8Recompositions - rc0) / 5.0}/s) " +
            "nativePropertyChanges=${probe.r8PropertyChanges - pc0} (${(probe.r8PropertyChanges - pc0) / 5.0}/s) mapFrames=${d.frames - c.frames} (${(d.frames - c.frames) / 5.0}/s) " +
            "needsRepaint=${probe.needsRepaint - nr1} placementChanged=${probe.placementChanged - pc1} modes=${probe.modes}",
    )
    r8.floatValue = 1f
    delay(1000)

    // R7: programmatic clicks (MapState.click) on feature 1 and on empty map.
    val f1 = state.dpOf(S2Points[0])
    if (f1 != null) {
        log("R7 programmatic click on fx-s2 feature 1 at $f1")
        state.click(f1)
        delay(800)
        val empty = DpOffset(f1.x, f1.y + 120.dp)
        log("R7 programmatic click on empty map at $empty")
        state.click(empty)
        delay(800)
    }

    // S4.
    log("PHASE s4-rest")
    delay(2500)
    s4CameraRun(state, probe)
    delay(1500)

    // S3 style switch.
    log("PHASE style-switch")
    val loads2 = probe.styleLoads
    setStyle(MapStyle.DARK)
    val ts = TimeSource.Monotonic.markNow()
    while (probe.styleLoads == loads2 && ts.elapsedNow().inWholeMilliseconds < 15000) delay(50)
    snapshotFlow { state.style.layers.any { it.id == "fx-s3-top2" } }.first { it }
    delay(1500)
    logLayerOrder(state, "dark after switch")
    if (src != null) log("S2 after style switch getFeatureState(2)=${runCatching { state.style.sources[src]?.getFeatureState("2") }.getOrElse { "ERR $it" }}")
    log("S1 after style switch globalState.get()=${runCatching { state.style.globalState.get() }.getOrElse { "ERR $it" }}")
    log("PHASE dark")
    delay(3000)
    log("PHASE done")
}
