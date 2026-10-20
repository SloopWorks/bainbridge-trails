import { ApiError } from "./http";
import { base64UrlRandom, sha256Hex } from "./util";

/**
 * Fixed-window counter in D1 (one upsert per call). Throws 429 when `limit` is exceeded within `windowMs`.
 * SQLite evaluates all SET expressions against the old row, so the reset/increment is atomic.
 */
export async function rateLimit(env: Env, now: number, key: string, limit: number, windowMs: number, code = "rate_limited"): Promise<void> {
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits (key, window_start, count) VALUES (?1, ?2, 1)
     ON CONFLICT (key) DO UPDATE SET
       count = CASE WHEN window_start <= ?3 THEN 1 ELSE count + 1 END,
       window_start = CASE WHEN window_start <= ?3 THEN ?2 ELSE window_start END
     RETURNING count, window_start`,
  )
    .bind(key, now, now - windowMs)
    .first<{ count: number; window_start: number }>();
  if (row && row.count > limit) throw tooMany(Math.ceil((row.window_start + windowMs - now) / 1000), code);
}

function tooMany(retrySeconds: number, code = "rate_limited"): ApiError {
  const retry = String(Math.max(1, retrySeconds));
  return new ApiError(429, code, "too many requests; slow down", { retryAfterSeconds: Number(retry) }, { "retry-after": retry });
}

export const LIMITS = {
  authPerIp: { limit: 30, windowMs: 10 * 60_000 },
  coveragePerUser: { limit: 30, windowMs: 60 * 60_000 },
  nameChangesPerUser: { limit: 5, windowMs: 24 * 60 * 60_000 },
  reportsPerUser: { limit: 20, windowMs: 24 * 60 * 60_000 },
  publicPerIp: { limit: 120, windowMs: 10 * 60_000 },
} as const;

const utcDay = (ms: number): string => new Date(ms).toISOString().slice(0, 10);

/** P1-5: IPs are never stored raw: SHA-256(random salt of the UTC day + IP). Old salts are purged by the cron. */
export async function hashedIp(env: Env, now: number, ip: string): Promise<string> {
  const day = utcDay(now);
  await env.DB.prepare("INSERT INTO daily_salt (day, salt) VALUES (?, ?) ON CONFLICT DO NOTHING").bind(day, base64UrlRandom(16)).run();
  const row = await env.DB.prepare("SELECT salt FROM daily_salt WHERE day = ?").bind(day).first<{ salt: string }>();
  return (await sha256Hex(`${row!.salt}:${ip}`)).slice(0, 32);
}

export function clientIp(request: Request): string {
  return request.headers.get("cf-connecting-ip") ?? "local";
}

// P1-6: public read endpoints use a per-isolate in-memory limiter (no D1 write per read). Not request state:
// shared counters, bounded in size.
const publicHits = new Map<string, { start: number; count: number }>();

export function publicRateLimit(ip: string, now: number): void {
  const { limit, windowMs } = LIMITS.publicPerIp;
  if (publicHits.size > 10_000) {
    for (const [k, v] of publicHits) if (now - v.start > windowMs) publicHits.delete(k);
    if (publicHits.size > 10_000) publicHits.clear();
  }
  const e = publicHits.get(ip);
  if (!e || now - e.start > windowMs) {
    publicHits.set(ip, { start: now, count: 1 });
    return;
  }
  e.count++;
  if (e.count > limit) throw tooMany(Math.ceil((e.start + windowMs - now) / 1000));
}

export function resetPublicRateLimit(): void {
  publicHits.clear();
}

/** Cron housekeeping: old counters, expired sessions, old salts. */
export async function purgeHousekeeping(env: Env, now: number): Promise<void> {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM rate_limits WHERE window_start < ?").bind(now - 2 * 24 * 60 * 60_000),
    env.DB.prepare("DELETE FROM sessions WHERE expires_at < ?").bind(now),
    env.DB.prepare("DELETE FROM daily_salt WHERE day < ?").bind(utcDay(now)),
  ]);
}
