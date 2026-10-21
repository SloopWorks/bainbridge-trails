# Bainbridge Play — UX spec (visual + interaction)

Date: 2026-10-20 · Owner: design-director (UX) · Status: decided, pending ACCEPT at the `design_before_build` gate
(decisions inline as **Decision:**; open items in §7.2, max 5).
Work item: `docs/play/brief.md`. Mockup: `docs/play/design/play-mockup.html` (light + dark, phone width).

Precedence
- `docs/mvp/decisions.md`, `docs/roads/decisions.md` > `docs/play/product.md` (ACCEPTED) > **this file** >
  `docs/play/effects.md` > `docs/play/engine.md` / `docs/play/schedule.md`.
- **product.md wins on words, rules, numbers and flows** (PF-numbers, `play_*` keys, marks, Logbook, Challenge).
- **This file wins on layout, color, type, motion, haptics and interaction** for every Play surface, including the
  map chrome. Where effects.md disagrees with product.md on a word or a mechanic, §0.3 records the reconciliation;
  effects.md still owns how each map effect is drawn and timed on the map (its §3 cards), with the overrides listed.
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

1. **One ink for art** (`playInk`): every Play figure, stamp and rope is line art in a single blue-black ink
   (pale on dark), with hatching for shade. Never a filled color shape larger than a fingertip.
2. **One accent for reward** (`playSeaGlass`, bottle-glass green): treasures, the marks glyph, `+n` deltas, and the
   progress fill of Play bars. It is the only Play hue a user learns, and it always means *marks*.
3. **One light** (`playLamp`, pale cream): lantern flames, lit windows, glints. Light, never a shape on its own.
4. **One weather** (`playFog`): fog over uncharted squares, mist and spray. Always below status lines.

Plus one new type use: **the survey mark glyph** (△ with a centre dot — the triangulation-station symbol on real
charts) is the icon for *marks* everywhere: HUD, reward chips, logbook, celebration line.

### 0.2 What Play must never do

| Never | Why / rule |
|---|---|
| Use a status hue (gray / ochre / tangerine / violet, any of the 4 roles) or `nearlyDoneCasing`, `recording`, `userTrack`, `error`, `positive` for anything Play | Status hues mean coverage; blue means *you*; spruce `positive` means island %-points. Guard test: every `PlayColors` value is ≥ ΔE 20 from all of them, both themes (measured §1.1). |
| Recolor, dim, cover or imitate a status line | Coverage is sacred. Ink rope and challenge outlines draw **under** the status line (effects.md §1.6 P3/P2); fog sits below the land-colored status halos. Play selection never dims trails. |
| Crowd the chart | Play adds **one** chrome row (HUD + Challenges chip). No Play button in the bottom bar, no FAB, no banners except the March 30 quiet banner. ≤ 40 Play markers visible (schedule S-7), ≤ 6 animated glints, one hero at a time. |
| Put text on map tiles | Play text sits on `mapChrome` or in sheets; the only map text is the kraken coast label, set like a map water label with a paper halo (§2.2). |
| Use Fraunces for numbers that change | Marks counters, distances and the HUD use system font + `tnum` (ux.md §1.3). Fraunces only for static totals (Logbook header, Me card). |
| Use a new full-screen modal for a Play reward | Rewards land in sheets, the HUD, results and celebration lines. The only Play full-screen moment is the once-only `play_wake` (§2.11). |
| Animate in a quiet zone, show anything at night, or point toward a beach at high tide | PP-6/7, R-1. Remembrance surfaces have no sea glass, no lamp, no motion, no haptics, no numbers. |
| Speak of speed | No countdowns, timers, pace or "race" visuals (PP-8). See §0.3 item 3. |

### 0.3 Reconciliation with sibling specs (binding for implementers)

| # | effects.md / engine.md / schedule.md says | This spec rules | Source |
|---|---|---|---|
| 1 | "points", "ledger", "ledger pill", "races" | **marks**, **Logbook**, **Play HUD** (product `PlayChip`), **Challenge**. Every effects.md announcement string is replaced by the product key in §5.4. | product § 3.1, OD-2, OD-9 |
| 2 | Ledger pill top-right | **HUD top-left, on a second chrome row** under the search chip; the trophy stays top-right (§3.5). | product § 5.3 |
| 3 | `race_track` countdown, ghost, finish tape, chevron marquee | **Not built.** Product forbids countdowns during a walk and anything timed (PP-8, PF9-3). Challenges that cover an area show a static dashed ink outline + one flag pin (§2.2). | product PP-8, § 4.5 |
| 4 | `play_wake` ends with a "+50 welcome points" pop | The pop carries the **starting balance** (`play_start_balance`, § 3.6.4); no welcome bonus exists. | product § 3.6.4 |
| 5 | `streak_float_line`: one float per day played, "Longest line" | One float per **kept week** (W-WEEK), knot every 4 (a month); no "longest" copy; nothing ever looks broken. | product § 3.2 W-WEEK, PP-12 |
| 6 | engine: FX tap query wins within 24dp | Tap priority of §3.5.4: a Play marker wins only inside its **figure** hit rect, which is lifted off the line; a tap on the line opens the pure trail preview. | product PF3-5 |
| 7 | effects §3.10 fallen tree / tide cut / fog bank, §3.5 tap-to-spot glimpses with points | Only product content types get UI: fog (squares), kraken, bramble, treasures, eggs, riddles, challenges, lore (creatures are proximity encounters, § 4.6). Other effects stay showcase-only until product adds them. | product § 4 |
| 8 | effects playSeaGlass `#08665A` / `#63DCC6` | **`#037245` / `#45E0AE`.** The proposed values sit ΔE 8.7 (light) / 14.8 (dark) from spruce `primary`/`positive`, so a sea-glass `+25 marks` would read as a spruce `+0.5 pts` island delta. Final values are ≥ ΔE 23 from both (§1.1). | measured |
| 9 | schedule.md: frog moves Wednesdays, kraken around high tide, weekend races, no spawns 21:00–06:00, roads ≤ 25 mph | UI follows product (§ 4.3.2 kraken Thu 06:00 → Sun 20:00 in low water; daylight only; ≤ 30 mph). Copy and What's On rows are generated from the scheduler's agenda, so whichever rule set the scheduler implements is what users see; the doc conflict is Open item 2. | product § 6 |

---

## 1. Tokens

All Play tokens live next to the existing ones in `ui/theme/` and are read through `BtTheme`. Nothing here changes
an existing token value.

### 1.1 Color: four Play roles

```kotlin
// ui/theme/BtColors.kt — added
@Immutable data class PlayColors(
    val ink: Color,        // all Play line art, stamps, rope, challenge outlines, art-tile contours
    val seaGlass: Color,   // reward accent: marks glyph, +n text (rules below), treasure fill, Play bar fill
    val lamp: Color,       // light only: flame, lit window, glint core, owl eyes (always inside ink or as glow)
    val fog: Color,        // fog patches / mist; alpha is part of the token
)
val PlayLight = PlayColors(ink = Color(0xFF22323C), seaGlass = Color(0xFF037245), lamp = Color(0xFFFFF1C9), fog = Color(0x8CF4F6F5)) // fog #F4F6F5 @ .55
val PlayDark  = PlayColors(ink = Color(0xFFCFE0E6), seaGlass = Color(0xFF45E0AE), lamp = Color(0xFFFFF0CF), fog = Color(0x407D9095)) // fog #7D9095 @ .25
// BtExtraColors gains `val play: PlayColors` (BtExtraLight.play = PlayLight, BtExtraDark.play = PlayDark).
// Brand-independent: identical in the trails and roads apps.
```

| Role | Light | Dark | Allowed | Never |
|---|---|---|---|---|
| `play.ink` | `#22323C` | `#CFE0E6` | Art contours (2.0dp contour / 1.25dp detail, effects §1.1), stamps, rope, challenge outline, art-tile glyphs in chrome, ink pool ≤ 35 % α | Body text (use `onSurface`); fills > 24dp² at full α |
| `play.seaGlass` | **`#037245`** | **`#45E0AE`** | Marks glyph △, treasure fills, Play bar fill, focus ring of a selected Play pin, and **text** only for a `+n` marks delta on `surface` / `surfaceContainerLow` / `mapChrome` (≥ 5.2:1) | Any line along the network; areas > 16dp ⌀ at full α; text on any other surface (e.g. the sea-glass wash: 4.33 light) |
| `play.lamp` | `#FFF1C9` | `#FFF0CF` | Inside an ink outline, or as radial glow; on dark it always sits on a dark backing | A mark on its own; a line; text |
| `play.fog` | `#F4F6F5` @ .55 | `#7D9095` @ .25 | Map P2 fog patches, spray, mist; wake paper wash | Above status lines or labels (except the 1.4 s fog-lift moment) |

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
val marksDelta = labelLarge.copy(fontFeatureSettings = "tnum")          // "+25" in HUD pops, sheets, logbook rows
val marksTotal = statLarge                                                // Fraunces 32 — static totals only (Logbook header, Me card)
```
Rules: HUD number = `labelLarge` + `tnum` (it rolls). Riddle clues = `bodyLarge` system (Fraunces italic stays
reserved to the celebration trail name and Me caption, ux.md §1.3). Content titles in sheets = `titleLarge` (Fraunces).
Date stamps ("Found · 27 Oct") = `labelMedium` system caps, letter-spacing 1.2sp.

### 1.3 Shapes, spacing, sizes

No new shape tokens. Sizes added to `BtSpacing` (named so nobody improvises):

| Token | Value | Use |
|---|---|---|
| `playChipHeight` | 40dp visual, 48dp touch (`minimumInteractiveComponentSize`) | HUD, Challenges chip |
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
| `overshootSettle` | `emphasizedDecelerate` to 108 % over 2/3 of the duration, then `standard` back to 100 % (`short4`) | Pin bump on tap, HUD bump, stamp thunk, rank badge earn |
| `anticipate` | `emphasizedAccelerate` dip −8 % over first 30 % | wind-up before a lift (map heroes only) |
| `sway` / `drift` / `ballistic` | effects.md §1.7 | map effects only; never in chrome |

UI-level motion (this file):

| Interaction | Duration | Easing | Reduced motion |
|---|---|---|---|
| Play marker tap: pin bump 1 → 1.15 → 1 | short4 | `overshootSettle` | none (instant select ring) |
| Camera pan to bring marker above the sheet | long1 | `emphasized` | jump |
| EncounterSheet in / out | medium2 slide + fade / short4 | MapSheet default (as TrailPeekSheet) | crossfade short3 |
| Sheet content swap (marker → marker) | medium1 crossfade + `animateContentSize` medium2 | `standard` | instant |
| `lore_stamp_ink` on first open after a find | 700 ms (effects §3.16) | as card | final frame |
| `points_pop` → HUD | 900 ms (effects §3.12) | as card | HUD crossfades to new value (short3) and shows `+n` beside it 1.5 s |
| HUD digit roll | short3 per changed digit | `standard` | crossfade short3 |
| Settings Play rows reveal | medium2 expand | `emphasizedDecelerate` | instant |
| Offer / Intro / Off / Consent modal sheets | M3 ModalBottomSheet default | — | crossfade short3 |
| FindMoment card in / out | medium2 (slide 16dp + fade) / short4 fade | `emphasizedDecelerate` / `standard` | crossfade short3 |
| Logbook / Results Play rows first show | fade + 4dp rise, short4, stagger 30 ms, max 8 rows | `emphasizedDecelerate` | instant |
| Play bar fill on first show | long2 | `emphasizedDecelerate` (as status bars) | final value |
| Riddle wrong: lantern shake | 360 ms (effects §3.9) | as card | none (text + haptic) |
| `play_wake` | 3000 ms (effects §3.17, overrides §0.3 #4) | as card | static card 1.2 s, crossfades short3 |

**Reduced motion (BtTheme.motion.reduced)** — everything in ux.md §1.7, plus: no glints, ripples, sway, flicker,
drift or particles on the map; every hero is its `reducedAtMs` frame; points never fly; FindMoment crossfades;
haptics and announcements unchanged. **"Moving map effects" off** (Settings, §3.1) applies the map half of these
rules without touching chrome motion. Battery Saver / Low Power applies both (effects §5).

### 1.5 Haptics (tokens from effects.md §1.8)

`tick` · `confirm` · `thud` · `reject`. Per-event mapping is in §4.4. Max one per 250 ms; none in background; none in
quiet zones (PF10-2).

---

## 2. Components

All new components live in `ui/play/` (UI) and `play/fx/` (map art), take `modifier: Modifier = Modifier`, and
render only under `if (play.enabled)`. Every interactive element ≥ 48dp. "Art" = an `FxArt` ImageVector (effects §1.1),
decorative, `clearAndSetSemantics {}`.

### 2.1 `PlayHud` (product `PlayChip`) and `ChallengesChip`

```
 ╭──────────────────────────────╮   ╭──────────────────────────╮
 │ △ 1,240 · Salal Scout        │   │ ⚑ 2 challenges this week │      40dp visual, 48dp touch, 8dp gap
 ╰──────────────────────────────╯   ╰──────────────────────────╯
   ▲ 18dp marks glyph, seaGlass       ▲ 18dp pennant, play.ink
```

| | PlayHud | ChallengesChip |
|---|---|---|
| Surface | `mapChrome`, `PillShape`, 3dp shadow (elevation level 3) | same |
| Content | glyph △ 18dp `seaGlass` · `play_map_chip` (`%1$s · %2$s`: marks, rank title), `labelLarge` + `tnum`, `onMapChrome` | pennant 18dp `play.ink` · `play_challenges_chip` (plural), `labelLarge`, `onMapChrome` |
| Padding | start 12, end 14, gap 6 | same |
| Width | wrap; min 96dp; marks ≥ 10,000 shown as `1.2k` (product § 3.1) | wrap |
| Compact (measured: row 2 may use width − 32 − 56dp, the trophy column stays clear) | 1st step: Challenges chip compacts; 2nd step (still too wide, or font scale ≥ 1.3): rank title dropped → `△ 1,240` | `⚑ 2` (count only; a11y keeps the full phrase). At 360dp with a typical rank the chip is compact (mockup) |
| Tap | → Logbook (pushed in the Map tab stack) | → Challenges screen |
| Pressed | M3 ripple on the pill; scale .97 short2 | same |
| Loading (marks not yet computed after launch) | glyph + 40×12dp `SkeletonBlock` | hidden until the agenda is ready |
| Disabled / error | n/a (local values; never an error state) | hidden when 0 challenges |
| Quiet day (Mar 30) | **hidden**; the quiet banner takes the row (§3.5.6) | hidden |
| `points_pop` target | bump `overshootSettle` short4 + digit roll | — |
| a11y | `Role.Button`; `play_map_chip_a11y` ("Play. 1,240 marks. Rank Salal Scout. Opens your logbook.") | `Role.Button`; plural string as label |
| Light / dark | token-driven; glyph sea glass on mapChrome 5.7 / 10.0 | ink pennant 12.6 / 12.3 |

### 2.2 `PlayMarker` anatomy (map layer art; effects.md §1.3 + overrides)

**Decision: every tappable Play marker is a PIN** — a figure lifted 30dp above its anchor on a 1.25dp ink stem to
a 4dp ink ground dot. Stamps (things printed flat on the chart) are never tappable. Why: product PF3-5 requires a
line tap to open the pure trail preview, and treasures sit on lines; a lifted figure gives Play its own hit target
that never contains the line point beneath it.

```
          ╭──────╮   ← hit rect 48×48dp centred on the FIGURE centre (not on the anchor)
          │ (◆)  │   ← figure 28dp: ink contour 2.0 / detail 1.25, ≤ 1 role fill
          ╰──┬───╯
             │       ← stem 1.25dp play.ink, lift 30dp (26dp at z12.5–14)
     ════════●═════  ← ground dot 4dp ink + SE shadow ellipse 18 % α; trail line untouched
```

Selected pin: figure 1.15× (bump), a 2dp `seaGlass` ring around the figure (non-text, 5.2+ on land), and other
Play pins at 50 % α. Status lines are **not** dimmed by a Play selection.

| Content | z 10.5–12.5 (island) | z 12.5–13 | z 13–14 | z 14–16 | z 16–18 | Tappable | Spent / found |
|---|---|---|---|---|---|---|---|
| Treasure (sea glass, agate, glass float, ship's bell) | per chart square: 1 item = 6dp sea-glass dot + 1.5dp ink ring; ≥ 2 = 20dp cluster disc (`mapChrome`, ink ring, count `labelSmall tnum`) | cluster / dot | pin 22dp pebble (tier figure) | pin 28dp + glint (≤ 6 in view) | pin 28dp (constant) | z ≥ 13 pin → sheet; z < 13 dot/cluster → camera to z14 on that square | pin keeps, figure becomes the open shell in ink 40 % α on `seaGlassWash`, no glint; tappable until the set washes away (opens the found sheet) |
| Easter egg | nothing | nothing | nothing | nothing until found | found: small pin 20dp (stamp figure in a ring) | z ≥ 15 after found | — |
| Riddle (`?` lantern, unlit) | dot (as treasure) | dot | pin 22dp lantern | pin 28dp, sway ±3° (z ≥ 15) | pin 28dp | z ≥ 13 | solved (armchair): lit lantern pin, still tappable; claimed: ink ring pin 14dp |
| Lore (frog, owl feather, bell, mill wheel, house…) | 10dp ink glyph only for kraken, frog, ferry | same | nothing else | pin 28dp story figure | pin 28dp | z ≥ 14 | met: figure keeps, ground dot gets a lamp core |
| Remembrance (memorial card) | nothing | nothing | nothing | quiet pin: 20dp ink ring with one horizontal rule, no fill, no shadow, no motion | same | z ≥ 14 | — (never changes) |
| Kraken attack | coast glyph 10dp ink curl in the water + coast label | lurk eye + 2 ripples at the offshore anchor; ink rope casing on stretches (P3) | same | same + intro hero once/day | same, tentacles geo-true | eye + label: one 48dp-min rect → KrakenAttackSheet; the **inked line itself is a trail tap** | your repelled stretches: rope removed (per user) |
| Bramble (August, trails) | nothing | thorny ink casing on the trail (P3) | + sprig pin 22dp at midpoint | pin 28dp | pin 28dp | z ≥ 13 | casing removed when cleared |
| Challenge area | dashed `play.ink` outline 1.25dp, dash 6/4dp (P2) | + flag pin 22dp at centroid | same | pin 28dp | same | z ≥ 13 | outline 40 % α when done |
| Ferry | glyph 10dp | figure 0.6× | figure | figure 1.0× | 1.4× | z ≥ 14 → ferry lore card (armchair stamp) | — |
| Fog | P2 fill per unwalked square | same | same | same | same | never | lifts per square |

Density: ≤ 40 Play markers rendered, ≤ 6 animated glints, ≤ 6 animated lanterns, ≤ 3 kraken anchors (effects §5).
Declutter: Play pins never overlap each other (`icon-allow-overlap = false` within the fx set, 4dp padding) with
`symbol-sort-key` priority **remembrance > kraken > challenge > riddle > treasure (bell > float > agate > sea glass) >
lore > bramble > found**; map labels yield to Play pins (effects §1.3). Quiet zones show only the remembrance pin.

Kraken coast label: a map `SymbolLayer` text, `play_kraken_coast` ("The kraken is at Blakely Harbor"), system font
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
│ ⓘ Gate note (tide / dark / quiet)  │  `GateNote`: icon 18dp + `bodyMedium`, surfaceContainerHigh pill
│ ┌───────────────┐                  │
│ │  Walk there   │  (+ secondary)   │  PrimaryButton 56dp, icon directions_walk
│ └───────────────┘                  │
└────────────────────────────────────┘
```

| Variant | Art tile | Title / meta | Chips | Body | CTA | Notes |
|---|---|---|---|---|---|---|
| Treasure, live | tier figure (pebble / agate / float / bell), sea-glass fill | `play_tier_*` / place · `play_distance_away` | `play_reward`, `play_treasure_expires` | pack text | `play_walk_there` → nearest Play-safe trail/road detail | PF4-1 |
| Treasure, found | figure on `seaGlassWash` tile; dated stamp "FOUND · 27 OCT" (`play_found_on`) inks in once | same | `play_treasure_collected` chip (check glyph sea glass) + reward chip shows "+25" if claimed | found text | none | mockup frame 1 |
| Treasure, gated | figure 60 % α | same | reward chip | — | **none**; `GateNote` with `play_gate_tide` / `play_gate_dark` / `play_gate_tide_unknown` | PF4-2/3 |
| Easter egg (found) | egg figure | `play_find_title_egg` / place | reward chip or `play_log_stamp` (armchair) | whole surprise text (no visual-only joke) | none | `play_egg_count` line at bottom, `bodySmall` |
| Lore | story figure | title / place | tag chip `play_tag_history` · `_myth` · `_tall_tale` · `_creature`; reward chip (first time) | 2–4 sentences; safety line (`play_safety_*`) in a `GateNote` with the shield icon; `play_sources` expandable row (48dp) listing links | `play_walk_there` if active & daylight | Frog Rock at z ≥ 16: armchair egg stamp line appears |
| Remembrance | quiet glyph (ink ring + rule), no fill, no stamp animation | title | `play_tag_remembrance` (outlined neutral chip) | card text; `play_reward_none` | **none** | No sea glass, lamp, motion, haptic or number anywhere (PF4-4) |
| Kraken (`KrakenAttackSheet`) | lurk eye on `secondaryContainer` | `play_kraken_title` / `play_kraken_until` | `play_tag_tall_tale`, `play_kraken_reward` | `play_kraken_body`; `play_kraken_rule`; **Stretches** list of `KrakenStretchRow`; island progress `play_kraken_island_progress` with n dots (filled `play.ink` = repelled by islanders); outcome line `play_kraken_retreat` / `_slipped` | none on the sheet; each stretch row has a 48dp "walk" icon button → that trail's detail | PF3-3, PF7-2/5; island progress offline → pure `OfflineNote` copy |
| Bramble | sprig figure | trail name / "a story bramble — the real trail is open" (pack text) | reward chip, `play_treasure_expires`-style meta from pack | rule from pack | `play_walk_there` | |
| Riddle | §2.4 | | | | | |
| Challenge | §2.5 (detail = ChallengeCard expanded in a MapSheet or pushed sheet) | | | | | |

`KrakenStretchRow`: 64dp; name `titleSmall` (spokenName for roads); below it a `playBar` 6dp with a 2dp × 10dp
`outline` tick at 60 % and the user's best walked share as sea-glass fill; trailing state text: "—" / `play_kraken_stretch_progress`
("50% walked. 60% sends it back.") / `play_kraken_repelled_you` with check glyph. Row tap → camera fits the stretch
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
│ ┌─────────────────┐┌─────────────┐ │
│ │Pick on the map  ││Choose a list│ │  2 × SecondaryButton 56dp (`play_riddle_pick_on_map`, `play_riddle_pick_list`)
│ └─────────────────┘└─────────────┘ │  count riddles: OutlinedTextField "Your count" (numeric + words) + Check button
│ Hint · A hint lowers the reward to 60 marks   ← TextButton `play_riddle_hint` + `play_riddle_hint_cost` bodySmall
```

- **Pick on the map:** the sheet collapses to a 72dp **pick bar** (`bodyLarge` "Pick the place on the map" + Cancel
  TextButton); map taps go to the answer hit test (trail/road lines and snapped landmarks, 44dp square as ux §6.1);
  the candidate gets the selection casing for 600 ms, then the sheet re-expands with the result.
- **Wrong:** `play_riddle_wrong` under the answer row (`bodyMedium` onSurface, no red, no ✕), lantern shake 360 ms,
  `reject` haptic, polite announcement. Unlimited tries.
- **Solved (armchair):** lantern lights (`riddle_light`), stamp line `play_riddle_solved_stamp`, CTA `play_walk_there`.
- **Claimed:** `play_riddle_claimed` + reward chip `+80`; no CTA.
- **Count riddle out of range:** field disabled, `play_riddle_count_near` as helper text (disabled is the only
  disabled state in Play, because the reason is shown inline).
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
| Island | kind chip `play_challenge_island`; progress is shared ("312 of 500 km"); a second line `play_challenge_band` when known; never a list of people (PF9-4) |
| First wave earned | small `play_challenge_first_wave` chip with `play_challenge_first_wave_body` in a11y |
| Hidden | `ch-bestweek` not rendered for the first 4 weeks |
| Loading | skeleton card 120dp |
| Pressed | ripple on card |

Never a countdown, never a clock icon, never "h:mm left" (PF9-3); windows are dates/days in island time. `ch-crossing`
detail shows `play_challenge_next_departures`. Merged semantics: "Weekend sweep. Just you. Saturday to Sunday. 1 of 3.
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

`LogbookGroupHeader`: day header `labelLarge` onSurfaceVariant sticky ("Sun, Nov 1"); walk subheader 48dp NavRow-style
"Walk · 2.0 km ›" → WalkDetail; week-close subheader "Week close" (no chevron). Dates only, never a time of day
(PF11-3). `LogbookTotals` (screen header, §3.13). Row semantics merged: "Treasure. Sea glass on Rose Loop. Weekly
treasure, common. Plus 25 marks."

### 2.7 `RankBadge` and the title ladder

- **RankBadge** (chip): 28dp pill, `tertiaryContainer` bg, `onTertiaryContainer` text `labelMedium`; leading 14dp
  rank mark = the survey △ with 0–3 tick marks under it (ranks 1–3 one tick, 4–7 two, 8–11 three) drawn in
  `onTertiaryContainer`. Non-interactive (used in HUD a11y, Me card, Play board rows, results rank-up line).
- **RankUpLine** (results / celebration): 56dp row, `tertiaryContainer` bg, large shape, RankBadge + `play_rank_up`
  `titleSmall`; `confirm` haptic once; polite `play_rank_up_a11y`. Never a modal (PF6-3).
- **RankLadderRow** (Ranks screen, 11 rows, 72dp): leading rank mark 32dp; title `titleMedium`; "from 1,500 marks"
  `numeric` onSurfaceVariant; flavour `bodySmall`. Earned: mark filled `play.ink`, trailing `play_rank_earned`.
  Current: row bg `tertiaryContainer` @ 60 % over surface, 2dp `tertiary` left bar, `play_ranks_current` above the list.
  Future: mark outlined, text onSurfaceVariant. a11y `play_rank_row_a11y`.
- **PlayBar to next rank**: `playBar` 6dp, fill `seaGlass`, track `surfaceContainerHighest` (4.5:1 light); label
  `play_log_next_rank` ("709 to Madrona Wanderer") `numeric`.

### 2.8 `WhatsOnRow`

64dp min; leading art tile 40dp; title `titleSmall` (one line); window `bodySmall` onSurfaceVariant
(`play_challenge_window` with island-time days/times: "Thu 6 am – Sun 8 pm"); trailing: gate glyph 20dp (sun = daylight
only, wave = low water, crescent = after dark now) **or** chevron. Tap → Map tab with the item selected and its
EncounterSheet open (camera fits the item, z ≥ 14) — or ChallengeCard detail for challenges. Remembrance quiet day
row has no chevron glyph color change and no art motion. Merged semantics "Kraken at Blakely Harbor, Thursday 6 am to
Sunday 8 pm, low water only".

### 2.9 `PlayNearbyStrip` / `NearbyRow` / `FindMoment` (record)

- `NearbyRow`: 48dp; glyph 24dp; `play_nearby_row` ("Sea glass · 120 m") `bodyMedium`; trailing found state
  (`play_nearby_found`, check glyph `seaGlass`). Distances update ≤ every 10 s; rows are not live regions.
- `FindsChip` (record sheet header, only when ≥ 1 find): `StateChip` variant, leading △ glyph sea glass,
  `play_record_finds` ("2 finds").
- `FindMoment` card: §3.8.

### 2.10 `OptInCard` (offer + intro + off + consent content)

One content block reused by `PlayOfferSheet`, `PlayIntroSheet`, `PlayOffSheet`, `PlayBoardConsentSheet` (all M3
`ModalBottomSheet`, `surfaceContainerLow`, extraLarge top corners, scrim .32, drag handle):

```
┌────────────────────────────────────┐
│              ───                   │
│   [ illustration 160dp, Canvas ]   │  offer/intro: island + 3 glints + a tentacle curl at the east shore + water lines;
│                                    │  off: the island with fog drifting in; consent: two pins and a trophy-less list
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
│ HUD slides in at its real position (top-left, §3.5) at 2600 ms, then the start-balance pop
└────────────────────────────┘
```
Full-screen above the Map tab including chrome; bottom bar stays visible but inert for ≤ 3 s; any tap at t < 2200
jumps to the lift (custom a11y action `play_wake_skip`). Plays once (opt-in) + Settings replay + showcase. Reduced:
static card 1.2 s → crossfade.

### 2.12 Other new pieces (brief)

| Component | Spec |
|---|---|
| `RewardChip` | 32dp neutral pill `surfaceContainerHigh`; △ 16dp `seaGlass` + `play_reward` `labelLarge` onSurface. Claimed: "+25" in onSurface. Remembrance: never rendered. |
| `MetaChip` | 32dp neutral pill, `labelMedium` onSurfaceVariant (expiry, window, "Found"). |
| `GateNote` | Inline row, `surfaceContainerHigh` small-shape box, icon 18dp (sun / wave / moon / quiet / shield) onSurfaceVariant + `bodyMedium` onSurface. Never error colors. |
| `PlayBar` | 6dp rounded; track `surfaceContainerHighest`; fill `seaGlass`; optional single tick `outline` 2×10dp. `progressBarRangeInfo` semantics. |
| `PlayBadge` (lists, product `play_list_badge_*`) | 24dp outlined pill, 1dp `play.ink` @ 60 %, glyph 14dp + `labelSmall` onSurfaceVariant; sits under the subtitle (never replaces the StatusChip). |
| `PlayLegendGroup` | In `StatusLegend` expanded card, after "Not counted": 1dp `outlineVariant` divider, header `play_legend_group` `labelMedium`, rows 40dp: 32×16dp art swatch + label (`play_legend_fog`, `_treasure`, `_kraken`, `_riddle`, `_lore`, `play_legend_challenge`). Swatches use Play art only, never a status pattern. |
| `PlayCard` (Me) | §3.12. |
| `PlayBoardRow` | LeaderRow variant: rank (40dp, Fraunces titleLarge, top 3 `primary`), name `titleSmall` + RankBadge-free title line `labelMedium` onSurfaceVariant, marks `numeric` 72dp right-aligned. a11y `play_board_row_a11y`. YouRow identical to pure (primaryContainer, 2dp primary bar). |
| `QuietBanner` | §3.5.6. |

### 2.13 New design-system primitives

`factory.design_system = none`; there is no `sloopworks-ui` dependency to change. The app's own theme gains
primitives that must land **as one theme work item before any Play screen** (so nothing ships a one-off):

1. `PlayColors` (4 roles + `seaGlassWash`) in `BtExtraColors`, with the contrast and ΔE guard tests (§1.1).
2. `PlayMotion` (`overshootSettle`, `anticipate`) in `BtMotion.kt`, built only from `BtEasing`/`BtDuration`.
3. `BtExtraType.marksDelta` and the `BtSpacing` Play sizes (§1.3).
4. `FxArt` glyph set (ImageVector, effects §1.1): marks △ + rank ticks, 4 treasure tiers, egg stamp, lantern
   unlit/lit, story figures (frog, owl feather, bell, mill, house, ferry, kraken eye, sprig, pennant), quiet ring,
   gate icons (sun, wave, moon, shield). One source per figure for both map rasters and chrome.
5. `MapSheet` made public with `maxHeightFraction`.
6. `PlayBar` and `RewardChip` (small, but shared by 6 screens; they belong next to `Progress.kt` / `Buttons.kt`).

---

## 3. Screens

Common rules: Play screens follow ux.md §3 common states (loading skeletons, inline offline notes, never a blocking
error). Play data is local first (pack bundled, logbook on device); only the Play board and island aggregates need
the network. All Play screens are pushed in the current tab's stack and keep the bottom bar.

### 3.1 Settings › Play (section between Units and Account; product § 5.1)

```
┌────────────────────────────────────┐
│ ← Settings                          │
│ …Units                              │
│ Play                                │  Group header (titleMedium, primary) — `play_settings_section`
│ Play                          [●  ] │  SettingSwitchRow; body `play_settings_toggle_body`
│ ── shown only when on (expand medium2) ──
│ Play sounds                   [  ○] │  `play_settings_sounds` + body; default off
│ Moving map effects            [●  ] │  `play_settings_ambient` + body; default on
│ Show me on the Play board     [  ○] │  `play_settings_board` + body; signed out → NavRow `play_settings_board_signed_out` → Profile
│ Ranks                             › │  NavRow → Ranks
│ Logbook                           › │  NavRow → Logbook
│ How Play works                    › │  NavRow → PlayAboutSheet (modal, the § 3 rules: `play_about_*`)
│ Replay the island waking up         │  NavRow, no chevron → Map tab + PlayWake replay
│ Play clock (debug)                › │  debug builds only → PlayClockDebug (§3.19)
│ Account …                           │
└────────────────────────────────────┘
```
- Toggle on: the switch shows on (pending) and `PlayIntroSheet` opens; Start → sheet closes, app switches to the Map
  tab (Settings stays in the Me stack), `PlayWake` plays, then snackbar `play_start_balance`. Dismiss without Start →
  switch animates back off (PF1-3).
- Toggle off: `PlayOffSheet` (`play_off_title`, `play_off_body`, `play_off_confirm` filled, `common_cancel`); cancel
  keeps it on.
- Board switch on for a signed-in user calls the consent endpoint; failure → revert + snackbar (pure "couldn't save"
  pattern). Offline → switch disabled with helper text from the pure offline copy.
- States: loading n/a; offline only affects the board switch.

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

### 3.4 PlayOffSheet / PlayAboutSheet / PlayBoardConsentSheet

`OptInCard` layouts with the product keys (§6). Consent: `play_board_consent_title/body`, `_yes` (filled),
`_no` (text). About: scrollable modal, 5 short sections with 24dp glyphs, `play_about_*`.

### 3.5 Map tab with Play on

#### 3.5.1 Layout

```
┌────────────────────────────────────┐
│ ▓ status bar                       │
│ ┌────────────────────────┐ ┌──┐    │  row 1 (unchanged): SearchChip (weight 1) + Trophy 48dp
│ │ 🔍 Find a trail        │ │🏆│    │  y = inset + 8
│ └────────────────────────┘ └──┘    │
│ ╭─────────────────────╮╭─────╮     │  row 2 (Play): PlayHud + ChallengesChip, start-aligned, 8dp gap
│ │ △ 1,240 · Salal Scout││ ⚑ 2 │     │  y = inset + 64 (playRowTop); LoadingChip, when shown, sits above it
│ ╰─────────────────────╯╰────────╯  │
│                                    │
│      (map: fog, rope, pins)        │  camera fit top padding = inset + 128 (playMapTopPadding)
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
  frame. Chips compact by measurement (§2.1: Challenges chip first, then the HUD's rank title); at font scale ≥ 1.5
  the Challenges chip wraps under the HUD (row 2 becomes 2 × 48dp; fit padding adds 56dp).
- `TrailMap` `controlsTopPadding` (scale bar / compass placement) = 120dp with Play on (pure 64dp).
- Mockup frame 1.

#### 3.5.2 Marker density

Per §2.2: island zoom shows dots and per-square clusters, never figures; figures from z13; glints only z ≥ 12.5,
max 6; at most 40 markers; a new user typically sees ~12 treasures + 2 riddles + 1 kraken coast + a few lore pins
across the island — about 1 per chart square.

#### 3.5.3 Fog and the honest map

Fog sits under status halos (effects §1.6 P2) at 55 % / 25 % α; status lines are fully readable through it
(PF3-2). Legend footnote unchanged; the Play legend group explains fog.

#### 3.5.4 Tap priority (MapTab `onTap`)

1. Query `fx-*-hit` features (pin hit rects, kraken eye/label, cluster discs) at the tap point.
   - Hit a **cluster/dot** (z < 13) → camera to z14 centred on that chart square (long1; jump under reduced motion).
     No sheet.
   - Hit a **pin** (or kraken eye/label) → select it: pin bump, clear any trail selection (peek closes, dimming
     removed), open its EncounterSheet, pan (no zoom change) so the pin sits in the free map area above the sheet if
     covered. No custom haptic: marker taps get the platform click feedback only.
   - Riddle pick mode (§2.4) → answer hit test instead.
2. Otherwise → the pure trail/road hit test (ux.md §6.1, road-end pins first). A tap on an inked kraken stretch,
   a bramble casing or a challenge outline is a **trail tap** (they are casings, not targets).
3. Nothing → close any sheet, clear selections.
Back closes the open sheet first (as pure). Recording: Play pins are not tappable on the record map (as trails).

#### 3.5.5 Encounter sheet over the map

The EncounterSheet replaces the peek in the same bottom stack (`stackHeight` logic unchanged), so legend, locate,
attribution and snackbars rise with it exactly as for the TrailPeekSheet. Selecting another pin while open swaps
content (§1.4). Max height 60 % of the map; the map stays interactive above it.

#### 3.5.6 Special states

| State | Map with Play |
|---|---|
| Loading (pack/agenda not ready, first ~200 ms) | Pure map; row 2 shows the HUD skeleton; no pins until the agenda is ready (no spinner). |
| Offline | No change (pack bundled). Kraken island progress in its sheet shows the pure OfflineNote. |
| Expired tide table | Shore pins drawn at 60 % α; their sheets show `play_gate_tide_unknown`. |
| Night (after civil dusk) | Pins stay (readable plans for tomorrow) but proximity-only items show `play_gate_dark`; no glints; ferry windows lit. |
| Quiet day (Mar 30) | Row 2 = `QuietBanner`: full-width `mapChrome` large-shape card (min 56dp), quiet glyph 20dp + `play_quiet_banner` `bodyMedium` + TextButton `play_quiet_banner_action` → memorial card. No pins except remembrance, no effects, HUD hidden. |
| Inside a quiet zone (memorial 200 m / cemeteries 100 m) while viewing | Only the remembrance pin renders within the zone; no effects animate there. |
| Error | Play never shows an error on the map; pack failures fall back to the bundled pack silently (debug log). |
| Signed out | No change (the board is the only signed-in Play surface). |

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
│      (record map: fog thins, ink recedes live, pins not tappable)
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
- No marks counter on the record screen: finds are provisional and the HUD is not shown here (keeps the walking UI
  calm and honest). Provisional marks appear only on the FindMoment card and the Nearby caption.
- Empty Nearby: section hidden (not an empty state; the walk UI stays short). If nothing is nearby for the whole
  walk, nothing Play shows.
- Screen off: no moment; notification suffix `play_notif_finds`.
- Quiet zone: no moment, no haptic, no Nearby rows for the zone.

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
│ ◇ Agate · The tide was in                        │  outline glyph onSurfaceVariant, reason `bodySmall`; no number
│ ┌────────────── Done ──────────────┐             │
```
Empty: `play_results_none` (bodyMedium onSurfaceVariant). Rows: first-show stagger (§1.4); the total does not count
up (static). Polite live region on the section header once: "65 marks this walk". Relaunch with `summary_shown` →
not shown again (PF6-4).

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
│ #14 on the Play board              › │  `play_me_board_row` / `_hidden` / signed out: `play_board_link`
└──────────────────────────────────────┘
```
Leaderboard screen: text link `play_board_link` at the bottom (Play on only).

### 3.13 Logbook screen (PF11)

```
┌────────────────────────────────────┐
│ ← Logbook                          │  BtTopBar `play_log_title`
│ ┌────────────────────────────────┐ │  LogbookTotals: surfaceContainerLow, medium shape, padding 16
│ │ 2,291 marks                    │ │  marksTotal + `play_log_total`
│ │ 640 this season · Kraken season│ │  `play_log_season` bodyMedium onSurfaceVariant
│ │ [△ Salal Scout] ▬▬▬▬░░ 709 to Madrona Wanderer │
│ │ ○○○○─○  Kept week, 4 in a row  │ │  float line (§0.3 #5): 14dp floats, ink rope; `play_kind_week_run`
│ │ Easter eggs: 7 of 40 found     │ │  `play_egg_count` bodySmall
│ └────────────────────────────────┘ │
│ Sun, Nov 1                         │  sticky day header
│ Week close                         │
│ ◎ Kept week, 4 in a row      +70   │
│ Walk · 2.0 km                    › │  → WalkDetail
│ ◆ Fairy door                 +20   │
│ ⚑ Weekend sweep              +100  │
│ …                                  │
└────────────────────────────────────┘
```
Paged 50 entries; newest first; totals equal the sum (PF11-4). States: loading = totals skeleton + 6 skeleton rows;
empty = `EmptyState` (water lines) `play_log_empty`; offline n/a; `local_only` rows per §2.6. Mockup frame 3.

### 3.14 Ranks screen (PF12)

`BtTopBar` `play_ranks_title`; header `play_ranks_current` (`headlineSmall` Fraunces) + `play_ranks_next` + PlayBar;
then 11 `RankLadderRow`s. No empty state (everyone is at least Walk-on).

### 3.15 What's On screen (schedule S-10; reached from Me › PlayCard and the map node's custom action)

```
┌────────────────────────────────────┐
│ ← What's on                        │  `play_whats_on_title`
│ Kraken season · week of Oct 19     │  `bodySmall` onSurfaceVariant (monthly theme + week, island time)
│ Today                              │  SectionHeader `play_whats_on_today`
│ [eye] The kraken is at Blakely Harbor   Thu 6 am – Sun 8 pm  〰   │  WhatsOnRow (gate glyph: low water)
│ [⛴] While the boat crosses   Next departures: 9:40 am, 10:35 am ›│
│ [ow] Barred owl              Daylight, until 5:41 pm         ☼   │
│ This week                          │  `play_whats_on_week`
│ [◆] 12 treasures · 3 found   Washes away Sunday night         ›   │
│ [?] 2 riddles                Mon – Sun                         ›   │
│ [⚑] Weekend sweep            Sat – Sun                         ›   │
│ Coming up                          │  `play_whats_on_coming` (7 days)
│ Mon  New treasures                 │  compact 48dp rows, day in `labelLarge numeric`
│ Sat  A second kraken (Oct 31)      │
└────────────────────────────────────┘
```
Rows come from `PlayScheduler.agenda(now)` (schedule §5). Times are island time with the local abbreviation
appended when the phone is elsewhere ("6 am PT"). Empty day → `play_whats_on_empty` compact EmptyState. Quiet day →
one row linking to the memorial card, nothing else. Never a countdown.

### 3.16 Challenges screen + ChallengeSheet (PF9)

`BtTopBar` `play_challenges_title`; sections "Just you" / "Whole island" (SectionHeaders with the product kind
labels); `ChallengeCard`s, 12dp gaps, screen margins 16; ended challenges of the last 7 days in a final "Ended"
section at 60 % art α. Card tap → `ChallengeSheet` (MapSheet-style modal over the screen, `titleLarge` title, full
body, progress, reward, `play_challenge_next_departures` for the crossing challenge, area challenges get "Show on
map"). Empty → compact EmptyState "No challenges this week" — **new key `play_challenges_empty`**. Mockup frame 4.

### 3.17 Island stories + Lore card

`IslandStoriesScreen`: header `play_stories_count`; 2-column grid (≥ 360dp) / list (< 360dp or font scale ≥ 1.5)
of 156dp cards: art tile 56dp, title `titleSmall`, tag `labelSmall`. Not yet met: art in ink outline only, title
still shown (cards are readable anytime; eggs are never listed). Remembrance cards grouped last under
"Remembrance", no art. `LoreCardScreen` = the lore EncounterSheet content as a full pushed screen (art 120dp,
sources expanded).

### 3.18 Play board (PF13)

Pure Leaderboard layout (ux.md §3.8) with: subtitle `play_board_subtitle`; segmented `play_board_season` (default,
label shows the season name) / `play_board_all_time`; column headers "#", "Name", "Marks"; `PlayBoardRow`s;
pinned YouRow; first open with consent off → consent sheet (PF13-1). Empty `play_board_empty`; offline and error as
the pure board. Never shows finds, places or dates.

### 3.19 Showcase (debug) and Play clock (debug)

- **ShowcaseScreen gains a "Play" page** (next to Tokens / Components / States, same Light/Dark/System switch):
  the 4 roles with hex + measured ratios, the △ glyph set, HUD (default / compact / skeleton), every pin at 3 zoom
  sizes, every EncounterSheet variant, RiddleSheet states (unsolved / wrong / solved / count-disabled), ChallengeCard
  states, LogbookRow kinds, RankBadge / RankUpLine / ladder, WhatsOnRow, OptInCard, NearbyRow, FindMoment, PlayBar.
  Launch: `screen=showcase section=play theme=dark`.
- **PlayShowcaseScreen (FX)** — effects §7 layout: full-bleed `TrailMap` + bottom control strip on
  `surfaceContainerLow` (2 × 56dp rows): row 1 effect picker, state picker, preset picker, Play/Pause, ×0.25 / ×1;
  row 2 timeline slider (0 → end, ms label `numeric`), theme, reduced-motion, shader/fallback, sounds. `FxHud`
  top-right on `mapChrome` (`labelSmall tnum`: overlay ms p50/p90, layer updates/s, particles, clock state). Launch
  args per effects §7 / engine stage 7.
- **PlayClockDebug** (Settings › Play, debug only; same as the debug-drawer `PlayClockPlugin`): segmented
  Real / Fixed / Offset; date + time pickers (island time); quick buttons "+1 day", "+7 days", "Next Thu 06:00",
  "Mar 30"; overrides: daylight on/off, low water on/off, tide table expired; "Dump agenda" (copies text). A
  persistent `StateChip` "Play clock: Thu Oct 22 14:00 PT" on the Map row 2 while an override is active (debug only).

---

## 4. Interaction and motion per screen

### 4.1 Map

| Step | What happens | Timing |
|---|---|---|
| Tap pin | bump (`overshootSettle` short4), select ring in, other pins → 50 % α (MapLibre transition 200 ms), trail selection cleared | t 0 |
| Pan if covered | camera pans the pin into the free area (no zoom change) | t 16 ms (after sheet measures), long1 `emphasized` |
| Sheet in | MapSheet slide + fade (medium2) | t 0 |
| First open after a find | `lore_stamp_ink` on the art tile title (700 ms), `tick` at 450 | after sheet settles |
| Swipe down / back / empty tap | sheet out short4; pins back to 100 % α | — |
| Line tap while open | sheet swaps to TrailPeekSheet (content crossfade medium1); Play selection cleared | — |
| `points_pop` | resolve heroes and claims on the map (kraken recede, riddle light) fly "+n" to the HUD (900 ms); coalesce within 400 ms; while a sheet covers the origin, it starts at the sheet's reward chip | effects §3.12 |
| Retreat moment (PF7-4) | once, next map open after the result: `kraken_recede` hero at the coast, then pop "+50" | effects §3.1 |

### 4.2 Record

FindMoment: sensed → card in (medium2) + map hero (`treasure_found` / `easter_egg_reveal` / lore encounter) + `confirm`
haptic once; Nearby row flips to Found (crossfade medium1); card out after 4 s (short4). Fog thins as you enter a
square (layer transition 1.2 s; instant reduced). No pops here (marks are provisional).

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
| Stamp lands (first open after find) | `tick` | |
| Rank-up line shown | `confirm` | once |
| Challenge done (shown in results) | `confirm` | coalesced with rank-up within 250 ms → one |
| Play turned on (wake) | `confirm` at 1900 ms | |
| Kraken recede / retreat on map | `ticks` ×4 + `confirm` (effects §3.1) | |
| Marker tap, chip tap, sheet open | platform click only | no custom haptics for navigation |
| Quiet zone, remembrance card | none | PF10-2 |

---

## 5. Accessibility

### 5.1 Focus order

**Map (Play on):** SearchChip → Trophy → PlayHud → ChallengesChip (or QuietBanner) → map node → Legend → Locate →
open sheet (title first). Map node custom actions gain `play_map_action_nearby` (opens a list sheet of Play items in
view, nearest first, each row = `play_marker_a11y`, tap = select + open sheet) and `play_log_title` (Logbook).
**Record:** existing order; FindMoment card (when present) after the chips, with a "Dismiss" custom action; Nearby
rows after the live list. **Sheets:** title → meta chips (merged) → body → gate note → sources → CTA. **Logbook:**
totals (one merged node: "2,291 marks. 640 this season. Rank Salal Scout, 709 to Madrona Wanderer.") → day headers
(heading) → rows. **Wake:** focus to the merged heading node, then to the HUD when done.

### 5.2 Headings and grouping

All screen titles, SectionHeaders, sheet titles and Logbook day headers are `heading()`. Rows merge descendants.
Art, pins, fog, rope, float line and illustrations are `clearAndSetSemantics {}`.

### 5.3 Large text

Text in `sp`; tested at Android font scale 2.0 / iOS AX3. HUD + Challenges chip compact at ≥ 1.3, wrap at ≥ 1.5
(§3.5.1). Sheet chips wrap to two lines (FlowRow); CTA stays full-width. ChallengeCard and LogbookRow grow vertically
(`heightIn(min)`); the `+n` trailing column moves under `why` at ≥ 1.5. Island stories grid → list at ≥ 1.5.
Numbers never truncate (`tnum`, no ellipsis on marks).

### 5.4 Announcements (one announcer: `PlayAnnouncer`, effects §1.10; strings from product)

| Event | Politeness | Text (key) |
|---|---|---|
| Play on (wake appears) | assertive | `play_wake_a11y` then `play_start_balance` |
| Treasure / egg / lore found (sensed) | assertive | `play_find_a11y` ("Found Sea glass. 25 marks when you finish.") |
| Kraken stretch repelled (claimed, results) | polite (results live region) | `play_kraken_a11y` |
| Kraken attack in view (first time per day) | polite | `play_kraken_coast` + `play_kraken_until` |
| Kraken retreat / slipped | polite | `play_kraken_retreat` / `play_kraken_slipped` |
| Fog lifted (claimed) | polite | `play_fog_lifted_a11y` |
| Riddle wrong / solved / claimed | polite / assertive / polite | `play_riddle_wrong` / `play_riddle_solved_stamp` / `play_riddle_claimed` |
| Ferry horn | polite | `play_find_title_horn` |
| Rank-up | polite | `play_rank_up_a11y` |
| Challenge done | polite | `play_challenge_done` + title |
| Results Play section | polite, once | `play_results_total` |
| Quiet day banner | polite on first map open of the day | `play_quiet_banner` |
| Remembrance visit | none | (stamp only, silent) |
Rules: ≤ 1 polite per 4 s, duplicates within 30 s dropped, nothing from the background, nothing inside quiet zones
except screen-initiated reading. Every timed thing keeps a ≥ 10-minute trace in Nearby / What's On (WCAG 2.2.1).

### 5.5 Reduced motion

§1.4 table. Every Play surface has a static final frame that carries the same information (found stamp shown,
lantern lit image, rope present/absent). Under reduced motion `points_pop` shows "+n" beside the HUD for 1.5 s.

### 5.6 Color is never alone

Marks = △ glyph + number + word "marks". Found = check glyph + "Found" text + wash. Gates = icon + sentence. Riddle
difficulty = stars + a11y count. Kraken repelled = check + text, not just rope removal.

---

## 6. Copy

All chrome copy comes from product.md § 7.3 by key; this file adds only strings product.md lacks (prefix `play_`,
same tone rules: sentence case, no exclamation, no race/run/fast/speed/sprint/beat). Product may reword any added
string; keys stay.

### 6.1 Keys used per surface (product.md)

| Surface | Keys |
|---|---|
| Settings › Play | `play_settings_section`, `play_settings_toggle`, `_toggle_body`, `_sounds`, `_sounds_body`, `_board`, `_board_body`, `_board_signed_out`, `_ranks`, `_logbook`, `_about`; `play_about_*` |
| Intro / offer / off / consent | `play_intro_title`, `play_intro_1..3`, `play_intro_start`, `play_start_balance`, `play_offer_title`, `_body`, `_accept`, `common_not_now`, `play_off_title`, `_body`, `_confirm`, `common_cancel`, `play_board_consent_*` |
| Map | `play_map_chip`, `play_map_chip_a11y`, `play_challenges_chip`, `play_legend_*`, `play_kraken_coast`, `play_ferry_a11y`, `play_quiet_banner`, `_action` |
| Sheets | `play_walk_there`, `play_reward`, `play_reward_none`, `play_tier_*`, `play_treasure_expires`, `_collected`, `play_treasures_gone`, `play_egg_count`, `play_distance_away`, `play_gate_*`, `play_tag_*`, `play_sources`, `play_safety_*`, `play_ferry_timetable_note`, `play_kraken_*`, `play_riddle_*` |
| Record | `play_nearby_header`, `play_nearby_row`, `play_find_title_*`, `play_find_provisional`, `play_find_a11y`, `play_fog_lifted_a11y`, `play_notif_finds` |
| Results / celebration | `play_results_header`, `_total`, `_none`, `play_unclaimed_*`, `play_rank_up`, `_a11y`, `play_celebrate_marks` |
| Logbook / ranks / stories | `play_log_*`, `play_kind_*`, `play_ranks_*`, `play_rank_*`, `play_stories_*` |
| Challenges | `play_challenges_title`, `play_challenge_*` |
| Me / board | `play_me_*`, `play_board_*` |
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
| play_kraken_reward | %1$s–%2$s marks a stretch | Kraken sheet reward chip (60–150) |
| play_riddle_list_search | Search places | RiddleAnswerPicker |
| play_riddle_count_field | Your count | count riddle field |
| play_legend_challenge | Challenge area | legend |
| play_challenges_empty | No challenges this week. | Challenges empty |
| play_whats_on_title | What's on | What's On |
| play_whats_on_today | Today | What's On |
| play_whats_on_week | This week | What's On |
| play_whats_on_coming | Coming up | What's On |
| play_whats_on_empty | A quiet day on the island. | What's On |
| play_me_whats_on | What's on | Me › PlayCard |

---

## 7. Decisions and open items

### 7.1 Decisions (summary; each is inline above as **Decision:** or a ruling)

1. **Sea glass final hex** `#037245` / `#45E0AE` (§1.1, §0.3 #8): reward accent must not read as spruce `positive`.
2. **HUD top-left on a second chrome row**; nothing Play on the right column (§3.5.1).
3. **Every tappable marker is a lifted pin**; stamps are never tappable; line taps stay pure (§2.2, §3.5.4).
4. **Island zoom shows dots/clusters, never figures**; cluster tap zooms, never opens a sheet (§2.2).
5. **One non-modal EncounterSheet** on `MapSheet` with a 60 % cap; decisions (offer, intro, off, consent) are the
   only modal sheets (§2.3, §2.10).
6. **Play selection never dims trails** (§2.2).
7. **No HUD and no marks counter on the record screen**; provisional finds only (§3.8).
8. **No countdown, ghost, tape or chevrons**; challenge areas are a static dashed ink outline + flag pin (§0.3 #3).
9. **Float line counts kept weeks**, not days (§0.3 #5).
10. **Offer morphs into Intro** in one sheet; Start lands on the Map tab with the wake (§3.3, §3.1).
11. **Quiet day replaces the Play row with one banner and hides the HUD** (§3.5.6).
12. **What's On** is a pushed screen from Me › PlayCard and the map's custom action; no new map chrome (§3.15).
13. **Ambient effects get their own toggle**, default on; reduced motion and battery saver override it (§3.1).

### 7.2 Open (≤ 5)

| # | Question | Owner | Default applied |
|---|---|---|---|
| 1 | Prue's House: confirm the Hilltop Panabode cabin (product OD-1) so effects §3.6 can unblock its art | Owner | Product default: lore card at the Hilltop trailhead; generic house figure, no trigger at the cabin |
| 2 | `schedule.md` cadence (frog moves Wed, kraken near high tide, community races, 21:00–06:00 rule, ≤ 25 mph) conflicts with ACCEPTED product.md | Spec author (schedule) | UI follows product.md; What's On shows the scheduler's agenda as-is |
| 3 | Spike S4 (overlay camera lag): if > 0 frames, pin bump and select ring must be layer-only (no overlay) | Engine | Bump via layer `icon-size` image swap; overlay only for heroes |
| 4 | Who authors the `FxArt` engraved glyph set (≈ 30 figures, both themes): illustrator vs AI-assisted drafts (ai.md AI-4) with human cleanup | Owner | Generic placeholder glyphs from the mockup until art lands; layouts do not change |
| 5 | "Moving map effects" default on vs off for battery-sensitive Dana | Owner | On (with Battery Saver/Low Power forcing off) |

---

## 8. Design-director verdict (own spec, token rules)

```
DESIGN BRIEF: docs/play/brief.md (work item) · this spec: docs/play/ux.md · mockup: docs/play/design/play-mockup.html
PROMPT: n/a — designed in-repo against the ACCEPTED product spec (no external tool round)
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
 6. Copy: product keys only, 26 added play_ keys, tone rules respected; no race/speed words. PASS.
 7. Fixed during this round (effects.md drift): proposed seaGlass failed the spruce ΔE check → re-picked;
    "points/ledger/races/countdown/welcome bonus/daily streak" removed in favour of product rules (§0.3).
 8. Buildability: everything is Compose M3 + existing components + MapLibre layers per engine.md; the only
    existing-component change is MapSheet (public + maxHeightFraction).
NEW PRIMITIVES NEEDED: none in sloopworks-ui (design_system: none). App-theme work item, must land first (§2.13):
    PlayColors (+ guard tests), PlayMotion, marksDelta type, Play spacing tokens, FxArt glyph set, public MapSheet,
    PlayBar, RewardChip.
BUILD NOTES (do not improvise):
 - Never draw Play in a status hue, never dim/recolor a status line, never put Play text on tiles.
 - seaGlass as text only for "+n" on surface / surfaceContainerLow / mapChrome; everywhere else onSurface.
 - Pins: figure lifted 30dp (26dp at z12.5–14), 48dp hit rect on the figure; line taps go to the pure handler.
 - HUD/Challenges chips start-aligned on row 2 at inset+64; camera fit top padding inset+128; controlsTopPadding 120.
 - Changing numbers: system font + tnum; Fraunces only for static totals.
 - One sheet at a time on the map; EncounterSheet max 60 %; CTA absent (not disabled) when gated, with a GateNote.
 - No countdowns, timers, pace, ghost or "race" visuals anywhere in Play.
 - Remembrance: no sea glass, lamp, motion, haptics, numbers or CTA.
 - Every effect has the reduced frame; every encounter announces through PlayAnnouncer with the §5.4 key.
 - With Play off nothing from this file composes: no row 2, no fx layers, no Settings rows beyond the toggle.
```
