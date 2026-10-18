# Spike: MapLibre rendering of bundled PMTiles (Task 9)

Result: **local `pmtiles://file://` sources work on both Android and iOS** with maplibre-compose 0.20.0.
No fallback (asset://, MBTiles, tile server shim) was needed.

Screenshots: `base-android.png`, `base-ios.png` (z12, island center), `base-android-z15.png`,
`base-ios-z15.png` (z15, Grand Forest 47.655,-122.548). Android: temp AVD, API 35 arm64 (Pixel 7 profile,
OpenGL ES via host GPU). iOS: iPhone 17 simulator (Xcode 26.2 / iOS 26 SDK).

## Library

- `org.maplibre.compose:maplibre-compose:0.20.0` (latest stable, 2026-10-10). Built against Kotlin 2.4.20 and
  CMP 1.12.1; works with this app's Kotlin 2.4.21 / CMP 1.12.2 / AGP 9.4.1. No snapshot needed.
- 0.20 no longer wraps the MapLibre Native Android SDK / iOS SDK. It drives MapLibre Native core through
  "MapLibre Native FFI" (`org.maplibre.nativeffi:maplibre-native-ffi*:0.202610.0`), with Compose drawing
  the map surface on every platform. Consequences:
  - **iOS needs no SPM/CocoaPods.** The Metal runtime is linked statically through the Kotlin framework. The
    Xcode target only needs extra system libs in Other Linker Flags:
    `-l"c++" -lz -framework CoreFoundation CoreGraphics CoreText Foundation ImageIO Metal QuartzCore`
    (added to both configs in `iosApp.xcodeproj/project.pbxproj`).
  - **Android needs a render backend runtime** at runtime scope:
    `maplibre-compose-runtime-opengl-android` (picked OpenGL ES for emulator + old-device coverage; a
    `-vulkan-android` runtime exists too). ABIs shipped: armeabi-v7a, arm64-v8a, x86_64.
  - API is new vs older docs/blog posts: `rememberMapState(baseStyle, initialCameraPosition)`,
    `MaplibreMap(state, cameraConstraints, overlay)`, `BaseStyle.Json(String)`,
    `CameraConstraints { minZoom; maxZoom; boundingBox }`.

## How assets load (same on both platforms)

1. `scripts/sync-app-assets.sh` copies `data/dist/{trails,basemap}.pmtiles`, `trails.json`,
   `terrain/{contours,hillshade}.pmtiles` flat into `composeApp/src/commonMain/composeResources/files/`
   (gitignored). Styles (`files/styles/*.json`) are committed.
2. On first `TrailMap` composition, `MapAssets.install` reads the bundled `trails.json` `version`; if it
   differs from `<dataDir>/map/.installed-version` (or any file is missing) it copies all tile sets via
   `Res.readBytes` + kotlinx-io (write `.part`, atomic move; stamp written last). Once per process, mutex
   guarded.
   - Android dataDir: `Context.filesDir` → `/data/user/0/com.bainbridgetrails.app/files/map/`
   - iOS dataDir: `Library/` → `.../Library/map/`. Chose Library over `Application Support` to avoid a space
     in the path (URL is percent-encoded anyway, but untested with a space).
3. The style template (`MapStyle.assetPath`) is read and `{{basemap}}`, `{{trails}}`, `{{contours}}`,
   `{{hillshade}}` are replaced with `pmtiles://file:///abs/path.pmtiles`; an unknown placeholder fails
   loudly. Any template using those names works (Task 10). Passed as `BaseStyle.Json`.

Verified second launch does not recopy (file mtimes unchanged).

## Gotchas

- **Source `attribution` in the style is ignored for `url:` (TileJSON) sources** — the PMTiles archive's
  embedded metadata attribution wins. The library's attribution button showed "© OpenStreetMap" (basemap)
  and the trails archive's string, missing Protomaps/USGS. So `TrailMap` replaces the default overlay with
  scale bar + compass + MapLibre logo + a fixed Compose credit line
  "© OpenStreetMap contributors, Protomaps, USGS". Alternative later: set proper attribution in the
  archives at build time (`pmtiles` / tippecanoe `-A`) and restore the library button.
- `CameraConstraints.boundingBox` constrains the camera **center**, not the viewport: at the bbox edge half
  the screen can be outside the island (beyond the basemap extract → background/water color). Fine for
  now; for a tighter feel use a slightly smaller bbox or clamp in the app.
- Labels (Task 10 styles) use Protomaps' public glyph URL, so they show only online; offline the map renders
  without labels (verified). For offline labels bundle glyph PBFs and serve via `file://` (or the library's
  `MapResourceProvider`). Styles and runtime switching: see `styles.md`.
- `Res.readBytes` loads each file fully into memory during the one-time copy (largest: hillshade 8.9 MB).
  OK at this size; stream per platform if data grows.
- Assets are stored twice on device (inside APK/app bundle + copied files dir), ~18.6 MB extra. On iOS
  the bundle files could be referenced directly (NSBundle path) to skip the copy; Android assets are
  compressed inside the APK so they must be copied (or use `asset://` — untested, not needed).
- iOS `Library/` is included in iCloud backup; should set `isExcludedFromBackup` (or move to Caches with
  the existing re-copy-if-missing logic) before release.
- Incremental Android builds kept stale Compose resources from the old default resource package after
  setting `packageOfResClass` (assets duplicated in the APK) — a clean build fixed it.

## Perf impressions (debug builds, emulator/simulator)

- Android emulator: MapLibre runtime created → first map frame ≈ 1.1–1.9 s on cold start (includes the
  one-time 18.6 MB copy on first launch). Panning/zooming smooth; tiles appear instantly (local reads).
- iOS simulator: map visible within a couple of seconds of launch; smooth panning.
- No measurements on real devices yet.

## Size delta

- Android debug APK (universal, assets included both times): 30.0 MB → 72.0 MB. Native libs per ABI:
  arm64-v8a `libmaplibre-native-c.so` 13.4 MB + `libjniMaplibreNativeC.so` 1.0 MB (x86_64 14.1 + 1.0,
  armeabi-v7a 9.0 + 0.8). Per-device (ABI split / AAB) cost ≈ +14–15 MB uncompressed native code plus dex.
- Map data in app: ≈ 18.6 MB (pmtiles are already gzip-compressed internally; APK compression saves ~1%).
- iOS: debug simulator .app = 89 MB (74 MB debug dylib; not representative). Release IPA size not
  measured — no signing configured yet.
