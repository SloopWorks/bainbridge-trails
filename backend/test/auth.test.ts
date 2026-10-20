import { createExecutionContext, env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { cachedJwks, clearJwksCache } from "../src/deps";
import { handle } from "../src/index";
import { devLogin, harness, idToken, rsaKey } from "./helpers";

const GOOGLE = "https://accounts.google.com";
const APPLE = "https://appleid.apple.com";

describe("dev auth", () => {
  it("issues a session usable on /v1/me, stable per deviceId", async () => {
    const h = harness();
    const a = await devLogin(h, "emulator-1");
    const b = await devLogin(h, "emulator-1");
    expect(a.userId).toBe(b.userId);
    expect(a.token).not.toBe(b.token);
    expect(a.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const me = await h.call("GET", "/v1/me", { token: a.token });
    expect(me.status).toBe(200);
    const j = (await me.json()) as { userId: string; isAnonymous: boolean; displayName: string };
    expect(j.userId).toBe(a.userId);
    expect(j.isAnonymous).toBe(true);
    expect(j.displayName).toMatch(/^\S+ \S+ [1-9]\d$/);
  });

  it("stores only the SHA-256 of the token, with 90-day expiry", async () => {
    const h = harness();
    const { token, userId } = await devLogin(h);
    const row = await env.DB.prepare("SELECT token_hash, expires_at FROM sessions WHERE user_id = ?").bind(userId).first<{
      token_hash: string;
      expires_at: number;
    }>();
    expect(row!.token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(row!.token_hash).not.toContain(token);
    expect(row!.expires_at - h.clock.t).toBe(90 * 24 * 3600 * 1000);
    h.clock.advanceHours(90 * 24 + 1);
    expect((await h.call("GET", "/v1/me", { token })).status).toBe(401);
  });

  it("uses a valid client anon name, ignores an invalid one", async () => {
    const h = harness();
    const r1 = await h.call("POST", "/v1/auth/dev", { body: { deviceId: "anon-ok", anonName: "Mossy Heron 42" } });
    expect(((await r1.json()) as { user: { anonName: string } }).user.anonName).toBe("Mossy Heron 42");
    const r2 = await h.call("POST", "/v1/auth/dev", { body: { deviceId: "anon-bad", anonName: "Evil Name 42" } });
    expect(((await r2.json()) as { user: { anonName: string } }).user.anonName).not.toBe("Evil Name 42");
  });

  it("is 404 unless ALLOW_DEV_AUTH=true", async () => {
    const h = harness();
    const res = await handle(
      new Request("http://localhost:8787/v1/auth/dev", { method: "POST", body: JSON.stringify({ deviceId: "x" }) }),
      { ...env, ALLOW_DEV_AUTH: undefined } as Env,
      createExecutionContext(),
      h.deps,
    );
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: { code: "not_found", message: "no such endpoint" } });
  });

  it("is 404 on a non-local host even with ALLOW_DEV_AUTH=true", async () => {
    const res = await handle(
      new Request("https://bainbridge-trails-api.example.workers.dev/v1/auth/dev", { method: "POST", body: JSON.stringify({ deviceId: "x" }) }),
      env,
      createExecutionContext(),
      harness().deps,
    );
    expect(res.status).toBe(404);
  });

  it("rejects bad deviceId", async () => {
    const res = await harness().call("POST", "/v1/auth/dev", { body: { deviceId: "has spaces" } });
    expect(res.status).toBe(400);
  });

  it("logout revokes the session", async () => {
    const h = harness();
    const { token } = await devLogin(h);
    expect((await h.call("POST", "/v1/auth/logout", { token })).status).toBe(204);
    const res = await h.call("GET", "/v1/me", { token });
    expect(res.status).toBe(401);
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe("unauthorized");
  });
});

describe("Google / Apple ID tokens (JWKS)", () => {
  it("accepts a valid Google token for any configured client id; same sub -> same user", async () => {
    const key = await rsaKey("g1");
    const h = harness({ keys: [key.jwk] });
    const t1 = await idToken(key, { iss: GOOGLE, aud: "google-client-b", sub: "g-sub-1" }, h.clock.t);
    const r1 = await h.call("POST", "/v1/auth/google", { body: { idToken: t1 } });
    expect(r1.status).toBe(200);
    const j1 = (await r1.json()) as { token: string; isNewUser: boolean; user: { userId: string; provider: string } };
    expect(j1.isNewUser).toBe(true);
    expect(j1.user.provider).toBe("google");
    const t2 = await idToken(key, { iss: "accounts.google.com", aud: "google-client-a", sub: "g-sub-1" }, h.clock.t);
    const j2 = (await (await h.call("POST", "/v1/auth/google", { body: { idToken: t2 } })).json()) as typeof j1;
    expect(j2.user.userId).toBe(j1.user.userId);
    expect(j2.isNewUser).toBe(false);
  });

  it("accepts Apple tokens and checks nonce when sent", async () => {
    const key = await rsaKey("a1");
    const h = harness({ keys: [key.jwk] });
    const t = await idToken(key, { iss: APPLE, aud: "com.sloopworks.bainbridgetrails", sub: "a-sub", nonce: "n-123" }, h.clock.t);
    expect((await h.call("POST", "/v1/auth/apple", { body: { idToken: t, nonce: "n-123" } })).status).toBe(200);
    const bad = await h.call("POST", "/v1/auth/apple", { body: { idToken: t, nonce: "other" } });
    expect(bad.status).toBe(401);
  });

  it.each([
    ["wrong audience", { iss: GOOGLE, aud: "someone-else", sub: "s" }, 3600],
    ["wrong issuer", { iss: "https://evil.example", aud: "google-client-a", sub: "s" }, 3600],
    ["apple issuer on google endpoint", { iss: APPLE, aud: "google-client-a", sub: "s" }, 3600],
    ["expired", { iss: GOOGLE, aud: "google-client-a", sub: "s" }, -3600],
  ])("rejects %s", async (_name, claims, exp) => {
    const key = await rsaKey("g2");
    const h = harness({ keys: [key.jwk] });
    const t = await idToken(key, claims, h.clock.t, exp);
    const res = await h.call("POST", "/v1/auth/google", { body: { idToken: t } });
    expect(res.status).toBe(401);
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe("invalid_token");
  });

  it("rejects a token signed by a key not in the JWKS", async () => {
    const good = await rsaKey("k-good");
    const evil = await rsaKey("k-good"); // same kid, different key
    const h = harness({ keys: [good.jwk] });
    const t = await idToken(evil, { iss: GOOGLE, aud: "google-client-a", sub: "s" }, h.clock.t);
    expect((await h.call("POST", "/v1/auth/google", { body: { idToken: t } })).status).toBe(401);
    const unknownKid = await rsaKey("k-unknown");
    const t2 = await idToken(unknownKid, { iss: GOOGLE, aud: "google-client-a", sub: "s" }, h.clock.t);
    expect((await h.call("POST", "/v1/auth/google", { body: { idToken: t2 } })).status).toBe(401);
  });

  it("rejects garbage", async () => {
    const h = harness();
    expect((await h.call("POST", "/v1/auth/google", { body: { idToken: "not-a-jwt-at-all" } })).status).toBe(401);
    expect((await h.call("POST", "/v1/auth/google", { body: "{" })).status).toBe(400);
    expect((await h.call("POST", "/v1/auth/google", { body: {} })).status).toBe(400);
  });
});

describe("JWKS cache (P2-12)", () => {
  it("refetches an unknown kid at most every 5 minutes and serves stale keys when the provider fails", async () => {
    clearJwksCache();
    const url = "https://jwks.test/certs";
    let calls = 0;
    const ok = async () => {
      calls++;
      return { keys: [] };
    };
    const t0 = 1_000_000;
    await cachedJwks(url, false, t0, ok);
    await cachedJwks(url, true, t0 + 60_000, ok); // unknown kid 1 min later: no refetch
    expect(calls).toBe(1);
    await cachedJwks(url, true, t0 + 6 * 60_000, ok); // after 5 min: refetch
    expect(calls).toBe(2);
    const failing = async () => {
      throw new Error("down");
    };
    await expect(cachedJwks(url, false, t0 + 2 * 3600_000, failing)).resolves.toEqual({ keys: [] });
    clearJwksCache();
    await expect(cachedJwks(url, false, t0, failing)).rejects.toThrow("down");
  });
});

describe("auth rate limit keys (P1-5)", () => {
  it("never store the raw IP", async () => {
    const h = harness();
    await h.call("POST", "/v1/auth/google", { body: { idToken: "garbage-token-xx" }, headers: { "cf-connecting-ip": "203.0.113.77" } });
    const { results } = await env.DB.prepare("SELECT key FROM rate_limits WHERE key LIKE 'auth:%'").run<{ key: string }>();
    expect(results.length).toBeGreaterThan(0);
    for (const r of results) {
      expect(r.key).not.toContain("203.0.113.77");
      expect(r.key).toMatch(/^auth:[0-9a-f]{32}$/);
    }
  });
});
