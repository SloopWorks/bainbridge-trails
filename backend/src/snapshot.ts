import { publicName } from "./moderation/public-name";
import { DATASETS, type Dataset, METAS } from "./segments";

/**
 * TL-11 inference resistance: others' leaderboard rows and community stats come from snapshots rebuilt daily
 * (cron) or on demand (admin). Renames, hides and deletions edit the snapshot in place and change snap_id.
 * P1-6: each isolate keeps the snapshot in memory keyed by snap_id, so a public read costs one D1 row.
 * RD-7: one snapshot per dataset (rows and meta keyed by `dataset`).
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

const memo = new Map<Dataset, Snapshot>(); // per-isolate cache, invalidated by snap_id

/** Rebuilds one dataset's snapshot. */
export async function rebuildSnapshot(env: Env, now: number, dataset: Dataset): Promise<{ ranked: number; trails: number }> {
  const meta = METAS[dataset];
  const [users, trails] = await env.DB.batch<unknown>([
    env.DB.prepare(
      `SELECT u.id, u.display_name, u.anon_name, m.island_rank, m.trails_completed, COALESCE(m.reached_at, 0) AS reached_at
       FROM user_metrics m JOIN users u ON u.id = m.user_id
       WHERE m.dataset = ? AND u.hidden = 0 AND u.self_hidden = 0 AND m.island_m > 0
       ORDER BY m.island_rank DESC, m.trails_completed DESC, reached_at ASC, u.id ASC`,
    ).bind(dataset),
    env.DB.prepare(
      `SELECT t.trail_id, t.fraction, (c.trail_id IS NOT NULL) AS done
       FROM user_trails t JOIN users u ON u.id = t.user_id
       LEFT JOIN user_completed c ON c.user_id = t.user_id AND c.trail_id = t.trail_id
       WHERE t.dataset = ? AND u.hidden = 0 AND t.fraction > 0
       ORDER BY t.trail_id, t.fraction`,
    ).bind(dataset),
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
    "INSERT INTO leaderboard_snapshot (dataset, user_id, ord, display_name, island_rank, trails_completed, reached_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
  );
  const insS = env.DB.prepare("INSERT INTO trail_stats_snapshot (dataset, trail_id, walkers, completed, median_fraction) VALUES (?, ?, ?, ?, ?)");
  const stmts: D1PreparedStatement[] = [
    env.DB.prepare("DELETE FROM leaderboard_snapshot WHERE dataset = ?").bind(dataset),
    env.DB.prepare("DELETE FROM trail_stats_snapshot WHERE dataset = ?").bind(dataset),
  ];
  userRows.forEach((u, i) => stmts.push(insB.bind(dataset, u.id, i + 1, publicName(u), u.island_rank, u.trails_completed, u.reached_at)));
  for (const trailId of Object.keys(meta.trails)) {
    const e = byTrail.get(trailId) ?? { fs: [], done: 0 };
    stmts.push(insS.bind(dataset, trailId, e.fs.length, e.done, e.fs.length > 0 ? median(e.fs) : null));
  }
  stmts.push(
    env.DB.prepare(
      `INSERT INTO snapshot_meta (dataset, snap_id, generated_at, dataset_version, total_ranked) VALUES (?1, ?2, ?3, ?4, ?5)
       ON CONFLICT (dataset) DO UPDATE SET snap_id = ?2, generated_at = ?3, dataset_version = ?4, total_ranked = ?5`,
    ).bind(dataset, crypto.randomUUID(), now, meta.version, userRows.length),
  );
  await env.DB.batch(stmts);
  return { ranked: userRows.length, trails: Object.keys(meta.trails).length };
}

/** Rebuilds every dataset's snapshot (cron, admin). Totals per dataset. */
export async function rebuildSnapshots(env: Env, now: number): Promise<Record<Dataset, { ranked: number; trails: number }>> {
  const out = {} as Record<Dataset, { ranked: number; trails: number }>;
  for (const d of DATASETS) out[d] = await rebuildSnapshot(env, now, d);
  return out;
}

const META_SQL = "SELECT snap_id, generated_at, dataset_version, total_ranked FROM snapshot_meta WHERE dataset = ?";

/** Current snapshot of a dataset (built if missing or computed with another dataset version), cached per isolate. */
export async function getSnapshot(env: Env, now: number, dataset: Dataset): Promise<Snapshot> {
  let meta = await env.DB.prepare(META_SQL).bind(dataset).first<Meta>();
  if (!meta || meta.dataset_version !== METAS[dataset].version) {
    await rebuildSnapshot(env, now, dataset);
    meta = await env.DB.prepare(META_SQL).bind(dataset).first<Meta>();
  }
  const cached = memo.get(dataset);
  if (cached && cached.snap_id === meta!.snap_id) return cached;
  const [b, s] = await env.DB.batch<unknown>([
    env.DB.prepare("SELECT user_id, display_name, island_rank, trails_completed, reached_at FROM leaderboard_snapshot WHERE dataset = ? ORDER BY ord").bind(
      dataset,
    ),
    env.DB.prepare("SELECT trail_id, walkers, completed, median_fraction FROM trail_stats_snapshot WHERE dataset = ?").bind(dataset),
  ]);
  const stats = new Map(((s?.results ?? []) as StatsSnapRow[]).map((r) => [r.trail_id, r]));
  const snap = { ...meta!, board: (b?.results ?? []) as BoardSnapRow[], stats };
  memo.set(dataset, snap);
  return snap;
}

/** New snap_id + ranked count for every dataset (a user edit can touch any of them). */
const touch = (env: Env) =>
  env.DB.prepare(
    `UPDATE snapshot_meta SET snap_id = lower(hex(randomblob(16))),
       total_ranked = (SELECT COUNT(*) FROM leaderboard_snapshot l WHERE l.dataset = snapshot_meta.dataset)`,
  );

/** Hide / deletion: drop the user's rows from every dataset now (not at the next rebuild). */
export function snapshotRemove(env: Env, userId: string): D1PreparedStatement[] {
  return [env.DB.prepare("DELETE FROM leaderboard_snapshot WHERE user_id = ?").bind(userId), touch(env)];
}

/** Rename / admin reset: show the new public name now, in every dataset. */
export function snapshotRename(env: Env, userId: string, name: string): D1PreparedStatement[] {
  return [env.DB.prepare("UPDATE leaderboard_snapshot SET display_name = ? WHERE user_id = ?").bind(name, userId), touch(env)];
}

export function median(sorted: number[]): number {
  const n = sorted.length;
  if (n === 0) return 0;
  const mid = Math.floor(n / 2);
  return n % 2 === 1 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2;
}
