# Trails edition v1.1 — content pack 2026.11.3

Lane L4 (content author) for [`brief.md`](brief.md) § 2 Content, AC 1 and AC 5. Facts come only from
[`lore-facts.md`](lore-facts.md), hedged as written there; placements are dataset refs the validator resolves
(`along: trail:<id>` seeded points, `place:` entries whose coordinates are dataset trailheads or trail midpoints).
Every item is `goals: ["trails"]`, `when: { every: "always" }`, `notBefore: 2026-10-26`, `repeat: once`.

## New items (18: 6 eggs, 6 riddles, 6 lore; 13 distinct parks/areas)

| id | type | area (dataset park) | anchor | marks | enabled / verify | source |
|---|---|---|---|---|---|---|
| `egg-strawberry` | egg | Moritani Preserve | along `trail-hayano-trail` | 20 | on | Wikipedia (island), bijac.org ?p=1143, Bainbridge Review ?p=68802 |
| `egg-fivemasts` | egg | Blakely Harbor Park | along `trail-fort-ward-to-blakely-harbor-trail` | 20 | on | bi-landtrust Blakely Harbor, Wikipedia Port Blakely |
| `egg-toejam` | egg, canopy 25 m | Nute's Pond (Toe Jam Hill) | along `trail-nutes-trail` | 20 | on | Wikipedia Toe Jam Hill |
| `egg-hilltop` | egg, canopy 25 m | Grand Forest | along `trail-hilltop-trail` | 20 | on | bi-landtrust Grand Forest, Bainbridge Review 2014-01-24 |
| `egg-coastline` | egg | Hawley Cove Park | along `trail-hawley-cove-trail` | 20 | on | Wikipedia (island) |
| `egg-chillyhilly` | egg | Sunny Hill Trail (no park, by Toe Jam Hill) | along `trail-sunny-hill-trail` | 20 | on | cascade.org Chilly Hilly 2026 |
| `riddle-roof` | riddle ★★ | Nute's Pond (Toe Jam Hill) | marker along `trail-nutes-pond-trails`; answer `pool:nutes-pond-trails` (= park:Nute's Pond), 100 m | 80 | on | Wikipedia Toe Jam Hill |
| `riddle-steep` | riddle ★★★ | Gazzam Lake Preserve | along `trail-peters-trail`; answer `trail:trail-peters-trail`, 100 m | 120 | on | dataset (gain_m 92.1), bi-landtrust Gazzam |
| `riddle-close` | riddle ★★ | Gazzam Lake Preserve | along `trail-close-trail`; answer `trail:trail-close-trail`, 100 m | 80 | on | WTA Gazzam Lake – Close Beach |
| `riddle-manzanita` | riddle ★ | Manzanita Park | along `pool:manzanita-trails` (= park:Manzanita Park); same answer, 100 m | 40 | on | WTA Manzanita Trail |
| `riddle-gift` | riddle ★★ | Ted Olson Nature Preserve | along `pool:ted-olson-trails` (= park); same answer, 100 m | 80 | on | bi-landtrust Ted Olson |
| `riddle-dock` | riddle ★ | Hidden Cove (trails have no park in the dataset) | marker along `trail-hidden-cove-loop-trail`; answer `trail:trail-hidden-cove-trails`, 100 m | 40 | on | Bainbridge Review ?p=42232, Wikipedia Port Madison |
| `lore-gazzam` | lore | Gazzam Lake Preserve | along `trail-gazzam-lake-main-trail`, 60 m | 15 | on | bi-landtrust Gazzam, WTA, HistoryLink 22902 |
| `lore-fortward-navy` | lore | Fort Ward Park | `place:fort-ward-south` (= trailhead th-b40243), 60 m | 15 | on | HistoryLink 9699, 9648, Wikipedia Fort Ward |
| `lore-hallbrothers` | lore | Blakely Harbor Park | `place:blakely-park-trailhead` (= trailhead th-23141f), 60 m | 15 | on | bi-landtrust Blakely Harbor, Wikipedia Port Blakely |
| `lore-moritani` | lore (armchair) | Moritani Preserve | none (view only, stamp) | 0 | on | Bainbridge Review ?p=68802, bijac.org ?p=1143 |
| `lore-battlepoint` | lore | Battle Point Park | along `trail-battle-point`, 60 m | 15 | on | bpastro.org/about, Bainbridge Review ?p=118963 |
| `lore-sakai` | lore | Sakai Park | `place:sakai-loop` (= midpoint of `trail-north-loop-trail`), 60 m | 15 | **off**, place `verify: true` | Bainbridge Review ?p=31487, bijac.org ?p=1233 (secondary only) |

Areas covered: Gazzam Lake Preserve, Grand Forest, Fort Ward Park, Blakely Harbor Park, Battle Point Park, Hidden
Cove, Moritani Preserve, Sakai Park, Ted Olson Nature Preserve, Manzanita Park, Nute's Pond / Toe Jam Hill, Sunny
Hill, Hawley Cove Park.

Also edited: `lore-helix` (disabled draft) copy now ends "…built an observatory on it, now the Battle Point
Observatory (renamed in June 2025)." Copy is not an immutable field (schedule.md § 4.3; the validator's
`IMMUTABLE` list is type/when/where/count/marks/tiers/bands/stars/fx). Note: the pack's `lore-helix` never said
"Edwin E. Ritchie Observatory" as `lore-facts.md` claims; only the current name was missing.

New pack objects: places `fort-ward-south`, `blakely-park-trailhead`, `sakai-loop` (verify); pools
`manzanita-trails`, `ted-olson-trails`, `nutes-pond-trails` (park members, the `grand-forest-trails` pattern).

Every `_note` names the `lore-facts.md` row and the placement reasoning. `fx`: riddles `riddle_marker`; eggs and
lore carry no fx (as the existing eggs and non-frog lore).

## Derived tables (`data/play/derived/trails.json`)

`scripts/build-play-content.mjs` `derive()` now writes, for the trails goal only:
- `featurePark`: `{ "<trail-id>": "<park>" }` for the 58 trails with a park (Pritchard Park included as data;
  consumers apply `excludedParks`), sorted by id;
- `featureGainM`: `{ "<trail-id>": gain_m }` for all 114 trails (0 when the dataset has none);
- `parks`: the 19 Play parks (sorted, `excludedParks` removed).

`derived/roads.json` is unchanged except its `pack` stamp (`2026.11.1` → `2026.11.2`), which the writer stamps
on both goals; the 2026.11.1 bump changed the same single line. Unit tests: `derived trails table carries
featurePark, featureGainM and the Play parks` (fixture) and `derives 19 Play parks and Peters Trail 92.1 m`
(real data, skipped in CI).

## 4-week preview

See [`agenda-preview.md`](agenda-preview.md). All new items are permanent, so the dated agenda (kraken Thursdays,
weekend sweep, Monday treasures) is identical to 2026.11.1; the "Always on" list grows from 16 to 33. The
regenerated `agenda-trails.tsv` golden adds 17 permanent rows and changes no dated row; its `density-dropped` rows
are byte-identical to the base (lore-ferry, lore-eagleharbor only). `agenda-roads.tsv`, `date-rules-2027.json` and
`docs/play/e2e-ids.env` change only in their pack-version header/stamp (the e2e treasure ids are unchanged:
`trail-farm-trail-north`, `road-northeast-morgan-road-2`).

## Left disabled, and why

- `lore-sakai` — Sakai Park facts are TBC in `lore-facts.md` (secondary sources only; biparks.org returned 403).
  Ships `enabled: false`; its place `sakai-loop` carries `verify: true` (OD-16). Items have no `verify` key in
  the schema, so the flag lives on the place, as `fairy-door` / `helix-house` do.
- Unchanged drafts from 2026.11.1: `egg-fairydoor`, `egg-helix`, `lore-helix`, `egg-marquee`, `lore-lynwood`,
  `lore-strawberry`, `lore-fay`.

## Compromises forced by validator / scheduler rules

1. **`lore-moritani` is an armchair card (0 marks, no placement).** Moritani Preserve lies wholly in chart square
   7:6 (the Winslow cell). With `maxPlacementsPerCell: 3` and the fill order heroes → challenges → riddles →
   treasure → lore (`Density.kt`), `riddle-madrone`, the weekly treasure, `lore-eagleharbor` and `lore-ferry`
   already fill it, and a proximity `lore-moritani` was `density-dropped` on 431 of 432 golden days. The Moritani
   story still ships (readable in Island stories, visit stamp); `egg-strawberry` (hidden eggs are not counted by
   density) keeps a 20-mark find on the ground there.
2. **`riddle-dock` marker moved off its answer.** Seeded on `trail-hidden-cove-trails` it landed in 14:6, Frog
   Rock's cell, where a riddle outranks lore, and `lore-frog` started being dropped in treasure weeks (14 days).
   The marker now sits on the neighbouring `trail-hidden-cove-loop-trail` (13:5); the answer is unchanged.
3. **Blakely Harbor Park:** only `trail-fort-ward-to-blakely-harbor-trail` is usable (`trail-blakely-harbor-trail`
   and `trail-blakely-trail` touch the cemetery quiet zone). Both Blakely items sit on it / at its trailhead.
4. **Fort Ward:** the new lore sits at the south trailhead (`th-b40243`, cell 2:6) rather than on the main trail,
   so it does not crowd cell 3:6 (`egg-station-s`, `lore-fortward`, a treasure most weeks).
5. **Hidden Cove trails have no `park`** in `trails.json`, so Hidden Cove earns no W-PARK marks; the riddle is the
   trails-goal stand-in for the park's facts.
6. **`at: trailhead:<id>` is not a placement shape** (`at` takes `place:` only in both validators), so trailhead
   anchors are `place:` entries with the trailhead's dataset coordinates and a `_note` naming the id.
7. Facts kept out: Gazzam name origin, Manzanita acreage / creek, Fort Ward "Dec 7 1941 message" (not in
   `lore-facts.md`; the Station S line is already in `lore-fortward` / `egg-station-s`).

## Open for other lanes / owner

- `backend/src/play/config.ts` imports published packs by version; the new snapshot
  `backend/src/data/play/2026.11.2.json` needs its import (backend lane).
- `app/composeApp/src/commonTest/.../PlayFixtures.kt` pins `bundledVersion = "2026.11.1"` (Kotlin lane; one line).
- `lore-fortward` (published) says "The Army left in 1958"; `lore-facts.md` says the Navy left in 1958
  (Army 1903–1928). Research-verifier / owner to reconcile; not edited here.
- `docs/play/trails/brief.md` § 1 and the test comment in `build-play-content.test.mjs` still mention 31 items / pack
  2026.11.1 in prose.
