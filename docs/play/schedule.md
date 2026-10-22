# Play: timing and scheduling design

Status: ACCEPTED 2026-10-20 (see decisions.md). Date: 2026-10-20. Role: plan-author.
Authority: `product.md` owns rules, economy and safety, and its § 4 content definitions are encoded here exactly;
**this file owns time, determinism and the content pack schema**; `engine.md` owns rendering. Decision ids here are
`SD-n` (schedule decisions), distinct from product's safety rules `S-n`. Vocabulary: `docs/play/README.md` glossary.
Brief: [`docs/play/brief.md`](brief.md), acceptance criterion 5 ("what appears when ... deterministic and fair, managed
via content config the owner can edit without an app release"), plus the parts of 3, 4 and 8 that depend on timing.
The owner asked for "timing and scheduling needs proper design and thought to think how and when these should
happen over days and months and how to manage". This document is that design.

It does not cover the content types' rules and rewards (`product.md`), their UX (`ux.md`), the `MapFx` effects
engine (`engine.md`) or the art (`effects.md`). This document defines **when** and **where** content
exists, and **who** sees the same thing. Rendering is someone else's job.

---

## 0. Decisions at a glance

| # | Decision | Section |
|---|---|---|
| SD-1 | The island runs on **one clock**: `America/Los_Angeles` wall time, wherever the phone is. Sun times come from an on-device NOAA/Meeus solar algorithm for 47.63 N, 122.52 W, with no network. | §1.1 |
| SD-2 | **Tides v1 = a bundled NOAA high/low table** for Eagle Harbor (subordinate station 9445882, referenced to Seattle 9447130), generated at build time and also served by the Worker. The app does **not** run a harmonic predictor. **v1 uses it for one rule:** kraken stretches are inactive above `rules.krakenMaxTideM` (product T-2). Low-water windows for shore point items are v2. | §1.6 |
| SD-3 | **Ferries v1 = a static timetable in the content config** (one entry per WSF season, weekday and weekend). The owner updates it each season. Fetching the WSF API through the Worker is deferred (owner decision D-3). | §1.7 |
| SD-4 | **No weather** (cut entirely, review P1-18). | §1.8 |
| SD-5 | Placement and timing are a pure function of `(islandSeed, island-local date, config)`. Island items are identical for every player, offline. Seeds are **per item** (`fnv1a64(itemId)`), so a pack bump never reshuffles an unchanged item; server aggregates are baked into the pack at publish; published item ids are immutable and occurrence keys are the claim ids. (v2) Personal items (salted eggs, creatures) also mix in a per-install random salt; v1 eggs are global. The PRNG is SplitMix64, with FNV-1a 64 for string hashing (never `String.hashCode()`). | §2 |
| SD-6 | Weekly rhythm = product § 4 exactly: **Mon 05:00** the weekly treasure set (12 trails / 20 roads, 4 tiers) · **Thu 06:00 → Sun 20:00** the kraken attacks one coast (clock window, daylight, tide-suppressed, coasts rotate) · **Sat 00:00 → Mon 00:00** `ch-sweep` · always: permanent global eggs, permanent riddles, fixed lore (Frog Rock fixed), the ferry sprite (visual only). (v2) 2 weekly riddles from Mon 05:00. | §3 |
| SD-7 | Hard rules: anything that resolves is resolvable only inside [civil dawn, civil dusk] (creatures, v2: [sunrise + 30 m, sunset − 30 m]), validator-enforced; an extra 06:00–21:00 clamp; at most one hero event at a time; at most 3 placements per chart square and 40 visible in total; per-item cooldowns; quiet zones from dataset geometry; road placements only on `roadSafety` roads. | §3.4 |
| SD-8 | Content config `play-content.json` lives in the repo at `data/play/`. A build script validates it and copies it into the Worker bundle and the app bundle. The Worker serves it at `GET /v1/play/content` with an ETag. The app caches it, validates it strictly, and keeps the last good copy. Pipeline: edit → PR → merge → owner deploys. No D1 table holds the config. | §4 |
| SD-9 | `PlayScheduler` lives in a new pure-Kotlin `:play` module. It builds one `DayPlan` per day in O(items) and memoizes it. An `agenda(now)` query costs ≤ 1 ms. | §5 |
| SD-10 | **Notifications: none in v1.** "What's on" is in-app only. Local notifications (opt-in, ≤ 1 per day, scheduled ahead, no location) are a later stage gated on its own design. | §6 |
| SD-11 | Product's economy, one server model: claims ride `PUT /v1/coverage` as `play: [{walkId, claims: [{id, kind, weekId}], dayKey, hints}]` tied to `walks[]`, with `playConfig`; the server derives every mark from its content table (the last 8 packs kept) and never trusts a client mark; caps and time rules are product § 3.5. Challenges are window-boxed (type `challenge`), never timed; standings are `{band, count}` only. The **existing** daily cron closes kraken attacks and island challenges, so no new cron trigger is needed. | §7 |

---

## 1. Time model: the calendars the island runs on

All schedule arithmetic uses **island-local time** (`TimeZone.of("America/Los_Angeles")`, kotlinx-datetime 0.7.1,
already a dependency in `app/gradle/libs.versions.toml`). A phone in another time zone still sees the island's day,
because the content is about a place, not about the player. Daylight saving is handled by the time zone database.
Two days a year have 23 or 25 hours. Every clock expression resolves through `LocalDateTime → Instant` using the
tz rules, and a time that does not exist (02:30 on the spring-forward day) moves forward to the next valid instant.
Tests pin the 2027 transitions (Mar 14, Nov 7).

### 1.1 Sun: time-of-day bands

**Algorithm.** Use NOAA's solar calculator equations, which are Meeus's *Astronomical Algorithms* and the same ones
behind NOAA's spreadsheet. They need Julian century, geometric mean longitude and anomaly of the sun, equation of
center, apparent longitude, obliquity, declination, equation of time, and the hour angle at the requested zenith.
NOAA says sunrise and sunset results are "theoretically accurate to within a minute for locations between ±72°
latitude". The calculator assumes 0.833° of atmospheric refraction and is good for 1800–2100 ([NOAA GML, Solar
Calculation Details](https://gml.noaa.gov/grad/solcalc/calcdetails.html)). That is far better than gameplay needs.
It is about 60 lines of `kotlin.math` with no tables, and it runs in commonMain.

- File: `app/play/src/commonMain/kotlin/com/bainbridgetrails/play/time/Sun.kt`
- `fun sunEvents(date: LocalDate, lat = 47.63, lon = -122.52, tz): SunDay`. The result is
  `SunDay(dawn, sunrise, noon, sunset, dusk)`, all `Instant`. Dawn and dusk are **civil twilight** (zenith 96°).
  Sunrise and sunset use zenith 90.833°. At 47.6° N the sun always rises, so there are no polar edge cases. The
  function is still total: if a zenith is never reached it returns `null` for that field.
- One fixed island point is used for every computation. Across the island the difference is a few seconds.
- Test: `SunTest` compares against values from NOAA's calculator for 47.63 N, −122.52 on 12 dates (each month's
  15th, plus both solstices and both DST days). It asserts |Δ| ≤ 2 min.

**Bands** (derived per day and used by the recurrence DSL as keywords):

| band | span | notes |
|---|---|---|
| `night` | dusk → next dawn | Nothing resolves; overnight items sleep (§3.4). |
| `dawn` | dawn → sunrise + 90 m | Finds resolvable (daylight starts at civil dawn), clamped to 06:00 at the earliest. No creatures (creature hours start at sunrise + 30 m). |
| `day` | sunrise + 90 m → sunset − 60 m | Most content. |
| `golden` | sunset − 60 m → sunset | Visual flavour only (light on the art); never a trigger. |
| `dusk` | sunset → dusk | Finds still resolvable until civil dusk (clamped to 21:00). No creatures (creature hours end at sunset − 30 m), no dusk items, no dusk triggers. |

Two derived spans are the only time-of-day **rules** (product T-1): **daylight** = [civil dawn, civil dusk] and
**creature hours** = [sunrise + 30 m, sunset − 30 m]. Bands are keywords for authoring windows inside them.

Approximate island sun times, for intuition only. Approximate Seattle-latitude values; the golden table in
`SunTest` is authoritative:

| | Jan 15 | Mar 15 | Jun 15 | Sep 15 | Nov 15 | Dec 15 |
|---|---|---|---|---|---|---|
| sunrise | ~07:55 | ~07:15 (PDT) | ~05:11 | ~06:50 | ~07:20 (PST) | ~07:50 |
| sunset | ~16:45 | ~19:15 | ~21:08 | ~19:20 | ~16:35 | ~16:18 |

Takeaway: in December finds run roughly 07:20 → 16:50 (civil dawn → civil dusk), so a Saturday or an after-school
walk fits (Marcus). In summer the 06:00–21:00 clamp cuts both ends, and Dana's mornings are the anchor.

### 1.2 Day of week, week, month

- **Day of week**: `mon` … `sun` in island-local time.
- **Week**: the half-open span **[Monday 00:00, next Monday 00:00)** island time (no "Sunday 23:59" gap). The
  **week id** used in seeds, occurrence keys and claims is the **Monday's date** (`2027-01-04`), never an ISO week
  number. That avoids week-53 and year-boundary bugs and stays readable in logs.
- **Month**: calendar month. Nth-weekday rules (`1sat`, `3sat`, `last-sun`) count within the month.

### 1.3 Seasons: astronomical and PNW climate

One definition of **season** (product Conventions), plus a flavour-only climate filter:

1. **Season** = island-time quarter: `storm` (Jan–Mar), `strawberry` (Apr–Jun), `madrona` (Jul–Sep), `kraken`
   (Oct–Dec). A date's season is its quarter; a week id's season is the quarter of that week's Thursday. It drives the
   (v2) board views, (v2) "first per season" creature repeats and the DSL `season` filter. The earlier meteorological
   seasons from Dec 1 are gone; equinox and solstice moments are available as named windows (v2).
2. **Climate** (`climate` filter, never called a season), the PNW nuance: `wet` (Oct 15 → Apr 30), `dry` (Jul 1 → Sep 15), and `shoulder` for the rest.
   The pattern is the well-known Puget Sound one: dry, settled summers and long wet winters. The exact boundaries are
   content, so the owner tunes them in config (`climate` block). They gate flavor, not rules: wet-season variants
   use "storm" art, more kraken, mushrooms and salamanders; dry-season variants use dust, berries and long evenings.

### 1.4 Named windows: holidays and local events (v2, except `exclusion-remembrance`)

Local events are **named windows** in config (`windows.<id>`), referenced by items. Fixed-rule events use a
recurrence rule. Variable-date events use explicit `dates` that the owner updates once a year, with an optional
`fallback` rule. A variable event with neither a date for this year nor a fallback simply does not occur, which is
the safe default.

| window id | rule | evidence |
|---|---|---|
| `chilly-hilly` | yearly, Feb, `last-sun`, 06:00–17:00 (2027: Feb 28) | Cascade Bicycle Club's ride; 2026 was Sun Feb 22, the 54th ([Cascade](https://cascade.org/news/2026/02/ride-chilly-hilly-wear-hot-hoodie), [WSMag](https://wsmag.net/ws-life/the-outdoors/2026-02-12/what-to-know-about-this-years-chilly-hilly-bike-ride/)); historically "the last Sunday in February" ([Visit Poulsbo](https://www.visitpoulsbo.com/?p=3404)). |
| `grand-old-fourth` | yearly, Jul 4, 06:00–21:00 | Winslow Way and Madison Ave. The 2026 listing gives the parade as 13:00–15:00 ([ParentMap](https://www.parentmap.com/?p=429876)). It is produced by the Chamber ([Bainbridge Review](https://www.bainbridgereview.com/news/a-star-spangled-shindig-bainbridge-islands-47th-grand-old-fourth-of-july-slideshow/)). |
| `mochi-tsuki` (cut) | — | No gameplay on the Bainbridge Island Japanese American Community's event without BIJAC's consent (D-10, review P1-23). |
| `harvest-fair` | `dates` (owner), fallback Sep `4sun` | Friends of the Farms at Johnson Farm, a Sunday in late September (Sept 22; earlier Sept 27 and 29) ([Bainbridge Review](https://www.bainbridgereview.com/?p=84846), [BI Review 2](https://www.bainbridgereview.com/?p=19729)). |
| `rotary-auction` | `dates` only (no fallback) | Usually the last Saturday of June or the first of July. **2026 was Sat Jul 11**, the 66th ([Bainbridge Review 2026-06-30](https://www.bainbridgereview.com/2026/06/30/rotary-auction-not-accepting-large-appliances-this-year/)). That pattern breaks too often for a rule. |
| `exclusion-remembrance` | yearly, Mar 30, all day | On March 30, 1942, Bainbridge Island's Japanese Americans were removed via the Eagledale ferry dock. The memorial (*Nidoto Nai Yoni*) is at the west edge of Pritchard Park ([Wikipedia](https://en.wikipedia.org/wiki/Bainbridge_Island_Japanese_American_Exclusion_Memorial)). **Play is quiet here, not gamified** (see quiet zones, §3.4). |
| `frog-rock-birthday` | yearly, Jun 6–7 (product § 4.6.1; **TBC**: sources give June 6 and June 7) | Frog Rock was painted in June 1971 ([WSU / Paul Brians](https://libraries.wsu.edu/wp-content/uploads/files/paul_brians/bi/frogrock.html), [Wikipedia](https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington))). The earlier "3rd Saturday" rule is dropped. |
| `king-tides` (cut) | — | The king-tide kraken is cut (review P0-5). |
| `race-weekends` (cut) | — | Timed races are cut (review P0-2); `ch-sweep` uses its own weekly window. |

Event windows (v2) mostly **pause or re-theme** content. They do not pull people into crowds:

- Chilly Hilly: roads-goal placements on the ride's roads are suppressed that day (thousands of cyclists, cars
  queued at the ferry). The trails goal gets a "Chilly Hilly" lore card instead.
- Grand Old Fourth: road placements on Winslow Way and Madison are paused. The map gets a fireworks `ambient` visual
  over Eagle Harbor, which is decor and spawns nothing.

### 1.5 Calendar arithmetic precision

Everything above is computed **on device, offline**, from the config and the bundled tide table. The only inputs
that age are the tide table (it covers fixed years) and the ferry timetable (it covers fixed seasons). Both have
explicit coverage ranges in config. Outside those ranges, items anchored to tides or ferries use their `fallback`
window (§4.4). They never error.

### 1.6 Tides: decision **SD-2 = a bundled NOAA high/low table**

**Station.** Eagle Harbor, Bainbridge Island is NOAA **9445882**. It is a *subordinate* station referenced to
Seattle 9447130, with offsets of +4 min (high), +5 min (low) and height ratios 1.00 / 1.02 (from NOAA metadata
`stations/9445882.json?expand=tidepredoffsets`, fetched 2026-10-20). Seattle is a harmonic station with **37
constituents** published (`stations/9447130/harcon.json`, fetched 2026-10-20). The largest are M2 1.063 m, K1
0.834 m, O1 0.459 m, S2 0.268 m. The Seattle tide is mixed semidiurnal.

**Options evaluated.**

| option | feasibility | cost | verdict |
|---|---|---|---|
| A. On-device harmonic predictor (37 constituents, Seattle + subordinate offsets) | Feasible. The math is h(t) = Z0 + Σ fᵢ Hᵢ cos(ωᵢ t + (V₀+u)ᵢ − κᵢ). The hard part is the nodal factors f and u and the equilibrium arguments V₀, which need Schureman-style astronomy per year. About 400 lines plus a constituent table, and it must be validated against NOAA. | High (correctness risk, tests) | **Rejected for v1.** It buys an unlimited year range, which we do not need. |
| B. Build-time NOAA high/low table, bundled and served by the Worker | The CO-OPS API serves `product=predictions&interval=hilo`. Subordinate stations can **only** return high/low, and high/low requests may span **10 years** ([CO-OPS API](https://tidesandcurrents.noaa.gov/api/)). | Low: one script, about 12 KB per year | **Chosen.** |
| C. Worker fetches a monthly table on a cron | Works, but adds a network dependency and cron CPU for data that is static years ahead. | Medium | Rejected; B is strictly simpler. |
| D. Skip tides in v1 | Possible. Kraken would then run on clock times only. | None | Fallback if the owner declines B. |

**Licensing.** NOAA's tides and currents site states that information on government servers is public domain
unless annotated otherwise. That wording is quoted from the site in the Debian `xtide-data` copyright file
([Debian](https://metadata.ftp-master.debian.org/changelogs/main/x/xtide-data/stable_copyright)), and other NOAA
sites word it the same way ([NWS disclaimer](https://www.nws.noaa.gov/disclaimer.php)). The NWS terms add
conditions: do not imply endorsement, and do not present modified data as official. So the app shows tide times
as "approx." with a "Tide predictions: NOAA CO-OPS (not for navigation)" credit in the attribution screen and in
`data/play/ATTRIBUTION.md`. Predictions can change: NOAA's page says they reflect "the latest information available
as of the date of your request". We regenerate yearly (step below).

**Pipeline.**

- `scripts/build-tides.mjs --station 9445882 --from 2026 --to 2029` calls
  `https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?product=predictions&interval=hilo&datum=MLLW&units=metric&time_zone=gmt&station=9445882&begin_date=…&end_date=…&format=json`,
  one request per year.
- Output: `data/play/tides/9445882-2027.json` = `{"station":"9445882","datum":"MLLW","year":2027,"t0":"2027-01-01T00:00Z","e":[[dMin,cm,t],…]}`.
  `dMin` is minutes since the previous event, `cm` is the height in centimetres, and `t` is `H` or `L`. That is
  about 1,410 events per year, roughly 12 KB of JSON.
- The same files are copied into `app/composeApp/src/commonMain/composeResources/files/play/tides/` and
  `backend/src/data/tides/`. `--check` fails CI when the copies drift.
- **Yearly chore** (owner, about October; NOAA publishes its annual tables around Oct 1): run the script for
  year+3, commit, deploy.
- On device, `TideTable.events(day)` returns that day's H/L. `heightAt(t)` interpolates with the standard cosine
  between adjacent extrema, accurate to a few cm for banding.

**What tides drive (v1).** One rule: a kraken stretch is inactive (not sensable, not claimable) while the interpolated
predicted height at Eagle Harbor is above `rules.krakenMaxTideM` = 2.44 m (8 ft) MLLW (product T-2). The kraken
window itself is clock-based (Thu 06:00 → Sun 20:00), never anchored to a high tide. If the table does not cover
today, stretches stay inactive (fail closed). (v2) Low-water windows for shore-tagged point items. Nothing is
placed on a beach (§3.4). The tide never sends anyone to the water, and the kraken never comes at high water.

### 1.7 Ferries: decision **SD-3 = a static timetable in config**

Facts:

- The WSF Schedule API (SOAP and REST, XML or JSON) needs a free access code, issued after registering an email
  address ([WSF Schedule API](https://www.wsdot.wa.gov/ferries/api/schedule/documentation/index.html),
  [WSDOT Traveler API](https://wsdot.wa.gov/traffic/api/)). WSDOT recommends caching and refreshing when
  `GetCacheFlushDate` changes.
- WSF publishes seasonal schedules with fixed effective dates, with separate weekday and weekend/holiday columns.
  In 2025–26 it moved to a two-season model; the winter schedule ran Dec 28, 2025 → Mar 21, 2026
  ([FOX 13](https://www.fox13seattle.com/news/washington-state-ferries-winter-schedules)).
- **The Seattle–Bainbridge schedule itself is being rebuilt in 2026–27**
  ([WSDOT schedule update](https://wsdot.wa.gov/construction-planning/search-studies/seattle-bainbridge-island-and-seattle-bremerton-sailing-schedules-update)).
  Expect at least one unplanned timetable change during Play's first year.

**Decision.** v1 embeds the timetable **in `play-content.json`** (`ferry.timetables[]`). Each entry has an
effective range, `weekday` and `weekend` departures from both terminals, and holiday dates that use the weekend
column. The crossing time is a config value (`crossingMin: 35`, the long-standing published figure). The owner
copies departures from the WSF PDF once per season, about 4 minutes of work twice a year. If a date falls outside
every range, ferry-anchored items fall back to their clock window, and the ambient ferry visual hides.

Why not the API now: it needs a registered access code (the owner's email, held as a Worker secret), a cron fetch
that parses a SOAP/REST payload in a 10 ms CPU budget, and a second content channel. Gameplay needs ferries only
to within a few minutes: a ferry "arrives", a horn plays, a wake effect runs. The API is owner decision **D-3**. If
chosen, the Worker cron writes `ferry.timetables` into the served config (§7.4), and no client change is needed
because the schema is the same.

**Safety.** No content ever says "catch the ferry" or counts down to a departure. v1 has no ferry encounter: the
ferry sprite is visual only (an `ambient` item). (v2) The horn encounter (product § 4.6.2) fires at Bainbridge
departures inside the Winslow waterfront zone, never at the terminal or its holding lanes. The Bainbridge terminal
reference point in every Play doc is 47.62371, −122.51283 (landward end of `road-bainbridge-island-ferry-terminal`).

### 1.8 Weather: decision **SD-4 = none** (cut)

Open-Meteo's free API is for **non-commercial use**, under 10,000 calls a day (5,000 an hour, 600 a minute). Its
data is CC BY 4.0, which requires attribution ([Open-Meteo terms](https://open-meteo.com/en/terms)). A Worker
cache (one fetch an hour, about 24 calls a day) would fit the limits easily, and 47.63 N is one grid point. We
still skip it in v1, for these reasons:

1. "Non-commercial" is ambiguous for a SloopWorks app, even a free one. That is a legal question, not an
   engineering one.
2. Gameplay keyed to rain is a fairness problem. A sunny-only treasure punishes the wet season, which is half the
   year, and rain-only content rewards walking in storms.
3. The climate seasons (§1.3) already give the PNW its weather *texture* deterministically.

Weather, rain and morning fog are **cut entirely** (review P1-18; product PP-10): no feed, no overlay, no gate.

---

## 2. Determinism and fairness

### 2.1 Goals

1. Two phones with the same config and the same island date show the **same island content in the same places at
   the same times**, with no server round trip.
2. (v2) Personal content (salted eggs, creatures) differs per player, so a friend cannot spoil it by sharing a
   screenshot. It is still stable for a given player across app restarts. v1 eggs are global (product § 4.2).
3. A stale cache (an old config) gives a coherent, slightly old island, never a broken one.
4. Changing the config moves **only** what changed. Editing item B never reshuffles item A.

### 2.2 Seeding scheme

```
islandSeed  : ULong                         // config.islandSeed (hex). Changed only for a deliberate "new island".
itemKey     = fnv1a64(item.id)              // stable across platforms; NEVER String.hashCode()
occDay      = occurrence's first island-local date, as epochDay (Long)
occSeed     = mix64(islandSeed xor itemKey xor (occDay.toULong() * 0x9E3779B97F4A7C15uL))
personal    : occSeed' = mix64(occSeed xor userSalt)       // only when item.scope == "personal"
rng         = SplitMix64(occSeed or occSeed')
```

- `mix64` is the SplitMix64 finalizer: the two xor-shift-multiply rounds with `0xBF58476D1CE4E5B9` and
  `0x94D049BB133111EB`.
- **Per-item seeds** keep goal 4: an item's draw depends only on its own id and occurrence, never on how many
  numbers other items consumed.
- **Immutable ids (no in-place reshuffle).** A published item id never changes meaning. To move or reshuffle an
  item, the owner publishes a **new id** and sets `expires` on the old one; the validator compares the pack with the
  previous published one (`data/play/published/`) and rejects edits to a published id's `when`, `where`, `count`,
  rewards or `fx` (it may flip `enabled` or list the id in `kill`). The old `reseed` salt is removed for this reason.
- **Aggregates baked in.** Inputs that would otherwise need the server (the low-traffic chart squares for the
  treasure spread, product § 4.1) are computed by the Worker cron, copied into the pack's `aggregates` block at
  publish, and read only from there. An offline phone and an online phone draw the same set.
- **Occurrence key** (identity for claims, cooldowns, sync and server dedupe): `"<itemId>@<yyyy-mm-dd>"`, using
  the occurrence's first island-local date (the Monday for weekly items, the start date for multi-day windows such
  as the kraken's Thursday). One placement of a multi-placement occurrence adds `#n` (`treasure-weekly-roads@2026-10-26#07`,
  `kraken-roads@2026-10-29#1`); anchored sub-windows also use `#n`.
- **Config version in every claim.** Claims name the pack version they were evaluated against (`playConfig`); the
  Worker keeps the last 8 published packs and evaluates each claim against its own version (§7.2).

### 2.3 PRNG

**SplitMix64** (Steele, Lea, Flood, "Fast splittable pseudorandom number generators", OOPSLA 2014; reference C:
[prng.di.unimi.it/splitmix64.c](https://prng.di.unimi.it/splitmix64.c)). The state is one `ULong`. `next()` adds
the golden gamma and applies `mix64`. It is 64-bit-exact in Kotlin common (`ULong` arithmetic wraps the same on
JVM, Android and Kotlin/Native), passes BigCrush for this use, has no allocation, and needs about 5 lines.
xorshift was considered and rejected: it needs a non-zero seed fix-up and fails some linearity tests, and nothing
is gained.

- `nextInt(bound)`: Lemire's multiply-shift with rejection (unbiased).
- `nextDouble()`: `(next() ushr 11).toDouble() * 2^-53`.
- `pick(k, list)`: partial Fisher–Yates over a **copy sorted by stable id**, so pool order in the JSON never
  matters.
- `jitter(m)`: a seeded offset along a segment (fraction 0.15–0.85, so it stays off segment ends and junctions),
  never off the line.

Golden vectors: `SplitMix64Test` asserts the first 8 outputs for seeds 0, 1 and `0x9E3779B97F4A7C15`, generated from
the reference C. `Fnv1aTest` covers ASCII and UTF-8 ids. Both tests run unchanged on JVM and iOS
(`:play:iosSimulatorArm64Test` if wired; `jvmTest` is the profile gate).

### 2.4 Island scope vs personal scope

| | island (`scope: "island"`) | personal (`scope: "personal"`) |
|---|---|---|
| Who sees it | everyone, identical | only this install |
| Seed | `occSeed` | `occSeed ⊕ userSalt` |
| Types | treasure (weekly set), egg (v1: global), obstacle (kraken), riddle, challenge, lore (Frog Rock, ferry, history, remembrance), ambient (ferry sprite) | (v2) salted egg, creature |
| Why | shared talk ("did you repel the kraken at Point White?"), island challenges, fairness | surprise, no spoilers, density spread |
| Server checks | the occurrence key belongs to the pack named in `playConfig` and to the stated week | same; position is not checked (no GPS on server) |

**Island placement is computed before, and independently of, personal placement and user state.** User state
(found, cooldown) only changes how an island item *displays* (found or available). It never moves it. Density caps
(§3.4) are applied first to island items in a fixed order, then personal items fill whatever capacity is left. So
personal content can never displace shared content.

(v2) `userSalt` is 64 random bits, created on first Play opt-in and stored in `play/state.json`. It is per install and
**not synced**, so two devices get different eggs. That is acceptable, and syncing it is owner decision D-8. It
never leaves the phone.

### 2.5 Offline-first and clock honesty

- The scheduler needs only `(config, tides, places, now)`. All four are local. The bundled config and tide tables
  ship in the APK and IPA, so a fresh install in airplane mode plays fully.
- If the user sets the device clock forward, they see tomorrow's island locally. That is harmless: island content
  is not secret. The server's time rules are product § 3.5 (stated once there): an occurrence must start ≤ server
  now + 1 h; a find occurrence must end ≥ the upload's server island date − 2 days; week-keyed items are accepted
  until the week's end + 7 days. A rolled-back clock can therefore replay at most two days.
- Config precedence: `max(bundled, cached)` by `version`, **among valid configs only**. A fresh, valid download
  replaces the cache. An invalid one is discarded with its reason logged, and the last good copy stays (§4.8).

---

## 3. Cadence design over days and months

### 3.1 Pacing principles

- **Appointments plus small surprises.** Fixed weekly anchors build habit: Dana knows the kraken is ashore from
  Thursday to Sunday, Marcus knows treasures reset on Monday. Permanent eggs and riddles give low-stakes novelty on
  any walk. (v2) Monthly themes and yearly landmarks (Frog Rock's birthday, the Fourth) make the island feel like
  *this* island.
- **No loss aversion.** No streaks that break, no "expires in 2 h!" copy, and nothing that punishes a missed week.
  Missed island content is shown in the Logbook as "the kraken came and went". Multi-day windows (treasures 7 days,
  kraken 3.5 days, sweep 2 days) mean a weekly walker never misses one.
- **Fatigue control.** v1 has **3 weekly asks** (the treasure set, the kraken, the sweep). Spot cooldowns keep the
  same treasure spot from recurring. Hero events never stack.
- **Both goals.** Every item names its placement per goal. When an item has no meaningful placement for a goal, it
  is omitted for that goal rather than forced in.

### 3.2 Weekly rhythm

This table encodes product § 4 exactly (product is the authority; the example pack in §4.7 implements it).

| when (island time) | island content (everyone) | Dana (weekday mornings, dog) | Marcus (weekends, kids) |
|---|---|---|---|
| **Mon 05:00 → next Mon 00:00** | **Weekly treasure set** (product § 4.1): 12 trails / 20 roads in 4 tiers (sea glass, agate, glass float, ship's bell every 4th week), radius 20 m trails / 25 m roads, ≤ 1 per chart square, ≥ 300 m apart, ≥ 40 % in low-traffic squares (baked aggregates). Resolvable in daylight only. | new glints on her morning loop | picks a weekend target |
| **Thu 06:00 → Sun 20:00** | **The kraken attacks one coast** (product § 4.3.2): clock window, coast by fixed rotation (roads K5, K2, K6, K4, K7, K3; trails T4, T2, T3, T5), 2–4 stretches; a stretch is active in daylight while the tide is ≤ 2.44 m MLLW; repel = walk ≥ 60 % in one walk. `ch-krakenweek` runs in the same window. | repels a stretch on a weekday | repels one on the weekend |
| **Sat 00:00 → Mon 00:00** | **`ch-sweep`**: finish 3 trails / 5 roads. | — | the family goal |
| **Mon 00:00** | Week close: W-WEEK (server-derived), F-RETREAT and island-challenge results land. | | |
| always | **Permanent eggs** (global, hidden, 15 m, once ever) · **permanent riddles** (solved from home = stamp, claimed by walking) · **fixed lore** (Frog Rock fixed at its landmark, history cards, remembrance quiet) · **the ferry sprite** on the timetable (visual only). | | |
| (v2) Mon 05:00 → next Mon 00:00 | 2 weekly riddles. | | |

Every week both personas get **a shared appointment that includes a weekday and a weekend day** (the kraken,
Thu–Sun) and something on any walk (treasures, eggs, riddles). Neither needs to play daily. Cut from the earlier
draft: the daily chest, "the frog moves", Ferry Friday, race weekends, treasure-map fragments (v2), dawn and dusk
creatures.

### 3.3 Annual calendar (v2)

Monthly themes are product § 6's table (v2; v1 ships only the Mar 30 quiet day). They are **variants** of the same
items (art, copy), not new mechanics, so the content load stays bounded. This section only adds the time facts the
scheduler needs per month:

| month | product theme (v2) | named windows | time facts |
|---|---|---|---|
| Jan | Winter tides | — | civil dusk ≈ 17:20 |
| Feb | Forest listening | `chilly-hilly` (pause roads on the route) | |
| Mar | First green | **`exclusion-remembrance` Mar 30 (v1: quiet day)** | DST (2027-03-14) moves every clock-anchored window |
| Apr | Hilltop meadow | | |
| May | Steamer days | | daytime low tides (v2 shore items) |
| Jun | Strawberry month | `frog-rock-birthday` Jun 6–7 (**TBC**) | longest day; the 21:00 clamp cuts finds before civil dusk |
| Jul | Long days | `grand-old-fourth`, `rotary-auction` (dates) | |
| Aug | Bramble month | | |
| Sep | Radio month | `harvest-fair` | equinox |
| Oct | Kraken season | Oct 7 Agate Pass Bridge card; Oct 31 second attack (yields to the weekly one) | wet starts Oct 15 |
| Nov | Mill month | | DST ends (2027-11-07) |
| Dec | Ferry lights | solstice | civil dusk ≈ 16:50 |

Cut (tombstones): "Mochi Moon" (Mochi Tsuki gameplay), "Grand Forest after dark", the king-tide kraken, dusk
creatures, chorus frogs at dusk, race weekends. (v2, unsourced, **TBC** before any card ships: winter wrens, Douglas
squirrels, chorus frogs, bats, banana slugs, chum salmon at Sakai.)

### 3.4 Spawn, expire, density and safety rules (global, in `rules`)

1. **Daylight rule** (product T-1; review P0-1). The scheduler computes each occurrence's **resolvable** spans as
   its window ∩ daylight [civil dawn, civil dusk] ∩ `rules.playHours` (06:00–21:00) for every day it covers; nothing
   is sensable or claimable outside them. The **validator** rejects any item with `resolve.by ≠ none` whose
   authored single-day window is not inside [civil dawn, civil dusk] on every one of 400 simulated days (no silent
   clipping of a "dusk" item), and any `creature` item (v2) whose window is not inside creature hours [sunrise +
   30 m, sunset − 30 m]. Multi-day items (treasures, eggs, riddles, the kraken) stay **visible** across nights but
   **sleep** outside daylight: rendered static, no pulse or glow, unclaimable. Non-interactive `ambient` decor (the
   ferry sprite) is exempt because it is neither a spawn nor a lure, but it is still limited to 05:00–23:00.
2. **Deleted:** `rules.afterSunsetOk` and the "sun respect" exception. No place (Winslow included) resolves anything
   after civil dusk.
3. **Heroes**: `hero: true` items. At most `rules.heroMaxConcurrent = 1` hero is active at any instant, and at most
   1 hero **starts** per island day. Conflicts resolve by `priority` (higher wins), then by id. The loser's
   occurrence is **suppressed**, not shifted, so outcomes stay predictable. The config build check simulates 400
   days and fails when a conflict's **loser** lacks `yields: true`. v1 has one hero family (the kraken, one coast per
   goal per week), so no conflict exists; the simulation matters once v2 event windows land (the Oct 31 second
   attack yields to the weekly one and is scheduled outside its window).
4. **Density**: on the **chart square**, the single Play grid (product glossary): 0.01° lat × 0.015° lon anchored
   at 47.55 N, 122.62 W. At most `maxPlacementsPerCell = 3` placements per chart square for the current goal; a
   hero's placements count, and hero multi-segment obstacles count as 1. At most `maxVisiblePlacements = 40`
   island-wide per view. Fill order: heroes, challenges, riddles, island treasure, island lore, eggs, then (v2)
   personal creatures. An overflowing placement
   is dropped for the day, deterministically.
5. **Cooldowns**:
   - `repeat` (per user, per item, product § 3.5): `once` (eggs, riddles, lore: once ever), `per_attack` (kraken
     stretches), `weekly_set` (treasures), `per_season` (v2 creatures).
   - `spotCooldown` (island, per item): a pool member used by occurrence *n* is excluded for the next *k*
     occurrences. Example: a weekly treasure slot never reuses a pool member within 4 weeks. It is computed
     deterministically by replaying the item's draws for the previous k occurrences, which is cheap because pools
     are small.
6. **Safety filters** (the resolver applies them; the validator also rejects config that violates them):
   - Placements only on **counted or included dataset segments** (public trails and counted public roads), so
     nothing lands on private land.
   - Roads: the one **`roadSafety`** rule (product § 4.0.2, verbatim): counted; `category ∈ {residential,
     collector, arterial}` (so never `state_route`); `public: true` and `ownership` starting `public_`;
     `speed_mph ≤ 25`, with `null` allowed only for `residential` (TBC). 2026-10-20: 370 of 430 counted roads pass.
     This filter is why the kraken attacks Point White Drive (25) and not Agate Point Road (35) or Country Club
     Road (30).
   - **No beach placements, ever.** There is no beach geometry in the datasets, and `point:` placements are checked
     against the coastline with a 30 m inland buffer by the build script, using `coastline.json`.
   - **Quiet zones** (`quietZones[]`, product § 4.6.3): **built from dataset geometry** at pack build, never typed
     circles: a 200 m buffer around every segment of `trail-bainbridge-island-japanese-american-memorial-trail`
     and a 100 m buffer around every segment of `trail-blakley-cemetary-trail`, plus owner-tagged places. No
     placement may land inside one, no playful FX may render inside one, and **the validator rejects any pool
     member (trail or road) that touches one** (today: `trail-pritchard-trail`, `trail-blakely-harbor-trail`,
     `trail-blakely-trail`, `trail-blakely-hills-trail`, Taylor Ave NE, Welfare Ave NE). `excludedParks:
     ["Pritchard Park"]` keeps Pritchard Park out of every pool. On `exclusion-remembrance` (Mar 30) Play shows only
     a quiet remembrance card: no marks, no "find". The earlier `respectZones` circle (47.6098, −122.5076, 120 m)
     sat ≈ 0.6 km from the memorial trail and is removed.
   - (v2) Event pauses: top-level `pauses` (§4.6) suppress **every** item's placements on named roads or trails
     during named windows (Chilly Hilly, the Fourth). Pause lists are not placement pools, so they may name roads
     that fail `roadSafety` (pausing them is harmless).
7. **Expiry**: each occurrence ends at its `to`. Unresolved island items vanish, and the Logbook gets a past-tense
   note. Unresolved personal items vanish silently. `notBefore` and `expires` on an item bound its whole life,
   for seasonal one-offs.

---

## 4. Content config: `play-content.json`

### 4.1 Where it lives and how it flows: decision SD-8

```
data/play/play-content.json            ← the ONE file the owner edits (source of truth, in git)
data/play/tides/9445882-YYYY.json      ← generated by scripts/build-tides.mjs
        │  scripts/build-play-content.mjs  (validate + copy; --check in CI and in `npm test`)
        ├──► app/composeApp/src/commonMain/composeResources/files/play/play-content.json  (bundled default)
        ├──► app/composeApp/.../files/play/tides/*.json
        ├──► backend/src/data/play-content.json        (imported by the Worker, like segments.json)
        └──► backend/src/data/tides/*.json
```

**Why a bundled Worker import and not D1 or static assets.**

- It is the same pattern as `segments.json` and `legal-pages.ts`: the config is code-reviewed, versioned, tested
  and deployed atomically with the Worker that validates finds against it.
- A D1 table would add an admin write path, a second source of truth, and drift between the app bundle and the
  server.
- Static assets would be served without the Worker, so the server could not use the config for claim and challenge
  validation without fetching itself.

The cost is that a config change needs `npm run deploy` (owner only, about 1 minute). For kill switches, the
latency is deploy time plus the client refresh interval (§4.8, ≤ 1 h after the next foreground), which is
acceptable. Owner decision D-11 covers an even faster path.

**Owner workflow.**

1. Edit `data/play/play-content.json`. Bump `version` (`YYYY.MM.N`).
2. `node scripts/build-play-content.mjs`. It validates the schema and the semantics (refs resolve against
   `data/dist/trails.json` and `data/roads/dist/roads.json`, safety filters, a 400-day hero-conflict simulation,
   a11y fields present) and copies the file to both bundles.
3. `cd app && ./gradlew :play:jvmTest`. The Kotlin validator also parses the copied file, and
   `PlayConfigBundledTest` fails on any error. The golden agenda changes only if intended.
4. Open a PR. Review the generated "next 14 days" preview: the script prints an agenda table, and that table goes
   in the PR body.
5. Merge, then `cd backend && npm run deploy`. Clients pick up the change on their next refresh. The next app
   release also bundles it.

**Preview "tomorrow's island" (debug drawer).** A new `PlayClockPlugin` (`app/androidApp/src/debug/.../DebugPlugins.kt`,
same pattern as `DatasetPlugin`) offers:

- **Time travel**: a fixed island date and time, an offset (+1 d, +7 d, next Thu 14:00), or "real".
- A **config source switch**: bundled / cached / fetch now.
- An **agenda dump**: today's `DayPlan` table, plus a "next 14 days" list.
- **Simulate user state**: clear finds, reset cooldowns, roll a new salt.

It writes `AppGraph.playClock` (a `PlayClock` with a debug override; release builds bind the system clock and
expose no setter). iOS uses the launch arguments `-btPlayClock 2027-07-04T14:00` and `-btPlayConfig bundled` for
simulator screenshots, following the existing `-btScreen` pattern.

### 4.2 Schema (version 1): top-level shape

```jsonc
{
  "schema": 1,                 // breaking-change integer; app rejects > its supported max (keeps last good)
  "version": "2026.11.0",      // monotonic, compared lexicographically by YYYY.MM.N parts; sent as `playConfig`
  "minAppBuild": 0,            // app versionCode floor; older apps keep their last good config
  "tz": "America/Los_Angeles", // must equal this value in schema 1 (validated)
  "islandSeed": "5eed1b1a2026c0de",
  "kill": { "all": false, "types": [], "items": [] },   // THE kill switch, one shape (no per-item status)
  "rules": { … },              // §3.4 knobs
  "caps": { … },               // product § 3.5, verbatim (the server reads these, never client values)
  "climate": [ … ],            // flavour filter; seasons are fixed quarters (§1.3), not configurable
  "aggregates": { "lowTrafficSquares": { "trails": [ … ], "roads": [ … ] }, "asOf": "2026-11-02" }, // baked at publish
  "windows": { "<id>": Recurrence, … },
  "places": { "<id>": Place, … },
  "pools": { "<id>": Pool, … },
  "quietZones": [ … ],         // from dataset geometry (§3.4)
  "excludedParks": [ "Pritchard Park" ],
  "quietDays": [ "exclusion-remembrance" ],
  "pauses": [ … ],             // (v2) global event pauses (§3.4)
  "ferry": { … },
  "tides": { "station": "9445882", "years": [2026, 2027, 2028, 2029] },
  "items": [ Item, … ]
}
```

### 4.3 Types (Kotlin is the normative definition; this is a summary)

```kotlin
// app/play/src/commonMain/kotlin/com/bainbridgetrails/play/config/PlayConfig.kt
@Serializable data class PlayConfig(
    val schema: Int, val version: String, val minAppBuild: Int = 0, val tz: String, val islandSeed: String,
    val kill: Kill = Kill(), val rules: Rules = Rules(), val caps: Caps = Caps(),
    val climate: List<SeasonSpan> = DEFAULT_CLIMATE, val aggregates: Aggregates = Aggregates(),
    val windows: Map<String, Recurrence> = emptyMap(), val places: Map<String, Place> = emptyMap(),
    val pools: Map<String, Pool> = emptyMap(), val quietZones: List<QuietZone> = emptyList(),
    val excludedParks: List<String> = emptyList(), val quietDays: List<String> = emptyList(),
    val pauses: List<Pause> = emptyList(),
    val ferry: Ferry? = null, val tides: TideRef? = null, val items: List<Item>,
)
@Serializable data class Kill(val all: Boolean = false, val types: List<ItemType> = emptyList(), val items: List<String> = emptyList())
@Serializable data class Rules(
    val playHours: ClockSpan = ClockSpan("06:00", "21:00"), val ambientHours: ClockSpan = ClockSpan("05:00", "23:00"),
    val heroMaxConcurrent: Int = 1, val maxPlacementsPerCell: Int = 3, val maxVisiblePlacements: Int = 40,
    val krakenMaxTideM: Double = 2.44,    // 8 ft MLLW at 9445882 (product T-2)
    val roadSafety: RoadSafety = RoadSafety(),  // product § 4.0.2, verbatim; not tunable below it
)
@Serializable data class RoadSafety(
    val categories: List<String> = listOf("residential", "collector", "arterial"),
    val maxSpeedMph: Int = 25, val nullSpeedOnly: List<String> = listOf("residential"),
    val ownershipPrefix: String = "public_", val requirePublic: Boolean = true,
)
@Serializable data class Caps(          // product § 3.5 is the authority; these mirror it exactly
    val foundPerDay: Int = 400, val foundPerWeek: Int = 2800, val weekLateDays: Int = 7,
    val findGraceDays: Int = 2, val clockAheadMin: Int = 60, val keptConfigs: Int = 8,
)
@Serializable data class QuietZone(val id: String, val feature: String, val bufferM: Int)  // feature = "trail:<id>" or "place:<id>"
@Serializable enum class ItemType { treasure, egg, obstacle, riddle, challenge, lore, creature, ambient }
@Serializable enum class Scope { island, personal }          // personal = v2
@Serializable enum class Repeat { once, weekly_set, per_attack, per_season }
@Serializable data class Item(
    val id: String,                       // [a-z0-9-]{3,48}, unique, IMMUTABLE once published (§2.2)
    val type: ItemType, val scope: Scope = Scope.island,
    val lore: String? = null,             // art/fx family: kraken, frog, ferry, treasure, riddle, remembrance, …
    val enabled: Boolean = true,          // false = draft (or v2 in the example)
    val hero: Boolean = false, val priority: Int = 0, val yields: Boolean = false,
    val goals: List<Goal> = listOf(Goal.trails, Goal.roads),
    val `when`: When,                     // recurrence inline, or {"window": "<id>"} (+ optional overrides)
    val where: Map<Goal, Placement> = emptyMap(),  // absent for a goal → item omitted for that goal
    val count: Int = 1,                   // placements per occurrence (1..20)
    val marks: Int = 0,                   // product § 3.3 reward (0..1000); the server reads it, the client never sends it
    val tiers: List<Tier> = emptyList(),  // treasure weekly set: tier, marks, count, everyNthWeek?, else
    val bands: List<Band> = emptyList(),  // kraken F-INK: [{pct: 60, marks: 60}, {80, 70}, {95, 80}]
    val stars: Int? = null,               // riddle ★ 1..3 → 40/80/120; hints −25 % each, floor 50 %
    val repeat: Repeat = Repeat.once,
    val hidden: Boolean = false,          // eggs: not on the map until found
    val shore: Boolean = false,           // shore-tagged (v2 low-water gate); kraken stretches use the tide rule instead
    val canopy: Boolean = false,          // eggs under forest cover: radius/accuracy 25 m (product § 4.2)
    val spotCooldown: Int = 0, val spread: Spread? = null,   // treasure: maxPerSquare, minApartM, lowTrafficShare, quadrants
    val resolve: Resolve,                 // how it is found/solved on device
    val challenge: ChallengeSpec? = null, // type == challenge only: scope solo|island, metric (completed | stretches_repelled | climb | parks), target, minPlayers
    val variants: List<Variant> = emptyList(),
    val copy: Copy, val a11y: A11y, val sources: List<String> = emptyList(),
    val fx: String? = null,               // snake_case effect id (effects.md § 3); reduced = its final frame
    val notBefore: LocalDate? = null, val expires: LocalDate? = null,
)
@Serializable data class Copy(val title: String, val body: String, val found: String? = null, val missed: String? = null)
@Serializable data class A11y(val label: String, val announce: String, val reducedMotion: ReducedMotion = ReducedMotion.static_icon)
@Serializable data class Variant(val `when`: VariantWhen, val fx: String? = null,
                                 val copy: Copy? = null)  // first match wins; variants never change rewards or windows
```

**Strictness.** The JSON uses `ignoreUnknownKeys = true` (forward compatibility, `_note` comments) and
`coerceInputValues = false` (a wrong enum or type is an **error**, not a silent default). After decoding,
`PlayConfigValidator.validate(config, places: PlaceIndex, previous: PlayConfig?): List<ConfigError>` checks the
following. **Any error rejects the whole config**, and the app keeps its last good one. There is no partial apply.

- Every id is unique and well formed. Every `window`, `place`, `pool` and `fx` reference resolves. Every
  trail and road id exists in the bundled dataset for its goal.
- **Immutability:** no published id (in `previous`) changes `when`, `where`, `count`, `marks`, `tiers`, `bands`,
  `stars` or `fx`; a removed id must have been given `expires` first.
- Recurrences parse and every `TimeExpr` is valid. `from < to` holds after resolution on at least one day per year.
- Rewards match product § 3.3 exactly (treasure tiers 25/40/60/150, egg 20, riddle stars, kraken bands 60/70/80,
  lore 15, remembrance 0, challenge marks) and `caps` equals product § 3.5. `count` is in 1..20. `copy.title`,
  `a11y.label` and `a11y.announce` are non-blank and ≤ 140 characters. Every placement-bearing item has
  `a11y.announce`. Copy contains no "race", "run", "fast", "speed", "points" and no exclamation mark.
- Every safety filter in §3.4 passes: the **daylight rule** (and creature hours), `roadSafety`, quiet zones (no
  anchor inside, no pool member touching), `excludedParks`, the coastline buffer.
- The hero-conflict simulation is clean. It runs in the build script and the JVM test, not on device, because it
  costs a few ms per simulated year.
- Items whose `where` is missing for every goal they claim are rejected, unless `resolve.by` is `challenge` or
  `none` (challenges and view-only cards have no placement).

### 4.4 Recurrence DSL

Much simpler than RFC 5545: no BYSETPOS, no COUNT or UNTIL interplay, no time zones per rule.

```
Recurrence := {
  "every":  "day" | "week" | "month" | "year" | "dates",
  "on"?:    [ Weekday | MonthDay | NthWeekday ],   // week: weekdays; month/year: month days or nth weekdays
  "in"?:    [ Month ],                             // month filter ("jan".."dec")
  "dates"?: [ "YYYY-MM-DD" ],                      // every: "dates" only
  "fallback"?: Recurrence,                         // used for a year with no `dates` entry (dates only)
  "from":   TimeExpr, "to": TimeExpr,              // island-local, on the occurrence's first day
  "days"?:  Int,                                   // window spans N local days; `to` applies on the last day
  "season"?: [ "winter"|"spring"|"summer"|"fall"|"wet"|"dry"|"shoulder" ],
  "except"?: [ WindowId ],                         // no occurrence on days overlapping these windows
  "anchor"?: Anchor
}
Weekday     := "mon"|"tue"|"wed"|"thu"|"fri"|"sat"|"sun"
MonthDay    := 1..31 | -1                           // -1 = last day of month; 31 in a 30-day month = no occurrence
NthWeekday  := "1sat".."5sat" | "last-sun" | …       // "5xxx" only in months that have one
TimeExpr    := "HH:MM"                               // 00:00..24:00 (24:00 = end of day)
             | ("dawn"|"sunrise"|"noon"|"sunset"|"dusk") [ ("+"|"-") Dur ]
             | "+" Dur                              // `to` only: relative to resolved `from`
Every "always" := { "every": "always" }              // permanent items (eggs, permanent riddles, fixed lore): one
                                                     // occurrence keyed `@<notBefore or pack epoch>`, resolvable in daylight
Dur         := <int>("m"|"h"|"d")                    // e.g. "30m", "2h", "1d"
Anchor      := {
  "at": "tide.high" | "tide.low" | "ferry.arrive.bainbridge" | "ferry.depart.bainbridge",
  "before": Dur, "after": Dur,
  "pick"?: "highest" | "lowest" | "all",             // tide: which extremum(s) inside the day window
  "minHeightM"?: Double, "maxHeightM"?: Double,      // tide filter (MLLW metres)
  "max"?: Int,                                       // cap sub-windows per day (ferry)
  "fallback"?: { "from": TimeExpr, "to": TimeExpr }  // when no anchor event qualifies or data is out of range
}
```

**Semantics.**

1. **Day set.** `every` plus `on`, `in`, `dates`, `season` and `except` give the island dates on which an
   occurrence **starts**. For `every: "week"` with no `on`, the start is Monday.
2. **Day window.** `[from, to]` resolved on that date, or across `days`, then intersected with `rules.playHours`
   (start clamp, §3.4). If the result is empty, there is no occurrence.
3. **Anchor.** Inside the day window, find the anchor events of that date, filter them, and pick. Each event `e`
   yields a sub-window `[e − before, e + after] ∩ dayWindow`. Several events produce several sub-windows (`#n`
   occurrence suffix). None produces the anchor `fallback` window, or else no occurrence.
4. **Overrides.** An item's `when` may be `{"window": "exclusion-remembrance"}` or `{"window": "x", "from": "09:00"}`.
   Fields given inline override the named window's fields.
5. **Determinism.** The evaluation reads only the date, the tz rules, `Sun`, `TideTable`, the ferry timetable and
   the config. There is no wall clock inside.

Examples:

```jsonc
{ "every": "week", "on": ["thu"], "from": "06:00", "to": "20:00", "days": 4 }            // the kraken: Thu 06:00 → Sun 20:00, clock-based
{ "every": "week", "on": ["mon"], "from": "05:00", "to": "24:00", "days": 7 }            // weekly treasures: Mon 05:00 → next Mon 00:00
{ "every": "week", "on": ["sat"], "from": "00:00", "to": "24:00", "days": 2 }            // ch-sweep: Sat 00:00 → Mon 00:00
{ "every": "always" }                                                                    // permanent eggs, riddles, lore
{ "every": "year", "in": ["feb"], "on": ["last-sun"], "from": "06:00", "to": "17:00" }   // (v2) Chilly Hilly
{ "every": "day", "from": "05:00", "to": "23:00",
  "anchor": { "at": "ferry.depart.bainbridge", "before": "0m", "after": "35m", "pick": "all" } } // ferry sprite (ambient)
{ "every": "day", "from": "sunrise+30m", "to": "sunset-30m" }                            // (v2) creature hours
```

Tide anchors (`tide.high` / `tide.low`) stay in the DSL for v2 low-water windows; no v1 item uses one, and the
validator rejects a tide-anchored `obstacle` (the kraken is never anchored to high water).

**Server subset.** The Worker (§7) evaluates only the **date-level** part (`every`, `on`, `in`, `dates`,
`fallback`, `days`, `season` by month-day) and **clock-time** `from` and `to` for challenges and the kraken window. Those
windows must use `HH:MM` times (validated). Parity is enforced by a shared golden file, `data/play/golden/date-rules-2027.json`,
which both `RecurrenceGoldenTest` (Kotlin) and `test/play-recurrence.test.ts` (Worker) must reproduce.

### 4.5 Placements

```
Placement := { "at": PlaceRef }                                  // fixed place
           | { "pick": Int, "from": PoolRef }                    // seeded pick of pool members
           | { "along": FeatureRef | PoolRef, "pick"?: Int }     // seeded segment(s) of a trail/road, jittered
           | { "segments": FeatureRef | PoolRef, "minM"?: Int }  // whole features (obstacles that cover roads)
           | { "rotate": [PoolRef], "epoch": "YYYY-MM-DD" }       // no RNG: occurrence n (weeks since epoch) → pool n mod len
PlaceRef   := "place:<id>"
PoolRef    := "pool:<id>"
FeatureRef := "trail:<trail-id>" | "road:<road-id>" | "park:<park name>" | "trailhead:<th-id>" | "trailheads:*"
            | "stretch:<trail-or-road-id>"   // the part within 60 m of the coastline, computed at pack build (≥ 100 m)
Place      := { "lat": Double, "lon": Double, "radiusM": Int = 40, "goals"?: [Goal], "verify"?: Boolean,
               "landmark"?: { "lat": Double, "lon": Double } }   // card coordinate when the trigger is snapped
Pool       := { "members": [FeatureRef|PlaceRef], "goal"?: Goal }
```

`PlaceIndex` (an interface in `:play`, implemented in composeApp over `DatasetRepository`) resolves features to
segment ids and midpoints. So `:play` has **no dataset dependency** and its tests use a fake index.

### 4.6 Resolution, variants, pauses

```
Resolve := { "by": "proximity", "radiusM": 20 }                 // pass within radius while recording (on device)
         | { "by": "segments", "minShare": 0.6 }                // walk ≥ share of a stretch in one walk (kraken)
         | { "by": "segments", "minM": 100 }                    // walk ≥ minM of a feature in one walk
         | { "by": "riddle", "answer": PlaceRef|FeatureRef, "radiusM"?: 30, "minM"?: 100 }  // solve at home, claim by walking
         | { "by": "challenge" }                                // window-counted (§7.3); never timed
         | { "by": "none" }                                     // lore card / ambient / remembrance: view (and visit stamp) only
VariantWhen := { "season"?: [...], "climate"?: [...], "window"?: WindowId, "month"?: [Month] }
Pause := { "window": WindowId, "features": [FeatureRef|PoolRef] }   // (v2) top-level `pauses`, applies to all items
```

Resolution is evaluated **on device** against the recording's fixes: live by the graph-level `PlaySensing`
observer (product § 3.6.1), and authoritatively at Finish against the kept fixes the Finalizer hands over through
`onGated(sessionId, keptFixes)` (product § 3.6.2). Fixes are never stored by Play, and only the occurrence key is
recorded. A kraken stretch additionally requires daylight and the tide rule (§1.6) at the kept fixes' times.

### 4.7 Full example config (v1 items, plus v2 items as disabled drafts)

This pack encodes product § 4 exactly (product is the authority). Coordinates are real where cited (Frog Rock per
[Wikipedia](https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington)), snapped point computed from
`data/roads/dist/coverage_segments.json`; Eagle Harbor station per NOAA). Places marked `"verify": true` must be
confirmed by the owner before `enabled: true`. **Ferry times in this example are illustrative placeholders, not the real WSF
timetable**; the shipped pack and `data/play/ferry-timetable.json` carry the WSF schedule (product OD-15). v2 items are present as `"enabled": false` drafts with a
`_v2` note so the schema is exercised; cut items are absent.

```json
{
  "schema": 1,
  "version": "2026.11.0",
  "minAppBuild": 0,
  "tz": "America/Los_Angeles",
  "islandSeed": "5eed1b1a2026c0de",
  "kill": { "all": false, "types": [], "items": [] },
  "rules": {
    "playHours": { "from": "06:00", "to": "21:00" },
    "ambientHours": { "from": "05:00", "to": "23:00" },
    "heroMaxConcurrent": 1,
    "maxPlacementsPerCell": 3,
    "maxVisiblePlacements": 40,
    "krakenMaxTideM": 2.44,
    "roadSafety": { "categories": ["residential", "collector", "arterial"], "maxSpeedMph": 25,
                    "nullSpeedOnly": ["residential"], "ownershipPrefix": "public_", "requirePublic": true }
  },
  "caps": { "foundPerDay": 400, "foundPerWeek": 2800, "weekLateDays": 7, "findGraceDays": 2,
            "clockAheadMin": 60, "keptConfigs": 8 },
  "climate": [
    { "id": "wet", "from": "10-15", "to": "04-30" }, { "id": "dry", "from": "07-01", "to": "09-15" }
  ],
  "aggregates": { "asOf": "2026-11-02", "lowTrafficSquares": { "trails": [], "roads": [] },
                  "_note": "filled by scripts/build-play-content.mjs from the Worker's per-square player counts at publish" },
  "windows": {
    "exclusion-remembrance": { "every": "year", "in": ["mar"], "on": [30], "from": "00:00", "to": "24:00" }
  },
  "places": {
    "frog-rock": { "lat": 47.69594, "lon": -122.52357, "radiusM": 60,
                   "landmark": { "lat": 47.69612, "lon": -122.52347 },
                   "_note": "trigger snapped 21 m onto road-northeast-spargur-loop-road (25 mph); never Phelps Rd NE or N Madison Ave NE (35 mph)" },
    "ladybug": { "lat": 47.69594, "lon": -122.52357, "radiusM": 15, "landmark": { "lat": 47.69612, "lon": -122.52347 } },
    "fairy-door": { "lat": 47.66908, "lon": -122.57829, "radiusM": 25, "verify": true, "_note": "canopy; spot TBC on site" },
    "helix-house": { "lat": 47.66609, "lon": -122.57720, "radiusM": 15, "verify": true, "_note": "observatory exact point TBC" },
    "memorial-card": { "lat": 47.61560, "lon": -122.51019, "radiusM": 30 },
    "mill-trailhead": { "lat": 47.59535, "lon": -122.51975, "radiusM": 30 },
    "winslow-waterfront": { "lat": 47.62371, "lon": -122.51283, "radiusM": 800,
                            "_note": "Bainbridge terminal reference point, the one coordinate in every Play doc" }
  },
  "pools": {
    "coast-k2": { "goal": "roads", "members": ["stretch:road-crystal-springs-drive-northeast", "stretch:road-point-white-drive-northeast", "stretch:road-pleasant-beach-drive-northeast"] },
    "coast-k3": { "goal": "roads", "members": ["stretch:road-shannon-drive-1", "stretch:road-lovell-avenue-southwest-2", "stretch:road-rose-loop"] },
    "coast-k4": { "goal": "roads", "members": ["stretch:road-northeast-south-beach-drive-1", "stretch:road-rockaway-beach-road-northeast", "stretch:road-northeast-seaborn-road", "stretch:road-three-t-road-northeast"] },
    "coast-k5": { "goal": "roads", "members": ["stretch:road-manitou-beach-drive-northeast", "stretch:road-northeast-gertie-johnson-road"] },
    "coast-k6": { "goal": "roads", "members": ["stretch:road-point-monroe-drive-northeast", "stretch:road-reitan-road-northeast", "stretch:road-skogen-lane-northeast"] },
    "coast-k7": { "goal": "roads", "members": ["stretch:road-olympus-beach-road-northeast", "stretch:road-northeast-monte-vista-drive"] },
    "coast-t2": { "goal": "trails", "members": ["stretch:trail-fort-ward-trail-fort-ward-park", "stretch:trail-lower-fort-ward-trail"] },
    "coast-t3": { "goal": "trails", "members": ["stretch:trail-west-loop-trail", "stretch:trail-west-loop-trail-3", "stretch:trail-waypoint-woods-trail"] },
    "coast-t4": { "goal": "trails", "members": ["stretch:trail-fairy-dell-trail", "stretch:trail-woodland-drive-road-end", "stretch:trail-schel-chelb-trail"] },
    "coast-t5": { "goal": "trails", "members": ["stretch:trail-hawley-cove-trail", "stretch:trail-tchookwop-park-trail"] },
    "grand-forest-trails": { "goal": "trails", "members": ["park:Grand Forest"] },
    "west-loop": { "goal": "trails", "members": ["trail:trail-west-loop-trail"] },
    "winslow-way": { "goal": "roads", "members": ["road:road-winslow-way-east"] }
  },
  "quietZones": [
    { "id": "exclusion-memorial", "feature": "trail:trail-bainbridge-island-japanese-american-memorial-trail", "bufferM": 200 },
    { "id": "blakely-cemetery", "feature": "trail:trail-blakley-cemetary-trail", "bufferM": 100 }
  ],
  "excludedParks": ["Pritchard Park"],
  "quietDays": ["exclusion-remembrance"],
  "pauses": [],
  "ferry": {
    "route": "seattle-bainbridge", "crossingMin": 35,
    "terminals": { "bainbridge": { "lat": 47.62371, "lon": -122.51283 }, "seattle": { "lat": 47.6026, "lon": -122.3393, "verify": true } },
    "timetables": [
      { "id": "2026-fall-ILLUSTRATIVE", "from": "2026-09-20", "to": "2026-12-26",
        "weekday": { "fromSeattle": ["05:30","06:20","07:05","07:55","08:45","09:35","10:25","11:15","12:05","12:55","13:45","14:35","15:25","16:15","17:05","17:55","18:45","19:35","20:25","21:15","22:30","23:59"],
                     "fromBainbridge": ["04:45","05:20","06:10","06:55","07:45","08:35","09:25","10:15","11:05","11:55","12:45","13:35","14:25","15:15","16:05","16:55","17:45","18:35","19:25","20:15","21:25","22:50"] },
        "weekend": { "fromSeattle": ["06:10","07:55","09:35","11:15","12:55","14:35","16:15","17:55","19:35","21:15","22:30","23:59"],
                     "fromBainbridge": ["05:20","06:55","08:35","10:15","11:55","13:35","15:15","16:55","18:35","20:15","21:25","22:50"] },
        "weekendDates": ["2026-11-26", "2026-12-25"] }
    ]
  },
  "tides": { "station": "9445882", "years": [2026, 2027, 2028, 2029] },
  "items": [
    {
      "id": "treasure-weekly-trails", "type": "treasure", "lore": "treasure", "goals": ["trails"],
      "when": { "every": "week", "on": ["mon"], "from": "05:00", "to": "24:00", "days": 7 },
      "where": { "trails": { "pick": 12, "from": "pool:play-safe-trails" } }, "count": 12,
      "tiers": [ { "tier": "sea_glass", "marks": 25, "count": 7 }, { "tier": "agate", "marks": 40, "count": 3 },
                 { "tier": "glass_float", "marks": 60, "count": 1 },
                 { "tier": "ships_bell", "marks": 150, "count": 1, "everyNthWeek": 4, "else": "glass_float" } ],
      "spread": { "maxPerSquare": 1, "minApartM": 300, "lowTrafficShare": 0.4, "quadrants": 4 },
      "spotCooldown": 4, "repeat": "weekly_set", "resolve": { "by": "proximity", "radiusM": 20 },
      "copy": { "title": "{tier} on {place}", "body": "This week's treasure. Gone at the end of the week.", "found": "{tier}, found", "missed": "This week's treasures have washed away. New ones Monday." },
      "a11y": { "label": "{tier}, {marks} marks", "announce": "Found {tier}. {marks} marks when you finish." },
      "fx": "treasure_glint"
    },
    {
      "id": "treasure-weekly-roads", "type": "treasure", "lore": "treasure", "goals": ["roads"],
      "when": { "every": "week", "on": ["mon"], "from": "05:00", "to": "24:00", "days": 7 },
      "where": { "roads": { "pick": 20, "from": "pool:play-safe-roads" } }, "count": 20,
      "tiers": [ { "tier": "sea_glass", "marks": 25, "count": 12 }, { "tier": "agate", "marks": 40, "count": 5 },
                 { "tier": "glass_float", "marks": 60, "count": 2 },
                 { "tier": "ships_bell", "marks": 150, "count": 1, "everyNthWeek": 4, "else": "glass_float" } ],
      "spread": { "maxPerSquare": 1, "minApartM": 300, "lowTrafficShare": 0.4, "quadrants": 4 },
      "spotCooldown": 4, "repeat": "weekly_set", "resolve": { "by": "proximity", "radiusM": 25 },
      "copy": { "title": "{tier} on {place}", "body": "This week's treasure. Gone at the end of the week.", "found": "{tier}, found", "missed": "This week's treasures have washed away. New ones Monday." },
      "a11y": { "label": "{tier}, {marks} marks", "announce": "Found {tier}. {marks} marks when you finish." },
      "fx": "treasure_glint"
    },
    {
      "id": "egg-ladybug", "type": "egg", "lore": "frog", "hidden": true, "notBefore": "2026-10-26",
      "when": { "every": "always" },
      "where": { "roads": { "at": "place:ladybug" }, "trails": { "at": "place:ladybug" } },
      "marks": 20, "repeat": "once", "resolve": { "by": "proximity", "radiusM": 15 },
      "sources": ["https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington)", "https://assets.atlasobscura.com/places/frog-rock-bainbridge-island"],
      "copy": { "title": "Frog Rock's ladybug", "body": "", "found": "The year after the frog was painted, its painters also painted the small rock beside it as a ladybug." },
      "a11y": { "label": "Easter egg", "announce": "An easter egg. 20 marks when you finish." }
    },
    {
      "id": "egg-fairydoor", "type": "egg", "lore": "forest", "goals": ["trails"], "hidden": true, "canopy": true,
      "notBefore": "2026-10-26", "when": { "every": "always" },
      "where": { "trails": { "at": "place:fairy-door" } },
      "marks": 20, "repeat": "once", "resolve": { "by": "proximity", "radiusM": 25 },
      "copy": { "title": "Fairy door", "body": "", "found": "A door too small for you. Knock anyway. (Made up for this app.)" },
      "a11y": { "label": "Easter egg", "announce": "An easter egg. 20 marks when you finish." }
    },
    {
      "id": "riddle-frog", "type": "riddle", "lore": "riddle", "notBefore": "2026-10-26",
      "when": { "every": "always" },
      "where": { "roads": { "at": "place:frog-rock" }, "trails": { "at": "place:frog-rock" } },
      "stars": 1, "repeat": "once", "resolve": { "by": "riddle", "answer": "place:frog-rock", "radiusM": 30 },
      "sources": ["https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington)"],
      "copy": { "title": "Riddle", "body": "I sit by the road but never hop. Two islanders painted me in 1971. Find me on the north end, by a quiet loop road.", "found": "Claimed. It was Frog Rock." },
      "a11y": { "label": "Riddle, 1 star", "announce": "A riddle. Solve it at home, then walk there to claim it." }
    },
    {
      "id": "riddle-bond", "type": "riddle", "lore": "riddle", "goals": ["trails"], "notBefore": "2026-10-26",
      "when": { "every": "always" },
      "where": { "trails": { "along": "pool:grand-forest-trails", "pick": 1 } },
      "stars": 2, "repeat": "once", "resolve": { "by": "riddle", "answer": "pool:grand-forest-trails", "minM": 100 },
      "sources": ["https://www.bi-landtrust.org/grand-forest/"],
      "copy": { "title": "Riddle", "body": "In 1991 islanders voted a bond to save a forest from sale. Walk a hundred metres of it.", "found": "Claimed. It was the Grand Forest." },
      "a11y": { "label": "Riddle, 2 stars", "announce": "A riddle. Solve it at home, then walk there to claim it." }
    },
    {
      "id": "riddle-weekly-trails", "type": "riddle", "lore": "riddle", "goals": ["trails"], "enabled": false,
      "_v2": "2 weekly riddles per goal (product § 4.4); text bank keyed by answer place",
      "when": { "every": "week", "on": ["mon"], "from": "05:00", "to": "24:00", "days": 7 },
      "where": { "trails": { "pick": 2, "from": "pool:play-safe-trails" } }, "count": 2,
      "stars": 2, "repeat": "once", "resolve": { "by": "riddle", "answer": "pool:play-safe-trails", "radiusM": 30 },
      "copy": { "title": "Riddle of the week", "body": "{riddle}", "found": "Claimed. It was {place}." },
      "a11y": { "label": "Riddle", "announce": "A riddle. Solve it at home, then walk there to claim it." }
    },
    {
      "id": "kraken-roads", "type": "obstacle", "lore": "kraken", "goals": ["roads"], "hero": true, "priority": 60,
      "when": { "every": "week", "on": ["thu"], "from": "06:00", "to": "20:00", "days": 4 },
      "where": { "roads": { "rotate": ["pool:coast-k5", "pool:coast-k2", "pool:coast-k6", "pool:coast-k4", "pool:coast-k7", "pool:coast-k3"], "epoch": "2026-10-29" } },
      "bands": [ { "pct": 60, "marks": 60 }, { "pct": 80, "marks": 70 }, { "pct": 95, "marks": 80 } ],
      "repeat": "per_attack", "resolve": { "by": "segments", "minShare": 0.6 },
      "copy": { "title": "The kraken is ashore", "body": "It's inking {coast}. Walk most of an inked road to send it back.", "found": "You sent it back", "missed": "The kraken slipped away. It'll be back next week." },
      "a11y": { "label": "Kraken at {coast}", "announce": "Kraken repelled on {place}. {marks} marks when you finish." },
      "fx": "kraken_attack"
    },
    {
      "id": "kraken-trails", "type": "obstacle", "lore": "kraken", "goals": ["trails"], "hero": true, "priority": 60,
      "when": { "every": "week", "on": ["thu"], "from": "06:00", "to": "20:00", "days": 4 },
      "where": { "trails": { "rotate": ["pool:coast-t4", "pool:coast-t2", "pool:coast-t3", "pool:coast-t5"], "epoch": "2026-10-29" } },
      "bands": [ { "pct": 60, "marks": 60 }, { "pct": 80, "marks": 70 }, { "pct": 95, "marks": 80 } ],
      "repeat": "per_attack", "resolve": { "by": "segments", "minShare": 0.6 },
      "copy": { "title": "The kraken is ashore", "body": "It's inking {coast}. Walk most of an inked stretch to send it back.", "found": "You sent it back", "missed": "The kraken slipped away. It'll be back next week." },
      "a11y": { "label": "Kraken at {coast}", "announce": "Kraken repelled on {place}. {marks} marks when you finish." },
      "fx": "kraken_attack"
    },
    {
      "id": "ch-sweep", "type": "challenge", "lore": "challenge",
      "when": { "every": "week", "on": ["sat"], "from": "00:00", "to": "24:00", "days": 2 },
      "marks": 100, "repeat": "once", "resolve": { "by": "challenge" },
      "challenge": { "scope": "solo", "metric": "completed", "target": { "trails": 3, "roads": 5 } },
      "copy": { "title": "Weekend sweep", "body": "Finish {target} {goal.items} this weekend. Short ones count." },
      "a11y": { "label": "Weekend sweep", "announce": "Weekend sweep: {progress} of {target}." }
    },
    {
      "id": "ch-krakenweek", "type": "challenge", "lore": "kraken",
      "when": { "every": "week", "on": ["thu"], "from": "06:00", "to": "20:00", "days": 4 },
      "marks": 75, "repeat": "once", "resolve": { "by": "challenge" },
      "challenge": { "scope": "island", "metric": "stretches_repelled", "minPlayersPerStretch": 3, "retreatMarks": 50 },
      "copy": { "title": "Kraken week", "body": "Will islanders repel every stretch before Sunday evening?" },
      "a11y": { "label": "Kraken week", "announce": "{progress} of {target} stretches sent back by islanders." }
    },
    {
      "id": "lore-frog", "type": "lore", "lore": "frog", "notBefore": "2026-10-26",
      "when": { "every": "always" },
      "where": { "roads": { "at": "place:frog-rock" }, "trails": { "at": "place:frog-rock" } },
      "marks": 15, "repeat": "once", "resolve": { "by": "proximity", "radiusM": 60 },
      "sources": ["https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington)", "https://assets.atlasobscura.com/places/frog-rock-bainbridge-island"],
      "copy": { "title": "Frog Rock", "body": "Two stacked granite boulders, painted as a frog by two residents in June 1971 (sources give June 6 and June 7)." },
      "a11y": { "label": "Frog Rock, island story", "announce": "Frog Rock. 15 marks when you finish." },
      "fx": "frog_rock"
    },
    {
      "id": "lore-mill", "type": "lore", "lore": "history", "notBefore": "2026-10-26",
      "when": { "every": "always" },
      "where": { "trails": { "at": "place:mill-trailhead" }, "roads": { "along": "road:road-blakely-hill-road-northeast", "pick": 1 } },
      "marks": 15, "repeat": "once", "resolve": { "by": "proximity", "radiusM": 60 },
      "sources": ["https://www.historylink.org/File/1053", "https://en.wikipedia.org/wiki/Blakely_Harbor"],
      "copy": { "title": "Port Blakely mill", "body": "Captain William Renton's mill opened in 1864; by 1882 it was the largest on the Pacific Coast." },
      "a11y": { "label": "Port Blakely mill, island story", "announce": "Port Blakely mill. 15 marks when you finish." }
    },
    {
      "id": "lore-memorial", "type": "lore", "lore": "remembrance",
      "when": { "every": "always" },
      "where": { "roads": { "at": "place:memorial-card" }, "trails": { "at": "place:memorial-card" } },
      "marks": 0, "repeat": "once", "resolve": { "by": "none" },
      "sources": ["https://www.nps.gov/places/the-bainbridge-island-japanese-american-exclusion-memorial.htm"],
      "copy": { "title": "Nidoto Nai Yoni", "body": "On March 30, 1942, 227 Japanese American islanders were forced from their homes and sent from the Eagledale ferry dock, the first removal under Executive Order 9066. Let it not happen again." },
      "a11y": { "label": "Remembrance", "announce": "Remembrance: the Japanese American Exclusion Memorial.", "reducedMotion": "static_icon" },
      "_note": "inside its own quiet zone by design: card marker only, a visit stamp with no number, no fx"
    },
    {
      "id": "ferry-sprite", "type": "ambient", "lore": "ferry",
      "when": { "every": "day", "from": "05:00", "to": "23:00", "anchor": { "at": "ferry.depart.bainbridge", "before": "0m", "after": "35m", "pick": "all" } },
      "where": { "trails": { "at": "place:winslow-waterfront" }, "roads": { "at": "place:winslow-waterfront" } },
      "resolve": { "by": "none" },
      "copy": { "title": "Ferry crossing", "body": "" },
      "a11y": { "label": "Ferry", "announce": "Ferry crossing Eagle Harbor, by the timetable.", "reducedMotion": "static_icon" },
      "fx": "ferry_crossing"
    },
    {
      "id": "lore-owl", "type": "creature", "scope": "personal", "lore": "forest", "goals": ["trails"], "enabled": false,
      "_v2": "creatures: creature hours only, every fact sourced, safety line on the card",
      "when": { "every": "day", "from": "sunrise+30m", "to": "sunset-30m" },
      "where": { "trails": { "along": "pool:grand-forest-trails", "pick": 1 } },
      "marks": 15, "repeat": "per_season", "resolve": { "by": "proximity", "radiusM": 60 },
      "sources": ["https://www.bainbridgereview.com/2017/08/11/heads-up-owl-attacks-on-the-rise-round-bainbridge/"],
      "copy": { "title": "Barred owl", "body": "Barred owls live in island woods. Give owls space, especially in spring." },
      "a11y": { "label": "Barred owl", "announce": "A barred owl. 15 marks when you finish." }
    }
  ]
}
```

Built-ins and conveniences, all part of the schema:

- The pools `pool:play-safe-trails` and `pool:play-safe-roads` are built in: every included trail outside quiet
  features and `excludedParks` and not touching a quiet zone, and every counted road passing `roadSafety` and not
  touching a quiet zone.
- `stretch:<id>` is the part of a trail or road within 60 m of `coastline.json`, computed at pack build; stretches
  under 100 m are rejected.
- `rotate` placements choose the coast by whole weeks since `epoch` (a Thursday), with no RNG.
- `every: "always"` gives permanent items one occurrence keyed by `notBefore`
  (`egg-ladybug@2026-10-26`), resolvable in daylight.
- `challenge: { … }` is the challenge-only sub-object: `scope` (`solo` | `island`), `metric` (`completed`,
  `stretches_repelled`; v1.1 adds the trails-only solo metrics `climb` and `parks`, PD-26; v2 adds `squares_lifted`,
  `new_metres`), `target`, and for island challenges `minPlayersPerStretch` and `retreatMarks` (F-RETREAT). There is
  no timing metric. `climb` = Σ `gain_m` of non-quiet trails newly completed inside the window; the pack `target`
  is in metres (`{ "trails": 150 }`), and `progress` / `target` travel in integer **decimetres** on the wire and in
  `/challenges`. `parks` = distinct Play parks with ≥ 1 new non-quiet completion inside the window (`{ "trails": 3 }`).
  Both require `goals: ["trails"]`, a target keyed `trails` only and a whole-day window (validator); the effective
  target is `min(default, what remains for the player)` and the item is hidden when nothing remains.

Copy tokens: `{place}`, `{coast}`, `{tier}`, `{marks}`, `{target}`, `{progress}`, `{end}`, `{n}` and `{riddle}`,
plus the goal vocabulary tokens `{goal.item}` and `{goal.items}`, are substituted by composeApp through
`GoalVocabulary`, so road and trail words never mix.

### 4.8 Delivery, cache and validation on device

- `GET /v1/play/content?v=<cachedVersion>` with `If-None-Match: <etag>` returns `304` or `200 {config}`.
  `ETag: "pc-<version>-<sha256[0:12]>"`, `Cache-Control: public, max-age=300`. `?v=` lets the Worker answer 304
  when the version is equal even without an ETag, and appears in logs.
- `PlayContentRepository` (composeApp, modelled on `LeaderboardRepository`) caches at
  `<appData>/play/content.json` as `{etag, version, fetchedAt, body}`. It refreshes on foreground when
  `now − lastCheck > 1 h` **and Play is on** (Play off means no request: pure mode is unchanged).
- Parse and validate run off Main. The result is `PlayConfigState(config, source = BUNDLED|CACHED|FRESH,
  rejected: String?)`. A rejection keeps the previous config, logs `AppLog.w("play", …)` and shows in the drawer.
- Tides: `GET /v1/play/tides/{year}` (immutable, `Cache-Control: public, max-age=31536000`) is fetched only when
  the bundled years do not cover today + 60 days.
- Server side, the Worker bundles the **last 8 published packs** (`backend/src/data/play/<version>.json`, written by
  `scripts/build-play-content.mjs` from `data/play/published/`) so a claim made against an older pack still
  validates (product § 3.5 "Content version"); `GET /v1/play/content` serves only the newest.

---

## 5. Device-side scheduler: `PlayScheduler` in `:play`

### 5.1 Module

`app/play/` is a KMP library cloned from `app/tracking/build.gradle.kts`: jvm, android, iosArm64 and
iosSimulatorArm64. Dependencies are kotlinx-serialization-json, kotlinx-datetime and kotlin-test, with **no**
dependency on `:coverage` or composeApp. Add `include(":play")` in `app/settings.gradle.kts`. `verify.kmp` in
`.agents/profile.yml` already names `:play:jvmTest`, so the gate is red until stage 1 lands.

```
play/src/commonMain/kotlin/com/bainbridgetrails/play/
  rng/SplitMix64.kt  rng/Fnv1a.kt
  time/Sun.kt  time/IslandTime.kt  time/TideTable.kt  time/FerryTimetable.kt
  config/PlayConfig.kt  config/PlayConfigValidator.kt  config/Recurrence.kt  config/TimeExpr.kt
  schedule/PlaceIndex.kt  schedule/DayPlan.kt  schedule/PlayScheduler.kt  schedule/Density.kt
```

### 5.2 API

```kotlin
interface PlaceIndex {                    // implemented in composeApp over DatasetRepository (+ trailheads.json)
    fun segments(ref: FeatureRef, goal: Goal): List<SegRef>     // SegRef(id, midLat, midLon, lengthM, speedMph?, category?)
    fun point(ref: PlaceRef): GeoPoint?
}
data class PlayContext(val goal: Goal, val recording: Boolean, val currentFeature: String? = null) // in memory only
data class PlayUserState(val salt: ULong?, val resolved: Map<String, Instant>, val foundOcc: Set<String>)  // salt: v2 only

class PlayScheduler(config: PlayConfig, places: PlaceIndex, tides: TideTable?, ferry: FerryTimetable?) {
    /** Pure, deterministic; memoized by the caller per (config.version, date, goal, salt). */
    fun dayPlan(date: LocalDate, goal: Goal, salt: ULong): DayPlan
    /** Cheap: filters the memoized plans of today (+ tomorrow for "upcoming"). */
    fun agenda(now: Instant, ctx: PlayContext, user: PlayUserState): PlayAgenda
}
data class DayPlan(val date: LocalDate, val sun: SunDay, val tides: List<TideEvent>, val occurrences: List<Occurrence>)
data class Occurrence(val key: String, val itemId: String, val start: Instant, val end: Instant,
                      val hero: Boolean, val placements: List<ResolvedPlacement>, val marks: Int, val variant: Int?)
data class PlayAgenda(
    val active: List<ActiveItem>,          // in window now, not killed, cooldown-filtered, sleeping flag at night
    val upcoming: List<UpcomingItem>,      // next 7 days, for "What's on" (island items only + "personal surprises" count)
    val day: DayInfo,                      // sunrise/sunset, today's tides, next ferry arrival (display only)
)
```

**Context and privacy.** `PlayContext.currentFeature` (the trail or road being walked, from the recording's live
match) is used only to sort "nearby" items first in What's on. The scheduler never receives coordinates and never
persists context. Placements carry geometry for the map, but that geometry comes from config and dataset, not
from the user.

### 5.3 Evaluation cost

- `dayPlan` costs O(items × occurrences per day × pool size for picks). With 20–60 items, pools ≤ 120 members and
  ≤ 3 sub-windows, that is a few thousand operations: **< 2 ms on a mid-range phone** and computed **once per
  day** per goal. It is memoized in `PlayRepository` (composeApp), keyed by `(config.version, date, goal,
  salt)`. Two to eight plans are kept (today, tomorrow, the next 7 days for What's on, built lazily).
- `agenda(now)` filters precomputed occurrences by instant, kill list and user state: **O(active occurrences) ≤
  O(items), well under 1 ms**. It runs at most once a minute (a ticker while the map is visible) and on state
  changes.
- No allocation in hot paths beyond the result lists. Sun is about 60 floating-point operations. TideTable looks
  up a day by binary search over a sorted `IntArray`.
- Perf guard: `PlaySchedulerPerfTest` (jvmTest) builds 365 day plans for the example config with a fake index
  (1,600 segments) and asserts total < 1.5 s on CI, a coarse ceiling against accidental O(n²). It also asserts
  10,000 `agenda()` calls in < 1 s, i.e. a ≤ 0.1 ms average with JIT. On-device timing is checked once with a
  debug-drawer "benchmark" button and recorded in the PR.

### 5.4 Test plan (`app/play/src/commonTest` + `jvmTest`)

Write these tests first, in this order:

| test | asserts |
|---|---|
| `SplitMix64Test` | reference vectors for 3 seeds; `nextInt` bounds and uniformity chi² (loose); `pick` independent of input order |
| `Fnv1aTest` | known vectors ("", "a", "foobar") and a UTF-8 id |
| `SunTest` | 12 dates vs NOAA calculator, ±2 min; civil dawn/dusk present; DST days |
| `IslandTimeTest` | 2027-03-14 02:30 → 03:00; 2027-11-07 01:30 resolves to the first instance; week id = Monday |
| `TideTableTest` | parse `9445882-2027.json` fixture; `events(day)` count 3–4; cosine interpolation monotonic between extrema; out-of-range → null |
| `FerryTimetableTest` | weekday/weekend/holiday column choice; season boundaries; arrival = departure + crossingMin |
| `TimeExprTest` | `HH:MM`, `sunset+30m`, `dawn-15m`, `+6d`, `24:00`; invalid strings rejected |
| `RecurrenceTest` | thu weekly; `last-sun` Feb 2027 = 28; `1sat`/`3sat` with `days:2`; `5sat` only when present; `-1`; `dates`+`fallback`; `except`; season filter; anchor highest-high + fallback; ferry `max:3` |
| `RecurrenceGoldenTest` | reproduces `data/play/golden/date-rules-2027.json` (shared with the Worker) |
| `PlayConfigParseTest` | the example parses; unknown keys ignored; wrong enum → error; schema 2 → rejected |
| `PlayConfigValidatorTest` | each rule in §4.3 has a failing fixture (bad ref, 30 mph road, null-speed collector, private road, quiet-zone point, pool member touching a quiet zone, Pritchard Park member, dusk-only find, creature at sunset, tide-anchored obstacle, reward ≠ product, caps ≠ product, in-place edit of a published id, blank a11y, hero conflict) |
| `RoadSafetyTest` (jvm) | over the real `roads.json` when present: 370 of 430 counted roads pass on the 2026-10-20 data, and every road in a `coast-*` pool passes (skipped with a message when data is absent) |
| `QuietZoneTest` | buffers built from segment geometry; the memorial and cemetery fixtures reject touching members; the remembrance card is allowed inside its own zone |
| `KrakenRotationTest` | `kraken-roads@2026-10-29` → K5, `@2026-11-05` → K2 … wraps after 6; trails after 4; window Thu 06:00 → Sun 20:00 across DST |
| `TideGateTest` | stretch inactive above 2.44 m at the fix time; inactive when the table does not cover the date |
| `PlayConfigBundledTest` (jvm) | the bundled `composeResources/files/play/play-content.json` validates against a jvm `PlaceIndex` built from `data/dist` (skipped with a message when data is absent: CI has no map data) |
| `DensityTest` | 4 placements in one cell → 3 kept, by fill order; island never displaced by personal |
| `HeroConflictTest` | at most 1 hero active at any instant over 400 days; (v2) the Oct 31 attack never overlaps the weekly one |
| `DeterminismTest` | same inputs → identical plans; editing item B leaves item A's placements unchanged; a different salt changes only personal items |
| `DaylightRuleTest` | for every day of 2027 no occurrence is resolvable outside [civil dawn, civil dusk] ∩ 06:00–21:00; no creature outside [sunrise + 30 m, sunset − 30 m]; overnight items flagged `sleeping` |
| `PlaySchedulerGoldenTest` (jvm) | `golden/agenda-2027-{trails,roads}.tsv`: one row per (date, occKey, start, end, placement refs) for island items over all of 2027 with the example config, a fixed seed and a fake index. Regenerate with `-Pplay.updateGolden=true`; the PR diff is the review artifact. |
| `PlaySchedulerPerfTest` (jvm) | §5.3 ceilings |

---

## 6. Notifications policy: decision SD-10 (v1: none; "Island notes" v2)

**v1: no notifications.** Everything time-based is shown in the in-app **What's on** surface (designed in the UI
spec): today's island items with times and an `upcoming` list for 7 days. The reasons:

1. The only background hook in the app, the trailhead geofence (`record/GeofenceOptIn.kt`,
   `tracking/Geofences.kt`), is a **prototype** declared only in `-PgeofencePrototype=true` debug builds and behind
   a prominent background-location disclosure. Play must not become the reason to ship background location.
2. "Never while driving" cannot be guaranteed without activity recognition, which means another sensitive
   permission.
3. Calm is the brand ("Chart room"). Push for a game layer is the fastest way to make Play feel like an ad.

**Later (its own design gate), if the owner wants it (D-6).** A separate opt-in, "Island notes", off by default and
independent of Play's opt-in:

- **Local notifications only**, scheduled ahead for the next 7 days whenever the app foregrounds or the config
  changes. Android uses `AlarmManager` inexact alarms or WorkManager; iOS uses `UNCalendarNotificationTrigger`.
  No server push and no location.
- **At most 1 per island day**, at a user-chosen time between 07:00 and 19:00 (default 07:30). It is silent and
  low priority (Android `IMPORTANCE_LOW`; iOS `interruptionLevel = .passive`), so the OS driving modes (iOS Driving
  Focus, Android driving mode) suppress it.
- Content: one line chosen from today's island items by priority ("The kraken is ashore at Manitou until Sunday
  evening"). Never personal eggs (spoilers), never a countdown, never "hurry", never ferry departures,
  never after sunset. The copy is generated from the same `Copy` fields, so it is reviewed with the config.
- Skipped on days with nothing but ambient content, and on `exclusion-remembrance`.
- If the geofence **ships** and the user has opted into both: an arrival notification may append one Play line
  ("This week's treasure is near here"), within the same 1-per-day budget. No new geofences are registered for Play.

---

## 7. Server side

### 7.1 Principles

The server stores **claims and derived marks only**: occurrence keys a user claimed, the week id of each, and the
marks it derived. It stores no positions, no claim times, no walk times and **never a day**. A claim's week id plus
a fixed-place occurrence key tells the server the user was within that item's radius of that place some time that
week; that is new coarse information compared with today's backend, so it is disclosed in `play_about_privacy` and
in `docs/legal/privacy-policy.md` before Play ships (D-5, product OD-19). Claims sync only when signed in **and**
Play is on. All tables are scoped `(user_id, dataset)` like RD-7, and `DELETE /v1/me` cascades.

The economy is **product § 3** (one model): claims ride the coverage upload; the server derives every mark from its
content table and accepted coverage and never trusts a client mark; caps and time rules are product § 3.5, stated
once there and mirrored in the pack's `caps`.

### 7.2 Claims on the coverage upload

**Migration numbering.** `backend/migrations/0004_cleanup.sql.pending` exists (RD-7 cleanup, destructive, applied
by renaming). Play's migration is `0005_play.sql` and may only land **after** 0004 has been renamed and applied per
`backend/README.md` (§ "Rolling out migration 0003"); otherwise wrangler would apply 0005 before 0004. If 0004 is
still pending when Play's backend stage starts, resolve it first (or renumber Play's file to follow whatever is
applied). The migration is additive only:

```sql
CREATE TABLE play_claims (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL CHECK (dataset IN ('trails','roads')),
  occ     TEXT NOT NULL,          -- occurrence key "<itemId>@<yyyy-mm-dd>[#n]" (immutable item ids)
  kind    TEXT NOT NULL,          -- treasure | egg | riddle | ink | lore | challenge
  week    TEXT NOT NULL,          -- week id = Monday date; never a day
  config  TEXT NOT NULL,          -- pack version the claim was evaluated against
  marks   INTEGER NOT NULL,       -- derived by the server from the content table
  PRIMARY KEY (user_id, dataset, occ)
) WITHOUT ROWID;
CREATE INDEX play_claims_week ON play_claims (user_id, dataset, week);

CREATE TABLE play_weeks (          -- W-DAY / W-WEEK inputs and the weekly found cap
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL, week TEXT NOT NULL,
  day_keys INTEGER NOT NULL DEFAULT 0, found_marks INTEGER NOT NULL DEFAULT 0, kept INTEGER,
  PRIMARY KEY (user_id, dataset, week)
) WITHOUT ROWID;

CREATE TABLE play_totals (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL,
  cov_marks INTEGER NOT NULL DEFAULT 0,   -- max(previous, new): monotone W-COV
  derived_marks INTEGER NOT NULL DEFAULT 0, found_marks INTEGER NOT NULL DEFAULT 0,
  week_marks INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, dataset)
) WITHOUT ROWID;

CREATE TABLE play_attack_stretches ( -- island aggregate for F-RETREAT / ch-krakenweek; counts only
  dataset TEXT NOT NULL, occ TEXT NOT NULL, repelled_by INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (dataset, occ)
) WITHOUT ROWID;
```

**v1.1 trails ledger (`0007_play_trails.sql`, additive; PD-26).** The six trails rules are paid **by value**, so one
MAX'd `derived_marks` cannot hold them (a drop in one rule would mask a new step in another):

```sql
CREATE TABLE play_trail_paid (     -- trails only: climb:<m> | mile:<mi> | high:<trailId> | park1:<park> | parkc:<park> | island
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL, key TEXT NOT NULL, marks INTEGER NOT NULL,
  PRIMARY KEY (user_id, dataset, key)
) WITHOUT ROWID;
ALTER TABLE play_totals ADD COLUMN trail_marks INTEGER NOT NULL DEFAULT 0;  -- = SUM(play_trail_paid.marks); total adds it
-- (a `play_progress_keys` table was planned and dropped before any apply: PD-26 D12)
```

Ledger rows derive from coverage bits and completions only (no time, day or position); inserts are
`ON CONFLICT DO NOTHING` inside the upload batch, before the totals upsert, and are allowed on the no-change upload
path. `DELETE /v1/me` removes both tables. Remote apply of 0007 is the owner's, with the Worker deploy (OD-19 first).

**Request.** `PUT /v1/coverage` (existing auth, rate limit and body limits) gains two optional fields, product § 3.10:
`playConfig` (string) and `play: [{ walkId, claims: [{ id, kind, weekId, band? }], dayKey?, hints? }]`, at most one
element per walk and at most 50 claims per upload. `SyncRepository` builds one `CoverageUpload` that batches up to
200 walks across weeks (2,000 on the first backfill); each `play` element is tied by `walkId` to a `walks[]` entry of
the same upload. Walks over the cap keep their Play element queued for the next upload.

**Server checks**, after the existing coverage plausibility passes and inside the same `DB.batch` that credits the
walk ids (so a retry can't double count). A failing claim is reported with a code; the others still apply:

1. `playConfig` is one of the Worker's last 8 packs, else every claim gets `stale_config`.
2. `id` is an occurrence key of an enabled, non-killed item of this dataset in that pack, the occurrence was
   active in `weekId`, and `kind` matches the item type. Otherwise `unknown_item` / `bad_occurrence`.
3. `walkId` is credited by this upload (new in `walks[]`). Otherwise `not_this_walk`. This replaces the old
   `no_walk` rule (any walk that day unlocked every occurrence).
4. Line and stretch items (kraken stretches, feature riddles): the anchor segments carry this user's coverage bits
   after the merge; for `ink`, at least `band` % of the stretch's segment length does. Otherwise `no_bits`.
5. Time rules (product § 3.5): occurrence start ≤ server now + 1 h (`too_early`); the find occurrence ended ≥ the
   upload's server island date − 2 days (`too_late`); `weekId` is the current or previous week; `dayKey`s within
   week end + 7 days.
6. Repeat rule (`repeat`) and `INSERT OR IGNORE` on `(user, dataset, occ)`: a re-sent claim is a no-op that still
   reports `accepted`.
7. **Marks are derived here**: from the item's `marks`, tier, band or stars (hints from `hints`, −25 % each, floor
   50 %), never from the request. The weekly found cap (2,800) is applied per `week`; over it the claim is accepted
   with 0 marks (`week_cap`).

**Derived marks without a claim.** W-COV (fixed rate, `cov_marks = max(previous, new)`), W-DONE and W-SQUARE from
the accepted coverage (quiet trails excluded); W-DAY from `dayKey`s (≤ 7 per week); W-WEEK at week close from
`day_keys ≥ 2` (lazily on the first read after Monday 00:00, or in the cron); F-RETREAT and C-ISLAND by the cron
after Sunday 20:00.

**Response.** When the request carried `play`, the 200 body gains `play: { accepted: [occ], rejected: [{ occ, code }],
total, week }`; otherwise it is byte-identical to today. A 422 rejects the coverage and its claims together; the
client marks those entries `local_only`. `GET /v1/play/me?dataset=` returns `{ total, week, perKind, occ: [...] }`
for the Logbook restore (product § 3.6.5). For `dataset=trails` (v1.1, PD-26) `perKind` also carries `climb`,
`high_ground`, `milestone`, `park_first`, `park_complete`, `island` (ledger key counts) and the body gains
`paid: string[]` (the sorted ledger keys, seeding the device's `trailPaid` on a Restored write); the roads body is
byte-identical to v1.

Write budget: one claim is about 3 row writes (claim, week, totals). 1,000 active players × 10 claims a week is
about 4.3k writes a day, inside D1's 100k writes a day. D1 started **enforcing** the free-tier limits on 2026-09-01,
so an excess fails the queries until midnight UTC ([D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/),
[changelog](https://developers.cloudflare.com/changelog/post/2026-09-01-d1-free-tier-limit-enforcement/)). The
50-claims-per-upload cap and the weekly cap bound the worst case.

### 7.3 Challenges: window-boxed, never timed

Timed races, the `race` item type, the `play_races` / `play_race_entries` tables, the `race` field on
`PUT /v1/coverage` and named standings are **cut** (review P0-2).

- **Solo** (`ch-sweep`): counted on device from claimed walk results whose walk **finished** inside the window
  (island time). The completing walk's `play` element carries `{ id: "ch-sweep@<Saturday>", kind: "challenge" }`.
  The server accepts it when the user's newly completed trails/roads credited by uploads received inside
  [window start, window end + 2 days] reach the target; it never sees a time.
- **Solo, trails only (v1.1, PD-26):** `ch-climb` (`climb`, Mon → Mon, 150 m = 1,500 dm) and `ch-parkhop` (`parks`,
  Sat → Mon, 3 parks). Same claim shape; the server evaluates them from the upload's Play completions (quiet trails
  and Pritchard Park add nothing), one upload contributes to **at most one occurrence per item** (the one active at
  `now`, else the latest still in grace), park-hop progress = the distinct parks of that upload's new completions added
  to `play_progress` (nothing else stored; a park reached in two separate uploads counts twice, PD-26 D12), and the claim is accepted when progress ≥ the personal target
  (`min(default, remaining)`). `/challenges` returns the personal target when signed in (default otherwise), climb
  `progress` / `target` in decimetres, and omits an item whose remaining is 0 for that user.
- **Island** (`ch-krakenweek`): the server increments `play_attack_stretches.repelled_by` once per distinct user
  per stretch on accepted `ink` claims. The cron after Sunday 20:00 checks every stretch ≥ 3 and credits F-RETREAT
  (+50) and C-ISLAND (+75) to every user with ≥ 1 accepted `ink` claim in that attack.
- **Results are `{band, count}` only.** `GET /v1/play/challenges?dataset=` returns
  `{ current: [{ occ, window, progress, target, you: { band }, bands: [{ band, count }] }] }`. Bands are
  `top | middle | starting`; counts below 5 are shown as "fewer than 5", larger ones rounded to 5, like stats. No
  names, no scores, no ranks, no timestamps.

### 7.4 Cron on the free tier

- Free plan: **5 cron triggers per account**, **10 ms CPU per cron invocation**. Time spent waiting on I/O (D1
  queries, fetch) does not count ([Workers limits](https://developers.cloudflare.com/workers/platform/limits/)).
- **No new trigger.** Play adds `playDaily(env, now)` to the existing `17 9 * * *` `daily()`: close kraken attacks
  that ended (F-RETREAT, C-ISLAND), compute W-WEEK for weeks that closed, refresh the per-square player counts that
  the next pack publish bakes into `aggregates`, and purge attack aggregates older than 180 days. CPU is
  microseconds of JS around a few D1 calls.
- **Risk flagged for the existing cron.** `backend/README.md` says "Snapshot rebuilds run in the cron (15 min
  CPU)". That is the **paid** limit for intervals of an hour or more; free is 10 ms. Snapshot rebuilds are mostly
  D1 I/O, which is not counted, but the JS ranking work is. Play must not add CPU there. Measure with
  `wrangler tail` (owner) and fix the README line (doc-drift-auditor).
- A future WSF fetch (D-3), if chosen, runs inside the same cron (one fetch, a small JSON parse) and writes the
  current season's departures into a `play_overrides` D1 row merged into `GET /v1/play/content` (ETag includes the
  override hash). It is listed only so the schema stays compatible; it is not planned here.

### 7.5 Endpoints summary (added to `docs/mvp/api.md` as "Play")

| method | path | auth | notes |
|---|---|---|---|
| GET | `/v1/play/content?v=` | public | ETag / 304, `max-age=300`, public IP rate limit (existing 120 / 10 min) |
| GET | `/v1/play/tides/{year}` | public | immutable, 404 for years not bundled |
| PUT | `/v1/coverage` | user | **+ optional `playConfig`, `play`** (additive; absent = byte-identical request and response) |
| GET | `/v1/play/me?dataset=` | user | summary for the Play chip and the Logbook restore |
| GET | `/v1/play/challenges?dataset=` | public (`you` if signed in) | window, progress, `{band, count}` |
| GET | `/v1/play/board?dataset=` | public | (v2) product § 3.7.2 |
| PUT | `/v1/play/board-consent` | user | (v2) `{visible}`; the one call allowed when Play is turned off (product § 5.5) |

Removed: `PUT /v1/play/finds` (claims ride the coverage upload), `GET /v1/play/races*` (cut).

Claim codes (per claim, inside a 200 response): `stale_config`, `unknown_item`, `bad_occurrence`, `not_this_walk`,
`no_bits`, `too_late`, `too_early`, `week_cap` (accepted with 0 marks), `repeat` (accepted, no-op).

Request budget: content refresh ≤ 1 per user per hour of foreground, mostly 304. 1,000 players is ≤ 10k a day,
well within 100k requests a day.

---

## 8. Staged implementation plan (plan-author contract)

```
PLAN: Play scheduling — time model, config, PlayScheduler, content delivery, claims & challenges backend
      (acceptance criteria satisfied: 5 fully; enables 3 (content timing), 4 (lore placement), 8 (unit + backend tests))
PRECONDITIONS:
  - Branch from latest origin/main (CLAUDE.md), e.g. play/schedule.
  - This spec ACCEPTED (decisions.md, round-1 findings resolved); owner answers D-1..D-5 at least (§9).
  - ADRs recorded in docs/play/decisions.md (PD-1…, done). No UI in this plan: "What's on", Logbook and challenge
    screens belong to the Play UI plan and docs/play/ux.md (gate design_before_build). The e2e script for brief AC 8
    lives in docs/play/plan.md (README matrix).
```

**STAGE 0: ADRs (docs only) — done**
- files: `docs/play/decisions.md` (PD-1 … PD-17; the SD-n decisions of §0 are indexed there).
- test-first: n/a (docs)
- verify: `grep -c '^## PD-' docs/play/decisions.md` → 17
- rollback: revert the commit

**STAGE 1: `:play` module + RNG**
- files: `app/settings.gradle.kts` (+`include(":play")`), `app/play/build.gradle.kts` (cloned from tracking, deps:
  serialization-json, datetime), `play/.../rng/SplitMix64.kt`, `rng/Fnv1a.kt`
- test-first: `SplitMix64Test`, `Fnv1aTest`
- verify: `cd app && ./gradlew :play:jvmTest :composeApp:testAndroidHostTest` (verify.kmp)
- rollback: remove the include + directory

**STAGE 2: island time + sun**
- files: `play/.../time/IslandTime.kt`, `time/Sun.kt`
- test-first: `IslandTimeTest`, `SunTest` (NOAA reference values committed in the test with a source comment)
- verify: verify.kmp
- rollback: revert the files

**STAGE 3: tide table + ferry timetable**
- files: `scripts/build-tides.mjs`, `data/play/tides/9445882-{2026..2029}.json`, `data/play/ATTRIBUTION.md`,
  `play/.../time/TideTable.kt`, `time/FerryTimetable.kt`, test fixture copy in `play/src/jvmTest/resources/`
- test-first: `TideTableTest`, `FerryTimetableTest`
- verify: verify.kmp; `node scripts/build-tides.mjs --check` (offline: checks the committed files' shape and years)
- rollback: revert; scheduling falls back to anchor `fallback` windows

**STAGE 4: config schema + validator**
- files: `play/.../config/PlayConfig.kt`, `TimeExpr.kt`, `PlayConfigValidator.kt`, `schedule/PlaceIndex.kt`,
  `data/play/play-content.json` (the §4.7 example), `play/src/commonTest/.../FakePlaceIndex.kt`
- test-first: `TimeExprTest`, `PlayConfigParseTest`, `PlayConfigValidatorTest`, `RoadSafetyTest`, `QuietZoneTest`
- verify: verify.kmp
- rollback: revert

**STAGE 5: recurrence DSL**
- files: `config/Recurrence.kt`, `data/play/golden/date-rules-2027.json` (generated by a jvmTest with
  `-Pplay.updateGolden=true`, then committed)
- test-first: `RecurrenceTest`, `RecurrenceGoldenTest`
- verify: verify.kmp
- rollback: revert

**STAGE 6: PlayScheduler**
- files: `schedule/DayPlan.kt`, `PlayScheduler.kt`, `Density.kt`, `play/src/jvmTest/resources/golden/agenda-2027-*.tsv`
- test-first: `DensityTest`, `HeroConflictTest`, `DaylightRuleTest`, `KrakenRotationTest`, `TideGateTest`, `DeterminismTest`, `PlaySchedulerGoldenTest`,
  `PlaySchedulerPerfTest`
- verify: verify.kmp
- rollback: revert (no callers yet)

**STAGE 7: content pipeline + Worker read endpoints**
- files: `scripts/build-play-content.mjs` (validate + copy + `--check` + 14-day preview print; the hero
  simulation runs in Kotlin stage 6 and in this script's JS date-subset for challenge and kraken windows), `backend/src/data/play-content.json`,
  `backend/src/data/tides/*.json`, `backend/src/play/config.ts` (load, ETag), `backend/src/play/recurrence.ts`
  (server subset), `backend/src/routes/play.ts` (`GET content`, `GET tides`), `backend/src/index.ts` (ROUTES),
  `backend/package.json` (`build:play`; `test` runs `--check` like `build:legal`), `docs/mvp/api.md` (Play section),
  `app/composeApp/src/commonMain/composeResources/files/play/*`
- test-first: `backend/test/play-content.test.ts` (200 + ETag, 304 on If-None-Match and on `?v=`, tides 404/200),
  `backend/test/play-recurrence.test.ts` (golden parity), `PlayConfigBundledTest` (jvm)
- verify: `cd backend && npm test && npx tsc --noEmit` (verify.api) **and** verify.kmp
- rollback: revert; no client uses it yet

**STAGE 8: app wiring (repository, clock, debug drawer)**
- files: `composeApp/.../play/PlayContentRepository.kt` (cache/ETag/last-good, modelled on LeaderboardRepository),
  `play/PlayClock.kt`, `play/PlayRepository.kt` (memoized day plans, `agenda` StateFlow, user state file
  `play/state.json` incl. salt), `play/DatasetPlaceIndex.kt`, `net/ApiClient.kt` (+`playContent`, `playTides`),
  `graph/AppGraph.kt` (+`playContent`, `play`, `playClock`), `androidApp/src/debug/.../DebugPlugins.kt`
  (+`PlayClockPlugin`: time travel, source switch, agenda dump, benchmark), iOS launch args `-btPlayClock`,
  `-btPlayConfig`
- test-first: `composeApp/src/commonTest/.../PlayContentRepositoryTest.kt` (bundled when no cache; 304 keeps cache;
  invalid fresh → keeps last good + `rejected`; older cached version than bundled → bundled; Play off → no request),
  `PlayRepositoryTest` (fixed PlayClock; day rollover rebuilds plan; DST day)
- verify: verify.kmp, then `cd app && ./gradlew :androidApp:assembleTrailsDebug :androidApp:assembleRoadsDebug`
  (subset of verify.full)
- reachability: production call site = the Play UI plan's What's on/map layer (blocked on its design spec).
  Until then the agenda is reachable only via the debug drawer. **Flagged**: do not merge stage 8 to main without
  either the UI plan's first screen or a feature flag `play.enabled=false` default that keeps pure mode
  byte-identical (`PlayContentRepository` is never constructed when Play is off).
- rollback: revert; AppGraph additions are lazy and unused when Play is off

**STAGE 9: claims on the coverage upload (backend)**
- precondition: `0004_cleanup.sql.pending` renamed and applied (§7.2 migration numbering)
- files: `backend/migrations/0005_play.sql`, `backend/src/play/claims.ts` (checks, derived marks),
  `backend/src/routes/coverage.ts` (optional `playConfig` / `play` → same `DB.batch`), `backend/src/routes/play.ts`
  (`GET me`), `backend/src/data/play/<version>.json` (last 8 packs), `backend/src/routes/me.ts` (DELETE cascade
  check), `docs/legal/privacy-policy.md` + `npm run build:legal` (week-and-place disclosure, D-5)
- test-first: `backend/test/play-claims.test.ts`: accepted with server-derived marks (client cannot send marks);
  idempotent retry; `stale_config`; `unknown_item`; `bad_occurrence`; `not_this_walk`; `no_bits` (ink band above
  the user's coverage share); `too_late` (occurrence ended > 2 days before upload); `too_early`; `week_cap`;
  `dayKey` > 7 per week ignored; killed item; 422 coverage rejects claims too; deletion cascade; pure upload (no
  `play`) byte-identical response to today (snapshot of the JSON); `migrations.test.ts` extended for 0005
- verify: verify.api
- rollback: Worker revert (0005 is additive; tables are inert; old Workers ignore the new fields)

**STAGE 10: kraken aggregates + challenges backend**
- files: `backend/src/play/challenges.ts` (stretch counts, F-RETREAT / C-ISLAND, `ch-sweep` check, W-WEEK close),
  `backend/src/index.ts` (`daily()` → `playDaily`), `routes/play.ts` (`GET challenges`), `docs/mvp/api.md`
- test-first: `backend/test/play-challenges.test.ts`: stretch counted once per user; retreat only when every
  stretch ≥ 3; cron run twice → same credits; W-WEEK from `day_keys ≥ 2`; `ch-sweep` accepted only with enough
  new completions inside the window; response is `{band, count}` only (no names, no scores, counts < 5 hidden)
- verify: verify.api
- rollback: Worker revert

**STAGE 11: client claims in the coverage upload**
- files: `composeApp/.../play/PlayClaimQueue.kt` (per-walk Play elements, file like SyncFile),
  `sync/SyncRepository.kt` (optional provider → `CoverageUpload.playConfig` / `play` for the walks in this upload;
  walks over the cap keep their Play element queued), `net/ApiDtos.kt`, `net/ApiClient.kt`
- test-first: `PlayClaimQueueTest` (rejected → `local_only`; idempotent resend), `SyncRepositoryTest` extension
  (Play elements only for walks in `walks[]`; capped walks' elements stay queued; fields absent when Play off,
  request byte-identical)
- verify: verify.kmp, then verify.full before PR
- rollback: revert; the server tolerates absence

**CROSS-CUTTING**
- swip: n/a (`factory.swip: false`)
- debugdrawer: yes. `PlayClockPlugin` (stage 8) does time travel, config source switching, agenda dump and the
  benchmark.
- atlas: n/a (`product_atlas: none`)
- goldens: no UI snapshot tooling. The scheduler goldens (`agenda-2027-*.tsv`, `date-rules-2027.json`) are the
  review artifacts, and any diff must be explained in the PR.
- migrations: yes. `0005_play.sql` is additive and lands only after `0004_cleanup.sql.pending` is resolved (§7.2).
  Deploy order is the README's: migrate, then the Worker. No destructive step.

**REVIEW** (in order): adversarial-reviewer (this spec) → privacy-security-reviewer (claims and week-and-place
disclosure, claim anti-cheat) → simplification-reviewer → kmp-verifier (verify.full green) → adversarial-reviewer
(branch) before the PR.

**RISKS** (what could make this plan wrong):
1. Stage 8 has no production call site until the Play UI spec is accepted. Mitigation: the flag above.
2. The Worker and Kotlin recurrence implementations drift. Mitigation: the shared golden, and challenge and kraken windows limited to
   `HH:MM` times.
3. The NOAA script needs network at generation time. CI only runs `--check` on committed files.
4. Config growth: ≤ 60 items is the planning assumption. Beyond about 200 items, revisit the `dayPlan` cost and
   the config size (currently about 20 KB).

---

## 9. Risks and owner decisions

**Decisions needed from the owner**

| id | question | recommendation |
|---|---|---|
| D-1 | **Prue's house**: what and where is it? | The item is disabled until answered. |
| D-2 | Tides: approve the bundled NOAA hilo table (a yearly regenerate chore each October)? | Yes (SD-2). The alternative is to skip tides and run the kraken on clock times. |
| D-3 | Ferries: a static timetable you update each WSF season, or register for a WSF API access code (your email) plus a Worker cron fetch? | Static for v1. Revisit after WSF's 2026–27 Bainbridge schedule rebuild settles. |
| D-4 | Weather: confirm none in v1 (Open-Meteo is non-commercial; fairness concerns). | None. |
| D-5 | Privacy: a claim reveals the **week** someone played and, for a fixed-place item, that they were near that place that week (never the day). Accept with a privacy-policy update and honest copy? | Sync, with disclosure (product OD-19). The server stores week ids, never days. |
| D-6 | Notifications: confirm none in v1; a later "Island notes" (≤ 1 per day, local, opt-in) needs its own spec. | None in v1. |
| D-7 | (cut) Race metric. Timed and ranked races are cut (review P0-2); challenges are window-boxed. | — |
| D-8 | (v2) The personal salt is per install (two devices, two sets of eggs). Sync it? | No. |
| D-9 | (v2) Annual event dates (Harvest Fair, Rotary Auction) need a yearly config edit. Who owns that? | Owner, as part of the October tide chore. |
| D-10 | **Exclusion Memorial quiet zone, the Mar 30 quiet day and the remembrance card.** Is the wording and approach acceptable? No gameplay on Japanese American community events (Mochi Tsuki) without BIJAC's consent. | Keep it muted: no marks, no FX; Mochi Tsuki gameplay cut. Seek BIJAC review. |
| D-11 | Kill-switch latency is deploy time plus ≤ 1 h client refresh. Acceptable, or add a D1 admin kill overlay? | Acceptable for v1. |
| D-12 | (v2) Chilly Hilly route ids and the Fourth parade roads to pause. Confirm the lists. | Owner to supply the route. |

**Risks**

- **Wrong local facts.** Event dates move (2026's Rotary Auction was July 11, not June), and WSF is rebuilding the
  Bainbridge schedule. Mitigation: `dates`-only windows for unreliable events, safe no-occurrence defaults, and the
  14-day preview in PRs.
- **Tide labels as "safety" signals.** Users might read the app's tide times as navigation data. Mitigation: show
  "approx.", show the NOAA "not for navigation" credit, and keep nothing on beaches.
- **Lures.** Even with the rules, a hero event on a shore road draws walkers to that road. Mitigation: `roadSafety`
  roads only (≤ 25 mph, public), stretches active only in daylight and below the tide threshold, and (v2) road
  content paused during big events. The owner reviews the `coast-*` pools.
- **Cron CPU on the free tier** (§7.4). This also affects the existing snapshot cron. Flagged for measurement.
- **Determinism across platforms.** Floating point in `Sun` differs by ULPs between JVM and Native. That only
  shifts times by microseconds, but window boundaries are rounded **down to the minute** before use so both
  platforms agree exactly. Integer-only RNG and hashing.
- **Config mistakes** reach every user within about an hour of deploy. Mitigation: a strict validator in three
  places (build script, JVM test, device), last-good fallback, and kill switches.
- **Scope creep.** The annual calendar is content, not code. New monthly themes are variants. If a month wants a
  new mechanic, it needs a new `ItemType` and therefore an app release, by design.
- **Off-island players** see island time. Intended, but surprising. The What's on surface should label times
  "island time" when the device's time zone differs.

---

### Sources

- NOAA GML, Solar Calculation Details: <https://gml.noaa.gov/grad/solcalc/calcdetails.html>
- NOAA CO-OPS API (hilo for subordinate stations, 10-year hilo limit): <https://tidesandcurrents.noaa.gov/api/>
- NOAA station metadata, Eagle Harbor 9445882 offsets: <https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations/9445882.json?expand=tidepredoffsets>; Seattle 9447130 harmonic constituents: <https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations/9447130/harcon.json>; Eagle Harbor predictions page: <https://tidesandcurrents.noaa.gov/noaatidepredictions.html?id=9445882>
- NOAA public-domain notice (quoted): <https://metadata.ftp-master.debian.org/changelogs/main/x/xtide-data/stable_copyright>; NWS disclaimer: <https://www.nws.noaa.gov/disclaimer.php>
- WSF Schedule API: <https://www.wsdot.wa.gov/ferries/api/schedule/documentation/index.html>; WSDOT Traveler API (access code): <https://wsdot.wa.gov/traffic/api/>; Seattle–Bainbridge schedule update: <https://wsdot.wa.gov/construction-planning/search-studies/seattle-bainbridge-island-and-seattle-bremerton-sailing-schedules-update>; winter schedule 2025–26: <https://www.fox13seattle.com/news/washington-state-ferries-winter-schedules>
- Open-Meteo terms: <https://open-meteo.com/en/terms>
- Cloudflare Workers limits (cron count, CPU): <https://developers.cloudflare.com/workers/platform/limits/>; D1 pricing: <https://developers.cloudflare.com/d1/platform/pricing/>; D1 enforcement: <https://developers.cloudflare.com/changelog/post/2026-09-01-d1-free-tier-limit-enforcement/>
- Chilly Hilly: <https://cascade.org/news/2026/02/ride-chilly-hilly-wear-hot-hoodie>, <https://wsmag.net/ws-life/the-outdoors/2026-02-12/what-to-know-about-this-years-chilly-hilly-bike-ride/>, <https://www.visitpoulsbo.com/?p=3404>
- Grand Old Fourth: <https://www.parentmap.com/?p=429876>, <https://www.bainbridgereview.com/news/a-star-spangled-shindig-bainbridge-islands-47th-grand-old-fourth-of-july-slideshow/>
- Mochi Tsuki: <https://www.nichibei.org/event/bainbridge-island-mochi-tsuki-festival-2025/>, <https://bijac.org/event/mochi-tsuki-2023/>
- Harvest Fair: <https://www.bainbridgereview.com/?p=84846>, <https://www.bainbridgereview.com/?p=19729>
- Rotary Auction 2026: <https://www.bainbridgereview.com/2026/06/30/rotary-auction-not-accepting-large-appliances-this-year/>
- Frog Rock: <https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington)>, <https://libraries.wsu.edu/wp-content/uploads/files/paul_brians/bi/frogrock.html>
- Exclusion Memorial: <https://en.wikipedia.org/wiki/Bainbridge_Island_Japanese_American_Exclusion_Memorial>
- SplitMix64 reference: <https://prng.di.unimi.it/splitmix64.c>
