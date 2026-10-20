package com.bainbridgetrails.ui

import android.os.Build
import com.bainbridgetrails.record.PlatformContext

actual fun appVersion(context: PlatformContext): AppVersion = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    AppVersion(info.versionName ?: "?", code.toString())
}.getOrDefault(AppVersion("?", "?"))
