import { COMPLETION_THRESHOLD, type CoverageMap, computeMetrics, decodeBits } from "./coverage";
import { META } from "./segments";
import { floorTo } from "./util";

/** Columns read for request handling. apple_refresh_token is deliberately excluded (read only on deletion). */
export const USER_COLS =
  "id, provider, display_name, anon_name, hidden, self_hidden, island_m, island_rank, covered_m, trails_completed, reached_at, " +
  "metrics_version, coverage_rev, backfilled, walk_count, last_walk_upload_at";
export const USER_COLS_U = USER_COLS.split(", ").map((c) => `u.${c}`).join(", ");

export interface UserRow {
  id: string;
  provider: string;
  display_name: string | null;
  anon_name: string;
  /** Admin moderation hide (leaderboard + community stats). */
  hidden: number;
  /** The user's own "Hide me from the leaderboard" (leaderboard only; P1-8). */
  self_hidden: number;
  island_m: number;
  island_rank: number;
  covered_m: number;
  trails_completed: number;
  reached_at: number | null;
  metrics_version: string | null;
  coverage_rev: number;
  backfilled: number;
  walk_count: number;
  last_walk_upload_at: number | null;
}

/** Ranking key: island % floored to 0.1 % (P1-4), as an integer. */
export function islandRank(islandM: number): number {
  if (META.islandTotalM <= 0) return 0;
  return Math.min(1000, Math.floor((islandM * 1000) / META.islandTotalM + 1e-9));
}

/** Island % floored to 1 decimal (TL-5). */
export const islandPct = (islandM: number): number => floorTo(islandRank(islandM) / 10, 1);

export async function getUser(env: Env, id: string): Promise<UserRow | null> {
  return env.DB.prepare(`SELECT ${USER_COLS} FROM users WHERE id = ?`).bind(id).first<UserRow>();
}

export async function loadCoverage(env: Env, userId: string): Promise<CoverageMap> {
  const { results } = await env.DB.prepare("SELECT segment_id, bits FROM coverage WHERE user_id = ?")
    .bind(userId)
    .run<{ segment_id: string; bits: string }>();
  const out: CoverageMap = new Map();
  for (const r of results) {
    const b = decodeBits(r.bits);
    if (b && b.length > 0) out.set(r.segment_id, b);
  }
  return out;
}

export async function completedSet(env: Env, userId: string): Promise<string[]> {
  const { results } = await env.DB.prepare("SELECT trail_id FROM user_completed WHERE user_id = ? ORDER BY trail_id")
    .bind(userId)
    .run<{ trail_id: string }>();
  return results.map((r) => r.trail_id);
}

/**
 * P1-7: recompute stored metrics with the bundled dataset (after a dataset update). Fractions are rewritten,
 * completions stay sticky, reached_at moves only if the rank rose. Returns false on a concurrent write.
 */
export async function recomputeMetrics(env: Env, user: UserRow, now: number): Promise<boolean> {
  const [cov, sticky] = await Promise.all([loadCoverage(env, user.id), completedSet(env, user.id)]);
  const m = computeMetrics(META, cov);
  const set = new Set(sticky);
  const added: string[] = [];
  for (const [t, f] of m.trailFractions) {
    if (f >= COMPLETION_THRESHOLD && !set.has(t)) {
      set.add(t);
      added.push(t);
    }
  }
  const rank = islandRank(m.islandM);
  const reachedAt = rank > user.island_rank || user.reached_at === null ? now : user.reached_at;
  const stmts: D1PreparedStatement[] = [
    env.DB.prepare(
      `UPDATE users SET coverage_rev = CASE WHEN coverage_rev = ?2 THEN coverage_rev + 1 ELSE NULL END,
         island_m = ?3, island_rank = ?4, covered_m = ?5, trails_completed = ?6, reached_at = ?7, metrics_version = ?8
       WHERE id = ?1`,
    ).bind(user.id, user.coverage_rev, m.islandM, rank, m.coveredM, set.size, reachedAt, META.version),
    env.DB.prepare("DELETE FROM user_trails WHERE user_id = ?").bind(user.id),
  ];
  const insT = env.DB.prepare("INSERT INTO user_trails (user_id, trail_id, fraction) VALUES (?, ?, ?)");
  for (const [t, f] of m.trailFractions) stmts.push(insT.bind(user.id, t, f));
  const insC = env.DB.prepare("INSERT INTO user_completed (user_id, trail_id) VALUES (?, ?) ON CONFLICT DO NOTHING");
  for (const t of added) stmts.push(insC.bind(user.id, t));
  try {
    await env.DB.batch(stmts);
    return true;
  } catch (e) {
    if (e instanceof Error && /NOT NULL constraint failed/i.test(e.message)) return false;
    throw e;
  }
}

/** Returns the user with metrics for the bundled dataset, recomputing lazily when stale. */
export async function freshUser(env: Env, user: UserRow, now: number): Promise<UserRow> {
  if (user.metrics_version === META.version) return user;
  await recomputeMetrics(env, user, now);
  return (await getUser(env, user.id)) ?? user;
}
