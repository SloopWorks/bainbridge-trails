package com.sloopworks.debugdrawer

import androidx.compose.runtime.Composable

// Mirrors the subset of com.sloopworks.debugdrawer's consumer API used by this app (signatures identical).
// Keep app code to these symbols so it compiles against the real drawer, its noop twin and this shim.

data class Backend(val id: String, val label: String, val url: String)

interface DebugPlugin {
    val id: String
    val title: String
    @Composable fun Content(scope: DebugScope)
}

/** The real scope also exposes store/logs; this app's plugins use only these members. */
interface DebugScope {
    val backends: List<Backend>
    fun activeBackendId(): String
    fun requestRestart()
    fun copy(text: String)
}

data class BuildInfo(
    val version: String,
    val build: String,
    val buildType: String = "debug",
    val commit: String? = null,
    val flavor: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

data class DebugDrawerConfig(
    val buildInfo: BuildInfo,
    val backends: List<Backend> = emptyList(),
    val plugins: List<DebugPlugin> = emptyList(),
    val includeBuiltins: Boolean = true,
)

object DebugDrawer {
    fun install(config: DebugDrawerConfig, context: Any? = null) { /* no-op */ }
    fun backendUrl(default: String): String = default
    fun selectedBackendId(): String? = null
}

@Composable
fun DebugDrawerHost(content: @Composable () -> Unit) {
    content()
}
