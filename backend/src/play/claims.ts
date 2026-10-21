import type { CoverageMap } from "../coverage";
import { ApiError, isObject } from "../http";
import type { Dataset } from "../segments";
import { DERIVED, featureSegments } from "./derived";
import { DAY_MARKS, type WalkDerived, coveredShare, inkMarks, itemMarks, riddleMarks, treasureMarks } from "./marks";
import {
  type Occurrence,
  type PlayItem,
  type PlayPack,
  dateOfEpochDay,
  epochDay,
  islandDate,
  islandInstant,
  occurrenceOn,
  parseOccurrenceKey,
  weekId,
  weekIdAt,
} from "./recurrence";

/**
 * Play claims on `PUT /v1/coverage` (schedule.md § 7.2, product § 3.5, § 3.6.3, § 3.10; PD-2, PD-3).
 *
 * Request (both fields optional; absent = a pure upload, byte-identical response):
 *   "playConfig": "2026.11.0",
 *   "play": [{ "walkId": "<uuid in walks[]>", "claims": [{ "id": "<occurrence key>", "kind": "treasure", "weekId":
 *             "2026-10-26", "band"?: 60|80|95 }], "dayKey"?: "<week id>", "hints"?: { "<riddle item id>": 1 } }]
 * Any other field (a client `marks`, `total`, `tier` …) is ignored: every mark is derived here.
 *
 * Checks per claim, after the coverage plausibility passed and inside the same `DB.batch` that credits the walks
 * (the coverage_rev compare-and-swap makes the read-evaluate-write atomic):
 *   0. repeat: an occurrence this user already claimed (or claimed earlier in this upload) is a no-op `accepted`;
 *   1. `stale_config`: `playConfig` is not one of the Worker's kept packs;
 *   2. `unknown_item`: no enabled, non-killed item of this goal with that id and a type matching `kind`;
 *      `bad_occurrence`: malformed key, no occurrence starting on that date, placement index out of range or not
 *      canonical, `weekId` not a Monday or the occurrence not active in it, a band the item does not pay;
 *   3. `not_this_walk`: `walkId` is not credited by this upload;
 *   5. `too_early` / `too_late`: occurrence start > now + 1 h, `weekId` after the current week / before the previous
 *      one, or the occurrence ended before the server island date − 2 days;
 *   4. `no_bits`: a kraken stretch with less than `band` % of its length covered, or a feature riddle whose answer
 *      has less than `minM` covered (after the merge);
 *   7. marks from the pack (tier / band / stars and hints / item marks); over the weekly found cap the claim is
 *      accepted with 0 marks and listed in `capped`.
 * Challenge claims are checked like any other, then left to L3-3 (`challenges.ts`), which owns their acceptance.
 */

export const MAX_CLAIMS_PER_UPLOAD = 50;
/** One element per walk: never more elements than walks an upload may carry (backfill). */
const MAX_ELEMENTS = 2000;
const MAX_HINT_ITEMS = 50;
const WALK_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export const CLAIM_KINDS = ["treasure", "egg", "riddle", "ink", "lore", "challenge"] as const;
export type ClaimKind = (typeof CLAIM_KINDS)[number];
/** Claim kind → pack item type. */
const KIND_TYPE: Record<ClaimKind, string> = {
  treasure: "treasure",
  egg: "egg",
  riddle: "riddle",
  ink: "obstacle",
  lore: "lore",
  challenge: "challenge",
};

export type ClaimCode = "stale_config" | "unknown_item" | "bad_occurrence" | "not_this_walk" | "no_bits" | "too_late" | "too_early";

/** product § 3.5 (the one caps table), mirrored in every pack's `caps`. */
const DEFAULT_CAPS = { foundPerWeek: 2800, weekLateDays: 7, findGraceDays: 2, clockAheadMin: 60 };
const MAX_DAY_KEYS_PER_WEEK = 7;
const DAY_MS = 86_400_000;

export interface PlayClaim {
  id: string;
  kind: string;
  weekId: string;
  band?: unknown;
}

export interface PlayElement {
  walkId: string;
  claims: PlayClaim[];
  dayKey: string | null;
  hints: Record<string, number>;
}

export interface PlayUpload {
  config: string | null;
  elements: PlayElement[];
}

const invalid = (message: string) => new ApiError(400, "invalid_play", message);
const isShortString = (v: unknown, max: number): v is string => typeof v === "string" && v.length <= max;

/**
 * The upload's `playConfig` + `play`, or null when `play` is absent (Play off: a pure upload). Structural errors are
 * `400 invalid_play`; more than 50 claims is `400 too_many_claims` (`maxClaims`). Parsed only after the coverage
 * fields validated, so a pure upload's errors are unchanged.
 */
export function parsePlay(body: Record<string, unknown>): PlayUpload | null {
  if (body.play === undefined || body.play === null) return null;
  const cfg = body.playConfig;
  if (cfg !== undefined && cfg !== null && !isShortString(cfg, 64)) throw invalid("playConfig must be a string");
  if (!Array.isArray(body.play)) throw invalid("play must be an array");
  if (body.play.length > MAX_ELEMENTS) throw invalid(`at most ${MAX_ELEMENTS} play elements`);
  let claimCount = 0;
  for (const e of body.play) if (isObject(e) && Array.isArray(e.claims)) claimCount += e.claims.length;
  if (claimCount > MAX_CLAIMS_PER_UPLOAD) {
    throw new ApiError(400, "too_many_claims", `at most ${MAX_CLAIMS_PER_UPLOAD} claims per upload`, { maxClaims: MAX_CLAIMS_PER_UPLOAD });
  }
  const seen = new Set<string>();
  const elements: PlayElement[] = [];
  for (const e of body.play) {
    if (!isObject(e)) throw invalid("play element must be an object");
    if (typeof e.walkId !== "string" || !WALK_ID.test(e.walkId.toLowerCase())) throw invalid("play walkId must be a UUID");
    const walkId = e.walkId.toLowerCase();
    if (seen.has(walkId)) throw invalid("at most one play element per walk");
    seen.add(walkId);
    const rawClaims = e.claims ?? [];
    if (!Array.isArray(rawClaims)) throw invalid("claims must be an array");
    const claims: PlayClaim[] = [];
    for (const c of rawClaims) {
      if (!isObject(c) || !isShortString(c.id, 128) || !isShortString(c.kind, 32) || !isShortString(c.weekId, 32)) {
        throw invalid("claim must be { id, kind, weekId } strings");
      }
      claims.push({ id: c.id, kind: c.kind, weekId: c.weekId, band: c.band });
    }
    if (e.dayKey !== undefined && e.dayKey !== null && !isShortString(e.dayKey, 32)) throw invalid("dayKey must be a week id");
    const hints: Record<string, number> = {};
    if (e.hints !== undefined && e.hints !== null) {
      if (!isObject(e.hints) || Object.keys(e.hints).length > MAX_HINT_ITEMS) throw invalid("hints must be an object of item id → count");
      for (const [k, v] of Object.entries(e.hints)) {
        if (k.length > 64 || typeof v !== "number" || !Number.isInteger(v) || v < 0 || v > 10) throw invalid("hints must be small integers");
        hints[k] = v;
      }
    }
    elements.push({ walkId, claims, dayKey: typeof e.dayKey === "string" ? e.dayKey : null, hints });
  }
  return { config: typeof cfg === "string" ? cfg : null, elements };
}

/** Occurrence keys and week ids an upload reads (for the pre-read of existing claims and week rows). */
export function playKeys(play: PlayUpload): { occs: string[]; weeks: string[] } {
  const occs = new Set<string>();
  const weeks = new Set<string>();
  for (const e of play.elements) {
    for (const c of e.claims) {
      occs.add(c.id);
      weeks.add(c.weekId);
    }
    if (e.dayKey) weeks.add(e.dayKey);
  }
  return { occs: [...occs], weeks: [...weeks] };
}

export interface WeekState {
  dayKeys: number;
  found: number;
}

export interface ClaimContext {
  dataset: Dataset;
  /** Server now (epoch ms). */
  now: number;
  /** Kept packs by version (`config.packByVersion`). */
  packFor(version: string): PlayPack | null;
  /** Walk ids this upload credits (new for this user and dataset). */
  freshWalkIds: ReadonlySet<string>;
  /** The user's coverage after the merge. */
  coverage: CoverageMap;
  /** Occurrence keys this user already claimed in this dataset. */
  existing: ReadonlySet<string>;
  /** Stored week rows (only the weeks the upload names). */
  weeks: ReadonlyMap<string, WeekState>;
}

export interface ClaimInsert {
  occ: string;
  kind: ClaimKind;
  week: string;
  marks: number;
}

export interface Evaluation {
  config: string | null;
  accepted: string[];
  rejected: { id: string; code: ClaimCode }[];
  /** Accepted with 0 marks: over the weekly found cap. */
  capped: string[];
  inserts: ClaimInsert[];
  /** Per week: day keys and found marks to add. */
  weekDelta: Map<string, WeekState>;
  foundMarks: number;
  dayMarks: number;
}

const isMonday = (d: string): boolean => !Number.isNaN(epochDay(d)) && weekId(d) === d;
const addDays = (d: string, n: number): string => dateOfEpochDay(epochDay(d) + n);

function killed(pack: PlayPack, item: PlayItem): boolean {
  const k = pack.kill as { all?: boolean; types?: string[]; items?: string[] } | undefined;
  return !!k && (k.all === true || (k.types ?? []).includes(item.type) || (k.items ?? []).includes(item.id));
}

function inGoal(item: PlayItem, dataset: Dataset): boolean {
  if (Array.isArray(item.goals)) return item.goals.includes(dataset);
  const where = item.where as Record<string, unknown> | undefined;
  return where === undefined || Object.keys(where).length === 0 || dataset in where;
}

/** The kraken coast's stretch ids for an attack starting on `date`, or null when the item has no rotation. */
function rotationStretches(item: PlayItem, dataset: Dataset, date: string): string[] | null {
  const where = (item.where as Record<string, { rotate?: string[]; epoch?: string }> | undefined)?.[dataset];
  if (!where?.rotate?.length || !where.epoch) return null;
  const weeks = (epochDay(date) - epochDay(where.epoch)) / 7;
  if (!Number.isInteger(weeks) || weeks < 0) return null;
  const pool = where.rotate[weeks % where.rotate.length]!;
  return DERIVED[dataset].coasts[pool.replace(/^pool:/, "")] ?? null;
}

/** Placements of a multi-placement occurrence (`#n`), or 0 for a single one. */
function placements(item: PlayItem, dataset: Dataset, date: string): number {
  if (item.type === "treasure") return typeof item.count === "number" ? item.count : 0;
  if (item.type === "obstacle") return rotationStretches(item, dataset, date)?.length ?? 0;
  return 0;
}

interface Checked {
  item: PlayItem;
  occ: Occurrence;
  n: number | null;
  pack: PlayPack;
}

/** Checks 1–2 (pack, item, occurrence, week). */
function checkOccurrence(c: PlayClaim, config: string | null, ctx: ClaimContext): Checked | ClaimCode {
  const pack = config === null ? null : ctx.packFor(config);
  if (!pack) return "stale_config";
  const key = parseOccurrenceKey(c.id);
  if (!key) return "bad_occurrence";
  const kind = c.kind as ClaimKind;
  const item = pack.items.find((i) => i.id === key.itemId);
  if (!item || item.enabled === false || killed(pack, item) || !inGoal(item, ctx.dataset)) return "unknown_item";
  if (!(CLAIM_KINDS as readonly string[]).includes(kind) || KIND_TYPE[kind] !== item.type) return "unknown_item";
  const occ = occurrenceOn(pack, item, key.date);
  if (!occ) return "bad_occurrence";
  const count = placements(item, ctx.dataset, key.date);
  if (count > 0) {
    if (key.n === null || key.n >= count) return "bad_occurrence";
    if (c.id !== `${item.id}@${key.date}#${String(key.n).padStart(String(count - 1).length, "0")}`) return "bad_occurrence";
  } else if (key.n !== null || item.type === "obstacle") {
    return "bad_occurrence";
  }
  if (!isMonday(c.weekId)) return "bad_occurrence";
  const weekStart = islandInstant(c.weekId, 0);
  const weekEnd = islandInstant(addDays(c.weekId, 7), 0);
  if (!(occ.start < weekEnd && (occ.end === null || occ.end > weekStart))) return "bad_occurrence";
  return { item, occ, n: key.n, pack };
}

/** Check 5 (product § 3.5 clock tolerance and replay guard). */
function checkTime(c: PlayClaim, ch: Checked, ctx: ClaimContext): ClaimCode | null {
  const caps = { ...DEFAULT_CAPS, ...((ch.pack.caps as Partial<typeof DEFAULT_CAPS> | undefined) ?? {}) };
  const current = weekIdAt(ctx.now);
  const previous = addDays(current, -7);
  if (c.weekId > current || ch.occ.start > ctx.now + caps.clockAheadMin * 60_000) return "too_early";
  if (c.weekId < previous) return "too_late";
  if (ch.item.type !== "challenge" && ch.occ.end !== null) {
    const graceStart = islandInstant(addDays(islandDate(ctx.now), -caps.findGraceDays), 0);
    if (ch.occ.end < graceStart) return "too_late";
  }
  return null;
}

/** Check 4 (coverage bits of line and stretch items) and the band; returns the marks, or a code. */
function marksOf(c: PlayClaim, ch: Checked, el: PlayElement, ctx: ClaimContext): number | ClaimCode {
  const { item, pack } = ch;
  switch (item.type) {
    case "treasure":
      return treasureMarks(item, ch.n!, pack, ch.occ.date) ?? "bad_occurrence";
    case "obstacle": {
      const marks = inkMarks(item, c.band);
      if (marks === null) return "bad_occurrence";
      const stretchId = rotationStretches(item, ctx.dataset, ch.occ.date)![ch.n!]!;
      const segs = DERIVED[ctx.dataset].stretches[stretchId]?.segments.map((s) => s[0]) ?? [];
      const { share } = coveredShare(ctx.dataset, ctx.coverage, segs);
      return segs.length > 0 && share + 1e-9 >= (c.band as number) / 100 ? marks : "no_bits";
    }
    case "riddle": {
      const resolve = item.resolve as { answer?: string; minM?: number } | undefined;
      const answer = resolve?.answer;
      if (typeof answer === "string" && !answer.startsWith("place:")) {
        const segs = featureSegments(ctx.dataset, answer, pack.pools as Record<string, { members?: string[] }> | undefined);
        // null: not resolvable on the server (park membership); a point answer is never checked (no positions)
        if (segs !== null) {
          const { coveredM, totalM } = coveredShare(ctx.dataset, ctx.coverage, segs);
          if (totalM <= 0 || coveredM + 1e-6 < Math.min(resolve?.minM ?? 100, totalM)) return "no_bits";
        }
      }
      return riddleMarks(item, el.hints[item.id] ?? 0);
    }
    case "challenge":
      // L3-3 (challenges.ts) owns ch-sweep / ch-krakenweek acceptance; until it lands, no challenge claim is paid.
      return "unknown_item";
    default:
      return itemMarks(item);
  }
}

/**
 * Evaluates every claim and day key of an upload. Pure: the caller supplies the merged coverage, the stored claims
 * and week rows, and turns the result into statements of the coverage batch (`playStatements`).
 */
export function evaluatePlay(play: PlayUpload, ctx: ClaimContext): Evaluation {
  const ev: Evaluation = {
    config: play.config,
    accepted: [],
    rejected: [],
    capped: [],
    inserts: [],
    weekDelta: new Map(),
    foundMarks: 0,
    dayMarks: 0,
  };
  const week = (w: string): WeekState => {
    let d = ev.weekDelta.get(w);
    if (!d) ev.weekDelta.set(w, (d = { dayKeys: 0, found: 0 }));
    return d;
  };
  const claimed = new Set(ctx.existing);
  const pack = play.config === null ? null : ctx.packFor(play.config);
  const capPerWeek = (pack?.caps as { foundPerWeek?: number } | undefined)?.foundPerWeek ?? DEFAULT_CAPS.foundPerWeek;

  for (const el of play.elements) {
    for (const c of el.claims) {
      if (claimed.has(c.id)) {
        ev.accepted.push(c.id); // repeat: a no-op that still reports accepted
        continue;
      }
      const ch = checkOccurrence(c, play.config, ctx);
      if (typeof ch === "string") {
        ev.rejected.push({ id: c.id, code: ch });
        continue;
      }
      if (!ctx.freshWalkIds.has(el.walkId)) {
        ev.rejected.push({ id: c.id, code: "not_this_walk" });
        continue;
      }
      const late = checkTime(c, ch, ctx);
      if (late) {
        ev.rejected.push({ id: c.id, code: late });
        continue;
      }
      let marks = marksOf(c, ch, el, ctx);
      if (typeof marks === "string") {
        ev.rejected.push({ id: c.id, code: marks });
        continue;
      }
      const found = (ctx.weeks.get(c.weekId)?.found ?? 0) + (ev.weekDelta.get(c.weekId)?.found ?? 0);
      if (marks > 0 && found + marks > capPerWeek) {
        marks = 0;
        ev.capped.push(c.id);
      }
      claimed.add(c.id);
      ev.accepted.push(c.id);
      ev.inserts.push({ occ: c.id, kind: c.kind as ClaimKind, week: c.weekId, marks });
      if (marks > 0) {
        week(c.weekId).found += marks;
        ev.foundMarks += marks;
      }
    }
  }

  // W-DAY: one per dayKey (a week id), only for walks this upload credits, ≤ 7 per week, accepted until week end + 7 d
  const current = weekIdAt(ctx.now);
  for (const el of play.elements) {
    const k = el.dayKey;
    if (k === null || !ctx.freshWalkIds.has(el.walkId) || !isMonday(k) || k > current) continue;
    if (ctx.now >= islandInstant(addDays(k, 7), 0) + DEFAULT_CAPS.weekLateDays * DAY_MS) continue;
    const have = (ctx.weeks.get(k)?.dayKeys ?? 0) + (ev.weekDelta.get(k)?.dayKeys ?? 0);
    if (have >= MAX_DAY_KEYS_PER_WEEK) continue;
    week(k).dayKeys += 1;
    ev.dayMarks += DAY_MARKS;
  }
  return ev;
}

/** Stored claims and week rows the evaluation needs (two indexed reads). */
export async function loadPlayState(
  env: Env,
  userId: string,
  dataset: Dataset,
  play: PlayUpload,
): Promise<{ existing: Set<string>; weeks: Map<string, WeekState> }> {
  const { occs, weeks } = playKeys(play);
  const [claims, weekRows] = await Promise.all([
    occs.length === 0
      ? { results: [] as { occ: string }[] }
      : env.DB.prepare("SELECT occ FROM play_claims WHERE user_id = ?1 AND dataset = ?2 AND occ IN (SELECT value FROM json_each(?3))")
          .bind(userId, dataset, JSON.stringify(occs))
          .run<{ occ: string }>(),
    weeks.length === 0
      ? { results: [] as { week: string; day_keys: number; found_marks: number }[] }
      : env.DB.prepare(
          "SELECT week, day_keys, found_marks FROM play_weeks WHERE user_id = ?1 AND dataset = ?2 AND week IN (SELECT value FROM json_each(?3))",
        )
          .bind(userId, dataset, JSON.stringify(weeks))
          .run<{ week: string; day_keys: number; found_marks: number }>(),
  ]);
  return {
    existing: new Set(claims.results.map((r) => r.occ)),
    weeks: new Map(weekRows.results.map((r) => [r.week, { dayKeys: r.day_keys, found: r.found_marks }])),
  };
}

/**
 * Statements appended to the coverage batch: new claims (`INSERT … ON CONFLICT DO NOTHING`), week rows, and the
 * totals row (W-COV and W-DONE + W-SQUARE as `max(previous, new)`, found and week marks added).
 */
export function playStatements(env: Env, userId: string, dataset: Dataset, ev: Evaluation, walk: WalkDerived): D1PreparedStatement[] {
  const stmts: D1PreparedStatement[] = [];
  const insClaim = env.DB.prepare(
    "INSERT INTO play_claims (user_id, dataset, occ, kind, week, config, marks) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
  );
  for (const c of ev.inserts) stmts.push(insClaim.bind(userId, dataset, c.occ, c.kind, c.week, ev.config ?? "", c.marks));
  const upWeek = env.DB.prepare(
    `INSERT INTO play_weeks (user_id, dataset, week, day_keys, found_marks) VALUES (?1, ?2, ?3, ?4, ?5)
     ON CONFLICT (user_id, dataset, week) DO UPDATE SET
       day_keys = MIN(${MAX_DAY_KEYS_PER_WEEK}, day_keys + excluded.day_keys), found_marks = found_marks + excluded.found_marks`,
  );
  for (const [w, d] of ev.weekDelta) if (d.dayKeys > 0 || d.found > 0) stmts.push(upWeek.bind(userId, dataset, w, d.dayKeys, d.found));
  stmts.push(
    env.DB.prepare(
      `INSERT INTO play_totals (user_id, dataset, cov_marks, derived_marks, found_marks, week_marks, total)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?3 + ?4 + ?5 + ?6)
       ON CONFLICT (user_id, dataset) DO UPDATE SET
         cov_marks = MAX(cov_marks, excluded.cov_marks),
         derived_marks = MAX(derived_marks, excluded.derived_marks),
         found_marks = found_marks + excluded.found_marks,
         week_marks = week_marks + excluded.week_marks,
         total = MAX(cov_marks, excluded.cov_marks) + MAX(derived_marks, excluded.derived_marks)
               + found_marks + excluded.found_marks + week_marks + excluded.week_marks`,
    ).bind(userId, dataset, walk.cov, walk.derived, ev.foundMarks, ev.dayMarks),
  );
  return stmts;
}

export interface PlaySummary {
  weekId: string;
  /** Server total: W-COV + W-DONE + W-SQUARE + finds + W-DAY (+ W-WEEK, challenges from L3-3). */
  total: number;
  /** This week's Play marks: finds + W-DAY of the current week id. */
  week: number;
}

export async function playSummary(env: Env, userId: string, dataset: Dataset, now: number): Promise<PlaySummary> {
  const weekId = weekIdAt(now);
  const row = await env.DB.prepare(
    `SELECT (SELECT total FROM play_totals WHERE user_id = ?1 AND dataset = ?2) AS total,
            (SELECT found_marks + ?4 * day_keys FROM play_weeks WHERE user_id = ?1 AND dataset = ?2 AND week = ?3) AS week`,
  )
    .bind(userId, dataset, weekId, DAY_MARKS)
    .first<{ total: number | null; week: number | null }>();
  return { weekId, total: row?.total ?? 0, week: row?.week ?? 0 };
}

/** The `play` object of a coverage response. */
export async function playResponse(env: Env, userId: string, dataset: Dataset, now: number, ev: Evaluation) {
  const s = await playSummary(env, userId, dataset, now);
  return { accepted: ev.accepted, rejected: ev.rejected, capped: ev.capped, total: s.total, week: s.week };
}
