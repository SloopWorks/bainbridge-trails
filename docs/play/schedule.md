# Play: timing and scheduling design

Status: DRAFT for review (adversarial-reviewer next). Date: 2026-10-20. Role: plan-author.
Brief: [`docs/play/brief.md`](brief.md), acceptance criterion 5 ("what appears when ... deterministic and fair, managed
via content config the owner can edit without an app release"), plus the parts of 3, 4 and 8 that depend on timing.
The owner asked for "timing and scheduling needs proper design and thought to think how and when these should
happen over days and months and how to manage". This document is that design.

It does not cover the content types' UX (discovery, resolution and reward screens), the `MapFx` effects engine, or
the art. Those are separate specs under `docs/play/design/`. This document defines **when** and **where** content
exists, and **who** sees the same thing. Rendering is someone else's job.

---

## 0. Decisions at a glance

| # | Decision | Section |
|---|---|---|
| S-1 | The island runs on **one clock**: `America/Los_Angeles` wall time, wherever the phone is. Sun times come from an on-device NOAA/Meeus solar algorithm for 47.63 N, −122.52 E, with no network. | §1.1 |
| S-2 | **Tides v1 = a bundled NOAA high/low table** for Eagle Harbor (subordinate station 9445882, referenced to Seattle 9447130), generated at build time and also served by the Worker. The app does **not** run a harmonic predictor. | §1.6 |
| S-3 | **Ferries v1 = a static timetable in the content config** (one entry per WSF season, weekday and weekend). The owner updates it each season. Fetching the WSF API through the Worker is deferred (owner decision D-3). | §1.7 |
| S-4 | **No weather in v1.** | §1.8 |
| S-5 | Placement and timing are a pure function of `(islandSeed, island-local date, config)`. Island items are identical for every player. Personal items (easter eggs, creatures) also mix in a per-install random salt. The PRNG is SplitMix64, with FNV-1a 64 for string hashing. | §2 |
| S-6 | Weekly rhythm: **Mon** riddle opens · **Wed** the frog moves · **Thu** Kraken (around the daytime high tide) · **Fri** Ferry Friday · **1st and 3rd weekends** a community race · **Sun** a treasure-map fragment. Every day: one island treasure, dawn and dusk creatures, personal eggs. **Tuesday is quiet.** | §3 |
| S-7 | Hard rules: no spawns or lures between 21:00 and 06:00; at most one hero event at a time; at most 3 placements per 1 km cell and 40 visible in total; per-item cooldowns; respect zones; road placements only on roads of 25 mph or less. | §3.4 |
| S-8 | Content config `play-content.json` lives in the repo at `data/play/`. A build script validates it and copies it into the Worker bundle and the app bundle. The Worker serves it at `GET /v1/play/content` with an ETag. The app caches it, validates it strictly, and keeps the last good copy. Pipeline: edit → PR → merge → owner deploys. No D1 table holds the config. | §4 |
| S-9 | `PlayScheduler` lives in a new pure-Kotlin `:play` module. It builds one `DayPlan` per day in O(items) and memoizes it. An `agenda(now)` query costs ≤ 1 ms. | §5 |
| S-10 | **Notifications: none in v1.** "What's on" is in-app only. Local notifications (opt-in, ≤ 1 per day, scheduled ahead, no location) are a later stage gated on its own design. | §6 |
| S-11 | The server stores only points summaries, idempotent finds (occurrence keys) and race entries. Race windows are server-authoritative and are expanded by the **existing** daily cron, so no new cron trigger is needed. A race score is new ground, bounded by the existing plausibility rules and a per-window cap. | §7 |

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
| `night` | dusk → next dawn | No spawns, no lures (see quiet hours §3.4). |
| `dawn` | dawn → sunrise + 90 m | Dawn creatures. Clamped to start at 06:00 at the earliest. In June (sunrise about 05:11) that leaves about 06:00–06:41, so the band is never empty. |
| `day` | sunrise + 90 m → sunset − 60 m | Most content. |
| `golden` | sunset − 60 m → sunset | Ferry glints, Frog Rock "evening light". |
| `dusk` | sunset → dusk | Dusk creatures. Clamped to end by 21:00. In summer the dusk band falls mostly after 21:00, so dusk items carry a seasonal variant that uses `golden` instead (§4.6). |

Approximate island sun times, for intuition only. Approximate Seattle-latitude values; the golden table in
`SunTest` is authoritative:

| | Jan 15 | Mar 15 | Jun 15 | Sep 15 | Nov 15 | Dec 15 |
|---|---|---|---|---|---|---|
| sunrise | ~07:55 | ~07:15 (PDT) | ~05:11 | ~06:50 | ~07:20 (PST) | ~07:50 |
| sunset | ~16:45 | ~19:15 | ~21:08 | ~19:20 | ~16:35 | ~16:18 |

Takeaway: in winter, dusk lands at about 16:30, which is **after school and kid-friendly** (Marcus). In summer the
day is long, quiet hours cut the evening off, and Dana's mornings are the anchor.

### 1.2 Day of week, week, month

- **Day of week**: `mon` … `sun` in island-local time.
- **Week**: ISO-8601 weeks, Monday through Sunday. The "week" identity used in seeds and occurrence keys is the
  **Monday's date** (`2027-01-04`), never an ISO week number. That avoids week-53 and year-boundary bugs and stays
  readable in logs.
- **Month**: calendar month. Nth-weekday rules (`1sat`, `3sat`, `last-sun`) count within the month.

### 1.3 Seasons: astronomical and PNW climate

There are two independent season systems, and an item may filter on either:

1. **Quarter seasons** (`winter`, `spring`, `summer`, `fall`) by fixed month-day: Dec 1, Mar 1, Jun 1, Sep 1
   (meteorological). Fixed dates are simpler to reason about than equinoxes, and equinox and solstice moments are
   available as named windows when an item wants them.
2. **Climate seasons**, the PNW nuance: `wet` (Oct 15 → Apr 30), `dry` (Jul 1 → Sep 15), and `shoulder` for the rest.
   The pattern is the well-known Puget Sound one: dry, settled summers and long wet winters. The exact boundaries are
   content, so the owner tunes them in config (`climate` block). They gate flavor, not rules: wet-season variants
   use "storm" art, more kraken, mushrooms and salamanders; dry-season variants use dust, berries and long evenings.

### 1.4 Named windows: holidays and local events

Local events are **named windows** in config (`windows.<id>`), referenced by items. Fixed-rule events use a
recurrence rule. Variable-date events use explicit `dates` that the owner updates once a year, with an optional
`fallback` rule. A variable event with neither a date for this year nor a fallback simply does not occur, which is
the safe default.

| window id | rule | evidence |
|---|---|---|
| `chilly-hilly` | yearly, Feb, `last-sun`, 06:00–17:00 (2027: Feb 28) | Cascade Bicycle Club's ride; 2026 was Sun Feb 22, the 54th ([Cascade](https://cascade.org/news/2026/02/ride-chilly-hilly-wear-hot-hoodie), [WSMag](https://wsmag.net/ws-life/the-outdoors/2026-02-12/what-to-know-about-this-years-chilly-hilly-bike-ride/)); historically "the last Sunday in February" ([Visit Poulsbo](https://www.visitpoulsbo.com/?p=3404)). |
| `grand-old-fourth` | yearly, Jul 4, 06:00–21:00 | Winslow Way and Madison Ave. The 2026 listing gives the parade as 13:00–15:00 ([ParentMap](https://www.parentmap.com/?p=429876)). It is produced by the Chamber ([Bainbridge Review](https://www.bainbridgereview.com/news/a-star-spangled-shindig-bainbridge-islands-47th-grand-old-fourth-of-july-slideshow/)). |
| `mochi-tsuki` | `dates` (owner, yearly), fallback Jan `2sat` | 2025: Sat Jan 11, Woodward MS; 2023: Sat Jan 7. Earlier years were often the first Sunday of January ([Nichi Bei](https://www.nichibei.org/event/bainbridge-island-mochi-tsuki-festival-2025/), [BIJAC](https://bijac.org/event/mochi-tsuki-2023/)). |
| `harvest-fair` | `dates` (owner), fallback Sep `4sun` | Friends of the Farms at Johnson Farm, a Sunday in late September (Sept 22; earlier Sept 27 and 29) ([Bainbridge Review](https://www.bainbridgereview.com/?p=84846), [BI Review 2](https://www.bainbridgereview.com/?p=19729)). |
| `rotary-auction` | `dates` only (no fallback) | Usually the last Saturday of June or the first of July. **2026 was Sat Jul 11**, the 66th ([Bainbridge Review 2026-06-30](https://www.bainbridgereview.com/2026/06/30/rotary-auction-not-accepting-large-appliances-this-year/)). That pattern breaks too often for a rule. |
| `exclusion-remembrance` | yearly, Mar 30, all day | On March 30, 1942, Bainbridge Island's Japanese Americans were removed via the Eagledale ferry dock. The memorial (*Nidoto Nai Yoni*) is at the west edge of Pritchard Park ([Wikipedia](https://en.wikipedia.org/wiki/Bainbridge_Island_Japanese_American_Exclusion_Memorial)). **Play is muted here, not gamified** (see respect zones, §3.4). |
| `frog-rock-birthday` | yearly, Jun, `3sat`, 2 days | Frog Rock was painted in June 1971 ([WSU / Paul Brians](https://libraries.wsu.edu/wp-content/uploads/files/paul_brians/bi/frogrock.html), [Wikipedia](https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington))). The day is our invention; the month is the fact. |
| `king-tides` | yearly, Dec–Jan (`in: [dec, jan]`), combined with a tide anchor | Puget Sound's highest tides of the year come in winter. The tide anchor's `minHeightM` decides the actual days. |
| `race-weekends` | monthly, `1sat` + `3sat`, Sat 07:00 → Sun 19:00 (`days: 2`) | Our cadence (§3.2). |

Event windows mostly **pause or re-theme** content. They do not pull people into crowds:

- Chilly Hilly: roads-goal placements on the ride's roads are suppressed that day (thousands of cyclists, cars
  queued at the ferry). The trails goal gets a "Chilly Hilly" lore card instead.
- Grand Old Fourth: road placements on Winslow Way and Madison are paused. The map gets a fireworks `ambient` visual
  over Eagle Harbor, which is decor and spawns nothing.

### 1.5 Calendar arithmetic precision

Everything above is computed **on device, offline**, from the config and the bundled tide table. The only inputs
that age are the tide table (it covers fixed years) and the ferry timetable (it covers fixed seasons). Both have
explicit coverage ranges in config. Outside those ranges, items anchored to tides or ferries use their `fallback`
window (§4.4). They never error.

### 1.6 Tides: decision **S-2 = a bundled NOAA high/low table**

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

**What tides drive.** Only **obstacle timing** (Kraken near the high tide) and **suppression**. Nothing is placed
on a beach (§3.4). The tide never sends anyone to the water; the kraken comes to the *road*.

### 1.7 Ferries: decision **S-3 = a static timetable in config**

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

**Safety.** No content ever says "catch the ferry" or counts down to a departure. Ferry encounters happen at the
**arrival** on the Bainbridge side, along trails and roads near Eagle Harbor, never at the terminal or its holding
lanes.

### 1.8 Weather: decision **S-4 = none in v1**

Open-Meteo's free API is for **non-commercial use**, under 10,000 calls a day (5,000 an hour, 600 a minute). Its
data is CC BY 4.0, which requires attribution ([Open-Meteo terms](https://open-meteo.com/en/terms)). A Worker
cache (one fetch an hour, about 24 calls a day) would fit the limits easily, and 47.63 N is one grid point. We
still skip it in v1, for these reasons:

1. "Non-commercial" is ambiguous for a SloopWorks app, even a free one. That is a legal question, not an
   engineering one.
2. Gameplay keyed to rain is a fairness problem. A sunny-only treasure punishes the wet season, which is half the
   year, and rain-only content rewards walking in storms.
3. The climate seasons (§1.3) already give the PNW its weather *texture* deterministically.

If the owner wants weather later (D-4), it would be a cosmetic overlay only (rain particles over `MapFx`), off by
default, fetched by the Worker hourly and cached, and never a gate on content.

---

## 2. Determinism and fairness

### 2.1 Goals

1. Two phones with the same config and the same island date show the **same island content in the same places at
   the same times**, with no server round trip.
2. Personal content (easter eggs, creatures) differs per player, so a friend cannot spoil it by sharing a
   screenshot. It is still stable for a given player across app restarts.
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
- **Variant salt:** when an item has `reseed: "v2"`, that string is mixed into `itemKey`. The owner bumps it to
  reshuffle one item, for example after a bad placement.
- **Occurrence key** (identity for finds, cooldowns, sync and server dedupe): `"<itemId>@<yyyy-mm-dd>"`, using
  the occurrence's first island-local date. Daily, weekly (Monday's date) and multi-day windows all reduce to one
  date. Ferry and tide anchored items with several sub-windows per day add `#n` (`ferry-friday@2027-01-08#2`).

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
| Types | treasure (daily, map), obstacle (kraken), riddle, race, lore (frog, ferry, events), ambient | easter_egg, creature |
| Why | shared talk ("did you beat the kraken on Point White?"), races, fairness | surprise, no spoilers, density spread |
| Server checks | occurrence day must match the date rule | same; position is not checked (no GPS on server) |

**Island placement is computed before, and independently of, personal placement and user state.** User state
(found, cooldown) only changes how an island item *displays* (found or available). It never moves it. Density caps
(§3.4) are applied first to island items in a fixed order, then personal items fill whatever capacity is left. So
personal content can never displace shared content.

`userSalt` is 64 random bits, created on first Play opt-in and stored in `play/state.json`. It is per install and
**not synced**, so two devices get different eggs. That is acceptable, and syncing it is owner decision D-8. It
never leaves the phone.

### 2.5 Offline-first and clock honesty

- The scheduler needs only `(config, tides, places, now)`. All four are local. The bundled config and tide tables
  ship in the APK and IPA, so a fresh install in airplane mode plays fully.
- If the user sets the device clock forward, they see tomorrow's island locally. That is harmless: island content
  is not secret. The server rejects finds whose occurrence day is more than 1 day ahead of the server's island date
  (§7.2).
- Config precedence: `max(bundled, cached)` by `version`, **among valid configs only**. A fresh, valid download
  replaces the cache. An invalid one is discarded with its reason logged, and the last good copy stays (§4.8).

---

## 3. Cadence design over days and months

### 3.1 Pacing principles

- **Appointments plus small surprises.** Fixed weekly anchors (a day per feature) build habit: Dana knows
  Thursday is kraken day. Daily treasure and personal eggs give low-stakes novelty on any walk. Monthly arcs
  (treasure map, themes) give a reason to come back over weeks. Yearly landmarks (Chilly Hilly, the Fourth, Frog
  Rock's birthday, king tides) make the island feel like *this* island.
- **No loss aversion.** No streaks that break, no "expires in 2 h!" copy, and nothing that punishes a missed week.
  Missed island content is shown in the ledger as "the kraken came and went". Multi-day windows (riddle 7 days,
  race 36 h, map one month) mean a weekly walker never misses an arc.
- **Fatigue control.** At most **4 "asks" per week** (riddle, kraken, ferry, race or map). Tuesday has only the
  ambient layer and the daily treasure. Per-item cooldowns keep the same treasure spot from recurring. Hero events
  never stack.
- **Both goals.** Every item names its placement per goal. When an item has no meaningful placement for a goal, it
  is omitted for that goal rather than forced in.

### 3.2 Weekly rhythm

| day (island) | island content (everyone) | personal | Dana (weekday mornings, dog) | Marcus (weekends, kids) |
|---|---|---|---|---|
| **Mon** | **Riddle of the week** opens 06:00 and runs to Sun 21:00. The answer is a place; walk there to solve it. | Eggs reroll (weekly) | Reads it on her 06:30 walk | Solves it Saturday with the kids |
| Tue | *quiet*: daily treasure only | creatures | dawn creatures | |
| **Wed** | **The frog moves.** Frog Rock's "frog" hops to a different trail (trails) or appears at Frog Rock (roads) for the week. | | finds it on a weekday | weekend "where's the frog?" |
| **Thu** | **Kraken Thursday** (hero), from 2 h before to 1 h after the highest daytime high tide on shore trails and roads. Fallback 15:00–sunset. | | if the high tide is in the morning | sees the aftermath in the ledger |
| **Fri** | **Ferry Friday**: up to 3 encounters at Bainbridge arrivals 07:00–19:00, near Eagle Harbor. | | morning arrival | Friday after school |
| **Sat** | **Race weekend** (1st and 3rd), Sat 07:00 → Sun 19:00 (hero). | | can join any time | headline family event |
| **Sun** | **Treasure-map fragment** (1 per week; the map's "X" opens on the month's last Sunday). | | | weekly ritual |
| daily | **Daily treasure** (1 island-wide) from max(06:00, sunrise) to min(21:00, sunset + 30 m) | **dawn and dusk creatures** (2 + 2), **eggs** (≤ 2 per week) | dawn creatures, daily treasure | dusk creatures in winter (16:30!) |

Every week both personas get **at least one shared appointment** (riddle or kraken for Dana; race weekend or the
map fragment for Marcus) **and** something personal on any walk (creatures, eggs). Neither needs to play daily.

### 3.3 Annual calendar

Monthly themes are **variants** of the same items (art, copy, small point changes). They are not new mechanics, so
the content load stays bounded.

| month | theme (variants) | named windows | seasonal creatures and lore | tides and light | hero events |
|---|---|---|---|---|---|
| Jan | "Mochi Moon": treasure skin, warm lantern art | `mochi-tsuki` (lore card + mochi treasure that day) | winter wrens, Douglas squirrels (dawn) | **king tides** (kraken x1.5 pts); dusk ~16:45 | king-tide kraken (replaces that week's Thu kraken) |
| Feb | "Hill climbers" | `chilly-hilly` (pause roads on the route; trails badge) | chorus frogs start (dusk) | wet | race weekends |
| Mar | "Spring tides" | `exclusion-remembrance` Mar 30 (muted; remembrance card only) | chorus frogs (dusk, Gazzam Lake / Grand Forest) | DST (2027-03-14) moves every clock-anchored window | race weekends |
| Apr | "Skunk cabbage & fiddleheads" | | frogs end Apr 30 | wet ends Apr 30 | |
| May | "Low-tide month": lore only, **no beach placements** | | swallows (day) | daytime lows; **beach content forbidden by rule** | |
| Jun | "Frog Rock's birthday" | `frog-rock-birthday` (3rd weekend) | dawn moved to 06:00 clamp | longest day; dusk variants → `golden` | birthday lore (hero, 2 days) |
| Jul | "Grand Old Fourth" | `grand-old-fourth`, `rotary-auction` (dates) | bats at golden hour | dry starts | Fourth (hero, priority beats a 1st-weekend race: 2027-07-03/04 race is suppressed) |
| Aug | "Blackberry month" | | | dry | race weekends |
| Sep | "Harvest" | `harvest-fair` (lore card) | | dry ends Sep 15; equinox | race weekends |
| Oct | "Grand Forest after dark", spooky-but-kind: no gore, no jump scares | Halloween week variant | owls, banana slugs (dusk ~18:15 → 17:50) | wet starts Oct 15; **this doc's launch window** | race weekends |
| Nov | "Salmon return" | | chum salmon lore at Sakai Salmon Stream | DST ends (2027-11-07); dusk 16:40 | |
| Dec | "Longest night lights" | solstice | wrens | **king tides** | king-tide kraken |

### 3.4 Spawn, expire, density and safety rules (global, in `rules`)

1. **Play hours** (`rules.playHours` = 06:00–21:00): an occurrence's **start** is clamped into play hours. Items
   that are active across a night (riddle, map, eggs) stay visible but **sleep** from 21:00 to 06:00. They render
   dimmed and static, and they do not pulse, glow or pull. Nothing is created and no lure is shown in that span.
   Non-interactive `ambient` decor (the ferry crossing, fireworks) is exempt because it is neither a spawn nor a
   lure, but it is still limited to 05:00–23:00.
2. **Sun respect**: proximity-resolved items in parks (forest trails) default to `to: "sunset"`. Only road items and
   in-town trails may run to 21:00 (`rules.afterSunsetOk` lists places).
3. **Heroes**: `hero: true` items. At most `rules.heroMaxConcurrent = 1` hero is active at any instant, and at most
   1 hero **starts** per island day. Conflicts resolve by `priority` (higher wins), then by id. The loser's
   occurrence is **suppressed**, not shifted, so outcomes stay predictable. The config build check simulates 400
   days and fails when a conflict's **loser** lacks `yields: true`. Examples: the 2027-07-03/04 race weekend
   yields to the Fourth, the June 3rd-Saturday race yields to Frog Rock's birthday, and a Thursday kraken yields
   to a king-tide kraken.
4. **Density**: a 1 km grid anchored at (47.55 N, −122.62). Cell ≈ 0.009° lat × 0.0133° lon. At most
   `maxPlacementsPerCell = 3` placements per cell for the current goal; a hero's placements count, and hero
   multi-segment obstacles count as 1. At most `maxVisiblePlacements = 40` island-wide per view. Fill order:
   heroes, races, riddles, island treasure, island lore, then personal creatures and eggs. An overflowing placement
   is dropped for the day, deterministically.
5. **Cooldowns**:
   - `cooldown` (per user, per item): after a resolution, the item shows no new occurrence to this user until the
     cooldown has passed. Example: eggs `6d`, creatures `20h`.
   - `spotCooldown` (island, per item): a pool member used by occurrence *n* is excluded for the next *k*
     occurrences. Example: the daily treasure never reuses a trailhead within 14 days. It is computed
     deterministically by replaying the item's draws for the previous k occurrences, which is cheap because pools
     are small.
6. **Safety filters** (the resolver applies them; the validator also rejects config that violates them):
   - Placements only on **counted or included dataset segments** (public trails and counted public roads), so
     nothing lands on private land.
   - Roads: `speed_mph ≤ rules.roadSafety.maxSpeedMph (25)` and `category ∉ ["state_route"]`. A road with unknown
     speed (`null`) is allowed only with `residential`. This filter is why the kraken haunts Point White Drive (25)
     and not Agate Point Road (35).
   - **No beach placements, ever.** There is no beach geometry in the datasets, and `point:` placements are checked
     against the coastline with a 30 m inland buffer by the build script, using `coastline.json`.
   - **Respect zones** (`respectZones[]`): circles where no Play placement may land and no playful FX may render.
     The first one is the Japanese American Exclusion Memorial at Pritchard Park (radius 120 m). On
     `exclusion-remembrance`, Play shows only a quiet remembrance card. It carries no points and no "find".
   - Event pauses: top-level `pauses` (§4.6) suppress **every** item's placements on named roads or trails during
     named windows (Chilly Hilly, the Fourth). The pauses are global, so no item can forget them.
7. **Expiry**: each occurrence ends at its `to`. Unresolved island items vanish, and the ledger gets a past-tense
   note. Unresolved personal items vanish silently. `notBefore` and `expires` on an item bound its whole life,
   for seasonal one-offs.

---

## 4. Content config: `play-content.json`

### 4.1 Where it lives and how it flows: decision S-8

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
- Static assets would be served without the Worker, so the server could not use the config for find and race
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
  "version": "2026.11.0",      // monotonic, compared lexicographically by YYYY.MM.N parts
  "minAppBuild": 0,            // app versionCode floor; older apps keep their last good config
  "tz": "America/Los_Angeles", // must equal this value in schema 1 (validated)
  "islandSeed": "5eed1b1a2026c0de",
  "kill": { "all": false, "types": [], "items": [] },
  "rules": { … },              // §3.4 knobs
  "seasons": [ … ], "climate": [ … ],
  "windows": { "<id>": Recurrence, … },
  "places": { "<id>": Place, … },
  "pools": { "<id>": Pool, … },
  "pauses": [
    { "window": "chilly-hilly", "features": ["pool:chilly-hilly-route"] },
    { "window": "grand-old-fourth", "features": ["pool:fourth-parade"] }
  ],
  "pauses": [ … ],             // global event pauses (§3.4)
  "respectZones": [ … ],
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
    val kill: Kill = Kill(), val rules: Rules = Rules(),
    val seasons: List<SeasonSpan> = DEFAULT_SEASONS, val climate: List<SeasonSpan> = DEFAULT_CLIMATE,
    val windows: Map<String, Recurrence> = emptyMap(), val places: Map<String, Place> = emptyMap(),
    val pools: Map<String, Pool> = emptyMap(), val respectZones: List<RespectZone> = emptyList(),
    val pauses: List<Pause> = emptyList(),
    val ferry: Ferry? = null, val tides: TideRef? = null, val items: List<Item>,
)
@Serializable data class Kill(val all: Boolean = false, val types: List<ItemType> = emptyList(), val items: List<String> = emptyList())
@Serializable data class Rules(
    val playHours: ClockSpan = ClockSpan("06:00", "21:00"), val ambientHours: ClockSpan = ClockSpan("05:00", "23:00"),
    val heroMaxConcurrent: Int = 1, val cellM: Int = 1000, val maxPlacementsPerCell: Int = 3,
    val maxVisiblePlacements: Int = 40, val maxPointsPerDay: Int = 250, val lateSubmitDays: Int = 14,
    val roadSafety: RoadSafety = RoadSafety(), val afterSunsetOk: List<String> = emptyList(),
)
@Serializable enum class ItemType { treasure, easter_egg, obstacle, riddle, race, lore, creature, ambient }
@Serializable enum class Scope { island, personal }
@Serializable data class Item(
    val id: String,                       // [a-z0-9-]{3,48}, unique
    val type: ItemType, val scope: Scope = Scope.island,
    val lore: String? = null,             // art/fx family: kraken, frog, ferry, forest, prue, event, …
    val enabled: Boolean = true,
    val hero: Boolean = false, val priority: Int = 0, val yields: Boolean = false,
    val goals: List<Goal> = listOf(Goal.trails, Goal.roads),
    val `when`: When,                     // recurrence inline, or {"window": "<id>"} (+ optional overrides)
    val where: Map<Goal, Placement> = emptyMap(),  // absent for a goal → item omitted for that goal
    val count: Int = 1,                   // placements per occurrence
    val points: Int = 0,                  // 0..100; races score separately
    val cooldown: Duration? = null, val spotCooldown: Int = 0,
    val resolve: Resolve,                 // how it is found/solved on device
    val race: RaceSpec? = null,           // type == race only: metric (new_ground_m), maxScoreM, graceH
    val variants: List<Variant> = emptyList(),
    val copy: Copy, val a11y: A11y,
    val fx: String? = null,               // MapFx effect id (spec'd in the MapFx doc)
    val reseed: String? = null, val notBefore: LocalDate? = null, val expires: LocalDate? = null,
)
@Serializable data class Copy(val title: String, val body: String, val found: String? = null, val missed: String? = null)
@Serializable data class A11y(val label: String, val announce: String, val reducedMotion: ReducedMotion = ReducedMotion.static_icon)
@Serializable data class Variant(val `when`: VariantWhen, val points: Int? = null, val fx: String? = null,
                                 val copy: Copy? = null, val window: Recurrence? = null)  // first match wins
```

**Strictness.** The JSON uses `ignoreUnknownKeys = true` (forward compatibility, `_note` comments) and
`coerceInputValues = false` (a wrong enum or type is an **error**, not a silent default). After decoding,
`PlayConfigValidator.validate(config, places: PlaceIndex): List<ConfigError>` checks the following. **Any error
rejects the whole config**, and the app keeps its last good one. There is no partial apply.

- Every id is unique and well formed. Every `window`, `place`, `pool` and `fx` reference resolves. Every
  trail and road id exists in the bundled dataset for its goal.
- Recurrences parse and every `TimeExpr` is valid. `from < to` holds after resolution on at least one day per year.
- `points` is in 0..100 and `count` is in 1..12. `copy.title`, `a11y.label` and `a11y.announce` are non-blank and
  ≤ 140 characters. Every placement-bearing item has `a11y.announce`.
- Every safety filter in §3.4 passes: roads speed and category, respect zones, the coastline buffer.
- The hero-conflict simulation is clean. It runs in the build script and the JVM test, not on device, because it
  costs a few ms per simulated year.
- Items whose `where` is missing for every goal they claim are rejected, unless `resolve.by` is `race` or `none`
  (races and view-only cards have no placement).

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
4. **Overrides.** An item's `when` may be `{"window": "race-weekends"}` or `{"window": "x", "from": "09:00"}`.
   Fields given inline override the named window's fields.
5. **Determinism.** The evaluation reads only the date, the tz rules, `Sun`, `TideTable`, the ferry timetable and
   the config. There is no wall clock inside.

Examples:

```jsonc
{ "every": "week", "on": ["thu"], "from": "07:00", "to": "sunset",
  "anchor": { "at": "tide.high", "pick": "highest", "before": "2h", "after": "1h",
              "fallback": { "from": "15:00", "to": "sunset" } } }        // Kraken Thursday
{ "every": "year", "in": ["feb"], "on": ["last-sun"], "from": "06:00", "to": "17:00" }   // Chilly Hilly
{ "every": "month", "on": ["1sat", "3sat"], "from": "07:00", "to": "19:00", "days": 2 }  // race weekends
{ "every": "day", "from": "dawn", "to": "sunrise+90m" }                                  // dawn creatures
{ "every": "week", "on": ["fri"], "from": "07:00", "to": "19:00",
  "anchor": { "at": "ferry.arrive.bainbridge", "before": "5m", "after": "15m", "max": 3 } } // Ferry Friday
```

**Server subset.** The Worker (§7) evaluates only the **date-level** part (`every`, `on`, `in`, `dates`,
`fallback`, `days`, `season` by month-day) and **clock-time** `from` and `to` for races. Race windows must use
`HH:MM` times (validated). Parity is enforced by a shared golden file, `data/play/golden/date-rules-2027.json`,
which both `RecurrenceGoldenTest` (Kotlin) and `test/play-recurrence.test.ts` (Worker) must reproduce.

### 4.5 Placements

```
Placement := { "at": PlaceRef }                                  // fixed place
           | { "pick": Int, "from": PoolRef }                    // seeded pick of pool members
           | { "along": FeatureRef | PoolRef, "pick"?: Int }     // seeded segment(s) of a trail/road, jittered
           | { "segments": FeatureRef | PoolRef, "minM"?: Int }  // whole features (obstacles that cover roads)
PlaceRef   := "place:<id>"
PoolRef    := "pool:<id>"
FeatureRef := "trail:<trail-id>" | "road:<road-id>" | "park:<park name>" | "trailhead:<th-id>" | "trailheads:*"
Place      := { "lat": Double, "lon": Double, "radiusM": Int = 40, "goals"?: [Goal], "verify"?: Boolean }
Pool       := { "members": [FeatureRef|PlaceRef], "goal"?: Goal }
```

`PlaceIndex` (an interface in `:play`, implemented in composeApp over `DatasetRepository`) resolves features to
segment ids and midpoints. So `:play` has **no dataset dependency** and its tests use a fake index.

### 4.6 Resolution, variants, pauses

```
Resolve := { "by": "proximity", "radiusM": 40 }                 // pass within radius while recording (on device)
         | { "by": "segments", "minM": 200 }                    // record ≥ minM on the placement's segments in window
         | { "by": "riddle", "answer": PlaceRef|FeatureRef }   // walk to the answer (proximity/segments rule)
         | { "by": "race" }                                     // server-scored (§7.3)
         | { "by": "none" }                                     // lore card / ambient: view only
VariantWhen := { "season"?: [...], "window"?: WindowId, "month"?: [Month] }
Pause := { "window": WindowId, "features": [FeatureRef|PoolRef] }   // top-level `pauses`, applies to all items
```

Resolution is evaluated **on device** by the encounter detector against in-memory fixes of the current recording.
Fixes are never stored by Play, and only the occurrence key is recorded. The scheduler supplies the active
placements; the detector is specified with the content-type UX.

### 4.7 Full example config (23 items, every type, all named lore)

Coordinates below are real where cited (Frog Rock per [Wikipedia](https://en.wikipedia.org/wiki/Frog_Rock_(Bainbridge_Island,_Washington));
trailheads from `data/dist/trailheads.json`; Eagle Harbor station per NOAA). Places marked `"verify": true` must be
confirmed by the owner before `enabled: true`. **Ferry times are illustrative placeholders, not the real WSF
timetable**; the owner fills them from the WSF schedule.

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
    "cellM": 1000,
    "maxPlacementsPerCell": 3,
    "maxVisiblePlacements": 40,
    "maxPointsPerDay": 250,
    "lateSubmitDays": 14,
    "roadSafety": { "maxSpeedMph": 25, "excludeCategories": ["state_route"], "unknownSpeedOnly": ["residential"] },
    "afterSunsetOk": ["pool:winslow"]
  },
  "seasons": [
    { "id": "winter", "from": "12-01", "to": "02-29" }, { "id": "spring", "from": "03-01", "to": "05-31" },
    { "id": "summer", "from": "06-01", "to": "08-31" }, { "id": "fall", "from": "09-01", "to": "11-30" }
  ],
  "climate": [
    { "id": "wet", "from": "10-15", "to": "04-30" }, { "id": "dry", "from": "07-01", "to": "09-15" }
  ],
  "windows": {
    "chilly-hilly": { "every": "year", "in": ["feb"], "on": ["last-sun"], "from": "06:00", "to": "17:00" },
    "grand-old-fourth": { "every": "year", "in": ["jul"], "on": [4], "from": "06:00", "to": "21:00" },
    "mochi-tsuki": { "every": "dates", "dates": [], "fallback": { "every": "year", "in": ["jan"], "on": ["2sat"], "from": "06:00", "to": "21:00" }, "from": "06:00", "to": "21:00", "_note": "BIJAC announces yearly; add the date when known" },
    "harvest-fair": { "every": "dates", "dates": [], "fallback": { "every": "year", "in": ["sep"], "on": ["4sun"], "from": "06:00", "to": "19:00" }, "from": "06:00", "to": "19:00" },
    "rotary-auction": { "every": "dates", "dates": [], "from": "06:00", "to": "18:00", "_note": "no fallback: 2026 was Jul 11, pattern unreliable" },
    "exclusion-remembrance": { "every": "year", "in": ["mar"], "on": [30], "from": "00:00", "to": "24:00" },
    "frog-rock-birthday": { "every": "year", "in": ["jun"], "on": ["3sat"], "from": "07:00", "to": "20:00", "days": 2 },
    "king-tides": { "every": "day", "in": ["dec", "jan"], "from": "07:00", "to": "sunset" },
    "race-weekends": { "every": "month", "on": ["1sat", "3sat"], "from": "07:00", "to": "19:00", "days": 2 }
  },
  "places": {
    "frog-rock": { "lat": 47.69612, "lon": -122.52347, "radiusM": 40 },
    "ladybug-rock": { "lat": 47.6961, "lon": -122.5232, "radiusM": 40, "verify": true },
    "eagle-harbor-gauge": { "lat": 47.62, "lon": -122.515, "radiusM": 80, "verify": true },
    "prues-house": { "lat": 0.0, "lon": 0.0, "verify": true, "_note": "owner: what/where is Prue's house?" },
    "johnson-farm": { "lat": 0.0, "lon": 0.0, "verify": true }
  },
  "pools": {
    "shore-roads": { "goal": "roads", "members": [
      "road:road-point-white-drive-northeast", "road:road-pleasant-beach-drive-northeast",
      "road:road-rockaway-beach-road-northeast", "road:road-manitou-beach-drive-northeast",
      "road:road-crystal-springs-drive-northeast", "road:road-northeast-south-beach-drive-1",
      "road:road-point-monroe-drive-northeast", "road:road-northeast-yeomalt-point-drive",
      "road:road-wing-point-way-northeast" ] },
    "shore-trails": { "goal": "trails", "members": [
      "park:Fort Ward Park", "park:Blakely Harbor Park", "trail:trail-pritchard-trail",
      "park:West Port Madison Nature Preserve", "trail:trail-hidden-cove-trails" ] },
    "grand-forest": { "goal": "trails", "members": ["park:Grand Forest"] },
    "wetlands": { "goal": "trails", "members": ["park:Gazzam Lake Preserve", "park:Grand Forest", "park:Meigs Park"] },
    "harbor-trails": { "goal": "trails", "members": ["trail:trail-sound-to-olympics-trail", "trail:trail-pritchard-trail"] },
    "winslow": { "goal": "roads", "members": ["road:road-winslow-way-east", "road:road-harborview-drive-southeast"] },
    "frog-hops": { "goal": "trails", "members": ["park:Grand Forest", "park:Battle Point Park", "trail:trail-hidden-cove-to-spargur-trail", "park:Gazzam Lake Preserve", "park:Manzanita Park", "park:Meigs Park", "park:Waypoint Woods"] },
    "chilly-hilly-route": { "goal": "roads", "members": ["road:road-northeast-eagle-harbor-drive", "road:road-ferncliff-avenue-northeast", "road:road-fletcher-bay-road-northeast"], "_note": "owner: full Chilly Hilly route ids" },
    "fourth-parade": { "goal": "roads", "members": ["road:road-winslow-way-east", "road:road-madison-avenue-north-2"] }
  },
  "pauses": [
    { "window": "chilly-hilly", "features": ["pool:chilly-hilly-route"] },
    { "window": "grand-old-fourth", "features": ["pool:fourth-parade"] }
  ],
  "respectZones": [
    { "id": "exclusion-memorial", "lat": 47.6098, "lon": -122.5076, "radiusM": 120, "verify": true,
      "_note": "Bainbridge Island Japanese American Exclusion Memorial, west edge of Pritchard Park" }
  ],
  "ferry": {
    "route": "seattle-bainbridge", "crossingMin": 35,
    "terminals": { "bainbridge": { "lat": 47.6229, "lon": -122.5106, "verify": true }, "seattle": { "lat": 47.6026, "lon": -122.3393, "verify": true } },
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
      "id": "daily-treasure", "type": "treasure", "scope": "island", "lore": "treasure",
      "when": { "every": "day", "from": "sunrise", "to": "sunset+30m" },
      "where": { "trails": { "pick": 1, "from": "pool:all-trailheads" }, "roads": { "along": "pool:winslow", "pick": 1 } },
      "spotCooldown": 14, "points": 10, "resolve": { "by": "proximity", "radiusM": 40 },
      "variants": [ { "when": { "month": ["jan"] }, "fx": "chest-lantern", "copy": { "title": "Mochi Moon chest", "body": "A lantern-lit chest is waiting near {place}." } } ],
      "copy": { "title": "Today's chest", "body": "A chest washed up near {place}.", "found": "You found today's chest.", "missed": "Yesterday's chest drifted off." },
      "a11y": { "label": "Treasure chest", "announce": "Today's treasure is near {place}.", "reducedMotion": "static_icon" },
      "fx": "chest-glint"
    },
    {
      "id": "treasure-map", "type": "treasure", "scope": "island", "lore": "treasure", "hero": false,
      "when": { "every": "week", "on": ["sun"], "from": "06:00", "to": "+6d" },
      "where": { "trails": { "pick": 1, "from": "pool:frog-hops" }, "roads": { "pick": 1, "from": "pool:shore-roads" } },
      "points": 15, "resolve": { "by": "segments", "minM": 150 },
      "variants": [ { "when": { "window": "last-sun-of-month" }, "points": 50, "fx": "x-marks", "copy": { "title": "X marks the spot", "body": "All four pieces point to {place}." } } ],
      "copy": { "title": "A torn map piece", "body": "This week's piece of the {month} map is near {place}.", "found": "Map piece {n} of 4." },
      "a11y": { "label": "Map fragment", "announce": "A treasure map piece is near {place} this week." },
      "fx": "map-flutter"
    },
    {
      "id": "kraken-thursday", "type": "obstacle", "scope": "island", "lore": "kraken", "hero": true, "priority": 60, "yields": true,
      "when": { "every": "week", "on": ["thu"], "from": "07:00", "to": "sunset",
                "anchor": { "at": "tide.high", "pick": "highest", "before": "2h", "after": "1h", "fallback": { "from": "15:00", "to": "sunset" } } },
      "where": { "roads": { "segments": "pool:shore-roads", "pick": 1 }, "trails": { "segments": "pool:shore-trails", "pick": 1 } },
      "points": 25, "cooldown": "6d", "resolve": { "by": "segments", "minM": 300 },
      "variants": [ { "when": { "season": ["wet"] }, "fx": "kraken-storm" } ],
      "copy": { "title": "The kraken is up", "body": "Tentacles are curling over {place} around high tide. Walk {minM} of it to push it back.", "found": "The kraken slid back into the Sound.", "missed": "The kraken came and went on {place}." },
      "a11y": { "label": "Kraken on {place}", "announce": "Kraken encounter on {place} until {end}.", "reducedMotion": "fade" },
      "fx": "kraken-rise"
    },
    {
      "id": "king-tide-kraken", "type": "obstacle", "scope": "island", "lore": "kraken", "hero": true, "priority": 70, "yields": true,
      "when": { "window": "king-tides", "anchor": { "at": "tide.high", "pick": "highest", "minHeightM": 3.9, "before": "2h", "after": "1h" } },
      "where": { "roads": { "segments": "pool:shore-roads", "pick": 2 }, "trails": { "segments": "pool:shore-trails", "pick": 2 } },
      "points": 40, "cooldown": "20h", "resolve": { "by": "segments", "minM": 300 },
      "copy": { "title": "King tide kraken", "body": "The year's biggest tide brought the big one to {place}.", "found": "You held the shore." },
      "a11y": { "label": "Giant kraken on {place}", "announce": "King tide kraken on {place} until {end}.", "reducedMotion": "fade" },
      "fx": "kraken-king", "_note": "minHeightM calibrated against the 2027 golden table so it fires on only a handful of days per winter month"
    },
    {
      "id": "riddle-of-the-week", "type": "riddle", "scope": "island", "lore": "riddle",
      "when": { "every": "week", "on": ["mon"], "from": "06:00", "to": "+6d" },
      "where": { "trails": { "pick": 1, "from": "pool:frog-hops" }, "roads": { "pick": 1, "from": "pool:shore-roads" } },
      "spotCooldown": 8, "points": 20, "resolve": { "by": "riddle", "answer": "pool:frog-hops" },
      "copy": { "title": "Riddle of the week", "body": "{riddle}", "found": "Solved! It was {place}." },
      "a11y": { "label": "Riddle", "announce": "A new riddle. Solve it by walking to the place it describes." },
      "_note": "riddle text bank is a separate content list keyed by answer place (content spec)"
    },
    {
      "id": "frog-wanders", "type": "lore", "scope": "island", "lore": "frog",
      "when": { "every": "week", "on": ["wed"], "from": "06:00", "to": "+6d" },
      "where": { "trails": { "along": "pool:frog-hops", "pick": 1 }, "roads": { "at": "place:frog-rock" } },
      "spotCooldown": 4, "points": 15, "resolve": { "by": "proximity", "radiusM": 50 },
      "copy": { "title": "Where's the frog?", "body": "Frog Rock's frog has hopped off to {place} this week.", "found": "You found the frog." },
      "a11y": { "label": "Frog", "announce": "The frog is at {place} this week." },
      "fx": "frog-hop"
    },
    {
      "id": "frog-rock-birthday", "type": "lore", "scope": "island", "lore": "frog", "hero": true, "priority": 85,
      "when": { "window": "frog-rock-birthday" },
      "where": { "roads": { "at": "place:frog-rock" }, "trails": { "along": "trail:trail-hidden-cove-to-spargur-trail" } },
      "points": 30, "cooldown": "300d", "resolve": { "by": "proximity", "radiusM": 60 },
      "copy": { "title": "Frog Rock's birthday", "body": "Painted in June 1971 and repainted ever since. Stop by and say happy birthday.", "found": "Happy birthday, frog." },
      "a11y": { "label": "Frog Rock birthday", "announce": "Frog Rock's birthday weekend." },
      "fx": "confetti-soft"
    },
    {
      "id": "ladybug-egg", "type": "easter_egg", "scope": "personal", "lore": "frog", "goals": ["roads"],
      "when": { "every": "week", "on": ["mon"], "from": "06:00", "to": "+6d" },
      "where": { "roads": { "at": "place:ladybug-rock" } },
      "points": 5, "cooldown": "28d", "resolve": { "by": "proximity", "radiusM": 40 },
      "copy": { "title": "A small red friend", "body": "Frog Rock has company.", "found": "Ladybug spotted." },
      "a11y": { "label": "Ladybug", "announce": "Something small and red near Frog Rock." }
    },
    {
      "id": "heron-egg", "type": "easter_egg", "scope": "personal", "lore": "shore",
      "when": { "every": "week", "on": ["mon"], "from": "06:00", "to": "+6d" },
      "where": { "trails": { "along": "pool:shore-trails", "pick": 1 }, "roads": { "along": "pool:shore-roads", "pick": 1 } },
      "points": 5, "cooldown": "6d", "resolve": { "by": "proximity", "radiusM": 35 },
      "copy": { "title": "A patient heron", "body": "Someone tall and grey is fishing near here.", "found": "The heron let you pass." },
      "a11y": { "label": "Heron", "announce": "A hidden heron is somewhere on your shore walks this week." }
    },
    {
      "id": "grand-forest-dawn", "type": "creature", "scope": "personal", "lore": "forest", "goals": ["trails"],
      "when": { "every": "day", "from": "dawn", "to": "sunrise+90m" },
      "where": { "trails": { "along": "pool:grand-forest", "pick": 2 } },
      "count": 2, "points": 3, "cooldown": "20h", "resolve": { "by": "proximity", "radiusM": 30 },
      "variants": [ { "when": { "season": ["winter"] }, "copy": { "title": "Winter wren", "body": "A tiny loud bird is up before you." } },
                    { "when": { "season": ["fall"] }, "copy": { "title": "Douglas squirrel", "body": "Someone is scolding you from a fir." } } ],
      "copy": { "title": "Dawn in the Grand Forest", "body": "Something is stirring.", "found": "You met a dawn creature." },
      "a11y": { "label": "Forest creature", "announce": "A dawn creature is nearby." },
      "fx": "creature-peek"
    },
    {
      "id": "grand-forest-dusk", "type": "creature", "scope": "personal", "lore": "forest", "goals": ["trails"],
      "when": { "every": "day", "from": "sunset", "to": "dusk" },
      "where": { "trails": { "along": "pool:grand-forest", "pick": 2 } },
      "count": 2, "points": 3, "cooldown": "20h", "resolve": { "by": "proximity", "radiusM": 30 },
      "variants": [ { "when": { "season": ["summer"] }, "window": { "every": "day", "from": "sunset-60m", "to": "sunset" } },
                    { "when": { "month": ["oct"] }, "fx": "owl-eyes-kind", "copy": { "title": "Barred owl", "body": "Who cooks for you? Someone is asking." } } ],
      "copy": { "title": "Dusk in the Grand Forest", "body": "The forest is changing shifts.", "found": "You met a dusk creature." },
      "a11y": { "label": "Forest creature", "announce": "A dusk creature is nearby." },
      "fx": "creature-peek"
    },
    {
      "id": "chorus-frogs", "type": "creature", "scope": "personal", "lore": "forest", "goals": ["trails"],
      "notBefore": "2027-02-01",
      "when": { "every": "day", "in": ["feb", "mar", "apr"], "from": "sunset-30m", "to": "dusk" },
      "where": { "trails": { "along": "pool:wetlands", "pick": 1 } },
      "points": 4, "cooldown": "20h", "resolve": { "by": "proximity", "radiusM": 40 },
      "copy": { "title": "Chorus frogs", "body": "The wetlands are loud tonight.", "found": "You heard the chorus." },
      "a11y": { "label": "Chorus frogs", "announce": "Chorus frogs nearby." }
    },
    {
      "id": "salmon-return", "type": "lore", "scope": "island", "lore": "salmon", "goals": ["trails"],
      "when": { "every": "week", "in": ["nov"], "on": ["sat"], "from": "09:00", "to": "sunset", "days": 2 },
      "where": { "trails": { "at": "trail:trail-sakai-salmon-stream-amphitheater" } },
      "points": 10, "cooldown": "6d", "resolve": { "by": "proximity", "radiusM": 60 },
      "copy": { "title": "The salmon are back", "body": "Chum salmon return to island creeks in late fall. Watch from the trail; stay out of the stream.", "found": "You visited the salmon." },
      "a11y": { "label": "Salmon run", "announce": "Salmon run lore at Sakai." }
    },
    {
      "id": "ferry-friday", "type": "lore", "scope": "island", "lore": "ferry",
      "when": { "every": "week", "on": ["fri"], "from": "07:00", "to": "19:00",
                "anchor": { "at": "ferry.arrive.bainbridge", "before": "5m", "after": "15m", "max": 3, "fallback": { "from": "12:00", "to": "13:00" } } },
      "where": { "trails": { "along": "pool:harbor-trails", "pick": 1 }, "roads": { "along": "pool:winslow", "pick": 1 } },
      "points": 10, "cooldown": "6d", "resolve": { "by": "proximity", "radiusM": 50 },
      "copy": { "title": "Ferry Friday", "body": "The boat's in. Its wake left something on {place}.", "found": "You caught the wake." },
      "a11y": { "label": "Ferry wake gift", "announce": "Ferry Friday gift on {place} until {end}." },
      "fx": "ferry-wake"
    },
    {
      "id": "ferry-crossing", "type": "ambient", "scope": "island", "lore": "ferry",
      "when": { "every": "day", "from": "05:00", "to": "23:00", "anchor": { "at": "ferry.depart.bainbridge", "before": "0m", "after": "35m", "pick": "all" } },
      "where": { "trails": { "at": "place:eagle-harbor-gauge" }, "roads": { "at": "place:eagle-harbor-gauge" } },
      "points": 0, "resolve": { "by": "none" },
      "copy": { "title": "Ferry crossing", "body": "" },
      "a11y": { "label": "Ferry", "announce": "A ferry is crossing to Seattle.", "reducedMotion": "hidden" },
      "fx": "ferry-sprite"
    },
    {
      "id": "weekend-race", "type": "race", "scope": "island", "lore": "race", "hero": true, "priority": 80, "yields": true,
      "when": { "window": "race-weekends" },
      "where": {}, "points": 20, "resolve": { "by": "race" },
      "race": { "metric": "new_ground_m", "maxScoreM": 30000, "graceH": 12 },
      "copy": { "title": "Weekend race: new ground", "body": "Cover the most ground you've never walked, Saturday 7 am to Sunday 7 pm.", "found": "You raced. Results Monday." },
      "a11y": { "label": "Weekend race", "announce": "Weekend race is on until Sunday 7 pm." }
    },
    {
      "id": "chilly-hilly-card", "type": "lore", "scope": "island", "lore": "event", "goals": ["trails"],
      "when": { "window": "chilly-hilly" }, "where": { "trails": { "pick": 1, "from": "pool:frog-hops" } },
      "points": 10, "resolve": { "by": "proximity", "radiusM": 60 },
      "copy": { "title": "Chilly Hilly day", "body": "Thousands of cyclists are climbing the island's roads. The trails are yours." },
      "a11y": { "label": "Chilly Hilly", "announce": "Chilly Hilly day card." }
    },
    {
      "id": "grand-old-fourth", "type": "lore", "scope": "island", "lore": "event", "hero": true, "priority": 90,
      "when": { "window": "grand-old-fourth" },
      "where": { "trails": { "along": "pool:harbor-trails", "pick": 1 }, "roads": { "along": "pool:shore-roads", "pick": 1 } },
      "points": 20, "resolve": { "by": "proximity", "radiusM": 60 },
      "copy": { "title": "Grand Old Fourth", "body": "The island's biggest party since the 1950 Strawberry Festival parade. Find the flag away from the crowds." },
      "a11y": { "label": "Fourth of July flag", "announce": "Grand Old Fourth flag on {place}." },
      "fx": "fireworks-ambient"
    },
    {
      "id": "mochi-tsuki-card", "type": "lore", "scope": "island", "lore": "event",
      "when": { "window": "mochi-tsuki" }, "where": { "trails": { "pick": 1, "from": "pool:frog-hops" }, "roads": { "along": "pool:winslow", "pick": 1 } },
      "points": 10, "resolve": { "by": "proximity", "radiusM": 60 },
      "copy": { "title": "Mochi Tsuki", "body": "Today the Bainbridge Island Japanese American Community pounds mochi to welcome the new year." },
      "a11y": { "label": "Mochi Tsuki", "announce": "Mochi Tsuki day card." }
    },
    {
      "id": "harvest-fair-card", "type": "lore", "scope": "island", "lore": "event",
      "when": { "window": "harvest-fair" }, "where": { "trails": { "pick": 1, "from": "pool:frog-hops" }, "roads": { "pick": 1, "from": "pool:shore-roads" } },
      "points": 10, "resolve": { "by": "proximity", "radiusM": 60 },
      "copy": { "title": "Harvest Fair", "body": "Island farms are celebrating the harvest today." },
      "a11y": { "label": "Harvest Fair", "announce": "Harvest Fair day card." }
    },
    {
      "id": "rotary-auction-card", "type": "lore", "scope": "island", "lore": "event", "goals": ["roads"],
      "when": { "window": "rotary-auction" }, "where": { "roads": { "along": "pool:winslow", "pick": 1 } },
      "points": 10, "resolve": { "by": "proximity", "radiusM": 60 },
      "copy": { "title": "Rotary rummage day", "body": "The whole island is treasure-hunting today." },
      "a11y": { "label": "Rotary Auction", "announce": "Rotary Auction day card." }
    },
    {
      "id": "exclusion-remembrance", "type": "lore", "scope": "island", "lore": "remembrance",
      "when": { "window": "exclusion-remembrance" }, "where": {},
      "points": 0, "resolve": { "by": "none" },
      "copy": { "title": "Nidoto Nai Yoni", "body": "On March 30, 1942, Bainbridge Island's Japanese American families were the first forced from their homes under Executive Order 9066. Let it not happen again." },
      "a11y": { "label": "Remembrance", "announce": "Remembrance: March 30, 1942.", "reducedMotion": "static_icon" }
    },
    {
      "id": "prues-house", "type": "lore", "scope": "island", "lore": "prue", "enabled": false,
      "when": { "every": "month", "on": ["2sat"], "from": "10:00", "to": "sunset" },
      "where": { "roads": { "at": "place:prues-house" }, "trails": { "at": "place:prues-house" } },
      "points": 15, "resolve": { "by": "proximity", "radiusM": 50 },
      "copy": { "title": "Prue's house", "body": "TBD (owner)" },
      "a11y": { "label": "Prue's house", "announce": "TBD" },
      "_note": "Disabled until the owner confirms what and where Prue's house is (brief)."
    }
  ]
}
```

The example uses three conveniences, which are part of the schema:

- The pool `pool:all-trailheads` is built in (every trailhead, or the `trailheads:*` ref).
- The named window `last-sun-of-month` is built in.
- `race: { … }` is the race-only sub-object: `metric` (only `new_ground_m` in v1), `maxScoreM` and `graceH`.

Copy tokens: `{place}`, `{end}`, `{month}`, `{n}`, `{minM}` and `{riddle}`, plus the goal vocabulary tokens
`{goal.item}` and `{goal.items}`, are substituted by composeApp through `GoalVocabulary`, so road and trail words
never mix.

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
data class PlayUserState(val salt: ULong, val resolved: Map<String, Instant>, val foundOcc: Set<String>)

class PlayScheduler(config: PlayConfig, places: PlaceIndex, tides: TideTable?, ferry: FerryTimetable?) {
    /** Pure, deterministic; memoized by the caller per (config.version, date, goal, salt). */
    fun dayPlan(date: LocalDate, goal: Goal, salt: ULong): DayPlan
    /** Cheap: filters the memoized plans of today (+ tomorrow for "upcoming"). */
    fun agenda(now: Instant, ctx: PlayContext, user: PlayUserState): PlayAgenda
}
data class DayPlan(val date: LocalDate, val sun: SunDay, val tides: List<TideEvent>, val occurrences: List<Occurrence>)
data class Occurrence(val key: String, val itemId: String, val start: Instant, val end: Instant,
                      val hero: Boolean, val placements: List<ResolvedPlacement>, val points: Int, val variant: Int?)
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
| `PlayConfigValidatorTest` | each rule in §4.3 has a failing fixture (bad ref, 35 mph road, respect-zone point, blank a11y, hero conflict) |
| `PlayConfigBundledTest` (jvm) | the bundled `composeResources/files/play/play-content.json` validates against a jvm `PlaceIndex` built from `data/dist` (skipped with a message when data is absent: CI has no map data) |
| `DensityTest` | 4 placements in one cell → 3 kept, by fill order; island never displaced by personal |
| `HeroConflictTest` | 2027-07-03/04 race suppressed by the Fourth; at most 1 hero active at any instant over 400 days |
| `DeterminismTest` | same inputs → identical plans; editing item B leaves item A's placements unchanged; a different salt changes only personal items |
| `QuietHoursTest` | no occurrence starts in 21:00–06:00 for any day of 2027; overnight items flagged `sleeping` |
| `PlaySchedulerGoldenTest` (jvm) | `golden/agenda-2027-{trails,roads}.tsv`: one row per (date, occKey, start, end, placement refs) for island items over all of 2027 with the example config, a fixed seed and a fake index. Regenerate with `-Pplay.updateGolden=true`; the PR diff is the review artifact. |
| `PlaySchedulerPerfTest` (jvm) | §5.3 ceilings |

---

## 6. Notifications policy: decision S-10

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
- Content: one line chosen from today's island items by priority ("Kraken Thursday: high tide around 2:40 pm on
  the south shore"). Never personal eggs (spoilers), never a countdown, never "hurry", never ferry departures,
  never after sunset. The copy is generated from the same `Copy` fields, so it is reviewed with the config.
- Skipped on days with nothing but ambient content, and on `exclusion-remembrance`.
- If the geofence **ships** and the user has opted into both: an arrival notification may append one Play line
  ("The frog is here this week"), within the same 1-per-day budget. No new geofences are registered for Play.

---

## 7. Server side

### 7.1 Principles

The server stores **summaries and identities only**: points per goal, occurrence keys found, and race scores. It
stores no positions, no find times and no walk times. Occurrence keys reveal the **day** (or week) a user was
active in Play. That is new coarse-time information compared with today's backend, so it must be disclosed in the
privacy policy and is owner decision **D-5**. Points and finds sync only when signed in **and** Play is on. All
tables are scoped `(user_id, dataset)` like RD-7, and `DELETE /v1/me` cascades.

### 7.2 Finds and points

Migration `backend/migrations/0005_play.sql` (additive only, per the README deploy rule):

```sql
CREATE TABLE play_points (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL CHECK (dataset IN ('trails','roads')),
  points  INTEGER NOT NULL DEFAULT 0,
  finds   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, dataset)
) WITHOUT ROWID;

CREATE TABLE play_finds (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dataset TEXT NOT NULL,
  occ     TEXT NOT NULL,          -- "<itemId>@<yyyy-mm-dd>[#n]" island-local occurrence key
  day     TEXT NOT NULL,          -- yyyy-mm-dd (occurrence day; daily cap index)
  points  INTEGER NOT NULL,
  PRIMARY KEY (user_id, dataset, occ)
) WITHOUT ROWID;
CREATE INDEX play_finds_day ON play_finds (user_id, dataset, day);
```

`PUT /v1/play/finds` (auth, body ≤ 8 KB, up to 50 finds, 30 requests per hour per user):

```json
{ "dataset": "trails", "configVersion": "2026.11.0",
  "finds": [ { "occ": "kraken-thursday@2027-01-07", "points": 25 } ] }
```

Server checks, per find. A rejected find is reported in `rejected[]` with a code, while the others still apply:

1. `itemId` exists in the Worker's bundled config, is enabled and not killed, is not `ambient`, and its `goals`
   contain `dataset`. Otherwise `unknown_item`. A `race` item is accepted only when the user has a
   `play_race_entries` row for that occurrence with `score_m > 0` (§7.3).
2. `points ≤ max(item.points, variants.points)`. Otherwise `bad_points`.
3. The occurrence day matches the item's **date rule** (the server subset, §4.4). Otherwise `bad_occurrence`.
   Days outside `[serverIslandToday − lateSubmitDays, serverIslandToday + 1]` give `too_late` or `too_early`.
4. **Walk evidence (reuses TL-2 plausibility).** `user_metrics.last_walk_upload_at` for this dataset is at or after
   the start of the occurrence day (island-local midnight, server clock). In other words, some walk was credited
   after that day began. The client always syncs coverage before finds. Otherwise `no_walk`.
5. **Caps.** `Σ points` for that `day` (index) plus the new points must not exceed `rules.maxPointsPerDay`.
   Otherwise `daily_cap`.
6. **Idempotent.** `INSERT OR IGNORE` on `(user, dataset, occ)`. Re-sending is a no-op and the response still
   reports `accepted`. `play_points` is updated in the same `DB.batch` with `points + changes()`, so a retry never
   double counts.

Response `200 {"accepted": [...], "rejected": [{"occ","code"}], "points": 1234, "finds": 57}`. The device ledger
stays the source of truth for its own UI. Rejected finds keep their local points with a "not counted online" note,
mirroring `sync_rejected_notice`. `GET /v1/play/me?dataset=` returns `{points, finds}`.

Write budget: one find is about 2 row writes. 1,000 active players × 10 finds a day is about 20k writes a day,
inside D1's 100k writes a day. D1 started **enforcing** the free-tier limits on 2026-09-01, so an excess fails the
queries until midnight UTC ([D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/),
[changelog](https://developers.cloudflare.com/changelog/post/2026-09-01-d1-free-tier-limit-enforcement/)). The
daily cap and the 50-per-request cap bound the worst case.

### 7.3 Races: a server-authoritative window and results

```sql
CREATE TABLE play_races (
  race_id     TEXT PRIMARY KEY,          -- "<itemId>@<yyyy-mm-dd>"
  dataset     TEXT NOT NULL,
  opens_at    INTEGER NOT NULL,          -- epoch ms (island-local window resolved by the Worker)
  closes_at   INTEGER NOT NULL,
  grace_until INTEGER NOT NULL,          -- closes_at + graceH (late offline uploads)
  max_score_m REAL NOT NULL,
  config_version TEXT NOT NULL,
  final       INTEGER NOT NULL DEFAULT 0
) WITHOUT ROWID;
CREATE INDEX play_races_window ON play_races (dataset, closes_at);

CREATE TABLE play_race_entries (
  race_id    TEXT NOT NULL REFERENCES play_races(race_id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  score_m    REAL NOT NULL DEFAULT 0,
  reached_at INTEGER NOT NULL,           -- server time score last rose (tie-break; never returned)
  PRIMARY KEY (race_id, user_id)
) WITHOUT ROWID;
CREATE INDEX play_race_entries_rank ON play_race_entries (race_id, score_m DESC, reached_at);
```

The race rows are created per dataset. `race_id` is unique per dataset because the `itemId@date` is suffixed
`:roads` for roads (`weekend-race@2027-01-02:roads`).

**Window authority.** The Worker computes race windows from its bundled config using the server subset and
`Intl.DateTimeFormat` for `America/Los_Angeles` offsets (DST-tested), then `INSERT OR IGNORE`s the next 21 days in
the daily cron. A config change to a race never edits an existing row; it only affects races not yet created. The
client shows windows from `GET /v1/play/races?dataset=`, falling back to its local computation offline. The two
agree via the shared golden file.

**Scoring = new ground.** This reuses existing machinery and needs no new client evidence.

- Joining is explicit (the "Join race" button in the UI). The client then adds `"race": "<raceId>"` to
  `PUT /v1/coverage` for uploads whose pending walks overlap the window. The field is optional and additive, and
  pure mode never sends it.
- The server, after the existing plausibility check passes, adds the upload's `newlyCoveredM` (already computed
  for TL-2) to the entry, in the same `DB.batch` that credits the walk ids. The add happens only when all of the
  following hold:
  - `now ∈ [opens_at, grace_until]`;
  - the dataset matches;
  - the upload credits at least one new walk;
  - `Σ new walk durationS ≤ (now − opens_at)/1000 + WALK_TIME_TOLERANCE_S` (the walks fit after the opening; the
    same idea as P1-3).
  The score is `MIN(max_score_m, score_m + newlyCoveredM)`.
- **Idempotency.** A retried upload has its walks already credited and its bits already stored. Then
  `newlyCoveredM = 0` and no walk is new, so the add is 0. The race add sits in the same batch as walk crediting,
  so the two cannot diverge.
- **Anti-cheat.**
  - Plausibility (4 m/s × duration + 500 m slack, 6 h per walk clamp, walk-time ≤ elapsed time) already bounds
    the metres.
  - `maxScoreM` (30 km per weekend) caps outliers.
  - The coverage rate limit is 30 uploads an hour.
  - A late submission after `grace_until` is accepted as coverage but not scored.
  - Known gap: a walk finished just before `opens_at` but uploaded inside the window can count. That is bounded by
    the duration check and accepted (documented).
- **Fairness.** New ground favors newer walkers. That is intentional (the race is "explore"), but it is owner
  decision **D-7**: alternatively, brackets by island % (`< 25 %`, `25–75 %`, `> 75 %`).

**Results.**

- `GET /v1/play/races?dataset=` (public) returns `{current, upcoming[≤ 3], recent[≤ 4]}` with windows and entrant
  counts. Counts below 5 are hidden and larger ones rounded to 5, like stats.
- `GET /v1/play/races/{raceId}?limit=20` returns standings `{rank, displayName, scoreKm (1 decimal, floor)}` plus
  `you`.
- Names follow leaderboard rules: admin-hidden and self-hidden users are excluded from others' views.
- Standings are final once `now > grace_until`, by **lazy finalization**: the first read after grace sets
  `final = 1` idempotently (`UPDATE … WHERE final = 0 AND ? > grace_until`). Live standings during the window are
  allowed; they are just a query on the index.
- Race points (`item.points`, participation) are granted when the entry's score is above 0 at finalization.
  The client then submits the `occ` as a find; the server accepts `race` items only if an entry with
  `score_m > 0` exists.

### 7.4 Cron on the free tier

- Free plan: **5 cron triggers per account**, **10 ms CPU per cron invocation**. Time spent waiting on I/O (D1
  queries, fetch) does not count ([Workers limits](https://developers.cloudflare.com/workers/platform/limits/)).
- **No new trigger.** Play adds `playDaily(env, now)` to the existing `17 9 * * *` `daily()`: expand race windows
  for 21 days (≤ 6 rows, `INSERT OR IGNORE`), finalize races past grace, and purge race entries of races closed
  more than 180 days ago. CPU is microseconds of JS around a few D1 calls.
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
| PUT | `/v1/play/finds` | user | §7.2; 30 / h |
| GET | `/v1/play/me?dataset=` | user | points summary |
| GET | `/v1/play/races?dataset=` | public | windows + counts |
| GET | `/v1/play/races/{raceId}` | public (`you` if signed in) | standings |
| PUT | `/v1/coverage` | user | **+ optional `race`** (additive) |

New error codes: `unknown_item`, `bad_points`, `bad_occurrence`, `too_late`, `too_early`, `no_walk`, `daily_cap`
(per find, inside a 200 response), and `race_closed` (when a race id is given for a closed race; the upload is
still accepted as coverage, and the response carries `raceScored: false`).

Request budget: content refresh ≤ 1 per user per hour of foreground, mostly 304. 1,000 players is ≤ 10k a day,
well within 100k requests a day.

---

## 8. Staged implementation plan (plan-author contract)

```
PLAN: Play scheduling — time model, config, PlayScheduler, content delivery, finds & races backend
      (acceptance criteria satisfied: 5 fully; enables 3 (content timing), 4 (lore placement), 8 (unit + backend tests))
PRECONDITIONS:
  - Branch from latest origin/main (CLAUDE.md), e.g. play/schedule.
  - This spec ACCEPTed by the owner after adversarial-reviewer; owner answers D-1..D-5 at least (§9).
  - ADRs recorded in docs/play/decisions.md (stage 0). No UI in this plan: "What's on", ledger and race screens
    belong to the Play UI plan and need an ACCEPTed design spec under docs/play/design/ (gate design_before_build).
```

**STAGE 0: ADRs (docs only)**
- files: `docs/play/decisions.md` (new): PL-S1 island time zone + solar algorithm; PL-S2 NOAA hilo table; PL-S3
  ferry timetable in config; PL-S4 no weather; PL-S5 seeding/PRNG; PL-S6 config-in-repo bundled into Worker+app;
  PL-S7 no notifications v1; PL-S8 race = new ground on coverage uploads.
- test-first: n/a (docs)
- verify: `grep -c '^## PL-S' docs/play/decisions.md` → 8
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
- test-first: `TimeExprTest`, `PlayConfigParseTest`, `PlayConfigValidatorTest`
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
- test-first: `DensityTest`, `HeroConflictTest`, `QuietHoursTest`, `DeterminismTest`, `PlaySchedulerGoldenTest`,
  `PlaySchedulerPerfTest`
- verify: verify.kmp
- rollback: revert (no callers yet)

**STAGE 7: content pipeline + Worker read endpoints**
- files: `scripts/build-play-content.mjs` (validate + copy + `--check` + 14-day preview print; the hero
  simulation runs in Kotlin stage 6 and in this script's JS date-subset for races), `backend/src/data/play-content.json`,
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

**STAGE 9: finds + points backend**
- files: `backend/migrations/0005_play.sql`, `backend/src/routes/play.ts` (+`PUT finds`, `GET me`),
  `backend/src/routes/me.ts` (DELETE cascade check), `backend/src/ratelimit.ts` (`playFindsPerUser`), `docs/legal/privacy-policy.md`
  + `npm run build:legal` (occurrence-day disclosure, if D-5 = sync)
- test-first: `backend/test/play-finds.test.ts`: accepted; idempotent retry; unknown_item; bad_points;
  bad_occurrence; too_late/too_early; no_walk (no walk since day start); daily_cap; killed item; deletion cascade;
  `migrations.test.ts` extended for 0005 on a migrated DB
- verify: verify.api
- rollback: Worker revert (0005 is additive; tables are inert)

**STAGE 10: races backend**
- files: `backend/src/play/races.ts` (window expansion, scoring add, finalize), `backend/src/routes/coverage.ts`
  (optional `race` field → batch add), `backend/src/index.ts` (`daily()` → `playDaily`), `routes/play.ts`
  (`GET races`, `GET races/{id}`), `docs/mvp/api.md`
- test-first: `backend/test/play-races.test.ts`: cron expansion idempotent (run twice → same rows); DST weekend
  window; upload with race inside window scores newlyCoveredM; retry adds 0; walk duration > elapsed → not scored;
  after grace → `raceScored:false`, coverage still accepted; cap at maxScoreM; standings exclude hidden users;
  lazy finalize idempotent; pure upload (no `race`) byte-identical response to today (snapshot of the JSON)
- verify: verify.api
- rollback: Worker revert; `race` field ignored by the old Worker (it is not read), so clients degrade safely

**STAGE 11: client sync of finds + race join**
- files: `composeApp/.../play/PlaySyncRepository.kt` (queue file like SyncFile; runs **after** a successful coverage
  sync; batches ≤ 50), `sync/SyncRepository.kt` (optional `raceId` provider → `CoverageUpload.race`), `net/ApiClient.kt`
- test-first: `PlaySyncRepositoryTest` (ordering after coverage; rejected → local note; idempotent resend),
  `SyncRepositoryTest` extension (race attached only when joined + walk overlaps window; absent when Play off)
- verify: verify.kmp, then verify.full before PR
- rollback: revert; the server tolerates absence

**CROSS-CUTTING**
- swip: n/a (`factory.swip: false`)
- debugdrawer: yes. `PlayClockPlugin` (stage 8) does time travel, config source switching, agenda dump and the
  benchmark.
- atlas: n/a (`product_atlas: none`)
- goldens: no UI snapshot tooling. The scheduler goldens (`agenda-2027-*.tsv`, `date-rules-2027.json`) are the
  review artifacts, and any diff must be explained in the PR.
- migrations: yes. `0005_play.sql` is additive. Deploy order is the README's: migrate, then the Worker. No
  destructive step.

**REVIEW** (in order): adversarial-reviewer (this spec) → privacy-security-reviewer (finds and occurrence-day
disclosure, race anti-cheat) → simplification-reviewer → kmp-verifier (verify.full green) → adversarial-reviewer
(branch) before the PR.

**RISKS** (what could make this plan wrong):
1. Stage 8 has no production call site until the Play UI spec is accepted. Mitigation: the flag above.
2. The Worker and Kotlin recurrence implementations drift. Mitigation: the shared golden, and races limited to
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
| D-2 | Tides: approve the bundled NOAA hilo table (a yearly regenerate chore each October)? | Yes (S-2). The alternative is to skip tides and run the kraken on clock times. |
| D-3 | Ferries: a static timetable you update each WSF season, or register for a WSF API access code (your email) plus a Worker cron fetch? | Static for v1. Revisit after WSF's 2026–27 Bainbridge schedule rebuild settles. |
| D-4 | Weather: confirm none in v1 (Open-Meteo is non-commercial; fairness concerns). | None. |
| D-5 | Privacy: syncing finds reveals the **day** someone played (occurrence keys). Accept with a privacy-policy update, or keep points local-only (no Play leaderboard)? | Sync, with disclosure. Races need the server anyway. |
| D-6 | Notifications: confirm none in v1; a later "Island notes" (≤ 1 per day, local, opt-in) needs its own spec. | None in v1. |
| D-7 | Race metric "new ground" favors newcomers. Add brackets by island %? | Ship without brackets and watch. |
| D-8 | The personal salt is per install (two devices, two sets of eggs). Sync it? | No. |
| D-9 | Annual event dates (Mochi Tsuki, Harvest Fair, Rotary Auction) need a yearly config edit. Who owns that? | Owner, as part of the October tide chore. |
| D-10 | **Exclusion Memorial respect zone and the remembrance card.** Is the wording and approach acceptable? Consider asking BIJAC. | Keep it muted: no points, no FX. Seek community review. |
| D-11 | Kill-switch latency is deploy time plus ≤ 1 h client refresh. Acceptable, or add a D1 admin kill overlay? | Acceptable for v1. |
| D-12 | Chilly Hilly route ids and the Fourth parade roads to pause. Confirm the lists. | Owner to supply the route. |

**Risks**

- **Wrong local facts.** Event dates move (2026's Rotary Auction was July 11, not June), and WSF is rebuilding the
  Bainbridge schedule. Mitigation: `dates`-only windows for unreliable events, safe no-occurrence defaults, and the
  14-day preview in PRs.
- **Tide labels as "safety" signals.** Users might read the app's tide times as navigation data. Mitigation: show
  "approx.", show the NOAA "not for navigation" credit, and keep nothing on beaches.
- **Lures.** Even with the rules, a hero event on a shore road draws walkers to that road. Mitigation: roads of
  25 mph or less only, daylight-only kraken windows, and road content paused during big events. The owner reviews
  `shore-roads` (D-12-style list review).
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
