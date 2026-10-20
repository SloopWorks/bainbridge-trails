package com.bainbridgetrails.config

/**
 * Backend base URL. Release always uses [PROD_BASE_URL]. Debug builds replace [resolver] with the debug drawer's
 * backend switch (`DebugDrawer.backendUrl(PROD_BASE_URL)`), so the emulator can point at a local `wrangler dev`.
 * Read [baseUrl] when building a request (not once at startup) so a drawer switch applies after its restart.
 */
object ApiConfig {
    /** Placeholder until the Worker is deployed under the owner's account. */
    const val PROD_BASE_URL = "https://api.bainbridgetrails.app"

    var resolver: () -> String = { PROD_BASE_URL }

    val baseUrl: String get() = resolver().trimEnd('/')
}
