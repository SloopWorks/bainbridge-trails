# Bainbridge Trails — MVP UX spec (visual + interaction)

Date: 2026-10-19 · Owner: Mobile UX designer · Status: decided (no open questions; decisions recorded inline as **Decision:**)
Scope source: `docs/superpowers/specs/2026-10-19-mvp-scope.md`. Map palette source: `docs/spikes/map-render/styles.md`.
Flows and final copy: `docs/mvp/product.md` (product designer). Where this file shows copy, it is placeholder unless product.md agrees; **product.md wins on words, this file wins on layout, color, motion and interaction.**

---

## 0. Design direction: "Chart room"

The map is already distinctive (Topo: lichen land, Sound-teal water, concentric "water lines" off the coast, hillshade + contours). The app chrome must **frame** that map, not compete with it.

- **Paper and ink.** Light theme surfaces are warm chart paper (`#F7F5EF`), text is a deep forest ink (`#1E2A24`). Dark theme is the Dark map's night-water green-black (`#101A16`). No pure white page backgrounds, no pure black.
- **One accent family for chrome: spruce.** Primary is a deep spruce green. Status colors (violet / burnt orange / ochre / warm gray) are **reserved for progress** — no button, tab or decoration ever uses a status hue, so a hue on screen always means "your coverage".
- **One characterful face.** Headings and big numbers in **Fraunces** (soft old-style serif — reads like a chart cartouche / engraved survey title). Everything else is the platform system font, for legibility and dynamic type.
- **Signature motif: water lines.** Thin concentric offset outlines (the Topo coastline treatment) recur in exactly three places: the Me hero, the Celebration, and empty states. Nowhere else. Restraint is the point.
- **Status shapes = map line patterns.** Not started is dotted, Partway dashed, Nearly done solid with dark casing, Complete thick solid. The same patterns appear in rings, chips and the legend, so color is never the only carrier.

---

## 1. Design tokens

All tokens live in `ui/theme/` in `:composeApp` commonMain (`BtColors.kt`, `BtType.kt`, `BtShapes.kt`, `BtSpacing.kt`, `BtMotion.kt`, `BtTheme.kt`). App-specific tokens are exposed via `CompositionLocal` (`LocalBtColors`, `LocalBtSpacing`, `LocalBtMotion`) alongside `MaterialTheme`.

### 1.1 Material 3 color scheme

Contrast verified (WCAG 2.1): `onSurface`/`surface` ≥ 13:1, `onSurfaceVariant`/`surface` ≥ 7:1, `outline`/`surface` ≥ 3.7:1 (non-text 3:1), `onPrimary`/`primary` ≥ 7:1.

```kotlin
// ui/theme/BtColors.kt
val BtLightScheme = lightColorScheme(
    primary = Color(0xFF2E5E4E),            // spruce
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE6DA),
    onPrimaryContainer = Color(0xFF0E2A20),
    inversePrimary = Color(0xFF93D3B6),
    secondary = Color(0xFF3D6E78),          // Sound teal (matches map water family)
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E7EB),
    onSecondaryContainer = Color(0xFF0F2E35),
    tertiary = Color(0xFF6E5A3A),           // contour brown; rare (links to official maps, trailhead pins)
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1E3C8),
    onTertiaryContainer = Color(0xFF2A1E0A),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F5EF),         // chart paper
    onBackground = Color(0xFF1E2A24),       // forest ink
    surface = Color(0xFFF7F5EF),
    onSurface = Color(0xFF1E2A24),
    surfaceVariant = Color(0xFFE3E6DE),
    onSurfaceVariant = Color(0xFF4A554E),
    surfaceTint = Color(0xFF2E5E4E),
    inverseSurface = Color(0xFF2E3530),
    inverseOnSurface = Color(0xFFEEF1EB),
    outline = Color(0xFF75807A),
    outlineVariant = Color(0xFFC5CBC3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBFAF6),
    surfaceDim = Color(0xFFDCDAD3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F0EA),
    surfaceContainer = Color(0xFFECEBE4),
    surfaceContainerHigh = Color(0xFFE6E5DE),
    surfaceContainerHighest = Color(0xFFE0DFD8),
)

val BtDarkScheme = darkColorScheme(
    primary = Color(0xFF93D3B6),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF1A4A3B),
    onPrimaryContainer = Color(0xFFB4EFD3),
    inversePrimary = Color(0xFF2E5E4E),
    secondary = Color(0xFFA3CED7),
    onSecondary = Color(0xFF01363F),
    secondaryContainer = Color(0xFF224D56),
    onSecondaryContainer = Color(0xFFC6EAF2),
    tertiary = Color(0xFFDCC39A),
    onTertiary = Color(0xFF3D2E10),
    tertiaryContainer = Color(0xFF554525),
    onTertiaryContainer = Color(0xFFF7E0B8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101A16),         // night water, matches Dark map chrome #13201B
    onBackground = Color(0xFFDCE6E1),       // = Dark map label ink
    surface = Color(0xFF101A16),
    onSurface = Color(0xFFDCE6E1),
    surfaceVariant = Color(0xFF3F4A44),
    onSurfaceVariant = Color(0xFFBCC8C1),
    surfaceTint = Color(0xFF93D3B6),
    inverseSurface = Color(0xFFDCE6E1),
    inverseOnSurface = Color(0xFF2A3530),
    outline = Color(0xFF87938C),
    outlineVariant = Color(0xFF3F4A44),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF35403B),
    surfaceDim = Color(0xFF101A16),
    surfaceContainerLowest = Color(0xFF0B1410),
    surfaceContainerLow = Color(0xFF17221E),
    surfaceContainer = Color(0xFF1B2722),
    surfaceContainerHigh = Color(0xFF25312C),
    surfaceContainerHighest = Color(0xFF303C37),
)
```

**Decision:** no dynamic color (Material You). The brand palette must stay stable so status colors never clash with a wallpaper-derived primary.

### 1.2 App-specific colors

Each status has four roles because map line colors are not all text-safe on paper (partway `#C98A00` is 2.7:1 on `#F7F5EF`):

| Role | Use | Requirement |
|---|---|---|
| `line` | Map layers only. **Exactly** styles.md values (Topo column for light app, Dark column for dark app; Light map style uses its own column inside the style JSON). | — |
| `accent` | Rings, bars, icons, legend swatches on app surfaces | ≥ 3:1 on `surface` |
| `container` | StatusChip background, row tint | — |
| `onContainer` | Text/icon on `container` | ≥ 7:1 (verified) |

```kotlin
// ui/theme/BtColors.kt
@Immutable data class StatusColors(val line: Color, val accent: Color, val container: Color, val onContainer: Color)

@Immutable data class BtExtraColors(
    val notStarted: StatusColors,
    val partway: StatusColors,
    val nearlyDone: StatusColors,
    val complete: StatusColors,
    val nearlyDoneCasing: Color,   // map + ring casing
    val notCounted: Color,         // map hairline; legend only in app UI
    val userTrack: Color,          // live GPS track on map
    val userTrackCasing: Color,
    val recording: Color,          // recording dot / pill
    val onRecording: Color,
    val selectionCasing: Color,    // selected-trail outline on map
    val mapChrome: Color,          // floating map buttons/legend background
    val onMapChrome: Color,
    val waterLine: Color,          // decorative water-line motif
    val positive: Color,           // "+0.4 pts" deltas
)

val BtExtraLight = BtExtraColors(
    notStarted = StatusColors(line = Color(0xFF6E6A64), accent = Color(0xFF6E6A64), container = Color(0xFFE8E6E2), onContainer = Color(0xFF3F3C37)),
    partway    = StatusColors(line = Color(0xFFC98A00), accent = Color(0xFFA87400), container = Color(0xFFF8EBC8), onContainer = Color(0xFF5E4200)),
    nearlyDone = StatusColors(line = Color(0xFFE2562A), accent = Color(0xFFE2562A), container = Color(0xFFFCE2D8), onContainer = Color(0xFF7A2A0F)),
    complete   = StatusColors(line = Color(0xFF9B1FA8), accent = Color(0xFF9B1FA8), container = Color(0xFFF3DDF6), onContainer = Color(0xFF5E0F68)),
    nearlyDoneCasing = Color(0xFF5A1F0C),
    notCounted = Color(0xBF7D8679),          // #7D8679 @ .75
    userTrack = Color(0xFF0B63CE),           // existing TrackLayer value; blue is free of status hues
    userTrackCasing = Color(0xFFFFFFFF),
    recording = Color(0xFFC0262D),           // 5.4:1 on paper; always paired with dot + "Recording" text
    onRecording = Color(0xFFFFFFFF),
    selectionCasing = Color(0xFF1E2A24),
    mapChrome = Color(0xF2FBFAF6),           // 95% surfaceBright
    onMapChrome = Color(0xFF26302B),         // styles.md switcher ink
    waterLine = Color(0xFF6F97A2),           // Topo water-line color
    positive = Color(0xFF2E5E4E),
)

val BtExtraDark = BtExtraColors(
    notStarted = StatusColors(line = Color(0xFFA9A49B), accent = Color(0xFFA9A49B), container = Color(0xFF2E302D), onContainer = Color(0xFFD3CFC7)),
    partway    = StatusColors(line = Color(0xFFF0B43C), accent = Color(0xFFF0B43C), container = Color(0xFF42330D), onContainer = Color(0xFFF8D98A)),
    nearlyDone = StatusColors(line = Color(0xFFFF7B47), accent = Color(0xFFFF7B47), container = Color(0xFF4A2112), onContainer = Color(0xFFFFC2A8)),
    complete   = StatusColors(line = Color(0xFFD45CFF), accent = Color(0xFFD45CFF), container = Color(0xFF3E1846), onContainer = Color(0xFFF0C2FF)),
    nearlyDoneCasing = Color(0xFF0B1410),
    notCounted = Color(0xCC5E6E66),          // #5E6E66 @ .8
    userTrack = Color(0xFF5EA8FF),
    userTrackCasing = Color(0xFF0B1410),
    recording = Color(0xFFFF8A80),
    onRecording = Color(0xFF3B0907),
    selectionCasing = Color(0xFFDCE6E1),
    mapChrome = Color(0xF213201B),           // styles.md Dark switcher bg @ 95%
    onMapChrome = Color(0xFFDCE6E1),
    waterLine = Color(0xFF2E5564),
    positive = Color(0xFF93D3B6),
)
```

Contrast (measured): accents on paper — complete 6.1, nearly 3.4, partway(accent) 3.7, not started 4.9; on `#101A16` — 5.7 / 6.9 / 9.6 / 7.2. `onContainer` on `container`: light 7.9–9.5, dark 8.6–9.7.

**Rule:** status *text* in a status color is only allowed via `onContainer` on `container` (StatusChip). Never write body text in `accent`.

Helper:
```kotlin
fun BtExtraColors.of(s: TrailStatus): StatusColors = when (s) {
    TrailStatus.NOT_STARTED -> notStarted; TrailStatus.PARTWAY -> partway
    TrailStatus.NEARLY_DONE -> nearlyDone; TrailStatus.COMPLETE -> complete
}
```

### 1.3 Typography

**Decision: Fraunces (Google Fonts, SIL OFL 1.1) for display/headline/titleLarge and big numbers; platform default (Roboto on Android, SF Pro on iOS via `FontFamily.Default`) for everything else.**
Why Fraunces: an old-style "wonky" serif with soft terminals — evokes engraved chart titles and park signage without being kitsch; at large sizes it gives the app a voice the map alone can't. System body keeps dynamic type, hinting and tabular figures solid.

Bundle (static instances, opsz 72, SOFT 50, WONK 0) into `app/composeApp/src/commonMain/composeResources/font/`:
- `fraunces_semibold.ttf` (600)
- `fraunces_bold.ttf` (700)
- `fraunces_semibold_italic.ttf` (600 italic) — used only for the trail name on Celebration and Me hero caption (italic = water/relief convention on the map).

Generate from the Google Fonts variable file if no matching static exists:
`fonttools varLib.instancer Fraunces[SOFT,WONK,opsz,wght].ttf opsz=72 SOFT=50 WONK=0 wght=600 -o fraunces_semibold.ttf` (repeat for 700, and the Italic file). Add the OFL text to `data/dist/ATTRIBUTION.md` / About screen. Size budget: ~3 × 150 KB.

```kotlin
// ui/theme/BtType.kt
@Composable fun frauncesFamily() = FontFamily(
    Font(Res.font.fraunces_semibold, FontWeight.SemiBold),
    Font(Res.font.fraunces_bold, FontWeight.Bold),
    Font(Res.font.fraunces_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
)

@Composable fun btTypography(): Typography {
    val display = frauncesFamily()
    val sys = FontFamily.Default
    return Typography(
        displayLarge   = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.5).sp),
        displayMedium  = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 45.sp, lineHeight = 52.sp),
        displaySmall   = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge  = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall  = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge     = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium    = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        titleSmall     = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge      = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        bodyMedium     = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
        bodySmall      = TextStyle(fontFamily = sys, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelLarge     = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        labelMedium    = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
        labelSmall     = TextStyle(fontFamily = sys, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    )
}

// App-specific (LocalBtType)
val statHero   = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1).sp)   // Me island %, Celebration %
val statLarge  = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp)                      // StatTile value
val statLive   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, fontFeatureSettings = "tnum") // live timer/distance: must not jitter
val numeric    = bodyMedium.copy(fontFeatureSettings = "tnum")                                                                               // % in rows, ranks
```

Rules: any number that **changes while visible** (timer, distance, animated counters) uses system font + `tnum`. Fraunces only for numbers that are static once shown (or animate only on Celebration where jitter is acceptable — use `statHero` with fixed-width `Box` sized to "100.0%" to avoid reflow). `%` sign rendered at 0.5× size, baseline-aligned (`SpanStyle(fontSize = 0.5.em)`).

### 1.4 Shapes

```kotlin
val BtShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // tooltips, small badges
    small = RoundedCornerShape(8.dp),        // text fields, legend swatch boxes
    medium = RoundedCornerShape(12.dp),      // cards, stat tiles, list containers
    large = RoundedCornerShape(16.dp),       // floating map panels, dialogs content cards
    extraLarge = RoundedCornerShape(28.dp),  // bottom sheets (top corners only), dialogs
)
// Pills (StatusChip, RecordingPill, filter chips, map style chip): CircleShape / RoundedCornerShape(50)
// Record center action: CircleShape
```

### 1.5 Spacing & sizing

```kotlin
@Immutable data class BtSpacing(
    val xxs: Dp = 2.dp, val xs: Dp = 4.dp, val sm: Dp = 8.dp, val md: Dp = 12.dp,
    val lg: Dp = 16.dp, val xl: Dp = 24.dp, val xxl: Dp = 32.dp, val xxxl: Dp = 48.dp,
    val screenH: Dp = 16.dp,        // horizontal screen margin
    val sectionGap: Dp = 24.dp,     // between sections
    val minTouch: Dp = 48.dp,       // every interactive element (iOS ≥ 44pt satisfied)
    val rowHeight: Dp = 72.dp,      // TrailRow / WalkRow / LeaderRow
    val bottomBarHeight: Dp = 80.dp,
    val recordFab: Dp = 64.dp,      // center action diameter
    val mapButton: Dp = 48.dp,      // locate / legend buttons
)
```

Content max width 600dp, centered (tablets / landscape).

### 1.6 Elevation

Prefer tonal elevation (surfaceContainer* roles). Shadows only for things floating **over the map**, because tone alone disappears over map imagery.

| Level | Tonal role | Shadow | Use |
|---|---|---|---|
| 0 | surface | 0 | Screens |
| 1 | surfaceContainerLow | 0 | Cards, list sections |
| 2 | surfaceContainer | 0 | Bottom bar |
| 3 | surfaceContainerHigh / mapChrome | 3dp | Map floating buttons, legend, search over map |
| 4 | surfaceContainerLow | 6dp | Bottom sheets over map, RecordingPill |
| 5 | surfaceContainerHigh | 8dp | Record center action, dialogs |

### 1.7 Motion

```kotlin
object BtEasing {
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val standardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}
object BtDuration { // ms
    const val short1 = 50; const val short2 = 100; const val short3 = 150; const val short4 = 200
    const val medium1 = 250; const val medium2 = 300; const val medium3 = 350; const val medium4 = 400
    const val long1 = 450; const val long2 = 500; const val long3 = 550; const val long4 = 600
    const val extraLong1 = 700; const val extraLong2 = 800; const val extraLong3 = 900; const val extraLong4 = 1000
}
```

| Interaction | Duration | Easing |
|---|---|---|
| Push screen (enter: slide 1/4 width + fade; exit: fade 0.9 + slide −1/10) | medium4 | emphasizedDecelerate / emphasizedAccelerate |
| Tab switch | crossfade short4 | standard |
| Bottom sheet expand/collapse | medium2 (Material default spring OK) | — |
| Map camera fit to trail | long1 | emphasized (MapLibre `animateTo` duration) |
| Progress ring / bar fill on first show | long2, staggered 30ms per row, max 8 rows | emphasizedDecelerate |
| Status change (chip color) | medium1 crossfade | standard |
| Recording dot pulse | 1200 loop, alpha 1 → 0.35 → 1 | standard |
| Celebration | see §3.6 | |

**Reduced motion.** Provide `expect @Composable fun rememberReducedMotion(): Boolean`:
- Android: `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` or `AccessibilityManager` "Remove animations" (API 33+: `Settings.Global.TRANSITION_ANIMATION_SCALE == 0f` also counts).
- iOS: `UIAccessibility.isReduceMotionEnabled`, observe `UIAccessibilityReduceMotionStatusDidChangeNotification`.

When true: screen transitions → crossfade short3; camera moves → jump (no animation); progress fills → final value immediately; recording dot → static (no pulse); Celebration → static variant (§3.6); no parallax, no counters, no particle/ring effects. Haptics stay (they are not motion).

---

## 2. Navigation model

### 2.1 Structure

```
App
├─ Onboarding (first run only; full-screen, no bottom bar)
└─ Shell (bottom bar)
   ├─ Tab: Map        root: MapScreen
   ├─ Tab: Trails     root: TrailsListScreen
   ├─ [Record]        center action → RecordFlow (full-screen overlay, bottom bar hidden)
   └─ Tab: Me         root: MeScreen
   Pushed (inside the current tab's stack, bottom bar stays visible):
     TrailDetail(trailId), Leaderboard, Profile, Settings, Attribution, WalkDetail(walkId) [summary only]
   Modal (full-screen, above everything): Celebration(trailIds), RecordFlow
```

### 2.2 Bottom bar

```
┌──────────────────────────────────────────────────┐
│   ◎        ☰                ⬤              ☺     │   ← 64dp record disc rises 16dp above bar top
│  Map     Trails          Record           Me     │
└──────────────────────────────────────────────────┘
```

- `surfaceContainer` bar, height 80dp + bottom inset. **Decision:** four slots in a custom `Row` — Map · Trails · ⬤ Record · Me — weighted `1 · 1 · 1.2 · 1`. The Record slot is not a `NavigationBarItem`: it is a custom 64dp circle (`primary` fill, `onPrimary` icon, elevation 5) rising 16dp above the bar, label "Record" below in `labelMedium`. It never shows a "selected" state (it opens an overlay, not a tab).
- Icons (Material Symbols Rounded, 24dp): Map `map`, Trails `hiking`, Me `person`. Selected: filled icon + `secondaryContainer` pill indicator (M3 default) + label `onSurface`; unselected `onSurfaceVariant`.
- Record disc icon: idle `directions_walk`; while recording the disc shows a `recording` red ring (3dp) around it with the pulsing dot, and label "Recording".
- Tab badges: none in MVP.

### 2.3 Back behavior

| Where | System back / iOS edge-swipe |
|---|---|
| Pushed screen | Pop |
| Tab root (Trails, Me) | Switch to Map tab |
| Map root with bottom sheet open | Collapse/close sheet |
| Map root, nothing open | Android: exit app (system default). iOS: no-op |
| Re-tap current tab | Pop that tab to root; if already root: Map → recenter to island bounds; Trails → scroll to top + clear nothing |
| RecordFlow, not recording | Close overlay → previous tab |
| RecordFlow, recording | **Minimize** (never stops). Overlay slides down; RecordingPill appears |
| RecordFlow, finalizing | Disabled (finalizing is ≤ a few seconds) |
| Results sheet | Same as "Done" |
| Celebration | Same as "Continue" (advances to next completed trail if several, then closes) |
| Onboarding page 2+ | Previous page; page 1: Android exit, iOS no-op |

Android: enable predictive back (`android:enableOnBackInvokedCallback="true"`), use `PredictiveBackHandler`/navigation's built-in support so the pop preview animates. iOS: left-edge swipe (20pt edge zone) pops pushed screens; it is disabled on Map root, RecordFlow (use the explicit minimize chevron) and Celebration.

### 2.4 State preservation

- Each tab keeps its own back stack (`saveState = true / restoreState = true` style), surviving tab switches **and** process death (`rememberSaveable`, nav saved state).
- Preserved: Map camera (target, zoom, bearing 0 forced), selected trail + sheet state; Trails search text, filters, sort, scroll position; Me scroll; Leaderboard scroll.
- Not preserved across process death: celebration queue (re-derived from "summary shown" flag — see scope known-fix), transient snackbars.
- Deep link targets (internal only for MVP): `bt://trail/{id}` → Trails tab stack [List, Detail]; `bt://record` → RecordFlow (used by the recording notification tap).

### 2.5 Record overlay and RecordingPill

- Tap Record disc → RecordFlow slides up (medium4, emphasizedDecelerate) as a full-screen layer above the Shell. Bottom bar hidden; system bars follow map style.
- RecordFlow top-left: minimize chevron `keyboard_arrow_down` (48dp) — content description "Minimize, recording continues" while recording, "Close" otherwise.
- While a session is active and RecordFlow is minimized, **RecordingPill** floats centered above the bottom bar (12dp gap), on every Shell screen (including pushed ones). Tap → RecordFlow. It never covers the Record disc; on Map it pushes the trail bottom sheet peek up by its height + 8dp.
- Notification tap (Android FGS notification / iOS live-activity-less banner) → `bt://record`.
- Starting from TrailDetail "Start walk" opens RecordFlow with `focusTrailId` (camera fits that trail, the trail gets the selection casing, the live list pins it first).

---

## 3. Screens

Common state rules (apply everywhere unless overridden):
- **Loading** (dataset parse is 5–8 s on debug, see scope): first launch shows a full-screen `DatasetLoading` (island water-lines motif + "Loading trails…" + indeterminate LinearProgressIndicator, primary). Afterwards screens show skeleton rows (surfaceContainerHigh blocks, no shimmer under reduced motion; shimmer 1200ms otherwise).
- **Offline:** local data always works. Online-only parts (community stats, leaderboard, sync) show inline `OfflineNote` (cloud_off icon + "Offline — showing saved data from {relative time}" or "Available when you're online"). Never a blocking error screen for offline.
- **Signed out:** everything works; leaderboard rank + sync + community "you" bits show a `SignInPrompt` card instead.
- **Error:** inline `EmptyState` with error variant + Retry; no raw exception text (log to debug drawer).
- All screens: content inside `WindowInsets.safeDrawing` except the map, which is full-bleed.

### 3.1 Onboarding (first run, 3 pages)

```
┌────────────────────────────┐
│                     Skip   │  ← text button, top-right (pages 1–2)
│                            │
│      [ illustration ]      │  ← 240dp tall. P1: island silhouette with water lines,
│                            │     trails drawn in all 4 statuses. P2: a single trail
│                            │     going dotted → dashed → solid → thick violet.
│                            │     P3: phone with a lock + island.
│  Walk every trail on       │  ← headlineMedium (Fraunces)
│  Bainbridge                │
│  Body copy, 2–3 lines      │  ← bodyLarge, onSurfaceVariant
│                            │
│         ● ○ ○              │  ← page indicator 8dp dots, primary / outlineVariant
│ ┌────────────────────────┐ │
│ │        Next            │ │  ← FilledButton 56dp, full width - 2×screenH
│ └────────────────────────┘ │
└────────────────────────────┘
```
- Pages (copy per product.md): 1 what the app does; 2 how coverage works (shows the 4 statuses with the StatusLegend component, real patterns); 3 privacy: "Your tracks stay on your phone" + "Get started".
- `HorizontalPager`, swipe enabled. Illustrations are Canvas-drawn from tokens (no raster assets) so they theme in dark mode. Page 2 illustration animates the line pattern morph once (long4) — reduced motion: static 4-status strip.
- **No permission request here** (scope: asked at first Start).
- "Get started" → Shell, Map tab, camera at island bounds. Persist `onboardingDone`.
- States: n/a (fully local). Dark mode supported.

### 3.2 Map tab

```
┌────────────────────────────┐
│ ▓ status bar (map beneath) │
│ ┌──────────────┐  ┌──┐     │
│ │ 🔍 Find a trail│  │🏆│     │  ← MapSearchChip (opens Trails tab with search focused); Leaderboard shortcut 48dp
│ └──────────────┘  └──┘     │
│                            │
│        (map, full bleed)   │
│                            │
│ ┌──┐                 ┌──┐  │
│ │≡K│                 │◎ │  │  ← Legend button (left), Locate button (right), 48dp, mapChrome
│ └──┘                 └──┘  │
│ ⓘ                          │  ← attribution (see §6.6)
├────────────────────────────┤
│  bottom bar                │
└────────────────────────────┘
```

Components: `TrailMap` (full bleed, under status bar), `MapSearchChip`, `MapIconButton`(Leaderboard), `MapIconButton`(Legend) + `StatusLegend` card, `LocateButton`, `TrailPeekSheet`, `TrailChooserSheet`, `RecordingPill` (when active).

**Trail tap → TrailPeekSheet** (non-modal bottom sheet over map, map stays interactive):
```
┌────────────────────────────┐
│           ───              │  ← drag handle
│ Gazzam Lake Main Trail     │  ← titleLarge (Fraunces), 2 lines max
│ Gazzam Lake Preserve · 1.4 mi  ← bodyMedium onSurfaceVariant
│ (◐ 42%)  [◐ Partway]       │  ← ProgressRing 48dp + StatusChip
│ ▬▬▬▬▬▬▬▬▬▬░░░░░░░░░░░░     │  ← (optional) ProgressBar hidden here; ring is enough
│ ┌────────────┐ ┌─────────┐ │
│ │ Start walk │ │ Details │ │  ← FilledButton (primary) + OutlinedButton
│ └────────────┘ └─────────┘ │
└────────────────────────────┘
```
- Peek height ≈ 196dp + nav inset; one detent only (no half/full expansion) — swipe up or tap body → TrailDetail push. Swipe down / tap empty map / back → dismiss + clear selection.
- Overlap (tap hits >1 named trail): `TrailChooserSheet` — header "{n} trails here", list of compact `TrailRow`s; choosing one selects it.
- Legend: collapsed by default after first 3 app launches; expanded on launches 1–3. Expanded card (large shape, mapChrome, 3dp shadow): 4 status rows + "Not counted" row, each with a 32×8dp Canvas swatch drawing the **map** pattern in `line` color of the active map style, label `bodyMedium`. Footnote `bodySmall`: "Colors show how much of each stretch you've walked." Tap outside or button → collapse.
- Locate button states: `my_location` outline (idle) → tap: if permission granted, animate to user at z15 (or keep zoom if >15), enters follow mode (icon filled, primary tint); any user pan exits follow. No permission: tap opens the existing PermissionFlow (foreground only, no background ask here). Location off/unavailable: snackbar "Can't find your location" + action "Settings". User off-island (> 3 km from island bounds): snackbar "You're off the island" and camera stays.
- Status bar icons: dark on Topo/Light, light on Dark map.

States:
- Loading: map style renders immediately (bundled PMTiles); trail statuses render as all `not_started` until coverage state loads (no spinner — dotted lines are the honest default). Search chip disabled until dataset ready.
- Empty (new user): normal map, all dotted. One-time coach mark anchored to Record disc: "Start a walk to color in the trails" (dismiss on tap; product.md copy).
- Offline: no visible change (map is fully bundled). Labels may be missing until glyphs bundled (styles.md) — acceptable for MVP.
- Error (style failed to load): EmptyState over paper background "Map couldn't load" + Retry.
- Signed out: no change.

### 3.3 Trails list

```
┌────────────────────────────┐
│ Trails              🏆     │  ← TopAppBar (headlineSmall Fraunces left-aligned) + Leaderboard action
│ ┌────────────────────────┐ │
│ │ 🔍 Search trails       │ │  ← SearchField (docked, 56dp, surfaceContainerHigh, extraLarge shape)
│ └────────────────────────┘ │
│ [Not started][Partway][Nearly done][Complete] [Park ▾] [Sort: Name ▾] ← horizontally scrolling FilterChips
│ 12 of 114 trails · 3 complete  ← labelLarge onSurfaceVariant, live region
│ ───────────────────────────│
│ (◌)  Arbor Trail           │
│  0%  Grand Forest · 0.3 mi │  ← TrailRow
│                [◌ Not started]
│ (◐)  Gazzam Lake Main Trail│
│ 42%  Gazzam Lake · 1.4 mi  │
│                  [◐ Partway]
└────────────────────────────┘
```
- Search: case/diacritic-insensitive substring on name and park; debounced 150ms; clear (x) icon 48dp. IME action Search hides keyboard.
- Status filter chips: multi-select; each chip shows the status glyph (leading icon, `accent` color) + label; selected chip uses `container`/`onContainer` of that status (allowed: chip is about status). Park chip opens a menu/bottom sheet of parks (alphabetical, "Any park" first, "No park" last). Sort chip menu: Name (A–Z), Progress (high→low), Length (long→short). Default: Name.
- Rows: see `TrailRow` (§4). Tap → TrailDetail. No swipe actions.
- Sticky "summary line" updates on filter change (TalkBack announces politely).
- Length shown: official length if present else computed, rounded 0.1 mi / 0.1 km; prefix "~" when computed (consistent with TrailDetail).

States:
- Loading: 8 skeleton rows.
- Empty (filters): EmptyState "No trails match" + "Clear filters" TextButton.
- Empty (search): "No trails called "{q}"" + "Clear search".
- Offline / signed out: no change (fully local).
- Error: dataset failed → EmptyState error + Retry.

### 3.4 Trail detail (pushed)

```
┌────────────────────────────┐
│ ←                     ⋯    │  ← transparent top bar over mini map (icons in mapChrome circles)
│ ┌────────────────────────┐ │
│ │    mini map 240dp      │ │  ← non-interactive TrailMap, fitted to trail, trail selected style;
│ │  (tap → Map tab focus) │ │     tap opens Map tab with this trail selected (content desc "Show on map")
│ └────────────────────────┘ │
│ Gazzam Lake Main Trail     │  ← headlineMedium Fraunces
│ Gazzam Lake Preserve · BIMPRD ← bodyMedium onSurfaceVariant (park · manager)
│ [◐ Partway]        42%     │  ← StatusChip + statLarge %
│ ▬▬▬▬▬▬▬▬▬░░░░░░░░░░░░░     │  ← ProgressBar 8dp with 75% & 85% tick marks
│ 0.6 of 1.4 mi walked       │  ← bodyMedium
│                            │
│ ┌──────────┐ ┌──────────┐  │
│ │ 1.4 mi   │ │ ↑ 38 m   │  │  ← StatTile grid 2×2
│ │ Length   │ │ Elev gain│  │
│ ├──────────┤ ├──────────┤  │
│ │ ↓ 41 m   │ │ 2        │  │
│ │ Elev loss│ │Trailheads│  │
│ └──────────┘ └──────────┘  │
│ Elevation                  │  ← SectionHeader
│ "Gentle — 38 m up, 41 m down over 1.4 mi"  ← summary sentence; tier from gain per mi:
│                              <30 m/mi Gentle, <60 Rolling, else Hilly
│ Trailheads                 │
│ 📍 Gazzam Lake – Marshall Rd   ›  ← ListItem 56dp; tap → Map tab centered on trailhead z16
│ Official map               │
│ ↗ bainbridge parks site    │  ← ListItem w/ open_in_new, opens system browser; hidden if no URL
│ Community                  │
│ ┌────────────────────────┐ │
│ │ 23 people completed    │ │  ← CommunityCard
│ │ Median progress 48%    │ │
│ └────────────────────────┘ │
│ (spacer 88dp)              │
├────────────────────────────┤
│ ┌────────────────────────┐ │
│ │      Start walk        │ │  ← sticky bottom FilledButton 56dp on surface gradient fade,
│ └────────────────────────┘ │     above bottom bar (bar remains visible)
└────────────────────────────┘
```
- Progress bar tick marks at nearly-done (75%) and complete (85%) thresholds (read from `CoverageParams`), 2dp × 12dp, `outline`. Content description: "42 percent walked. Nearly done at 75, complete at 85."
- Complete trails: bar full-color violet capped at actual % (e.g. 91%); add `verified` seal icon next to chip; Start walk label stays (walking more is fine).
- Overflow ⋯ menu: "Show on map", "Copy name". (No share in MVP.)
- Elevation: no profile chart in MVP (data has gain/loss/climb only). Units follow settings (m or ft; mi or km).
- Not-counted trails cannot be opened (they are not in the named list), so no special state.

States:
- Loading: mini map placeholder (surfaceContainerHigh) + skeleton.
- Community offline: CommunityCard shows OfflineNote "Community stats need a connection" (last cached values with "as of" if cached).
- Community < 3 users completed: show "Be one of the first to finish this trail" (no small-number stats → privacy).
- Signed out: community stats still shown (public); no change.
- Error (unknown trailId): EmptyState "Trail not found" + Back.

### 3.5 Record flow (full-screen overlay)

All sub-states share: full-bleed TrailMap (follow user, z16, bearing north-up — no heading rotation in MVP), user track layer (`userTrack` 4dp + 2dp casing), current position puck (MapLibre location component, `primary`), minimize chevron top-left, chips top-center.

**a) Idle (not recording)**
```
┌────────────────────────────┐
│ ⌄                          │
│                            │
│          (map)             │
│                            │
│ ┌────────────────────────┐ │
│ │ Ready when you are     │ │  ← titleLarge Fraunces
│ │ Focus: Gazzam Lake …  ✕│ │  ← only if started from TrailDetail; chip w/ clear
│ │ ┌────────────────────┐ │ │
│ │ │    ▶  Start walk   │ │ │  ← FilledButton 64dp tall, primary
│ │ └────────────────────┘ │ │
│ │ Tracks stay on your phone │ ← bodySmall onSurfaceVariant + lock icon
│ └────────────────────────┘ │
└────────────────────────────┘
```

**b) Permission** — existing `PermissionFlow` / `LocationAsk` steps, restyled: full-screen paper surface, "Step n of m" `labelLarge`, title `headlineMedium`, body `bodyLarge`, illustration 160dp, primary FilledButton "Continue", TextButton "Not now". OPEN_SETTINGS variants: button "Open Settings". Optional steps (activity/steps): show "Optional — without it, walks are marked Unverified." in `bodyMedium onSurfaceVariant`. Denied → back to Idle with inline error-container banner "Location is needed to record" + "Open Settings".

**c) Waiting for GPS** — same as Recording, timer running, neutral StateChip "Finding GPS…" with a 16dp indeterminate CircularProgressIndicator. Never use status colors for GPS chips.

**d) Recording**
```
┌────────────────────────────┐
│ ⌄   [● GPS good] [Unverified ⓘ] │  ← StateChips (neutral), Unverified opens explainer sheet
│                            │
│          (map follows)  ◎  │  ← Locate (re-enter follow) appears only after user pans
│                            │
│ ┌────────────────────────┐ │  ← RecordSheet: bottom sheet, 2 detents (peek / expanded)
│ │ ● Recording            │ │  ← recording dot (pulse) + labelLarge in `recording`
│ │ 01:08:22   2.41 mi     │ │  ← statLive ×2 (time, distance), labels bodySmall below
│ │ Trails this walk  (3) ⌃│ │  ← SectionHeader; expands sheet
│ │ (◐) Gazzam Main  12→31%│ │  ← LiveTrailRow (peek shows top 1–2)
│ │ ┌────────────────────┐ │ │
│ │ │       Finish       │ │ │  ← primary FilledButton 56dp, full width
│ │ └────────────────────┘ │ │
│ └────────────────────────┘ │
└────────────────────────────┘
```
- **Decision:** no Pause in MVP (tracking module has no pause state); single Finish button. Finish is `primary` (not red — finishing is success, not destruction). Tap Finish → AlertDialog "Finish this walk?" [Keep walking] [Finish]. Prevents pocket taps.
- Spike "Fixes" stat is removed from the main UI (debug drawer only).
- **Live provisional trails list** (`LiveTrailRow`): each trail touched this session, ordered by most-recently touched; shows ProgressRing 32dp with two arcs (before = status accent at 40% alpha, gained this walk = full accent), name, "12% → 31%" (`numeric`), and a "Provisional" caption once in the header (`bodySmall`: "Updates as you walk; final after Finish"). Status crossings during the walk animate the chip (medium1) and fire a light haptic; **no celebration mid-walk** (avoid false positives; celebrate after matching on Finish).
- Empty list: "No trails yet — head to a trail to start counting" with a dotted-line glyph.
- Expanded sheet height: max 70% of screen; map remains visible above.

**e) Finalizing** — sheet content replaced: CircularProgressIndicator 48dp (indeterminate) + "Saving your walk…" `titleMedium`. Not cancellable, back disabled. Reduced motion: indicator static ring + text (Compose indeterminate indicators still rotate; replace with a static `Icons.Rounded.HourglassTop`).

**f) Results** (ResultsSheet restyled; modal bottom sheet over map that now shows the walk's track + updated statuses)
```
┌────────────────────────────┐
│ Walk saved                 │  ← headlineSmall Fraunces
│ 1:08 · 2.4 mi              │  ← bodyMedium onSurfaceVariant
│ ┌────────────────────────┐ │
│ │ Island  11.9% → 12.4%  │ │  ← StatTile wide: "+0.5 pts" in `positive`
│ └────────────────────────┘ │
│ ⚠ Unverified walk ⓘ        │  ← only if !verified; tertiaryContainer banner
│ Trails                     │
│ (◕) Gazzam Main 61→78% [◕ Nearly done]  ← ResultTrailRow: before→after, chip if status changed
│ (●) Close Tract 70→88% [● Complete]     │
│ No trail progress this walk.            ← empty variant
│ 14 points ignored (vehicle speed) ⓘ     ← bodySmall, only if >0
│ ┌────────────────────────┐ │
│ │         Done           │ │
│ └────────────────────────┘ │
└────────────────────────────┘
```
- If any trail crossed into COMPLETE: Celebration plays **first** (full-screen), then Results sheet is revealed underneath on dismiss. Persist "summary shown" so a second sheet never appears (scope known-fix).
- Done → close RecordFlow → return to previous tab (Map tab if launched from bar).
- **Crash recovery**: on app start with a recovered session, RecordFlow opens directly in Results with a banner "We recovered a walk that was interrupted" (secondaryContainer).

States: Offline — results computed locally; sync queued, show `bodySmall` "Will sync when online" with cloud_upload icon if signed in. Signed out — results + one-time `SignInPrompt` card "Sign in to put this on the leaderboard" (dismissible, max once per 7 days).

### 3.6 Celebration (full-screen modal)

```
┌────────────────────────────┐
│                            │
│     ((( ~~~~~~~~~ )))      │  ← water-line rings expanding from the trail glyph
│        ╱╲_╱‾‾╲             │  ← the trail's own geometry, drawn in Canvas, violet `complete.accent`, 6dp
│                            │
│     Trail complete         │  ← labelLarge caps tracking 1.5sp, complete.accent
│  Gazzam Lake Main Trail    │  ← displaySmall Fraunces SemiBold Italic, centered, 3 lines max
│  You've walked 88% of it   │  ← bodyLarge
│                            │
│        12.4%               │  ← statHero (island %), counter
│     of the island          │  ← bodyMedium
│   9 of 114 trails complete │  ← titleMedium
│   23 others finished it    │  ← bodyMedium onSurfaceVariant (online, ≥3 only)
│                            │
│ ┌────────────────────────┐ │
│ │       Continue         │ │  ← FilledButton (primary). If more completions: "Next trail (2 of 3)"
│ └────────────────────────┘ │
└────────────────────────────┘
```
- Background: `surface` with a radial wash of `complete.container` from the trail glyph center (alpha .6 → 0 at 70% radius). Dark: same with dark tokens.
- Trail glyph: project the trail's segment polylines (from `coverage_segments.json`) into a 200×160dp box (fit, keep aspect), stroke 6dp round caps, `complete.accent`.

**Animation timeline (full motion), total ≈ 2.4 s, then idle:**
| t (ms) | Element | Animation |
|---|---|---|
| 0–150 | Screen | fade in from scrim (short3) |
| 100–1300 | Trail glyph | path draws 0→100% (`PathMeasure` segment), emphasizedDecelerate. Before draw: faint dotted ghost of the trail (not_started pattern) so it reads as "dots become solid" |
| 1100–1400 | Glyph | stroke width 6→8→6dp pulse, haptic `LongPress`/`HapticFeedbackType.Confirm` (iOS `.success`) at 1300 |
| 1200–2400 | Water lines | 3 concentric offset outlines of the glyph (offset 10/20/30dp, `waterLine` color, 1.5dp) expand outward from offset 0 and fade alpha .8→0, staggered 150ms. Plays **once**, no loop |
| 600–900 | "Trail complete" + name | fade + 8dp rise (emphasizedDecelerate) |
| 900–1700 | Island % | counter old → new value in `statHero` (Fraunces) inside a fixed-width box sized to "100.0%" and end-aligned, so proportional figures can't reflow the layout |
| 1700–2000 | Stats + button | fade in |

**Reduced-motion variant:** single 150ms crossfade to the final frame: glyph fully drawn, one static water-line ring at 20dp offset, final numbers, no counter, no pulse. Haptic still fires once on appear.

**Screen reader:** on appear, move accessibility focus to a merged heading node (`Modifier.semantics(mergeDescendants = true) { heading(); liveRegion = LiveRegionMode.Assertive }`). Text (product.md may reword):
> "Trail complete. Gazzam Lake Main Trail. You've walked 88 percent of it. Island total now 12.4 percent. 9 of 114 trails complete. 23 other people have finished this trail. Continue button."
The glyph and water lines are `clearAndSetSemantics {}` (decorative). Animation never blocks interaction: Continue is enabled from t=0 (focus order: heading → stats → Continue). iOS: VoiceOver announces via the same semantics; additionally post `UIAccessibility.post(.screenChanged)` is handled by CMP when the modal appears.

Multiple completions in one walk: one page per trail, ordered by trail length desc; island % counter only animates on the first page; subsequent pages show the final island value.

### 3.7 Me (tab root)

```
┌────────────────────────────┐
│ Me                    ⚙    │  ← TopAppBar; gear → Settings
│ ┌────────────────────────┐ │
│ │ (avatar) Mossy Heron 42 › │  ← ProfileHeaderRow → Profile. Signed out: "Sign in to sync & join the board ›"
│ └────────────────────────┘ │
│ ┌────────────────────────┐ │
│ │  ≈≈ IslandChart ≈≈     │ │  ← hero card 280dp: Canvas drawing of the whole counted network
│ │   (tiny island of      │ │     (all included segments, projected), segments in `line` status
│ │    colored trails)     │ │     colors with the same patterns at small widths; 3 water-line
│ │        12.4%           │ │     offset rings around the network hull; statHero % overlaid
│ │  of the island's trails│ │     bottom-left, caption bodyMedium. Tap → Map tab island view.
│ └────────────────────────┘ │
│ ┌────────┐┌────────┐┌────────┐
│ │ 9/114  ││ 14.2 mi││ 23     │  ← StatTile row: Trails complete, Miles on trails, Walks
│ └────────┘└────────┘└────────┘
│ ┌────────────────────────┐ │
│ │ 🏆 Leaderboard  #37 of 412 › │  ← LeaderboardEntryCard (rank only if signed in & online/cached)
│ └────────────────────────┘ │
│ In progress (6)        All›│  ← SectionHeader; "All" → Trails tab with Partway+Nearly done filter
│ TrailRow × max 5, sorted by % desc (closest to done first)
│ Walk history               │
│ WalkRow: Oct 18 · 1:08 · 2.4 mi · 3 trails        │  ← tap → WalkDetail (summary = Results content, read-only)
│ … paginated 20 at a time                          │
└────────────────────────────┘
```
- **Decision: hero = IslandChart (the user's own network map in miniature) rather than a ring or silhouette fill.** It uses data already bundled (segment geometry + coverage), shows *where* progress is (a ring can't), and the water-line rings tie it to the Topo map's identity. Canvas-drawn once per CoverageState (cache to `ImageBitmap`), so it costs nothing per frame.
- IslandChart semantics: `contentDescription = "Island map of your trails: 12.4 percent walked, 9 of 114 trails complete"`.

States:
- New user (0 walks): hero shows all-dotted network + "0%" + caption "Your first walk will start coloring this in"; In progress → EmptyState compact "No trails started yet"; Walk history → "Walks you record show up here".
- Signed out: ProfileHeaderRow becomes sign-in prompt; Leaderboard card shows "See the board" (no rank).
- Offline: rank from cache with "as of {time}" or hidden if never fetched.
- Loading: hero skeleton (rounded rect) + tile skeletons.

### 3.8 Leaderboard (pushed; from Me card, Map 🏆, Trails 🏆)

```
┌────────────────────────────┐
│ ← Leaderboard              │
│ Ranked by island %, then trails completed   ← bodySmall onSurfaceVariant
│ Updated 3 min ago  ⟳       │  ← labelMedium; pull-to-refresh also
│ ┌────────────────────────┐ │
│ │ 1  Salal Otter 7  64.2% 81 │ ← LeaderRow: rank in titleLarge Fraunces;
│ │ 2  Pat J.         58.9% 77 │   top 3 rank numerals in `primary`, others `onSurfaceVariant`
│ │ 3  …                       │   (no medals, no status colors — those are reserved)
│ │ …                          │ │
│ └────────────────────────┘ │
│ ┌────────────────────────┐ │
│ │ 37  You (Mossy Heron 42) 12.4% 9 │ ← pinned YouRow (primaryContainer bg, 2dp primary left bar),
│ └────────────────────────┘ │   sticky at bottom when your row is off-screen; inline when visible
└────────────────────────────┘
```
- LeaderRow columns: rank (40dp, right-aligned, tnum), name (weight 1, ellipsis), % (`numeric`, 64dp right-aligned), trails completed (`numeric` + small `verified` icon 16dp, 48dp). Column headers as a sticky `labelMedium` row: "#", "Name", "Island", "Trails".
- Row overflow menu (long-press / ⋯ 48dp on others' rows): "Report name" → confirm dialog → toast "Thanks — we'll review it".
- Shows top 100 (server-defined). Ties share rank number.

States:
- Loading: 10 skeleton rows.
- Empty: EmptyState with water-lines glyph "No one's on the board yet" + (signed in) "Finish a walk to be first" / (signed out) Sign in button.
- Offline with cache: banner OfflineNote "Offline — board from {time}". Offline no cache: EmptyState "The leaderboard needs a connection" + Retry.
- Signed out: YouRow replaced by sticky `SignInPrompt` card "Sign in to appear on the board" [Sign in].
- Error: EmptyState error + Retry.

### 3.9 Profile / sign-in (pushed from Me)

**Signed out**
```
┌────────────────────────────┐
│ ← Account                  │
│  Sign in to sync your      │  ← headlineSmall Fraunces
│  progress and join the     │
│  leaderboard               │
│  Walking works without an account. ← bodyMedium
│ ┌────────────────────────┐ │
│ │ [G] Sign in with Google│ │  ← Android only
│ └────────────────────────┘ │
│ ┌────────────────────────┐ │
│ │  Sign in with Apple   │ │  ← iOS only
│ └────────────────────────┘ │
│ We only use your account to save progress. Your name is never shown unless you choose. ← bodySmall
└────────────────────────────┘
```
- **Android / Google:** follow Google Identity branding: light theme → white fill `#FFFFFF`, 1dp stroke `#747775`, text `#1F1F1F`; dark theme → fill `#131314`, stroke `#8E918F`, text `#E3E3E3`. Standard multicolor "G" logo (official asset, 20dp), label "Sign in with Google" (Roboto Medium 14sp), height 48dp, full width, pill or 4dp radius — **use pill** (Google allows "pill" shape). Triggers Credential Manager. Don't recolor the G.
- **iOS / Apple:** use the native `ASAuthorizationAppleIDButton` via `UIKitView` (type `.signIn`, style `.black` in light mode, `.white` in dark mode), height 50pt, corner radius 25 (pill, matching our pills). If interop is a problem, a custom button must follow HIG exactly (Apple logo glyph, "Sign in with Apple", system font, black/white, ≥44pt). Apple button must be at least as prominent as any other sign-in option (only option here).
- Loading: button shows inline progress, other actions disabled. Error: snackbar "Sign-in didn't work. Try again." Cancelled: no message.

**Signed in**
```
┌────────────────────────────┐
│ ← Account                  │
│ On the leaderboard as      │  ← labelLarge
│ ┌────────────────────────┐ │
│ │ ( ) My name   (•) Anonymous │ ← SegmentedButton (single choice), 48dp
│ └────────────────────────┘ │
│ Anonymous name: Mossy Heron 42   ← bodyLarge; read-only (assigned server-side)
│ ─ or when "My name": ─       │
│ ┌ Display name ──────────┐ │  ← OutlinedTextField, 3–24 chars, counter, Save button
│ │ Pat J.                 │ │     Error states: "That name isn't allowed", "Name is taken" (if server enforces)
│ └────────────────────────┘ │
│ Preview: [LeaderRow sample] │  ← live preview of how your row looks on the board
│ Sync: Last synced 2 min ago ⟳ ← ListItem; offline: "Waiting for connection"
│ Signed in with Google      │  ← ListItem, bodyMedium
│ [ Sign out ]               │  ← OutlinedButton
│                            │
│ Danger zone                │  ← SectionHeader in `error`
│ [ Delete account ]         │  ← TextButton, error color, 48dp
└────────────────────────────┘
```
- Toggle saves immediately (optimistic, revert + snackbar on failure). Name field saves on "Save".
- Delete account → AlertDialog: title "Delete your account?", body "This removes your progress from the server and the leaderboard. This can't be undone.", Checkbox "Also delete walks and progress on this phone" (default **unchecked**), buttons [Cancel] [Delete] (error color). Then progress dialog → back to signed-out Profile + snackbar "Account deleted". Offline: Delete disabled with helper text "Connect to the internet to delete your account".
- Sign out dialog: "Your walks stay on this phone." [Cancel] [Sign out].

### 3.10 Settings / About (pushed from Me ⚙)

```
┌────────────────────────────┐
│ ← Settings                 │
│ Map                        │  ← SectionHeader
│ Map style                  │
│ (•) Auto  — Topo by day, Dark at night   ← RadioRow with 56×40 style swatch (Canvas: land/water/
│ ( ) Topo                   │               trail colors from styles.md for that style)
│ ( ) Light — focus on your progress
│ ( ) Dark                   │
│ Units                      │
│ [ Miles | Kilometers ]     │  ← SegmentedButton; default from locale (US → miles)
│ About                      │
│ Privacy policy           ↗ │  ← placeholder URL
│ Map data & licenses      › │  ← Attribution screen: renders data/dist/ATTRIBUTION.md + OFL (Fraunces)
│ Version 0.1.0 (42)         │  ← bodySmall; 7 taps = nothing in release (debug drawer is separate)
└────────────────────────────┘
```
- "Auto" (default) = Topo when app is light, Dark when system dark. Light/Dark app theme always follows system (**Decision:** no in-app theme override in MVP). If user picks Dark map while system light, chrome stays light — allowed.
- Attribution screen: plain scrollable text (bodyMedium), headings titleSmall, links tappable.
- Debug builds only: "Debug drawer" row at the bottom (opens drawer).

---

## 4. Component catalog

All in `ui/components/`. Every component takes `modifier: Modifier = Modifier` last-but-content. Status glyph shapes come from one source: `StatusGlyph`.

| Component | Params | Behavior / spec |
|---|---|---|
| `StatusGlyph` | `status: TrailStatus, size: Dp = 16.dp` | Canvas circle icon mirroring map patterns: NOT_STARTED = ring of 8 dots; PARTWAY = dashed ring (4 dashes) with left half filled; NEARLY_DONE = 3/4 filled pie with 1dp `nearlyDoneCasing` outline; COMPLETE = filled disc with check cut-out. Color `accent`. Decorative (status text always adjacent). |
| `StatusChip` | `status, compact: Boolean = false` | Pill, height 28dp (min touch not required: non-interactive), padding 8/10dp, `container` bg, StatusGlyph 14dp + label `labelMedium` in `onContainer`. Labels: "Not started", "Partway", "Nearly done", "Complete" (product.md may rename; keep these 4 keys). `compact` = glyph only + contentDescription label. |
| `ProgressRing` | `fraction: Float, status: TrailStatus, size: Dp = 40.dp, stroke: Dp = size/10, gained: Float? = null, showLabel: Boolean = true` | Track = `outlineVariant`, **dotted** (dash 0, gap 2×stroke, round cap) — so a 0% ring reads as "not started" by shape. Arc from 12 o'clock clockwise, round caps, `accent`, always solid (pattern differences live in the dotted track + adjacent glyph/label, to avoid noise at 32–48dp). COMPLETE: arc full width + small check at center instead of %. `gained`: second segment drawn at full alpha, base at 40%. Label: % in `labelMedium` tnum, integer ("42%"; "<1%" for 0<f<0.005). Animates fill on first composition (§1.7). Semantics: `progressBarRangeInfo` + "42 percent, Partway". |
| `ProgressBar` | `fraction, status, thresholds: List<Float> = listOf(.75f,.85f), height: Dp = 8.dp` | Rounded track `surfaceContainerHighest`, fill `accent`; threshold ticks in `outline`. |
| `TrailRow` | `name, subtitle (park · length), fraction, status, onClick, trailing: (@Composable)? = null` | 72dp min (grows with font scale), ListItem layout: leading ProgressRing 40dp, headline titleMedium (2 lines max), supporting bodyMedium, trailing StatusChip(compact when width < 360dp). Whole row clickable, ripple. Merged semantics: "Gazzam Lake Main Trail, Gazzam Lake Preserve, 1.4 miles, 42 percent, Partway". |
| `LiveTrailRow` | `name, before, after, status` | 56dp; ProgressRing 32dp with `gained`; "12% → 31%". |
| `ResultTrailRow` | `name, before, after, beforeStatus, afterStatus` | Shows StatusChip only if status changed; arrow `→` in onSurfaceVariant. |
| `StatTile` | `value: String, label: String, icon: ImageVector? = null, emphasis: Boolean = false` | surfaceContainerLow, medium shape, padding 16, value `statLarge`, label `bodySmall` onSurfaceVariant. Min 96dp height. Merged semantics "value label". |
| `SectionHeader` | `title, action: Pair<String, () -> Unit>? = null, color = onSurface` | `titleMedium`, 48dp tall, `heading()` semantics; optional trailing TextButton. |
| `EmptyState` | `title, body: String? = null, kind: Empty / Error / Offline, action: Pair<String,()->Unit>? = null, compact = false` | Water-lines glyph 96dp (Canvas: 3 concentric rounded rects, `waterLine`) for Empty; `cloud_off` for Offline; `error` icon for Error. title titleMedium, body bodyMedium, centered, max width 320dp. |
| `OfflineNote` | `text` | Inline row: `cloud_off` 18dp + bodySmall, onSurfaceVariant, surfaceContainerHigh pill. |
| `SignInPrompt` | `text, onSignIn, onDismiss: (() -> Unit)? = null` | Card secondaryContainer, body + TextButton "Sign in". |
| `RecordingPill` | `elapsedMs, distanceM, onClick` | Pill 48dp tall, surfaceContainerHigh + 6dp shadow, leading 10dp `recording` dot (pulse unless reduced motion), "Recording · 1:08:22 · 2.4 mi" `labelLarge` tnum. Semantics: "Recording, 1 hour 8 minutes, 2.4 miles. Open recording." Updates semantics at most every 60 s (no TalkBack spam). |
| `RecordDisc` | `recording: Boolean, onClick` | 64dp circle, primary; recording → 3dp `recording` ring + dot. contentDescription "Record a walk" / "Recording in progress, open". |
| `StateChip` | `text, icon, onClick: (() -> Unit)? = null` | Neutral pill (surfaceContainerHigh/onSurface), for GPS / Unverified. Never status-colored. |
| `MapIconButton` | `icon, contentDescription, onClick, active: Boolean = false` | 48dp circle, `mapChrome` + 3dp shadow, icon `onMapChrome` (active → primary). |
| `LocateButton` | `mode: Idle/Following/Unavailable` | MapIconButton variants (`my_location` outline / filled primary / `location_disabled`). |
| `StatusLegend` | `style: MapStyle, expanded: Boolean, onToggle` | See §3.2. Swatches draw map patterns from the **active style's** `line` colors (topo/light/dark table in styles.md). |
| `TrailPeekSheet` | `trail, progress, onStart, onDetails, onDismiss` | §3.2. |
| `TrailChooserSheet` | `trails: List<TrailSummary>, onPick` | §3.2. |
| `IslandChart` | `segments, coverage, style` | §3.7. |
| `CommunityCard` | `completedCount: Int?, medianFraction: Float?, state: Loaded/Offline/Few/Loading` | §3.4. |
| `LeaderRow` / `YouRow` | `rank, name, fraction, trailsCompleted, isYou` | §3.8. |
| `SkeletonBlock` | `width, height, shape` | surfaceContainerHigh; shimmer unless reduced motion. |
| `DatasetLoading` | — | §3 common. |

---

## 5. Accessibility

### 5.1 Status never by color alone
Status is carried by **four** channels everywhere: (1) color, (2) shape/pattern (map line pattern; StatusGlyph; dotted ring track), (3) text label (StatusChip / semantics), (4) number (%). On the map, the pattern + width hierarchy from styles.md is the non-color channel (dotted < dashed < solid+casing < thick solid). Any new surface showing status must include at least glyph + label.

Color-blind notes (styles.md CVD check): Complete stays distinct (violet → vivid blue under deutan/protan). Nearly done vs Partway converge to olive/gold under deutan/protan; they are separated by pattern (solid+casing vs dashed), lightness, glyph (¾ pie vs ½ pie) and label. Not started = neutral gray dots. Tritan not formally checked: violet/orange stay separable by lightness; patterns cover the rest. **Decision:** keep styles.md partway `#C98A00` on the map (owner-approved); UI uses the darker `#A87400` accent for 3:1 on paper.

### 5.2 Touch targets
≥ 48dp for every interactive element (Compose `minimumInteractiveComponentSize()` on all custom clickables). Map trail taps use a 44dp-diameter hit area (§6.1). Spacing ≥ 8dp between adjacent targets.

### 5.3 Dynamic type
- All text in `sp`; layouts tested at font scale 2.0 (Android) / AX3 (iOS). Rows grow vertically; no fixed heights on text containers (use `heightIn(min = …)`).
- At font scale ≥ 1.5: StatTile row of 3 → 2-column grid / stacked; TrailRow trailing chip moves below supporting text; Celebration statHero caps at 1.3× (`fontSize = min(scaled, 83.sp)` via `LocalDensity.fontScale`) — never truncate numbers.
- Map labels are not app text (MapLibre); unaffected.

### 5.4 Contrast
WCAG AA: text ≥ 4.5:1 (≥ 3:1 for ≥ 18.5sp bold / 24sp), non-text UI ≥ 3:1. Token pairs in §1.1/§1.2 meet this (measured). Text over map always sits on a `mapChrome` surface (≥ 95% opaque), never directly on tiles.

### 5.5 Screen readers
- Map: the MapLibre view is one node: contentDescription "Trail map. {n} of 114 trails complete." Map interactions are mirrored by accessible paths: Trails list (all trails), Locate button, TrailDetail. Add a custom accessibility action on the map node "Show trails list" → Trails tab.
- Headings: screen titles + SectionHeaders marked `heading()`.
- Live regions: Trails filter summary (polite), Results island delta (polite), Celebration (assertive, once).
- Rows merge descendants; decorative Canvas art uses `clearAndSetSemantics {}`.
- Recording: timer node not live (would spam); RecordingPill summary updated per minute.

---

## 6. Map interaction details

### 6.1 Hit-testing
- On tap, `queryRenderedFeatures` in a **44dp square centered on the tap** (22dp radius tolerance), layers: the counted-status trail layers only (not-counted hairlines, roads, labels excluded).
- Map hit segment ids → trails via the dataset index (`segment_ids` → trail ids; same relation `Progress.trailsOn` uses). Collect distinct **named** trails across all hit segments.
- Rank candidates by screen distance from tap to nearest point of each trail's rendered geometry; ties by name.
- 0 trails → dismiss sheet / clear selection. 1 → select + TrailPeekSheet. ≥2 → if nearest is ≥ 12dp closer than the next, select it; else TrailChooserSheet.
- During Recording, trail taps are disabled (avoid pocket taps while map follows); the live list is the interface.

### 6.2 Selected-trail highlight
- Selected trail's segments are drawn in an overlay layer (GeoJSON source of the trail's segment lines) **above** status layers: (1) outer casing `selectionCasing` width = status width + 6px at z15 (scale with zoom like styles.md widths), opacity .9; (2) inner `surfaceBright`/`#0B1410` (dark) gap line + 2px; (3) the status line re-drawn on top with the **trail-level** status color & pattern.
- All other counted trails dim to 45% opacity (paint-opacity transition 200ms; instant under reduced motion).
- Selection persists through pans/zooms; cleared on sheet dismiss.

### 6.3 Camera
- Island home bounds: SW (47.575, −122.600) NE (47.725, −122.480); initial camera fits these, z ≈ 11.5–12.
- Fit trail: `bounds(trail segments)` with padding top = status-bar inset + 72dp (search chip row), bottom = sheet peek height + 24dp (+ RecordingPill if shown), left/right = 48dp; clamp zoom to [12, 16.5]; animate long1 emphasized (jump under reduced motion). Bearing 0, tilt 0 always (no rotation gestures enabled in MVP — **Decision:** disable rotate & tilt; north-up chart convention, simpler legibility).
- Trailhead focus: center on point, z16.
- Min zoom 10.5, max 18; constrain panning to island bounds expanded by 25%.

### 6.4 Status on the map: per segment
**Decision: each segment is colored by its own covered fraction** (from its tick bitset), mapped through the same thresholds as trails (0 → not_started; >0 and <0.75 → partway; ≥0.75 → nearly_done; ≥0.85 → complete; values from `CoverageParams`). The **selected trail** (§6.2) and every list/chip/ring use **trail-level** status.

Why not "best status of trails containing it": shared segments would paint unwalked ground violet whenever any overlapping trail is complete — the map would lie about where you've been. Why not "trail status for all its segments": a trail at 86% (Complete) would hide its remaining 14%, and on overlaps the segment would need an arbitrary winner. Per-segment coverage is (a) truthful, (b) unambiguous on overlaps, (c) exactly the data `:coverage` already stores, and (d) answers the user's real map question — *"where haven't I walked yet?"* — so the gaps you need to finish a trail show as dots/dashes. Trail-level status remains one tap away. The legend footnote states "Colors show how much of each stretch you've walked."

Implementation: replace the fake `STATUS` expression (styles.md) with a data-driven `match` on segment `id` (four id lists built from CoverageState, rebuilt per finished walk / per live tick at most every 5 s while recording), or a client GeoJSON overlay — engineers' choice; `match` keeps all styles' existing pattern layers intact. Segments with `included == false` keep the not-counted styling regardless of coverage.

### 6.5 Dark style
Map style "Auto" (default) = Topo in light system theme, Dark in dark system theme, switching live (verified runtime restyle keeps camera, styles.md). Status bar icon appearance follows the **map** style on full-bleed map screens, and the app theme elsewhere.

### 6.6 Attribution & logo
MapLibre logo + attribution must remain. Bottom-left, above bottom bar/sheets, 8dp margin: show full text "© OpenStreetMap contributors, Protomaps, USGS" for 5 s after first map render each session, then collapse to an `ⓘ` 48dp button (mapChrome) that re-expands on tap. Full licenses in Settings → Map data & licenses. Mini maps (TrailDetail) show the `ⓘ` only.

---

## 7. Platform adaptations

**Android**
- `enableEdgeToEdge()` in `MainActivity`; transparent status + navigation bars; content uses `WindowInsets.safeDrawing`; map is full-bleed under both bars. Bottom bar pads for `navigationBars` inset; gesture nav → bar draws behind the handle; 3-button nav → bar background extends under buttons (no contrast scrim: `isNavigationBarContrastEnforced = false`, since the bar is opaque).
- Status-bar icon appearance: `WindowCompat.getInsetsController(...).isAppearanceLightStatusBars` = !isDark of the current map style on Map/Record/TrailDetail-header, else !isSystemInDarkTheme.
- Predictive back enabled (§2.3). Keyboard: `imePadding()` on Profile name field and Trails search; `adjustResize` behavior via edge-to-edge insets.
- Recording notification: small icon monochrome footprint; tap → `bt://record`; "Stop" action in notification opens Finish dialog (not silent stop).
- Haptics: `HapticFeedbackType.Confirm` (API 30+) fallback `LongPress`.

**iOS**
- Respect safe areas via `WindowInsets.safeDrawing` (CMP maps them); map full-bleed under status bar and home indicator; bottom bar sits above home indicator with its background extended underneath.
- Status bar style follows map style (light content on Dark map) — set via `UIViewController.preferredStatusBarStyle` hook in the host controller.
- Left-edge swipe back on pushed screens (§2.3); avoid horizontal gestures within 20pt of left edge on pushed screens.
- Sign in with Apple native button (§3.9). Dynamic Type: CMP honors `fontScale` from content size category — verify AX sizes.
- Haptics: `UINotificationFeedbackGenerator().notificationOccurred(.success)` on completion; `UIImpactFeedbackGenerator(.light)` for live status crossings.
- No iOS-style large-title collapsing in MVP; same TopAppBar layout on both platforms (Fraunces titles left-aligned) — **Decision:** one cross-platform look; platform conventions only for back gesture, sign-in buttons, insets, haptics, and system dialogs/permission sheets.

**Both**
- Portrait-first; landscape supported without layout breakage (content max width 600dp, map full).
- Minimum supported width 320dp (iPhone SE): chips compact, StatTile row wraps.
