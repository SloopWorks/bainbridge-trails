# MapFx — map effects engine (ADR + API design + plan)

Date: 2026-10-20 · Status: **PROPOSED** (needs ACCEPT per `gates.design_before_build`) · Work item: `docs/play/brief.md`
acceptance criterion 6 (and the engine half of 3, 7, 9). Author role: plan-author (architecture).

Proposed decision ids **PF-1 … PF-9** (copy into `docs/play/decisions.md` on ACCEPT; that file does not exist yet).

Everything marked **[verified]** was read from the maplibre-compose 0.20.0 Android AAR in the Gradle cache
(`~/.gradle/caches/modules-2/files-2.1/org.maplibre.compose/maplibre-compose-android/0.20.0/…/maplibre-compose.aar`,
`javap` with the LocalVariableTable for parameter names — no `-sources.jar` is published to the cache for 0.20.0) or
from `ui-graphics-1.11.1-sources.jar` (CMP; 1.12.2 sources are not cached — assumed unchanged, see Risks). Everything
marked **[spike]** could not be settled from bytecode and has a ≤1 h spike in §5.

---

## 0. Verified library facts this design relies on

### maplibre-compose 0.20.0

| What | Signature (Kotlin, reconstructed from bytecode) | Notes |
|---|---|---|
| Map | `MaplibreMap(modifier, state: MapState, viewportInsets: PaddingValues, cameraConstraints, renderOptions: RenderOptions, interactions: MapInteractions, uiOptions: MapUiOptions, overlay: @Composable MapOverlayScope.() -> Unit)` | No `onFrame` param. |
| State | `rememberMapState(runtime, baseStyle, initialCameraPosition, content: @Composable @MaplibreComposable () -> Unit): MapState` | Layers are composed in the **map's style composition**, not the UI composition. |
| Camera read | `MapState.cameraPosition: CameraPosition`, `MapState.viewport: Viewport?`, `isCameraMoving: Boolean`, `cameraMoveReason` | `viewport` is backed by a `MutableState` in `MapAttachment` (`viewportState$delegate`) → snapshot-observable. |
| Viewport | `Viewport(cameraPosition, size: DpSize, visibleBounds, visibleRegion, metersPerDpAtCenter: Double)` | |
| CameraPosition | `CameraPosition(center: Position, zoom, bearing, pitch, padding: DpPadding)` | |
| Projection | `MapState.screenLocationFromPosition(Position): DpOffset?`, `positionFromScreenLocation(DpOffset): Position?`, `metersPerDpAtLatitude(Double): Double?` | **Synchronous**; each call takes `MlnFfiLock` and calls MapLibre Native through FFI on the *mirrored* viewport. |
| Frame-synced projection | `MapState.overlayScreenLocationFromPosition(...)` is **internal**; public access is `MapOverlayScope.placedAt(position, alignment)` / `placedTowards(position, PlacedTowardsState)` | `GeographicLayout`'s placement block reads `state.viewport` and the presented-frame projection (`NativeViewport.presentationRevision: MutableLongState`) → children are **re-placed (not recomposed) on every presented map frame**, in sync with the pixels on screen. |
| Overlay host | `MapOverlayHost(overlay, mapState, viewportInsets, modifier)` = `CompositionLocalProvider(LocalViewport providesComputed …) { GeographicLayout { overlay() } }` | Reading `LocalViewport.current` **in composition** recomposes on every camera change — never do it in FX code; read `state.viewport` in layout/draw. `MapOverlayScope : BoxScope`. |
| Locals | `LocalMapState`, `LocalViewport` (`org.maplibre.compose.map`) | |
| Events | `MapState.events: Flow<MapEvent>`; `MapEvent.FrameRendered(stats: RenderStats)`, `CameraMoveStarted/Moved/MoveEnded`, `Idle`, `StyleLoaded`, … | `RenderStats(mode, needsRepaint, placementChanged, encodingTime: Duration, renderingTime: Duration, frameCount, drawCallCount, totalDrawCallCount)` → free MapLibre per-frame cost for the HUD. |
| Render opts | `RenderOptions { maximumFps: Int?; tileLod; debug { tileBorders; collisionBoxes … }; cameraProjection }`; `RenderOptions.Standard/Debug` | |
| UI opts | `MapUiOptions { loadColor; renderMode: AndroidRenderMode }`, default **`AndroidRenderMode.Surface`** (`Texture` available) | Surface mode: Compose `graphicsLayer`/`RenderEffect` cannot post-process the map pixels themselves. |
| Global state | `MapState.style.globalState: StyleGlobalState` → `setProperty(name: String, value: JsonElement)`, `resetProperty`, `suspend get(): JsonObject`; DSL `globalState(name): Expression<AnyValue>` (`expressions.dsl.InputsKt`) | One write animates every expression that reads it — no layer recomposition, no GeoJSON rebuild. Native support per property **[spike S1]**. |
| Feature state | `MapState.style.sources[geoJsonSource]: GeoJsonSourceHandle` → `setFeatureState(id: String, state: JsonObject)`, `removeFeatureState(id, key?)`, `resetFeatureStates()`, `suspend getFeatureState(id)`; DSL `feature.state("key")` | Ids on GeoJSON features **[spike S2]** (no `promoteId` in `GeoJsonOptions`). |
| Source data | `rememberGeoJsonSource(data: GeoJsonData, from: GeoJsonOptions, block: GeoJsonOptions.Builder.() -> Unit)`; `GeoJsonData.JsonString/Features/Uri`; `MutableGeoJsonSourceHandle.setData(GeoJsonData)`; options incl. `lineMetrics`, `synchronousTiling`, `cluster*` | `lineMetrics` enables `feature.lineProgress()` + `LineLayer(gradient=…)` (trail "drawing" sweeps). |
| Image source | `rememberImageSource(PositionQuad, url: String)` / `(PositionQuad, PreparedImage)` → `RasterLayer(id, source, minZoom, maxZoom, visible, opacity…, resampling, fadeDuration)` | Geo-pinned bitmaps (e.g. a "sea chart" vignette). |
| Images | `image(ImageBitmap, sdf, stretch)`, `image(Painter, DpSize, sdf, stretch, density, colorFilter)`, `image(name)`; `style.images.set(name, ResolvedStyleImage(PreparedImage, sdf, stretch))`; `PreparedImage.fromBitmap(ImageBitmap)` | Sprite registration for SymbolLayer. |
| Layers | `LineLayer(id, source, sourceLayer, minZoom, maxZoom, filter, visible, sortKey, translate…, opacity, layerOpacity, color, dasharray, pattern, gradient, blur, width, gapWidth, offset, cap, join, …, onClick, onLongClick, onDoubleClick, hitPadding)`; `CircleLayer(… opacity, color, blur, radius, strokeOpacity, strokeColor, strokeWidth, pitchScale, pitchAlignment, onClick…)`; `SymbolLayer(… iconImage, iconOpacity, iconColor, iconHalo*, iconSize, iconRotate, iconAnchor, iconOffset, iconAllowOverlap, iconIgnorePlacement, textField, …)`; `FillLayer(… opacity, layerOpacity, color, pattern, antialias, outlineColor …)`; `HeatmapLayer(… color, opacity, radius, weight, intensity …)`; also Background/FillExtrusion/Hillshade/ColorRelief/LocationIndicator | Every paint property has a `…Transition: TransitionOptions` (native, GPU-side tweening). |
| Z-order | `Anchor.Top {}`, `Anchor.Bottom {}`, `Anchor.Above(layerId) {}`, `Anchor.Below(layerId) {}`, predicate overloads `Above/Below((LayerSummary) -> Boolean)`, `Anchor.At(anchor) {}`; `LocalAnchor` | StatusLayers sits inside `Anchor.Below("trail-label")`. |
| Expression DSL | `const, feature["k"], feature.state, feature.id(), feature.lineProgress(), feature.distance/within, zoom(), globalState, interpolate(linear()/exponential()/cubicBezier(), …), step, case/switch, coalesce, all/any, plus/minus/times/div/rem, sin/cos, min/max, convertToNumber, asNumber/asString/asBoolean, rgbColor, interpolateHcl/Lab, image, format` | `sin`, `rem` and `globalState` make GPU-side periodic motion possible. |
| Layer handles | `style.layers[id]: LayerHandle` → `asMutable.setPaintProperty(name, JsonElement)`, `setLayoutProperty`, `setPaintTransition` | Escape hatch; prefer composable params. |

Repo facts: rotation and pitch gestures are disabled (`chartInteractions()`), `fitTo` forces `bearing=0, pitch=0`;
`mercatorOffsetDp` (`map/MapLogic.kt`) already projects relative to a reference point with `WORLD_DP = 512·2^zoom` and is
used for tap ranking; `TrailMap` owns the `overlay` slot privately (`TrailMapOverlay`); a tab switch disposes the map.

### Compose Multiplatform (ui-graphics 1.11.1 sources; app is on 1.12.2)

- Common: `withFrameNanos` (runtime), `Modifier.drawWithCache`, `Modifier.graphicsLayer`, `Path`/`PathMeasure`,
  `expect class Shader`, `ShaderBrush(shader)` and `abstract class ShaderBrush { createShader(size) }`,
  `expect sealed class RenderEffect` with `BlurEffect`, `OffsetEffect` only. **No runtime shader in commonMain.**
- Android: `actual typealias Shader = android.graphics.Shader` → `android.graphics.RuntimeShader` (API 33+, AGSL).
- Skiko (iOS): `actual class Shader` wrapping `org.jetbrains.skia.Shader`; `SkShader.asComposeShader()`;
  `org.jetbrains.skia.RuntimeEffect.makeForShader(sksl)` + `RuntimeShaderBuilder` (SkSL).

### Lottie for CMP — compottie

`io.github.alexzhirkevich:compottie:2.3.3` (Maven Central, 2026-10-05). Targets: android, iosArm64,
iosSimulatorArm64, desktop, js, wasmJs, macosArm64. Built against Kotlin 2.4.0 and CMP foundation **1.12.1** (compatible
with ours). `compottie-core` (~1.76 MB AAR) pulls `keight-core` (a JS engine for Lottie expressions), okio, atomicfu,
kotlinx-serialization; `compottie-lite` and `compottie-resources` also exist. Recommendation in PF-6.

---

## 1. Decision record

### Context

Play needs animated things *on* the map (glinting treasures, a kraken grabbing a shore road, ferries crossing, Frog
Rock blinking, Grand Forest creatures), plus full-screen moments. The map is MapLibre Native rendered by maplibre-compose
0.20 into its own surface; Compose draws UI on top. Effects must be geo-anchored and zoom-correct, cheap on battery,
reduced-motion aware, testable without a device, and invisible (zero cost) when Play is off. The app's existing motion
idiom is a single clock + `phase(t, from, to)` + `PathMeasure` (CelebrationScreen / IslandCelebration).

### PF-1 (A) Where effects render — three tiers

Options considered:
1. Everything as MapLibre layers (GeoJSON + expressions). GPU, zoom-correct, cheap to keep static, but rich 2D motion
   (curling tentacles, particles, morphs) is impossible or needs per-frame `setData` (re-tiling on a worker each
   frame — the reason `TrackThrottle` exists).
2. Everything as Compose overlay. Rich, but each sprite must be projected per frame, does not z-interleave with map
   layers (always above labels and trail lines), and many always-on markers would burn Compose draw time.
3. **Three tiers with one scene model** (chosen).

Decision:

| Tier | Renders in | Use for | Animated by | Cost model |
|---|---|---|---|---|
| **Layer** | `TrailMap` `content` (MapLibre style composition) | Many, persistent, geo-anchored items: treasure/egg markers, obstacle tints, puzzle pins, race corridors, far-zoom LOD of every overlay effect | (1) native paint `…Transition` (one-shots, ping-pong pulses), (2) `globalState` expressions written ≤30 Hz by the FxClock, (3) `feature.state` for per-instance state changes (found/armed), (4) GeoJSON `setData` ≤ 4 Hz for moving things (ferry position) | Any animated layer makes MapLibre re-render the **whole map frame**; budget it (§3). Static layers cost nothing per frame. |
| **Overlay** | `TrailMap` `overlay` slot (`MapOverlayScope`, Compose Canvas) | Few (≤3) rich 2D effects at near zoom: kraken tentacles, treasure burst, ferry wake, forest creature peeking | FxClock frame + `FxTimeline`; anchor placed with `placedAt` (frame-synced), geometry in anchor-local dp | Compose draw only; the map does **not** re-render for overlay animation. |
| **Moment** | Full-screen Compose scene above the shell (like `CelebrationScreen`) | Hero beats: "The kraken rises", riddle solved, race finish | Own clock (same `FxTimeline`), map scene paused | Map hidden/paused; full budget available. |

Rule: an effect may declare a Layer part and an Overlay part and a zoom hand-off between them (kraken: Symbol marker
below z13.5, tentacles at ≥z13.5). Moments are separate effects triggered by the scene, never drawn on the map.

### PF-2 (B) One frame clock

Options: per-effect `Animatable`/`rememberInfiniteTransition` (N coroutines, no global pause/budget, hard to test);
MapLibre `FrameRendered` events as the clock (only fires when the map renders — overlay animation would stall the map
or vice versa); **one `withFrameNanos` loop per map** (chosen).

Decision: `FxClock` — a single `LaunchedEffect` in `FxHost` running `withFrameNanos`, publishing `frameMs` in a
`MutableLongState` that is **read only in draw/layout phases** (never in composition). Policy (pure, in `:play`):

- runs only while: Play on ∧ lifecycle ≥ RESUMED ∧ map composed ∧ scene has ≥1 effect whose current phase animates;
- **reduced motion** ⇒ never runs; every effect renders its `reducedFrame` (a fixed, authored frame — not t=0);
- **idle**: when the scene holds only AMBIENT effects and there was no touch/camera move for 20 s, ambient loops settle to
  their rest frame and the clock stops until the next `CameraMoveStarted` or tap;
- ambient-only scenes tick at 30 Hz (`delay` to the next 33 ms boundary before `withFrameNanos`); HERO/NOTICE at
  display rate;
- ambient off while a walk is recording (battery first; heroes still play);
- time is the clock's own monotonic ms since scene start (pauses don't jump timelines forward); wall time is only for
  scheduling (other spec).

### PF-3 (C) Scene model

`FxSpec` (immutable description) → `FxScene` (pure state machine, `:play`) → `FxFrame` (what to draw at `nowMs`) →
`FxHost` (Compose renderer across tiers).

- **Effect** = `FxSpec(id, kind, anchor, zoom: ClosedFloatingPointRange<Double>, band, priority, timeline, loop,
  cooldownMs, announce)`; `FxAnchor` = `Point(GeoPoint)` | `Line(List<GeoPoint>)` | `Area(List<List<GeoPoint>>)` |
  `Screen(xFrac, yFrac)`.
- **Lifecycle** `Spawn → Run → Settle → Despawn → Done`, each a timeline phase range; `Run` may loop; `dismiss()` jumps
  to `Settle` (never cuts).
- **Priority** `AMBIENT < NOTICE < HERO`. Caps: 1 HERO live (others queue FIFO, expire after 10 s in queue), global HERO
  cooldown 8 s after a HERO's `Done`, ≤4 NOTICE, ≤12 animated AMBIENT, ≤3 Overlay-tier live in total (lowest priority
  oldest-first demoted to its Layer LOD or reduced frame). A spawn returns `Spawned | Queued | Rejected(reason)`.
- **Deterministic ordering**: draw order = `(band, priority, spawnSeq)`; `spawnSeq` is a scene counter, never a hash
  or wall clock. Same input sequence ⇒ same frames (unit-tested).
- **Bands** (z-order), relative to StatusLayers (all of which live in `Anchor.Below("trail-label")`):

| Band | Placement | Example |
|---|---|---|
| `UNDER_STATUS` | composed **before** `StatusLayers` inside `Anchor.Below("trail-label")` | water churn fill, area tint, race corridor |
| `OVER_STATUS` | composed **after** `StatusLayers`, same anchor (above `app-selected-*`, below labels) | treasure halos, glint sweep along a trail |
| `OVER_LABELS` | `Anchor.Top`, composed **before** `LocationIndicatorLayer` in MapTab | treasure icons, kraken far-zoom marker |
| `OVERLAY` | Compose overlay slot, under TrailMap chrome (scale bar, logo, credit) | tentacles, bursts |
| `MOMENT` | full screen | hero scenes |

Composition-order = z-order inside one anchor is assumed **[spike S3]**. FX layer ids: `fx-<kind>-<part>` (one layer
set per *kind*, instances are features) — never `app-…`, so `TappableTrailLayerIds`/StatusLayerSpec are untouched.

### PF-4 (D) Projection — geo → overlay px

Options measured against the bytecode:

| Option | Frame sync | Cost / point | Accuracy (north-up, untilted) |
|---|---|---|---|
| P1 `placedAt(anchor)` + anchor-local Web-Mercator offsets for geometry | **synced** to presented map frame (library re-places on `presentationRevision`) | 1 FFI call (locked) per **anchor**; vertices are pure math (~20 flops) | exact: Mercator y is computed exactly (`ln((1+s)/(1-s))`), x linear; error = double rounding |
| P2 `screenLocationFromPosition` per vertex | mirrored viewport — may lead/lag the presented frame by one frame **[spike S4]** | 1 locked FFI per vertex | exact incl. tilt |
| P3 full Mercator from `viewport.cameraPosition.center/zoom` + `size` + `padding` | same as P2 | 0 FFI | exact if padding handled; same sync risk |

Decision: **P1**. Each overlay effect is one `Box(Modifier.placedAt(anchorPos, Alignment.Center).size(extentDp))`
whose `Canvas` draws in anchor-local dp; local offsets come from `FxProjection.offsetDp(anchor, p, zoom)` (the
`mercatorOffsetDp` math, moved/duplicated into `:play` with a parity test). `zoom` is read from `state.viewport` in the
**draw** phase only. Guard: if `bearing != 0 || pitch != 0` (impossible with `chartInteractions`, but defensive) the
effect falls back to P2 per vertex. Do not read `LocalViewport` in composition (it recomposes per camera change).

### PF-5 (B/C) Rendering contract per tier

- Layer-tier parts are `@MaplibreComposable` functions that take **only stable inputs** (feature collection string,
  palette, zoom range) — never the frame time. Motion reaches them through `globalState("fx.<kind>.t")`, transitions or
  feature state. Fallback if S1 fails: `FxLayerDriver` recomposes ≤4 layers' `const(value)` at ≤30 Hz.
- Overlay-tier parts draw with `Modifier.drawWithCache { … onDrawBehind { val t = clock.frameMs … } }`, reuse `Path`
  objects (`rewind()`), preallocated `FloatArray` rigs, no per-frame allocation (verified in the HUD with an allocation
  counter on debug builds — see §3).
- Overlay content is decorative: `clearAndSetSemantics {}`, no pointer input except explicit 48 dp tap targets an
  effect declares (`FxTapTarget`) with `Role.Button` + label. FxHost owns **one** polite live region that announces
  HERO/NOTICE spawns and resolutions via `FxSpec.announce` (strings through `GoalVocabulary`).

### PF-6 (E) Asset model

- **Vector art = Kotlin.** Each motif is an SVG authored in `design/fx-art/*.svg`, converted by
  `scripts/fx-art.py` into a generated `FxArt.kt` of path-data `const val`s (checked in); parsed once, lazily, with
  `PathParser().parsePathString(d).toPath()` (common) and cached per kind. No runtime file IO, no SVG parser in the app.
- **Sprites** (only where vector is too costly: foam, sparkle frames): PNG strips in
  `composeResources/drawable/fx_*.png`, ≤ 256×256 per frame, ≤ 2 MB decoded total; registered once per style load as
  map images (`style.images.set`) for the Layer tier, drawn with `drawImage(srcOffset, srcSize)` for the Overlay tier.
- **Lottie: not now.** compottie 2.3.3 is current and targets our platforms, but adds ~2 MB (core + JS engine + okio),
  R8 rules, and a per-instance interpreter whose map-overlay cost is unmeasured. Hand-written Canvas timelines fit the
  existing idiom and the "Chart room" line-art look. Re-open **only** for the Moment tier if a designer delivers
  After Effects scenes, behind spike S6, using `compottie-lite`/`compottie-resources` if expressions are not needed.
- **Shaders (optional polish).** `expect fun rememberFxShaderBrush(kind: FxShaderKind, uniforms: FxUniforms): Brush?`
  — Android: `RuntimeShader` (API ≥33) wrapped in `ShaderBrush`; iOS: `RuntimeEffect.makeForShader` →
  `RuntimeShaderBuilder` → `asComposeShader()` → `ShaderBrush`; returns **null** on Android < 33 or on compile failure,
  and every caller has a gradient/vector fallback. One SkSL/AGSL-compatible source per shader (`half4 main(float2)`),
  kept in `FxShaders.kt`. Shaders are overlay-tier only (they cannot touch the Surface-mode map pixels).

### PF-7 (F) Budgets & instrumentation

`FxStats` (debug only) samples: FxHost overlay draw time (TimeSource.Monotonic around draw), live effects per tier,
clock state (running/paused/why), and MapLibre `RenderStats` from `state.events.filterIsInstance<FrameRendered>()`
(`renderingTime`, `encodingTime`, `drawCallCount`). Shown in a HUD on the FX showcase and in a debug-drawer panel
"Play FX" (`factory.debug_drawer: true`). Budgets in §3. A runtime `FxQuality` (HIGH/MED/LOW) steps down when the p95
overlay draw time exceeds budget for 2 s.

### PF-8 (G/H) Testability and module layout

New KMP module **`:play`** (pure Kotlin; jvm + android + iosArm64 + iosSimulatorArm64; deps: stdlib,
kotlinx-serialization, kotlinx-coroutines-core; **no Compose, no MapLibre, no `:composeApp`**). FX lives in
`com.bainbridgetrails.play.fx`: `FxTimeline`, `FxEasing` (pure cubic-bezier solver with the `BtEasing` control
points), `FxScene`, `FxClockPolicy`, `FxProjection`, rigs (`TentacleRig`), `FxSpec` model, `FxStatsWindow`
(percentiles). Rules/points/scheduling/content from the other Play specs share the module.

`composeApp/src/commonMain/kotlin/com/bainbridgetrails/play/fx/` holds Compose + MapLibre rendering: `FxClock`,
`FxHost` (+ `FxLayers`, `FxOverlay`), `FxShaderBrush` (expect; actuals in `androidMain`/`iosMain`), `FxArt`, effects
under `play/fx/effects/`, `FxShowcaseScreen`, `FxHud`.

Dependency rules: `:composeApp → :play`; `:play` never depends on `:composeApp`, Compose or MapLibre (enforced by its
build file having no Compose plugin); nothing under `map/` or `ui/` imports `play.*` except the three gated call sites
(MapTab, TrailMap's new slot param, AppShell for moments/showcase).

Tests: timelines, scene, clock policy, projection, rigs → `:play:jvmTest` (no emulator). GeoJSON builders, layer-id
guards, palette guard → `:composeApp:testAndroidHostTest`. Visuals → FX showcase screenshots on both platforms in
`docs/play/screens/fx-*.png` (`verify.ui_snapshot: none`).

### PF-9 (I) Pure mode stays untouched

- `TrailMap` gains `fxOverlay: (@Composable MapOverlayScope.() -> Unit)? = null` drawn **before** the chrome; `null`
  ⇒ identical composition to today.
- MapTab composes `FxHost` parts only `if (play.enabled)`; when off no `FxClock`, no `FxScene`, no sources, no layers,
  no state objects are created (the gate is a plain `if`, not a `remember` that survives).
- Guards: host test asserts every FX layer id starts with `fx-` and is not in `TappableTrailLayerIds`; host test that
  `FxPalette` colors are ≥ ΔE 20 from every status color in both palettes (brief: status hues reserved); a debug-build
  assertion in the showcase lists `state.style.layers` and fails if any `fx-` layer exists with Play off.

### Consequences

- (+) Persistent content is GPU-cheap and zoom-correct; rich motion is confined to ≤3 overlay effects; one clock gives
  one place for pause, reduced motion, battery and budget.
- (+) Almost all logic is JVM-testable; rendering is thin.
- (−) Layer-tier animation re-renders the full map each frame — must be scarce, 30 Hz, and in-viewport only.
- (−) Overlay effects always sit above map labels (Compose over the map surface) — acceptable for transient effects;
  persistent markers must be Layer tier.
- (−) Relies on `globalState` and feature-state working through MapLibre Native FFI (spikes S1/S2 before Stage 5),
  with a documented fallback.
- (−) `TrailMap` gets one new optional parameter; `MapTab` gets one gated block.

---

## 2. API sketch

### 2.1 `:play` (pure Kotlin)

```kotlin
package com.bainbridgetrails.play.fx

data class GeoPoint(val lon: Double, val lat: Double)

sealed interface FxAnchor {
    data class Point(val at: GeoPoint) : FxAnchor
    data class Line(val path: List<GeoPoint>) : FxAnchor
    data class Area(val rings: List<List<GeoPoint>>) : FxAnchor
    data class Screen(val xFrac: Float, val yFrac: Float) : FxAnchor
}

enum class FxBand { UNDER_STATUS, OVER_STATUS, OVER_LABELS, OVERLAY, MOMENT }
enum class FxPriority { AMBIENT, NOTICE, HERO }
enum class FxStage { SPAWN, RUN, SETTLE, DESPAWN, DONE }

/** One easing curve, same control points as ui/theme/BtMotion.kt (parity-tested against Compose's CubicBezierEasing). */
class FxEasing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun transform(f: Float): Float = TODO("Newton + bisection solve, no allocation")
    companion object {
        val Linear = FxEasing(0f, 0f, 1f, 1f)
        val Emphasized = FxEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = FxEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = FxEasing(0.3f, 0f, 0.8f, 0.15f)
    }
}

/** Lifecycle stage boundaries in ms from spawn; RUN may loop with [runLoopMs]. */
data class FxTimeline(
    val spawnMs: Int, val runMs: Int, val settleMs: Int, val despawnMs: Int,
    val runLoopMs: Int? = null,
    /** The authored frame shown under reduced motion (and as the idle rest frame). */
    val reducedAtMs: Int,
) {
    val totalMs: Int get() = spawnMs + runMs + settleMs + despawnMs
    fun stageAt(localMs: Long, dismissedAtMs: Long? = null): FxStage = TODO()
    /** Local time folded into the RUN loop (so RUN can last forever while live). */
    fun foldedMs(localMs: Long, dismissedAtMs: Long? = null): Int = TODO()
    /** Same contract as CelebrationScreen's phase(t, from, to). */
    fun phase(t: Int, from: Int, to: Int, easing: FxEasing = FxEasing.Linear): Float =
        easing.transform(((t - from).toFloat() / (to - from)).coerceIn(0f, 1f))
}

data class FxSpec(
    val id: String,                    // stable content id, e.g. "kraken:rolling-bay-rd"
    val kind: String,                  // renderer key, e.g. "kraken", "treasure-glint"
    val anchor: FxAnchor,
    val zoom: ClosedFloatingPointRange<Double>,
    val band: FxBand,
    val priority: FxPriority,
    val timeline: FxTimeline,
    val loop: Boolean,
    val cooldownMs: Long = 0,
    val seed: Long = id.hashCode().toLong(),  // per-instance variation, deterministic
    val announceKey: String? = null,          // string resource key, resolved through GoalVocabulary
)

data class FxInstance(val spec: FxSpec, val seq: Long, val spawnedAtMs: Long, val dismissedAtMs: Long? = null) {
    fun localMs(nowMs: Long) = nowMs - spawnedAtMs
}

sealed interface SpawnResult {
    data class Spawned(val seq: Long) : SpawnResult
    data class Queued(val position: Int) : SpawnResult
    data class Rejected(val reason: String) : SpawnResult   // cooldown, cap, duplicate id
}

/** Pure state machine; all times are FxClock ms (monotonic, pause-aware). Not thread-safe: Main only. */
class FxScene(private val caps: FxCaps = FxCaps()) {
    fun spawn(spec: FxSpec, nowMs: Long): SpawnResult = TODO()
    fun dismiss(id: String, nowMs: Long) { TODO() }
    /** Advances stages, retires DONE, promotes queued HEROs, applies caps. Returns the draw list (deterministic order). */
    fun tick(nowMs: Long, reduced: Boolean): FxFrame = TODO()
    val isAnimating: Boolean get() = TODO()  // drives FxClockPolicy
}

data class FxCaps(val hero: Int = 1, val notice: Int = 4, val ambientAnimated: Int = 12, val overlay: Int = 3,
                  val heroCooldownMs: Long = 8_000, val heroQueueTtlMs: Long = 10_000)

data class FxFrame(val nowMs: Long, val live: List<FxLive>)              // sorted by (band, priority, seq)
data class FxLive(val instance: FxInstance, val stage: FxStage, val t: Int, val reduced: Boolean)

object FxClockPolicy {
    enum class Mode { STOPPED, AMBIENT_30HZ, FULL }
    fun mode(playOn: Boolean, resumed: Boolean, reduced: Boolean, scene: FxSceneSummary,
             msSinceInteraction: Long, recording: Boolean): Mode = TODO()
}
data class FxSceneSummary(val animatingHero: Boolean, val animatingNotice: Boolean, val animatingAmbient: Boolean)

/** Anchor-local Web-Mercator offsets in dp (north-up, untilted); same math as map/MapLogic.mercatorOffsetDp. */
object FxProjection {
    const val WORLD_DP = 512.0
    /** Writes (x, y) dp of each point relative to [anchor] into [out] (size 2·n). Allocation-free. */
    fun offsetsDp(anchor: GeoPoint, points: List<GeoPoint>, zoom: Double, out: FloatArray) { TODO() }
    fun metresToDp(metres: Double, lat: Double, zoom: Double): Double = TODO()
}
```

### 2.2 composeApp rendering core

```kotlin
package com.bainbridgetrails.play.fx

/** The one frame clock. frameMs is read in draw/layout only. */
@Stable
class FxClock internal constructor() {
    val frameMs: Long get() = _frame.longValue
    internal val _frame = mutableLongStateOf(0L)
    internal var mode by mutableStateOf(FxClockPolicy.Mode.STOPPED)
}

@Composable
internal fun rememberFxClock(scene: FxScene, policyInputs: () -> FxClockPolicy.Mode): FxClock {
    val clock = remember { FxClock() }
    val mode = policyInputs()
    LaunchedEffect(mode) {
        clock.mode = mode
        if (mode == FxClockPolicy.Mode.STOPPED) return@LaunchedEffect
        var last = -1L
        while (true) {
            if (mode == FxClockPolicy.Mode.AMBIENT_30HZ) delay(33)
            withFrameNanos { now ->
                val dt = if (last < 0) 0 else ((now - last) / 1_000_000).coerceAtMost(100) // clamp: pauses never jump
                last = now
                clock._frame.longValue += dt
            }
            if (!scene.isAnimating) break   // re-evaluated by policyInputs on the next state change
        }
    }
    return clock
}

/** What a renderer kind contributes. One object per kind (not per instance): instances are data. */
interface FxRenderer {
    val kind: String
    /** Map layers for every live instance of this kind, in this band. Inputs must not change per frame. */
    @Composable @MaplibreComposable
    fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {}
    /** Compose overlay for one live instance (only for OVERLAY band and zoom inside spec.zoom). */
    @Composable
    fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {}
}

class FxLayerContext(val map: MapState, val clock: FxClock, val palette: FxPalette, val reduced: Boolean)
class FxOverlayContext(val map: MapState, val clock: FxClock, val palette: FxPalette, val reduced: Boolean,
                       val quality: FxQuality, val stats: FxStats?)

/** Hosts the scene. Split in three entry points because TrailMap has two slots and moments live in the shell. */
class FxHostState(val scene: FxScene, val renderers: Map<String, FxRenderer>, internal val clock: FxClock)

@Composable @MaplibreComposable
fun FxLayers(host: FxHostState, band: FxBand, ctx: FxLayerContext) {
    // live list is recomputed only when the *set* of instances or stages changes (scene emits a version counter),
    // never per frame.
    val live = host.liveFor(band)
    for (kind in live.kindsInOrder()) host.renderers.getValue(kind).Layers(band, live.of(kind), ctx)
}

@Composable
fun MapOverlayScope.FxOverlay(host: FxHostState, ctx: FxOverlayContext) {
    for (item in host.liveFor(FxBand.OVERLAY)) key(item.instance.seq) {
        with(host.renderers.getValue(item.instance.spec.kind)) { Overlay(item, ctx) }
    }
}
```

Call sites (the only touch points in existing code):

```kotlin
// map/TrailMap.kt — new optional param, default null = today's composition
fun TrailMap(..., fxOverlay: (@Composable MapOverlayScope.() -> Unit)? = null, content: ...)
//   overlay = { fxOverlay?.invoke(this); TrailMapOverlay(...) }   // chrome stays on top

// ui/MapTab.kt
val fx = if (play.enabled) rememberFxHost(graph.play, reduced) else null
TrailMap(..., fxOverlay = fx?.let { h -> { FxOverlay(h, h.overlayContext()) } }) {
    Anchor.Below("trail-label") {          // StatusLayers re-enters the same anchor itself; nesting is a no-op order-wise [S3]
        fx?.let { FxLayers(it, FxBand.UNDER_STATUS, it.layerContext()) }
    }
    StatusLayers(...)
    fx?.let { h -> Anchor.Below("trail-label") { FxLayers(h, FxBand.OVER_STATUS, h.layerContext()) } }
    fx?.let { h -> Anchor.Top { FxLayers(h, FxBand.OVER_LABELS, h.layerContext()) } }
    if (locating && locationGranted) LocationIndicatorLayer(...)   // stays above every FX layer
}
```

`rememberFxHost` also: subscribes to `LifecycleResumeEffect` (resumed flag), `state.events` (`CameraMoveStarted`
resets idle), the recording flag MapTab already derives, and writes `globalState` once per clock frame for kinds that
declared global keys.

### 2.3 Example 1 — `TreasureGlint` (Layer tier, GeoJSON + `globalState` + feature-state)

```kotlin
object TreasureGlint : FxRenderer {
    override val kind = "treasure-glint"
    const val KEY_T = "fx.treasure.t"          // 0..1 sawtooth, 1.6 s period, written by FxHost at ≤30 Hz
    val timeline = FxTimeline(spawnMs = 400, runMs = 1_600, settleMs = 400, despawnMs = 300,
                              runLoopMs = 1_600, reducedAtMs = 900)

    /** Pure (host-tested): one Point feature per live treasure, numeric top-level id = instance seq (feature-state). */
    fun geoJson(live: List<FxLive>): String = buildString {
        append("""{"type":"FeatureCollection","features":[""")
        live.forEachIndexed { i, l ->
            val p = (l.instance.spec.anchor as FxAnchor.Point).at
            if (i > 0) append(',')
            append("""{"type":"Feature","id":""").append(l.instance.seq)
            append(""","properties":{"phase":""").append((l.instance.spec.seed and 0xFF) / 255.0) // de-sync
            append("""},"geometry":{"type":"Point","coordinates":[""").append(p.lon).append(',').append(p.lat).append("]}}")
        }
        append("]}")
    }

    @Composable @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {
        val ids = live.map { it.instance.seq }                    // set changes on spawn/despawn only
        val json = remember(ids) { geoJson(live) }
        val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
        // per-feature 0..1 glint position: (t + phase) mod 1 — GPU-side, no recomposition
        val t = if (ctx.reduced) const(0.5f) else globalState(KEY_T).asNumber(const(0.5f))
        val local = (t + feature["phase"].asNumber(const(0f))) % const(1f)
        val found = feature.state("found").asBoolean(const(false))
        val fade = TransitionOptions(duration = if (ctx.reduced) Duration.ZERO else 400.milliseconds)
        val baseR = interpolate(linear(), zoom(), 13 to const(6.dp), 17 to const(14.dp))

        when (band) {
            FxBand.OVER_STATUS -> CircleLayer(                         // soft halo, breathes with t
                id = "fx-treasure-halo", source = source, minZoom = 13f,
                color = const(ctx.palette.treasureGlow), blur = const(0.6f),
                radius = baseR * (const(1f) + const(0.25f) * sin(local * const(2 * PI.toFloat()))),  // [S1] if not: const
                opacity = switch(condition(found, const(0f)), fallback = const(0.55f)),
                opacityTransition = fade,
            )
            FxBand.OVER_LABELS -> {
                SymbolLayer(                                          // chest/coin icon, always readable
                    id = "fx-treasure-icon", source = source, minZoom = 12f,
                    iconImage = image(FxArt.treasurePainter(), DpSize(28.dp, 28.dp)),
                    iconSize = interpolate(linear(), zoom(), 12 to const(0.6f), 16 to const(1f)),
                    iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
                    iconOpacity = switch(condition(found, const(0f)), fallback = const(1f)),
                    iconOpacityTransition = fade,
                )
                SymbolLayer(                                          // sparkle: short window of each period
                    id = "fx-treasure-sparkle", source = source, minZoom = 14f,
                    iconImage = image(FxArt.sparklePainter(), DpSize(18.dp, 18.dp)),
                    iconOffset = const(DpOffset(9.dp, (-11).dp)),
                    iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
                    iconOpacity = if (ctx.reduced) const(0f) else
                        interpolate(linear(), local, 0f to const(0f), 0.08f to const(1f), 0.22f to const(0f)),
                    iconRotate = local * const(90f),
                )
            }
            else -> Unit
        }
    }

    /** Discovery: feature state flips (native 400 ms fade); the scene spawns a HERO "treasure-burst" overlay effect. */
    fun markFound(map: MapState, source: GeoJsonSource, seq: Long) {
        map.style.sources[source].setFeatureState(seq.toString(), buildJsonObject { put("found", true) })   // [S2]
    }
}
```

Per-frame work while any glint is live and in view: one `globalState.setProperty(KEY_T, JsonPrimitive(t))` at 30 Hz
→ MapLibre re-renders at ≤30 Hz. Outside the viewport / below z13 the host stops writing `KEY_T` (the scene marks the
kind non-animating), so the map goes back to idle rendering. Fallback if S1 fails: halo radius/sparkle opacity become
`const(v)` fed from `clock.frameMs` through `FxLayerDriver` (recomposing 2 layers at 30 Hz, per-feature phase dropped).

Taps: MapTab's `onTap` first queries `setOf("fx-treasure-icon")` within 24 dp; a hit goes to Play (`PlayActions.tap`)
instead of trail selection. Reduced motion: static icon + static halo, found = instant hide. A11y: the live region
announces "Treasure found near <trail>" on resolution; treasures are also listed in the Play sheet (other spec), so
nothing is map-only.

### 2.4 Example 2 — `KrakenTentacles` (Overlay tier, projection + Canvas + phases)

```kotlin
// :play — pure, allocation-free rig (unit-tested)
class TentacleRig(val count: Int, val segments: Int, seed: Long) {
    val spine = FloatArray(count * segments * 2)      // dp, anchor-local
    val width = FloatArray(count * segments)
    /** Fills spine/width for local time [t] (ms) given the road polyline offsets [road] (dp) and water direction. */
    fun pose(t: Int, timeline: FxTimeline, road: FloatArray, waterDx: Float, waterDy: Float, reduced: Boolean) { TODO() }
}

object KrakenTentacles : FxRenderer {
    override val kind = "kraken"
    // Spawn: ripples + tentacles rise · Run: sway + grip (loops 2.4 s) · Settle: retract · Despawn: last ripple fades
    val timeline = FxTimeline(spawnMs = 700, runMs = 4_000, settleMs = 800, despawnMs = 500,
                              runLoopMs = 2_400, reducedAtMs = 1_500)     // reduced = mid-grip pose, no motion
    val zoom = 13.5..18.0                                                 // below: OVER_LABELS marker (Layers())

    @Composable @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {
        if (band != FxBand.OVER_LABELS) return
        // far-zoom LOD: a static kraken glyph on the water point; maxZoom hands off to the overlay
        val json = remember(live.map { it.instance.seq }) { krakenPointsGeoJson(live) }
        SymbolLayer(id = "fx-kraken-marker", source = rememberGeoJsonSource(GeoJsonData.JsonString(json)),
            maxZoom = 13.5f, iconImage = image(FxArt.krakenGlyphPainter(), DpSize(32.dp, 32.dp)),
            iconAllowOverlap = const(true))
    }

    @Composable
    override fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {
        val spec = item.instance.spec
        val road = (spec.anchor as FxAnchor.Line).path
        val anchorGeo = road[road.size / 2]
        val rig = remember(spec.id, ctx.quality) { TentacleRig(count = ctx.quality.tentacles, segments = 14, spec.seed) }
        val roadDp = remember(road) { FloatArray(road.size * 2) }
        val brush = rememberFxShaderBrush(FxShaderKind.WaterCaustic, FxUniforms.time { ctx.clock.frameMs })  // null → gradient
        val extent = 320.dp                                   // generous box; Canvas does not clip
        Box(
            Modifier.placedAt(anchorGeo.toPosition(), Alignment.Center)   // frame-synced anchor (library)
                .size(extent)
                .clearAndSetSemantics {}
                .drawWithCache {
                    val tentacle = Path(); val sucker = Path(); val ripple = Path()
                    val fallbackWater = Brush.radialGradient(listOf(ctx.palette.krakenWater, Color.Transparent))
                    onDrawBehind {
                        val cam = ctx.map.viewport?.cameraPosition ?: return@onDrawBehind   // draw-phase read
                        if (cam.zoom !in zoom) return@onDrawBehind
                        if (cam.bearing != 0.0 || cam.pitch != 0.0) { /* P2 fallback: per-vertex screenLocationFromPosition */ }
                        val now = ctx.clock.frameMs                                         // draw-phase read
                        val t = if (item.reduced) timeline.reducedAtMs else timeline.foldedMs(item.instance.localMs(now), item.instance.dismissedAtMs)
                        FxProjection.offsetsDp(anchorGeo.toGeo(), road, cam.zoom, roadDp)
                        val c = center
                        // 1 water: ripples (Spawn) + caustic disc
                        val rise = timeline.phase(t, 0, 700, FxEasing.EmphasizedDecelerate)
                        drawCircle(brush ?: fallbackWater, radius = 60.dp.toPx() * rise, center = c + waterOffset(roadDp))
                        // 2 tentacles: rig fills spine/width; build a tapered outline per tentacle, rewinding paths
                        rig.pose(t, timeline, roadDp, waterDx = 1f, waterDy = 0f, reduced = item.reduced)
                        for (k in 0 until rig.count) {
                            tentacle.rewind(); sucker.rewind()
                            buildTaperedOutline(rig, k, c, density, tentacle, sucker)
                            drawPath(tentacle, ctx.palette.krakenBody)
                            drawPath(tentacle, ctx.palette.krakenRim, style = Stroke(1.2.dp.toPx()))
                            drawPath(sucker, ctx.palette.krakenSucker)
                        }
                        // 3 grip: the road under attack darkens + shakes (Run only, not reduced)
                        val grip = timeline.phase(t, 700, 1_100) * (1f - timeline.phase(t, 4_700, 5_200))
                        val shake = if (item.reduced) 0f else kotlin.math.sin(now / 45.0).toFloat() * 1.5.dp.toPx() * grip
                        drawRoadStroke(roadDp, c + Offset(shake, 0f), ctx.palette.krakenGrip.copy(alpha = 0.6f * grip))
                    }
                },
        )
        // Optional interaction: a 48dp tap target on the water point ("Face the kraken") with Role.Button + label.
        if (spec.announceKey != null) FxTapTarget(anchorGeo, label = fxString(spec.announceKey)) { /* PlayActions */ }
    }
}
```

Frame loop for this effect: `FxClock` advances `frameMs` → only the `drawWithCache` draw block is invalidated (it
read `frameMs` and `viewport`) → no recomposition, no relayout; `GeographicLayout` re-places the anchor box on every
presented map frame → tentacles stay glued to the road during pans/flings. Phases: Spawn 0–700 (ripples +
tentacles rise, emphasizedDecelerate), Run 700–4 700 (2.4 s sway loop while live, grip + shake), Settle 4 700–5 500
(retract, emphasizedAccelerate), Despawn 5 500–6 000 (ripple fades) → `DONE` → scene retires it and the far-zoom
marker goes too. Reduced motion: clock never runs for it; `t = reducedAtMs` gives a still mid-grip pose; the live
region says "The kraken is gripping <road>". Quality LOW: 3 tentacles × 10 segments, no suckers, no shader.

---

## 3. Performance plan

### Budgets (per frame, release build, mid device ≈ Pixel 6a / iPhone 12)

| Item | Budget |
|---|---|
| FxHost overlay draw (all overlay effects) | p95 ≤ 2.0 ms, p99 ≤ 4 ms |
| FX layer-tier animation | ≤ 30 Hz, only while an animating FX feature is in view at its zoom; MapLibre `renderingTime` p95 ≤ baseline + 1.5 ms |
| Live effects | 1 HERO, ≤4 NOTICE, ≤12 animated AMBIENT, ≤3 overlay-tier |
| Allocation | 0 objects per overlay frame in steady state (debug counter) |
| Jank (Android `gfxinfo`, overlay scenario) | janky frames ≤ 5 %, p90 ≤ 16 ms on 60 Hz |
| Memory | FX bitmaps ≤ 2 MB decoded; FX heap growth over a 10 min soak < 5 MB |
| Battery | clock STOPPED ≥ 95 % of map time when only ambient effects are live and the user is idle (HUD counter); no FX work in background |

### Method

Scenario: FX showcase screen (`--es screen fx --es fx <kind|all>` on Android; `-btScreen fx:<kind|all>` on iOS) with a
scripted camera path (pan + pinch, 20 s) and 3 overlay + 12 layer effects; plus the real Map tab with Play on.

- **Android (emulator for regressions, real device for numbers):** release build signed with the debug key
  (`-PreleaseSignWithDebugKey=true`), then `adb shell dumpsys gfxinfo com.bainbridgetrails.app reset` → run the
  scenario → `adb shell dumpsys gfxinfo com.bainbridgetrails.app framestats` (Compose/HWUI frames = overlay cost;
  the Surface-mode map renders outside HWUI, so its cost comes from `RenderStats` in the HUD log). Perfetto:
  `adb shell perfetto -o /data/misc/perfetto-traces/fx.pftrace -t 20s sched freq gfx view am wm dalvik` with
  `FxTrace.section("fx.draw")` (expect/actual: `android.os.Trace` / no-op on iOS) — inspect RenderThread, the
  MapLibre render thread and main thread overlap. Battery: `adb shell dumpsys batterystats --reset` → 10 min idle on
  the Map tab with ambient effects → `dumpsys batterystats` CPU time vs Play off.
- **iOS:** device build, Instruments Time Profiler + Animation Hitches + Metal System Trace (MapLibre is Metal);
  simulator only for functional screenshots.
- Each measurement goes into `docs/play/perf.md` (device, build, scenario, p50/p90/p95/p99, janky %, RenderStats).

### Over budget — in this order

1. Ambient overlay → 30 Hz; idle settle sooner (20 s → 8 s).
2. `FxQuality` step-down (fewer tentacles/segments, no suckers, no shader, no blur).
3. Demote overlay effects to their Layer-tier LOD (static symbol) beyond the overlay cap.
4. Stop layer-tier `globalState` animation (static halos); keep transitions only.
5. Cut the effect from the device class (quality LOW = no overlay ambient at all).

---

## 4. Staged implementation plan

```
PLAN: MapFx engine + two reference effects  (brief AC 6; engine parts of 3, 7, 9)
PRECONDITIONS: branch gamification/v1 (or a child branch from latest main per CLAUDE.md); this doc ACCEPTed;
  spikes S1–S4 done (S5–S6 before their stages); Play opt-in flag exists in AppSettings (owned by the Play core plan —
  if not landed yet, Stage 4 adds `play: Boolean = false`, JSON-default compatible).
```

**STAGE 0 — Spikes S1–S4** (§5). Code only in a throwaway branch / the existing style spike screen; results +
screenshots in `docs/play/spikes/fx.md`.
- files: `docs/play/spikes/fx.md`
- test-first: n/a (spike); each spike states pass/fail criteria up front
- verify: `cd app && ./gradlew :androidApp:assembleTrailsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- rollback: delete the spike branch; if S1/S2 fail, the doc's fallbacks become the plan (no stage changes order)

**STAGE 1 — `:play` module + timeline/easing**
- files: `app/settings.gradle.kts` (`include(":play")`), `app/play/build.gradle.kts` (copy of `:tracking` shape, no
  Compose), `app/play/src/commonMain/kotlin/com/bainbridgetrails/play/fx/{FxEasing,FxTimeline,FxModel}.kt`,
  `app/composeApp/build.gradle.kts` (`implementation(project(":play"))`), `.github/workflows/ci.yml` (+`:play:jvmTest`),
  `app/AGENTS.md` (module list + full verify set)
- test-first: `app/play/src/commonTest/.../fx/FxEasingTest.kt` (`emphasizedDecelerate matches Compose at 101 samples
  within 1e-3` — reference values pasted from a one-off Compose run), `FxTimelineTest.kt`
  (`stageAt boundaries`, `foldedMs loops RUN`, `dismiss jumps to SETTLE without cutting`, `phase equals CelebrationScreen
  phase`)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest`
- rollback: revert commit (module is unreferenced at runtime)

**STAGE 2 — `FxScene` + caps + `FxClockPolicy`**
- files: `play/fx/{FxScene,FxCaps,FxClockPolicy}.kt`
- test-first: `FxSceneTest` (`second HERO queues`, `hero cooldown 8s`, `queued hero expires after 10s`,
  `overlay cap demotes oldest ambient`, `same inputs same frames` (determinism), `reduced marks every live item reduced
  and isAnimating=false`); `FxClockPolicyTest` (`stopped when paused/reduced/play off`, `ambient-only idle 20s stops`,
  `recording disables ambient but not hero`, `30Hz when ambient only`)
- verify: `cd app && ./gradlew :play:jvmTest`
- rollback: revert

**STAGE 3 — Projection + rigs**
- files: `play/fx/{FxProjection,TentacleRig}.kt`; `composeApp/src/commonTest/.../map/FxProjectionParityTest.kt`
- test-first: `FxProjectionTest` (`offsets match hand-computed Web Mercator at z12/z15/z18 for island points`,
  `metresToDp at 47.6°`); `FxProjectionParityTest` (host: equals `mercatorOffsetDp` within 1e-9);
  `TentacleRigTest` (`deterministic by seed`, `reduced pose is time independent`, `pose writes only into preallocated
  arrays` — same array identity before/after)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest`
- rollback: revert

**STAGE 4 — Rendering core: FxClock, FxHost, TrailMap slot, MapTab gate**
- files: `composeApp/.../play/fx/{FxClock,FxHost,FxRenderer,FxPalette}.kt`, `map/TrailMap.kt` (`fxOverlay` param,
  default null), `ui/MapTab.kt` (gated block), `data/SettingsRepository.kt` (only if `play` flag missing),
  `graph/AppGraph.kt` (`Installed.playFx: FxScene` lazy — not created until first Play-on use)
- test-first: host tests `FxPaletteGuardTest` (`no FX color within ΔE 20 of a status color, light and dark`),
  `PlayGateTest` (`fx host inputs null when play off`), `AppSettingsCompatTest` (`old settings.json without play
  decodes with play=false`)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleTrailsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- rollback: revert; `fxOverlay=null` default means pure mode never changed

**STAGE 5 — Layer tier + `TreasureGlint`** (needs S1, S2, S3)
- files: `play/fx/effects/TreasureGlint.kt`, `play/fx/FxArt.kt` (+ `scripts/fx-art.py`, `design/fx-art/treasure.svg`,
  `sparkle.svg`), `FxHost` globalState writer, `ui/MapTab.kt` tap pre-check for `fx-` ids
- test-first: `TreasureGlintTest` (host: `geoJson has numeric ids = seq`, `phase in 0..1`, `empty list = valid empty
  collection`); `FxLayerIdsTest` (`every FX layer id starts with fx- and is not tappable-trail`)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug`
- rollback: remove the renderer from the registry (one line) or revert

**STAGE 6 — Overlay tier + `KrakenTentacles`** (needs S4)
- files: `play/fx/effects/KrakenTentacles.kt`, `play/fx/FxOverlay.kt`, `FxTapTarget.kt`, `FxArt` glyph
- test-first: `KrakenTimelineTest` (`:play`: `reducedAtMs inside RUN`, `grip envelope 0 at spawn and after settle`),
  `TentacleRig` already covered; host `KrakenAnchorTest` (`anchor is the road midpoint vertex`, `extent covers rig at z18`)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleRoadsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
- rollback: remove renderer from registry or revert

**STAGE 7 — FX showcase + HUD + debug-drawer panel + screenshots**
- files: `map/StyleSpikeScreen.kt` (`Screen.FX`, option `fx=<kind|all>`), `nav/AppShell.kt`, `nav/DebugLaunch.kt`
  (`-btScreen fx:<kind>`), `play/fx/{FxShowcaseScreen,FxHud,FxStats}.kt`, debug-drawer panel registration
  (`androidMain` debug), `docs/play/screens/fx-{treasure,kraken,kraken-reduced}-{android,ios}.png`
- test-first: `SpikeLaunchTest` (`screen=fx parses with fx option`), `FxStatsWindowTest` (`:play`: percentiles over a
  ring buffer, no allocation on add)
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleTrailsDebug :composeApp:linkDebugFrameworkIosSimulatorArm64` + screenshots committed
- rollback: revert (debug-only surface)

**STAGE 8 — Shader seam** (needs S5)
- files: `composeApp/src/commonMain/.../play/fx/FxShaderBrush.kt` (expect), `androidMain/.../FxShaderBrush.android.kt`
  (`Build.VERSION.SDK_INT >= 33` → `RuntimeShader`), `iosMain/.../FxShaderBrush.ios.kt` (Skia `RuntimeEffect`),
  `FxShaders.kt` (sources)
- test-first: `FxShaderSupportTest` (host: `sdk 32 → null`, `sdk 33 → supported` via the pure `FxShaders.supported(sdk)`)
- verify: `cd app && ./gradlew :composeApp:testAndroidHostTest :androidApp:assembleTrailsRelease :composeApp:linkDebugFrameworkIosSimulatorArm64`
  (release: R8 sanity) + showcase screenshot on an API < 33 emulator showing the fallback
- rollback: make the expect return null everywhere (one-line actuals)

**STAGE 9 — Performance pass**
- files: `docs/play/perf.md`; tuning constants in `FxCaps`/`FxQuality`
- test-first: n/a (measurement); regression guard = `FxSceneTest` caps
- verify: full set — `cd app && ./gradlew :coverage:jvmTest :tracking:jvmTest :play:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug :androidApp:assembleTrailsRelease :androidApp:assembleRoadsRelease :composeApp:linkDebugFrameworkIosSimulatorArm64`
  + the §3 measurements recorded
- rollback: constants only

```
CROSS-CUTTING: swip n/a (factory.swip false) | debugdrawer yes: "Play FX" panel (HUD toggle, quality, spawn any effect,
  clock state, RenderStats) | atlas n/a | goldens n/a (ui_snapshot none) → showcase screenshots both platforms in
  docs/play/screens/ | migrations: AppSettings.play (default false) only if the core plan has not added it
REACHABILITY: FxHost is reachable from MapTab behind the Play flag (Stage 4) and from the FX showcase (Stage 7); the
  effects become reachable in production when the Play content/scheduling plan spawns them (other spec) — until then
  only through the showcase/debug drawer, which is acceptable for an engine plan but must be closed by that plan.
REVIEW: adversarial-reviewer (this doc) → compose-ui-reviewer + mobile expert (Stages 4–8) → kmp-verifier (Stage 9 full
  set) → simplification-reviewer (branch) → adversarial-reviewer (branch, pre-PR)
```

---

## 5. Risks and spikes (≤1 h each, before the stage that needs them)

| # | Unknown (could not be settled from bytecode) | Spike | Pass criterion | Fallback |
|---|---|---|---|---|
| S1 | `globalState` expressions work through MapLibre Native FFI 0.202610.0 in **paint** properties (circle-radius, icon-opacity, icon-rotate), and `StyleGlobalState.setProperty` triggers a repaint without other changes | Style spike screen: one CircleLayer with `radius = const(6.dp) * globalState("k").asNumber(const(1f))`; a button writes 1→3 | radius changes on Android + iOS within one frame; no style reload | `FxLayerDriver` recomposes `const()` values ≤30 Hz on ≤4 layers |
| S2 | `setFeatureState(id: String, …)` matches GeoJSON features with numeric top-level `id` (no `promoteId`) | GeoJSON with ids 1..3; set `{found:true}` on "2"; layer opacity via `feature.state("found")` | only feature 2 fades, both platforms | encode found-ness into the GeoJSON (`setData` on find — rare event, cheap) |
| S3 | Composition order = z-order for layers in separate `Anchor.Below("trail-label")` blocks and for `Anchor.Top` vs `LocationIndicatorLayer`; nested same-anchor blocks are harmless | Compose three colored LineLayers before/inside/after StatusLayers; list `state.style.layers` order | order matches composition; also stable after a style switch (Topo→Dark) | anchor FX bands with `Anchor.Above/Below` predicates on known style ids |
| S4 | `placedAt` anchor + draw-phase `viewport.cameraPosition.zoom` stays visually glued during fling and pinch (zoom may come from the mirrored, not presented, frame) | Overlay box at a trail vertex drawing a cross + a ring at a second vertex via `FxProjection`; record screen during fling/pinch; compare with a MapLibre CircleLayer at the same points | drift ≤ 1 dp pan, ≤ 2 dp pinch, both platforms | derive zoom from two `placedAt` anchors' distance (both frame-synced), or accept pinch drift |
| S5 | AGSL/SkSL single-source shader compiles on Android 33+ and iOS Skiko; `ShaderBrush` + uniform updates per frame are cheap; CMP 1.12.2 kept 1.11's `Shader`/`asComposeShader` shape | 20-line caustic shader drawn in a 300 dp Canvas, uniform `time` from the clock | 60 fps, < 0.5 ms draw on device; compiles both platforms | gradients only (shader seam returns null) |
| S6 | compottie Moment-tier cost and binary size (only if a designer delivers Lottie scenes) | Load a 3 s, 60-layer Lottie in a full-screen scene; measure APK delta (R8) and frame time | ≤ +1.5 MB release APK, p95 frame < 8 ms | hand-written Canvas moments (default) |
| R7 | Per-layer `onClick` on FX layers vs MapTab's map-level `click` consumer: which wins | Add `onClick` to a CircleLayer; tap it | documented precedence | always route through MapTab `onTap` + `queryRenderedFeatures(fx ids)` (design already does this) |
| R8 | `rememberMapState(content=…)` style composition picks up snapshot reads promptly enough for `FxLayerDriver` (fallback of S1) | Animate a layer `opacity = const(state.value)` from a `withFrameNanos` loop | ≥ 30 updates/s observed via RenderStats | Layer tier limited to transitions + `setData` |

Other risks: Surface render mode means no Compose post-processing of map pixels (no map-wide blur/tint "storm"
effects) unless `AndroidRenderMode.Texture` is adopted after measurement; overlay effects draw above map labels;
the iOS map renders in Metal while Compose draws via Skia — overlay/map sync relies on the library's presented-frame
projection (S4).
