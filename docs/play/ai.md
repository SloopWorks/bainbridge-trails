# Play — AI for effects and content (brainstorm, graded)

Date: 2026-10-20 · Stage: design (brainstorm; input to the Play spec, not itself a spec) · Status: ACCEPTED 2026-10-20
(see decisions.md) · Brief: [`brief.md`](brief.md) (owner: *"brainstorm use of AI for novel and creative effects"*).

**v1 scope (cut line, product.md "v1 scope").** v1 uses AI **only for offline authoring**: the AI-12 content-pack
workflow, with AI-1 riddles and AI-4 sprites as its jobs, all producing reviewed data in `play-content.json` and SVG
in the repo. Everything else here is **(v2)**; race write-ups (AI-6) and ink-wash weather (N-1) are **(cut)**. Pack
fields follow `schedule.md` § 4 (the normative schema) and rules follow `product.md`; vocabulary per
`docs/play/README.md` (marks, challenge, quiet zone).

**Verdict up front.** AI earns its place in Play mostly at **authoring time**: an agent writes riddles, narrator
notes, sprites and whole seasonal packs into `play-content.json` during a PR, people review it, and the phone
plays it back with **no model at runtime**. That keeps the hosting bill at $0, keeps raw GPS on the phone, and makes
sure a 9-year-old never reads made-up history. Most of the "alive island" feeling (creatures that turn up in the morning,
a kraken that sulks when nobody walks the shore roads) needs **no model at all**: seeded schedules, server
aggregates and procedural drawing. A runtime model call survives in at most one v2 place (an optional "ask the
island", retrieval first, on Cloudflare Workers AI's free daily allocation, with a fallback and a cap); race
write-ups are cut. **Calling the Claude API at runtime is on the "never" list**: it is a
bill and a new third-party processor, which the brief's non-goals rule out.

---

## 0. Ground rules every idea is graded against

### 0.1 Where AI can run (the four tiers)

| Tier | What | Runtime cost | Sees |
|---|---|---|---|
| **P: Precompute** | Agent (Claude Code in the repo, Claude Opus 5.5 `claude-opus-5-5`) generates content into `play-content.json` in a PR; human gates; shipped as config | **$0** at runtime; authoring cost is a dev-tool cost (§1.3) | Curated facts, public trail/road data. Never players. |
| **D: On-device, no model** | Rules, seeded RNG, behaviour trees, procedural drawing (MapFx), platform TTS | $0, offline | The player's own state, which never leaves the phone |
| **S: Server aggregate, no model** | Worker cron + D1 counts → a config field (k-anonymised, daily) | $0 (inside the existing D1/cron budget) | Counts only |
| **W: Worker + model** | Workers AI (free 10,000 neurons/day) behind the Worker; optionally the Claude API | Free tier up to a cap, then a bill | Only coarse context we send it |

Preference order is P > D > S > W. A W idea has to beat the same idea built as P or D by a clear margin.

### 0.2 Privacy (non-negotiable, from the brief and `backend/README.md` §Privacy)

- **No location goes to any model, ever.** Not at runtime, not in a log. At most a model sees coarse context:
  `{park: "Grand Forest", timeBand: "day", season: "kraken", goal: "trails"}`, and only at authoring time or in W.
- No player-identifying data in prompts. No names of any kind: named standings and write-ups are cut (review P0-2).
- A runtime model provider other than Cloudflare means a new processor in `docs/legal/privacy-policy.md`, and the
  brief forbids "new third-party services with a bill". That is why runtime Claude is rejected (§5.3).
- No photos, audio or free text from players go to a server (§AI-9, §N-5).

### 0.3 Safety (kids play)

- **Facts are curated, never generated.** Each factual sentence in shipped content cites a `facts[]` entry. A
  human writes those entries from a named source (city/parks district pages, historical society, signage the owner
  photographed). The model may *rephrase* a fact. It may never *add* one. Validators reject any shipped string
  whose claims aren't backed by a `factId` (§AI-12 gate G3).
- **Myths and fiction are labelled.** Every string has a `kind`: `fact` | `legend` (local folklore, sourced) |
  `fiction` (our invention: the kraken, the owl's moods). The UI marks `legend` and `fiction` with a chip ("Legend",
  "Chart fancy") and an icon, not a status hue.
- **Places that are not a game.** The Bainbridge Island Japanese American Exclusion Memorial (and its trail,
  `trail-bainbridge-island-japanese-american-memorial-trail`), the cemetery and Pritchard Park get no treasures,
  creatures, kraken, riddles or marks: they are quiet zones built from dataset geometry (product § 4.6.3), and the
  schedule validator hard-excludes them. Suquamish history and place names (e.g. T'Chookwop Park) are used
  only as the Tribe publishes them, never generated or "mythologised" by a model (§5.3).
- Placement rules come from data and are enforced by validators, not by trusting a prompt: no private land, no
  beach spots, road spots only on `roadSafety` roads (product § 4.0.2), daylight-only windows, no roadside lures for
  kids (Frog Rock's trigger is snapped onto a 25 mph loop road, never the 35 mph junction; §AI-9).
- No conversational AI characters for kids (no "chat with the kraken"; §5.3).

### 0.4 Grades

**A** = build in v1 with known tech, low risk · **B** = good, needs a spike or volume first · **C** = possible, real
quality/safety/cost risk · **D** = don't (or only in a radically reduced form). Each entry names the **1-day
spike** that would prove or kill it.

---

## 1. Cost model (shows why precompute wins)

### 1.1 Prices used (checked 2026-10-20)

| Item | Price / limit | Source |
|---|---|---|
| Claude Opus 5.5 `claude-opus-5-5` | $4 in / $20 out per MTok; cache read $0.20/MTok | Claude API skill model table (cached 2026-10-06) |
| Claude Haiku 5.5 `claude-haiku-5-5` | $0.10 in / $0.50 out per MTok (≤100K-token prompts) | same |
| Message Batches API | 50% of standard price, async | same |
| Workers AI free allocation | **10,000 neurons/day**, Free and Paid plans; beyond that $0.011 / 1,000 neurons, **Paid plan only** (Free plan cannot be billed, so it is a hard cap) | [Workers AI pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/) |
| `@cf/meta/llama-3.1-8b-instruct-fp8` | 13,778 neurons/MTok in, 26,128 out | same |
| `@cf/meta/llama-3.2-1b-instruct` | 2,457 in, 18,252 out | same |
| `@cf/baai/bge-small-en-v1.5` (embeddings) | 1,841 neurons/MTok | same |
| `@cf/myshell-ai/melotts` (TTS) | 18.63 neurons / audio minute | same |
| `@cf/black-forest-labs/flux-1-schnell` | 4.80 neurons / 512² tile + 9.60 / step | same |
| Workers AI rate limit | text gen 300 req/min, embeddings 3,000 req/min | [Workers AI limits](https://developers.cloudflare.com/workers-ai/platform/limits/) |
| Vectorize (Free) | 30M queried dims/month, 5M stored dims | [Vectorize pricing](https://developers.cloudflare.com/vectorize/platform/pricing/) |
| Workers Free / D1 | 100k req/day; D1 5M rows read, 100k written per day; 5 crons (1 used) | `backend/README.md` |

### 1.2 Assumptions

"Monthly players" = MAU with Play on. Each one has **8 active days/month** with weekend peaking (Saturday ≈ 2.5× an
average day). Heavy use of a Q&A feature = **3 questions per MAU per month**. Content (`play-content.json`, about 200 KB
raw / 50 KB gzip) is fetched with `ETag` on app open, so most of those requests come back 304.

### 1.3 Authoring cost (P tier): paid once per content release, independent of players

Run inside the owner's existing Claude Code plan, the way this repo's agents already work, the marginal cost is **$0**.
Run through the API instead (say, a scheduled pack build), one **seasonal pack** costs:

| Job | Calls | Per call | Total | Batch (50%) |
|---|---|---|---|---|
| Riddles: 200 places × 4 candidates, Opus 5.5 | 800 | 3k cached prefix ($0.0006) + 500 in ($0.002) + 400 out ($0.008) = $0.0106 | $8.50 | **$4.25** |
| Blind-solver validation (§AI-1), Haiku 5.5 | 800 | 2k in ($0.0002) + 50 out (~$0) | $0.17 | $0.09 |
| Narrator: 90 days × 3 candidates, Opus 5.5 | 270 | 3k cached ($0.0006) + 300 in ($0.0012) + 150 out ($0.003) | $1.30 | $0.65 |
| Sprites: 20 new × 3 iterations, Opus 5.5 (not batchable: the agent iterates on screenshots) | 60 | 4k in ($0.016) + 5k out ($0.10) | $7.00 | — |
| **Season total** | | | **≈ $17** | **≈ $12** |

That comes to roughly $50–70/year at four packs, and it is the same at 100 or 10,000 players. It isn't hosting, and
it runs on the dev side, not in the product.

### 1.4 Runtime cost at 100 / 1,000 / 10,000 monthly players

| Approach | Per unit | 100 MAU | 1,000 MAU | 10,000 MAU | Fits $0? |
|---|---|---|---|---|---|
| **Precomputed content** (narrator, riddles, sprites) via Worker `GET /v1/play/content` | 1 request, mostly 304 | 800 req/mo | 8k req/mo | 80k req/mo ≈ 2.7k/day (peak ~7k) vs 100k/day | **Yes, all scales** |
| **Anti-pattern: per-player daily note generated at runtime** (Llama 8B, 1k in + 100 out ≈ 16.4 neurons) | 8 notes/MAU/mo | 13k neurons/mo (~440/day) | 131k/mo (4.4k/day; Saturday ~11k → **over cap**) | 1.31M/mo (44k/day, 4.4× cap) → Paid: (44k−10k)×30×$0.011/1k ≈ **$11 + $5 plan** | Breaks at ~1k |
| **Ask the island: curated-answer retrieval, on device** (BM25 over ~300 answers) | 0 | $0 | $0 | $0 | **Yes, offline** |
| **Ask the island: Worker embedding + Vectorize** over curated answers (bge-small, ~30 tokens ≈ 0.055 neurons; 384-dim query) | 3 q/MAU | 300 q: 17 neurons, 115k dims | 3k q: 165 neurons, 1.15M dims | 30k q: 1.7k neurons, **11.5M of 30M dims** | **Yes** |
| **Ask the island: RAG generation, Workers AI Llama 3.1 8B** (2.5k in ≈ 34.4 + 200 out ≈ 5.2 ≈ 40 neurons) | 3 q/MAU | 12k neurons/mo (400/day) | 120k/mo (4k/day avg, Saturday ≈ cap) | 1.2M/mo (40k/day) → Paid ≈ **$10 + $5 plan** | Up to ~1k, with a cap |
| Same with **Claude Haiku 5.5** ($0.00035/q) | | $0.11/mo | $1.05/mo | $10.50/mo | **No** (any bill + new processor) |
| Same with **Claude Opus 5.5** (1.5k cached system, ≈ $0.0083/q) | | $2.50/mo | $25/mo | $250/mo | **No** |
| (cut) Race write-ups | — | — | — | — | Cut (review P0-2) |
| **Kraken mood / adaptive difficulty** (D1 aggregate in the existing cron) | per day | a few hundred row reads | same | ~10k row reads/day of 5M | **Yes** |
| (cut) Ink-wash weather | — | — | — | — | Cut (review P1-18) |
| **Runtime image generation** (flux-schnell 512², 4 steps ≈ 43 neurons) | per image | — | — | — | Possible, pointless (§AI-4) |

**Takeaway.** Anything priced per player per day runs out of the free allocation somewhere between 1,000 and 10,000
players. Anything priced per content release or per community event costs nothing at any scale. Design every
feature to be the second kind.

---

## 2. Catalogue at a glance (priority order)

| # | Idea | AI job | Runs | Grade | Release |
|---|---|---|---|---|---|
| AI-12 | Seasonal content packs as an agent workflow | generation + validation | P | **A** | v1 (offline authoring) |
| AI-1 | Place riddles, fact-anchored, blind-solver validated | generation + classification | P | **A** | v1 (offline authoring) |
| AI-4 | Agent-authored SVG line-art sprites | generation (code, not pixels) | P | **A−** | v1 (offline authoring) |
| AI-2 | Chart-room narrator, 365-day packs | generation | P | **A** | v2 (needs a narrator surface) |
| AI-3 | Seeded creature behaviour | none (behaviour trees) | D | **A** | v2 (creatures are v2) |
| AI-5 | Personal quest suggestions | ranking (rules) + precomputed phrasing | D + P | **A** | v2 |
| AI-8 | Read-aloud via platform TTS | none (platform speech) | D | **A** | v2 (a11y) |
| AI-7 | Kraken mood from community activity | aggregation | S | **A** | v2 |
| N-1 | Ink-wash weather from real forecasts | — | — | — | **cut** |
| N-2 | Ferry wakes from the schedule (Kelvin wake) | procedural | P + D | **B+** | v2 |
| N-3 | Trap streets: forged chart entries to spot | generation | P | **A−** | v2 |
| N-4 | Survey plate: a walk engraved as a chart | procedural + precomputed titles | D + P | **B** | v2 |
| AI-10 | Adaptive riddle difficulty | aggregation | S | **B** | v2 (needs volume) |
| AI-6 | Race write-ups | — | — | — | **cut** |
| N-5 | Sightings almanac (crowd, enum only) | aggregation | S | **B−** | v2 |
| N-6 | Tide-aware lore and safety | none (prediction tables) | P + D | **A** | v2 (with kraken) |
| N-7 | Marginalia: hand-lettered chart margins | generation | P | **B** | v2 |
| AI-11 | "Ask the island" | retrieval (curated answers); generation graded down | D / W | **B** retrieval · **C** gen · **D** Claude | v2 (retrieval only) |
| AI-9 | Photo-to-place easter eggs | classification | D | **D** upload · **C** on-device · **A** no-photo alternative | v2 (alternative only) |

---

## 3. Entries

Each entry: idea · player experience · AI job · where it runs · cost · privacy · safety · latency/offline · a11y ·
grade + 1-day spike.

### AI-12 · Seasonal content packs as a repeatable agent workflow (the big win) (v1: offline authoring)

**Idea.** Content stops being a code change and becomes a reviewed data release. Each season (and for special
events (v2): the first frost, the Fourth of July parade weekend, the island's fall harvest season), one agent run
produces a **pack**: riddles, lore copy drafts and sprite updates in v1; (v2) narrator notes, creature windows,
quest phrasings, challenge definitions.
Every item cites curated facts and passes machine gates plus human review. The Worker serves it, and the owner can
edit it without an app release (brief acceptance criterion 5).

**Player experience.** When a new pack lands, new permanent riddles unlock in Grand Forest and Fort Ward and the
treasure art gains a winter variant; (v2) the narrator's first winter note appears ("Short days. The heron keeps the
same hours as the ferry."). Nothing ships through the app store.

**AI job.** Generation (copy, riddles, SVG), classification (self-checks against the fact card), adversarial review
(a separate agent context hunting for unsupported claims).

**Where it runs.** P only. Claude Code in a PR, using the repo's agent roles (`.claude/agents/`: `brief-shaper`,
`plan-author`, `adversarial-reviewer`, `design-director`).

**Workflow and gates.**

```
G0 brief        owner/brief-shaper: theme, dates, places in scope, what's new          → docs/play/packs/<id>/brief.md
G1 facts        a HUMAN adds/updates docs/play/lore/facts.yml (each fact: id, text, source URL or "owner photo
                of signage", kind fact|legend, reviewedBy). The model never edits this file (CODEOWNERS on the path).
G2 generate     agent writes candidates → docs/play/packs/<id>/candidates.json (3–4 per slot)
G3 machine gates scripts/validate-play-content.mjs (runs in CI, which has no map data, so it validates
                place ids against the committed backend/src/data/segments.json + a committed places.json):
                - JSON schema; ids unique; dates inside the pack window
                - every `fact`/`legend` string lists factIds that exist; `fiction` strings carry no factIds
                  and contain no proper nouns except allow-listed place names + our fictional cast
                - placement: the schedule validator's rules (quiet zones from geometry, no pool member touching
                  one, no private land, no beach slots, road slots only on `roadSafety` roads, daylight-only
                  windows, creature hours)
                - reading level: Flesch-Kincaid ≤ grade 5 for `audience: all`, ≤ 8 for `audience: adult`
                - blocklist (reuses backend/src/moderation/blocklist.json) + a scary-word list for kids tier
                - riddle checks (§AI-1): answer not in the text, blind solver hits ≥ 2/3, ambiguity check
                - length budgets per surface (narrator ≤ 220 chars, so it fits one card at 200% font)
G4 adversarial  adversarial-reviewer agent, fresh context, given only facts.yml + candidates: "list every claim
                not entailed by a cited fact; every place a child could be lured somewhere unsafe" → must be empty
G5 pick + show  agent picks 1 per slot, renders the Play showcase screens (brief AC 7) for new sprites/effects and
                a static review page (docs/play/packs/<id>/review.html: every string with its cited facts side by
                side, sprites on paper and night palettes, the dated calendar)
G6 human        owner (or a delegated islander reviewer) approves in the PR. No auto-merge for packs.
G7 publish      merge → `npm run build:play` bundles into the Worker (static import, like legal-pages.ts) →
                owner deploys. Clients fetch GET /v1/play/content (ETag); app also bundles the last pack for offline.
```

**JSON the authoring workflow emits** (a working file; `scripts/build-play-content.mjs` turns accepted candidates into
`schedule.md` § 4 items, which are normative; `facts` and `solverScore` stay in the authoring folder):

```json
{
  "schema": 1,
  "packId": "2026-winter",
  "version": 3,
  "validFrom": "2026-12-01",
  "validTo": "2027-02-28",
  "generatedBy": "claude-opus-5-5 via Claude Code",
  "reviewedBy": "owner",
  "quietZones": "from play-content.json (dataset geometry), never re-declared here",
  "facts": [
    { "id": "F-ferry-001", "kind": "fact", "text": "Ferries cross between Winslow and Seattle.", "source": "wsdot.wa.gov/ferries" }
  ],
  "riddles": [
    {
      "id": "R-gf-07", "placeId": "trail-grand-forest-…", "goal": "trails", "audience": "all",
      "kind": "fiction", "factIds": [], "text": "…", "answer": { "type": "mapTap", "placeId": "…" },
      "aliases": [], "hints": ["…", "…"], "difficulty": 2, "solverScore": 1.0,
      "window": { "months": [12, 1, 2], "timeBands": ["day"] }, "tts": false
    }
  ],
  "narrator": [
    { "date": "2026-12-21", "kind": "fact", "factIds": ["F-solstice-001"], "text": "…", "season": "winter", "tags": ["solstice"] }
  ],
  "creatures (v2)": [
    { "id": "owl", "sprite": "sprite-owl@2", "behaviour": "bt-owl@1",
      "windows": [{ "placeIds": ["park:grand-forest"], "timeBands": ["creature-hours"], "months": [11,12,1,2], "weight": 0.6 }] }
  ],
  "phrases": {
    "quest.trailsLeftInPark": ["{n} {goal.unitPlural} left in {park}.", "…"],
    "quest.creatureWindow": ["The {creature} is out at {timeBand}.", "…"]
  },
  "challenges (v2)": [ { "id": "ch-solstice", "scope": "solo", "window": { "from": "2026-12-20", "to": "2026-12-22" }, "metric": "new_metres", "target": 2000 } ],
  "kraken (v2)": { "moodFrom": "server" }
}
```

**Cost.** §1.3: about $12–17 per pack through the API, or $0 marginal inside Claude Code. Runtime $0 at every scale.
**Privacy.** The model sees curated facts and public trail/road data only. **Safety.** G1/G3/G4/G6 are the safety
case, and making facts human-owned is what keeps hallucination out of shipped content. **Latency/offline.** The pack
is bundled in the app, and a newer one is fetched on open, so it works fully offline. **A11y.** G3 enforces reading
level and length; each sprite ships with its `contentDescription`; every string is TTS-safe (no emoji or ASCII-art
punctuation). **Grade A.** **Spike (1 day):** hand-write 10 facts for Grand Forest + Fort Ward, run G2–G5 for a
"mini-pack" (10 riddles, 14 narrator days, 1 creature), and implement `validate-play-content.mjs` with the schema,
factId and memorial checks. Pass condition: zero unsupported claims survive G3+G4 on a planted-error test (seed 3
candidates with invented facts and check all 3 get caught).

### AI-1 · Riddles and puzzles per place, with answer validation (v1: offline authoring)

**Idea.** Each place (trail, park, landmark, shore road) gets 2–4 riddles whose answer is a **place**, an
**observable thing at the place**, or a **curated fact**. They are generated per content release from the place's
fact card. Recommendation: **constrained free generation**. Templates alone read flat after the fifth riddle, and
unconstrained generation invents facts. So the model writes freely inside a schema: one answer, ≥ 1 cited fact (or
`kind: fiction` with no facts), a fixed meter/length band, and 2 graded hints. About 20 human-written riddles in the
pack style guide serve as few-shot anchors for voice.

**Player experience.** Marcus's kids, at the Sakai Park trailhead, open a riddle card in Fraunces type on chart
paper: *"I hold still water and keep a dead tree's reflection. Find me before the lily pads close."* They walk,
tap the pond on the map (a stamp), then walk there to claim it. Each hint lowers the reward by 25 % (floor 50 %,
product § 4.4). (The example is
illustrative; shipped text cites real fact ids.)

**Answer validation.**
- **Default answer type: `mapTap`** (tap the place on the map, or pick it from a list for TalkBack/VoiceOver users).
  No typing, nothing for kids to misspell, nothing free-text to moderate, and fully offline.
- **`observe`** (something only knowable on site, e.g. "how many posts hold the bridge rail?"). The answer is
  curated by a human who has been there; the model may *propose* observe-riddles, but they are flagged
  `needsFieldCheck` and can't ship until a person confirms on site.
- **`text`** (rare, adults tier): the agent precomputes `aliases[]` (plural, article, common misspellings), and the
  device normalises (lowercase, strip articles/punctuation) and accepts Levenshtein ≤ 1 for answers of 5+ characters.
  No runtime model.
- **Blind-solver gate (G3).** A second model (Haiku 5.5, a fresh context) gets the riddle plus the 20 nearest
  candidate places and has to pick the answer. A riddle ships only if the solver hits the intended answer in ≥ 2/3
  runs (*solvable*) and fails when given only the first line (*not trivial*). Solver confidence seeds `difficulty`,
  and AI-10 calibrates it later.

**AI job.** Generation + classification (solver). **Runs** P. **Cost** ≈ $4–9/release (§1.3), $0 runtime.
**Privacy** none at runtime; validation on device. **Safety** fact-anchored; `observe` riddles field-checked; no
riddle whose answer requires leaving a trail, entering water, or crossing a road without a crossing (validator
checks the answer place against road class). **Offline** fully. **A11y** list alternative to map tap; riddle and
hints readable by TTS (AI-8); no colour-only clues ("the red gate" needs a second cue). **Grade A.**
**Spike (1 day):** 40 riddles for Gazzam Lake Preserve + Grand Forest + Frog Rock, run the blind solver, read all 40
by hand. Measure: % passing, % a human judges good, % with any unsupported claim (must be 0 after gates).

### AI-2 · The Chart-room narrator (daily island notes) (v2)

**Idea.** One short note per day (≤ 220 chars) in one voice: a patient harbourmaster-cartographer, dry and warm,
never cute, a little archaic in diction ("hereabouts", "the chart shows") but clear. They are precomputed as
**365-day packs**, layered as `season → month → special date → weekday flavour`, with 2–3 alternates per date so a
returning player doesn't see the same note as a friend on the same day (variant chosen by `hash(installId, date)`,
on device).

**Player experience.** Dana opens the app at 6:40 on a February Tuesday. Under the Me hero's water lines:
*"Frost on the boardwalks at Gazzam. The chart says nothing about slipping; the chart was drawn in summer."* Tap it
and it reads aloud (AI-8). On a `fact` note, the "From the logbook" footer links the source.

**AI job.** Generation, voice-constrained. The style guide (`docs/play/voice.md`: lexicon, banned words, 20 gold
notes) is the cached prompt prefix. **Runs** P. **Cost** ≈ $0.65–1.30 per 90 days through Batch; $0 runtime.
**Privacy** none. **Safety** about 70% `fiction`/mood (weather, light, the cast), 30% `fact` with factIds; no
safety advice disguised as flavour ("the tide's out, go explore" is banned, since tides are handled by N-6).
**Offline** yes. **A11y** text first, TTS, honours dynamic type, no emoji. **Grade A.**
**Spike (1 day):** write the voice guide + 20 gold notes, generate 90 winter days, blind-shuffle 10 gold and 10
generated notes, and have the owner pick out the generated ones. If they score ≤ 70%, the voice holds.

### AI-3 · Procedural creature behaviour (no LLM) (v2)

**Idea.** Grand Forest creatures (owl, heron, a fox, a banana slug for the kids), the kraken, the gulls on the
ferry each run a **tiny behaviour tree on device**: `Selector(Sleep if outside window, Sequence(Approach waypoint,
Idle anim, Maybe-call), Flee if player close)`. Trees are data (`bt-owl@1` in the pack). Randomness is **seeded
by the schedule** (schedule.md § 2.2 per-item fnv1a64 seeds). Everyone on the island sees the owl in the same grove
today, and it is still a surprise from day to day. Windows lie inside creature hours only (sunrise + 30 min → sunset
− 30 min). Fair and deterministic (brief AC 5), and
testable in unit tests by pinning the date.

**Player experience.** On a Saturday morning Marcus's map shows a small inked owl gliding between two snags on the
Grand Forest trails. It lands, turns its head, then flies off as he gets close (the "Flee" node fires on proximity,
computed on device). His daughter's phone shows the same owl in the same grove.

**AI job.** None; this is game AI, not ML. **Runs** D, as a MapFx entity, driven by the single MapFx clock, budget
≤ 3 active creatures on screen. **Cost** $0. **Privacy** proximity is computed on device and never leaves it.
**Safety** creatures never sit off-trail (waypoints are snapped to trail geometry by the content validator) and
never lure toward roads or water. **Offline** yes. **A11y** reduced motion = a static sprite at the perch, with a
TalkBack announcement ("An owl is perched in Grand Forest, near the Upper Trail"), at most once per session per
creature. **Grade A.** **Spike (1 day):** BT interpreter (≈ 150 lines of KMP common code) + one owl tree +
deterministic tests (same seed → same path), rendered in the MapFx showcase with a time scrubber.

### AI-4 · Line-art sprites in the chart style (v1: offline authoring)

**Options compared.**

| | Image model (flux-schnell, SDXL…) | Hand-drawn SVG (human) | **Agent-written SVG path data** |
|---|---|---|---|
| Style consistency | Poor: line weight, perspective, "AI sheen" drift between sprites | Excellent | Good, *if* constrained by a pen grammar |
| Light/dark themes | Raster, needs two renders | Tokens | **Tokens** (`stroke = ink`, swap per theme) |
| Animatable (MapFx) | No (pixels) | Yes | **Yes: named groups (`wing-l`, `head`) and PathMeasure draw-on** |
| Size | 20–100 KB PNG each | 1–4 KB | 1–4 KB |
| Licence/provenance | Murky training data | Clear | Clear (the code is ours) |
| Cost | ~43 neurons/image; cheap but useless | Artist time | ≈ $0.35/sprite via API (§1.3) |

**Recommendation: an agent-written SVG pipeline.** The agent writes SVG under a **pen grammar** (`docs/play/pen.md`):
viewBox 64×64; strokes only (fills only for a 15%-ink hatch); two stroke widths (1.25, 2.0); round caps; ≤ 60
path commands; hatching for shade, never gradients; colour only through tokens (`ink`, `paper`, `waterLine`, and
**never a status hue**); a named group for each animatable part; a `<title>` that becomes the `contentDescription`.
A build step (`scripts/svg-to-imagevector.mjs`) converts to Compose `ImageVector` + per-group path data for MapFx.
**Review is visual:** the agent renders every new sprite on the showcase screen (paper + night palettes, 24/48/96 dp,
reduced-motion pose, next to 3 existing sprites for consistency) and attaches the screenshots to the PR; the
`design-director` agent reviews them against the pen grammar, then the owner signs off. A lint enforces the grammar
(stroke widths, command count, token-only colour).

**Player experience.** The kraken's tentacle curls out of the water lines in the same engraved hand as the
compass rose and the owl, and in dark mode it re-inks in the night palette.

**Runs** P (D renders it). **Cost** ≈ $7 per 20 sprites, $0 runtime. **Privacy** n/a. **Safety** the design gate
covers "creepy": the kraken is grumpy and cartoonish, never gory (pen.md lists banned motifs: teeth, eyes in the
dark, blood). **Offline** yes. **A11y** `<title>` → contentDescription; contrast ≥ 3:1 for ink on paper in both
themes; reduced-motion pose. **Grade A−** (the agent's drawing quality varies; the grammar plus showcase loop is the
mitigation). **Spike (1 day):** pen.md + lint + converter; agent draws owl, frog, ferry, and a kraken tentacle in
3 iterations each from showcase screenshots. Pass = owner calls 3 of 4 "on-style" without edits.

### AI-5 · Personal quest suggestions (v2)

**Idea.** A one-line nudge on the map sheet: *"3 trails left in Grand Forest. The owl is about this morning."* It is
**computed on device** from the player's coverage (already local in the coverage store), the pack schedule (AI-3
windows, N-6 tides, challenges) and simple ranking: `score = unfinishedInPark × proximityBand × windowOpenSoon ×
novelty`. Proximity comes from the device's last known location, used **only locally**, banded (near/far), and never
stored in Play state.

**LLM-written phrasing vs no model.** The *decision* needs no model. The *phrasing* comes from precomputed
template variants (`phrases.*` in the pack: 30 templates × 6 variants, generated and reviewed like everything else),
with slots filled on device through `GoalVocabulary` (trails vs roads words; brief constraint). A runtime LLM would
add latency, cost and hallucination risk ("the owl is definitely there!") and buy nothing.

**Player experience.** Dana, roads goal on a Saturday: *"Four roads left on the south end. The kraken is restless
near Lynwood; best walked at low tide after 2."* (slots: count, area, kraken mood from AI-7, tide from N-6).

**Runs** D + P. **Cost** $0. **Privacy** best case: location never leaves the phone and isn't persisted by Play.
**Safety** suggestions never recommend shore spots outside a safe tide window, or after dark for `audience: all`.
**Offline** yes. **A11y** plain sentence; actionable ("Show on map" button). **Grade A.** **Spike (1 day):** ranking
function + 30 templates + unit tests over 5 synthetic coverage states × 2 goals.

### AI-6 · Race commentary and results write-ups (cut)

Cut entirely (review P0-2): no named standings or write-ups; challenges report `{band, count}` only.

### AI-7 · Kraken mood from community activity (v2)

**Idea.** The kraken's temperament (`dozing` → `restless` → `rampaging` → `sated`) comes from **community
shore-road coverage**: how many distinct walkers covered shore roads this week vs a 4-week baseline. Mood changes
only the art and the copy; which coast and stretches are attacked stays product's fixed rotation and rewards stay
product's (no extra stretches, no bonus marks).
A busy week puts it to sleep. The server aggregate is computed in the existing daily cron
(`rebuildSnapshots`) and written to `play_state.kraken_mood`, served in `/v1/play/content`.

**Player experience.** Monday: *"The kraken is restless: only a handful of walkers along Rockaway Beach Rd last
week."* Tentacles curl over three shore roads on the roads map. By Friday the community has cleared them and the
narrator note changes to *"Sated. It has gone back under the ferry lanes."*

**AI job.** None, just aggregation and a state machine. **Runs** S + D. **Cost** a few hundred D1 reads/day. **Privacy**
count of distinct users per week over the whole shore-road set, never per road with < 5 contributors, a daily
snapshot (diff-resistant as in the README). **Safety** stretches only on `roadSafety` roads; inactive above the tide
threshold (product T-2). The kraken never "attacks" a player, only roads. **Offline** last mood
cached, decays to `dozing` if > 3 days stale. **A11y** mood is a word + icon pose, not just animation; announced once
per day. **Grade A.** **Spike (1 day):** SQL over `user_trails` for the roads dataset + mood state machine +
vitest with fixtures for hysteresis (no flapping between states).

### AI-8 · Voice: platform TTS for riddles and lore (v2)

**Idea.** A "Read aloud" control on riddle, narrator, lore and challenge cards uses **Android `TextToSpeech` / iOS
`AVSpeechSynthesizer`**. It is free, on device and offline (with the system voice installed), and it respects the
user's chosen voice and rate. It does double duty: it's a delight for kids who can't read yet (Marcus's
youngest) and an accessibility feature for low-vision users. It also complements, but doesn't replace, screen
readers: when TalkBack/VoiceOver is on, the button hides, so the screen reader isn't fighting a second voice.

**Content hooks.** The pack authors `ssmlLite` hints the agent precomputes (pauses after line breaks in riddles,
pronunciations for local names via a curated lexicon, e.g. how islanders say "Winslow", "Gazzam", "Suquamish";
human-checked). Android supports SSML on many engines; iOS uses `AVSpeechUtterance` attributes. Fall back to plain
text.

**Why not AI voices.** Workers AI melotts is cheap (≈ 18.6 neurons/min), but precomputed audio for 365 notes runs
to megabytes, can't follow the user's voice/rate settings, and a branded "narrator voice" invites cloning requests
(§5.3). Maybe v3: a handful of pre-rendered hero lines for the kraken, owner-approved.

**Cost** $0. **Privacy** on device. **Offline** yes. **A11y** this *is* an a11y feature: respects
reduced motion (no karaoke highlight animation), with captions always visible. **Grade A.** **Spike (1 day):**
KMP `expect/actual` `Speaker`, the lexicon for 15 local names, test on both platforms with 10 riddles.

### AI-9 · Image-to-place easter eggs (photograph Frog Rock) (v2: the no-photo alternative only)

**The ask.** The player photographs Frog Rock (the painted boulder) and gets an easter egg.

**Assessment.**
- **Upload to a server model: D.** Photos carry faces (kids), licence plates, house numbers and EXIF GPS. Any of
  that breaks the privacy stance, needs moderation, and adds a processor. Don't.
- **On-device classifier: C.** Feasible (a small Core ML / TFLite model, or a generic image-labelling API), but
  it needs a training set of Frog Rock from many angles and seasons, and the paint changes. Recognising *this*
  rock rather than "a rock" is unreliable, and spoofing it with a picture of a picture is trivial. **Safety is the
  killer.** Frog Rock is at a road junction, and a "take a photo" mechanic puts kids on the verge, looking at a screen,
  next to traffic.
- **No-photo alternative (recommended, A): "Look up" moments.** When the player is inside the place's geofence
  (on device, and only at curated safe viewpoints such as a sidewalk or parking area that the validator checks
  against road class), an `observe` riddle appears: *"The frog has company this month. What's painted beside it?"*
  It's answered from a 4-choice list that a human field-checked for the pack's season. It rewards being there
  and looking, needs no camera, and a parent can answer by reading it out. It also ships with an "I'm not there"
  path (the stamp is just a lore card, with no marks) so nobody is pushed to go.

**Runs** D + P. **Cost** $0. **Privacy** geofence check on device; nothing uploaded. **A11y** list answers, TTS.
**Spike (1 day)** for the alternative: geofence + observe-riddle flow at 3 curated viewpoints, with emulator route
replay (`scripts/emu-route.sh`).

### AI-10 · Adaptive riddle difficulty from solve rates (v2)

**Idea.** Clients sync tiny counters per riddle id (`shown`, `solved`, `hintsUsed`, bucketed to 0/1/2), with no
time and no place, in the existing summary sync. The nightly cron computes the solve rate per riddle, and the pack
config gains `difficultyObserved`, which re-tiers riddles (marks, which hint is free) and suggests easier riddles
first to players with low personal solve rates (the personal rate is computed on device, never sent).

**AI job.** Aggregation (a Bayesian solve rate with a prior from the blind-solver score). **Runs** S + D. **Cost**
one D1 upsert per riddle per sync; well inside 100k writes/day at 10k MAU if batched per sync. **Privacy**
counters only, k ≥ 5 before a riddle's observed rate is used. **Safety** n/a. **Offline** uses last config.
**A11y** n/a. **Grade B**: real value only at roughly ≥ 30 attempts per riddle, so v2. **Spike (1 day):** schema +
endpoint + Beta-binomial estimate + vitest; simulate 1,000 players to check convergence.

### AI-11 · "Ask the island" (Q&A about trails and history) (v2)

**Idea.** A text box on the Lore sheet: "Is Fort Ward dog-friendly?", "Why is it called Frog Rock?", "How long is
the Grand Forest loop?"

**Honest grading.**

| Variant | How | Grade | Why |
|---|---|---|---|
| **Curated answers + retrieval, on device** | ~300 Q&A pairs authored in the pack (agent-drafted from `facts.yml`, `trails.json`, docs/; human-approved), BM25/keyword match on device, top-3 shown as cards with sources; "No answer on the chart yet" otherwise | **B** | $0, offline, can't hallucinate. Limit: only answers what we wrote |
| Curated answers + Worker embeddings (bge-small + Vectorize) | Same answers, better paraphrase matching | **B** | Free at 10k MAU (§1.4), but online-only; on-device first |
| RAG generation, Workers AI Llama 8B | Retrieve facts → generate answer | **C** | Small models paraphrase into falsehood, kids read the output, budget hits the cap around 1k MAU, and we'd need output checks |
| RAG generation, Claude API | Same, better model, cached system prompt, daily token budget | **D** for v1/v2 | Good answers, but a bill + new processor (brief non-goal), and free text from kids goes to a third party |

**Recommendation.** Ship the first row (v2), measure **unanswered queries** (counted on device; optionally
synced as a normalised query string only if it matches no blocklist and contains no digits, which catches
addresses and phone numbers), and feed them into the next pack's Q&A authoring. Over time the "AI" is an
authoring loop that learns what people ask, not a runtime chatbot. **Privacy** questions stay on device by default.
**Safety** only curated answers are shown, with no generated text at runtime. **A11y** results as a list; TTS.
**Spike (1 day):** 100 curated Q&A for 3 parks, BM25 in common Kotlin, 50 paraphrased test questions, measure
top-3 hit rate (target ≥ 80%).

---

### Ideas of our own (novel for a map app)

### N-1 · Ink-wash weather from real forecasts (cut)

Cut entirely (review P1-18): no weather feed, no weather visuals, no third-party call.

### N-2 · Ferry wakes from the schedule (generative wake patterns) (v2)

**Idea.** The ferries crossing the Sound on the chart, positioned from the **published WSF sailing schedule**
(precomputed into the pack as sailing times; interpolated along a fixed route line on device; no live AIS), each
trailing a **true Kelvin wake** (the 19.47° half-angle V with transverse arcs) drawn as fading ink lines that
interfere with the coastline water lines. Wake patterns are physics, not ML, but generative and unique to every
crossing. A shipped "ferry horn" moment fires when a boat docks at Winslow, timed to the schedule.

**Player experience.** Waiting at the terminal, Marcus's son watches the inked ferry slide in on the app at the
same moment the real one arrives (± schedule slip; the UI says "scheduled", not "live").

**Cost** $0 (schedule refreshed with packs; the WSDOT traveller API needs a free access code and may be used
later for delays: [WSDOT Traveler Info API](https://wsdot.wa.gov/traffic/api/)). **Privacy** none. **Safety** n/a.
**Offline** yes. **A11y** reduced motion = static ferry icons at their scheduled positions with times; announced
"Ferry to Seattle departs 7:05". **Grade B+.** **Spike (1 day):** Kelvin-wake path generator (common Kotlin, unit
tested for the angle) + one ferry on a fixed line in the MapFx showcase.

### N-3 · Trap streets: forged chart entries to spot (v2)

**Idea.** Real cartographers planted fictitious "trap streets" and "paper towns" to catch copiers. Each month the
Play chart hides **one forgery**: a trail name that doesn't exist, a slightly wrong park outline, a phantom
island in the Sound, labelled in the same engraved hand. Players find it by **noticing** (tap it → "You've caught
the forger!"). No walking required, and no one is lured anywhere, because the forgery is in the map, not on the
ground: phantom trails sit in water or on public parkland, never on private land, and phantom paths can't be walked.

**AI job.** Generation (plausible-but-fake names in the island's naming style, checked against real names and
Geographic Names lists so they don't collide; fake outlines generated as small perturbations of real geometry,
always rendered *only* in Play mode and labelled as "chart fancy" after discovery). **Runs** P. **Cost** $0
runtime. **Safety** forgeries never on roads or land a person could try to reach; never in Pure mode (byte-for-byte
unchanged, brief AC 1). **A11y** the list view includes "Chart oddities" with forged entries discoverable by name.
**Grade A−.** **Spike (1 day):** 12 forged names + 3 phantom shapes, validator for collision with real names and
for placement (in water or on public parkland, never walkable).

### N-4 · Survey plate: your walk engraved as a chart (v2)

**Idea.** After a walk that finished a trail, the celebration offers a **survey plate**: a generated engraving in
the chart style with a cartouche title, a compass rose, soundings (elevation numbers along the trail), hachured
hills from the hillshade, and a scale bar. The rendered line is the **public trail geometry of the trails you
completed**, not your GPS track, so sharing reveals nothing about where you live or when you walked. Title from
precomputed templates: *"A Survey of the Gazzam Uplands, made in the Autumn of 2026 by Captain Quietheron"* (anon
name).

**AI job.** Procedural layout (label placement, cartouche ornament seeded per plate) + precomputed title phrase
bank (P). **Runs** D. **Cost** $0. **Privacy** public geometry + anon name only; no date finer than season, no time.
**Safety** n/a. **Offline** yes. **A11y** alt text generated from the same slots ("Survey plate: Gazzam Lake Loop,
1.8 miles, completed autumn 2026"). **Grade B** (typography and label collision are real work).
**Spike (1 day):** render one plate to a bitmap from `trails.json` geometry with Fraunces cartouche; screenshot both
themes.

### N-5 · Sightings almanac (crowd-sourced, enum-only, moderation by design) (v2)

**Idea.** Players log what they saw: **pick from a closed list** (species/thing × park × time band: "Heron ·
Blakely Harbor · morning"). No free text, no photos, no coordinates, so nothing needs moderating. Logs are
batched in the summary sync; the cron aggregates per park per week, shows a park's sighting only with ≥ 5 distinct
reporters, and publishes with a 24 h delay. The almanac feeds the next pack: the agent proposes creature windows
(AI-3) from the observed seasonality, and a human approves. This closes the loop between real islanders and the
fictional cast.

**AI job.** Aggregation (S) + authoring-time pattern suggestion (P). **Cost** small D1 writes. **Privacy** park +
time band, delayed, k ≥ 5. **Safety** list curated (no "saw a person", nothing about people, no protected nesting
sites; the list avoids exact locations of sensitive species). **A11y** list picker. **Grade B−** (needs volume;
abuse = spamming a species, mitigated by 1 report per species per park per day per user). **Spike (1 day):**
schema + aggregate + almanac card in the showcase.

### N-6 · Tide-aware lore and safety (v2)

**Idea.** NOAA tide predictions are deterministic, so the authoring step bakes a year of high/low tides for the
nearest station into the pack ([NOAA CO-OPS API](https://api.tidesandcurrents.noaa.gov/api/prod/); Eagle Harbor
9445882, subordinate to Seattle 9447130, schedule.md § 1.6). v1 already uses it for one rule: kraken stretches are
inactive above the tide threshold. (v2) Uses: shore slots open only inside safe low-water windows; the narrator
remarks on the tide; the quest line (AI-5) says "best at low tide after 2". There is no king-tide kraken (cut).

**Cost** $0 runtime. **Offline** yes. **Grade A.** **Spike (1 day):** fetch + compress a year of predictions (~1.4k
events), a `tideAt(t)` interpolator in common code, and validator checks for beach slots.

### N-7 · Marginalia: hand-lettered notes in the chart margins (v2)

**Idea.** Old charts carry notes in the margins: "Good anchorage", "Here the current runs strong". In Play mode,
the map's **screen edges** (Compose overlay, outside the coastline) carry 1–2 small italic marginalia for the
area in view, precomputed per park/shore segment (`kind` labelled), fading in as you pan, so the island seems to be
annotated by a previous surveyor. *"Owls. Many owls."* beside Grand Forest. *"Do not trust the kraken."* off Rolling
Bay.

**AI job.** Generation (P), same gates as the narrator. **Cost** $0 runtime. **A11y** marginalia are decorative
(`contentDescription = null`) unless tapped; reduced motion = no fade. **Grade B** (screen-space label layout).
**Spike (1 day):** 40 marginalia + an overlay that pins them to the nearest viewport edge from geo anchors.

---

## 4. Cross-cutting rules for any model use

1. **Model ids.** Authoring default `claude-opus-5-5` (adaptive thinking; effort `high` for riddles/sprites,
   `medium` for narrator). Validation/solver: `claude-haiku-5-5`. Use the Batches API for bulk, non-interactive
   generation. Use exact ids only (no date suffixes). Runtime (v2 only): Workers AI only,
   `@cf/baai/bge-small-en-v1.5` if embeddings are ever used.
2. **Everything generated is data in git**, with `generatedBy`, `reviewedBy` and cited `factIds`. Re-runs diff
   cleanly. Nothing generated at runtime is shown without an exact-slot check and a template fallback.
3. **Budget guard in the Worker** for any W feature: a D1 counter `ai_neurons:<yyyy-mm-dd>` with a hard ceiling of
   8,000/day (20% headroom under the free 10,000), falling back to template text when exceeded. Never upgrade to Paid
   to get past it without an owner decision.
4. **Pure mode untouched.** No AI artefact renders, fetches or computes when Play is off.
5. **Prompts are versioned** in `docs/play/prompts/` with the pack that used them, so the voice can be reproduced.

---

## 5. Recommendations

### 5.1 v1 (offline authoring only; zero runtime model)

1. **AI-12 Content-pack workflow** (agent + gates G0–G7 + `validate-play-content.mjs`, feeding
   `scripts/build-play-content.mjs` and `/v1/play/content`). This is the engine every other item rides on.
2. **AI-1 Fact-anchored permanent riddles** (place and feature answers) with the blind-solver gate.
3. **AI-4 Agent-written SVG sprites** under the pen grammar, reviewed as showcase screenshots (brief AC 7).

v1 runtime AI spend: **$0 at every scale.** Authoring: ≈ $12–17/season through the API, or $0 marginal in Claude Code.

### 5.2 v2

AI-2 narrator (with AI-8 platform TTS) · AI-3 + AI-5 seeded island brain (creatures and quest suggestions) · AI-7
kraken mood (art and copy only) · N-6 tide-aware shore lore · N-2 ferry wakes · N-3 trap streets · N-4 survey plate ·
AI-10 adaptive difficulty (once riddles have ≥ 30 attempts each) · N-5 sightings almanac · N-7 marginalia · AI-11
"ask the island" as **curated answers retrieved on device** · AI-9 as **"look up" observe riddles** (no camera).

**Cut:** AI-6 race write-ups (named standings), N-1 ink-wash weather.

### 5.3 Never (with reasons)

| Never | Why |
|---|---|
| **Runtime Claude API calls** (Q&A, narration) | A bill at every scale ($0.11–$250/mo, §1.4), a new third-party processor (privacy policy change), and kids' free text going to a third party. The brief's non-goal: no new services with a bill. Authoring-time use in PRs is fine. |
| **Generated history, facts or local "myths"** | Hallucinated history is unacceptable; facts are human-curated (G1), and models only rephrase. Inventing a "legend" and passing it off as local folklore misleads the island about itself. |
| **AI content about the Japanese American Exclusion Memorial or Suquamish history/culture** | Not game material. The memorial is hard-excluded from Play. Tribal history is used only as the Tribe publishes it, never generated, gamified or "mythologised". |
| **Uploading photos (or audio) to any server or model** | Faces of kids, plates, house numbers, EXIF GPS. It breaks the stance and needs moderation. |
| **Per-player runtime generation** (personal daily note, personal riddles) | Breaks the free tier at ~1k MAU (§1.4), varies in quality, and can't be reviewed. Personalisation comes from on-device selection over reviewed variants. |
| **Image-model sprites/art** | Style drift, raster, unthemeable, can't be animated, unclear provenance. Use agent-written SVG instead. |
| **Conversational AI characters** ("chat with the kraken/owl") | Parasocial with kids, unbounded output, moderation burden, and a runtime model anyway. |
| **AI-generated "historic photos" or reconstructions** | Fabricated imagery reads as evidence. |
| **AI voice cloning / branded synthetic voice of a real person** | Consent and impersonation risk; the platform TTS respects user settings for free. |
| **LLM-chosen coordinates** | Placement must pass land-use, tide and road-class validators on real data. A model "placing" a treasure is how kids end up on private land or on the verge at Frog Rock. |
| **Free-text community content** (sightings notes, riddle submissions) | Needs moderation we can't staff at $0. Use closed lists (N-5). |

---

## 6. Open questions for the owner

1. **"Prue's house"**: what and where? No model may guess. It needs a human-written fact entry before any content
   mentions it.
2. Who besides the owner can be a **G6 reviewer** (an islander with local history knowledge)?
3. Fact sources the owner is comfortable citing (city/parks district pages, historical museum, signage photos).
4. Do we want the Tribe consulted before *any* Suquamish place name appears in Play copy, or stick to official park
   names only?
5. Is ≈ $12–17/season of API authoring acceptable for automated packs, or does authoring stay inside Claude Code
   sessions ($0 marginal)?

## Sources

- Claude model ids and prices: Claude API skill reference, model table cached 2026-10-06 (Opus 5.5 $4/$20, Haiku 5.5
  $0.10/$0.50 per MTok; Batches 50%).
- Workers AI pricing (free 10,000 neurons/day; per-model neuron rates): https://developers.cloudflare.com/workers-ai/platform/pricing/
- Workers AI limits (300 req/min text gen, 3,000 embeddings): https://developers.cloudflare.com/workers-ai/platform/limits/
- Vectorize pricing (Free: 30M queried dims/month, 5M stored): https://developers.cloudflare.com/vectorize/platform/pricing/
- NWS API (N-1, cut): https://www.weather.gov/documentation/services-web-api
- NOAA CO-OPS tides API: https://api.tidesandcurrents.noaa.gov/api/prod/
- WSDOT Traveler Information API: https://wsdot.wa.gov/traffic/api/
- Repo: `docs/play/brief.md`, `docs/mvp/product.md` §1, `docs/mvp/ux.md` §0, `backend/README.md`, `backend/src/index.ts`,
  `backend/src/data/segments.json`, `data/dist/trails.json`.
