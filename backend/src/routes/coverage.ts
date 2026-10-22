import {
  type Bits,
  type CoverageMap,
  COMPLETION_THRESHOLD,
  bitsEqual,
  computeMetrics,
  decodeBits,
  fitsTicks,
  maskTicks,
  or,
  toJsonObject,
} from "../coverage";
import type { Deps } from "../deps";
import { ApiError, KB, badRequest, isObject, json, readJson } from "../http";
import {
  MAX_STORED_WALKS,
  MAX_WALKS_BACKFILL,
  MAX_WALKS_PER_UPLOAD,
  MAX_WALK_DURATION_S,
  WALK_TIME_TOLERANCE_S,
  allowedGrowthM,
  limitsFrom,
} from "../plausibility";
import { type Evaluation, assertNoPlayWrites, evaluatePlay, loadPlayState, parsePlay, playResponse, playStatements } from "../play/claims";
import { packByVersion } from "../play/config";
import { walkDerivedMarks } from "../play/marks";
import { trailQualifying } from "../play/trails";
import { LIMITS, rateLimit } from "../ratelimit";
import { type Dataset, METAS, type SegmentMeta, parseDataset } from "../segments";
import { type MetricsRow, type UserRow, completedSet, getMetricsRow, islandPct, islandRank, loadCoverage, metricsFor } from "../users";
import { round } from "../util";

export const MAX_COVERAGE_BODY = 512 * KB;
const WALK_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_SEGMENTS = 20_000;
const CAS_RETRIES = 3;

type MetricsFields = Pick<MetricsRow, "island_m" | "covered_m" | "trails_completed">;

/** Metrics as the client sees them. Field names are shared by both datasets (roads: trails* = roads). */
export function metricsJson(meta: SegmentMeta, u: MetricsFields) {
  return {
    islandPct: islandPct(meta, u.island_m),
    islandCoveredM: round(u.island_m, 1),
    islandTotalM: meta.islandTotalM,
    coveredM: round(u.covered_m, 1),
    trailsCompleted: u.trails_completed,
    trailsTotal: Object.keys(meta.trails).length,
  };
}

function coverageBody(meta: SegmentMeta, cov: CoverageMap, completed: string[], u: MetricsFields) {
  return {
    dataset: meta.dataset,
    datasetVersion: meta.version,
    tickM: meta.tickM,
    segments: toJsonObject(cov),
    completedTrailIds: completed,
    metrics: metricsJson(meta, u),
  };
}

/** GET /v1/coverage?dataset=trails|roads (default trails). */
export async function getCoverage(url: URL, env: Env, deps: Deps, user: UserRow): Promise<Response> {
  const dataset = parseDataset(url.searchParams.get("dataset") ?? undefined);
  const m = await metricsFor(env, user.id, dataset, deps.now());
  const [cov, completed] = await Promise.all([loadCoverage(env, user.id, dataset), completedSet(env, user.id, dataset)]);
  return json(coverageBody(METAS[dataset], cov, completed, m));
}

interface Walk {
  id: string;
  durationS: number;
}

interface ParsedUpload {
  dataset: Dataset;
  segments: Map<string, Bits>;
  walks: Walk[];
  ignored: string[];
}

/** `dataset` from the body, else the query, else trails; both present and different is an error. */
function uploadDataset(body: Record<string, unknown>, url: URL): Dataset {
  const q = url.searchParams.get("dataset");
  const fromBody = body.dataset === undefined ? undefined : parseDataset(body.dataset);
  const fromQuery = q === null ? undefined : parseDataset(q);
  if (fromBody && fromQuery && fromBody !== fromQuery) throw new ApiError(400, "invalid_dataset", "dataset in body and query differ");
  return fromBody ?? fromQuery ?? "trails";
}

function parseUpload(body: unknown, url: URL): Omit<ParsedUpload, "walks"> & { rawWalks: unknown } {
  if (!isObject(body)) throw badRequest("body must be an object");
  const dataset = uploadDataset(body, url);
  const meta = METAS[dataset];
  if (typeof body.datasetVersion !== "string" || body.datasetVersion.length > 64) {
    throw new ApiError(400, "invalid_coverage", "datasetVersion must be a string");
  }
  // TL-7: any dataset version is accepted; the server computes with its bundled dataset.
  const sameDataset = body.datasetVersion === meta.version;
  if (body.tickM !== meta.tickM) throw new ApiError(400, "invalid_coverage", `tickM must be ${meta.tickM}`);
  if (!isObject(body.segments)) throw new ApiError(400, "invalid_coverage", "segments must be an object");
  const entries = Object.entries(body.segments);
  if (entries.length > MAX_SEGMENTS) throw new ApiError(400, "invalid_coverage", "too many segments");
  const segments = new Map<string, Bits>();
  const ignored: string[] = [];
  for (const [id, v] of entries) {
    // RD-7: only this dataset's id prefix (seg- / rseg-), so one dataset's upload never writes the other's rows
    if (!meta.segmentId.test(id)) {
      throw new ApiError(400, "invalid_coverage", `unknown ${dataset} segment id ${JSON.stringify(id.slice(0, 40))}`);
    }
    if (typeof v !== "string") throw new ApiError(400, "invalid_coverage", `segment ${id}: bits must be a base64 string`);
    let bits = decodeBits(v);
    if (bits === null) throw new ApiError(400, "invalid_coverage", `segment ${id}: invalid base64`);
    const seg = meta.segments[id];
    if (!seg) {
      // well-formed id that does not count for metrics (not included, not on a named trail/road): accepted, not stored
      ignored.push(id);
      continue;
    }
    if (!fitsTicks(bits, seg[1])) {
      if (sameDataset) throw new ApiError(400, "invalid_coverage", `segment ${id}: bits beyond tick count ${seg[1]}`);
      bits = maskTicks(bits, seg[1]); // other dataset version: geometry may differ, drop out-of-range ticks
    }
    if (bits.length > 0) segments.set(id, bits);
  }
  return { dataset, segments, ignored, rawWalks: body.walks };
}

function parseWalks(raw: unknown, maxWalks: number): Walk[] {
  const walks: Walk[] = [];
  if (raw === undefined) return walks;
  if (!Array.isArray(raw)) throw new ApiError(400, "invalid_coverage", "walks must be an array");
  if (raw.length > maxWalks) throw new ApiError(400, "too_many_walks", `at most ${maxWalks} walks per upload`, { maxWalks });
  const seen = new Set<string>();
  for (const w of raw) {
    if (!isObject(w) || typeof w.id !== "string" || !WALK_ID.test(w.id.toLowerCase())) {
      throw new ApiError(400, "invalid_coverage", "walk id must be a UUID");
    }
    if (typeof w.durationS !== "number" || !Number.isFinite(w.durationS) || w.durationS < 0) {
      throw new ApiError(400, "invalid_coverage", "walk durationS must be a non-negative number");
    }
    const id = w.id.toLowerCase();
    if (seen.has(id)) continue;
    seen.add(id);
    walks.push({ id, durationS: Math.min(Math.round(w.durationS), MAX_WALK_DURATION_S) });
  }
  return walks;
}

/** Walks whose ids this user has not been credited for in this dataset (one query via json_each). */
async function newWalks(env: Env, userId: string, dataset: Dataset, walks: Walk[]): Promise<Walk[]> {
  if (walks.length === 0) return [];
  const { results } = await env.DB.prepare(
    "SELECT j.value AS id FROM json_each(?3) j JOIN walks_v2 w ON w.user_id = ?1 AND w.dataset = ?2 AND w.walk_id = j.value",
  )
    .bind(userId, dataset, JSON.stringify(walks.map((w) => w.id)))
    .run<{ id: string }>();
  const known = new Set(results.map((r) => r.id));
  return walks.filter((w) => !known.has(w.id));
}

const CAS_FAILED = /NOT NULL constraint failed/i;

export async function putCoverage(request: Request, url: URL, env: Env, deps: Deps, user: UserRow): Promise<Response> {
  const body = await readJson(request, MAX_COVERAGE_BODY);
  // P2-7: one budget per dataset (each app syncs on its own); counted before the costly validation
  const dataset = isObject(body) ? uploadDataset(body, url) : "trails";
  await rateLimit(env, deps.now(), `cov:${dataset}:${user.id}`, LIMITS.coveragePerUser.limit, LIMITS.coveragePerUser.windowMs);
  const parsed = parseUpload(body, url);
  const meta = METAS[dataset];
  let m: MetricsRow = await metricsFor(env, user.id, dataset, deps.now());
  const upload: ParsedUpload = { ...parsed, walks: parseWalks(parsed.rawWalks, m.backfilled ? MAX_WALKS_PER_UPLOAD : MAX_WALKS_BACKFILL) };
  // Play (schedule.md § 7.2): parsed after the coverage fields; absent = a pure upload with today's exact response
  const play = parsePlay(body as Record<string, unknown>);
  const limits = limitsFrom(env);
  for (let attempt = 0; attempt <= CAS_RETRIES; attempt++) {
    if (attempt > 0) {
      const row = await getMetricsRow(env, user.id, dataset);
      if (!row) throw new ApiError(401, "unauthorized", "account no longer exists");
      m = row;
    }
    const now = deps.now();
    const [stored, sticky, fresh, playState] = await Promise.all([
      loadCoverage(env, user.id, dataset),
      completedSet(env, user.id, dataset),
      newWalks(env, user.id, dataset, upload.walks),
      play ? loadPlayState(env, user.id, dataset, play, now) : null,
    ]);
    if (m.walk_count + fresh.length > MAX_STORED_WALKS) {
      throw new ApiError(422, "walk_limit", `at most ${MAX_STORED_WALKS} walks per account`);
    }
    const merged: CoverageMap = new Map(stored);
    const changed: string[] = [];
    for (const [id, bits] of upload.segments) {
      const mb = or(stored.get(id), bits);
      if (!bitsEqual(mb, stored.get(id))) {
        merged.set(id, mb);
        changed.push(id);
      }
    }
    const before = computeMetrics(meta, stored);
    const after = changed.length > 0 ? computeMetrics(meta, merged) : before;
    const newM = after.coveredM - before.coveredM;
    const walkSeconds = fresh.reduce((s, w) => s + w.durationS, 0);
    // P1-3: walks credited since the last walk upload must fit in the elapsed server time
    if (m.last_walk_upload_at !== null && fresh.length > 0) {
      const elapsedS = Math.max(0, now - m.last_walk_upload_at) / 1000;
      if (walkSeconds > elapsedS + WALK_TIME_TOLERANCE_S) {
        throw new ApiError(422, "implausible", "walk durations exceed the time since the last upload", {
          newlyCoveredM: round(newM, 1),
          allowedM: 0,
        });
      }
    }
    const allowedM = allowedGrowthM(fresh.length, walkSeconds, limits);
    if (newM > allowedM + 1e-6) {
      throw new ApiError(422, "implausible", "coverage grew faster than plausible on foot for the walks sent", {
        newlyCoveredM: round(newM, 1),
        allowedM: round(allowedM, 1),
      });
    }

    // TL-7 sticky completions: union of the stored set and trails currently >= threshold
    const stickySet = new Set(sticky);
    const newlyCompleted: string[] = [];
    for (const [trailId, f] of after.trailFractions) {
      if (f >= COMPLETION_THRESHOLD && !stickySet.has(trailId)) {
        stickySet.add(trailId);
        newlyCompleted.push(trailId);
      }
    }
    const completed = [...stickySet].sort();

    // Claims are evaluated only once the coverage is plausible (a 422 above rejects them with it), against the
    // merged coverage, and written in the same batch as the walk credits.
    let playEval: Evaluation | null = null;
    let playStmts: D1PreparedStatement[] = [];
    if (play && playState) {
      playEval = evaluatePlay(play, {
        dataset,
        now,
        packFor: packByVersion,
        freshWalks: new Map(fresh.map((w) => [w.id, w.durationS])),
        coverage: merged,
        existing: playState.existing,
        existingZero: playState.existingZero,
        weeks: playState.weeks,
        newlyCompleted,
        progress: playState.progress,
        // trails rules (v1.1): keys the merged coverage, sticky completions and island metres qualify for; roads: none
        trailQualifying: dataset === "trails" ? trailQualifying(dataset, merged, completed, after.islandM) : undefined,
        trailPaid: playState.trailPaid,
      });
      playStmts = playStatements(env, user.id, dataset, playEval, walkDerivedMarks(dataset, merged, completed));
    }

    if (changed.length === 0 && fresh.length === 0 && newlyCompleted.length === 0) {
      if (!playEval) {
        return json({ ...coverageBody(meta, merged, completed, m), accepted: true, newlyCoveredM: 0, ignoredSegments: upload.ignored });
      }
      // nothing new to credit: no claim can be accepted fresh (no new walk). This batch runs outside the coverage_rev
      // compare-and-swap, so it may only refresh the monotone totals (MAX of walk-derived marks, + 0 found / week):
      // asserted before writing (review P2-14).
      assertNoPlayWrites(playEval);
      await env.DB.batch(playStmts);
      return json({
        ...coverageBody(meta, merged, completed, m),
        accepted: true,
        newlyCoveredM: 0,
        ignoredSegments: upload.ignored,
        play: await playResponse(env, user.id, dataset, now, playEval),
      });
    }

    const rank = islandRank(meta, after.islandM);
    const reachedAt = rank > m.island_rank || m.reached_at === null ? now : m.reached_at;
    const stmts: D1PreparedStatement[] = [
      // compare-and-swap on coverage_rev: on a concurrent merge the NOT NULL check fails and the batch rolls back
      env.DB.prepare(
        `UPDATE user_metrics SET coverage_rev = CASE WHEN coverage_rev = ?3 THEN coverage_rev + 1 ELSE NULL END,
           island_m = ?4, island_rank = ?5, covered_m = ?6, trails_completed = ?7, reached_at = ?8,
           metrics_version = ?9, backfilled = 1, walk_count = walk_count + ?10, last_walk_upload_at = ?11
         WHERE user_id = ?1 AND dataset = ?2`,
      ).bind(
        user.id,
        dataset,
        m.coverage_rev,
        after.islandM,
        rank,
        after.coveredM,
        completed.length,
        reachedAt,
        meta.version,
        fresh.length,
        fresh.length > 0 ? now : m.last_walk_upload_at,
      ),
    ];
    const upsertCov = env.DB.prepare(
      "INSERT INTO coverage (user_id, segment_id, bits) VALUES (?, ?, ?) ON CONFLICT (user_id, segment_id) DO UPDATE SET bits = excluded.bits",
    );
    const encoded = toJsonObject(merged);
    for (const id of changed) stmts.push(upsertCov.bind(user.id, id, encoded[id]!));
    const upsertTrail = env.DB.prepare(
      `INSERT INTO user_trails (user_id, trail_id, fraction, dataset) VALUES (?, ?, ?, ?)
       ON CONFLICT (user_id, trail_id) DO UPDATE SET fraction = excluded.fraction`,
    );
    for (const [trailId, f] of after.trailFractions) {
      if (f !== before.trailFractions.get(trailId)) stmts.push(upsertTrail.bind(user.id, trailId, f, dataset));
    }
    const insCompleted = env.DB.prepare("INSERT INTO user_completed (user_id, trail_id, dataset) VALUES (?, ?, ?) ON CONFLICT DO NOTHING");
    for (const t of newlyCompleted) stmts.push(insCompleted.bind(user.id, t, dataset));
    const insWalk = env.DB.prepare("INSERT INTO walks_v2 (user_id, dataset, walk_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING");
    for (const w of fresh) stmts.push(insWalk.bind(user.id, dataset, w.id));
    stmts.push(...playStmts);
    try {
      await env.DB.batch(stmts);
    } catch (e) {
      if (e instanceof Error && CAS_FAILED.test(e.message)) continue;
      throw e;
    }
    const updated = { island_m: after.islandM, covered_m: after.coveredM, trails_completed: completed.length };
    const res = {
      ...coverageBody(meta, merged, completed, updated),
      accepted: true,
      newlyCoveredM: round(newM, 1),
      ignoredSegments: upload.ignored,
    };
    return json(playEval ? { ...res, play: await playResponse(env, user.id, dataset, now, playEval) } : res);
  }
  // P2-9: too much contention
  throw new ApiError(503, "busy", "concurrent sync in progress; retry shortly", { retryAfterSeconds: 2 }, { "retry-after": "2" });
}
