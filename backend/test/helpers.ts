import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { type JSONWebKeySet, SignJWT, exportJWK, generateKeyPair } from "jose";
import type { Deps } from "../src/deps";
import { handle } from "../src/index";
import { META } from "../src/segments";

/** Local host: dev auth only answers on localhost / 127.0.0.1 / 10.0.2.2. */
export const BASE = "http://localhost:8787";

export class Clock {
  constructor(public t = Date.UTC(2026, 9, 19, 12, 0, 0)) {}
  advanceHours(h: number) {
    this.t += h * 3_600_000;
  }
}

export interface Harness {
  clock: Clock;
  jwks: JSONWebKeySet;
  deps: Deps;
  /** Outbound requests made via deps.fetch (Apple token/revoke). */
  outbound: { url: string; body: string }[];
  outboundResponse: () => Response;
  call(method: string, path: string, opts?: { token?: string; body?: unknown; headers?: Record<string, string> }): Promise<Response>;
}

export function harness(jwks: JSONWebKeySet = { keys: [] }): Harness {
  const clock = new Clock();
  const h: Harness = {
    clock,
    jwks,
    outbound: [],
    outboundResponse: () => new Response(JSON.stringify({ refresh_token: "apple-refresh-1" }), { status: 200 }),
    deps: {
      now: () => clock.t,
      fetchJwks: async () => h.jwks,
      fetch: async (url, init) => {
        h.outbound.push({ url, body: String(init?.body ?? "") });
        return h.outboundResponse();
      },
    },
    async call(method, path, opts = {}) {
      const headers: Record<string, string> = { ...(opts.headers ?? {}) };
      if (opts.token) headers.authorization = `Bearer ${opts.token}`;
      let body: string | undefined;
      if (opts.body !== undefined) {
        body = typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body);
        headers["content-type"] = "application/json";
      }
      const ctx = createExecutionContext();
      const res = await handle(new Request(`${BASE}${path}`, { method, headers, body }), env, ctx, h.deps);
      await waitOnExecutionContext(ctx);
      return res;
    },
  };
  return h;
}

let devCounter = 0;
export async function devLogin(h: Harness, deviceId = `device-${Date.now()}-${devCounter++}`): Promise<{ token: string; userId: string }> {
  const res = await h.call("POST", "/v1/auth/dev", { body: { deviceId } });
  if (res.status !== 200) throw new Error(`dev login failed ${res.status} ${await res.text()}`);
  const j = (await res.json()) as { token: string; user: { userId: string } };
  return { token: j.token, userId: j.user.userId };
}

export async function rsaKey(kid: string) {
  const { publicKey, privateKey } = await generateKeyPair("RS256", { extractable: true });
  const jwk = { ...(await exportJWK(publicKey)), kid, alg: "RS256", use: "sig" };
  return { jwk, privateKey, kid };
}

export async function idToken(
  key: { privateKey: CryptoKey; kid: string },
  claims: { iss: string; aud: string; sub: string; nonce?: string },
  nowMs: number,
  expInS = 3600,
): Promise<string> {
  const iat = Math.floor(nowMs / 1000);
  const jwt = new SignJWT(claims.nonce ? { nonce: claims.nonce } : {})
    .setProtectedHeader({ alg: "RS256", kid: key.kid })
    .setIssuer(claims.iss)
    .setAudience(claims.aud)
    .setSubject(claims.sub)
    .setIssuedAt(iat)
    .setExpirationTime(iat + expInS);
  return jwt.sign(key.privateKey);
}

/** Bits with ticks [0, n) set, base64 — CoverageState encoding. */
export function fullBits(n: number): string {
  const bytes = new Uint8Array(Math.ceil(n / 8));
  for (let i = 0; i < n; i++) bytes[i >> 3]! |= 1 << (i & 7);
  return btoa(String.fromCharCode(...bytes));
}

export function bitsOf(ticks: number[]): string {
  const max = Math.max(...ticks);
  const bytes = new Uint8Array((max >> 3) + 1);
  for (const i of ticks) bytes[i >> 3]! |= 1 << (i & 7);
  return btoa(String.fromCharCode(...bytes));
}

export function upload(segments: Record<string, string>, walks: { id: string; durationS: number }[] = []) {
  return { datasetVersion: META.version, tickM: 5, segments, walks };
}

/** One new walk long enough (at 4 m/s) to cover `metres`. */
export function walkFor(metres: number): { id: string; durationS: number } {
  return { id: crypto.randomUUID(), durationS: Math.ceil(metres / 4) };
}

export const ADMIN = { token: "test-admin-token-0123456789" };

/** Upload `segments` as the result of one new 1-hour walk (advances the clock by 1 h; allows 14.9 km). */
export async function syncWalk(h: Harness, token: string, segments: Record<string, string>): Promise<Response> {
  h.clock.advanceHours(1);
  return h.call("PUT", "/v1/coverage", { token, body: upload(segments, [{ id: crypto.randomUUID(), durationS: 3600 }]) });
}

/** Full coverage for every segment of a trail. */
export function trailFull(trailId: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const id of META.trails[trailId]!) out[id] = fullBits(META.segments[id]![1]);
  return out;
}

export { META };
