import { SignJWT, importPKCS8 } from "jose";
import type { Deps } from "./deps";

/** TL-10: Apple token exchange/revocation is enabled only when team id, key id and private key are configured. */
export function appleRevokeConfigured(env: Env): boolean {
  return Boolean(env.APPLE_TEAM_ID && env.APPLE_KEY_ID && env.APPLE_PRIVATE_KEY);
}

async function clientSecret(env: Env, deps: Deps, clientId: string): Promise<string> {
  const key = await importPKCS8(env.APPLE_PRIVATE_KEY!.replace(/\\n/g, "\n"), "ES256");
  const iat = Math.floor(deps.now() / 1000);
  return new SignJWT({})
    .setProtectedHeader({ alg: "ES256", kid: env.APPLE_KEY_ID })
    .setIssuer(env.APPLE_TEAM_ID)
    .setSubject(clientId)
    .setAudience("https://appleid.apple.com")
    .setIssuedAt(iat)
    .setExpirationTime(iat + 300)
    .sign(key);
}

const form = (o: Record<string, string>) => new URLSearchParams(o).toString();
const FORM = { "content-type": "application/x-www-form-urlencoded" };

/** Exchanges the one-time authorization code for a refresh token (needed later to revoke). Null on any failure. */
export async function exchangeAppleCode(env: Env, deps: Deps, clientId: string, code: string): Promise<string | null> {
  if (!appleRevokeConfigured(env)) return null;
  try {
    const res = await deps.fetch("https://appleid.apple.com/auth/token", {
      method: "POST",
      headers: FORM,
      body: form({ client_id: clientId, client_secret: await clientSecret(env, deps, clientId), code, grant_type: "authorization_code" }),
    });
    if (!res.ok) {
      console.warn(JSON.stringify({ msg: "apple code exchange failed", status: res.status }));
      return null;
    }
    const j = (await res.json()) as { refresh_token?: unknown };
    return typeof j.refresh_token === "string" ? j.refresh_token : null;
  } catch (e) {
    console.warn(JSON.stringify({ msg: "apple code exchange error", error: String(e) }));
    return null;
  }
}

/** Best effort: account deletion never fails because Apple is unreachable. */
export async function revokeApple(env: Env, deps: Deps, clientId: string, refreshToken: string): Promise<boolean> {
  if (!appleRevokeConfigured(env)) return false;
  try {
    const res = await deps.fetch("https://appleid.apple.com/auth/revoke", {
      method: "POST",
      headers: FORM,
      body: form({
        client_id: clientId,
        client_secret: await clientSecret(env, deps, clientId),
        token: refreshToken,
        token_type_hint: "refresh_token",
      }),
    });
    if (!res.ok) console.warn(JSON.stringify({ msg: "apple revoke failed", status: res.status }));
    return res.ok;
  } catch (e) {
    console.warn(JSON.stringify({ msg: "apple revoke error", error: String(e) }));
    return false;
  }
}
