# Roads prototype — screenshots (2026-10-20)

Android emulator (Pixel, API 37), debug build on branch `roads/app-prototype`, launched with
`adb shell am start -n com.bainbridgetrails.app.dev/com.bainbridgetrails.app.MainActivity --es dataset roads`.
Walks are replayed with `scripts/emu-route.sh`; no real GPS track was used. Findings:
[`../plan.md`](../plan.md#what-the-roads-mode-actually-feels-like-prototype-2026-10-20).

| file | what it shows |
|---|---|
| `map-island.png` | the island at launch on the roads dataset: 2003 segments of "not started" dots, one road painted |
| `map-street.png` | street zoom in the tab shell: category beds under the status dots, white = not counted |
| `map-style-categories.png` | the style alone (style spike, no status layers): state route / arterial / collector / residential beds, private dashed, ownership holdouts dotted |
| `walk-in-progress.png` | a replayed 494 m walk on Island Center Road NE, matched to the right road |
| `walk-paints-whole-segment.png` | the same walk finished: 137 m segment complete (violet), **the whole 1108 m segment** painted Partway (gold) although only 357 m of it was walked |
| `road-detail.png` | road detail: length and progress work, "Elevation gain —" because `TrailMeta` has no climb field |
| `me-progress.png` | Me: island 0.1 %, "0 / 664", and every string still saying "trail" |
| `debug-dataset.png` | the debug drawer's dataset switch (running profile, goal, next launch) |
| `debug-dataset-totals.png` | the same drawer proving the denominator: 664 roads, 2003 segments (1462 counted), island total 261.5 km = 162.5 mi |
| `trail-walk-paints-a-road.png` | **the blocker**: a 0.6 mi walk on the Battle Point *trail*, entirely inside the park, credits Battle Point Drive NE 0 % → 1 % |
