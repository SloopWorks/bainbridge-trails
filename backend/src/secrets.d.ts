// Secrets / local-only vars are not in wrangler.jsonc, so `wrangler types` cannot see them.
interface Env {
  /** Secret (`wrangler secret put ADMIN_TOKEN`). Admin endpoints are disabled when unset. */
  ADMIN_TOKEN?: string;
  /** "true" only in local .dev.vars. Enables POST /v1/auth/dev. */
  ALLOW_DEV_AUTH?: string;
  /** Secret: Sign in with Apple private key (.p8 PEM) for token exchange/revocation. */
  APPLE_PRIVATE_KEY?: string;
}
