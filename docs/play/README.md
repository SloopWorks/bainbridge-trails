# Play — opt-in gamification (docs index)

Work item: [`brief.md`](brief.md). Status: specs ACCEPTED 2026-10-20 after round-1 reconciliation
([`decisions.md`](decisions.md), resolving [`reviews/2026-10-20-round1-specs.md`](reviews/2026-10-20-round1-specs.md)).
v1 scope: [`product.md` § "v1 scope"](product.md).

## Documents

| doc | owns | status |
|---|---|---|
| [`brief.md`](brief.md) | The owner request, problem, non-goals, acceptance criteria 1–9, gates | intake |
| [`decisions.md`](decisions.md) | PD-1 … PD-17: every material decision and the review findings each resolves | ACCEPTED |
| [`product.md`](product.md) | **Rules, economy and safety**: principles, marks, Logbook, ranks, content types, opt-in, flows and ACs (PF0–PF16), copy, safety, owner decisions, the v1 scope | ACCEPTED |
| [`schedule.md`](schedule.md) | **Time, determinism and the content pack schema**: island time, sun, tides, ferry, seeds, weekly rhythm, `play-content.json`, `PlayScheduler`, the server's claims and challenges | ACCEPTED |
| [`engine.md`](engine.md) | **Rendering (normative)**: MapFx tiers, clock, scene, bands, projection, budgets, PF-1 … PF-9, engine plan | ACCEPTED |
| [`effects.md`](effects.md) | Look, motion, sound, haptics and announcements of every effect; conforms to engine, product and schedule | ACCEPTED |
| [`ai.md`](ai.md) | AI for content: v1 = offline authoring only (content-pack workflow, riddles, sprites) | ACCEPTED |
| `ux.md` | Screen layout and interaction (being written separately) | in progress |
| `plan.md` | The staged build plan, the AC 8 e2e script and the effect inventory per stage | to write (PD-17) |
| [`reviews/`](reviews/) | Adversarial reviews | — |

Precedence: `decisions.md` > `product.md` > `schedule.md` > `engine.md` > `effects.md` > `ai.md` > the brief.
`docs/mvp/decisions.md` and `docs/roads/decisions.md` still govern pure mode.

## Acceptance criteria → sections

| AC (brief) | where it is specified | notes |
|---|---|---|
| **1** Opt-in from Settings and a one-time offer; off = unchanged pure app | product § 5, PF0, PF1, PF2, PF14; engine PF-9; decisions PD-1 | PF0-1 = screenshot diffs + "nothing composed" host test (no snapshot tooling) |
| **2** Points system with a visible ledger, clear rules, per goal, synced as summaries | product § 3 (marks, caps § 3.5, claiming § 3.6, ranks § 3.7.1, Logbook § 3.8, sync § 3.10), PF6, PF11, PF12, PF15; schedule § 7.2; decisions PD-2, PD-3 | "points" = **marks**, "ledger" = **Logbook**; marks are server-derived |
| **3** Treasures, easter eggs, obstacles, puzzles/riddles and races as content types with on-map presence, discovery, resolution, reward, reduced-motion and a11y variants | product § 4.1–4.5 (obstacles: § 4.3.1 exploration fog, § 4.3.2 kraken), PF3–PF9; schedule § 3.2, § 4.7; effects § 3.1–3.4, § 3.7, § 3.9, § 3.12, § 3.17, § 3.18 | "races" = window-boxed **challenges** (`ch-sweep`, `ch-krakenweek`), never timed (PD-4) |
| **4** Bainbridge lore on real coordinates: kraken, Frog Rock, ferries, Prue's house, Grand Forest creatures, plus a catalogue | product § 4.6 (catalogue § 4.6.1, remembrance § 4.6.3), § 4.3.2 coasts; schedule § 4.7; effects § 3.1–3.6 | Prue's House (OD-1) and creatures are v2 with design text kept; TBC facts in product OD-16 |
| **5** Scheduling design and implementation: what appears when, deterministic and fair, owner-editable config | schedule § 1–§ 5 and § 8 stages 1–8; product § 6; decisions PD-8 … PD-11 | content pack `play-content.json` at `GET /v1/play/content` |
| **6** Map effects engine `MapFx`: geo-space effects, one clock, layering, lifecycle, budget, reduced motion, tests | engine § 0–§ 5 (PF-1 … PF-9), engine plan stages 0–7 and 9; decisions PD-13 | stage 8 (shaders) is v2 |
| **7** Showcase screens (debug) for every effect, both platforms | engine § 4 stage 7; effects § 7 (the v1 effect list); product § 7.2 debug row | the showcase hosts **every v1 effect**; a host test fails if a v1 id has no preset; screenshots in `docs/play/screens/fx-*` |
| **8** E2E walkthrough opt-in → treasure → kraken → riddle → race → ledger, screenshots in `docs/play/screens/`, unit and backend tests | **e2e script: `docs/play/plan.md`** (play-clock override on both platforms: `PlayClockPlugin` / `-btPlayClock`, schedule § 4.1; route replay `scripts/emu-route.sh`); unit tests schedule § 5.4 and engine § 4; backend tests schedule § 8 stages 7, 9, 10 | "race" step = `ch-sweep` or `ch-krakenweek`; "ledger" step = the Logbook |
| **9** Mobile-expert review (Compose correctness, perf, battery, a11y) before done | engine § 4 REVIEW (compose-ui-reviewer + mobile expert, stages 4–8), engine § 3 budgets; `plan.md` gates | |

## Glossary (binding; from the round-1 review, with the reconciled definitions)

| term | meaning | never |
|---|---|---|
| **marks** | Play's unit, integer, per goal | points, pts |
| **Logbook** | the record of every mark (what, why, when, how many) | ledger |
| **Play chip** | the marks · rank chip on the Map | ledger pill |
| **challenge** | a window-boxed goal, solo or island; results `{band, count}` | race; timed; ranked |
| **content item** | an entry in the content pack `play-content.json`, served at `GET /v1/play/content` | |
| **content types** | treasure, egg, obstacle, riddle, challenge, lore, creature (v2), ambient | |
| **occurrence key** | `<itemId>@<Monday-or-start date>[#n]`; item ids are immutable | |
| **week id** | that week's Monday date (`2026-10-26`); the week is [Mon 00:00, next Mon 00:00) | ISO week numbers |
| **island time** | `America/Los_Angeles`, whatever the phone's zone | |
| **daylight** | civil dawn → civil dusk (finds resolve only here) | |
| **creature hours** | sunrise + 30 min → sunset − 30 min (creatures appear only here) | dusk, dark |
| **season** | an island quarter: Storm, Strawberry, Madrona, Kraken; a week's season is its Thursday's | meteorological seasons |
| **sensed → claimed** | sensed = provisional, live on a walk; claimed = confirmed at Finish and accepted by the server | |
| **armchair → stamp** | found or solved by tapping from home earns a stamp, never marks | |
| **quiet zone** | a zone built from dataset geometry (memorial trail 200 m, cemetery trail 100 m, owner-tagged) where Play shows and awards nothing | respect zone |
| **kraken attack / stretch / repel** | the weekly attack on a coast; a stretch = the part of a road or trail within 60 m of the shore; repel = walk ≥ 60 % of it | wrap, drive off, clear |
| **fog** | exploration fog only (unvisited chart squares) | morning fog, fog bank |
| **chart square** | the single grid: 0.01° × 0.015° anchored at 47.55 N, 122.62 W | 1 km cell |
| **scope** | `island` (everyone) or `personal` (v2) | |
| **`roadSafety`** | counted road, residential/collector/arterial, `public: true`, `ownership` `public_*`, ≤ 25 mph (null only for residential) | "Play-safe ≤ 30 mph" |
| **effect priority** | HERO / NOTICE / AMBIENT; bands as engine PF-3 (UNDER_STATUS, CASING, OVER_STATUS, OVER_LABELS, OVERLAY, MOMENT) | H0–H2, E, A, U; P1–P4 |
| **effect id** | snake_case (`kraken_attack`), referenced by the content item's `fx` field | |
| **seed hash** | fnv1a64 (with SplitMix64) | `String.hashCode()` |
| **v2 / cut** | (v2) = designed, later; (cut) = removed, one-line tombstone | |
