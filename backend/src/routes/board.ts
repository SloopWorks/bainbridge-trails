import type { UserRow } from "../auth";
import type { Deps } from "../deps";
import { ApiError, KB, badRequest, isObject, json, readJson } from "../http";
import { LIMITS, rateLimit } from "../ratelimit";
import { META } from "../segments";
import { ensureSnapshots } from "../snapshot";
import { COMPLETION_THRESHOLD } from "../coverage";
import { floorTo } from "../util";
import { islandPct } from "./coverage";
import { publicName } from "./me";

/** Community counts below this are hidden (TL-4 / TL-11). */
export const MIN_COMMUNITY_COUNT = 5;

interface SnapRow {
  user_id: string;
  island_m: number;
  trails_completed: number;
  display_name: string | null;
  anon_name: string;
}

export async function leaderboard(url: URL, env: Env, deps: Deps, me: UserRow | null): Promise<Response> {
  const limitParam = url.searchParams.get("limit");
  const limit = limitParam === null ? 100 : Number(limitParam);
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw badRequest("limit must be an integer 1-100");
  await ensureSnapshots(env, deps.now());

  const visible = "FROM leaderboard_snapshot s JOIN users u ON u.id = s.user_id WHERE u.hidden = 0";
  const [topRes, totalRes] = await env.DB.batch<SnapRow | { n: number }>([
    env.DB.prepare(
      `SELECT s.user_id, s.island_m, s.trails_completed, u.display_name, u.anon_name ${visible} ORDER BY s.ord LIMIT ?`,
    ).bind(limit),
    env.DB.prepare(`SELECT COUNT(*) AS n ${visible}`),
  ]);
  const rows = (topRes?.results ?? []) as SnapRow[];
  let totalRanked = ((totalRes?.results?.[0] as { n: number } | undefined)?.n) ?? 0;

  // entries are snapshot values (also the caller's own entry); `you` below is live
  const entries = rows.map((r, i) => ({
    rank: i + 1,
    userId: r.user_id,
    displayName: publicName(r),
    islandPct: islandPct(r.island_m),
    trailsCompleted: r.trails_completed,
    isYou: me !== null && r.user_id === me.id,
  }));

  let you: (typeof entries)[number] | null = null;
  if (me && me.hidden === 0 && me.island_m > 0) {
    // live rank of the caller against everyone else's snapshot rows
    const ahead = await env.DB.prepare(
      `SELECT COUNT(*) AS n ${visible} AND s.user_id != ?4 AND (
         s.island_m > ?1 OR (s.island_m = ?1 AND (s.trails_completed > ?2 OR (s.trails_completed = ?2 AND (
           s.reached_at < ?3 OR (s.reached_at = ?3 AND s.user_id < ?4))))))`,
    )
      .bind(me.island_m, me.trails_completed, me.reached_at ?? 0, me.id)
      .first<{ n: number }>();
    const inSnapshot = rows.some((r) => r.user_id === me.id) ||
      (await env.DB.prepare("SELECT 1 AS x FROM leaderboard_snapshot WHERE user_id = ?").bind(me.id).first()) !== null;
    if (!inSnapshot) totalRanked += 1;
    you = {
      rank: (ahead?.n ?? 0) + 1,
      userId: me.id,
      displayName: publicName(me),
      islandPct: islandPct(me.island_m),
      trailsCompleted: me.trails_completed,
      isYou: true,
    };
  }
  return json({ entries, you, totalRanked });
}

/** GET /v1/trails/stats — from the daily snapshot; counts below 5 are null. */
export async function trailStats(env: Env, deps: Deps): Promise<Response> {
  await ensureSnapshots(env, deps.now());
  const { results } = await env.DB.prepare("SELECT trail_id, walkers, completed, median_fraction FROM trail_stats_snapshot").run<{
    trail_id: string;
    walkers: number;
    completed: number;
    median_fraction: number | null;
  }>();
  const byId = new Map(results.map((r) => [r.trail_id, r]));
  const trails: Record<string, { walkers: number | null; completedCount: number | null; medianPct: number | null }> = {};
  for (const trailId of Object.keys(META.trails)) {
    const r = byId.get(trailId);
    const walkers = r?.walkers ?? 0;
    const completed = r?.completed ?? 0;
    trails[trailId] = {
      walkers: walkers >= MIN_COMMUNITY_COUNT ? walkers : null,
      completedCount: completed >= MIN_COMMUNITY_COUNT ? completed : null,
      medianPct: walkers >= MIN_COMMUNITY_COUNT && r?.median_fraction != null ? floorTo(r.median_fraction * 100, 1) : null,
    };
  }
  return json({ datasetVersion: META.version, completionThreshold: COMPLETION_THRESHOLD, minCount: MIN_COMMUNITY_COUNT, trails }, 200, {
    "cache-control": "public, max-age=300",
  });
}

/** Reports only flag the name for admin review (auto-hide cut in TL decisions). */
export async function reportName(request: Request, env: Env, deps: Deps, me: UserRow): Promise<Response> {
  const body = await readJson(request, 1 * KB);
  if (!isObject(body) || typeof body.userId !== "string" || body.userId.length > 64) throw badRequest("userId required");
  const target = body.userId;
  if (target === me.id) throw badRequest("cannot report yourself");
  const exists = await env.DB.prepare("SELECT id FROM users WHERE id = ?").bind(target).first();
  if (!exists) throw new ApiError(404, "not_found", "user not found");
  await rateLimit(env, deps.now(), `report:${me.id}`, LIMITS.reportsPerUser.limit, LIMITS.reportsPerUser.windowMs);
  await env.DB.prepare("INSERT INTO name_reports (reporter_id, target_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
    .bind(me.id, target, deps.now())
    .run();
  return json({ ok: true }, 202);
}
