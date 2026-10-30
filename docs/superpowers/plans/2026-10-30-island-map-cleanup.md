# Island map cleanup — 2026-10-30

## Design spec — ACCEPT

The owner's request supplies the visual direction: Bainbridge alone, on clean water, without a wash that clears
only where walked. Retain the existing Topo, Light and Dark map palette roles, road/trail status encodings,
controls, typography, 48dp targets, camera gestures, loading and error surfaces. No new primitives or copy.

Use a continuous, detailed `map-coastline.json` land polygon for an opaque water-colored exterior mask and a thin
shoreline. Omit offset ripple copies: native review showed tangled crosshatching in narrow concave inlets.
Put the mask above geographic base layers, below the continuous shore and labels. Filter labels to the
island so hidden neighboring towns cannot reappear above the mask. All styles, both editions and all shared
maps (including detail, history and Record) use these generated base styles. Offshore Play figures remain on
otherwise plain water. Do not change camera framing: the whole island should continue to fit on screen.

Remove exploration fog from the live ambient scene and its renderer registry, including Record. Keep the effect
in the debug showcase and preserve chart-square accounting, achievements and all other Play behavior. The
map is equally clear before and after coverage, while covered lines retain their status colors.

States: loading/error/empty retain existing UI; populated maps show the island with plain water outside it.
Style changes and relaunch must retain the mask. Offline geometry is bundled; no new downloads.
Check shoreline close-ups for simplification artifacts and seams, overview for neighboring labels, and live
Play with existing coverage for the reported rectangular patches. Check light, dark, both goals and iOS.

This bounded correction follows the owner's explicit direction, using app-local palette roles. No external
design round or new screen is needed. This spec supersedes the live fog visual in Play PD-14 / effects §3.18;
the showcase effect and rewards remain supported.

## Implementation plan

1. Add failing regression tests for island exterior geometry, mask opacity/order, label filtering and continuous
   shore in `scripts/test_map_styles.py`; add a live fog regression in `MapTapPriorityTest.kt`. Run them red.
   Wire the Python regression into `.github/workflows/ci.yml` alongside the existing script checks.
2. Extend `scripts/build-coastline.sh` to preserve the z12/20m hero outline and generate a separate max-zoom
   z15/0.5m map outline (archive maximum, 0.01m seam welding) in `files/map-coastline.json`. The original 20m outline clips up to ~14m of coast in
   measured z14 earth geometry, so it is unsuitable at z18. Add a regression for the measured coastal points.
   Update `scripts/build-map-styles.py` and regenerate all three `files/styles/*.json`. Native regression:
   changing Topo to Dark retained Topo's terrain under dark chrome. Fix `map/TrailMap.kt`'s keyed style producer
   to use the requested style's cache entry, rather than return because the previous style was Ready.
   Verify both uncached and cached live style changes with native rendered-water color checks.
   Remove fog from
   `ui/play/map/PlayMapEffects.kt` and remove obsolete derived/coverage scene inputs at MapTab and RecordScreen.
   Update the regression to the simplified API. Document the superseding decision in `docs/play/decisions.md`
   and map rendering in `docs/spikes/map-render/styles.md`.
3. Run Python regressions, `:play:jvmTest :composeApp:testAndroidHostTest`, then the full Gradle verification
   from `app/AGENTS.md` and the iOS simulator Xcode build. Sync existing map assets before the first build.
4. Capture Android/iOS screenshots, review both goals and all map styles, and run fresh adversarial and UI
   review roles. Repair observed problems and rerun affected checks. Save evidence under `docs/mvp/screens/`.

Rollback: revert the changed style generator, generated styles and live scene wiring together. No migrations,
SWIP events, debug drawer changes or Product Atlas changes. Main risk: native polygon holes / `within` support
and shoreline precision at high zoom; verify on the native renderers.
