import { communityCount, MIN_COMMUNITY_COUNT } from "../routes/board";
import { DATASETS, type Dataset } from "../segments";
import { type ClaimCode, inGoal, killed, rotationStretches } from "./claims";
import { CURRENT, PUBLISHED } from "./config";
import { DERIVED } from "./derived";
import { itemMarks } from "./marks";
import {
  type Occurrence,
  type PlayItem,
  type PlayPack,
  addDays,
  capsOf,
  dateOfEpochDay,
  epochDay,
  islandDate,
  islandInstant,
  islandIso,
  occurrenceOn,
  weekId,
  weekIdAt,
} from "./recurrence";
import { type TrailMetric, challengeTarget, tablesOf } from "./trails";
import { completedSet } from "../users";

/**
 * Challenges, kraken aggregates and the daily Play cron (schedule.md § 7.3, § 7.4; product § 3.2 W-WEEK, § 3.4,
 * § 4.3.2, § 4.5; PD-3, PD-4). Windows are server-authoritative: they come from the Worker's current pack and island
 * time, never from the client. Nothing here is timed and nothing names a person: public results are `{band, count}`.
 *
 * Contributions (`play_progress`, migration 0006), one count per user and challenge occurrence. One Play upload (the
 * request carried `play`) contributes to at most ONE occurrence per challenge item (plan P1-1): the one active at
 * `now`, else the latest whose window end is still inside the find grace. A walk synced late, after the next window
 * opened, therefore counts toward the new occurrence while the phone counted it by its Logbook date (PD-26).
 * - `ch-sweep@<Saturday>` (solo, metric `completed`): trails / roads newly completed by the upload. The claim
 *   `{ id: "ch-sweep@<Saturday>", kind: "challenge" }` is accepted when that count reaches the goal's target.
 * - `ch-climb@<Monday>` (solo, metric `climb`, trails only, v1.1): Σ gain of the non-quiet trails newly completed, in
 *   integer decimetres (`derived-trails.json` `featureGainM` × 10; 149.9 m = 1,499 < 1,500). The pack's target is
 *   metres; the server carries it as dm everywhere (claims, `/challenges`, bands).
 * - `ch-parkhop@<Saturday>` (solo, metric `parks`, trails only): the distinct Play parks (Pritchard Park excluded by
 *   the pack build, quiet trails never) among the trails this upload newly completed. A count only (plan D12 reversed
 *   by the privacy review: no per-park key table), so the same park reached by two different trails completed in two
 *   separate uploads of one window counts twice; a bounded overcount, documented in api.md.
 * - The two trails challenges have a personal target (D5): `min(default, remaining + progress)` where remaining is
 *   what the user's sticky completed set still leaves (climb: dm of non-quiet trails not completed; parks: Play parks
 *   with a non-quiet trail not completed); 0 = nothing left: not listed for the user, never claimable. The claim is
 *   accepted when progress ≥ target > 0 and the occurrence is unclaimed (P2-6), inside the existing caps.
 * - `ch-krakenweek@<Thursday>` (island, metric `stretches_repelled`): kraken stretches of that attack the user
 *   repelled (accepted `ink` claims). `play_attack_stretches.repelled_by` counts each stretch's distinct users.
 *   The island challenge is never claimed by the client: the cron credits it.
 *
 * Cron (`playDaily`, run by the existing `17 9 * * *` `daily()`; idempotent: every credit is guarded by the row that
 * records it, so running twice, or concurrently, credits once):
 * - W-WEEK: a closed week with `day_keys ≥ 2` is kept: +40, +10 per consecutive kept week before it, max +100
 *   (`play_weeks.kept` = the marks paid; 0 = not kept, closed only once its dayKey grace is over; NULL = open).
 * - attacks whose claims can no longer arrive (end before the server island date − find grace): when every stretch
 *   was repelled by ≥ `minPlayersPerStretch` (3) users, F-RETREAT (+50, claim row `kraken-<goal>@<Thursday>`) and
 *   C-ISLAND (+75, claim row `ch-krakenweek@<Thursday>`) to every contributor, in the attack's week (weekly found cap).
 * - purge stretch aggregates and contributions of occurrences older than 180 days.
 */

const DAY_MS = 86_400_000;
const DEFAULT_MIN_PLAYERS = 3;
const DEFAULT_RETREAT_MARKS = 50;
/** W-WEEK ladder (product § 3.2). */
export const WEEK_BASE_MARKS = 40;
const WEEK_STEP_MARKS = 10;
const WEEK_MAX_MARKS = 100;
/** Attacks the cron looks back over (a missed run catches up; credits are idempotent). */
const ATTACK_LOOKBACK_DAYS = 21;
/** Occurrences listed by GET /v1/play/challenges: started within this many days (window ≤ 4 days + grace). */
const CURRENT_LOOKBACK_DAYS = 10;
const PURGE_DAYS = 180;
const CRON_ROWS = 200;
const CRON_ROUNDS = 10;


// ---------------------------------------------------------------------------------------------------------------
// Definitions

interface ChallengeSpec {
  scope?: string;
  metric?: string;
  target?: Record<string, number>;
  minPlayersPerStretch?: number;
  retreatMarks?: number;
}

export type ChallengeMetric = "completed" | "stretches_repelled" | TrailMetric;
type SoloMetric = Exclude<ChallengeMetric, "stretches_repelled">;

export interface ChallengeDef {
  item: PlayItem;
  scope: "solo" | "island";
  metric: ChallengeMetric;
  spec: ChallengeSpec;
}

const SOLO_METRICS: readonly SoloMetric[] = ["completed", "climb", "parks"];
const isTrailMetric = (m: ChallengeMetric): m is TrailMetric => m === "climb" || m === "parks";

/** The challenge kinds the server evaluates; any other `challenge` item is unknown here. */
export function challengeDef(item: PlayItem): ChallengeDef | null {
  if (item.type !== "challenge") return null;
  const spec = item.challenge as ChallengeSpec | undefined;
  if (spec?.scope === "solo" && (SOLO_METRICS as readonly string[]).includes(spec.metric ?? "")) {
    return { item, scope: "solo", metric: spec.metric as SoloMetric, spec };
  }
  if (spec?.scope === "island" && spec.metric === "stretches_repelled") return { item, scope: "island", metric: "stretches_repelled", spec };
  return null;
}

/** Enabled, non-killed challenges of `dataset` the server evaluates (trail metrics only where the tables exist). */
export function challengesOf(pack: PlayPack, dataset: Dataset): ChallengeDef[] {
  const out: ChallengeDef[] = [];
  for (const item of pack.items) {
    if (item.enabled === false || killed(pack, item) || !inGoal(item, dataset)) continue;
    const def = challengeDef(item);
    if (def && (!isTrailMetric(def.metric) || tablesOf(dataset) !== null)) out.push(def);
  }
  return out;
}

/**
 * The pack's target for `dataset` as the server counts it (climb: metres → dm), or 0 when the item has none. Solo
 * challenges only; island targets are the attack's stretch count.
 */
export function defaultTarget(def: ChallengeDef, dataset: Dataset): number {
  const t = def.spec.target?.[dataset];
  if (typeof t !== "number" || !(t > 0)) return 0;
  return def.metric === "climb" ? Math.round(t * 10) : t;
}

/**
 * D5 personal target of a solo challenge for one user: the default for `completed`; for `climb` / `parks`
 * `min(default, remaining(completed) + progress)` from `trails.ts`. 0 = nothing left (no card, no claim).
 */
export function personalTarget(def: ChallengeDef, dataset: Dataset, completed: Iterable<string>, progress: number): number {
  const dflt = defaultTarget(def, dataset);
  if (!isTrailMetric(def.metric)) return dflt;
  return challengeTarget(def.metric, dflt, completed, progress, tablesOf(dataset));
}

/** True while claims for an occurrence ending at `end` are still accepted (same rule as `too_late` in claims.ts). */
export function inGrace(end: number, now: number, pack: PlayPack): boolean {
  return end >= islandInstant(addDays(islandDate(now), -capsOf(pack).findGraceDays), 0);
}

/** Occurrences of `item` that started (≤ now) and still take contributions (window end + grace), oldest first. */
export function currentOccurrences(pack: PlayPack, item: PlayItem, now: number): Occurrence[] {
  const today = islandDate(now);
  const out: Occurrence[] = [];
  for (let k = CURRENT_LOOKBACK_DAYS; k >= 0; k--) {
    const o = occurrenceOn(pack, item, addDays(today, -k));
    if (o && o.end !== null && o.start <= now && inGrace(o.end, now, pack)) out.push(o);
  }
  return out;
}

/**
 * The one occurrence of `item` an upload received at `now` contributes to (P1-1): the occurrence active at `now`,
 * else the latest still in grace; null when none takes contributions.
 */
export function contributionOccurrence(pack: PlayPack, item: PlayItem, now: number): Occurrence | null {
  const current = currentOccurrences(pack, item, now);
  return current.find((o) => now < o.end!) ?? current[current.length - 1] ?? null;
}

/** The kraken attack starting on `date` in `dataset`: its item id and stretch occurrence keys (claim index order). */
export function attackOf(pack: PlayPack, dataset: Dataset, date: string): { itemId: string; stretchOccs: string[] } | null {
  for (const item of pack.items) {
    if (item.type !== "obstacle" || item.enabled === false || killed(pack, item) || !inGoal(item, dataset)) continue;
    const stretches = rotationStretches(item, dataset, date);
    if (!stretches?.length || !occurrenceOn(pack, item, date)) continue;
    const digits = String(stretches.length - 1).length;
    return { itemId: item.id, stretchOccs: stretches.map((_, i) => `${item.id}@${date}#${String(i).padStart(digits, "0")}`) };
  }
  return null;
}

export type Band = "top" | "middle" | "starting";
const BANDS: readonly Band[] = ["top", "middle", "starting"];

/** top = target reached; middle = at least half way; starting = any contribution. Null without a contribution. */
export function bandOf(progress: number, target: number): Band | null {
  if (progress <= 0 || target <= 0) return null;
  if (progress >= target) return "top";
  return progress * 2 >= target ? "middle" : "starting";
}

// ---------------------------------------------------------------------------------------------------------------
// Claims (called by claims.ts inside the coverage upload)

/**
 * Contributions of one Play upload to the solo challenges, occurrence key → amount (`completed`: trails / roads newly
 * completed, quiet trails never, PD-6; `climb`: their gain in dm; `parks`: their distinct Play parks), each to the one
 * occurrence per item that takes contributions at `now` (server windows, current pack; `contributionOccurrence`).
 */
export function completionProgress(
  dataset: Dataset,
  now: number,
  newlyCompleted: readonly string[],
  pack: PlayPack = CURRENT,
): Map<string, number> {
  const out = new Map<string, number>();
  if (newlyCompleted.length === 0) return out;
  const amounts = uploadAmounts(dataset, newlyCompleted);
  for (const def of challengesOf(pack, dataset)) {
    if (def.scope !== "solo") continue;
    const n = amounts[def.metric as SoloMetric];
    if (n <= 0) continue;
    const o = contributionOccurrence(pack, def.item, now);
    if (o) out.set(o.key, n);
  }
  return out;
}

/** What one upload's new completions are worth per solo metric (0 for a trail metric without tables: roads). */
function uploadAmounts(dataset: Dataset, newlyCompleted: readonly string[]): Record<SoloMetric, number> {
  const completed = newlyCompleted.filter((f) => !DERIVED[dataset].quietFeatures.has(f)).length;
  const tables = tablesOf(dataset);
  if (!tables) return { completed, climb: 0, parks: 0 };
  let climb = 0;
  const parks = new Set<string>();
  for (const t of newlyCompleted) {
    if (tables.quietFeatures.has(t) || !(t in tables.gainDm)) continue;
    climb += tables.gainDm[t]!;
    const park = tables.parkOf[t];
    if (park && tables.parks.has(park)) parks.add(park);
  }
  return { completed, climb, parks: parks.size };
}

/** The island challenge occurrence an accepted `ink` claim of the attack starting on `date` contributes to. */
export function islandOccFor(pack: PlayPack, dataset: Dataset, date: string): string | null {
  for (const def of challengesOf(pack, dataset)) {
    if (def.metric === "stretches_repelled" && occurrenceOn(pack, def.item, date)) return `${def.item.id}@${date}`;
  }
  return null;
}

/**
 * A `challenge` claim (checks 1–3 and 5 already passed): only solo challenges are claimable, and a trail metric only
 * on a goal with trails tables. Accepted until the week's end + `weekLateDays` (product § 3.5 late grace) when the
 * stored + this upload's contributions reach the user's target (`personalTarget`; `completed`: the pack's); short of
 * it, or with nothing left to do (target 0) → `no_bits` (the uploads do not support the claim). Returns the item's
 * marks, or a code.
 */
export function challengeClaimMarks(
  item: PlayItem,
  week: string,
  dataset: Dataset,
  now: number,
  pack: PlayPack,
  progress: number,
  completed: Iterable<string> = [],
): number | ClaimCode {
  const def = challengeDef(item);
  if (!def || def.scope !== "solo" || defaultTarget(def, dataset) <= 0 || (isTrailMetric(def.metric) && !tablesOf(dataset))) return "unknown_item";
  if (now >= islandInstant(addDays(week, 7), 0) + capsOf(pack).weekLateDays * DAY_MS) return "too_late";
  const target = personalTarget(def, dataset, completed, progress);
  return target > 0 && progress >= target ? itemMarks(item) : "no_bits";
}

// ---------------------------------------------------------------------------------------------------------------
// GET /v1/play/challenges

export interface ChallengeEntry {
  id: string;
  occ: string;
  scope: "solo" | "island";
  /** The schedule's window (island wall time with offset): the same for everyone, never a user's time. */
  windowStart: string;
  windowEnd: string;
  /** false after the window end, while late contributions are still accepted. */
  open: boolean;
  /** The caller's contributions (null when not signed in); `ch-climb`: decimetres. */
  progress: number | null;
  /** The pack's target (`ch-climb`: dm) for everyone; signed in, a trails challenge carries the caller's D5 target. */
  target: number;
  you: { band: Band } | null;
  /** Participants per band; a count below 5 is null, larger ones rounded to 5. */
  bands: { band: Band; count: number | null }[];
  /** Island challenges: stretches of the attack, and how many reached the player minimum. */
  island?: { stretches: number; repelled: number };
}

interface Pending {
  def: ChallengeDef;
  o: Occurrence;
  target: number;
  stretchOccs: string[] | null;
}

/**
 * Body of `GET /v1/play/challenges?dataset=`: every current challenge occurrence of the goal, sorted by `occ`. Bands
 * are counted against the pack's target (dm for `ch-climb`); signed in, a trails challenge's `target` and `you.band`
 * use the caller's personal D5 target, and an occurrence with nothing left for the caller (target 0) is omitted.
 */
export async function challengesBody(
  env: Env,
  dataset: Dataset,
  now: number,
  userId: string | null,
  pack: PlayPack = CURRENT,
): Promise<{ dataset: Dataset; minCount: number; current: ChallengeEntry[] }> {
  const pending: Pending[] = [];
  for (const def of challengesOf(pack, dataset)) {
    for (const o of currentOccurrences(pack, def.item, now)) {
      if (def.scope === "solo") {
        const target = defaultTarget(def, dataset);
        if (target > 0) pending.push({ def, o, target, stretchOccs: null });
      } else {
        const attack = attackOf(pack, dataset, o.date);
        if (attack) pending.push({ def, o, target: attack.stretchOccs.length, stretchOccs: attack.stretchOccs });
      }
    }
  }
  pending.sort((a, b) => (a.o.key < b.o.key ? -1 : a.o.key > b.o.key ? 1 : 0));
  if (pending.length === 0) return { dataset, minCount: MIN_COMMUNITY_COUNT, current: [] };

  const bandSql = env.DB.prepare(
    `SELECT COALESCE(SUM(p.progress >= ?3), 0) AS top,
            COALESCE(SUM(p.progress * 2 >= ?3 AND p.progress < ?3), 0) AS middle,
            COALESCE(SUM(p.progress * 2 < ?3), 0) AS starting
       FROM play_progress p JOIN users u ON u.id = p.user_id
      WHERE p.dataset = ?1 AND p.occ = ?2 AND p.progress > 0 AND u.hidden = 0`,
  );
  const mineSql = env.DB.prepare("SELECT COALESCE((SELECT progress FROM play_progress WHERE user_id = ?1 AND dataset = ?2 AND occ = ?3), 0) AS n");
  const repelledSql = env.DB.prepare(
    "SELECT COUNT(*) AS n FROM play_attack_stretches WHERE dataset = ?1 AND occ IN (SELECT value FROM json_each(?2)) AND repelled_by >= ?3",
  );
  const stmts: D1PreparedStatement[] = [];
  for (const p of pending) {
    stmts.push(bandSql.bind(dataset, p.o.key, p.target));
    if (userId !== null) stmts.push(mineSql.bind(userId, dataset, p.o.key));
    if (p.stretchOccs) {
      const min = p.def.spec.minPlayersPerStretch ?? DEFAULT_MIN_PLAYERS;
      stmts.push(repelledSql.bind(dataset, JSON.stringify(p.stretchOccs), min));
    }
  }
  const personal = userId !== null && pending.some((p) => isTrailMetric(p.def.metric));
  const [results, completed] = await Promise.all([
    env.DB.batch<Record<string, number>>(stmts),
    personal ? completedSet(env, userId!, dataset) : Promise.resolve([] as string[]),
  ]);
  let i = 0;
  const current: ChallengeEntry[] = [];
  for (const p of pending) {
    const counts = results[i++]!.results[0]!;
    const mine = userId !== null ? (results[i++]!.results[0]!.n ?? 0) : null;
    const repelled = p.stretchOccs ? (results[i++]!.results[0]!.n ?? 0) : null;
    const target = mine === null || !isTrailMetric(p.def.metric) ? p.target : personalTarget(p.def, dataset, completed, mine);
    if (target <= 0) continue; // nothing left for this user (D5): no card
    const band = mine === null ? null : bandOf(mine, target);
    const entry: ChallengeEntry = {
      id: p.def.item.id,
      occ: p.o.key,
      scope: p.def.scope,
      windowStart: islandIso(p.o.start),
      windowEnd: islandIso(p.o.end!),
      open: now < p.o.end!,
      progress: mine,
      target,
      you: band ? { band } : null,
      bands: BANDS.map((b) => ({ band: b, count: communityCount(counts[b] ?? 0) })),
    };
    if (repelled !== null) entry.island = { stretches: p.stretchOccs!.length, repelled };
    current.push(entry);
  }
  return { dataset, minCount: MIN_COMMUNITY_COUNT, current };
}

// ---------------------------------------------------------------------------------------------------------------
// GET /v1/play/me `log`: the server-only credits (review P0-2)

export type LogKind = "week" | "retreat" | "island_challenge";
export interface LogEntry {
  week: string;
  kind: LogKind;
  marks: number;
}

const LOG_ORDER: Record<LogKind, number> = { island_challenge: 0, retreat: 1, week: 2 };

/** True when `itemId` is an island challenge in any kept pack (the cron credits only those; solo ones are claimed). */
function isIslandChallenge(itemId: string): boolean {
  for (const pack of PUBLISHED.values()) {
    const item = pack.items.find((i) => i.id === itemId);
    if (item && challengeDef(item)?.scope === "island") return true;
  }
  return false;
}

/**
 * Marks only the daily cron credits, so the Logbook can show them (the client never claims them): W-WEEK
 * (`play_weeks.kept > 0`; a week closed as not kept is not a credit), F-RETREAT (claim rows `kraken-<goal>@<Thursday>`,
 * kind `ink` without `#n`: a client `ink` claim always names a stretch) and C-ISLAND (claim rows of an island
 * challenge, kind `challenge`). A cron credit over the weekly found cap is listed with 0 marks. Sorted by week, then
 * kind. Derived from the rows the cron writes once, so it is stable across calls and cron runs.
 */
export async function playLog(env: Env, userId: string, dataset: Dataset): Promise<LogEntry[]> {
  const [weeks, claims] = await env.DB.batch<{ week: string; kind: string; occ: string; marks: number }>([
    env.DB.prepare("SELECT week, 'week' AS kind, '' AS occ, kept AS marks FROM play_weeks WHERE user_id = ?1 AND dataset = ?2 AND kept > 0").bind(
      userId,
      dataset,
    ),
    env.DB.prepare(
      `SELECT week, kind, occ, marks FROM play_claims
        WHERE user_id = ?1 AND dataset = ?2 AND ((kind = 'ink' AND instr(occ, '#') = 0) OR kind = 'challenge')`,
    ).bind(userId, dataset),
  ]);
  const out: LogEntry[] = weeks!.results.map((r) => ({ week: r.week, kind: "week", marks: r.marks }));
  for (const c of claims!.results) {
    if (c.kind === "ink") out.push({ week: c.week, kind: "retreat", marks: c.marks });
    else if (isIslandChallenge(c.occ.slice(0, c.occ.indexOf("@")))) out.push({ week: c.week, kind: "island_challenge", marks: c.marks });
  }
  return out.sort((a, b) => (a.week < b.week ? -1 : a.week > b.week ? 1 : LOG_ORDER[a.kind] - LOG_ORDER[b.kind]));
}

// ---------------------------------------------------------------------------------------------------------------
// Daily cron

export interface PlayDailyResult {
  /** Weeks closed as kept (W-WEEK paid) / not kept. */
  weeksKept: number;
  weeksNotKept: number;
  /** Attacks repelled by the island this run (F-RETREAT + C-ISLAND credited to `credited` contributors). */
  retreats: number;
  credited: number;
  purged: number;
}

/** The Play part of the daily cron. Idempotent: running it twice, or concurrently, credits once. */
export async function playDaily(env: Env, now: number, pack: PlayPack = CURRENT): Promise<PlayDailyResult> {
  const r: PlayDailyResult = { weeksKept: 0, weeksNotKept: 0, retreats: 0, credited: 0, purged: 0 };
  await closeWeeks(env, now, pack, r);
  for (const dataset of DATASETS) await closeAttacks(env, now, dataset, pack, r);
  await purge(env, now, r);
  return r;
}

interface WeekRow {
  user_id: string;
  dataset: string;
  week: string;
  day_keys: number;
  prev_kept: number | null;
  prev_exists: number;
}

/**
 * W-WEEK (product § 3.2, § 3.5): weeks are closed in week order so a week's run continues from the one before it.
 * A week with ≥ 2 day keys is paid as soon as it ended (late day keys only matter below 2); one below 2 is closed as
 * not kept once day keys can no longer arrive (week end + `weekLateDays`, claims.ts). A week whose previous week is
 * still open waits for the next run.
 */
async function closeWeeks(env: Env, now: number, pack: PlayPack, r: PlayDailyResult): Promise<void> {
  const { weekLateDays } = capsOf(pack);
  const current = weekIdAt(now);
  const pastGrace = addDays(current, -7 - Math.ceil(weekLateDays / 7) * 7);
  const decided = new Map<string, number>();
  const key = (u: string, d: string, w: string) => `${u}|${d}|${w}`;
  const select = env.DB.prepare(
    `SELECT w.user_id, w.dataset, w.week, w.day_keys, p.kept AS prev_kept, p.week IS NOT NULL AS prev_exists
       FROM play_weeks w
       LEFT JOIN play_weeks p ON p.user_id = w.user_id AND p.dataset = w.dataset AND p.week = date(w.week, '-7 days')
      WHERE w.kept IS NULL AND w.week < ?1 AND (w.day_keys >= 2 OR w.week <= ?2)
        AND (w.week, w.user_id, w.dataset) > (?3, ?4, ?5)
      ORDER BY w.week, w.user_id, w.dataset LIMIT ${CRON_ROWS}`,
  );
  const credit = env.DB.prepare(
    `INSERT INTO play_totals (user_id, dataset, week_marks, total)
     SELECT ?1, ?2, ?4, ?4 WHERE EXISTS (SELECT 1 FROM play_weeks WHERE user_id = ?1 AND dataset = ?2 AND week = ?3 AND kept IS NULL)
     ON CONFLICT (user_id, dataset) DO UPDATE SET week_marks = week_marks + excluded.week_marks, total = total + excluded.total`,
  );
  const close = env.DB.prepare("UPDATE play_weeks SET kept = ?4 WHERE user_id = ?1 AND dataset = ?2 AND week = ?3 AND kept IS NULL");
  let after: [string, string, string] = ["", "", ""];
  for (let round = 0; round < CRON_ROUNDS; round++) {
    const { results } = await select.bind(current, pastGrace, ...after).run<WeekRow>();
    if (results.length === 0) break;
    const stmts: D1PreparedStatement[] = [];
    for (const w of results) {
      const end = islandInstant(addDays(w.week, 7), 0);
      if (now < end) continue;
      const prevKey = key(w.user_id, w.dataset, addDays(w.week, -7));
      const prev = decided.has(prevKey) ? decided.get(prevKey)! : w.prev_exists ? w.prev_kept : 0;
      if (w.day_keys >= 2) {
        if (prev === null) continue; // previous week not closed yet
        const marks = prev > 0 ? Math.min(WEEK_MAX_MARKS, prev + WEEK_STEP_MARKS) : WEEK_BASE_MARKS;
        stmts.push(credit.bind(w.user_id, w.dataset, w.week, marks), close.bind(w.user_id, w.dataset, w.week, marks));
        decided.set(key(w.user_id, w.dataset, w.week), marks);
        r.weeksKept++;
      } else {
        // day keys are accepted until week end + weekLateDays (fixed ms in claims.ts) and the island week after it
        const lateEnd = Math.max(end + weekLateDays * DAY_MS, islandInstant(addDays(w.week, 7 + weekLateDays), 0));
        if (now < lateEnd) continue;
        stmts.push(close.bind(w.user_id, w.dataset, w.week, 0));
        decided.set(key(w.user_id, w.dataset, w.week), 0);
        r.weeksNotKept++;
      }
    }
    if (stmts.length > 0) await env.DB.batch(stmts);
    const last = results[results.length - 1]!;
    after = [last.week, last.user_id, last.dataset];
    if (results.length < CRON_ROWS) break;
  }
}

/**
 * F-RETREAT and C-ISLAND for attacks whose claims can no longer arrive. Every credit is three statements in one batch,
 * the first two guarded by the absence of the claim row the third inserts, so a credit is written exactly once.
 */
async function closeAttacks(env: Env, now: number, dataset: Dataset, pack: PlayPack, r: PlayDailyResult): Promise<void> {
  const { foundPerWeek } = capsOf(pack);
  const today = islandDate(now);
  const totals = env.DB.prepare(
    `INSERT INTO play_totals (user_id, dataset, found_marks, total)
     SELECT ?1, ?2, ?4, ?4 WHERE NOT EXISTS (SELECT 1 FROM play_claims WHERE user_id = ?1 AND dataset = ?2 AND occ = ?3)
     ON CONFLICT (user_id, dataset) DO UPDATE SET found_marks = found_marks + excluded.found_marks, total = total + excluded.total`,
  );
  const week = env.DB.prepare(
    `INSERT INTO play_weeks (user_id, dataset, week, found_marks)
     SELECT ?1, ?2, ?5, ?4 WHERE NOT EXISTS (SELECT 1 FROM play_claims WHERE user_id = ?1 AND dataset = ?2 AND occ = ?3)
     ON CONFLICT (user_id, dataset, week) DO UPDATE SET found_marks = found_marks + excluded.found_marks`,
  );
  const claim = env.DB.prepare(
    "INSERT INTO play_claims (user_id, dataset, occ, kind, week, config, marks) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) ON CONFLICT DO NOTHING",
  );
  for (const def of challengesOf(pack, dataset)) {
    if (def.metric !== "stretches_repelled") continue;
    const min = def.spec.minPlayersPerStretch ?? DEFAULT_MIN_PLAYERS;
    const retreatMarks = def.spec.retreatMarks ?? DEFAULT_RETREAT_MARKS;
    const islandMarks = itemMarks(def.item);
    for (let k = ATTACK_LOOKBACK_DAYS; k >= 0; k--) {
      const date = addDays(today, -k);
      const o = occurrenceOn(pack, def.item, date);
      if (!o || o.end === null || inGrace(o.end, now, pack)) continue;
      const attack = attackOf(pack, dataset, date);
      if (!attack) continue;
      const counts = await env.DB.prepare(
        "SELECT COUNT(*) AS n FROM play_attack_stretches WHERE dataset = ?1 AND occ IN (SELECT value FROM json_each(?2)) AND repelled_by >= ?3",
      )
        .bind(dataset, JSON.stringify(attack.stretchOccs), min)
        .first<{ n: number }>();
      if ((counts?.n ?? 0) < attack.stretchOccs.length) continue; // the kraken slipped away
      const islandOcc = o.key;
      const retreatOcc = `${attack.itemId}@${date}`;
      const attackWeek = weekId(date);
      let any = false;
      for (let round = 0; round < CRON_ROUNDS; round++) {
        // contributors not yet credited, with their found marks of the attack's week (weekly found cap)
        const { results } = await env.DB.prepare(
          `SELECT p.user_id, COALESCE(w.found_marks, 0) AS found
             FROM play_progress p
             LEFT JOIN play_weeks w ON w.user_id = p.user_id AND w.dataset = p.dataset AND w.week = ?3
            WHERE p.dataset = ?1 AND p.occ = ?2 AND p.progress >= 1
              AND NOT EXISTS (SELECT 1 FROM play_claims c WHERE c.user_id = p.user_id AND c.dataset = ?1 AND c.occ = ?2)
            ORDER BY p.user_id LIMIT ${CRON_ROWS}`,
        )
          .bind(dataset, islandOcc, attackWeek)
          .run<{ user_id: string; found: number }>();
        if (results.length === 0) break;
        any = true;
        const stmts: D1PreparedStatement[] = [];
        for (const u of results) {
          let found = u.found;
          for (const [occ, kind, full] of [
            [retreatOcc, "ink", retreatMarks],
            [islandOcc, "challenge", islandMarks],
          ] as const) {
            const marks = full > 0 && found + full <= foundPerWeek ? full : 0;
            found += marks;
            if (marks > 0) {
              stmts.push(totals.bind(u.user_id, dataset, occ, marks), week.bind(u.user_id, dataset, occ, marks, attackWeek));
            }
            stmts.push(claim.bind(u.user_id, dataset, occ, kind, attackWeek, pack.version, marks));
          }
          r.credited++;
        }
        await env.DB.batch(stmts);
        if (results.length < CRON_ROWS) break;
      }
      if (any) r.retreats++;
    }
  }
}

/** Stretch aggregates and challenge contributions of occurrences older than 180 days (schedule.md § 7.4). */
async function purge(env: Env, now: number, r: PlayDailyResult): Promise<void> {
  const cutoff = addDays(islandDate(now), -PURGE_DAYS);
  const res = await env.DB.batch([
    env.DB.prepare("DELETE FROM play_attack_stretches WHERE substr(occ, instr(occ, '@') + 1, 10) < ?1").bind(cutoff),
    env.DB.prepare("DELETE FROM play_progress WHERE substr(occ, instr(occ, '@') + 1, 10) < ?1").bind(cutoff),
  ]);
  r.purged = res.reduce((s, x) => s + (x.meta.changes ?? 0), 0);
}
