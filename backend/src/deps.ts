import type { JSONWebKeySet } from "jose";

/** Injectable side effects, so tests can supply JWKS and a clock. */
export interface Deps {
  now(): number;
  fetchJwks(url: string): Promise<JSONWebKeySet>;
  /** Outbound calls other than JWKS (Apple token exchange / revocation). */
  fetch(input: string, init?: RequestInit): Promise<Response>;
}

// Module-level cache of provider public keys: shared, non-request state (safe across requests).
const jwksCache = new Map<string, { jwks: JSONWebKeySet; fetchedAt: number }>();
const JWKS_TTL_MS = 60 * 60 * 1000;

export const defaultDeps: Deps = {
  now: () => Date.now(),
  fetch: (input, init) => fetch(input, init),
  async fetchJwks(url: string): Promise<JSONWebKeySet> {
    const hit = jwksCache.get(url);
    if (hit && Date.now() - hit.fetchedAt < JWKS_TTL_MS) return hit.jwks;
    const res = await fetch(url, { headers: { accept: "application/json" } });
    if (!res.ok) throw new Error(`JWKS fetch ${url} failed: ${res.status}`);
    const jwks = (await res.json()) as JSONWebKeySet;
    jwksCache.set(url, { jwks, fetchedAt: Date.now() });
    return jwks;
  },
};

export function forgetJwks(url: string): void {
  jwksCache.delete(url);
}
