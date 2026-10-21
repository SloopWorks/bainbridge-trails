# MapFx Stage 0 — spikes S1–S4, R7, R8 (results)

Date: 2026-10-20 · Plan: `docs/play/engine.md` §4 Stage 0 / §5 · Library: maplibre-compose 0.20.0 · CMP 1.12.2

| Spike | Android (emulator-5554, API 37) | iOS (sim iPhone 17 Pro, iOS 26.3) | Verdict | Fallback / consequence |
|---|---|---|---|---|
| S1 `globalState` in paint + repaint | works, no style reload | works, no style reload | **PASS** (with 2 caveats) | none needed; re-write global state after every `StyleLoaded`; cap map fps while animating |
| S2 feature-state on numeric GeoJSON ids | only feature 2 fades | only feature 2 fades | **PASS** | none needed |
| S3 composition order = z-order | yes, also after Topo→Dark | yes, also after Topo→Dark | **PASS** | none needed |
| S4 `placedAt` overlay stays glued | ≤1 dp only while slow and frame-locked; 4–17 dp when frames drop | ≤1 dp under `animateCamera`; 2–15 dp during fling | **FAIL** (gestures) | doc fallback does not help; see S4 |
| R7 layer `onClick` vs map `click` | map-level runs first; `Consume` blocks layer | same | **documented** | keep design (route through MapTab `onTap`) |
| R8 `const(state)` recomposition → native | every write reached native (15–31/s, emulator-bound) | 60 writes/s → 60 native changes/s | **PASS** (iOS), Android emulator borderline | `FxLayerDriver` is a working fallback |

Not measured on a physical device: the owner's phone was not attached. Emulator GPU timings (encode 90–270 ms/frame)
and simulator timings (render 0.04 ms) say nothing about device cost. Treat every frame-cost number below as
"functional only". The S4 desync is a sync problem, not a speed problem, so it should also happen on devices. The
Android size of it there (one device frame) still has to be confirmed on a device.

## How to run

Code is debug-only and left uncommitted in the working tree:

- `app/composeApp/src/commonMain/kotlin/com/bainbridgetrails/play/spike/FxSpikeScreen.kt` (the whole spike)
- `app/composeApp/src/{androidMain,iosMain}/kotlin/com/bainbridgetrails/play/spike/FxSpikeUiOptions.*.kt` (Android
  render mode)
- Wiring is the same as `screen=style`. `SpikeLaunch.Screen.FX` and its options (`map/StyleSpikeScreen.kt:54-95`),
  `FxSpikeRoute` (`nav/Routes.kt:36`), `startRouteFor` (`nav/AppShell.kt:104`), the NavHost entry
  (`nav/AppShell.kt:288`) and `-btScreen fx` (`nav/AppShell.kt:367`). Release never sees it, because launch options
  are only parsed in debuggable builds and debug binaries.

```sh
# Android
adb shell am start -n com.bainbridgetrails.app.dev/com.bainbridgetrails.app.MainActivity --es screen fx [--es fxAuto 1] \
  [--es fxTexture 1] [--es fxNoTween 1] [--es fxMaxFps 30]
adb logcat -s FxSpike:I
# iOS
xcrun simctl launch <udid> com.bainbridgetrails.app screen=fx fxAuto=1 [fxNoTween=1] [fxMaxFps=30]   # or -btScreen fx
xcrun simctl spawn <udid> log stream --style compact --predicate 'eventMessage CONTAINS "FxSpike"'
```

`fxAuto=1` runs the scripted sequence `autoScript` (`FxSpikeScreen.kt:547`). Each step logs a `PHASE <name>` line,
and host scripts take a screenshot at each one. Buttons: R7 consume/pass, cam (S4 animation), k=1/3, S1 anim,
found2, style, R8 anim, order. The HUD shows map fps, render/encode ms and the S4 drift maxima.

Camera: z16 centred on Grand Forest East Trail segment `seg-006175`, which StatusLayers draws as COMPLETE.

---

## S1 — `globalState` in a paint property

**Criterion:** the radius changes within one frame on Android and iOS, with no style reload.

**Built:** `CircleLayer("fx-s1", radius = const(6.dp) * globalState("k").asNumber(const(1f)))` at
`FxSpikeScreen.kt:381`. A button writes `state.style.globalState.setProperty("k", JsonPrimitive(1|3))`. There is an
animated variant `animateGlobalK` (`FxSpikeScreen.kt:473`): `delay(33)` + `withFrameNanos` +
`setProperty("k", 2 + sin(2πt/2s))` for 5 s. Frame stats come from `state.events` → `MapEvent.FrameRendered.stats`
(`FxSpikeScreen.kt:262`).

**Observed:**

| | Android | iOS |
|---|---|---|
| single write k=3 → first `FrameRendered` | 90 / 169 / 529 ms (3 runs; map idle before, emulator pacing) | 12 / 15 ms |
| `StyleLoaded` during writes | 0 | 0 |
| visual | disc 6 → 18 dp (`fx-s1s2-android.png`, orange) | same (`fx-s1s2-ios.png`) |
| `globalState.get()` after write | `{"k":3}` | `{"k":3}` |
| animated: writes/s | 11.8–14.4 (emulator UI thread) | 20.2 |
| animated: map frames/s | 4.4–9.8 | 128–139 (120 Hz sim) |
| frames with `needsRepaint` | 17 of 28 | 623–635 of 640–652 |
| animated + `fxNoTween=1` (radiusTransition 0) | — | 130 frames/s (no change) |
| animated + `fxMaxFps=30` | — | 27.4 frames/s |
| render / encode per frame | 5–32 / 90–180 ms (emulator GL) | 0.04 / 0.8–1.2 ms (sim) |
| **after a Topo→Dark style switch** | `globalState.get()` = `{}`, the disc is back to 6 dp | same |

**Verdict: PASS.** Global state drives paint expressions through MapLibre Native FFI on both platforms, and a write
repaints without a reload.

**Consequences for the design:**
1. **Global state does not survive a base-style change.** `FxHost` must write every live `fx.*` key again on
   `MapEvent.StyleLoaded`. A theme switch or a tab return both reload the style. Feature state does survive (S2).
2. **Writes at 20 Hz still make the map render at display rate.** `needsRepaint` stays true for about 97% of frames,
   and a zero paint transition does not change that. The §2.3 claim "MapLibre re-renders at ≤30 Hz" is wrong. While
   any Layer-tier effect animates, `FxHost` must set `RenderOptions { maximumFps = 30 }`, then restore it when the
   clock stops. That measured 27–29 frames/s. Add this to PF-2/PF-7 and to the §3 budget.
3. **`delay(33)` + `withFrameNanos` gives 20 Hz on a 120 Hz display and about 12 Hz on a slow device, not 30 Hz.**
   `FxClock` should gate on elapsed frame time instead: run `withFrameNanos` every frame and skip publishing until at
   least 33 ms have passed.

## S2 — feature-state on GeoJSON features with numeric top-level `id`

**Criterion:** only feature 2 fades, on both platforms.

**Built:** a GeoJSON source with three `Point`s and top-level `"id":1..3` (`pointsJson`). `CircleLayer("fx-s2")`
sets `opacity`/`strokeOpacity = switch(condition(feature.state("found").asBoolean(const(false)), const(0.15f)),
fallback = const(1f))` (`FxSpikeScreen.kt:391`). `setFound` (`FxSpikeScreen.kt:456`) calls
`state.style.sources[src]?.setFeatureState("2", {found:true})`.

**Observed (both platforms, identical):** `setFeatureState` returns normally. `getFeatureState` gives `1 → {}`,
`2 → {"found":true}`, `3 → {}`. Only the middle disc drops to 15% opacity (`fx-s1s2-*.png`, green row). After the
Topo→Dark switch, `getFeatureState("2")` is still `{"found":true}` and the disc stays faded. Layer `onClick` reports
the numeric id as `"1"`/`"3"`.

**Verdict: PASS.** No `promoteId` is needed, and the `setData` fallback is not needed.

## S3 — composition order = z-order across anchors and a style switch

**Criterion:** `state.style.layers` order matches composition order, also after Topo→Dark.

**Built:** `FxSpikeLayers` (`FxSpikeScreen.kt:353`), composed in this order:
- `Anchor.Below("trail-label") { fx-s3-under }` (red 20 dp, `:360`)
- `StatusLayers(COMPLETE = seg-006175)`
- `Anchor.Below("trail-label") { fx-s3-over; Anchor.Below("trail-label") { fx-s3-nested } }` (green 6 dp / yellow
  2 dp, `:365-367`)
- `Anchor.Top { fx-s3-top }` (blue, offset +5 dp)
- `Anchor.Top { fx-s3-top2 }` (black, −5 dp; stands in for the later `LocationIndicatorLayer`)
- un-anchored `fx-s1`, `fx-s2`, `fx-r8`, then `Anchor.Top { fx-s4-ref }`

`logLayerOrder` (`:465`) logs `state.style.layers.map { it.id }`.

**Observed (identical on both platforms):**

```
topo  total=48: 27:water-label < 28:fx-s3-under < 32:app-trail-halo-not_started < 33:app-trail-halo-complete
  < 34:app-trail-not_started < 35:app-trail-complete < 36..37:app-trail-nc-* < 38:fx-s3-over < 39:fx-s3-nested
  < 40:trail-label < 41:place-label < 42:fx-s3-top < 43:fx-s3-top2 < 44:fx-s1 < 45:fx-s2 < 46:fx-r8 < 47:fx-s4-ref
dark  total=47: 25:water-label < 26:fx-s3-under < 30..31:halo < 32:app-trail-glow < 33..36:status < 37:fx-s3-over
  < 38:fx-s3-nested < 39:trail-label < 40:place-label < 41:fx-s3-top < 42:fx-s3-top2 < 43:fx-s1 … < 46:fx-s4-ref
```

The visual stack, from bottom to top, is red under the status line, then green, then yellow, with blue/black on
top (`fx-s3-android.png`, `fx-s3-ios.png`).

**Verdict: PASS.** `UNDER_STATUS` before StatusLayers and `OVER_STATUS` after it, both in `Anchor.Below("trail-label")`,
work as PF-3 says. A nested same-anchor block is a no-op: the layers stay adjacent. `Anchor.Top` blocks stack in
composition order. **Un-anchored layers composed later also land above earlier `Anchor.Top` blocks.** MapTab's
`LocationIndicatorLayer` therefore stays above `OVER_LABELS` only if it is composed after `FxLayers(OVER_LABELS)`,
which is what §2.2 already shows. Keep that order and add a guard test. The predicate-anchor fallback is not needed.

## S4 — `placedAt` anchor + draw-phase Web-Mercator offset vs map pixels

**Criterion:** drift ≤ 1 dp while panning and ≤ 2 dp while pinching, on both platforms.

**Built:** `FxSpikeOverlay` (`FxSpikeScreen.kt:420`):
- `Box(Modifier.placedAt(P1, Alignment.Center).size(2.dp).drawBehind { … })`
- a magenta cross at P1 (the arms leave a gap so the reference disc stays visible)
- a magenta ring at P2, drawn at `center + mercatorOffsetDp(P1, P2, viewport.cameraPosition.zoom)` (`:434`), where
  the zoom is read in the draw phase
- reference: a MapLibre `CircleLayer("fx-s4-ref")` of pure cyan at P1 and P2 (`:411`)

P1 and P2 are the first and last vertex of `seg-006175`, about 295 dp apart at z16. `s4CameraRun` (`:511`) runs
three 4 s linear `animateCamera` legs (z16 → 14.5 → 16.3 → 16, with a pan). During each leg the draw block also
compares itself with `screenLocationFromPosition` (`:438`).

Pixel drift was measured from screen recordings: Android `adb screenrecord` 720×1606, iOS `simctl io recordVideo`
1206×2622. The script takes the centroid of the cyan disc and of the magenta cross/ring around it, in dp. Video
compression adds about 0.3 dp of noise, and full-resolution screenshots at rest read 0.05–0.2 dp. Gestures were
`adb input swipe` on Android, and the simulator tool's swipe and two-finger path on iOS. Android cannot pinch from
adb, so the zoom legs of `animateCamera` stand in for pinch there.

| Case | Android Surface (default) | Android `fxTexture=1` | iOS |
|---|---|---|---|
| rest | 0.14 / 0.17 dp | — | 0.05–0.21 dp |
| `animateCamera` zoom-out leg (≈0.5 dp/frame, map frames steady) | ring p50 0.27 · p95 0.68 · max 1.42; cross p50 0.44 · p95 1.17 · max 2.19 | ring p95 0.92 · max 1.18; cross p95 1.38 · max 2.16 | screenshots 0.2–0.98 dp; in-app max anchor 0.29–0.31, ring 0.37–0.72 dp |
| `animateCamera` zoom-in leg (emulator drops to ≈8–12 fps, 5–12 dp/frame) | ring p50 1.24 · p95 12.7 · max 14.7; cross p50 4.4 · p95 10.6 · max 17.4 | ring p95 6.9 · max 12.0; cross p95 9.6 · max 15.1 | — |
| fling (≈20 dp/frame) | too few recorded frames (recorder dropped them) | 10–16 dp in the frames captured | p50 2.1–6.0 · p95 7–15 · max 15.3 dp |
| in-app `placedAt` vs `screenLocationFromPosition` during the run | mean 0.15–0.24, max 2.5–13.1 dp | max 18.5 dp | mean 0.12, max 0.29–1.18 dp (1.18 with `fxMaxFps=30`) |

Pattern: drift is about 0 when the overlay and the map present the same camera. It jumps to **one frame of camera
motion** whenever one of them presents a frame the other does not. In the Android recordings the cyan disc stands
still for a frame (`v = 0`) while the cross has moved, so the overlay is ahead. In the iOS fling the cross trails
the disc, so the overlay is behind (`fx-s4-android.png`, `fx-s4-ios.png`). The cross is placed by `placedAt` alone,
and it drifts as much as the ring. **The error comes from overlay/map frame sync, not from the draw-phase zoom.**
`zoomSkew` (draw zoom vs `state.cameraPosition.zoom`) was 0.0 in every run, and the ring is no worse than the
cross. Texture render mode did not remove the desync on the emulator.

**Verdict: FAIL** against ≤1/≤2 dp for gesture-driven motion on both platforms, and for any motion on Android once
frames drop. PASS for programmatic `animateCamera` on iOS. On Android it passes only while the emulator holds its
frame rate (p95 0.7–1.4 dp, max about 2.2 dp).

**Consequence:** the §5 fallback, "derive zoom from two `placedAt` anchors", does not address this, because the
anchor itself is out of sync. P1's zoom math is fine. What follows for the design:
- Overlay-tier art must tolerate about one frame of slip. A kraken in water or a burst over a point can absorb
  10–15 dp during a fling.
- Anything that has to **coincide with map geometry** belongs in the Layer tier: the road "grip" stroke, glints
  along a trail, halos on markers. Use GeoJSON + `globalState`/feature-state (S1/S2 pass).
- `FxHost` should hide or freeze overlay effects, or hand them to their Layer LOD, while
  `state.isCameraMoving && cameraMoveReason == GESTURE`, then fade back on `CameraMoveEnded`. Programmatic `fitTo`
  moves are acceptable on iOS.
- KrakenTentacles (§2.4): draw `drawRoadStroke` as a Layer-tier `LineLayer`, not on the Canvas.
- Re-run this spike on a physical Android device before Stage 6. On a 60 Hz device one frame is 16 ms, so the
  slip is smaller, but it is still a slip.

## R7 — layer `onClick` vs the map-level `click` consumer

**Built:** `fx-s2` has `onClick = { features -> log; ClickResult.Consume }` (`FxSpikeScreen.kt:397`). The map-level
`MapInteractions { callbacks { click { onEvent { … if (r7Pass) Pass else Consume } } } }` (`:233`) has the same
shape as `chartInteractions(onTap)`. Both real taps and `MapState.click(DpOffset)` were used.

**Observed (both platforms):**
- Tap on a feature while the map-level handler returns `Consume` (what `chartInteractions` does today): only the
  map-level handler fires. Layer `onClick` never runs.
- Map-level returns `Pass`: the map-level handler fires **first**, then the layer `onClick` fires with
  `features = [id "1"/"3"]`.
- Tap on empty map: map-level only.
- Programmatic `MapState.click` behaved the same as the `Consume` case.

**Verdict: documented.** Precedence is map-level click first, then layer `onClick` only if the map-level handler
passed. Keep the design: MapTab `onTap` → `queryRenderedFeatures(fx-* ids)`. Per-layer `onClick` on FX layers would
need `chartInteractions` to return `Pass`, which changes trail selection.

## R8 — `opacity = const(snapshotState)` driven from a frame loop

**Built:** `CircleLayer("fx-r8", opacity = const(r8Opacity.floatValue))` inside the `rememberMapState` content
(`FxSpikeScreen.kt:407`), with a `SideEffect` counter for recompositions. `animateR8` (`:489`) writes every
`withFrameNanos` frame for 5 s and polls `style.layers["fx-r8"].getProperty("circle-opacity")` for native changes.

| | Android | iOS |
|---|---|---|
| writes/s | 14.6 / 24.8 / 31.4 (3 runs, emulator UI frame rate) | 60.0 |
| style-composition recompositions/s | same as writes | 60.0 |
| native property changes/s | same as writes (72/73, 124/124, 157/157) | 60.0 (300/300) |
| map frames/s | 5.6–14.8 | 114–120 (29.2 with `fxMaxFps=30`) |

**Verdict: PASS** on iOS (≥30/s, every write lands). On Android every write lands too. Throughput is capped by the
emulator's UI frame rate (15–31/s), not by the style composition. The `FxLayerDriver` fallback works, and S1 passed
anyway. Map frames follow display rate, not write rate, so the `maximumFps` cap from S1 applies here as well.

---

## API corrections to engine.md §0 (and related claims)

1. **`MapEvent.FrameRendered.stats` is `RenderStats?`** (nullable). `RenderStats.mode` is an inline `Mode`
   (prints `Mode(value=Full)`).
2. **`state.style.sources[geoJsonSource]` returns `GeoJsonSourceHandle?`** (nullable). The same is true for
   `style.layers[id]: LayerHandle?`.
3. **Layer `onClick` type is `ClickEvent.(features: List<Feature<Geometry, JsonObject?>>) -> ClickResult`**: a
   receiver lambda with one parameter, not `(ClickEvent, List) -> …`. The same applies to `onLongClick` and
   `onDoubleClick`. `ClickEvent.position` is `Position?`.
4. **`MapUiOptions.renderMode` exists only on Android** (`AndroidMapUiOptions.kt`). commonMain cannot set it, so it
   needs an expect/actual (see `FxSpikeUiOptions.*.kt`).
5. **Paint `…Transition` parameters are nullable** (default `null` = style transition). `TransitionOptions` has no
   `Default`; use `TransitionOptions(duration = …)`. `RenderOptions(RenderOptions.Standard) { maximumFps = n }` works
   and caps MapLibre's frame rate (measured).
6. **Global state is reset by a base-style change; feature state is not.** §0's "one write animates every
   expression" holds only until the next `StyleLoaded`.
7. **A `globalState` write at ≤30 Hz does not limit MapLibre to ≤30 Hz.** It repaints at display rate
   (`needsRepaint` stays true; transitions are irrelevant). §2.3 and §3 must budget display-rate map frames, or set
   `maximumFps`.
8. **`placedAt` is not frame-locked to the map pixels under gestures (iOS) or frame drops (Android).** PF-4's
   "in sync with the pixels on screen" is too strong: expect up to one frame of camera motion.
9. **The `FxClock` sketch (PF-2, §2.2) `if (AMBIENT_30HZ) delay(33)` before `withFrameNanos` gives about 20 Hz on
   120 Hz displays**, and 12–15 Hz on a slow device. Gate on elapsed frame time instead.
10. Map-level `click` callbacks run before layer `onClick`, and `ClickResult.Consume` suppresses layer handlers. This
    was not stated in §0. `MapState.click(DpOffset)` exists for scripted taps.
11. Confirmed as written: `globalState(name)` DSL; `feature.state("k")`; `setFeatureState/getFeatureState`;
    `screenLocationFromPosition: DpOffset?`; `state.style.layers` iterates handles in native style order;
    `MapOverlayScope.placedAt(position, alignment)`; `animateCamera(CameraUpdate, CameraAnimation.Ease { duration;
    easing })`; `state.events` emits `StyleLoaded` once per (re)load.

## Screenshots

- `fx-s1s2-android.png`, `fx-s1s2-ios.png`: k=3 (orange 18 dp) and feature 2 found (middle green disc at 15%).
- `fx-s3-android.png`, `fx-s3-ios.png`: band stack on Topo and after the switch to Dark.
- `fx-s4-android.png`: at rest, in sync during the zoom-out leg, and two one-frame desyncs (11.5 / 10.6 dp) from the
  Surface-mode recording.
- `fx-s4-ios.png`: at rest, `animateCamera` (0.73 dp), and fling (15.3 / 14.9 dp).
