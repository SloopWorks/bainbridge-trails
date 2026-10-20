import { revokeApple } from "../apple";
import type { UserRow } from "../auth";
import type { Deps } from "../deps";
import { ApiError, KB, badRequest, isObject, json, readJson } from "../http";
import { checkDisplayName, isAnonName, isBlocked } from "../moderation/names";
import { publicName } from "../moderation/public-name";
import { LIMITS, rateLimit } from "../ratelimit";
import { snapshotRemove, snapshotRename } from "../snapshot";
import { getUser } from "../users";
import { metricsJson } from "./coverage";

export { publicName };

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
    metrics: metricsJson(u),
  };
}

export async function putMe(request: Request, env: Env, deps: Deps, user: UserRow): Promise<Response> {
  const body = await readJson(request, 4 * KB);
  if (!isObject(body)) throw badRequest("body must be an object");
  if (!("anonName" in body) && !("displayName" in body)) throw badRequest("nothing to update: send displayName and/or anonName");
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
  if (anon !== user.anon_name || custom !== user.display_name) {
    const next = { display_name: custom, anon_name: anon };
    await env.DB.batch([
      env.DB.prepare("UPDATE users SET anon_name = ?, display_name = ? WHERE id = ?").bind(anon, custom, user.id),
      ...(publicName(next) !== publicName(user) ? snapshotRename(env, user.id, publicName(next)) : []),
    ]);
  }
  return json(meBody((await getUser(env, user.id))!));
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
    env.DB.prepare("DELETE FROM walks WHERE user_id = ?").bind(id),
    env.DB.prepare("DELETE FROM name_reports WHERE reporter_id = ?1 OR target_id = ?1").bind(id),
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ?").bind(id),
    ...snapshotRemove(env, id),
    env.DB.prepare("DELETE FROM rate_limits WHERE key IN (?1, ?2, ?3)").bind(`cov:${id}`, `name:${id}`, `report:${id}`),
    env.DB.prepare("DELETE FROM users WHERE id = ?").bind(id),
  ]);
}
