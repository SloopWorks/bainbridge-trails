package com.bainbridgetrails.auth

import com.bainbridgetrails.record.PlatformContext

actual fun privateNoBackupDir(context: PlatformContext): String = context.noBackupFilesDir.absolutePath
