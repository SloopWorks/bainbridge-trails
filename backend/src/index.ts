import { exchangeAppleCode } from "./apple";
import { logout, optionalUser, parseIdTokenBody, requireUser, signIn, verifyIdToken } from "./auth";
import { type Deps, defaultDeps } from "./deps";
import { ApiError, KB, badRequest, errorResponse, isObject, json, readJson } from "./http";
import { LIMITS, rateLimit } from "./ratelimit";
import { adminRoute } from "./routes/admin";
import { leaderboard, reportName, trailStats } from "./routes/board";
import { getCoverage, putCoverage } from "./routes/coverage";
import { deleteUser, meBody, putMe } from "./routes/me";
import { deleteAccountPage } from "./routes/pages";
import { META } from "./segments";
import { rebuildSnapshots } from "./snapshot";

async function authRateLimit(request: Request, env: Env, deps: Deps): Promise<void> {
  const ip = request.headers.get("cf-connecting-ip") ?? "local";
  await rateLimit(env, deps.now(), `auth:${ip}`, LIMITS.authPerIp.limit, LIMITS.authPerIp.windowMs);
}

function sessionBody(s: Awaited<ReturnType<typeof signIn>>) {
  return { token: s.token, expiresAt: s.expiresAt, isNewUser: s.isNewUser, user: meBody(s.user) };
}

async function route(request: Request, env: Env, deps: Deps): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const method = request.method;
  const is = (m: string, p: string) => method === m && path === p;

  if (is("GET", "/delete-account")) return deleteAccountPage(env);
  if (is("GET", "/v1/health")) return json({ ok: true, datasetVersion: META.version, tickM: META.tickM });

  // --- auth
  if (is("POST", "/v1/auth/google") || is("POST", "/v1/auth/apple")) {
    const provider = path.endsWith("google") ? "google" : "apple";
    await authRateLimit(request, env, deps);
    const body = parseIdTokenBody(await readJson(request, 16 * KB));
    const { sub, aud } = await verifyIdToken(provider, body.idToken, env, deps, body.nonce);
    const session = await signIn(env, deps, provider, sub, body.anonName);
    if (provider === "apple" && body.authorizationCode) {
      // TL-10: keep a refresh token so account deletion can revoke it (only when Apple credentials are configured)
      const refresh = await exchangeAppleCode(env, deps, aud, body.authorizationCode);
      if (refresh) {
        await env.DB.prepare("UPDATE users SET apple_refresh_token = ?, apple_client_id = ? WHERE id = ?").bind(refresh, aud, session.user.id).run();
      }
    }
    return json(sessionBody(session));
  }
  if (is("POST", "/v1/auth/dev")) {
    if (env.ALLOW_DEV_AUTH !== "true") throw new ApiError(404, "not_found", "no such endpoint");
    const body = await readJson(request, 1 * KB);
    if (!isObject(body) || typeof body.deviceId !== "string" || !/^[A-Za-z0-9._:-]{1,128}$/.test(body.deviceId)) {
      throw badRequest("deviceId required (1-128 chars of A-Z a-z 0-9 . _ : -)");
    }
    return json(sessionBody(await signIn(env, deps, "dev", body.deviceId, body.anonName)));
  }
  if (is("POST", "/v1/auth/logout")) {
    await logout(request, env);
    return new Response(null, { status: 204 });
  }

  // --- profile
  if (path === "/v1/me") {
    const user = await requireUser(request, env, deps);
    if (method === "GET") return json(meBody(user));
    if (method === "PUT") return putMe(request, env, deps, user);
    if (method === "DELETE") {
      await deleteUser(env, deps, user);
      return new Response(null, { status: 204 });
    }
  }

  // --- coverage
  if (path === "/v1/coverage") {
    const user = await requireUser(request, env, deps);
    if (method === "GET") return getCoverage(env, user);
    if (method === "PUT") return putCoverage(request, env, deps, user);
  }

  // --- community
  if (is("GET", "/v1/leaderboard")) return leaderboard(url, env, deps, await optionalUser(request, env, deps));
  if (is("GET", "/v1/trails/stats")) return trailStats(env, deps);
  if (is("POST", "/v1/report-name")) return reportName(request, env, deps, await requireUser(request, env, deps));

  // --- admin
  if (path.startsWith("/v1/admin/")) return adminRoute(request, env, deps, path);

  const known = ["/v1/me", "/v1/coverage"].includes(path);
  throw known ? new ApiError(405, "method_not_allowed", `${method} not allowed`) : new ApiError(404, "not_found", "no such endpoint");
}

export async function handle(request: Request, env: Env, _ctx: ExecutionContext, deps: Deps = defaultDeps): Promise<Response> {
  try {
    return await route(request, env, deps);
  } catch (e) {
    if (e instanceof ApiError) return errorResponse(e);
    console.error(JSON.stringify({ msg: "unhandled", path: new URL(request.url).pathname, error: String(e) }));
    return errorResponse(new ApiError(500, "internal", "internal error"));
  }
}

export default {
  fetch(request, env, ctx) {
    return handle(request, env, ctx);
  },
  // TL-11: daily snapshot rebuild (wrangler.jsonc triggers.crons)
  async scheduled(controller, env) {
    const r = await rebuildSnapshots(env, controller.scheduledTime);
    console.log(JSON.stringify({ msg: "snapshots rebuilt", ...r }));
  },
} satisfies ExportedHandler<Env>;
