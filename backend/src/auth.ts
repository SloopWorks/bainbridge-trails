import { createLocalJWKSet, errors as joseErrors, jwtVerify } from "jose";
import type { Deps } from "./deps";
import { ApiError, badRequest, bearer } from "./http";
import { anonName, isAnonName } from "./moderation/names";
import { USER_COLS, USER_COLS_U, type UserRow, metricsFor } from "./users";
import { base64UrlRandom, csv, sha256Hex } from "./util";

export type { UserRow } from "./users";
export type Provider = "google" | "apple" | "dev";

interface ProviderConfig {
  jwksUrl: string;
  issuers: string[];
  audiences: (env: Env) => string[];
}

export const PROVIDERS: Record<"google" | "apple", ProviderConfig> = {
  google: {
    jwksUrl: "https://www.googleapis.com/oauth2/v3/certs",
    issuers: ["https://accounts.google.com", "accounts.google.com"],
    audiences: (env) => csv(env.GOOGLE_CLIENT_IDS),
  },
  apple: {
    jwksUrl: "https://appleid.apple.com/auth/keys",
    issuers: ["https://appleid.apple.com"],
    audiences: (env) => csv(env.APPLE_CLIENT_IDS),
  },
};

export const SESSION_TTL_MS = 90 * 24 * 60 * 60 * 1000;

/** Verifies a Google/Apple ID token (RS256, JWKS, iss, aud, exp, optional nonce); returns sub + matched aud. */
export async function verifyIdToken(
  provider: "google" | "apple",
  idToken: string,
  env: Env,
  deps: Deps,
  nonce?: string,
): Promise<{ sub: string; aud: string }> {
  const cfg = PROVIDERS[provider];
  const audience = cfg.audiences(env);
  if (audience.length === 0) throw new ApiError(503, "provider_not_configured", `${provider} sign-in is not configured`);
  const verify = async (refresh: boolean) =>
    jwtVerify(idToken, createLocalJWKSet(await deps.fetchJwks(cfg.jwksUrl, refresh)), {
      issuer: cfg.issuers,
      audience,
      algorithms: ["RS256"],
      clockTolerance: 60,
      currentDate: new Date(deps.now()),
    });
  let payload;
  try {
    try {
      ({ payload } = await verify(false));
    } catch (e) {
      if (!(e instanceof joseErrors.JWKSNoMatchingKey)) throw e;
      ({ payload } = await verify(true)); // key rotation: refetch (rate limited in deps)
    }
  } catch (e) {
    if (e instanceof joseErrors.JOSEError) throw new ApiError(401, "invalid_token", `ID token rejected: ${e.code}`);
    throw new ApiError(502, "provider_unavailable", "could not fetch provider keys");
  }
  if (typeof payload.sub !== "string" || payload.sub.length === 0) throw new ApiError(401, "invalid_token", "ID token has no sub");
  if (nonce !== undefined && payload.nonce !== nonce) throw new ApiError(401, "invalid_token", "nonce mismatch");
  const aud = Array.isArray(payload.aud) ? payload.aud.find((a) => audience.includes(a)) : payload.aud;
  return { sub: payload.sub, aud: aud ?? audience[0]! };
}

/** Finds or creates the user for (provider, sub), then issues a session. */
export async function signIn(env: Env, deps: Deps, provider: Provider, sub: string, clientAnonName?: unknown) {
  const now = deps.now();
  const inserted = await env.DB.prepare(
    `INSERT INTO users (id, provider, sub, anon_name) VALUES (?, ?, ?, ?)
     ON CONFLICT (provider, sub) DO NOTHING`,
  )
    .bind(crypto.randomUUID(), provider, sub, isAnonName(clientAnonName) ? clientAnonName : anonName())
    .run();
  const user = await env.DB.prepare(`SELECT ${USER_COLS} FROM users WHERE provider = ? AND sub = ?`).bind(provider, sub).first<UserRow>();
  if (!user) throw new ApiError(500, "internal", "user upsert failed");
  // the sign-in response's `user` carries the trails metrics (unchanged contract; RD-7)
  const metrics = await metricsFor(env, user.id, "trails", now);
  const token = base64UrlRandom(32);
  const expiresAt = now + SESSION_TTL_MS;
  await env.DB.batch([
    env.DB.prepare("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)").bind(await sha256Hex(token), user.id, expiresAt),
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ? AND expires_at < ?").bind(user.id, now),
  ]);
  return { token, expiresAt, user, metrics, isNewUser: inserted.meta.changes === 1 };
}

/** Returns the session user, null when no Authorization header; throws 401 for a bad/expired token. */
export async function optionalUser(request: Request, env: Env, deps: Deps): Promise<UserRow | null> {
  const token = bearer(request);
  if (token === null) return null;
  const row = await env.DB.prepare(
    `SELECT ${USER_COLS_U} FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > ?`,
  )
    .bind(await sha256Hex(token), deps.now())
    .first<UserRow>();
  if (!row) throw new ApiError(401, "unauthorized", "invalid or expired session");
  return row;
}

export async function requireUser(request: Request, env: Env, deps: Deps): Promise<UserRow> {
  const user = await optionalUser(request, env, deps);
  if (!user) throw new ApiError(401, "unauthorized", "Authorization: Bearer <token> required");
  return user;
}

export async function logout(request: Request, env: Env): Promise<void> {
  const token = bearer(request);
  if (token === null) throw new ApiError(401, "unauthorized", "Authorization: Bearer <token> required");
  await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(await sha256Hex(token)).run();
}

export function parseIdTokenBody(body: unknown): { idToken: string; nonce?: string; anonName?: unknown; authorizationCode?: string } {
  if (typeof body !== "object" || body === null) throw badRequest("body must be an object");
  const b = body as Record<string, unknown>;
  if (typeof b.idToken !== "string" || b.idToken.length < 10 || b.idToken.length > 8192) throw badRequest("idToken required");
  if (b.nonce !== undefined && typeof b.nonce !== "string") throw badRequest("nonce must be a string");
  if (b.authorizationCode !== undefined && (typeof b.authorizationCode !== "string" || b.authorizationCode.length > 2048)) {
    throw badRequest("authorizationCode must be a string");
  }
  return {
    idToken: b.idToken,
    anonName: b.anonName,
    ...(typeof b.nonce === "string" ? { nonce: b.nonce } : {}),
    ...(typeof b.authorizationCode === "string" ? { authorizationCode: b.authorizationCode } : {}),
  };
}
