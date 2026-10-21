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
const upTo0003 = all.filter((m) => m.name < "0004");
const play0005 = all.filter((m) => m.name.startsWith("0005_"));
const PLAY_OBJECTS = ["play_attack_stretches", "play_claims", "play_claims_week", "play_totals", "play_weeks"];

/** Every schema object (table, index) and its SQL, except D1/SQLite internals. */
async function schemaOf(db: D1Database): Promise<Map<string, string>> {
  const { results } = await db
    .prepare("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' AND name != 'd1_migrations'")
    .all<{ name: string; sql: string }>();
  return new Map(results.map((r) => [r.name, r.sql]));
}

describe("migration 0003 (dataset scope) and 0004 cleanup", () => {
  it("backfills, stays compatible with the previous Worker, then cleans up", async () => {
    expect(all.map((m) => m.name)).toEqual(["0001_init.sql", "0002_self_hidden.sql", "0003_dataset_scope.sql", "0005_play.sql"]);
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

    await applyD1Migrations(db, upTo0003);

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

    // 0005 (Play) also applies when 0004 went first
    await applyD1Migrations(db, all);
    const after = (await db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all<{ name: string }>()).results.map((r) => r.name);
    for (const t of ["play_claims", "play_weeks", "play_totals", "play_attack_stretches"]) expect(after).toContain(t);
  });
});

describe("migration 0005 (play)", () => {
  // its own scratch database: storage is shared by the tests of this file, and the 0003 test above already ran 0004
  const db = env.MIGRATION_DB_PLAY;
  const one = async <T>(sql: string, ...args: unknown[]) => (await db.prepare(sql).bind(...args).first<T>())!;
  const count = async (sql: string, ...args: unknown[]) => (await one<{ n: number }>(sql, ...args)).n;
  const schema = () => schemaOf(db);

  it("0005 applies after 0003 and is additive", async () => {
    expect(play0005.map((m) => m.name)).toEqual(["0005_play.sql"]);
    await applyD1Migrations(db, upTo0003);
    await db.batch([
      db.prepare("INSERT INTO users (id, provider, sub, anon_name) VALUES ('p1', 'dev', 'ps1', 'Calm Otter 21') ON CONFLICT DO NOTHING"),
      db.prepare("INSERT INTO coverage VALUES ('p1', 'seg-000001', 'AQ==') ON CONFLICT DO NOTHING"),
    ]);
    const before = await schema();
    const rowsBefore = await count("SELECT COUNT(*) AS n FROM coverage");
    await applyD1Migrations(db, all);
    const after = await schema();
    // every object that existed is unchanged; the only new ones are Play's
    for (const [name, sql] of before) expect(after.get(name)).toBe(sql);
    const added = [...after.keys()].filter((n) => !before.has(n)).sort();
    expect(added).toEqual(PLAY_OBJECTS.filter((n) => !before.has(n)));
    expect(PLAY_OBJECTS.every((n) => after.has(n))).toBe(true);
    // no Play statement names a table or column that 0004 drops or clears
    const sql = play0005[0]!.queries.join("\n");
    for (const t of ["walks ", "walks(", "leaderboard_snapshot ", "trail_stats_snapshot", "snapshot_meta ", "island_m", "coverage_rev", "walk_count"]) {
      expect(sql).not.toContain(t);
    }
    expect(await count("SELECT COUNT(*) AS n FROM coverage")).toBe(rowsBefore);
    // the tables take a claim / week / totals row and enforce the dataset check
    await db.batch([
      db.prepare("INSERT INTO play_claims VALUES ('p1', 'trails', 'egg-station-s@2026-10-26', 'egg', '2026-10-26', '2026.11.0', 20) ON CONFLICT DO NOTHING"),
      db.prepare("INSERT INTO play_weeks (user_id, dataset, week, day_keys) VALUES ('p1', 'trails', '2026-10-26', 1) ON CONFLICT DO NOTHING"),
      db.prepare("INSERT INTO play_totals (user_id, dataset, total) VALUES ('p1', 'trails', 35) ON CONFLICT DO NOTHING"),
    ]);
    await expect(
      db.prepare("INSERT INTO play_claims VALUES ('p1', 'rivers', 'x@2026-10-26', 'egg', '2026-10-26', 'v', 0)").run(),
    ).rejects.toThrow(/CHECK constraint failed/);
  });

  it("0004 cleanup still applies after 0005", async () => {
    await applyD1Migrations(db, all);
    const tables = async () => (await db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all<{ name: string }>()).results.map((r) => r.name);
    await db.batch([
      db.prepare("INSERT INTO users (id, provider, sub, anon_name) VALUES ('p2', 'dev', 'ps2', 'Calm Otter 22') ON CONFLICT DO NOTHING"),
      db.prepare("INSERT INTO walks VALUES ('p2', 'w9') ON CONFLICT DO NOTHING"),
      db.prepare("INSERT INTO play_claims VALUES ('p2', 'roads', 'egg-madrone@2026-10-26', 'egg', '2026-10-26', '2026.11.0', 20) ON CONFLICT DO NOTHING"),
      db.prepare("INSERT INTO play_totals (user_id, dataset, found_marks, total) VALUES ('p2', 'roads', 20, 20) ON CONFLICT DO NOTHING"),
    ]);
    await db.batch(env.CLEANUP_QUERIES.map((q) => db.prepare(q)));
    const t = await tables();
    for (const n of ["walks", "leaderboard_snapshot", "trail_stats_snapshot", "snapshot_meta"]) expect(t).not.toContain(n);
    for (const n of ["play_claims", "play_weeks", "play_totals", "play_attack_stretches"]) expect(t).toContain(n);
    expect(await one("SELECT marks FROM play_claims WHERE user_id = 'p2'")).toEqual({ marks: 20 });
    expect(await one("SELECT total FROM play_totals WHERE user_id = 'p2'")).toEqual({ total: 20 });
    expect(await one("SELECT walk_id FROM walks_v2 WHERE user_id = 'p2'")).toEqual({ walk_id: "w9" });
  });
});
