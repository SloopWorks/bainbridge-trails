package com.bainbridgetrails.auth

import com.bainbridgetrails.record.PlatformContext

/**
 * App-private directory excluded from device/cloud backup, for the session token and sync bookkeeping.
 * Android: `Context.noBackupFilesDir`. iOS: Application Support/private with `NSURLIsExcludedFromBackupKey`.
 *
 * MVP storage choice: a plain file here, not Keystore/Keychain (EncryptedSharedPreferences is deprecated). The
 * token is an opaque, revocable 90-day session for a leaderboard; the app sandbox protects it, and excluding it
 * from backup keeps it from being restored onto another device. Move to Keychain / a Keystore-wrapped key if it
 * ever guards anything more sensitive.
 */
expect fun privateNoBackupDir(context: PlatformContext): String
