import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { computeMetrics, decodeBits, encodeBits, fitsTicks, maskTicks, or, popcount } from "../src/coverage";
import { META, bitsOf, devLogin, fullBits, harness, trailFull, upload, walkFor } from "./helpers";

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
  it("requires auth", async () => {
    expect((await harness().call("GET", "/v1/coverage")).status).toBe(401);
  });

  it("unions with stored bits (OR) and returns merged state for new devices", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const r1 = await h.call("PUT", "/v1/coverage", { token, body: upload({ [ROTARY_SEG]: bitsOf([0, 1]) }) });
    expect(r1.status).toBe(200);
    const r2 = await h.call("PUT", "/v1/coverage", { token, body: upload({ [ROTARY_SEG]: bitsOf([1, 2, 6]) }) });
    const j2 = (await r2.json()) as CovRes;
    expect(j2.segments[ROTARY_SEG]).toBe(bitsOf([0, 1, 2, 6]));
    const g = (await (await h.call("GET", "/v1/coverage", { token })).json()) as CovRes;
    expect(g.segments).toEqual({ [ROTARY_SEG]: bitsOf([0, 1, 2, 6]) });
    expect(g.datasetVersion).toBe(META.version);
    expect(g.tickM).toBe(5);
    // a smaller upload never removes bits
    const r3 = (await (await h.call("PUT", "/v1/coverage", { token, body: upload({}) })).json()) as CovRes;
    expect(r3.segments[ROTARY_SEG]).toBe(bitsOf([0, 1, 2, 6]));
    expect(r3.newlyCoveredM).toBe(0);
  });

  it("recomputes metrics server-side and stores per-trail fractions", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    const res = (await (await h.call("PUT", "/v1/coverage", { token, body: upload({ ...trailFull(ROTARY), ...trailFull(WHARF) }) })).json()) as CovRes;
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
    const [r1, r2] = await Promise.all([
      h.call("PUT", "/v1/coverage", { token, body: upload({ [segs[0]!]: "AQ==" }) }),
      h.call("PUT", "/v1/coverage", { token, body: upload({ [segs[1]!]: "AQ==" }) }),
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
    const write = env.DB.prepare("INSERT INTO coverage (user_id, segment_id, bits, updated_at) VALUES (?, 'seg-x', 'AQ==', 0)").bind(userId);
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
    const other = await put({ ...upload({ [ROTARY_SEG]: bitsOf([0, 9]) }), datasetVersion: "2026-01-01+old" });
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
    // well-formed id that does not count (not included, not on a trail): accepted but ignored
    const ignored = await put(upload({ "seg-000001": "AQ==" }));
    expect(ignored.status).toBe(200);
    expect(ignored.body.ignoredSegments).toEqual(["seg-000001"]);
    expect(ignored.body.segments["seg-000001"]).toBeUndefined();
  });

  it("rejects oversized bodies", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const res = await h.call("PUT", "/v1/coverage", { token, body: "x".repeat(600 * 1024) });
    expect(res.status).toBe(413);
  });
});

describe("plausibility limits (TL-2)", () => {
  it("allows 500 m of slack without walks; more needs new walk durations at <= 4 m/s", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const small = includedUpTo(400);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(small) })).status).toBe(200);
    const big = includedUpTo(3000, new Set(Object.keys(small)));
    const r = await h.call("PUT", "/v1/coverage", { token, body: upload(big) });
    expect(r.status).toBe(422);
    const e = (await r.json()) as ErrRes;
    expect(e.error.code).toBe("implausible");
    expect(e.error.allowedM).toBe(500);
    expect(e.error.newlyCoveredM).toBeGreaterThan(3000);
    // nothing stored on rejection
    const g = (await (await h.call("GET", "/v1/coverage", { token })).json()) as CovRes;
    expect(Object.keys(g.segments).sort()).toEqual(Object.keys(small).sort());
    // a walk of ~12.5 min (750 s x 4 m/s = 3000 m) + 500 m slack is not enough for > 3.5 km...
    const tooShort = { id: crypto.randomUUID(), durationS: 600 };
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(big, [tooShort]) })).status).toBe(422);
    // ...a long enough walk is
    const walk = walkFor(4000);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(big, [walk]) })).status).toBe(200);
  });

  it("credits each walk id once (dedup per user)", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const walk = walkFor(5000);
    const a = includedUpTo(4000);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(a, [walk]) })).status).toBe(200);
    const b = includedUpTo(4000, new Set(Object.keys(a)));
    // same walk again: already credited -> only the 500 m slack
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(b, [walk, walk]) })).status).toBe(422);
    expect((await h.call("PUT", "/v1/coverage", { token, body: upload(b, [walk, walkFor(4000)]) })).status).toBe(200);
  });

  it("first-sign-in backfill with all local walks", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const all = includedUpTo(Infinity);
    const walks = Array.from({ length: 40 }, () => ({ id: crypto.randomUUID(), durationS: 3600 })); // 40 h x 4 m/s = 576 km
    const r = await h.call("PUT", "/v1/coverage", { token, body: upload(all, walks) });
    expect(r.status).toBe(200);
    const j = (await r.json()) as CovRes;
    expect(j.metrics.islandPct).toBe(99.9); // 105290.8 / 105396 = 99.900...% floored
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
    const r = (await (await h.call("PUT", "/v1/coverage", { token, body: upload(trailFull(ROTARY)) })).json()) as CovRes;
    expect(r.completedTrailIds).toContain(ROTARY);
    expect(r.metrics.trailsCompleted).toBe(r.completedTrailIds.length);
    // a completion recorded earlier (e.g. under an older dataset) stays counted
    await env.DB.prepare("INSERT INTO user_completed (user_id, trail_id) VALUES (?, 'trail-retired-in-new-dataset')").bind(userId).run();
    const r2 = (await (await h.call("PUT", "/v1/coverage", { token, body: upload(trailFull(WHARF)) })).json()) as CovRes;
    expect(r2.completedTrailIds).toEqual([ROTARY, "trail-retired-in-new-dataset", WHARF].sort());
    expect(r2.metrics.trailsCompleted).toBe(3);
    const g = (await (await h.call("GET", "/v1/coverage", { token })).json()) as CovRes;
    expect(g.completedTrailIds).toEqual(r2.completedTrailIds);
  });
});
