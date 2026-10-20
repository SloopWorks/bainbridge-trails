import { META } from "./segments";

/**
 * TL-11 inference resistance: others' leaderboard rows and community stats come from snapshots rebuilt daily
 * (cron) or on demand (admin). Names, hidden flags and deletions are still applied live at read time.
 */
export async function rebuildSnapshots(env: Env, now: number): Promise<{ ranked: number; trails: number }> {
  const { results } = await env.DB.prepare(
    `SELECT t.trail_id, t.fraction, (c.trail_id IS NOT NULL) AS done
     FROM user_trails t JOIN users u ON u.id = t.user_id
     LEFT JOIN user_completed c ON c.user_id = t.user_id AND c.trail_id = t.trail_id
     WHERE u.hidden = 0 AND t.fraction > 0
     ORDER BY t.trail_id, t.fraction`,
  ).run<{ trail_id: string; fraction: number; done: number }>();
  const byTrail = new Map<string, { fs: number[]; done: number }>();
  for (const r of results) {
    let e = byTrail.get(r.trail_id);
    if (!e) byTrail.set(r.trail_id, (e = { fs: [], done: 0 }));
    e.fs.push(r.fraction);
    if (r.done) e.done++;
  }
  const ins = env.DB.prepare("INSERT INTO trail_stats_snapshot (trail_id, walkers, completed, median_fraction) VALUES (?, ?, ?, ?)");
  const stmts: D1PreparedStatement[] = [
    env.DB.prepare("DELETE FROM leaderboard_snapshot"),
    env.DB.prepare(
      `INSERT INTO leaderboard_snapshot (user_id, ord, island_m, trails_completed, reached_at)
       SELECT id, ROW_NUMBER() OVER (ORDER BY island_m DESC, trails_completed DESC, reached_at ASC, id ASC),
              island_m, trails_completed, COALESCE(reached_at, 0)
       FROM users WHERE hidden = 0 AND island_m > 0`,
    ),
    env.DB.prepare("DELETE FROM trail_stats_snapshot"),
  ];
  for (const trailId of Object.keys(META.trails)) {
    const e = byTrail.get(trailId) ?? { fs: [], done: 0 };
    stmts.push(ins.bind(trailId, e.fs.length, e.done, e.fs.length > 0 ? median(e.fs) : null));
  }
  stmts.push(
    env.DB.prepare(
      "INSERT INTO snapshot_meta (id, generated_at) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET generated_at = excluded.generated_at",
    ).bind(now),
  );
  const res = await env.DB.batch(stmts);
  return { ranked: res[1]?.meta.changes ?? 0, trails: Object.keys(META.trails).length };
}

/** Builds the snapshots once if they were never built (fresh deploy / local dev). */
export async function ensureSnapshots(env: Env, now: number): Promise<void> {
  const meta = await env.DB.prepare("SELECT generated_at FROM snapshot_meta WHERE id = 1").first();
  if (!meta) await rebuildSnapshots(env, now);
}

export function median(sorted: number[]): number {
  const n = sorted.length;
  if (n === 0) return 0;
  const mid = Math.floor(n / 2);
  return n % 2 === 1 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2;
}
