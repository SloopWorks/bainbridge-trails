# Bainbridge Play — UX spec (visual + interaction)

Date: 2026-10-20 · Owner: design-director (UX) · Status: **ACCEPTED 2026-10-20** (`docs/play/decisions.md` PD-18),
after a conformance pass against the round-1 reconciliation (PD-1 … PD-17, the README glossary, product's v1 scope and
spike S4). Decisions inline as **Decision:**; open items in §7.2, max 5.
Work item: `docs/play/brief.md`. Mockup: `docs/play/design/play-mockup.html` (light + dark, phone width).

Precedence (PD-18; `docs/play/README.md`)
- `docs/mvp/decisions.md`, `docs/roads/decisions.md` (pure mode) > `docs/play/decisions.md` > `product.md` >
  `schedule.md` > `engine.md` > **this file** > `effects.md` > `ai.md`.
- **product.md wins on words, rules, numbers and flows** (PF-numbers, `play_*` keys, marks, Logbook, challenge);
  **schedule.md on time** (windows, daylight, the agenda, the play clock); **engine.md on rendering** (tiers, bands,
  clock, tap routing, PF-1 … PF-9 and the spike results in `spikes/fx.md`).
- **This file wins on layout, color, type, motion, haptics and interaction** for every Play surface, including the
  map chrome and the marker anatomy. effects.md owns how each map effect is drawn and timed on the map (its §3
  cards), with the overrides listed in §0.3.
- Vocabulary is the README glossary (binding, PD-16): marks, Logbook, Play chip, challenge, quiet zone, fog =
  exploration fog, chart square, occurrence key, week id = the Monday date, island time, daylight, seasons Storm /
  Strawberry / Madrona / Kraken.
- Scope is product's "v1 scope" (PD-15). Later items keep their design text here tagged **(v2)**; cut items are
  one-line **(cut)** tombstones.
- Base spec: `docs/mvp/ux.md` (tokens, components, nav, a11y) and `docs/roads/ux.md` (brand roles per goal).
  **Everything not overridden here is unchanged. With Play off, nothing in this file renders** (PP-2, PF0-1).

Copy shown in wireframes is the product.md text for the key named. Words that name the goal route through
`GoalVocabulary` (`{trail}`/`{road}`); keys marked (goal) in product.md have a `roads_` twin.

---

## 0. Design direction: Play inside "Chart room"

### 0.1 What Play adds

The pure app is a calm chart: paper, ink, spruce chrome, status lines that tell the truth. Play is **the chart's
marginalia waking up** (effects.md §0): engraved figures in one ink, one small accent of sea glass, a lamp that only
glows, and fog that only lifts. It adds four things to the visual language and nothing else:

1. **One ink for art** (`playInk`): every Play figure, stamp and casing is line art in a single blue-black ink
   (pale on dark), with hatching for shade. Never a filled color shape larger than a fingertip.
2. **One accent for reward** (`playSeaGlass`, bottle-glass green): treasures, the marks glyph, `+n` deltas, and the
   progress fill of Play bars. It is the only Play hue a user learns, and it always means *marks*.
3. **One light** (`playLamp`, pale cream): lantern flames, lit windows, glints. Light, never a shape on its own.
4. **One veil** (`playFog`): exploration fog over unwalked chart squares (and the kraken's spray). Always below the
   status halos and lines.

Plus one new type use: **the survey mark glyph** (△ with a centre dot — the triangulation-station symbol on real
charts) is the icon for *marks* everywhere: Play chip, reward chips, Logbook, celebration line.

### 0.2 What Play must never do

| Never | Why / rule |
|---|---|
| Use a status hue (gray / ochre / tangerine / violet, any of the 4 roles) or `nearlyDoneCasing`, `recording`, `userTrack`, `error`, `positive` for anything Play | Status hues mean coverage; blue means *you*; spruce `positive` means island %-points. Guard test: every `PlayColors` value is ≥ ΔE 20 from all of them, both themes (measured §1.1). |
| Recolor, dim, cover or imitate a status line | Coverage is sacred. The kraken's ink casing draws in the `CASING` band, between the halo and the status line, which stays on top (engine PF-3); exploration fog sits in `UNDER_STATUS`, below the land-colored status halos. Play selection never dims trails. |
| Crowd the chart | Play adds **one** chrome row (Play chip + Challenges chip). No Play button in the bottom bar, no FAB, no banners except the March 30 quiet banner. ≤ 40 Play markers visible (schedule SD-7, § 3.4), ≤ 6 animated glints, one hero at a time. |
| Put text on map tiles | Play text sits on `mapChrome` or in sheets; the only map text is the kraken coast label, set like a map water label with a paper halo (§2.2). |
| Use Fraunces for numbers that change | Marks counters, distances and the Play chip use system font + `tnum` (ux.md §1.3). Fraunces only for static totals (Logbook header, Me card). |
| Use a new full-screen modal for a Play reward | Rewards land in sheets, the Play chip, results and celebration lines. The only Play full-screen moment is the once-only `play_wake` (§2.11). |
| Animate in a quiet zone, make anything lively or resolvable outside daylight, or point anyone at a beach | PP-6/7, R-1, S-3, PD-5. At night multi-day items **sleep** (static, no glint, unclaimable; schedule § 3.4). Nothing is placed on a beach. Remembrance surfaces have no sea glass, no lamp, no motion, no haptics, no numbers. |
| Speak of speed or time pressure | No countdowns, timers, pace or "race" visuals; nothing timed (PP-8, PD-4). See §0.3 item 3. |
| Show weather | No weather, rain or morning fog, ever (cut, PD-4/PD-15). Night looks are dark-theme looks, never time-of-day (PD-5). |

### 0.3 Reconciliation with sibling specs (binding for implementers; rows 1–7 and 9 are settled by PD-1 … PD-17)

| # | earlier effects.md / engine.md / schedule.md text | This spec rules | Source |
|---|---|---|---|
| 1 | "points", "ledger", "ledger pill", "races" | **marks**, **Logbook**, **Play chip** (component `PlayChip`), **challenge**. Every announcement uses the product key in §5.4. | README glossary, PD-16 |
| 2 | Ledger pill top-right | **Play chip top-left, on a second chrome row** under the search chip; the trophy stays top-right (§3.5). effects § 3.12 / § 3.17 now agree. | product § 5.3 |
| 3 | `race_track` countdown, ghost, finish tape, chevron marquee | **(cut).** Nothing timed (PP-8, PD-4). v1's challenges (`ch-sweep`, `ch-krakenweek`) have no area of their own; (v2) area challenges show a static dashed ink outline + one flag pin (§2.2). | PD-4, PD-15 |
| 4 | `play_wake` ends with a "+50 welcome points" pop | The pop carries the **starting balance** (`play_start_balance`, product § 3.6.4); no welcome bonus exists. | product § 3.6.4 |
| 5 | `streak_float_line` | **(cut)** (PD-15). Kept weeks are Logbook lines only (`play_kind_week_run`); no float line, no "longest" copy. | PD-14, PD-15, PP-12 |
| 6 | engine: FX tap query wins within 24dp | **Pure trail/road hit test first**; Play takes a tap only when the `TappableTrailLayerIds` query has no hit (§3.5.4). Pins are lifted so their figure rect sits off the line. | PD-12, product PF3-5 |
| 7 | effects fallen tree / tide cut / fog bank (cut), creature glimpses, brambles, Prue's House | UI only for v1 content: exploration fog, the kraken, treasures, eggs (stamp), riddles, `ch-sweep` / `ch-krakenweek`, lore (Frog Rock, the ferry, history) and remembrance. Creatures, brambles, Prue's House, `ch-crossing` and the horn are **(v2)**. | PD-15, product v1 scope |
| 8 | effects playSeaGlass `#08665A` / `#63DCC6` | **`#037245` / `#45E0AE`.** The proposed values sit ΔE 8.7 (light) / 14.8 (dark) from spruce `primary`/`positive`, so a sea-glass `+25 marks` would read as a spruce `+0.5 pts` island delta. Final values are ≥ ΔE 23 from both (§1.1). effects § 1.2 must adopt them (PD-18). | measured |
| 9 | schedule cadence (frog moves, kraken at high tide, race weekends, `afterSunsetOk`, ≤ 30 mph) | Settled: kraken **Thu 06:00 → Sun 20:00, clock-based**, a stretch active only in daylight with the tide ≤ 8 ft MLLW (PD-8); finds in **daylight** only (PD-5); roads by the one **`roadSafety`** rule, ≤ 25 mph (PD-7); content per product § 4 (PD-9). What's On renders the scheduler's `agenda` (§3.15). | PD-5, PD-7 … PD-9 |
| 10 | engine PF-4 "`placedAt` is in sync with the pixels" | **Spike S4 FAILED** (`spikes/fx.md`): overlays slip up to one frame of camera motion under gestures. Anything that must coincide with map geometry (pin selection, ring, bump, halos, glints, casings) is **Layer tier**; the Overlay tier is used only for transient heroes that tolerate slip and is hidden or handed to its Layer part during gesture camera moves; chrome is screen-anchored. | engine PF-1, `spikes/fx.md` S4 |

---

## 1. Tokens

All Play tokens live next to the existing ones in `ui/theme/` and are read through `BtTheme`. Nothing here changes
an existing token value.

### 1.1 Color: four Play roles

```kotlin
// ui/theme/BtColors.kt — added
@Immutable data class PlayColors(
    val ink: Color,        // all Play line art, stamps, the kraken's ink casing, (v2) challenge outlines, art-tile contours
    val seaGlass: Color,   // reward accent: marks glyph, +n text (rules below), treasure fill, Play bar fill
    val lamp: Color,       // light only: lantern flame, glint core (always inside ink or as glow)
    val fog: Color,        // exploration fog patches / spray; alpha is part of the token
)
val PlayLight = PlayColors(ink = Color(0xFF22323C), seaGlass = Color(0xFF037245), lamp = Color(0xFFFFF1C9), fog = Color(0x8CF4F6F5)) // fog #F4F6F5 @ .55
val PlayDark  = PlayColors(ink = Color(0xFFCFE0E6), seaGlass = Color(0xFF45E0AE), lamp = Color(0xFFFFF0CF), fog = Color(0x407D9095)) // fog #7D9095 @ .25
// BtExtraColors gains `val play: PlayColors` (BtExtraLight.play = PlayLight, BtExtraDark.play = PlayDark).
// Brand-independent: identical in the trails and roads apps.
```

| Role | Light | Dark | Allowed | Never |
|---|---|---|---|---|
| `play.ink` | `#22323C` | `#CFE0E6` | Art contours (2.0dp contour / 1.25dp detail, effects §1.1), stamps, the kraken's ink casing, (v2) challenge outline, art-tile glyphs in chrome, ink pool ≤ 35 % α | Body text (use `onSurface`); fills > 24dp² at full α |
| `play.seaGlass` | **`#037245`** | **`#45E0AE`** | Marks glyph △, treasure fills, Play bar fill, focus ring of a selected Play pin, and **text** only for a `+n` marks delta on `surface` / `surfaceContainerLow` / `mapChrome` (≥ 5.2:1) | Any line along the network; areas > 16dp ⌀ at full α; text on any other surface (e.g. the sea-glass wash: 4.33 light) |
| `play.lamp` | `#FFF1C9` | `#FFF0CF` | Inside an ink outline, or as radial glow; on dark it always sits on a dark backing | A mark on its own; a line; text; a time-of-day look (PD-5) |
| `play.fog` | `#F4F6F5` @ .55 | `#7D9095` @ .25 | Exploration fog patches (`UNDER_STATUS`, effects § 3.18), kraken spray; wake paper wash | Above status halos, lines or labels; inside a quiet zone |

Derived (computed, not new roles; implement as named vals so no one re-mixes them):
- `play.seaGlassWash` = `seaGlass @ .14` over `surfaceContainerLow` (light → `#D1DED3`), `@ .18` (dark → `#1F4438`).
  Use: background of a **found / done** state (found treasure tile, done ChallengeCard band). Text on it:
  `onSurface` only.
- Rank and title chips use existing `tertiaryContainer` / `onTertiaryContainer` (contour brown = engraved brass).
- Map chrome stays `mapChrome` / `onMapChrome`.

**Contrast (measured, WCAG 2.1; script: relative luminance, sRGB; map values from `styles.md` Topo / Dark):**

| Pair | Light | Dark | Requirement |
|---|---|---|---|
| `ink` on surface / surfaceContainerLow / surfaceContainerHigh | 12.1 / 11.6 / 10.5 | 13.1 / 12.0 / 9.9 | ≥ 3 (art) |
| `ink` on map land / water / woods / road | 11.4 / 6.8 / 9.6 / 13.2 | 11.1 / 12.4 / 10.1 / 7.9 | ≥ 3 |
| `ink` on `mapChrome` (95 % over land) | 12.6 | 12.3 | ≥ 3 |
| `ink` on `tertiaryContainer` / `secondaryContainer` (art fills) | 10.4 / 10.3 | 6.8 / 6.8 | ≥ 3 |
| `seaGlass` on surface / surfaceContainerLow / mapChrome (**text allowed**) | 5.5 / 5.3 / 5.7 | 10.6 / 9.8 / 10.0 | ≥ 4.5 |
| `seaGlass` on surfaceContainerHigh / sea-glass wash (non-text only) | 4.8 / 4.3 | 8.1 / 6.4 | ≥ 3 |
| `seaGlass` on map land / water / woods / road | 5.2 / 3.1 / 4.4 / 6.0 | 9.0 / 10.1 / 8.2 / 6.4 | ≥ 3 (always with ink contour, 6.8 on water) |
| `lamp` vs its `ink` outline | 11.8 | — (dark: lamp on dark backing, 15.8 on surface, 14.8 on mapChrome) | ≥ 3 |
| `onSurface` on sea-glass wash | 10.7 | 8.5 | ≥ 4.5 |
| `onSurfaceVariant` on sea-glass wash | 5.6 | 6.3 | ≥ 4.5 |
| `onTertiaryContainer` on `tertiaryContainer` (RankBadge) | 12.9 | 7.2 | ≥ 4.5 |
| Fogged land (status-line surround) | `#ECEFE6` → `#F0F3EE` (fog lightens; partway contrast rises) | `#1D2925` → `#354341`; status halos draw above fog, so line-to-surround stays at the no-fog value | ≥ no-fog value |

**Distance from reserved hues (CIE76 ΔE, guard ≥ 20):** sea glass light vs spruce `#2E5E4E` 23.3, vs not-started
41.8, vs roads slate 50.1, vs every other status/recording/userTrack ≥ 73; dark vs `#93D3B6` 25.9, vs every other
≥ 41. Ink vs not-started 28.3 (light) / 23.4 (dark). Lamp vs partway 59.4 / 52.7. Ink light vs roads slate
`#2F5068` is 15.7: acceptable because slate is chrome-only (buttons, tabs) and ink is art-only; the guard test
checks Play vs **status + recording + userTrack + positive**, and `primary` is excluded only for `ink`.

CVD: under deutan/protan sea glass shifts toward a desaturated teal-gray, far from `complete` violet→blue (it never
appears as a line, so no confusion with status lines). The △ shape, the pebble and the `+` carry meaning, never hue
alone.

### 1.2 Type

No new families or sizes. Two named styles in `BtExtraType`:

```kotlin
val marksDelta = labelLarge.copy(fontFeatureSettings = "tnum")          // "+25" in claim pops, sheets, Logbook rows
val marksTotal = statLarge                                                // Fraunces 32 — static totals only (Logbook header, Me card)
```
Rules: Play chip number = `labelLarge` + `tnum` (it rolls). Riddle clues = `bodyLarge` system (Fraunces italic stays
reserved to the celebration trail name and Me caption, ux.md §1.3). Content titles in sheets = `titleLarge` (Fraunces).
Date stamps ("Found · 27 Oct") = `labelMedium` system caps, letter-spacing 1.2sp.

### 1.3 Shapes, spacing, sizes

No new shape tokens. Sizes added to `BtSpacing` (named so nobody improvises):

| Token | Value | Use |
|---|---|---|
| `playChipHeight` | 40dp visual, 48dp touch (`minimumInteractiveComponentSize`) | Play chip, Challenges chip |
| `playRowTop` | 64dp below the status-bar inset | top of the Play chrome row (search row 8 + 48 + 8) |
| `playMapTopPadding` | 128dp (+ inset) | camera fit top padding with Play on (pure: 72dp) |
| `playPin` | figure 28dp (22dp at z12.5–14), lift 30dp (26dp) | map pin anatomy (§2.2) |
| `playHit` | 48 × 48dp | every Play marker hit rect |
| `playArtTile` | 56dp (sheets), 40dp (rows), 32dp (logbook) | rounded `medium` paper tile holding an ink glyph |
| `playBar` | 6dp | Play progress bar (status ProgressBar is 8dp; Play never uses its ticks) |

### 1.4 Motion

Durations and easings are `BtDuration` / `BtEasing`. Play adds `PlayMotion` (effects.md §1.7) as **compositions** of
existing tokens, so there is no new curve:

| Name | Built from | Use |
|---|---|---|
| `overshootSettle` | `emphasizedDecelerate` to 108 % over 2/3 of the duration, then `standard` back to 100 % (`short4`) | Chrome only: Play chip bump, rank badge earn, (v2) stamp thunk. Never on map pins (Layer tier, S4) |
| `anticipate` | `emphasizedAccelerate` dip −8 % over first 30 % | wind-up before a lift (map heroes only) |
| `sway` / `drift` / `ballistic` | effects.md §1.7 | map effects only; never in chrome |

UI-level motion (this file):

| Interaction | Duration | Easing | Reduced motion |
|---|---|---|---|
| Play marker tap: the selected figure (1.15×, sea-glass ring) crossfades in over the pin, **Layer tier** (§2.2, S4) | ≈ short4 paint transition | MapLibre ease (accepted for layers, effects § 3.2) | instant swap |
| Camera pan to bring marker above the sheet | long1 | `emphasized` | jump |
| EncounterSheet in / out | medium2 slide + fade / short4 | MapSheet default (as TrailPeekSheet) | crossfade short3 |
| Sheet content swap (marker → marker) | medium1 crossfade + `animateContentSize` medium2 | `standard` | instant |
| (v2) `lore_stamp_ink` on first open after a find; v1 shows its final frame (stamp present, no motion) | 700 ms (effects §3.16) | as card | final frame |
| `claim_pop` → Play chip (claims only: results at Finish; on the map when server-derived marks arrive on sync) | 900 ms (effects §3.12) | as card | Play chip crossfades to new value (short3) and shows `+n` beside it 1.5 s |
| Play chip digit roll | short3 per changed digit | `standard` | crossfade short3 |
| Settings Play rows reveal | medium2 expand | `emphasizedDecelerate` | instant |
| Offer / Intro / Off / (v2) Consent modal sheets | M3 ModalBottomSheet default | — | crossfade short3 |
| FindMoment card in / out | medium2 (slide 16dp + fade) / short4 fade | `emphasizedDecelerate` / `standard` | crossfade short3 |
| Logbook / Results Play rows first show | fade + 4dp rise, short4, stagger 30 ms, max 8 rows | `emphasizedDecelerate` | instant |
| Play bar fill on first show | long2 | `emphasizedDecelerate` (as status bars) | final value |
| Riddle wrong: lantern shake | 360 ms (effects §3.9) | as card | none (text + haptic) |
| `play_wake` | 3000 ms (effects §3.17) | as card | static card 1.2 s, crossfades short3 |

**Reduced motion (BtTheme.motion.reduced)** — everything in ux.md §1.7, plus: no glints, ripples, sway, flicker,
drift or particles on the map; every hero is its `reducedAtMs` frame; marks never fly; FindMoment crossfades;
haptics and announcements unchanged. **"Moving map effects" off** (Settings, §3.1) applies the map half of these
rules without touching chrome motion. Battery Saver / Low Power applies both (effects §5). While a walk records,
ambient effects rest on their final frame on every map (engine PF-2); heroes still play.

### 1.5 Haptics (tokens from effects.md §1.8)

`tick` · `confirm` · `thud` · `reject`. Per-event mapping is in §4.4. Max one per 250 ms; none in background; none in
quiet zones (PF10-2).

---

## 2. Components

All new components live in `ui/play/` (UI) and `play/fx/` (map art), take `modifier: Modifier = Modifier`, and
render only under `if (play.enabled)`. Every interactive element ≥ 48dp. "Art" = an `FxArt` ImageVector (effects §1.1),
decorative, `clearAndSetSemantics {}`.

### 2.1 `PlayChip` (the Play chip) and `ChallengesChip`

```
 ╭──────────────────────────────╮   ╭──────────────────────────╮
 │ △ 1,240 · Salal Scout        │   │ ⚑ 2 challenges this week │      40dp visual, 48dp touch, 8dp gap
 ╰──────────────────────────────╯   ╰──────────────────────────╯
   ▲ 18dp marks glyph, seaGlass       ▲ 18dp pennant, play.ink
```

| | PlayChip | ChallengesChip |
|---|---|---|
| Surface | `mapChrome`, `PillShape`, 3dp shadow (elevation level 3) | same |
| Content | glyph △ 18dp `seaGlass` · `play_map_chip` (`%1$s · %2$s`: marks, rank title), `labelLarge` + `tnum`, `onMapChrome` | pennant 18dp `play.ink` · `play_challenges_chip` (plural), `labelLarge`, `onMapChrome` |
| Padding | start 12, end 14, gap 6 | same |
| Width | wrap; min 96dp; marks ≥ 10,000 shown as `1.2k` (product § 3.1) | wrap |
| Compact (measured: row 2 may use width − 32 − 56dp, the trophy column stays clear) | 1st step: Challenges chip compacts; 2nd step (still too wide, or font scale ≥ 1.3): rank title dropped → `△ 1,240` | `⚑ 2` (count only; a11y keeps the full phrase). At 360dp with a typical rank the chip is compact (mockup) |
| Tap | → Logbook (pushed in the Map tab stack) | → Challenges screen |
| Count | — | this week's challenges (week id), started or not: 0–2 in v1 (`ch-sweep`, `ch-krakenweek`) |
| Pressed | M3 ripple on the pill; scale .97 short2 | same |
| Loading (marks not yet computed after launch) | glyph + 40×12dp `SkeletonBlock` | hidden until the agenda is ready |
| Disabled / error | n/a (local values; never an error state) | hidden when 0 challenges |
| Quiet day (Mar 30) | **hidden**; the quiet banner takes the row (§3.5.6) | hidden |
| `claim_pop` target | bump `overshootSettle` short4 + digit roll (chrome, screen-anchored) | — |
| a11y | `Role.Button`; `play_map_chip_a11y` ("Play. 1,240 marks. Rank Salal Scout. Opens your logbook.") | `Role.Button`; plural string as label |
| Light / dark | token-driven; glyph sea glass on mapChrome 5.7 / 10.0 | ink pennant 12.6 / 12.3 |

### 2.2 `PlayMarker` anatomy (map layer art; effects.md §1.3 + overrides)

**Decision: every tappable Play marker is a PIN** — a figure lifted 30dp above its anchor on a 1.25dp ink stem to
a 4dp ink ground dot. Stamps (things printed flat on the chart) are never tappable. This overrides effects § 1.3,
which draws treasures as stamps (PD-18). Why: the pure trail hit test runs first (PD-12, PF3-5) and treasures sit on
lines, so a stamp on a line could never be tapped; a lifted figure gives Play its own hit target that sits off the
line point beneath it. Pins are Layer-tier `SymbolLayer`s (`icon-anchor = bottom`, the stem and dot in the image);
hit rects are `fx-*-hit` features queried by MapTab `onTap` (engine R7), never overlay boxes.

```
          ╭──────╮   ← hit rect 48×48dp centred on the FIGURE centre (not on the anchor)
          │ (◆)  │   ← figure 28dp: ink contour 2.0 / detail 1.25, ≤ 1 role fill
          ╰──┬───╯
             │       ← stem 1.25dp play.ink, lift 30dp (26dp at z12.5–14)
     ════════●═════  ← ground dot 4dp ink + SE shadow ellipse 18 % α; trail line untouched
```

Selected pin (**Layer tier only**, spike S4): the item's feature gets `feature-state {selected: true}` (S2 PASS) and
a second symbol layer on the same source shows the selected variant — figure at 1.15× with a 2dp `seaGlass` ring
baked into the image (non-text, 5.2+ on land) — crossfading in by `icon-opacity` paint transition (≈ short4), like
the glyph↔stamp swap of effects § 1.4. Other Play pins drop to 50 % α through the same paint expression. No
overshoot (layout `icon-size` takes neither feature-state nor transitions) and nothing positioned with `placedAt`,
because an overlay ring would slip off the pin by up to one frame of camera motion during a fling. Status lines are
**not** dimmed by a Play selection.

| Content | z 10.5–12.5 (island) | z 12.5–13 | z 13–14 | z 14–16 | z 16–18 | Tappable | Spent / found |
|---|---|---|---|---|---|---|---|
| Treasure (sea glass, agate, glass float, ship's bell) | per chart square: 1 item = 6dp sea-glass dot + 1.5dp ink ring; ≥ 2 = 20dp cluster disc (`mapChrome`, ink ring, count `labelSmall tnum`) | cluster / dot | pin 22dp pebble (tier figure) | pin 28dp + glint (≤ 6 in view) | pin 28dp (constant) | z ≥ 13 pin → sheet; z < 13 dot/cluster → camera to z14 on that square | pin keeps, figure becomes the open shell in ink 40 % α on `seaGlassWash`, no glint; tappable until the set washes away (opens the found sheet) |
| Easter egg | nothing | nothing | nothing | nothing until found | found: small pin 20dp (stamp figure in a ring) | z ≥ 15 after found | — |
| Riddle (`?` lantern, unlit) | dot (as treasure) | dot | pin 22dp lantern | pin 28dp, sway ±3° (z ≥ 15) | pin 28dp | z ≥ 13 | solved (armchair): lit lantern pin, still tappable; claimed: ink ring pin 14dp |
| Lore (frog, bell, mill wheel, helix house, battery…) | 10dp ink glyph only for kraken, frog, ferry | same | nothing else | pin 28dp story figure | pin 28dp | z ≥ 14 | met: figure keeps, ground dot gets a lamp core |
| Remembrance (memorial card) | nothing | nothing | nothing | quiet pin: 20dp ink ring with one horizontal rule, no fill, no shadow, no motion | same | z ≥ 14 | — (never changes) |
| Kraken attack (Thu 06:00 → Sun 20:00) | coast glyph 10dp ink curl in the water + coast label | lurk eye + 2 ripples at the offshore anchor (`OVER_STATUS` / `UNDER_STATUS`); ink casing on stretches (`CASING`) | same | same + intro hero once/day | same, tentacles geo-true (the road stroke is a Layer-tier line, S4) | eye + label: one 48dp-min rect → KrakenAttackSheet; the **inked line itself is a trail tap** | your repelled stretches: ink casing removed (per user) |
| (v2) Bramble (August, trails) | nothing | thorny ink casing on the trail (`CASING`) | + sprig pin 22dp at midpoint | pin 28dp | pin 28dp | z ≥ 13 | casing removed when cleared |
| (v2) Challenge area (none in v1) | dashed `play.ink` outline 1.25dp, dash 6/4dp (`UNDER_STATUS`) | + flag pin 22dp at centroid | same | pin 28dp | same | z ≥ 13 | outline 40 % α when done |
| Ferry (visual only, 05:00–23:00) | glyph 10dp | figure 0.6× | figure | figure 1.0× | 1.4× | z ≥ 14 → ferry lore card (armchair stamp) | — |
| Exploration fog | `UNDER_STATUS` fill per unwalked chart square; never inside a quiet zone | same | same | same | same | never | lifts per square |

Density: ≤ 40 Play markers rendered, ≤ 6 animated glints, ≤ 6 animated lanterns, ≤ 3 kraken anchors (effects §5).
Declutter: Play pins never overlap each other (`icon-allow-overlap = false` within the fx set, 4dp padding) with
`symbol-sort-key` priority **remembrance > kraken > riddle > treasure (bell > float > agate > sea glass) > lore >
found** ((v2) challenge after kraken, bramble after lore). Pins live in `OVER_LABELS` (`Anchor.Top`), which places
before the map labels, so labels yield to Play pins (engine PF-3, effects §1.3). Quiet zones show only the
remembrance pin.

Kraken coast label: a map `SymbolLayer` text, `play_kraken_coast` ("The kraken is at Manitou"), system font
13sp medium italic (the water-label convention), `play.ink` with a 2dp `surfaceBright` (light) / `#0B1410` (dark)
halo; contrast ink on halo 12.6 / 12.3.

Marker semantics: markers are not focusable on the map canvas (ux.md §5.5). Their text exists in the Nearby list,
What's On and the map node's custom action (§5.1). Each item has `play_marker_a11y` ("Sea glass, 25 marks, 240 m away")
for those lists.

### 2.3 `EncounterSheet` (product `PlayContentSheet` + `KrakenAttackSheet` + `RiddleSheet` + `ChallengeSheet`)

Built on `MapSheet` (non-modal, map stays interactive, drag handle, swipe down dismisses). **Decision:** make
`MapSheet` public and give it a `maxHeightFraction = 0.6f` parameter (content scrolls inside beyond 60 % of the map
height); no second detent. That is the only change to an existing component.

```
┌────────────────────────────────────┐
│               ───                  │  handle 32×4, 24dp row
│ ┌──────┐  Sea glass                │  art tile 56dp (paper `surfaceBright`, ink 1dp border, medium shape)
│ │ (◆)  │  Grand Forest West · 240 m away     ← title `titleLarge` Fraunces 2 lines; meta `bodyMedium` onSurfaceVariant
│ └──────┘  [△ 25 marks] [Washes away Sunday night] ← RewardChip + MetaChip (neutral pills, 32dp)
│                                    │
│ A pale green pebble, frosted by    │  body `bodyLarge`, ≤ 4 lines then "More" TextButton (pack text)
│ the Sound. …                       │
│ ⓘ Gate note (dark / tide / quiet)  │  `GateNote`: icon 18dp + `bodyMedium`, surfaceContainerHigh pill
│ ┌───────────────┐                  │
│ │  Walk there   │  (+ secondary)   │  PrimaryButton 56dp, icon directions_walk
│ └───────────────┘                  │
└────────────────────────────────────┘
```

| Variant | Art tile | Title / meta | Chips | Body | CTA | Notes |
|---|---|---|---|---|---|---|
| Treasure, live | tier figure (pebble / agate / float / bell), sea-glass fill | `play_tier_*` / place · `play_distance_away` | `play_reward`, `play_treasure_expires` | pack text | `play_walk_there` → nearest Play-safe trail/road detail | PF4-1 |
| Treasure, found | figure on `seaGlassWash` tile; dated stamp "FOUND · 27 OCT" (`play_found_on`), shown as its final frame ((v2) `lore_stamp_ink` inks it in) | same | `play_treasure_collected` chip (check glyph sea glass) + reward chip shows "+25" if claimed | found text | none | mockup frame 1 |
| Treasure, asleep (outside daylight) | figure 60 % α, no glint | same | reward chip | — | **none**; `GateNote` (moon) with `play_gate_dark` and the next civil dawn. (v2) shore-tagged items: `play_gate_tide` outside their low-water window | PF4-3 |
| Easter egg (found) | egg figure | `play_find_title_egg` / place | reward chip or `play_log_stamp` (armchair) | whole surprise text (no visual-only joke) | none | `play_egg_count` line at bottom, `bodySmall` |
| Lore | story figure | title / place | tag chip `play_tag_history` · `_myth` · `_tall_tale` · (v2) `_creature`; reward chip (first time) | 2–4 sentences; safety line where relevant (`play_safety_shore`; (v2) `_owl`, `_coyote`, `_newt`) in a `GateNote` with the shield icon; `play_sources` expandable row (48dp) listing links; ferry card adds `play_ferry_timetable_note` | `play_walk_there` if it has a proximity anchor and it is daylight; armchair-only cards (`lore-fay`, `lore-bloedel`) have none | Frog Rock at z ≥ 16: armchair egg stamp line appears |
| Remembrance | quiet glyph (ink ring + rule), no fill, no stamp animation | title | `play_tag_remembrance` (outlined neutral chip) | card text; `play_reward_none` | **none** | No sea glass, lamp, motion, haptic or number anywhere (PF4-4) |
| Kraken (`KrakenAttackSheet`) | lurk eye on `secondaryContainer` | `play_kraken_title` / `play_kraken_until` | `play_tag_tall_tale`, `play_kraken_reward` | `play_kraken_body`; `play_kraken_rule`; **Stretches** list of `KrakenStretchRow`; island progress `play_kraken_island_progress` with n dots (filled `play.ink` = sent back by ≥ 3 islanders); outcome line `play_kraken_retreat` / `_slipped`. Tide above 8 ft MLLW: `GateNote` (wave) `play_gate_tide` with the island time it drops; tide table missing or expired: `play_gate_tide_unknown`; outside daylight: `play_gate_dark` | none on the sheet; each stretch row has a 48dp "walk" icon button → that trail's detail, **absent** while the stretch is gated (PF4-2) | PF3-3, PF4-2, PF7-2/5, PF16-3; island progress offline → pure `OfflineNote` copy |
| (v2) Bramble | sprig figure | trail name / "a story bramble — the real trail is open" (pack text) | reward chip, `play_treasure_expires`-style meta from pack | rule from pack | `play_walk_there` | |
| Riddle | §2.4 | | | | | |
| Challenge | §2.5 (detail = ChallengeCard expanded in a MapSheet or pushed sheet) | | | | | |

`KrakenStretchRow`: 64dp; name `titleSmall` (spokenName for roads); below it a `playBar` 6dp with a 2dp × 10dp
`outline` tick at 60 % and the user's best share walked in one walk as sea-glass fill; trailing state text: "—" /
`play_kraken_stretch_progress` ("50% walked. 60% sends it back.") / `play_kraken_repelled_you` with check glyph (the
claimed bucket's marks, 60 / 70 / 80, show in the reward chip). Row tap → camera fits the stretch
(sheet stays). Merged semantics: "Manitou Beach Drive Northeast, 50 percent walked, 60 percent sends it back".

States (all variants): **default** as above; **pressed** ripple on CTA / rows; **disabled** — CTA never rendered
disabled, it is absent with a reason (GateNote); **loading** — distance unknown → meta omits distance (no spinner);
island progress loading → 3 skeleton dots; **error** — content paused while open (kill switch) → body replaced by
`play_content_gone`, chips removed, sheet stays until dismissed; **empty** — n/a.

a11y: sheet title is `heading()`; art is decorative; chips merge into one node ("25 marks. Washes away Sunday
night."); the dated stamp reads as part of the heading ("Sea glass. Found 27 October."); custom action "Close" as
MapSheet. Focus moves to the title on open (polite announcement of the title).

### 2.4 `RiddleSheet` + `RiddleAnswer` input + `RiddleAnswerPicker`

```
│ ┌──────┐ Riddle  ★★                │  lantern art (unlit / lit); stars = 16dp ink stars, a11y `play_riddle_stars_a11y`
│ │  ?   │ Grand Forest · 1.1 km away│
│ └──────┘ [△ 80 marks]              │
│ ┃ In 1991 islanders voted a bond   │  clue `bodyLarge`, 2dp play.ink rule at left (cartouche edge), 12dp indent
│ ┃ to save a forest from sale. …    │
│ Answer                             │  `labelLarge` onSurfaceVariant
│ ┌────────────────┐┌──────────────────┐
│ │Pick the place  ││Choose from a list│  2 × SecondaryButton 56dp (`play_riddle_pick_on_map`, `play_riddle_pick_list`)
│ └────────────────┘└──────────────────┘  (v2) count riddles: OutlinedTextField `play_riddle_count_field` (numeric + words) + Check button
│ Hint · A hint lowers the reward to 60 marks   ← TextButton `play_riddle_hint` + `play_riddle_hint_cost` bodySmall
```

- **Pick on the map:** the sheet collapses to a 72dp **pick bar** (`bodyLarge` "Pick the place on the map" + Cancel
  TextButton); map taps go to the answer hit test (trail/road lines and snapped landmarks, 44dp square as ux §6.1);
  the candidate gets the selection casing for 600 ms, then the sheet re-expands with the result.
- **Wrong:** `play_riddle_wrong` under the answer row (`bodyMedium` onSurface, no red, no ✕), lantern shake 360 ms,
  `reject` haptic, polite announcement. Unlimited tries.
- **Solved (armchair):** lantern lights (`riddle_light`), stamp line `play_riddle_solved_stamp`, CTA `play_walk_there`.
- **Claimed:** `play_riddle_claimed` + reward chip `+80`; no CTA.
- (v2) **Count riddle out of range:** field disabled, `play_riddle_count_near` as helper text (disabled is the only
  disabled state in Play, because the reason is shown inline). v1 riddles are place and feature answers only.
- `RiddleAnswerPicker` (full screen, pushed): `BtTopBar` "Answer" + docked `SearchField` (`play_riddle_list_search`) +
  list of `NavRow`s (trails/roads via GoalVocabulary + landmarks), alphabetical, 56dp. Tap = answer. Screen-reader
  default path (PF8-5).

### 2.5 `ChallengeCard`

```
┌──────────────────────────────────────┐  surfaceContainerLow, medium shape, padding 16, min 120dp
│ [Just you]           Sat – Sun        │  kind chip (neutral pill 28dp, `labelMedium`) · window `labelMedium` onSurfaceVariant
│ Weekend sweep                         │  `titleMedium` (list) / `titleLarge` Fraunces (detail)
│ Finish 3 trails this weekend. Short   │  `bodyMedium` onSurfaceVariant (goal copy)
│ ones count.                           │
│ ▬▬▬▬▬▬▬░░░░░░░░  1 of 3              │  playBar 6dp (track surfaceContainerHighest, fill seaGlass) + `play_challenge_progress` `numeric`
│ △ 100 marks                    ›      │  reward + chevron (whole card is one button)
└──────────────────────────────────────┘
```

| State | Look |
|---|---|
| Active | as above |
| Done | top band 4dp `seaGlassWash` → full card bg `seaGlassWash`; `play_challenge_done` chip with check glyph; reward shows `+100` in `onSurface` (wash rule) |
| Ended (not reached) | 60 % α art, `play_challenge_ended` replaces window; progress text stays ("2 of 3"); no reward chip |
| Island (`ch-krakenweek`) | kind chip `play_challenge_island`; progress is shared ("3 of 4 stretches", `play_challenge_progress`); a second line `play_challenge_band` when known (`{band, count}`; for counts under 5 see §7.2 item 2); never a list of people (PF9-4) |
| Upcoming (this week, window not open yet) | full α; window shown, progress row omitted |
| (v2) First wave earned | small `play_challenge_first_wave` chip with `play_challenge_first_wave_body` in a11y |
| (v2) Hidden | `ch-bestweek` not rendered for the first 4 weeks |
| Loading | skeleton card 120dp |
| Pressed | ripple on card |

Never a countdown, never a clock icon, never "h:mm left" (PP-8, PD-4); windows are dates/days in island time. (v2)
`ch-crossing` detail shows `play_challenge_next_departures`. Merged semantics: "Weekend sweep. Just you. Saturday to Sunday. 1 of 3.
100 marks."

### 2.6 `LogbookRow` (+ `LogbookGroupHeader`, `LogbookTotals`)

```
 ┌────┐ Sea glass on Rose Loop                  +25   ← 56dp min; art tile 32dp (kind glyph, ink on surfaceContainerHigh circle)
 │ ◆  │ Weekly treasure, common                        what `titleSmall`; why `bodySmall` onSurfaceVariant; +n `marksDelta` seaGlass
 └────┘
```
| Entry | Trailing | Extra |
|---|---|---|
| Normal | `+25` (`play_log_line`) in `seaGlass` | — |
| Daily cap | `+0` in onSurfaceVariant | why = `play_log_cap` |
| Stamp (armchair) | stamp glyph 18dp ink, label `play_log_stamp` | no number |
| Remembrance visit | nothing | kind `play_kind_visit`; no number at all (product § 3.8) |
| `local_only` | number in onSurfaceVariant | third line `play_log_local_only` `bodySmall` with cloud_off 14dp |
| Start balance | `+1,296` | kind `play_kind_start_balance` |
| Restored (reinstall) | `+2,410` | kind `play_kind_restored`; why = `play_log_restored` |

`LogbookGroupHeader`: day header `labelLarge` onSurfaceVariant sticky ("Sun, Nov 1"); walk subheader 48dp NavRow-style
`play_log_walk_group` ("Walk · 2.0 km") › → WalkDetail; week-close subheader `play_log_week_close` (no chevron), on
the Sunday of the closing week id. Dates only, never a time of day
(PF11-3). `LogbookTotals` (screen header, §3.13). Row semantics merged: "Treasure. Sea glass on Rose Loop. Weekly
treasure, common. Plus 25 marks."

### 2.7 `RankBadge` and the title ladder

- **RankBadge** (chip): 28dp pill, `tertiaryContainer` bg, `onTertiaryContainer` text `labelMedium`; leading 14dp
  rank mark = the survey △ with 0–3 tick marks under it (ranks 1–3 one tick, 4–7 two, 8–11 three) drawn in
  `onTertiaryContainer`. Non-interactive (used in the Play chip a11y, Me card, results rank-up line, (v2) Play board rows).
- **RankUpLine** (results / celebration): 56dp row, `tertiaryContainer` bg, large shape, RankBadge + `play_rank_up`
  `titleSmall`; `confirm` haptic once; polite `play_rank_up_a11y`. Never a modal (PF6-3).
- **RankLadderRow** (Ranks screen, 11 rows, 72dp): leading rank mark 32dp; title `titleMedium`; `play_rank_from`
  ("from 1,500 marks") `numeric` onSurfaceVariant; flavour `bodySmall`. Earned: mark filled `play.ink`, trailing `play_rank_earned`.
  Current: row bg `tertiaryContainer` @ 60 % over surface, 2dp `tertiary` left bar, `play_ranks_current` above the list.
  Future: mark outlined, text onSurfaceVariant. a11y `play_rank_row_a11y`.
- **PlayBar to next rank**: `playBar` 6dp, fill `seaGlass`, track `surfaceContainerHighest` (4.5:1 light); label
  `play_log_next_rank` ("709 to Madrona Wanderer") `numeric`.

### 2.8 `WhatsOnRow`

64dp min; leading art tile 40dp; title `titleSmall` (one line); window `bodySmall` onSurfaceVariant
(`play_challenge_window` with island-time days/times: "Thu 6 am – Sun 8 pm"; `play_island_time` appended when the
phone's zone is not island time); trailing: gate glyph 20dp (sun = daylight only, wave = waits for the tide, moon =
asleep until daylight) **or** chevron. Tap → Map tab with the item selected and its EncounterSheet open (camera fits
the item, z ≥ 14) — or ChallengeCard detail for challenges. Remembrance quiet day row has no chevron glyph color
change and no art motion. Merged semantics "The kraken is at Manitou, Thursday 6 am to Sunday 8 pm, waits for the
tide".

### 2.9 `PlayNearbyStrip` / `NearbyRow` / `FindMoment` (record)

- `NearbyRow`: 48dp; glyph 24dp; `play_nearby_row` ("Sea glass · 120 m") `bodyMedium`; trailing found state
  (`play_nearby_found`, check glyph `seaGlass`). Distances update ≤ every 10 s; rows are not live regions.
- `FindsChip` (record sheet header, only when ≥ 1 find): `StateChip` variant, leading △ glyph sea glass,
  `play_record_finds` ("2 finds").
- `FindMoment` card: §3.8.

### 2.10 `OptInCard` (offer + intro + off + consent content)

One content block reused by `PlayOfferSheet`, `PlayIntroSheet`, `PlayOffSheet`, (v2) `PlayBoardConsentSheet` (all M3
`ModalBottomSheet`, `surfaceContainerLow`, extraLarge top corners, scrim .32, drag handle):

```
┌────────────────────────────────────┐
│              ───                   │
│   [ illustration 160dp, Canvas ]   │  offer/intro: island + 3 glints + a tentacle curl at the east shore + water lines;
│                                    │  off: the island under exploration fog; (v2) consent: two pins and a trophy-less list
│ Want a little more island in       │  `headlineSmall` Fraunces, 3 lines max
│ your walks?                        │
│ Play adds treasures, riddles, the  │  `bodyLarge` onSurfaceVariant
│ odd kraken and island stories …    │
│ (intro only: 3 bullets with 24dp ink glyphs, `bodyLarge`)
│ ┌────────────────────────────────┐ │
│ │           Try Play             │ │  PrimaryButton 56dp full width
│ └────────────────────────────────┘ │
│            Not now                 │  TextButton 48dp, centred
└────────────────────────────────────┘
```
Illustrations are Canvas from tokens (both themes), decorative. States: default; pressed; **loading** (intro Start:
PrimaryButton `loading = true` while the starting balance computes, ≤ 1 s typical); error: n/a (local).

### 2.11 "The island wakes up" moment (`PlayWake`, effects §3.17)

```
┌────────────────────────────┐
│ (paper wash over the live  │  surface α .82 (.85 dark) + grain
│  map, map faintly visible) │
│          ✣                 │  compass rose 56dp at the NE water
│      The island is awake   │  `headlineMedium` Fraunces, centred at 30 % height
│  Treasures, riddles and    │  `bodyMedium` onSurfaceVariant (`play_wake_body`)
│  island stories are on     │
│  your map now.             │
│   ((( island coast )))     │  coast inks in exactly over the real coastline; rings converge
│     · ◆   · ?   · ⚑        │  glyph wave from Winslow
│                            │
│ Play chip slides in at its real position (top-left, §3.5) at 2600 ms, then the start-balance pop
└────────────────────────────┘
```
Full-screen above the Map tab including chrome; bottom bar stays visible but inert for ≤ 3 s; any tap at t < 2200
jumps to the lift (custom a11y action `play_wake_skip`). A Moment-tier scene (engine PF-1): the coast and glyphs
are drawn by the Moment over a paused map, so nothing depends on overlay/map sync (S4). Plays once (opt-in) +
Settings replay + showcase; never on the Mar 30 quiet day (§3.1). Reduced: static card 1.2 s → crossfade.

### 2.12 Other new pieces (brief)

| Component | Spec |
|---|---|
| `RewardChip` | 32dp neutral pill `surfaceContainerHigh`; △ 16dp `seaGlass` + `play_reward` `labelLarge` onSurface. Claimed: "+25" in onSurface. Remembrance: never rendered. |
| `MetaChip` | 32dp neutral pill, `labelMedium` onSurfaceVariant (expiry, window, "Found"). |
| `GateNote` | Inline row, `surfaceContainerHigh` small-shape box, icon 18dp (sun / wave / moon / quiet / shield) onSurfaceVariant + `bodyMedium` onSurface. Never error colors. |
| `PlayBar` | 6dp rounded; track `surfaceContainerHighest`; fill `seaGlass`; optional single tick `outline` 2×10dp. `progressBarRangeInfo` semantics. |
| `PlayBadge` (lists, product `play_list_badge_*`) | 24dp outlined pill, 1dp `play.ink` @ 60 %, glyph 14dp + `labelSmall` onSurfaceVariant; sits under the subtitle (never replaces the StatusChip). |
| `PlayLegendGroup` | In `StatusLegend` expanded card, after "Not counted": 1dp `outlineVariant` divider, header `play_legend_group` `labelMedium`, rows 40dp: 32×16dp art swatch + label (`play_legend_fog`, `_treasure`, `_kraken`, `_riddle`, `_lore`; (v2) `play_legend_challenge`). Swatches use Play art only, never a status pattern. |
| `PlayCard` (Me) | §3.12. |
| (v2) `PlayBoardRow` | LeaderRow variant: rank (40dp, Fraunces titleLarge, top 3 `primary`), name `titleSmall` + RankBadge-free title line `labelMedium` onSurfaceVariant, marks `numeric` 72dp right-aligned. a11y `play_board_row_a11y`. YouRow identical to pure (primaryContainer, 2dp primary bar). |
| `QuietBanner` | §3.5.6. |

### 2.13 New design-system primitives

`factory.design_system = none`; there is no `sloopworks-ui` dependency to change. The app's own theme gains
primitives that must land **as one theme work item before any Play screen** (so nothing ships a one-off):

1. `PlayColors` (4 roles + `seaGlassWash`) in `BtExtraColors`, with the contrast and ΔE guard tests (§1.1).
2. `PlayMotion` (`overshootSettle`, `anticipate`) in `BtMotion.kt`, built only from `BtEasing`/`BtDuration`.
3. `BtExtraType.marksDelta` and the `BtSpacing` Play sizes (§1.3).
4. `FxArt` glyph set (ImageVector, effects §1.1): marks △ + rank ticks, 4 treasure tiers, egg stamp, lantern
   unlit/lit, story figures (frog, bell, mill, helix house, battery, ferry, kraken eye, pennant; (v2) owl feather,
   Prue's house, sprig), quiet ring, gate icons (sun, wave, moon, shield). One source per figure for both map
   rasters (including each pin's selected variant, §2.2) and chrome.
5. `MapSheet` made public with `maxHeightFraction`.
6. `PlayBar` and `RewardChip` (small, but shared by 6 screens; they belong next to `Progress.kt` / `Buttons.kt`).

---

## 3. Screens

Common rules: Play screens follow ux.md §3 common states (loading skeletons, inline offline notes, never a blocking
error). Play data is local first (pack bundled, Logbook on device); only island aggregates (kraken island progress,
challenge bands), the Logbook restore and (v2) the Play board need the network. All Play screens are pushed in the current tab's stack and keep the bottom bar.

### 3.1 Settings › Play (section between Units and Account; product § 5.1)

```
┌────────────────────────────────────┐
│ ← Settings                          │
│ …Units                              │
│ Play                                │  Group header (titleMedium, primary) — `play_settings_section`
│ Play                          [●  ] │  SettingSwitchRow; body `play_settings_toggle_body`
│ ── shown only when on (expand medium2) ──
│ Play sounds                   [  ○] │  `play_settings_sounds` + body; default off
│ Moving map effects            [●  ] │  `play_settings_ambient` + body; default on (UX addition, PD-18)
│ (v2) Show me on the Play board [  ○]│  `play_settings_board` + body; signed out → NavRow `play_settings_board_signed_out` → Profile
│ Ranks                             › │  NavRow `play_settings_ranks` → Ranks
│ Logbook                           › │  NavRow `play_settings_logbook` → Logbook
│ How Play works                    › │  NavRow `play_settings_about` → PlayAboutSheet (modal: `play_about_*`)
│ Replay the island waking up         │  NavRow `play_settings_replay`, no chevron → Map tab + PlayWake replay
│ Play clock (debug)                › │  debug builds only, `play_settings_clock_debug` → PlayClockDebug (§3.19)
│ Account …                           │
└────────────────────────────────────┘
```
- Toggle on: the switch shows on (pending) and `PlayIntroSheet` opens; Start → sheet closes, app switches to the Map
  tab (Settings stays in the Me stack), `PlayWake` plays, then snackbar `play_start_balance`. Dismiss without Start →
  switch animates back off (PF1-3).
- Toggle off: `PlayOffSheet` (`play_off_title`, `play_off_body`, `play_off_confirm` filled, `common_cancel`); cancel
  keeps it on.
- Rows match product § 5.1 (toggle; when on: sounds, (v2) board, ranks, Logbook, about) plus three UX rows:
  "Moving map effects" (§1.4: turns map ambient loops off; reduced motion and Battery Saver / Low Power force it
  off), the wake replay and the debug-only Play clock. PD-18 adds the ambient row to product § 5.1.
- (v2) Board switch on for a signed-in user calls `PUT /v1/play/board-consent`; failure → revert + snackbar (pure
  "couldn't save" pattern). Offline → switch disabled with helper text from the pure offline copy. Turning Play off
  while visible sends the one consent carve-out (PD-1).
- **Quiet day (Mar 30):** the section is unchanged and Play can still be turned on or off, but nothing plays: Start
  skips `PlayWake` (no effects on a quiet day) and lands on the Map with the quiet banner, then the snackbar
  `play_start_balance`; the replay row is hidden for the day.
- States: loading n/a; offline affects only (v2) the board switch.

### 3.2 PlayIntroSheet (PF1) and PlayWake

Intro = `OptInCard` with `play_intro_title`, bullets `play_intro_1..3` (glyphs: pebble, △, chart), `play_intro_start`.
Screen reader: title, then bullets, illustration decorative (PF1-4). Then §2.11.

### 3.3 The one-time Offer (PlayOfferSheet, PF2)

- Trigger: the first finished walk's Results sheet closes (after any celebration), RecordFlow closes, the previous tab
  shows, **then** after 400 ms (medium4) the offer rises. Never over the celebration, never while recording.
- Layout: `OptInCard` with `play_offer_title`, `play_offer_body`, `play_offer_accept`, `common_not_now`.
- Accept → the same sheet crossfades (medium1) to the Intro content (no second sheet) → Start → PlayWake.
- Scrim tap, swipe down, back, Not now = dismissed forever (PF2-3).
- Mockup frame 2.

### 3.4 PlayOffSheet / PlayAboutSheet / (v2) PlayBoardConsentSheet

`OptInCard` layouts with the product keys (§6). About: scrollable modal, `play_about_title` + 4 short sections with
24dp glyphs (`play_about_marks`, `_claim`, `_safety`, `_privacy`). (v2) Consent: `play_board_consent_title/body`,
`_yes` (filled), `_no` (text).

### 3.5 Map tab with Play on

#### 3.5.1 Layout

```
┌────────────────────────────────────┐
│ ▓ status bar                       │
│ ┌────────────────────────┐ ┌──┐    │  row 1 (unchanged): SearchChip (weight 1) + Trophy 48dp
│ │ 🔍 Find a trail        │ │🏆│    │  y = inset + 8
│ └────────────────────────┘ └──┘    │
│ ╭─────────────────────╮╭─────╮     │  row 2 (Play): PlayChip + ChallengesChip, start-aligned, 8dp gap
│ │ △ 1,240 · Salal Scout││ ⚑ 2 │     │  y = inset + 64 (playRowTop); LoadingChip, when shown, sits above it
│ ╰─────────────────────╯╰────────╯  │
│                                    │
│      (map: fog, ink, pins)         │  camera fit top padding = inset + 128 (playMapTopPadding)
│         ⌂?          ◆              │
│ ┌──┐                         ┌──┐  │  legend (gains Play group) · locate — unchanged
│ └──┘                         └──┘  │
│ ⓘ                                  │
├────────────────────────────────────┤
│ EncounterSheet / TrailPeekSheet    │  one sheet at a time
├────────────────────────────────────┤
│ bottom bar                          │
└────────────────────────────────────┘
```
- Row 2 never shows anything on the right edge: the right column (trophy, locate) stays clear so the chart keeps its
  frame. Chips compact by measurement (§2.1: Challenges chip first, then the Play chip's rank title); at font scale
  ≥ 1.5 the Challenges chip wraps under the Play chip (row 2 becomes 2 × 48dp; fit padding adds 56dp).
- `TrailMap` `controlsTopPadding` (scale bar / compass placement) = 120dp with Play on (pure 64dp).
- Mockup frame 1.

#### 3.5.2 Marker density

Per §2.2: island zoom shows dots and per-square clusters, never figures; figures from z13; glints only z ≥ 12.5,
max 6; at most 40 markers; a new user typically sees this week's treasures (12 trails / 20 roads) + the permanent
riddles + (Thu–Sun) 1 kraken coast + a few lore pins across the island — about 1 per chart square.

#### 3.5.3 Fog and the honest map

Exploration fog sits in `UNDER_STATUS`, under the status halos (engine PF-3, effects § 3.18), at 55 % / 25 % α;
status lines are fully readable through it (PF3-2). It is never drawn inside a quiet zone. Legend footnote
unchanged; the Play legend group explains fog (`play_legend_fog`).

#### 3.5.4 Tap priority (MapTab `onTap`; PD-12, product PF3-5: a pure trail hit wins)

0. Riddle pick mode (§2.4) → the answer hit test takes every tap until the pick bar closes.
1. **The pure trail/road hit test first** (`TappableTrailLayerIds`, ux.md §6.1, road-end pins first). A hit → the
   pure behaviour (TrailPeekSheet), and any Play selection is cleared. A tap on an inked kraken stretch is a **trail
   tap** (the casing is not a target; so are (v2) bramble casings and challenge outlines).
2. Only when step 1 has **no hit**: query `fx-*-hit` features (pin figure rects, kraken eye/label, cluster discs).
   - Hit a **cluster/dot** (z < 13) → camera to z14 centred on that chart square (long1; jump under reduced motion).
     No sheet.
   - Hit a **pin** (or kraken eye/label) → select it (Layer-tier selected variant, §2.2), clear any trail selection
     (peek closes, dimming removed), open its EncounterSheet, pan (no zoom change) so the pin sits in the free map
     area above the sheet if covered. No custom haptic: marker taps get the platform click feedback only.
3. Nothing → close any sheet, clear selections.
Pins are lifted so the figure rect sits off its own line; where another line runs under a figure, that part of the
rect is a trail tap and the item stays reachable by its upper half, the "Play nearby" action (§5.1) and What's On.
Back closes the open sheet first (as pure). Recording: Play pins are not tappable on the record map (as trails).

#### 3.5.5 Encounter sheet over the map

The EncounterSheet replaces the peek in the same bottom stack (`stackHeight` logic unchanged), so legend, locate,
attribution and snackbars rise with it exactly as for the TrailPeekSheet. Selecting another pin while open swaps
content (§1.4). Max height 60 % of the map; the map stays interactive above it.

#### 3.5.6 Special states

| State | Map with Play |
|---|---|
| Loading (pack/agenda not ready, first ~200 ms) | Pure map; row 2 shows the Play chip skeleton; no pins until the agenda is ready (no spinner). |
| Offline | No change (pack bundled). Kraken island progress in its sheet shows the pure OfflineNote. |
| Tide above 8 ft MLLW (kraken window) | The attack still shows all window long (eye, label, ink casing); its sheet shows `play_gate_tide` with the time the tide drops; stretch rows lose their walk button (PF4-2). |
| Missing or expired tide table | As above with `play_gate_tide_unknown`; every stretch stays inactive (fail closed, PF16-3). |
| Night (outside daylight) | Multi-day items **sleep** (schedule § 3.4): pins stay as readable plans for tomorrow, static (no glint, sway or flicker), sheets show `play_gate_dark` with the next civil dawn. The ferry sprite shows 05:00–23:00 only. Dark theme is the only night look (PD-5). |
| Quiet day (Mar 30) | Row 2 = `QuietBanner`: full-width `mapChrome` large-shape card (min 56dp), quiet glyph 20dp + `play_quiet_banner` `bodyMedium` + TextButton `play_quiet_banner_action` → memorial card. No pins except remembrance, no kraken, no effects, no sounds, Play chip and Challenges chip hidden. Walk-derived marks still accrue silently (product § 4.6.3). |
| Inside a quiet zone (memorial 200 m / cemetery 100 m / owner-tagged) while viewing | Only the remembrance pin renders within the zone; no fog, no effects there. |
| Error | Play never shows an error on the map; pack failures fall back to the bundled pack silently (debug log). |
| Signed out | No change (only sync, the Logbook restore and (v2) the Play board need an account). |

### 3.6 EncounterSheet variants — see §2.3. Kraken sheet = mockup frame 5.

### 3.7 Riddle — see §2.4.

### 3.8 Record screen with Play on

```
┌────────────────────────────────────┐
│ ⌄   [● GPS good] [Unverified ⓘ]    │  unchanged chips row
│ ╭────────────────────────────────╮ │  FindMoment card (only when sensed): y = inset + 64,
│ │ ┌────┐ Sea glass, found    +25 │ │  width − 32 (max 400), mapChrome, large shape, 6dp shadow, padding 12;
│ │ │ ◆  │ Found on this walk.     │ │  art 48dp; title `play_find_title_*` titleMedium; body `play_find_provisional`
│ │ └────┘ Confirmed when you finish.│ │  bodySmall onSurfaceVariant; trailing "+25" marksDelta seaGlass
│ ╰────────────────────────────────╯ │  auto-dismiss 4 s or tap; never covers Finish
│      (record map: fog thins, ink recedes live, pins not tappable, ambient at rest)
│ ┌────────────────────────────────┐ │
│ │ ● Recording          [△ 2 finds]│ │  FindsChip appears in the header row once ≥ 1 find
│ │ 01:08:22   2.41 mi             │ │
│ │ Trails this walk (3)        ⌃  │ │  pure live list first, unchanged
│ │ …LiveTrailRows                 │ │
│ │ Nearby                         │ │  SectionHeader `play_nearby_header` (expanded sheet only)
│ │ Found on this walk. Confirmed when you finish. ← bodySmall caption, once, only if ≥ 1 found
│ │ ◆ Sea glass · 120 m     Found ✓│ │  ≤ 3 NearbyRows within 400 m, by distance
│ │ ? Riddle answer · 340 m        │ │
│ │ ┌──────────── Finish ─────────┐│ │
└────────────────────────────────────┘
```
Rules (product § 3.6.1, § 5.3, PF5; engine PF-2, PF-8, PD-12):
- RecordScreen's own `TrailMap` is a gated MapFx call site; sensing runs in the graph-level `PlaySensing` observer,
  so finds are sensed and claimed with the Record screen closed or the screen off. Live sensing is a nicety; Finish
  is the truth.
- While recording, **ambient effects rest on their final frame** (no glints, sway, ripples or drift); heroes still
  play (`treasure_found`, `frog_rock`; eggs use the reduced stamp crossfade, `easter_egg_reveal` is (v2)). The
  FindMoment card is chrome, screen-anchored, so it does not depend on overlay/map sync (S4).
- No marks counter on the record screen: finds are provisional and the Play chip is not shown here (keeps the walking
  UI calm and honest). Provisional marks appear only on the FindMoment card and the Nearby caption, always with
  `play_find_provisional`; `claim_pop` never plays mid-walk (PD-14).
- Kraken stretch repelled mid-walk (≥ 60 % in this walk, provisional): the same FindMoment with title
  `play_kraken_repelled_you`, the bucket's marks, and `play_find_a11y` (polite).
- Nothing is sensed outside daylight, with GPS accuracy worse than the item's radius, above 4.0 m/s over the last
  60 s, or inside a quiet zone (product § 3.6.1).
- Empty Nearby: section hidden (not an empty state; the walk UI stays short). If nothing is nearby for the whole
  walk, nothing Play shows.
- Screen off: no moment; notification suffix `play_notif_finds`.
- Quiet zone: no moment, no haptic, no Nearby rows for the zone; a remembrance visit adds a silent Logbook stamp.
- Mar 30 quiet day: nothing Play on the record screen.

### 3.9 Results sheet with Play on (PF6)

```
│ …pure content (headline, island line, trails, verification, details) unchanged and first
│ Play                          65 marks this walk │  SectionHeader `play_results_header` + trailing `play_results_total` (numeric)
│ ◆ Sea glass on Grand Forest West           +25  │  LogbookRows (this walk's entries, rule order: coverage,
│ ⌁ New ground                               +21  │  completions, squares, day, finds, challenges)
│ ☼ Walking day                              +15  │
│ ┌──────────────────────────────────────────────┐│
│ │ △ New rank: Salal Scout                       ││  RankUpLine (only on rank-up)
│ └──────────────────────────────────────────────┘│
│ Not claimed                                      │  `play_unclaimed_header` (titleSmall), only if any
│ ◇ Agate · It was after dark                      │  outline glyph onSurfaceVariant, reason `play_unclaimed_*` `bodySmall`; no number
│ ┌────────────── Done ──────────────┐             │
```
Empty: `play_results_none` (bodyMedium onSurfaceVariant). Rows: first-show stagger (§1.4); each line's `+n` rolls
into the section total (`claim_pop` results variant, effects § 3.12; reduced: static total). Polite live region on
the section header once: "65 marks this walk". Relaunch with `summary_shown` → not shown again (PF6-4). Marks shown
here are the client's display values; the server derives the marks on sync (PD-3), and a rejected claim later shows
as `local_only` in the Logbook.

### 3.10 Celebration lines

Up to 2 lines under the existing body/milestone lines, before the button, fading in with the stats (1700–2000 ms):
`play_celebrate_marks` ("+300 marks", △ glyph 18dp `seaGlass` + `bodyLarge` onSurface) and `play_rank_up`
(`bodyLarge`, RankBadge mark). Appended to the celebration's merged announcement. No other change.

### 3.11 Detail and lists

- TrailDetail / RoadDetail: section `play_detail_section` (goal) after facts, before Community: up to 4
  `WhatsOnRow`s for content on this feature; empty → section hidden.
- Trails/Roads list: `PlayBadge` under the subtitle on rows with content this week; filter chip `play_list_filter`
  at the end of the chip row (neutral FilterChip, never status-colored). Count line unchanged.

### 3.12 Me › PlayCard (under the stat tiles)

```
┌──────────────────────────────────────┐  surfaceContainerLow, medium shape, padding 16
│ Play                                 │  `play_me_card_title` titleMedium, heading
│ 2,291                  [△ Salal Scout]│  marksTotal (Fraunces 32) + "marks" bodyMedium; RankBadge right
│ ▬▬▬▬▬▬▬▬▬▬▬░░░░  709 to Madrona Wanderer│  PlayBar + `play_log_next_rank`
│ Logbook                            › │  48dp rows, dividers outlineVariant
│ What's on                          › │  `play_me_whats_on`
│ Island stories        12 of 24 met › │  `play_me_stories` + `play_stories_count`
│ (v2) #14 on the Play board         › │  `play_me_board_row` / `_hidden` / signed out: `play_board_link`
└──────────────────────────────────────┘
```
Logbook row = `play_me_logbook`; the total is `play_me_marks` set as `marksTotal` + `bodyMedium`. (v2) Leaderboard
screen: text link `play_board_link` at the bottom (Play on only).

### 3.13 Logbook screen (PF11)

```
┌────────────────────────────────────┐
│ ← Logbook                          │  BtTopBar `play_log_title`
│ ┌────────────────────────────────┐ │  LogbookTotals: surfaceContainerLow, medium shape, padding 16
│ │ 2,291 marks                    │ │  marksTotal + `play_log_total`
│ │ 185 this week                  │ │  `play_log_week` bodyMedium onSurfaceVariant ((v2) `play_log_season`)
│ │ [△ Salal Scout] ▬▬▬▬░░ 709 to Madrona Wanderer │
│ │ Easter eggs: 7 of 40 found     │ │  `play_egg_count` bodySmall (no float line: `streak_float_line` is cut)
│ └────────────────────────────────┘ │
│ Sun, Nov 1                         │  sticky day header
│ Week close                         │  `play_log_week_close` (week id 2026-10-26 closes Mon 00:00, logged Sunday)
│ ◎ Kept week, 4 in a row      +70   │  `play_kind_week_run`
│ Walk · 2.0 km                    › │  `play_log_walk_group` → WalkDetail
│ ◆ Fairy door                 +20   │
│ ⚑ Weekend sweep              +100  │
│ …                                  │
└────────────────────────────────────┘
```
Paged 50 entries; newest first; totals equal the sum (PF11-4); dates only, never a time of day (PF11-3). After a
reinstall the first entry is "Restored from your account" (PF15-7). States: loading = totals skeleton + 6 skeleton
rows; empty = `EmptyState` (water lines) `play_log_empty`; offline n/a; `local_only` rows per §2.6. Mockup frame 3
(its float line and "this season" line predate the cut; this text wins).

### 3.14 Ranks screen (PF12)

`BtTopBar` `play_ranks_title`; header `play_ranks_current` (`headlineSmall` Fraunces) + `play_ranks_next` + PlayBar;
then 11 `RankLadderRow`s. No empty state (everyone is at least Walk-on).

### 3.15 What's On screen (schedule SD-10, § 5.2, § 6; reached from Me › PlayCard and the map node's custom action)

The screen is a direct rendering of `PlayScheduler.agenda(now, ctx, user)` → `PlayAgenda` (schedule § 5.2): **Today**
= `agenda.active`, **Coming up** = `agenda.upcoming` (next 7 days), and the day line = `agenda.day` (`DayInfo`). It
adds no item, rule or window of its own.

```
┌────────────────────────────────────┐
│ ← What's on                        │  `play_whats_on_title`
│ Week of Oct 26                     │  (wireframe: Saturday Oct 31) `play_whats_on_week_of` (the week id = its Monday) `bodySmall` onSurfaceVariant
│ Daylight 7:31 am – 6:34 pm · Next ferry arrives 10:05 am │  `agenda.day`: `play_whats_on_daylight` · `play_whats_on_ferry` (display only)
│ Today                              │  SectionHeader `play_whats_on_today` = agenda.active
│ [eye] The kraken is at Manitou     Thu 6 am – Sun 8 pm  〰   │  WhatsOnRow; gate glyph wave while the tide is above 8 ft
│ [◆] 12 treasures this week · 3 found  Washes away Sunday night  ›  │  `play_whats_on_treasures` + `play_treasure_expires`
│ [?] Bought with a bond      ★★                          ›   │  each active riddle: pack title + stars (permanent, no window)
│ [⚑] Weekend sweep            Sat – Sun                     ›   │  challenge title + `play_challenge_window`
│ Coming up                          │  `play_whats_on_coming` = agenda.upcoming (7 days)
│ Mon  New treasures                 │  compact 48dp rows, day in `labelLarge numeric`; title = the item's pack copy
│ Thu  The kraken comes ashore       │
└────────────────────────────────────┘
```
- Row fields map 1:1 to the agenda: title (product key or the item's pack `text`), window (`start`–`end` →
  `play_challenge_window`), gate glyph from the item's state (`sleeping` → moon + `play_gate_dark`; kraken tide gate
  → wave), reward chip from `marks`, tap target from the occurrence key. Lore, eggs and the ferry sprite are not
  rows (always there; eggs are never listed); remembrance appears only on the quiet day.
- Order: the scheduler's; items on `ctx.currentFeature` (the trail or road being walked) sort first.
- Times are island time; when the phone's zone differs, `play_island_time` is appended ("6 am island time";
  schedule § 9).
- Empty day → `play_whats_on_empty` compact EmptyState. Quiet day → one row linking to the memorial card, nothing
  else. Never a countdown, never "ends in".
- (v2) `upcoming` also carries a "personal surprises" count, monthly themes and event windows (Oct 31 second
  attack); `ch-crossing` rows show `play_challenge_next_departures`; creature rows show creature hours.

### 3.16 Challenges screen + ChallengeSheet (PF9)

`BtTopBar` `play_challenges_title`; sections `play_challenge_solo` ("Just you") / `play_challenge_island` ("Whole
island"); `ChallengeCard`s, 12dp gaps, screen margins 16; this week's challenges whose window has not opened show as
Upcoming (§2.5); ended challenges of the last 7 days in a final `play_challenges_ended` section at 60 % art α. Card
tap → `ChallengeSheet` (MapSheet-style modal over the screen, `titleLarge` title, full body, progress, reward;
`ch-krakenweek` gets `play_show_on_map` → Map tab with the KrakenAttackSheet open; (v2) `ch-crossing` gets
`play_challenge_next_departures`, area challenges get `play_show_on_map`). Empty → compact EmptyState
`play_challenges_empty`. Mockup frame 4.

### 3.17 Island stories + Lore card

`IslandStoriesScreen`: header `play_stories_count`; 2-column grid (≥ 360dp) / list (< 360dp or font scale ≥ 1.5)
of 156dp cards: art tile 56dp, title `titleSmall`, tag `labelSmall`. Not yet met: art in ink outline only, title
still shown (cards are readable anytime; eggs are never listed; (v2) creatures). Remembrance cards grouped last under
`play_tag_remembrance`, no art. `LoreCardScreen` = the lore EncounterSheet content as a full pushed screen (art 120dp,
sources expanded).

### 3.18 Play board (PF13) (v2)

Pure Leaderboard layout (ux.md §3.8) with: subtitle `play_board_subtitle`; segmented `play_board_season` (default,
label shows the season name, `play_board_season_name_q1..q4`: Storm / Strawberry / Madrona / Kraken season) /
`play_board_all_time`; column headers "#", "Name", "Marks"; `PlayBoardRow`s;
pinned YouRow; first open with consent off → consent sheet (PF13-1). Empty `play_board_empty`; offline and error as
the pure board. Never shows finds, places or dates.

### 3.19 Showcase (debug) and Play clock (debug)

- **ShowcaseScreen gains a "Play" page** (next to Tokens / Components / States, same Light/Dark/System switch):
  the 4 roles with hex + measured ratios, the △ glyph set, Play chip (default / compact / skeleton), every pin at 3
  zoom sizes plus its selected variant, every v1 EncounterSheet variant, RiddleSheet states (unsolved / wrong /
  solved / claimed), ChallengeCard states, LogbookRow kinds, RankBadge / RankUpLine / ladder, WhatsOnRow, OptInCard,
  NearbyRow, FindMoment, PlayBar.
  Launch: `screen=showcase section=play theme=dark`.
- **PlayShowcaseScreen (FX)** — effects §7 layout, hosting **every v1 effect** (`treasure_glint`, `treasure_found`,
  `kraken_attack`, `kraken_recede`, `kraken_lurk`, `riddle_marker`, `riddle_light`, `frog_rock`, `ferry_crossing`,
  `exploration_fog`, `claim_pop` map + results, `play_wake`; AC 7): full-bleed `TrailMap` + bottom control strip on
  `surfaceContainerLow` (2 × 56dp rows): row 1 effect picker, state picker, preset picker, Play/Pause, ×0.25 / ×1,
  time-warp; row 2 timeline slider (0 → end, ms label `numeric`), theme, map style, reduced-motion, sounds ((v2)
  shader/fallback). `FxHud` top-right on `mapChrome` (`labelSmall tnum`: overlay ms p50/p95, `globalState` writes/s,
  particles, clock state). Launch args per effects §7 / engine stage 7.
- **PlayClockDebug** (Settings › Play, debug builds only) is the in-app face of schedule § 4.1's **`PlayClockPlugin`**
  (debug drawer); both write `AppGraph.playClock` (release binds the system clock and exposes no setter). Controls,
  named as in schedule § 4.1:
  - **Time travel**: Real / Fixed (island date + time pickers) / Offset, with the offsets **+1 d**, **+7 d** and
    **Next Thu 14:00**. Daylight, night, the kraken window, high tide, an expired tide table and the Mar 30 quiet day
    are reached by time travel to that instant, never by toggles, so the screen shows what the scheduler computes.
  - **Config source**: bundled / cached / fetch now (the pack inspector shows the active `version`).
  - **Agenda dump**: today's `DayPlan` table plus the next 14 days (copies text).
  - **Simulate user state**: clear finds, reset cooldowns; (v2) roll a new salt.
  - **Benchmark** (schedule § 5.3).
  - iOS and the e2e script use the same override by launch argument: `-btPlayClock 2027-07-04T14:00` and
    `-btPlayConfig bundled` (PD-17).
  A persistent `StateChip` "Play clock: Thu Oct 29 14:00 island time" sits on Map row 2 while an override is active
  (debug only).

---

## 4. Interaction and motion per screen

### 4.1 Map

| Step | What happens | Timing |
|---|---|---|
| Tap pin (only when the pure trail hit test missed, §3.5.4) | Layer tier only (S4): `feature-state selected` → the selected variant (1.15×, sea-glass ring) and other pins → 50 % α by paint transitions (≈ 200 ms, MapLibre ease); trail selection cleared | t 0 |
| Pan if covered | camera pans the pin into the free area (no zoom change) | t 16 ms (after sheet measures), long1 `emphasized` |
| Sheet in | MapSheet slide + fade (medium2) | t 0 |
| First open after a find | v1: the dated stamp is already shown (final frame). (v2) `lore_stamp_ink` on the art tile title (700 ms), `tick` at 450 | after sheet settles |
| Swipe down / back / empty tap | sheet out short4; pins back to 100 % α | — |
| Line tap while open | sheet swaps to TrailPeekSheet (content crossfade medium1); Play selection cleared | — |
| `claim_pop` on the map | only for server-derived marks arriving on sync (kraken retreat, kept week, island challenge), never mid-walk; "+n" flies to the Play chip (900 ms); coalesce within 400 ms; while a sheet covers the origin, it starts at the sheet's reward chip. Its overlay part is ≤ 3 s and is hidden or starts at the Play chip while a gesture moves the camera (S4) | effects §3.12 |
| Retreat moment (PF7-4) | once, next map open after the result: `kraken_recede` hero at the coast, then `claim_pop` "+50" | effects §3.1 |

### 4.2 Record

FindMoment: sensed → card in (medium2) + map hero (`treasure_found` / egg stamp crossfade ((v2) `easter_egg_reveal`)
/ `frog_rock`) + `confirm` haptic once; Nearby row flips to Found (crossfade medium1); card out after 4 s (short4).
Exploration fog thins as you enter a square (paint transition to .25, effects § 3.18; instant reduced). Ambient
effects rest while recording (engine PF-2). No pops here (marks are provisional).

### 4.3 Sheets, results, logbook

Results Play rows stagger in after the pure content settles; RankUpLine scales 0.96 → 1 (`overshootSettle` short4)
with `confirm`. Logbook first page staggers (max 8 rows). ChallengeCard done: wash crossfades in (medium1) when the
state changes while visible. Riddle states as §2.4.

### 4.4 Haptics map

| Event | Token | Notes |
|---|---|---|
| Find sensed (FindMoment) | `confirm` | once per item |
| Riddle solved (armchair) | `confirm` | |
| Riddle wrong | `reject` | never paired with red |
| (v2) Stamp lands (first open after find, `lore_stamp_ink`) | `tick` | |
| Rank-up line shown | `confirm` | once |
| Challenge done (shown in results) | `confirm` | coalesced with rank-up within 250 ms → one |
| Play turned on (wake) | `confirm` at 1900 ms | |
| Kraken recede / retreat on map | `ticks` ×4 + `confirm` (effects §3.1) | |
| Marker tap, chip tap, sheet open | platform click only | no custom haptics for navigation |
| Quiet zone, remembrance card | none | PF10-2 |

---

## 5. Accessibility

### 5.1 Focus order

**Map (Play on):** SearchChip → Trophy → PlayChip → ChallengesChip (or QuietBanner) → map node → Legend → Locate →
open sheet (title first). Map node custom actions gain `play_map_action_nearby` (opens a list sheet of Play items in
view, nearest first, each row = `play_marker_a11y`, tap = select + open sheet), `play_whats_on_title` (What's On)
and `play_log_title` (Logbook).
**Record:** existing order; FindMoment card (when present) after the chips, with a "Dismiss" custom action; Nearby
rows after the live list. **Sheets:** title → meta chips (merged) → body → gate note → sources → CTA. **Logbook:**
totals (one merged node: "2,291 marks. 185 this week. Rank Salal Scout, 709 to Madrona Wanderer.") → day headers
(heading) → rows. **Wake:** focus to the merged heading node, then to the Play chip when done.

### 5.2 Headings and grouping

All screen titles, SectionHeaders, sheet titles and Logbook day headers are `heading()`. Rows merge descendants.
Art, pins, fog, ink casing and illustrations are `clearAndSetSemantics {}`.

### 5.3 Large text

Text in `sp`; tested at Android font scale 2.0 / iOS AX3. Play chip + Challenges chip compact at ≥ 1.3, wrap at ≥ 1.5
(§3.5.1). Sheet chips wrap to two lines (FlowRow); CTA stays full-width. ChallengeCard and LogbookRow grow vertically
(`heightIn(min)`); the `+n` trailing column moves under `why` at ≥ 1.5. Island stories grid → list at ≥ 1.5.
Numbers never truncate (`tnum`, no ellipsis on marks).

### 5.4 Announcements (one announcer: `PlayAnnouncer`, effects §1.10; strings from product)

| Event | Politeness | Text (key) |
|---|---|---|
| Play on (wake appears) | assertive | `play_wake_a11y` then `play_start_balance` |
| Treasure / egg / lore found, kraken stretch walked (sensed, provisional) | polite | `play_find_a11y` ("Found Sea glass. 25 marks when you finish.") |
| Kraken stretch repelled (claimed, results) | polite (results live region) | `play_kraken_a11y` |
| Marks arrive on the map (`claim_pop`, sync) | polite, coalesced per 4 s | `play_claim_pop_a11y` |
| Kraken attack in view (first time per day) | polite | `play_kraken_coast` + `play_kraken_until` |
| Kraken retreat / slipped | polite | `play_kraken_retreat` / `play_kraken_slipped` |
| Fog lifted (claimed) | polite | `play_fog_lifted_a11y` |
| Riddle wrong / solved (armchair) / claimed | polite | `play_riddle_wrong` / `play_riddle_solved_stamp` / `play_riddle_claimed` |
| (v2) Ferry horn | polite | `play_find_title_horn` |
| Rank-up | polite | `play_rank_up_a11y` |
| Challenge done | polite | `play_challenge_done` + title |
| Results Play section | polite, once | `play_results_total` |
| Quiet day banner | polite on first map open of the day | `play_quiet_banner` |
| Remembrance visit | none | (stamp only, silent) |
Rules (effects § 1.10): assertive only for the opt-in moment; ≤ 1 polite per 4 s, duplicates within 30 s dropped,
nothing from the background, nothing inside quiet zones except screen-initiated reading. Every timed thing keeps a ≥ 10-minute trace in Nearby / What's On (WCAG 2.2.1).

### 5.5 Reduced motion

§1.4 table. Every Play surface has a static final frame that carries the same information (found stamp shown,
lantern lit image, ink casing present/absent). Under reduced motion `claim_pop` shows "+n" beside the Play chip for
1.5 s.

### 5.6 Color is never alone

Marks = △ glyph + number + word "marks". Found = check glyph + "Found" text + wash. Gates = icon + sentence. Riddle
difficulty = stars + a11y count. Kraken repelled = check + text, not just ink removal.

---

## 6. Copy

All chrome copy comes from product.md § 7.3 by key; this file adds only strings product.md lacks (prefix `play_`,
same tone rules: sentence case, no exclamation, no race/run/fast/speed/sprint/beat/points, PF9-5, PD-16). Content
text (riddles, lore, item titles) comes from the pack's `text`, never `strings.xml`. Product may reword any added
string; keys stay. Every key this file names is either in product § 7.3 or in §6.2 (checked 2026-10-20).

### 6.1 Keys used per surface (product.md)

| Surface | Keys |
|---|---|
| Settings › Play | `play_settings_section`, `play_settings_toggle`, `_toggle_body`, `_sounds`, `_sounds_body`, `_ranks`, `_logbook`, `_about`; (v2) `_board`, `_board_body`, `_board_signed_out`; `play_about_title`, `play_about_marks`, `_claim`, `_safety`, `_privacy` |
| Intro / offer / off / consent | `play_intro_title`, `play_intro_1..3`, `play_intro_start`, `play_start_balance`, `play_offer_title`, `_body`, `_accept`, `common_not_now`, `play_off_title`, `_body`, `_confirm`, `common_cancel`; (v2) `play_board_consent_*` |
| Map | `play_map_chip`, `play_map_chip_a11y`, `play_challenges_chip`, `play_legend_group`, `play_legend_fog`, `_treasure`, `_kraken`, `_riddle`, `_lore`, `play_kraken_coast`, `play_ferry_a11y`, `play_quiet_banner`, `_action` |
| Sheets | `play_walk_there`, `play_reward`, `play_reward_none`, `play_tier_*`, `play_treasure_expires`, `_collected`, `play_treasures_gone`, `play_egg_count`, `play_distance_away`, `play_gate_dark`, `_tide`, `_tide_unknown`, `_quiet`, `play_tag_history`, `_myth`, `_tall_tale`, `_remembrance`, (v2) `_creature`, `play_sources`, `play_safety_shore`, (v2) `_owl`, `_coyote`, `_newt`, `play_ferry_timetable_note`, `play_kraken_*`, `play_riddle_title`, `_stars_a11y`, `_answer`, `_pick_on_map`, `_pick_list`, `_hint`, `_hint_cost`, `_wrong`, `_solved_stamp`, `_claimed`, (v2) `_count_hint`, `_count_near` |
| Record | `play_nearby_header`, `play_nearby_row`, `play_find_title_treasure`, `_egg`, `_lore`, (v2) `_horn`, `play_find_provisional`, `play_find_a11y`, `play_fog_lifted_a11y`, `play_notif_finds`, `play_kraken_repelled_you` |
| Results / celebration | `play_results_header`, `_total`, `_none`, `play_unclaimed_header`, `_vehicle`, `_accuracy`, `_window`, `_dark`, `_tide`, `play_rank_up`, `_a11y`, `play_celebrate_marks` |
| Logbook / ranks / stories | `play_log_title`, `_total`, `_week`, (v2) `_season`, `_restored`, `_next_rank`, `_line`, `_cap`, `_local_only`, `_stamp`, `_empty`; `play_kind_*`; `play_ranks_title`, `_current`, `_next`; `play_rank_row_a11y`, `play_rank_earned`, `play_rank_1..11`, `_flavour`; `play_stories_title`, `_count` |
| Challenges | `play_challenges_title`, `play_challenge_solo`, `_island`, `_window`, `_progress`, `_done`, `_ended`, `_sweep_title`, `_sweep_body`, `_band`, `_band_top`, `_band_middle`, `_band_starting`; (v2) `_next_departures`, `_crossing_*`, `_foglifter_*`, `_bestweek_*`, `_strawberry_*`, `_first_wave*` |
| Me / board | `play_me_card_title`, `play_me_marks`, `play_me_logbook`, `play_me_stories`; (v2) `play_me_board_row`, `_hidden`, `play_board_*` |
| Lists / detail | `play_list_badge_*`, `play_list_filter`, `play_detail_section` |

### 6.2 Added keys (not in product.md)

| key | text | used |
|---|---|---|
| play_settings_ambient | Moving map effects | Settings |
| play_settings_ambient_body | Glints, ripples and the ferry's wake. Turn off to save battery. | Settings |
| play_settings_replay | Replay the island waking up | Settings |
| play_settings_clock_debug | Play clock (debug) | Settings, debug only (debug resources) |
| play_wake_title | The island is awake | PlayWake |
| play_wake_body | Treasures, riddles and island stories are on your map now. | PlayWake |
| play_wake_skip | Skip | PlayWake custom action |
| play_wake_a11y | Play is on. Treasures, riddles, challenges and island stories are on your map. Your marks are at the top left. | PlayWake |
| play_marker_a11y | %1$s, %2$s marks, %3$s away | Nearby list, map action list |
| play_marker_cluster_a11y (plural) | one: 1 Play item here. Zoom in to see it. · other: %1$d Play items here. Zoom in to see them. | map action list at island zoom |
| play_map_action_nearby | Play nearby | map custom action |
| play_record_finds (plural) | one: 1 find · other: %1$d finds | FindsChip |
| play_nearby_found | Found | NearbyRow |
| play_found_on | Found %1$s | sheet stamp (date, island time) |
| play_content_gone | This one's gone for now. | sheet error state |
| play_kraken_reward | %1$s–%2$s marks a stretch | Kraken sheet reward chip (F-INK buckets 60–80) |
| play_claim_pop_a11y | Plus %1$s marks. %2$s marks. | `claim_pop` announcement (effects § 3.12) |
| play_riddle_list_search | Search places | RiddleAnswerPicker |
| play_riddle_count_field (v2) | Your count | count riddle field |
| play_legend_challenge (v2) | Challenge area | legend |
| play_challenges_empty | No challenges this week. | Challenges empty |
| play_challenges_ended | Ended | Challenges, last section |
| play_show_on_map | Show on map | ChallengeSheet (`ch-krakenweek`; (v2) area challenges) |
| play_log_walk_group | Walk · %1$s | Logbook walk subheader (distance) |
| play_log_week_close | Week close | Logbook week-close subheader |
| play_rank_from | from %1$s marks | RankLadderRow |
| play_island_time | %1$s island time | any time shown when the phone's zone is not island time |
| play_whats_on_title | What's on | What's On, map custom action |
| play_whats_on_week_of | Week of %1$s | What's On header (the week id's Monday) |
| play_whats_on_daylight | Daylight %1$s – %2$s | What's On day line (civil dawn – civil dusk) |
| play_whats_on_ferry | Next ferry arrives %1$s | What's On day line (timetable, display only) |
| play_whats_on_treasures (plural) | one: 1 treasure this week · %2$d found · other: %1$d treasures this week · %2$d found | What's On treasure row |
| play_whats_on_today | Today | What's On |
| play_whats_on_coming | Coming up | What's On |
| play_whats_on_empty | A quiet day on the island. | What's On |
| play_me_whats_on | What's on | Me › PlayCard |

---

## 7. Decisions and open items

### 7.1 Decisions (summary; each is inline above as **Decision:** or a ruling; recorded as PD-18)

1. **Sea glass final hex** `#037245` / `#45E0AE` (§1.1, §0.3 #8): reward accent must not read as spruce `positive`.
2. **Play chip top-left on a second chrome row**; nothing Play on the right column (§3.5.1).
3. **Every tappable marker is a lifted pin**; stamps are never tappable; the pure trail hit test runs first and Play
   takes a tap only when it misses (§2.2, §3.5.4, PD-12).
4. **Island zoom shows dots/clusters, never figures**; cluster tap zooms, never opens a sheet (§2.2).
5. **One non-modal EncounterSheet** on `MapSheet` with a 60 % cap; decisions (offer, intro, off, (v2) consent) are
   the only modal sheets (§2.3, §2.10).
6. **Play selection never dims trails** (§2.2).
7. **No Play chip and no marks counter on the record screen**; provisional finds only (§3.8).
8. **Nothing timed**: no countdown, ghost, tape or chevrons; (v2) challenge areas are a static dashed ink outline +
   flag pin (§0.3 #3).
9. **No float line** (`streak_float_line` cut): kept weeks are Logbook lines (§0.3 #5, §3.13).
10. **Offer morphs into Intro** in one sheet; Start lands on the Map tab with the wake (§3.3, §3.1).
11. **Quiet day replaces the Play row with one banner and hides the Play chip**; no wake on Mar 30 (§3.5.6, §3.1).
12. **What's On** is a pushed screen from Me › PlayCard and the map's custom action; no new map chrome; it renders
    `PlayScheduler.agenda` and nothing else (§3.15).
13. **Ambient effects get their own toggle** ("Moving map effects"), default on; reduced motion and battery saver
    override it (§3.1).
14. **Geometry-exact UI is Layer tier** (spike S4 FAILED): pin selection, ring and 50 % α of other pins are
    feature-state / paint transitions, never a `placedAt` overlay; overlays only for transient heroes, hidden or
    handed to their Layer part during gesture camera moves; chrome is screen-anchored (§0.3 #10, §2.2).

### 7.2 Open (≤ 5)

| # | Question | Owner | Default applied |
|---|---|---|---|
| 1 | (v2) Prue's House: confirm the Hilltop Panabode cabin (product OD-1) so effects §3.6 can unblock its art | Owner | Not in v1; product default when it lands: lore card at the Hilltop trailhead, generic house figure, no trigger at the cabin |
| 2 | Challenge band counts: schedule § 7.3 sends counts < 5 as "fewer than 5", but `play_challenge_band` takes `%2$d` | Product (copy) | Until product adds a key, the band line is omitted when the count is under 5 |
| 3 | Mockup `design/play-mockup.html` predates the reconciliation (Blakely Harbor kraken coast — a cut, quiet-zone coast; the float line; "this season") | Design | This text wins; refresh the mockup before the UI build stage |
| 4 | Who authors the `FxArt` engraved glyph set (≈ 30 figures, both themes): illustrator vs AI-assisted drafts (ai.md AI-4) with human cleanup | Owner | Generic placeholder glyphs from the mockup until art lands; layouts do not change |
| 5 | "Moving map effects" default on vs off for battery-sensitive Dana | Owner | On (with Battery Saver/Low Power forcing off) |

Resolved since the first draft: the schedule cadence conflict (old item 2) is **resolved** by PD-5, PD-7, PD-8 and
PD-9 — the schedule now encodes product § 4 exactly, so What's On shows the reconciled rules. Spike S4 (old item 3)
**FAILED**, which settles it the strict way: selection and anything geometry-exact are Layer tier (§7.1 #14).

---

## 8. Design-director verdict (own spec, token rules)

```
DESIGN BRIEF: docs/play/brief.md (work item) · this spec: docs/play/ux.md · mockup: docs/play/design/play-mockup.html
PROMPT: n/a — designed in-repo against the ACCEPTED product spec (no external tool round); conformance pass
2026-10-20 against PD-1 … PD-17, the README glossary, product v1 scope and spike S4 → PD-18
--- on return ---
VERDICT: ACCEPT
FINDINGS:
 1. Token compliance: every Play color is one of 4 PlayColors roles or an existing role; no status, recording,
    userTrack, error or positive hue used by Play. Measured: seaGlass text ≥ 5.3:1 where allowed, ink ≥ 6.8:1 on
    every map/chrome surface, ΔE ≥ 23 from spruce and ≥ 41 from every status hue. PASS.
 2. Both themes: every role has a dark value; dark lamp rule (dark backing) stated; mockup shows light + dark. PASS.
 3. States: default/pressed/disabled/loading/error/empty specified per component (§2) and per screen (§3);
    reachable via Settings, offer, map, Me, results. PASS.
 4. A11y: ≥ 48dp targets (pins 48dp lifted rects, chips 48dp touch), focus order, announcements for every
    encounter via product keys, large-text rules, color-never-alone. PASS.
 5. Motion taxonomy: all durations/easings are BtDuration/BtEasing; PlayMotion is composed from them; reduced
    variants for every item. PASS.
 6. Copy: product keys plus the §6.2 play_ keys (every key named here cross-checked), tone rules respected; no
    race/speed/points words. PASS.
 7. Fixed during this round (effects.md drift): proposed seaGlass failed the spruce ΔE check → re-picked;
    "points/ledger/races/countdown/welcome bonus/daily streak" removed in favour of product rules (§0.3).
 9. Conformance pass: v1 scope tags (creatures, Prue's House, brambles, board, seasons, count riddles, ch-crossing,
    horn → (v2); float line → (cut)); tap precedence trail-first (PD-12); What's On = the agenda; play clock = schedule
    § 4.1; S4 → Layer-tier selection; find announcements polite. PASS.
 8. Buildability: everything is Compose M3 + existing components + MapLibre layers per engine.md; the only
    existing-component change is MapSheet (public + maxHeightFraction).
NEW PRIMITIVES NEEDED: none in sloopworks-ui (design_system: none). App-theme work item, must land first (§2.13):
    PlayColors (+ guard tests), PlayMotion, marksDelta type, Play spacing tokens, FxArt glyph set, public MapSheet,
    PlayBar, RewardChip.
BUILD NOTES (do not improvise):
 - Never draw Play in a status hue, never dim/recolor a status line, never put Play text on tiles.
 - seaGlass as text only for "+n" on surface / surfaceContainerLow / mapChrome; everywhere else onSurface.
 - Pins: figure lifted 30dp (26dp at z12.5–14), 48dp hit rect on the figure; the pure trail hit test runs first.
 - Selection, rings, halos, glints, casings: Layer tier (feature-state / paint transitions). No placedAt UI.
 - Play chip / Challenges chip start-aligned on row 2 at inset+64; camera fit top padding inset+128; controlsTopPadding 120.
 - Changing numbers: system font + tnum; Fraunces only for static totals.
 - One sheet at a time on the map; EncounterSheet max 60 %; CTA absent (not disabled) when gated, with a GateNote.
 - No countdowns, timers, pace, ghost or "race" visuals anywhere in Play.
 - Remembrance: no sea glass, lamp, motion, haptics, numbers or CTA.
 - Every effect has the reduced frame; every encounter announces through PlayAnnouncer with the §5.4 key.
 - With Play off nothing from this file composes: no row 2, no fx layers, no Settings rows beyond the toggle.
```
