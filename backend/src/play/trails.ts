import { type CoverageMap, coveredClamped } from "../coverage";
import { type Dataset, METAS } from "../segments";
import { DERIVED } from "./derived";

/**
 * The six trails rules of Play v1.1 (brief § 2; plan D1, D6): W-CLIMB, W-STEEP ("high ground"), W-MILE, W-PARK1,
 * W-PARKC and W-ISLAND. Pure functions mirrored by the Kotlin `play/rules/TrailRewards.kt`; both sides are pinned to
 * `data/play/golden/trails-rewards.json` (G0). Every key is a value the server can re-derive from coverage bits and
 * the sticky completed set: `climb:<m>`, `high:<trailId>`, `mile:<mi>`, `park1:<park>`, `parkc:<park>`, `island`.
 * Paid keys live in `play_trail_paid` (0007) and are never re-derived: a dataset that drops a trail lowers nothing,
 * one that adds a trail pays only keys not yet paid (AC 2 monotone). Trails only: roads has no tables (AC 1).
 *
 * Numbers (D6): climb in integer decimetres (`round(gain_m × 10)`; 99.9 m = 999 < 1000); miles =
 * `nonQuietCoveredM / 1609.344` on the Milestones ladder (1, then every 5 up to 60; EPS 1e-9); W-ISLAND = island
 * metres ≥ island total × (1 − 1e-9). Quiet segments are the derived JSON `quietSegments` (segments of quiet trails and
 * segments touching a quiet zone), not the v1 quiet-feature set `walkDerivedMarks` uses (P1-2 ruling).
 */

export const CLIMB_STEPS_M = [100, 250, 500, 750, 1000, 1250] as const;
export const MILE_STEPS = [1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60] as const;
export const METRES_PER_MILE = 1609.344;
/** W-STEEP threshold: gain ≥ 40.0 m, in decimetres. */
export const STEEP_DM = 400;
const EPS = 1e-9;

export const TRAIL_KINDS = ["climb", "high_ground", "milestone", "park_first", "park_complete", "island"] as const;
export type TrailKind = (typeof TRAIL_KINDS)[number];
export const TRAIL_MARKS: Record<TrailKind, number> = {
  climb: 50,
  high_ground: 50,
  milestone: 50,
  park_first: 50,
  park_complete: 150,
  island: 1000,
};
const KIND_BY_PREFIX: Record<string, TrailKind> = {
  climb: "climb",
  high: "high_ground",
  mile: "milestone",
  park1: "park_first",
  parkc: "park_complete",
  island: "island",
};

/** The trails tables the rules read (derived JSON `featureGainM` → dm, `featurePark`, `parks`, quiet sets). */
export interface TrailTables {
  /** Trail id → gain in integer decimetres. Every named trail of the dataset. */
  gainDm: Readonly<Record<string, number>>;
  /** Trail id → park name (only trails inside a park; Pritchard Park included, filtered by `parks`). */
  parkOf: Readonly<Record<string, string>>;
  /** Play parks (Pritchard Park excluded by the pack build). */
  parks: ReadonlySet<string>;
  quietFeatures: ReadonlySet<string>;
  /** Derived JSON `quietSegments`. */
  quietSegments: ReadonlySet<string>;
}

export interface TrailInputs {
  /** Σ min(coveredM, length) over counted segments outside `quietSegments` (float: compare within 1e-6). */
  nonQuietCoveredM: number;
  /** Trails with at least one such covered segment, sorted. */
  touchedTrails: string[];
  /** Their parks ∩ Play parks, sorted. */
  touchedParks: string[];
}

export type TrailMetric = "climb" | "parks";

const tablesOf = (dataset: Dataset): TrailTables | null => DERIVED[dataset].trailRules;

/**
 * Covered metres per counted (included) segment, clamped to its length, in dataset order, from coverage bits. The
 * golden's `segmentLengthM` and W-COV read the same set: included segments; a named trail's non-included segments
 * never count.
 */
export function coveredMetres(dataset: Dataset, cov: CoverageMap): Record<string, number> {
  const meta = METAS[dataset];
  const out: Record<string, number> = {};
  for (const [id, s] of Object.entries(meta.segments)) {
    if (s[3] !== 1 || !cov.has(id)) continue;
    const c = coveredClamped(meta, cov, id);
    if (c > 0) out[id] = c;
  }
  return out;
}

/** `inputs(coverage)` of the golden: raw `{segmentId: coveredM}` → the rule inputs. Empty inputs without tables. */
export function trailInputs(dataset: Dataset, coveredM: Readonly<Record<string, number>>, tables = tablesOf(dataset)): TrailInputs {
  const meta = METAS[dataset];
  if (!tables) return { nonQuietCoveredM: 0, touchedTrails: [], touchedParks: [] };
  let nonQuietCoveredM = 0;
  const covered = new Set<string>();
  for (const [id, m] of Object.entries(coveredM)) {
    const s = meta.segments[id];
    if (!s || s[3] !== 1 || tables.quietSegments.has(id)) continue;
    const c = Math.min(m, s[0]);
    if (c <= 0) continue;
    nonQuietCoveredM += c;
    covered.add(id);
  }
  const touchedTrails = Object.keys(meta.trails)
    .filter((t) => meta.trails[t]!.some((s) => covered.has(s)))
    .sort();
  const touchedParks = [...new Set(touchedTrails.map((t) => tables.parkOf[t]).filter((p): p is string => !!p && tables.parks.has(p)))].sort();
  return { nonQuietCoveredM, touchedTrails, touchedParks };
}

/** Completed non-quiet trails the tables know (a completed id the dataset no longer has is ignored). */
function doneTrails(completed: Iterable<string>, tables: TrailTables): Set<string> {
  const done = new Set<string>();
  for (const t of completed) if (t in tables.gainDm && !tables.quietFeatures.has(t)) done.add(t);
  return done;
}

/** Every key the inputs qualify for (paid or not). */
export function qualifyingKeys(inputs: TrailInputs, completed: Iterable<string>, islandWhole: boolean, tables: TrailTables | null): Set<string> {
  const keys = new Set<string>();
  if (!tables) return keys;
  const done = doneTrails(completed, tables);
  let climbDm = 0;
  for (const t of done) climbDm += tables.gainDm[t]!;
  for (const m of CLIMB_STEPS_M) if (climbDm >= m * 10) keys.add(`climb:${m}`);
  for (const t of done) if (tables.gainDm[t]! >= STEEP_DM) keys.add(`high:${t}`);
  const miles = inputs.nonQuietCoveredM / METRES_PER_MILE;
  for (const mi of MILE_STEPS) if (mi <= miles + EPS) keys.add(`mile:${mi}`);
  for (const p of inputs.touchedParks) keys.add(`park1:${p}`);
  const parkTrails = new Map<string, string[]>();
  for (const [t, p] of Object.entries(tables.parkOf)) {
    if (tables.quietFeatures.has(t) || !tables.parks.has(p)) continue;
    parkTrails.set(p, [...(parkTrails.get(p) ?? []), t]);
  }
  for (const [p, trails] of parkTrails) if (trails.every((t) => done.has(t))) keys.add(`parkc:${p}`);
  if (islandWhole) keys.add("island");
  return keys;
}

export function kindOf(key: string): TrailKind | null {
  const colon = key.indexOf(":");
  return KIND_BY_PREFIX[colon < 0 ? key : key.slice(0, colon)] ?? null;
}

/** Marks a key pays (0 for an unknown key). */
export function marksOf(key: string): number {
  const k = kindOf(key);
  return k === null ? 0 : TRAIL_MARKS[k];
}

/** `qualifying − paid`, sorted, with the marks each key pays now. */
export function owedKeys(qualifying: ReadonlySet<string>, paid: ReadonlySet<string>): { key: string; marks: number }[] {
  return [...qualifying]
    .filter((k) => !paid.has(k))
    .sort()
    .map((key) => ({ key, marks: marksOf(key) }));
}

/** W-ISLAND: the island complete (D6: covered island metres ≥ total × (1 − 1e-9)). */
export function islandWhole(dataset: Dataset, islandM: number): boolean {
  const total = METAS[dataset].islandTotalM;
  return total > 0 && islandM >= total * (1 - EPS);
}

/**
 * Keys a user's merged coverage, sticky completed set and island metres qualify for after an upload (the route's
 * entry point). Empty for a dataset without trails tables (roads).
 */
export function trailQualifying(dataset: Dataset, cov: CoverageMap, completed: Iterable<string>, islandM: number): Set<string> {
  const tables = tablesOf(dataset);
  if (!tables) return new Set();
  return qualifyingKeys(trailInputs(dataset, coveredMetres(dataset, cov), tables), completed, islandWhole(dataset, islandM), tables);
}

/**
 * D5 personal challenge target: `min(default, remaining(completed) + progress)`. `climb`: remaining = Σ dm of non-quiet
 * trails not completed, `progress` in dm; `parks`: remaining = Play parks with ≥ 1 non-quiet trail not completed,
 * `progress` = the distinct parks already hopped this window (counted into the union). 0 = nothing left to do (no
 * card, no claim).
 */
export function challengeTarget(
  metric: TrailMetric,
  dflt: number,
  completed: Iterable<string>,
  progress: number | readonly string[],
  tables: TrailTables | null,
): number {
  if (!tables) return 0;
  const c = new Set(completed);
  if (metric === "climb") {
    let remaining = 0;
    for (const [t, dm] of Object.entries(tables.gainDm)) if (!tables.quietFeatures.has(t) && !c.has(t)) remaining += dm;
    return Math.min(dflt, remaining + (typeof progress === "number" ? progress : progress.length));
  }
  const remaining = new Set(typeof progress === "number" ? [] : progress);
  for (const [t, p] of Object.entries(tables.parkOf)) if (!tables.quietFeatures.has(t) && tables.parks.has(p) && !c.has(t)) remaining.add(p);
  return Math.min(dflt, remaining.size);
}
