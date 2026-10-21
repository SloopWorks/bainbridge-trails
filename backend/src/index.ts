import { exchangeAppleCode } from "./apple";
import { logout, optionalUser, parseIdTokenBody, requireUser, signIn, verifyIdToken } from "./auth";
import { type Deps, defaultDeps } from "./deps";
import { ApiError, KB, badRequest, errorResponse, isObject, json, readJson } from "./http";
import { LIMITS, clientIp, hashedIp, publicRateLimit, purgeHousekeeping, rateLimit } from "./ratelimit";
import { adminMethod, adminRoute } from "./routes/admin";
import { leaderboard, reportName, trailStats } from "./routes/board";
import { getCoverage, putCoverage } from "./routes/coverage";
import { deleteUser, getMe, meBody, putMe } from "./routes/me";
import { deleteAccountPage, legalPage } from "./routes/pages";
import { playContent, playTides } from "./routes/play";
import { DATASETS, type Dataset, META, METAS, parseDataset } from "./segments";
import { rebuildSnapshots } from "./snapshot";
import { METRICS_COLS, type MetricsRow, recomputeMetrics } from "./users";

/** Allowed methods per path. Checked before auth so a wrong method is always 405 (P2-14). */
const ROUTES: Record<string, string[]> = {
  "/delete-account": ["GET", "HEAD"],
  "/privacy": ["GET", "HEAD"],
  "/terms": ["GET", "HEAD"],
  "/support": ["GET", "HEAD"],
  "/v1/health": ["GET"],
  "/v1/auth/google": ["POST"],
  "/v1/auth/apple": ["POST"],
  "/v1/auth/dev": ["POST"],
  "/v1/auth/logout": ["POST"],
  "/v1/me": ["GET", "PUT", "DELETE"],
  "/v1/coverage": ["GET", "PUT"],
  "/v1/leaderboard": ["GET"],
  "/v1/trails/stats": ["GET"],
  "/v1/stats": ["GET"],
  "/v1/report-name": ["POST"],
  "/v1/play/content": ["GET"],
};

/** Path prefixes with a parameter: `/v1/play/tides/{year}`. */
const PREFIX_ROUTES: [prefix: string, methods: string[]][] = [["/v1/play/tides/", ["GET"]]];

/** Dev auth answers only on hosts a local emulator/simulator uses (P2-13), on top of ALLOW_DEV_AUTH. */
const DEV_HOSTS = new Set(["localhost", "127.0.0.1", "10.0.2.2"]);

async function authRateLimit(request: Request, env: Env, deps: Deps): Promise<void> {
  const ip = await hashedIp(env, deps.now(), clientIp(request));
  await rateLimit(env, deps.now(), `auth:${ip}`, LIMITS.authPerIp.limit, LIMITS.authPerIp.windowMs);
}

function sessionBody(s: Awaited<ReturnType<typeof signIn>>) {
  return { token: s.token, expiresAt: s.expiresAt, isNewUser: s.isNewUser, user: meBody(s.user, s.metrics) };
}

async function route(request: Request, env: Env, deps: Deps): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const method = request.method;
  const allowed =
    ROUTES[path] ??
    PREFIX_ROUTES.find(([p]) => path.startsWith(p))?.[1] ??
    (path.startsWith("/v1/admin/") ? [adminMethod(path) ?? ""] : null);
  if (!allowed || allowed[0] === "") throw new ApiError(404, "not_found", "no such endpoint");
  if (!allowed.includes(method)) {
    throw new ApiError(405, "method_not_allowed", `${method} not allowed`, undefined, { allow: allowed.join(", ") });
  }

  switch (path) {
    case "/delete-account":
      return deleteAccountPage(env, method);
    case "/privacy":
    case "/terms":
    case "/support":
      return legalPage(path.slice(1) as "privacy" | "terms" | "support", method);
    case "/v1/health":
      return json({
        ok: true,
        datasetVersion: META.version,
        tickM: META.tickM,
        datasets: Object.fromEntries(DATASETS.map((d) => [d, { datasetVersion: METAS[d].version, tickM: METAS[d].tickM }])),
      });
    case "/v1/auth/google":
    case "/v1/auth/apple": {
      const provider = path.endsWith("google") ? "google" : "apple";
      await authRateLimit(request, env, deps);
      const body = parseIdTokenBody(await readJson(request, 16 * KB));
      const { sub, aud } = await verifyIdToken(provider, body.idToken, env, deps, body.nonce);
      const session = await signIn(env, deps, provider, sub, body.anonName);
      if (provider === "apple" && body.authorizationCode) {
        // TL-10: keep a refresh token so account deletion can revoke it (only when Apple credentials are configured)
        const refresh = await exchangeAppleCode(env, deps, aud, body.authorizationCode);
        if (refresh) {
          await env.DB.prepare("UPDATE users SET apple_refresh_token = ?, apple_client_id = ? WHERE id = ?")
            .bind(refresh, aud, session.user.id)
            .run();
        }
      }
      return json(sessionBody(session));
    }
    case "/v1/auth/dev": {
      if (env.ALLOW_DEV_AUTH !== "true" || !DEV_HOSTS.has(url.hostname)) throw new ApiError(404, "not_found", "no such endpoint");
      const body = await readJson(request, 1 * KB);
      if (!isObject(body) || typeof body.deviceId !== "string" || !/^[A-Za-z0-9._:-]{1,128}$/.test(body.deviceId)) {
        throw badRequest("deviceId required (1-128 chars of A-Z a-z 0-9 . _ : -)");
      }
      return json(sessionBody(await signIn(env, deps, "dev", body.deviceId, body.anonName)));
    }
    case "/v1/auth/logout":
      await logout(request, env);
      return new Response(null, { status: 204 });
    case "/v1/me": {
      const user = await requireUser(request, env, deps);
      if (method === "GET") return getMe(env, deps, user);
      if (method === "PUT") return putMe(request, env, deps, user);
      await deleteUser(env, deps, user);
      return new Response(null, { status: 204 });
    }
    case "/v1/coverage": {
      const user = await requireUser(request, env, deps);
      return method === "GET" ? getCoverage(url, env, deps, user) : putCoverage(request, url, env, deps, user);
    }
    case "/v1/leaderboard":
      publicRateLimit(clientIp(request), deps.now());
      return leaderboard(url, env, deps, await optionalUser(request, env, deps));
    case "/v1/trails/stats":
      publicRateLimit(clientIp(request), deps.now());
      return trailStats(env, deps, "trails");
    case "/v1/stats":
      publicRateLimit(clientIp(request), deps.now());
      return trailStats(env, deps, parseDataset(url.searchParams.get("dataset") ?? undefined));
    case "/v1/report-name":
      return reportName(request, env, deps, await requireUser(request, env, deps));
    case "/v1/play/content":
      publicRateLimit(clientIp(request), deps.now());
      return playContent(request, url);
    default:
      if (path.startsWith("/v1/play/tides/")) {
        publicRateLimit(clientIp(request), deps.now());
        return playTides(path);
      }
      return adminRoute(request, env, deps, path);
  }
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

/** Daily cron: housekeeping, metrics for a dataset update (P1-7), then the snapshots (TL-11). */
export async function daily(env: Env, now: number): Promise<{ recomputed: number; ranked: Record<Dataset, number> }> {
  await purgeHousekeeping(env, now);
  let recomputed = 0;
  for (const dataset of DATASETS) {
    for (let round = 0; round < 10; round++) {
      const { results } = await env.DB.prepare(`SELECT ${METRICS_COLS} FROM user_metrics WHERE dataset = ? AND metrics_version IS NOT ? LIMIT 100`)
        .bind(dataset, METAS[dataset].version)
        .run<MetricsRow>();
      if (results.length === 0) break;
      for (const m of results) if (await recomputeMetrics(env, m, now)) recomputed++;
    }
  }
  const built = await rebuildSnapshots(env, now);
  return { recomputed, ranked: { trails: built.trails.ranked, roads: built.roads.ranked } };
}

export default {
  fetch(request, env, ctx) {
    return handle(request, env, ctx);
  },
  async scheduled(controller, env) {
    const r = await daily(env, controller.scheduledTime);
    console.log(JSON.stringify({ msg: "daily cron done", ...r }));
  },
} satisfies ExportedHandler<Env>;
