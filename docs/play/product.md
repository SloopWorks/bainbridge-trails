# Bainbridge Play — product spec

Date: 2026-10-20 · Owner: Product Designer (design-director, product capacity) · Status: ACCEPTED 2026-10-20 (see decisions.md)
Work item: `docs/play/brief.md` (the owner request quoted there is the authority for scope).
Precedence: `docs/mvp/decisions.md` and `docs/roads/decisions.md` > this file for anything they already decide. This
file never changes pure-mode behavior; where it seems to, the decisions files win and this file is wrong.
Authority (round-1 reconciliation, `docs/play/decisions.md`): **this file owns rules, economy and safety**;
`docs/play/schedule.md` owns time, determinism and the content pack schema (and encodes § 4 exactly);
`docs/play/engine.md` is normative for rendering; `docs/play/effects.md` conforms to all three; `docs/play/ai.md` is
trimmed to the v1 cut line. Visual layout lives in `docs/play/ux.md`. Glossary and the AC traceability matrix:
`docs/play/README.md`. This file owns behavior, rules, numbers, copy, content, flows and acceptance criteria.

Conventions (in addition to `docs/mvp/product.md` and `docs/roads/product.md` conventions)
- **Play** = the opt-in layer described here. **Pure mode** = the app with Play off (today's app).
- **Goal** = trails (Bainbridge Trails app) or roads (Bainbridge Roads app), per `DatasetProfile`. Play exists in
  both apps, separately: marks, logbook and board are **per goal** (one per app, like the pure boards, RD-7).
- **Marks** = Play's unit (§ 3.1). Never "points": the results sheet already uses `pts` for island
  percentage points (`results_delta_pts`).
- **Content item** = one entry in the content pack `play-content.json` (served at `GET /v1/play/content`): a
  treasure, egg, obstacle, riddle, challenge, lore, creature (v2) or ambient item. Content text (riddles, lore) lives in the **content pack** (§ 6), not in `strings.xml`, so the owner can edit it
  without an app release. App chrome copy lives in `strings.xml` with the `play_` prefix (§ 7.3).
- **Island time** = `America/Los_Angeles`. Every schedule, day boundary, week and season in Play uses island time,
  whatever the phone's time zone.
- **Week** = the half-open span [Monday 00:00, next Monday 00:00) island time. **Week id** = that week's Monday date,
  `2026-10-26` (never an ISO week number).
- **Daylight** = from civil dawn to civil dusk at 47.63 N, 122.52 W, computed on device (no network). **Creature
  hours** = sunrise + 30 min → sunset − 30 min.
- **Season** = an island-time quarter (§ 3.7.2): Storm (Jan–Mar), Strawberry (Apr–Jun), Madrona (Jul–Sep), Kraken
  (Oct–Dec). A week belongs to the season of its Thursday. The only meaning of "season" in Play.
- **Occurrence key** = `<itemId>@<Monday-or-start date>` (+ `#n` for one placement of a multi-placement item), the
  identity of one appearance of a content item (schedule.md § 2.2). Item ids are immutable once published.
- Vocabulary follows the glossary in `docs/play/README.md`: marks (never points), Logbook (never ledger), Play chip
  (never ledger pill), challenge (never race), quiet zone, kraken attack / stretch / repel, fog = exploration fog
  only, chart square = the single grid.
- **Counted** / **included**: as defined in the trail and roads specs. Coverage math is untouched by Play.
- Copy rules from `docs/mvp/product.md` § 3 apply: sentence case, calm, local, short. No exclamation marks in Play
  chrome. Copy never says run, race, sprint, fast or speed (§ 8.4). Copy that names the goal routes through
  `GoalVocabulary`; keys marked **(goal)** have a `roads_` twin with the same arguments.
- Coordinates are WGS84 `lat, lon` with 5 decimals. Every seed coordinate below is either taken from the shipped
  datasets (named by feature id) or from a cited source; the ones marked **TBC** need an on-site or owner check
  before the content ships.

---

## 0. One-screen summary

Play turns the island into a game board without touching the honest map underneath.

- **Off by default.** Turned on in Settings or from a single gentle offer after your first finished walk.
  With Play off the app is exactly today's app: same screens, same numbers, no Play network calls.
- **Marks come from your feet.** Every mark is earned by a recorded walk: new metres, finishing a trail or road,
  keeping a walking week, lifting fog over an uncharted square, and finding things placed on the island.
  Tapping on the map can *discover* and *solve*, but only walking *claims*.
- **Things to find (v1):** weekly treasures, permanent hidden easter eggs, place riddles, the weekly kraken on
  shore roads and trails, fog over uncharted squares, two challenges, and lore with Frog Rock, the ferry and island
  history. Prue's House and Grand Forest creatures are v2 (see v1 scope).
- **History with respect.** Remembrance places (the Japanese American Exclusion Memorial, the cemetery) are quiet
  zones built from dataset geometry: no marks, no effects, no trivia. Pritchard Park is out of every pool.
- **Safe by rule.** Finds only in daylight, the kraken waits for the tide to drop, nothing on private land, state
  routes or roads over 25 mph (`roadSafety`, § 4.0.2), nothing that asks anyone to go fast.
- **Private by design.** GPS never leaves the phone. Play syncs which content items you claimed and in which week.
  Claiming a fixed-place item tells the server you were near that place some time that week; it never learns the
  day, the time or your route (§ 3.10). The copy and the privacy policy say so.
- **$0.** Content pack is a static file on the existing Cloudflare Worker; tides and ferry times are bundled into
  it at build time; sun times are computed on device.

Glossary
| term | meaning |
|---|---|
| Marks | Play's unit (never "points"). Integer. Per goal. |
| Logbook | The record of every mark: what, why, when, how many (§ 3.8). Never "ledger". |
| Play chip | The marks · rank chip on the Map (§ 5.3). Never "ledger pill". |
| Rank | Title earned from cumulative marks (§ 3.7). |
| Chart square | One cell of the single Play grid, 0.01° lat × 0.015° lon (≈ 1.11 × 1.12 km) anchored at 47.55 N, 122.62 W. Only squares containing at least one counted segment exist in a goal: 62 for trails, 83 for roads today (computed, never hard-coded). Treasure spacing and the scheduler's density cap use the same squares. |
| Fog | Exploration fog: per-user Play overlay on chart squares you haven't walked in yet. Lifts on first new coverage there. The only meaning of "fog" in Play. |
| Sensed | Provisional: the phone noticed you reached a content item during a walk (live, like live coverage). |
| Claimed | Confirmed at Finish by the Finalizer after the walk passes the same gates as coverage. Only claimed items earn marks. |
| Armchair | Found or solved by tapping on the map without walking. Earns a logbook stamp, never marks. |
| Play-safe anchor | A place Play may put content (§ 4.0.2). |
| Quiet zone | An area built from dataset geometry where Play shows no effects, no content and awards nothing (§ 4.6.3, § 8.3). Never "respect zone". |
| Content pack | Owner-edited JSON describing content, schedules, ferry timetable, tide table, quiet zones (§ 6; schema in schedule.md § 4). |
| Challenge | A window-boxed goal (§ 4.5). Type `challenge` everywhere; never timed, never "race". |
| Occurrence key | `<itemId>@<Monday-or-start date>[#n]`: the id a claim carries (Conventions). |

---

## v1 scope

The round-1 review's cut line (`docs/play/reviews/2026-10-20-round1-specs.md`), adjusted only where a round-1 fix
changed it (noted in *italics*). Every later item keeps its design text in all docs with a **(v2)** tag in its
heading or row; every cut item is reduced to a one-line **(cut)** tombstone.

**Ships in v1**
- Opt-in from Settings + the one-time offer; off = the pure app (§ 5, PF0).
- Marks for walk-derived events: W-COV, W-DONE, W-SQUARE (exploration fog), W-DAY, W-WEEK, and the starting balance
  (§ 3.2, § 3.6.4). Logbook and ranks (§ 3.7.1, § 3.8).
- Weekly treasures, fixed tiers (§ 4.1). Permanent seed eggs: global, 15 m, once, a simple stamp (§ 4.2).
- The weekly kraken: one coast, clock window Thu 06:00 → Sun 20:00, daylight, `roadSafety` roads (≤ 25 mph)
  (§ 4.3.2). *Its stretches are inactive above the tide threshold (P0-5 fix), so v1 bundles the NOAA high/low table
  for that one rule (schedule.md § 1.6); a missing or expired table keeps stretches inactive.*
- Permanent riddles with place and feature answers (§ 4.4).
- Lore cards with fixed anchors: Frog Rock, the ferry, history, and remembrance as quiet (§ 4.6).
- The ferry sprite on the timetable, visual only (§ 4.6.1 `lore-ferry`).
- Two challenges: `ch-sweep` and `ch-krakenweek` (with F-INK, F-RETREAT and C-ISLAND) (§ 4.5).
- The Mar 30 quiet day (§ 4.6.3).
- Claims on the coverage upload (§ 3.10); server-derived marks (§ 3.6.3).
- Effects: `treasure_glint` / `treasure_found`, `kraken_attack` / `kraken_recede` / `kraken_lurk`, `riddle_marker` /
  `riddle_light`, `frog_rock`, `ferry_crossing` (layers), the `exploration_fog` layer, `claim_pop`, `play_wake`
  (effects.md).
- MapFx engine stages 0–7 and 9 (engine.md § 4).
- The e2e flow of brief AC 8 (script in the plan, `docs/play/README.md`).

**Later (v2)**
- The Play board, its consent and seasons views (§ 3.7.2, § 5.4, PF13).
- Creatures and tracks (creature hours only, every fact sourced) (§ 4.6.1).
- Prue's House (OD-1).
- Brambles (§ 4.3.3).
- F-HORN and `ch-crossing` (§ 4.6.2, § 4.5).
- Tide-gated shore point items (low-water windows): shore-tagged treasures, eggs, riddle answers and lore
  (§ 8.2 T-2).
- Personal-salt eggs (schedule.md § 2.4) and treasure-map fragments.
- Event windows other than Mar 30 (Frog Rock's birthday, Agate Pass Bridge, the Oct 31 second attack, monthly
  themes, `ch-strawberry`).
- Shaders (engine stage 8) and Lottie.
- Notifications (schedule.md § 6).
- All of ai.md except offline authoring.
- *Not named in the review's cut line, therefore deferred with the rest:* W-PARK1, W-PARKC, the roads milestones
  W-MILE / W-ROADS / W-TYPE, W-ISLAND, C-WAVE, `ch-foglifter`, `ch-bestweek`, weekly riddles, count-on-site riddles,
  `proximity_ripple`, `easter_egg_reveal` (v1 eggs use its reduced stamp), `lore_stamp_ink`, `tide_lines`.

**Cut entirely**
- `race_track` (timed courses, countdown, ghost, finish times) · `streak_float_line` · weather and rain · morning fog ·
  `tide_cut` · `fallen_tree` and `fog_bank` · the king-tide kraken · named race standings and write-ups (AI-6) ·
  Mochi Tsuki gameplay.

---

## 1. Principles

| # | principle | what it means in practice |
|---|---|---|
| PP-1 | **Opt-in.** | Play is off on install and after update. Turned on only by the user, from Settings or the one-time offer (§ 5). Never auto-enabled by sign-in, a promo, or a server flag. |
| PP-2 | **Pure mode untouched.** | With Play off: identical screens, strings, numbers, network calls, analytics events and battery profile to the build without Play. With Play on: coverage, completion thresholds, island %, roads milestones, celebrations, the pure leaderboard and the sync contract are unchanged. Play adds a layer and sections; it never edits a pure number or re-orders pure content. |
| PP-3 | **Honest numbers.** | Marks never feed island %, completions or the pure board. Every mark has a logbook line saying exactly why. Provisional numbers are labelled ("Found on this walk — confirmed when you finish"). No hidden multipliers, no random rewards, no "mystery" amounts: every reward is shown before you earn it. |
| PP-4 | **Marks come from feet.** | Marks require a recorded walk that passes the coverage gates (vehicle, speed, steps, mock). Armchair discovery and solving are allowed and fun, but claim nothing until you walk there. |
| PP-5 | **Kid-safe.** | Kids may play alongside a parent. No chat, no messaging, no user-generated text in Play, no other players' locations, no meetups, nothing frightening (the kraken is a mischief-maker, not a monster), no dares. Creature cards carry a "give wildlife space" line. Names follow the existing moderation rules. |
| PP-6 | **Safe places only.** | Content is placed only on Play-safe anchors (§ 4.0.2): public counted trails outside quiet zones, and counted roads that pass the one `roadSafety` rule (residential, collector or arterial; ≤ 25 mph; public). Never on private or unknown-ownership roads, lanes, driveways, state routes, roads over 25 mph, or private property, and never on a beach. |
| PP-7 | **No lures at night or at high water.** | Finds and encounters are only active in daylight (civil dawn → civil dusk); creatures (v2) only in creature hours. Kraken stretches are inactive while the predicted tide is above the threshold (§ 8.2). Shore-tagged point content waits for a low-water window (v2). Play sends no push notifications at all. |
| PP-8 | **Nothing asks for speed.** | Challenges measure what you covered in a window, capped, never how fast. No timers, countdowns, pace, ghosts or finish times. Community results are `{band, count}` only, never a ranked list of people. UI and code say "challenge", never "race". |
| PP-9 | **Privacy stated honestly.** | No raw GPS, no walk times, no walk dates leave the phone. Play syncs occurrence keys, week ids and hint counts only (§ 3.10). A claimed fixed-place item tells the server you were within its radius of that place some time that week: the copy (`play_about_privacy`) and the privacy policy say so. The server never stores a day. The Play board (v2) shows name, rank title and marks only. |
| PP-10 | **$0 hosting.** | Content pack = static JSON on the existing Worker with ETag; aggregates in the existing D1. No third-party calls from the phone (no tide API, no ferry API, no weather API, no maps API beyond the bundled PMTiles). |
| PP-11 | **Respect history.** | Real history is cited and verified; myths are labelled as myths; invented things are labelled as made-up ("an island tall tale"). Remembrance places are quiet (§ 4.6.3). Indigenous cultural sites are included only after consultation with the Suquamish Tribe (owner decision OD-5). |
| PP-12 | **Never punish.** | Nothing is lost by not playing: no streak that breaks with shame, no decay, no expiring balance. Missed weekly content simply moves on. |
| PP-13 | **Human-authored content.** | No generative AI at runtime (cost, kid safety, accuracy). AI may help the owner draft content offline; every published lore line is human-reviewed and sourced. |

---

## 2. Core Play loop

### 2.1 How Play composes with the existing loop

The six existing steps stay, in order, with their pure content first. Play adds a layer to each.

| step | pure (unchanged) | Play adds (only when on) |
|---|---|---|
| **See** | Map colored by your status | Exploration fog over uncharted squares; this week's treasures (glints); the kraken's ink on attacked shore stretches; riddle markers and the Challenges chip; lore markers; the ferry sprite on its timetable crossing Eagle Harbor; the Play chip (marks · rank). |
| **Pick** | Tap a trail/road → detail → Start walk | Tap a Play marker → content sheet (what it is, reward, how to claim, when it ends) → "Walk there" opens the nearest trail/road detail with Start walk. Lists show a small Play badge on trails/roads that carry content this week. |
| **Walk** | Record, live provisional coverage | Live "Nearby" strip (≤ 3 items within 400 m, sorted by distance); find moments when you reach something (sensed); fog lifting as you enter a new square; kraken ink receding behind you. All provisional. |
| **Progress** | Results: before/after, island % | A **Play section below** the pure results: marks earned this walk as logbook lines, claimed finds, anything that didn't claim and why. |
| **Celebrate** | Full-screen completion moment | One extra line under the body for the completion marks ("+100 marks"); rank-up shown as a line in the same screen, never a second modal (keeps D15/RP-9). A walk with finds but no completion gets no modal: finds show in results. |
| **Compare** (v2) | Pure board | A **separate** Play board (opt-in consent) by marks, per season and all-time. The pure board is untouched. |

### 2.2 Persona loops

**P1 Dana — Island Completionist (47, dog, mornings; uses both apps, roads daily).**
- *Daily:* opens Map before the dog walk. Sees the kraken's ink on Manitou Beach Drive (Thursday), and that her
  usual loop passes one of this week's sea glass glints. Walks. Results: +23 coverage marks, +15 walking day,
  +25 sea glass. Logbook keeps it tidy.
- *Weekly:* "kept week" (walked on 2+ days) lands Sunday night; the kraken retreats if the island repelled it.
- *Monthly:* lifts fog over one more chart square; the month's theme gives her a reason to take a different
  street.
- *What keeps her:* small, honest, frequent numbers; a reason to vary the route; never a broken streak.
- *Trails app:* same loop; treasures sit on trails; the kraken attacks shore trails.

**P2 Marcus — Weekend Explorer (34, kids, privacy-minded).**
- *Weekend:* Saturday morning the kids pick a riddle on the map ("I'm a frog who never hops"). They solve it at
  the table (armchair: stamp, no marks), walk there, and claim it. (v2: on the way, a barred owl encounter card in
  creature hours, "give owls space".)
- *Challenge:* "Weekend sweep" — finish 3 trails Saturday–Sunday. Short trails make it doable with kids.
- *Compare (v2):* checks his rank title ("Salal Scout") on the Play board under an anonymous name. No location, no
  times shown anywhere. In v1 he sees his rank on Me.
- *What keeps him:* things to find, a reason to pick *this* park *this* weekend, a light competition.

**P3 Neighbourhood Runner (roads app).**
- Play doesn't change their run. Coverage marks and kept weeks work as for walkers (runs pass the 4.0 m/s
  plausibility rule). Finds claim the same way. Play never asks them to go faster, and no challenge rewards pace.

### 2.3 Per goal

| aspect | trails goal | roads goal |
|---|---|---|
| Where content sits | Included segments of named trails outside quiet zones; trailheads; road-end trails | Counted roads passing `roadSafety` (§ 4.0.2, computed at pack build); road ends |
| Exploration unit | Chart squares (62) (+ parks, v2) | Chart squares (83) |
| Completion marks | 100 per trail (quiet trails never) | 25 per road |
| Milestone marks (v2) | Park complete (all named trails in a park) +150; island +1,000 | Existing roads milestones (§ 3.2): distance +50, roads ladder +50, type +250, island +1,000 |
| Kraken | Attacks shore trails (4 coasts) | Attacks shore roads (6 coasts) |
| Ferry | Sprite on the timetable, visual only (horn encounter v2) | Same |
| Typical walk | 1–3 km in a park | 2–5 km on streets |

Both goals are tuned so a player who finishes the goal's map earns about the same from walking alone (≈ 23k marks
in v1, ≈ 27–28k once the v2 park, milestone and island marks land; § 3.7.3).

---

## 3. Marks economy

### 3.1 Unit, naming, display

- Name: **marks** (survey marks on a chart; fits "Chart room"). Singular "mark". Display as an integer with a
  thousands separator: `1,240 marks`. Never decimals. Never abbreviated in body text; the Play chip may use
  `1.2k` above 9,999.
- Record name: **Logbook** (never "ledger").
- Marks are per goal and per user. They never decrease (no spending, no decay, no penalties). There is nothing to
  buy with marks (no shop, no loot, no currency; brief non-goal).
- Marks are integers computed by deterministic rules from claimed events. The same events always give the same
  marks on any device and on the server.

### 3.2 Walk-derived marks

Earned on every finished walk with Play on (and retroactively once, at opt-in, § 3.6.4).

| id | event | trails | roads | rule | cap |
|---|---|---|---|---|---|
| W-COV | New counted coverage | **Fixed rate:** 1 mark per 10 m of new counted coverage outside quiet trails, cumulative: `floor(nonQuietCoveredM / 10)`; walk's marks = value after − value before | **Fixed rate:** 1 mark per 25 m: `floor(coveredM / 25)` | A fixed rate per goal, never a share of the island total, so a dataset update that grows the map never lowers anyone's marks; the server credits `max(previous, new)` so a dataset update that removes segments can't either (monotone). Same `coveredM` as island % (shared segments once), minus quiet trails' metres. Computed from the post-Finalizer snapshot, never live. Today's whole maps: ≈ 10,296 (trails, 102,964 m outside quiet trails) and ≈ 10,461 (roads, 261,537 m). | Bounded by the map. |
| W-DONE | A trail/road crosses completion (`newlyCompleted`) | +100 each | +25 each | Uses the walk summary's `newlyCompleted` (sticky set, TL-7). Uncounted roads never. Quiet trails (§ 4.6.3) never. | Bounded by N. |
| W-CLIMB (v1.1 trails) | Cumulative climb ladder | +50 per step | — | Σ `gain_m` (`trails.json`, per trail) of **completed** non-quiet trails; partial coverage adds nothing. Steps keyed in **metres**: 100, 250, 500, 750, 1,000, 1,250 (1,500 is unreachable: the non-quiet total is 1,368.8 m). Shown in the user's unit (ft with mi, m with km, integer). Trails that share segments each count their own `gain_m` (completion-based, like W-DONE; brief Q4). Never from GPS or barometric altitude. PD-26. | 6 steps. |
| W-STEEP "High ground" (v1.1 trails) | A steep trail finished | +50 | — | Completing a non-quiet trail with `gain_m ≥ 40`; once per trail id, by value. PD-26. | 10 trails today. |
| W-PARK1 (v1.1 trails) | First new coverage in a park | +50 per park | — | Park = `trails.json` `park` (non-null), except Pritchard Park (quiet, never a Play park). First **non-quiet** coverage; once per park ever, by value. PD-26. | 19 parks today. |
| W-PARKC (v1.1 trails) | Every named trail in a park complete | +150 per park | — | Every named **non-quiet** trail; fires on the walk that completes the park's last trail. PD-26. | Once per park (19 parks, 7 single-trail). |
| W-MILE (v1.1 trails; roads v2) | Distance milestone (1, 5, 10, 15 … 60 in user's unit) | +50 per step | +50 | Same `Milestones` ladder math (RP-8). Trails (v1.1, PD-26): cumulative **non-quiet** covered trail metres (W-COV's `coveredM`), `nonQuietCoveredM / 1,609.344`, every step crossed paid (13 steps on today's 102,964 m). Roads (v2): highest crossed only (as today). Unit switching can't double-pay: Play records paid milestones by value in **miles** (km converted down). | Bounded by island length. |
| W-ROADS (v2) | Roads-completed ladder (1, 10, 25 … 400) | — | +50 | As RP-8. | 11 steps. |
| W-TYPE (v2) | Every road of a type complete | — | +250 | As RP-8. | 4. |
| W-ISLAND (v1.1 trails; roads v2) | Island complete | +1,000 | +1,000 | Existing `islandReached` (trails: island fraction ≥ 1 − 1e-9, PD-26). | Once. |
| W-SQUARE | First new coverage in a chart square ("fog lifted") | +25 | +25 | Square derived on device from the segment coordinates of the newly covered ticks. Counted segments only, never quiet trails (a square lifts only through non-quiet coverage). Once per square ever. | 62 / 83. |
| W-DAY | Walking day | +15 | +15 | First finished walk of the island-time day with duration ≥ 10 min **and** matched distance ≥ 500 m on any mapped segment (repeats count, same as "miles on this phone"). Rewards Dana's familiar loop. | 1 per day. |
| W-WEEK | Kept week | +40, +10 for each consecutive kept week before it, max +100 | same | A week is kept when W-DAY was earned on **≥ 2 different days** of the week. Awarded at week close (Monday 00:00 island time; the week is half-open), logged on Sunday. The server derives it from the walks' `dayKey`s (§ 3.10). Missing a week resets the run to the base +40 next time; nothing else happens, no "streak lost" copy. Two days so weekend walkers (Marcus) qualify. | 1 per week. |

Why no daily streak: daily streaks create compulsion and punish rest days, dogs that are sick and kids' weekends.
A two-day week is achievable for both personas and still rewards habit.

### 3.3 Find-derived marks

All require a **claimed** find (§ 3.6). Amounts are shown on the content sheet before you go.

| id | event | marks | repeat | notes |
|---|---|---|---|---|
| F-TREASURE | Weekly treasure claimed | Sea glass 25 · Agate 40 · Glass float 60 · Ship's bell 150 | Each treasure once; new set weekly | Tier counts per week, § 4.1. |
| F-EGG | Easter egg claimed | 20 | Once per egg ever | Hidden until found; armchair taps never claim. |
| F-RIDDLE | Riddle claimed | ★ 40 · ★★ 80 · ★★★ 120; each hint used −25 %, floor 50 % | Once per riddle | Solving at home = stamp; walking to the answer = claim. |
| F-INK | Kraken stretch repelled (one road or trail) | **Buckets by % of the stretch walked in one walk:** ≥ 60 % → 60 · ≥ 80 % → 70 · ≥ 95 % → 80 | Once per stretch per attack | § 4.3.2. The claim carries the bucket (`band`, § 3.10); the server accepts a bucket only if at least that share of the stretch's segment length carries this user's coverage bits, and derives the marks itself. Repeat metres count on device (it's an obstacle, not coverage). |
| F-RETREAT | Island repels the kraken | +50 to everyone who repelled ≥ 1 stretch that attack | Once per attack | Community result, server aggregate (§ 4.3.2). |
| F-BRAMBLE (v2) | Seasonal bramble cleared (trails, August) | 40 | Once per bramble per season | § 4.3.3. |
| F-LORE | Lore encounter, first time | 15 | First per encounter; (v2) creatures first per season | Remembrance encounters: 0, always (§ 4.6.3). |
| F-HORN (v2) | Ferry horn heard (recording within the Winslow waterfront zone at a scheduled departure) | 10 | Max 2 per day | § 4.6. Commuters who walk to the boat earn it honestly. |

### 3.4 Challenge-derived marks

| id | event | marks | notes |
|---|---|---|---|
| C-SOLO | Solo challenge completed | 50 · 100 · 150 (by tier, shown up front) | Window-boxed, capped, personal. § 4.5. |
| C-ISLAND | Island (community) challenge succeeded | 75 to every participant who contributed ≥ the minimum | Collective target; nobody beats anybody. |
| C-WAVE (v2) | "First wave" (contributed within the first 48 h of an island challenge) | +25 badge bonus | Everyone in the window, not a ranking. |

### 3.5 Caps and anti-grind

| rule | value | enforced |
|---|---|---|
| Daily found-marks cap (F-*, C-* summed, island day) | 400 | Client (the server never learns days). Extra finds still claim and get a logbook stamp "Daily limit reached — found, no marks". |
| Weekly found-marks cap (week id) | 2,800 | Server, per week id. |
| W-DAY | 1 per day | Client; server ≤ 7 `dayKey`s per week id. |
| W-WEEK | 1 per week | Server-derived from `dayKey`s (≥ 2 in the week). |
| F-INK | 60 / 70 / 80 by bucket, once per stretch per attack | Both (server checks the bucket against the stretch's coverage bits). |
| F-HORN (v2) | 2 per day | Client; server ≤ 14 per week. |
| Repeat content | Each occurrence key claims once (items with `repeat: once` claim once ever across occurrences; per season for (v2) creatures, per attack for kraken stretches) | Both |
| Find claims: replay guard | The occurrence must have ended no earlier than the upload's server island date − 2 days. | Server |
| Week-keyed items: late grace | `dayKey`s, W-WEEK and challenge contributions are accepted until the week's end + 7 days. | Server |
| Clock tolerance (the only statement of it) | The device clock is trusted only inside the two rows above plus: an occurrence's start must be ≤ server now + 1 h, and a week id must be the server's current week or the one before. Nothing else in Play trusts the device clock. | Server |
| Content version | Claims are evaluated against the pack version they name (`playConfig`); the Worker keeps the last 8 published packs; an unknown version → `stale_config`, the claim stays `local_only`. | Server |
| Coverage-derived marks | Recomputed by the server from **accepted** coverage | Server is authoritative; client value shown locally. |
| Armchair | 0 marks, ever | Both. |
| Diminishing returns on the same route | Repeat metres earn coverage marks 0 (coverage is unique by definition); only W-DAY (1/day) and kraken stretches (1/attack) reward repeats. | Built into the rules. |

This is the **only caps table** in the Play docs. `schedule.md` § 4.2 (`caps`) and § 7 encode exactly these values.

Rationale: walk-derived marks are bounded by the island, so they need no cap. Find marks are the farmable part,
so they are capped per day and per week; at the caps a player is walking for hours.

### 3.6 Claiming, plausibility and confirmation

#### 3.6.1 Sensed (live, during a walk)
A content item is **sensed** when, during an active recording:
1. The phone has a fix with horizontal accuracy ≤ the item's radius (treasures 20 m trails / 25 m roads; eggs
   15 m, 25 m for owner-flagged canopy eggs; lore and riddle answers 30 m), and
2. The fix is within the item's radius (or, for walk-through items, the walk has covered the required stretch in
   the live estimate), and
3. Speed over the trailing 60 s ≤ 4.0 m/s (the server plausibility speed), and the fix is not mock, and
4. The item is active now (schedule, daylight, tide, quiet zones; § 8) and the fix is outside every quiet zone, and
5. Play is on.
Sensing shows the find moment (§ 7.1 PF5) labelled provisional. Sensing does not need network. Sensing runs in a
**graph-level observer** (`PlaySensing` on `AppGraph`, subscribed to the recording's fixes from
`RecordingController`), not in a screen, so it works with the Record screen closed. The Record screen's own
`TrailMap` is a gated MapFx call site (engine.md PF-8) that shows find moments when it is visible.

#### 3.6.2 Claimed (at Finish)
At Finish, after the Finalizer gates the track (vehicle/speed/steps filters), each sensed item is re-checked
against the **gated** track: at least one **kept** fix must satisfy 3.6.1 rules 1–4. The kept fixes come from a new
Finalizer hook, `onGated(sessionId, keptFixes)`, called inside `finalize` after the coverage fix filter (mock,
accuracy and speed filtering live in `coverage/Fixes.kt`, `filterFixes` / `SegmentIndex.coverFixes`, not in
`Gating`). The hook is null when Play is off and idempotent per (sessionId, contentId). Items that pass are claimed and
written to the logbook. Items that fail are listed once in the results Play section with the reason
(`play_unclaimed_*` copy). Coverage and Play use the same gated track, so a car ride that the Finalizer drops can't
claim anything.

Items can also be claimed at Finish without having been sensed live (screen off, Record screen not visible,
TL-8): the Finalizer pass checks every item active during the walk. Live sensing is a nicety, Finish is the truth.

#### 3.6.3 Server acceptance
- A walk's claims ride that walk's coverage upload (TL-2) as one element of `play[]`, tied by `walkId` to the
  upload's `walks[]` (§ 3.10). If the coverage upload is rejected (`422 implausible`), its claims are rejected too:
  marks stay on the phone (shown, logbook marked `local_only`), exactly as rejected coverage stays local today
  (`sync_rejected_notice`). Walks over `SyncRepository`'s per-upload walk cap keep their Play element queued for the
  next upload instead of being dropped uncredited.
- Server checks per claim, against the pack version named in `playConfig`: the id is an occurrence key of a live,
  non-killed item of this goal; the occurrence was active in the stated week id; the claim's `walkId` is credited
  by this upload; line and stretch items (kraken stretches, feature riddles) have anchor segments carrying this
  user's coverage bits after the merge; the repeat rule; the § 3.5 caps and time rules. Failing claims are reported
  with a code and kept locally as `local_only`.
- **The server derives every mark** from its content table and its accepted coverage. The client never sends a
  mark value; any client total is display only. Coverage-derived marks (W-COV, W-DONE, W-SQUARE; v2: W-PARK*,
  milestones, W-ISLAND) are **recomputed by the server** from accepted coverage with its bundled dataset version.

#### 3.6.4 Opt-in starting balance
When Play is first turned on, the walk-derived marks your existing progress already earned are credited once, as
one logbook entry "Your walks so far": W-COV, W-DONE and W-SQUARE (v2 adds W-PARK1, W-PARKC, roads milestones and
W-ISLAND) computed from the current snapshot. Not credited: W-DAY, W-WEEK, finds, challenges (no history is kept for them).
Rationale: honest (you walked it), and Dana's two years of mornings are not worth zero. Turning Play off and on
again never credits twice.

#### 3.6.5 Logbook restore
On a reinstall or a new device the local Logbook is gone but the server total is not. When Play is turned on while
signed in, the app reads `GET /v1/play/me?dataset=` (total, this week, per-kind counts and claimed occurrence keys)
and writes one entry, "Restored from your account", carrying the server total, so the Logbook total equals the
server total. Claimed occurrence keys are re-marked as found (no second claim). Detail lines from before the
restore are not recoverable; the entry says so.

### 3.7 What marks are for

#### 3.7.1 Ranks (titles)
Cumulative marks per goal. Titles are Bainbridge-flavoured and goal-neutral (same ladder in both apps).

| # | title | marks | flavour (shown on the Ranks screen) |
|---|---|---|---|
| 1 | Walk-on | 0 | Every islander starts as a walk-on passenger. |
| 2 | Dock Walker | 250 | You know where the boats come in. |
| 3 | Beachcomber | 750 | Sea glass in your pockets. |
| 4 | Salal Scout | 1,500 | Through the understory, off the main path. |
| 5 | Madrona Wanderer | 3,000 | Red bark, long views. |
| 6 | Heron Watcher | 5,000 | Patient at the water's edge. |
| 7 | Fog Navigator | 8,000 | You walk where the chart is still blank. |
| 8 | Tide Reader | 12,000 | You know when the beach is safe. |
| 9 | Kraken Tamer | 17,000 | The kraken knows your name. |
| 10 | Island Cartographer | 23,000 | Most of the chart is yours. |
| 11 | Keeper of the Chart | 30,000 | The island, walked and known. |

- Rank-up is shown as one line in results (or in the celebration if one is showing), never a separate modal.
- No title uses "ranger", "official", "admin" or anything in the impersonation blocklist.
- Titles never appear on the pure board.

#### 3.7.2 The Play board (v2)
- A **separate** leaderboard per goal: `GET /v1/play/board?dataset=…` (backend doc to define). The pure board
  (`/v1/board`) never shows Play data and Play never changes its order.
- **Consent:** a user appears only after turning on "Show me on the Play board" (default off, § 5.4). Signed-out
  users can view it.
- **Views:** "This season" (default) and "All time". Seasons are island-time quarters, named:
  Q1 Storm season (Jan–Mar) · Q2 Strawberry season (Apr–Jun) · Q3 Madrona season (Jul–Sep) · Q4 Kraken season
  (Oct–Dec). Season view ranks by marks earned in that quarter (server sums by week id → quarter of the week's
  Thursday). Seasons reset the view, never the marks.
- **Row:** rank, display name (existing identity), rank title, marks. Nothing else: no finds, no locations, no
  dates.
- **Order:** marks desc → server time the user reached that total (never shown) → user id. Unique ranks. Top 100
  + you. Others from the daily snapshot (TL-11); your row live.
- **Eligibility:** consented, signed in, not hidden/deleted, marks > 0, Play on (turning Play off hides the row
  on the next sync; turning on restores it).
- Reports/moderation: existing name report flow applies unchanged.

#### 3.7.3 Economy totals (today's data)
| source | trails | roads |
|---|---|---|
| Coverage (W-COV, fixed rate) | 102,964 m / 10 ≈ 10,296 | 261,537 m / 25 ≈ 10,461 |
| Completions (W-DONE) | 111 non-quiet trails × 100 = 11,100 | 430 × 25 = 10,750 |
| Chart squares (W-SQUARE) | 62 × 25 = 1,550 | 83 × 25 = 2,075 |
| **Walking the whole goal, v1** | **≈ 22,946** | **≈ 23,286** |
| Climb ladder (W-CLIMB) (v1.1 trails) | 6 × 50 = 300 | — |
| High ground (W-STEEP) (v1.1 trails) | 10 × 50 = 500 | — |
| Trail miles (W-MILE) (v1.1 trails) | 13 × 50 = 650 | — |
| Parks (W-PARK1 + W-PARKC) (v1.1 trails) | 19 × 50 + 19 × 150 = 3,800 | — |
| Island (W-ISLAND) (v1.1 trails; roads v2) | 1,000 | 1,000 |
| **Walking the whole goal, v1.1** (PD-26) | **≈ 29,196** (22,946 + 6,250) | **≈ 23,286** (unchanged) |
| Roads milestones (v2) | — | distance 33 × 50 + ladder 11 × 50 + types 4 × 250 = 3,200 |
| **Walking the whole goal, with v2** | **≈ 29,196** | **≈ 27,486** |
| Typical weekly content available (finds + kraken + challenges) | ≈ 500–800 | ≈ 600–900 |

So "Keeper of the Chart" (30,000) needs the whole map plus several seasons of Play content: a multi-year title for
both goals. The trails/roads asymmetry is accepted (PD-26): ranks are per goal and nothing compares across goals.
Counts are recomputed from the dataset at build; the table is illustrative.

### 3.8 The Logbook

Every mark is one logbook entry. Entries are append-only and stored locally (Play summary syncs them as totals and
content ids, § 3.10).

| field | type | example | shown? |
|---|---|---|---|
| `id` | uuid | — | no |
| `kind` | enum: `coverage`, `complete`, `park_first`, `park_complete`, `milestone`, `island`, `square`, `day`, `week`, `treasure`, `egg`, `riddle`, `ink`, `retreat`, `bramble`, `lore`, `horn`, `challenge`, `island_challenge`, `wave`, `start_balance`, `stamp` | `treasure` | as icon + label |
| `what` | string (resolved display text) | "Sea glass on Rose Loop" | yes |
| `why` | string (rule in words) | "Weekly treasure, common" | yes |
| `when` | local date (island time) + walk id | "Tue, Oct 27" | date only, on this phone |
| `marks` | int ≥ 0 | 25 | yes, `+25` |
| `walkId` | uuid or null | — | links to walk history entry (local) |
| `contentId` | occurrence key or null | `treasure-weekly-roads@2026-10-26#07` | no (debug drawer) |
| `weekId` | Monday date | `2026-10-26` | no |
| `board` | enum: `pending`, `accepted`, `local_only` | — | only `local_only` shows a note |

Display rules
- Grouped by day, newest first; a walk's entries grouped under that walk (date · distance).
- Week close entries (`week`, `retreat`, island challenges) are their own group on Sunday.
- `stamp` entries (armchair finds, cap-reached finds, remembrance visits) show with `0` hidden and a stamp icon;
  remembrance stamps show no number at all.
- Totals at top: all-time marks, this week (this season arrives with seasons, v2), rank + progress to next rank.
- Logbook never shows times of day (privacy and calm); dates only, on device only.
- The server never receives `when`, `what` or `marks`; it gets `kind`, the occurrence key and the week id per claim
  (§ 3.10) and derives the marks.
- Reinstall / new device: restored from the server summary (§ 3.6.5).

### 3.9 Worked example week (week id `2026-10-26`: Mon Oct 26 → Mon Nov 2 00:00, Kraken season; v1 rules)

Schedule that week (from the pack): treasure drop Mon 05:00; kraken attacks the **Manitou / Murden Cove** coast
(K5) Thu 06:00 → Sun 20:00; island challenge `ch-krakenweek` "Kraken week: repel every stretch on K5"; `ch-sweep`
Sat–Sun.

**Dana, roads app.** Before the week: 21,600 m covered (13.42 mi, 864 coverage marks at 1 per 25 m), 2,410 marks
total (Salal Scout), 3 consecutive kept weeks.

| day | walk | logbook entries | marks |
|---|---|---|---|
| Mon | 52 min, 2.3 mi, 600 m new road | coverage +24 · walking day +15 | 39 |
| Tue | 49 min, 2.2 mi, 540 m new; passes this week's sea glass on Rose Loop (`road-rose-loop`, 47.61676, −122.52101) | coverage +21 · walking day +15 · sea glass +25 | 61 |
| Wed | — | — | 0 |
| Thu | 61 min, 2.9 mi, 720 m new; walks the whole attacked stretch of Manitou Beach Drive NE (1,158 m, tide below the threshold) | coverage +29 · walking day +15 · kraken repelled on Manitou Beach Drive NE, ≥ 95 % bucket +80 | 124 |
| Fri | 47 min, 2.0 mi, 480 m new; finishes Northeast Gertie Johnson Road and repels its stretch (337 m) | coverage +19 · walking day +15 · road complete +25 · kraken repelled on NE Gertie Johnson Road, ≥ 95 % bucket +80 | 139 |
| Sat | 78 min, 3.6 mi, 1,410 m new; first coverage in a new square; finishes 2 short roads | coverage +57 · walking day +15 · fog lifted +25 · 2 roads complete +50 | 147 |
| Sun 20:00 | Kraken retreats (islanders repelled both K5 stretches, each by ≥ 3 players) | kraken retreats +50 · island challenge "Kraken week" +75 | 125 |
| Mon 00:00 | Week kept (5 walking days), 4th in a row (logged on Sunday) | kept week 40 + 3 × 10 = +70 | 70 |
| **Week** | | | **705** |

Total 3,115 → rank up to **Madrona Wanderer** (3,000) on Sunday at the kraken's retreat (2,920 before it); the
rank-up line shows the next time she opens the app, after the sync that delivers the server-derived retreat marks.
Daily found cap never approached (max Thu: 80). Coverage after: 25,350 m (1,014 coverage marks). (v2 would add
"milestone 15 mi +50" on Saturday.)

**Marcus, trails app, first week with Play.** He turned Play on after last weekend's walk. Starting balance:
8,400 m covered → 840 coverage marks, 2 trails complete → 200, 6 squares → 150: **1,190** (one entry, "Your walks
so far") → Beachcomber.

| day | walk | logbook entries | marks |
|---|---|---|---|
| Sat | Grand Forest with kids, 3.1 km, 1.8 km new trail | coverage +180 · walking day +15 · sea glass on Grand Forest West +25 · riddle "Bought with a bond" ★★ with 1 hint: 80 × 0.75 = +60 · fog lifted +25 | 305 |
| Sun | Battle Point Park + Fairy Dell, 2.0 km, 1.2 km new; finishes Fairy Dell Trail, Battle Point Equestrial Trail and Battle Point Park Trail | coverage +120 · walking day +15 · 3 trails complete +300 · challenge "Weekend sweep" (3 trails Sat–Sun) +100 · egg "Fairy door" +20 · egg "Helix hum" +20 · fog lifted +25 | 600 |
| Mon 00:00 | Week kept (2 days), first (logged on Sunday) | kept week +40 | 40 |
| **Week** | | | **945** |

Total 2,135 → rank up to **Salal Scout** on Sunday (1,495 after Saturday). Found marks: Sat 85, Sun 140 (cap 400).
The celebration on Sunday (3 trails) carries two extra lines: "+300 marks" and "New rank: Salal Scout". (v2 would
add the barred owl, +15, and "new park Battle Point Park", +50.)

### 3.10 Sync of Play summaries

- Only when signed in and Play on. Rides the existing sync triggers (after each finished walk, foreground > 15
  min, sign-in). Offline → queued with coverage (same queue, same backoff).
- Claims ride the existing `PUT /v1/coverage` body as two optional fields (shape matches `SyncRepository`'s one
  `CoverageUpload(version, TICK_M, delta, walks)` that batches up to 200 walks across weeks):
  ```json
  "playConfig": "2026.11.0",
  "play": [ { "walkId": "<uuid in walks[]>",
              "claims": [ { "id": "treasure-weekly-roads@2026-10-26#07", "kind": "treasure", "weekId": "2026-10-26" },
                          { "id": "kraken-roads@2026-10-29#0", "kind": "ink", "weekId": "2026-10-26", "band": 95 } ],
              "dayKey": "2026-10-26",
              "hints": { "riddle-bond": 1 } } ]
  ```
  One element per walk that has Play content, each tied to a `walks[]` entry of the same upload. `kind` ∈ `treasure`,
  `egg`, `riddle`, `ink`, `lore`, `challenge`; `band` (60 / 80 / 95) only on `ink`. `dayKey` = the week id when this
  walk earned W-DAY, else absent (server: ≤ 7 per week id). `hints` = hints used per riddle item claimed on this walk.
  `playConfig` = the content-pack version the phone evaluated the claims against. No marks, no timestamps, no
  coordinates, no segment ids beyond what coverage already sends, no `what`/`why` text, and no days beyond the
  occurrence keys themselves: a claim `id` names its occurrence's day or window (`kraken-roads@2026-10-29#0`,
  `egg-ferry-rail@2026-01-05`) and `weekId` / `dayKey` name the week; never a time of day. How long the server keeps
  those place-and-week keys is OD-21 (PD-22).
- W-WEEK has no request of its own: the server derives it from `dayKey`s at week close.
- The coverage response gains `play: { accepted: [id], rejected: [{ id, code }], total, week }` only when the request
  carried `play`; the client marks rejected entries `local_only`. `GET /v1/play/me?dataset=` returns the summary
  used for restore (§ 3.6.5).
- Board consent (v2): `PUT /v1/play/board-consent { visible: bool }`.
- Account deletion deletes Play rows; "Also delete … on this phone" clears the local logbook too: both goals' Play
  state (Logbook, finds, solves, hints), both goals' claim queues and the walk-id map, whether Play is on or off
  (PD-22).
- On the phone, Play state lives in the no-backup directory (Android `noBackupFilesDir`, iOS excluded from backup)
  and is never in a device or cloud backup; Logbook entries name their walk by an opaque per-walk id, never the local
  session id (PD-22).
- Pure sync contract unchanged: the coverage body gains the optional `playConfig` and `play` fields, ignored by
  older servers; with Play off both are absent (byte-identical request) and the response has no `play` field.

---

## 4. Content types

### 4.0 Common model

#### 4.0.1 Content record (pack fields, product-level)
Product-level meaning only; the normative schema is `schedule.md` § 4.3, which encodes this section exactly.

| field | meaning |
|---|---|
| `id` | Immutable item id, e.g. `egg-ladybug`, `treasure-weekly-roads`, `kraken-roads`. A change to `when`/`where`/rewards is a new id plus `expires` on the old one. Claims carry occurrence keys (`kraken-roads@2026-10-29#0`). |
| `type` | `treasure`, `egg`, `obstacle`, `riddle`, `challenge`, `lore`, `creature` (v2), `ambient` |
| `goals` | `trails`, `roads` or both; per-goal anchor can differ |
| `anchor` | Point (`lat`, `lon`) and/or feature ids (`trail-*`, `road-*`, segment ids) |
| `radius_m` | Discovery radius (point items) |
| `walk_through` | Required stretch (feature ids + share) for stretch items |
| `active` | Schedule rule (`when` in schedule.md § 4.4) |
| `reward` | Marks (fixed, shown up front; the server reads them from its own copy of the pack) |
| `repeat` | `once`, `per_season`, `per_attack`, `weekly_set` |
| `tags` | `shore`, `park`, `quiet`, `history`, `creature`, `kids` |
| `text` | Title, teaser, found text, a11y text, hints (riddles), sources (lore) |
| `fx` | MapFx effect id, snake_case (effects.md § 3); the reduced variant is the effect's final frame (engine PF-2) |
| `enabled` | `false` = draft. The kill switch is the pack's one top-level `kill { all, types, items }` block (schedule.md § 4.2); there is no per-item `status: paused`. |

#### 4.0.2 Play-safe anchors
- **Trails goal:** points on included segments of named trails; trailheads from `trailheads.json`; road-end trails
  (`trail-woodland-drive-road-end`, `trail-wharf-street-road-end`) at their landward end.
- **Roads goal — the one `roadSafety` rule** (also schedule.md § 3.4 and effects): a counted road qualifies when
  `category ∈ {residential, collector, arterial}` (never `state_route`), `public: true`, `ownership` starts with
  `public_`, and `speed_mph ≤ 25`. A road with `speed_mph: null` qualifies only if it is `residential` (Washington's
  statutory default for city streets is 25 mph, RCW 46.61.400; **TBC** by the owner for the island). Run over
  `data/roads/dist/roads.json` on 2026-10-20: **370 of 430** counted roads qualify (332 residential, of which 198
  have no speed tag; 31 collectors; 7 arterials). Excluded: 24 at 30 mph (23 plus one state route), 21 at 35 mph,
  1 at 40 mph, 1 state route at 50 mph, 8 private, 3 unknown ownership, 2 collectors with no speed tag. Two
  qualifying roads touch the memorial quiet zone (Taylor Avenue NE, Welfare Avenue NE) and are never pool members.
  Computed at pack build; no count is hard-coded in code or tests.
- **Both:** no anchor within a quiet zone and no pool member (trail or road) touching one (§ 4.6.3; today the
  memorial zone rules out `trail-pritchard-trail`, Taylor Ave NE and Welfare Ave NE, the cemetery zone rules out
  `trail-blakely-harbor-trail`, `trail-blakely-trail` and `trail-blakely-hills-trail`); Pritchard Park is out of
  every pool; no anchor on a beach (shoreline distance < 10 m) — shore items
  sit at the road end or trail end above the beach; no anchor inside a fee or ticketed site (Bloedel Reserve) or a
  rental building (Prue's House cabin itself); no anchor at a private home (Station S building, Batteries Nash and
  Warner are on private property).
- Coverage marks still accrue on every counted road (pure progress is pure progress); Play just never *sends* you
  to a non-safe one.
- **Landmarks** (Frog Rock, the memorial, the helix house, the Lynwood marquee …) are not on a line. Their content is
  **snapped**: the trigger point is the nearest Play-safe point (trail or Play-safe road, in either goal) to the
  landmark, and the radius is measured from that snapped point. The card still shows the landmark's own
  coordinate. Example: Frog Rock (47.69612, −122.52347) snaps to `road-northeast-spargur-loop-road` (21 m away,
  25 mph), never to Phelps Road NE or North Madison Avenue NE (35 mph). Landmark content may snap to a Play-safe
  road in the trails app too (walks are recorded everywhere; only the anchor must be safe).
- Weekly goal content (treasures, kraken stretches, brambles, feature riddles) uses only the goal's own features.

#### 4.0.3 Discovery modes
| mode | how | claims marks? | used by |
|---|---|---|---|
| Proximity | GPS within radius while recording (§ 3.6) | yes | treasures, eggs, lore, riddle answers (place) |
| Walk-through | Cover a share of a stretch during one walk (gated track) | yes | kraken ink, brambles, riddle answers (trail/road) |
| Window | Something counted across walks in a time window | yes | challenges, kept week |
| Armchair tap | Tap a marker or a map feature at zoom ≥ 15 | no, stamp only | eggs in map art, riddle solving, lore cards |

### 4.1 Treasure

| aspect | rule |
|---|---|
| Definition | Small collectibles placed on the island each week, the same places for everyone. Items are made-up keepsakes, not history claims: **Sea glass** (common), **Agate** (uncommon), **Glass float** (rare), **Ship's bell** (legendary). |
| On the map | A glint marker on the trail/road line, visible to everyone at all zooms ≥ 13 (cluster count below 13). Collected ones show as a small open shell for the rest of the week. |
| Discovery | Proximity, radius **20 m** (trails) / **25 m** (roads); accuracy ≤ radius. v1 places no shore-tagged treasure; shore-tagged treasures with a low-water window are v2 (§ 8.2). |
| Resolution | Reach it during a walk → find moment ("Sea glass — found on this walk") → claimed at Finish. |
| Reward | 25 / 40 / 60 / 150. |
| Weekly set | Trails: 12 per week (7 sea glass, 3 agate, 1 glass float, 1 ship's bell every 4th week, else a 2nd float). Roads: 20 per week (12 / 5 / 2 / 1 ship's bell every 4th week, else 3rd float). Placed by the scheduler: at most 1 per chart square, at least 40 % in squares where few players have coverage (per-square player counts aggregated by the server and **baked into the pack at publish**, so offline phones draw the same set), at least 1 within 400 m of each of the 4 quadrants' main trailheads/road clusters, ≥ 300 m apart. |
| Failure / expiry | Uncollected treasures vanish at week end (Monday 00:00). No penalty; copy: "This week's treasures have washed away. New ones Monday." |
| Repeatability | Each treasure once. New set every Monday 05:00. |
| A11y / reduced motion | Marker has a text label for screen readers ("Sea glass, 25 marks, 240 m away"). Glint animation → static icon under reduced motion. Find moment announces `play_find_a11y`. |

Seed instances (permanent "island treasures" that return every 8 weeks as part of the weekly set; real anchors):
| id | item | goal | anchor | source of coordinate |
|---|---|---|---|---|
| `isl-tr-reitan` (v2) | Agate | roads | Reitan Road NE road end, 47.70878, −122.56393 (`road-reitan-road-northeast`; shore-tagged) | `road_ends.json` |
| `isl-tr-fairydell` | Glass float | trails | Fairy Dell Trail midpoint, 47.66908, −122.57829 (`trail-fairy-dell-trail`, above the beach) | `coverage_segments.json` |
| `isl-tr-blakely` (cut) | — | — | Blakely Harbor Trail passes within 100 m of the cemetery quiet zone. | — |
| `isl-tr-seaborn` | Sea glass | roads | Northeast Seaborn Road, 47.59780, −122.50722 (`road-northeast-seaborn-road`) | dataset |
| `isl-tr-monroe` (v2) | Ship's bell | roads | Point Monroe Drive NE, 47.70590, −122.50909 (`road-point-monroe-drive-northeast`; shore-tagged) | dataset |
| `isl-tr-gfwest` | Sea glass | trails | Grand Forest West, 47.65472, −122.55968 (`trail-grand-forest-west`) | dataset |

### 4.2 Easter egg

| aspect | rule |
|---|---|
| Definition | Permanent hidden surprises tied to a real spot or a joke about it. Not shown on the map until found. Some are hinted by riddles or lore cards. |
| On the map | Nothing until found; afterwards a small stamp icon. Some eggs also live in the map art (tap the ferry, tap Frog Rock's icon at zoom ≥ 16) — those are armchair eggs (stamp only). |
| Discovery | Proximity, radius **15 m**, accuracy ≤ 15 m (a fix this good is common in open places; eggs are placed in the open). An egg the owner flags `canopy: true` after an on-site check (forest cover, e.g. Fairy Dell) uses 25 m radius and accuracy instead, because canopy rarely gives a 15 m fix. |
| Resolution | Find moment with a one-line surprise text and a small MapFx flourish. |
| Reward | 20 marks, first find. Armchair eggs: stamp only. |
| Failure / expiry | None; permanent unless the owner pauses it. |
| Repeatability | Once per egg ever. The Logbook shows "Easter eggs: 7 of 40 found" (count only, never a list of the unfound). |
| A11y / reduced motion | Find text is the whole content (no visual-only joke). Flourish → static stamp. |

Seed instances:
| id | name | goal | anchor | text gist | source |
|---|---|---|---|---|---|
| `egg-ladybug` | Frog Rock's ladybug | both | 47.69612, −122.52347 (Frog Rock; nearest Play-safe road `road-northeast-spargur-loop-road`, 21 m) | The year after the frog was painted, its painters also painted the small rock beside it as a ladybug. | Wikipedia (coords), Atlas Obscura |
| `egg-fairydoor` | Fairy door | trails | 47.66908, −122.57829 (`trail-fairy-dell-trail`; under canopy → `canopy: true`, 25 m; spot **TBC** on site) | "A door too small for you. Knock anyway." Labelled as made-up. | invented |
| `egg-helix` | Helix hum | trails | 47.66609, −122.57720 (`trail-battle-point`; observatory exact point **TBC**) | The round concrete helix house once matched the Navy's transmitters to their antennas; now it watches stars. | navy-radio history, Bainbridge Review |
| `egg-marquee` | The 1936 marquee | roads | 47.60444, −122.54750 (Lynwood Theatre; Play-safe road `road-pleasant-beach-drive-northeast`, 25 mph) | The Lynwood Theatre opened July 3, 1936 ("the island's first sound-film cinema" is **TBC**: single source). | Wikipedia |
| `egg-madrone` | Madrone | roads | 47.62475, −122.50810 (`road-winslow-way-east`, 25 mph) | Before the shipyard came in 1902–03, this town was called Madrone. | HistoryLink |
| `egg-stationS` | Station S static | trails | 47.58971, −122.52871 (`trail-fort-ward-trail-fort-ward-park`; placed in the park, not at the private former Station S building) | In 1939 the Navy moved a listening post here; "Station S" intercepted Japanese naval traffic. | Wikipedia, HistoryLink |

### 4.3 Obstacles

Obstacles are per-user overlays that a walk clears. They never block pure progress, never change status colors,
and never stop a walk from counting.

#### 4.3.1 Fog (exploration)
| aspect | rule |
|---|---|
| Definition | Soft exploration fog over every chart square where you have no counted coverage yet. Permanent until lifted. Never drawn inside a quiet zone. |
| On the map | Fog layer drawn above the basemap and below status lines (lines stay readable through fog; effects.md § 3.18, engine band UNDER_STATUS). |
| Discovery / resolution | Walk-through: first new counted coverage in the square (claimed at Finish). Live: the fog thins as you enter (provisional). |
| Reward | W-SQUARE +25. |
| Failure / expiry | None. |
| Repeatability | Once per square. |
| A11y / reduced motion | Lifting animation → instant swap. Screen reader: "Fog lifted over a new square. 23 of 83 squares explored." |

#### 4.3.2 The kraken (weekly shore obstacle)
The kraken is an **island tall tale** (labelled as such), inspired by the real giant Pacific octopus, the world's
largest octopus, common in Puget Sound. It is mischievous, not scary: it "inks" shore stretches and sulks back to
the deep when islanders walk them.

| aspect | rule |
|---|---|
| Definition | Each week the kraken attacks one coast: 2–6 Play-safe shore stretches (roads) or 2–3 shore trails (trails). A **stretch** = the part of a road/trail within 60 m of the shoreline (computed at pack build from the coastline). Only stretches ≥ 100 m qualify. |
| When | Attack window **Thursday 06:00 → Sunday 20:00** island time, every week, **clock-based** (no tide anchor; covers a weekday for Dana and a weekend for Marcus). Within the window a stretch is only *active* (sensable, claimable) in daylight (civil dawn → civil dusk) and while the predicted tide at Eagle Harbor (NOAA 9445882) is at or below **8 ft (2.44 m) MLLW** (`rules.krakenMaxTideM`, § 8.2 T-2); above it the sheet says the kraken is waiting for the tide to drop. A missing or expired tide table keeps every stretch inactive (fail closed). The attack itself (map visuals) shows all window long. There is no king-tide kraken (cut). |
| On the map | Ink along the attacked stretch (beside the line, never recoloring it), tentacles at the water's edge, a coast label "The kraken is at Manitou". Per user: your ink clears on stretches you repelled. A small island progress bar on the attack sheet: "3 of 4 stretches repelled by islanders". |
| Discovery | Visible to everyone on the map when Play is on; no proximity needed to see it. |
| Resolution | Walk-through: walk ≥ **60 %** of an attacked stretch's length during one walk (gated track, repeat metres count). |
| Reward | F-INK: 60 / 70 / 80 for ≥ 60 / ≥ 80 / ≥ 95 % of the stretch walked in one walk (§ 3.3). F-RETREAT: if, by Sunday 20:00, every stretch was repelled by ≥ 3 distinct players (server aggregate, roads and trails counted separately), everyone who repelled ≥ 1 stretch gets +50 and sees the kraken sink back. |
| Failure | If not all repelled: the kraken "sulks back to the deep" at Sunday 20:00. No penalty; repelled stretches still paid. Copy: "The kraken slipped away. It'll be back next week." |
| Repeatability | Weekly; coasts rotate (below). Each stretch pays once per attack. |
| Fairness | Every coast appears once every 6 weeks (roads) / 4 weeks (trails), so nobody's side of the island is favoured. Stretches are `roadSafety` roads only (≤ 25 mph). Minimum 3 players per stretch for the community result: small enough to be achievable on a quiet island; the pack can lower it (owner). |
| A11y / reduced motion | Tentacle and ink animation → static ink pattern + label. The attack sheet lists stretches as text with "repelled by you" state. Announcement on find (`play_find_a11y` form): "Kraken repelled on Manitou Beach Drive Northeast. 80 marks when you finish." |

Coasts, roads goal (stretch shore length in brackets, computed from `coastline.json` at 60 m; every road passes
`roadSafety`, recomputed 2026-10-20; lengths are recomputed at pack build):
| coast | stretches (road id, midpoint of shore stretch) |
|---|---|
| K1 (merged into K4) | Country Club Road NE and Fort Ward Hill Road NE are 30 mph and fail `roadSafety`; South Beach Drive moved to K4. |
| K2 Rich Passage west / Point White | `road-crystal-springs-drive-northeast` (2,193 m) 47.59790, −122.57543 · `road-point-white-drive-northeast` (1,126 m) 47.59608, −122.56062 · `road-pleasant-beach-drive-northeast` (461 m) 47.60156, −122.54637 |
| K3 Eagle Harbor south & west | `road-shannon-drive-1` (206 m) 47.62282, −122.51913 · `road-lovell-avenue-southwest-2` (146 m) 47.62220, −122.52616 · `road-rose-loop` (110 m) 47.61676, −122.52101 |
| K4 South & east shore | `road-northeast-south-beach-drive-1` (2,267 m) 47.57458, −122.50948 · `road-rockaway-beach-road-northeast` (570 m) 47.61226, −122.49913 · `road-northeast-seaborn-road` (629 m) 47.59780, −122.50722 · `road-three-t-road-northeast` (160 m) 47.59712, −122.51338 |
| K5 Manitou / Murden Cove | `road-manitou-beach-drive-northeast` (1,158 m) 47.65637, −122.51034 · `road-northeast-gertie-johnson-road` (337 m) 47.66593, −122.50630 (the other Murden Cove shore stretches are under 100 m today, so K5 has two) |
| K6 North end / Port Madison / Agate Pass | `road-point-monroe-drive-northeast` (582 m) 47.70590, −122.50909 · `road-reitan-road-northeast` (316 m) 47.71147, −122.56406 · `road-skogen-lane-northeast` (145 m) 47.69886, −122.53724 |
| K7 West shore | `road-olympus-beach-road-northeast` (148 m) 47.64593, −122.58065 · `road-northeast-monte-vista-drive` (274 m) 47.66907, −122.56450 (Manzanita Road NE is 30 mph: out) |

Excluded on purpose: NE Eagle Harbor Drive, Euclid Avenue NE, Blakely Avenue NE (35 mph); Country Club Road NE,
Fort Ward Hill Road NE, Manzanita Road NE (30 mph); State Highway 305;
every private shore road; Creosote Place NE and the Pritchard Park shore (remembrance quiet zone and Superfund
cleanup site — no jokes there).

Coasts, trails goal:
| coast | stretches (trail id, closest shore point) |
|---|---|
| T1 (cut) | Blakely Harbor Trail and Blakely Trail pass within 100 m of the cemetery quiet zone. |
| T2 Fort Ward / Rich Passage | `trail-fort-ward-trail-fort-ward-park` 47.58004, −122.52578 · `trail-lower-fort-ward-trail` 47.58745, −122.53073 |
| T3 Winslow waterfront | `trail-west-loop-trail` 47.62221, −122.52057 · `trail-west-loop-trail-3` 47.62504, −122.52908 · `trail-waypoint-woods-trail` 47.62406, −122.51476 |
| T4 West shore | `trail-fairy-dell-trail` 47.67125, −122.57803 · `trail-woodland-drive-road-end` 47.67380, −122.56112 · `trail-schel-chelb-trail` 47.60424, −122.55081 |
| T5 East & north | `trail-hawley-cove-trail` 47.62651, −122.50318 · `trail-tchookwop-park-trail` 47.69702, −122.53172 |
Excluded: `trail-pritchard-trail` and `trail-bainbridge-island-japanese-american-memorial-trail` (Pritchard Park,
memorial quiet zone), `trail-blakley-cemetary-trail`, `trail-blakely-harbor-trail`, `trail-blakely-trail`
(cemetery quiet zone).

Rotation (schedule.md § 4.7 `rotate`, epoch = the first attack's Thursday): roads K5, K2, K6, K4, K7, K3; trails T4,
T2, T3, T5. First attacks after launch: K5 / T4, K2 / T2, K6 / T3, K4 / T5, K7 / T4, K3 / T2. Occurrence keys are
`kraken-roads@<Thursday date>#<stretch index>` and `kraken-trails@…`. (v2) The October theme's second, smaller
attack in the week of Oct 31 is an event window; it yields to the weekly attack and never overlaps it
(`heroMaxConcurrent 1`).

#### 4.3.3 Bramble (trails, August) (v2)
| aspect | rule |
|---|---|
| Definition | A made-up blackberry bramble drawn across a trail (labelled "a story bramble — the real trail is open"). |
| On the map | Thorny overlay on 5 trails per week in August. |
| Resolution | Walk-through ≥ 80 % of the trail in one walk. |
| Reward | 40. |
| Failure / expiry | Gone at week end (Monday 00:00). |
| Repeatability | Once per bramble per season. |
| A11y | Static pattern; text says clearly it's a story, the trail is open. |
Seeds (Aug 2027 week 1): `trail-hidden-cove-trails` (47.68901, −122.53881), `trail-meigs-trail` (47.65709,
−122.52501), `trail-old-mill-trail` (47.61034, −122.53624), `trail-close-trail` (47.62633, −122.57482),
`trail-manzanita-trail` (47.68539, −122.54965).

### 4.4 Puzzles and riddles

| aspect | rule |
|---|---|
| Definition | A short rhyme or clue whose answer is a place on the island. Answer kinds: **place** (walk within 30 m of a point) and **feature** (walk 100 m of a named trail/road) in v1; **count on site** (a number visible only there; answered in-app while within 30 m) is v2. History riddles are only about public, non-painful facts; no riddles about the Exclusion, the Suquamish, cemeteries or the Superfund site. |
| On the map | A "?" marker placed in the right chart square but not on the answer (offset 300–800 m, deterministic), so the map hints at the area. ★ difficulty shown. |
| Discovery | Always visible when active; tap to read. |
| Resolution | 1) **Solve** (armchair): pick from the map (tap the answer feature) or type a count → stamp "Solved — walk there to claim". Wrong answers: unlimited tries, no penalty, gentle copy. 2) **Claim**: reach the answer on a walk (place/feature rules) → claimed at Finish. Walking there without solving also claims (lucky explorers win). Count riddles must be answered while within 30 m. |
| Hints | Up to 2 per riddle, each −25 % of the reward (floor 50 %). |
| Reward | ★ 40 · ★★ 80 · ★★★ 120. |
| Failure / expiry | Permanent riddles (seeds below, v1): always active. Weekly riddles (v2): 2 per week, active Monday 05:00 → next Monday 00:00. Both are solved from home (stamp) and claimed by walking. No failure state. |
| Repeatability | Once per riddle. |
| A11y | Answer picker has a searchable list alternative to tapping the map (screen readers can't aim at a map). Count riddles accept digits and words ("seven"). |

Seed instances (all facts sourced; all answers Play-safe):
| id | ★ | clue | answer (kind, anchor) | source |
|---|---|---|---|---|
| `rd-frog` | ★ | "I sit by the road but never hop. Two islanders painted me in 1971. Find me on the north end, by a quiet loop road." | Frog Rock (place; landmark 47.69612, −122.52347, snapped to `road-northeast-spargur-loop-road`, 25 mph; trails players reach it from `trail-port-madison-water-company-trail-2`, 235 m away) | Wikipedia; Atlas Obscura (June 6 vs 7 1971 discrepancy noted in lore text) |
| `rd-madrone` | ★★ | "A shipyard came and the town took a brother's name. What was I called before?" Answer typed or picked: Madrone → claim by walking 100 m of `road-winslow-way-east` (roads) or `trail-west-loop-trail` (trails). | feature | HistoryLink 8274 |
| `rd-fairy` | ★ | "Near the park that's named for a battle, a small dell where tiny folk might settle." | `trail-fairy-dell-trail` (feature) | dataset |
| `rd-agate` (v2, shore-tagged) | ★★ | "A painter sailed with Wilkes in 1841; a channel took his name. A bridge has crossed it since 1950." | Agate Pass → claim at `road-reitan-road-northeast` road end, 30 m (place, 47.70878, −122.56393; shore-tagged) | Wikipedia (Agate Pass), HistoryLink 7136 |
| `rd-bond` | ★★ | "In 1991 islanders voted a bond to save a forest from sale. Walk a hundred metres of it." | Any Grand Forest trail (feature: `trail-grand-forest-west`, `trail-main-loop-trail`, `trail-hilltop-trail`, …) | Bainbridge Island Land Trust |
| `rd-monroe` | ★★★ | "Wilkes named a port for one president and a point for the next. Walk the road along the point's spit." | `road-point-monroe-drive-northeast` (feature, roads); trails: claim at Fay Bainbridge Park entrance is not a trail → trails version answers "Point Monroe" as a stamp-only armchair riddle | Wikipedia (Bainbridge Island), coastview.org |
| `rd-count-template` (v2) | ★★★ | Template only: "Count the … at …" | Owner writes and verifies on site before publishing; pack validation refuses a count riddle without `verified_on_site: <date>` | — |

### 4.5 Challenges

Type `challenge` everywhere: content pack, tables, routes, copy. Nothing is timed. A challenge shows its window and
your progress, and finishes with a stamp; there is no countdown, ghost, finish time or ranked standing.

| aspect | rule |
|---|---|
| Definition | A goal inside a time window. **Solo**: you against your own target. **Island**: everyone together against a shared target. |
| On the map | A flag chip on the Map header ("2 challenges this week") → Challenges list. Challenges tied to an area also show a dashed outline of the area. |
| Discovery | Listed when active; join is implicit (anyone with Play on is in). |
| Resolution | Counted from claimed walk results inside the window (window-boxed by island time; a walk counts if it **finished** inside the window). |
| Reward | Solo 50 / 100 / 150; island success 75 each; first wave +25 (v2). |
| Failure / expiry | Window ends → "Challenge ended" with what you did; no penalty. |
| Repeatability | Each challenge instance once; templates recur on the schedule. |
| Fairness | Targets are **caps**, not races: everyone who reaches a target ties at the top. Solo targets can be **personal**: based on your own median new metres per walk over your last 8 walks (min 300 m trails / 500 m roads, max 2× the goal default), so a parent with kids and a runner face the same difficulty. Community results are `{band, count}` only (e.g. "You were in the top band, with 12 others"), never a ranked list of people or names. No challenge measures elapsed time between two points. |
| A11y | Progress as text ("2 of 3 trails"), not only a ring. |

Seed instances:
| id | kind | window | target | reward | notes |
|---|---|---|---|---|---|
| `ch-sweep` | solo | Sat 00:00 → Mon 00:00, weekly | Trails: finish 3 trails. Roads: finish 5 roads. | 100 | Short trails/roads make it family-sized (113 counted roads under 0.1 mi). |
| `ch-climb` (v1.1 trails) | solo | Mon 00:00 → Mon 00:00, weekly | Finish non-quiet trails that climb ≥ 150 m between them (metric `climb`, Σ `gain_m` of trails newly completed in the window; progress and target carried in decimetres) | 100 | "Uphill week". Never timed. Target = min(150 m, what remains for this player); nothing left → card hidden. Progress as text ("95 of 150 m climbed" / "311 of 492 ft climbed"). PD-26. |
| `ch-parkhop` (v1.1 trails) | solo | Sat 00:00 → Mon 00:00, weekly | Finish a trail in ≥ 3 distinct Play parks (metric `parks`; each park counts once; Pritchard Park and quiet trails never) | 100 | "Park hop". Never timed. Target = min(3, parks with an uncompleted trail left); nothing left → card hidden. Progress as text ("2 of 3 parks"). PD-26. |
| `ch-crossing` (v2) | solo | Any 35 min starting at a scheduled departure from Bainbridge terminal (pack timetable), daylight | New coverage ≥ 300 m (trails) / 800 m (roads) within one crossing time | 50 | "Walk while the boat crosses." A walk qualifies if ≥ the target of its new coverage was matched within that 35 min (from the walk's own local timeline; never uploaded). Copy never says beat or race the boat. |
| `ch-foglifter` (v2) | solo | Calendar month | Lift fog over 3 new squares | 150 | Monthly. If fewer than 3 fogged squares remain, target = remaining. |
| `ch-krakenweek` | island | Kraken window | Every attacked stretch repelled by ≥ 3 players | 75 + F-RETREAT | Same mechanic as § 4.3.2, framed as a challenge. |
| `ch-strawberry` (v2) | island | June 1 → June 30 | Islanders together walk 500 km (roads) / 200 km (trails) of new coverage (sum of accepted W-COV metres server-side) | 75 each to contributors with ≥ 1 km new in June | Paired with the strawberry history lore card (remembrance-adjacent: respectful text, § 4.6). |
| `ch-bestweek` (v2) | solo | Weekly | More new metres than your best week of the last 4 | 50 | Personal. Hidden for your first 4 weeks. |

"First to …" (owner request): delivered as **island firsts**, not people firsts — "Will islanders be first to clear
the coast before Sunday?" — plus (v2) **first wave** (+25 for anyone contributing in the first 48 h). Named
first-finders are not shown (privacy: it would publish that a named person was at a place recently). Owner
decision OD-8.

### 4.6 Lore encounters

| aspect | rule |
|---|---|
| Definition | A character, creature or piece of island history that appears at a real place. Each card: title, 2–4 sentences, "Myth", "Tall tale" or "History" tag, sources (tap to expand), safety line where relevant. |
| On the map | Small illustrated marker at zoom ≥ 14. Ambient creatures have no fixed marker (they appear on a walk in their habitat). |
| Discovery | Proximity, radius **60 m** (accuracy ≤ 30 m) while recording; or armchair tap (card readable anytime; stamp only). |
| Resolution | Encounter moment (character animation, one line) → card → logbook. |
| Reward | First encounter 15; (v2) creatures 15 first per season; (v2) ferry horn 10 (max 2/day). Remembrance: 0, no number shown. |
| Failure / expiry | None; seasonal ones simply aren't active out of season. |
| Repeatability | Card always re-readable in "Island stories" (the collection). |
| A11y / reduced motion | Character animation → static illustration + 150 ms crossfade. Card is text-first. Sounds (ferry horn) off by default; never the only cue. |

#### 4.6.1 Catalogue (anchors, triggers, facts)
| id | encounter | tag | goal(s) | anchor | trigger / schedule | card facts (sourced) |
|---|---|---|---|---|---|---|
| `lore-kraken` | The kraken | Tall tale | both | Its weekly coast (§ 4.3.2) | Kraken window | Made up for this app. Inspired by the giant Pacific octopus, the largest octopus species, abundant in Puget Sound; real ones live 3–5 years. Not affiliated with any team. |
| `lore-frog` | Frog Rock | History + local custom | both | 47.69612, −122.52347 (landmark; trigger snapped to `road-northeast-spargur-loop-road`, § 4.0.2) | Daylight, always; (v2) "Frog Rock's birthday" bonus card June 6–7 (date **TBC**: sources give June 6 and June 7; the "3rd Saturday" rule is dropped) | Two stacked granite boulders, split by blasting decades earlier (accounts differ: 1940s barn or road right-of-way), painted as a frog by two residents in June 1971 (sources give June 6 and June 7). Reported added to the Washington State Heritage Register in Sept 2026 (single source; **TBC** before publishing). |
| `lore-ferry` | The ferry | History + everyday | both | Winslow waterfront zone: 800 m around the Bainbridge terminal reference point **47.62371, −122.51283** (landward end of `road-bainbridge-island-ferry-terminal`, not counted; the one terminal coordinate in every Play doc). Anchors: `road-brien-drive-southeast` 47.62319, −122.51700; `trail-west-loop-trail` 47.62219, −122.52097 | v1: the ferry sprite crosses Eagle Harbor on the timetable all day (visual only) and the card is readable (F-LORE at the anchors in daylight). (v2) At each scheduled Bainbridge departure, daylight: horn encounter if recording in the zone (F-HORN). | Seattle–Bainbridge is about a 35-minute crossing. Jumbo Mark II ferries (Tacoma, Wenatchee, Puyallup) serve the route. Under the US inland rules a power-driven vessel leaving a dock sounds one prolonged blast. |
| `lore-prue` (v2) | Prue's House | History | trails (roads: Mandus Olson Road NE) | Hilltop: anchor at Grand Forest trailhead `th-329893` 47.65535, −122.54784 (Hilltop Trail) and `trail-hilltop-loop-trail` 47.65434, −122.55058; roads: `road-mandus-olson-road-northeast-2` 47.65958, −122.54783 (public parks road, 25 mph). Cabin coordinate **TBC**; no trigger at the cabin door (rental venue). | Daylight; spring card "Hilltop meadow" Apr–May | A restored vintage Panabode cabin at Hilltop, 9600 Mandus Olson Rd, once owned by islander Prue Trudgian; Hilltop's 36 acres were protected in 2011 by the Land Trust, the park district and donors; the cabin is now a park district gathering place next to the Grand Forest trails. **Owner to confirm this is the "Prue's house" meant (OD-1).** |
| `lore-owl` (v2) | Barred owl | Creature | trails | Habitat: Grand Forest, Gazzam Lake, Meigs Park trails | Ambient, creature hours; first per season | Barred owls live in island woods; in 2017 park staff warned that owls guarding young had swooped at walkers near Gazzam Lake and the Grand Forest. Safety line: "Give owls space, especially in spring." |
| `lore-pileated` (v2) | Pileated woodpecker | Creature | trails | Habitat: mature forest trails (Grand Forest, Gazzam, Hidden Cove) | Ambient, creature hours; first per season | North America's second-largest woodpecker, usually found in mature forest. Local presence: **TBC** with a naturalist/eBird before publishing. |
| `lore-coyote` (v2) | Coyote | Creature | both | Habitat: any square with park land | Ambient, creature hours only; first per season | Coyotes live on the island; the Land Trust's wildlife cameras have recorded them and volunteers monitor them with the Seattle Urban Carnivore Project. Safety line: "Keep dogs leashed. Never feed wildlife." |
| `lore-newt` (v2) | Rough-skinned newt | Creature | trails | Habitat: wet trails (Gazzam Lake, Grand Forest, Nute's Pond) | Ambient, Oct–Apr, creature hours | Card focuses on "look, don't touch: their skin is toxic." Local presence **TBC** with a naturalist. |
| `lore-mosskeeper` (v2) | The Moss Keeper | Tall tale (made up) | trails | Grand Forest, `trail-main-loop-trail` 47.65395, −122.54681 | Daylight; misty-month special (Nov–Feb) | An invented, gentle forest keeper who tidies moss. Clearly labelled made-up. Replaces the requested "Grand Forest troll": no local troll legend found; the island's real troll, *Pia the Peacekeeper* by Thomas Dambo at Sakai Park, is a copyrighted artwork and is **not** depicted or referenced in Play (OD-6). |
| `lore-helix` | Battle Point helix house | History | both | `trail-battle-point` 47.66609, −122.57720 (snapped landmark; observatory point **TBC**, address 10800 Battle Point Dr NE) | Daylight | Navy radio transmitting station from the early 1940s (land acquired 1941) with an 800 ft tower beside the helix house, which matched transmitters to antennas; deactivated 1959; volunteers saved the helix house in 1992 and built an observatory on it (renamed Battle Point Observatory in 2025). |
| `lore-fortward` | Fort Ward batteries | History | trails | `trail-fort-ward-trail-fort-ward-park` 47.58971, −122.52871; `trail-fort-ward-fence-line-trail` 47.58869, −122.52678 | Daylight; September theme | Coast-artillery post from 1903: Batteries Thornburgh and Vinton (3-inch guns, 1903–1920); Batteries Nash and Warner now stand on private property (not mapped). From 1939 the Navy's "Station S" listening post intercepted Japanese naval radio here; the Army left in 1958; the park passed to the park district in 2011. |
| `lore-mill` | Port Blakely mill | History | both | Blakely Harbor Park trailhead `th-fa3d07` 47.59535, −122.51975; roads: `road-blakely-hill-road-northeast` 47.59845, −122.51383 (25 mph) | Daylight; November theme | Captain William Renton's mill opened in 1864; by 1882 it was the largest on the Pacific Coast and was billed as the world's largest; it burned in 1888 and 1907 and closed in the early 1920s (sources say 1922 or 1923). Today: a few pilings, a crumbling concrete building, a mill pond. |
| `lore-yama` | Yama | History (quiet) | both | **Not marked on the map.** Card anchored to Blakely Harbor Park (`th-fa3d07`). | Daylight | A village of Japanese immigrant families who worked at the Port Blakely mill, on mill land above the harbor; listed on the National Register; an archaeology field school has studied it. The site is protected; Play never points to it (looting risk and respect). Stamp only, no marks. |
| `lore-mosquito` (v2) | The Mosquito Fleet | History | roads (+ trails at `trail-woodland-drive-road-end`) | Former landing at Dock Street road end 47.67677, −122.56159 (`road-dock-street`, shore-tagged); NE Fletcher Landing road end 47.64235, −122.57828 (`road-northeast-fletcher-landing`, landing status **TBC**) | Daylight + low-water window; May theme | Small privately owned steamers once linked Puget Sound towns; Bainbridge had over 30 landings; some road ends are former landings. |
| `lore-memorial` | Japanese American Exclusion Memorial | **Remembrance** | both | `trail-bainbridge-island-japanese-american-memorial-trail` 47.61560, −122.51019 | Always readable; no effects (§ 4.6.3) | On March 30, 1942, 227 Japanese American islanders were forced from their homes and sent from the Eagledale ferry dock, the first removal under Executive Order 9066. The memorial wall names all 276 islanders of Japanese descent who were excluded. Its words: *Nidoto Nai Yoni* — "Let it not happen again." |
| `lore-strawberry` | Strawberry fields | History (respectful) | both | Strawberry Hill Park road `road-strawberry-hill-park` 47.63854, −122.54879 (public parks road) | June theme | Japanese American families were known for Bainbridge strawberries; Filipino immigrants came from the late 1920s to work the farms; during the war many Japanese American farmers left their farms in the care of Filipino American farmhands; the Strawberry Festival dates to 1948 (**TBC**: sources conflict, one says 1950), and the community hall near Strawberry Hill was built in 1928. No marks for reading; F-LORE only for the place visit. |
| `lore-agate` (v2) | Agate Pass | History | roads | `road-reitan-road-northeast` road end 47.70878, −122.56393 | Daylight; Oct 7 bridge anniversary card | Named by Lt. Charles Wilkes's 1841 expedition after its artist, Alfred Agate; the bridge opened Oct 7, 1950, and was a toll bridge until 1951. Bridge itself (SR 305) is never an anchor. |
| `lore-monroe` (v2) | Point Monroe spit | History + nature | roads | `road-point-monroe-drive-northeast` 47.70590, −122.50909 (shore-tagged) | Daylight + low-water window | Wilkes named Point Monroe and Port Madison for Presidents Monroe and Madison. The spit (Hedley Spit) is low sand, about 3.5 ft above sea level, built from sediment drifting north from eroding bluffs. |
| `lore-fay` | Fay Bainbridge Park bell | History | roads | **Armchair only**: the park entrance road `road-sunrise-drive-northeast-1` is 30 mph and fails `roadSafety`, so the card has no proximity trigger until the owner picks a Play-safe anchor (**TBC**). Park coordinate 47.70167, −122.50750 | Readable anytime | The state bought the land in 1944 from the estate of Temple S. Fay, keeping the Fay name. The bell's provenance (San Francisco around 1883 with Capt. Jeremiah Farnham; Port Madison's town crier; moved to the park in 1953) is **TBC**: single source. |
| `lore-eagleharbor` | Eagle Harbor & Winslow | History | both | `road-winslow-way-east` 47.62475, −122.50810; `trail-west-loop-trail` | Daylight | The Hall Brothers shipyard moved here from Port Blakely in 1902–03; the town of Madrone became Winslow, after Winslow Hall. |
| `lore-lynwood` | Lynwood Center | History | roads | 47.60444, −122.54750 via `road-pleasant-beach-drive-northeast` | Daylight | Edna and Emmanuel Olson built Lynwood Center; its theatre opened July 3, 1936 ("first sound-film cinema" **TBC**). |
| `lore-haleets` | Haleets | **Cultural site — excluded pending consultation** | — | Not placed (site 47.71864, −122.54447 is on the beach, ~30 m offshore) | — | A Suquamish petroglyph boulder (also called x̌alilc / Figurehead Rock). Default: **not in Play** until the owner consults the Suquamish Tribe (OD-5). If included later: remembrance-style card, no marks, no map pin. |
| `lore-bloedel` | Bloedel Reserve | Place card | both | Armchair only; card anchored on `road-agate-point-road-northeast` (35 mph → not Play-safe, so card has no proximity trigger) | Readable anytime | A public garden reserve at 7571 NE Dolphin Drive with timed tickets. No content inside; Play never sends anyone to its gate. |
| `lore-commuter` (v2) | Walk-on | Everyday culture | both | Winslow waterfront zone | Weekday mornings 06:00–09:00 and evenings 16:00–19:00, daylight | A light card about walk-on passengers and the ferry rhythm of the island; ties to the "Walk-on" rank. No tracking of commutes beyond the normal ferry horn rule. |
| `lore-pegasus` | Pegasus Coffee (brick building) | **Excluded by default** | — | — | — | The 1937 building on Parfitt Way is said to be built from Port Blakely mill chimney bricks (business's own site + Bainbridge Review). Default: no named businesses in Play (OD-7). |

#### 4.6.2 Ferry horn rule (exact) (v2)
- Zone: 800 m radius around 47.62371, −122.51283, minus the terminal footprint (state route; not counted; Play
  never asks anyone onto the ferry approach).
- Trigger: a pack-timetable departure time from Bainbridge passes while you are recording inside the zone, in
  daylight, with the same plausibility rules as finds.
- Effect: horn moment (visual ripple across Eagle Harbor; sound only if "Play sounds" is on; haptic tick).
- Reward: F-HORN 10, max 2 per day. First ever: also F-LORE 15 for the ferry card.
- Accuracy note in card: "Times come from the published timetable; the real boat may be late."

#### 4.6.3 Remembrance rules
Applies to `lore-memorial`, `lore-yama`, the cemetery (`trail-blakley-cemetary-trail`, Port Blakely Cemetery
area) and any place the owner tags `quiet`.
- **Quiet zones come from dataset geometry**, built at pack build (never a hand-typed circle): a 200 m buffer
  around every segment of `trail-bainbridge-island-japanese-american-memorial-trail` (7 segments, 47.6146–47.6157 N,
  122.5103–122.5097 W) and a 100 m buffer around every segment of `trail-blakley-cemetary-trail` (3 segments,
  47.5975–47.6011 N, 122.5193–122.5187 W), plus owner-tagged places. The earlier hand-placed circle at 47.6098,
  −122.5076 was ≈ 0.6 km from the memorial trail and is gone.
- **Quiet features:** the memorial trail, the cemetery trail and every trail of **Pritchard Park** (memorial and
  Superfund site). Pritchard Park is out of every pool and is never a Play park. Coverage on quiet features earns
  no W-* mark (§ 3.2); pure coverage and island % are unchanged.
- The validator rejects any pool member (trail or road) that touches a quiet zone and any anchor inside one.
- Inside a quiet zone: no Play markers except the remembrance card marker, no exploration fog, no animations, no
  sounds, no haptics, no find moments, no kraken, no treasures, no challenges counting.
- The card is reachable from the map and from Island stories; visiting on a walk adds a logbook stamp with no
  number ("Visited the Exclusion Memorial").
- No marks, no ranks, no trivia, no riddles, no challenges about these histories. Ever.
- **March 30** (anniversary of the 1942 removal): island-wide quiet day. No kraken, no treasure drops, no
  challenges, no sounds; the Map shows one quiet banner linking to the memorial card. Walk-derived marks still
  accrue silently (honest numbers). Owner decision OD-4.

---

## 5. Opt-in and off

### 5.1 Settings toggle
- New Settings section **Play** (between Units and Account).
- Rows: `play_settings_toggle` (switch) with body `play_settings_toggle_body`; when on, also
  `play_settings_sounds` (switch, default off), (v2) `play_settings_board` (switch "Show me on the Play board",
  default off; signed-out → row explains sign-in), `play_settings_ranks` (→ Ranks), `play_settings_logbook` (→ Logbook),
  `play_settings_about` (→ "How Play works" sheet with the § 3 rules in plain words).
- Toggling on → PF1 (intro sheet, then starting balance). Toggling off → PF13 (confirm sheet, nothing deleted).

### 5.2 One-time offer
- When: after the **results sheet of the first finished walk closes** (not over a celebration, not while
  recording, not on app launch). For existing users: after the first finished walk on the first version with
  Play.
- Shown once per install ever. Dismiss or "Not now" = never again (Settings remains). Accept → PF1.
- Not shown if Play was ever toggled in Settings, if the walk was discarded, or if the walk was shorter than 1 min.
- Content: illustration (island with a few glints, a tentacle at the shore), `play_offer_title`, `play_offer_body`,
  primary `play_offer_accept`, text `common_not_now`.

### 5.3 What changes on each tab (Play on)
| surface | change | pure content position |
|---|---|---|
| Map | Play layers (fog, glints, ink, markers, ferry sprite); Play chip top-left (`play_map_chip`: marks · rank); Challenges chip; legend gains a "Play" group (separate from statuses). Play markers never sit on top of the selected-trail halo; tapping a line still opens the pure preview first: Play takes a tap only when the `TappableTrailLayerIds` query has no hit (engine § 2.3). | Unchanged; Play below status lines in z-order except markers. |
| Trails / Roads list | Optional badge on rows with content this week (`play_list_badge_*`); filter chip "Play this week". | Unchanged order, count line unchanged. |
| Detail | Section "Play on this trail/road" after facts, before community: content items with rewards and status. | Unchanged above it. |
| Record | "Nearby" strip under the live list (max 3); find moments; fog/ink live effects on the record map (RecordScreen's `TrailMap` is a gated MapFx call site, engine PF-8; sensing itself runs in the graph-level `PlaySensing` observer, § 3.6.1); recording notification appends "· N finds". | Live list unchanged, stays first. |
| Results | Play section **after** all pure content (trails/roads list, verification, details): marks this walk (logbook lines), unclaimed items with reasons, rank progress. | Pure headline/island line unchanged and first. |
| Celebration | Up to 2 extra lines under the existing body/milestone lines: completion marks, rank-up. Roads milestone lines keep priority (RP-9: max 2 milestone lines + max 2 Play lines). | Unchanged. |
| Me | Play card under the stat tiles: marks, rank title, bar to next rank, "Logbook", "Island stories", (v2) Play board row. | Unchanged above it. |
| Leaderboard | Pure board unchanged; (v2) a text link at the bottom "Play board" (Play on only). | Unchanged. |
| Settings | Play section (§ 5.1). | — |
| Onboarding | Unchanged (no Play page). | — |

### 5.4 Play board consent (v2)
- First time a signed-in user opens the Play board with consent off: sheet `play_board_consent_title/body` with
  `play_board_consent_yes` / `play_board_consent_no`. Answer stored; Settings row mirrors it.
- Copy states exactly what is shown: name, rank title, marks. "Never where or when you walk."
- Declining keeps full Play locally; your marks are not shown to others.

### 5.5 Turning Play off
- Confirm sheet: `play_off_title`, `play_off_body` ("Your marks and logbook are kept, just hidden. Turn Play back
  on any time and they'll be here."), `play_off_confirm`, `common_cancel`.
- Effects: all Play UI disappears immediately; app is byte-for-byte pure (PP-2); content pack refresh stops; no
  Play sync payloads, with **one carve-out** (v2, with the board): if the user was visible on the Play board, a
  single `PUT /v1/play/board-consent { visible: false }` is sent when Play is turned off, so the row disappears
  without any further Play call; weekly closes stop (no W-WEEK for weeks with Play off; no back-pay).
- Nothing is deleted. Logbook, marks, stamps, consent, sounds setting persist on device and server.
- Walks finished while Play was off never earn Play marks retroactively except through coverage-derived marks,
  which are recomputed from coverage when Play is next turned on (same as starting balance, no double counting:
  coverage marks are cumulative by formula).

---

## 6. Scheduling hooks (product requirements for the scheduler)

The scheduler design (`docs/play/schedule.md`, which owns time, determinism and the pack schema) satisfies these.
All times island time.

| need | requirement | used by |
|---|---|---|
| Determinism | Same pack + island date + goal → same content for everyone, offline. No per-user randomness in placement (v1). **Per-item seeds** (schedule.md § 2.2: `fnv1a64(itemId)` mixed with the island seed and the occurrence date), so a pack bump never reshuffles an unchanged item; server aggregates (low-traffic squares) are baked into the pack at publish; ids are occurrence keys and item ids are immutable. | treasures, kraken coast, (v2) weekly riddles |
| Week cycle | Week = [Mon 00:00, next Mon 00:00). Treasure drop Mon 05:00 → next Mon 00:00; (v2) weekly riddles Mon 05:00 → next Mon 00:00; kraken Thu 06:00 → Sun 20:00 (clock-based); week close Mon 00:00. | § 4 |
| Daylight | Civil dawn/dusk computed on device for 47.63 N, 122.52 W. Proximity triggers only in daylight. Ambient creatures only between sunrise + 30 min and sunset − 30 min. | PP-7 |
| Time-of-day windows (v2) | Lore may declare windows inside daylight (commuter 06:00–09:00 / 16:00–19:00 weekdays). | `lore-commuter` |
| Day-of-week | Rules may target days (kraken Thu–Sun, `ch-sweep` Sat–Sun). Every weekly item must include ≥ 1 weekend day and ≥ 1 weekday, or be weekend-only by design (sweep). | fairness |
| Seasons | One definition (Conventions): island quarters, a week in the season of its Thursday. Used by (v2) the board, (v2) creature "first per season" and (v2) brambles. | § 3.7.2, § 4.6 |
| Monthly themes (v2) | One theme per month (table below) that biases which lore is featured and which extra content runs. | all |
| Special dates | v1: Mar 30 quiet day only. (v2) Jun 6–7 Frog Rock; Oct 7 Agate Pass Bridge; Oct 31 second kraken; equinox/solstice cards (no Haleets unless OD-5). | § 4.6 |
| Tides | Bundled NOAA high/low predictions for station 9445882 (Eagle Harbor, Bainbridge Island; subordinate to Seattle 9447130) for ≥ 400 days, built into the pack at publish time. **v1 uses them for one rule:** a kraken stretch is inactive while the interpolated predicted height is above 8 ft (2.44 m) MLLW; a missing or expired table keeps stretches inactive. (v2) Shore-tagged point content active only from 2 h before to 2 h after a predicted low tide and never above 8 ft MLLW. | § 8.2 |
| Ferry timetable | Owner-maintained Bainbridge departure times per WSF season (weekday/weekend lists), in the pack. Never fetched live from WSF. Expired timetable → the ferry sprite hides; (v2) horn and crossing challenge off. | § 4.6.1, (v2) § 4.6.2, `ch-crossing` |
| Kraken rotation | Roads: 6 coasts, fixed rotation order K5, K2, K6, K4, K7, K3 (spreads north/south/east/west); trails: 4 coasts T4, T2, T3, T5. Deterministic by weeks since the epoch (schedule.md `rotate`); the pack can skip a coast (e.g. roadworks) only by publishing a new item id. | § 4.3.2 |
| Quiet rules | Quiet zones (from dataset geometry) and quiet days override everything. | § 4.6.3 |
| Owner control without release | Pack JSON in the repo, published by the Worker deploy; app fetches `GET /v1/play/content?v=` with ETag on Play-on foreground at most once per hour (schedule.md § 4.8); bundled base pack for first run/offline. Kill switch: the pack's one `kill { all, types, items }` block, effective on next fetch. | PP-10 |
| Validation | Pack build refuses: anchors not Play-safe (`roadSafety`, quiet zones, beaches); any pool member touching a quiet zone; any `resolve.by ≠ none` item whose resolvable window leaves [civil dawn, civil dusk]; any creature outside creature hours; shore items without `shore` tag; count riddles without `verified_on_site`; lore without sources; kraken stretches < 100 m; in-place edits of a published item's `when`/`where`/rewards; expired tide or timetable data beyond 30 days. | § 8 |
| Clock trust | Windows evaluated on device with island time; the server's time rules are the § 3.5 caps table (stated once there). | § 3.5 |

Monthly themes (v2; v1 ships only the Mar 30 quiet day):
| month | theme | featured |
|---|---|---|
| Jan | Winter tides | Tide safety card; shore items rarer (fewer low-water daylight windows) |
| Feb | Forest listening | Owl, newt, Moss Keeper |
| Mar | First green | Pileated woodpecker; **Mar 30 quiet day** |
| Apr | Hilltop meadow | Prue's House spring card |
| May | Steamer days | Mosquito Fleet landings |
| Jun | Strawberry month | `ch-strawberry`; strawberry fields card; Frog Rock birthday (Jun 6–7) |
| Jul | Long days | Extra solo challenge "Long days": lift fog over 6 new squares this month, 150 marks (alongside `ch-foglifter`) |
| Aug | Bramble month | Brambles (trails); roads: extra treasures on residential streets |
| Sep | Radio month | Fort Ward / Station S, helix house |
| Oct | Kraken season | Second Oct-31 attack; Agate Pass Bridge card Oct 7 |
| Nov | Mill month | Port Blakely mill, Moss Keeper |
| Dec | Ferry lights | Ferry sprite lit; horn encounter cap stays 2/day |

---

## 7. Flows, screens, copy, events

Flow ids `PF`; acceptance criteria `PFn-m`. "Play on" = Settings toggle on. "Walk" = a finished recording that
passes the Finalizer.

### 7.1 Flows

#### PF0. Pure mode guarantee
AC
- PF0-1 Given Play has never been turned on, when Map, Trails/Roads list, Detail, Record, Results, Celebration, Me,
  Leaderboard and Settings (except the new Play section row) render, then emulator and simulator screenshots match
  the same screens from the pre-Play build (`docs/play/screens/pure-*.png`, diffed in the e2e script;
  `verify.ui_snapshot: none`, so there are no pixel snapshot tests), **and** a host test asserts that no Play node,
  no `fx-` layer, no `FxClock` and no `PlaySensing` observer is composed or created while Play is off.
- PF0-2 Given Play off, when the app runs a full session (launch → walk → results → sync), then no request is made
  to any `/v1/play/*` endpoint (the only exception is the v2 board-consent carve-out of § 5.5, sent once when Play
  is turned off) and the coverage upload body has no `play` or `playConfig` field.
- PF0-3 Given Play on, when a walk finishes, then island %, trails/roads completed, milestones and the pure board
  values equal those of the same walk with Play off.
- PF0-4 Given Play off, then no `play_*` analytics events are emitted.

#### PF1. Turn Play on (Settings)
Entry: Settings → Play toggle.
1. Toggle on → intro sheet (`play_intro_title`, 3 bullet lines `play_intro_1..3`, `play_intro_start`).
2. Start → Play on; starting balance computed (§ 3.6.4) → snackbar `play_start_balance` ("Your walks so far:
   1,296 marks"); Map shows Play layers.
3. Back/close the sheet without Start → toggle returns to off.
AC
- PF1-1 Given Play off, when the toggle is switched on and Start tapped, then the Map shows the Play chip with
  marks equal to the starting balance computed from the current snapshot.
- PF1-2 Given Play was on before, turned off, then on again, then no second "Your walks so far" entry is created
  and marks are unchanged.
- PF1-3 Given the intro sheet is dismissed without Start, then Play stays off and no Play request is made.
- PF1-4 Given screen reader, when the intro opens, then title then bullets are read; illustration is decorative.

#### PF2. One-time offer
AC
- PF2-1 Given a fresh install and Play never toggled, when the first finished walk's results sheet closes, then
  the offer sheet shows once.
- PF2-2 Given the first walk triggered a celebration, then the offer shows only after the results sheet (that
  follows the celebration) closes.
- PF2-3 Given the offer was dismissed, when later walks finish, then it never shows again.
- PF2-4 Given Play was toggled in Settings before the first walk, then the offer never shows.
- PF2-5 Given the walk was discarded or shorter than 1 min, then the offer is not shown for it.

#### PF3. Map with Play
AC
- PF3-1 Given Play on and this week's treasures, then each active treasure shows at its anchor; collected ones
  show the collected icon; none show inside a quiet zone.
- PF3-2 Given a trail with status Nearly done under fog, then its line is drawn in the Nearly done color and
  pattern, readable (fog never recolors status lines).
- PF3-3 Given a kraken attack on K2, then ink shows beside its three stretches and the coast label reads "The
  kraken is at Point White"; tapping it opens the attack sheet listing stretches as text.
- PF3-4 Given reduced motion, then glints, tentacles, fog drift and the ferry sprite are static; the ferry sprite
  is shown at its timetable position, updated at most once a minute.
- PF3-5 Given Play on, when the user taps a trail line where a Play marker also sits, then the pure trail preview
  opens (product wins: Play takes a tap only when the `TappableTrailLayerIds` query has no hit; Play markers have
  their own hit target, ≥ 48 dp, offset from the line).
- PF3-6 Given the screen reader focuses the Play chip, then it reads `play_map_chip_a11y`.

#### PF4. Content sheet
Entry: tap a Play marker.
AC
- PF4-1 Given a treasure, then the sheet shows item name, tier, marks, distance (if location known), "Gone Sunday
  night", and `play_walk_there` → opens the nearest Play-safe trail/road detail.
- PF4-2 Given a kraken stretch while the predicted tide is above 8 ft MLLW, then the attack sheet shows
  `play_gate_tide` with the time the tide drops below it (island time) and no Walk there button for that stretch.
  (v2: the same for shore-tagged point content outside its low-water window.)
- PF4-3 Given it's dark, then the sheet shows `play_gate_dark` with the next daylight time.
- PF4-4 Given a remembrance card, then no marks, no Walk-there call to action, no animation.

#### PF5. Find during a walk (treasure, egg, lore)
Entry: active recording, Play on.
1. Item sensed (§ 3.6.1) → find moment: overlay card over the record map (`play_find_title_*`, item text,
   `play_find_provisional`), MapFx flourish, haptic success once; auto-dismiss 4 s or tap.
2. Nearby strip updates (item marked found).
3. Screen off → no moment; notification body appends `play_notif_finds`.
4. Finish → claim pass (§ 3.6.2) → results Play section.
AC
- PF5-1 Given a replayed walk passing within 15 m of an active treasure with 10 m accuracy at 1.3 m/s, then a find
  moment shows within 5 s of the fix and the item is claimed at Finish with +25.
- PF5-2 Given a replayed drive (12 m/s) past the treasure, then no find moment shows and nothing is claimed.
- PF5-3 Given a walk with the screen off for its whole duration, when finished, then items reached are claimed at
  Finish and listed in results.
- PF5-4 Given the walk is discarded, then no Play entry exists.
- PF5-5 Given accuracy 35 m near a 20 m-radius treasure, then it is not sensed.
- PF5-6 Given reduced motion, then the find moment appears with a 150 ms crossfade and no flourish; screen reader
  announces `play_find_a11y`.
- PF5-7 Given the day's found-marks total is 390, when a 25-mark treasure is claimed, then the logbook shows the
  find with `play_log_cap` and +0 marks.

#### PF6. Results Play section
AC
- PF6-1 Given Play on and a walk with coverage, then the results show all pure content first and then the Play
  section with one line per logbook entry and a total `play_results_total`.
- PF6-2 Given an item sensed live but not claimed (dropped fixes), then it shows under `play_unclaimed_header`
  with reason `play_unclaimed_vehicle` / `_accuracy` / `_window` / `_dark` / `_tide`.
- PF6-3 Given a rank-up on this walk, then `play_rank_up` shows as a line in the Play section (and in the
  celebration if one is shown) and nowhere as a separate modal.
- PF6-4 Given the same summary on relaunch, then the Play section is not shown again (follows `summary_shown`).

#### PF7. Kraken attack
AC
- PF7-1 Given an attack on K5 and a replayed walk covering 1,158 m of the 1,158 m Manitou stretch in daylight with
  the tide below the threshold, then +80 marks are claimed (≥ 95 % bucket, `band: 95`).
- PF7-2 Given a walk covering 50 % of a stretch, then nothing is claimed for it and the attack sheet shows "50 %
  walked — 60 % repels it".
- PF7-3 Given the same stretch walked again in the same attack, then no second F-INK entry is created.
- PF7-4 Given every stretch repelled by ≥ 3 players by Sunday 20:00, then every contributor gets +50 on next sync
  and the Map shows the retreat moment once.
- PF7-5 Given not all repelled, then the sheet shows `play_kraken_slipped` and nobody loses marks.
- PF7-6 Given March 30, then no attack shows.
- PF7-7 Given the predicted tide above 8 ft MLLW for the whole walk, when it covers a stretch, then nothing is
  claimed for it and results show `play_unclaimed_tide`.
- PF7-8 Given an attack window, then it opens Thursday 06:00 and closes Sunday 20:00 island time regardless of the
  tide (clock-based); no second attack overlaps it.

#### PF8. Riddle
AC
- PF8-1 Given `rd-frog` opened, when the user taps Frog Rock's map area (armchair), then the riddle shows
  `play_riddle_solved_stamp` and a logbook stamp with no marks.
- PF8-2 Given the riddle solved, when a walk passes within 30 m of the answer in daylight, then it is claimed with
  40 marks (★).
- PF8-3 Given 1 hint used on a ★★ riddle, then the claim pays 60.
- PF8-4 Given an unsolved riddle, when a walk passes the answer, then it is claimed anyway.
- PF8-5 Given screen reader, then an answer list (searchable) is offered in place of map tapping.
- PF8-6 Given a wrong answer, then `play_riddle_wrong` shows and nothing else changes.

#### PF9. Challenge
AC
- PF9-1 Given `ch-sweep` active and 3 trails completed by walks finishing Saturday and Sunday, then +100 is
  claimed on the walk that reaches 3.
- PF9-2 Given a walk finishing Monday 00:05, then it does not count toward the previous weekend's sweep.
- PF9-3 (v2) Given `ch-crossing`, then the challenge sheet shows the next departure times and never shows a countdown
  during a walk.
- PF9-4 Given an island challenge, then the sheet shows the shared progress (e.g. "3 of 4 stretches") and the
  user's band as `{band, count}`, never a ranked list of people; the server response contains no names.
- PF9-5 Copy test: no `play_*` string contains "race", "run", "fast", "speed", "sprint" or "beat" (case-insensitive).

#### PF10. Lore and remembrance
AC
- PF10-1 Given a walk within 60 m of Frog Rock in daylight, then the Frog Rock encounter shows and +15 is claimed
  once ever.
- PF10-2 Given a walk inside the memorial quiet zone, then no Play visuals, sounds or haptics play, no content is
  sensed, and the logbook gets a stamp without a number.
- PF10-3 (v2) Given a creature encounter, then its safety line is shown on the card.
- PF10-4 Given a lore card, then a Sources row lists at least one source.
- PF10-5 (v2) Given "Play sounds" off (default), then the ferry horn moment is silent.

#### PF11. Logbook
AC
- PF11-1 Given entries across 3 walks, then they group by walk (date · distance) newest first, each line showing
  what, why and `+n`.
- PF11-2 Given a `local_only` entry, then it shows `play_log_local_only`.
- PF11-3 The logbook never shows a time of day.
- PF11-4 Totals at top equal the sum of entries.

#### PF12. Ranks
AC
- PF12-1 Given 2,291 marks, then the Ranks screen shows Salal Scout as current and Madrona Wanderer as next with
  709 to go.
- PF12-2 Each rank row is readable by screen reader as `play_rank_row_a11y`.

#### PF13. Play board (v2)
AC
- PF13-1 Given signed in with consent off, when the Play board opens, then the consent sheet shows once.
- PF13-2 Given consent on and marks > 0, then the user's row appears with name, rank title and marks only.
- PF13-3 The board response contains no timestamps, coordinates, content ids, week ids or walk data (backend
  privacy test, as F8-4).
- PF13-4 Given season view, then ranks are by marks earned this quarter; all-time view by total.
- PF13-5 The pure board response and order are unchanged when Play data exists.

#### PF14. Turn Play off
AC
- PF14-1 Given Play on with 1,240 marks, when turned off and confirmed, then every Play surface disappears and
  PF0-1/PF0-2 hold.
- PF14-2 Given Play turned on again, then marks are 1,240 plus coverage-derived marks for coverage gained while
  off, and the logbook entries are intact.
- PF14-3 (v2) Given Play off, then the user's Play board row is absent on the next board fetch.

#### PF15. Sync
AC
- PF15-1 Given a walk with 2 claims offline, when connectivity returns, then the coverage upload carries the
  `play` field with 2 claims and the server's accepted total matches.
- PF15-2 Given a coverage upload rejected as implausible, then the walk's Play entries become `local_only`.
- PF15-3 Given a claim for a content id the server doesn't know in that week, then that claim is rejected and only
  that entry becomes `local_only`.
- PF15-4 The `play` payload contains no timestamps, days, marks or coordinates (contract test).
- PF15-5 Given 250 queued walks with Play claims (cap 200 per upload), then the 50 walks over the cap keep their
  Play elements queued and send them with the next upload; none is dropped uncredited.
- PF15-6 Given a claim naming a pack version the Worker no longer keeps, then it is rejected `stale_config` and
  only that entry becomes `local_only`.
- PF15-7 Given a reinstall with 2,410 marks on the server, when Play is turned on, then the Logbook shows one
  "Restored from your account" entry and a total of 2,410.

#### PF16. Pack update and kill switch
AC
- PF16-1 Given a pack whose `kill.items` lists a treasure item, when fetched, then the treasure disappears from the
  Map and can't be sensed.
- PF16-2 Given no network on first run, then the bundled base pack is used and Play works offline.
- PF16-3 Given an expired tide table, then every kraken stretch shows `play_gate_tide_unknown` and can't be
  sensed (fail closed).

### 7.2 Screen inventory (names only; layout in `docs/play/ux.md`)
| screen / surface | kind |
|---|---|
| `PlayIntroSheet` | sheet |
| `PlayOfferSheet` | sheet |
| `PlayOffSheet` | sheet |
| Settings → `PlaySettingsSection` | section |
| `PlayAboutSheet` ("How Play works") | sheet |
| Map → `PlayMapLayer` (MapFx), `PlayChip`, `ChallengesChip`, Play legend group | overlay |
| `PlayContentSheet` (treasure / egg / lore / remembrance variants) | sheet |
| `KrakenAttackSheet` | sheet |
| `RiddleSheet` + `RiddleAnswerPicker` | sheet + full screen |
| `ChallengesScreen` + `ChallengeSheet` | screen + sheet |
| Record → `PlayNearbyStrip`, `FindMoment` | inline + overlay |
| Results → `PlayResultsSection` | section |
| Celebration → Play lines | lines |
| Detail → `PlayOnThisFeature` section | section |
| Me → `PlayCard` | card |
| `LogbookScreen` | screen |
| `RanksScreen` | screen |
| `IslandStoriesScreen` (lore collection) + `LoreCardScreen` | screen |
| (v2) `PlayBoardScreen` + `PlayBoardConsentSheet` | screen + sheet |
| Debug: `PlayShowcaseScreen` (every v1 effect, both themes, reduced variant; AC 7), `PlayClockDebug` (time travel: week, daylight, tide; also the e2e play-clock override, `-btPlayClock` on iOS), Play pack inspector | debug |

### 7.3 Copy (string table, `play_` keys)

Tone: the island speaking gently. Sentence case. No exclamation marks. "Walk" never "run"; "challenge" never
"race". Numbers formatted per § 3.1. Keys marked **(goal)** have a `roads_` twin (same args) registered in
`GoalVocabulary.Roads`; the roads text is given.

#### Settings, intro, offer, off
| key | text | roads text (goal) |
|---|---|---|
| play_settings_section | Play | |
| play_settings_toggle | Play | |
| play_settings_toggle_body | Add treasures, riddles, challenges and island stories to your walks. Your progress and the leaderboard stay exactly as they are. | |
| play_settings_sounds | Play sounds | |
| play_settings_sounds_body | Ferry horns and small chimes. Off by default. | |
| play_settings_board | Show me on the Play board | |
| play_settings_board_body | Others see your name, rank and marks. Never where or when you walk. | |
| play_settings_board_signed_out | Sign in to appear on the Play board. | |
| play_settings_ranks | Ranks | |
| play_settings_logbook | Logbook | |
| play_settings_about | How Play works | |
| play_intro_title | Play on the island | |
| play_intro_1 | Find treasures, easter eggs and riddles hidden around the island. (goal) | Find treasures, easter eggs and riddles along the island's roads. |
| play_intro_2 | Earn marks for every walk. Walking there is what counts. | |
| play_intro_3 | Your map, your progress and the leaderboard don't change. Turn Play off any time. | |
| play_intro_start | Start playing | |
| play_start_balance | Your walks so far: %1$s marks | |
| play_offer_title | Want a little more island in your walks? | |
| play_offer_body | Play adds treasures, riddles, the odd kraken and island stories to the map. It's optional, and your progress stays exactly the same. | |
| play_offer_accept | Try Play | |
| play_off_title | Turn off Play? | |
| play_off_body | Your marks and logbook are kept, just hidden. Turn Play back on any time and they'll be here. | |
| play_off_confirm | Turn off | |
| play_about_title | How Play works | |
| play_about_marks | Marks come from your walks: new ground, finished trails, walking days and things you find. (goal) | Marks come from your walks: new road, finished roads, walking days and things you find. |
| play_about_claim | You can spot and solve things from home. You claim them by walking there. | |
| play_about_safety | Finds only happen in daylight, never on private land or busy roads, and the kraken waits for the tide to drop. | |
| play_about_privacy | Play syncs which island things you found and in which week. Finding something at a fixed place tells us you were near it that week. Your GPS track and walk times stay on this phone. | |

#### Map, legend, chips
| key | text | roads text (goal) |
|---|---|---|
| play_map_chip | %1$s · %2$s | |
| play_map_chip_a11y | Play. %1$s marks. Rank %2$s. Opens your logbook. | |
| play_challenges_chip (plural) | one: 1 challenge this week · other: %1$d challenges this week | |
| play_legend_group | Play | |
| play_legend_fog | Fog: squares you haven't walked yet | |
| play_legend_treasure | This week's treasure | |
| play_legend_kraken | Kraken ink | |
| play_legend_riddle | Riddle | |
| play_legend_lore | Island story | |
| play_kraken_coast | The kraken is at %1$s | |
| play_ferry_a11y | Ferry crossing Eagle Harbor, by the timetable. | |
| play_quiet_banner | Today we remember March 30, 1942. Play is quiet today. | |
| play_quiet_banner_action | Read more | |
| play_list_badge_treasure | Treasure | |
| play_list_badge_kraken | Kraken | |
| play_list_badge_riddle | Riddle | |
| play_list_filter | Play this week | |
| play_detail_section | Play on this trail (goal) | Play on this road |

#### Content sheets
| key | text |
|---|---|
| play_walk_there | Walk there |
| play_reward | %1$s marks |
| play_reward_none | Nothing to earn here. Just a place to visit. |
| play_tier_sea_glass | Sea glass |
| play_tier_agate | Agate |
| play_tier_glass_float | Glass float |
| play_tier_ships_bell | Ship's bell |
| play_treasure_expires | Washes away Sunday night |
| play_treasure_collected | Found |
| play_treasures_gone | This week's treasures have washed away. New ones Monday. |
| play_egg_count | Easter eggs: %1$d of %2$d found |
| play_distance_away | %1$s away |
| play_gate_dark | Back in daylight, from %1$s |
| play_gate_tide | The tide is high here. The kraken can be sent back from %1$s |
| play_gate_tide_unknown | The kraken is paused until tide times are updated. |
| play_gate_quiet | A quiet place. Nothing to find here. |
| play_tag_history | History |
| play_tag_myth | Myth |
| play_tag_tall_tale | Island tall tale, made up for this app |
| play_tag_remembrance | Remembrance |
| play_tag_creature | Island creature |
| play_sources | Sources |
| play_safety_owl | Give owls space, especially in spring. |
| play_safety_coyote | Keep dogs leashed. Never feed wildlife. |
| play_safety_newt | Look, don't touch. Their skin is toxic. |
| play_safety_shore | Stay off the beach when the tide is coming in. |
| play_ferry_timetable_note | Times come from the published timetable. The real boat may be late. |

#### Kraken
| key | text | roads text (goal) |
|---|---|---|
| play_kraken_title | The kraken is ashore | |
| play_kraken_body | It's inking %1$s. Walk most of an inked stretch to send it back. (goal) | It's inking %1$s. Walk most of an inked road to send it back. |
| play_kraken_rule | Walk %1$d%% of a stretch in one walk | |
| play_kraken_stretch_progress | %1$d%% walked. %2$d%% sends it back. | |
| play_kraken_repelled_you | You sent it back | |
| play_kraken_island_progress | %1$d of %2$d stretches sent back by islanders | |
| play_kraken_until | Until Sunday, 8 pm | |
| play_kraken_retreat | The kraken has slipped back into the deep. Thanks, islanders. | |
| play_kraken_slipped | The kraken slipped away. It'll be back next week. | |
| play_kraken_a11y | Kraken sent back on %1$s. %2$d marks. | |

#### Riddles
| key | text |
|---|---|
| play_riddle_title | Riddle |
| play_riddle_stars_a11y (plural) | one: Difficulty 1 star · other: Difficulty %1$d stars |
| play_riddle_answer | Answer |
| play_riddle_pick_on_map | Pick the place on the map |
| play_riddle_pick_list | Choose from a list |
| play_riddle_count_hint | Count it when you're there |
| play_riddle_hint | Hint |
| play_riddle_hint_cost | A hint lowers the reward to %1$s marks |
| play_riddle_wrong | Not quite. Try again any time. |
| play_riddle_solved_stamp | Solved. Walk there to claim it. |
| play_riddle_claimed | Claimed. %1$s marks. |
| play_riddle_count_near | Get within 30 m to answer this one. |

#### Challenges
| key | text | roads text (goal) |
|---|---|---|
| play_challenges_title | Challenges | |
| play_challenge_solo | Just you | |
| play_challenge_island | Whole island | |
| play_challenge_window | %1$s – %2$s | |
| play_challenge_progress | %1$s of %2$s | |
| play_challenge_done | Done | |
| play_challenge_ended | Challenge ended | |
| play_challenge_sweep_title | Weekend sweep | |
| play_challenge_sweep_body | Finish %1$d trails this weekend. Short ones count. (goal) | Finish %1$d roads this weekend. Short streets count. |
| play_challenge_crossing_title (v2) | While the boat crosses | |
| play_challenge_crossing_body (v2) | In the %1$d minutes after a ferry leaves, walk %2$s of new trail. (goal) | In the %1$d minutes after a ferry leaves, walk %2$s of new road. |
| play_challenge_next_departures | Next departures: %1$s | |
| play_challenge_foglifter_title | Fog lifter | |
| play_challenge_foglifter_body | Lift the fog over %1$d new squares this month. | |
| play_challenge_bestweek_title | Your best week | |
| play_challenge_bestweek_body | Walk more new ground than your best week of the last four. | |
| play_challenge_strawberry_title | Strawberry month | |
| play_challenge_strawberry_body | Together, islanders walk %1$s of new ground this June. | |
| play_challenge_band | You were in the %1$s band, with %2$d others | |
| play_challenge_band_top | top | |
| play_challenge_band_middle | middle | |
| play_challenge_band_starting | starting | |
| play_challenge_first_wave | First wave | |
| play_challenge_first_wave_body | You helped in the first two days. | |

#### Record and find moments
| key | text |
|---|---|
| play_nearby_header | Nearby |
| play_nearby_row | %1$s · %2$s |
| play_find_title_treasure | %1$s, found |
| play_find_title_egg | An easter egg |
| play_find_title_lore | %1$s |
| play_find_title_horn | The ferry's leaving |
| play_find_provisional | Found on this walk. Confirmed when you finish. |
| play_find_a11y | Found %1$s. %2$s marks when you finish. |
| play_fog_lifted_a11y | Fog lifted over a new square. %1$d of %2$d squares explored. |
| play_notif_finds (plural) | one: · 1 find · other: · %1$d finds |

#### Results, celebration, logbook, ranks
| key | text |
|---|---|
| play_results_header | Play |
| play_results_total | %1$s marks this walk |
| play_results_none | No marks this walk. Walking days need 10 minutes and half a kilometre. |
| play_unclaimed_header | Not claimed |
| play_unclaimed_vehicle | Looked like a ride, not a walk |
| play_unclaimed_accuracy | GPS wasn't precise enough there |
| play_unclaimed_window | It had already washed away |
| play_unclaimed_dark | It was after dark |
| play_unclaimed_tide | The tide was in |
| play_rank_up | New rank: %1$s |
| play_rank_up_a11y | New rank, %1$s. |
| play_celebrate_marks | +%1$s marks |
| play_log_title | Logbook |
| play_log_total | %1$s marks |
| play_log_week | %1$s this week |
| play_log_season (v2) | %1$s this season |
| play_log_restored | Restored from your account. Earlier details stay on your old phone. |
| play_log_next_rank | %1$s to %2$s |
| play_log_line | +%1$s |
| play_log_cap | Daily limit reached. Found, no marks. |
| play_log_local_only | Not on the Play board (couldn't be confirmed) |
| play_log_stamp | Stamped |
| play_log_empty | Your logbook fills in as you walk. |
| play_kind_coverage | New ground |
| play_kind_complete | Trail complete (goal; roads: Road complete) |
| play_kind_park_first | First steps in a park |
| play_kind_park_complete | Every trail in a park |
| play_kind_milestone | Milestone |
| play_kind_island | The whole island |
| play_kind_square | Fog lifted |
| play_kind_day | Walking day |
| play_kind_week | Kept week |
| play_kind_week_run (plural) | one: Kept week · other: Kept week, %1$d in a row |
| play_kind_treasure | Treasure |
| play_kind_egg | Easter egg |
| play_kind_riddle | Riddle |
| play_kind_ink | Kraken sent back |
| play_kind_retreat | Kraken retreat |
| play_kind_bramble | Bramble cleared |
| play_kind_lore | Island story |
| play_kind_horn | Ferry horn |
| play_kind_challenge | Challenge |
| play_kind_island_challenge | Island challenge |
| play_kind_wave | First wave |
| play_kind_start_balance | Your walks so far |
| play_kind_restored | Restored from your account |
| play_kind_visit | Visited |
| play_ranks_title | Ranks |
| play_ranks_current | You're a %1$s |
| play_ranks_next | %1$s marks to %2$s |
| play_rank_row_a11y | %1$s, from %2$s marks. %3$s |
| play_rank_earned | Earned |
| play_rank_1 | Walk-on |
| play_rank_2 | Dock Walker |
| play_rank_3 | Beachcomber |
| play_rank_4 | Salal Scout |
| play_rank_5 | Madrona Wanderer |
| play_rank_6 | Heron Watcher |
| play_rank_7 | Fog Navigator |
| play_rank_8 | Tide Reader |
| play_rank_9 | Kraken Tamer |
| play_rank_10 | Island Cartographer |
| play_rank_11 | Keeper of the Chart |
| play_rank_1_flavour … play_rank_11_flavour | (the flavour lines in § 3.7.1) |
| play_stories_title | Island stories |
| play_stories_count | %1$d of %2$d met |

#### Me, board (board keys v2)
| key | text |
|---|---|
| play_me_card_title | Play |
| play_me_marks | %1$s marks |
| play_me_logbook | Logbook |
| play_me_stories | Island stories |
| play_me_board_row | #%1$d on the Play board |
| play_me_board_row_hidden | Not on the Play board |
| play_board_link | Play board |
| play_board_title | Play board |
| play_board_subtitle | Ranked by marks. Separate from the leaderboard. |
| play_board_season | This season |
| play_board_all_time | All time |
| play_board_season_name_q1 | Storm season |
| play_board_season_name_q2 | Strawberry season |
| play_board_season_name_q3 | Madrona season |
| play_board_season_name_q4 | Kraken season |
| play_board_row_a11y | Rank %1$d, %2$s, %3$s, %4$s marks |
| play_board_consent_title | Join the Play board? |
| play_board_consent_body | Others will see your name, rank and marks. Never where or when you walk. You can change this in Settings. |
| play_board_consent_yes | Show me |
| play_board_consent_no | Not now |
| play_board_empty | No one's on the Play board yet. |

Content-pack text (riddles, lore, treasure placements) is not in `strings.xml`; the pack stores `text.en` per item
and the pack validator enforces the same tone rules (sentence case, no "race/run/fast/speed", no "points", no
exclamation).

### 7.4 Analytics facade events (debug logs only)
Via `Analytics.track` (`LogAnalytics` in debug, `NoOpAnalytics` in release). Props never contain coordinates,
timestamps, display names or free text.
| event | props |
|---|---|
| `play_enabled` | `source`: settings / offer · `goal` |
| `play_disabled` | `goal` |
| `play_offer_shown` | — |
| `play_offer_accepted` | — |
| `play_offer_dismissed` | — |
| `play_start_balance` | `marks` |
| `play_content_opened` | `type`, `content_id` |
| `play_content_sensed` | `type`, `content_id` |
| `play_content_claimed` | `type`, `content_id`, `marks` |
| `play_content_unclaimed` | `type`, `content_id`, `reason`: vehicle / accuracy / window / dark / tide / quiet |
| `play_gate_blocked` | `gate`: dark / tide / quiet / speed / mock · `type` |
| `play_cap_reached` | `cap`: day / week |
| `play_riddle_solved_armchair` | `riddle_id`, `hints` |
| `play_riddle_hint` | `riddle_id`, `n` |
| `play_kraken_repelled` | `coast`, `stretch_id`, `marks` |
| `play_kraken_result` | `coast`, `outcome`: retreat / slipped |
| `play_challenge_progress` | `challenge_id`, `pct` (10 % steps) |
| `play_challenge_completed` | `challenge_id`, `marks` |
| `play_horn` (v2) | `marks` |
| `play_rank_up` | `rank` |
| `play_logbook_opened` | — |
| `play_board_opened` | `view`: season / all_time |
| `play_board_consent` | `visible`: true / false |
| `play_pack_updated` | `version`, `source`: network / bundled |
| `play_quiet_day` | — |
| `play_remembrance_viewed` | `card_id` |

---

## 8. Safety and fairness rules

### 8.1 Placement (pack build refuses violations)
| rule | detail |
|---|---|
| S-1 Play-safe anchors only | § 4.0.2. Roads: the one `roadSafety` rule — counted; `category` residential, collector or arterial (never `state_route`); `public: true` and `ownership` `public_*`; `speed_mph ≤ 25` (null only for residential, TBC). From `roads.json` `counted`, `category`, `public`, `ownership`, `speed_mph`. |
| S-2 No private land | No anchor on private roads, driveways, private parcels (Batteries Nash/Warner, Station S building), rental buildings (Prue's House cabin), ticketed sites (Bloedel Reserve). |
| S-3 No beach anchors | Shoreline distance ≥ 10 m; shore-tagged items (< 60 m) sit at road/trail ends above the beach. |
| S-4 Shoulderless roads | `roads.json` has no shoulder data. Until it does, the speed/category rule stands in for it, and the owner can add road ids to a `no_play` list in the pack (OD-10). |
| S-5 Quiet zones | Built from dataset geometry: memorial trail 200 m buffer, cemetery trail 100 m buffer, owner-tagged places; Pritchard Park out of every pool; no pool member may touch a zone; § 4.6.3. |
| S-6 Archaeology | Sensitive sites (Yama, Haleets) are never pinned. |
| S-7 Spacing | Treasures ≥ 300 m apart; ≤ 1 per chart square per week (the single grid). |

### 8.2 Time and water
| rule | detail |
|---|---|
| T-1 Daylight only | Every item that resolves (`resolve.by ≠ none`) is resolvable only inside civil dawn → civil dusk; creatures (v2) only in creature hours, sunrise + 30 min → sunset − 30 min. The schedule validator rejects any item whose resolvable window leaves those bounds (schedule.md § 3.4). Walks after dark still earn walk-derived marks (honest), never finds. No dusk, night-only or "after dark" content exists anywhere in Play. |
| T-2 Tide | **v1 (kraken):** a stretch is inactive while the predicted height at Eagle Harbor (9445882) is above 8 ft (2.44 m) MLLW; inactive if the tide table is missing or expired. The kraken window itself is clock-based. **(v2) shore-tagged point items:** active only from 2 h before to 2 h after a predicted low tide and at ≤ 8 ft MLLW. |
| T-3 No push | Play sends no notifications. The only Play text outside the app is the "· N finds" suffix on the existing recording notification. |
| T-4 Weather | No weather data (PP-10). Content copy never encourages going out in storms; January theme is a tide-safety card. |

### 8.3 Respect
| rule | detail |
|---|---|
| R-1 Remembrance | No marks, effects, trivia, riddles or challenges about the Exclusion, Yama, cemeteries or Suquamish cultural sites (§ 4.6.3). March 30 quiet day. |
| R-2 Myths labelled | Every card tagged History / Myth / Tall tale / Remembrance / Creature. Invented content says "made up for this app". |
| R-3 Sources | Every History card cites ≥ 1 source; discrepancies stated, not resolved by guessing. |
| R-4 No brands | No team names or logos (the kraken is generic folklore, never Seattle Kraken branding); no named businesses (OD-7); no depiction of copyrighted public art (Dambo's troll). |
| R-5 Indigenous content | Only after consultation with the Suquamish Tribe (OD-5). |

### 8.4 Anti-cheat (reuse of coverage plausibility)
| rule | detail |
|---|---|
| A-1 Same gates | Claims use the Finalizer's gated track (vehicle/activity, speed, steps) and the coverage fix filter (mock, accuracy, speed), handed to Play through `Finalizer.onGated(sessionId, keptFixes)` (§ 3.6.2). Nothing new to tune. |
| A-2 Live speed | Sensing requires trailing-60 s speed ≤ 4.0 m/s (`PLAUSIBLE_SPEED_MPS`). |
| A-3 Accuracy | Fix accuracy ≤ the item radius (20/15/30 m). |
| A-4 Server plausibility | Claims ride the coverage upload, each tied to a `walkId` credited by that upload; `422 implausible` rejects both (§ 3.6.3). |
| A-5 Server-derived marks | The server derives every mark from its content table and accepted coverage; the client sends occurrence keys only, never marks. Stretch and feature claims need this user's bits on their anchor segments. |
| A-6 Content validity | Server checks the occurrence against the pack version named in `playConfig` (last 8 kept), repeat rules, caps, time rules. Published item ids are immutable. |
| A-7 Caps | § 3.5 (the only caps table). |
| A-8 Clock tampering | § 3.5 time rules: find occurrences must end ≥ server date − 2 d and start ≤ server now + 1 h; week-keyed items until week end + 7 d. A rolled-back clock can replay at most two days. |
| A-9 Armchair | Tapping never pays. |

### 8.5 Fairness
| rule | detail |
|---|---|
| FR-1 Same world | Everyone sees the same content at the same time (deterministic). |
| FR-2 Geography | Kraken coasts rotate through all sides; treasures spread across chart squares with a share in low-traffic squares. |
| FR-3 Schedule | Weekly windows include a weekday and a weekend day (except weekend-only by design). |
| FR-4 Fitness | No speed measures; caps not rankings; personal targets; distance-based rewards are per new metre, which fitness can't farm beyond the map. |
| FR-5 Goals | Trails and roads economies tuned to the same totals (§ 3.7.3); separate boards. |
| FR-6 Late joiners | Starting balance credits past walking (§ 3.6.4); (v2) seasons give everyone a fresh board view every quarter. |
| FR-7 Accessibility | Every content type has a text path; answer lists replace map tapping; no audio-only cues; reduced-motion variants for every effect. |

---

## 9. Owner decisions needed

Recommended default already applied in the body. Change any row and this spec updates in one place.

| # | question | default applied | why |
|---|---|---|---|
| OD-1 | Is "Prue's house" the restored Panabode cabin at Hilltop (9600 Mandus Olson Rd NE, once owned by Prue Trudgian), next to the Grand Forest? | Yes; lore card anchored at the Hilltop trailhead, no trigger at the cabin (rental venue). | Only match found; it's a public park district site by Grand Forest trails. |
| OD-2 | Unit name | **Marks**; the record is the "Logbook". | `pts` already means percentage points in results; chart-room theme. |
| OD-3 | Darkness rule | Finds and encounters only in daylight; walks after dark still earn walk-derived marks. | Kid-safety and no night lures; Dana's dark winter mornings still count for coverage marks. |
| OD-4 | March 30 island-wide quiet day | On. | Anniversary of the 1942 removal; avoids kraken jokes on that day. |
| OD-5 | Include Haleets / Suquamish cultural content? | Excluded until the owner consults the Suquamish Tribe. | Living cultural site on the beach; the Tribe claims it as heritage. |
| OD-6 | "Grand Forest troll" | Replaced by an invented, labelled "Moss Keeper"; Dambo's *Pia the Peacekeeper* (Sakai Park) not depicted. | No local troll legend found; the real troll is a copyrighted artwork. |
| OD-7 | Named businesses (e.g. Pegasus Coffee) in lore | None. | Endorsement, consent and churn risk. |
| OD-8 | Show named "first finders"? | No; "island firsts" and "first wave" instead. | Would publish that a named person was at a place recently. |
| OD-9 | Word for window-boxed goals | "Challenge" in UI, code, tables and routes (type `challenge`); timed races are cut. | No speed language near roads (PP-8; review P0-2). |
| OD-10 | Shoulderless roads | Speed/category rule + owner `no_play` road list in the pack (empty at launch). | Dataset has no shoulder attribute. |
| OD-11 | Kraken community threshold | 3 distinct players per stretch. | Small island; achievable; pack can lower it. |
| OD-12 | Daily / weekly found caps | 400 / 2,800. | Hours of walking to hit; stops content farming. |
| OD-13 | Kept-week threshold | 2 walking days. | Weekend walkers qualify; no daily streak. |
| OD-14 | Starting balance on opt-in | Credit walk-derived marks from existing coverage once. | Honest; long-time walkers aren't reset to zero. |
| OD-15 | Ferry timetable source | Owner updates the pack each WSF season; no live API. **Resolved: real timetable**, source WSF Seattle/Bainbridge Island sailing schedule <https://wsdot.wa.gov/ferries/schedule/scheduledetailbyroute.aspx?route=sea-bi> (fetched 2026-10-21; cross-checked against WSF Best Times to Travel PDF <https://wsdot.wa.gov/sites/default/files/2026-09/WSF-BTTT-SEA-BI-Fall2026.pdf>), season **Fall 2026 (Sep 20 – Dec 26, 2026)**, `validUntil` 2026-12-26; holidays Thanksgiving and Christmas (WSF runs the weekend schedule). Pack 2026.11.1. The Saturday-and-holiday-only 07:55 Bainbridge sailing is in the holiday column only. Winter 2026–27 (Dec 27 – Mar 20) was not published on 2026-10-21: the owner adds it before Dec 27 or the ferry sprite hides (no build check enforces the expiry today). | $0, no third-party calls from the phone; WSF API needs a key. |
| OD-16 | Unverified creature/lore facts marked TBC (Pileated woodpecker and rough-skinned newt local presence, helix house exact coordinate, Fletcher Landing as a former steamer landing, Frog Rock Heritage Register listing, Prue's House cabin coordinate, Frog Rock's birthday June 6 vs 7, the Strawberry Festival's first year 1948 vs 1950, Lynwood as "first sound-film cinema", the Fay bell's provenance, the null-speed residential = 25 mph assumption, the Fairy door egg spot; schedule.md's wren, squirrel, chorus frog, bat, banana slug and chum salmon notes are v2 and unsourced) | Shipped only after an owner or naturalist check; cards are `draft` until then. | Don't invent facts. |
| OD-17 | Play sounds | Off by default. | Calm app; walks are often with headphones or near others. |
| OD-18 | Play board seasons (v2) | Quarterly views, named Storm / Strawberry / Madrona / Kraken. | Fresh competition for late joiners without deleting anything. |
| OD-19 | Privacy disclosure of claims | Disclose: `play_about_privacy` says a fixed-place find tells us you were near it that week; `docs/legal/privacy-policy.md` gains a Play paragraph (week ids, occurrence keys, no days) before Play ships; the server stores week ids, never a day. **Owner signs off the paragraph's wording before the next Worker deploy** (it is rendered into `backend/src/data/legal-pages.ts` and goes live with the deploy); without sign-off the deploy keeps the old legal page. Proof: a legal-page test asserting the served HTML equals the signed-off revision. | Review P1-20: "not where or when" was false. Privacy review 2026-10-20 P1-1. |
| OD-20 | Japanese American community events | No gameplay on them without BIJAC's consent; Mochi Tsuki gameplay is cut (schedule.md D-10). | Review P1-23. |
| OD-21 | Retention of place-and-week claims (filed as "OD-20" in privacy review 2026-10-20 P1-2; renumbered, OD-20 is taken) | **Open, owner decides.** Today `play_claims.occ` + `week` ("near fixed place P during week W": treasures, eggs, lore, point riddles) are kept until account deletion with no limit. Candidate: collapse weekly claims older than N weeks into totals and keep only the one-shot "always" keys. The owner also records the posture in the hub (`compliance/data-privacy.md`, `portfolio/bainbridge-trails.md`) and qualifies the policy's "No GPS tracks, location points…" bullet. Proof: a cron/migration test that old weekly rows prune and totals hold. | A new data class with no retention limit; the policy bullet reads broader than the data. |

---

## Sources

Places and history
- Frog Rock: [Wikipedia — Frog Rock (Bainbridge Island)](https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington)) · [Atlas Obscura — Frog Rock](https://assets.atlasobscura.com/places/frog-rock-bainbridge-island) · [Bainbridge Review](https://www.bainbridgereview.com/?p=104439)
- Prue's House: [Bainbridge Currents — Prue's House](https://bainbridgecurrents.com/venue/prues-house/) · [BIMPRD — Prue's House: A Cabin at the Heart of Hilltop](https://biparks.org/prues-house-a-cabin-at-the-heart-of-hilltop/) · [Bainbridge Review — Meadow and forest forever: Hilltop preserved](https://www.bainbridgereview.com/news/meadow-and-forest-forever-hilltop-preserved/)
- Haleets: [Wikipedia — Haleets](https://en.wikipedia.org/wiki/Haleets) · [Bainbridge Review — New mystery of x̌alilc](https://www.bainbridgereview.com/?p=115949)
- Japanese American Exclusion Memorial: [NPS — The Bainbridge Island Japanese American Exclusion Memorial](https://www.nps.gov/places/the-bainbridge-island-japanese-american-exclusion-memorial.htm) · [TCLF](https://tclf.org/bainbridge-island-japanese-american-exclusion-memorial) · [Densho](https://densho.org/?p=4976)
- Fort Ward and Station S: [Wikipedia — Fort Ward (Washington)](https://en.wikipedia.org/wiki/Fort_Ward_(Washington)) · [HistoryLink 9648 — Station S](https://historylink.org/File/9648) · [HistoryLink 9699](https://historylink.org/File/9699) · [NCVA — The Ear to the Pacific](https://usncva.org/nsga-bainbridge-island.html)
- Battle Point helix house: [Navy Radio — NRTS Battle Point](https://navy-radio.virhistory.com/commsta/battle.htm) · [Bainbridge Review — Observatory renovations coincide with new name](https://www.bainbridgereview.com/?p=118963) · [BPAA — About](https://bpastro.org/about/)
- Port Blakely mill: [HistoryLink 1053 — Capt. William Renton](https://www.historylink.org/File/1053) · [Wikipedia — Blakely Harbor](https://en.wikipedia.org/wiki/Blakely_Harbor) · [Port Blakely — Partnering on Blakely Harbor cleanup](https://portblakely.com/news/2020/partnering-on-blakely-harbor-cleanup)
- Yama: [Discover Nikkei — Bainbridge Island's lost village](https://5dn.org/en/journal/2016/11/21/bainbridge-island-lost-village/) · [History News Network — Vanished village](https://www.historynewsnetwork.org/article/126755) · [Bainbridge Review — Archaeological plans](https://bainbridgereview.com/?p=21059)
- Mosquito Fleet: [Wikipedia — Puget Sound mosquito fleet](https://en.wikipedia.org/wiki/Puget_Sound_mosquito_fleet) · [HistoryLink 8274 — Bainbridge Island (Winslow) thumbnail history](https://www.historylink.org/file/8274) · [Sandee — Dock St road end](https://sandee.com/united-states/washington/bainbridge-island/dock-st-road-end)
- Winslow / Hall Brothers: [HistoryLink 8274](https://www.historylink.org/file/8274) · [RevisitWA — Winslow](https://revisitwa.org/waypoint/winslow/) · [Wikipedia — Winslow Marine Railway and Shipbuilding Company](https://en.wikipedia.org/wiki/Winslow_Marine_Railway_and_Shipbuilding_Company)
- Agate Pass: [Wikipedia — Agate Pass](https://en.wikipedia.org/wiki/Agate_Pass) · [Wikipedia — Agate Pass Bridge](https://en.wikipedia.org/wiki/Agate_Pass_Bridge) · [HistoryLink 7136](https://historylink.org/File/7136)
- Point Monroe / Wilkes naming: [Wikipedia — Bainbridge Island](https://en.wikipedia.org/wiki/Bainbridge_Island,_Washington) · [Coastview — Hedley Spit](https://coastview.org/2023/10/07/hedley-spit-bainbridge-island/) · [HistoryLink 5226 — Charles Wilkes](https://www.historylink.org/file/5226)
- Fay Bainbridge Park: [Wikipedia — Fay Bainbridge Park](https://en.wikipedia.org/wiki/Fay_Bainbridge_Park) · [WWTA — Fay Bainbridge](https://www.wwta.org/sites/fay-bainbridge-sp/)
- Strawberry farming: [Bainbridge Review — The berry that loved Bainbridge](https://www.bainbridgereview.com/?p=41767) · [Bainbridge Review — Strawberry fest ripens anew](https://www.bainbridgereview.com/news/strawberry-fest-ripens-anew/) · [Northwest Asian Weekly — Bainbridge's Filipino community](https://nwasianweekly.com/2026/05/bainbridges-filipino-community-works-through-the-decades-to-help-create-preserve-community/) · [Densho — Island Roots](https://resourceguide.densho.org/Island%20Roots%20(film)/)
- Eagle Harbor / Wyckoff: [EPA — Wyckoff/Eagle Harbor site history](https://epa.gov/wyckoff-eagle-harbor/site-history) · [Wikipedia — Pritchard Park](https://en.wikipedia.org/wiki/Pritchard_Park)
- Lynwood Theatre: [Wikipedia — Lynwood Theatre](https://en.wikipedia.org/wiki/Lynwood_Theatre)
- Bloedel Reserve: [Bloedel Reserve — Plan your visit](https://bloedelreserve.org/visit)
- Grand Forest: [Bainbridge Island Land Trust — Grand Forest](https://www.bi-landtrust.org/grand-forest/)
- Pegasus building (excluded by default): [Pegasus Coffee — 131 Parfitt Way](https://pegasuscoffee.com/original-pegasus-coffee-house-bainbridge/) · [Bainbridge Review](https://www.bainbridgereview.com/?p=41711)
- Troll: [Smithsonian — Giant trolls in the Pacific Northwest](https://www.smithsonianmag.com/smart-news/see-the-giant-trolls-taking-over-the-pacific-northwest-180982875/) · [Gig Harbor Now — Troll on Bainbridge](https://www.gigharbornow.org/news/environment/day-tripper-troll-takes-up-residence-on-nearby-bainbridge-island)

Ferries, tides, rules
- [Wikipedia — Seattle–Bainbridge Island ferry](https://en.wikipedia.org/wiki/Seattle%E2%80%93Bainbridge_Island_ferry) · [Wikipedia — MV Tacoma](https://en.wikipedia.org/wiki/MV_Tacoma) · [Wikipedia — MV Wenatchee](https://en.wikipedia.org/wiki/MV_Wenatchee) · [WSF schedule booklet (crossing ≈ 35 min)](https://www.sanjuancountywa.gov/DocumentCenter/View/11487/2016-Spring)
- [33 CFR 83.34 — Rule 34(g), leaving a dock or berth](https://www.law.cornell.edu/cfr/text/33/83.34)
- [NOAA CO-OPS — Eagle Harbor, Bainbridge Island (9445882)](https://tidesandcurrents.noaa.gov/noaatidepredictions.html?id=9445882)

Creatures
- [Atlas Obscura — Giant Pacific octopus](https://www.atlasobscura.com/articles/giant-pacific-octopus-monster-tacoma) · [KOMO via WJLA — Giant Pacific octopus in Puget Sound](https://wjla.com/news/offbeat/octopus-seattle-scuba-dive-puget-sound-cute-animal-giant-pacific-ocean-des-moines-nate-paradiso-smithsonian-noaa) · [Britannica — Kraken](https://www.britannica.com/topic/kraken)
- [Bainbridge Review — Owl attacks on the rise round Bainbridge (2017)](https://www.bainbridgereview.com/2017/08/11/heads-up-owl-attacks-on-the-rise-round-bainbridge/)
- [Bainbridge Island Land Trust — 2022 (wildlife camera, coyotes)](https://www.bi-landtrust.org/2022/01/)

Repo data (coordinates and ids): `data/dist/trails.json`, `data/dist/trailheads.json`,
`data/dist/coverage_segments.json`, `data/roads/dist/roads.json`, `data/roads/dist/road_ends.json`,
`data/roads/dist/coverage_segments.json`, `app/composeApp/src/commonMain/composeResources/files/coastline.json`
(shore stretches computed at 60 m; chart-square counts computed on 0.01° × 0.015° cells anchored at 47.55 N,
122.62 W; `roadSafety` counts and quiet-zone geometry recomputed with `python3 -I` over `roads.json`, `trails.json`
and `coverage_segments.json`, 2026-10-20).
