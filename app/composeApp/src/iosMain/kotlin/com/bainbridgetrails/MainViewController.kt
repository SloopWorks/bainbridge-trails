package com.bainbridgetrails

import androidx.compose.ui.window.ComposeUIViewController
import com.bainbridgetrails.map.SpikeLaunch
import com.bainbridgetrails.record.debugAutostartRecording
import platform.Foundation.NSProcessInfo

/** Launch arguments `-spike key=value ...` (e.g. via `simctl launch`) feed [SpikeLaunch] (spike only). */
fun MainViewController() = ComposeUIViewController { App(spikeLaunchFromArguments()) }
    .also { debugAutostartRecording() } // debug builds + `-autostartRecording 1` only (simulator verification)

/** Null without `key=value` arguments: a normal launch (tab shell). */
private fun spikeLaunchFromArguments(): SpikeLaunch? {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val options = args.filter { '=' in it && !it.startsWith("-") }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    return if (options.isEmpty()) null else SpikeLaunch.parse(options)
}
