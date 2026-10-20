import { applyD1Migrations, env } from "cloudflare:test";
import { describe, expect, it } from "vitest";

/**
 * RD-7 migration 0003 on a scratch database seeded with pre-RD-7 data: backfill values, and the previous Worker's
 * queries (src at 0002) still succeed afterwards (it keeps serving between `migrate:remote` and the Worker upload).
 * Then the pending 0004 cleanup.
 */
const db = env.MIGRATION_DB;
const all = env.TEST_MIGRATIONS;
const one = async <T>(sql: string, ...args: unknown[]) => (await db.prepare(sql).bind(...args).first<T>())!;
const count = async (sql: string, ...args: unknown[]) => (await one<{ n: number }>(sql, ...args)).n;

describe("migration 0003 (dataset scope) and 0004 cleanup", () => {
  it("backfills, stays compatible with the previous Worker, then cleans up", async () => {
    expect(all.map((m) => m.name)).toEqual(["0001_init.sql", "0002_self_hidden.sql", "0003_dataset_scope.sql"]);
    await applyD1Migrations(db, all.slice(0, 2));
    await db.batch([
      db.prepare(
        `INSERT INTO users (id, provider, sub, anon_name, island_m, island_rank, covered_m, trails_completed, reached_at,
           metrics_version, coverage_rev, backfilled, walk_count, last_walk_upload_at)
         VALUES ('u1', 'dev', 's1', 'Calm Otter 11', 1234.5, 11, 1300, 2, 99, 'v-old', 7, 1, 3, 500),
                ('u2', 'dev', 's2', 'Calm Otter 12', 0, 0, 0, 0, NULL, 'v-old', 0, 0, 0, NULL)`,
      ),
      db.prepare("INSERT INTO coverage VALUES ('u1', 'seg-000001', 'AQ==')"),
      db.prepare("INSERT INTO walks VALUES ('u1', 'w1'), ('u1', 'w2')"),
      db.prepare("INSERT INTO user_trails VALUES ('u1', 'trail-a', 0.5)"),
      db.prepare("INSERT INTO user_completed VALUES ('u1', 'trail-a')"),
      db.prepare("INSERT INTO leaderboard_snapshot VALUES ('u1', 1, 'Calm Otter 11', 11, 2, 99)"),
      db.prepare("INSERT INTO trail_stats_snapshot VALUES ('trail-a', 1, 0, 0.5)"),
      db.prepare("INSERT INTO snapshot_meta VALUES (1, 'snap', 1, 'v-old', 1)"),
    ]);

    await applyD1Migrations(db, all);

    // backfill
    expect(await db.prepare("SELECT * FROM user_metrics ORDER BY user_id").all().then((r) => r.results)).toEqual([
      {
        user_id: "u1", dataset: "trails", island_m: 1234.5, island_rank: 11, covered_m: 1300, trails_completed: 2, reached_at: 99,
        metrics_version: null, coverage_rev: 7, backfilled: 1, walk_count: 3, last_walk_upload_at: 500,
      },
      {
        user_id: "u2", dataset: "trails", island_m: 0, island_rank: 0, covered_m: 0, trails_completed: 0, reached_at: null,
        metrics_version: null, coverage_rev: 0, backfilled: 0, walk_count: 0, last_walk_upload_at: null,
      },
    ]);
    expect(await db.prepare("SELECT * FROM walks_v2 ORDER BY walk_id").all().then((r) => r.results)).toEqual([
      { user_id: "u1", dataset: "trails", walk_id: "w1" },
      { user_id: "u1", dataset: "trails", walk_id: "w2" },
    ]);
    expect(await one("SELECT dataset FROM user_trails")).toEqual({ dataset: "trails" });
    expect(await one("SELECT dataset FROM user_completed")).toEqual({ dataset: "trails" });
    // old tables untouched
    expect(await count("SELECT COUNT(*) AS n FROM walks")).toBe(2);
    expect(await count("SELECT COUNT(*) AS n FROM leaderboard_snapshot")).toBe(1);
    expect(await count("SELECT COUNT(*) AS n FROM trail_stats_snapshot")).toBe(1);
    expect(await count("SELECT COUNT(*) AS n FROM coverage")).toBe(1);

    // the previous Worker's statements (src at migration 0002) still run
    await db.batch([
      db.prepare(
        `UPDATE users SET coverage_rev = CASE WHEN coverage_rev = ?2 THEN coverage_rev + 1 ELSE NULL END,
           island_m = ?3, island_rank = ?4, covered_m = ?5, trails_completed = ?6, reached_at = ?7,
           metrics_version = ?8, backfilled = 1, walk_count = walk_count + ?9, last_walk_upload_at = ?10
         WHERE id = ?1`,
      ).bind("u1", 7, 1500, 14, 1600, 3, 100, "v-old", 1, 600),
      db.prepare("INSERT INTO coverage (user_id, segment_id, bits) VALUES (?, ?, ?) ON CONFLICT (user_id, segment_id) DO UPDATE SET bits = excluded.bits")
        .bind("u1", "seg-000002", "Aw=="),
      db.prepare("INSERT INTO user_trails (user_id, trail_id, fraction) VALUES (?, ?, ?) ON CONFLICT (user_id, trail_id) DO UPDATE SET fraction = excluded.fraction")
        .bind("u1", "trail-b", 0.2),
      db.prepare("INSERT INTO user_completed (user_id, trail_id) VALUES (?, ?) ON CONFLICT DO NOTHING").bind("u1", "trail-b"),
      db.prepare("INSERT INTO walks (user_id, walk_id) VALUES (?, ?) ON CONFLICT DO NOTHING").bind("u1", "w3"),
    ]);
    await db.batch([
      db.prepare("DELETE FROM leaderboard_snapshot"),
      db.prepare("DELETE FROM trail_stats_snapshot"),
      db.prepare(
        "INSERT INTO leaderboard_snapshot (user_id, ord, display_name, island_rank, trails_completed, reached_at) VALUES (?, ?, ?, ?, ?, ?)",
      ).bind("u1", 1, "Calm Otter 11", 14, 3, 100),
      db.prepare("INSERT INTO trail_stats_snapshot (trail_id, walkers, completed, median_fraction) VALUES (?, ?, ?, ?)").bind("trail-a", 1, 0, 0.5),
      db.prepare(
        `INSERT INTO snapshot_meta (id, snap_id, generated_at, dataset_version, total_ranked) VALUES (1, ?1, ?2, ?3, ?4)
         ON CONFLICT (id) DO UPDATE SET snap_id = ?1, generated_at = ?2, dataset_version = ?3, total_ranked = ?4`,
      ).bind("snap2", 2, "v-old", 1),
      db.prepare("UPDATE leaderboard_snapshot SET display_name = ? WHERE user_id = ?").bind("Renamed", "u1"),
      db.prepare("UPDATE snapshot_meta SET snap_id = ?, total_ranked = (SELECT COUNT(*) FROM leaderboard_snapshot) WHERE id = 1").bind("snap3"),
      db.prepare("SELECT snap_id, generated_at, dataset_version, total_ranked FROM snapshot_meta WHERE id = 1"),
      db.prepare("SELECT user_id, display_name, island_rank, trails_completed, reached_at FROM leaderboard_snapshot ORDER BY ord"),
    ]);
    expect(await one("SELECT dataset FROM user_trails WHERE trail_id = 'trail-b'")).toEqual({ dataset: "trails" });
    // the previous Worker's account deletion (rows referencing users go too)
    await db.batch([
      db.prepare("DELETE FROM coverage WHERE user_id = ?").bind("u2"),
      db.prepare("DELETE FROM walks WHERE user_id = ?").bind("u2"),
      db.prepare("DELETE FROM leaderboard_snapshot WHERE user_id = ?").bind("u2"),
      db.prepare("DELETE FROM users WHERE id = ?").bind("u2"),
    ]);
    expect(await count("SELECT COUNT(*) AS n FROM user_metrics WHERE user_id = 'u2'")).toBe(0);

    // 0004 cleanup (applied by hand after deploy)
    await db.batch(env.CLEANUP_QUERIES.map((q) => db.prepare(q)));
    const tables = (await db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all<{ name: string }>()).results.map((r) => r.name);
    for (const t of ["walks", "leaderboard_snapshot", "trail_stats_snapshot", "snapshot_meta"]) expect(tables).not.toContain(t);
    for (const t of ["walks_v2", "user_metrics", "leaderboard_snapshot_v2", "stats_snapshot_v2", "snapshot_meta_v2"]) expect(tables).toContain(t);
    // the walk the previous Worker credited in the window survives
    expect((await db.prepare("SELECT walk_id FROM walks_v2 ORDER BY walk_id").all<{ walk_id: string }>()).results.map((r) => r.walk_id)).toEqual([
      "w1",
      "w2",
      "w3",
    ]);
    expect(await one("SELECT island_m, metrics_version, walk_count FROM users WHERE id = 'u1'")).toEqual({
      island_m: 0,
      metrics_version: null,
      walk_count: 0,
    });
    expect(await one("SELECT island_m FROM user_metrics WHERE user_id = 'u1'")).toEqual({ island_m: 1234.5 });
  });
});
