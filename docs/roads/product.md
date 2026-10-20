# Bainbridge Roads — roads-mode product spec

Date: 2026-10-20 · Owner: Product Designer · Status: ready for build · Branch `roads/mode`
Precedence (from `decisions.md`): `docs/roads/decisions.md` > `docs/roads/ux.md` > **this file** > `plan.md`.
Everything not overridden here follows the trail spec `docs/mvp/product.md` (flows F0–F11, stats, rules, tone).
Visual layout, motion and component choice belong to `ux.md`; this file owns behavior, copy, math.

Conventions (in addition to `docs/mvp/product.md` conventions)
- "Road" = a row of `roads.json` (664 today). **Counted road** = a row with `counted == true` (430 today). Only
  counted roads are listed, completable, celebrated and ranked. Never hard-code any count; read from the dataset.
- **Counted segment** = segment with `included == true`. A counted road may contain uncounted (private/unknown)
  segments: 39 roads today ("partly public").
- Denominators: island total = `island_total_shippable_m` (162.5 mi today, D1). Copy that quotes the island total
  rounds **down** to a whole unit and says "over": `floor(total in unit)`.
- Category labels: `state_route` → State route, `arterial` → Arterial, `collector` → Collector, `residential` →
  Residential street, `lane` → Lane or alley. Today (counted): 2 state routes, 28 arterials, 55 collectors,
  345 residential.
- On foot only (RD-3): walk or run. Copy never says hike or bike.
- Placeholders are Compose Multiplatform syntax (`%1$s`, `%1$d`); a literal percent sign is a bare `%`.

---

## 1. Positioning and core loop

**Pitch:** "Walk every road on Bainbridge." The same app as Bainbridge Trails pointed at 162 miles of public road:
a multi-year completionist map, not a summer checklist. 2.5× the trail goal, so progress is measured in **miles
and roads**, with milestones along the way, not only an island percentage that moves 0.1 at a time.

Persona fit
- **P1 Island Completionist** (Dana) is the core user: walks the dog through her own neighbourhood every morning,
  wants to see every street of the island coloured in over a few years. Needs: what's left near me, did today
  count, a moment of recognition more often than once per finished road.
- **P3 Neighbourhood Runner** (new): runs 3–5 mi before work on roads, not trails. Wants the run to count, a
  number that goes up every run (miles of new road), and a nudge to take a different street.

Core loop (same six steps as trails; roads wording)
1. **See** — Map shows every road by type, coloured by your status; private roads are drawn but muted.
2. **Pick** — tap a road (map or Roads list) → detail → Start walk. Short streets are quick wins.
3. **Walk** — walk or run, screen off; live list of roads touched.
4. **Progress** — Finish → new road miles, roads moved, island %.
5. **Celebrate** — a road completes → full-screen moment; a mileage milestone → results banner.
6. **Compare** — signed in: progress syncs to the **roads** leaderboard (separate from trails).

Non-goals this version: cycling (RD-3, P2), walkability ratings (P4), neighbourhood/area milestones (no area data;
P2), routing to the nearest unwalked road, iOS build (RD-1).

---

## 2. Vocabulary — implementation

**Mechanism: explicit lookup via profile.** Compose Multiplatform resources have no flavour qualifier (qualifiers
are language/region/theme/density only) and Android flavour `res/` does not reach `commonMain` composeResources.
So:

1. New file `composeResources/values/strings_roads.xml`. Every override key = `roads_` + the trail key, **same
   arguments in the same order** (so call sites never branch on arguments). Roads-only keys (no trail
   counterpart) also use the `roads_` prefix.
2. `GoalVocabulary` (commonMain, `data/`), one instance per `DatasetProfile`:
   ```kotlin
   class GoalVocabulary(
       val profile: DatasetProfile,
       private val strings: Map<StringResource, StringResource>,
       private val plurals: Map<PluralStringResource, PluralStringResource>,
   ) {
       fun s(r: StringResource): StringResource = strings[r] ?: r
       fun p(r: PluralStringResource): PluralStringResource = plurals[r] ?: r
       companion object { fun of(p: DatasetProfile): GoalVocabulary = if (p == ROADS) Roads else Trails }
   }
   val LocalGoal = staticCompositionLocalOf { GoalVocabulary.Trails }
   // call site: Text(stringResource(LocalGoal.current.s(Res.string.map_search_hint)))
   ```
   `Roads` is a hand-written table `Res.string.x to Res.string.roads_x` (one line per row of § 3). Non-composable
   callers (view models building a11y announcements, `getString`) take the `GoalVocabulary` from `AppGraph`.
3. Android-only strings: launcher label via flavour `androidApp/src/roads/res/values/strings.xml`
   (`app_name` = Bainbridge Roads). `notif_*` need no change ("walk").
4. **Guard test** (commonTest/jvmTest, parses the XML): every key in `strings*.xml` whose text matches
   `/trail/i` must have a `roads_` twin with the same placeholder set, or be on an allowlist of trail-only screens
   (park filter, trailheads, official map). Also: every `roads_` twin is registered in `GoalVocabulary.Roads`.
   This catches a future trail string leaking into the roads build.
5. The trail build uses `GoalVocabulary.Trails` (identity) — zero behavior change for trails, except the two
   trail keys that RD-4 makes data-driven (§ 3.2).

The debug drawer's dataset switch picks the vocabulary with the profile (same relaunch rule).

---

## 3. Vocabulary map (trail key → roads text)

Keys not listed are unchanged in roads. "→ hidden" = the roads build never shows it.

### 3.1 Common, tabs, errors
| key | trails text | roads text (`roads_` + key) |
|---|---|---|
| app_name | Bainbridge Trails | Bainbridge Roads |
| common_loading_trails | Loading trails… | Loading roads… |
| map_loading_trails | Loading trails… | Loading roads… |
| nav_trails | Trails | Roads |
| trails_title | Trails | Roads |
| error_dataset_title | Trails didn't load | Roads didn't load |
| error_dataset_body | Something went wrong reading the trail data. Try again, or reinstall if it keeps happening. | Something went wrong reading the road data. Try again, or reinstall if it keeps happening. |
| settings_attribution_dataset | Trail data version %1$s | Road data version %1$s |

### 3.2 Onboarding (RD-4: data-driven denominator)
New shared keys (both builds): `goal_distance_mi` = `%1$d miles`, `goal_distance_km` = `%1$d km`. The app
formats `floor(island_total_shippable in unit)` with these and passes the result as `%1$s`. Onboarding runs
before settings, so it uses the default unit (miles).

| key | trails text | roads text |
|---|---|---|
| onboarding_1_title | Walk every trail on the island | Walk every road on the island |
| onboarding_1_body | Bainbridge has over 60 miles of public trails. See them all, pick one, and watch your map fill in. **(trail key changes to:** Bainbridge has over %1$s of public trails. See them all, pick one, and watch your map fill in.**)** | Bainbridge has over %1$s of public road. Pick a street, walk it, and watch the island fill in, one road at a time. |
| onboarding_2_body | Record a walk and the trails you cover light up. Finish a trail across as many walks as you like, in any direction. A trail counts as complete at %1$d%. | Walk or run, and the roads you cover light up. Finish a road across as many walks as you like, in any direction. A road counts as complete at %1$d%. |
| onboarding_3_body | We sync which trail sections you've covered — never your GPS track or walk times. | We sync which road sections you've covered — never your GPS track or walk times. |
| onboarding_privacy_body | Your GPS tracks never leave this phone. We sync which trail sections you've covered — never your GPS track or walk times. | Your GPS tracks never leave this phone. We sync which road sections you've covered — never your GPS track or walk times. |

Roads-only onboarding key, shown under `roads_onboarding_2_body`:
`roads_onboarding_2_milestones` = "It's a big island. You'll get a milestone every %1$s of new road along the way."
(`%1$s` = "5 mi" / "5 km", § 5.3.)

### 3.3 Map and legend
| key | trails text | roads text |
|---|---|---|
| map_search_hint | Find a trail | Find a road |
| map_a11y | Trail map. %1$d of %2$d trails complete. | Road map. %1$d of %2$d roads complete. |
| map_legend_footnote | Colors show how much of each stretch you've walked. | Colors show how much of each stretch you've walked. Every public road counts the same, whatever its type. |
| map_legend_not_counted_body | Shown on the map but not part of the island total (roads, informal paths). | Drawn so you can find your way, but not part of the island total. |
| map_preview_open | View trail | View road |
| map_preview_multi_title | Trails on this section | Roads on this section |

Roads-only legend keys (§ 6): `roads_legend_types` Road types · `roads_cat_state_route` State route ·
`roads_cat_arterial` Arterial · `roads_cat_collector` Collector · `roads_cat_residential` Residential street ·
`roads_cat_lane` Lane or alley · `roads_legend_private` Private road · `roads_legend_unknown` Not confirmed public ·
`roads_legend_private_body` Private roads aren't counted. Please respect No Trespassing signs. ·
`roads_legend_unknown_body` We couldn't confirm these are public, so they don't count yet. If they're confirmed, walks you've already done there will count.

### 3.4 Roads list
| key | trails text | roads text |
|---|---|---|
| trails_search_hint | Search trails or parks | Search roads |
| trails_count | %1$d of %2$d trails | %1$d of %2$d roads |
| trails_empty_title | No trails match | No roads match |
| trails_empty_search_title | No trails called "%1$s" | No roads called "%1$s" |
| trails_first_walk_banner | Pick a trail and tap Start walk. Progress shows up here after your first walk. | Pick a road and tap Start walk. Short streets are quick wins. Progress shows up here after your first walk. |
| trails_filter_park, _all, _none, trails_park_sheet_title | Park… | → hidden (replaced by type filter) |
| trails_sort_length | Length | Shortest first |

Roads-only list keys: `roads_filter_type` Road type · `roads_filter_type_all` All types ·
`roads_type_sheet_title` Filter by road type · `roads_row_partly_public` Partly private ·
`roads_cat_*_plural` State routes / Arterials / Collectors / Residential streets (chip labels).

### 3.5 Road detail
| key | trails text | roads text |
|---|---|---|
| detail_gain | Elevation gain | → hidden; use `roads_detail_climb` |
| detail_managed_by | Managed by %1$s | Maintained by %1$s |
| detail_trailheads, detail_trailhead_row | Trailheads (%1$d)… | → hidden; use road-end keys |
| detail_official_map, _host | Official trail map | → hidden |
| detail_community_completed (plural) | %1$d person has / people have completed this trail | one: %1$d person has walked this whole road · other: %1$d people have walked this whole road |
| detail_community_few | Be one of the first to finish this trail | Be one of the first to finish this road |
| detail_partly_counted | Only part of this trail counts toward your island % | Only the public part of this road counts. Progress is measured on that part. |
| detail_not_found | Trail not found | Road not found |

Roads-only detail keys: `roads_detail_type` Type · `roads_detail_climb` Climb ·
`roads_detail_climb_info` Total up and down along the road, either direction. · `roads_detail_flat` Flat ·
`roads_detail_surface` Surface · `roads_surface_paved` Paved · `roads_surface_gravel` Gravel ·
`roads_surface_dirt` Dirt · `roads_surface_unpaved` Unpaved · `roads_detail_speed` Speed limit ·
`roads_detail_speed_value` %1$d mph · `roads_detail_public_length` Public part: %1$s of %2$s ·
`roads_owner_city` City of Bainbridge Island · `roads_owner_state` Washington State (WSDOT) ·
`roads_owner_county` Kitsap County · `roads_owner_parks` Bainbridge Island Parks & Trails District ·
`roads_owner_school` Bainbridge Island School District ·
`roads_reason_private` Private road. It isn't part of the island total. Please respect No Trespassing signs. ·
`roads_reason_unknown` We couldn't confirm this road is public, so it doesn't count yet. If it's confirmed, walks you've already done here will count. ·
`roads_reason_lane` Lanes and alleys aren't part of the island total. ·
`roads_caution_fast` Fast traffic (%1$d mph). Walk facing traffic and keep to the shoulder. ·
`roads_caution_state_route` State highway. Walk facing traffic and keep to the shoulder. ·
`roads_detail_road_end` Road end at the water · `roads_detail_road_end_body` Public shore access at the end of this road. ·
`detail_directions`, `detail_directions_a11y` reused unchanged.

### 3.6 Record and live
| key | trails text | roads text |
|---|---|---|
| record_idle_what | Your walk is matched to mapped trails. Only the trail sections you cover count. | Walk or run along public roads. Your route is matched to mapped roads; only the road sections you cover count. Bikes and cars don't count. |
| record_live_header | Trails on this walk | Roads on this walk |
| record_live_header_count | Trails on this walk (%1$d) | Roads on this walk (%1$d) |
| record_live_empty | No trails yet. Head onto a trail and it'll show up here. | No roads yet. Head along a public road and it'll show up here. |
| record_live_expand_a11y | Show all trails on this walk | Show all roads on this walk |
| record_live_collapse_a11y | Show fewer trails | Show fewer roads |
| record_finish_body | We'll match it to trails and save it. | We'll match it to roads and save it. |
| record_matching | Matching your walk to trails… | Matching your walk to roads… |
| record_loading_failed | Trail data didn't load. | Road data didn't load. |

### 3.7 Permissions
| key | trails text | roads text |
|---|---|---|
| perm_location_body | Used only while you record a walk, to match your steps to trails. Your track stays on this phone. | Used only while you record a walk, to match your steps to roads. Your track stays on this phone. |
| perm_precise_body | Trails here are close together, so we need precise location to tell them apart. | Roads here run close to each other and to trails, so we need precise location to tell them apart. |

`perm_activity_body` ("Helps keep bike and car rides from counting") is unchanged and matches RD-3.

### 3.8 Results
| key | trails text | roads text |
|---|---|---|
| results_trails_header | Trails | Roads |
| results_no_progress | No new trail progress this walk. | No new road progress this walk. |
| results_no_progress_hint | Only mapped public trails count. Walks on roads or already-covered sections don't add progress. | Only public roads count. Trails, private roads and stretches you've already covered don't add progress. |
| results_approx_warning | …so some trails may not have matched… | Recorded with approximate location, so some roads may not have matched. Turn on precise location in Settings. |

Roads-only: `roads_results_new_miles` = "+%1$s of new road" (headline, § 5.2) ·
`roads_results_milestone_eyebrow` Milestone.

### 3.9 Celebration (+ screen reader)
| key | trails text | roads text |
|---|---|---|
| celebrate_title_multi (plural) | %1$d trail(s) complete! | one: %1$d road complete! · other: %1$d roads complete! |
| celebrate_body | You've walked %1$s of the island and finished %2$d of %3$d trails. | You've walked %1$s of the island's roads and finished %2$d of %3$d. |
| celebrate_view_trail | View trail | View road |
| celebrate_eyebrow | Trail complete | Road complete |
| celebrate_eyebrow_multi (plural) | %1$d trail(s) complete | one: %1$d road complete · other: %1$d roads complete |
| celebrate_trails_count | %1$d of %2$d trails complete | %1$d of %2$d roads complete |
| celebrate_community (plural) | …to finish it. | unchanged |
| celebrate_a11y_announcement | Trail complete: %1$s. Island progress %2$s. %3$d of %4$d trails finished. | Road complete: %1$s. Island progress %2$s. %3$d of %4$d roads finished. |
| celebrate_a11y_announcement_multi | %1$d trails complete: %2$s. Island progress %3$s. | %1$d roads complete: %2$s. Island progress %3$s. |
| celebrate_island_title | You've walked the whole island | You've walked every road on the island |
| celebrate_island_body | Every counted trail on Bainbridge, done. Take a bow. | Every public road on Bainbridge, done, on foot. Take a bow. |
| celebrate_island_eyebrow | Island complete | Every road |
| celebrate_island_a11y | You've walked the whole island. Every counted trail on Bainbridge, done. Island progress %1$s. %2$d of %3$d trails finished. | You've walked every road on the island. Every public road on Bainbridge, done. Island progress %1$s. %2$d of %3$d roads finished. |

Milestone keys (roads-only, § 5): `roads_milestone_eyebrow` Milestone ·
`roads_milestone_miles` %1$s of road walked · `roads_milestone_miles_body` That's %1$s of the island. Next milestone at %2$s. ·
`roads_milestone_roads` (plural) one: Your first road · other: %1$d roads complete ·
`roads_milestone_cat_state_route` Every state route, done · `roads_milestone_cat_arterial` Every arterial, done ·
`roads_milestone_cat_collector` Every collector, done · `roads_milestone_cat_residential` Every residential street, done ·
`roads_milestone_a11y` Milestone: %1$s.

### 3.10 Me
| key | trails text | roads text |
|---|---|---|
| me_hero_caption | of the island's trails walked | of public road walked |
| me_hero_a11y | Island map of your trails: %1$s walked, %2$d of %3$d trails complete | Island map of your roads: %1$s walked, %2$d of %3$d roads complete |
| me_stat_trails | Trails completed | Roads completed |
| me_stat_miles | Trail miles walked | Road miles walked |
| me_stat_km | Trail km walked | Road km walked |
| me_stat_miles_phone | Trail miles on this phone | Road miles on this phone |
| me_stat_km_phone | Trail km on this phone | Road km on this phone |
| stat_island_info | Share of the island's counted trail length you've covered. Walking the same section twice counts once. | Public road you've covered, out of %1$s on the island. Walking the same stretch twice counts once. |
| stat_trails_info | Named trails you've covered at least %1$d% of. | Public roads you've covered at least %1$d% of. On partly private roads only the public part counts. |
| stat_miles_info | Distance you've walked on mapped trails and paths, across all walks on this phone. Repeats count. | Distance you've walked on mapped roads, across all walks on this phone. Repeats count. |
| me_in_progress_empty | Trails you've started will show here. | Roads you've started will show here. |
| me_history_trails (plural) | No trails / 1 trail / %1$d trails | No roads / 1 road / %1$d roads |
| me_leaderboard_row_unranked | Not on the board yet. Walk a trail to get ranked. | Not on the board yet. Walk a road to get ranked. |
| walk_no_track_note | …The trails this walk added to are outlined. | Routes are deleted from this phone after 30 days. The roads this walk added to are outlined. |

Note: `roads_stat_island_info` adds `%1$s` (island total, e.g. "162.5 mi") — the only override with a different
argument list; its call site passes the total in both builds (trail text ignores it — Compose allows unused args).

Roads-only Me keys: `roads_me_hero_pct` %1$s of the island · `roads_me_next_milestone` Next milestone: %1$s · %2$s to go ·
`roads_me_by_type` By road type · `roads_me_type_row` %1$d of %2$d ·
`roads_me_type_row_a11y` %1$s: %2$d of %3$d roads complete, %4$s walked.

### 3.11 Leaderboard, account, privacy
| key | trails text | roads text |
|---|---|---|
| board_subtitle | Ranked by share of the island walked. Ties go to more trails completed. | Ranked by share of the island's roads walked. Ties go to more roads completed. |
| board_col_trails | Trails | Roads |
| board_row_a11y (plural) | Rank %1$d, %2$s, %3$s of the island, %4$d trail(s) completed | Rank %1$d, %2$s, %3$s of the island's roads, %4$d road(s) completed |
| board_row_you_a11y (plural) | You. Rank %1$d, %2$s of the island, %3$d trail(s) completed | You. Rank %1$d, %2$s of the island's roads, %3$d road(s) completed |
| board_empty_body | No one's on it yet. Walk a trail and sign in to claim the top spot. | No one's on it yet. Walk a road and sign in to claim the top spot. |
| board_not_ranked | Walk any counted trail to appear on the board. | Walk any public road to appear on the board. |
| board_signed_out_prompt | …island % and trails completed are shown. | Sign in to appear on the board. Only your name, island % and roads completed are shown. |
| board_signed_out_body | …island % and trails completed are shown. | Sign in to add your progress. Only your name, island % and roads completed are shown. |
| profile_privacy_note | We sync which trail sections… | We sync which road sections you've covered — never your GPS track or walk times. Your public name is a random one unless you choose your own. |
| privacy_lead | We sync which trail sections… | We sync which road sections you've covered — never your GPS track or walk times. |
| privacy_account_body | …trail sections… per 5 m of trail… trails completed… | We store your account id, your public name, and which road sections you've covered (as on/off flags per 5 m of road). From those we compute your island % and roads completed for the leaderboard. |
| privacy_board_body | …trails completed… | Others see only your name, island % and roads completed — never your GPS track or walk times. Use a random name if you prefer. |

Roads-only: `roads_board_separate_note` "This board is for roads. Trails have their own board in Bainbridge Trails."
(shown once under the subtitle).

Notifications (`notif_*`), sync, name, report, delete and settings-style strings: unchanged.

---

## 4. Road detail (F4 for roads)

Data: `TrailMeta` gains optional fields read from `roads.json` (all default null/true so trails parse unchanged):
`counted`, `category`, `ownership`, `surface`, `speed_mph`, `maintainer`, `climb_total_m`. Road ends join by
`road_id` from `road_ends.json`.

Order, top to bottom (hidden rows leave no gap or header):
1. **Mini map** (as trails): road highlighted, others dimmed. A road end, if any, shows as a pin.
2. **Header:** name; under it `Type · Owner` (e.g. "Collector · City of Bainbridge Island"). Owner from
   `ownership` via `roads_owner_*` (WSDOT row overrides with `maintainer == "WSDOT"`). `maintainer` text itself
   is not shown (inconsistent values in data). Status chip + big %. **Uncounted roads:** chip `status_not_counted`,
   no %, no progress bar.
3. **Count status line** (always one line, text not colour):
   - counted, all segments counted → `detail_counts`
   - counted, partly private → `roads_detail_partly_counted` + `roads_detail_public_length` ("Public part: 0.3 of 0.6 mi")
   - `ownership == private` → `roads_reason_private`
   - `ownership == unknown` → `roads_reason_unknown`
   - `category == lane` (any ownership) → `roads_reason_lane`
4. **Safety line** (only when true): `category == state_route` → `roads_caution_state_route`; else
   `speed_mph >= 40` → `roads_caution_fast`.
5. **Facts grid** (each tile hidden when its value is null):
   - Length — counted length for counted roads (Σ included segment lengths), full length for uncounted.
   - Covered — `detail_covered_value` over the same length.
   - Climb — `climb_total_m` in ft/m; `< 3 m` shows `roads_detail_flat`; info icon `roads_detail_climb_info`.
   - Speed limit — `roads_detail_speed_value` (433 of 664 roads have none → hidden).
   - Surface — `roads_surface_*` (457 have none → hidden). Never show "—".
6. **Road end** (21 roads): row `roads_detail_road_end` + body + `detail_directions` (geo URI to the point).
   `city_confirmed` is not shown.
7. **Community** (counted roads only; online): same rules and thresholds as trails (count < 5 hidden, median
   < 3 hidden).
8. **Primary button** `detail_start_walk` / `detail_go_to_recording`. **Uncounted roads: no button.**

Hidden for roads always: park, trailheads, official map link, `detail_length_official_note`, gain/loss split,
`agency_only_length_mi`.

AC
- RF4-1 Given a counted road with null surface and speed, then those tiles are absent and no "—" appears.
- RF4-2 Given a private road, then the chip says Not counted, `roads_reason_private` shows, and there is no
  Start walk button or community section.
- RF4-3 Given a partly private road (e.g. Agatewood Road NE), then Length and % use only its counted segments and
  `roads_detail_public_length` shows both lengths.
- RF4-4 Given State Highway 305, then `roads_caution_state_route` shows.
- RF4-5 Given a road with a road end, then the road-end row shows with Directions; tapping opens maps.

---

## 5. Retention (RD-6)

### 5.1 Stats definitions (roads)
| stat | definition | where |
|---|---|---|
| **Road miles covered** (primary) | `coveredM` from `ProgressSnapshot` (Σ covered metres over included segments, shared segments once), in units | Me hero, results headline, milestones |
| Island % | coveredM ÷ `island_total_shippable_m` (unchanged math) | Me hero secondary, results, board, celebration |
| Road fraction | covered ÷ length over the road's **counted** segments only (all segments if none counted) | list, detail, map preview, live |
| **Roads completed** | counted roads with fraction ≥ `completionThreshold`, ∪ sticky set (TL-7). Denominator = number of counted roads | Me, board, celebration, map a11y |
| Roads by type | roads completed per category / counted roads per category | Me "By road type" |
| Road miles on this phone | Σ per walk of distinct matched ticks on **road** segments (`rseg-` prefix, counted or not) × tick; trail competitor segments (`seg-`) excluded | Me tile |
| Walks on this phone | unchanged | Me tile |

Engine changes these imply (all small, gated on profile so trails are unchanged): road fraction over included
segments; `trailsCompleted` and `newlyCompleted` filtered to `counted`; `matchedM` filtered by
`segmentIdPrefix`. No new persistence.

### 5.2 Me and Results layout content
- **Me hero:** big number = road miles covered ("12.4 mi"), caption `roads_me_hero_caption` ("of public road
  walked"), secondary `roads_me_hero_pct` ("7.6% of the island"). Rationale: a 0.5 mi walk shows "0.4 mi", not
  "0.1%".
- Tiles: Roads completed `x / N` · Road miles on this phone · Walks on this phone.
- **Next milestone line** under tiles: `roads_me_next_milestone` with the next mileage step and distance to go
  ("Next milestone: 15 mi · 2.6 mi to go").
- **By road type:** four rows (state route, arterial, collector, residential), each `x of N` + thin bar
  (fraction of roads complete). Tap a row → Roads list filtered to that type.
- In progress, history, leaderboard row: as trails.
- **Results headline:** `roads_results_new_miles` ("+1.3 mi of new road") above `results_island`. Zero new
  coverage → existing no-progress copy.

### 5.3 Milestones (derived from each walk's `SessionSummary`; nothing stored)
Inputs per finished walk: `islandBefore`, `islandAfter` (→ miles before/after = fraction × island total, in the
user's unit at finish time), `newlyCompleted` (counted only), the post-walk snapshot.

| id | fires when | copy |
|---|---|---|
| M-miles | `floor(step(before)) < floor(step(after))` on the ladder **1, 5, 10, 15, 20, …** (unit = user's mi/km); if several crossed, the highest | `roads_milestone_miles` ("10 mi of road walked") + `_body` |
| M-roads | completed-before = completed-after − newly; crosses **1, 10, 25, 50, 100, 150, 200, 250, 300, 350, 400** | `roads_milestone_roads` (plural; 1 → "Your first road") |
| M-type | a newly completed road's category now has every counted road complete | `roads_milestone_cat_*` |
| island | existing `islandReached` | existing `celebrate_island_*` (roads text) |

Which surface:
- **A road completed or island complete** → the existing full-screen celebration (one screen, D15). Up to
  **2 milestone lines** appear under the body, priority island > M-type > M-roads > M-miles.
- **No road completed, M-miles crossed** → **results-sheet milestone banner** (eyebrow `roads_milestone_eyebrow`,
  title + body; light motion per ux.md; reduced-motion = static). No modal.
- Screen reader: each shown milestone appends `roads_milestone_a11y` to the announcement.
- Monotonic coverage means a milestone cannot fire twice for the same unit. Switching units may surface a
  different-unit milestone later; accepted. Coverage arriving by sync never fires milestones (no walk).

Expected cadence: ~1 mi of new road per typical walk → M-miles on walk 1, then about every 4–5 walks; 113 counted
roads are under 0.1 mi, so "Your first road" and "10 roads complete" come in the first weeks.

---

## 6. Map: counted, private, unknown

| what the user sees | data | meaning in copy |
|---|---|---|
| Road in type weight (state route heaviest → residential), status colours on top | included segments | Counts. Every metre counts the same whatever the type. |
| Muted dashes | `ownership == private` | `roads_legend_private`: drawn so you can find your way; never counted; respect signs. |
| Muted dots | `ownership == unknown` | `roads_legend_unknown`: may count later; walks already done there will count if confirmed (coverage is kept on those segments). |
| Hairline | `category == lane` (public or not) | `roads_cat_lane`, not counted. |
| Not drawn as progress | trail competitor segments | Trails don't count here (they count in Bainbridge Trails). |

Legend sheet order: 4 statuses (+ Not counted) as trails, then "Road types" (4 swatches), then Private, Not
confirmed public, Lane or alley, footnote `roads_map_legend_footnote`.

Tap behavior: tap a counted road → preview (name, `Type · length`, status chip + %, `map_preview_open`). Tap an
uncounted road → preview with name, `status_not_counted` and the one-line reason; `map_preview_open` still opens
the detail (no Start walk there). Segment shared by several roads → `map_preview_multi_title` list.

---

## 7. Roads list (F3 for roads)

1. Top: leaderboard card (as trails), search `roads_trails_search_hint` ("Search roads").
2. **Shows counted roads only** (430). Uncounted roads are reachable from the map only (no trespass prompts in a
   to-do list).
3. Filters: **Status** (multi, as trails) · **Road type** (single-select sheet `roads_type_sheet_title`: All
   types, State routes, Arterials, Collectors, Residential streets). No park filter.
4. Sort: **Name A–Z** (default) · **Progress** (high→low, ties by name) · **Shortest first**
   (`roads_trails_sort_length`; quick wins). No grouping headers: type is a filter, not a section (345 of 430 are
   residential, so sections would be one giant group).
5. Row: name (1 line) · second line `Type · length` (counted length; + `roads_row_partly_public` when partly
   private) · status chip with %. Duplicate names (10 today) are told apart by type/length and the detail map.
6. Search: case-, accent- and **abbreviation-insensitive** token-prefix match on name. Normalise both sides:
   Northeast↔NE, Northwest↔NW, Southeast↔SE, Southwest↔SW, North/South/East/West↔N/S/E/W, Road↔Rd,
   Avenue↔Ave, Street↔St, Drive↔Dr, Lane↔Ln, Place↔Pl, Court↔Ct, Boulevard↔Blvd, Way↔Wy, Highway↔Hwy;
   "SR 305"/"305" matches State Highway 305. Debounce 150 ms.
7. Count line `roads_trails_count` + complete count (`trails_count_complete`). Empty/no-progress states as
   trails with roads copy.

AC
- RF3-1 Given the roads dataset, then the list count equals rows with `counted == true` and no private road
  appears.
- RF3-2 Given search "madison ave ne", then North Madison Avenue Northeast appears.
- RF3-3 Given type = Arterials, then only arterial rows show and the count line updates.
- RF3-4 Given sort Shortest first, then rows are ordered by counted length ascending, ties by name.

---

## 8. Leaderboard and community (RD-7)

- **Separate board**, `dataset=roads` on every board/aggregate/community request. Roads progress never appears
  on the trails board and vice versa. A user signed into both apps with one account has one row per board.
- **Same rules** as `docs/mvp/product.md` § 7: eligible = signed in, not deleted/hidden, island % > 0; order
  island % desc → roads completed desc → `reached_at` asc → user id; unique ranks; top 100 + you; daily snapshot
  for others, own row live (TL-11).
- Columns: # · Name · Island (%, 1 decimal) · Roads (`roads_board_col_trails`).
- Display name, hide-me and reports are **per account** (shared across both boards): one identity.
- **Community stats** per road (keyed by `road-*` id, already distinct from trail ids): completed count (hidden
  < 5) and median (hidden < 3 walkers), as trails. Only counted roads have community stats.
- Sync: `PUT /v1/coverage` carries `dataset=roads`; plausibility rule (4.0 m/s × duration + 500 m) unchanged —
  it matches RD-3 (on foot). Running at ~3.5 m/s fits under it.

---

## 9. Acceptance criteria — roads build end to end

Build: `roadsDebug` (`com.bainbridgetrails.roads.dev`) on the Pixel; replay via the debug drawer's simulate-walk
on a recorded road track.

- **E1 Install.** Given a fresh install of the roads build, when it launches, then the launcher label and
  `app_name` read "Bainbridge Roads" and onboarding page 1 shows.
- **E2 Onboarding.** Given onboarding, then page 1 reads "Walk every road on the island" and its body says
  "over 162 miles" computed from `island_total_shippable_m` (changing the dataset total to 200 km makes it
  "over 124 miles"); page 2 shows the completion threshold from params and the 5 mi milestone line; no screen
  in onboarding contains the word "trail".
- **E3 Map.** Given onboarding done, when the Map opens, then the search says "Find a road", the bottom tab says
  "Roads", the legend lists Road types, Private road and Not confirmed public, and no permission prompt appears.
- **E4 Uncounted tap.** Given a private road on the map, when tapped, then the preview shows Not counted and its
  reason; its detail has no Start walk.
- **E5 Road detail.** Given a counted road opened from the Roads list, then the detail shows Type · Owner,
  Length, Covered, Climb (or Flat), and Speed/Surface only when present; no Park, Trailheads, Elevation gain or
  Official map rows.
- **E6 Start walk.** Given Start walk on the road detail and the permission flow completes, then recording
  starts and that road is listed first under "Roads on this walk" at 0%.
- **E7 Live.** Given a replayed 0.5 mi walk along that road, then within 60 s of first matched movement the live
  list shows the road with % > 0 and the footnote "Estimate…"; no trail beside the road appears and no
  parallel road 100 m away gains progress.
- **E8 Results.** Given Finish → confirm, then the results sheet headline reads "+0.4 mi of new road" (± rounding),
  the island line shows before → after %, the road row shows before → after % with status chip, and the walk's
  first-mile crossing (if ≥ 1 mi) shows the milestone banner; otherwise no banner.
- **E9 Road complete.** Given a replay that takes a counted road from 0.80 to ≥ 0.85, then the full-screen
  celebration shows "Road complete" with the road name, "x of N roads complete" (N = counted roads), and — on
  the first ever completion — the milestone line "Your first road"; TalkBack announces
  `roads_celebrate_a11y_announcement` followed by "Milestone: Your first road."
- **E10 Trail walk doesn't count.** Given a replay along a trail that runs beside Battle Point Drive NE, then
  results show "No new road progress this walk." (≤ 2 % of walked length credited, RD-2b).
- **E11 Me.** Given the walks above, then Me shows the miles hero ("of public road walked") with island % under
  it, Roads completed `x / N` (N = counted roads, not 664), Road miles on this phone, Walks, a Next milestone
  line, and By road type rows whose counts sum to Roads completed.
- **E12 Milestones.** Given covered miles 4.8 and a walk adding 0.4 mi with no road completed, then the results
  banner shows "5 mi of road walked"; given the next walk adds 0.3 mi, then no banner shows.
- **E13 Type milestone.** Given every counted state route but one complete, when the last completes, then the
  celebration includes "Every state route, done".
- **E14 Board.** Given signed in, then the leaderboard subtitle and Roads column show, the request carries
  `dataset=roads`, and the user's trails standing is not shown.
- **E15 No trail words.** Given the guard test, then it passes: every `/trail/i` string has a `roads_` twin or is
  allowlisted, and every twin is registered in `GoalVocabulary.Roads`.
- **E16 Trails unchanged.** Given the trails build, then all trail screens render their current text except
  `onboarding_1_body`, which now reads "over 65 miles" from data.

---

## 10. Decisions log

| # | decision | rationale |
|---|---|---|
| RP-1 | Explicit `GoalVocabulary` lookup over a parallel `strings_roads.xml` with `roads_` keys, same args; guard test on `/trail/i` | CMP resources have no flavour qualifier; Android flavour res doesn't reach commonMain; explicit lookup keeps the debug runtime switch; the test stops leaks. |
| RP-2 | App name "Bainbridge Roads"; tab "Roads" | Plain, parallel to Trails; RD-1 is a second app. |
| RP-3 | Only counted roads are listed, completable, celebrated, ranked; N = counted roads (430), not 664 | Prototype showed "0 / 664": private roads in a to-do list invite trespass and inflate N. |
| RP-4 | Road fraction measured over its counted segments only (partly private roads) | 39 roads have private stretches; completion must never require walking private land. |
| RP-5 | Uncounted roads stay tappable on the map with a reason; no Start walk; no progress shown | Users need to know why a road they see doesn't count; distinct copy for private vs unknown vs lane. |
| RP-6 | Unknown-ownership roads: "doesn't count yet", coverage kept | Segments are scored, so confirming them later credits past walks honestly. |
| RP-7 | Primary stat = road miles covered; island % secondary | 0.5 mi walk = "0.4 mi" vs "0.1%"; same math as island %, just a felt unit (RD-6). |
| RP-8 | Milestones: mileage 1, then every 5 (unit-aware); roads 1/10/25/50/100…/400; all-of-a-type; derived from `SessionSummary`, nothing persisted | Engine already gives before/after + newlyCompleted; buildable in a day; fires every few walks. |
| RP-9 | Mileage-only milestone → results banner, not a modal; with a completion → extra lines on the one celebration (max 2) | Keeps D15 (one celebration) and avoids modal fatigue on a multi-year goal. |
| RP-10 | No neighbourhood/area milestones this version | No area polygons in data; P2. |
| RP-11 | Type is a list filter, not grouping | 345 of 430 counted roads are residential; groups would be lopsided. |
| RP-12 | "Shortest first" sort label for Length | 113 counted roads < 0.1 mi: quick wins drive early retention. |
| RP-13 | Abbreviation-insensitive search | Data spells "Northeast … Road"; people type "NE … Rd". |
| RP-14 | Owner shown from `ownership` enum, not `maintainer` | `maintainer` mixes "City of Bainbridge Island"/"Bainbridge Island"/null. |
| RP-15 | Null surface/speed tiles hidden; climb as total up+down with "Flat" under 3 m | Data is thin (surface 31 %, speed 35 % of rows); "—" read as broken in the prototype. |
| RP-16 | Safety line for state routes and ≥ 40 mph | SR 305 at 50 mph is in the goal; one honest line, no rating (P4). |
| RP-17 | Road ends shown on detail with Directions; `city_confirmed` hidden | 21 shore access points are a reason to visit; confirmation flag not reliable in dist yet. |
| RP-18 | "Road miles on this phone" counts road segments only (`rseg-`), excludes trail competitors | Trail competitors (RD-2b) are mapped segments; without the filter a trail walk would inflate "road miles". |
| RP-19 | Separate roads board, same rules; one identity (name/hide/report) across boards | RD-7; two identities would double moderation. |
| RP-20 | Trail `onboarding_1_body` also becomes data-driven ("over %1$s") | RD-4 applies to both builds; supersedes trail D23's fixed "60". |
| RP-21 | Copy says "walk or run", never "hike" or "bike" | RD-3 on foot only; matches anti-cheat. |
