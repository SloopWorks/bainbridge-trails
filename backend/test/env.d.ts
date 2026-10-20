declare namespace Cloudflare {
  interface Env {
    TEST_MIGRATIONS: import("cloudflare:test").D1Migration[];
    /** Scratch database for test/migrations.test.ts (schema built step by step). */
    MIGRATION_DB: D1Database;
    /** migrations/0004_cleanup.sql.pending, split into statements. */
    CLEANUP_QUERIES: string[];
  }
}
