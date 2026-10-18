package com.bainbridgetrails

import androidx.compose.ui.window.ComposeUIViewController
import com.bainbridgetrails.map.SpikeLaunch
import platform.Foundation.NSProcessInfo

/** Launch arguments `-spike key=value ...` (e.g. via `simctl launch`) feed [SpikeLaunch] (spike only). */
fun MainViewController() = ComposeUIViewController { App(spikeLaunchFromArguments()) }

private fun spikeLaunchFromArguments(): SpikeLaunch {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val options = args.filter { '=' in it && !it.startsWith("-") }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    return SpikeLaunch.parse(options)
}
