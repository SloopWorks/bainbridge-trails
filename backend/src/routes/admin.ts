import type { Deps } from "../deps";
import { ApiError, KB, bearer, isObject, json, readJson } from "../http";
import { rebuildSnapshots } from "../snapshot";
import { safeEqual } from "../util";

export async function requireAdmin(request: Request, env: Env): Promise<void> {
  const token = bearer(request);
  // When ADMIN_TOKEN is unset (or too short) the admin surface does not exist.
  if (!env.ADMIN_TOKEN || env.ADMIN_TOKEN.length < 16) throw new ApiError(404, "not_found", "no such endpoint");
  if (token === null || !(await safeEqual(token, env.ADMIN_TOKEN))) throw new ApiError(403, "forbidden", "admin token required");
}

export async function adminRoute(request: Request, env: Env, deps: Deps, path: string): Promise<Response> {
  await requireAdmin(request, env);
  if (path === "/v1/admin/reports" && request.method === "GET") {
    const { results } = await env.DB.prepare(
      `SELECT u.id AS userId, u.display_name AS customName, u.anon_name AS anonName, u.hidden AS hidden,
              COUNT(r.reporter_id) AS reports
       FROM name_reports r JOIN users u ON u.id = r.target_id
       GROUP BY u.id ORDER BY reports DESC LIMIT 200`,
    ).run();
    return json({ reports: results });
  }
  if (path === "/v1/admin/snapshot" && request.method === "POST") {
    return json({ ok: true, ...(await rebuildSnapshots(env, deps.now())) });
  }
  const m = /^\/v1\/admin\/users\/([0-9a-f-]{36})\/(hide|reset-name|dismiss-reports)$/.exec(path);
  if (!m || request.method !== "POST") throw new ApiError(404, "not_found", "no such endpoint");
  const userId = m[1]!;
  const action = m[2]!;
  const stmts: D1PreparedStatement[] = [];
  if (action === "hide") {
    const body = request.headers.get("content-length") === "0" || !request.body ? {} : await readJson(request, 1 * KB);
    const hidden = isObject(body) && body.hidden === false ? 0 : 1;
    stmts.push(env.DB.prepare("UPDATE users SET hidden = ? WHERE id = ?").bind(hidden, userId));
  } else if (action === "reset-name") {
    stmts.push(env.DB.prepare("UPDATE users SET display_name = NULL WHERE id = ?").bind(userId));
    stmts.push(env.DB.prepare("DELETE FROM name_reports WHERE target_id = ?").bind(userId));
  } else {
    stmts.push(env.DB.prepare("UPDATE users SET id = id WHERE id = ?").bind(userId)); // existence check
    stmts.push(env.DB.prepare("DELETE FROM name_reports WHERE target_id = ?").bind(userId));
  }
  const [res] = await env.DB.batch(stmts);
  if (!res || res.meta.changes === 0) throw new ApiError(404, "not_found", "user not found");
  return json({ ok: true });
}
