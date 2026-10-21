package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.lowPowerModeEnabled

/** `NSProcessInfo.lowPowerModeEnabled`, re-read on `NSProcessInfoPowerStateDidChangeNotification` (main queue). */
@Composable
actual fun rememberPowerSaveMode(): Boolean {
    var lowPower by remember { mutableStateOf(NSProcessInfo.processInfo.lowPowerModeEnabled) }
    DisposableEffect(Unit) {
        val token = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSProcessInfoPowerStateDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> lowPower = NSProcessInfo.processInfo.lowPowerModeEnabled }
        lowPower = NSProcessInfo.processInfo.lowPowerModeEnabled
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(token) }
    }
    return lowPower
}
