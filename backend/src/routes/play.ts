import { ApiError } from "../http";
import { CONTENT_BODY, CURRENT, contentEtag, tidesFor } from "../play/config";

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
