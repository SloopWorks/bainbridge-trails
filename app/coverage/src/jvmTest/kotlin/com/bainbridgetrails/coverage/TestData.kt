package com.bainbridgetrails.coverage

import java.io.File
import org.junit.Assume

/**
 * The real dataset (`data/dist/`, built by `pipeline/` + tippecanoe) and the app's bundled copy
 * (`scripts/sync-app-assets.sh`) are gitignored, so a plain clone or CI has neither. Tests that need them call
 * this: missing files report the test as skipped (JUnit assumption) instead of failing or silently passing.
 */
internal fun assumeDataFiles(test: String, vararg files: File) {
    val missing = files.filterNot { it.exists() }
    Assume.assumeTrue(
        "$test: SKIPPED, missing ${missing.joinToString { it.path }} " +
            "(build data/dist with pipeline/, then scripts/sync-app-assets.sh)",
        missing.isEmpty(),
    )
}
