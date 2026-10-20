# Bainbridge Roads — UX spec (visual + interaction adaptations)

Date: 2026-10-20 · Owner: Mobile UX designer · Status: decided (decisions inline as **Decision:**).
Precedence (decisions.md): `docs/roads/decisions.md` > **this file** > `docs/roads/product.md` > `docs/roads/plan.md`.
Base spec: `docs/mvp/ux.md` (tokens, components, nav, a11y). **Everything not overridden here is unchanged.** Words
are placeholders unless product.md agrees — product.md wins on copy and on *which* milestones exist; this file wins on
layout, color, motion, map encoding and interaction.

Scope rule for engineers: one code path. Every difference below is a value read from a profile object keyed by
`DatasetProfile` (RD-1 flavour fixes it), never an `if (roads)` inside a composable's drawing code. Three profile
objects carry it all:

| Object | Lives in | Holds |
|---|---|---|
| `BtBrand` | `ui/theme/BtBrand.kt` (new) | primary/secondary roles, launcher-facing names, empty-state motif |
| `StatusLayerSpec` | `map/MapPalette.kt` (replaces `TrailLineSpec` object with two instances) | widths, dashes, halo, zoom gates, hit layers (§2) |
| `ChartSpec` | `ui/components/IslandChart.kt` | Me hero stroke widths, simplification, uncounted drawing (§5) |

`GoalVocabulary` (RD-4, product.md) carries the words; this file never hard-codes a count.

---

## 0. Design direction: "Survey sheet"

The trail app is a *chart room*: spruce ink on chart paper, water lines, Fraunces. Roads is the same atlas's
**survey sheet** — the municipal road plat that sits next to the nautical chart. Same paper, same type, same status
ramp, same water lines; the ink changes from spruce to **survey slate**, and the secondary motif is the **road grid**
(a plat's crossing right-of-way lines) instead of a lone winding path.

What is identical (do not fork): paper surfaces, all `surface*`/`outline*`/`error` roles, Fraunces + system type
scale, shapes, spacing, elevation, motion tokens, **all four status colors in all four roles**, status patterns,
recording red, user-track blue, selection casing, waterLine.

What differs: primary family, secondary family (swapped), launcher icon + label, empty-state glyph, map layer spec,
chart spec, a few road-only components (§3, §5, §6).

---

## 1. Roads identity within the shared theme

### 1.1 Brand roles

**Decision:** roads primary = **survey slate** `#2F5068`. It is far from every status hue (gray/ochre/tangerine/violet),
far enough from user-track blue `#0B63CE` (desaturated, darker), and reads as "civic/municipal" without going
traffic-sign green or yellow (sign yellow would collide with Partway ochre; sign green is the trail app's spruce).
Secondary is **swapped** to spruce so the trails brand is still present as the minor note; teal secondary is not
used in roads (it sits 1.5:1 from slate and would muddy it).

```kotlin
// ui/theme/BtBrand.kt
@Immutable data class BrandRoles(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val positive: Color, // BtExtraColors.positive
)

enum class BtBrand(val light: BrandRoles, val dark: BrandRoles, val emptyMotif: EmptyMotif) {
    TRAILS( // exactly docs/mvp/ux.md §1.1 values — no change for the trail app
        light = BrandRoles(Color(0xFF2E5E4E), Color.White, Color(0xFFCFE6DA), Color(0xFF0E2A20), Color(0xFF93D3B6),
                           Color(0xFF3D6E78), Color.White, Color(0xFFD3E7EB), Color(0xFF0F2E35), Color(0xFF2E5E4E)),
        dark  = BrandRoles(Color(0xFF93D3B6), Color(0xFF003827), Color(0xFF1A4A3B), Color(0xFFB4EFD3), Color(0xFF2E5E4E),
                           Color(0xFFA3CED7), Color(0xFF01363F), Color(0xFF224D56), Color(0xFFC6EAF2), Color(0xFF93D3B6)),
        emptyMotif = EmptyMotif.WaterLines,
    ),
    ROADS(
        light = BrandRoles(Color(0xFF2F5068), Color.White, Color(0xFFD3E2EE), Color(0xFF0B2235), Color(0xFFA8C8E0),
                           Color(0xFF2E5E4E), Color.White, Color(0xFFCFE6DA), Color(0xFF0E2A20), Color(0xFF2F5068)),
        dark  = BrandRoles(Color(0xFFA8C8E0), Color(0xFF00334D), Color(0xFF1E3A50), Color(0xFFD3E8F8), Color(0xFF2F5068),
                           Color(0xFF93D3B6), Color(0xFF003827), Color(0xFF1A4A3B), Color(0xFFB4EFD3), Color(0xFFA8C8E0)),
        emptyMotif = EmptyMotif.RoadGrid,
    );
    companion object { fun of(p: DatasetProfile) = when (p) { DatasetProfile.TRAILS -> TRAILS; DatasetProfile.ROADS -> ROADS } }
}
```

`BtTheme(brand: BtBrand = BtBrand.of(DatasetProfile.current), …)` copies the base scheme with these roles
(`BtLightScheme.copy(primary = …, surfaceTint = primary, …)`) and sets `BtExtraColors.positive`.

Contrast (measured, WCAG 2.1): white on `#2F5068` **8.5:1**; `#2F5068` on paper `#F7F5EF` 7.8:1, on
`surfaceContainer` 7.1:1; `#0B2235` on `#D3E2EE` 12.3:1; dark `#00334D` on `#A8C8E0` 7.6:1; `#A8C8E0` on `#101A16`
10.2:1; `#D3E8F8` on `#1E3A50` 9.4:1. All meet the §1.1 bars of the base spec.

### 1.2 Launcher icon and label

Adaptive icon in `androidApp/src/roads/res/` (flavour source set), 108dp canvas, 66dp safe zone:

- **Background:** solid survey slate `#2F5068`.
- **Foreground:** the island silhouette (same coastline as `coastline.json`, simplified to ≤ 60 points) filled chart
  paper `#F7F5EF`, ringed by two water lines (`#A8C8E0`, 1.5dp, offsets 3dp/6dp at icon scale). Inside the island,
  **three road strokes in slate**: SR 305 as a 4dp diagonal NW→SE, two 2.5dp cross streets — and one of the cross
  streets carries a paper-colored dashed centreline (2dp dash / 2dp gap). No status colors in the icon.
- **Monochrome (themed icons, API 33+):** island silhouette with the three roads cut out.
- Trail flavour sibling (optional, same template, owner's call): spruce background, single dotted winding trail —
  so the pair reads as one family on the home screen.

Labels: release `app_name` = **"Bainbridge Roads"**; debug (`.dev`) = **"Roads dev"** (short so it never truncates
next to "Trails dev" on the owner's Pixel). Notification small icon: the trail app's monochrome footprint stays (the
activity is walking in both).

### 1.3 Navigation chrome

- Bottom bar tab 2: label from `GoalVocabulary.tabName` ("Roads"), icon **Material Symbols `road`** (outline /
  filled for selected), replacing `Hiking`/`HikingFilled`. Add `ic_road.xml`, `ic_road_filled.xml` to
  composeResources; `BtIcons.Things` / `ThingsFilled` resolve per profile.
- Record disc: unchanged (`DirectionsWalk`). RD-3: on foot only.
- Map search chip: copy from vocabulary ("Find a road").

---

## 2. Map — island and street zoom

### 2.1 Problem and principle

At island zoom (z ≈ 11.5) the trail encoding paints ~2000 counted segments as dots edge to edge
(`screens/map-island.png`): a lace in which progress is invisible. **Decision:** on roads, *the category bed is the
"not started" encoding below z15*. Unwalked road = plain civic road line, weighted by class. Status is drawn only
where it is information: **progress** at every zoom, **not-started dots** only from z15 (street zoom), where "which
block haven't I walked" is the actual question and dots are legible.

Layer stack, bottom → top (style layers in *italics*, app layers from `StatusLayers` in `code`):

1. *basemap roads* (white, unchanged)
2. *`roads-ds-private`*, *`roads-ds-holdout`* — uncounted (style, §2.4)
3. *`roads-ds-bed`* — counted, category-colored (style, §2.3)
4. `app-trail-halo-{partway,nearly_done,complete}` — land-colored gap under **progress only**
5. `app-highlight-*` (walk detail), `app-trail-casing-*`, `app-trail-glow` (Dark)
6. `app-trail-not_started` (minZoom 15), `app-trail-partway`, `app-trail-nearly_done`, `app-trail-complete`
7. `app-road-ends` (circle layer, §2.6)
8. `app-selected-*` (selection, §2.5)
9. *labels*

### 2.2 `StatusLayerSpec` — the parameter set (one code path)

Replace the `TrailLineSpec` object with a data class and two instances. `StatusLayers(…)` gains
`spec: StatusLayerSpec = StatusLayerSpec.of(DatasetProfile.current)`; every hard-coded `TrailLineSpec.*` reference
reads `spec.*`. The legend (`StatusLegend`) and `TappableTrailLayerIds` read the same spec.

```kotlin
data class StatusLayerSpec(
    val widths: Map<TrailStatus, List<Pair<Int, Float>>>,   // zoom → dp
    val dashes: Map<TrailStatus, List<Float>?>,             // × width; null = solid
    val notStartedMinZoom: Float,                           // app not-started layer minZoom
    val notStartedOpacity: Float,
    val haloStatuses: Set<TrailStatus>,
    val haloExtra: Float,
    val casingExtra: Float,
    val dimOpacity: Float,
    val drawUnnamedHairlines: Boolean,                      // app-nc-hairline/closed/easement
    val drawNamedNotCounted: Boolean,                       // TL-6 layers
    val extraTappableLayerIds: Set<String>,                 // style layers queried on tap
    val fitZoomRange: ClosedFloatingPointRange<Float>,      // camera fit clamp (§2.5)
)
```

| Param | `TRAILS` (= today, unchanged) | `ROADS` |
|---|---|---|
| widths COMPLETE z11/13/15/17 | 1.8 / 3.2 / 5.6 / 9.0 | **2.6 / 3.4 / 4.4 / 6.0** |
| widths NEARLY_DONE | 1.3 / 2.3 / 3.8 / 6.2 | **2.0 / 2.8 / 3.6 / 5.0** |
| widths PARTWAY | 1.2 / 2.0 / 3.2 / 5.0 | **1.8 / 2.4 / 3.0 / 4.2** |
| widths NOT_STARTED | 1.1 / 1.7 / 2.6 / 4.0 | **— / — / 2.0 / 3.0** (stops 15, 17 only) |
| dashes | NS [0.01,1.9]; PW [2.6,1.4] | NS **[0.01, 2.2]**; PW [2.6, 1.4] |
| notStartedMinZoom | 0 | **15** |
| notStartedOpacity | 1.0 | **0.8** |
| haloStatuses / haloExtra | all four / 3.0 | **PARTWAY, NEARLY_DONE, COMPLETE / 2.0** |
| casingExtra | 2.2 | 2.0 |
| dimOpacity | 0.45 | 0.45 |
| drawUnnamedHairlines | true | **false** (style draws uncounted roads) |
| drawNamedNotCounted | true | **false** (uncounted roads never show progress) |
| extraTappableLayerIds | ∅ | **{"roads-ds-bed"}** (§2.5) |
| fitZoomRange | 12 .. 16.5 | **13 .. 17** |

Why these widths: at island zoom progress must out-weigh the bed (residential bed ≈ 1.6dp at z11 after §2.3, so a
2.6dp complete line + 2dp halo reads as a clean violet stroke); at street zoom progress sits **inside** the bed like a
painted centreline (residential bed 5.5dp at z15 vs complete 4.4 + halo → the halo replaces the bed on minor
roads; on arterials/SR the steel bed shows on both sides). Progress on a 2.5×-larger goal is scarce; these widths
make every walked block findable at island zoom.

Implementation notes:
- Not started below z15 has **no app layer**: `present(NOT_STARTED)` stays true but the layer gets
  `minZoom = spec.notStartedMinZoom`. Width expression for a status whose stops start at 15 is a normal
  `interpolate` over the listed stops.
- Halo layer: emit only for `s in spec.haloStatuses`.
- Glow (Dark complete): unchanged; width `extra = 3f` for roads (was 4).
- **MapLibre gotcha** (plan.md #6): any category-scaled or zoom-dependent value must keep `interpolate(zoom)`
  outermost and put `match(category)` *inside each stop*. Never `["*", interpolate, …]` — the native renderer
  silently drops the layer. Applies to §2.3 opacity too.

### 2.3 Category bed (style, `scripts/build-map-styles.py`)

Colors unchanged (steel family per style, "never reads as the status ramp"). Two changes so island zoom calms down:

| | z10 | z12 | z13 | z15 | z18 |
|---|---|---|---|---|---|
| bed width, residential base (dp) | 0.6 | 1.2 | 2.0 | 5.5 | 11.0 |
| × class factor (unchanged) | SR 2.1 · arterial 1.7 · collector 1.35 · residential 1.0 | | | | |
| bed opacity residential | 0.55 | 0.7 | 0.85 | 1.0 | 1.0 |
| bed opacity collector | 0.75 | 0.85 | 0.95 | 1.0 | 1.0 |
| bed opacity arterial / SR | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 |

Both via `road_bed_width` (already per-stop) and a new `road_bed_opacity(stops)` built the same way
(`["interpolate",["linear"],["zoom"], z, ["match",["get","category"], …, default]]`).

Result at island zoom: SR 305 and arterials read as the island's skeleton, collectors as the secondary web, residential
as faint fill; progress strokes sit on top in full status color.

### 2.4 Counted vs private vs unknown ownership

| Kind | Encoding | Layer | minzoom (**new**) | width z10/14/18 | opacity |
|---|---|---|---|---|---|
| Counted, not started | category bed (+ gray dots z≥15) | `roads-ds-bed` (+ `app-trail-not_started`) | 10 | §2.3 | §2.3 |
| Counted, progress | status color/pattern over halo | `app-trail-*` | — | §2.2 | 1.0 |
| Private (`ownership=private`) | **muted dash** `[2.2, 1.8]`, `rd.private` | `roads-ds-private` | **12** | 0.6 / 1.6 / 3.4 | 0.85 |
| Unknown / lane / driveway (holdouts) | **dotted** `[0.01, 2.4]`, `rd.holdout` | `roads-ds-holdout` | **13** | 0.5 / 1.3 / 2.6 | 0.64 |

Rules:
- Uncounted roads never carry status (even if walked), are never tappable on the map, never dim/undim on selection.
- Hiding them below z12/z13 removes ~70 mi of extra line from the island view; they reappear at neighbourhood zoom
  where "why isn't this road in my goal" can come up.
- Holdout dots vs not-started dots (both z≥15): not-started dots always sit on a steel bed, are darker
  (`notStarted.line`) and wider; holdouts are bed-less, warmer and fainter. The legend shows both.

### 2.5 Selection, hit-testing, camera

- **Hit-testing:** query `spec.statusLayerIds + spec.extraTappableLayerIds` in the 44dp square (base §6.1). For
  roads the style's `roads-ds-bed` layer is the hit target for unwalked roads below z15 (there is no app layer there).
  Segment id → `road_ids` → road, same `Progress.trailsOn` relation. Ranking/chooser unchanged; chooser header uses
  vocabulary ("3 roads here"). Intersections are the common overlap case: the 12dp-closer rule picks the road under
  the finger; otherwise the chooser.
- **Selected road halo:** `SelectionLayers` unchanged in structure — outline `selection` (+6dp, 0.9), gap
  `selectionGap` (+2dp), road-level status line on top. For roads the outline is drawn at the **road-level status
  width, minimum NEARLY_DONE widths** (so a not-started road, which has no status line below z15, still gets a
  visible 2.8+6dp outline at z13). The road-level line drawn on top for NOT_STARTED below z15 = solid
  `notStarted.line` (not dots), z-scaled with NEARLY_DONE widths. Other app layers dim to 0.45; the style bed does
  not dim (it's already pale; the dark outline carries the selection).
- **Camera fit:** padding as base §6.3, zoom clamp `spec.fitZoomRange` = 13..17 (most roads are < 1 km; the trail clamp
  would put a 300 m cul-de-sac at z16.5 with no context — 17 cap, 13 floor keeps SR 305 whole-ish).
- **Live recording:** taps disabled (unchanged).

### 2.6 Road-end pins

`road_ends.json` (21 points) drawn by the app as a `CircleLayer` over a GeoJSON source, above status lines:
- minZoom 12; radius 4dp @z12 → 7dp @z16; fill `tertiary` (contour brown, the trailhead-pin role in the trail app),
  stroke 2dp `surfaceBright` (`#0B1410` on Dark). From z15 a `SymbolLayer` label "Road end" (labelSmall size, halo
  = label halo) when glyphs are available.
- Tap (44dp, wins over lines when within 16dp): selects the parent road and opens the peek sheet with the road-end
  row expanded (§3.3).

### 2.7 Legend (roads variant)

`StatusLegend(style, spec, expanded, onToggle)`; swatches 32×8dp drawn with the active style's colors:
1. Complete · 2. Nearly done · 3. Partway (unchanged patterns, roads widths at z15)
4. **Not walked yet** — a 32×8 steel residential bed swatch with gray dots on it
5. **Road type** — a mini 4-step ramp (SR / arterial / collector / residential beds, 6dp each, labelled
   "Main road → Side street")
6. **Private — not counted** (muted dash) · 7. **Ownership unconfirmed — not counted** (dotted)
8. Road end pin
Footnote: "Colors show how much of each stretch you've walked. Plain road lines are roads you haven't walked yet."
Legend expanded on launches 1–3 (unchanged rule); it is taller (8 rows), so it scrolls inside max 60% height.

### 2.8 Map summary

| Zoom | What you see |
|---|---|
| ≤ 11.9 | steel skeleton (SR/arterials strong, residential faint), progress as bold status strokes, no uncounted, no dots |
| 12–12.9 | + private dashes, + road-end pins |
| 13–14.9 | + holdout dots; bed fully opaque on collectors |
| ≥ 15 | + gray not-started dots on beds; progress reads as painted centreline inside the bed |

---

## 3. Road detail and peek sheet

### 3.1 Wireframe (pushed, replaces TrailDetail content when `spec` is roads; same screen file, data-driven blocks)

```
┌────────────────────────────┐
│ ←                     ⋯    │  transparent bar, mapChrome circles (unchanged)
│ ┌────────────────────────┐ │
│ │  mini map 200dp         │ │  fitted to road (clamp 13..17), selection style,
│ │  road-end pins shown    │ │  tap → Map tab focus. 200dp (not 240): roads are short
│ └────────────────────────┘ │
│ Agate Pass Road Northeast  │  headlineMedium Fraunces, full name, 3 lines max
│ Residential · City road    │  bodyMedium onSurfaceVariant: category label · ownership label
│ [✓ Counts toward the island]│  OwnershipBadge (§3.2)
│                            │
│ [◌ Not started]       0%   │  StatusChip + statLarge (unchanged)
│ ▬▬▬▬░░░░░░░░░░░░░░|░|░     │  ProgressBar w/ 75 / 85 ticks (unchanged)
│ 0.0 of 0.7 mi walked       │  bodyMedium
│                            │
│ ┌──────────┐ ┌──────────┐  │  StatTile grid, 2 columns (§3.2 rules)
│ │ 0.7 mi   │ │ 10 m     │  │
│ │ Length   │ │ Climb    │  │
│ ├──────────┤ ├──────────┤  │
│ │ (25) mph │ │ Paved    │  │  speed tile shows SpeedSignGlyph; surface tile
│ │ Speed lim│ │ Surface  │  │  — each only if the field is non-null
│ └──────────┘ └──────────┘  │
│ ┌────────────────────────┐ │
│ │ City of Bainbridge Isl.│ │  full-width StatTile(compactValue = true)
│ │ Maintained by          │ │
│ └────────────────────────┘ │
│ ⚠ 35 mph, no sidewalk data │  CautionRow (§3.2), only when rule fires
│                            │
│ Elevation                  │  SectionHeader
│ "Gentle — 0 m up, 10 m down over 0.7 mi"                    (unchanged tiers)
│                            │
│ Road ends to the water (1) │  SectionHeader, only if ≥ 1
│ ≈ Road end · 28 m to shore  [↗]  RoadEndRow 56dp: tap row → map, trailing
│   Listed by the City             directions IconButton 48dp
│                            │
│ Community                  │  CommunityCard (unchanged)
│ (spacer 88dp)              │
├────────────────────────────┤
│ [        Start walk       ]│  sticky (unchanged)
└────────────────────────────┘
```

Removed vs trails: trailheads section, park line, official-map link (no per-road URL).

### 3.2 Components and rules

**Labels** (strings via vocabulary; listed here for the mapping only):

| `category` | label | | `ownership` | label |
|---|---|---|---|---|
| state_route | State highway | | public_city | City road |
| arterial | Arterial | | public_state | State highway (WSDOT) |
| collector | Collector | | public_parks | Park road |
| residential | Residential | | public_school | School road |
| lane | Lane | | private | Private road |
| | | | unknown | Ownership unconfirmed |

Maintainer normalisation: `"Bainbridge Island"` → `"City of Bainbridge Island"`; `"WSDOT"` → `"WSDOT (state)"`;
`"private"` → `"Private owner"`; null → hide the maintainer tile.

**StatTile set** — order and visibility:
1. **Length** (always) — `mi`/`km` per units setting, 0.1 precision, icon `straighten`.
2. **Climb** (always) — `climb_total_m` in m/ft, icon `trending_up`; label "Climb"; 0 → "Flat".
3. **Speed limit** (only if `speed_mph != null`) — value `"25 mph"` **always mph** (it is the signed value, not a
   unit preference); leading `SpeedSignGlyph` 24×28dp: rounded-rect outline 1.5dp `onSurface`, tiny "SPEED" caption
   is *not* drawn (illegible) — just the number in `labelMedium` inside. Decorative; the tile semantics read
   "Speed limit 25 miles per hour".
4. **Surface** (only if `surface != null`) — Paved / Gravel / Unpaved / Dirt, icon `texture`.
5. **Maintained by** (only if maintainer non-null) — full-width tile, `StatTile(compactValue = true)`:
   value in `titleLarge` (Fraunces 22sp, 2 lines max) instead of `statLarge`.

Grid rule: tiles 1–4 flow into 2 columns; if the count is odd the last of them spans both columns; tile 5 always
full width. ~65 % of roads have no speed/surface → most roads show Length + Climb + Maintained by; that is fine.

`StatTile` change (one new param, default keeps trail behaviour):
`StatTile(value, label, modifier, icon = null, emphasis = false, compactValue = false, leading: (@Composable () -> Unit)? = null)`.

**OwnershipBadge** `(counted: Boolean, ownership: String)` — pill 28dp, **neutral** (never a status color):
- counted: `secondaryContainer`/`onSecondaryContainer` (spruce), leading `check` 14dp, "Counts toward the island".
- private: `surfaceContainerHigh`/`onSurfaceVariant`, leading 14×4dp muted-dash swatch (map pattern), "Private —
  not counted".
- unknown/lane: same neutral pill, leading dotted swatch, "Not counted — ownership unconfirmed".
Tap → small explainer bottom sheet (bodyMedium, product.md copy) on why the road is/isn't in the goal.

**Not-counted road detail** (reachable from the list's Not counted group only): progress block (chip, %, bar, walked
line), CommunityCard and Start walk are **hidden**; under the badge an info card (`surfaceContainerLow`) explains;
stat tiles and road ends still show.

**CautionRow** — shown when `speed_mph ≥ 35` or `category ∈ {state_route, arterial}`: row on `tertiaryContainer`,
`warning` icon 18dp `onTertiaryContainer`, bodyMedium, e.g. "Fast traffic — walk facing traffic, be visible."
(copy: product.md). Neutral, not error red; not a status color. P4's walkability rating replaces this later in the
same slot.

**RoadEndRow** `(distanceToShoreM, cityConfirmed, onShow, onDirections)` — ListItem 56dp min; leading 24dp glyph:
three short wavy lines (waterLine color) under a tiny road stub; headline "Road end at the water"; supporting
"28 m to shore · Listed by the City" (second part only if `city_confirmed`); row tap → Map tab centered z16 with
the pin; trailing `IconButton` 48dp `directions` icon (`ic_directions`, add) → `directionsUri(lat, lon, name)`;
contentDescription "Directions to road end". Rows ordered by `shore_distance_m` asc.

### 3.3 Peek sheet (map tap)

`TrailPeekSheet` unchanged in layout; subtitle = "Residential · 0.7 mi" (category label · length). When opened from
a road-end pin, an extra RoadEndRow sits between the ring row and the buttons; peek height grows by 56dp.

---

## 4. Roads list

### 4.1 Layout

```
┌────────────────────────────┐
│ Roads               🏆     │  TopAppBar (vocabulary)
│ ┌────────────────────────┐ │
│ │ 🔍 Search roads        │ │  SearchField
│ └────────────────────────┘ │
│ [Type ▾][Not started][Partway][Nearly done][Complete][Sort ▾]   FilterChips, h-scroll
│ 12 of 430 roads · 3 complete   summary (live region polite)
│ ── Main roads · 2 of 32 ──────│  sticky CategoryHeader
│ (◐) Fletcher Bay Rd NE        │  RoadRow 64dp
│ 42% Arterial · 2.1 mi  [◐]    │
│ ── Collectors · 0 of 55 ──────│
│ …                             │
│ ── Residential · 1 of 343 ────│
│ …                             │
│ ── Not counted (234) ─── ⌄ ───│  collapsed group, tap to expand
└────────────────────────────┘
```

### 4.2 Grouping and filters

- **Decision: default view is grouped by category**, A–Z within a group. Groups and order:
  **Main roads** (state_route + arterial, merged: only 3 SR rows), **Collectors**, **Residential**, **Not counted**
  (private + unknown + lane; collapsed by default, never counted in the summary line).
- `CategoryHeader(title, done, total, expanded?)`: sticky (`stickyHeader`), 40dp min, `surfaceContainerLow`
  background, `titleSmall` + trailing `numeric` "2 of 32" in onSurfaceVariant, `heading()` semantics. Not counted
  header has a chevron and is a 48dp toggle.
- **Type ▾** chip: multi-select menu (Main roads / Collectors / Residential / Not counted) — when any is selected
  only those groups render. Status chips: unchanged behaviour (multi-select, status container colors).
- **Sort ▾**: *Grouped (A–Z)* default · *Progress (high→low)* · *Length (long→short)*. Non-default sorts render a
  flat list without category headers (headers would fragment a ranking).
- Search: matches full name **and** abbreviated name ("fletcher bay rd ne" and "Fletcher Bay Road Northeast" both
  hit); results flat, headers hidden while a query is active.
- Summary line counts only counted roads, from the dataset (RD-4).

### 4.3 `RoadRow`

`TrailRow` with two parameter changes (same component, `dense: Boolean = false`):
- `dense = true` → `heightIn(min = 64.dp)` instead of 72 (430+ rows; still ≥ 48dp touch), one-line supporting text.
- Headline: **display abbreviation** of the name (Avenue→Ave, Road→Rd, Drive→Dr, Street→St, Place→Pl, Lane→Ln,
  Boulevard→Blvd, Court→Ct, Northeast→NE, Northwest→NW, Southeast→SE, Southwest→SW), `titleMedium`, 1 line,
  ellipsis. **Semantics use the full name.** Detail screen title always full name.
- Supporting: `"{category label} · {length}"`, plus `" · {surface}"` when surface is gravel/unpaved/dirt.
- Leading ProgressRing 40dp, trailing StatusChip (compact < 360dp) — unchanged.
- Not counted rows: leading 40dp neutral glyph (circle drawn with the private dash or holdout dots, `notCounted`
  color) instead of a ring; no chip; supporting "Private road" / "Ownership unconfirmed".

States: unchanged from base §3.3 (skeleton 10 rows of 64dp, empty filter/search states with vocabulary copy).

---

## 5. Me — hero, stats, milestones

### 5.1 Hero (IslandChart, roads `ChartSpec`)

Layout: same 280dp hero card; **primary number changes** (RD-6: roads completed is the primary stat; a 0.1 % island
figure is demoralising as a headline).

```
┌────────────────────────────┐
│ ≈≈ island, all counted roads ≈≈ │  IslandChart (right-weighted: island is tall, ~0.6 aspect)
│                                 │
│ 12                              │  statHero = roads complete (Fraunces)
│ of 430 roads walked             │  bodyMedium (vocabulary)
│ 14.2 mi · 8.7% of the island    │  bodySmall onSurfaceVariant, numeric
└────────────────────────────┘
```
Island % stays visible as the secondary line; product.md may swap which is primary — layout holds either.

`ChartSpec` (one code path in `IslandChart`; trails values = today's):

| Param | TRAILS | ROADS |
|---|---|---|
| simplification `tolerance` (chart units, height = 1) | 0.0015 | **0.003** (~0.8dp at 280dp; ~2400 lines × fewer points) |
| draw uncounted lines | yes (0.8dp) | **no** (`build` skips `!included` when spec says so) |
| not started stroke | 1.2dp solid, `notStarted.line` | **0.6dp solid, `notStarted.line` @ 0.45 alpha** |
| partway | 1.8dp, dash 4/2.5 | **1.4dp, dash 3/2** |
| nearly done | 2.0dp + 3.4dp casing | **1.7dp + 2.9dp casing** |
| complete | 2.8dp | **2.4dp** |
| progress halo | none | **land-colored 1.2dp extra under progress paths** (separates progress from the dense gray web) |
| water lines | 3 | 3 (unchanged — the island's identity) |

At 280dp the island is ~16 km tall → 1dp ≈ 57 m; residential streets 100–200 m apart are 2–3dp apart, so 0.6dp
not-started lines leave visible paper between them and read as a pale plat; any progress stroke ≥ 1.4dp with a
halo pops. Cached exactly as today (drawn in the display list, built off Main, once per dataset).

Semantics: "Island map of your roads: 12 of 430 roads walked, 14.2 miles, 8.7 percent of the island."

### 5.2 Stats row

`StatTileRow`: **Roads complete** `12 / 430` · **Road miles** `14.2` · **Walks** `9`. At font scale ≥ 1.5 wraps per
base §5.3.

### 5.3 Milestone strip

Under the stats row: `SectionHeader("Milestones", action = "All" → MilestonesScreen)` then a `LazyRow` of
`MilestoneBadge`s, `contentPadding = screenH`, spacing `md`: **last earned (max 2) + next 2**, in that order.
Product.md defines the ladders (e.g. road miles, roads complete, road ends reached, category firsts); UX defines
the component:

`MilestoneBadge(title, valueText, state: Earned | Next(fraction) | Locked, onClick)` — 96dp wide column:
- 64dp **survey-marker disc** (a benchmark medallion): Earned = `primaryContainer` fill, 1.5dp `primary` outer ring,
  inner engraved ring 1dp `onPrimaryContainer` @ 0.4 at 6dp inset, number/icon in `titleLarge` Fraunces
  `onPrimaryContainer`. Next = `surfaceContainerLow` fill, dotted 2dp `outlineVariant` track (same "dotted =
  not yet" language) with a solid `primary` arc for `fraction`. Locked (only on MilestonesScreen) = dotted ring,
  `onSurfaceVariant` @ 0.6 number.
- **Milestones use `primary`, never status hues** — status colors mean per-road/segment coverage only.
- Below: `labelMedium` title 2 lines centered ("5 mi of road"); Next adds `numeric` bodySmall "3.2 / 5".
- 48dp+ touch (whole column); semantics "5 miles of road, earned October 18" / "Next: 5 miles of road, 3.2 of 5".

`MilestonesScreen` (pushed): one `SectionHeader` per ladder, `FlowRow` of badges (Earned → Next → Locked).

---

## 6. Celebrations

### 6.1 Milestone mini-celebration

**Decision:** not a modal. A `MilestoneCard` is inserted as the **first item of the Results sheet** (base §3.5f)
when a walk crosses ≥ 1 milestone and completes no road. If a road was also completed, the road celebration plays
first (§6.2) and the card still appears in Results.

```
┌────────────────────────────┐
│ (◉ 5)  Milestone           │  64dp MilestoneBadge (Earned) left; labelLarge caps "MILESTONE" in primary
│        5 mi of road         │  titleLarge Fraunces
│        4.6 → 5.0 mi         │  numeric (system font, tnum) counter
└────────────────────────────┘
```
Card: `primaryContainer` @ 0.5 over `surfaceContainerLow`, large shape, padding 16.

Timeline (full motion, total 1.2 s, starts when the sheet settles):
| t (ms) | Element | Animation |
|---|---|---|
| 0–300 | badge | scale 0.6 → 1.08 → 1.0 (emphasizedDecelerate), "stamp" |
| 0–600 | badge ring | `primary` arc sweeps 0 → 360° |
| 250 | — | haptic `Confirm` (iOS `.success`) |
| 300–1000 | counter | old → new value, `tnum`, fixed-width box |
| 200–500 | title | fade + 6dp rise |

More than one milestone: the card shows the highest; a trailing `+2 more` TextButton opens MilestonesScreen.
Reduced motion: final frame immediately, haptic once. Semantics: card is a merged node, `liveRegion = Polite`:
"Milestone: 5 miles of road. 4.6 to 5.0 miles."

### 6.2 Road-complete celebration (variant of CelebrationScreen)

Same screen, same timeline structure (base §3.6), these differences (via a `CelebrationSpec` on the profile):
- **Ghost before draw:** the road drawn as a **steel bed** (its category bed color from the active style, 10dp,
  0.6 alpha) instead of a dotted ghost — "plain road becomes painted road".
- **Draw:** `complete.accent` 5dp line draws along the road 100–1300 ms (PathMeasure, emphasizedDecelerate),
  centred on the bed. Pulse 5→7→5dp at 1100–1400 + haptic. Offset water lines unchanged (on a road polyline they
  read as lane edges — keep, it's the shared signature).
- **Glyph source:** the road's segment polylines; the box fit uses the same `fit()`; roads under 150 m get a
  minimum drawn length of 60% of the box diagonal (scale up), so a cul-de-sac still feels like a stroke.
- **Numbers:** statHero counter = **roads complete** (old → new, e.g. 11 → 12) in the fixed-width box sized to the
  widest of `"{total}"`; secondary line `titleMedium` "of 430 roads"; tertiary bodyMedium "14.2 mi · 8.7% of the
  island" (no counter).
- **Batching (roads are short — a walk can finish many):** 1–2 completions → one page each (base rule, length desc).
  **≥ 3 → a single combined page:** glyph = all completed roads fitted together in one box (each draws in sequence,
  80ms stagger, total draw window still 100–1300 ms), title labelLarge "ROADS COMPLETE", name line =
  displaySmall "4 roads", then up to 3 names `bodyLarge` + "and 2 more"; counter animates once by the full delta.
- Reduced motion: unchanged rule — final frame, bed + violet road fully drawn, one static ring, no counter, haptic
  once. Screen reader: "4 roads complete: Aaron Avenue Northeast, …, and 1 more. 16 of 430 roads walked. Continue
  button."

---

## 7. Onboarding illustrations (road-grid motif)

Canvas-drawn from tokens (themes in dark), 240dp tall, same pager.

- **P1 "Walk every road":** island silhouette with water lines (as trails) filled with a **real plat**: the counted
  network from `IslandGeometry` drawn with the §5.1 ROADS chart spec, all not started, plus ~8 % of segments
  (deterministic pick: the segments within 1.2 km of Winslow ferry terminal) in complete/nearly/partway. Reads
  immediately as "a whole island of streets, a little colored in".
- **P2 "How a road fills in":** a 3×3 **road grid** fragment (two vertical, two horizontal streets, one diagonal
  arterial as a wider steel bed). One street animates once (long4): bed only → gray dots → partway dash → nearly
  done (casing) → complete violet, like a centreline being painted. Reduced motion: static strip of the four states
  on four parallel streets, labelled with `StatusLegend` rows beneath (real patterns).
- **P3 privacy:** unchanged illustration; if product.md adds a "private roads don't count" page, illustrate a grid
  where one street is the muted private dash with a small lock-free "no entry" bar at its mouth — never the
  `error` color.
- **Road-grid empty-state glyph** (`EmptyMotif.RoadGrid`, used by `EmptyState` in roads): 96dp, two vertical +
  two horizontal 2dp lines in `waterLine` forming a `#` with rounded ends, the centre cell's right edge dotted.
  Replaces the water-lines glyph in roads empty states only. `DatasetLoading` keeps the island + water lines
  (identity), copy from vocabulary.

---

## 8. Accessibility (roads-specific)

1. **Status never by color alone** still holds: on the roads map the not-started channel below z15 is *absence of
   status color on a plain road line* (shape: no dash/dots; legend row 4 says so); from z15 it's dots. Progress keeps
   dash / casing / thickness.
2. **CVD:** under deutan/protan, Complete violet shifts toward blue — toward the steel bed. Separation is carried by
   (a) the land-colored **halo gap** under every progress line (non-negotiable for roads; never drop it for perf),
   (b) saturation and width (complete is the thickest line), (c) the bed being lighter than any progress stroke on
   collectors/residential. Arterial/SR bed vs complete is only 1.7:1 in luminance — the halo is what makes it pass.
3. **Dense-map screen reader:** the map node reads "Road map. 12 of 430 roads complete." Custom actions: "Show roads
   list", "Show road ends" (opens list filtered to roads with ends). Map taps at intersections fall back to the
   chooser (base rule) — the chooser is a list and fully accessible.
4. **Hit targets:** 44dp tap square unchanged; road-end pins win within 16dp so a pin on a road is reachable.
5. **Long list:** category headers are `heading()` so TalkBack heading navigation jumps groups (the fastest way
   through 430+ rows without a fast-scroller). Summary line is a polite live region. Abbreviated names are visual
   only; contentDescription uses the full name ("Northeast", not "N E").
6. **Font scale ≥ 1.5:** RoadRow `dense` min height no longer applies (grows); category header counts drop to a
   second line; road-detail StatTiles go single column; MilestoneBadge strip keeps 96dp width but titles wrap to
   3 lines; Me hero secondary line wraps under the caption.
7. **Speed sign glyph and road-end glyph** are decorative (`clearAndSetSemantics {}` on the glyph; tile/row text
   carries meaning).
8. **Reduced motion:** milestone card, road celebration, onboarding P2 all have static variants (above); camera fits
   jump.
9. **Contrast:** new pairs measured (§1.1). OwnershipBadge and CautionRow use container/onContainer pairs from the
   base scheme (≥ 7:1).

---

## 9. Engineer checklist (file → change)

| File | Change |
|---|---|
| `ui/theme/BtBrand.kt` (new), `BtTheme.kt` | brand roles per profile (§1.1) |
| `androidApp/src/roads/res/` | adaptive + monochrome icon, `app_name` (§1.2) |
| `ui/components/BtIcons.kt` + composeResources | `ic_road`, `ic_road_filled`, `ic_directions`, `ic_straighten`, `ic_texture`, `ic_warning` |
| `map/MapPalette.kt` | `TrailLineSpec` → `StatusLayerSpec.TRAILS/ROADS` (§2.2) |
| `map/StatusLayers.kt` | read `spec`; halo statuses; NS minZoom; skip hairline/TL-6 by flag; road-end layer; selection min width |
| `map/TrailMap.kt` / hit-testing | tappable ids = spec status ids + `extraTappableLayerIds`; fit clamp from spec |
| `scripts/build-map-styles.py` | bed widths/opacity per §2.3 (per-stop match), private minzoom 12, holdout minzoom 13; regenerate styles |
| `ui/components/StatusLegend.kt` | roads rows (§2.7) |
| `ui/TrailDetailScreen.kt` | road blocks (§3), OwnershipBadge, CautionRow, RoadEndRow, SpeedSignGlyph |
| `ui/components/Layout.kt` | `StatTile(compactValue, leading)` |
| `ui/components/Rows.kt` | `TrailRow(dense)`, not-counted leading glyph; `CategoryHeader` |
| `ui/TrailsScreen.kt`, `TrailListLogic.kt` | grouping, Type chip, sort modes, abbreviation + search (§4) |
| `ui/components/IslandChart.kt` | `ChartSpec` (§5.1) |
| `ui/MeScreen.kt` | hero numbers, stats, milestone strip; `MilestoneBadge`, `MilestonesScreen` |
| `record/ResultsSheet.kt` | `MilestoneCard` (§6.1) |
| `ui/CelebrationScreen.kt` | `CelebrationSpec` ghost/numbers/batching (§6.2) |
| `ui/OnboardingScreen.kt`, `ui/components/States.kt` | road-grid illustrations, `EmptyMotif` (§7) |
