import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { computeMetrics, decodeBits, encodeBits, fitsTicks, maskTicks, or, popcount, toJsonObject } from "../src/coverage";
import { META, bitsOf, devLogin, fullBits, harness, syncWalk, trailFull, upload, walkFor } from "./helpers";

type CovRes = {
  datasetVersion: string;
  tickM: number;
  segments: Record<string, string>;
  accepted?: boolean;
  newlyCoveredM?: number;
  ignoredSegments?: string[];
  completedTrailIds: string[];
  metrics: { islandPct: number; islandCoveredM: number; coveredM: number; trailsCompleted: number; trailsTotal: number };
};
type ErrRes = { error: { code: string; message: string; newlyCoveredM?: number; allowedM?: number } };

const ROTARY = "trail-rotary-park-trails"; // 1 segment, 7 ticks, 30.8 m, included
const ROTARY_SEG = META.trails[ROTARY]![0]!;
const WHARF = "trail-wharf-street-road-end"; // not included in island total

/** Included segments, in id order, until their length reaches `metres`. */
function includedUpTo(metres: number, skip = new Set<string>()): Record<string, string> {
  const out: Record<string, string> = {};
  let sum = 0;
  for (const [id, [len, n, , inc]] of Object.entries(META.segments)) {
    if (inc !== 1 || skip.has(id)) continue;
    if (sum >= metres) break;
    out[id] = fullBits(n);
    sum += len;
  }
  return out;
}

describe("bit encoding (CoverageState contract)", () => {
  it("matches fixtures asserted by the Kotlin CoverageState tests", () => {
    // app/coverage/src/commonTest/.../CoverTest.kt encodeBitOrderLittleEndian: ticks {0, 9} <-> {"seg-000001":"AQI="}
    expect(bitsOf([0, 9])).toBe("AQI=");
    expect([...decodeBits("AQI=")!]).toEqual([0x01, 0x02]);
    // CoverTest.encodeDecodeRoundTrip: EMPTY.encode() == "{}"
    expect(JSON.stringify(toJsonObject(new Map()))).toBe("{}");
    // hand-crafted from the algorithm (not Kotlin-emitted): sorted keys, empty segment omitted, 64+ ticks
    const m = new Map([
      ["seg-000010", decodeBits(bitsOf([63, 64, 65]))!],
      ["seg-000002", decodeBits(bitsOf([7]))!],
      ["seg-000005", new Uint8Array(0)],
    ]);
    // tick 7 -> byte0 0x80; ticks 63,64,65 -> byte7 0x80, byte8 0x03 (9 bytes, no trailing zero bytes)
    expect(JSON.stringify(toJsonObject(m))).toBe('{"seg-000002":"gA==","seg-000010":"AAAAAAAAAIAD"}');
  });

  it("little-endian ticks, trailing zero bytes dropped, OR union", () => {
    expect(bitsOf([0])).toBe("AQ==");
    expect(encodeBits(decodeBits("AQAA")!)).toBe("AQ==");
    expect(decodeBits("AAAA")!.length).toBe(0);
    const a = decodeBits(bitsOf([0, 9]))!;
    const b = decodeBits(bitsOf([1, 17]))!;
    expect(encodeBits(or(a, b))).toBe(bitsOf([0, 1, 9, 17]));
    expect(popcount(or(a, b))).toBe(4);
    expect(decodeBits("not base64!")).toBeNull();
  });
  it("fitsTicks rejects bits beyond the tick count", () => {
    expect(fitsTicks(decodeBits(bitsOf([6]))!, 7)).toBe(true);
    expect(fitsTicks(decodeBits(bitsOf([7]))!, 7)).toBe(false);
    expect(fitsTicks(decodeBits(bitsOf([8]))!, 8)).toBe(false);
    expect(fitsTicks(decodeBits(bitsOf([15]))!, 16)).toBe(true);
    expect(encodeBits(maskTicks(decodeBits(bitsOf([0, 6, 7, 12]))!, 7))).toBe(bitsOf([0, 6]));
  });
});

describe("metrics mirror Kotlin Progress", () => {
  it("trail completes at >= 0.85 of its length; island % counts included metres only", () => {
    const [len, n, tickLen] = META.segments[ROTARY_SEG]!;
    expect(n).toBe(7);
    const six = new Map([[ROTARY_SEG, decodeBits(bitsOf([0, 1, 2, 3, 4, 5]))!]]);
    const m6 = computeMetrics(META, six);
    expect(m6.trailFractions.get(ROTARY)).toBeCloseTo((6 * tickLen) / len, 9);
    expect(m6.trailFractions.get(ROTARY)!).toBeGreaterThanOrEqual(0.85);
    expect(m6.trailsCompleted).toBeGreaterThanOrEqual(1);
    expect(m6.islandM).toBeCloseTo(6 * tickLen, 9);
    expect(m6.islandFraction).toBeCloseTo((6 * tickLen) / 105396, 12);

    const five = computeMetrics(META, new Map([[ROTARY_SEG, decodeBits(bitsOf([0, 1, 2, 3, 4]))!]]));
    expect(five.trailFractions.get(ROTARY)!).toBeLessThan(0.85);
    expect(five.trailsCompleted).toBe(0);

    // full coverage is clamped to segment length
    const full = computeMetrics(META, new Map([[ROTARY_SEG, decodeBits(fullBits(7))!]]));
    expect(full.islandM).toBeLessThanOrEqual(len);
    expect(full.trailFractions.get(ROTARY)).toBeLessThanOrEqual(1);
  });

  it("non-included trail segments complete trails without moving island %", () => {
    const cov = new Map(Object.entries(trailFull(WHARF)).map(([k, v]) => [k, decodeBits(v)!]));
    const m = computeMetrics(META, cov);
    expect(m.islandM).toBe(0);
    expect(m.coveredM).toBeGreaterThan(40);
    expect(m.trailsCompleted).toBe(1);
  });
});

describe("PUT/GET /v1/coverage", () => {
  it("requires auth; wrong method is 405 before auth", async () => {
    const h = harness();
    expect((await h.call("GET", "/v1/coverage")).status).toBe(401);
    expect((await h.call("DELETE", "/v1/coverage")).status).toBe(405);
    expect((await h.call("POST", "/v1/me")).status).toBe(405);
  });

  it("unions with stored bits (OR) and returns merged state for new devices", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    expect((await syncWalk(h, token, { [ROTARY_SEG]: bitsOf([0, 1]) })).status).toBe(200);
    const j2 = (await (await syncWalk(h, token, { [ROTARY_SEG]: bitsOf([1, 2, 6]) })).json()) as CovRes;
    expect(j2.segments[ROTARY_SEG]).toBe(bitsOf([0, 1, 2, 6]));
    const g = (await (await h.call("GET", "/v1/coverage", { token })).json()) as CovRes;
    expect(g.segments).toEqual({ [ROTARY_SEG]: bitsOf([0, 1, 2, 6]) });
    expect(g.datasetVersion).toBe(META.version);
    expect(g.tickM).toBe(5);
    // a smaller upload never removes bits; already-known bits need no walk
    const r3 = (await (await h.call("PUT", "/v1/coverage", { token, body: upload({ [ROTARY_SEG]: bitsOf([0]) }) })).json()) as CovRes;
    expect(r3.segments[ROTARY_SEG]).toBe(bitsOf([0, 1, 2, 6]));
    expect(r3.newlyCoveredM).toBe(0);
  });

  it("recomputes metrics server-side and stores per-trail fractions", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    const res = (await (await syncWalk(h, token, { ...trailFull(ROTARY), ...trailFull(WHARF) })).json()) as CovRes;
    expect(res.metrics.trailsCompleted).toBe(2);
    expect(res.metrics.trailsTotal).toBe(Object.keys(META.trails).length);
    expect(res.metrics.islandCoveredM).toBeCloseTo(30.8, 0);
    expect(res.metrics.islandPct).toBe(0); // 0.029% floors to 0.0 (TL-5); islandCoveredM carries precision
    const rows = await env.DB.prepare("SELECT trail_id, fraction FROM user_trails WHERE user_id = ? ORDER BY trail_id").bind(userId).all();
    expect(rows.results.map((r) => r.trail_id)).toEqual([ROTARY, WHARF]);
    const me = (await (await h.call("GET", "/v1/me", { token })).json()) as { metrics: CovRes["metrics"] };
    expect(me.metrics.trailsCompleted).toBe(2);
  });

  it("concurrent uploads from two devices both land (compare-and-swap retry)", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const segs = Object.keys(includedUpTo(200));
    h.clock.advanceHours(2);
    const [r1, r2] = await Promise.all([
      h.call("PUT", "/v1/coverage", { token, body: upload({ [segs[0]!]: "AQ==" }, [walkFor(100)]) }),
      h.call("PUT", "/v1/coverage", { token, body: upload({ [segs[1]!]: "AQ==" }, [walkFor(100)]) }),
    ]);
    expect([r1.status, r2.status]).toEqual([200, 200]);
    const g = (await (await h.call("GET", "/v1/coverage", { token })).json()) as CovRes;
    expect(Object.keys(g.segments).sort()).toEqual([segs[0], segs[1]].sort());
  });

  it("a stale coverage_rev aborts the whole batch (CAS guard)", async () => {
    const h = harness();
    const { userId } = await devLogin(h);
    const stale = env.DB.prepare(
      "UPDATE users SET coverage_rev = CASE WHEN coverage_rev = ?2 THEN coverage_rev + 1 ELSE NULL END, island_m = 5 WHERE id = ?1",
    ).bind(userId, 99);
    const write = env.DB.prepare("INSERT INTO coverage (user_id, segment_id, bits) VALUES (?, 'seg-x', 'AQ==')").bind(userId);
    await expect(env.DB.batch([stale, write])).rejects.toThrow(/NOT NULL constraint failed/);
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM coverage WHERE user_id = ?").bind(userId).first<{ n: number }>();
    expect(n!.n).toBe(0);
  });

  it("validates uploads", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const put = async (body: unknown) => {
      const r = await h.call("PUT", "/v1/coverage", { token, body });
      return { status: r.status, body: (await r.json()) as ErrRes & CovRes };
    };
    // other dataset versions are accepted and computed with the bundled dataset; out-of-range ticks dropped
    const other = await put({ ...upload({ [ROTARY_SEG]: bitsOf([0, 9]) }, [walkFor(10)]), datasetVersion: "2026-01-01+old" });
    expect(other.status).toBe(200);
    expect(other.body.datasetVersion).toBe(META.version);
    expect(other.body.segments[ROTARY_SEG]).toBe(bitsOf([0]));
    expect((await put({ ...upload({}), datasetVersion: 5 })).status).toBe(400);
    expect((await put(upload({}, [{ id: "not-a-uuid", durationS: 60 }]))).status).toBe(400);
    expect((await put(upload({}, [{ id: crypto.randomUUID(), durationS: -1 }]))).status).toBe(400);
    expect((await put({ ...upload({}), tickM: 10 })).status).toBe(400);
    expect((await put(upload({ "../etc": "AQ==" }))).body.error.code).toBe("invalid_coverage");
    expect((await put(upload({ [ROTARY_SEG]: bitsOf([7]) }))).body.error.code).toBe("invalid_coverage");
    expect((await put(upload({ [ROTARY_SEG]: "%%%" }))).status).toBe(400);
    expect((await put(upload({ [ROTARY_SEG]: 5 as unknown as string }))).status).toBe(400);
    const ignored = await put(upload({ "seg-000001": "AQ==" }));
    expect(ignored.status).toBe(200);
    expect(ignored.body.ignoredSegments).toEqual(["seg-000001"]);
    expect(ignored.body.segments["seg-000001"]).toBeUndefined();
  });

  it("rejects oversized bodies", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    expect((await h.call("PUT", "/v1/coverage", { token, body: "x".repeat(600 * 1024) })).status).toBe(413);
  });

  it("stores no walk times/durations and no coverage timestamps (P0-1)", async () => {
    const cols = async (t: string) =>
      ((await env.DB.prepare(`SELECT name FROM pragma_table_info('${t}')`).all<{ name: string }>()).results.map((r) => r.name));
    expect(await cols("walks")).toEqual(["user_id", "walk_id"]);
    expect(await cols("coverage")).toEqual(["user_id", "segment_id", "bits"]);
  });
});

describe("plausibility limits (TL-2 + review P1-3)", () => {
  it("new coverage needs new walks: 4 m/s x duration + 500 m; no slack without walks", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const tiny = { [ROTARY_SEG]: bitsOf([0]) };
    const noWalk = await h.call("PUT", "/v1/coverage", { token, body: upload(tiny) });
    expect(noWalk.status).toBe(422);
    expect(((await noWalk.json()) as ErrRes).error.allowedM).toBe(0);
    const small = includedUpTo(400);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(small, [{ id: crypto.randomUUID(), durationS: 0 }]) })).status).toBe(200);
    h.clock.advanceHours(1);
    const big = includedUpTo(3000, new Set(Object.keys(small)));
    const r = await h.call("PUT", "/v1/coverage", { token, body: upload(big, [{ id: crypto.randomUUID(), durationS: 600 }]) });
    expect(r.status).toBe(422);
    const e = (await r.json()) as ErrRes;
    expect(e.error.code).toBe("implausible");
    expect(e.error.allowedM).toBe(4 * 600 + 500);
    // nothing stored on rejection
    const g = (await (await h.call("GET", "/v1/coverage", { token })).json()) as CovRes;
    expect(Object.keys(g.segments).sort()).toEqual(Object.keys(small).sort());
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(big, [walkFor(4000)]) })).status).toBe(200);
  });

  it("credits each walk id once (dedup per user)", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const walk = walkFor(5000);
    const a = includedUpTo(4000);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(a, [walk]) })).status).toBe(200);
    h.clock.advanceHours(3);
    const b = includedUpTo(4000, new Set(Object.keys(a)));
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(b, [walk, walk]) })).status).toBe(422);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(b, [walk, walkFor(4000)]) })).status).toBe(200);
  });

  it("caps each walk at 6 h and new walk time at the time since the last walk upload", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const first = includedUpTo(90_000);
    // one 100 h walk counts as 6 h = 86.4 km + 0.5 km < 90 km
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(first, [{ id: crypto.randomUUID(), durationS: 360_000 }]) })).status).toBe(422);
    const ok = includedUpTo(1000);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(ok, [walkFor(1000)]) })).status).toBe(200);
    // 30 min later, a "2 h" walk does not fit (tolerance 15 min)
    h.clock.advanceHours(0.5);
    const more = includedUpTo(500, new Set(Object.keys(ok)));
    const r = await h.call("PUT", "/v1/coverage", { token, body: upload(more, [{ id: crypto.randomUUID(), durationS: 7200 }]) });
    expect(r.status).toBe(422);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(more, [{ id: crypto.randomUUID(), durationS: 2400 }]) })).status).toBe(200);
  });

  it("first-sign-in backfill may carry up to 2000 walks; later uploads 200 (P0-2)", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const all = includedUpTo(Infinity);
    const walks = Array.from({ length: 300 }, () => ({ id: crypto.randomUUID(), durationS: 1800 })); // 150 h -> 2160 km
    const r = await h.call("PUT", "/v1/coverage", { token, body: upload(all, walks) });
    expect(r.status).toBe(200);
    expect(((await r.json()) as CovRes).metrics.islandPct).toBe(99.9); // 105290.8 / 105396 = 99.900...% floored
    const again = await h.call("PUT", "/v1/coverage", { token, body: upload({}, walks.slice(0, 201)) });
    expect(again.status).toBe(400);
    expect(((await again.json()) as ErrRes).error.code).toBe("too_many_walks");
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload({}, walks.slice(0, 200)) })).status).toBe(200);
  });

  it("caps stored walk ids per account", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    await env.DB.prepare("UPDATE users SET walk_count = 9999 WHERE id = ?").bind(userId).run();
    const r = await h.call("PUT", "/v1/coverage", { token, body: upload({}, [walkFor(1), walkFor(1)]) });
    expect(r.status).toBe(422);
    expect(((await r.json()) as ErrRes).error.code).toBe("walk_limit");
  });

  it("rate limits uploads per user", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    let last = 0;
    for (let i = 0; i < 31; i++) last = (await h.call("PUT", "/v1/coverage", { token, body: upload({}) })).status;
    expect(last).toBe(429);
  });
});

describe("sticky completions (TL-7)", () => {
  it("returns completedTrailIds and counts them", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    const r = (await (await syncWalk(h, token, trailFull(ROTARY))).json()) as CovRes;
    expect(r.completedTrailIds).toContain(ROTARY);
    expect(r.metrics.trailsCompleted).toBe(r.completedTrailIds.length);
    await env.DB.prepare("INSERT INTO user_completed (user_id, trail_id) VALUES (?, 'trail-retired-in-new-dataset')").bind(userId).run();
    const r2 = (await (await syncWalk(h, token, trailFull(WHARF))).json()) as CovRes;
    expect(r2.completedTrailIds).toEqual([ROTARY, "trail-retired-in-new-dataset", WHARF].sort());
    expect(r2.metrics.trailsCompleted).toBe(3);
    const g = (await (await h.call("GET", "/v1/coverage", { token })).json()) as CovRes;
    expect(g.completedTrailIds).toEqual(r2.completedTrailIds);
  });
});

describe("dataset updates (P1-7)", () => {
  it("recomputes stale metrics lazily on the user's own read", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    await syncWalk(h, token, trailFull(ROTARY));
    await env.DB.prepare("UPDATE users SET metrics_version = 'old', island_m = 0, island_rank = 0, trails_completed = 0 WHERE id = ?").bind(userId).run();
    const me = (await (await h.call("GET", "/v1/me", { token })).json()) as { metrics: CovRes["metrics"] };
    expect(me.metrics.islandCoveredM).toBeCloseTo(30.8, 0);
    expect(me.metrics.trailsCompleted).toBe(1);
    const row = await env.DB.prepare("SELECT metrics_version FROM users WHERE id = ?").bind(userId).first<{ metrics_version: string }>();
    expect(row!.metrics_version).toBe(META.version);
  });
});
