# Work item: Play — Trails edition (v1.1)

Date: 2026-10-21 · Stage: intake → design · Branch: `play/trails-v1-1` (cut from latest `origin/main`, `2aee859`)
Parent: [`../brief.md`](../brief.md) (Play v1, PRs #11–#13). Precedence unchanged: `../decisions.md` > `../product.md` >
`../schedule.md` > … > this brief. This is the **trails-only delta** on Play v1, not a rebuild.

Owner request (verbatim, the authority for scope):

> bring the gamification platform and mode to the trails version of bainbridge trails. very similar - opt in
> gamification. point system, schedule releases and items, treasures, danger, races etc, however only on trails.
> incorporate island specifics, and trail specifics. extra award/points for elevation accomplishments, mileage, etc.
> leverage sloopworks product agent coordinating with tech lead agent, coding agent, review agent, mobile android and
> iOS coding agent, backend coding agent. review for correctness, formatting, performance, security and privacy,
> location sharing privacy. Complete e2e and commit and merge and ship to shipyard.

```
BRIEF: Add trails-only Play rewards (climb, high ground, mileage, parks, island), two trails challenges and new
       trails content to the existing opt-in Play mode, then ship the trails dev build to Shipyard.
OUTCOME: A trails player with Play on earns marks for climbing, steep trails, miles walked, parks started/finished
         and the whole island; sees two new weekly challenges and new trail-anchored finds/lore. Roads app unchanged.
```

## 1. What already exists (do not rebuild, do not re-litigate)

Play v1 is on `main` for both goals: opt-in (PF0, `AppSettings.play.enabled`), marks/Logbook/ranks, weekly treasures,
eggs, riddles, kraken ("danger"), lore, challenges `ch-sweep`/`ch-krakenweek` ("races" = window-boxed challenges,
never timed, PD-4), content pack `data/play/play-content.json` (v `2026.11.1`, 31 items) built and validated by
`node scripts/build-play-content.mjs`, server-derived marks (PD-3) in `backend/src/play/{marks,claims,challenges}.ts`,
Kotlin mirror `app/play/.../play/rules/{Marks,StartBalance,Logbook,LogCopy}.kt`. Glossary in `../README.md` is binding
(marks, Logbook, challenge, quiet zone …); pack copy validation rejects "race", "run", "fast", "speed", "points".

Owner's words → Play terms: points = **marks**; danger = the existing **kraken** obstacle (no new obstacle type);
races = **challenges**; schedule releases and items = the existing pack + recurrence DSL (`../schedule.md`).

## 2. Owner-level decisions (made by the orchestrator under owner delegation; record as PD-26 in `../decisions.md`)

PD-26 **amends PD-15's cut line for the trails goal only**: W-PARK1, W-PARKC, W-ISLAND and a trails W-MILE move
from v2 to v1.1, and two new rules (W-CLIMB, W-STEEP) and two challenges are added. It does not change PD-2/PD-3:
every new mark is a fixed rule, server-derived, monotone.

| id | rule (goals `[trails]` only) | marks | today's count (dataset `2026-10-18+aad9d16e`) |
|---|---|---|---|
| **W-CLIMB** | Cumulative climb ladder over Σ `gain_m` (`data/dist/trails.json`, per trail) of **completed** (sticky set) non-quiet trails. Partial coverage adds nothing. Steps keyed in **metres**: 100, 250, 500, 750, 1,000, 1,250 (, 1,500 — see Q1). Displayed in the user's units (D19: ft with mi, m with km, integer). | +50 per step | non-quiet total **1,368.8 m** → 6 steps reachable = 300 |
| **W-STEEP** "High ground" | Completing a non-quiet trail with `gain_m ≥ 40`. Once per trail id. | +50 | **10 trails** (see Q2): Peter's Trail 92.1, Hidden Cove Trails 62.8, Main Loop 59.5, Grand Forest East 58.7, Manzanita Park 51.9, Grand Forest West 49.2, McRedmond 47.5, Lost Valley 46.8, Kojima 41.0, Trillium 40.4 → 500 |
| **W-MILE** (trails) | Distance ladder on cumulative non-quiet covered trail metres (same `coveredM` as W-COV): 1, 5, 10, 15 … 60 mi. Same ladder math as `ui/Milestones.kt` (RP-8); paid steps keyed in **miles** so a unit switch can't double-pay. | +50 per step | 102,964 m = 63.98 mi → 13 steps = 650 |
| **W-PARK1** | First non-quiet coverage in a Play park (`trails.json` `park`, non-null, ≠ Pritchard Park). Once per park ever. | +50 | 19 parks → 950 |
| **W-PARKC** | Every named non-quiet trail in a Play park complete. Fires on the walk completing the last one. Once per park. | +150 | 19 parks → 2,850 (7 parks have a single trail) |
| **W-ISLAND** | Island complete (existing `islandReached`). Once. | +1,000 | 1 |

Common to all six:
- **Server-derived from accepted coverage + the sticky completed set**, like W-DONE/W-SQUARE (`walkDerivedMarks`);
  credited `max(previous, new)` (monotone); paid steps/parks/trails stored by value so a dataset update can't
  double-pay or claw back. Never from GPS/barometric altitude.
- **Credited once at opt-in** in the start balance (`StartBalance.onEnable` / server equivalent), then per walk.
- **Rationale:** deterministic on device and server; no new data leaves the phone (the coverage upload is unchanged);
  unspoofable by altitude noise; reuses the completion and coverage the user already earns.
- **Data:** `data/play/derived/trails.json` gains `featurePark` and `featureGainM` (written by
  `scripts/build-play-content.mjs`); closes the follow-up noted in `backend/src/play/derived.ts` ("the derived tables
  carry no park membership today"). `data/play/derived/roads.json` stays byte-identical.
- **Economy (update product § 3.2 and § 3.7.3):** trails whole-goal ≈ 22,946 + 6,250 ≈ **29,196** (roads stays
  ≈ 23,286). Ranks unchanged; "Keeper of the Chart" (30,000) still needs Play content. The goal asymmetry is accepted
  and stated in PD-26 (ranks are per goal and there is no cross-goal comparison; the Play board stays v2, PD-21).

**Challenges** (solo, window-boxed, never timed, `goals: [trails]`, C-SOLO, counts against the existing caps):

| id | metric (new) | window | target | marks |
|---|---|---|---|---|
| `ch-climb` | `climb`: Σ `gain_m` of non-quiet trails **newly completed** inside the window | weekly, Mon 00:00 → Mon 00:00 island time | ≥ 150 m (see Q3) | +100 |
| `ch-parkhop` | `parks`: distinct Play parks with ≥ 1 new non-quiet completion inside the window | weekend, Sat 00:00 → Mon 00:00 (as `ch-sweep`) | ≥ 3 parks (see Q3) | +100 |

Server evaluates both from Play-upload completions, extending `challenges.ts` (today `completed`,
`stretches_repelled`); the client card shows progress as text ("95 of 150 m climbed", "2 of 3 parks").

**Content:** new trails-only items (eggs, riddles, lore) anchored on real trails/trailheads/parks — Gazzam Lake
Preserve, Grand Forest, Fort Ward Park, Blakely Harbor Park (Port Blakely mill), Battle Point Park, Hidden Cove,
Moritani Preserve, Sakai Park, and others. Anchors only via dataset refs the validator resolves (`along: trail:<id>`,
`trailhead:<id>`, `park:<name>` pools). Every lore fact cites a source; uncertain facts `verify: true` (OD-16 pattern).
A separate research-verifier pass checks facts; this item requires the citations. Placement rules (product § 8.1:
not on beaches, not in or touching quiet zones — note Blakely Harbor trails touch the cemetery quiet zone) are
enforced by the build script. New pack version (`2026.11.2` or next); published item ids immutable.

**Privacy contract unchanged:** PF0 (off = nothing created); no new field in `PUT /v1/coverage` (`walks[]` and the
`play` element keep their v1 key sets); no coordinates or altitude sent; Logbook never stores time of day; any change
that would send new data is out of scope and must be flagged to the orchestrator, not built. Any new D1 table/column
holds only values derivable from data the server already has (coverage bits, completions) and is additive.

**Delivery:** PR from `play/trails-v1-1` → merge to `main` → publish `com.bainbridgetrails.app.dev` (`trailsDebug`)
to Shipyard channel `main` via `/Users/patrick/.local/bin/shipyard-deploy-bainbridge` (`app/AGENTS.md`). Roads dev
build republished only if its APK changed. **Worker deploy is excluded** (`backend/README.md`: owner only, agents
never deploy; OD-19 privacy-paragraph sign-off precedes the next Worker deploy). Consequence: on the Shipyard build
the new server-derived marks are live only after the owner deploys; e2e evidence uses local `wrangler dev`.

## 3. Surface (Product Atlas: none in this repo — `factory.product_atlas: none`; module paths instead)

- Pack + build: `data/play/play-content.json`, `scripts/build-play-content.mjs` (+ `.test.mjs`),
  `data/play/derived/trails.json`, `data/play/golden/`, generated copies in `backend/src/data/play/` and
  `app/composeApp/src/commonMain/composeResources/files/play/`.
- Backend (`backend`): `src/play/marks.ts`, `src/play/challenges.ts`, `src/play/derived.ts`, start-balance path in
  `src/play/claims.ts`, tests in `backend/test/`.
- Rules (`app/play`): `rules/Marks.kt`, `rules/StartBalance.kt`, `rules/Logbook.kt` (kinds `park_first`,
  `park_complete`, `milestone`, `island` exist; climb/high-ground need new kinds or `milestone` subtypes — plan
  decides; persisted state must round-trip), `rules/LogCopy.kt`; ladder math shared with `ui/Milestones.kt`.
- Client (`app/composeApp`): `play/WalkMarks.kt`, `play/PlayDerived.kt`, `play/PlayRepository.kt`; UI under
  `ui/play/` and the results sheet, Logbook, Ranks, Me and Challenges screens.
- Docs: `docs/play/trails/` (this brief, design addendum, screens), `../decisions.md` (PD-26), `../product.md`
  § 3.2 / § 3.7.3 / § 4.5 rows, `../README.md` index row.
- E2E: `scripts/play-e2e.sh` (today Android **roads** flavour only), `scripts/emu-route.sh`.

## 4. Acceptance criteria (each a test or a checkable artifact)

1. **Trails only.** Every new rule, challenge and content item has `goals: ["trails"]` (validator rejects otherwise).
   A roads guard test asserts, against goldens captured on `main` before this branch: roads `walkDerivedMarks`, start
   balance, challenge list, agenda preview and `data/play/derived/roads.json` are identical; the Kotlin roads path
   likewise (`:play:jvmTest`).
2. **Rules, exact numbers, parity.** Server (`backend/test`) and Kotlin (`:play:jvmTest`) tests pin, from one shared
   golden fixture: W-CLIMB steps (100 m → +50 … per Q1 ladder; 99.9 m → 0; partial coverage of a 92 m trail → 0);
   W-STEEP (gain 40.0 → +50, 39.9 → 0; quiet trail with gain 66.5 → 0; re-completion → 0); W-MILE (1,609 m → 1 mi
   step; unit switch mi↔km pays nothing extra); W-PARK1 (first segment in Gazzam → +50, second walk → 0; Pritchard
   Park → 0); W-PARKC (completing the single trail of a one-trail park → +50 and +150 on the same walk); W-ISLAND
   once. Server and Kotlin produce identical totals on the golden (parity test). Monotone: a fixture dataset that drops
   a trail/park lowers nothing; one that adds a trail pays only new steps.
3. **Start balance.** Turning Play on with N prior completions/coverage credits each new rule exactly once, as part of
   the `start_balance` entry (or its sibling entries per design); off → on again pays nothing more (client test +
   server test).
4. **Challenges end to end.** `ch-climb` and `ch-parkhop` are in the pack, pass the validator, appear in the agenda
   preview on the right windows, show a trails Challenges card with text progress, and the server accepts/denies them
   correctly: boundary tests at 149.9/150 m and 2/3 parks; completions outside the window don't count; quiet trails
   and Pritchard Park don't count; each instance pays once; daily/weekly caps (product § 3.5) still bind.
5. **Content.** ≥ 12 new trails-only items (≥ 4 eggs, ≥ 4 riddles, ≥ 4 lore) across ≥ 6 distinct parks/areas;
   `node scripts/build-play-content.mjs` validates (dataset refs resolve, placement/quiet-zone rules, copy blocklist,
   immutable ids); every lore item has a `sources` citation; uncertain facts carry `verify: true`; the agenda preview
   for the next 4 weeks is attached to the PR; `node --test scripts/*.test.mjs` green.
6. **Surfaces.** Results lines, Logbook rows (icon + label + why), Ranks progress and Me tiles (climb, miles, parks)
   show the new kinds per the design addendum: every string from a `play_` copy key routed through `GoalVocabulary`;
   TalkBack/VoiceOver labels on every new row/tile; climb shown in ft or m per Units setting; no new MapFx effect or
   map layer (reduced motion therefore unaffected; any reuse of `claim_pop` keeps its reduced variant); derived values
   computed outside composition.
7. **Privacy.** Existing test: the coverage upload is byte-identical with Play off. New test: with Play on, a trails
   upload after earning every new rule has exactly the v1 key sets in `walks[]` and `play[]`. Logbook state for the new
   kinds holds no time of day (extends `PlayStatePrivacyTest`). No new network call, no altitude/coordinates sent.
   privacy-security-reviewer returns SHIP (location-sharing posture explicitly covered).
8. **Verify + e2e.** `verify.full` and `verify.api` green; `scripts/play-e2e.sh` gains a trails mode
   (`com.bainbridgetrails.app.dev`, trails dataset, simulated routes generated from dataset geometry, no real
   tracks) with steps that earn W-PARK1 + W-PARKC (a one-trail park), W-STEEP (e.g. Peter's Trail), a W-CLIMB step, a
   W-MILE step, and `ch-climb` or `ch-parkhop`; Logbook total equals local D1 `play_totals`. Screenshots in
   `docs/play/trails/screens/` (Android required; iOS simulator for results, Logbook and Me) with a `README.md`
   recording the run, HEAD and findings.
9. **Reviews.** adversarial-reviewer, compose-ui-reviewer, privacy-security-reviewer, simplification-reviewer return
   SHIP (or findings fixed and re-reviewed); kmp-verifier reports green from machine output. Reviews filed in
   `docs/play/reviews/` (or `docs/play/trails/reviews/`).
10. **Shipped.** PR merged to `main`; `trailsDebug` APK built from merged `main` with
    `-PshipyardDeployPluginRepository=…` and published to Shipyard project `bainbridge-trails`, channel `main`;
    `shipyard-deploy-bainbridge status --application-id com.bainbridgetrails.app.dev --json` shows the merge commit.
    Roads APK republished only if changed (state which). No Worker deploy.

## 5. Gates

- **design:** yes — `gates.design_before_build: true` and UI changes. Scope: a **UX addendum only**
  (`docs/play/trails/ux.md`): new Logbook rows, challenge cards, results lines, Me/Ranks climb + miles + parks tiles,
  copy keys, a11y labels. No new effects. Accepted before build.
- **privacy:** yes — touches location-derived rewards and the sync contract (owner asked for location-sharing review).
- **viability:** no (no new product, pricing or strategy).
- **ui-snapshot:** no (`gates.ui_change_requires_snapshot: false`, `verify.ui_snapshot: none`); evidence = AC 8
  screenshots.

## 6. Roles (pipeline order)

brief-shaper → design-director (UX addendum only) → plan-author → kmp-implementer (client, Android + iOS) +
backend implementer (Cloudflare Worker; general-purpose agent, no dedicated role) + content author (Node pack;
general-purpose agent) → adversarial-reviewer → compose-ui-reviewer → privacy-security-reviewer →
simplification-reviewer → kmp-verifier → release-shepherd.

## 7. Autonomy

**auto-merge** (owner delegated full autonomy; owner away). In-contract, bounded, recoverable: opt-in mode, no money,
auth, store listing or published-privacy change; dev channel only. Human gates that stay closed and are **not** part
of this item: Worker deploy (owner only; OD-19), any destructive D1 migration, OD-21 retention. Stop and flag if the
work would need any of them.

## 8. Questions

**Blocking:** none.

**Non-blocking (default stands unless the orchestrator overrides):**
- **Q1 W-CLIMB top step.** The island's non-quiet climb is 1,368.8 m (1,495.3 m includes the three quiet trails), so
  1,500 m is unreachable and would leave a Me tile stuck at "next: 1,500 m". Default: ladder 100 … 1,250 (6 steps).
- **Q2 W-STEEP count.** 10 eligible trails, not 11: Blakley Cemetery Trail (66.5 m) is a quiet feature like Pritchard
  Trail. Default: quiet trails never earn (existing rule).
- **Q3 Consumable challenge targets.** Completions are sticky, so a player runs out of uncompleted climb/parks.
  Default (as `ch-foglifter`): target = min(default, what remains for that player); the card is hidden when nothing
  remains. Server computes the same from the completed set.
- **Q4 Climb overlap.** Trails that share segments each count their own `gain_m` (completion-based, like W-DONE).
  Default: accept; state it in product § 3.2.
- **Q5 Logbook kinds.** New enum values vs `milestone` subtypes for climb/high ground: plan-author decides; persisted
  state must decode across the change.

## 9. Non-goals

- No change to roads behaviour, pure mode, coverage math, completion thresholds, the pure leaderboard or its sync.
- No GPS/barometric elevation, live altitude, pace or timing; no timed or ranked challenge; no Play board (v2, PD-21).
- No new obstacle type (brambles stay v2), no creatures, no Prue's House, no notifications, no new MapFx effects.
- No new data in any upload; no new third-party call; no Worker deploy; no store release.
- No changes to rank thresholds or existing marks amounts.
