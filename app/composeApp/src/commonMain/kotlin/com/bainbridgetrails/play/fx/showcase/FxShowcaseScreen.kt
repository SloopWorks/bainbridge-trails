package com.bainbridgetrails.play.fx.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.map.StatusLayers
import com.bainbridgetrails.map.TrailMap
import com.bainbridgetrails.map.rememberTrailMapController
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxClock
import com.bainbridgetrails.play.fx.FxHostState
import com.bainbridgetrails.play.fx.FxInstance
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxStage
import com.bainbridgetrails.play.fx.FxGlobalStateSink
import com.bainbridgetrails.play.fx.FxHostInputs
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxOverlay
import com.bainbridgetrails.play.fx.FxOverlayContext
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxRendererRegistry
import com.bainbridgetrails.play.fx.FxView
import com.bainbridgetrails.play.fx.PlayMapContent
import com.bainbridgetrails.play.fx.WithFxRenderOptions
import com.bainbridgetrails.play.fx.effects.ExplorationFog
import com.bainbridgetrails.play.fx.effects.FerryCrossing
import com.bainbridgetrails.play.fx.effects.FrogRock
import com.bainbridgetrails.play.fx.effects.KrakenAttack
import com.bainbridgetrails.play.fx.effects.KrakenInk
import com.bainbridgetrails.play.fx.effects.KrakenRecede
import com.bainbridgetrails.play.fx.effects.RiddleLight
import com.bainbridgetrails.play.fx.effects.TreasureFound
import com.bainbridgetrails.play.fx.effects.KrakenLurk
import com.bainbridgetrails.play.fx.effects.RiddleMarker
import com.bainbridgetrails.play.fx.effects.TreasureGlint
import com.bainbridgetrails.play.fx.rememberFxHost
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import kotlin.time.Instant
import kotlin.time.TimeSource

private const val TAG = "FxShowcase"

/**
 * The FX showcase (effects §7, ux §3.19, engine stage 7; debug only, `screen=fx`): a full-bleed TrailMap at the preset's
 * camera with FxHost driven straight from [FxPresets] (no PlayRepository), StatusLayers with every line not started (so
 * the CASING band has its slot), the control strip and the budget HUD. Launch options: `fx`, `preset`, `state`, `t`
 * (pause at ms), `theme=dark|light`, `reduced=1`, `warp`.
 */
@Composable
fun FxShowcaseScreen(launch: SpikeLaunch = SpikeLaunch(screen = SpikeLaunch.Screen.FX), modifier: Modifier = Modifier) {
    var preset by remember { mutableStateOf(FxPresets.initial(launch.fx, launch.preset)) }
    var state by remember { mutableStateOf(launch.state?.takeIf { it in preset.states } ?: preset.states.first()) }
    var dark by remember { mutableStateOf((launch.theme ?: preset.theme).equals("dark", ignoreCase = true)) }
    var reduced by remember { mutableStateOf(launch.reduced) }
    var pausedAt by remember { mutableStateOf(launch.t) }
    val time = remember { FxShowcaseTime() }
    val stats = remember { FxShowcaseStats() }
    val data by produceState<FxShowcaseData?>(null) { value = FxPresets.load() }
    SideEffect {
        time.warp = launch.warp ?: preset.warp
        time.pausedAt = pausedAt
    }
    BtTheme(dark = dark, reducedMotion = reduced) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    val d = data
                    if (d != null) key(preset.id, state) { ShowcaseMap(preset, state, d, dark, time, stats, pausedAt) }
                    if (d != null && preset.effect in FxPresets.screenEffects) {
                        key(preset.id, state, pausedAt == null) { FxScreenScene(preset, state, d, pausedAt) }
                    }
                    FxHud(stats, Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp))
                }
                FxControls(
                    preset, state, dark, reduced, pausedAt,
                    onPreset = { preset = it; state = it.states.first(); it.theme?.let { th -> dark = th == "dark" } },
                    onState = { state = it },
                    onDark = { dark = it },
                    onReduced = { reduced = it },
                    onPausedAt = { pausedAt = it; stats.host?.noteInteraction() },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
        }
    }
}

@Composable
private fun ShowcaseMap(
    preset: FxPreset, state: String, data: FxShowcaseData, dark: Boolean, time: FxShowcaseTime, stats: FxShowcaseStats, pausedAt: Long?,
) {
    val controller = rememberTrailMapController()
    val frozen = remember { FxShowcaseFrozen() }
    val registry = remember(data) {
        FxRendererRegistry(
            listOf(
                ExplorationFog, KrakenLurk, KrakenAttack(), KrakenRecede, FerryCrossing(data.ferry, now = time::islandNow),
                RiddleMarker(), RiddleLight, FrogRock, TreasureGlint, TreasureFound,
            ).map { ShowcaseRenderer(it, time, frozen) },
        )
    }
    val host = rememberFxHost(FxHostInputs(ambientOff = false, renderers = registry), controller)
    SideEffect { stats.host = host }
    val specs = remember(preset, state, data) { FxPresets.specs(preset, state, data) }
    // Paused (`t`): OVERLAY parts are drawn still at `t` by the showcase (never retired by the scene), and the finite
    // Layer parts of a hero are held, so a screenshot can be taken at any key frame.
    val reduced = BtTheme.motion.reduced
    val still = pausedAt != null
    val sceneSpecs = remember(specs, still) { if (still) FxShowcaseFrozen.held(specs) else specs }
    SideEffect {
        frozen.atMs = pausedAt ?: 0L
        frozen.items = if (still) FxShowcaseFrozen.items(specs, reduced) else emptyList()
    }
    LaunchedEffect(host, sceneSpecs) {
        time.restart(FxPresets.FerryStart)
        sceneSpecs.forEach { host.spawn(it) }
        val kraken = preset.effect == KrakenInk.kind || preset.effect == KrakenRecede.kind
        stats.note = "${preset.id} · $state · ${specs.size} items" + if (kraken && specs.isEmpty()) " · needs dataset=roads" else ""
        AppLog.i(TAG, "spawned ${sceneSpecs.size} for ${preset.id}/$state (still=$still)")
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.mapState }.filterNotNull().collectLatest { m ->
            m.events.collect { e ->
                if (e is MapEvent.FrameRendered) {
                    e.stats?.let { s -> stats.frames++; stats.renderMs.add(s.renderingTime.inWholeMicroseconds / 1000f) }
                } else if (e is MapEvent.StyleLoaded) {
                    AppLog.i(TAG, "StyleLoaded")
                }
            }
        }
    }
    // P2 carry-forward from the UI review: log where the fx layers landed in the style (bands vs status/labels).
    LaunchedEffect(controller, dark) {
        snapshotFlow { controller.mapState }.filterNotNull().first()
        delay(4_000)
        val s = controller.mapState ?: return@LaunchedEffect
        val ids = s.style.layers.map { it.id }
        val interesting = ids.withIndex().filter { (_, id) -> FxLayerIds.isFx(id) || id == "trail-label" || id.startsWith("app-trail-") }
        AppLog.i(TAG, "layer order (${if (dark) "dark" else "light"}) total=${ids.size}: " + interesting.joinToString(" < ") { (i, id) -> "$i:$id" })
    }
    val style = if (dark) MapStyle.DARK else MapStyle.TOPO
    val overlay: @Composable MapOverlayScope.() -> Unit = remember(host) { { FxOverlay(host); FrozenOverlays(host, frozen) } }
    WithFxRenderOptions(host) { renderOptions ->
        TrailMap(
            style, Modifier.fillMaxSize(), initialCamera = preset.camera, controlsTopPadding = 52.dp, controller = controller,
            fxOverlay = overlay, renderOptions = renderOptions,
        ) {
            PlayMapContent(host, status = { casingFx -> StatusLayers(emptyMap(), style, casingFx = casingFx) })
        }
    }
}

/**
 * Showcase time: a time-warp ([warp]) and a pause at local ms ([pausedAt]) applied to every renderer's
 * `writeGlobalState` (scene time is FxHost's own), and the island clock the ferry reads.
 */
class FxShowcaseTime {
    var warp: Double = 1.0
    var pausedAt: Long? = null
    private var start: Instant = FxPresets.FerryStart
    private var mark = TimeSource.Monotonic.markNow()

    fun restart(at: Instant) {
        start = at
        mark = TimeSource.Monotonic.markNow()
    }

    fun islandNow(): Instant {
        val ms = pausedAt ?: mark.elapsedNow().inWholeMilliseconds
        return start + (ms * warp).toLong().milliseconds
    }

    fun map(nowMs: Long, baseMs: Long): Long = pausedAt?.let { baseMs + it } ?: (baseMs + ((nowMs - baseMs) * warp).toLong())
}

/** Paused showcase: the OVERLAY items drawn still at [atMs] (spawned at 0, never in the scene). */
@Stable
internal class FxShowcaseFrozen {
    var items: List<FxLive> by mutableStateOf(emptyList())
    var atMs: Long by mutableLongStateOf(0L)

    fun of(kind: String): List<FxLive> = items.filter { it.instance.spec.kind == kind }

    companion object {
        /** Held for an hour: the hero's finite Layer parts outlive the still frame. */
        private const val HOLD_MS = 3_600_000

        /** What the scene spawns while paused: no OVERLAY items; finite Layer parts held. */
        fun held(specs: List<FxSpec>): List<FxSpec> = specs.filter { it.band != FxBand.OVERLAY }.map {
            if (it.timeline.runLoopMs == null) it.copy(timeline = it.timeline.copy(despawnMs = it.timeline.despawnMs + HOLD_MS)) else it
        }

        fun items(specs: List<FxSpec>, reduced: Boolean): List<FxLive> = specs.filter { it.band == FxBand.OVERLAY }.mapIndexed { i, s ->
            FxLive(FxInstance(s, seq = -1L - i, spawnedAtMs = 0L), FxStage.RUN, t = 0, reduced = reduced)
        }
    }
}

/** The paused OVERLAY items, drawn by their renderers against a still clock at [FxShowcaseFrozen.atMs]. */
@Composable
private fun MapOverlayScope.FrozenOverlays(host: FxHostState, frozen: FxShowcaseFrozen) {
    val items = frozen.items
    if (items.isEmpty()) return
    val ctx = host.overlayContext ?: return
    val clock = remember { FxClock() }
    SideEffect { clock.frame.longValue = frozen.atMs }
    val still = remember(ctx, clock) { FxOverlayContext(ctx.map, clock, ctx.palette, ctx.reduced) { null } }
    for (item in items) {
        val renderer = host.renderers[item.instance.spec.kind] ?: continue
        key(item.instance.seq) { with(renderer) { Overlay(item, still) } }
    }
}

/** Delegates to [inner], with the showcase's warp / pause applied to the Layer-tier clock and paused items added. */
private class ShowcaseRenderer(private val inner: FxRenderer, private val time: FxShowcaseTime, private val frozen: FxShowcaseFrozen) : FxRenderer {
    override val kind: String = inner.kind

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) = writeGlobalState(live, nowMs, null, out)

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, view: FxView?, out: FxGlobalStateSink) {
        val all = live + frozen.of(kind)
        val base = all.minOfOrNull { it.instance.spawnedAtMs } ?: nowMs
        inner.writeGlobalState(all, time.map(nowMs, base), view, out)
    }

    override fun writeRestState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        val all = live + frozen.of(kind)
        val base = all.minOfOrNull { it.instance.spawnedAtMs } ?: nowMs
        inner.writeRestState(all, time.map(nowMs, base), out)
    }

    @Composable
    override fun rememberSource(live: List<FxLive>, ctx: FxLayerContext): GeoJsonSource? = inner.rememberSource(withStill(live), ctx)

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        inner.Layers(band, withStill(live), ctx, source)
    }

    @Composable
    private fun withStill(live: List<FxLive>): List<FxLive> {
        val still = frozen.of(kind)
        return remember(live, still) { if (still.isEmpty()) live else live + still }
    }

    @Composable
    override fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {
        with(inner) { Overlay(item, ctx) }
    }
}
