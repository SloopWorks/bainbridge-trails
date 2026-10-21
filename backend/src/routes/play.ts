import type { UserRow } from "../auth";
import type { Deps } from "../deps";
import { ApiError, json } from "../http";
import { challengesBody, playLog } from "../play/challenges";
import { CLAIM_KINDS, playSummary } from "../play/claims";
import { CONTENT_BODY, CURRENT, contentEtag, tidesFor } from "../play/config";
import { parseDataset } from "../segments";

const CONTENT_CACHE = "public, max-age=300";
const TIDES_CACHE = "public, max-age=31536000, immutable";
const JSON_TYPE = "application/json; charset=utf-8";

/** True when an If-None-Match list names `etag` (weak comparison, RFC 9110 § 13.1.2) or is `*`. */
function etagMatches(header: string | null, etag: string): boolean {
  if (header === null) return false;
  if (header.trim() === "*") return true;
  return header.split(",").some((t) => t.trim().replace(/^W\//, "") === etag);
}

/**
 * GET /v1/play/content?v=<cachedVersion> (schedule.md § 4.8): the newest pack. 304 when If-None-Match names the
 * current ETag or `v` equals the current version (lets a client without an ETag skip the body). Public, cacheable.
 */
export async function playContent(request: Request, url: URL): Promise<Response> {
  const etag = await contentEtag();
  const headers = { etag, "cache-control": CONTENT_CACHE };
  if (etagMatches(request.headers.get("if-none-match"), etag) || url.searchParams.get("v") === CURRENT.version) {
    return new Response(null, { status: 304, headers });
  }
  return new Response(CONTENT_BODY, { status: 200, headers: { ...headers, "content-type": JSON_TYPE } });
}

/** GET /v1/play/tides/{year}: a bundled NOAA high/low table (schedule.md § 1.6); 404 for any other year. */
export function playTides(path: string): Response {
  const m = /^\/v1\/play\/tides\/(\d{4})$/.exec(path);
  const table = m ? tidesFor(Number(m[1])) : null;
  if (!table) throw new ApiError(404, "not_found", "no tide table for that year");
  return new Response(JSON.stringify(table), { status: 200, headers: { "content-type": JSON_TYPE, "cache-control": TIDES_CACHE } });
}

/**
 * GET /v1/play/me?dataset=trails|roads (schedule.md § 7.2, product § 3.6.5): the Play chip summary and the Logbook
 * restore after a reinstall. `total` = the server total, `week` = this week's finds + W-DAY marks (`weekId` = the
 * server's current week), `perKind` = claim counts, `occ` = every claimed occurrence key (re-marked found, never
 * claimed twice), `log` = the server-only credits (W-WEEK, F-RETREAT, C-ISLAND: `{week, kind, marks}`, review P0-2)
 * for the Logbook. No times, no days, no positions. No side effects.
 */
export async function playMe(url: URL, env: Env, deps: Deps, user: UserRow): Promise<Response> {
  const dataset = parseDataset(url.searchParams.get("dataset") ?? undefined);
  const [summary, claims, log] = await Promise.all([
    playSummary(env, user.id, dataset, deps.now()),
    env.DB.prepare("SELECT occ, kind FROM play_claims WHERE user_id = ? AND dataset = ? ORDER BY occ")
      .bind(user.id, dataset)
      .run<{ occ: string; kind: string }>(),
    playLog(env, user.id, dataset),
  ]);
  const perKind: Record<string, number> = Object.fromEntries(CLAIM_KINDS.map((k) => [k, 0]));
  for (const c of claims.results) if (c.kind in perKind) perKind[c.kind]!++;
  return json({
    dataset,
    weekId: summary.weekId,
    total: summary.total,
    week: summary.week,
    perKind,
    occ: claims.results.map((c) => c.occ),
    log,
  });
}

/**
 * GET /v1/play/challenges?dataset=trails|roads (schedule.md § 7.3, product § 4.5, PD-4): every current challenge
 * occurrence of the goal (window started, contributions still accepted) with `{band, count}` results only: no names,
 * scores, ranks or per-user times; a count below 5 is null, larger ones rounded to 5. Public; with a bearer token the
 * caller's own `progress` and `you.band` are filled (and the response is private). No side effects.
 */
export async function playChallenges(url: URL, env: Env, deps: Deps, user: UserRow | null): Promise<Response> {
  const dataset = parseDataset(url.searchParams.get("dataset") ?? undefined);
  const body = await challengesBody(env, dataset, deps.now(), user?.id ?? null);
  return json(body, 200, { "cache-control": user ? "private, no-store" : "public, max-age=300" });
}
