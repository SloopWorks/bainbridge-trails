import { ApiError } from "./http";

/**
 * Fixed-window counter in D1 (one upsert per call). Throws 429 when `limit` is exceeded within `windowMs`.
 * SQLite evaluates all SET expressions against the old row, so the reset/increment is atomic.
 */
export async function rateLimit(
  env: Env,
  now: number,
  key: string,
  limit: number,
  windowMs: number,
  code = "rate_limited",
): Promise<void> {
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits (key, window_start, count) VALUES (?1, ?2, 1)
     ON CONFLICT (key) DO UPDATE SET
       count = CASE WHEN window_start <= ?3 THEN 1 ELSE count + 1 END,
       window_start = CASE WHEN window_start <= ?3 THEN ?2 ELSE window_start END
     RETURNING count, window_start`,
  )
    .bind(key, now, now - windowMs)
    .first<{ count: number; window_start: number }>();
  if (row && row.count > limit) {
    const retry = Math.max(1, Math.ceil((row.window_start + windowMs - now) / 1000));
    throw new ApiError(429, code, "too many requests; slow down", { retryAfterSeconds: retry }, { "retry-after": String(retry) });
  }
}

export const LIMITS = {
  authPerIp: { limit: 30, windowMs: 10 * 60_000 },
  coveragePerUser: { limit: 30, windowMs: 60 * 60_000 },
  nameChangesPerUser: { limit: 5, windowMs: 24 * 60 * 60_000 },
  reportsPerUser: { limit: 20, windowMs: 24 * 60 * 60_000 },
} as const;
