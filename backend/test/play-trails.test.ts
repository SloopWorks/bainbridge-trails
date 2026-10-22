/// <reference types="vite/client" />
import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import { DERIVED } from "../src/play/derived";
import { walkDerivedMarks } from "../src/play/marks";
import {
  CLIMB_STEPS_M,
  METRES_PER_MILE,
  MILE_STEPS,
  STEEP_DM,
  TRAIL_KINDS,
  TRAIL_MARKS,
  type TrailKind,
  type TrailMetric,
  challengeTarget,
  coveredMetres,
  islandWhole,
  kindOf,
  owedKeys,
  qualifyingKeys,
  trailInputs,
  trailQualifying,
} from "../src/play/trails";
import { CURRENT } from "../src/play/config";
import { decodeBits } from "../src/coverage";
import { type Harness, META, devLogin, harness, trailFull, upload } from "./helpers";

/**
 * L3-1 (plan § 3; brief § 2, § 4 AC 1–3, 7): the six trails rules on the server. Part 1 runs every case of the shared
 * G0 golden `data/play/golden/trails-rewards.json` through `trails.ts` (parity with the Kotlin `TrailRewards`: both
 * sides reproduce the same inputs, keys and marks). Part 2 drives `PUT /v1/coverage` + `GET /v1/play/me`: the ledger
 * (`play_trail_paid`), `play_totals.trail_marks`, idempotency, Play-off privacy (PF0) and deletion.
 */

const GOLDEN_FILES = import.meta.glob<string>("../../data/play/golden/trails-rewards.json", { eager: true, query: "?raw", import: "default" });

interface GoldenCase {
  name: string;
  input: { coverage: Record<string, number>; completed: string[]; islandWhole: boolean };
  paidBefore?: string[];
  expectInputs: { nonQuietCoveredM: number; touchedTrails: string[]; touchedParks: string[] };
  expectNewKeys: string[];
  expectMarks: Record<TrailKind, number>;
}
interface ChallengeCase {
  name: string;
  metric: TrailMetric;
  default: number;
  completed: string[];
  progress: number | { parks: string[] };
  expectTarget: number;
  expectClaim: boolean;
}
interface Golden {
  dataset: string;
  rules: { climbStepsM: number[]; mileSteps: number[]; metresPerMile: number; steepGainDm: number; marks: Record<TrailKind, number> };
  tables: {
    countedSegments: number;
    quietSegments: number;
    islandNonQuietM: number;
    nonQuietTrails: number;
    nonQuietGainM: number;
    quietFeatures: string[];
    parks: string[];
    steepTrails: { id: string; gainM: number; park: string | null }[];
    singleTrailParks: string[];
  };
  cases: GoldenCase[];
  challengeCases: ChallengeCase[];
  mileCases: { name: string; nonQuietCoveredM: number; expectMileKeys: string[] }[];
}

const golden = JSON.parse(Object.values(GOLDEN_FILES)[0]!) as Golden;
const TABLES = DERIVED.trails.trailRules!;
const HAYANO = "trail-hayano-trail"; // Moritani Preserve's only trail: park1 + parkc (+50, +150); gain 8.4 m, 403.1 m
const HAYANO_KEYS = ["park1:Moritani Preserve", "parkc:Moritani Preserve"];
const TRILLIUM = "trail-trillium-trail"; // Grand Forest, gain 40.4 m: high ground + park1 (+50, +50)
const TRILLIUM_KEYS = ["high:trail-trillium-trail", "park1:Grand Forest"];
/** Hayano (403.1 m) + Trillium (1,465.5 m) = 1,868.6 non-quiet metres: the first mile step too (golden "dataset add"). */
const BOTH_KEYS = [...HAYANO_KEYS, ...TRILLIUM_KEYS, "mile:1"].sort();
const BOTH_MARKS = 200 + 100 + 50;
const ROTARY = "trail-rotary-park-trails"; // Rotary Park (several trails): park1 only
const THU = Date.UTC(2026, 9, 29, 21, 0, 0); // Thu 2026-10-29 14:00 PDT, week 2026-10-26
const WEEK = "2026-10-26";
const PACK = CURRENT.version;

describe("trails-rewards golden (parity with Kotlin TrailRewards)", () => {
  it("golden tables equal the bundled dataset and derived JSON (drift guard)", () => {
    expect(golden.dataset).toBe(META.version);
    expect(golden.rules.climbStepsM).toEqual([...CLIMB_STEPS_M]);
    expect(golden.rules.mileSteps).toEqual([...MILE_STEPS]);
    expect(golden.rules.metresPerMile).toBe(METRES_PER_MILE);
    expect(golden.rules.steepGainDm).toBe(STEEP_DM);
    expect(golden.rules.marks).toEqual(TRAIL_MARKS);
    const t = golden.tables;
    // counted = included segments (W-COV's set); a named trail's non-included segments are in the meta but never count
    const counted = Object.entries(META.segments).filter(([, s]) => s[3] === 1);
    expect(counted.length).toBe(t.countedSegments);
    expect(TABLES.quietSegments.size).toBe(t.quietSegments);
    let nonQuietM = 0;
    for (const [id, s] of counted) if (!TABLES.quietSegments.has(id)) nonQuietM += s[0];
    expect(nonQuietM).toBeCloseTo(t.islandNonQuietM, 6);
    const nonQuiet = Object.keys(TABLES.gainDm).filter((id) => !TABLES.quietFeatures.has(id));
    expect(nonQuiet.length).toBe(t.nonQuietTrails);
    expect(nonQuiet.reduce((a, id) => a + TABLES.gainDm[id]!, 0) / 10).toBeCloseTo(t.nonQuietGainM, 6);
    expect([...TABLES.quietFeatures].sort()).toEqual([...t.quietFeatures].sort());
    expect([...TABLES.parks].sort()).toEqual([...t.parks].sort());
    const steep = nonQuiet
      .filter((id) => TABLES.gainDm[id]! >= STEEP_DM)
      .map((id) => ({ id, gainM: TABLES.gainDm[id]! / 10, park: TABLES.parkOf[id] ?? null }))
      .sort((a, b) => b.gainM - a.gainM || (a.id < b.id ? -1 : 1));
    expect(steep).toEqual(t.steepTrails.map((s) => ({ id: s.id, gainM: s.gainM, park: s.park })));
    const perPark = new Map<string, number>();
    for (const id of nonQuiet) {
      const p = TABLES.parkOf[id];
      if (p && TABLES.parks.has(p)) perPark.set(p, (perPark.get(p) ?? 0) + 1);
    }
    expect([...perPark].filter(([, n]) => n === 1).map(([p]) => p).sort()).toEqual([...t.singleTrailParks].sort());
    // every trail the dataset names has a gain; every park a trail names is a Play park or Pritchard Park
    for (const id of Object.keys(META.trails)) expect(TABLES.gainDm[id], id).toBeTypeOf("number");
    for (const p of new Set(Object.values(TABLES.parkOf))) expect(TABLES.parks.has(p) || p === "Pritchard Park", p).toBe(true);
  });

  it("the golden carries the G0 cases", () => {
    expect(golden.cases.length).toBeGreaterThanOrEqual(20);
    expect(golden.challengeCases.length).toBeGreaterThanOrEqual(10);
    expect(golden.mileCases.length).toBeGreaterThanOrEqual(1);
  });

  describe("golden inputs and keys equal Kotlin", () => {
    for (const c of golden.cases) {
      it(c.name, () => {
        const inputs = trailInputs("trails", c.input.coverage);
        expect(inputs.nonQuietCoveredM).toBeCloseTo(c.expectInputs.nonQuietCoveredM, 6);
        expect(inputs.touchedTrails).toEqual(c.expectInputs.touchedTrails);
        expect(inputs.touchedParks).toEqual(c.expectInputs.touchedParks);
        const qualifying = qualifyingKeys(inputs, c.input.completed, c.input.islandWhole, TABLES);
        const owed = owedKeys(qualifying, new Set(c.paidBefore ?? []));
        expect(owed.map((k) => k.key)).toEqual(c.expectNewKeys);
        const marks: Record<TrailKind, number> = { climb: 0, high_ground: 0, milestone: 0, park_first: 0, park_complete: 0, island: 0 };
        for (const k of owed) marks[kindOf(k.key)!] += k.marks;
        expect(marks).toEqual(c.expectMarks);
      });
    }
  });

  describe("challenge targets (D5)", () => {
    for (const c of golden.challengeCases) {
      it(c.name, () => {
        // the count, as production passes it (`personalTarget`: the Worker keeps no park names, review P1-A)
        const progress = typeof c.progress === "number" ? c.progress : c.progress.parks.length;
        const target = challengeTarget(c.metric, c.default, c.completed, progress, TABLES);
        expect(target).toBe(c.expectTarget);
        expect(target > 0 && progress >= target).toBe(c.expectClaim);
      });
    }
  });

  describe("mile ladder cap (review P2-A)", () => {
    for (const c of golden.mileCases) {
      it(c.name, () => {
        const keys = qualifyingKeys({ nonQuietCoveredM: c.nonQuietCoveredM, touchedTrails: [], touchedParks: [] }, [], false, TABLES);
        const miles = [...keys].filter((k) => k.startsWith("mile:")).sort((a, b) => +a.slice(5) - +b.slice(5));
        expect(miles).toEqual(c.expectMileKeys);
      });
    }
  });

  it("kindOf and marks of every key kind; unknown keys pay nothing", () => {
    expect(kindOf("climb:250")).toBe("climb");
    expect(kindOf("high:trail-peters-trail")).toBe("high_ground");
    expect(kindOf("mile:5")).toBe("milestone");
    expect(kindOf("park1:Gazzam Lake Preserve")).toBe("park_first");
    expect(kindOf("parkc:Gazzam Lake Preserve")).toBe("park_complete");
    expect(kindOf("island")).toBe("island");
    expect(kindOf("island:x")).toBeNull(); // Kotlin `TrailRewards.kindOf`: `island` is the exact key only
    expect(kindOf("constructor:x")).toBeNull(); // own keys only, never a prototype member
    expect(kindOf("__proto__")).toBeNull();
    expect(kindOf("bogus:1")).toBeNull();
    expect(owedKeys(new Set(["island", "bogus:1"]), new Set())).toEqual([
      { key: "bogus:1", marks: 0 },
      { key: "island", marks: 1000 },
    ]);
  });

  it("roads has no trails tables: nothing qualifies", () => {
    expect(DERIVED.roads.trailRules).toBeNull();
    const roadsCov = new Map(Object.entries({ "rseg-000001": decodeBits("/w==")! }));
    expect(trailQualifying("roads", roadsCov, ["road-x"], 1e9)).toEqual(new Set());
    expect(trailInputs("roads", { "rseg-000001": 100 })).toEqual({ nonQuietCoveredM: 0, touchedTrails: [], touchedParks: [] });
  });

  it("coverage bits → covered metres → the same keys as the golden's raw metres (Hayano, island whole)", () => {
    const cov = new Map(Object.entries(trailFull(HAYANO)).map(([id, b]) => [id, decodeBits(b)!]));
    const metres = coveredMetres("trails", cov);
    expect(Object.keys(metres).sort()).toEqual([...META.trails[HAYANO]!].sort());
    // full bits = tickCount × tickLengthM, a hair under the segment length (never above it)
    for (const id of META.trails[HAYANO]!) {
      const [len, , tickLen] = META.segments[id]!;
      expect(metres[id]).toBeLessThanOrEqual(len);
      expect(metres[id]).toBeGreaterThan(len - tickLen);
    }
    expect([...trailQualifying("trails", cov, [HAYANO], 0)].sort()).toEqual(HAYANO_KEYS);
    expect(islandWhole("trails", META.islandTotalM)).toBe(true);
    expect(islandWhole("trails", META.islandTotalM * (1 - 1e-9))).toBe(true);
    expect(islandWhole("trails", META.islandTotalM - 0.01)).toBe(false);
    expect([...trailQualifying("trails", cov, [HAYANO], META.islandTotalM)].sort()).toEqual([...HAYANO_KEYS, "island"].sort());
  });
});

// ---------------------------------------------------------------------------------------------------------------
// PUT /v1/coverage ledger, totals, /me, deletion

type PlayRes = { accepted: string[]; rejected: { id: string; code: string }[]; capped: string[]; dayAccepted: string[]; total: number; week: number };
type Totals = { cov_marks: number; derived_marks: number; found_marks: number; week_marks: number; trail_marks: number; total: number };

let h: Harness;
let token: string;
let userId: string;

beforeEach(async () => {
  h = harness();
  h.clock.t = THU;
  ({ token, userId } = await devLogin(h));
});

const rows = async <T>(sql: string, ...args: unknown[]) => (await env.DB.prepare(sql).bind(...args).all<T>()).results;
const ledger = async (u = userId) => (await rows<{ key: string; marks: number }>("SELECT key, marks FROM play_trail_paid WHERE user_id = ? ORDER BY key", u));
const totalsRow = async (u = userId, dataset = "trails") =>
  env.DB.prepare("SELECT cov_marks, derived_marks, found_marks, week_marks, trail_marks, total FROM play_totals WHERE user_id = ? AND dataset = ?")
    .bind(u, dataset)
    .first<Totals>();

/** A pure upload (Play off): one new 1-hour walk, no `play`. */
async function syncOff(segments: Record<string, string>): Promise<Response> {
  h.clock.advanceHours(1);
  const res = await h.call("PUT", "/v1/coverage", { token, body: upload(segments, [{ id: crypto.randomUUID(), durationS: 3600 }]) });
  expect(res.status).toBe(200);
  return res;
}

/** A Play upload: one new 1-hour walk with an empty `play` element (walk-derived marks only), or none at all. */
async function syncOn(segments: Record<string, string>, opts: { walk?: boolean; element?: Record<string, unknown>; walkId?: string } = {}): Promise<PlayRes> {
  h.clock.advanceHours(1);
  const walkId = opts.walkId ?? crypto.randomUUID();
  const walks = opts.walk === false ? [] : [{ id: walkId, durationS: 3600 }];
  const play = opts.walk === false ? [] : [{ walkId, claims: [], ...(opts.element ?? {}) }];
  const res = await h.call("PUT", "/v1/coverage", { token, body: { ...upload(segments, walks), playConfig: PACK, play } });
  expect(res.status, await res.clone().text()).toBe(200);
  return ((await res.json()) as { play: PlayRes }).play;
}

const covOf = (segments: Record<string, string>) => new Map(Object.entries(segments).map(([id, b]) => [id, decodeBits(b)!]));
const walkMarks = (segments: Record<string, string>, completed: string[]) => {
  const w = walkDerivedMarks("trails", covOf(segments), completed);
  return w.cov + w.derived;
};

describe("trails ledger on PUT /v1/coverage (L3-1)", () => {
  it("first Play upload with prior completions credits each rule once", async () => {
    // Play off: the Hayano walk syncs, nothing Play-related exists (PF0)
    await syncOff(trailFull(HAYANO));
    expect(await ledger()).toEqual([]);
    expect(await totalsRow()).toBeNull();
    // Play on: the first Play upload (a new walk, no new coverage) pays park1 + parkc Moritani once
    const p = await syncOn({});
    expect(await ledger()).toEqual([
      { key: "park1:Moritani Preserve", marks: 50 },
      { key: "parkc:Moritani Preserve", marks: 150 },
    ]);
    const expected = walkMarks(trailFull(HAYANO), [HAYANO]) + 200;
    expect(p.total).toBe(expected);
    expect(await totalsRow()).toMatchObject({ found_marks: 0, week_marks: 0, trail_marks: 200, total: expected });
  });

  it("second upload pays 0", async () => {
    const first = await syncOn(trailFull(HAYANO));
    const before = await ledger();
    expect(before.map((r) => r.key)).toEqual(HAYANO_KEYS);
    const second = await syncOn({});
    expect(second.total).toBe(first.total);
    expect(await ledger()).toEqual(before);
    // the same walk and coverage again (a retry): still nothing more
    const third = await syncOn(trailFull(HAYANO));
    expect(third.total).toBe(first.total);
    expect(await ledger()).toEqual(before);
    expect((await totalsRow())!.trail_marks).toBe(200);
  });

  it("upload without play writes no ledger row", async () => {
    await syncOff(trailFull(HAYANO));
    await syncOff(trailFull(TRILLIUM));
    for (const t of ["play_trail_paid", "play_totals", "play_claims", "play_weeks", "play_progress"]) {
      expect(await rows(`SELECT * FROM ${t} WHERE user_id = ?`, userId), t).toEqual([]);
    }
    // playConfig alone does not switch Play on either
    h.clock.advanceHours(1);
    const res = await h.call("PUT", "/v1/coverage", { token, body: { ...upload({}, []), playConfig: PACK } });
    expect(res.status).toBe(200);
    expect(await rows("SELECT * FROM play_trail_paid WHERE user_id = ?", userId)).toEqual([]);
  });

  it("off-period progress paid once on next Play upload", async () => {
    const first = await syncOn(trailFull(HAYANO));
    expect((await ledger()).map((r) => r.key)).toEqual(HAYANO_KEYS);
    // Play off: Trillium completes; nothing is paid
    await syncOff(trailFull(TRILLIUM));
    expect((await ledger()).map((r) => r.key)).toEqual(HAYANO_KEYS);
    expect((await totalsRow())!.total).toBe(first.total);
    // Play on again: the keys earned meanwhile are paid once (no new coverage, no new walk: the no-change path)
    const back = await syncOn({}, { walk: false });
    expect((await ledger()).map((r) => r.key)).toEqual(BOTH_KEYS);
    expect(back.total).toBe(walkMarks({ ...trailFull(HAYANO), ...trailFull(TRILLIUM) }, [HAYANO, TRILLIUM]) + BOTH_MARKS);
    expect(await syncOn({}, { walk: false })).toMatchObject({ total: back.total });
    expect((await ledger()).length).toBe(BOTH_KEYS.length);
  });

  it("dataset drop lowers nothing", async () => {
    const first = await syncOn(trailFull(HAYANO));
    // keys a previous dataset paid for a trail and a park that no longer exist: never re-derived, never removed
    await env.DB.batch([
      env.DB.prepare("INSERT INTO play_trail_paid VALUES (?, 'trails', 'high:trail-not-in-dataset', 50)").bind(userId),
      env.DB.prepare("INSERT INTO play_trail_paid VALUES (?, 'trails', 'park1:Vanished Park', 50)").bind(userId),
    ]);
    const p = await syncOn({});
    expect((await ledger()).map((r) => r.key)).toEqual(["high:trail-not-in-dataset", "park1:Moritani Preserve", "park1:Vanished Park", "parkc:Moritani Preserve"]);
    expect(p.total).toBe(first.total + 100);
    expect((await totalsRow())!.trail_marks).toBe(300);
    // a dataset add pays only the new trail's keys (Trillium: high ground, park1 Grand Forest, and the mile its metres reach)
    const added = await syncOn(trailFull(TRILLIUM));
    expect(added.total).toBe(walkMarks({ ...trailFull(HAYANO), ...trailFull(TRILLIUM) }, [HAYANO, TRILLIUM]) + BOTH_MARKS + 100);
    expect((await ledger()).length).toBe(BOTH_KEYS.length + 2);
  });

  it("totals UPDATE path: total = cov + derived + found + week + trail_marks", async () => {
    // an existing totals row with found and week marks (a treasure claim + a dayKey on the rotary walk)
    const first = await syncOn(trailFull(ROTARY), { element: { claims: [{ id: `treasure-weekly-trails@${WEEK}#00`, kind: "treasure", weekId: WEEK }], dayKey: WEEK } });
    expect(first.accepted).toEqual([`treasure-weekly-trails@${WEEK}#00`]);
    expect(first.dayAccepted.length).toBe(1);
    const t1 = (await totalsRow())!;
    expect(t1).toMatchObject({ found_marks: 25, week_marks: 15, trail_marks: 50 });
    expect(t1.total).toBe(t1.cov_marks + t1.derived_marks + t1.found_marks + t1.week_marks + t1.trail_marks);
    expect((await ledger()).map((r) => r.key)).toEqual(["park1:Rotary Park"]);
    // the UPDATE path: Hayano adds 200 trail marks; found and week marks survive, walk-derived marks are MAX'd
    const second = await syncOn(trailFull(HAYANO));
    const t2 = (await totalsRow())!;
    expect(t2).toMatchObject({ found_marks: 25, week_marks: 15, trail_marks: 250 });
    expect(t2.cov_marks).toBeGreaterThanOrEqual(t1.cov_marks);
    expect(t2.derived_marks).toBeGreaterThan(t1.derived_marks);
    expect(t2.total).toBe(t2.cov_marks + t2.derived_marks + 25 + 15 + 250);
    expect(second.total).toBe(t2.total);
    const w = walkDerivedMarks("trails", covOf({ ...trailFull(ROTARY), ...trailFull(HAYANO) }), [ROTARY, HAYANO]);
    expect(t2).toMatchObject({ cov_marks: w.cov, derived_marks: w.derived });
  });

  it("no-change upload inserts ledger keys only", async () => {
    // Play off: the Hayano walk is credited
    const walkId = crypto.randomUUID();
    h.clock.advanceHours(1);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(trailFull(HAYANO), [{ id: walkId, durationS: 3600 }]) })).status).toBe(200);
    // Play on, the same walk and coverage with claims and a dayKey: no change to credit, so the batch runs outside the
    // compare-and-swap and may only pay the derived ledger keys (assertNoPlayWrites)
    const p = await syncOn(trailFull(HAYANO), {
      walkId,
      element: { claims: [{ id: `egg-station-s@${WEEK}`, kind: "egg", weekId: WEEK }], dayKey: WEEK },
    });
    expect(p.rejected).toEqual([{ id: `egg-station-s@${WEEK}`, code: "not_this_walk" }]);
    expect(p.dayAccepted).toEqual([]);
    expect((await ledger()).map((r) => r.key)).toEqual(HAYANO_KEYS);
    expect(await rows("SELECT * FROM play_claims WHERE user_id = ?", userId)).toEqual([]);
    expect(await rows("SELECT * FROM play_weeks WHERE user_id = ?", userId)).toEqual([]);
    expect(await totalsRow()).toMatchObject({ found_marks: 0, week_marks: 0, trail_marks: 200 });
    expect(p.total).toBe(walkMarks(trailFull(HAYANO), [HAYANO]) + 200);
  });

  it("me trails perKind and paid", async () => {
    await syncOn({ ...trailFull(HAYANO), ...trailFull(TRILLIUM) });
    const me = (await (await h.call("GET", "/v1/play/me?dataset=trails", { token })).json()) as Record<string, unknown>;
    expect(Object.keys(me)).toEqual(["dataset", "weekId", "total", "week", "perKind", "occ", "log", "paid"]);
    expect(me.perKind).toEqual({
      treasure: 0,
      egg: 0,
      riddle: 0,
      ink: 0,
      lore: 0,
      challenge: 0,
      climb: 0,
      high_ground: 1,
      milestone: 1,
      park_first: 2,
      park_complete: 1,
      island: 0,
    });
    expect(me.paid).toEqual(BOTH_KEYS);
    expect(me.total).toBe((await totalsRow())!.total);
    // the roads body is what it was: six claim kinds, no `paid`
    const roads = (await (await h.call("GET", "/v1/play/me?dataset=roads", { token })).json()) as Record<string, unknown>;
    expect(Object.keys(roads)).toEqual(["dataset", "weekId", "total", "week", "perKind", "occ", "log"]);
    expect(Object.keys(roads.perKind as object)).toEqual(["treasure", "egg", "riddle", "ink", "lore", "challenge"]);
  });

  it("delete removes ledger and progress", async () => {
    await syncOn(trailFull(HAYANO));
    await env.DB.prepare("INSERT INTO play_progress VALUES (?, 'trails', 'ch-parkhop@2026-10-31', 1)").bind(userId).run();
    expect((await ledger()).length).toBe(2);
    expect((await h.call("DELETE", "/v1/me", { token })).status).toBe(204);
    for (const t of ["play_trail_paid", "play_totals", "play_claims", "play_weeks", "play_progress"]) {
      expect(await rows(`SELECT * FROM ${t} WHERE user_id = ?`, userId), t).toEqual([]);
    }
  });
});
