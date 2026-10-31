# Island map cleanup verification — 2026-10-30

Both editions use the shared generated styles. The exterior is opaque water, off-island labels are filtered,
the detailed shoreline has a single continuous stroke, and live Play no longer overlays exploration fog.
Chart-square rewards and coverage data are unchanged. Fixed an existing keyed `produceState` bug that kept
the previous base style when the map controls/theme changed styles.

## Checks

- `python3 -m unittest discover -s scripts -p 'test_map_styles.py'`: **4 passed**. Covers neighboring geography,
  mask opacity/order/winding, actual coastal points clipped by the old simplified outline, no tile/ripple strokes,
  island label filtering and generated-template parity. Added to CI.
- Full Gradle verification from `app/AGENTS.md`, with the local Shipyard plugin property: **BUILD SUCCESSFUL**.
  `coverage` 82, `tracking` 101, `play` 253 and `composeApp` 489 tests: **925 passed, zero failures or skips**.
  Both goals' debug and release/R8 APKs built; iOS simulator framework linked.
- `xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator
  -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/bt-dd build`: **BUILD SUCCEEDED**.
- Independent correctness review: **SHIP**. Final map outline is valid, 3,529 vertices, maximum measured
  deviation 0.339m from max-zoom z15 earth (0.01m seam welding). Original hero/Play coastline bytes unchanged.
- Native style regression: Android control taps **Topo → Light → Dark → cached Topo** and iOS appearance
  **Topo → Dark → cached Topo** all passed rendered-water color checks. This catches the previous retained-style
  bug (dark chrome over Topo terrain). Frames were captured after native rendering, then visually reviewed.
- Android API37 Pixel emulator and iOS26.3 iPhone16e simulator: overview, both map palettes, shoreline at z18,
  roads with no coverage, trails with existing covered lines/Play, and Record idle. No new Android ANR after
  final launches; crash buffer empty. An old, destroyed Roads window showed a focus-loss ANR during in-place
  installation; closing that stale dialog was necessary before capturing the final frames.
- Independent Compose/UI review: **SHIP**, all final native captures inspected. Both reported findings
  (crossed offshore ripples and the stale-style binding) resolved; no remaining substantive findings.

## Evidence

| Surface | Android | iOS |
| --- | --- | --- |
| Trails Topo (Android Play on, existing coverage) | [Map](trails-topo-play-android.png) | [Map](trails-topo-ios.png) |
| Roads Topo (Play on, no coverage) | [Map](roads-topo-play-android.png) | No iOS roads target |
| Light style | [Map](light-overview-android.png) | [Map](light-overview-ios.png) |
| Dark style | [Map](dark-overview-android.png) | [Map](trails-dark-ios.png) |
| Cached style switch back to Topo | [Map](topo-cached-switch-android.png) | [Map](trails-topo-cached-switch-ios.png) |
| North shoreline z18 | [Map](topo-shore-z18-android.png) | [Map](topo-shore-z18-ios.png) |
| Winslow z15, on-island labels | [Map](topo-winslow-z15-android.png) | — |
| Record idle, Play enabled | [Record](record-play-android.png) | — |

Design and implementation scope: [plan](../../../superpowers/plans/2026-10-30-island-map-cleanup.md).
