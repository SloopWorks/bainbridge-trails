import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  // not yet a migration (applied by hand after deploy); test/migrations.test.ts runs it on a scratch database
  const pending = await readD1Migrations({ migrationsDir: "migrations", migrationsPattern: "migrations/*.sql.pending" });
  const cleanup = pending.find((m) => m.name.startsWith("0004_cleanup"))?.queries;
  if (!cleanup) throw new Error("migrations/0004_cleanup.sql.pending not found");
  return {
    plugins: [
      cloudflareTest({
        main: "./src/index.ts",
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          d1Databases: { MIGRATION_DB: "migration-test-db", MIGRATION_DB_PLAY: "migration-play-test-db" },
          bindings: {
            TEST_MIGRATIONS: migrations,
            CLEANUP_QUERIES: cleanup,
            GOOGLE_CLIENT_IDS: "google-client-a,google-client-b",
            APPLE_CLIENT_IDS: "com.sloopworks.bainbridgetrails",
            ALLOW_DEV_AUTH: "true",
            ADMIN_TOKEN: "test-admin-token-0123456789",
            // a local .dev.vars may carry an e2e clock offset; tests run on the real (injected) clock
            DEV_CLOCK_OFFSET_MS: "0",
          },
        },
      }),
    ],
    test: { setupFiles: ["./test/apply-migrations.ts"] },
  };
});
