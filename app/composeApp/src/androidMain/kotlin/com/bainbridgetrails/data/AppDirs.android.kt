package com.bainbridgetrails.data

import com.bainbridgetrails.record.PlatformContext

actual fun appDataDir(context: PlatformContext): String = context.filesDir.absolutePath
