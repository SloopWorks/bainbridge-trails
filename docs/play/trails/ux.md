# Bainbridge Play — Trails edition (v1.1): UX addendum

Date: 2026-10-21 · Owner: design-director · Status: **ACCEPTED 2026-10-21** (own conformance pass, § 10; to be cited
in PD-26 by the orchestrator). Work item: [`brief.md`](brief.md) (§ 2 rules, § 4 AC 6, § 5 design gate, § 8 Q1–Q5).

**This file is an addendum to [`../ux.md`](../ux.md).** Everything there holds (tokens § 1, components § 2, screens
§ 3, a11y § 5, copy rules § 6). This file adds only what the six trails rules (W-CLIMB, W-STEEP, W-MILE, W-PARK1,
W-PARKC, W-ISLAND) and the two challenges (`ch-climb`, `ch-parkhop`) need. Precedence as in `../ux.md` header;
`brief.md` § 2 wins on rules and numbers, this file on layout, copy, a11y and display formatting of those rules.

## 0. Scope rules (binding)

1. **Trails only.** Everything here composes only when Play is on **and** `LocalGoal.current` is the trails
   vocabulary (`!isRoads`). The roads app shows nothing new: no strip, no sheet, no new cards, no new rows, no new
   formatting path. Models for these surfaces are `null` on roads (computed outside composition), so the composable
   guard is a second line, not the only one.
2. **No new effects, layers or motion.** No MapFx, no map layer, no new animation. Results / Logbook rows use the
   existing first-show stagger; marks arriving on sync use the existing `claim_pop` (with its reduced variant).
3. **Tokens.** Only `BtTheme` roles and the four Play roles of `../ux.md` § 1.1. No status hue, no `positive`, no
   `primary` for Play progress (sea glass is the Play progress fill).
4. **Numbers.** Changing numbers in system font + `tnum` (`BtTheme.type.numeric` / `marksDelta`); thousands
   separators (`1,250`); never decimals for marks.
5. **Derived values** (ladders, so-far numbers, counts of 19 parks / 10 high-ground trails) come from the dataset's
   derived Play tables (`featurePark`, `featureGainM`) and the sticky completed set, computed in a logic object
   (as `Milestones.ladder` / `ChallengesLogic`), never in composition and never hard-coded (19 and 10 are today's
   counts, not constants).

## 1. Display formatting (one helper, used by every surface below)

Name it `TrailRewardFormat` (logic, `ui/play/`), unit-tested. Units follow Settings (D19: miles ⇒ ft + mi,
kilometres ⇒ m + km). Rules are stored and paid in **metres** (climb) and **miles** (trail miles); the display
converts, it never decides.

| value | miles setting | km setting | rounding |
|---|---|---|---|
| climb so far / challenge climb progress | `1,207 ft` (m × 3.28084) | `368 m` | **floor**, integer |
| climb step / challenge climb target | `820 ft` (250 m) | `250 m` | **nearest**, integer |
| trail distance so far | `7.4 mi` | `11.9 km` | floor, one decimal |
| trail-mile step | `10 mi` | `16.1 km` (10 mi) | mi: integer; km: nearest, one decimal |

**Clamp (no false "reached"):** while a step/target is not reached in its stored unit, the shown progress is at most
shown target − 1 (one display unit); once reached, it is at least the shown target. So a player never sees
"492 of 492 ft" on an unfinished challenge, nor "2,460 ft" under an earned 2,461 ft step.
Climb steps as shown (ft): 328 · 820 · 1,640 · 2,461 · 3,281 · 4,101 (100 … 1,250 m, Q1 default, 6 steps).

Spoken units (TalkBack/VoiceOver do not reliably expand `m`/`ft`): a11y strings use the plural keys
`play_unit_feet_a11y` / `play_unit_metres_a11y` / `play_unit_miles_a11y` / `play_unit_km_a11y` (§ 8).

## 2. Logbook rows (`LogbookRow`, `../ux.md` § 2.6 — unchanged anatomy)

### 2.1 Q5, design side: distinct kinds

**Recommendation: two new `LogKind` values, `climb` and `high_ground`, plus `start_trails` (§ 6). W-MILE uses the
existing `milestone`; W-PARK1 / W-PARKC / W-ISLAND use the existing `park_first` / `park_complete` / `island`.**
Not `milestone` subtypes. The kind is what drives icon, label, results order, the per-kind counts of
`GET /v1/play/me` and the Logbook dedupe key `(kind, contentId)`; a subtype would need a new persisted field and a
second branch in every `when (kind)`. New enum values append to `LogKind` (names are the stored keywords). Plan
must keep decoding safe (an older build reading a newer Logbook is a downgrade case: state it, test it or refuse it).

### 2.2 Rows

| rule | kind | art tile glyph (32dp tile, `play.ink`) | label (`play_kind_*`) | what (shown) | why (persisted, `LogCopy`) | `+n` |
|---|---|---|---|---|---|---|
| W-CLIMB step | `climb` | `FxGlyph.Summit` (new, § 2.3) | `play_kind_climb` "Climb" | rendered: `play_log_climb_what` "%1$s climbed" → "820 ft climbed" / "250 m climbed" | "Climb on trails you've finished" | +50 |
| W-STEEP | `high_ground` | `FxGlyph.Summit` | `play_kind_high_ground` "High ground" | the trail's name ("Peter's Trail") | "A steep trail, finished" | +50 |
| W-MILE step | `milestone` | `FxGlyph.Marks` (existing default) | `play_kind_milestone` "Milestone" (existing) | rendered: `play_log_mile_what` "%1$s of trail walked" → "10 mi of trail walked" / "16.1 km of trail walked" | "Distance across all your walks" | +50 |
| W-PARK1 | `park_first` | `FxGlyph.Marks` | `play_kind_park_first` "First steps in a park" (existing) | the park's name ("Gazzam Lake Preserve") | "First new ground in this park" | +50 |
| W-PARKC | `park_complete` | `FxGlyph.Marks` | `play_kind_park_complete` "Every trail in a park" (existing) | the park's name | "Every trail in this park finished" | +150 |
| W-ISLAND | `island` | `FxGlyph.Marks` | `play_kind_island` "The whole island" (existing) | "The whole island" | "All of the island's trails walked" | +1,000 |

- **Persisted text.** `what`/`why` are written once and frozen (`LogCopy` rule; `LogCopyTest` pins the new lines).
  The **why** strings above are unit-free on purpose. The persisted **what** of `climb` / `milestone` is the
  canonical text in the stored unit ("250 m climbed", "10 mi of trail walked"); the row **renders** it from the step
  value in the entry's `contentId` (suggested `climb:250` metres, `mile:10` miles; plan may rename, the value must be
  recoverable) through § 1, so a miles user reads feet and a unit switch re-renders. Unparseable → persisted what.
- Row semantics (one merged node, existing pattern): "Climb. 820 feet climbed. Climb on trails you've finished.
  +50 marks." — the spoken what uses § 1's spoken units.
- States: **pending sync** = no note (as v1; these are walk-derived display values, PD-3); **`local_only`** = the
  existing third line `play_log_local_only` with cloud-off; **accepted** = no note. No time of day, ever. A walk
  that earns several steps at once writes one row per step (each `+50`); the results collapse (§ 3) keeps it calm.
- Same-walk order inside the walk group = results order (§ 3).

### 2.3 One glyph added to the existing `FxArt` set

`FxGlyph.Summit` — 24-unit artboard, `play.ink`: one open hill contour arc (2.0 contour, spanning x 3–21, crest at
y 15), one inner contour arc (1.25 detail), and the survey △ with centre dot (the Marks figure at 55 %) standing on
the crest. Decorative (`clearAndSetSemantics {}`), both themes by ink. Used by `climb`, `high_ground` and the
Trail-milestones climb/high-ground rows. Parks/miles/island keep the survey mark (already the walk-derived glyph).

## 3. Results sheet, Play section (`../ux.md` § 3.9)

- **Order** (extends `ResultsPlayLogic.RULE_ORDER`): coverage, complete, **high_ground**, park_first, park_complete,
  **climb**, milestone, island, **start_trails** (never on a walk; listed for completeness), square, day, week, then
  finds and challenges unchanged.
- **Collapse (new, all goals' rows but only ever reached by trails walks today):** the section shows at most
  **8 rows** (= the § 1.4 stagger cap). With more than 8 entries it shows the first **7** in order, then one
  non-interactive line `play_results_more` (plural): "+3 more in your Logbook" — `bodyMedium`, `onSurfaceVariant`,
  48dp min height, start-aligned with the row text (after the 32dp tile + 12dp gap), no chevron (a tap would close
  results that show only once, PF6-4). The header total `play_results_total` always counts **all** entries.
  Semantics: the line is read in order; it adds nothing to the polite header announcement.
- Rank-up line and "Not claimed" unchanged (after the rows / the more-line).

## 4. Celebration (`../ux.md` § 3.10)

**Unchanged.** Still ≤ 2 lines: `play_celebrate_marks` (the walk's total, now including the trails kinds) and the
rank-up line. No park/climb line: the results section carries the detail; the island celebration already exists
for W-ISLAND and its `+1,000` is part of the marks line.

## 5. Me › Trail milestones (strip + sheet; roads `MilestoneStrip` / `MilestonesSheet` pattern)

### 5.1 Strip

Placement: a Me list item **directly under the PlayCard** (`key = "trail-milestones"`), trails + Play on only.
Same layout as roads (`RoadsMe.kt` `MilestoneStrip`): `SectionHeader` `play_trail_ms_title` "Trail milestones" with
action `play_trail_ms_all` "All" → sheet; `LazyRow` of `MilestoneBadge`s (96dp wide, 12dp gaps, `screenH` content
padding); a badge tap opens the sheet. **Always 4 badges, fixed order, one per ladder** (not roads' "2 earned +
2 next": these are the four tiles of the work item):

| badge | mark (medallion) | title | progress text (`play_trail_ms_progress` "%1$s / %2$s") | state |
|---|---|---|---|---|
| Climb | next step value ("820") | `play_log_climb_what` → "820 ft climbed" | so far / step: "673 / 820" | NEXT; all 6 earned → EARNED, last step, no progress |
| Trail miles | next step ("10" / "16.1") | `play_log_mile_what` → "10 mi of trail walked" | "7.4 / 10" | NEXT; top reachable step earned → EARNED |
| Parks | total parks ("19") | `play_trail_ms_parks_title` "Parks finished" | finished / total: "4 / 19" | NEXT; all → EARNED |
| High ground | total ("10") | `play_trail_ms_high_title` "High-ground trails" | "3 / 10" | NEXT; all → EARNED |

- Fractions: climb/miles = so far ÷ next step; parks/high ground = n ÷ total. A brand-new player sees all four at 0
  (NEXT, "0 / 328") — the strip is the invitation, never hidden for zero.
- Mile ladder reachable top = the highest step ≤ the island's non-quiet trail distance in miles (60 today; same
  `maxDistance` idea as roads). Climb top = 1,250 m (Q1).
- **Medallion colors (Play variant):** `MilestoneMedallion` / `MilestoneBadge` gain an optional colors parameter
  (default = today's roads values, so roads renders byte-identically). Play passes: EARNED fill
  `tertiaryContainer`, ring + mark `onTertiaryContainer` (12.9 / 7.2 : 1); NEXT fill `surfaceContainerLow`, dotted
  track `outlineVariant` (unchanged), progress arc `play.seaGlass` (non-text on surfaceContainerLow 5.3 / 9.8 : 1),
  mark `onSurface`; LOCKED unchanged. Never `primary` for Play progress.
- Loading (derived tables not ready): 4 skeleton badges (64dp `SkeletonBlock` circle + 72×12dp line), no shimmer
  under reduced motion. Derived tables missing (pack failure): item absent.
- a11y per badge (merged, `Role.Button`): NEXT `play_trail_ms_next_a11y` "Next: %1$s, %2$s of %3$s" ("Next: 820 feet
  climbed, 673 of 820 feet"); EARNED `play_trail_ms_earned_a11y` "%1$s, earned"; values use spoken units (§ 1).

### 5.2 "Trail milestones" sheet (roads `MilestonesSheet` shell)

`ModalBottomSheet`, `skipPartiallyExpanded`, `surfaceContainerLow`, scrollable, nav-bar inset; title
`play_trail_ms_title` `headlineSmall` heading. Four sections, each a `SectionHeader` (heading) + one so-far line
(`bodyMedium` `onSurfaceVariant`) + content:

1. **Climb** (`play_trail_ms_climb` "Climb") — `play_trail_ms_so_far` "So far: %1$s" ("So far: 1,207 ft"); `FlowRow`
   of all 6 step badges (EARNED / NEXT / LOCKED), each title `play_log_climb_what`, a11y as § 5.1 (LOCKED:
   `play_trail_ms_locked_a11y` "%1$s, not yet").
2. **Trail miles** (`play_trail_ms_miles` "Trail miles") — "So far: 41.3 mi"; `FlowRow`: every earned step, the
   next, then 2 locked (roads `lockedAhead = 2`), capped at the reachable top.
3. **Parks** (`play_trail_ms_parks` "Parks") — `play_trail_ms_parks_line` "%1$d of %2$d finished · %3$d started";
   one 56dp row per Play park (alphabetical; Pritchard Park never listed): park name `bodyLarge`; state text
   trailing `labelMedium`: `play_trail_ms_park_finished` "Finished" (`onSurface`, leading 32dp tile with the survey
   mark in `play.ink`), `play_trail_ms_park_started` "Started" (`onSurfaceVariant`, same tile), or
   `play_trail_ms_park_not_started` "Not yet" (`onSurfaceVariant`, tile glyph at 60 % α). Tap (whole row,
   `Role.Button`, chevron) → closes the sheet, opens the Trails list filtered to that park (existing park filter).
   a11y: "Gazzam Lake Preserve, started".
4. **High ground** (`play_trail_ms_high` "High ground") — `play_trail_ms_high_line` "%1$d of %2$d trails"; one 56dp
   row per eligible trail, steepest first: `Summit` tile (ink earned / 60 % α not), trail name `bodyLarge`, its
   climb per § 1 (`numeric`, `onSurfaceVariant`, nearest), trailing `play_rank_earned` "Earned" or nothing. Tap →
   closes the sheet, opens TrailDetail. a11y: "Peter's Trail, 302 feet of climb, earned".

Color is never alone: earned = word + glyph α, not hue. Large text: rows grow (`heightIn`), badges wrap (FlowRow).

### 5.3 Ranks screen

**Unchanged.** Ranks is the marks ladder; trail milestones have one home (Me). No change to `RankLadderRow`.

## 6. Start balance (opt-in and upgrade)

**Decision: one sibling entry, not a fold into `start_balance`, not one entry per rule.**
- At opt-in (and, for v1.1 players who already have a start balance, on the first launch of v1.1 with Play on),
  the trails rules' marks for past progress are written as **one** entry, kind `start_trails`:
  what "Trail rewards so far", why "Climb, trail miles, parks and high ground already walked", date = that day,
  no walk. If the island is already complete, its `+1,000` is a **separate `island` entry** (§ 2.2 text), because the
  sibling's frozen why does not name it.
- Play off → on with trail rewards earned meanwhile: a further `start_trails` entry, what "Trail rewards while Play
  was off", same why (mirrors `LogCopy.catchUp`). Nothing owed → nothing written.
- **Why one sibling:** (1) `start_balance`'s persisted why ("Coverage, completions and squares from before Play") is
  frozen and would become false; (2) v1 players already hold a `start_balance` and `hasStartBalance` forbids a
  second — the same sibling serves opt-in and upgrade; (3) roads never writes it, so the roads start balance stays
  byte-identical (AC 1); (4) one calm line instead of up to ~70 step rows; the per-step ledger lives in Play state
  (plan), not in Logbook rows. Glyph: survey mark; label `play_kind_start_trails` "Trail rewards so far".
- Order on the opt-in day: `start_balance`, `start_trails`, `island`. The PlayWake start-balance pop and
  `play_start_balance` copy show the **sum** of these entries (one number; no copy change).

## 7. Challenges cards (`ChallengeCard`, `../ux.md` § 2.5 — unchanged anatomy and states)

| id | glyph / kind chip | title (pack `text`) | body (pack `text`) | progress text | reward |
|---|---|---|---|---|---|
| `ch-climb` | Pennant · Just you · `Mon – Sun` | "Uphill week" | "Finish trails that climb {target} between them this week." | `play_challenge_progress_climb` "%1$s of %2$s climbed" → "95 of 150 m climbed" / "311 of 492 ft climbed" | 100 |
| `ch-parkhop` | Pennant · Just you · `Sat – Sun` | "Park hop" | "Finish a trail in different parks this weekend. Each park counts once." | `play_challenge_progress_parks` (plural on target) "%1$d of %2$d parks" → "2 of 3 parks" | 100 |

- `{target}` for the `climb` metric is filled by `ChallengesLogic` through § 1 (nearest, with unit), not
  `toString()`. The parks body carries no number (a reduced target of 1 would read "1 parks"); the number lives in
  the plural progress text. Progress bar = progress ÷ target (stored units); text per § 1 with the clamp;
  `coerceAtMost(target)` once done ("150 of 150 m climbed").
- States exactly as § 2.5: **Active**; **Done** (wash, Done chip, `+100` in `onSurface`); **Ended** ("Challenge
  ended", progress text stays, no reward); **Upcoming** (no progress row); **Loading** (skeleton 120dp).
  Progress before the server answers = local completions inside the window (as `ch-sweep` does).
- **Nothing left (Q3):** target = min(default, what remains for this player at window start: climb of uncompleted
  non-quiet trails / uncompleted Play parks). Remaining 0 → the card is **not rendered** anywhere for that player:
  Challenges screen, ChallengesChip count, What's On row. Remaining < default → the card shows the reduced target
  with no extra copy. Server computes the same (brief Q3).
- Card semantics (merged): "Uphill week. Just you. Monday to Sunday. 311 of 492 feet climbed. 100 marks."
- Pack copy obeys the validator tone rules; no number words in titles.

## 8. Copy keys added by this addendum (`strings.xml`, trails-only)

None has a roads twin (unreachable on roads); keys whose text contains "trail" join the guard's `TRAIL_ONLY` set in
`GoalVocabularyGuardTest`, like `play_kind_park_complete`. Render through `LocalGoal.current.s(...)` (identity on
trails) so AC 6's routing rule holds uniformly.

| key | text |
|---|---|
| play_kind_climb | Climb |
| play_kind_high_ground | High ground |
| play_kind_start_trails | Trail rewards so far |
| play_log_climb_what | %1$s climbed |
| play_log_mile_what | %1$s of trail walked |
| play_results_more (plural) | one: +1 more in your Logbook · other: +%1$d more in your Logbook |
| play_trail_ms_title | Trail milestones |
| play_trail_ms_all | All |
| play_trail_ms_progress | %1$s / %2$s |
| play_trail_ms_next_a11y | Next: %1$s, %2$s of %3$s |
| play_trail_ms_earned_a11y | %1$s, earned |
| play_trail_ms_locked_a11y | %1$s, not yet |
| play_trail_ms_so_far | So far: %1$s |
| play_trail_ms_climb | Climb |
| play_trail_ms_miles | Trail miles |
| play_trail_ms_parks | Parks |
| play_trail_ms_parks_title | Parks finished |
| play_trail_ms_parks_line | %1$d of %2$d finished · %3$d started |
| play_trail_ms_park_finished | Finished |
| play_trail_ms_park_started | Started |
| play_trail_ms_park_not_started | Not yet |
| play_trail_ms_high | High ground |
| play_trail_ms_high_title | High-ground trails |
| play_trail_ms_high_line | %1$d of %2$d trails |
| play_trail_ms_high_row_a11y | %1$s, %2$s of climb |
| play_challenge_progress_climb | %1$s of %2$s climbed |
| play_challenge_progress_parks (plural on %2$d) | one: %1$d of %2$d park · other: %1$d of %2$d parks |
| play_unit_feet_a11y / _metres_a11y / _miles_a11y / _km_a11y (plurals) | %1$s feet · %1$s metres · %1$s miles · %1$s kilometres (one: foot / metre / mile / kilometre) |

Persisted `LogCopy` lines (not resources; frozen once shipped): § 2.2 why column, the `start_trails` what/why and
catch-up what (§ 6), `island` what/why. Blocklist check (all of § 2, § 6–8 and the pack copy in § 7): no "race",
"run", "fast", "speed", "points", "beat", "sprint", no exclamation marks; unit is always "marks"; sentence case.

## 9. Acceptance criteria (UX; each checkable by test or screenshot)

1. **UX-1 Guard.** Roads with Play on: Me has no Trail-milestones item; no `ch-climb`/`ch-parkhop` card or chip
   count; results/Logbook unchanged (logic tests on the models return null/empty for roads; roads screenshots in
   AC 8 unchanged).
2. **UX-2 Rows.** Each of the 6 kinds + `start_trails` renders label, what, why, `+n` per § 2.2; `LogCopyTest` pins
   the new persisted lines; `climb`/`milestone` re-render in both unit settings (test: same entry, mi → "820 ft
   climbed", km → "250 m climbed").
3. **UX-3 Formatting.** `TrailRewardFormat` tests: floor/nearest/clamp at 99.9 m vs 100 m in ft; 149.9 vs 150 m
   challenge; mile steps in km one decimal; thousands separators.
4. **UX-4 Results collapse.** 9 entries → 7 rows + "+2 more in your Logbook", header total = sum of 9; ≤ 8 → no
   more-line.
5. **UX-5 Strip + sheet.** 4 badges in fixed order with § 5.1 values from a fixture state; sheet lists 6 climb steps,
   the mile ladder to the reachable top, all Play parks (no Pritchard) and all eligible high-ground trails with
   earned/not states; counts come from the derived tables.
6. **UX-6 Challenges.** Progress strings in both units; Done/Ended/Upcoming per § 2.5; remaining-0 card absent from
   screen, chip count and What's On.
7. **UX-7 Start balance.** Opt-in with prior progress → `start_balance` + one `start_trails` (+ `island` if
   reached); off → on → only a catch-up entry when owed; roads opt-in writes no `start_trails`.
8. **UX-8 A11y.** Every new row/badge/park/high-ground row is one merged node with § 2/§ 5 text and spoken units;
   targets ≥ 48dp; TalkBack + VoiceOver spot check on Logbook, results and Me recorded in the screens README.
9. **UX-9 Themes + large text.** Light and dark screenshots of Me strip and sheet; font scale 2.0 shows no
   truncated number.
10. **UX-10 Motion.** No new animation call; reduced motion leaves only existing behaviour (static rows, static
    total, `claim_pop` reduced variant).

## 10. Design-director verdict (own spec)

```
DESIGN BRIEF: docs/play/trails/brief.md · this addendum: docs/play/trails/ux.md · mockup: none (layouts reuse
built components; no ambiguity a mockup would settle)
PROMPT: n/a — designed in-repo (owner away, no external tool), as for docs/play/ux.md
VERDICT: ACCEPT
FINDINGS (conformance pass):
 1. Tokens: only BtTheme roles + PlayColors; seaGlass as non-text arc on surfaceContainerLow (5.3/9.8) and as +n text
    on surface (existing); medallion EARNED uses tertiaryContainer/onTertiaryContainer (12.9/7.2, measured in ux.md
    § 1.1). No status hue, positive or primary for Play. PASS.
 2. Both themes: every color is a theme role with light+dark values; the new glyph is ink-only. PASS.
 3. States: rows pending/local_only/accepted; strip loading/zero/next/earned/absent; challenges
    active/done/ended/upcoming/loading/hidden; start balance first/upgrade/catch-up/nothing-owed. PASS.
 4. A11y: merged nodes, spoken units, ≥ 48dp, headings, color never alone, no new announcement. PASS.
 5. Motion: none added; reduced motion unaffected. PASS.
 6. Copy: blocklist clean; persisted why strings unit-free; parks body avoids a broken plural. PASS.
 7. Buildability: LogbookRow, ResultsPlaySectionView, ChallengeCard, MilestoneBadge/MilestoneMedallion,
    MilestonesSheet shell, SectionHeader, ModalBottomSheet — all exist. Two small changes to existing pieces:
    an optional colors param on MilestoneMedallion/MilestoneBadge (roads default unchanged) and one FxGlyph
    (Summit) in the existing FxArt set. PASS.
NEW PRIMITIVES NEEDED: none (design_system: none; no new component type, token or motion).
```
