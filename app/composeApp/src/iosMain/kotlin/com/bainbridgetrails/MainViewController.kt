package com.bainbridgetrails

import androidx.compose.ui.window.ComposeUIViewController
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.record.debugAutostartRecording
import com.bainbridgetrails.config.ApiConfig
import com.bainbridgetrails.nav.DebugLaunch
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults

/** Launch arguments `-spike key=value ...` (e.g. via `simctl launch`) feed [SpikeLaunch] (spike only). */
fun MainViewController() = run {
    debugApiBaseUrl()
    AppGraph.warmUp() // dataset parse overlaps the first composition
    ComposeUIViewController { App(spikeLaunchFromArguments()) }
        .also { debugAutostartRecording() } // debug builds + `-autostartRecording 1` only (simulator verification)
}

/** Null without `key=value` arguments: a normal launch (tab shell). */
private fun spikeLaunchFromArguments(): SpikeLaunch? {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val options = args.filter { '=' in it && !it.startsWith("-") }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    return if (options.isEmpty()) null else SpikeLaunch.parse(options)
}

/**
 * Debug binaries only: `-apiBaseUrl http://127.0.0.1:8798` (a launch argument, so it lasts for that launch) points the
 * API at a local `wrangler dev` (iOS has no debug drawer backend switch; release always uses the production URL), and
 * `-btScreen`/`-btScroll`/`-btPage`/`-btDevSignIn` fill [DebugLaunch] for simulator screenshots.
 */
@OptIn(ExperimentalNativeApi::class)
private fun debugApiBaseUrl() {
    if (!Platform.isDebugBinary) return
    val d = NSUserDefaults.standardUserDefaults
    d.stringForKey("btScreen")?.let { DebugLaunch.screen = it }
    DebugLaunch.scroll = d.integerForKey("btScroll").toInt()
    DebugLaunch.page = d.integerForKey("btPage").toInt()
    DebugLaunch.devSignIn = d.boolForKey("btDevSignIn")
    val url = d.stringForKey("apiBaseUrl")?.takeIf { it.startsWith("http") } ?: return
    ApiConfig.resolver = { url }
}
