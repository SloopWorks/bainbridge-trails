import type { JSONWebKeySet } from "jose";

/** Injectable side effects, so tests can supply JWKS, outbound fetch and a clock. */
export interface Deps {
  now(): number;
  /** `refresh`: the token's kid was not found; refetch unless refetched in the last 5 min. */
  fetchJwks(url: string, refresh?: boolean): Promise<JSONWebKeySet>;
  /** Outbound calls other than JWKS (Apple token exchange / revocation). */
  fetch(input: string, init?: RequestInit): Promise<Response>;
}

// Module-level cache of provider public keys: shared, non-request state (safe across requests).
const jwksCache = new Map<string, { jwks: JSONWebKeySet; fetchedAt: number }>();
const JWKS_TTL_MS = 60 * 60 * 1000;
const JWKS_MIN_REFETCH_MS = 5 * 60 * 1000;

export async function cachedJwks(
  url: string,
  refresh: boolean,
  now: number,
  load: (url: string) => Promise<JSONWebKeySet>,
): Promise<JSONWebKeySet> {
  const hit = jwksCache.get(url);
  if (hit) {
    const age = now - hit.fetchedAt;
    if (!refresh && age < JWKS_TTL_MS) return hit.jwks;
    if (refresh && age < JWKS_MIN_REFETCH_MS) return hit.jwks; // unknown kid: at most one refetch per 5 min
  }
  try {
    const jwks = await load(url);
    jwksCache.set(url, { jwks, fetchedAt: now });
    return jwks;
  } catch (e) {
    if (hit) return hit.jwks; // provider down: serve stale keys
    throw e;
  }
}

export function clearJwksCache(): void {
  jwksCache.clear();
}

export const defaultDeps: Deps = {
  now: () => Date.now(),
  fetch: (input, init) => fetch(input, init),
  fetchJwks: (url, refresh = false) =>
    cachedJwks(url, refresh, Date.now(), async (u) => {
      const res = await fetch(u, { headers: { accept: "application/json" } });
      if (!res.ok) throw new Error(`JWKS fetch ${u} failed: ${res.status}`);
      return (await res.json()) as JSONWebKeySet;
    }),
};
