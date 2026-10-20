import { revokeApple } from "../apple";
import type { UserRow } from "../auth";
import type { Deps } from "../deps";
import { ApiError, KB, badRequest, isObject, json, readJson } from "../http";
import { checkDisplayName, isAnonName, isBlocked } from "../moderation/names";
import { LIMITS, rateLimit } from "../ratelimit";
import { metricsJson } from "./coverage";

/** Public name: the custom name unless absent or caught by the current blocklist (then the anon name). */
export function publicName(u: Pick<UserRow, "display_name" | "anon_name">): string {
  if (u.display_name === null || isBlocked(u.display_name)) return u.anon_name;
  return u.display_name;
}

export function nameStatus(u: Pick<UserRow, "display_name">): "anonymous" | "ok" | "blocked" {
  if (u.display_name === null) return "anonymous";
  return isBlocked(u.display_name) ? "blocked" : "ok";
}

export function meBody(u: UserRow) {
  return {
    userId: u.id,
    provider: u.provider,
    displayName: publicName(u),
    customName: u.display_name,
    anonName: u.anon_name,
    isAnonymous: publicName(u) === u.anon_name,
    nameStatus: nameStatus(u),
    hiddenFromBoard: u.hidden === 1,
    metrics: metricsJson({ islandM: u.island_m, coveredM: u.covered_m, trailsCompleted: u.trails_completed }),
  };
}

export async function putMe(request: Request, env: Env, deps: Deps, user: UserRow): Promise<Response> {
  const body = await readJson(request, 4 * KB);
  if (!isObject(body)) throw badRequest("body must be an object");
  if (!("anonName" in body) && !("displayName" in body)) throw badRequest("nothing to update: send displayName and/or anonName");
  const sets: string[] = [];
  const binds: (string | null)[] = [];
  if ("anonName" in body) {
    if (!isAnonName(body.anonName)) throw new ApiError(400, "name_invalid", "anonName must be 'Adjective Noun NN' from the shared word lists");
    sets.push("anon_name = ?");
    binds.push(body.anonName);
  }
  if ("displayName" in body) {
    if (body.displayName === null) {
      sets.push("display_name = NULL");
    } else {
      const check = checkDisplayName(body.displayName);
      if (!check.ok) throw new ApiError(400, check.code, check.message);
      if (check.name !== user.display_name) {
        await rateLimit(env, deps.now(), `name:${user.id}`, LIMITS.nameChangesPerUser.limit, LIMITS.nameChangesPerUser.windowMs, "name_rate_limited");
        sets.push("display_name = ?");
        binds.push(check.name);
      }
    }
  }
  if (sets.length > 0) await env.DB.prepare(`UPDATE users SET ${sets.join(", ")} WHERE id = ?`).bind(...binds, user.id).run();
  const fresh = await env.DB.prepare("SELECT * FROM users WHERE id = ?").bind(user.id).first<UserRow>();
  return json(meBody(fresh!));
}

/** Account deletion: revoke Apple (best effort, TL-10), then remove every row tied to the user. */
export async function deleteUser(env: Env, deps: Deps, user: UserRow): Promise<void> {
  if (user.apple_refresh_token && user.apple_client_id) await revokeApple(env, deps, user.apple_client_id, user.apple_refresh_token);
  const id = user.id;
  await env.DB.batch([
    env.DB.prepare("DELETE FROM coverage WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM user_trails WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM user_completed WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM walks WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM name_reports WHERE reporter_id = ?1 OR target_id = ?1").bind(id),
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM leaderboard_snapshot WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM rate_limits WHERE key IN (?1, ?2, ?3)").bind(`cov:${id}`, `name:${id}`, `report:${id}`),
    env.DB.prepare("DELETE FROM users WHERE id = ?").bind(id),
  ]);
}
