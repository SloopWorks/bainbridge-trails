package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.map.TrailMapController
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PlayLight
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.RenderOptions

private const val TAG = "fx"

/**
 * What a map call site hands FxHost when Play is on. [ambientOff]: ambient effects rest on their final frame (a walk
 * is recording, or the user turned ambient off; PF-2). [renderers]: the effect registry (empty until L2-4).
 */
@Immutable
data class FxHostInputs(val ambientOff: Boolean, val renderers: FxRendererRegistry = FxRendererRegistry.Empty)

/**
 * The Play gate for map call sites (engine PF-9): null when Play is off, so MapTab / RecordScreen create no host, no
 * scene, no clock, no sources and no layers (a plain `if`, nothing remembered survives the toggle).
 */
fun fxHostInputs(
    play: PlaySettings,
    recording: Boolean,
    renderers: FxRendererRegistry = FxRendererRegistry.Empty,
): FxHostInputs? = if (!play.enabled) null else FxHostInputs(ambientOff = recording || !play.ambient, renderers = renderers)

/**
 * Hosts one map's effect scene (engine §2.2, PF-2, PD-19): owns the [FxScene] and the one frame clock, publishes the
 * overlay frame per display frame (or 30 Hz ambient) and Layer-tier `globalState` values at ≤ 30 Hz, pauses per
 * [FxClockPolicy] (map not attached, app not resumed, reduced motion, Battery Saver / Low Power, idle, ambient off),
 * re-writes every live `fx.*` global-state key on `StyleLoaded`, exposes the `maximumFps` cap for TrailMap's
 * `renderOptions`, and tracks gesture camera moves for [FxOverlayContext.gestureAlpha]. Main thread only.
 */
@Stable
class FxHostState internal constructor(val renderers: FxRendererRegistry) {
    private val scene = FxScene()
    val clock = FxClock()
    val announcer = FxAnnouncer()
    private val gate = FxFrameGate()

    // Inputs (written from composition via SideEffect, or from the run loop).
    internal var reduced by mutableStateOf(false)

    /** Battery Saver / Low Power: the map half of reduced motion (effects.md § 5). */
    internal var powerSave by mutableStateOf(false)
    internal var ambientOff by mutableStateOf(false)
    internal var palette by mutableStateOf(FxPalette.of(PlayLight))
    private var map by mutableStateOf<MapState?>(null)
    private var resumed by mutableStateOf(false)
    private var idle by mutableStateOf(false)
    private var interactions by mutableIntStateOf(0)
    private var gestureEndedAtMs by mutableStateOf<Long?>(null)

    /** Reduced motion or power save: every map effect draws its final frame (chrome motion follows [reduced] only). */
    private val mapReduced: Boolean by derivedStateOf { reduced || powerSave }

    // Scene outputs: change only when the instance set (scene.version) or the reduced flag changes, never per frame.
    private var seenVersion = -1L
    private var seenReduced = false
    private var summary by mutableStateOf(FxSceneSummary(false, false, false, false))

    /** Scene ms of the last [FxScene.summary] (re-read on an instance-set change, else at most every 33 ms). */
    private var summaryAtMs = Long.MIN_VALUE / 2

    /** Live instances per kind (all bands), for [FxLayers]; a kind whose instance set is unchanged keeps its list. */
    internal var liveByKind by mutableStateOf<Map<String, List<FxLive>>>(emptyMap())
        private set

    /** Live OVERLAY-band instances in draw order, for [FxOverlay]. */
    internal var overlayItems by mutableStateOf<List<FxLive>>(emptyList())
        private set

    internal val layerContext: FxLayerContext by derivedStateOf { FxLayerContext(palette, mapReduced) }

    internal val overlayContext: FxOverlayContext? by derivedStateOf {
        map?.let { m -> FxOverlayContext(m, clock, palette, mapReduced) { gestureEndedAtMs } }
    }

    private val mode: FxClockPolicy.Mode by derivedStateOf {
        FxClockPolicy.mode(
            playOn = true, // the host only exists while Play is on
            resumed = resumed && map != null,
            reduced = reduced,
            scene = summary,
            msSinceInteraction = if (idle) Long.MAX_VALUE else 0L,
            recording = ambientOff,
            powerSave = powerSave,
        )
    }

    /** MapLibre `maximumFps`: 30 while a Layer-tier part animates (spike S1 #6), else null (library default). */
    val maximumFps: Int? by derivedStateOf { FxClockPolicy.mapMaxFps(mode, summary) }

    /** For TrailMap's `renderOptions`: Standard, or Standard capped at [maximumFps]. */
    val renderOptions: RenderOptions by derivedStateOf {
        val fps = maximumFps
        if (fps == null) RenderOptions.Standard else RenderOptions(RenderOptions.Standard) { maximumFps = fps }
    }

    /** Reduced motion / power save: real-time parts (the ferry) still step once a minute (PF3-4), lifecycle-gated. */
    private val reducedTick: Boolean by derivedStateOf {
        FxClockPolicy.reducedTick(
            playOn = true,
            resumed = resumed && map != null,
            reduced = reduced,
            powerSave = powerSave,
            recording = ambientOff,
            msSinceInteraction = if (idle) Long.MAX_VALUE else 0L,
        )
    }

    /** Last written `fx.*` global-state values (re-written after every base-style load). */
    private val globals = HashMap<String, Double>()
    private val sink = FxGlobalStateSink { key, value ->
        require(key.startsWith(FX_GLOBAL_PREFIX)) { "global-state key $key outside $FX_GLOBAL_PREFIX" }
        if (globals[key] != value) {
            globals[key] = value
            map?.let { it.style.globalState.setProperty(key, JsonPrimitive(value)) }
        }
    }
    private val driver = FxLayerDriver(renderers, sink)

    /** The camera for Layer-tier drivers; re-read (≤ 30 Hz) after a camera move, null until the map has a viewport. */
    private var view: FxView? = null
    private var viewDirty = true

    /** HUD (showcase): live instances, how many of them are static, and whether anything animates. */
    val debugCounts: String
        get() {
            val all = liveByKind.values.sumOf { it.size }
            val static = liveByKind.values.sumOf { l -> l.count { it.instance.spec.isStatic } }
            val s = summary
            val anim = listOfNotNull("hero".takeIf { s.animatingHero }, "notice".takeIf { s.animatingNotice }, "ambient".takeIf { s.animatingAmbient })
            return "live $all · static $static · animating ${anim.joinToString("+").ifEmpty { "none" }}"
        }

    /** Spawn [spec] at the current scene time (see [FxScene.spawn]); starts the clock when it animates. */
    fun spawn(spec: FxSpec, inView: Boolean = true): SpawnResult = scene.spawn(spec, clock.frameMs, inView).also { refresh() }

    /** Dismiss a live effect (it plays out from SETTLE) or drop it from the HERO queue. */
    fun dismiss(id: String) {
        scene.dismiss(id, clock.frameMs)
        refresh()
    }

    /** A tap or camera move: resets the 20 s ambient idle stop (PF-2). */
    fun noteInteraction() {
        interactions++
    }

    /**
     * Re-tick the scene; publish the live lists only when the instance set or the reduced flag changed, and re-read the
     * summary then or at most every 33 ms of scene time (an animation ending stops the clock ≤ 33 ms later).
     */
    private fun refresh() {
        val still = mapReduced
        val nowMs = clock.frameMs
        val frame = scene.tick(nowMs, still)
        val changed = scene.version != seenVersion || still != seenReduced
        if (changed) {
            seenVersion = scene.version
            seenReduced = still
            val byKind = LinkedHashMap<String, MutableList<FxLive>>()
            val overlay = ArrayList<FxLive>()
            for (l in frame.live) {
                byKind.getOrPut(l.instance.spec.kind) { ArrayList() } += l
                if (l.instance.spec.band == FxBand.OVERLAY) overlay += l
            }
            liveByKind = FxLayerDriver.reuseUnchanged(liveByKind, byKind)
            overlayItems = overlay
            // A stopped clock still shows new instances on their rest frame (a glint spawned while idle is matte).
            if (clock.mode == FxClockPolicy.Mode.STOPPED) driver.rest(liveByKind, nowMs)
        }
        if (changed || nowMs - summaryAtMs >= SUMMARY_INTERVAL_MS) {
            summaryAtMs = nowMs
            summary = scene.summary()
        }
    }

    private fun writeGlobals() {
        val m = map
        if (viewDirty && m != null) {
            view = runCatching {
                m.getVisibleBounds()?.let { b -> FxView(m.cameraPosition.zoom, b.west, b.south, b.east, b.north) }
            }.getOrNull()
            viewDirty = view == null
        }
        driver.frame(liveByKind, clock.frameMs, view)
    }

    private fun rewriteGlobals(m: MapState) {
        for ((k, v) in globals) m.style.globalState.setProperty(k, JsonPrimitive(v))
    }

    internal suspend fun run(lifecycle: Lifecycle, controller: TrailMapController) = coroutineScope {
        AppLog.i(TAG, "host start (${renderers.kinds.size} kinds)")
        try {
            launch { lifecycle.currentStateFlow.collect { resumed = it.isAtLeast(Lifecycle.State.RESUMED); announcer.active = resumed } }
            launch { snapshotFlow { controller.mapState }.collect { map = it } }
            launch { snapshotFlow { mapReduced }.collect { refresh() } }
            // Ambient idle stop: 20 s after the last tap / camera move (PF-2).
            launch {
                snapshotFlow { interactions }.collectLatest {
                    idle = false
                    delay(FxCaps().idleStopMs)
                    idle = true
                }
            }
            // Map events: global state after a style load (S1 #1), gesture moves (S4), interaction for idle.
            launch {
                snapshotFlow { map }.collectLatest { m ->
                    if (m == null) return@collectLatest
                    // The first StyleLoaded can land before this collector subscribes: write the globals now too.
                    rewriteGlobals(m)
                    var gestureMove = false
                    viewDirty = true
                    m.events.collect { e ->
                        when (e) {
                            is MapEvent.StyleLoaded -> rewriteGlobals(m)
                            is MapEvent.CameraMoveStarted -> {
                                noteInteraction()
                                viewDirty = true
                                gestureMove = m.cameraMoveReason is CameraMoveReason.Gesture
                            }
                            is MapEvent.CameraMoved -> {
                                viewDirty = true
                                if (m.cameraMoveReason is CameraMoveReason.Gesture) gestureMove = true
                            }
                            is MapEvent.CameraMoveEnded -> {
                                viewDirty = true
                                // Only a gesture move hid the overlay; programmatic moves keep it (no fade).
                                if (gestureMove || m.cameraMoveReason is CameraMoveReason.Gesture) gestureEndedAtMs = clock.frameMs
                                gestureMove = false
                            }
                            else -> Unit
                        }
                    }
                }
            }
            // Reduced motion: real-time parts step once a minute while the map is up (the clock is stopped).
            launch {
                snapshotFlow { reducedTick }.collectLatest { on ->
                    while (on) {
                        driver.rest(liveByKind, clock.frameMs)
                        delay(FxClockPolicy.REDUCED_TICK_MS)
                    }
                }
            }
            // The clock: withFrameNanos every frame while not STOPPED, publishing through the gate (S1 #3). On the
            // transition to STOPPED every kind writes its rest frame (PF-2): nothing freezes mid-blink or mid-sway.
            snapshotFlow { mode }.collectLatest { m ->
                clock.mode = m
                driver.onMode(m, liveByKind, clock.frameMs)
                if (m == FxClockPolicy.Mode.STOPPED) return@collectLatest
                gate.restart()
                while (true) {
                    withFrameNanos { nanos ->
                        val out = gate.onFrame(nanos, m)
                        if (out and FxFrameGate.OVERLAY != 0) {
                            clock.frame.longValue = gate.sceneMs
                            refresh()
                        }
                        if (out and FxFrameGate.LAYER != 0) writeGlobals()
                    }
                }
            }
        } finally {
            clock.mode = FxClockPolicy.Mode.STOPPED
            announcer.active = false
            AppLog.i(TAG, "host stop")
        }
    }

    private companion object {
        const val SUMMARY_INTERVAL_MS = 33L
    }
}

/**
 * The Layer-tier drivers of one host (engine PF-2): per-frame `globalState` writes while the clock runs, and the rest
 * frame when it stops. Pure (no map, no clock): FxHostState feeds it. Main thread only.
 */
internal class FxLayerDriver(private val renderers: FxRendererRegistry, private val sink: FxGlobalStateSink) {
    private var lastMode = FxClockPolicy.Mode.STOPPED

    /** One driver frame (≤ 30 Hz, only while the clock runs). */
    fun frame(liveByKind: Map<String, List<FxLive>>, nowMs: Long, view: FxView?) {
        for ((kind, live) in liveByKind) renderers[kind]?.writeGlobalState(live, nowMs, view, sink)
    }

    /** Every kind's rest values ([FxRenderer.writeRestState]). */
    fun rest(liveByKind: Map<String, List<FxLive>>, nowMs: Long) {
        for ((kind, live) in liveByKind) renderers[kind]?.writeRestState(live, nowMs, sink)
    }

    /** The clock's mode changed to [mode]: on the transition to STOPPED, write the rest frame. */
    fun onMode(mode: FxClockPolicy.Mode, liveByKind: Map<String, List<FxLive>>, nowMs: Long) {
        if (mode == FxClockPolicy.Mode.STOPPED && lastMode != FxClockPolicy.Mode.STOPPED) rest(liveByKind, nowMs)
        lastMode = mode
    }

    companion object {
        /**
         * [next] with each kind's previous list kept when its instance set is unchanged (same instances, same
         * reduced / demoted / final-frame / dismissal), so one kind's change does not rebuild every kind's GeoJSON.
         */
        fun reuseUnchanged(prev: Map<String, List<FxLive>>, next: Map<String, List<FxLive>>): Map<String, List<FxLive>> {
            val out = LinkedHashMap<String, List<FxLive>>(next.size)
            for ((kind, live) in next) {
                val old = prev[kind]
                out[kind] = if (old != null && sameSet(old, live)) old else live
            }
            return out
        }

        private fun sameSet(a: List<FxLive>, b: List<FxLive>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) {
                val x = a[i]
                val y = b[i]
                if (x.instance != y.instance || x.reduced != y.reduced || x.demoted != y.demoted || x.finalFrame != y.finalFrame) return false
            }
            return true
        }
    }
}

/**
 * FxHost for one map (engine §2.2). Call only inside the Play gate (`if (inputs != null)`): when Play is off nothing
 * here is composed or remembered. [controller] is the TrailMap's controller (the host needs its live MapState).
 */
@Composable
fun rememberFxHost(inputs: FxHostInputs, controller: TrailMapController): FxHostState {
    val host = remember(inputs.renderers) { FxHostState(inputs.renderers) }
    val reduced = BtTheme.motion.reduced
    val powerSave = rememberPowerSaveMode()
    val palette = rememberFxPalette()
    SideEffect {
        host.reduced = reduced
        host.powerSave = powerSave
        host.ambientOff = inputs.ambientOff
        host.palette = palette
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(host, controller, lifecycle) { host.run(lifecycle, controller) }
    return host
}

/**
 * Calls [content] with [host]'s `renderOptions` (Standard when Play is off). The read happens in this scope, so a
 * `maximumFps` toggle re-runs only the map call inside [content], not the screen around it.
 */
@Composable
fun WithFxRenderOptions(host: FxHostState?, content: @Composable (RenderOptions) -> Unit) {
    content(host?.renderOptions ?: RenderOptions.Standard)
}
