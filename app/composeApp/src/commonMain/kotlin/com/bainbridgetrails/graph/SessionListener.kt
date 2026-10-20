package com.bainbridgetrails.graph

import com.bainbridgetrails.auth.AuthListener
import com.bainbridgetrails.data.ProfileRepository
import com.bainbridgetrails.net.AuthResponse
import com.bainbridgetrails.sync.SyncRepository

/**
 * The app's reaction to auth changes: profile, sync and cached account data ([onAuthChanged]: leaderboard).
 * [clearPhone] runs after an account deletion with "also clear this phone" (F9e).
 */
internal class SessionListener(
    private val sync: SyncRepository,
    private val profile: ProfileRepository,
    private val onAuthChanged: suspend () -> Unit,
    private val clearPhone: suspend () -> Unit,
) : AuthListener {
    override suspend fun onSignedIn(response: AuthResponse) {
        profile.onSignedIn(response.user)
        onAuthChanged()
        sync.onSignedIn() // backfill (TL-2)
    }

    override suspend fun onSignedOut(deleted: Boolean, wipeLocal: Boolean) {
        val wipe = deleted && wipeLocal
        sync.onSignedOut(wipe)
        profile.onSignedOut(wipe = deleted)
        onAuthChanged()
        if (wipe) clearPhone()
    }
}
