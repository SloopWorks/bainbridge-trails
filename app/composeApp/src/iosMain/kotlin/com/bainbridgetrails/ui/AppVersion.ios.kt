package com.bainbridgetrails.ui

import com.bainbridgetrails.record.PlatformContext
import platform.Foundation.NSBundle

actual fun appVersion(context: PlatformContext): AppVersion {
    val info = NSBundle.mainBundle.infoDictionary
    return AppVersion(
        info?.get("CFBundleShortVersionString")?.toString() ?: "?",
        info?.get("CFBundleVersion")?.toString() ?: "?",
    )
}
