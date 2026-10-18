package com.bainbridgetrails.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// Library/ (not "Application Support"): no space in the path, so the file URL needs no escaping.
@Composable
internal actual fun rememberMapDataDir(): String = remember {
    NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true).first() as String
}

/** Tiles are re-creatable from the bundle: keep them out of iCloud/device backups. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun excludeFromBackup(path: String) {
    NSURL.fileURLWithPath(path).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
}
