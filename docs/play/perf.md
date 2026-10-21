# Play FX — performance (plan W8 PERF, engine stage 9)

Status (2026-10-21): **method and budgets recorded; device numbers PENDING.** No physical device was attached in this
session, and the only emulator (emulator-5554) was being driven by the AC 8 e2e run (the roads dev app was resumed with
launch extras), so no emulator measurement was taken either. The S4 re-run is **pending physical device**. Nothing in
this file is a device number yet; the owner's run below fills the tables.

## Budgets (engine.md § 3, normative; release build; budget devices Pixel 6a / iPhone 12)

| Item | Budget |
|---|---|
| FxHost overlay draw (all overlay effects) | p95 ≤ 2.0 ms, p99 ≤ 4 ms |
| FX layer-tier animation | ≤ 30 Hz, only while an animating feature is in view; MapLibre `renderingTime` p95 ≤ baseline + 1.5 ms |
| Live effects | 1 HERO, ≤ 4 NOTICE, ≤ 12 animated AMBIENT, ≤ 3 overlay-tier |
| Allocation | 0 objects per overlay frame in steady state |
| Jank (Android `gfxinfo`, overlay scenario) | janky frames ≤ 5 %, p90 ≤ 16 ms at 60 Hz |
| Memory | FX bitmaps ≤ 2 MB decoded; heap growth over a 10 min soak < 5 MB |
| Battery | clock STOPPED ≥ 95 % of map time with only ambient effects live and the user idle; no FX work in background |

Over budget: follow engine.md § 3 "Over budget — in this order" (constants only: `FxCaps` / `FxQuality`).

## Method (engine.md § 3)

Build: release signed with the debug key so HWUI numbers are not debug-inflated:
`cd app && ./gradlew :androidApp:assembleRoadsRelease -PreleaseSignWithDebugKey=true` (package
`com.bainbridgetrails.roads`; trails: `com.bainbridgetrails.app`). The FX showcase is debug-only: for the showcase
presets use the `*Debug` build and label the rows "debug build"; the Map-tab rows use the release build.

Presets (FX showcase, ux § 3.19; launch options `fx`, `preset`, `state`, `t` = pause at ms, `theme`, `reduced`, `warp`):

| Row | Android | iOS (plan § 2: `-btScreen fx:<id> -btFxPreset <preset>`) | What it loads |
|---|---|---|---|
| glints | `--es screen fx --es preset fay-5` | `-btScreen fx:treasure_glint -btFxPreset fay-5` | `treasure_glint` ×5, present, ambient 30 Hz |
| kraken attack | `--es screen fx --es preset manitou --es state intro --es t 1100` | `-btScreen fx:kraken_attack -btFxPreset manitou` (state/`t` via the control strip) | `kraken_attack` HERO pinned at its peak frame (clock STOPPED: draw cost of the heaviest frame); repeat without `t` for the running intro |
| fog | `--es screen fx --es preset island-20of62` | `-btScreen fx:exploration_fog -btFxPreset island-20of62` | `exploration_fog`, 20 of 62 squares lifted, static |

Per row, read from the showcase HUD (top-right): `fps`, `render p50 / p95` (MapLibre `renderingTime` over the
`FxStatsWindow`), `clock` mode, `maxFps`, live / static counts. Then, around a scripted 20 s pan + pinch:

```
adb shell dumpsys gfxinfo <pkg> reset
# run the scenario (20 s: pan, pinch in, pinch out, idle 5 s)
adb shell dumpsys gfxinfo <pkg> framestats > gfx-<row>.txt   # 50th/90th/95th/99th percentile, janky %
adb shell perfetto -o /data/misc/perfetto-traces/fx.pftrace -t 20s sched freq gfx view am wm dalvik
adb pull /data/misc/perfetto-traces/fx.pftrace                # inspect fx.draw vs RenderThread / MapLibre thread
```

Compose/HWUI frames are the overlay cost; the Surface-mode map renders outside HWUI, so its cost is the HUD's
`RenderStats`. Battery: `adb shell dumpsys batterystats --reset` → 10 min idle on the Map tab, Play on, ambient
effects in view → `adb shell dumpsys batterystats` CPU time vs the same 10 min with Play off; the HUD's clock counter
gives the STOPPED share.

iOS: device build, Instruments Time Profiler + Animation Hitches + Metal System Trace (MapLibre is Metal); the
simulator is for functional screenshots only.

## Results

### EMULATOR (not representative) — not taken this session

The emulator was in use by the e2e run. When it is free, fill this table for regressions only (emulator GPU and
vsync are not a device; never compare these against the budgets).

| Row | build | HUD fps | render p50 / p95 ms | clock | maxFps | gfxinfo p50 / p90 / p95 / p99 ms | janky % |
|---|---|---|---|---|---|---|---|
| fay-5 glints | debug | — | — | — | — | — | — |
| manitou kraken t=1100 | debug | — | — | — | — | — | — |
| island-20of62 fog | debug | — | — | — | — | — | — |

### Device (Pixel-class Android) — PENDING

| Row | device / OS | build | HUD fps | render p50 / p95 ms | overlay draw p95 / p99 ms | clock | maxFps | gfxinfo p50 / p90 / p95 / p99 ms | janky % | within budget |
|---|---|---|---|---|---|---|---|---|---|---|
| fay-5 glints | | | | | | | | | | |
| manitou kraken t=1100 | | | | | | | | | | |
| manitou kraken running intro | | | | | | | | | | |
| island-20of62 fog | | | | | | | | | | |
| Map tab, Play on, 10 min idle | | release | | | | STOPPED % = | | | | |

### iOS device — PENDING

| Row | device / iOS | hitches (ms/s) | Time Profiler: fx.draw p95 | Metal frame p95 | within budget |
|---|---|---|---|---|---|
| fay-5 / manitou / island-20of62 | | | | | |

### S4 re-run (overlay glue, `docs/play/spikes/fx.md` § S4) — pending physical device

Criterion: overlay drift ≤ 1 dp while panning, ≤ 2 dp while pinching, frames not dropped. Emulator result (2026-10-20)
was FAIL under gestures (2–17 dp), which drove PD-19 (geometry-exact art on the Layer tier). Re-run on the device:
spike screen, `cam` button for the animated camera, then a manual fling and pinch; record the HUD drift maxima.
If drift exceeds one frame on the device, plan.md "open" #1 applies (even short overlay parts move to Layer tier).

| device | pan max dp | pinch max dp | fling max dp | verdict |
|---|---|---|---|---|
| | | | | |

## Owner's checklist (Pixel-class device run)

1. [ ] Attach a Pixel 6a (or the closest Pixel-class device); `adb devices` shows it; note model, Android version,
   refresh rate (set 60 Hz for the jank budget: Settings → Display → Smooth display off).
2. [ ] Install the roads **debug** build for the showcase rows and the roads **release** build signed with the debug
   key for the Map-tab row; Battery Saver off (it forces the static path), animations on.
3. [ ] For each preset row: launch, wait 3 s, read the HUD, run the 20 s scripted pan + pinch with `gfxinfo reset`
   before and `framestats` after; save the raw outputs next to this file (`docs/play/perf/`).
4. [ ] One Perfetto trace for the manitou running intro; check `fx.draw` p95 ≤ 2 ms and no main-thread overlap with
   the MapLibre render thread.
5. [ ] 10 min idle soak on the Map tab with Play on: STOPPED share ≥ 95 %, heap growth < 5 MB (`dumpsys meminfo`
   before / after), batterystats CPU vs Play off.
6. [ ] S4 re-run on the spike screen (pan / pinch / fling drift maxima).
7. [ ] Repeat rows 3–5 on an iPhone 12-class device with Instruments.
8. [ ] Any row over budget: apply engine.md § 3 steps in order (constants in `FxCaps` / `FxQuality` only), re-measure,
   and record both runs here.
