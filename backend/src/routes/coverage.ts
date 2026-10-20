import type { UserRow } from "../auth";
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
import { MAX_WALK_DURATION_S, allowedGrowthM, limitsFrom } from "../plausibility";
import { LIMITS, rateLimit } from "../ratelimit";
import { META } from "../segments";
import { floorTo, round } from "../util";

export const MAX_COVERAGE_BODY = 512 * KB;
const SEGMENT_ID = /^seg-\d{6}$/;
const WALK_ID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const MAX_SEGMENTS = 20_000;
const MAX_WALKS = 5_000;

async function loadCoverage(env: Env, userId: string): Promise<CoverageMap> {
  const { results } = await env.DB.prepare("SELECT segment_id, bits FROM coverage WHERE user_id = ?")
    .bind(userId)
    .run<{ segment_id: string; bits: string }>();
  const out: CoverageMap = new Map();
  for (const r of results) {
    const b = decodeBits(r.bits);
    if (b && b.length > 0) out.set(r.segment_id, b);
  }
  return out;
}

/** Island % is floored to 1 decimal (TL-5): never shows 100.0 before it is. */
export function islandPct(islandM: number): number {
  if (META.islandTotalM <= 0) return 0;
  return floorTo(Math.min(100, (islandM / META.islandTotalM) * 100), 1);
}

export function metricsJson(m: { islandM: number; coveredM: number; trailsCompleted: number }) {
  return {
    islandPct: islandPct(m.islandM),
    islandCoveredM: round(m.islandM, 1),
    islandTotalM: META.islandTotalM,
    coveredM: round(m.coveredM, 1),
    trailsCompleted: m.trailsCompleted,
    trailsTotal: Object.keys(META.trails).length,
  };
}

async function completedSet(env: Env, userId: string): Promise<string[]> {
  const { results } = await env.DB.prepare("SELECT trail_id FROM user_completed WHERE user_id = ? ORDER BY trail_id")
    .bind(userId)
    .run<{ trail_id: string }>();
  return results.map((r) => r.trail_id);
}

function coverageBody(cov: CoverageMap, completed: string[], m: { island_m: number; covered_m: number; trails_completed: number }) {
  return {
    datasetVersion: META.version,
    tickM: META.tickM,
    segments: toJsonObject(cov),
    completedTrailIds: completed,
    metrics: metricsJson({ islandM: m.island_m, coveredM: m.covered_m, trailsCompleted: m.trails_completed }),
  };
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
  clientDatasetVersion: string;
}

function parseUpload(body: unknown): ParsedUpload {
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
    if (!Array.isArray(body.walks) || body.walks.length > MAX_WALKS) throw new ApiError(400, "invalid_coverage", `walks must be an array (max ${MAX_WALKS})`);
    const seen = new Set<string>();
    for (const w of body.walks) {
      if (!isObject(w) || typeof w.id !== "string" || !WALK_ID.test(w.id)) throw new ApiError(400, "invalid_coverage", "walk id must be a UUID");
      if (typeof w.durationS !== "number" || !Number.isFinite(w.durationS) || w.durationS < 0) {
        throw new ApiError(400, "invalid_coverage", "walk durationS must be a non-negative number");
      }
      const id = w.id.toLowerCase();
      if (seen.has(id)) continue;
      seen.add(id);
      walks.push({ id, durationS: Math.min(Math.round(w.durationS), MAX_WALK_DURATION_S) });
    }
  }
  return { segments, walks, ignored, clientDatasetVersion: body.datasetVersion };
}

async function newWalks(env: Env, userId: string, walks: Walk[]): Promise<Walk[]> {
  if (walks.length === 0) return [];
  const known = new Set<string>();
  // D1 caps bound parameters per statement at 100: query in chunks.
  for (let i = 0; i < walks.length; i += 90) {
    const chunk = walks.slice(i, i + 90);
    const { results } = await env.DB.prepare(
      `SELECT walk_id FROM walks WHERE user_id = ? AND walk_id IN (${chunk.map(() => "?").join(",")})`,
    )
      .bind(userId, ...chunk.map((w) => w.id))
      .run<{ walk_id: string }>();
    for (const r of results) known.add(r.walk_id);
  }
  return walks.filter((w) => !known.has(w.id));
}

const CAS_FAILED = /NOT NULL constraint failed/i;

export async function putCoverage(request: Request, env: Env, deps: Deps, user0: UserRow): Promise<Response> {
  await rateLimit(env, deps.now(), `cov:${user0.id}`, LIMITS.coveragePerUser.limit, LIMITS.coveragePerUser.windowMs);
  const upload = parseUpload(await readJson(request, MAX_COVERAGE_BODY));
  const limits = limitsFrom(env);
  let user = user0;
  for (let attempt = 0; ; attempt++) {
    const now = deps.now();
    const [stored, sticky, fresh] = await Promise.all([
      loadCoverage(env, user.id),
      completedSet(env, user.id),
      newWalks(env, user.id, upload.walks),
    ]);
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
    const allowedM = allowedGrowthM(walkSeconds, limits);
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

    const islandIncreased = after.islandM > before.islandM + 1e-9;
    const reachedAt = islandIncreased || user.reached_at === null ? now : user.reached_at;
    const stmts: D1PreparedStatement[] = [
      // compare-and-swap on coverage_rev: on a concurrent merge the NOT NULL check fails and the batch rolls back
      env.DB.prepare(
        `UPDATE users SET coverage_rev = CASE WHEN coverage_rev = ?2 THEN coverage_rev + 1 ELSE NULL END,
           island_m = ?3, covered_m = ?4, trails_completed = ?5, reached_at = ?6
         WHERE id = ?1`,
      ).bind(user.id, user.coverage_rev, after.islandM, after.coveredM, completed.length, reachedAt),
    ];
    const upsertCov = env.DB.prepare(
      `INSERT INTO coverage (user_id, segment_id, bits, updated_at) VALUES (?, ?, ?, ?)
       ON CONFLICT (user_id, segment_id) DO UPDATE SET bits = excluded.bits, updated_at = excluded.updated_at`,
    );
    const encoded = toJsonObject(merged);
    for (const id of changed) stmts.push(upsertCov.bind(user.id, id, encoded[id]!, now));
    const upsertTrail = env.DB.prepare(
      `INSERT INTO user_trails (user_id, trail_id, fraction) VALUES (?, ?, ?)
       ON CONFLICT (user_id, trail_id) DO UPDATE SET fraction = excluded.fraction`,
    );
    for (const [trailId, f] of after.trailFractions) {
      if (f !== before.trailFractions.get(trailId)) stmts.push(upsertTrail.bind(user.id, trailId, f));
    }
    const insCompleted = env.DB.prepare("INSERT INTO user_completed (user_id, trail_id) VALUES (?, ?) ON CONFLICT DO NOTHING");
    for (const t of newlyCompleted) stmts.push(insCompleted.bind(user.id, t));
    const insWalk = env.DB.prepare("INSERT INTO walks (user_id, walk_id, duration_s) VALUES (?, ?, ?) ON CONFLICT DO NOTHING");
    for (const w of fresh) stmts.push(insWalk.bind(user.id, w.id, w.durationS));
    try {
      await env.DB.batch(stmts);
    } catch (e) {
      if (attempt < 3 && e instanceof Error && CAS_FAILED.test(e.message)) {
        const row = await env.DB.prepare("SELECT * FROM users WHERE id = ?").bind(user.id).first<UserRow>();
        if (!row) throw new ApiError(401, "unauthorized", "account no longer exists");
        user = row;
        continue;
      }
      throw e;
    }
    const updated = { island_m: after.islandM, covered_m: after.coveredM, trails_completed: completed.length };
    return json({
      ...coverageBody(merged, completed, updated),
      accepted: true,
      newlyCoveredM: round(newM, 1),
      ignoredSegments: upload.ignored,
    });
  }
}
