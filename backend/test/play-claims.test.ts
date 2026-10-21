import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import derivedTrails from "../src/data/play/derived-trails.json";
import { handle } from "../src/index";
import { evaluatePlay, parsePlay } from "../src/play/claims";
import { CURRENT } from "../src/play/config";
import type { PlayPack } from "../src/play/recurrence";
import { type Harness, META, devLogin, fullBits, harness, trailFull, upload } from "./helpers";

/**
 * L3-2: claims ride PUT /v1/coverage (schedule.md § 7.2, product § 3.5/3.6/3.10, PD-2, PD-3). The server derives every
 * mark; the client never sends one. Fixtures come from the bundled pack 2026.11.0 and derived-trails.json.
 */

const PACK = CURRENT.version; // "2026.11.0"
const THU = Date.UTC(2026, 9, 29, 21, 0, 0); // Thu 2026-10-29 14:00 PDT: week 2026-10-26, kraken attack open
const WEEK = "2026-10-26";
const TREASURE0 = `treasure-weekly-trails@${WEEK}#00`; // placement 0 = sea glass, 25
const EGG = `egg-station-s@${WEEK}`; // trails egg, 20
const KRAKEN0 = "kraken-trails@2026-10-29#0"; // coast-t4 stretch 0 = trail-fairy-dell-trail (107.7 m)
const FAIRY_STRETCH = ["seg-007562", "seg-007560", "seg-007557", "seg-007558"];
const ROTARY = "trail-rotary-park-trails"; // 1 included segment, 30.8 m, not quiet
const ROTARY_SEG = META.trails[ROTARY]![0]!;

type Claim = { id: string; kind: string; weekId: string; band?: number; [k: string]: unknown };
type Element = { walkId: string; claims: Claim[]; dayKey?: string; hints?: Record<string, number>; [k: string]: unknown };
type PlayRes = { accepted: string[]; rejected: { id: string; code: string }[]; capped: string[]; total: number; week: number };
type CovRes = { accepted?: boolean; metrics: unknown; play?: PlayRes };

const claim = (id: string, kind: string, weekId = WEEK, extra: Partial<Claim> = {}): Claim => ({ id, kind, weekId, ...extra });

let h: Harness;
let token: string;
let userId: string;

beforeEach(async () => {
  h = harness();
  h.clock.t = THU;
  ({ token, userId } = await devLogin(h));
});

/** One upload of `segments` crediting one new 1-hour walk (clock +1 h) with `play` elements built from its walk id. */
async function syncPlay(
  segments: Record<string, string>,
  build: (walkId: string) => Element[],
  opts: { config?: string | null; extra?: Record<string, unknown>; walkId?: string; advance?: boolean; durationS?: number } = {},
): Promise<Response> {
  if (opts.advance !== false) h.clock.advanceHours(1);
  const walkId = opts.walkId ?? crypto.randomUUID();
  const walks = [{ id: walkId, durationS: opts.durationS ?? 3600 }];
  const body: Record<string, unknown> = { ...upload(segments, walks), play: build(walkId), ...opts.extra };
  if (opts.config !== null) body.playConfig = opts.config ?? PACK;
  return h.call("PUT", "/v1/coverage", { token, body });
}

async function playOf(res: Response): Promise<PlayRes> {
  expect(res.status).toBe(200);
  const j = (await res.json()) as CovRes;
  expect(j.play).toBeDefined();
  return j.play!;
}

const codes = (p: PlayRes) => Object.fromEntries(p.rejected.map((r) => [r.id, r.code]));
const rows = async <T>(sql: string, ...args: unknown[]) => (await env.DB.prepare(sql).bind(...args).all<T>()).results;
const squaresOf = (segIds: string[]) =>
  new Set(segIds.flatMap((s) => (derivedTrails.segmentSquares as Record<string, string[]>)[s] ?? [])).size;

/** W-COV + W-DONE + W-SQUARE of fully covering the rotary trail (trails goal: 1 mark / 10 m, +100, +25 per square). */
const ROTARY_WALK_MARKS = Math.floor(30.8 / 10) + 100 + 25 * squaresOf([ROTARY_SEG]);

describe("claims on PUT /v1/coverage", () => {
  it("accepted with server-derived marks", async () => {
    const res = await syncPlay(trailFull(ROTARY), (w) => [{ walkId: w, claims: [claim(TREASURE0, "treasure"), claim(EGG, "egg")], dayKey: WEEK }]);
    const p = await playOf(res);
    expect(p.accepted).toEqual([TREASURE0, EGG]);
    expect(p.rejected).toEqual([]);
    expect(p.capped).toEqual([]);
    // 25 sea glass + 20 egg + 15 W-DAY, plus the walk-derived marks of the rotary trail
    expect(p.total).toBe(ROTARY_WALK_MARKS + 25 + 20 + 15);
    expect(p.week).toBe(25 + 20 + 15);
    const stored = await rows<Record<string, unknown>>("SELECT * FROM play_claims WHERE user_id = ? ORDER BY occ", userId);
    expect(stored).toEqual([
      { user_id: userId, dataset: "trails", occ: EGG, kind: "egg", week: WEEK, config: PACK, marks: 20 },
      { user_id: userId, dataset: "trails", occ: TREASURE0, kind: "treasure", week: WEEK, config: PACK, marks: 25 },
    ]);
    expect(await rows("SELECT week, day_keys, found_marks FROM play_weeks WHERE user_id = ?", userId)).toEqual([
      { week: WEEK, day_keys: 1, found_marks: 45 },
    ]);

    // GET /v1/play/me: the Logbook restore summary
    expect((await h.call("GET", "/v1/play/me")).status).toBe(401);
    const me = await h.call("GET", "/v1/play/me?dataset=trails", { token });
    expect(me.status).toBe(200);
    expect(await me.json()).toEqual({
      dataset: "trails",
      weekId: WEEK,
      total: p.total,
      week: p.week,
      perKind: { treasure: 1, egg: 1, riddle: 0, ink: 0, lore: 0, challenge: 0 },
      occ: [EGG, TREASURE0],
    });
    const roads = await (await h.call("GET", "/v1/play/me?dataset=roads", { token })).json();
    expect(roads).toMatchObject({ dataset: "roads", total: 0, week: 0, occ: [] });
  });

  it("client marks field ignored", async () => {
    const res = await syncPlay(trailFull(ROTARY), (w) => [
      { walkId: w, marks: 9999, total: 9999, claims: [claim(TREASURE0, "treasure", WEEK, { marks: 5000, tier: "ships_bell" })] },
    ], { extra: { marks: 9999, playTotal: 9999 } });
    const p = await playOf(res);
    expect(p.accepted).toEqual([TREASURE0]);
    expect(p.total).toBe(ROTARY_WALK_MARKS + 25);
    expect(await rows("SELECT marks FROM play_claims WHERE user_id = ?", userId)).toEqual([{ marks: 25 }]);
  });

  it("idempotent retry reports accepted", async () => {
    const walkId = crypto.randomUUID();
    const build = (w: string) => [{ walkId: w, claims: [claim(TREASURE0, "treasure"), claim(EGG, "egg")], dayKey: WEEK }];
    const first = await playOf(await syncPlay(trailFull(ROTARY), build, { walkId }));
    // the same payload again (lost response): walk already credited, claims already stored
    const second = await playOf(await syncPlay(trailFull(ROTARY), build, { walkId }));
    expect(second.accepted).toEqual([TREASURE0, EGG]);
    expect(second.rejected).toEqual([]);
    expect(second.total).toBe(first.total);
    expect(second.week).toBe(first.week);
    // and a claim repeated inside one upload counts once
    const third = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(`egg-ladybug@${WEEK}`, "egg"), claim(`egg-ladybug@${WEEK}`, "egg")] }]));
    expect(third.accepted).toEqual([`egg-ladybug@${WEEK}`, `egg-ladybug@${WEEK}`]);
    expect(third.total).toBe(first.total + 20);
    expect(await rows("SELECT day_keys FROM play_weeks WHERE user_id = ?", userId)).toEqual([{ day_keys: 1 }]);
  });

  it("stale_config", async () => {
    const res = await syncPlay(trailFull(ROTARY), (w) => [{ walkId: w, claims: [claim(TREASURE0, "treasure"), claim(EGG, "egg")] }], {
      config: "2025.1.0",
    });
    const p = await playOf(res);
    expect(p.accepted).toEqual([]);
    expect(codes(p)).toEqual({ [TREASURE0]: "stale_config", [EGG]: "stale_config" });
    // a missing playConfig is stale too; the coverage itself is accepted either way
    const p2 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(EGG, "egg")] }], { config: null }));
    expect(codes(p2)).toEqual({ [EGG]: "stale_config" });
    expect(p2.total).toBe(ROTARY_WALK_MARKS);
  });

  it("unknown_item", async () => {
    const p = await playOf(
      await syncPlay({}, (w) => [
        {
          walkId: w,
          claims: [
            claim(`nope-item@${WEEK}`, "egg"),
            claim(`egg-fairydoor@${WEEK}`, "egg"), // enabled:false (OD-16)
            claim(EGG, "treasure"), // kind does not match the item type
            claim("kraken-roads@2026-10-29#0", "ink", WEEK, { band: 95 }), // roads item on a trails upload
            claim(`egg-madrone@${WEEK}`, "egg"), // roads-only egg
            claim(`egg-ladybug@${WEEK}`, "coin"), // not a claim kind
          ],
        },
      ]),
    );
    expect(p.accepted).toEqual([]);
    expect(Object.values(codes(p))).toEqual(Array(6).fill("unknown_item"));
  });

  it("bad_occurrence", async () => {
    const ids = [
      "treasure-weekly-trails@2026-10-27#00", // no occurrence starts on a Tuesday
      `treasure-weekly-trails@${WEEK}#12`, // trails set has 12 placements (#00..#11)
      `treasure-weekly-trails@${WEEK}#7`, // not canonical (#07)
      `treasure-weekly-trails@${WEEK}`, // a placement index is required
      `${EGG}#1`, // single-placement item with an index
      "kraken-trails@2026-10-29#2", // coast-t4 has 2 stretches
      "not a key",
    ];
    const p = await playOf(
      await syncPlay({}, (w) => [
        {
          walkId: w,
          claims: [
            ...ids.map((id) => claim(id, id.startsWith("kraken") ? "ink" : id.startsWith("egg") ? "egg" : "treasure", WEEK, { band: 60 })),
            claim("treasure-weekly-trails@2026-10-26#01", "treasure", "2026-10-19"), // not active in the stated week
            claim("treasure-weekly-trails@2026-10-26#02", "treasure", "2026-10-27"), // week id is not a Monday
          ],
        },
      ]),
    );
    expect(p.accepted).toEqual([]);
    expect(Object.values(codes(p))).toEqual(Array(9).fill("bad_occurrence"));
  });

  it("not_this_walk", async () => {
    const other = crypto.randomUUID(); // not in walks[] of this upload
    const p = await playOf(await syncPlay({}, () => [{ walkId: other, claims: [claim(EGG, "egg")], dayKey: WEEK }]));
    expect(codes(p)).toEqual({ [EGG]: "not_this_walk" });
    expect(p.week).toBe(0); // the dayKey is not credited either
    // a walk credited by an earlier upload does not unlock claims later
    const walkId = crypto.randomUUID();
    await syncPlay({}, () => [], { walkId });
    const p2 = await playOf(await syncPlay({}, () => [{ walkId, claims: [claim(EGG, "egg")] }], { walkId }));
    expect(codes(p2)).toEqual({ [EGG]: "not_this_walk" });
  });

  it("no_bits when band above coverage share", async () => {
    // 2 of the 4 stretch segments: 99.4 of 107.7 m = 92 %
    const part: Record<string, string> = {};
    for (const s of FAIRY_STRETCH.slice(2)) part[s] = fullBits(META.segments[s]![1]);
    const p = await playOf(await syncPlay(part, (w) => [{ walkId: w, claims: [claim(KRAKEN0, "ink", WEEK, { band: 95 })] }]));
    expect(codes(p)).toEqual({ [KRAKEN0]: "no_bits" });
    const p2 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(KRAKEN0, "ink", WEEK, { band: 80 })] }]));
    expect(p2.accepted).toEqual([KRAKEN0]);
    expect(await rows("SELECT marks FROM play_claims WHERE user_id = ? AND occ = ?", userId, KRAKEN0)).toEqual([{ marks: 70 }]);
    // a band the item does not pay is malformed
    const p3 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim("kraken-trails@2026-10-29#1", "ink", WEEK, { band: 50 })] }]));
    expect(codes(p3)).toEqual({ "kraken-trails@2026-10-29#1": "bad_occurrence" });
    // feature riddle: 100 m of the answer trail must carry bits (only 99.4 m of trail-fairy-dell-trail so far)
    const p4 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(`riddle-fairy@${WEEK}`, "riddle")] }]));
    expect(codes(p4)).toEqual({ [`riddle-fairy@${WEEK}`]: "no_bits" });
    const p5 = await playOf(
      await syncPlay(trailFull("trail-fairy-dell-trail"), (w) => [{ walkId: w, claims: [claim(`riddle-fairy@${WEEK}`, "riddle")], hints: { "riddle-fairy": 1 } }]),
    );
    expect(p5.accepted).toEqual([`riddle-fairy@${WEEK}`]);
    // ★ 40, one hint −25 % → 30
    expect(await rows("SELECT marks FROM play_claims WHERE user_id = ? AND occ = ?", userId, `riddle-fairy@${WEEK}`)).toEqual([{ marks: 30 }]);
  });

  it("too_late", async () => {
    h.clock.t = Date.UTC(2026, 10, 5, 20, 0, 0); // Thu 2026-11-05: the 2026-10-26 set ended Mon 2026-11-02 00:00 (> 2 days ago)
    ({ token } = await devLogin(h));
    const p = await playOf(
      await syncPlay({}, (w) => [
        {
          walkId: w,
          claims: [claim(TREASURE0, "treasure"), claim("treasure-weekly-trails@2026-10-19#00", "treasure", "2026-10-19")],
          dayKey: "2026-10-19",
        },
      ]),
    );
    // the set ended more than 2 days ago; week 2026-10-19 is neither this week nor the last (its dayKey is ignored)
    expect(codes(p)).toEqual({ [TREASURE0]: "too_late", "treasure-weekly-trails@2026-10-19#00": "too_late" });
    expect(p.week).toBe(0);
    // on Wednesday 2026-11-04 the 2026-10-26 set is still within the 2-day grace
    h.clock.t = Date.UTC(2026, 10, 4, 20, 0, 0);
    ({ token } = await devLogin(h));
    const p2 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(TREASURE0, "treasure")] }]));
    expect(p2.accepted).toEqual([TREASURE0]);
  });

  it("too_early", async () => {
    const next = "treasure-weekly-trails@2026-11-02#00";
    const p = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(next, "treasure", "2026-11-02")], dayKey: "2026-11-02" }]));
    expect(codes(p)).toEqual({ [next]: "too_early" });
    expect(p.week).toBe(0);
    // Thursday 04:00 PDT: the attack opens at 06:00, more than 1 h ahead
    h.clock.t = Date.UTC(2026, 9, 29, 10, 0, 0);
    ({ token } = await devLogin(h));
    const p2 = await playOf(await syncPlay(trailFull("trail-fairy-dell-trail"), (w) => [{ walkId: w, claims: [claim(KRAKEN0, "ink", WEEK, { band: 95 })] }]));
    expect(codes(p2)).toEqual({ [KRAKEN0]: "too_early" });
  });

  it("week_cap accepts with 0 marks", async () => {
    await env.DB.prepare("INSERT INTO play_weeks (user_id, dataset, week, found_marks) VALUES (?, 'trails', ?, 2770)").bind(userId, WEEK).run();
    // 2770 + 25 fits the 2,800 weekly cap; + 20 more does not: accepted with 0 marks
    const p = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(TREASURE0, "treasure"), claim(EGG, "egg")] }]));
    expect(p.accepted).toEqual([TREASURE0, EGG]);
    expect(p.capped).toEqual([EGG]);
    expect(p.rejected).toEqual([]);
    expect(p.total).toBe(25);
    expect(await rows("SELECT occ, marks FROM play_claims WHERE user_id = ? ORDER BY occ", userId)).toEqual([
      { occ: EGG, marks: 0 },
      { occ: TREASURE0, marks: 25 },
    ]);
    expect(await rows("SELECT found_marks FROM play_weeks WHERE user_id = ?", userId)).toEqual([{ found_marks: 2795 }]);
  });

  it("dayKey over 7 per week ignored", async () => {
    await env.DB.prepare("INSERT INTO play_weeks (user_id, dataset, week, day_keys) VALUES (?, 'trails', ?, 7)").bind(userId, WEEK).run();
    const p = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [], dayKey: WEEK }]));
    expect(p.total).toBe(0);
    expect(await rows("SELECT day_keys FROM play_weeks WHERE user_id = ?", userId)).toEqual([{ day_keys: 7 }]);
  });

  it("killed item rejected", () => {
    const killed = (kill: Record<string, unknown>): PlayPack => ({ ...CURRENT, kill: { all: false, types: [], items: [], ...kill } });
    const walkId = crypto.randomUUID();
    const play = parsePlay({ playConfig: PACK, play: [{ walkId, claims: [claim(EGG, "egg"), claim(TREASURE0, "treasure")] }] })!;
    const run = (pack: PlayPack) =>
      evaluatePlay(play, {
        dataset: "trails",
        now: THU,
        packFor: (v) => (v === PACK ? pack : null),
        freshWalks: new Map([[walkId, 3600]]),
        coverage: new Map(),
        existing: new Set(),
        weeks: new Map(),
      });
    expect(run(killed({})).accepted).toEqual([EGG, TREASURE0]);
    expect(run(killed({ items: ["egg-station-s"] })).rejected).toEqual([{ id: EGG, code: "unknown_item" }]);
    expect(run(killed({ types: ["treasure"] })).rejected).toEqual([{ id: TREASURE0, code: "unknown_item" }]);
    expect(run(killed({ all: true })).rejected.map((r) => r.code)).toEqual(["unknown_item", "unknown_item"]);
  });

  describe("kill switches are the current pack's", () => {
    const OLD = "2026.10.9";
    const walkId = crypto.randomUUID();
    const play = parsePlay({ playConfig: OLD, play: [{ walkId, claims: [claim(EGG, "egg"), claim(TREASURE0, "treasure")] }] })!;
    const run = (current: PlayPack) =>
      evaluatePlay(play, {
        dataset: "trails",
        now: THU,
        packFor: (v) => (v === OLD ? { ...CURRENT, version: OLD } : v === current.version ? current : null),
        current,
        freshWalks: new Map([[walkId, 3600]]),
        coverage: new Map(),
        existing: new Set(),
        weeks: new Map(),
      });
    const patched = (id: string, patch: Record<string, unknown>): PlayPack => ({
      ...CURRENT,
      items: CURRENT.items.map((i) => (i.id === id ? { ...i, ...patch } : i)),
    });

    it("killed item in current pack rejected even when client names an older pack", () => {
      const kill = (k: Record<string, unknown>): PlayPack => ({ ...CURRENT, kill: { all: false, types: [], items: [], ...k } });
      expect(run(kill({})).accepted).toEqual([EGG, TREASURE0]);
      expect(run(kill({ items: ["egg-station-s"] })).rejected).toEqual([{ id: EGG, code: "unknown_item" }]);
      expect(run(kill({ types: ["treasure"] })).rejected).toEqual([{ id: TREASURE0, code: "unknown_item" }]);
      expect(run(kill({ all: true })).rejected.map((r) => r.code)).toEqual(["unknown_item", "unknown_item"]);
      expect(run(patched("egg-station-s", { enabled: false })).rejected).toEqual([{ id: EGG, code: "unknown_item" }]);
    });

    it("item expired in current pack rejected", () => {
      expect(run(patched("egg-station-s", { expires: "2026-10-25" })).rejected).toEqual([{ id: EGG, code: "unknown_item" }]);
      expect(run(patched("egg-station-s", { expires: "2026-12-31" })).accepted).toEqual([EGG, TREASURE0]);
    });
  });

  // privacy review 2026-10-20 P2-3: W-DAY needs a walk of at least 10 minutes
  it("dayKey on a walk under 10 min ignored", async () => {
    const p = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [], dayKey: WEEK }], { durationS: 599 }));
    expect(p.week).toBe(0);
    expect(p.total).toBe(0);
    expect(await rows("SELECT day_keys FROM play_weeks WHERE user_id = ?", userId)).toEqual([]);
    const p2 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [], dayKey: WEEK }], { durationS: 600 }));
    expect(p2.week).toBe(15);
    expect(await rows("SELECT day_keys FROM play_weeks WHERE user_id = ?", userId)).toEqual([{ day_keys: 1 }]);
  });

  // P2-4: a claim-bearing walk must have walked at all (honest-client limit beyond that: PD-21)
  it("claim on a 0 s walk → not_this_walk", async () => {
    const p = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(EGG, "egg")], dayKey: WEEK }], { durationS: 0 }));
    expect(codes(p)).toEqual({ [EGG]: "not_this_walk" });
    expect(p.total).toBe(0);
    expect(await rows("SELECT occ FROM play_claims WHERE user_id = ?", userId)).toEqual([]);
    const p2 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(EGG, "egg")] }], { durationS: 1 }));
    expect(p2.accepted).toEqual([EGG]);
  });

  // P2-5: the client does not pick the week of an "always" item (no 2 × weekly cap at a week boundary)
  it("always item with previous weekId is booked to current week", async () => {
    const NEXT = "2026-11-02";
    h.clock.t = Date.UTC(2026, 10, 2, 18, 0, 0); // Mon 2026-11-02 10:00 PST: week 2026-11-02, previous 2026-10-26
    ({ token, userId } = await devLogin(h));
    const p = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(EGG, "egg", WEEK)] }]));
    expect(p.accepted).toEqual([EGG]);
    expect(p.week).toBe(20);
    expect(await rows("SELECT occ, week, marks FROM play_claims WHERE user_id = ?", userId)).toEqual([{ occ: EGG, week: NEXT, marks: 20 }]);
    expect(await rows("SELECT week, found_marks FROM play_weeks WHERE user_id = ?", userId)).toEqual([{ week: NEXT, found_marks: 20 }]);
    // the current week is full: naming the (empty) previous week does not reach around the cap
    await env.DB.prepare("UPDATE play_weeks SET found_marks = 2790 WHERE user_id = ? AND week = ?").bind(userId, NEXT).run();
    const LADYBUG = `egg-ladybug@${WEEK}`;
    const p2 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(LADYBUG, "egg", WEEK)] }]));
    expect(p2.accepted).toEqual([LADYBUG]);
    expect(p2.capped).toEqual([LADYBUG]);
    expect(await rows("SELECT week, marks FROM play_claims WHERE user_id = ? AND occ = ?", userId, LADYBUG)).toEqual([{ week: NEXT, marks: 0 }]);
    expect(await rows("SELECT week FROM play_weeks WHERE user_id = ?", userId)).toEqual([{ week: NEXT }]);
    // a weekly item keeps the week the client named (it is checked against the occurrence)
    const prev = "treasure-weekly-trails@2026-10-26#00";
    const p3 = await playOf(await syncPlay({}, (w) => [{ walkId: w, claims: [claim(prev, "treasure", WEEK)] }]));
    expect(p3.accepted).toEqual([prev]);
    expect(await rows("SELECT week, marks FROM play_claims WHERE user_id = ? AND occ = ?", userId, prev)).toEqual([{ week: WEEK, marks: 25 }]);
  });

  // P2-6: caps come from the current pack, never from the version the client names
  it("old pack with foundPerWeek 9999 still capped at 2,800", () => {
    const OLD = "2026.10.9";
    const old: PlayPack = {
      ...CURRENT,
      version: OLD,
      caps: { ...(CURRENT.caps as object), foundPerWeek: 9999, findGraceDays: 99, clockAheadMin: 99999 },
    };
    const walkId = crypto.randomUUID();
    const run = (claims: Claim[], weeks = new Map([[WEEK, { dayKeys: 0, found: 2790 }]]), now = THU) =>
      evaluatePlay(parsePlay({ playConfig: OLD, play: [{ walkId, claims }] })!, {
        dataset: "trails",
        now,
        packFor: (v) => (v === OLD ? old : v === PACK ? CURRENT : null),
        freshWalks: new Map([[walkId, 3600]]),
        coverage: new Map(),
        existing: new Set(),
        weeks,
      });
    const ev = run([claim(TREASURE0, "treasure")]);
    expect(ev.accepted).toEqual([TREASURE0]);
    expect(ev.capped).toEqual([TREASURE0]);
    expect(ev.foundMarks).toBe(0);
    // marks still come from the named pack's tables (sea glass 25 when under the cap)
    expect(run([claim(TREASURE0, "treasure")], new Map()).inserts).toEqual([{ occ: TREASURE0, kind: "treasure", week: WEEK, marks: 25 }]);
    // the time caps too: the old pack's 99-day grace does not reopen an ended set
    const late = run([claim(TREASURE0, "treasure")], new Map(), Date.UTC(2026, 10, 5, 20, 0, 0));
    expect(late.rejected).toEqual([{ id: TREASURE0, code: "too_late" }]);
  });

  it("422 implausible rejects claims too", async () => {
    // a whole long trail on a 1-minute walk
    const big: Record<string, string> = {};
    let m = 0;
    for (const [id, [len, n, , inc]] of Object.entries(META.segments)) {
      if (inc !== 1 || m > 2000) continue;
      big[id] = fullBits(n);
      m += len;
    }
    const walkId = crypto.randomUUID();
    const body = {
      ...upload(big, [{ id: walkId, durationS: 60 }]),
      playConfig: PACK,
      play: [{ walkId, claims: [claim(TREASURE0, "treasure")], dayKey: WEEK }],
    };
    const res = await h.call("PUT", "/v1/coverage", { token, body });
    expect(res.status).toBe(422);
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe("implausible");
    for (const t of ["play_claims", "play_weeks", "play_totals"]) {
      expect(await rows(`SELECT * FROM ${t} WHERE user_id = ?`, userId)).toEqual([]);
    }
  });

  it("pure upload response byte-identical without play", async () => {
    const b = await devLogin(h);
    const segs = trailFull(ROTARY);
    const walk = { id: crypto.randomUUID(), durationS: 3600 };
    h.clock.advanceHours(1);
    const pure = await h.call("PUT", "/v1/coverage", { token, body: upload(segs, [walk]) });
    const withPlay = await h.call("PUT", "/v1/coverage", {
      token: b.token,
      body: { ...upload(segs, [walk]), playConfig: PACK, play: [{ walkId: walk.id, claims: [] }] },
    });
    const pureText = await pure.text();
    const playJson = (await withPlay.json()) as Record<string, unknown>;
    expect(Object.keys(JSON.parse(pureText))).toEqual([
      "dataset",
      "datasetVersion",
      "tickM",
      "segments",
      "completedTrailIds",
      "metrics",
      "accepted",
      "newlyCoveredM",
      "ignoredSegments",
    ]);
    expect(Object.keys(playJson).at(-1)).toBe("play");
    const { play: _p, ...rest } = playJson;
    expect(JSON.stringify(rest)).toBe(pureText);
    // nothing Play-related is written for a pure upload, and playConfig alone does not switch Play on
    const pure2 = await h.call("PUT", "/v1/coverage", { token, body: { ...upload(segs, []), playConfig: PACK } });
    const pure2Text = await pure2.text();
    expect(Object.keys(JSON.parse(pure2Text) as object)).not.toContain("play");
    expect(pure2Text.includes('"play"')).toBe(false);
    expect(await rows("SELECT * FROM play_totals WHERE user_id = ?", userId)).toEqual([]);
  });

  it("50 claims per upload cap", async () => {
    const many = (n: number) => Array.from({ length: n }, (_, i) => claim(`treasure-weekly-trails@${WEEK}#${String(i % 12).padStart(2, "0")}`, "treasure"));
    const res = await syncPlay(trailFull(ROTARY), (w) => [{ walkId: w, claims: many(51) }]);
    expect(res.status).toBe(400);
    expect(((await res.json()) as { error: { code: string; maxClaims: number } }).error).toMatchObject({ code: "too_many_claims", maxClaims: 50 });
    // nothing stored, coverage included
    const cov = (await (await h.call("GET", "/v1/coverage", { token })).json()) as { segments: Record<string, string> };
    expect(cov.segments).toEqual({});
    expect(await rows("SELECT * FROM play_claims WHERE user_id = ?", userId)).toEqual([]);
    // 50 is fine (repeats inside the upload are no-ops)
    expect((await syncPlay(trailFull(ROTARY), (w) => [{ walkId: w, claims: many(50) }])).status).toBe(200);
    // malformed play is a 400 as well
    const bad = await syncPlay({}, () => [{ walkId: "not-a-uuid", claims: [] }]);
    expect(bad.status).toBe(400);
    expect(((await bad.json()) as { error: { code: string } }).error.code).toBe("invalid_play");
  });

  it("deletion cascades play rows", async () => {
    await playOf(await syncPlay(trailFull(ROTARY), (w) => [{ walkId: w, claims: [claim(TREASURE0, "treasure")], dayKey: WEEK }]));
    for (const t of ["play_claims", "play_weeks", "play_totals"]) {
      expect((await rows(`SELECT * FROM ${t} WHERE user_id = ?`, userId)).length).toBe(1);
    }
    expect((await h.call("DELETE", "/v1/me", { token })).status).toBe(204);
    for (const t of ["play_claims", "play_weeks", "play_totals"]) {
      expect(await rows(`SELECT * FROM ${t} WHERE user_id = ?`, userId)).toEqual([]);
    }
  });
});

describe("dev clock", () => {
  async function weekIdVia(host: string, vars: Record<string, string>): Promise<string> {
    const ctx = createExecutionContext();
    const req = new Request(`${host}/v1/play/me`, { headers: { authorization: `Bearer ${token}` } });
    const res = await handle(req, { ...env, ...vars } as Env, ctx, h.deps);
    await waitOnExecutionContext(ctx);
    expect(res.status).toBe(200);
    return ((await res.json()) as { weekId: string }).weekId;
  }

  it("dev clock offset ignored in prod host", async () => {
    const twoWeeks = String(14 * 86_400_000);
    // honoured on a dev host with dev auth on
    expect(await weekIdVia("http://localhost:8787", { DEV_CLOCK_OFFSET_MS: twoWeeks })).toBe("2026-11-09");
    expect(await weekIdVia("http://10.0.2.2:8787", { DEV_CLOCK_OFFSET_MS: twoWeeks })).toBe("2026-11-09");
    // ignored on any other host, and without ALLOW_DEV_AUTH=true
    expect(await weekIdVia("https://bainbridge-trails-api.example.workers.dev", { DEV_CLOCK_OFFSET_MS: twoWeeks })).toBe(WEEK);
    expect(await weekIdVia("http://localhost:8787", { DEV_CLOCK_OFFSET_MS: twoWeeks, ALLOW_DEV_AUTH: "false" })).toBe(WEEK);
    // malformed offsets are ignored
    expect(await weekIdVia("http://localhost:8787", { DEV_CLOCK_OFFSET_MS: "soon" })).toBe(WEEK);
    expect(await weekIdVia("http://localhost:8787", {})).toBe(WEEK);
  });
});
