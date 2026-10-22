import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { challengesOf, completionProgress, contributionOccurrence } from "../src/play/challenges";
import { evaluatePlay, parsePlay } from "../src/play/claims";
import { CURRENT, packByVersion } from "../src/play/config";
import { DERIVED } from "../src/play/derived";
import type { PlayPack } from "../src/play/recurrence";
import { LIMITS } from "../src/ratelimit";
import { type Harness, devLogin, harness, trailFull, upload } from "./helpers";

/**
 * L3-2 (plan § 3; D5, P1-1, P2-6, P2-7; PD-26; privacy review: D12 reversed, park-hop is a per-upload count):
 * `ch-climb` and `ch-parkhop` on the server (bundled pack 2026.11.3 carries both), plus the `/v1/play/me` privacy
 * checks (isolation, 401, per-user limit).
 */

const PACK = CURRENT.version;
const pdt = (y: number, mo: number, d: number, h: number) => Date.UTC(y, mo - 1, d, h + 7);
const pst = (y: number, mo: number, d: number, h: number) => Date.UTC(y, mo - 1, d, h + 8);

const WEEK = "2026-10-26";
const CLIMB = `ch-climb@${WEEK}`; // Mon 2026-10-26 00:00 → Mon 2026-11-02 00:00
const CLIMB_NEXT = "ch-climb@2026-11-02";
const PARKHOP = "ch-parkhop@2026-10-31"; // Sat 2026-10-31 00:00 → Mon 2026-11-02 00:00, grace through Wed 11-04
const TABLES = DERIVED.trails.trailRules!;
const dm = (t: string) => TABLES.gainDm[t]!;

// fixtures (derived-trails.json gains in dm; parks)
const PETERS = "trail-peters-trail"; // 921 dm, Gazzam Lake Preserve
const GF_EAST = "trail-grand-forest-east-trail"; // 587 dm, Grand Forest
const HAYANO = "trail-hayano-trail"; // Moritani Preserve (its only trail)
const GIDEON = "trail-gideon-park-trail"; // Gideon Park
const YEOMALT = "trail-camp-yeomalt-trail"; // Camp Yeomalt
const CLOSE = "trail-close-trail"; // Gazzam Lake Preserve
const KNUDSEN = "trail-knudsen-trail"; // Gazzam Lake Preserve
const KOJIMA = "trail-kojima-trail"; // 410 dm, no park
const CEMETERY = "trail-blakley-cemetary-trail"; // quiet, 665 dm
const PRITCHARD = "trail-pritchard-trail"; // quiet, Pritchard Park (excluded)
/** The golden's "120 m remaining" set: every non-quiet trail but battle-point (206), manzanita-park (519), mcredmond (475). */
const REMAINING_120 = ["trail-battle-point", "trail-manzanita-park", "trail-mcredmond-trail"];
const ALL_TRAILS = Object.keys(TABLES.gainDm).filter((t) => !TABLES.quietFeatures.has(t));
const ALL_BUT_120 = ALL_TRAILS.filter((t) => !REMAINING_120.includes(t));

type Claim = { id: string; kind: string; weekId: string; capped?: boolean };
type PlayRes = { accepted: string[]; rejected: { id: string; code: string }[]; capped: string[]; total: number; week: number };
type Entry = { id: string; occ: string; progress: number | null; target: number; you: { band: string } | null; bands: { band: string; count: number | null }[] };

const rows = async <T>(sql: string, ...args: unknown[]) => (await env.DB.prepare(sql).bind(...args).all<T>()).results;
const progressOf = async (userId: string) =>
  rows<{ occ: string; progress: number }>("SELECT occ, progress FROM play_progress WHERE user_id = ? AND dataset = 'trails' ORDER BY occ", userId);
const parkhopOf = async (userId: string) => (await progressOf(userId)).filter((r) => r.occ.startsWith("ch-parkhop@"));
const claimRow = async (userId: string, occ: string) =>
  env.DB.prepare("SELECT kind, week, marks FROM play_claims WHERE user_id = ? AND occ = ?").bind(userId, occ).first<{ kind: string; week: string; marks: number }>();
const foundMarks = async (userId: string) =>
  (await env.DB.prepare("SELECT found_marks FROM play_totals WHERE user_id = ? AND dataset = 'trails'").bind(userId).first<{ found_marks: number }>())?.found_marks ?? 0;

let h: Harness;

beforeEach(() => {
  h = harness();
  h.clock.t = pdt(2026, 10, 29, 14); // Thu 2026-10-29 14:00 PDT, week 2026-10-26
});

/** One Play upload crediting one new 1-hour walk (clock +1 h) over `segments`, with `claims`. */
async function syncPlay(token: string, segments: Record<string, string>, claims: Claim[] = []): Promise<PlayRes> {
  h.clock.advanceHours(1);
  const walkId = crypto.randomUUID();
  const res = await h.call("PUT", "/v1/coverage", {
    token,
    body: { ...upload(segments, [{ id: walkId, durationS: 3600 }]), playConfig: PACK, play: [{ walkId, claims }] },
  });
  expect(res.status, await res.clone().text()).toBe(200);
  return ((await res.json()) as { play: PlayRes }).play;
}

const full = (...trails: string[]) => Object.assign({}, ...trails.map((t) => trailFull(t))) as Record<string, string>;
const claim = (id: string, extra: Partial<Claim> = {}): Claim => ({ id, kind: "challenge", weekId: WEEK, ...extra });

/** Marks the trails completed earlier (sticky set) without coverage, as a prior dataset or walk would have. */
async function seedCompleted(userId: string, trails: string[]) {
  const ins = env.DB.prepare("INSERT INTO user_completed (user_id, trail_id, dataset) VALUES (?, ?, 'trails') ON CONFLICT DO NOTHING");
  for (let i = 0; i < trails.length; i += 100) await env.DB.batch(trails.slice(i, i + 100).map((t) => ins.bind(userId, t)));
}

async function challenges(token?: string): Promise<Entry[]> {
  const res = await h.call("GET", "/v1/play/challenges?dataset=trails", token ? { token } : {});
  expect(res.status).toBe(200);
  return ((await res.json()) as { current: Entry[] }).current;
}
const entry = (list: Entry[], occ: string) => list.find((c) => c.occ === occ);

describe("ch-climb / ch-parkhop claims on PUT /v1/coverage (L3-2)", () => {
  it("ch-climb 149.9 m no_bits, 150.0 m accepted +100", async () => {
    // pure: the stored contribution against the default 1,500 dm
    const walkId = crypto.randomUUID();
    const run = (stored: number) =>
      evaluatePlay(parsePlay({ playConfig: PACK, play: [{ walkId, claims: [claim(CLIMB)] }] })!, {
        dataset: "trails",
        now: h.clock.t,
        packFor: packByVersion,
        freshWalks: new Map([[walkId, 3600]]),
        coverage: new Map(),
        existing: new Set(),
        weeks: new Map(),
        completed: [],
        progress: new Map([[CLIMB, stored]]),
      });
    expect(run(1499).rejected).toEqual([{ id: CLIMB, code: "no_bits" }]);
    const ok = run(1500);
    expect(ok.accepted).toEqual([CLIMB]);
    expect(ok.inserts).toEqual([{ occ: CLIMB, kind: "challenge", week: WEEK, marks: 100 }]);

    // on the wire: Peters (92.1 m) then Grand Forest East (58.7 m) = 150.8 m this week
    const u = await devLogin(h);
    let p = await syncPlay(u.token, full(PETERS), [claim(CLIMB)]);
    expect(p.rejected).toEqual([{ id: CLIMB, code: "no_bits" }]);
    expect(await progressOf(u.userId)).toEqual([{ occ: CLIMB, progress: dm(PETERS) }]);
    p = await syncPlay(u.token, full(GF_EAST), [claim(CLIMB)]);
    expect(p.accepted).toEqual([CLIMB]);
    expect(p.capped).toEqual([]);
    expect(await progressOf(u.userId)).toEqual([{ occ: CLIMB, progress: dm(PETERS) + dm(GF_EAST) }]);
    expect(dm(PETERS) + dm(GF_EAST)).toBeGreaterThanOrEqual(1500);
    expect(await claimRow(u.userId, CLIMB)).toEqual({ kind: "challenge", week: WEEK, marks: 100 });
    expect(await foundMarks(u.userId)).toBe(100);
  });

  it("ch-parkhop 2 parks no_bits, 3 accepted", async () => {
    h.clock.t = pdt(2026, 10, 31, 9); // Sat: window open
    const u = await devLogin(h);
    let p = await syncPlay(u.token, full(HAYANO, GIDEON), [claim(PARKHOP)]);
    expect(p.rejected).toEqual([{ id: PARKHOP, code: "no_bits" }]);
    expect(await parkhopOf(u.userId)).toEqual([{ occ: PARKHOP, progress: 2 }]);
    p = await syncPlay(u.token, full(YEOMALT), [claim(PARKHOP)]);
    expect(p.accepted).toEqual([PARKHOP]);
    expect(await parkhopOf(u.userId)).toEqual([{ occ: PARKHOP, progress: 3 }]);
    expect(await claimRow(u.userId, PARKHOP)).toEqual({ kind: "challenge", week: WEEK, marks: 100 });
    // the ch-sweep contribution of the same uploads counts trails, not parks
    expect((await progressOf(u.userId)).find((r) => r.occ === "ch-sweep@2026-10-31")).toEqual({ occ: "ch-sweep@2026-10-31", progress: 3 });
  });

  it("upload after Mon 00:00 contributes to the new occurrence only", async () => {
    h.clock.t = pst(2026, 11, 2, 10); // Mon 2026-11-02 10:00 PST: ch-climb@2026-11-02 active, ch-climb@2026-10-26 in grace
    const u = await devLogin(h);
    await syncPlay(u.token, full(KOJIMA));
    expect((await progressOf(u.userId)).filter((r) => r.occ.startsWith("ch-climb@"))).toEqual([{ occ: CLIMB_NEXT, progress: dm(KOJIMA) }]);
  });

  it("late upload in grace with no active occurrence contributes to the latest", async () => {
    h.clock.t = pst(2026, 11, 3, 12); // Tue: no park-hop window open; ch-parkhop@2026-10-31 still in grace
    const item = CURRENT.items.find((i) => i.id === "ch-parkhop")!;
    expect(contributionOccurrence(CURRENT, item, h.clock.t)?.key).toBe(PARKHOP);
    const u = await devLogin(h);
    await syncPlay(u.token, full(HAYANO));
    expect(await parkhopOf(u.userId)).toEqual([{ occ: PARKHOP, progress: 1 }]);
    // Thu 2026-11-05: past the grace, nothing takes park contributions
    h.clock.t = pst(2026, 11, 5, 12);
    expect(contributionOccurrence(CURRENT, item, h.clock.t)).toBeNull();
    await syncPlay(u.token, full(GIDEON));
    expect(await parkhopOf(u.userId)).toEqual([{ occ: PARKHOP, progress: 1 }]);
  });

  it("one completion never counts toward two ch-climb occurrences", async () => {
    // pure: Tue 2026-11-03, when ch-climb@2026-10-26 (grace) and ch-climb@2026-11-02 (active) both take contributions
    const c = completionProgress("trails", pst(2026, 11, 3, 12), [KOJIMA], CURRENT);
    expect([...c].filter(([occ]) => occ.startsWith("ch-climb@"))).toEqual([[CLIMB_NEXT, dm(KOJIMA)]]);
    // on the wire
    h.clock.t = pst(2026, 11, 3, 12);
    const u = await devLogin(h);
    await syncPlay(u.token, full(KOJIMA));
    const climb = (await progressOf(u.userId)).filter((r) => r.occ.startsWith("ch-climb@"));
    expect(climb.reduce((s, r) => s + r.progress, 0)).toBe(dm(KOJIMA));
    expect(climb).toEqual([{ occ: CLIMB_NEXT, progress: dm(KOJIMA) }]);
  });

  it("quiet trail and Pritchard Park add nothing", async () => {
    h.clock.t = pdt(2026, 10, 31, 9); // Sat: both windows open
    const u = await devLogin(h);
    const p = await syncPlay(u.token, full(CEMETERY, PRITCHARD), [claim(CLIMB), claim(PARKHOP)]);
    expect(p.rejected).toEqual([
      { id: CLIMB, code: "no_bits" },
      { id: PARKHOP, code: "no_bits" },
    ]);
    expect(await progressOf(u.userId)).toEqual([]);
    expect(completionProgress("trails", h.clock.t, [CEMETERY, PRITCHARD], CURRENT).size).toBe(0);
  });

  it("same park in one upload counts once; in two uploads twice (D12 reversed, bounded overcount)", async () => {
    h.clock.t = pdt(2026, 10, 31, 9);
    // two trails of one park in a single upload: one park
    const u = await devLogin(h);
    await syncPlay(u.token, full(CLOSE, KNUDSEN));
    expect(await parkhopOf(u.userId)).toEqual([{ occ: PARKHOP, progress: 1 }]);
    // the same park reached by two different trails in two uploads: a count per upload, so twice (documented)
    const v = await devLogin(h);
    await syncPlay(v.token, full(CLOSE));
    await syncPlay(v.token, full(KNUDSEN));
    expect(await parkhopOf(v.userId)).toEqual([{ occ: PARKHOP, progress: 2 }]);
    // a re-sent walk completes nothing new: no contribution
    await syncPlay(v.token, full(KNUDSEN));
    expect(await parkhopOf(v.userId)).toEqual([{ occ: PARKHOP, progress: 2 }]);
  });

  it("parkhop /challenges progress equals distinct parks of each upload", async () => {
    h.clock.t = pdt(2026, 10, 31, 9);
    const u = await devLogin(h);
    await syncPlay(u.token, full(CLOSE, KNUDSEN, HAYANO)); // Gazzam ×2 + Moritani = 2 parks
    const e = entry(await challenges(u.token), PARKHOP)!;
    expect(e).toMatchObject({ id: "ch-parkhop", progress: 2, target: 3, you: { band: "middle" } });
  });

  it("instance pays once", async () => {
    h.clock.t = pdt(2026, 10, 31, 9);
    const u = await devLogin(h);
    let p = await syncPlay(u.token, full(HAYANO, GIDEON, YEOMALT), [claim(PARKHOP)]);
    expect(p.accepted).toEqual([PARKHOP]);
    expect(await foundMarks(u.userId)).toBe(100);
    // the claim again, on a later walk with a fourth park: a no-op that still reports accepted
    p = await syncPlay(u.token, full(CLOSE), [claim(PARKHOP)]);
    expect(p.accepted).toEqual([PARKHOP]);
    expect(p.capped).toEqual([]);
    expect(await rows("SELECT occ FROM play_claims WHERE user_id = ?", u.userId)).toEqual([{ occ: PARKHOP }]);
    expect(await foundMarks(u.userId)).toBe(100);
    expect(await parkhopOf(u.userId)).toEqual([{ occ: PARKHOP, progress: 4 }]);
  });

  it("day cap and week cap still bind", async () => {
    h.clock.t = pdt(2026, 10, 31, 9);
    // the client's daily found cap (`capped: true`): stored with 0, listed in capped, never paid later
    const u = await devLogin(h);
    let p = await syncPlay(u.token, full(HAYANO, GIDEON, YEOMALT), [claim(PARKHOP, { capped: true })]);
    expect(p.accepted).toEqual([PARKHOP]);
    expect(p.capped).toEqual([PARKHOP]);
    expect(await claimRow(u.userId, PARKHOP)).toEqual({ kind: "challenge", week: WEEK, marks: 0 });
    p = await syncPlay(u.token, full(CLOSE), [claim(PARKHOP)]);
    expect(p.accepted).toEqual([PARKHOP]);
    expect(p.capped).toEqual([PARKHOP]);
    expect(await foundMarks(u.userId)).toBe(0);
    // the weekly found cap (2,800): 2,750 already found this week → +100 does not fit
    const v = await devLogin(h);
    await env.DB.prepare("INSERT INTO play_weeks (user_id, dataset, week, found_marks) VALUES (?, 'trails', ?, 2750)").bind(v.userId, WEEK).run();
    p = await syncPlay(v.token, full(HAYANO, GIDEON, YEOMALT), [claim(PARKHOP)]);
    expect(p.accepted).toEqual([PARKHOP]);
    expect(p.capped).toEqual([PARKHOP]);
    expect(await claimRow(v.userId, PARKHOP)).toEqual({ kind: "challenge", week: WEEK, marks: 0 });
  });
});

describe("D5 personal target", () => {
  it("120 m remaining → target 1200 dm", async () => {
    const u = await devLogin(h);
    await seedCompleted(u.userId, ALL_BUT_120);
    expect(entry(await challenges(u.token), CLIMB)).toMatchObject({ progress: 0, target: 1200, you: null });
    // the three remaining trails this week: R = 0 + 1200 → target 1200, the claim is accepted at 1200 < 1500
    const p = await syncPlay(u.token, full(...REMAINING_120), [claim(CLIMB)]);
    expect(p.accepted).toEqual([CLIMB]);
    expect(await progressOf(u.userId)).toEqual([{ occ: CLIMB, progress: 1200 }]);
    expect(await claimRow(u.userId, CLIMB)).toMatchObject({ marks: 100 });
    // for everyone else the card still says 1,500
    expect(entry(await challenges(), CLIMB)).toMatchObject({ progress: null, target: 1500 });
  });

  it("climb bands use the dm target", async () => {
    // a week no other test touches (storage is shared across this file's tests): Mon 2026-11-09, ch-climb@2026-11-09
    h.clock.t = pst(2026, 11, 9, 12);
    const occ = "ch-climb@2026-11-09";
    // 5 users at the default target, 5 half way, 5 started: bands against 1,500 dm (749 would be starting)
    for (const n of [1500, 1500, 1500, 1500, 1500, 750, 750, 750, 750, 750, 100, 100, 100, 100, 100]) {
      const w = await devLogin(h);
      await env.DB.prepare("INSERT INTO play_progress (user_id, dataset, occ, progress) VALUES (?, 'trails', ?, ?)").bind(w.userId, occ, n).run();
    }
    const e = entry(await challenges(), occ)!;
    expect(e.target).toBe(1500);
    expect(e.bands).toEqual([
      { band: "top", count: 5 },
      { band: "middle", count: 5 },
      { band: "starting", count: 5 },
    ]);
    // a user with a lower personal target sees the same island bands (counted against 1,500), their own band apart
    const u = await devLogin(h);
    await seedCompleted(u.userId, ALL_TRAILS);
    await env.DB.prepare("INSERT INTO play_progress (user_id, dataset, occ, progress) VALUES (?, 'trails', ?, 1200)").bind(u.userId, occ).run();
    expect(entry(await challenges(u.token), occ)).toMatchObject({ target: 1200, you: { band: "top" }, bands: e.bands });
  });

  it("you.band uses the personal target", async () => {
    const u = await devLogin(h);
    await seedCompleted(u.userId, ALL_TRAILS);
    await env.DB.prepare("INSERT INTO play_progress (user_id, dataset, occ, progress) VALUES (?, 'trails', ?, 1200)").bind(u.userId, CLIMB).run();
    // 1,200 of a personal 1,200 → top (against the default 1,500 it would be middle)
    expect(entry(await challenges(u.token), CLIMB)).toMatchObject({ progress: 1200, target: 1200, you: { band: "top" } });
    // park hop: 17 parks complete leave 2 → target 2; one hopped this window → middle (starting under the default 3)
    h.clock.t = pdt(2026, 10, 31, 9);
    const v = await devLogin(h);
    const seventeen = ALL_TRAILS.filter((t) => {
      const p = TABLES.parkOf[t];
      return p && TABLES.parks.has(p) && p !== "Waypoint Woods" && p !== "West Port Madison Nature Preserve";
    });
    await seedCompleted(v.userId, seventeen);
    expect(entry(await challenges(v.token), PARKHOP)).toMatchObject({ progress: 0, target: 2, you: null });
    await syncPlay(v.token, full("trail-waypoint-woods-trail"));
    expect(entry(await challenges(v.token), PARKHOP)).toMatchObject({ progress: 1, target: 2, you: { band: "middle" } });
  });

  it("remaining 0 → not listed for the user", async () => {
    h.clock.t = pdt(2026, 10, 31, 9);
    const u = await devLogin(h);
    await seedCompleted(u.userId, ALL_TRAILS);
    const mine = await challenges(u.token);
    expect(mine.map((c) => c.id)).not.toContain("ch-climb");
    expect(mine.map((c) => c.id)).not.toContain("ch-parkhop");
    expect(mine.map((c) => c.id)).toContain("ch-sweep");
    // still listed for everyone else
    const anon = await challenges();
    expect(anon.map((c) => c.id)).toEqual(expect.arrayContaining(["ch-climb", "ch-parkhop"]));
    // and a claim with nothing remaining is not accepted
    const p = await syncPlay(u.token, {}, [claim(CLIMB), claim(PARKHOP)]);
    expect(p.rejected).toEqual([
      { id: CLIMB, code: "no_bits" },
      { id: PARKHOP, code: "no_bits" },
    ]);
  });

  it("anonymous gets default target", async () => {
    h.clock.t = pdt(2026, 10, 31, 9);
    const anon = await challenges();
    expect(entry(anon, CLIMB)).toMatchObject({
      id: "ch-climb",
      scope: "solo",
      progress: null,
      target: 1500,
      you: null,
      windowStart: "2026-10-26T00:00-07:00",
      windowEnd: "2026-11-02T00:00-08:00",
    });
    expect(entry(anon, PARKHOP)).toMatchObject({
      id: "ch-parkhop",
      scope: "solo",
      progress: null,
      target: 3,
      you: null,
      windowStart: "2026-10-31T00:00-07:00",
      windowEnd: "2026-11-02T00:00-08:00",
    });
    expect(Object.keys(entry(anon, CLIMB)!).sort()).toEqual(["bands", "id", "occ", "open", "progress", "scope", "target", "windowEnd", "windowStart", "you"]);
  });
});

describe("pack", () => {
  it("current pack: both trails-only, absent for roads", async () => {
    for (const id of ["ch-climb", "ch-parkhop"]) {
      const item = CURRENT.items.find((i) => i.id === id)!;
      expect(item.goals, id).toEqual(["trails"]);
      expect((item.challenge as { target: Record<string, number> }).target).toEqual({ trails: id === "ch-climb" ? 150 : 3 });
    }
    expect(challengesOf(CURRENT, "trails").map((d) => d.item.id)).toEqual(expect.arrayContaining(["ch-climb", "ch-parkhop"]));
    expect(challengesOf(CURRENT, "roads").map((d) => d.item.id)).not.toEqual(expect.arrayContaining(["ch-climb"]));
    const roads = (await (await h.call("GET", "/v1/play/challenges?dataset=roads")).json()) as { current: { id: string }[] };
    expect(roads.current.map((c) => c.id)).not.toContain("ch-climb");
    expect(roads.current.map((c) => c.id)).not.toContain("ch-parkhop");
  });

  it("roads never evaluates climb or parks, even if a pack lists them", () => {
    const pack: PlayPack = {
      ...CURRENT,
      items: CURRENT.items.map((i) => (i.id === "ch-climb" || i.id === "ch-parkhop" ? { ...i, goals: ["trails", "roads"] } : i)),
    };
    expect(challengesOf(pack, "roads").map((d) => d.item.id)).not.toEqual(expect.arrayContaining(["ch-climb"]));
    expect(challengesOf(pack, "roads").map((d) => d.item.id)).not.toEqual(expect.arrayContaining(["ch-parkhop"]));
    expect(challengesOf(pack, "trails").map((d) => d.item.id)).toEqual(expect.arrayContaining(["ch-climb", "ch-parkhop"]));
    const c = completionProgress("roads", pdt(2026, 10, 31, 9), ["road-x"], pack);
    expect([...c.keys()]).not.toContain("ch-climb@2026-10-26");
    expect([...c.keys()]).not.toContain("ch-parkhop@2026-10-31");
  });
});

describe("GET /v1/play/me privacy (review)", () => {
  type Me = { paid: string[]; occ: string[]; total: number; perKind: Record<string, number> };
  const me = async (token: string) => {
    const res = await h.call("GET", "/v1/play/me?dataset=trails", { token });
    expect(res.status).toBe(200);
    return (await res.json()) as Me;
  };

  it("two users: A's paid keys and claims never appear in B's body", async () => {
    h.clock.t = pdt(2026, 10, 31, 9);
    const a = await devLogin(h);
    const b = await devLogin(h);
    await syncPlay(a.token, full(HAYANO, GIDEON, YEOMALT), [claim(PARKHOP)]);
    const ma = await me(a.token);
    expect(ma.paid).toEqual(expect.arrayContaining(["park1:Moritani Preserve", "parkc:Moritani Preserve", "park1:Gideon Park", "park1:Camp Yeomalt"]));
    expect(ma.occ).toEqual([PARKHOP]);
    const mb = await me(b.token);
    expect(mb).toMatchObject({ paid: [], occ: [], total: 0 });
    expect(mb.perKind.park_first).toBe(0);
    // B pays its own keys only
    await syncPlay(b.token, full(CLOSE));
    const mb2 = await me(b.token);
    expect(mb2.paid).toEqual(["park1:Gazzam Lake Preserve"]);
    expect(mb2.occ).toEqual([]);
    expect((await me(a.token)).paid).toEqual(ma.paid);
  });

  it("unauthenticated → 401", async () => {
    for (const path of ["/v1/play/me", "/v1/play/me?dataset=trails", "/v1/play/me?dataset=roads"]) {
      const res = await h.call("GET", path);
      expect(res.status, path).toBe(401);
    }
    expect((await h.call("GET", "/v1/play/me?dataset=trails", { token: "not-a-token" })).status).toBe(401);
  });

  it("per-user rate limit → 429 with retry-after", async () => {
    const u = await devLogin(h);
    const { limit, windowMs } = LIMITS.playMePerUser;
    for (let i = 0; i < limit; i++) expect((await h.call("GET", `/v1/play/me?dataset=${i % 2 ? "roads" : "trails"}`, { token: u.token })).status).toBe(200);
    const limited = await h.call("GET", "/v1/play/me?dataset=trails", { token: u.token });
    expect(limited.status).toBe(429);
    expect(limited.headers.get("retry-after")).toMatch(/^\d+$/);
    expect(await limited.json()).toMatchObject({ error: { code: "rate_limited", retryAfterSeconds: expect.any(Number) } });
    // another user is unaffected; the window passes and the first user is served again
    const v = await devLogin(h);
    expect((await h.call("GET", "/v1/play/me?dataset=trails", { token: v.token })).status).toBe(200);
    h.clock.t += windowMs + 1;
    expect((await h.call("GET", "/v1/play/me?dataset=trails", { token: u.token })).status).toBe(200);
  });
});
