import { type CoverageMap, coveredClamped } from "../coverage";
import { type Dataset, METAS } from "../segments";
import { DERIVED } from "./derived";
import { type PlayItem, type PlayPack, epochDay, weekId } from "./recurrence";

/**
 * Marks rules the server derives (product § 3.2–3.3, PD-2). Pure functions; the Kotlin `play/rules/Marks.kt` (L1-6)
 * mirrors them. The client never sends a mark: every value here comes from the pack and the accepted coverage.
 */

/** W-COV fixed rate: metres per mark. */
export const COV_RATE_M: Record<Dataset, number> = { trails: 10, roads: 25 };
/** W-DONE per completed trail / road. */
export const DONE_MARKS: Record<Dataset, number> = { trails: 100, roads: 25 };
/** W-SQUARE per chart square with non-quiet coverage. */
export const SQUARE_MARKS = 25;
/** W-DAY per accepted `dayKey`. */
export const DAY_MARKS = 15;
/** F-RIDDLE per star. */
export const RIDDLE_STAR_MARKS = 40;
export const MAX_HINTS = 2;

export interface WalkDerived {
  /** W-COV: floor(non-quiet included covered metres / rate). */
  cov: number;
  /** W-DONE + W-SQUARE. */
  derived: number;
}

/**
 * Walk-derived marks of a whole coverage state (cumulative, so the starting balance is the same formula):
 * - W-COV = floor(Σ covered metres of included segments outside quiet trails / rate), summed in dataset order like
 *   `computeMetrics` (trails today: 102,964 m / 10; roads 261,537 m / 25).
 * - W-DONE = rate × completed (sticky) features that are not quiet trails.
 * - W-SQUARE = 25 × chart squares touched by a covered included segment outside quiet trails.
 * The server credits `max(previous, new)` of each, so a dataset update never lowers anyone's marks.
 */
export function walkDerivedMarks(dataset: Dataset, cov: CoverageMap, completed: Iterable<string>): WalkDerived {
  const meta = METAS[dataset];
  const d = DERIVED[dataset];
  let metres = 0;
  const squares = new Set<string>();
  for (const [id, s] of Object.entries(meta.segments)) {
    if (s[3] !== 1 || !cov.has(id) || d.quietSegments.has(id)) continue;
    const c = coveredClamped(meta, cov, id);
    if (c <= 0) continue;
    metres += c;
    for (const q of d.segmentSquares[id] ?? []) squares.add(q);
  }
  let done = 0;
  for (const f of completed) if (!d.quietFeatures.has(f)) done++;
  return { cov: Math.floor(metres / COV_RATE_M[dataset]), derived: done * DONE_MARKS[dataset] + squares.size * SQUARE_MARKS };
}

/** Share (0..1) of a set of segments' length that carries coverage bits (by segment meta length). */
export function coveredShare(dataset: Dataset, cov: CoverageMap, segmentIds: readonly string[]): { share: number; coveredM: number; totalM: number } {
  const meta = METAS[dataset];
  let total = 0;
  let covered = 0;
  for (const id of segmentIds) {
    const s = meta.segments[id];
    if (!s) continue;
    total += s[0];
    covered += coveredClamped(meta, cov, id);
  }
  return { share: total <= 0 ? 0 : covered / total, coveredM: covered, totalM: total };
}

interface Tier {
  tier: string;
  marks: number;
  count: number;
  everyNthWeek?: number;
  else?: string;
}

/** Weeks between the pack epoch's week and `monday` (0 = launch week). */
export function weekIndex(pack: PlayPack, monday: string): number {
  const epoch = weekId(pack.epoch ?? monday);
  return Math.round((epochDay(monday) - epochDay(epoch)) / 7);
}

/**
 * F-TREASURE: placement `n` of a weekly set takes the tiers in pack order, each repeated `count` times
 * (trails: #00–#06 sea glass, #07–#09 agate, #10 glass float, #11 ship's bell). A tier with `everyNthWeek` pays as
 * its `else` tier except in weeks whose index since the pack epoch's week is a multiple of it (week 0 = launch).
 * Null when `n` is outside the set.
 */
export function treasureMarks(item: PlayItem, n: number, pack: PlayPack, monday: string): number | null {
  const tiers = (item.tiers as Tier[] | undefined) ?? [];
  let i = 0;
  for (const t of tiers) {
    if (n < i + t.count) {
      if (t.everyNthWeek && weekIndex(pack, monday) % t.everyNthWeek !== 0) {
        const alt = tiers.find((x) => x.tier === t.else);
        return alt ? alt.marks : null;
      }
      return t.marks;
    }
    i += t.count;
  }
  return null;
}

/** F-INK: the marks of bucket `band` (60 / 80 / 95), or null when the item pays no such bucket. */
export function inkMarks(item: PlayItem, band: unknown): number | null {
  const bands = (item.bands as { pct: number; marks: number }[] | undefined) ?? [];
  return typeof band === "number" ? (bands.find((b) => b.pct === band)?.marks ?? null) : null;
}

/** F-RIDDLE: stars × 40, each hint −25 %, floor 50 %, rounded down. */
export function riddleMarks(item: PlayItem, hints: number): number {
  const stars = typeof item.stars === "number" ? item.stars : 0;
  const h = Math.min(Math.max(0, Math.floor(hints)), MAX_HINTS);
  return Math.floor(stars * RIDDLE_STAR_MARKS * Math.max(0.5, 1 - 0.25 * h));
}

/** F-EGG / F-LORE / challenge: the item's `marks`; remembrance lore pays 0, always (product § 4.6.3). */
export function itemMarks(item: PlayItem): number {
  if (item.lore === "remembrance") return 0;
  return typeof item.marks === "number" && item.marks > 0 ? Math.floor(item.marks) : 0;
}
