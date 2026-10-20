import { COMPLETION_THRESHOLD, type CoverageMap, computeMetrics, decodeBits } from "./coverage";
import { type Dataset, METAS, type SegmentMeta, segmentRange } from "./segments";
import { floorTo } from "./util";

/** Columns read for request handling. apple_refresh_token is deliberately excluded (read only on deletion). */
export const USER_COLS = "id, provider, display_name, anon_name, hidden, self_hidden";
export const USER_COLS_U = USER_COLS.split(", ").map((c) => `u.${c}`).join(", ");

/** Identity + visibility. Metrics live per dataset in [MetricsRow] (RD-7). */
export interface UserRow {
  id: string;
  provider: string;
  display_name: string | null;
  anon_name: string;
  /** Admin moderation hide (leaderboard + community stats, every dataset). */
  hidden: number;
  /** The user's own "Hide me from the leaderboard" (leaderboard only, every dataset; P1-8). */
  self_hidden: number;
}

export const METRICS_COLS =
  "user_id, dataset, island_m, island_rank, covered_m, trails_completed, reached_at, metrics_version, coverage_rev, backfilled, " +
  "walk_count, last_walk_upload_at";

/** One `user_metrics` row: a user's standing and sync state in one dataset. */
export interface MetricsRow {
  user_id: string;
  dataset: Dataset;
  island_m: number;
  island_rank: number;
  covered_m: number;
  /** Sticky completed trails (roads dataset: roads). */
  trails_completed: number;
  reached_at: number | null;
  metrics_version: string | null;
  coverage_rev: number;
  backfilled: number;
  walk_count: number;
  last_walk_upload_at: number | null;
}

/** Ranking key: island % floored to 0.1 % (P1-4), as an integer. */
export function islandRank(meta: SegmentMeta, islandM: number): number {
  if (meta.islandTotalM <= 0) return 0;
  return Math.min(1000, Math.floor((islandM * 1000) / meta.islandTotalM + 1e-9));
}

/** Island % floored to 1 decimal (TL-5). */
export const islandPct = (meta: SegmentMeta, islandM: number): number => floorTo(islandRank(meta, islandM) / 10, 1);

export async function getUser(env: Env, id: string): Promise<UserRow | null> {
  return env.DB.prepare(`SELECT ${USER_COLS} FROM users WHERE id = ?`).bind(id).first<UserRow>();
}

export async function getMetricsRow(env: Env, userId: string, dataset: Dataset): Promise<MetricsRow | null> {
  return env.DB.prepare(`SELECT ${METRICS_COLS} FROM user_metrics WHERE user_id = ? AND dataset = ?`).bind(userId, dataset).first<MetricsRow>();
}

/** Inserts the empty row for (user, dataset) if missing; a new row is current for the bundled dataset. */
export function ensureMetricsStmt(env: Env, userId: string, dataset: Dataset): D1PreparedStatement {
  return env.DB.prepare("INSERT INTO user_metrics (user_id, dataset, metrics_version) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").bind(
    userId,
    dataset,
    METAS[dataset].version,
  );
}

/** The user's coverage rows of one dataset (segment-id range on the primary key; never the other dataset's). */
export async function loadCoverage(env: Env, userId: string, dataset: Dataset): Promise<CoverageMap> {
  const [lo, hi] = segmentRange(dataset);
  const { results } = await env.DB.prepare("SELECT segment_id, bits FROM coverage WHERE user_id = ? AND segment_id >= ? AND segment_id < ?")
    .bind(userId, lo, hi)
    .run<{ segment_id: string; bits: string }>();
  const out: CoverageMap = new Map();
  for (const r of results) {
    const b = decodeBits(r.bits);
    if (b && b.length > 0) out.set(r.segment_id, b);
  }
  return out;
}

export async function completedSet(env: Env, userId: string, dataset: Dataset): Promise<string[]> {
  const { results } = await env.DB.prepare("SELECT trail_id FROM user_completed WHERE user_id = ? AND dataset = ? ORDER BY trail_id")
    .bind(userId, dataset)
    .run<{ trail_id: string }>();
  return results.map((r) => r.trail_id);
}

/**
 * P1-7: recompute stored metrics with the bundled dataset (after a dataset update). Fractions are rewritten,
 * completions stay sticky, reached_at moves only if the rank rose. Returns false on a concurrent write.
 */
export async function recomputeMetrics(env: Env, row: MetricsRow, now: number): Promise<boolean> {
  const meta = METAS[row.dataset];
  const [cov, sticky] = await Promise.all([loadCoverage(env, row.user_id, row.dataset), completedSet(env, row.user_id, row.dataset)]);
  const m = computeMetrics(meta, cov);
  const set = new Set(sticky);
  const added: string[] = [];
  for (const [t, f] of m.trailFractions) {
    if (f >= COMPLETION_THRESHOLD && !set.has(t)) {
      set.add(t);
      added.push(t);
    }
  }
  const rank = islandRank(meta, m.islandM);
  const reachedAt = rank > row.island_rank || row.reached_at === null ? now : row.reached_at;
  const stmts: D1PreparedStatement[] = [
    env.DB.prepare(
      `UPDATE user_metrics SET coverage_rev = CASE WHEN coverage_rev = ?3 THEN coverage_rev + 1 ELSE NULL END,
         island_m = ?4, island_rank = ?5, covered_m = ?6, trails_completed = ?7, reached_at = ?8, metrics_version = ?9
       WHERE user_id = ?1 AND dataset = ?2`,
    ).bind(row.user_id, row.dataset, row.coverage_rev, m.islandM, rank, m.coveredM, set.size, reachedAt, meta.version),
    env.DB.prepare("DELETE FROM user_trails WHERE user_id = ? AND dataset = ?").bind(row.user_id, row.dataset),
  ];
  const insT = env.DB.prepare("INSERT INTO user_trails (user_id, trail_id, fraction, dataset) VALUES (?, ?, ?, ?)");
  for (const [t, f] of m.trailFractions) stmts.push(insT.bind(row.user_id, t, f, row.dataset));
  const insC = env.DB.prepare("INSERT INTO user_completed (user_id, trail_id, dataset) VALUES (?, ?, ?) ON CONFLICT DO NOTHING");
  for (const t of added) stmts.push(insC.bind(row.user_id, t, row.dataset));
  try {
    await env.DB.batch(stmts);
    return true;
  } catch (e) {
    if (e instanceof Error && /NOT NULL constraint failed/i.test(e.message)) return false;
    throw e;
  }
}

/**
 * The user's metrics in `dataset`, current for the bundled dataset version (recomputed lazily when stale).
 * Creates the empty row on first use, so every signed-in user has one per dataset they touch.
 */
export async function metricsFor(env: Env, userId: string, dataset: Dataset, now: number): Promise<MetricsRow> {
  let row = await getMetricsRow(env, userId, dataset);
  if (!row) {
    await ensureMetricsStmt(env, userId, dataset).run();
    row = (await getMetricsRow(env, userId, dataset))!;
  }
  if (row.metrics_version === METAS[dataset].version) return row;
  await recomputeMetrics(env, row, now);
  return (await getMetricsRow(env, userId, dataset)) ?? row;
}
