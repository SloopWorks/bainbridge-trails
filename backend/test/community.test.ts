import { createExecutionContext, env } from "cloudflare:test";
import { handle } from "../src/index";
import { describe, expect, it } from "vitest";
import { communityCount } from "../src/routes/board";
import { resetPublicRateLimit } from "../src/ratelimit";
import { islandRank } from "../src/users";
import { ADMIN, META, bitsOf, devLogin, harness, idToken, rsaKey, syncWalk, trailFull, upload } from "./helpers";
type Entry = { rank: number; userId: string; displayName: string; islandPct: number; trailsCompleted: number; isYou: boolean };
type Board = { entries: Entry[]; you: Entry | null; totalRanked: number };

async function setMetrics(userId: string, islandM: number, trails: number, reachedAt: number) {
  await env.DB.prepare("UPDATE users SET island_m = ?, island_rank = ?, trails_completed = ?, reached_at = ? WHERE id = ?")
    .bind(islandM, islandRank(islandM), trails, reachedAt, userId)
    .run();
}

describe("leaderboard", () => {
  it("orders by island % (0.1 % floor) desc, trails desc, reached_at asc; hides 0% and hidden users; no timestamps", async () => {
    const h = harness();
    const u = await Promise.all(["lb-a", "lb-b", "lb-c", "lb-d", "lb-e", "lb-zero"].map((d) => devLogin(h, d)));
    const [a, b, c, d, e, zero] = u as [typeof u[0], typeof u[0], typeof u[0], typeof u[0], typeof u[0], typeof u[0]];
    await setMetrics(a.userId, 90_000, 1, 500); // most island
    // b, c, d all show 47.4 %: b has slightly fewer metres but more trails -> ahead (P1-4)
    await setMetrics(b.userId, 50_000, 5, 300);
    await setMetrics(c.userId, 50_010, 2, 100);
    await setMetrics(d.userId, 50_020, 2, 200); // same floored % and trails as c, reached later
    await setMetrics(e.userId, 95_000, 9, 1); // hidden below
    expect((await h.call("POST", `/v1/admin/users/${e.userId}/hide`, ADMIN)).status).toBe(200);
    expect((await h.call("POST", "/v1/admin/snapshot", ADMIN)).status).toBe(200);

    const res = await h.call("GET", "/v1/leaderboard?limit=100", { token: c.token });
    expect(res.status).toBe(200);
    const board = (await res.json()) as Board;
    const mine = board.entries.filter((x) => u.some((y) => y.userId === x.userId)).map((x) => x.userId);
    expect(mine).toEqual([a.userId, b.userId, c.userId, d.userId]);
    expect(mine).not.toContain(zero.userId);
    const ranks = board.entries.map((x) => x.rank);
    expect(ranks).toEqual(ranks.map((_, i) => i + 1));
    const you = board.entries.find((x) => x.isYou)!;
    expect(you.userId).toBe(c.userId);
    expect(board.you).toEqual(you);
    expect(board.entries.find((x) => x.userId === a.userId)!.islandPct).toBe(85.3); // 85.39 floored
    expect(Object.keys(you).sort()).toEqual(["displayName", "isYou", "islandPct", "rank", "trailsCompleted", "userId"]);
    expect(JSON.stringify(board)).not.toMatch(/reached|_at|At"/);
    expect(board.totalRanked).toBe(board.entries.length);

    const small = (await (await h.call("GET", "/v1/leaderboard?limit=1", { token: d.token })).json()) as Board;
    expect(small.entries).toHaveLength(1);
    const full = (await (await h.call("GET", "/v1/leaderboard", { token: d.token })).json()) as Board;
    expect(small.you!.rank).toBe(full.entries.find((x) => x.userId === d.userId)!.rank);
    expect(small.totalRanked).toBe(full.totalRanked);

    // TL-11: others see the snapshot; the caller's own `you` row is live
    await setMetrics(d.userId, 99_000, 2, 900);
    const live = (await (await h.call("GET", "/v1/leaderboard", { token: d.token })).json()) as Board;
    expect(live.you!.rank).toBe(1);
    expect(live.you!.islandPct).toBe(93.9);
    const seenByC = (await (await h.call("GET", "/v1/leaderboard", { token: c.token })).json()) as Board;
    expect(seenByC.entries.find((x) => x.userId === d.userId)!.islandPct).toBe(47.4);
    // hide and rename apply immediately
    await h.call("POST", `/v1/admin/users/${a.userId}/hide`, ADMIN);
    await h.call("PUT", "/v1/me", { token: b.token, body: { displayName: "Renamed Walker" } });
    const after = (await (await h.call("GET", "/v1/leaderboard")).json()) as Board;
    expect(after.entries.some((x) => x.userId === a.userId)).toBe(false);
    expect(after.entries.find((x) => x.userId === b.userId)!.displayName).toBe("Renamed Walker");
    expect(after.totalRanked).toBe(full.totalRanked - 1);

    const anon = (await (await h.call("GET", "/v1/leaderboard")).json()) as Board;
    expect(anon.you).toBeNull();
    expect(anon.entries.every((x) => !x.isYou)).toBe(true);
    expect((await h.call("GET", "/v1/leaderboard?limit=0")).status).toBe(400);
  });

  it("reached_at moves only when the floored island % rises (P1-4)", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h, "lb-reach");
    const seg = META.trails["trail-rotary-park-trails"]![0]!;
    const reached = async () =>
      (await env.DB.prepare("SELECT reached_at FROM users WHERE id = ?").bind(userId).first<{ reached_at: number }>())!.reached_at;
    await syncWalk(h, token, { [seg]: bitsOf([0]) });
    const r1 = await reached();
    expect(r1).toBe(h.clock.t);
    await syncWalk(h, token, { [seg]: bitsOf([0, 1, 2]) }); // more metres, still 0.0 %
    expect(await reached()).toBe(r1);
    await syncWalk(h, token, trailFull("trail-wharf-street-road-end")); // trails only
    expect(await reached()).toBe(r1);
  });

  it("public endpoints are rate limited per IP in memory (P1-6)", async () => {
    resetPublicRateLimit();
    const h = harness();
    let last = 0;
    for (let i = 0; i < 121; i++) last = (await h.call("GET", "/v1/trails/stats", { headers: { "cf-connecting-ip": "198.51.100.9" } })).status;
    expect(last).toBe(429);
    expect((await h.call("GET", "/v1/trails/stats", { headers: { "cf-connecting-ip": "198.51.100.10" } })).status).toBe(200);
    resetPublicRateLimit();
  });
});

describe("community trail stats", () => {
  it("snapshot-based; counts below 5 hidden, larger counts rounded to 5", async () => {
    const h = harness();
    const trail = "trail-upper-veterane-trail"; // 1 segment, 8 ticks
    const seg = META.trails[trail]![0]!;
    const users = await Promise.all(Array.from({ length: 8 }, (_, i) => devLogin(h, `st-${i}`)));
    const full = [0, 1, 2, 3, 4, 5, 6, 7];
    const ticks = [full, [0, 1], [0, 1, 2, 3], full, full, full, full, [0]];
    for (let i = 0; i < 4; i++) await syncWalk(h, users[i]!.token, { [seg]: bitsOf(ticks[i]!) });
    await h.call("POST", "/v1/admin/snapshot", ADMIN);

    type Stats = { minCount: number; trails: Record<string, { walkers: number | null; completedCount: number | null; medianPct: number | null }> };
    const r1 = await h.call("GET", "/v1/trails/stats");
    expect(r1.headers.get("cache-control")).toBe("public, max-age=300");
    const s1 = (await r1.json()) as Stats;
    expect(s1.minCount).toBe(5);
    expect(s1.trails[trail]).toEqual({ walkers: null, completedCount: null, medianPct: null });
    expect(Object.keys(s1.trails)).toHaveLength(Object.keys(META.trails).length);

    for (let i = 4; i < 8; i++) await syncWalk(h, users[i]!.token, { [seg]: bitsOf(ticks[i]!) });
    expect(((await (await h.call("GET", "/v1/trails/stats")).json()) as Stats).trails[trail]!.walkers).toBeNull();
    await h.call("POST", "/v1/admin/snapshot", ADMIN);
    const s2 = (await (await h.call("GET", "/v1/trails/stats")).json()) as Stats;
    expect(s2.trails[trail]).toEqual({ walkers: 10, completedCount: 5, medianPct: 100 }); // 8 -> 10, 5 -> 5
  });

  it("communityCount rounding", () => {
    expect([0, 4, 5, 6, 7, 8, 12, 13, 102].map(communityCount)).toEqual([null, null, 5, 5, 5, 10, 10, 15, 100]);
  });

  it("admin snapshot requires the admin token", async () => {
    expect((await harness().call("POST", "/v1/admin/snapshot")).status).toBe(403);
  });
});

describe("profile, moderation, deletion", () => {
  it("PUT /v1/me sets a validated name, null reverts to anon, max 5 changes/day", async () => {
    const h = harness();
    const { token } = await devLogin(h, "me-1");
    const put = (body: unknown) => h.call("PUT", "/v1/me", { token, body });
    const ok = await put({ displayName: "  Trail   Fox " });
    expect(ok.status).toBe(200);
    expect(await ok.json()).toMatchObject({ displayName: "Trail Fox", isAnonymous: false, nameStatus: "ok" });
    const bad = await put({ displayName: "sh1t head" });
    expect(bad.status).toBe(400);
    expect(((await bad.json()) as { error: { code: string } }).error.code).toBe("name_rejected");
    const anon = (await (await put({ displayName: null })).json()) as { displayName: string; anonName: string; isAnonymous: boolean };
    expect(anon.isAnonymous).toBe(true);
    expect(anon.displayName).toBe(anon.anonName);
    expect((await put({ anonName: "Calm Otter 77" })).status).toBe(200);
    expect((await put({ anonName: "Calm Badger 77" })).status).toBe(400);
    for (const n of ["Name Two", "Name Three", "Name Four", "Name Five"]) expect((await put({ displayName: n })).status).toBe(200);
    const limited = await put({ displayName: "Name Six" });
    expect(limited.status).toBe(429);
    expect(((await limited.json()) as { error: { code: string } }).error.code).toBe("name_rate_limited");
  });

  it("reports only flag for admin review; admin reset-name / dismiss-reports", async () => {
    const h = harness();
    const target = await devLogin(h, "rep-target");
    await h.call("PUT", "/v1/me", { token: target.token, body: { displayName: "Questionable" } });
    const reporters = await Promise.all(["rep-1", "rep-2", "rep-3"].map((d) => devLogin(h, d)));
    const report = (t: string) => h.call("POST", "/v1/report-name", { token: t, body: { userId: target.userId } });
    expect((await report(reporters[0]!.token)).status).toBe(202);
    expect((await report(reporters[0]!.token)).status).toBe(202); // duplicate from same user doesn't count
    await report(reporters[1]!.token);
    await report(reporters[2]!.token);
    type Me = { displayName: string; nameStatus: string; anonName: string };
    let me = (await (await h.call("GET", "/v1/me", { token: target.token })).json()) as Me;
    expect(me.displayName).toBe("Questionable"); // no auto-hide (cut)
    expect((await report(target.token)).status).toBe(400); // self
    expect((await h.call("POST", "/v1/report-name", { body: { userId: target.userId } })).status).toBe(401);
    expect((await h.call("POST", "/v1/report-name", { token: reporters[0]!.token, body: { userId: "nope" } })).status).toBe(404);

    expect((await h.call("GET", "/v1/admin/reports")).status).toBe(403);
    expect((await h.call("GET", "/v1/admin/reports", { token: "wrong-token-xxxxxxxxxxxx" })).status).toBe(403);
    type Reports = { reports: { userId: string; reports: number }[] };
    const list = (await (await h.call("GET", "/v1/admin/reports", ADMIN)).json()) as Reports;
    expect(list.reports.find((r) => r.userId === target.userId)!.reports).toBe(3);
    expect((await h.call("POST", `/v1/admin/users/${target.userId}/dismiss-reports`, ADMIN)).status).toBe(200);
    const list2 = (await (await h.call("GET", "/v1/admin/reports", ADMIN)).json()) as Reports;
    expect(list2.reports.some((r) => r.userId === target.userId)).toBe(false);
    expect((await h.call("POST", `/v1/admin/users/${target.userId}/reset-name`, ADMIN)).status).toBe(200);
    me = (await (await h.call("GET", "/v1/me", { token: target.token })).json()) as Me;
    expect(me.nameStatus).toBe("anonymous");
    expect(me.displayName).toBe(me.anonName);
    expect((await h.call("POST", `/v1/admin/users/00000000-0000-0000-0000-000000000000/hide`, ADMIN)).status).toBe(404);
  });

  it("DELETE /v1/me removes every row for the user", async () => {
    const h = harness();
    const u = await devLogin(h, "del-me");
    const other = await devLogin(h, "del-other");
    await syncWalk(h, u.token, trailFull("trail-rotary-park-trails"));
    await h.call("POST", "/v1/admin/snapshot", ADMIN);
    await h.call("POST", "/v1/report-name", { token: u.token, body: { userId: other.userId } });
    await h.call("POST", "/v1/report-name", { token: other.token, body: { userId: u.userId } });
    expect((await h.call("DELETE", "/v1/me", { token: u.token })).status).toBe(204);
    for (const [table, col] of [
      ["users", "id"],
      ["sessions", "user_id"],
      ["coverage", "user_id"],
      ["user_trails", "user_id"],
      ["name_reports", "reporter_id"],
      ["name_reports", "target_id"],
      ["user_completed", "user_id"],
      ["walks", "user_id"],
      ["leaderboard_snapshot", "user_id"],
    ] as const) {
      const r = await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${table} WHERE ${col} = ?`).bind(u.userId).first<{ n: number }>();
      expect(r!.n, `${table}.${col}`).toBe(0);
    }
    const keys = await env.DB.prepare("SELECT COUNT(*) AS n FROM rate_limits WHERE key LIKE ?").bind(`%${u.userId}`).first<{ n: number }>();
    expect(keys!.n).toBe(0);
    expect((await h.call("GET", "/v1/me", { token: u.token })).status).toBe(401);
    // signing in again creates a fresh account
    const again = await devLogin(h, "del-me");
    expect(again.userId).not.toBe(u.userId);
  });

  it("revokes the Apple refresh token on deletion when Apple credentials are configured (TL-10)", async () => {
    const { exportPKCS8, generateKeyPair } = await import("jose");
    const { privateKey } = await generateKeyPair("ES256", { extractable: true });
    const appleEnv = { ...env, APPLE_TEAM_ID: "TEAM123456", APPLE_KEY_ID: "KEY1234567", APPLE_PRIVATE_KEY: await exportPKCS8(privateKey) } as Env;
    const key = await rsaKey("apple-k");
    const h = harness({ keys: [key.jwk] });
    const call = async (method: string, path: string, token?: string, body?: unknown) =>
      handle(
        new Request(`http://localhost:8787${path}`, {
          method,
          headers: token ? { authorization: `Bearer ${token}` } : {},
          ...(body ? { body: JSON.stringify(body) } : {}),
        }),
        appleEnv,
        createExecutionContext(),
        h.deps,
      );
    const idt = await idToken(key, { iss: "https://appleid.apple.com", aud: "com.sloopworks.bainbridgetrails", sub: "apple-del" }, h.clock.t);
    const login = await call("POST", "/v1/auth/apple", undefined, { idToken: idt, authorizationCode: "c0de" });
    expect(login.status).toBe(200);
    const { token } = (await login.json()) as { token: string };
    expect(h.outbound[0]!.url).toBe("https://appleid.apple.com/auth/token");
    expect(h.outbound[0]!.body).toContain("code=c0de");
    expect((await call("DELETE", "/v1/me", token)).status).toBe(204);
    expect(h.outbound[1]!.url).toBe("https://appleid.apple.com/auth/revoke");
    expect(h.outbound[1]!.body).toContain("token=apple-refresh-1");
    expect(h.outbound[1]!.body).toContain("client_id=com.sloopworks.bainbridgetrails");
  });

  it("Apple revoke is a no-op without credentials", async () => {
    const key = await rsaKey("apple-k2");
    const h = harness({ keys: [key.jwk] });
    const idt = await idToken(key, { iss: "https://appleid.apple.com", aud: "com.sloopworks.bainbridgetrails", sub: "apple-nocreds" }, h.clock.t);
    const { token } = (await (await h.call("POST", "/v1/auth/apple", { body: { idToken: idt, authorizationCode: "c" } })).json()) as { token: string };
    expect((await h.call("DELETE", "/v1/me", { token })).status).toBe(204);
    expect(h.outbound).toHaveLength(0);
  });

  it("serves the public delete-account page", async () => {
    const r = await harness().call("GET", "/delete-account");
    expect(r.status).toBe(200);
    expect(r.headers.get("content-type")).toContain("text/html");
    expect(await r.text()).toContain("Delete account");
  });

  it("unknown routes and methods return JSON errors", async () => {
    const h = harness();
    const r = await h.call("GET", "/v2/nope");
    expect(r.status).toBe(404);
    expect(await r.json()).toEqual({ error: { code: "not_found", message: "no such endpoint" } });
    const { token } = await devLogin(h);
    expect((await h.call("POST", "/v1/me", { token })).status).toBe(405);
  });
});

describe("cron trigger", () => {
  it("scheduled() purges housekeeping rows, recomputes stale metrics and rebuilds the snapshots", async () => {
    const { createScheduledController } = await import("cloudflare:test");
    const worker = (await import("../src/index")).default;
    const h = harness();
    const { token, userId } = await devLogin(h, "cron-user");
    await syncWalk(h, token, trailFull("trail-rotary-park-trails"));
    await env.DB.prepare("UPDATE users SET metrics_version = 'old', island_m = 0 WHERE id = ?").bind(userId).run();
    await env.DB.prepare("INSERT INTO rate_limits (key, window_start, count) VALUES ('auth:old', 1, 1)").run();
    await env.DB.prepare("INSERT INTO daily_salt (day, salt) VALUES ('2000-01-01', 'x')").run();
    await env.DB.prepare("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ('dead', ?, 1)").bind(userId).run();
    await env.DB.prepare("UPDATE snapshot_meta SET dataset_version = 'old'").run();

    const t = Date.UTC(2026, 9, 20, 9, 17);
    await worker.scheduled(createScheduledController({ scheduledTime: new Date(t), cron: "17 9 * * *" }), env);
    const one = async (sql: string) => (await env.DB.prepare(sql).first<{ n: number }>())!.n;
    expect(await one("SELECT COUNT(*) AS n FROM rate_limits WHERE key = 'auth:old'")).toBe(0);
    expect(await one("SELECT COUNT(*) AS n FROM daily_salt WHERE day = '2000-01-01'")).toBe(0);
    expect(await one("SELECT COUNT(*) AS n FROM sessions WHERE token_hash = 'dead'")).toBe(0);
    const u = await env.DB.prepare("SELECT metrics_version, island_m FROM users WHERE id = ?").bind(userId).first<{ metrics_version: string; island_m: number }>();
    expect(u!.metrics_version).toBe(META.version);
    expect(u!.island_m).toBeGreaterThan(30);
    const meta = await env.DB.prepare("SELECT generated_at, dataset_version FROM snapshot_meta WHERE id = 1").first<{ generated_at: number; dataset_version: string }>();
    expect(meta).toEqual({ generated_at: t, dataset_version: META.version });
  });
});
