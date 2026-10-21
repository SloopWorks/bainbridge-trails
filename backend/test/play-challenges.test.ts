import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { daily } from "../src/index";
import { playDaily } from "../src/play/challenges";
import { CURRENT } from "../src/play/config";
import { type Harness, devLogin, harness, trailFull, upload } from "./helpers";

/**
 * L3-3: kraken aggregates, challenges and the daily cron (schedule.md § 7.3, § 7.4; product § 3.2 W-WEEK, § 3.4,
 * § 4.3.2, § 4.5; PD-3, PD-4). Fixtures: pack 2026.11.0 (trails goal), whose kraken rotation is coast-t4 (2 stretches)
 * on 2026-10-29, coast-t2 (2) on 2026-11-05, coast-t3 (3) on 2026-11-12.
 */

const PACK = CURRENT.version;
const pdt = (y: number, mo: number, d: number, h: number) => Date.UTC(y, mo - 1, d, h + 7);
const pst = (y: number, mo: number, d: number, h: number) => Date.UTC(y, mo - 1, d, h + 8);

const WEEK = "2026-10-26";
const KRAKEN0 = "kraken-trails@2026-10-29#0"; // coast-t4 stretch 0 = trail-fairy-dell-trail
const FAIRY = "trail-fairy-dell-trail";
const SWEEP = "ch-sweep@2026-10-31";

type Claim = { id: string; kind: string; weekId: string; band?: number };
type PlayRes = { accepted: string[]; rejected: { id: string; code: string }[]; capped: string[]; total: number; week: number };

const rows = async <T>(sql: string, ...args: unknown[]) => (await env.DB.prepare(sql).bind(...args).all<T>()).results;
const one = async <T>(sql: string, ...args: unknown[]) => (await env.DB.prepare(sql).bind(...args).first<T>())!;
const total = async (userId: string, dataset = "trails") =>
  (await env.DB.prepare("SELECT total FROM play_totals WHERE user_id = ? AND dataset = ?").bind(userId, dataset).first<{ total: number }>())?.total ?? 0;

let h: Harness;

beforeEach(() => {
  h = harness();
  h.clock.t = pdt(2026, 10, 29, 14); // Thu 14:00 PDT: kraken attack 2026-10-29 open
});

/** One Play upload crediting one new 1-hour walk (clock +1 h). */
async function syncPlay(token: string, segments: Record<string, string>, claims: Claim[] = []): Promise<PlayRes> {
  h.clock.advanceHours(1);
  const walkId = crypto.randomUUID();
  const res = await h.call("PUT", "/v1/coverage", {
    token,
    body: { ...upload(segments, [{ id: walkId, durationS: 3600 }]), playConfig: PACK, play: [{ walkId, claims }] },
  });
  expect(res.status).toBe(200);
  return ((await res.json()) as { play: PlayRes }).play;
}

/** A signed-in user with a Play totals row (every Play user has one after their first Play upload). */
async function player(dataset = "trails"): Promise<{ token: string; userId: string }> {
  const u = await devLogin(h);
  await env.DB.prepare("INSERT INTO play_totals (user_id, dataset) VALUES (?, ?) ON CONFLICT DO NOTHING").bind(u.userId, dataset).run();
  return u;
}

/** Seeds an attack's stretch aggregate and its island-challenge contributions. */
async function seedAttack(date: string, counts: number[], contributors: { userId: string; stretches: number }[]) {
  const stmts = counts.map((n, i) =>
    env.DB.prepare(
      "INSERT INTO play_attack_stretches (dataset, occ, repelled_by) VALUES ('trails', ?, ?) ON CONFLICT (dataset, occ) DO UPDATE SET repelled_by = excluded.repelled_by",
    ).bind(`kraken-trails@${date}#${i}`, n),
  );
  for (const c of contributors) {
    stmts.push(
      env.DB.prepare("INSERT INTO play_progress (user_id, dataset, occ, progress) VALUES (?, 'trails', ?, ?)").bind(
        c.userId,
        `ch-krakenweek@${date}`,
        c.stretches,
      ),
    );
  }
  await env.DB.batch(stmts);
}

const islandClaims = async (userId: string) =>
  rows<{ occ: string; kind: string; week: string; marks: number }>(
    "SELECT occ, kind, week, marks FROM play_claims WHERE user_id = ? AND (occ LIKE 'ch-krakenweek@%' OR occ NOT LIKE '%#%') ORDER BY occ",
    userId,
  );

describe("kraken aggregates", () => {
  it("stretch counted once per user", async () => {
    const a = await devLogin(h);
    const b = await devLogin(h);
    const ink = { id: KRAKEN0, kind: "ink", weekId: WEEK, band: 95 };
    const repelled = async () => (await one<{ n: number } | null>("SELECT repelled_by AS n FROM play_attack_stretches WHERE dataset = 'trails' AND occ = ?", KRAKEN0))?.n ?? 0;
    const base = await repelled();

    expect((await syncPlay(a.token, trailFull(FAIRY), [ink])).accepted).toEqual([KRAKEN0]);
    expect(await repelled()).toBe(base + 1);
    // the same claim again (a re-sent element, a later walk over the same stretch): a repeat, not a second count
    expect((await syncPlay(a.token, {}, [ink])).accepted).toEqual([KRAKEN0]);
    expect((await syncPlay(a.token, trailFull(FAIRY), [ink, { ...ink, band: 60 }])).accepted).toEqual([KRAKEN0, KRAKEN0]);
    expect(await repelled()).toBe(base + 1);
    // a rejected claim never counts
    const p = await syncPlay(b.token, {}, [ink]);
    expect(p.rejected).toEqual([{ id: KRAKEN0, code: "no_bits" }]);
    expect(await repelled()).toBe(base + 1);
    // another user does
    expect((await syncPlay(b.token, trailFull(FAIRY), [ink])).accepted).toEqual([KRAKEN0]);
    expect(await repelled()).toBe(base + 2);
    // each user's contribution to the island challenge: one stretch of this attack
    for (const u of [a, b]) {
      expect(await rows("SELECT occ, progress FROM play_progress WHERE user_id = ?", u.userId)).toEqual([
        { occ: "ch-krakenweek@2026-10-29", progress: 1 },
      ]);
    }
    // account deletion removes the user's contributions; the island aggregate is a count with no user in it
    expect((await h.call("DELETE", "/v1/me", { token: a.token })).status).toBe(204);
    expect(await rows("SELECT * FROM play_progress WHERE user_id = ?", a.userId)).toEqual([]);
    expect(await repelled()).toBe(base + 2);
  });

  it("retreat only when every stretch ≥ 3", async () => {
    const p1 = await player();
    const p2 = await player();
    const bystander = await player();
    const before = { p1: await total(p1.userId), p2: await total(p2.userId), by: await total(bystander.userId) };
    // attack 2026-11-05 (coast-t2: 2 stretches), Thu 06:00 → Sun 2026-11-08 20:00; claims accepted through Tue 11-10
    await seedAttack("2026-11-05", [3, 2], [
      { userId: p1.userId, stretches: 2 },
      { userId: p2.userId, stretches: 1 },
    ]);
    // after the grace: one stretch short of 3 players → the kraken slipped away, nobody is credited
    await playDaily(env, pst(2026, 11, 11, 2));
    expect(await islandClaims(p1.userId)).toEqual([]);
    expect(await total(p1.userId)).toBe(before.p1);

    // every stretch ≥ 3, but claims are still accepted (Tue): not closed yet
    await seedAttack("2026-11-05", [3, 3], []);
    await playDaily(env, pst(2026, 11, 10, 2));
    expect(await islandClaims(p1.userId)).toEqual([]);

    // closed with every stretch ≥ 3: F-RETREAT +50 and C-ISLAND +75 to every contributor, in the attack's week
    await playDaily(env, pst(2026, 11, 11, 2));
    for (const [u, t0] of [
      [p1, before.p1],
      [p2, before.p2],
    ] as const) {
      expect(await islandClaims(u.userId)).toEqual([
        { occ: "ch-krakenweek@2026-11-05", kind: "challenge", week: "2026-11-02", marks: 75 },
        { occ: "kraken-trails@2026-11-05", kind: "ink", week: "2026-11-02", marks: 50 },
      ]);
      expect(await total(u.userId)).toBe(t0 + 125);
      expect(await one("SELECT found_marks FROM play_weeks WHERE user_id = ? AND week = '2026-11-02'", u.userId)).toEqual({ found_marks: 125 });
    }
    expect(await islandClaims(bystander.userId)).toEqual([]);
    expect(await total(bystander.userId)).toBe(before.by);
    // the Logbook restore lists them (re-marked found, never claimed again)
    const me = (await (await h.call("GET", "/v1/play/me?dataset=trails", { token: p1.token })).json()) as { occ: string[] };
    expect(me.occ).toEqual(["ch-krakenweek@2026-11-05", "kraken-trails@2026-11-05"]);
  });

  it("cron twice same credits", async () => {
    const p = await player();
    // attack 2026-11-12 (coast-t3: 3 stretches) repelled; week 2026-11-09 kept (2 walking days)
    await seedAttack("2026-11-12", [4, 3, 5], [{ userId: p.userId, stretches: 3 }]);
    await env.DB.prepare("INSERT INTO play_weeks (user_id, dataset, week, day_keys) VALUES (?, 'trails', '2026-11-09', 2)").bind(p.userId).run();
    const t0 = await total(p.userId);
    const at = Date.UTC(2026, 10, 18, 9, 17); // Wed 2026-11-18 09:17 UTC (01:17 PST), the cron's time
    // two runs at once (a retried cron overlapping the first): every credit is guarded by the row that records it
    await Promise.all([playDaily(env, at), playDaily(env, at)]);
    const snapshot = async () => ({
      total: await total(p.userId),
      totals: await rows("SELECT * FROM play_totals WHERE user_id = ?", p.userId),
      claims: await rows("SELECT * FROM play_claims WHERE user_id = ? ORDER BY occ", p.userId),
      weeks: await rows("SELECT * FROM play_weeks WHERE user_id = ? ORDER BY week", p.userId),
    });
    const first = await snapshot();
    expect(first.total).toBe(t0 + 50 + 75 + 40);
    expect(first.weeks).toEqual([
      { user_id: p.userId, dataset: "trails", week: "2026-11-09", day_keys: 2, found_marks: 125, kept: 40 },
    ]);
    // the scheduled handler's daily() runs it again: nothing more
    await daily(env, at);
    await daily(env, at + 60_000);
    expect(await snapshot()).toEqual(first);
  });
});

describe("server-only credits in GET /v1/play/me log", () => {
  type Me = { log: { week: string; kind: string; marks: number }[]; occ: string[]; total: number };
  const me = async (token: string, dataset = "trails") => {
    const res = await h.call("GET", `/v1/play/me?dataset=${dataset}`, { token });
    expect(res.status).toBe(200);
    return (await res.json()) as Me;
  };

  // P0-2: W-WEEK, F-RETREAT and C-ISLAND reach the Logbook
  it("closed W-WEEK week listed once; second call identical", async () => {
    const p = await player();
    // a client-claimed stretch of the attack (kind ink with #n) is not a server-only credit
    expect((await syncPlay(p.token, trailFull(FAIRY), [{ id: KRAKEN0, kind: "ink", weekId: WEEK, band: 95 }])).accepted).toEqual([KRAKEN0]);
    expect((await me(p.token)).log).toEqual([]);
    await seedAttack("2026-11-12", [4, 3, 5], [{ userId: p.userId, stretches: 3 }]);
    await env.DB.batch([
      env.DB.prepare("INSERT INTO play_weeks (user_id, dataset, week, day_keys) VALUES (?, 'trails', '2026-11-09', 2)").bind(p.userId),
      // one walking day only: closes as not kept (kept = 0), never listed
      env.DB.prepare("INSERT INTO play_weeks (user_id, dataset, week, day_keys) VALUES (?, 'trails', '2026-11-16', 1)").bind(p.userId),
    ]);
    const at = Date.UTC(2026, 10, 18, 9, 17);
    await playDaily(env, at);
    const expected = [
      { week: "2026-11-09", kind: "island_challenge", marks: 75 },
      { week: "2026-11-09", kind: "retreat", marks: 50 },
      { week: "2026-11-09", kind: "week", marks: 40 },
    ];
    const first = await me(p.token);
    expect(first.log).toEqual(expected);
    // the cron again, and the week after (2026-11-16 closes as not kept): nothing new
    await playDaily(env, at);
    await playDaily(env, Date.UTC(2026, 10, 30, 9, 17));
    expect(await one("SELECT kept FROM play_weeks WHERE user_id = ? AND week = '2026-11-16'", p.userId)).toEqual({ kept: 0 });
    const second = await me(p.token);
    expect(second.log).toEqual(expected);
    expect(second).toEqual(first);
    // per goal
    expect((await me(p.token, "roads")).log).toEqual([]);
  });

  it("capped server credit listed with 0 marks", async () => {
    const p = await player();
    await seedAttack("2026-11-12", [4, 3, 5], [{ userId: p.userId, stretches: 3 }]);
    await env.DB.prepare("INSERT INTO play_weeks (user_id, dataset, week, found_marks) VALUES (?, 'trails', '2026-11-09', 2740)").bind(p.userId).run();
    await playDaily(env, Date.UTC(2026, 10, 18, 9, 17));
    // F-RETREAT +50 still fits under 2,800 (2,790); C-ISLAND +75 does not: stored and listed with 0
    expect((await me(p.token)).log).toEqual([
      { week: "2026-11-09", kind: "island_challenge", marks: 0 },
      { week: "2026-11-09", kind: "retreat", marks: 50 },
    ]);
  });
});

describe("W-WEEK", () => {
  it("W-WEEK from day_keys ≥ 2", async () => {
    const p = await player("roads");
    // weeks of 2026-09 / 10 (roads goal, an unused range): kept, kept, one day, (no row), kept × 8, then open weeks
    const weeks: [string, number][] = [
      ["2026-08-03", 2],
      ["2026-08-10", 3],
      ["2026-08-17", 1],
      // 2026-08-24: no walk with Play at all
      ["2026-08-31", 2],
      ["2026-09-07", 7],
      ["2026-09-14", 2],
      ["2026-09-21", 2],
      ["2026-09-28", 2],
      ["2026-10-05", 2],
      ["2026-10-12", 2],
      ["2026-10-19", 1], // closed, still inside the dayKey grace (week end + 7 days)
      ["2026-10-26", 4], // current week: not closed
    ];
    await env.DB.batch(
      weeks.map(([w, k]) =>
        env.DB.prepare("INSERT INTO play_weeks (user_id, dataset, week, day_keys) VALUES (?, 'roads', ?, ?)").bind(p.userId, w, k),
      ),
    );
    const t0 = await total(p.userId, "roads");
    await playDaily(env, pdt(2026, 10, 29, 2)); // Thu 2026-10-29
    const kept = Object.fromEntries(
      (await rows<{ week: string; kept: number | null }>("SELECT week, kept FROM play_weeks WHERE user_id = ? AND dataset = 'roads'", p.userId)).map(
        (r) => [r.week, r.kept],
      ),
    );
    // +40, +10 for each consecutive kept week before it, max +100; a missed week resets the run to +40
    expect(kept).toEqual({
      "2026-08-03": 40,
      "2026-08-10": 50,
      "2026-08-17": 0,
      "2026-08-31": 40,
      "2026-09-07": 50,
      "2026-09-14": 60,
      "2026-09-21": 70,
      "2026-09-28": 80,
      "2026-10-05": 90,
      "2026-10-12": 100,
      "2026-10-19": null,
      "2026-10-26": null,
    });
    const paid = 40 + 50 + 40 + 50 + 60 + 70 + 80 + 90 + 100;
    expect(await total(p.userId, "roads")).toBe(t0 + paid);
    expect(await one("SELECT week_marks FROM play_totals WHERE user_id = ? AND dataset = 'roads'", p.userId)).toEqual({ week_marks: paid });

    // a late dayKey makes 2026-10-19 kept within its grace; the run continues from 2026-10-12 (+100, the max)
    await env.DB.prepare("UPDATE play_weeks SET day_keys = 2 WHERE user_id = ? AND dataset = 'roads' AND week = '2026-10-19'").bind(p.userId).run();
    await playDaily(env, pdt(2026, 10, 30, 2));
    expect(await one("SELECT kept FROM play_weeks WHERE user_id = ? AND dataset = 'roads' AND week = '2026-10-19'", p.userId)).toEqual({ kept: 100 });
    // one walking day only, after the grace (2026-11-09 onwards): closed as not kept
    await env.DB.prepare("UPDATE play_weeks SET day_keys = 1 WHERE user_id = ? AND dataset = 'roads' AND week = '2026-10-26'").bind(p.userId).run();
    await playDaily(env, pst(2026, 11, 8, 23));
    expect(await one("SELECT kept FROM play_weeks WHERE user_id = ? AND dataset = 'roads' AND week = '2026-10-26'", p.userId)).toEqual({ kept: null });
    await playDaily(env, pst(2026, 11, 9, 2));
    expect(await one("SELECT kept FROM play_weeks WHERE user_id = ? AND dataset = 'roads' AND week = '2026-10-26'", p.userId)).toEqual({ kept: 0 });
    expect(await total(p.userId, "roads")).toBe(t0 + paid + 100);
  });
});

describe("challenges", () => {
  const SHORT = ["trail-rotary-park-trails", "trail-upper-veterane-trail", "trail-sound-to-olympics-trail-2", "trail-battle-point-park-trail", "trail-wharf-street-road-end", "trail-lower-veterane-trail"];
  const sweep = { id: SWEEP, kind: "challenge", weekId: WEEK };

  it("ch-sweep accepted only with enough new completions in window", async () => {
    const u = await devLogin(h);
    const done = async () => (await one<{ progress: number } | null>("SELECT progress FROM play_progress WHERE user_id = ? AND occ = ?", u.userId, SWEEP))?.progress ?? 0;
    // Fri 2026-10-30: a completion before the window does not count
    h.clock.t = pdt(2026, 10, 30, 12);
    await syncPlay(u.token, trailFull(SHORT[0]!));
    expect(await done()).toBe(0);
    // Sat: the first completion inside the window
    h.clock.t = pdt(2026, 10, 31, 9);
    let p = await syncPlay(u.token, trailFull(SHORT[1]!));
    expect(await done()).toBe(1);
    // a pure upload (Play off) completes a trail but is not a Play contribution
    h.clock.advanceHours(1);
    const pure = await h.call("PUT", "/v1/coverage", { token: u.token, body: upload(trailFull(SHORT[2]!), [{ id: crypto.randomUUID(), durationS: 3600 }]) });
    expect(pure.status).toBe(200);
    expect(await done()).toBe(1);
    p = await syncPlay(u.token, trailFull(SHORT[3]!), [sweep]);
    expect(p.rejected).toEqual([{ id: SWEEP, code: "no_bits" }]);
    expect(await done()).toBe(2);
    // Sun: the third completion inside the window, on the walk that carries the claim → +100
    h.clock.t = pdt(2026, 11, 1, 12);
    const t0 = await total(u.userId);
    p = await syncPlay(u.token, trailFull(SHORT[4]!), [sweep]);
    expect(p.accepted).toEqual([SWEEP]);
    expect(await done()).toBe(3);
    expect(await one("SELECT kind, week, marks FROM play_claims WHERE user_id = ? AND occ = ?", u.userId, SWEEP)).toEqual({
      kind: "challenge",
      week: WEEK,
      marks: 100,
    });
    expect(p.total).toBeGreaterThanOrEqual(t0 + 100);
    // repeat: a no-op that still reports accepted
    expect((await syncPlay(u.token, {}, [sweep])).accepted).toEqual([SWEEP]);

    // a second user: completions in the grace (Tue) count, from Thu they do not; the claim is accepted late
    const v = await devLogin(h);
    const vDone = async () => (await one<{ progress: number } | null>("SELECT progress FROM play_progress WHERE user_id = ? AND occ = ?", v.userId, SWEEP))?.progress ?? 0;
    h.clock.t = pst(2026, 11, 3, 12);
    await syncPlay(v.token, { ...trailFull(SHORT[0]!), ...trailFull(SHORT[1]!) });
    expect(await vDone()).toBe(2);
    h.clock.t = pst(2026, 11, 5, 12);
    p = await syncPlay(v.token, trailFull(SHORT[5]!), [sweep]);
    expect(p.rejected).toEqual([{ id: SWEEP, code: "no_bits" }]);
    expect(await vDone()).toBe(2);
    // the island challenge is credited by the cron only: a client claim for it is not a claimable item
    h.clock.t = pdt(2026, 10, 31, 12);
    const w = await devLogin(h);
    p = await syncPlay(w.token, trailFull(FAIRY), [{ id: "ch-krakenweek@2026-10-29", kind: "challenge", weekId: WEEK }]);
    expect(p.rejected).toEqual([{ id: "ch-krakenweek@2026-10-29", code: "unknown_item" }]);
  });

  // P0-1 (server side): the client claims once its completions over several walks reach the target
  it("sweep claim accepted at target, rejected below", async () => {
    const u = await devLogin(h);
    h.clock.t = pdt(2026, 10, 31, 9); // Sat: window open
    // walk 1: two completions; a claim below the target is rejected
    let p = await syncPlay(u.token, { ...trailFull(SHORT[0]!), ...trailFull(SHORT[1]!) }, [sweep]);
    expect(p.rejected).toEqual([{ id: SWEEP, code: "no_bits" }]);
    // walk 2: the third completion, no claim on it
    h.clock.t = pdt(2026, 10, 31, 15);
    p = await syncPlay(u.token, trailFull(SHORT[2]!));
    expect(await one("SELECT progress FROM play_progress WHERE user_id = ? AND occ = ?", u.userId, SWEEP)).toEqual({ progress: 3 });
    // walk 3 completes nothing new and carries the claim: the stored contributions reach the target → +100
    h.clock.t = pdt(2026, 11, 1, 10);
    p = await syncPlay(u.token, {}, [sweep]);
    expect(p.accepted).toEqual([SWEEP]);
    expect(p.capped).toEqual([]);
    expect(await one("SELECT kind, week, marks FROM play_claims WHERE user_id = ? AND occ = ?", u.userId, SWEEP)).toEqual({
      kind: "challenge",
      week: WEEK,
      marks: 100,
    });
    // a solo challenge is client-claimed: never in the server-only log
    const me = (await (await h.call("GET", "/v1/play/me?dataset=trails", { token: u.token })).json()) as { log: unknown[]; perKind: { challenge: number } };
    expect(me.log).toEqual([]);
    expect(me.perKind.challenge).toBe(1);
  });

  it("response has no names scores ranks or timestamps", async () => {
    h.clock.t = pdt(2026, 10, 31, 12); // Sat: weekend sweep open, kraken attack 2026-10-29 open
    const u = await devLogin(h);
    await h.call("PUT", "/v1/me", { token: u.token, body: { displayName: "Harbor Heron" } });
    // completes one trail (sweep 1 of 3) and repels one stretch of 2
    await syncPlay(u.token, trailFull(FAIRY), [{ id: KRAKEN0, kind: "ink", weekId: WEEK, band: 95 }]);

    const anon = await h.call("GET", "/v1/play/challenges?dataset=trails");
    expect(anon.status).toBe(200);
    expect(anon.headers.get("cache-control")).toBe("public, max-age=300");
    const signed = await h.call("GET", "/v1/play/challenges?dataset=trails", { token: u.token });
    expect(signed.status).toBe(200);
    expect(signed.headers.get("cache-control")).toBe("private, no-store");
    const text = await signed.text();
    const body = JSON.parse(text) as { dataset: string; minCount: number; current: Record<string, unknown>[] };

    expect(body.dataset).toBe("trails");
    expect(body.minCount).toBe(5);
    expect(body.current.map((c) => c.occ)).toEqual(["ch-krakenweek@2026-10-29", "ch-sweep@2026-10-31"]);
    const [kraken, sw] = body.current;
    expect(kraken).toMatchObject({
      id: "ch-krakenweek",
      occ: "ch-krakenweek@2026-10-29",
      scope: "island",
      windowStart: "2026-10-29T06:00-07:00",
      windowEnd: "2026-11-01T20:00-08:00",
      open: true,
      progress: 1,
      target: 2,
      you: { band: "middle" },
      island: { stretches: 2, repelled: expect.any(Number) },
    });
    expect(Object.keys(kraken!).sort()).toEqual(["bands", "id", "island", "occ", "open", "progress", "scope", "target", "windowEnd", "windowStart", "you"]);
    for (const c of body.current) {
      const b = c.bands as { band: string; count: number | null }[];
      expect(b.map((x) => x.band)).toEqual(["top", "middle", "starting"]);
      for (const x of b) expect(x.count === null || (x.count >= 5 && x.count % 5 === 0)).toBe(true);
    }
    expect(sw).toMatchObject({ id: "ch-sweep", scope: "solo", windowStart: "2026-10-31T00:00-07:00", windowEnd: "2026-11-02T00:00-08:00", open: true, progress: 1, target: 3, you: { band: "starting" } });
    expect(Object.keys(sw!).sort()).toEqual(["bands", "id", "occ", "open", "progress", "scope", "target", "windowEnd", "windowStart", "you"]);

    // no names, no scores, no ranks, no user ids, no per-user times: only the schedule's window bounds are times
    const keys = new Set<string>();
    const strings: string[] = [];
    const walk = (v: unknown, key: string) => {
      if (Array.isArray(v)) v.forEach((x) => walk(x, key));
      else if (v !== null && typeof v === "object") for (const [k, x] of Object.entries(v)) (keys.add(k), walk(x, k));
      else if (typeof v === "string" && /\d{4}-\d{2}-\d{2}T/.test(v)) strings.push(`${key}=${v}`);
    };
    walk(body, "");
    for (const k of keys) expect(k).not.toMatch(/name|user|score|marks|total|rank|ord|time|date|At$|created|updated/i);
    expect(strings.every((s) => /^window(Start|End)=/.test(s))).toBe(true);
    expect(text).not.toContain("Harbor Heron");
    expect(text).not.toContain(u.userId);
    const anonBody = (await anon.json()) as { current: { progress: unknown; you: unknown }[] };
    for (const c of anonBody.current) expect([c.progress, c.you]).toEqual([null, null]);

    // dataset is validated; the endpoint is GET only
    expect((await h.call("GET", "/v1/play/challenges?dataset=rivers")).status).toBe(400);
    expect((await h.call("POST", "/v1/play/challenges")).status).toBe(405);
  });

  it("counts below 5 hidden", async () => {
    h.clock.t = pst(2026, 11, 7, 12); // Sat 2026-11-07: sweep ch-sweep@2026-11-07 open (target 3 on trails)
    const occ = "ch-sweep@2026-11-07";
    const seed = async (progress: number[]) => {
      for (const n of progress) {
        const u = await devLogin(h);
        await env.DB.prepare("INSERT INTO play_progress (user_id, dataset, occ, progress) VALUES (?, 'trails', ?, ?)").bind(u.userId, occ, n).run();
      }
    };
    const bands = async () => {
      const b = (await (await h.call("GET", "/v1/play/challenges?dataset=trails")).json()) as { current: { occ: string; bands: { band: string; count: number | null }[] }[] };
      return Object.fromEntries(b.current.find((c) => c.occ === occ)!.bands.map((x) => [x.band, x.count]));
    };
    // 4 finished, 2 halfway, 1 started: every band below 5 → hidden
    await seed([3, 4, 3, 5, 2, 2, 1]);
    expect(await bands()).toEqual({ top: null, middle: null, starting: null });
    // 5 finished → shown; 7 → rounded to 5; 8 → 10. Users with no progress are not participants.
    await seed([3]);
    expect(await bands()).toEqual({ top: 5, middle: null, starting: null });
    await seed([3, 6, 0, 0, 0, 0]);
    expect(await bands()).toEqual({ top: 5, middle: null, starting: null });
    await seed([3, 2, 2, 2]);
    expect(await bands()).toEqual({ top: 10, middle: 5, starting: null });
    // an admin-hidden user is not counted (like community stats)
    await env.DB.prepare("UPDATE users SET hidden = 1 WHERE id IN (SELECT user_id FROM play_progress WHERE occ = ? AND progress = 2 LIMIT 1)").bind(occ).run();
    expect(await bands()).toEqual({ top: 10, middle: null, starting: null });
    // the roads goal has its own counts
    const roads = (await (await h.call("GET", "/v1/play/challenges?dataset=roads")).json()) as { current: { occ: string; target: number; bands: unknown }[] };
    expect(roads.current.find((c) => c.occ === occ)).toMatchObject({
      target: 5,
      bands: [
        { band: "top", count: null },
        { band: "middle", count: null },
        { band: "starting", count: null },
      ],
    });
  });
});

