declare namespace Cloudflare {
  interface Env {
    TEST_MIGRATIONS: import("cloudflare:test").D1Migration[];
    /** Scratch database for test/migrations.test.ts (schema built step by step). */
    MIGRATION_DB: D1Database;
    /** Scratch database for the 0005 (Play) ordering tests in test/migrations.test.ts. */
    MIGRATION_DB_PLAY: D1Database;
    /** Scratch database for the 0007 (Play trails) test in test/migrations.test.ts (built to 0006 first). */
    MIGRATION_DB_TRAILS: D1Database;
    /** migrations/0004_cleanup.sql.pending, split into statements. */
    CLEANUP_QUERIES: string[];
    /** "1" when the shell ran vitest with PLAY_GOLDEN_WRITE=1 (golden capture, test/play-roads-guard.test.ts). */
    PLAY_GOLDEN_WRITE: string;
    /** Node-side writer of data/play/golden/<name>.json (vitest.config.ts); refuses unless PLAY_GOLDEN_WRITE=1. */
    GOLDEN_WRITER: Fetcher;
  }
}
