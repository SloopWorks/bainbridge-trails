# Play — visual effects design (`MapFx` catalogue)

Date: 2026-10-20 · Role: design-director (motion / visual effects) · Status: **draft for review** (gate
`design_before_build`: needs ACCEPT before code) · Scope source: `docs/play/brief.md` (owner request = authority).

Inputs: `docs/mvp/ux.md` (§0 "Chart room", §1 tokens, §1.7 motion, §3.6 celebration, §5 a11y, §6 map),
`docs/spikes/map-render/styles.md` (map palettes, style layer ids), `ui/theme/BtMotion.kt`, `ui/theme/BtColors.kt`,
`ui/CelebrationScreen.kt` + `ui/IslandCelebration.kt` (timeline idiom), `map/TrailMap.kt`, `map/StatusLayers.kt`,
`map/MapPalette.kt`, `record/TrackLayer.kt`, `map/MapLogic.kt` (`mercatorOffsetDp`).

What this file decides: how every Play effect **looks, moves, layers, sounds, announces and costs**. It does **not**
decide game rules, point values, or scheduling (when an effect is armed). Those belong to the Play rules and
scheduling specs. This file assumes each content item emits the lifecycle events in §2.9 and draws from them.

Words in effect copy (`{road}`, `{roads}`, `{trail}`) route through `GoalVocabulary` / `LocalGoal`. Final copy belongs
to the product spec. Copy here is placeholder unless that spec agrees, as in ux.md.

---

## 0. Direction: "the chart comes alive"

Old sea charts had marginalia: sea monsters in the empty water, ships under sail on the routes, a compass rose, a
cartouche, little engraved trees and houses. The Chart room direction already gives us paper, ink, Fraunces and water
lines. **Play is that chart's marginalia waking up.** Everything Play draws should look like it was engraved on the same
chart by the same hand, then given a pulse.

Five rules hold everything below together.

1. **Coverage is sacred.** Status lines (not started / partway / nearly done / complete) are never covered, recolored,
   dimmed or imitated by Play. Play never uses a status hue. Play never draws a line *along* a trail or road in a
   saturated colour. The only Play lines along the network are ink (kraken wrap, race rails), and they draw *under*
   the status line like a casing, so the status line keeps its full width and colour on top.
2. **Persistent things live in the map, passing things live above it.** Anything that stays on screen for more than
   ~3 s (stamps, glints, wraps, fog, tide, ferry, tracks) is a MapLibre layer. It is zoom-correct, sits under the
   labels and costs almost nothing. Compose overlay drawing (above the map and its labels) is only for short hero
   moments of 3 s or less, bursts and flights. When the moment ends, it hands off to a layer.
3. **One hero at a time.** At most one hero effect plays on screen at a time. Ambient effects are quiet enough to
   ignore. Under reduced motion, nothing moves, but every event still announces itself and keeps its haptic.
4. **Engraving, not cartoon.** Line art in ink, hatching for shade, flat paper fills, at most one Play colour per
   figure, no gradients. Light sources (lamp, glint) are the only exception: they get a soft radial falloff. Shadows
   fall to the south-east, because the hillshade light comes from the north-west (315°). Every figure agrees with the
   relief.
5. **Kind, not creepy.** Kids play this. The kraken is grumpy and curious, not scary: no teeth, a big lidded eye, curling
   tentacles. Creatures are glimpsed, never chased. No effect points anyone onto private land, a beach at high tide,
   or a road without a shoulder. Content placement enforces that. Effects never animate *toward* such places.

---

## 1. Shared vocabulary

### 1.1 Illustration style ("engraved marginalia")

| Aspect | Rule |
|---|---|
| Line | `playInk`, round caps/joins. Two weights only: **contour** 2.0dp (silhouette) and **detail** 1.25dp (interior, hatching). At stamp sizes under 20dp: contour 1.5dp and no detail lines. |
| Hatching | Shade = parallel hatching at 45° (NE–SW strokes) on the SE side of a form. Spacing 3dp, detail weight. Never use a gradient for shade. |
| Fill | Paper fill (`surfaceBright` light / `surfaceContainerHigh` dark) + at most **one** role fill per figure (`secondaryContainer`, `tertiaryContainer`, `playSeaGlass`, `playLamp`). |
| Paper grain | A 128×128 tileable grain bitmap, multiplied at 6% (light) / screened at 4% (dark), **only inside stamp backings and full-screen moments**, never over the map. |
| Glow | Only for light sources (lamp, glint, phosphor). Radial falloff of the source colour, alpha 0.55 → 0 over 1.0–1.6× the source radius. Dark theme: up to 0.75. |
| Shadow | A flat ink ellipse at 18% alpha (dark: `#000` at 35%), offset (+1.5dp, +1.5dp), i.e. SE. Ground-anchored stamps get no shadow (they are printed on the chart). Lifted figures (pins, hopping frog, rising tentacle) get one, and it shrinks as they lift. |
| Grid / authoring | 24-unit artboard (stamps) or 48-unit (hero figures). Authored as SVG paths, converted to Compose `ImageVector` (commonMain) for overlay drawing and rasterised at @2x/@3x to MapLibre images for layers (§4.2). One source per figure. |
| Type in art | None, except the riddle "?" and the race digits. Labels belong to the map or the sheet. |
| Faces | Eyes only: a paper almond, an ink pupil, a lid stroke. No mouths except the frog's (a single curve). No teeth anywhere. |

### 1.2 Color roles

Allowed existing roles: `secondary` / `secondaryContainer` (the Sound teal family), `tertiary` / `tertiaryContainer`
(contour brown), `waterLine`, `mapChrome` / `onMapChrome`, `surfaceBright` / `surface*` (paper), `onSurface` (ink on
chrome only). **Forbidden:** `notStarted`, `partway`, `nearlyDone`, `complete` (all four roles each), `nearlyDoneCasing`,
`recording`, `error`, `userTrack` (blue means *you*), `primary` on the map (chrome only).

**New Play roles (4).** They go into `BtExtraColors` as `play: PlayColors` and must be checked by the existing
contrast tests.

| Role | Light | Dark | Use | Never |
|---|---|---|---|---|
| `playInk` | `#22323C` | `#CFE0E6` | All Play line art; kraken; wrap rope; race rails; ink pool (at ≤ 35% α). | Text on the map. Fills larger than 24dp² at full alpha. |
| `playSeaGlass` | `#08665A` | `#63DCC6` | Treasures (sea glass), finds, the "+" of points, streak floats, proximity ping. | A line along the network. Any area > 16dp diameter at full alpha. |
| `playLamp` | `#FFF1C9` | `#FFF0CF` | Light: lantern flame, lit windows, owl eyes, glint core. Always inside an ink outline or as a glow. | On its own as a mark (it is near-invisible on light land, by design). As a line. |
| `playFog` | `#F4F6F5` @ ≤ 55% α | `#7D9095` @ ≤ 25% α | Fog patches, mist, dust puffs, kraken spray (light). | Above status lines, above labels (except the fog-lift moment, §3.15). |

Contrast (WCAG 2.1, measured against styles.md Topo / Dark values):

| Pair | Light | Dark |
|---|---|---|
| `playInk` on land / water / woods | 11.4 / 6.8 / 9.6 | 11.1 / 12.4 / 10.1 |
| `playInk` on road (stamp over a road) | 13.2 | 7.9 |
| `playSeaGlass` on land / water / woods / paper | 5.9 / 3.5 / 5.0 / 6.3 | 9.0 / 10.1 / 8.2 / 10.7 |
| `playLamp` vs its `playInk` outline | 11.8 | (lamp sits on a dark-chrome disc) 14.9 |
| Fog over land, light (status line vs fogged land, worst case partway) | 2.54 → 2.64 (fog *raises* it) | — |
| Fog over land, dark | land goes `#1D2925` → `#354341` | status lines keep their own land-coloured **halo** (drawn above fog, §1.6), so the line-to-surround contrast stays at the no-fog value |

Notes:
- Sea glass vs `primary` spruce (`#2E5E4E`): the hues are close, but primary never appears on the map and sea glass never
  appears on buttons. The pairing with the faceted pebble / sparkle shape is the identity.
- CVD: under deutan/protan, sea glass becomes a desaturated blue-gray, near `complete` (which becomes blue). This is
  safe because Play never draws sea glass as a line and `complete` is only ever a line on the map. Shape (pebble,
  sparkle, "+") is the carrier.
- `playLamp` is a pale cream, not amber. Partway (`#C98A00` / `#F0B43C`) is saturated ochre. They differ by more than
  25 L* in light, and the lamp is never a line or a ring.

### 1.3 Marker anatomy

Two kinds of marker. Every content item uses exactly one.

```
 STAMP (ground-anchored, printed on the chart)        PIN (lifted token, stands on a point)
                                                            ╭───╮
   ┌·······┐   ← optional paper backing (rounded           │ ? │   ← figure 28dp, ink contour, one fill
   : ◇~◇~◇ :     square 4dp corners or circle), ink        ╰─┬─╯
   └·······┘     1.5dp, grain inside                         │      ← 1.25dp ink stem, 6dp
      ▲ anchor = centre; rotates with line bearing       ·───●───·  ← ground dot 4dp ink + SE shadow ellipse
        when on a line (log, tracks, wrap)                   ▲ anchor = ground dot
```

| | Stamp | Pin |
|---|---|---|
| Meaning | A *thing on the ground* (treasure, frog, log, tracks, house, lantern stand) | An *invitation* (riddle, race start, easter-egg hint, lore point) |
| Render | MapLibre `SymbolLayer`, `icon-pitch-alignment = map`, `icon-rotation-alignment = map` for line-bound stamps, `viewport` otherwise | MapLibre `SymbolLayer`, `icon-anchor = bottom`, `icon-rotation-alignment = viewport` |
| Size by zoom | Grows with the map (see §1.4) | Constant screen size (28dp figure) from z14 up |
| Hit target | ≥ 48dp square centred on the anchor (`queryRenderedFeatures`, like ux.md §6.1) | 48dp square centred on the figure, not the ground dot |
| Collision | `icon-allow-overlap = true`, `icon-ignore-placement = false`: map labels yield to Play markers, never the reverse | same |
| Spent state | `playInk` at 40% α, no fill, no backing ("an old mark on the chart"). Never gray (warm gray is not-started). | Collapses to a stamp-sized ink ring. |

### 1.4 Scale across zoom (z10.5 island → z18 street)

| Band | What the user is doing | Play shows |
|---|---|---|
| **z10.5–12.5** island | Seeing the whole goal | No figures. Each live item becomes a **glyph**: a 6dp sea-glass dot with a 1.5dp ink ring (treasure / egg / riddle / race), or a 10dp ink glyph for lore (kraken curl, frog, ferry). The kraken wrap shows as rope casing (it carries information). Weather: fog and tide only. No glints, no idle loops. |
| **z12.5–14** neighbourhood | Picking where to go | Stamps at 0.6× (≈ 18dp) and pins at 22dp. Glints on (max 6). Ferry figure at 0.6×. Hero intros may play from z13.5, at reduced amplitude (tentacles 60% length). |
| **z14–16** walking | Following a trail | Full stamps (1.0×, 28–32dp), pins 28dp, idle loops, creature glimpses, tracks, race chevrons. |
| **z16–18** street | Close-up | Stamps grow to 1.4× (exponential base 1.5, capped at 44dp). Hero figures use geo-true lengths (tentacles reach the actual road). Detail lines on. |

Implementation: one `icon-size` expression per stamp layer,
`interpolate(exponential(1.5), zoom(), 12.5 to 0.6, 14 to 1.0, 16 to 1.0, 18 to 1.4)`. The glyph↔stamp swap is two
layers that cross-fade by opacity over z12.3–12.7. Overlay heroes read the same table through
`FxScale.at(zoom)` so both renderers agree.

### 1.5 North-up, no tilt

The chart is north-up with rotate and pitch disabled (ux.md §6.3, `chartInteractions`). Effects lean on that:

- **"Up" on screen is north, and also "up" for figures.** Standing figures (pins, the frog, the house, tentacles
  rising) are drawn in elevation, like the little engraved lighthouses on a chart. They are never foreshortened.
- Shadows always fall SE (+x, +y). Spray and splash rise toward −y (screen up). Rain falls toward +y, slanted 12° to
  the right, as on a southerly wind.
- Because bearing and tilt are always 0, geo→screen is pure Web Mercator from the camera target and zoom.
  `mercatorOffsetDp` in `MapLogic.kt` already does this math. The overlay can project hundreds of points per frame
  without one FFI call per point (§4.3).
- Line-bound stamps (fallen log, tracks, wrap rope) rotate with the line bearing in map space. Everything else stays
  upright.

### 1.6 Layer stack

From bottom to top. The `fx-*` slots are new. Style layer ids come from the shipped style JSON
(`background … shore, shore-ripple-0..2, contour-*, buildings, roads-*, ferry, roads-ds-*, trails-mup-bed,
contour-label, water-label, trail-label, place-label`).

```
 ┌──────────────────────────────────────────────────────────────────────────┐
 │ M   full-screen moment (play_wake, celebrations)          Compose        │
 │ O2  map chrome: search, buttons, ledger pill, RecordingPill Compose      │
 │ O1  FX overlay: hero figures, bursts, pops, rain          Compose Canvas │  ← above labels: transient only (≤ 3 s heroes)
 ├──────────────────────────────────────────────────────────────────────────┤
 │     place-label, trail-label                               style         │
 │ P4  fx-stamp: stamps, pins, glints, ferry, chevrons, ghost Anchor.Below("trail-label"), after StatusLayers
 │     ── StatusLayers: status lines, selection, road ends ──               │
 │ P3  fx-casing: kraken wrap rope, race rails                inside StatusLayers, after halos, before casings
 │     ── StatusLayers: hairlines, halos ──                                  │
 │     water-label, contour-label                             style         │
 │ P2  fx-ground: fog patches, tide wash on sand, creature tracks, house glow Anchor.Below("contour-label")
 │     roads, ferry, buildings, contours                      style         │
 │     shore-ripple-0..2                                      style         │
 │ P1  fx-water: ink pool, lurk ripples, ferry wake, horn rings (static part) Anchor.Below("shore")
 │     water, hillshade, landuse, earth                       style         │
 └──────────────────────────────────────────────────────────────────────────┘
```

Why this order:
- **P1 under `shore`** keeps the coastline stroke and the signature water lines crisp over the kraken's ink.
- **P2 under the status halos.** Fog and tide lighten land but never the land-coloured halo around each counted line.
  So status contrast stays at the no-fog value (§1.2).
- **P3 between halo and status line**, like the existing casing. The rope shows on both sides of the status line,
  and the status line draws on top, untouched. This needs one small API addition to `StatusLayers`: a
  `casingFx: @Composable @MaplibreComposable () -> Unit = {}` slot composed after the halos. Pure mode passes nothing.
- **P4 above the status lines but under the labels**, so trail names stay readable and markers stay tappable.
- **O1 is the only Play drawing above labels.** That is why it is reserved for transient heroes (§0 rule 2).

### 1.7 Motion language

Durations come from `BtDuration` and easings from `BtEasing`. Play adds a few named curves, built from the M3 set,
in a `PlayMotion` object next to `BtMotion`:

| Name | Definition | Use |
|---|---|---|
| `anticipate` | `emphasizedAccelerate` over the first 30%, reversed (a dip of −8% of the travel) | Wind-up before a rise or hop |
| `overshootSettle` | `emphasizedDecelerate` to 108%, then `standard` back to 100% over `short4` | Landings, pill bumps, stamps thunking |
| `sway` | `sin(2πt/period)`, amplitude in degrees, period 2800–3600 ms, seeded phase | Idle loops (tentacles, lantern, kelp) |
| `drift` | Linear in time (no easing), for things that move with wind or tide | Fog, wake, ferry |
| `ballistic` | Constant-gravity parabola in screen space, g = 1400 dp/s², initial v from a seeded spread | Splash droplets, shards, dust |

Timeline idiom (from `CelebrationScreen` / `IslandCelebrationArt`): one `Animatable` (or the shared `FxClock`, §4.3) gives
`t` in ms. Each element maps `phase(t, from, to)` through an easing. Paths draw with `PathMeasure.getSegment`. The
**reduced-motion variant is `t = end`** (the final frame), unless the card says otherwise.

### 1.8 Haptics vocabulary

| Token | Android (`HapticFeedbackType`, CMP 1.12) | iOS | Use |
|---|---|---|---|
| `tick` | `SegmentTick` | `UISelectionFeedbackGenerator` | Countdown digit, a stamp landing, a proximity ping |
| `ticks` | `SegmentFrequentTick` × n, 80 ms apart | selection × n | Woodpecker taps, rope unspooling |
| `confirm` | `Confirm` (fallback `LongPress` < API 30, as today) | `.success` | A find, a solve, a clear, race GO, Play on |
| `thud` | `LongPress` | `UIImpactFeedbackGenerator(.medium)` | Kraken squeeze, log drop, tape break |
| `reject` | `Reject` | `.warning` | Wrong riddle answer (never paired with red) |

Haptics fire under reduced motion (ux.md §1.7). They never fire while the app is backgrounded, and never more than
one per 250 ms.

### 1.9 Sound (optional, off by default)

Setting: **Play → Sounds** (default off). When on: short (≤ 1.5 s), soft (peak −18 dBFS), recorded or synthesised from
natural sources (horn, water, wood, glass). iOS `AVAudioSession` category `.ambient` (respects the silent switch,
mixes with the user's music). Android `AudioAttributes.USAGE_GAME` with transient audio focus `MAY_DUCK`. Muted
while TalkBack or VoiceOver is speaking an announcement, and 22:00–07:00 local unless the sheet is open. Bundled
OGG/AAC, about 15 files, ≤ 400 KB total.

### 1.10 Accessibility contract

- **One announcer.** Map effects never post their own live regions. They call `PlayAnnouncer.say(text, politeness)`.
  It is a single invisible node on the map screen that queues, de-duplicates (same text within 30 s is dropped), and
  rate-limits (≤ 1 polite announcement per 4 s). Assertive is reserved for the opt-in moment, race GO and finish,
  and find/solve/clear resolutions.
- **Every timed thing has an untimed path.** Anything that appears briefly (glimpses, glints, horn) leaves a trace you
  can still act on for ≥ 10 min (WCAG 2.2.1), and it shows in the Play list sheet. The map node gets custom accessibility
  actions: "Play items nearby", "Ledger".
- Decorative drawing is `clearAndSetSemantics {}`. Markers are reachable through the Play list, not by exploring
  the map canvas (same as trails, ux.md §5.5).
- **Reduced motion** (`BtTheme.motion.reduced`): heroes jump to the final frame, crossfading `short3`. Ambient loops,
  glints, rain, drift and particles are off. Fog and tide show as static patches. Water-line rings become one static ring.
  Point flights become an in-place crossfade on the ledger pill. Haptics and announcements are unchanged.

### 1.11 Lifecycle every effect draws from

```
dormant ──arm──▶ present ──first in view (once/day)──▶ intro ──▶ idle ──engage (proximity ≤ r or tap)──▶ engaged
                                                                                      │
                     spent ◀──outro── resolved ◀──resolve (found/solved/cleared/finished)┘
                       ▲                                                    expire ──▶ exit (fade, no fanfare)
```

`intro` plays once per item per day per device (seeded by `itemId + localDate`), and only when the item is in view at a
zoom where the hero applies. Otherwise the item simply *is there* (`present` = static stamp).

---

## 2. Arbitration: the effect director

`FxDirector` (part of `MapFx`) decides what may play. Same rules for every effect.

| Class | Examples | Concurrency | Global gap |
|---|---|---|---|
| **H0 moment** | `play_wake`, celebrations | exclusive; everything else holds | — |
| **H1 resolve hero** | `treasure_found`, `kraken_recede`, `riddle_light`, `race_finish`, `obstacle_clear`, `egg_reveal` | 1 on screen | 1.2 s after any hero |
| **H2 intro hero** | `kraken_attack`, `obstacle_drop`, `race_countdown` | 1 on screen; queued behind H1 | 8 s between intros |
| **E encounter** | `frog_hop`, creature glimpse, `ferry_horn`, `proximity_ripple` | ≤ 1 overlay encounter + any layer ones | per-effect cooldowns |
| **A ambient** | lurk, glints, ferry run, tide, fog, rain, idle loops | layer: unlimited within budget; overlay: ≤ 2 | — |
| **U UI micro** | `points_pop`, `streak_float_line`, `lore_stamp_ink` | independent of the map | coalescing |

- While an H plays, overlay ambient (O1) **holds its current frame**. Layer ambient (P1–P4) continues, because it is
  calm and cheap.
- An H that is not in view when it fires is not queued: the item goes straight to its end state (`present` or `spent`)
  and the announcer still speaks.
- **Recording mode** (map follows the walker, trail taps off): allowed are `proximity_ripple`, `points_pop`, H1
  resolves, `race_*`, creature tracks, and layer ambient at ≤ 2 Hz. Overlay ambient is off.
- **Input:** O1 and M are pointer-transparent except the tappable Play markers' 48dp hit areas. No effect except
  `play_wake` blocks map input, and `play_wake` is skippable by any tap (§3.17).

---

## 3. Effect catalogue

Card format: **Purpose / trigger · Where · Layer · Storyboard · Timeline · Colors · Light/Dark · Reduced · Announce ·
Haptics · Sound · Budget · Showcase preset.** Times in ms from the effect's `t = 0`. "Where": **(map)** geo-anchored layer,
**(overlay)** Compose overlay projected from geo, **(moment)** full-screen, **(sheet)** UI micro-animation. Budgets
target a 2020 mid phone (Pixel 4a / iPhone SE 2) at ≤ 2 ms per frame of Compose overlay draw. Layer effects are
budgeted in property updates per second.

Showcase presets give `lat, lon, zoom` and the state. Coordinates marked ≈ must be confirmed from content config at
build time. Frog Rock was computed from the roads dataset (Phelps Rd NE × N Madison Ave NE vertex).

---

### 3.1 `kraken_attack` — the kraken wraps a shore road

**Purpose / trigger.** The signature obstacle. A shore road (roads goal) or shore trail / beach access (trails goal)
inside the "kraken reach" (configured per item, ≤ 150 m from water) is *wrapped*. Walking the wrapped stretch clears it.
The intro plays on the attack's first in-view moment of the day at z ≥ 13.5, or on approach within 200 m while recording.

**Where.** Wrap rope = (map) P3. Ink pool = (map) P1. Tentacles + splash = (overlay) O1, hero ≤ 2.6 s, handing off to
`kraken_lurk` (map) and the rope (map).

**Storyboard** (z15, road along the shore, water to the east):

```
 t=0            t=400            t=900              t=1300              t=1800 → handoff
 land │ water   land │  ·  ◌      land │   ╭╮          land │ ╭─╮ ˙ ·      land │
 ═════╪         ═════╪ ◌ (●) ◌   ═════╪  ╱  ╲ ╭╮     ═≈≈≈≈╪╯╭╯ │  ˙     ═≈≈≈≈╪  (eye)
 road │  ~ ~    road │  ◌  ·     road │ ╱ ╭╮ ╲╯ ╲    ≈rope≈ ╰╯ ╭╯ ˙     ≈rope≈ ◌
      │              │ rings          │╱ ╱  ╲  ╲│          (squeeze)          lurk ripples
      calm      converge (wind-up)   tentacles rise    wrap + splash      tentacles sink to
                                     + ink blooms                         rope + lurk
```

**Timeline (full motion, 2600 ms; input never blocked).**

| t (ms) | Element | Motion | Easing / duration |
|---|---|---|---|
| 0–400 | Water anchor (offshore point, ≥ 60 m from the road) | 3 `waterLine` rings **converge** inward (radius 48→8dp, α .0→.7): anticipation, the opposite of the celebration rings | `emphasizedAccelerate`, `medium4` |
| 250–700 | Ink pool | `playInk` blob blooms (radius 0→geo 60 m, α 0→.35), edge noise (shader or fallback, §4.4) | `emphasizedDecelerate`, `long1` |
| 400–500 | Hold | Nothing moves: the beat before | — |
| 500–1100 | 3 tentacles (z ≥ 15; 2 at z14; 1 at z13.5) | Each is a tapered stroke along a cubic Bézier from the water anchor to a target point on the road (target spacing 25 m). It grows with `getSegment(0, L·p)`, width 12→2dp along its length, sucker dots (paper, 2dp) on the inner side. Stagger 90 ms. Tip overshoots by 10% and curls back. | `emphasizedDecelerate`, `extraLong1` each; curl `overshootSettle` |
| 900–1300 | Splash | 12 droplets (`playInk` 6, `playFog` 6), `ballistic` from each tentacle base, 2–4dp | `ballistic`, `medium4` |
| 1100–1700 | Rope wrap | P3 rope draws on along the attacked segment(s) from the tentacle targets outward: GeoJSON trimmed in 8 steps (≈ 13 Hz). Rope = `line-pattern` coil sprite (§4.2), width = status width + 8px | stepped, `long4` |
| 1500–1800 | Squeeze | Tentacles scale 1→.94→1 about their bases; `thud` haptic at 1500 | `overshootSettle`, `medium2` |
| 1800–2600 | Handoff | Tentacles sink (reverse draw to 35%, then α→0). `kraken_lurk` eye appears in the pool. Ink pool settles to its idle α .2. | `emphasizedAccelerate`, `extraLong2` |

**Idle (in view, z ≥ 14, layers only):** the rope stays. `kraken_lurk` runs. No overlay tentacles idle (they would sit
above labels).

**`kraken_recede` (resolve: the wrapped stretch was walked).** H1, 2200 ms:

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–150 | Rope | α flickers 1→.6→1 (grip loosens) | `standard`, `short3` |
| 150–750 | Rope | Un-draws from the walker's end back to the water (stepped trim, 8 steps); `ticks` ×4 | stepped, `long4` |
| 200–900 | Tentacles (overlay, re-spawned at full length) | Slide back along their own paths into the water (`getSegment(L·p, L)`) | `emphasizedAccelerate`, `extraLong1` |
| 600–1400 | Ink pool | α .2→0, radius ×1.3 (dissipates) | `standardDecelerate`, `extraLong2` |
| 900–2100 | Water lines | 3 rings expand from the anchor (8→56dp, α .8→0, stagger 150): the celebration motif, "the Sound is calm" | `emphasizedDecelerate`, as ux.md §3.6 |
| 1200 | `points_pop` | from the road midpoint | — |

**Colors.** Body/tentacles: `playInk` contour + `secondaryContainer` fill (light `#D3E7EB`, dark `#224D56`), suckers paper.
Eye: paper almond, `playInk` pupil, `tertiary` lid stroke. Ink pool: `playInk` α ≤ .35 (light, over water → `#738E96`).
On dark the ink is pale (`#CFE0E6`), so the pool reads as a moonlit *disturbance*, not a dark stain. Fine: it is the same role.
Rope: `playInk` coil at α .85 over the road bed.

**Reduced.** No converge, no tentacles: the rope appears in full (one `short3` crossfade), the static lurk eye
(open) sits in the pool at α .2, and one static water-line ring marks the anchor. Recede: the rope disappears
(crossfade), plus one static ring for 2 s, then gone.

**Announce.** Intro, polite: "The kraken has wrapped {road name}. Walk it to drive the kraken off." Recede, assertive:
"You drove the kraken off {road name}. Plus {n} points." Words via `GoalVocabulary` ({road} / {trail}).

**Haptics.** `thud` at squeeze (intro); `ticks` ×4 on unspool, `confirm` at 900 (recede).
**Sound.** Intro: a low water swell + wet slap (1.2 s). Recede: a receding swell + one soft bell (1.4 s).

**Budget.** Overlay: 3 tentacle paths (≈ 40 verts each, preallocated) + 3 sucker point arrays (≤ 12 pts) + 12 droplets
+ 3 rings ⇒ ≈ 25 draw calls, about 0.9 ms on target. Layer: 8 GeoJSON trims of ≤ 200 verts plus 1 paint transition.
At most **one** kraken intro on screen, and at most 3 active attacks island-wide (content rule, also a render cap).

**Showcase.** Manitou Beach Drive NE, `47.66007, -122.50494, z15.5`, state = armed, intro at t=0. Key frames t =
400 / 1100 / 1500 / 2600. Recede variant: same camera, state = wrapped → resolved. Second preset: Rockaway Beach Rd NE
`47.60070, -122.49995, z15`.

---

### 3.2 `kraken_lurk` — ambient: something in the Sound

**Purpose / trigger.** Tells you an attack is live nearby when you are not looking at the road. Runs while any attack
is active and its anchor is in view, z ≥ 12.5. At z < 12.5 the anchor becomes the kraken glyph (§1.4).

**Where.** (map) P1 + P4. No overlay at all: this runs for minutes.

**Timeline (loop; transition-driven, §4.2 "keyframe transitions").**

| t (ms, loop 3200) | Element | Motion |
|---|---|---|
| 0–3200 | Ripple A | `CircleLayer` stroke-only ring at the anchor: radius 6→34dp and stroke α .6→0 via a 3200 ms paint transition, then reset with transition 0 |
| 1600–4800 | Ripple B | Same, offset by half a period |
| every 12–20 s (seeded) | Eye (P4 symbol) | Surfaces: `icon-opacity` 0→1 and `icon-translate` (0, 6dp)→(0, 0) over 400 ms. Holds 1.6 s. Blinks (image swap, open→closed 120 ms→open). Looks toward the attacked road (pupil image variant chosen by bearing octant). Sinks: reverse, 400 ms. |

Map transitions use MapLibre's built-in ease, not `BtEasing`. That is acceptable for ambient. Timing that must match
`BtEasing` belongs to overlay heroes.

**Colors.** Rings `waterLine` (light `#6F97A2`, dark `#2E5564`) mixed 50% toward `playInk`. Eye as in 3.1.
**Light/Dark.** Same roles. On Dark the eye's paper is `surfaceContainerHigh` with a 1px `playInk` edge, so it doesn't glare.
**Reduced.** One static ring (r 20dp, α .45) and the eye open at α .7. No loop.
**Announce.** None on its own: the attack's announcement covers it. The Play list shows "Kraken near {road}".
**Haptics / Sound.** None.
**Budget.** 2 transition writes per 1.6 s + 4 eye writes per surfacing ⇒ < 2 property updates/s per anchor. Max 3 anchors.
After 60 s of map idle: the rings stop on a rest frame (r 20dp) and the eye stays down.
**Showcase.** `47.6625, -122.4985, z14` (water off Manitou Beach), state = attack active, time-warp ×4 for the eye cadence.

---

### 3.3 `ferry_crossing` — the boat on the real run, and the horn at Winslow

**Purpose / trigger.** Island life on the map. A ferry figure moves along the Eagle Harbor ↔ Seattle route on the
bundled approximate timetable (content config, so the owner can edit it without a release). The crossing is about 35 min.
Only the island end is visible: the camera bounds stop at −122.47, so the boat enters or leaves at the east edge of the
reachable chart. Arrival at the Winslow terminal triggers `ferry_horn`, which is an encounter only if the terminal is
in view at z ≥ 13.

**Where.** Boat + wake = (map) P4 (boat) and P1 (wake), updated at 1 Hz. Horn = (overlay) O1, encounter, 1600 ms.

**Geometry.** The route polyline is baked at build time from the OSM ferry route (`kind = ferry`, same data as the style's
`ferry` layer) into Play content as a GeoJSON LineString with line metrics. Do not query rendered tiles at runtime:
tiles may not be loaded, and the route must exist off-screen. Terminal ≈ `47.6227, -122.5106`.

**Run (ambient).**

| Element | Behaviour |
|---|---|
| Boat | Point on the route at `distance = timetableFraction(now) × length`. Rotates with the route bearing (`icon-rotate`, map-aligned) but the figure is drawn so it stays readable when pointing E or W: two mirrored images, chosen by the sign of the bearing's east component, so it never sails upside down. Speed is real (≈ 9 m/s ≈ 1.5 dp/s at z14), which is calm and believable. |
| Wake | A LineString of the last 400 m of route behind the boat, `line-gradient` over `line-progress` from `waterLine` α .7 at the stern to 0, width 6→1dp (zoom-interpolated). Rebuilt at 1 Hz. |
| Docked | Boat sits at the terminal slip; a 1px `playFog` steam wisp symbol fades in and out (transition 2.4 s). |

**`ferry_horn` timeline (overlay, 1600 ms).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–150 | Boat | Settles into the slip: overlay copy of the boat nudges 4dp forward and back (follow-through) | `overshootSettle`, `short3` |
| 100–700 | Steam | 3 puffs (`playFog` light / `playInk` α .25 dark) rise 18dp from the stack, scale .6→1.2, α .8→0; stagger 120 | `standardDecelerate`, `long4` |
| 200–1200 | Horn arcs | 3 sound arcs ")))" (70° wedge, `secondary` 1.5dp) expand from the stack toward the town (west), 6→40dp, α .9→0; stagger 160 | `emphasizedDecelerate`, `extraLong2` |
| 1200–1600 | Handoff | Overlay boat fades to the layer boat (identical frame) | `standard`, `medium4` |

**Colors.** Hull paper fill + `playInk` contour; a `secondary` stripe along the hull (teal, deliberately **not** a
copy of any operator's livery); wheelhouse windows `playLamp` after local sunset. Wake `waterLine`.
**Light/Dark.** Dark: hull fill `surfaceContainerHigh`, windows always lit, wake in `waterLine` dark (`#2E5564`) at α .9.
**Reduced.** The boat jumps in 1 Hz position steps (it already does). No wake gradient animation (static 200 m wake).
Horn: no arcs, no steam; announcement only.
**Announce.** Horn, polite, only when the terminal is in view: "The ferry has arrived at Winslow."
The map node's accessibility text gains "Next ferry leaves Winslow at {time}" when Play is on.
**Haptics.** None for the run; `tick` on the horn.
**Sound.** Horn: one long, soft, low blast (1.2 s). Never at night hours (§1.9).
**Budget.** Layer: 2 GeoJSON updates/s (boat point + 400 m wake) ≈ 0.1 ms CPU. Overlay horn: about 10 draw calls.
Paused when the route bbox is off-screen (the boat's position is still computed when shown, so there is nothing to catch up).
**Showcase.** Eagle Harbor approach `47.6195, -122.4990, z14`, time-warp ×60 (a crossing in 35 s), horn at arrival.
Terminal close-up `47.6227, -122.5106, z16`, state = arriving, t = 0 at horn.

---

### 3.4 `frog_rock` — the painted frog at Phelps Rd NE & Madison Ave NE

**Purpose / trigger.** A beloved local landmark as a friendly lore stamp. It is always present when Play is on. Idle blink.
Hop + croak when the walker comes within 60 m (recording; trigger from the shoulder, never require standing in the
road) or on tap. Cooldown 10 min on approach, 3 s on tap.

**Location.** `47.69591, -122.52353` (intersection vertex of `road-phelps-road-northeast-1` ×
`road-north-madison-avenue-northeast-1` in `data/roads`). The stamp is offset 8 m off-carriageway, onto the verge.

**Where.** Stamp + blink = (map) P4 (image swap). Hop + croak = (overlay) O1 encounter, 1800 ms.

**Storyboard.**
```
  idle        squash        hop (apex)        land          croak
  (◕‿◕)       (◕‿◕)          (◕‿◕)            (◕‿◕)         (◕‿◕) )  )
  /▔▔\        /▁▁▁\           /\/\            /▁▁▁\         /▔▔\ ) )
 ▔▔▔▔▔▔     ▔▔▔▔▔▔▔          · ·  ←shadow    ▔▔▔▔▔▔▔       ▔▔▔▔▔▔  rings
```

**Timeline (hop + croak).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–150 | Frog | Anticipation squash: scaleY 1→.85, scaleX 1→1.08 (pivot = feet) | `emphasizedAccelerate`, `short3` |
| 150–450 | Frog | Rise −14dp (z16 scale), stretch scaleY 1.1; shadow shrinks to 60% and fades α .18→.08 | `emphasizedDecelerate`, `medium2` |
| 450–650 | Frog | Fall back | `standardAccelerate`, `short4` |
| 650–750 | Frog | Land squash 1→.9→1, `tick` at 650 | `overshootSettle`, `short2` |
| 900–1100 | Throat | Paper throat sac inflates (ellipse 0→7dp) and deflates | `standard`, `short4` |
| 1000–1800 | Croak rings | 2 arcs (`secondary`, 1.25dp) expand 8→36dp, α .8→0, stagger 200 | `emphasizedDecelerate`, `extraLong2` |
| idle | Blink | Every 6–10 s (seeded): open→half→closed→open images, 120/80/120 ms (layer image swap) | step |

**Colors.** Body `playInk` contour; fill: a muted lichen green made from `secondaryContainer` (teal family, *not* a new
green). Spots: hatching. Eyes paper + ink pupil. Rock: `tertiaryContainer` with hatching on the SE side.
**Light/Dark.** Dark: fill `secondaryContainer` dark `#224D56`, rock `tertiaryContainer` dark `#554525`.
**Reduced.** No hop. The stamp swaps to the "croaking" image (sac out) for 1.2 s, then back. No rings.
**Announce.** Approach, polite: "Frog Rock says hello." First visit adds: "Plus {n} points." The tap opens the lore card
(the card's own copy is in the product spec).
**Haptics.** `tick` on landing; `confirm` on first-visit points.
**Sound.** A single soft "ribbit" (0.5 s), kid-friendly.
**Budget.** Overlay: 1 frog image (`ImageVector` → cached `Painter`), 1 shadow, 2 arcs ≈ 6 draw calls, about 0.2 ms.
The blink costs ≈ 0.4 layout updates/s, and only at z ≥ 15.
**Showcase.** `47.69591, -122.52353, z17`, state = present → engaged (hop at t=0). Key frames 0 / 300 / 700 / 1100.

---

### 3.5 `grand_forest_creatures` — owl, coyote, woodpecker, salamander

**Purpose / trigger.** Glimpses that reward attention in the Grand Forest (and, later, other wooded preserves). A glimpse
is a short encounter (≤ 1.6 s) near the walker or in view at z ≥ 15 inside a forest polygon. You "spot" it by tapping
within its trace window (10 min), or by walking past while recording, which auto-spots it. **Tracks** are the persistent
part: prints appear ahead of the walker along the trail they are on, inviting them to follow, then fade.

Frequency (render-side cap; scheduling decides the rest): island-wide ≤ 1 glimpse per 90 s while walking, ≤ 1 per 5 min
while browsing. Time of day picks the species: owl at dusk and dark, woodpecker in the morning, salamander only after
rain (§3.15), coyote at dawn and dusk.

**Where.** Glimpse figures = (overlay) O1 encounter. Traces and tracks = (map) P2 (tracks under status lines, over land)
and P4 (trace marker).

| Variant | Glimpse (overlay) | Trace (map, 10 min) |
|---|---|---|
| `owl` | Two `playLamp` eyes with ink brows open in the canopy 20–40 m off the trail: lids open 0–200 (`emphasizedDecelerate`), hold 800, one slow blink 1000–1240, close 1300–1500. A 20dp ring of glow (α .5). | A feather stamp (ink, hatched vane) at the spot. |
| `coyote` | Ink silhouette (tail low, side view, 36dp) trots across a gap in the trees for 1400 ms along a short seeded path (12–20 m) perpendicular to the trail, 4-frame trot cycle at 8 fps (a deliberate *engraved flip-book*, not smooth). It fades in and out over the first and last 200 ms. | 6 paw-print stamps along its path (P2). |
| `woodpecker` | A small ink bird (18dp) on a trunk glyph; head pecks 3× (each 0→6° in 60 ms and back), with "tok" marks (two 4dp ticks) flashing at the bill. | A trunk stamp with 3 hatched holes. |
| `salamander` | A small ink salamander (24dp) scuttles 4 m across the trail in 900 ms with a sinuous body: the spine is a path whose control points follow a travelling sine, amplitude 3dp, 2 waves. **Not orange** (Ensatina's real colour = the nearly-done hue): `tertiaryContainer` fill + ink spots. | A wiggle track (P2, ink dotted S-curve). |

**Tracks along your trail (map, P2).** When a glimpse is spotted *or* when recording on a forest trail with an active
creature, the next 30–60 m of the current trail gets prints: GeoJSON points every 2.5 m (alternating ±0.6 m offset
left and right), with properties `seq` and `bearing`. They reveal with the **expression clock** (§4.2):
`icon-opacity = clamp((tick − seq)/4, 0, 1) × clamp((seq + 24 − tick)/6, 0, 1)` at 8 Hz. That makes a travelling
window of prints ≈ 15 m long that moves ahead at walking pace, then dissolves. Rotation = bearing (map-aligned).
Prints are `playInk` at α .7, 6dp at z16.

**Colors.** Ink figures; owl eyes `playLamp`; coyote fill `tertiaryContainer`; feathers hatch only.
**Light/Dark.** Dark: owl eye glow α .75 (the best-looking variant at night). Coyote fill `tertiaryContainer` dark.
**Reduced.** No glimpse motion: the trace stamp appears directly with a `short3` crossfade. Tracks show all at once
(static, α .7) for 20 m ahead.
**Announce.** Polite: "An owl is watching near {trail name}. Tap to spot it." Then "You spotted an owl. Plus {n} points."
The custom action on the map node lists active traces.
**Haptics.** Woodpecker: `ticks` ×3 synced to the pecks. Spot: `confirm`. Others: none on the glimpse (surprise without buzzing).
**Sound.** Owl: two soft hoots. Woodpecker: three taps. Coyote and salamander: silent. Kid-friendly, no howls.
**Budget.** Overlay: ≤ 1 glimpse at a time, ≤ 8 draw calls (coyote frames are prebuilt `ImageVector`s). Tracks: 1
paint-expression update at 8 Hz for ≤ 24 points, only while the window moves, then stop.
**Showcase.** Grand Forest West `47.65405, -122.56034, z16` (owl, dusk theme). Grand Forest East `47.65312, -122.54474, z16`
(coyote, tracks). Time-of-day override in the showcase.

---

### 3.6 `prues_house` — a warm window (generic until the owner confirms)

**Status: BLOCKED on owner input.** The brief says "Prue's house (owner's words; confirm what/where)". The design stays
generic, and this effect may **not** ship pinned to any real residence until the owner confirms what it is, where it is,
and that featuring it is welcome (privacy, and "nothing that lures onto private land"). Triggers must fire from public
right-of-way only.

**Purpose / trigger.** A cosy lore point: a small engraved house whose window glows after sunset and whose door opens
when you come near (≤ 80 m from public road/trail) or tap. If it turns out to be a story or legend rather than a
building, the same figure works as a "story house" in a park.

**Where.** House stamp + window glow = (map) P4 (stamp) + P2 (glow, a blurred `CircleLayer` under the stamp). Door
opening = (overlay) O1 encounter, 1200 ms.

**Timeline.**

| t | Element | Motion | Easing |
|---|---|---|---|
| dusk ramp (layer) | Window glow | `circle-opacity` 0→.6 over 2 s (paint transition) at local sunset (content clock) | MapLibre ease |
| idle (z ≥ 15, layer) | Lamp | A flicker of α .55↔.65, irregular (3 seeded keyframes per 4 s) | MapLibre ease |
| 0–100 | Door | Anticipation: door nudges 1dp in | `emphasizedAccelerate`, `short2` |
| 100–500 | Door | Swings open: door panel scaleX 1→.15 about its hinge edge, with interior `playLamp` spilling | `emphasizedDecelerate`, `medium4` |
| 300–900 | Light spill | A trapezoid of `playLamp` α .5→.3 falls onto the ground SE of the door, length 0→14dp | `standardDecelerate`, `long4` |
| 900–1200 | Settle | Door eases to 20% open and stays until you leave the radius (then closes over 400) | `overshootSettle`, `medium2` |

**Colors.** Walls paper, roof `tertiaryContainer` hatched, ink contours, windows/door light `playLamp`.
**Light/Dark.** Light: glow α max .45 (lamp is pale on paper; the ink outline carries it). Dark: glow α .6, radius 1.6×.
**Reduced.** No swing: the door image swaps to open; static glow.
**Announce.** Polite: "A light is on at {place name}." (placeholder until confirmed). Tap opens the lore card.
**Haptics.** `tick` when the door opens. **Sound.** A soft latch click + warm room tone (0.8 s).
**Budget.** Layer: ≤ 1 update/s. Overlay: 4 draw calls.
**Showcase.** Placeholder location (any public park building, e.g. ≈ `47.6260, -122.5210, z16`), state = dusk, approach at t=0.

---

### 3.7 `treasure_glint` + `treasure_found` — sea glass

**Purpose / trigger.** Treasures are **sea glass** (Bainbridge beaches are full of it; it is also the Play colour). Present
treasures glint now and then, so a scan of the map finds them. Found = the walker reaches it (≤ 25 m while recording)
or taps it while within range.

**Where.** Stamp + glint = (map) P4. The glint runs fully on the GPU through the expression clock: zero Compose. Found
burst = (overlay) O1, H1, 900 ms, then `points_pop`.

**Glint (ambient, map).**
- Stamp: a faceted pebble (ink contour, sea-glass fill α .85, two paper facet highlights).
- Sparkle layer: a second symbol layer with a 4-point star image (`playLamp` core, ink 1px edge) at the stamp's upper-left
  facet. Opacity is driven by `tick` (10 Hz) and a per-feature seeded `phase` and `period` (4–7 s):
  `icon-opacity = max(0, 1 − |((tick·100 + phase) % period) − 300| / 300)` ⇒ a 600 ms triangle flash per period.
  The size pulse is a second image (star rotated 45°, 70% size) at half the opacity: a cheap "twinkle" without layout updates.
- Visible from z12.5. **Max 6 glinting in view.** The rest stay matte. Feature-state or a filter on a precomputed
  "nearest 6 to the camera centre" list rebuilds on camera idle.

**`treasure_found` timeline (overlay, 900 ms).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–100 | Pebble | Anticipation: scale 1→.9 | `emphasizedAccelerate`, `short2` |
| 100–300 | Pebble | Pops: scale .9→1.3, rises 10dp, rotates −8° | `emphasizedDecelerate`, `short4` |
| 200–650 | Shards | 10 sea-glass shards (3–5dp irregular quads, 3 tints of `playSeaGlass` at α .9/.7/.5) and 4 `playLamp` stars burst radially (`ballistic`, upward bias) | `ballistic`, `long1` |
| 250–700 | Ring | One `waterLine` ring 8→44dp, α .8→0 | `emphasizedDecelerate`, `long1` |
| 300–600 | Pebble | Shrinks to 0 while moving toward the ledger pill; the flight continues as `points_pop` | `emphasizedAccelerate`, `medium2` |
| 600–900 | Spent stamp | The map stamp crossfades to spent (ink ring α .4) | `standard`, `medium2` |

**Colors.** `playSeaGlass`, `playLamp` (sparkle core), `playInk`, `waterLine`.
**Light/Dark.** Dark: sea glass `#63DCC6`, which glows naturally on dark; the sparkle has a soft glow α .6.
**Reduced.** No glints (matte stamps). Found: stamp crossfades to spent, the pill number updates.
**Announce.** Assertive (resolve): "Found: sea glass at {place}. Plus {n} points." Presence is not announced; the Play
list has "{n} treasures nearby".
**Haptics.** `confirm` at 100. **Sound.** Glass clink (two notes, 0.4 s).
**Budget.** Glint: 1 paint update per 100 ms for 2 layers (only while ≥ 1 glinting treasure is in view; stops at map idle
60 s). Found: 16 particles + 3 shapes ≈ 0.6 ms.
**Showcase.** Fay Bainbridge Park shore ≈ `47.7010, -122.5080, z15.5`, 5 treasures seeded, glint time-warp ×1; found at
t=0 on the nearest.

---

### 3.8 `easter_egg_reveal` — the chart peels back

**Purpose / trigger.** Easter eggs are invisible until their condition holds (a time, a sequence, a place visited twice,
a riddle chain). On reveal, the chart itself cracks and a small **inset cartouche** (the framed detail map of old charts)
unfolds, showing the egg's figure. After that the item is a normal stamp.

**Where.** (overlay) O1, H1, 1600 ms, then a (map) P4 stamp.

**Storyboard.**
```
  t=0           t=350            t=800               t=1300              end
   ·           ╲ │ ╱            ┌──╲─╱──┐          ╔═══════════╗         [✦]  ← stamp
              ─ ✶ ─             │ ░lamp░ │          ║   ✦ egg   ║
               ╱ │ ╲            └──╱─╲──┘          ╚═══════════╝
           hairline cracks     light seeps        cartouche unfolds
```

**Timeline.**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–120 | Ground | A 2dp jolt (translate 0→2→0dp) of a paper patch: anticipation | `standard`, `short2` |
| 0–400 | Cracks | 5 ink hairlines (1.25dp) branch out from the anchor, drawn with `getSegment` and seeded jaggedness, 10–26dp | `emphasizedDecelerate`, `medium4` |
| 300–700 | Light | `playLamp` glow from the cracks, radius 0→24dp, α 0→.7 | `standardDecelerate`, `medium4` |
| 600–1200 | Cartouche | A 72×48dp paper cartouche (ink double rule, corner flourishes) unfolds: scaleY 0→1 with a fold line, 4° skew settling to 0 | `emphasizedDecelerate`, `long4`, then `overshootSettle` |
| 900–1300 | Egg figure | Draws on inside the cartouche (`getSegment`), then fills `playSeaGlass` α .85 | `emphasizedDecelerate`, `medium4` |
| 1300–1600 | Handoff | Cartouche folds down into the P4 stamp (scale → stamp size, position → anchor) | `emphasizedAccelerate`, `medium2` |

**Colors.** Paper, `playInk`, `playLamp`, `playSeaGlass`.
**Light/Dark.** Dark: cartouche paper = `surfaceContainerHigh`, rules `playInk` dark; glow α .8.
**Reduced.** The stamp appears with a `short3` crossfade. The cartouche content is shown in the sheet that opens.
**Announce.** Assertive: "Something hidden appeared at {place}. Plus {n} points."
**Haptics.** `tick` at 0, `confirm` at 900. **Sound.** A paper crackle + a chime (0.9 s).
**Budget.** About 14 draw calls, with no particles. ≈ 0.5 ms.
**Showcase.** Grand Forest centre `47.6550, -122.5480, z16`, state = condition met at t=0.

---

### 3.9 `riddle_marker` + `riddle_light` — the question lantern

**Purpose / trigger.** A riddle is a **pin**: a ship's lantern on a short post with a "?" etched on the glass, unlit.
Solve (in the sheet) → the lantern lights and stays lit (the place now "remembers" you). Wrong answer → it shakes.

**Where.** Lantern = (map) P4 pin. Idle sway = layer `icon-rotate`, 2 keyframe transitions per period. Lighting =
(overlay) O1 H1 1100 ms, then the lit (map) image. Wrong = (sheet) micro on the lantern thumbnail in the sheet,
**and** (overlay) if the map is visible.

**Timeline — `riddle_light`.**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–150 | Wick | A spark: 3 tiny `playLamp` points jump 2–4dp | `ballistic`, `short3` |
| 150–500 | Flame | Teardrop of `playLamp` grows 0→9dp tall with an ink outline; the "?" fades out (α 1→0) | `emphasizedDecelerate`, `medium3` |
| 300–900 | Glow | Radial `playLamp`, 0→28dp (z16), α 0→.6 (dark .8) | `standardDecelerate`, `long4` |
| 500–1100 | Rays | 8 short ink rays (4dp) flick out and fade, stagger 30 | `emphasizedDecelerate`, `long4` |
| 900–1100 | Handoff | → lit layer image + P2 glow circle | — |
| idle (lit) | Flicker | Glow α .55↔.65, 3 seeded keyframes per 4 s; z ≥ 15 only | MapLibre ease |
| idle (unlit) | Sway | `icon-rotate` ±3° about the post top, period 3200, seeded phase; z ≥ 15 only | MapLibre ease |

**Wrong answer (`riddle_reject`, 360 ms).** Lantern translateX 0→−4→4→−3→3→0dp (3 cycles), `reject` haptic. **No red,
no ✕.** The sheet says "Not quite" (product copy).

**Colors.** Ink lantern frame, glass paper α .6, flame and glow `playLamp`.
**Light/Dark.** Lit lanterns are the best night feature: dark glow α .8, radius 1.6×.
**Reduced.** Lit image swaps in; static glow; wrong answer = sheet text + haptic only.
**Announce.** Assertive: "Riddle solved. The lantern at {place} is lit. Plus {n} points." Wrong, polite: "Not quite. Try again."
**Haptics.** `confirm` at 150 (light); `reject` (wrong). **Sound.** A match strike + soft whoomp (0.7 s).
**Budget.** Overlay ≈ 14 draw calls. Idle: ≤ 0.6 property updates/s per lantern; max 6 animated lanterns in view.
**Showcase.** Winslow waterfront ≈ `47.6245, -122.5195, z16`, unlit → solve at t=0; second preset: dark theme, 3 lit.

---

### 3.10 `obstacle_generic` — fallen tree, tide cut, fog bank

**Purpose / trigger.** Content obstacles that block a stretch until cleared (walk around, walk at the right time, or
solve). Three variants share one lifecycle: `obstacle_drop` (intro, H2), idle (map), `obstacle_clear` (resolve, H1).

**Safety tie-in.** `tide_cut` appears only when the real predicted tide is high (bundled tide table). The game obstacle
**agrees with real safety**: it never asks you to cross a beach at high tide.

| Variant | Present (map) | Drop intro (overlay) | Clear (overlay → map) |
|---|---|---|---|
| `fallen_tree` | P4 stamp: an engraved log lying **across** the line (rotation = line bearing + 90°), 36dp at z16, root plate hatched. | 0–200 the log falls from 20dp above (`emphasizedAccelerate`), 200–300 lands with `overshootSettle` and a `thud`, 200–600 4 dust puffs (`playFog` light / `playInk` α .2 dark) roll sideways (`standardDecelerate`). 600 ms total. | 0–150 a saw kerf line draws across the middle. 150–650 two halves roll apart 8dp and rotate ±12°. 400–900 they fade to the spent stamp (two short ink stubs beside the path). 900 ms. |
| `tide_cut` | P2: diagonal `waterLine` hatching (fill-pattern) over the affected beach stretch's buffer polygon, under the status line, and 2 wavelets that lap via `fill-translate` keyframes (±1.5dp, 4 s). | 0–800 the hatch polygon "floods" in from the waterside edge: `fill-opacity` transition .0→.6 plus a water-line ring sweeping across (overlay). | Tide recedes: the hatch slides seaward (`fill-translate` 0→6dp, transition 1200) and fades; one water-line ring settles at the shore. 1400 ms. |
| `fog_bank` | P2: 2–3 fog patch polygons (`playFog`, `fill-pattern` = soft noise sprite) over the stretch, drifting by `fill-translate` keyframes (4dp / 8 s). | 0–1200 the patches thicken (opacity .0→ max) and drift in from the water side. | **Fog lift** (the one shader showcase, §4.4): 0–1400 an overlay fog quad over the stretch dissolves from the walker's position outward (noise threshold rising), while the P2 patches fade out underneath. Fallback: two noise-bitmap layers scaling up and fading. |

**Colors.** Ink, `tertiaryContainer` (log), `waterLine` (tide), `playFog`.
**Light/Dark.** Fog dark `#7D9095` α ≤ .25, as §1.2 (status halos stay above it). Log fill dark `tertiaryContainer` dark.
**Reduced.** Present states are static. Drop/clear = `short3` crossfade between present and spent.
**Announce.** Drop, polite: "A fallen tree blocks part of {trail name}." / "High tide covers part of {trail name} until {time}."
/ "Fog has rolled over {trail name}." Clear, assertive: "{Obstacle} cleared on {trail name}. Plus {n} points."
**Haptics.** `thud` on the log landing and the halves parting; `confirm` on any clear. **Sound.** A wood creak / water
wash / soft wind (≤ 1.2 s).
**Budget.** Overlay ≤ 12 draw calls (log) / 1 shader quad (fog). Layer: tide and fog ≤ 0.25 updates/s each. Max 4
obstacles animated in view.
**Showcase.** Log on Gazzam Lake Loop `47.61791, -122.56445, z16.5`. Tide on Fay Bainbridge beach ≈ `47.7020, -122.5070,
z16` (tide override). Fog on Fort Ward ≈ `47.5949, -122.5239, z15.5`.

---

### 3.11 `race_track` — course, countdown, finish tape (+ ghost)

**Purpose / trigger.** Races are timed courses along the network (solo against your best, or against a community pace).
The course must read as **"this way, go"** without ever looking like a status. A race is armed in the sheet. The
countdown starts when the walker is at the start (≤ 30 m) and taps Start. It finishes on crossing the finish.

**Where.**

| Part | Where | Notes |
|---|---|---|
| `race_course` | (map) P3 rails + P4 chevrons | Rails: two `playInk` 1px hairlines offset ±(status width/2 + 4px) along the course (`line-offset`), α .8, under the status line. Chevrons: precomputed points every 25 m with `seq` + `bearing`, ink "›" images, map-aligned. Visibility thinned by zoom (`seq % step == 0`, step = 4/2/1 at z13/14/16). Marquee: `icon-opacity = .35 + .65·[(seq − tick) % 4 == 0]` at 6 Hz (chase lights in the race direction). Marquee only while armed or running. |
| `race_countdown` | (overlay) at the start point, H2 | Also mirrored in the sheet for screen readers. |
| `race_ghost` | (map) P4 | A hollow `playInk` ring (10dp, 2dp stroke) with a paper centre, moving along the course at the target pace (GeoJSON point at 2 Hz). Never a filled dot, never blue (blue = you). |
| `race_finish` | (overlay) at the finish, H1 | Tape + pennants. |

**Countdown timeline (3000 ms + GO).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0, 1000, 2000 | Digit 3 / 2 / 1 | System font + `tnum` (`statLive`, ux.md §1.3: changing numbers never in Fraunces), 34sp on a 64dp `mapChrome` disc. Each digit enters scale .7→1, α 0→1 (`emphasizedDecelerate`, `short4`) and exits α→0 at +850 (`short3`). `tick` on each. | as noted |
| 0–3000 | Ring | A 2.5dp `playInk` ring around the disc depletes clockwise from 12 o'clock, one full ring per digit | linear |
| 3000–3400 | GO | The disc flips to the "GO" glyph (an ink pennant), scale 1→1.15→1 (`overshootSettle`). `confirm`. The chevron marquee speeds to 10 Hz for 2 s. | `overshootSettle`, `medium4` |

Cancel stays available throughout (sheet button). The overlay never blocks the map.

**Finish timeline (`race_finish`, 1500 ms).**

| t | Element | Motion | Easing |
|---|---|---|---|
| idle (armed) | Tape | Between two ink posts across the course at the finish; tape sags (catenary) and flutters ±1dp (overlay **only within 150 m**, else a P4 stamp of the tape) | `sway` 1800 |
| 0–120 | Tape | Stretches: the sag goes to 0, anticipation | `emphasizedAccelerate`, `short2` |
| 120–200 | Tape | Snaps: splits at the walker's crossing point. `thud`. | step |
| 200–900 | Tape halves | Whip back to their posts with follow-through (damped spring, 2 overshoots), then hang | spring (stiffness 300, damping .45) |
| 300–1100 | Pennants | 6 small ink-outlined pennants with sea-glass / paper fills (`ballistic` up, then they flutter down with ±15° rotation) | `ballistic`, `extraLong2` |
| 600–1500 | Time | The time is shown in the sheet / pill (system `tnum`), not on the map | — |

**Colors.** `playInk` rails, chevrons, tape; `playSeaGlass` pennant fills; `mapChrome` disc.
**Light/Dark.** Same roles. On Dark the rails are `playInk` dark (pale) at α .7.
**Reduced.** Chevrons static (all at α .7); countdown digits swap without scale; GO = text + haptic; finish = tape shown
broken (static), no pennants.
**Announce.** Assertive: "Race starts in 3, 2, 1. Go." (one utterance at t=0, so TalkBack/VoiceOver timing can't drift),
"Finished in {mm:ss}. {Personal best / {n} seconds behind your best}." Polite mid-race splits come from the rules spec.
**Haptics.** `tick` per digit, `confirm` at GO, `thud` at the tape, `confirm` at the result.
**Sound.** Three soft wood-block ticks + a ship's bell at GO; a bell double-strike at the finish.
**Budget.** Course: ≤ 200 chevron points, 1 paint update per 166 ms while running. Ghost: 2 GeoJSON updates/s.
Countdown overlay: 3 draw calls. Finish: ≈ 12. While a race runs, every other H2 is suppressed and E is ≤ 1 per 60 s.
**Showcase.** Course around Gazzam Lake `47.6180, -122.5645, z15`, state = armed (marquee). Countdown at the start, z16.
Finish at the end, z16.5, crossing at t=0. Ghost with pace override.

---

### 3.12 `points_pop` — "+N" into the ledger pill

**Purpose / trigger.** Every earn event. The number comes from where the points were earned and flies into the ledger
pill (top-right, `mapChrome`, part of O2 chrome when Play is on). Off-map earns (sheet actions) start at the sheet's
action button.

**Where.** (overlay) O1 → (sheet/chrome) pill. U class.

**Timeline (900 ms).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–200 | "+N" | Appears at the origin (geo-projected): scale .6→1, α 0→1, rises 16dp. Text: `labelLarge` system font `tnum`, `onMapChrome` on a `mapChrome` capsule; the "+" is a `playSeaGlass` glyph | `emphasizedDecelerate`, `short4` |
| 200–300 | Hold | — | — |
| 300–750 | Flight | Along a quadratic Bézier to the pill (control point 30% up and toward the screen centre); scale 1→.7 | `emphasizedAccelerate`, `long1` |
| 700–900 | Pill | Bump: scale 1→1.08→1 (`overshootSettle`); the number rolls old→new (vertical digit roll per changed digit, 150 ms, `standard`, system `tnum` so it never reflows) | `overshootSettle`, `short4` |

**Coalescing.** A pop within 400 ms of another merges into it (the sum). Max 3 in flight; the 4th joins the last.
While the pill is hidden (sheet expanded), the flight targets the sheet's ledger row instead.

**Colors.** `mapChrome`, `onMapChrome`, `playSeaGlass` "+". Never `positive` (spruce) on the map.
**Light/Dark.** Token-driven.
**Reduced.** No flight: the pill crossfades to the new number (`short3`) and shows "+N" beside it for 1.5 s.
**Announce.** Polite, coalesced per 4 s: "Plus {n} points. {total} total." Skipped if the resolving effect already said the points.
**Haptics.** None (the resolve already confirmed). **Sound.** A soft coin-on-wood tick (0.2 s), coalesced too.
**Budget.** ≤ 3 text layouts (cached `TextLayoutResult`, measured once per value) + capsules ≈ 0.3 ms.
**Showcase.** Any map preset; buttons for +5, +20, a burst of 5 pops in 300 ms (coalescing test), and pill hidden.

---

### 3.13 `streak_float_line` — glass floats on a net line (not a flame)

**Purpose / trigger.** A Bainbridge streak motif. Old Japanese **glass fishing floats** wash up on Pacific Northwest beaches
and are prized finds. The streak is a net line with one glass float tied on for each day you played. It is chart-room,
local, and gentle.

**Where.** (sheet) in the ledger sheet header and on Me (Play card). Never on the map.

**Anatomy.** A rope (ink, 1.5dp, slight catenary between ties) across the card; floats are 14dp circles with ink
contour, `playSeaGlass` fill α .55, a paper highlight crescent, and a netting cross-hatch (detail weight). Today's tie
point is at the right end. Every 7th day a **knot** (a small ink bend) joins the floats into a bundle of 7.

**Timeline — day added (1100 ms, on first open of the sheet after the day's first earn).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–300 | Rope | Extends right by one float spacing (path grows) | `emphasizedDecelerate`, `medium2` |
| 200–500 | New float | Drops in from 12dp above on its tie line, swing ±6° → 0 | `overshootSettle`, `medium3` |
| 400–1000 | Fill | Sea-glass fill rises inside the float like water (clip rect 0→100% from the bottom) | `standardDecelerate`, `long4` |
| 900–1100 | Glint | One sparkle crosses the float (the same star as 3.7) | `standard`, `short4` |
| on 7th | Knot | The bend draws (`getSegment`), and the 7 floats nudge together 2dp, `tick` | `emphasizedDecelerate`, `long2` |

**Streak broken.** No sad state. The rope ends; the past floats stay (bundles keep their knots); a new rope starts
below them on the next play day. Copy says "Longest line: {n} days" (product copy). No float ever "breaks".

**Colors.** `playInk`, `playSeaGlass`, paper.
**Reduced.** Final state; no drop, no fill rise.
**Announce.** On sheet open (polite, in the header's merged node): "Play streak: {n} days. Longest: {m}."
**Haptics.** `tick` on the knot. **Sound.** Soft glass tock.
**Budget.** Sheet-only; ≤ 30 floats drawn (older bundles collapse to "×7" glyphs).
**Showcase.** Ledger sheet with day 1, day 6→7 (knot), day 23, and a broken-then-restarted line.

---

### 3.14 `proximity_ripple` — "something is near" (content need)

**Purpose / trigger.** While recording, when an undiscovered item comes within its hint radius (scheduling/rules
decide, typically 120 m), a water-line arc ripples from the walker's position toward it. This is a compass hint, not a
map pin (it keeps the treasure-hunt feel).

**Where.** (overlay) O1, E class, 1400 ms; anchored on the user location puck.

**Timeline.** 0–1200: 2 arcs (90° wedge, `waterLine` mixed 40% toward `playSeaGlass`, 2dp) expand from 14→64dp toward
the item's bearing, α .9→0, stagger 250 (`emphasizedDecelerate`, `extraLong1`). The arc centre stays on the puck
while the camera follows.

**Colors.** `waterLine` + `playSeaGlass`. **Reduced.** One static arc for 2 s, crossfade in and out.
**Announce.** Polite: "Something to find about {distance} {direction}." (e.g. "80 metres north-east"; units follow
locale/settings).
**Haptics.** `tick`. **Sound.** A single sonar-like water drop (0.3 s).
**Budget.** 2 arcs. Cooldown 60 s per item, one item pinged at a time, never during a race.
**Showcase.** Recording preset on Grand Forest West `47.65405, -122.56034, z16`, item 90 m NE.

---

### 3.15 `weather_tide_ambient` — tide lines, morning fog, rain streaks

**Purpose / trigger.** The island changes with the day. Subtle and togglable: **Play → Weather & tide effects** (default
on with Play; off under reduced motion and Low Power / Battery Saver). Inputs: the bundled tide prediction table and
sunrise/sunset (computed locally). Rain is optional and comes from the scheduling spec's weather source (a free, keyless
public feed for the island grid point, never the user's location). If that is not approved, rain stays debug/showcase-only.

| Sub-effect | Where | Visual | Motion |
|---|---|---|---|
| `tide_lines` | (map) P2 | Bundled beach shoreline polylines (content config) get 1–3 `waterLine` lines with **landward** `line-offset` proportional to tide height above mean (0–3 lines; spacing 3/6/9px at z15, zoom-interpolated). At low tide: 0 lines plus a pale `tertiaryContainer` α .25 "wet sand" band seaward of the shore. | The offset changes slowly with the real tide (recomputed every 10 min, 2 s paint transition). One "wash": the innermost line's offset breathes ±0.6px, period 6 s (2 keyframe transitions per period), z ≥ 15 only. |
| `morning_fog` | (map) P2 | 6–10 large soft fog patch polygons (precomputed island-scale blobs, mostly over water and low ground), `fill-pattern` = noise sprite, `playFog` α as §1.2. Window: local sunrise −30 min → +3 h, on days the schedule marks foggy. | Drift via `fill-translate` keyframes, 6dp per 12 s, eastward. Thins out over the last 30 min (opacity transition). |
| `rain_streaks` | (overlay) O1, **screen-space** (not geo) | ≤ 40 hairlines (1dp, 10–18dp long), `secondary` α .22 (dark .3), slanted 12°. | Fall at 600–900 dp/s; recycled when off-screen; 30 fps cap (draw every other frame). Off when the map is idle 20 s (static rain is worse than none). |

**Layering.** Tide and fog sit at P2: **under the status halos** (status contrast unchanged, §1.2) and under labels.
Rain is the one ambient overlay; that is why it is thin, sparse and low-alpha, and why it stops on idle.
**Reduced.** Tide lines static; fog static (no drift); no rain.
**Announce.** None on the map. The Play sheet header reads "High tide 2:14 pm · Fog until 9:30" when relevant.
**Haptics / Sound.** None (an optional rain loop at −30 dBFS only while the sheet is open is out of scope).
**Budget.** Tide: ≤ 0.35 updates/s. Fog: ≤ 0.2 updates/s. Rain: 40 lines ≈ 0.3 ms at 30 fps. All three stop with the
map idle rules (§5).
**Showcase.** Island `47.6450, -122.5350, z12` with fog on (the whole-island look); Fay Bainbridge beach ≈ `47.7020, -122.5070,
z16` with a tide slider; rain toggle on any preset.

---

### 3.16 `lore_stamp_ink` — the card gets stamped (content need)

**Purpose / trigger.** Opening a found, solved or visited item's lore card for the first time. The sheet's cartouche
title inks in and a dated rubber stamp thunks onto the card: "FOUND · 20 OCT 2026".

**Where.** (sheet) only.

**Timeline (700 ms).** 0–400: the cartouche double rule draws around the title (`getSegment`, `emphasizedDecelerate`).
300–450: the stamp drops from scale 1.25 (α 0→1) to 1.0, rotation −4° (`emphasizedAccelerate`, `short3`). 450–600:
`overshootSettle`, `tick` at 450. Stamp ink `playInk` α .8 with a grain mask (uneven ink, the paper grain bitmap).
The stamp date uses the system font (it is text).
**Reduced.** Final state. **Announce.** Part of the sheet's heading: "{Item}. Found 20 October 2026."
**Showcase.** The lore sheet for each content type, first open vs reopen.

---

### 3.17 `play_wake` — the island wakes up (opt-in moment)

**Purpose / trigger.** Once, when the user turns Play on (Settings, or the gentle offer after the first finished walk).
This is the showcase moment: ~3 s, skippable by any tap. It **lands the user on their own live map with Play in
place**. It replays from the debug showcase or Settings → Play → "Replay intro". Never on app launch.

**Where.** (moment) full-screen Compose over the live map (M), with an optional (d) fog shader for the fog lift. The
map underneath is moved to the island camera first (instant under reduced motion; `long1 emphasized` otherwise), so the
art's coastline and the map's coastline coincide when the paper lifts.

**Storyboard.**
```
 0 ms                     600 ms                    1300 ms                   1900 ms                   2600 ms → 3000
 ┌──────────────┐        ┌──────────────┐          ┌──────────────┐          ┌──────────────┐          ┌──────────────┐
 │ paper wash   │        │   ✣ compass  │          │ ((( island ))) ◌        │  ·✦  ·?  ·⚑  │          │ live map     │
 │ over the map │        │  rose inks   │          │ rings ripple  inward    │ stamps pop   │          │ + stamps     │
 │              │        │ The island   │          │ to the coast │          │ from Winslow │          │ + ledger pill│
 │   island     │        │  is awake    │          │  eye blinks ◉│          │ outward  ⛴)))│          │   +50        │
 └──────────────┘        └──────────────┘          └──────────────┘          └──────────────┘          └──────────────┘
```

**Timeline (3000 ms).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–300 | Paper wash | `surface` α 0→.82 over the map, paper grain at 6%; the map stays visible beneath (dimmed) | `standard`, `medium2` |
| 100–900 | Coastline | The island outline draws itself in `playInk` 1.6dp (the `IslandCelebrationArt` method: rings by length, `getSegment`), exactly over the real coast (same camera projection) | `emphasizedDecelerate`, `extraLong2` |
| 300–1100 | Compass rose | A 56dp engraved rose (8 points, hatched alternate points) inks in at the NE water (top-right of the island), rotating −20°→0° | `emphasizedDecelerate`, `extraLong2` |
| 500–900 | Title | "The island is awake" (`headlineMedium` Fraunces), +8dp rise, α 0→1; subtitle (`bodyMedium`) 700–1000 | `emphasizedDecelerate`, `medium4` |
| 900–1700 | Water lines | 3 water-line rings **converge** from 36/24/12dp outside the coast *onto* it (the tide coming in), α .8→.3, stagger 120: inverse of the celebration | `emphasizedDecelerate`, `extraLong2` |
| 1200–1500 | Kraken eye | Surfaces once off the east shore, blinks, sinks (the lurk eye, overlay scale) | `emphasizedDecelerate`, `medium2` ×2 |
| 1300–2200 | Stamps | Every present Play item's glyph pops in (scale 0→1.15→1, `overshootSettle`, `short4` each), ordered by distance from the Winslow terminal: a wave spreading out from the ferry. Stagger = 600 ms × (distance rank / n), capped at 40 items (the rest appear at 2200 with no pop) | `overshootSettle` |
| 1700–2100 | Ferry | The horn arcs ")))" at Winslow (3.3 horn, overlay scale) | as 3.3 |
| 1900 | Haptic | `confirm` | — |
| 2200–2700 | Paper lifts | The wash fades α .82→0 **and** a fog-lift dissolve (shader, or the fallback: radial mask growing from Winslow) reveals the live map; the title fades out 2200–2500. The art's stamps crossfade into the real P4 stamps (same projected positions) | `emphasizedAccelerate`, `long2` |
| 2600–3000 | Ledger pill | Slides in at top-right (16dp, `emphasizedDecelerate`, `medium4`), then the welcome `points_pop` ("+50", rules spec) | 3.12 |

Skip: any tap at t < 2200 jumps to t = 2200 (the lift), so the end state is always reached through the lift, never a cut.

**Colors.** `surface` + grain, `playInk`, `waterLine`, `playSeaGlass` glyphs, paper.
**Light/Dark.** Dark: wash = `surface` dark (`#101A16`) α .85; coast ink pale; the rose gets a faint `playLamp` glow α .25
(night chart); the eye glows.
**Reduced.** One `short3` crossfade to a static card (title + rose + coast + rings at final positions) for 1.2 s,
then a `short3` crossfade to the live map with stamps and pill in place. Haptic as normal.
**Announce.** Assertive, on appear: "Play is on. Treasures, riddles, races and island lore are now on your map. Your
points ledger is at the top right. Plus 50 points." (product copy may reword). Focus then goes to the ledger pill.
**Haptics.** `confirm` at 1900. **Sound.** A soft horn + glass chime (1.4 s), only if sounds are on (they are off by
default, so normally silent).
**Budget.** This is the one moment allowed a heavier frame: ≤ 4 ms/frame on target (coast path ≈ 1.5k vertices,
pre-simplified to ≤ 600 at the island zoom; up to 40 glyphs; 3 rings). It runs once, then the clock stops.
**Showcase.** Camera = island home (`IslandCamera`, `47.645, -122.535, z12`), state = Play just enabled, 25 items
seeded. Key frames 300 / 900 / 1500 / 2000 / 2600 / 3000; skip-at-800 test.

---

## 4. Rendering approach per effect

### 4.1 Recommendation table

Approaches: **(a)** MapLibre layers (GeoJSON, expressions, paint transitions, the expression clock). **(b)** Compose
Canvas overlay projected from geo through the camera. **(c)** full-screen Compose moment. **(d)** runtime shader
(SkSL on iOS/Skia, AGSL on Android 33+). **(e)** Compose UI micro-animation in a sheet or chrome (no map).

| Effect | Approach | Why |
|---|---|---|
| `kraken_attack` | **a + b** (+ d optional for the ink bloom) | Rope and pool are persistent and zoom-true → (a). Tentacles need per-frame path growth and taper with BtEasing → (b), ≤ 2.6 s, then handed to (a). |
| `kraken_recede` | **b + a** | Mirror of the intro. |
| `kraken_lurk` | **a** | Runs for minutes; transitions on rings and eye are free. |
| `ferry_crossing` (run) | **a** | 1 Hz point + wake: real-time slow motion needs no frame clock. |
| `ferry_horn` | **b** | Short, rich, encounter. |
| `frog_rock` | **a** (stamp, blink) + **b** (hop/croak) | Squash and stretch needs per-frame transforms. |
| `grand_forest_creatures` | **b** (glimpses) + **a** (traces, tracks via the expression clock) | Glimpses are brief figures; tracks must stay under status lines. |
| `prues_house` | **a** (stamp, glow) + **b** (door) | |
| `treasure_glint` | **a** | GPU-side opacity math per feature; zero Compose work. |
| `treasure_found` | **b** | Burst + flight. |
| `easter_egg_reveal` | **b** → **a** | |
| `riddle_marker` / `riddle_light` | **a** (pin, sway, flicker) + **b** (lighting) | |
| `obstacle_generic` | **a** (present) + **b** (drop, clear) + **d** for the fog lift only | |
| `race_track` | **a** (rails, chevrons, ghost) + **b** (countdown, tape) | The marquee via the expression clock keeps chevrons zoom-correct. |
| `points_pop` | **b → e** | Starts geo-projected, ends in chrome. |
| `streak_float_line` | **e** | Sheet only. |
| `proximity_ripple` | **b** | Anchored to the puck; short. |
| `weather_tide_ambient` | **a** (tide, fog) + **b** (rain, screen-space) | Fog in (a) stays *under* status lines. That is why fog is not a shader overlay. |
| `lore_stamp_ink` | **e** | |
| `play_wake` | **c** (+ **d** optional for the lift) | |

### 4.2 (a) MapLibre layer techniques

Four patterns, cheapest first. All run inside `@MaplibreComposable` content of `TrailMap`.

1. **Static + zoom expressions.** Stamps, glyph↔stamp swaps, `icon-size` by zoom. Zero ongoing cost.
2. **Paint transitions ("keyframe transitions").** Set a paint target with `TransitionOptions(duration)`. MapLibre
   interpolates on the render thread at full frame rate with its own ease. The pattern is already used for selection
   dimming (`opacityTransition = fade` in `StatusLayers`). Use it for every ambient loop: ripples, eye, flicker, sway,
   wash, fog drift. A loop is just 2 keyframe writes per period. Transitionable paint properties include opacity,
   color, width, radius, `*-translate` and `line-offset`. **Layout** properties (`icon-size`, `icon-image`) are not
   transitionable: use image swaps for discrete changes (blinks).
3. **Expression clock.** One `tick: Int` state per FX layer group, incremented by the `FxClock` at that group's rate
   (6–10 Hz). Paint expressions combine `const(tick)` with per-feature properties (`seq`, `phase`, `period`). Each tick
   is one `setPaintProperty` call. No re-tiling, no Compose work beyond a tiny `@MaplibreComposable` that reads
   `tick`. Used for glints, tracks and the race marquee. Isolate the reader so a tick never recomposes `StatusLayers`.
4. **Small GeoJSON rebuilds.** For moving points and trims: the ferry (1 Hz), the ghost (2 Hz), the rope draw-on
   (8 steps). Geometry ≤ 400 vertices. The source uses `lineMetrics = true` where `line-gradient` is needed (wake).
   Never rebuild a large source per frame. `TrackThrottle` is the precedent.

Images: figures are authored once as `ImageVector` and rasterised to `ImageBitmap` per density for MapLibre (`@2x`,
`@3x`). Images are registered by id (`fx-frog-open`, `fx-frog-closed`, …). **Spike item:** confirm the maplibre-compose
0.20 API for runtime images (`image(...)` from a `Painter`/`ImageBitmap` in `SymbolLayer`) and for `fill-pattern` /
`line-pattern`. The fallback is bundled PNG sprites under `composeResources/files/fx/`.

### 4.3 (b) Compose overlay projected from geo

- Lives in `MaplibreMap(overlay = …)` next to `TrailMapOverlay`: `FxOverlay(state)` is composed **before** the chrome, so
  chrome stays on top.
- **Projection.** Bearing and tilt are always 0, so: `screen(geo) = centerPx + mercatorOffsetDp(camera.target, geo, zoom)`,
  where `centerPx` is the viewport centre adjusted by the camera padding. One `cameraPosition` read per frame, then pure
  math for every point (`mercatorOffsetDp` in `MapLogic.kt`). A once-per-session self-check compares it against
  `MapState.screenLocationFromPosition` at the anchor and logs drift > 1dp.
- **Camera swim.** If the overlay's camera snapshot lags the GL frame by one frame, figures "swim" during pans
  (≈ 16dp at 1000 dp/s). Rule: during an active gesture or camera animation, O1 figures fade to α 0 in `short3` and
  their layer counterparts (stamp/eye/rope) carry the scene. They return in `medium1` after the camera idles. Heroes that
  start during a gesture wait (≤ 1 s) or resolve to their end state. Measure in the spike; if lag is 0 frames, keep
  figures visible and drop this rule.
- **No recomposition per frame.** The clock value is read **inside the draw lambda** (`Canvas { val t = clock.value … }`
  or `drawWithCache`), so a frame invalidates draw only. Paths, `PathMeasure`, `Paint`, text layouts and particle arrays
  are preallocated per effect instance. No allocation in draw (no `listOf`, no lambdas, no `Path()` per frame).
- Overlay is `pointerInput`-transparent. Tappable Play items are hit-tested through `queryRenderedFeatures` on their
  P4 layers (ux.md §6.1 pattern), never via overlay hit boxes.

### 4.4 (d) Where a runtime shader truly earns its place

Only three visuals gain something that paths cannot give cheaply. **Each ships with a non-shader fallback, and the
fallback is the default until the shader is measured on target.**

| Visual | Why a shader | Shader | Fallback (all platforms) |
|---|---|---|---|
| Fog lift (`obstacle_generic` fog clear, `play_wake` lift) | An organic dissolving edge across a large area; with paths it's either blobby or expensive | fbm noise (3 octaves) thresholded by a moving radial front; half-res quad | Two tileable 256px noise bitmaps (`ImageShader`, `TileMode.Repeated`) drifting in parallax under a growing radial `BlendMode.DstOut` mask |
| Kraken ink bloom (`kraken_attack`) | Ink diffusing in water reads as *ink* only with domain-warped edges | Domain-warped noise on a radial falloff, `playInk` | 5 seeded overlapping circles with the noise-bitmap mask, `BlendMode.SrcOver` |
| Nothing else | Water rings, glints, flames, tentacles, rain, confetti: paths and particles look better (they are line art) and cost less | — | — |

Platform notes:
- **iOS** (Compose MP on Skia): `org.jetbrains.skia.RuntimeEffect.makeForShader(sksl)` → `RuntimeShaderBuilder` → `Shader`
  → `ShaderBrush`. Available on all supported iOS versions.
- **Android**: `android.graphics.RuntimeShader` (AGSL) is API 33+. minSdk is 26, so API 26–32 always uses the fallback.
  One shader source; AGSL and SkSL differ only in small dialect details, kept in an `expect/actual` `FxShaders` with
  a `isSupported` flag.
- Shaders never run in map layers (maplibre-compose has no custom GL layer). They only draw in O1/M, which is why fog
  *ambient* is (a) and only the transient fog *lift* uses (d).

### 4.5 (c) and (e)

- **(c)** follows `CelebrationScreen`: one `Animatable` clock, `phase()` helpers, `LaunchedEffect(reduced)`, the
  announcement on a merged heading node with `LiveRegionMode.Assertive`, decorative Canvas `clearAndSetSemantics {}`.
  `play_wake` is the only new (c). Play resolutions do not get full-screen moments (the celebration stays the pure app's
  moment; Play heroes stay on the map).
- **(e)** is plain Compose animation (`animateFloatAsState`, `Animatable`), with `BtMotion.tweenOrSnap` for reduced motion.

---

## 5. Performance budget and pausing (engine-wide)

Target device: a 2020 mid phone (Pixel 4a / Snapdragon 730G; iPhone SE 2 / A13). Measured with Macrobenchmark
`FrameTimingMetric` + Perfetto (Android) and Instruments (iOS) on the showcase presets.

| Budget | Value |
|---|---|
| O1 overlay draw | **≤ 2 ms/frame** p90 (≤ 4 ms during `play_wake`) |
| Overlay recompositions while animating | **0 per frame** (draw-only invalidation) |
| Overlay allocations per frame | 0 |
| Particles on screen | ≤ 40 total (hero ≤ 16, rain ≤ 40 *instead of* hero particles) |
| Overlay paths animating | ≤ 8 |
| Layer property updates | ambient ≤ 10/s total; hero ≤ 30/s total |
| GeoJSON rebuilds | ≤ 3/s total, each ≤ 400 vertices |
| Animated instances in view | glints ≤ 6, lanterns ≤ 6, obstacles ≤ 4, kraken anchors ≤ 3 |
| Shader quad | ≤ 1 at a time, half-res, ≤ 1.2 ms |

**Governor.** `FxDirector` tracks overlay draw time (moving average over 2 s). Above 3 ms it drops overlay ambient (rain)
and halves particle counts. Above 5 ms it turns all heroes into reduced variants for the session and logs
`fx.degraded` (local log only).

**Pause rules.**

| Condition | Effect |
|---|---|
| App background (lifecycle `ON_STOP`), screen off, Map tab not visible | `FxClock` stops; no expression ticks; no GeoJSON rebuilds. On return, positions (ferry, tide) are recomputed from wall time. Nothing "catches up" visibly. |
| Map idle 20 s (no touch, no camera move, no location fix change > 10 m) | O1 ambient settles to a rest frame and stops. |
| Map idle 60 s | Layer ambient loops (ripples, glints, sway, flicker, marquee, tide wash, fog drift) stop on a rest frame. The ferry keeps 1 Hz only while in view. |
| Recording | O1 ambient off; layer ambient ≤ 2 Hz; heroes allowed (§2). |
| Low Power (iOS) / Battery Saver (Android) | All ambient off; heroes use reduced variants; tide and fog static. |
| Reduced motion | §1.10. |
| Item off-screen | Not drawn, not ticked. A hero that fires off-screen resolves to its end state. |

---

## 6. Craft checklist (every effect, before ACCEPT)

- [ ] **Anticipation**: every hero motion has a wind-up of 100–400 ms (converge, squash, nudge, jolt).
- [ ] **Follow-through and overlapping action**: parts finish at different times (tentacles staggered 90 ms, tape halves
      spring, floats swing). Nothing stops on one frame all at once.
- [ ] **Settle**: final positions arrive with `overshootSettle` or a damped spring, never a hard stop. The end frame is a
      calm, screenshot-worthy composition.
- [ ] **Hand-off**: every overlay hero ends in a frame identical to its layer counterpart, then crossfades. No pop at the seam.
- [ ] **Input**: no effect blocks input for more than 300 ms, except `play_wake` (skippable) and the existing celebration.
      Overlay never intercepts touches.
- [ ] **One hero**: at most one H effect on screen; H2 intros ≥ 8 s apart; cooldowns per card respected.
- [ ] **Coverage first**: status lines readable at every frame (check frame captures at key frames, both themes,
      deutan sim). No Play layer between a status line and its halo. No Play colour on the network except the ink rope/rails.
- [ ] **Labels**: persistent Play drawing sits under `trail-label` / `place-label`. Only transient O1 heroes may overlap
      labels, for ≤ 3 s.
- [ ] **Chart physics**: north-up elevation figures, SE shadows, rain slanting right, up = north. No figure rotates with
      the camera (it can't), and line-bound stamps follow the line bearing.
- [ ] **Style**: ink contour + detail weights only, hatching for shade, ≤ 1 role fill, no gradients except light glows,
      grain only inside paper.
- [ ] **Both themes** checked; dark uses its own role values (not inverted light art).
- [ ] **Reduced motion** variant = a final frame or a static marker, plus the same announcement and haptic.
- [ ] **Announcement** text routed through `GoalVocabulary`, via `PlayAnnouncer`, with the right politeness; timed
      things have a 10-minute trace.
- [ ] **Haptics** at most one per 250 ms; none in background.
- [ ] **Sound** off by default, ≤ 1.5 s, ambient category, quiet hours.
- [ ] **Budget** measured on target at the showcase preset (p90 frame ≤ 2 ms overlay); pause rules verified (background,
      idle 20 s / 60 s, Low Power).
- [ ] **Kind**: no teeth, no chase, nothing that points at private land, high-tide beaches or shoulderless roads.
- [ ] **Pure mode**: with Play off, zero `fx-*` layers, no overlay, no clock. Screenshot-identical to today.

---

## 7. Debug showcase ("Play FX")

A new page in the existing showcase (`screen=showcase` launch option, `section=fx`): a full-bleed `TrailMap` with the
FX engine, plus a bottom control strip.

- Effect picker (every id in §3, plus sub-variants: owl/coyote/woodpecker/salamander, log/tide/fog,
  countdown/finish/ghost, recede).
- Preset camera (the card's showcase preset) and state (`dormant/present/intro/idle/engaged/resolved/spent`).
- Timeline scrubber (0 → effect end, ms), Play/Pause, speed ×0.25 / ×1, and **time-warp** for slow real-time things
  (ferry ×60, tide hours, glint cadence ×4).
- Theme System/Light/Dark (like `ShowcaseScreen`), map style Topo/Light/Dark, reduced-motion override, sounds toggle,
  fallback-vs-shader toggle, budget HUD (overlay ms p50/p90, layer updates/s, particles).
- Launch arguments for scripted screenshots, both platforms: `screen=showcase section=fx fx=<id> state=<state> t=<ms>
  theme=dark reduced=0 warp=60`. The output goes to `docs/play/screens/fx-<id>-<theme>-t<ms>.png` at the key frames
  listed per card.

---

## 8. Open items (need an owner or a spike)

1. **Prue's house**: what, where, and whether it may be featured (§3.6, blocked).
2. **Spike: maplibre-compose 0.20 capabilities**: runtime images from `ImageBitmap`/`Painter`, `fill-pattern`,
   `line-pattern`, `line-gradient` + `lineMetrics`, `*-translate`/`line-offset` transitions, feature-state support.
   Each technique in §4.2 names its fallback if one is missing.
3. **Spike: overlay camera lag** (§4.3 swim rule) on Android OpenGL and iOS Metal.
4. **Rain data source**: needs the scheduling spec's approval (free, keyless, island grid point only), otherwise
   showcase-only.
5. **Frog Rock depiction**: an engraved generic frog, not a reproduction of the current paint job (it changes, and it's
   a community artwork).
6. **Ferry figure**: a generic double-ended ferry with a teal stripe; confirm no operator trade dress is implied.
7. **`StatusLayers` slot** for P3 (`casingFx`) is a small API change in `map/StatusLayers.kt`; pure mode must pass nothing.
