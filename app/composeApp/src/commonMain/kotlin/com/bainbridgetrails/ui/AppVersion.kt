package com.bainbridgetrails.ui

import com.bainbridgetrails.record.PlatformContext

/** The installed app's marketing version and build number (Settings `settings_version`). */
data class AppVersion(val name: String, val build: String)

expect fun appVersion(context: PlatformContext): AppVersion
