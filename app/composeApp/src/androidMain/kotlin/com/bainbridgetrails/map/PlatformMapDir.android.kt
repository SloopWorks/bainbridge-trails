package com.bainbridgetrails.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberMapDataDir(): String {
    val context = LocalContext.current
    // noBackupFilesDir: ~hundreds of MB of re-creatable tiles must not eat the Auto Backup quota.
    return remember(context) { context.noBackupFilesDir.absolutePath }
}

/** Android: noBackupFilesDir is already excluded from backup. */
internal actual fun excludeFromBackup(path: String) = Unit
