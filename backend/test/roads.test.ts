import { createScheduledController, env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { METAS, parseDataset } from "../src/segments";
import { ADMIN, META, devLogin, fullBits, harness, trailFull, upload, type Harness } from "./helpers";

/**
 * RD-7: per-dataset scoping. Road ids are read from the generated meta (never hard-coded): the roads segment ids
 * change whenever the roads dataset is re-split and src/data/segments.roads.json is regenerated.
 */
const ROADS = METAS.roads;
const [ROAD, ROAD_SEGS] = Object.entries(ROADS.trails).find(([, s]) => s.length === 1)!;
const ROAD_SEG = ROAD_SEGS[0]!;
const ROTARY = "trail-rotary-park-trails";

type Metrics = { islandPct: number; islandCoveredM: number; islandTotalM: number; trailsCompleted: number; trailsTotal: number };
type CovRes = { dataset: string; datasetVersion: string; segments: Record<string, string>; completedTrailIds: string[]; metrics: Metrics };
type Entry = { userId: string; trailsCompleted: number; isYou: boolean };
type Board = { dataset: string; entries: Entry[]; you: Entry | null; totalRanked: number };

function roadUpload(segments: Record<string, string>, walks: { id: string; durationS: number }[] = []) {
  return { ...upload(segments, walks), dataset: "roads", datasetVersion: ROADS.version };
}
function roadFull(roadId: string): Record<string, string> {
  return Object.fromEntries(ROADS.trails[roadId]!.map((id) => [id, fullBits(ROADS.segments[id]![1])]));
}
const hourWalk = () => [{ id: crypto.randomUUID(), durationS: 3600 }];
async function syncRoad(h: Harness, token: string, segments: Record<string, string>, walks = hourWalk()) {
  h.clock.advanceHours(1);
  return h.call("PUT", "/v1/coverage", { token, body: roadUpload(segments, walks) });
}
async function syncTrail(h: Harness, token: string, segments: Record<string, string>, walks = hourWalk()) {
  h.clock.advanceHours(1);
  return h.call("PUT", "/v1/coverage", { token, body: upload(segments, walks) });
}

describe("roads segment meta (generated)", () => {
  it("keeps only counted roads over included rseg segments; island total = roads shippable", () => {
    expect(ROADS.dataset).toBe("roads");
    expect(Object.keys(ROADS.segments).length).toBeGreaterThan(0);
    for (const [id, s] of Object.entries(ROADS.segments)) {
      expect(id).toMatch(/^rseg-\d{6}$/);
      expect(s[3]).toBe(1);
    }
    for (const [id, segs] of Object.entries(ROADS.trails)) {
      expect(id).toMatch(/^road-/);
      for (const s of segs) expect(ROADS.segments[s], `${id} -> ${s}`).toBeDefined();
    }
    const included = Object.values(ROADS.segments).reduce((a, s) => a + s[0], 0);
    expect(Math.abs(included - ROADS.islandTotalM)).toBeLessThan(5);
    for (const id of Object.keys(META.segments)) expect(id).toMatch(/^seg-\d{6}$/);
  });

  it("parseDataset defaults to trails and rejects unknown values", () => {
    expect(parseDataset(undefined)).toBe("trails");
    expect(parseDataset("roads")).toBe("roads");
    expect(() => parseDataset("rivers")).toThrow(/dataset must be one of/);
  });
});

describe("coverage per dataset", () => {
  it("roads coverage is stored, returned and scored on its own; trails untouched", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    const r = await syncRoad(h, token, roadFull(ROAD));
    expect(r.status).toBe(200);
    const body = (await r.json()) as CovRes;
    expect(body.dataset).toBe("roads");
    expect(body.datasetVersion).toBe(ROADS.version);
    expect(body.segments).toEqual(roadFull(ROAD));
    expect(body.completedTrailIds).toEqual([ROAD]);
    expect(body.metrics.trailsCompleted).toBe(1);
    expect(body.metrics.trailsTotal).toBe(Object.keys(ROADS.trails).length);
    expect(body.metrics.islandTotalM).toBe(ROADS.islandTotalM);
    expect(body.metrics.islandCoveredM).toBeCloseTo(ROADS.segments[ROAD_SEG]![0], 0);

    const roads = (await (await h.call("GET", "/v1/coverage?dataset=roads", { token })).json()) as CovRes;
    expect(roads.segments).toEqual(body.segments);
    expect(roads.metrics).toEqual(body.metrics);
    const trails = (await (await h.call("GET", "/v1/coverage", { token })).json()) as CovRes;
    expect(trails.dataset).toBe("trails");
    expect(trails.segments).toEqual({});
    expect(trails.completedTrailIds).toEqual([]);
    expect(trails.metrics.islandCoveredM).toBe(0);
    // GET /v1/me keeps reporting the trails metrics
    const me = (await (await h.call("GET", "/v1/me", { token })).json()) as { metrics: Metrics };
    expect(me.metrics).toEqual(trails.metrics);
  });

  it("trails coverage never shows up in roads", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    expect((await syncTrail(h, token, trailFull(ROTARY))).status).toBe(200);
    const roads = (await (await h.call("GET", "/v1/coverage?dataset=roads", { token })).json()) as CovRes;
    expect(roads.segments).toEqual({});
    expect(roads.metrics.trailsCompleted).toBe(0);
  });

  it("rejects ids of the other dataset and bad dataset values (no cross-dataset rows)", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    const put = async (body: unknown, q = "") => {
      const r = await h.call("PUT", `/v1/coverage${q}`, { token, body });
      return { status: r.status, code: ((await r.json()) as { error?: { code: string } }).error?.code };
    };
    const trailSeg = META.trails[ROTARY]![0]!;
    expect(await put(roadUpload({ [trailSeg]: "AQ==" }, hourWalk()))).toEqual({ status: 400, code: "invalid_coverage" });
    expect(await put(upload({ [ROAD_SEG]: "AQ==" }, hourWalk()))).toEqual({ status: 400, code: "invalid_coverage" });
    expect(await put({ ...roadUpload({}), dataset: "rivers" })).toEqual({ status: 400, code: "invalid_dataset" });
    expect(await put(roadUpload({}), "?dataset=trails")).toEqual({ status: 400, code: "invalid_dataset" });
    expect((await h.call("GET", "/v1/coverage?dataset=nope", { token })).status).toBe(400);
    // the query works as an alternative to the body field
    const { dataset: _d, ...noField } = roadUpload({ [ROAD_SEG]: "AQ==" }, hourWalk());
    expect((await put(noField, "?dataset=roads")).status).toBe(200);
    const rows = await env.DB.prepare("SELECT segment_id FROM coverage WHERE user_id = ?").bind(userId).all<{ segment_id: string }>();
    expect(rows.results.map((r) => r.segment_id)).toEqual([ROAD_SEG]);
  });

  it("walk ids, plausibility and sync state are scoped per dataset", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    const walk = { id: crypto.randomUUID(), durationS: 3600 };
    expect((await syncTrail(h, token, trailFull(ROTARY), [walk])).status).toBe(200);
    // the same walk id is new for roads
    expect((await syncRoad(h, token, roadFull(ROAD), [walk])).status).toBe(200);
    const walks = await env.DB.prepare("SELECT dataset FROM walks WHERE user_id = ? ORDER BY dataset").bind(userId).all<{ dataset: string }>();
    expect(walks.results.map((r) => r.dataset)).toEqual(["roads", "trails"]);
    // reusing it again within roads credits nothing, so new coverage is implausible
    const other = Object.keys(ROADS.segments).find((id) => id !== ROAD_SEG)!;
    const again = await syncRoad(h, token, { [other]: "AQ==" }, [walk]);
    expect(again.status).toBe(422);
    const m = await env.DB.prepare("SELECT dataset, walk_count, backfilled FROM user_metrics WHERE user_id = ? ORDER BY dataset").bind(userId).all();
    expect(m.results).toEqual([
      { dataset: "roads", walk_count: 1, backfilled: 1 },
      { dataset: "trails", walk_count: 1, backfilled: 1 },
    ]);
  });

  it("road completion is sticky per dataset and stored with its dataset", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    await syncRoad(h, token, roadFull(ROAD));
    await syncTrail(h, token, trailFull(ROTARY));
    const done = await env.DB.prepare("SELECT dataset, trail_id FROM user_completed WHERE user_id = ? ORDER BY dataset").bind(userId).all();
    expect(done.results).toEqual([
      { dataset: "roads", trail_id: ROAD },
      { dataset: "trails", trail_id: ROTARY },
    ]);
    const fr = await env.DB.prepare("SELECT dataset, trail_id FROM user_trails WHERE user_id = ? ORDER BY dataset").bind(userId).all();
    expect(fr.results).toEqual([
      { dataset: "roads", trail_id: ROAD },
      { dataset: "trails", trail_id: ROTARY },
    ]);
  });
});

describe("leaderboard and stats per dataset", () => {
  it("roads board ranks roads walkers only; shared hide applies to every board", async () => {
    const h = harness();
    const roader = await devLogin(h, "rd-roader");
    const trailer = await devLogin(h, "rd-trailer");
    await syncRoad(h, roader.token, roadFull(ROAD));
    await syncTrail(h, trailer.token, trailFull(ROTARY));
    await syncTrail(h, roader.token, trailFull(ROTARY));
    await h.call("POST", "/v1/admin/snapshot", ADMIN);

    const roads = (await (await h.call("GET", "/v1/leaderboard?dataset=roads", { token: trailer.token })).json()) as Board;
    expect(roads.dataset).toBe("roads");
    expect(roads.entries.some((e) => e.userId === roader.userId)).toBe(true);
    expect(roads.entries.some((e) => e.userId === trailer.userId)).toBe(false);
    expect(roads.you).toBeNull(); // trailer has no roads coverage
    expect(roads.entries.find((e) => e.userId === roader.userId)!.trailsCompleted).toBe(1);
    const trails = (await (await h.call("GET", "/v1/leaderboard", { token: roader.token })).json()) as Board;
    expect(trails.dataset).toBe("trails");
    expect(trails.entries.some((e) => e.userId === trailer.userId)).toBe(true);
    expect(trails.you!.userId).toBe(roader.userId);
    expect((await h.call("GET", "/v1/leaderboard?dataset=x")).status).toBe(400);

    // hide-me is user-level: gone from both boards at once
    await h.call("PUT", "/v1/me", { token: roader.token, body: { hiddenFromBoard: true } });
    for (const q of ["", "?dataset=roads"]) {
      const b = (await (await h.call("GET", `/v1/leaderboard${q}`)).json()) as Board;
      expect(b.entries.some((e) => e.userId === roader.userId), q).toBe(false);
    }
  });

  it("GET /v1/stats?dataset=roads lists counted roads; /v1/trails/stats stays trails", async () => {
    const h = harness();
    const roads = (await (await h.call("GET", "/v1/stats?dataset=roads")).json()) as { dataset: string; trails: Record<string, unknown> };
    expect(roads.dataset).toBe("roads");
    expect(Object.keys(roads.trails).sort()).toEqual(Object.keys(ROADS.trails).sort());
    const legacy = (await (await h.call("GET", "/v1/trails/stats")).json()) as { dataset: string; trails: Record<string, unknown> };
    const dflt = (await (await h.call("GET", "/v1/stats")).json()) as typeof legacy;
    expect(legacy.dataset).toBe("trails");
    expect(Object.keys(legacy.trails)).toEqual(Object.keys(META.trails));
    expect(dflt).toEqual(legacy);
    expect((await h.call("GET", "/v1/stats?dataset=bogus")).status).toBe(400);
  });

  it("health reports both dataset versions", async () => {
    const j = (await (await harness().call("GET", "/v1/health")).json()) as { datasetVersion: string; datasets: Record<string, { datasetVersion: string }> };
    expect(j.datasetVersion).toBe(META.version);
    expect(j.datasets.roads!.datasetVersion).toBe(ROADS.version);
  });
});

describe("lifecycle across datasets", () => {
  it("DELETE /v1/me clears every dataset", async () => {
    const h = harness();
    const u = await devLogin(h, "rd-del");
    await syncRoad(h, u.token, roadFull(ROAD));
    await syncTrail(h, u.token, trailFull(ROTARY));
    await h.call("POST", "/v1/admin/snapshot", ADMIN);
    expect((await h.call("DELETE", "/v1/me", { token: u.token })).status).toBe(204);
    for (const t of ["user_metrics", "coverage", "user_trails", "user_completed", "walks", "leaderboard_snapshot"]) {
      const r = await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${t} WHERE user_id = ?`).bind(u.userId).first<{ n: number }>();
      expect(r!.n, t).toBe(0);
    }
  });

  it("the cron recomputes stale roads metrics and snapshots both datasets", async () => {
    const worker = (await import("../src/index")).default;
    const h = harness();
    const { token, userId } = await devLogin(h, "rd-cron");
    await syncRoad(h, token, roadFull(ROAD));
    await env.DB.prepare("UPDATE user_metrics SET metrics_version = 'old', island_m = 0 WHERE user_id = ? AND dataset = 'roads'").bind(userId).run();
    await worker.scheduled(createScheduledController({ scheduledTime: new Date(h.clock.t), cron: "17 9 * * *" }), env);
    const m = await env.DB.prepare("SELECT metrics_version, island_m FROM user_metrics WHERE user_id = ? AND dataset = 'roads'")
      .bind(userId)
      .first<{ metrics_version: string; island_m: number }>();
    expect(m!.metrics_version).toBe(ROADS.version);
    expect(m!.island_m).toBeGreaterThan(0);
    const board = (await (await h.call("GET", "/v1/leaderboard?dataset=roads")).json()) as Board;
    expect(board.entries.some((e) => e.userId === userId)).toBe(true);
  });
});
