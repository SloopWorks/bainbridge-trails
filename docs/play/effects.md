# Play — visual effects design (`MapFx` catalogue)

Date: 2026-10-20 · Role: design-director (motion / visual effects) · Status: ACCEPTED 2026-10-20 (see decisions.md)
· Scope source: `docs/play/brief.md` (owner request = authority).

**Conformance (round-1 reconciliation).** `engine.md` is normative for rendering: tiers, the one clock, bands,
arbitration (HERO / NOTICE / AMBIENT), the overlay hand-off bound, reduced motion = final frame, idle, recording,
`globalState` ≤ 30 Hz, `placedAt` projection, budgets and the budget device. `product.md` owns every trigger, reward
and safety rule; `schedule.md` owns timing. This file decides only how effects **look, move, sound, buzz and
announce**; where a card mentions a trigger it restates product, and product wins. Vocabulary: `docs/play/README.md`
glossary (marks, Play chip, challenge, quiet zone, kraken attack / stretch / repel). v1 effects are listed in
product.md "v1 scope"; cards for later effects carry **(v2)** and cut effects are one-line **(cut)** tombstones.

Inputs: `docs/mvp/ux.md` (§0 "Chart room", §1 tokens, §1.7 motion, §3.6 celebration, §5 a11y, §6 map),
`docs/spikes/map-render/styles.md` (map palettes, style layer ids), `ui/theme/BtMotion.kt`, `ui/theme/BtColors.kt`,
`ui/CelebrationScreen.kt` + `ui/IslandCelebration.kt` (timeline idiom), `map/TrailMap.kt`, `map/StatusLayers.kt`,
`map/MapPalette.kt`, `record/TrackLayer.kt`, `map/MapLogic.kt` (`mercatorOffsetDp`).

What this file decides: how every Play effect **looks, moves, layers, sounds, announces and costs**. It does **not**
decide game rules, point values, or scheduling (when an effect is armed). Those belong to the Play rules and
scheduling specs. This file assumes each content item emits the lifecycle events in §1.11 and draws from them.

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
   saturated colour. The only Play line along the network is the kraken's ink casing (engine band `CASING`), and it
   draws *under* the status line like a casing, so the status line keeps its full width and colour on top.
2. **Persistent things live in the map, passing things live above it.** Anything that stays on screen for more than
   ~3 s (stamps, glints, the ink casing, exploration fog, ferry, (v2) tracks) is a MapLibre layer. It is zoom-correct, sits under the
   labels and costs almost nothing. Compose overlay drawing (above the map and its labels) is only for short hero
   moments of 3 s or less (engine `overlayMaxMs`), bursts and flights. When the moment ends, it hands off to a layer.
3. **One hero at a time.** At most one hero effect plays on screen at a time. Ambient effects are quiet enough to
   ignore. Under reduced motion, nothing moves (every effect shows its final frame), but every event still
   announces itself and keeps its haptic.
4. **Engraving, not cartoon.** Line art in ink, hatching for shade, flat paper fills, at most one Play colour per
   figure, no gradients. Light sources (lamp, glint) are the only exception: they get a soft radial falloff. Shadows
   fall to the south-east, because the hillshade light comes from the north-west (315°). Every figure agrees with the
   relief.
5. **Kind, not creepy.** Kids play this. The kraken is grumpy and curious, not scary: no teeth, a big lidded eye, curling
   tentacles. Creatures are glimpsed, never chased. No effect points anyone onto private land, a beach, or a
   road that fails `roadSafety`. Content placement enforces that. Effects never animate *toward* such places, never
   play inside a quiet zone, and have no dusk, dark, rain or tide-high triggers.

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
| Type in art | None, except the riddle "?". Labels belong to the map or the sheet. |
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
| `playInk` | `#22323C` | `#CFE0E6` | All Play line art; kraken; the ink casing; ink pool (at ≤ 35% α). | Text on the map. Fills larger than 24dp² at full alpha. |
| `playSeaGlass` | `#08665A` | `#63DCC6` | Treasures (sea glass), finds, the "+" of the claim pop, (v2) proximity ping. | A line along the network. Any area > 16dp diameter at full alpha. |
| `playLamp` | `#FFF1C9` | `#FFF0CF` | Light: lantern flame, lit windows, owl eyes, glint core. Always inside an ink outline or as a glow. | On its own as a mark (it is near-invisible on light land, by design). As a line. |
| `playFog` | `#F4F6F5` @ ≤ 55% α | `#7D9095` @ ≤ 25% α | Exploration fog, kraken spray (light). | Above status lines, above labels (except the `play_wake` lift, §3.17). |

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
        when on a line (casing, tracks)                     ▲ anchor = ground dot
```

| | Stamp | Pin |
|---|---|---|
| Meaning | A *thing on the ground* (treasure, frog, egg stamp, (v2) tracks, (v2) house) | An *invitation* (riddle, lore point) |
| Render | MapLibre `SymbolLayer`, `icon-pitch-alignment = map`, `icon-rotation-alignment = map` for line-bound stamps, `viewport` otherwise | MapLibre `SymbolLayer`, `icon-anchor = bottom`, `icon-rotation-alignment = viewport` |
| Size by zoom | Grows with the map (see §1.4) | Constant screen size (28dp figure) from z14 up |
| Hit target | ≥ 48dp square centred on the anchor (`queryRenderedFeatures`, like ux.md §6.1) | 48dp square centred on the figure, not the ground dot |
| Collision | Symbols place top-down, so a stamp in engine band `OVER_STATUS` (under the labels) **loses** collisions to map labels; use `icon-allow-overlap = true` there and accept that labels win. A marker that must always show (the treasure icon, the kraken far marker) lives in `OVER_LABELS` (`Anchor.Top`) with `icon-allow-overlap` and `icon-ignore-placement` true, so labels keep their own placement. | same |
| Spent state | `playInk` at 40% α, no fill, no backing ("an old mark on the chart"). Never gray (warm gray is not-started). | Collapses to a stamp-sized ink ring. |

### 1.4 Scale across zoom (z10.5 island → z18 street)

| Band | What the user is doing | Play shows |
|---|---|---|
| **z10.5–12.5** island | Seeing the whole goal | No figures. Each live item becomes a **glyph**: a 6dp sea-glass dot with a 1.5dp ink ring (treasure / egg / riddle), or a 10dp ink glyph for lore (kraken curl, frog, ferry). The kraken's ink casing shows (it carries information). Exploration fog shows. No glints, no idle loops. |
| **z12.5–14** neighbourhood | Picking where to go | Stamps at 0.6× (≈ 18dp) and pins at 22dp. Glints on (max 6). Ferry figure at 0.6×. Hero intros may play from z13.5, at reduced amplitude (tentacles 60% length). |
| **z14–16** walking | Following a trail | Full stamps (1.0×, 28–32dp), pins 28dp, idle loops, (v2) creature glimpses and tracks. |
| **z16–18** street | Close-up | Stamps grow to 1.4× (exponential base 1.5, capped at 44dp). Hero figures use geo-true lengths (tentacles reach the actual road). Detail lines on. |

Implementation: one `icon-size` expression per stamp layer,
`interpolate(exponential(1.5), zoom(), 12.5 to 0.6, 14 to 1.0, 16 to 1.0, 18 to 1.4)`. The glyph↔stamp swap is two
layers that cross-fade by opacity over z12.3–12.7. Overlay heroes read the same table through
`FxScale.at(zoom)` so both renderers agree.

### 1.5 North-up, no tilt

The chart is north-up with rotate and pitch disabled (ux.md §6.3, `chartInteractions`). Effects lean on that:

- **"Up" on screen is north, and also "up" for figures.** Standing figures (pins, the frog, the house, tentacles
  rising) are drawn in elevation, like the little engraved lighthouses on a chart. They are never foreshortened.
- Shadows always fall SE (+x, +y). Spray and splash rise toward −y (screen up).
- Because bearing and tilt are always 0, geo→screen is pure Web Mercator. Engine PF-4 is normative: each overlay
  effect is anchored with `placedAt` (frame-synced) and draws anchor-local offsets from `FxProjection` (the
  `mercatorOffsetDp` math), so it never needs one FFI call per point (§4.3).
- Line-bound marks (the ink casing, (v2) tracks) follow the line bearing in map space. Everything else stays
  upright.

### 1.6 Layer stack (conforms to engine PF-3 bands)

The earlier P1–P4 / O1 / M slots map onto engine bands as below; the engine bands are normative. The engine has no
`Anchor.Below("shore")` or `Anchor.Below("contour-label")` band, so water and ground parts sit in `UNDER_STATUS`
(inside `Anchor.Below("trail-label")`, before `StatusLayers`); the ink pool stays at α ≤ .35 so the coastline and
water lines remain readable through it.

| old slot | engine band | what goes there |
|---|---|---|
| M | `MOMENT` | full-screen moment: `play_wake` |
| O2 | (chrome, not FX) | map chrome: search, buttons, the **Play chip**, RecordingPill |
| O1 | `OVERLAY` | hero figures, bursts, the claim pop's flight: transient only, ≤ 3 s (`overlayMaxMs`) |
| P4 | `OVER_LABELS` | markers that must always show: the treasure icon, the kraken far-zoom marker |
| P4 | `OVER_STATUS` | stamps and pins under the labels: treasure halo, riddle lantern, lore and egg stamps, the ferry sprite, the lurk eye |
| P3 | `CASING` | the kraken's ink casing, through `StatusLayers`' `casingFx` slot (after halos, before status lines) |
| P1, P2 | `UNDER_STATUS` | exploration fog, kraken ink pool, lurk ripples, ferry wake |

Why this order:
- **Fog and water under the status halos.** They lighten or tint land but never the land-coloured halo around each
  counted line, so status contrast stays at the no-fog value (§1.2).
- **`CASING` between halo and status line**, like the existing casing. The ink shows on both sides of the status
  line, and the status line draws on top, untouched. Pure mode passes `casingFx = null`.
- **`OVER_STATUS` under the labels**, so trail names stay readable; markers there lose label collisions (§1.3).
- **`OVERLAY` is the only transient Play drawing above labels**, ≤ 3 s, which is why it is reserved for heroes
  (§0 rule 2). `OVER_LABELS` holds only the two always-visible markers.

### 1.7 Motion language

Durations come from `BtDuration` and easings from `BtEasing`. Play adds a few named curves, built from the M3 set,
in a `PlayMotion` object next to `BtMotion`:

| Name | Definition | Use |
|---|---|---|
| `anticipate` | `emphasizedAccelerate` over the first 30%, reversed (a dip of −8% of the travel) | Wind-up before a rise or hop |
| `overshootSettle` | `emphasizedDecelerate` to 108%, then `standard` back to 100% over `short4` | Landings, chip bumps, stamps thunking |
| `sway` | `sin(2πt/period)`, amplitude in degrees, period 2800–3600 ms, seeded phase | Idle loops (tentacles, lantern, kelp) |
| `drift` | Linear in time (no easing), for things that move with wind or tide | Fog, wake, ferry |
| `ballistic` | Constant-gravity parabola in screen space, g = 1400 dp/s², initial v from a seeded spread | Splash droplets, shards, dust |

Timeline idiom (from `CelebrationScreen` / `IslandCelebrationArt`): one `Animatable` (or the shared `FxClock`, §4.3) gives
`t` in ms. Each element maps `phase(t, from, to)` through an easing. Paths draw with `PathMeasure.getSegment`. The
**reduced-motion variant is `t = end`** (the final frame), unless the card says otherwise.

### 1.8 Haptics vocabulary

| Token | Android (`HapticFeedbackType`, CMP 1.12) | iOS | Use |
|---|---|---|---|
| `tick` | `SegmentTick` | `UISelectionFeedbackGenerator` | A stamp landing, the frog landing, (v2) a proximity ping |
| `ticks` | `SegmentFrequentTick` × n, 80 ms apart | selection × n | Ink casing unspooling, (v2) woodpecker taps |
| `confirm` | `Confirm` (fallback `LongPress` < API 30, as today) | `.success` | A find, a solve, a repel, Play on |
| `thud` | `LongPress` | `UIImpactFeedbackGenerator(.medium)` | Kraken squeeze |
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

- **One announcer.** Map effects never post their own live regions. They call `PlayAnnouncer.say(text, politeness)`,
  which **is** engine PF-5's single FxHost live region: one invisible node on the map screen that queues,
  de-duplicates (same text within 30 s is dropped), and rate-limits (≤ 1 polite announcement per 4 s). Assertive is
  reserved for the opt-in moment and for claims confirmed at Finish. Mid-walk finds are provisional and polite, with
  product copy `play_find_a11y` ("Found {item}. {n} marks when you finish.").
- **Every timed thing has an untimed path.** Anything that appears briefly (glimpses, glints, horn) leaves a trace you
  can still act on for ≥ 10 min (WCAG 2.2.1), and it shows in the Play list sheet. The map node gets custom accessibility
  actions: "Play items nearby", "Logbook".
- Decorative drawing is `clearAndSetSemantics {}`. Markers are reachable through the Play list, not by exploring
  the map canvas (same as trails, ux.md §5.5).
- **Reduced motion** (`BtTheme.motion.reduced`): every effect shows its **final frame** (engine PF-2), crossfading
  `short3`. Ambient loops, glints, drift and particles are off. Exploration fog shows as static patches. Water-line
  rings become one static ring. The claim pop becomes an in-place crossfade on the Play chip. Haptics and
  announcements are unchanged.

### 1.11 Lifecycle every effect draws from

```
dormant ──arm──▶ present ──first in view (once/day)──▶ intro ──▶ idle ──engage (sensed, or tap = open sheet)──▶ engaged
                                                                                      │
                     spent ◀──outro── resolved ◀──resolve (sensed find / solved / repelled)┘
                       ▲                                                    expire ──▶ exit (fade, no fanfare)
```

`intro` plays once per item per day per device (keyed by the occurrence key + island date), and only when the item is
in view at a zoom where the hero applies. Otherwise the item simply *is there* (`present` = static stamp). Arming,
sensing and resolving are product rules (product § 3.6, § 4); a tap only opens the sheet and never claims.

---

## 2. Arbitration (conforms to engine PF-2 / PF-3)

The engine's `FxScene` is the only arbiter; there is no separate `FxDirector`. Its classes and caps are normative:
1 HERO live (others queue FIFO, expire after 10 s), an 8 s global HERO cooldown after a HERO's `Done`, ≤ 4 NOTICE,
≤ 12 animated AMBIENT, ≤ 3 Overlay-tier parts live, each ≤ 3 s. This file maps its effects onto those classes:

| engine class | v1 effects | (v2) effects |
|---|---|---|
| **HERO** | `play_wake` (MOMENT), `kraken_attack` intro, `kraken_recede`, `treasure_found`, `riddle_light` | `easter_egg_reveal`, creature glimpses |
| **NOTICE** | `frog_rock` hop + croak, `claim_pop` | `ferry_horn`, `proximity_ripple` |
| **AMBIENT** | `treasure_glint`, `kraken_lurk`, `ferry_crossing` run, `exploration_fog`, riddle lantern sway / flicker | `tide_lines`, tracks |

Sheet-only micro-animations (`lore_stamp_ink`, v2) are plain Compose UI, outside the scene.

- A HERO that is not in view when it fires is not queued: the item goes straight to its final frame (`present` or
  `spent`) and the announcer still speaks (engine PF-3).
- **Idle:** with only AMBIENT live and no touch or camera move for 20 s, the clock stops and every ambient effect
  rests on its final frame until the next camera move or tap (engine PF-2).
- **Recording mode** (map follows the walker, trail taps off): AMBIENT is off in both tiers (it rests on its final
  frame); HERO and NOTICE still play (engine PF-2). No reduced-rate ambient while recording.
- **Input:** `OVERLAY` and `MOMENT` are pointer-transparent except the tappable Play markers' 48dp hit areas, which
  are queried only after the pure `TappableTrailLayerIds` query misses (product PF3-5). No effect except `play_wake`
  blocks map input, and `play_wake` is skippable by any tap (§3.17).

---

## 3. Effect catalogue

Card format: **Purpose / trigger · Where · Layer · Storyboard · Timeline · Colors · Light/Dark · Reduced · Announce ·
Haptics · Sound · Budget · Showcase preset.** Times in ms from the effect's `t = 0`. "Where": **(map)** geo-anchored layer,
**(overlay)** Compose overlay projected from geo, **(moment)** full-screen, **(sheet)** UI micro-animation; each names
its engine band. Budgets use the engine's budget device (Pixel 6a / iPhone 12) and its p95 ≤ 2 ms overlay draw;
layer effects are budgeted in `globalState` writes (≤ 30 Hz) and GeoJSON updates (≤ 4 Hz). Effect ids are
snake_case and are what a content item's `fx` field names.

Showcase presets give `lat, lon, zoom` and the state. Coordinates marked ≈ must be confirmed from content config at
build time. Frog Rock is the product landmark (47.69612, −122.52347).

---

### 3.1 `kraken_attack` — the kraken attacks a shore stretch

**Purpose / trigger.** The signature obstacle. Trigger, timing and rules are product § 4.3.2 (restated, product
wins): each week the kraken attacks one coast's stretches (the part of a `roadSafety` road or a shore trail within
60 m of the shoreline; never a beach access), Thu 06:00 → Sun 20:00, clock-based. Walking ≥ 60 % of a stretch in one
walk repels it. The intro plays on the attack's first in-view moment of the day at z ≥ 13.5. No tide-high, dusk or
dark trigger exists; while the tide is above the threshold the casing simply shows the static "waiting" state.

**Where.** Ink casing = (map) `CASING`. Ink pool = (map) `UNDER_STATUS`. Tentacles + splash = (overlay) `OVERLAY`,
HERO, ≤ 2.6 s (inside `overlayMaxMs`), handing off to `kraken_lurk` (map) and the casing (map).

**Storyboard** (z15, road along the shore, water to the east):

```
 t=0            t=400            t=900              t=1300              t=1800 → handoff
 land │ water   land │  ·  ◌      land │   ╭╮          land │ ╭─╮ ˙ ·      land │
 ═════╪         ═════╪ ◌ (●) ◌   ═════╪  ╱  ╲ ╭╮     ═≈≈≈≈╪╯╭╯ │  ˙     ═≈≈≈≈╪  (eye)
 road │  ~ ~    road │  ◌  ·     road │ ╱ ╭╮ ╲╯ ╲    ≈rope≈ ╰╯ ╭╯ ˙     ≈rope≈ ◌
      │              │ rings          │╱ ╱  ╲  ╲│          (squeeze)          lurk ripples
      calm      converge (wind-up)   tentacles rise    ink + splash       tentacles sink to
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
| 1100–1700 | Ink casing | The `CASING` ink draws on along the attacked stretch from the tentacle targets outward: `line-gradient` over `lineProgress()` with the reveal edge keyed to `globalState("fx.kraken.t")` (engine PF-1 (2), ≤ 30 Hz; fallback 4 `setData` steps ≤ 4 Hz). Casing = `line-pattern` ink sprite (§4.2), width = status width + 8px | linear in `t`, `long4` |
| 1500–1800 | Squeeze | Tentacles scale 1→.94→1 about their bases; `thud` haptic at 1500 | `overshootSettle`, `medium2` |
| 1800–2600 | Handoff | Tentacles sink (reverse draw to 35%, then α→0). `kraken_lurk` eye appears in the pool. Ink pool settles to its idle α .2. | `emphasizedAccelerate`, `extraLong2` |

**Idle (in view, z ≥ 14, layers only):** the casing stays. `kraken_lurk` runs. No overlay tentacles idle (they would
sit above labels).

**`kraken_recede` (resolve: the stretch was sensed as repelled, provisional until Finish).** HERO, 2200 ms. It plays
on the record map when the walk passes the 60 % share (provisional, polite announcement); the marks are claimed at
Finish and only then does `claim_pop` play.

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–150 | Casing | α flickers 1→.6→1 (grip loosens) | `standard`, `short3` |
| 150–750 | Casing | Un-draws from the walker's end back to the water (the same `globalState` reveal, reversed); `ticks` ×4 | linear in `t`, `long4` |
| 200–900 | Tentacles (overlay, re-spawned at full length) | Slide back along their own paths into the water (`getSegment(L·p, L)`) | `emphasizedAccelerate`, `extraLong1` |
| 600–1400 | Ink pool | α .2→0, radius ×1.3 (dissipates) | `standardDecelerate`, `extraLong2` |
| 900–2100 | Water lines | 3 rings expand from the anchor (8→56dp, α .8→0, stagger 150): the celebration motif, "the Sound is calm" | `emphasizedDecelerate`, as ux.md §3.6 |
| — | (no pop) | marks are provisional; `claim_pop` plays in results at Finish | — |

**Colors.** Body/tentacles: `playInk` contour + `secondaryContainer` fill (light `#D3E7EB`, dark `#224D56`), suckers paper.
Eye: paper almond, `playInk` pupil, `tertiary` lid stroke. Ink pool: `playInk` α ≤ .35 (light, over water → `#738E96`).
On dark the ink is pale (`#CFE0E6`), so the pool reads as a moonlit *disturbance*, not a dark stain. Fine: it is the same role.
Casing: `playInk` ink at α .85 beside the road bed.

**Reduced.** The final frame: no converge, no tentacles; the casing appears in full (one `short3` crossfade), the
static lurk eye (open) sits in the pool at α .2, and one static water-line ring marks the anchor. Recede: the casing
disappears (crossfade), plus one static ring for 2 s, then gone.

**Announce.** Intro, polite: "The kraken is attacking {road name}. Walk most of it to repel it." Recede (sensed),
polite, `play_find_a11y` form: "Kraken repelled on {road name}. {n} marks when you finish." Words via
`GoalVocabulary` ({road} / {trail}).

**Haptics.** `thud` at squeeze (intro); `ticks` ×4 on unspool, `confirm` at 900 (recede).
**Sound.** Intro: a low water swell + wet slap (1.2 s). Recede: a receding swell + one soft bell (1.4 s).

**Budget.** Overlay: 3 tentacle paths (≈ 40 verts each, preallocated) + 3 sucker point arrays (≤ 12 pts) + 12 droplets
+ 3 rings ⇒ ≈ 25 draw calls, about 0.9 ms on target. Layer: one `globalState` key at ≤ 30 Hz during the reveal, then
static. At most **one** kraken intro on screen; one attack per goal per week (product), 2–4 stretches.

**Showcase.** Manitou Beach Drive NE, `47.66007, -122.50494, z15.5`, state = armed, intro at t=0. Key frames t =
400 / 1100 / 1500 / 2600. Recede variant: same camera, state = attacked → repelled. Second preset: Rockaway Beach Rd NE
`47.60070, -122.49995, z15`.

---

### 3.2 `kraken_lurk` — ambient: something in the Sound

**Purpose / trigger.** Tells you an attack is live nearby when you are not looking at the road. Runs while any attack
is active and its anchor is in view, z ≥ 12.5. At z < 12.5 the anchor becomes the kraken glyph (§1.4).

**Where.** (map) `UNDER_STATUS` (ripples) + `OVER_STATUS` (eye). No overlay at all: this runs for minutes.

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
**Budget.** 2 transition writes per 1.6 s + 4 eye writes per surfacing ⇒ < 2 property updates/s per anchor. One anchor
per attack. After 20 s of map idle (engine): the rings stop on their final frame (r 20dp) and the eye stays down.
**Showcase.** `47.6625, -122.4985, z14` (water off Manitou Beach), state = attack active, time-warp ×4 for the eye cadence.

---

### 3.3 `ferry_crossing` — the boat on the timetable run (v1, layers); the horn at Winslow (v2)

**Purpose / trigger.** Island life on the map. A ferry figure moves along the Eagle Harbor ↔ Seattle route on the
bundled approximate timetable (content config, so the owner can edit it without a release). The crossing is about 35 min.
Only the island end is visible: the camera bounds stop at −122.47, so the boat enters or leaves at the east edge of the
reachable chart. v1 is visual only: no encounter, no marks. (v2) A scheduled Bainbridge departure triggers
`ferry_horn` (product § 4.6.2), an encounter only if the terminal is in view at z ≥ 13.

**Where.** Boat + wake = (map) `OVER_STATUS` (boat) and `UNDER_STATUS` (wake), updated at 1 Hz (≤ 4 Hz engine cap).
(v2) Horn = (overlay) `OVERLAY`, NOTICE, 1600 ms.

**Geometry.** The route polyline is baked at build time from the OSM ferry route (`kind = ferry`, same data as the style's
`ferry` layer) into Play content as a GeoJSON LineString with line metrics. Do not query rendered tiles at runtime:
tiles may not be loaded, and the route must exist off-screen. Terminal reference point (the one coordinate in every
Play doc): `47.62371, -122.51283`; the sprite docks at the route polyline's island end.

**Run (ambient).**

| Element | Behaviour |
|---|---|
| Boat | Point on the route at `distance = timetableFraction(now) × length`. Rotates with the route bearing (`icon-rotate`, map-aligned) but the figure is drawn so it stays readable when pointing E or W: two mirrored images, chosen by the sign of the bearing's east component, so it never sails upside down. Speed is real (≈ 9 m/s ≈ 1.5 dp/s at z14), which is calm and believable. |
| Wake | A LineString of the last 400 m of route behind the boat, `line-gradient` over `line-progress` from `waterLine` α .7 at the stern to 0, width 6→1dp (zoom-interpolated). Rebuilt at 1 Hz. |
| Docked | Boat sits at the terminal slip; a 1px `playFog` steam wisp symbol fades in and out (transition 2.4 s). |

**`ferry_horn` timeline (overlay, 1600 ms) (v2).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–150 | Boat | Settles into the slip: overlay copy of the boat nudges 4dp forward and back (follow-through) | `overshootSettle`, `short3` |
| 100–700 | Steam | 3 puffs (`playFog` light / `playInk` α .25 dark) rise 18dp from the stack, scale .6→1.2, α .8→0; stagger 120 | `standardDecelerate`, `long4` |
| 200–1200 | Horn arcs | 3 sound arcs ")))" (70° wedge, `secondary` 1.5dp) expand from the stack toward the town (west), 6→40dp, α .9→0; stagger 160 | `emphasizedDecelerate`, `extraLong2` |
| 1200–1600 | Handoff | Overlay boat fades to the layer boat (identical frame) | `standard`, `medium4` |

**Colors.** Hull paper fill + `playInk` contour; a `secondary` stripe along the hull (teal, deliberately **not** a
copy of any operator's livery); wheelhouse windows unlit in the light theme (never time-of-day driven). Wake `waterLine`.
**Light/Dark.** Dark: hull fill `surfaceContainerHigh`, windows lit `playLamp`, wake in `waterLine` dark (`#2E5564`) at α .9.
**Reduced.** The boat is shown at its timetable position, updated at most once a minute (product PF3-4). No wake
gradient animation (static 200 m wake). (v2) Horn: no arcs, no steam; announcement only.
**Announce.** The map node's accessibility text gains "Ferry crossing Eagle Harbor, by the timetable" (`play_ferry_a11y`)
when Play is on. (v2) Horn, polite, only when the terminal is in view: "The ferry is leaving Winslow."
**Haptics.** None for the run; (v2) `tick` on the horn.
**Sound.** (v2) Horn: one long, soft, low blast (1.2 s). Never at night hours (§1.9).
**Budget.** Layer: 2 GeoJSON updates/s (boat point + 400 m wake) ≈ 0.1 ms CPU. Overlay horn: about 10 draw calls.
Paused when the route bbox is off-screen (the boat's position is still computed when shown, so there is nothing to catch up).
**Showcase.** Eagle Harbor approach `47.6195, -122.4990, z14`, time-warp ×60 (a crossing in 35 s), horn at arrival.
(v2) Terminal close-up `47.62371, -122.51283, z16`, state = departing, t = 0 at horn.

---

### 3.4 `frog_rock` — the painted frog at Frog Rock

**Purpose / trigger.** A beloved local landmark as a friendly lore stamp. It is always present when Play is on. Idle
blink. Hop + croak when the scene reports the `lore-frog` encounter (sensed per product § 3.6 at the snapped trigger
point) or on tap (the tap opens the card; it never claims). Cooldown 10 min after an encounter, 3 s on tap.

**Location.** The stamp sits at the product landmark `47.69612, -122.52347` (product § 4.0.2). The trigger point is
product's snap onto `road-northeast-spargur-loop-road` (25 mph, 21 m away, 47.69594, −122.52357), **never** the
Phelps Rd NE × N Madison Ave NE junction (both 35 mph arterials that fail `roadSafety`). Effects never move the trigger.

**Where.** Stamp + blink = (map) `OVER_STATUS` (image swap). Hop + croak = (overlay) `OVERLAY`, NOTICE, 1800 ms.

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
**Announce.** Encounter, polite: "Frog Rock says hello." First visit adds: "{n} marks when you finish." The tap opens
the lore card (the card's own copy is in the product spec).
**Haptics.** `tick` on landing; `confirm` on a first visit.
**Sound.** A single soft "ribbit" (0.5 s), kid-friendly.
**Budget.** Overlay: 1 frog image (`ImageVector` → cached `Painter`), 1 shadow, 2 arcs ≈ 6 draw calls, about 0.2 ms.
The blink costs ≈ 0.4 layout updates/s, and only at z ≥ 15.
**Showcase.** `47.69612, -122.52347, z17`, state = present → engaged (hop at t=0). Key frames 0 / 300 / 700 / 1100.

---

### 3.5 `grand_forest_creatures` — owl, coyote, woodpecker, salamander (v2)

**Purpose / trigger.** Glimpses that reward attention in the Grand Forest (and, later, other wooded preserves). A glimpse
is a short encounter (≤ 1.6 s) near the walker or in view at z ≥ 15 inside a forest polygon. You "spot" it by tapping
within its trace window (10 min), or by walking past while recording, which auto-spots it. **Tracks** are the persistent
part: prints appear ahead of the walker along the trail they are on, inviting them to follow, then fade.

Frequency (render-side cap; scheduling decides the rest): island-wide ≤ 1 glimpse per 90 s while walking, ≤ 1 per 5 min
while browsing. Species windows come from the content pack and always lie inside creature hours (sunrise + 30 min →
sunset − 30 min, product T-1). Effects define no dusk, dark, dawn or rain trigger.

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
**Light/Dark.** Dark theme: owl eye glow α .75. Coyote fill `tertiaryContainer` dark.
**Reduced.** No glimpse motion: the trace stamp appears directly with a `short3` crossfade. Tracks show all at once
(static, α .7) for 20 m ahead.
**Announce.** Polite: "An owl is watching near {trail name}." Then "You spotted an owl. {n} marks when you finish."
The custom action on the map node lists active traces.
**Haptics.** Woodpecker: `ticks` ×3 synced to the pecks. Spot: `confirm`. Others: none on the glimpse (surprise without buzzing).
**Sound.** Owl: two soft hoots. Woodpecker: three taps. Coyote and salamander: silent. Kid-friendly, no howls.
**Budget.** Overlay: ≤ 1 glimpse at a time, ≤ 8 draw calls (coyote frames are prebuilt `ImageVector`s). Tracks: 1
paint-expression update at 8 Hz for ≤ 24 points, only while the window moves, then stop.
**Showcase.** Grand Forest West `47.65405, -122.56034, z16` (owl, dark theme). Grand Forest East `47.65312, -122.54474, z16`
(coyote, tracks). Time-of-day override in the showcase.

---

### 3.6 `prues_house` — a warm window (v2; generic until the owner confirms, OD-1)

**Status: BLOCKED on owner input.** The brief says "Prue's house (owner's words; confirm what/where)". The design stays
generic, and this effect may **not** ship pinned to any real residence until the owner confirms what it is, where it is,
and that featuring it is welcome (privacy, and "nothing that lures onto private land"). Triggers must fire from public
right-of-way only.

**Purpose / trigger.** A cosy lore point: a small engraved house whose window glows in the dark theme (never by time
of day) and whose door opens when the scene reports the encounter (product § 4.6.1, daylight) or on tap. If it turns out to be a story or legend rather than a
building, the same figure works as a "story house" in a park.

**Where.** House stamp + window glow = (map) P4 (stamp) + P2 (glow, a blurred `CircleLayer` under the stamp). Door
opening = (overlay) O1 encounter, 1200 ms.

**Timeline.**

| t | Element | Motion | Easing |
|---|---|---|---|
| theme switch (layer) | Window glow | `circle-opacity` 0→.6 over 2 s (paint transition) when the map switches to the dark theme | MapLibre ease |
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
**Showcase.** Placeholder location (any public park building, e.g. ≈ `47.6260, -122.5210, z16`), dark theme, encounter at t=0.

---

### 3.7 `treasure_glint` + `treasure_found` — sea glass

**Purpose / trigger.** Treasures are **sea glass** (Bainbridge beaches are full of it; it is also the Play colour). Present
treasures glint now and then, so a scan of the map finds them. Found = sensed per product § 3.6 (radius 20 m trails /
25 m roads, daylight, gated), provisional until Finish. A tap opens the sheet and never finds or claims anything.

**Where.** Icon = (map) `OVER_LABELS` (engine `fx-treasure-icon`); halo and sparkle = (map) `OVER_STATUS`. The glint runs
fully on the GPU through `globalState("fx.treasure.t")` written ≤ 30 Hz (engine § 2.3): zero Compose. Found burst =
(overlay) `OVERLAY`, HERO, 900 ms; no pop until the claim at Finish.

**Glint (ambient, map).**
- Stamp: a faceted pebble (ink contour, sea-glass fill α .85, two paper facet highlights).
- Sparkle layer: a second symbol layer with a 4-point star image (`playLamp` core, ink 1px edge) at the stamp's upper-left
  facet. Opacity is driven by the engine's `globalState` `t` (0..1 sawtooth) plus a per-feature seeded `phase`
  (engine § 2.3): a short triangle flash per period.
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
| 300–600 | Pebble | Shrinks to 0 in place (no flight: the marks are provisional until Finish; `claim_pop` plays in results) | `emphasizedAccelerate`, `medium2` |
| 600–900 | Spent stamp | The map stamp crossfades to spent (ink ring α .4) | `standard`, `medium2` |

**Colors.** `playSeaGlass`, `playLamp` (sparkle core), `playInk`, `waterLine`.
**Light/Dark.** Dark: sea glass `#63DCC6`, which glows naturally on dark; the sparkle has a soft glow α .6.
**Reduced.** Final frame: no glints (matte stamps). Found: stamp crossfades to spent.
**Announce.** Polite (provisional), `play_find_a11y`: "Found sea glass. {n} marks when you finish." Presence is not
announced; the Play list has "{n} treasures nearby".
**Haptics.** `confirm` at 100. **Sound.** Glass clink (two notes, 0.4 s).
**Budget.** Glint: one `globalState` write at ≤ 30 Hz (only while ≥ 1 glinting treasure is in view at z ≥ 13; stops at
the engine's 20 s idle). Found: 16 particles + 3 shapes ≈ 0.6 ms.
**Showcase.** Fay Bainbridge Park shore ≈ `47.7010, -122.5080, z15.5`, 5 treasures seeded, glint time-warp ×1; found at
t=0 on the nearest.

---

### 3.8 `easter_egg_reveal` — the chart peels back (v2; v1 eggs use its reduced variant, a stamp crossfade)

**Purpose / trigger.** Easter eggs are invisible until sensed within their radius (product § 4.2: 15 m, global, once
ever); effects define no other condition. On reveal, the chart itself cracks and a small **inset cartouche** (the framed detail map of old charts)
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
**Announce.** Polite (provisional): "An easter egg. {n} marks when you finish."
**Haptics.** `tick` at 0, `confirm` at 900. **Sound.** A paper crackle + a chime (0.9 s).
**Budget.** About 14 draw calls, with no particles. ≈ 0.5 ms.
**Showcase.** Grand Forest centre `47.6550, -122.5480, z16`, state = condition met at t=0.

---

### 3.9 `riddle_marker` + `riddle_light` — the question lantern

**Purpose / trigger.** A riddle is a **pin**: a ship's lantern on a short post with a "?" etched on the glass, unlit.
Solve (in the sheet, armchair: a stamp, product § 4.4) → the lantern lights and stays lit; claiming happens later by
walking there. Wrong answer → it shakes.

**Where.** Lantern = (map) `OVER_STATUS` pin. Idle sway = layer `icon-translate` ±1.5 dp, 2 keyframe transitions per
period (see the sway note below). Lighting =
(overlay) `OVERLAY`, HERO, 1100 ms, then the lit (map) image. Wrong = (sheet) micro on the lantern thumbnail in the sheet,
**and** (overlay) if the map is visible.

**Timeline — `riddle_light`.**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–150 | Wick | A spark: 3 tiny `playLamp` points jump 2–4dp | `ballistic`, `short3` |
| 150–500 | Flame | Teardrop of `playLamp` grows 0→9dp tall with an ink outline; the "?" fades out (α 1→0) | `emphasizedDecelerate`, `medium3` |
| 300–900 | Glow | Radial `playLamp`, 0→28dp (z16), α 0→.6 (dark .8) | `standardDecelerate`, `long4` |
| 500–1100 | Rays | 8 short ink rays (4dp) flick out and fade, stagger 30 | `emphasizedDecelerate`, `long4` |
| 900–1100 | Handoff | → lit layer image + `UNDER_STATUS` glow circle | — |
| idle (lit) | Flicker | Glow α .55↔.65, 3 seeded keyframes per 4 s; z ≥ 15 only | MapLibre ease |
| idle (unlit) | Sway | `icon-rotate` ±3° about the post top, period 3200, seeded phase; z ≥ 15 only | MapLibre ease |

**Sway note (as built, UI review 2026-10-21).** The sway is a ±1.5 dp horizontal `icon-translate`, not a ±3° rotate:
`icon-rotate` is a layout property, so it cannot take a paint transition and every keyframe would re-place the symbol.
`icon-translate` is a paint property with `iconTranslateTransition`, so two writes per 3.2 s period ease on the GPU. The
step follows the FxHost clock (stops on idle, recording, background, reduced motion) and returns to centre (0 dp) at
rest. Lit lanterns are static parts: no sway of their own; the glow rests at α .6.

**Wrong answer (`riddle_reject`, 360 ms).** Lantern translateX 0→−4→4→−3→3→0dp (3 cycles), `reject` haptic. **No red,
no ✕.** The sheet says "Not quite" (product copy).

**Colors.** Ink lantern frame, glass paper α .6, flame and glow `playLamp`.
**Light/Dark.** Dark theme: glow α .8, radius 1.6×.
**Reduced.** Lit image swaps in; static glow; wrong answer = sheet text + haptic only.
**Announce.** Polite: "Solved. Walk there to claim it." (`play_riddle_solved_stamp`). Wrong, polite: "Not quite. Try again any time."
**Haptics.** `confirm` at 150 (light); `reject` (wrong). **Sound.** A match strike + soft whoomp (0.7 s).
**Budget.** Overlay ≈ 14 draw calls. Idle: ≤ 0.6 property updates/s per lantern; max 6 animated lanterns in view.
**Showcase.** Winslow waterfront ≈ `47.6245, -122.5195, z16`, unlit → solve at t=0; second preset: dark theme, 3 lit.

---

### 3.10 `obstacle_generic` (cut)

Cut entirely (review P1-19 and the cut line): `fallen_tree`, `tide_cut` and `fog_bank` are gone; the kraken and
exploration fog are the only obstacles.

---

### 3.11 `race_track` (cut)

Cut entirely (review P0-2): no timed courses, countdown, ghost, finish tape or finish time. A challenge shows its
window and progress in its sheet and finishes with a stamp; it has no map effect.

---

### 3.12 `claim_pop` — "+N marks" into the Play chip

**Purpose / trigger.** Only when marks are **claimed**: in the results Play section at Finish (product § 3.6.2), and on
the map when server-derived marks arrive on sync (kraken retreat, kept week, island challenge). Never mid-walk:
sensed finds are provisional and only announce (`play_find_a11y`). In results, each line's "+N" rolls into the
section total (sheet). On the map, "+N" rises from its origin (the coast label for a retreat) and flies into the Play
chip (top-left, `mapChrome`, O2 chrome).

**Where.** (sheet) in results; (overlay) `OVERLAY` → (chrome) Play chip on the map. NOTICE.

**Timeline (900 ms).**

| t | Element | Motion | Easing |
|---|---|---|---|
| 0–200 | "+N" | Appears at the origin (geo-projected): scale .6→1, α 0→1, rises 16dp. Text: `labelLarge` system font `tnum`, `onMapChrome` on a `mapChrome` capsule; the "+" is a `playSeaGlass` glyph | `emphasizedDecelerate`, `short4` |
| 200–300 | Hold | — | — |
| 300–750 | Flight | Along a quadratic Bézier to the Play chip (control point 30% up and toward the screen centre); scale 1→.7 | `emphasizedAccelerate`, `long1` |
| 700–900 | Chip | Bump: scale 1→1.08→1 (`overshootSettle`); the number rolls old→new (vertical digit roll per changed digit, 150 ms, `standard`, system `tnum` so it never reflows) | `overshootSettle`, `short4` |

**Coalescing.** A pop within 400 ms of another merges into it (the sum). Max 3 in flight; the 4th joins the last.
While the chip is hidden (sheet expanded), the flight targets the sheet's Logbook row instead.

**Colors.** `mapChrome`, `onMapChrome`, `playSeaGlass` "+". Never `positive` (spruce) on the map.
**Light/Dark.** Token-driven.
**Reduced.** Final frame, no flight: the chip crossfades to the new number (`short3`) and shows "+N" beside it for 1.5 s.
**Announce.** Polite, coalesced per 4 s: "Plus {n} marks. {total} marks." Skipped if the results line already said it.
**Haptics.** None (the resolve already confirmed). **Sound.** A soft coin-on-wood tick (0.2 s), coalesced too.
**Budget.** ≤ 3 text layouts (cached `TextLayoutResult`, measured once per value) + capsules ≈ 0.3 ms.
**Showcase.** Any map preset; buttons for +25, +80, a burst of 5 pops in 300 ms (coalescing test), and chip hidden; the
results sheet variant.

---

### 3.13 `streak_float_line` (cut)

Cut entirely (review P1-17, product PP-12): no daily streak motif. Kept weeks are a Logbook line.

---

### 3.14 `proximity_ripple` — "something is near" (v2)

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
**Budget.** 2 arcs. Cooldown 60 s per item, one item pinged at a time.
**Showcase.** Recording preset on Grand Forest West `47.65405, -122.56034, z16`, item 90 m NE.

---

### 3.15 `tide_lines` (v2) — weather, rain and morning fog (cut)

Rain streaks, morning fog and every weather-driven visual are **cut entirely** (review P1-18; no weather feed). Only
`tide_lines` remains, for v2:

| Sub-effect | Where | Visual | Motion |
|---|---|---|---|
| `tide_lines` (v2) | (map) `UNDER_STATUS` | Bundled beach shoreline polylines (content config) get 1–3 `waterLine` lines with **landward** `line-offset` proportional to tide height above mean (0–3 lines; spacing 3/6/9px at z15, zoom-interpolated). At low tide: 0 lines plus a pale `tertiaryContainer` α .25 "wet sand" band seaward of the shore. | The offset changes slowly with the predicted tide (recomputed every 10 min, 2 s paint transition). One "wash": the innermost line's offset breathes ±0.6px, period 6 s (2 keyframe transitions per period), z ≥ 15 only. |

**Layering.** Under the status halos (status contrast unchanged, §1.2) and under labels.
**Reduced.** Final frame: static tide lines.
**Announce.** None on the map. The Play sheet header reads "High tide 2:14 pm" when relevant.
**Haptics / Sound.** None.
**Budget.** ≤ 0.35 updates/s; stops with the engine's idle rule.
**Showcase.** Fay Bainbridge beach ≈ `47.7020, -122.5070, z16` with a tide slider.

---

### 3.16 `lore_stamp_ink` — the card gets stamped (v2; v1 shows its final frame)

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
This is the showcase moment and v1's only `MOMENT`: ~3 s, skippable by any tap. It **lands the user on their own live map with Play in
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
 │              │        │ The island   │          │ to the coast │          │ from Winslow │          │ + Play chip  │
 │   island     │        │  is awake    │          │  eye blinks ◉│          │ outward  ⛴)))│          │ +1,190 marks │
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
| 1700–2100 | Ferry | The horn arcs ")))" at Winslow (the 3.3 horn art reused as decoration; no encounter) | as 3.3 |
| 1900 | Haptic | `confirm` | — |
| 2200–2700 | Paper lifts | The wash fades α .82→0 **and** a fog-lift dissolve (shader, or the fallback: radial mask growing from Winslow) reveals the live map; the title fades out 2200–2500. The art's stamps crossfade into the real P4 stamps (same projected positions) | `emphasizedAccelerate`, `long2` |
| 2600–3000 | Play chip | Slides in at top-left (16dp, `emphasizedDecelerate`, `medium4`), then the starting-balance `claim_pop` ("Your walks so far", product § 3.6.4) | 3.12 |

Skip: any tap at t < 2200 jumps to t = 2200 (the lift), so the end state is always reached through the lift, never a cut.

**Colors.** `surface` + grain, `playInk`, `waterLine`, `playSeaGlass` glyphs, paper.
**Light/Dark.** Dark: wash = `surface` dark (`#101A16`) α .85; coast ink pale; the rose gets a faint `playLamp` glow α .25
(night chart); the eye glows.
**Reduced.** One `short3` crossfade to a static card (title + rose + coast + rings at final positions) for 1.2 s,
then a `short3` crossfade to the live map with stamps and the Play chip in place (the final frame). Haptic as normal.
**Announce.** Assertive, on appear: "Play is on. Treasures, riddles, challenges and island stories are now on your
map. Your Play chip is at the top left. Your walks so far: {n} marks." (product copy may reword). Focus then goes to
the Play chip.
**Haptics.** `confirm` at 1900. **Sound.** A soft horn + glass chime (1.4 s), only if sounds are on (they are off by
default, so normally silent).
**Budget.** This is the one moment allowed a heavier frame: ≤ 4 ms/frame on target (coast path ≈ 1.5k vertices,
pre-simplified to ≤ 600 at the island zoom; up to 40 glyphs; 3 rings). It runs once, then the clock stops.
**Showcase.** Camera = island home (`IslandCamera`, `47.645, -122.535, z12`), state = Play just enabled, 25 items
seeded. Key frames 300 / 900 / 1500 / 2000 / 2600 / 3000; skip-at-800 test.

---

### 3.18 `exploration_fog` — fog over uncharted chart squares (v1)

**Purpose / trigger.** Product § 4.3.1: soft exploration fog over every chart square where the user has no counted
coverage yet; it lifts on the first new coverage there (provisionally live, claimed at Finish). The only Play "fog".

**Where.** (map) `UNDER_STATUS`: one `FillLayer` over the fogged chart squares (one GeoJSON polygon set rebuilt only
when a square lifts), `fill-pattern` = a soft 256px noise sprite, `playFog` α as §1.2; cut out of every quiet zone.

| t | Element | Motion | Easing |
|---|---|---|---|
| idle | Fog | Static (no drift: AMBIENT with no animation, so it never keeps the clock running) | — |
| 0–900 (lift, live) | Square | `fill-opacity` of the lifted square → .25 (thinning, provisional) via a paint transition | MapLibre ease |
| at Finish (claimed) | Square | Removed from the source; the remaining fog is unchanged | — |

**Colors.** `playFog` only. **Light/Dark.** Light α ≤ .55, dark α ≤ .25; status halos draw above it (§1.2).
**Reduced.** Final frame: instant swap, no transition.
**Announce.** Polite at the claim: `play_fog_lifted_a11y` ("Fog lifted over a new square. 23 of 83 squares explored.").
**Haptics / Sound.** None.
**Budget.** ≤ 1 GeoJSON rebuild per lifted square; ≤ 83 polygons. No per-frame cost.
**Showcase.** Island `47.6450, -122.5350, z12`, a user state with 20 of 62 squares lifted; lift at t=0 on one square.

---

## 4. Rendering approach per effect

### 4.1 Recommendation table

Approaches: **(a)** MapLibre layers (GeoJSON, expressions, paint transitions, the expression clock). **(b)** Compose
Canvas overlay projected from geo through the camera. **(c)** full-screen Compose moment. **(d)** runtime shader
(SkSL on iOS/Skia, AGSL on Android 33+). **(e)** Compose UI micro-animation in a sheet or chrome (no map).

| Effect | Approach | Why |
|---|---|---|
| `kraken_attack` | **a + b** | Casing and pool are persistent and zoom-true → (a). Tentacles need per-frame path growth and taper with BtEasing → (b), ≤ 2.6 s, then handed to (a). |
| `kraken_recede` | **b + a** | Mirror of the intro. |
| `kraken_lurk` | **a** | Runs for minutes; transitions on rings and eye are free. |
| `ferry_crossing` (run) | **a** | 1 Hz point + wake: real-time slow motion needs no frame clock. |
| `ferry_horn` (v2) | **b** | Short, rich, encounter. |
| `frog_rock` | **a** (stamp, blink) + **b** (hop/croak) | Squash and stretch needs per-frame transforms. |
| `grand_forest_creatures` (v2) | **b** (glimpses) + **a** (traces, tracks) | Glimpses are brief figures; tracks must stay under status lines. |
| `prues_house` (v2) | **a** (stamp, glow) + **b** (door) | |
| `treasure_glint` | **a** | GPU-side opacity math per feature via `globalState`; zero Compose work. |
| `treasure_found` | **b** | Burst. |
| `easter_egg_reveal` (v2) | **b** → **a** | v1 eggs use the reduced stamp crossfade (a). |
| `riddle_marker` / `riddle_light` | **a** (pin, sway, flicker) + **b** (lighting) | |
| `exploration_fog` | **a** | Persistent and under the status lines, so never a shader overlay. |
| `claim_pop` | **e**, or **b → e** on the map | Results sheet at Finish; on the map it starts geo-projected and ends in chrome. |
| `proximity_ripple` (v2) | **b** | Anchored to the puck; short. |
| `tide_lines` (v2) | **a** | |
| `lore_stamp_ink` (v2) | **e** | |
| `play_wake` | **c** (+ **d** optional for the lift, v2) | |
| `obstacle_generic`, `race_track`, `streak_float_line`, weather (cut) | — | — |

### 4.2 (a) MapLibre layer techniques

Four patterns, cheapest first. All run inside `@MaplibreComposable` content of `TrailMap`.

1. **Static + zoom expressions.** Stamps, glyph↔stamp swaps, `icon-size` by zoom. Zero ongoing cost.
2. **Paint transitions ("keyframe transitions").** Set a paint target with `TransitionOptions(duration)`. MapLibre
   interpolates on the render thread at full frame rate with its own ease. The pattern is already used for selection
   dimming (`opacityTransition = fade` in `StatusLayers`). Use it for every ambient loop: ripples, eye, flicker, sway,
   wash, fog drift. A loop is just 2 keyframe writes per period. Transitionable paint properties include opacity,
   color, width, radius, `*-translate` and `line-offset`. **Layout** properties (`icon-size`, `icon-image`) are not
   transitionable: use image swaps for discrete changes (blinks).
3. **`globalState` clock (engine PF-1/PF-5, normative).** One `globalState("fx.<kind>.t")` key per animated kind,
   written by the `FxClock` at ≤ 30 Hz while an animated feature of that kind is in view. Paint expressions combine
   it with per-feature properties (`phase`, `seq`). No re-tiling, no recomposition. Used for glints, the ink casing
   reveal and (v2) tracks. Fallback if spike S1 fails: the engine's `FxLayerDriver` (≤ 4 layers' `const()` at
   ≤ 30 Hz). There is no separate `tick` / `setPaintProperty` clock.
4. **Small GeoJSON rebuilds.** For moving points: the ferry (1 Hz). Engine cap ≤ 4 Hz; geometry ≤ 400 vertices. The source uses `lineMetrics = true` where `line-gradient` is needed (wake).
   Never rebuild a large source per frame. `TrackThrottle` is the precedent.

Images: figures are authored once as `ImageVector` and rasterised to `ImageBitmap` per density for MapLibre (`@2x`,
`@3x`). Images are registered by id (`fx-frog-open`, `fx-frog-closed`, …). **Spike item:** confirm the maplibre-compose
0.20 API for runtime images (`image(...)` from a `Painter`/`ImageBitmap` in `SymbolLayer`) and for `fill-pattern` /
`line-pattern`. The fallback is bundled PNG sprites under `composeResources/files/fx/`.

### 4.3 (b) Compose overlay projected from geo

- Lives in `TrailMap`'s new `fxOverlay` slot (engine § 2.2), composed **before** `TrailMapOverlay`, so chrome stays
  on top. MapTab and RecordScreen are the gated call sites.
- **Projection (engine PF-4, normative).** Each effect is one box placed with `placedAt(anchor)`, which the library
  re-places on every presented map frame (frame-synced); geometry is drawn in anchor-local dp from
  `FxProjection.offsetsDp` (the `mercatorOffsetDp` math), with `zoom` read in the draw phase only.
- **Camera swim** is not expected with `placedAt`. Only if spike S4 fails (drift > 1 dp pan / 2 dp pinch) does this
  fallback apply: during an active gesture, `OVERLAY` figures fade to α 0 in `short3` and their layer counterparts
  (stamp / eye / casing) carry the scene, returning in `medium1` after the camera idles.
- **No recomposition per frame.** The clock value is read **inside the draw lambda** (`Canvas { val t = clock.value … }`
  or `drawWithCache`), so a frame invalidates draw only. Paths, `PathMeasure`, `Paint`, text layouts and particle arrays
  are preallocated per effect instance. No allocation in draw (no `listOf`, no lambdas, no `Path()` per frame).
- Overlay is `pointerInput`-transparent. Tappable Play items are hit-tested through `queryRenderedFeatures` on their
  `fx-` layers (ux.md §6.1 pattern), never via overlay hit boxes, and only after the pure `TappableTrailLayerIds`
  query misses (product PF3-5: the trail preview wins).

### 4.4 (d) Where a runtime shader truly earns its place (v2, engine stage 8)

Only three visuals gain something that paths cannot give cheaply. **Each ships with a non-shader fallback, and the
fallback is the default until the shader is measured on target.**

| Visual | Why a shader | Shader | Fallback (all platforms) |
|---|---|---|---|
| Fog lift (`play_wake` lift) | An organic dissolving edge across a large area; with paths it's either blobby or expensive | fbm noise (3 octaves) thresholded by a moving radial front; half-res quad | Two tileable 256px noise bitmaps (`ImageShader`, `TileMode.Repeated`) drifting in parallax under a growing radial `BlendMode.DstOut` mask |
| Kraken ink bloom (`kraken_attack`) | Ink diffusing in water reads as *ink* only with domain-warped edges | Domain-warped noise on a radial falloff, `playInk` | 5 seeded overlapping circles with the noise-bitmap mask, `BlendMode.SrcOver` |
| Nothing else | Water rings, glints, flames, tentacles, rain, confetti: paths and particles look better (they are line art) and cost less | — | — |

Platform notes:
- **iOS** (Compose MP on Skia): `org.jetbrains.skia.RuntimeEffect.makeForShader(sksl)` → `RuntimeShaderBuilder` → `Shader`
  → `ShaderBrush`. Available on all supported iOS versions.
- **Android**: `android.graphics.RuntimeShader` (AGSL) is API 33+. minSdk is 26, so API 26–32 always uses the fallback.
  One shader source; AGSL and SkSL differ only in small dialect details, kept in an `expect/actual` `FxShaders` with
  a `isSupported` flag.
- Shaders never run in map layers (maplibre-compose has no custom GL layer). They only draw in `OVERLAY`/`MOMENT`,
  which is why exploration fog is (a) and only the transient `play_wake` lift may use (d). v1 ships the fallbacks.

### 4.5 (c) and (e)

- **(c)** follows `CelebrationScreen`: one `Animatable` clock, `phase()` helpers, `LaunchedEffect(reduced)`, the
  announcement on a merged heading node with `LiveRegionMode.Assertive`, decorative Canvas `clearAndSetSemantics {}`.
  `play_wake` is the only new (c). Play resolutions do not get full-screen moments (the celebration stays the pure app's
  moment; Play heroes stay on the map).
- **(e)** is plain Compose animation (`animateFloatAsState`, `Animatable`), with `BtMotion.tweenOrSnap` for reduced motion.

---

## 5. Performance budget and pausing (conforms to engine § 3 and PF-2)

Budget device: **Pixel 6a / iPhone 12** (engine § 3, normative; the earlier Pixel 4a / iPhone SE 2 target is
replaced). Engine budgets rule (overlay draw p95 ≤ 2 ms, p99 ≤ 4 ms; 0 allocations per overlay frame; Layer-tier
animation ≤ 30 Hz and only in view). This table only adds per-effect counts:

| Budget | Value |
|---|---|
| `play_wake` overlay draw | ≤ 4 ms/frame (the one Moment) |
| Overlay recompositions while animating | **0 per frame** (draw-only invalidation) |
| Particles on screen | ≤ 16 (one HERO's burst) |
| Overlay paths animating | ≤ 8 |
| Layer animation | `globalState` writes ≤ 30 Hz while animating; transitions otherwise |
| GeoJSON rebuilds | ≤ 4 Hz (engine), each ≤ 400 vertices |
| Animated instances in view | glints ≤ 6, lanterns ≤ 6, kraken anchors 1 per goal |
| Shader quad (v2) | ≤ 1 at a time, half-res, ≤ 1.2 ms |

**Quality step-down** is the engine's `FxQuality` (HIGH / MED / LOW, stepping down when p95 overlay draw exceeds budget
for 2 s; engine PF-7 and § 3 "Over budget"). There is no separate effects governor.

**Pause rules** (engine PF-2):

| Condition | Effect |
|---|---|
| App background (lifecycle `ON_STOP`), screen off, Map / Record not visible | `FxClock` stops; no `globalState` writes; no GeoJSON rebuilds. On return, positions (ferry) are recomputed from wall time. Nothing "catches up" visibly. |
| Only AMBIENT live and no touch / camera move for 20 s | The clock stops; every ambient effect rests on its final frame until the next camera move or tap. The ferry keeps 1 Hz only while in view. |
| Recording | AMBIENT off in both tiers (final frame); HERO and NOTICE allowed (§2). |
| Low Power (iOS) / Battery Saver (Android) | All ambient off; heroes use their final frames; fog static. |
| Reduced motion | §1.10 (final frame everywhere). |
| Item off-screen | Not drawn, not animated. A HERO that fires off-screen resolves to its final frame. |

---

## 6. Craft checklist (every effect, before ACCEPT)

- [ ] **Anticipation**: every hero motion has a wind-up of 100–400 ms (converge, squash, nudge, jolt).
- [ ] **Follow-through and overlapping action**: parts finish at different times (tentacles staggered 90 ms, the frog's
      throat after the landing). Nothing stops on one frame all at once.
- [ ] **Settle**: final positions arrive with `overshootSettle` or a damped spring, never a hard stop. The end frame is a
      calm, screenshot-worthy composition.
- [ ] **Hand-off**: every overlay hero ends in a frame identical to its layer counterpart, then crossfades. No pop at the seam.
- [ ] **Input**: no effect blocks input for more than 300 ms, except `play_wake` (skippable) and the existing celebration.
      Overlay never intercepts touches.
- [ ] **One hero**: at most one HERO on screen; 8 s HERO cooldown (engine); overlay parts ≤ 3 s; cooldowns per card respected.
- [ ] **Coverage first**: status lines readable at every frame (check frame captures at key frames, both themes,
      deutan sim). No Play layer between a status line and its halo except the `CASING` band. No Play colour on the
      network except the kraken's ink casing.
- [ ] **Labels**: persistent Play drawing sits under `trail-label` / `place-label`, except the two `OVER_LABELS`
      markers (treasure icon, kraken far marker). Only transient `OVERLAY` heroes may overlap labels, for ≤ 3 s.
- [ ] **Chart physics**: north-up elevation figures, SE shadows, up = north. No figure rotates with
      the camera (it can't), and line-bound stamps follow the line bearing.
- [ ] **Style**: ink contour + detail weights only, hatching for shade, ≤ 1 role fill, no gradients except light glows,
      grain only inside paper.
- [ ] **Both themes** checked; dark uses its own role values (not inverted light art).
- [ ] **Reduced motion** variant = the final frame (engine PF-2), plus the same announcement and haptic.
- [ ] **Announcement** text routed through `GoalVocabulary`, via `PlayAnnouncer`, with the right politeness; timed
      things have a 10-minute trace.
- [ ] **Haptics** at most one per 250 ms; none in background.
- [ ] **Sound** off by default, ≤ 1.5 s, ambient category, quiet hours.
- [ ] **Budget** measured on the engine's budget device at the showcase preset (p95 overlay draw ≤ 2 ms); pause rules
      verified (background, idle 20 s, recording, Low Power).
- [ ] **Kind**: no teeth, no chase, nothing that points at private land, beaches or roads failing `roadSafety`; nothing
      inside a quiet zone; no dusk, dark, rain or tide-high trigger.
- [ ] **Vocabulary**: marks (never points), Play chip, Logbook, challenge, repel (never wrap / drive off / clear).
- [ ] **Pure mode**: with Play off, zero `fx-*` layers, no overlay, no clock. Screenshot-identical to today.

---

## 7. Debug showcase ("Play FX") — brief AC 7

The engine's FX showcase (engine § 4 stage 7: `Screen.FX` in `StyleSpikeScreen`): a full-bleed `TrailMap` with the FX
engine, plus a bottom control strip. **It must host every v1 effect** (AC 7); a host test fails if a v1 id below has
no preset:

`treasure_glint`, `treasure_found`, `kraken_attack`, `kraken_recede`, `kraken_lurk`, `riddle_marker`, `riddle_light`,
`frog_rock`, `ferry_crossing`, `exploration_fog`, `claim_pop` (map and results variants), `play_wake`. (v2 effects
add their presets when they are built.)

- Effect picker (the ids above, plus the `kraken_attack` / `kraken_recede` pair and each effect's reduced variant).
- Preset camera (the card's showcase preset) and state (`dormant/present/intro/idle/engaged/resolved/spent`).
- Timeline scrubber (0 → effect end, ms), Play/Pause, speed ×0.25 / ×1, and **time-warp** for slow real-time things
  (ferry ×60, glint cadence ×4).
- Theme System/Light/Dark (like `ShowcaseScreen`), map style Topo/Light/Dark, reduced-motion override, sounds toggle,
  budget HUD (engine `FxHud`: overlay ms p50/p95, `globalState` writes/s, particles, clock state).
- Launch arguments for scripted screenshots, both platforms (engine stage 7): Android `--es screen fx --es fx <id>
  --es state <state> --es t <ms>`, iOS `-btScreen fx:<id>` with the same options, plus theme / reduced / warp. The
  output goes to `docs/play/screens/fx-<id>-<theme>-t<ms>-{android,ios}.png` at the key frames listed per card.

---

## 8. Open items (need an owner or a spike)

1. **Prue's house**: what, where, and whether it may be featured (§3.6, blocked).
2. **Spike: maplibre-compose 0.20 capabilities**: runtime images from `ImageBitmap`/`Painter`, `fill-pattern`,
   `line-pattern`, `line-gradient` + `lineMetrics`, `*-translate`/`line-offset` transitions, feature-state support.
   Each technique in §4.2 names its fallback if one is missing.
3. **Spike: overlay camera lag** = engine spike S4 (`placedAt` drift); the §4.3 swim rule applies only if it fails.
4. (cut) Rain data source: weather is cut entirely.
5. **Frog Rock depiction**: an engraved generic frog, not a reproduction of the current paint job (it changes, and it's
   a community artwork).
6. **Ferry figure**: a generic double-ended ferry with a teal stripe; confirm no operator trade dress is implied.
7. **`StatusLayers` slot** for the `CASING` band (`casingFx`) is now part of engine PF-3 / PF-9; pure mode passes null.
