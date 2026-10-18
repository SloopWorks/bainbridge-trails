package com.bainbridgetrails.map

import androidx.compose.runtime.Composable

/** Absolute path of a private, persistent app directory for map data. */
@Composable
internal expect fun rememberMapDataDir(): String

/** Marks [path] (a directory) as excluded from device backups (iOS: NSURLIsExcludedFromBackupKey; Android: no-op). */
internal expect fun excludeFromBackup(path: String)
