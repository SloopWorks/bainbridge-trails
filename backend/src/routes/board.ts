import type { UserRow } from "../auth";
import { COMPLETION_THRESHOLD } from "../coverage";
import type { Deps } from "../deps";
import { ApiError, KB, badRequest, isObject, json, readJson } from "../http";
import { publicName } from "../moderation/public-name";
import { LIMITS, rateLimit } from "../ratelimit";
import { META } from "../segments";
import { type BoardSnapRow, getSnapshot } from "../snapshot";
import { floorTo } from "../util";

/** Community counts below this are hidden (TL-4 / TL-11). */
export const MIN_COMMUNITY_COUNT = 5;

/** Counts >= 5 are rounded to the nearest 5 so day-to-day snapshot diffs reveal less (review P2-16). */
export function communityCount(n: number): number | null {
  if (n < MIN_COMMUNITY_COUNT) return null;
  return Math.max(MIN_COMMUNITY_COUNT, Math.round(n / 5) * 5);
}

/** a ranks ahead of b: island % (0.1 % floor) desc, trails desc, reached_at asc, id asc (P1-4). */
function ahead(a: { island_rank: number; trails_completed: number; reached_at: number; id: string }, b: typeof a): boolean {
  if (a.island_rank !== b.island_rank) return a.island_rank > b.island_rank;
  if (a.trails_completed !== b.trails_completed) return a.trails_completed > b.trails_completed;
  if (a.reached_at !== b.reached_at) return a.reached_at < b.reached_at;
  return a.id < b.id;
}

export async function leaderboard(url: URL, env: Env, deps: Deps, me: UserRow | null): Promise<Response> {
  const limitParam = url.searchParams.get("limit");
  const limit = limitParam === null ? 100 : Number(limitParam);
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw badRequest("limit must be an integer 1-100");
  const snap = await getSnapshot(env, deps.now());

  // entries are snapshot values (also the caller's own entry); `you` below is live
  const entries = snap.board.slice(0, limit).map((r, i) => ({
    rank: i + 1,
    userId: r.user_id,
    displayName: r.display_name,
    islandPct: r.island_rank / 10,
    trailsCompleted: r.trails_completed,
    isYou: me !== null && r.user_id === me.id,
  }));

  let totalRanked = snap.total_ranked;
  let you: (typeof entries)[number] | null = null;
  if (me && me.hidden === 0 && me.self_hidden === 0 && me.island_m > 0) {
    const mine = { island_rank: me.island_rank, trails_completed: me.trails_completed, reached_at: me.reached_at ?? 0, id: me.id };
    let rank = 1;
    let inSnapshot = false;
    for (const r of snap.board as BoardSnapRow[]) {
      if (r.user_id === me.id) {
        inSnapshot = true;
        continue;
      }
      if (ahead({ ...r, id: r.user_id }, mine)) rank++;
    }
    if (!inSnapshot) totalRanked += 1;
    you = { rank, userId: me.id, displayName: publicName(me), islandPct: me.island_rank / 10, trailsCompleted: me.trails_completed, isYou: true };
  }
  return json({ entries, you, totalRanked });
}

/** GET /v1/trails/stats — from the daily snapshot; counts below 5 are null, larger ones rounded to 5. */
export async function trailStats(env: Env, deps: Deps): Promise<Response> {
  const snap = await getSnapshot(env, deps.now());
  const trails: Record<string, { walkers: number | null; completedCount: number | null; medianPct: number | null }> = {};
  for (const trailId of Object.keys(META.trails)) {
    const r = snap.stats.get(trailId);
    const walkers = communityCount(r?.walkers ?? 0);
    trails[trailId] = {
      walkers,
      completedCount: communityCount(r?.completed ?? 0),
      medianPct: walkers !== null && r?.median_fraction != null ? floorTo(r.median_fraction * 100, 1) : null,
    };
  }
  return json(
    { datasetVersion: META.version, completionThreshold: COMPLETION_THRESHOLD, minCount: MIN_COMMUNITY_COUNT, trails },
    200,
    { "cache-control": "public, max-age=300" },
  );
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
