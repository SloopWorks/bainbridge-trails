import { revokeApple } from "../apple";
import type { UserRow } from "../auth";
import type { Deps } from "../deps";
import { ApiError, KB, badRequest, isObject, json, readJson } from "../http";
import { checkDisplayName, isAnonName, isBlocked } from "../moderation/names";
import { publicName } from "../moderation/public-name";
import { LIMITS, rateLimit } from "../ratelimit";
import { snapshotRemove, snapshotRename } from "../snapshot";
import { META } from "../segments";
import { type MetricsRow, getUser, metricsFor } from "../users";
import { metricsJson } from "./coverage";

export { publicName };

export function nameStatus(u: Pick<UserRow, "display_name">): "anonymous" | "ok" | "blocked" {
  if (u.display_name === null) return "anonymous";
  return isBlocked(u.display_name) ? "blocked" : "ok";
}

/** `metrics` are the trails dataset's (contract predates RD-7); roads metrics come with GET /v1/coverage?dataset=roads. */
export function meBody(u: UserRow, trails: MetricsRow) {
  return {
    userId: u.id,
    provider: u.provider,
    displayName: publicName(u),
    customName: u.display_name,
    anonName: u.anon_name,
    isAnonymous: publicName(u) === u.anon_name,
    nameStatus: nameStatus(u),
    // The user's own toggle (P1-8). An admin hide is moderation state, reported separately (P2-10) so the app can
    // explain why turning the toggle off doesn't bring the row back.
    hiddenFromBoard: u.self_hidden === 1,
    hiddenByModeration: u.hidden === 1,
    metrics: metricsJson(META, trails),
  };
}

export async function putMe(request: Request, env: Env, deps: Deps, user: UserRow): Promise<Response> {
  const body = await readJson(request, 4 * KB);
  if (!isObject(body)) throw badRequest("body must be an object");
  if (!("anonName" in body) && !("displayName" in body) && !("hiddenFromBoard" in body)) {
    throw badRequest("nothing to update: send displayName, anonName and/or hiddenFromBoard");
  }
  if ("hiddenFromBoard" in body && typeof body.hiddenFromBoard !== "boolean") throw badRequest("hiddenFromBoard must be a boolean");
  let anon = user.anon_name;
  let custom = user.display_name;
  if ("anonName" in body) {
    if (!isAnonName(body.anonName)) throw new ApiError(400, "name_invalid", "anonName must be 'Adjective Noun NN' from the shared word lists");
    anon = body.anonName;
  }
  if ("displayName" in body) {
    if (body.displayName === null) {
      custom = null;
    } else {
      const check = checkDisplayName(body.displayName);
      if (!check.ok) throw new ApiError(400, check.code, check.message);
      if (check.name !== user.display_name) {
        await rateLimit(env, deps.now(), `name:${user.id}`, LIMITS.nameChangesPerUser.limit, LIMITS.nameChangesPerUser.windowMs, "name_rate_limited");
      }
      custom = check.name;
    }
  }
  // Checked before any write so a 429 never leaves a half-applied update. Unchanged values don't count.
  const hide = body.hiddenFromBoard === true ? 1 : 0;
  const hideChanges = "hiddenFromBoard" in body && hide !== user.self_hidden;
  if (hideChanges) {
    await rateLimit(env, deps.now(), `hide:${user.id}`, LIMITS.hideTogglesPerUser.limit, LIMITS.hideTogglesPerUser.windowMs);
  }
  if (anon !== user.anon_name || custom !== user.display_name) {
    const next = { display_name: custom, anon_name: anon };
    await env.DB.batch([
      env.DB.prepare("UPDATE users SET anon_name = ?, display_name = ? WHERE id = ?").bind(anon, custom, user.id),
      ...(publicName(next) !== publicName(user) ? snapshotRename(env, user.id, publicName(next)) : []),
    ]);
  }
  if (hideChanges) {
    // Hiding drops the row from the snapshot at once. Un-hiding shows the caller's live `you` row at once; others
    // see the row again at the next daily rebuild (TL-11: no live numbers for others).
    await env.DB.batch([
      env.DB.prepare("UPDATE users SET self_hidden = ? WHERE id = ?").bind(hide, user.id),
      ...(hide ? snapshotRemove(env, user.id) : []),
    ]);
  }
  return json(meBody((await getUser(env, user.id))!, await metricsFor(env, user.id, "trails", deps.now())));
}

export async function getMe(env: Env, deps: Deps, user: UserRow): Promise<Response> {
  return json(meBody(user, await metricsFor(env, user.id, "trails", deps.now())));
}

/** Account deletion: revoke Apple (best effort, TL-10), then remove every row tied to the user. */
export async function deleteUser(env: Env, deps: Deps, user: UserRow): Promise<void> {
  const apple = await env.DB.prepare("SELECT apple_refresh_token AS t, apple_client_id AS c FROM users WHERE id = ?")
    .bind(user.id)
    .first<{ t: string | null; c: string | null }>();
  if (apple?.t && apple.c) await revokeApple(env, deps, apple.c, apple.t);
  const id = user.id;
  await env.DB.batch([
    env.DB.prepare("DELETE FROM coverage WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM user_trails WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM user_completed WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM walks_v2 WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM user_metrics WHERE user_id = ?").bind(id), // every dataset
    env.DB.prepare("DELETE FROM name_reports WHERE reporter_id = ?1 OR target_id = ?1").bind(id),
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ?").bind(id),
    ...snapshotRemove(env, id),
    env.DB.prepare("DELETE FROM rate_limits WHERE key IN (?1, ?2, ?3, ?4, ?5)").bind(
      `cov:trails:${id}`,
      `cov:roads:${id}`,
      `name:${id}`,
      `report:${id}`,
      `hide:${id}`,
    ),
    env.DB.prepare("DELETE FROM users WHERE id = ?").bind(id),
  ]);
}
