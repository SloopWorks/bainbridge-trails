import { publicName } from "./moderation/public-name";
import { META } from "./segments";

/**
 * TL-11 inference resistance: others' leaderboard rows and community stats come from snapshots rebuilt daily
 * (cron) or on demand (admin). Renames, hides and deletions edit the snapshot in place and change snap_id.
 * P1-6: each isolate keeps the snapshot in memory keyed by snap_id, so a public read costs one D1 row.
 */

export interface BoardSnapRow {
  user_id: string;
  display_name: string;
  island_rank: number;
  trails_completed: number;
  reached_at: number;
}
export interface StatsSnapRow {
  trail_id: string;
  walkers: number;
  completed: number;
  median_fraction: number | null;
}
interface Meta {
  snap_id: string;
  generated_at: number;
  dataset_version: string;
  total_ranked: number;
}
export interface Snapshot extends Meta {
  board: BoardSnapRow[];
  stats: Map<string, StatsSnapRow>;
}

let memo: Snapshot | null = null; // per-isolate cache, invalidated by snap_id

export async function rebuildSnapshots(env: Env, now: number): Promise<{ ranked: number; trails: number }> {
  const [users, trails] = await env.DB.batch<unknown>([
    env.DB.prepare(
      `SELECT id, display_name, anon_name, island_rank, trails_completed, COALESCE(reached_at, 0) AS reached_at
       FROM users WHERE hidden = 0 AND self_hidden = 0 AND island_m > 0
       ORDER BY island_rank DESC, trails_completed DESC, reached_at ASC, id ASC`,
    ),
    env.DB.prepare(
      `SELECT t.trail_id, t.fraction, (c.trail_id IS NOT NULL) AS done
       FROM user_trails t JOIN users u ON u.id = t.user_id
       LEFT JOIN user_completed c ON c.user_id = t.user_id AND c.trail_id = t.trail_id
       WHERE u.hidden = 0 AND t.fraction > 0
       ORDER BY t.trail_id, t.fraction`,
    ),
  ]);
  type U = { id: string; display_name: string | null; anon_name: string; island_rank: number; trails_completed: number; reached_at: number };
  const userRows = (users?.results ?? []) as U[];
  const byTrail = new Map<string, { fs: number[]; done: number }>();
  for (const r of (trails?.results ?? []) as { trail_id: string; fraction: number; done: number }[]) {
    let e = byTrail.get(r.trail_id);
    if (!e) byTrail.set(r.trail_id, (e = { fs: [], done: 0 }));
    e.fs.push(r.fraction);
    if (r.done) e.done++;
  }
  const insB = env.DB.prepare(
    "INSERT INTO leaderboard_snapshot (user_id, ord, display_name, island_rank, trails_completed, reached_at) VALUES (?, ?, ?, ?, ?, ?)",
  );
  const insS = env.DB.prepare("INSERT INTO trail_stats_snapshot (trail_id, walkers, completed, median_fraction) VALUES (?, ?, ?, ?)");
  const stmts: D1PreparedStatement[] = [env.DB.prepare("DELETE FROM leaderboard_snapshot"), env.DB.prepare("DELETE FROM trail_stats_snapshot")];
  userRows.forEach((u, i) => stmts.push(insB.bind(u.id, i + 1, publicName(u), u.island_rank, u.trails_completed, u.reached_at)));
  for (const trailId of Object.keys(META.trails)) {
    const e = byTrail.get(trailId) ?? { fs: [], done: 0 };
    stmts.push(insS.bind(trailId, e.fs.length, e.done, e.fs.length > 0 ? median(e.fs) : null));
  }
  stmts.push(
    env.DB.prepare(
      `INSERT INTO snapshot_meta (id, snap_id, generated_at, dataset_version, total_ranked) VALUES (1, ?1, ?2, ?3, ?4)
       ON CONFLICT (id) DO UPDATE SET snap_id = ?1, generated_at = ?2, dataset_version = ?3, total_ranked = ?4`,
    ).bind(crypto.randomUUID(), now, META.version, userRows.length),
  );
  await env.DB.batch(stmts);
  return { ranked: userRows.length, trails: Object.keys(META.trails).length };
}

/** Current snapshot (built if missing or computed with another dataset version), cached per isolate. */
export async function getSnapshot(env: Env, now: number): Promise<Snapshot> {
  let meta = await env.DB.prepare("SELECT snap_id, generated_at, dataset_version, total_ranked FROM snapshot_meta WHERE id = 1").first<Meta>();
  if (!meta || meta.dataset_version !== META.version) {
    await rebuildSnapshots(env, now);
    meta = await env.DB.prepare("SELECT snap_id, generated_at, dataset_version, total_ranked FROM snapshot_meta WHERE id = 1").first<Meta>();
  }
  if (memo && memo.snap_id === meta!.snap_id) return memo;
  const [b, s] = await env.DB.batch<unknown>([
    env.DB.prepare("SELECT user_id, display_name, island_rank, trails_completed, reached_at FROM leaderboard_snapshot ORDER BY ord"),
    env.DB.prepare("SELECT trail_id, walkers, completed, median_fraction FROM trail_stats_snapshot"),
  ]);
  const stats = new Map(((s?.results ?? []) as StatsSnapRow[]).map((r) => [r.trail_id, r]));
  memo = { ...meta!, board: (b?.results ?? []) as BoardSnapRow[], stats };
  return memo;
}

const touch = (env: Env) =>
  env.DB.prepare("UPDATE snapshot_meta SET snap_id = ?, total_ranked = (SELECT COUNT(*) FROM leaderboard_snapshot) WHERE id = 1").bind(
    crypto.randomUUID(),
  );

/** Hide / deletion: drop the user's row now (not at the next rebuild). */
export function snapshotRemove(env: Env, userId: string): D1PreparedStatement[] {
  return [env.DB.prepare("DELETE FROM leaderboard_snapshot WHERE user_id = ?").bind(userId), touch(env)];
}

/** Rename / admin reset: show the new public name now. */
export function snapshotRename(env: Env, userId: string, name: string): D1PreparedStatement[] {
  return [env.DB.prepare("UPDATE leaderboard_snapshot SET display_name = ? WHERE user_id = ?").bind(name, userId), touch(env)];
}

export function median(sorted: number[]): number {
  const n = sorted.length;
  if (n === 0) return 0;
  const mid = Math.floor(n / 2);
  return n % 2 === 1 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2;
}
