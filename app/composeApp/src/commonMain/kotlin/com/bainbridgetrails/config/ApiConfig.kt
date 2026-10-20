package com.bainbridgetrails.config

/**
 * Backend base URL. Release always uses [PROD_BASE_URL]. Debug builds replace [resolver] with the debug drawer's
 * backend switch (`DebugDrawer.backendUrl(PROD_BASE_URL)`), so the emulator can point at a local `wrangler dev`.
 * Read [baseUrl] when building a request (not once at startup) so a drawer switch applies after its restart.
 */
object ApiConfig {
    /**
     * TEMPORARY workers.dev host (deployed 2026-10-20) so sign-in and sync can be integrated now.
     * TODO(before store release): switch to the final custom domain; also metadata/ios/en-US/{privacy,support}_url.txt.
     */
    const val PROD_BASE_URL = "https://bainbridge-trails-api.patjackson52.workers.dev"

    var resolver: () -> String = { PROD_BASE_URL }

    val baseUrl: String get() = resolver().trimEnd('/')

    /** Public web pages served by the backend (store-required): `/privacy`, `/terms`, `/support`. */
    fun pageUrl(path: String): String = baseUrl + "/" + path.trimStart('/')
}
