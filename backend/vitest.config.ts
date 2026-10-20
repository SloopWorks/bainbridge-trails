import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        main: "./src/index.ts",
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            GOOGLE_CLIENT_IDS: "google-client-a,google-client-b",
            APPLE_CLIENT_IDS: "com.sloopworks.bainbridgetrails",
            ALLOW_DEV_AUTH: "true",
            ADMIN_TOKEN: "test-admin-token-0123456789",
          },
        },
      }),
    ],
    test: { setupFiles: ["./test/apply-migrations.ts"] },
  };
});
