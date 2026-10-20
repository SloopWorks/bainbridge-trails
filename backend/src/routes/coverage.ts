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
import { LIMITS, rateLimit } from "../ratelimit";
import { META } from "../segments";
import { type UserRow, completedSet, getUser, islandPct, islandRank, loadCoverage } from "../users";
import { round } from "../util";

export const MAX_COVERAGE_BODY = 512 * KB;
const SEGMENT_ID = /^seg-\d{6}$/;
const WALK_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_SEGMENTS = 20_000;
const CAS_RETRIES = 3;

export function metricsJson(u: Pick<UserRow, "island_m" | "covered_m" | "trails_completed">) {
  return {
    islandPct: islandPct(u.island_m),
    islandCoveredM: round(u.island_m, 1),
    islandTotalM: META.islandTotalM,
    coveredM: round(u.covered_m, 1),
    trailsCompleted: u.trails_completed,
    trailsTotal: Object.keys(META.trails).length,
  };
}

function coverageBody(cov: CoverageMap, completed: string[], u: Pick<UserRow, "island_m" | "covered_m" | "trails_completed">) {
  return { datasetVersion: META.version, tickM: META.tickM, segments: toJsonObject(cov), completedTrailIds: completed, metrics: metricsJson(u) };
}

export async function getCoverage(env: Env, user: UserRow): Promise<Response> {
  const [cov, completed] = await Promise.all([loadCoverage(env, user.id), completedSet(env, user.id)]);
  return json(coverageBody(cov, completed, user));
}

interface Walk {
  id: string;
  durationS: number;
}

interface ParsedUpload {
  segments: Map<string, Bits>;
  walks: Walk[];
  ignored: string[];
}

function parseUpload(body: unknown, maxWalks: number): ParsedUpload {
  if (!isObject(body)) throw badRequest("body must be an object");
  if (typeof body.datasetVersion !== "string" || body.datasetVersion.length > 64) {
    throw new ApiError(400, "invalid_coverage", "datasetVersion must be a string");
  }
  // TL-7: any dataset version is accepted; the server computes with its bundled dataset.
  const sameDataset = body.datasetVersion === META.version;
  if (body.tickM !== META.tickM) throw new ApiError(400, "invalid_coverage", `tickM must be ${META.tickM}`);
  if (!isObject(body.segments)) throw new ApiError(400, "invalid_coverage", "segments must be an object");
  const entries = Object.entries(body.segments);
  if (entries.length > MAX_SEGMENTS) throw new ApiError(400, "invalid_coverage", "too many segments");
  const segments = new Map<string, Bits>();
  const ignored: string[] = [];
  for (const [id, v] of entries) {
    if (!SEGMENT_ID.test(id)) throw new ApiError(400, "invalid_coverage", `unknown segment id ${JSON.stringify(id.slice(0, 40))}`);
    if (typeof v !== "string") throw new ApiError(400, "invalid_coverage", `segment ${id}: bits must be a base64 string`);
    let bits = decodeBits(v);
    if (bits === null) throw new ApiError(400, "invalid_coverage", `segment ${id}: invalid base64`);
    const meta = META.segments[id];
    if (!meta) {
      // well-formed id that does not count for metrics (not included, not on a named trail): accepted, not stored
      ignored.push(id);
      continue;
    }
    if (!fitsTicks(bits, meta[1])) {
      if (sameDataset) throw new ApiError(400, "invalid_coverage", `segment ${id}: bits beyond tick count ${meta[1]}`);
      bits = maskTicks(bits, meta[1]); // other dataset version: geometry may differ, drop out-of-range ticks
    }
    if (bits.length > 0) segments.set(id, bits);
  }
  const walks: Walk[] = [];
  if (body.walks !== undefined) {
    if (!Array.isArray(body.walks)) throw new ApiError(400, "invalid_coverage", "walks must be an array");
    if (body.walks.length > maxWalks) throw new ApiError(400, "too_many_walks", `at most ${maxWalks} walks per upload`, { maxWalks });
    const seen = new Set<string>();
    for (const w of body.walks) {
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
  }
  return { segments, walks, ignored };
}

/** Walks whose ids this user has not been credited for (one query via json_each). */
async function newWalks(env: Env, userId: string, walks: Walk[]): Promise<Walk[]> {
  if (walks.length === 0) return [];
  const { results } = await env.DB.prepare(
    "SELECT j.value AS id FROM json_each(?2) j JOIN walks w ON w.user_id = ?1 AND w.walk_id = j.value",
  )
    .bind(userId, JSON.stringify(walks.map((w) => w.id)))
    .run<{ id: string }>();
  const known = new Set(results.map((r) => r.id));
  return walks.filter((w) => !known.has(w.id));
}

const CAS_FAILED = /NOT NULL constraint failed/i;

export async function putCoverage(request: Request, env: Env, deps: Deps, user0: UserRow): Promise<Response> {
  await rateLimit(env, deps.now(), `cov:${user0.id}`, LIMITS.coveragePerUser.limit, LIMITS.coveragePerUser.windowMs);
  const upload = parseUpload(await readJson(request, MAX_COVERAGE_BODY), user0.backfilled ? MAX_WALKS_PER_UPLOAD : MAX_WALKS_BACKFILL);
  const limits = limitsFrom(env);
  let user = user0;
  for (let attempt = 0; attempt <= CAS_RETRIES; attempt++) {
    if (attempt > 0) {
      const row = await getUser(env, user.id);
      if (!row) throw new ApiError(401, "unauthorized", "account no longer exists");
      user = row;
    }
    const now = deps.now();
    const [stored, sticky, fresh] = await Promise.all([
      loadCoverage(env, user.id),
      completedSet(env, user.id),
      newWalks(env, user.id, upload.walks),
    ]);
    if (user.walk_count + fresh.length > MAX_STORED_WALKS) {
      throw new ApiError(422, "walk_limit", `at most ${MAX_STORED_WALKS} walks per account`);
    }
    const merged: CoverageMap = new Map(stored);
    const changed: string[] = [];
    for (const [id, bits] of upload.segments) {
      const m = or(stored.get(id), bits);
      if (!bitsEqual(m, stored.get(id))) {
        merged.set(id, m);
        changed.push(id);
      }
    }
    const before = computeMetrics(META, stored);
    const after = changed.length > 0 ? computeMetrics(META, merged) : before;
    const newM = after.coveredM - before.coveredM;
    const walkSeconds = fresh.reduce((s, w) => s + w.durationS, 0);
    // P1-3: walks credited since the last walk upload must fit in the elapsed server time
    if (user.last_walk_upload_at !== null && fresh.length > 0) {
      const elapsedS = Math.max(0, now - user.last_walk_upload_at) / 1000;
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

    if (changed.length === 0 && fresh.length === 0 && newlyCompleted.length === 0) {
      return json({ ...coverageBody(merged, completed, user), accepted: true, newlyCoveredM: 0, ignoredSegments: upload.ignored });
    }

    const rank = islandRank(after.islandM);
    const reachedAt = rank > user.island_rank || user.reached_at === null ? now : user.reached_at;
    const stmts: D1PreparedStatement[] = [
      // compare-and-swap on coverage_rev: on a concurrent merge the NOT NULL check fails and the batch rolls back
      env.DB.prepare(
        `UPDATE users SET coverage_rev = CASE WHEN coverage_rev = ?2 THEN coverage_rev + 1 ELSE NULL END,
           island_m = ?3, island_rank = ?4, covered_m = ?5, trails_completed = ?6, reached_at = ?7,
           metrics_version = ?8, backfilled = 1, walk_count = walk_count + ?9, last_walk_upload_at = ?10
         WHERE id = ?1`,
      ).bind(
        user.id,
        user.coverage_rev,
        after.islandM,
        rank,
        after.coveredM,
        completed.length,
        reachedAt,
        META.version,
        fresh.length,
        fresh.length > 0 ? now : user.last_walk_upload_at,
      ),
    ];
    const upsertCov = env.DB.prepare(
      "INSERT INTO coverage (user_id, segment_id, bits) VALUES (?, ?, ?) ON CONFLICT (user_id, segment_id) DO UPDATE SET bits = excluded.bits",
    );
    const encoded = toJsonObject(merged);
    for (const id of changed) stmts.push(upsertCov.bind(user.id, id, encoded[id]!));
    const upsertTrail = env.DB.prepare(
      "INSERT INTO user_trails (user_id, trail_id, fraction) VALUES (?, ?, ?) ON CONFLICT (user_id, trail_id) DO UPDATE SET fraction = excluded.fraction",
    );
    for (const [trailId, f] of after.trailFractions) {
      if (f !== before.trailFractions.get(trailId)) stmts.push(upsertTrail.bind(user.id, trailId, f));
    }
    const insCompleted = env.DB.prepare("INSERT INTO user_completed (user_id, trail_id) VALUES (?, ?) ON CONFLICT DO NOTHING");
    for (const t of newlyCompleted) stmts.push(insCompleted.bind(user.id, t));
    const insWalk = env.DB.prepare("INSERT INTO walks (user_id, walk_id) VALUES (?, ?) ON CONFLICT DO NOTHING");
    for (const w of fresh) stmts.push(insWalk.bind(user.id, w.id));
    try {
      await env.DB.batch(stmts);
    } catch (e) {
      if (e instanceof Error && CAS_FAILED.test(e.message)) continue;
      throw e;
    }
    const updated = { island_m: after.islandM, covered_m: after.coveredM, trails_completed: completed.length };
    return json({ ...coverageBody(merged, completed, updated), accepted: true, newlyCoveredM: round(newM, 1), ignoredSegments: upload.ignored });
  }
  // P2-9: too much contention
  throw new ApiError(503, "busy", "concurrent sync in progress; retry shortly", { retryAfterSeconds: 2 }, { "retry-after": "2" });
}
