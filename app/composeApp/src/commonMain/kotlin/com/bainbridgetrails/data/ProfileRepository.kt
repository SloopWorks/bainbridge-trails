package com.bainbridgetrails.data

import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiError
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.net.MeDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A name change not yet accepted by the server (offline save, or chosen while signed out). [customName] null = anonymous. */
@Serializable
data class PendingName(val customName: String?, val anonName: String)

@Serializable
private data class ProfileFile(val anonName: String, val pending: PendingName? = null)

/**
 * What Me/Settings show. [me] = last server profile (null signed out / not fetched yet). [anonName] = this device's
 * anon name (generated at first launch, kept across sign-out). [pending] = unsynced name change.
 */
data class ProfileState(val anonName: String, val me: MeDto? = null, val pending: PendingName? = null) {
    /** Name to show for "you": the pending choice wins, then the server's, then the local anon name. */
    val displayName: String get() = pending?.let { it.customName ?: it.anonName } ?: me?.displayName ?: anonName

    /** Current custom name (pending or server), null when anonymous. */
    val customName: String? get() = if (pending != null) pending.customName else me?.customName

    /** Custom name was reset because it hit a newly added blocklist term (`name_reset_notice`). */
    val nameBlocked: Boolean get() = pending == null && me?.nameBlocked == true
}

sealed interface NameSaveResult {
    data class Saved(val me: MeDto) : NameSaveResult

    /** Offline or signed out: kept locally and sent on the next sync (`name_saved_offline`). */
    data object SavedLocally : NameSaveResult

    /** Failed client-side validation; nothing sent. */
    data class Invalid(val check: NameCheck) : NameSaveResult

    /** Server `name_rejected` (`name_err_blocked`); the old name stays. */
    data object Rejected : NameSaveResult

    /** Server `name_rate_limited` (`name_err_rate`). */
    data object RateLimited : NameSaveResult

    data class Failed(val error: ApiError) : NameSaveResult
}

/**
 * Profile + display name (F9a step 2, F9b). Anon names are generated on device (TL-4); the server validates.
 * Local file: [path] (`profile.json` in the app data dir).
 */
class ProfileRepository(
    private val api: ApiClient,
    path: String,
    private val isSignedIn: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val newAnonName: () -> String = { AnonNames.generate() },
) {
    private val file = JsonFile(path, ProfileFile.serializer())
    private val _profile = MutableStateFlow<ProfileState?>(null)
    /** Null until [load]. */
    val profile: StateFlow<ProfileState?> = _profile.asStateFlow()
    private val mutex = Mutex()

    /** Reads (or creates) the local anon name. Idempotent. */
    suspend fun load(): ProfileState = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): ProfileState {
        _profile.value?.let { return it }
        val f = withContext(io) {
            file.read()?.takeIf { AnonNames.isAnonName(it.anonName) } ?: ProfileFile(newAnonName()).also { file.write(it) }
        }
        return ProfileState(f.anonName, pending = f.pending).also { _profile.value = it }
    }

    suspend fun anonName(): String = load().anonName

    /** A fresh suggestion for Shuffle; nothing is saved until [saveAnonymous]. */
    fun shuffle(): String = newAnonName()

    /** Go (or stay) anonymous with [anonName] (current one unless shuffled, F9b.3). */
    suspend fun saveAnonymous(anonName: String? = null): NameSaveResult {
        val anon = anonName ?: load().anonName
        if (!AnonNames.isAnonName(anon)) return NameSaveResult.Invalid(NameCheck.InvalidCharacters)
        return save(PendingName(null, anon))
    }

    suspend fun saveCustomName(name: String): NameSaveResult = when (val check = DisplayNameRules.check(name)) {
        is NameCheck.Ok -> save(PendingName(check.normalized, load().anonName))
        else -> NameSaveResult.Invalid(check)
    }

    private suspend fun save(change: PendingName): NameSaveResult {
        mutex.withLock {
            val cur = loadLocked()
            persist(cur.copy(anonName = change.anonName, pending = change))
        }
        return flushPending() ?: NameSaveResult.SavedLocally
    }

    /**
     * Sends a pending name change, if any (also called by sync/foreground). Null = nothing sent (none pending,
     * signed out, or offline/transient: stays pending).
     */
    suspend fun flushPending(): NameSaveResult? {
        val pending = load().pending ?: return null
        if (!isSignedIn()) return null
        val patch = JsonObject(
            mapOf(
                "displayName" to (pending.customName?.let { JsonPrimitive(it) } ?: JsonNull),
                "anonName" to JsonPrimitive(pending.anonName),
            ),
        )
        return when (val r = api.putMe(patch)) {
            is ApiResult.Ok -> {
                applyServer(r.value, clearPending = pending)
                NameSaveResult.Saved(r.value)
            }
            is ApiResult.Failure -> when (val e = r.error) {
                is ApiError.Offline, is ApiError.Unavailable, is ApiError.RateLimited -> null
                is ApiError.NameRejected -> dropPending(pending).let { NameSaveResult.Rejected }
                is ApiError.NameRateLimited -> dropPending(pending).let { NameSaveResult.RateLimited }
                is ApiError.NameInvalid -> dropPending(pending).let { NameSaveResult.Invalid(NameCheck.InvalidCharacters) }
                is ApiError.Unauthorized -> null // session cleared by the client; keep the change for the next sign-in
                else -> NameSaveResult.Failed(e)
            }
        }
    }

    /**
     * P1-8 "Hide me from the leaderboard" (ux.md §3.9: the toggle saves at once, optimistic, reverted on failure).
     * Online only: the server hides the row immediately, so there is no offline queue. Signed out / no profile yet
     * → [ApiError.Unauthorized] without a request.
     */
    suspend fun setHiddenFromBoard(hidden: Boolean): ApiResult<MeDto> {
        val before = mutex.withLock {
            val cur = loadLocked()
            val me = cur.me
            if (me == null || !isSignedIn()) return ApiResult.Failure(ApiError.Unauthorized("signed out"))
            _profile.value = cur.copy(me = me.copy(hiddenFromBoard = hidden))
            me.hiddenFromBoard
        }
        val r = api.putMe(JsonObject(mapOf("hiddenFromBoard" to JsonPrimitive(hidden))))
        when (r) {
            is ApiResult.Ok -> applyServer(r.value, clearPending = null)
            is ApiResult.Failure -> mutex.withLock {
                val cur = _profile.value
                val me = cur?.me
                // Revert only our optimistic flip (a sign-out meanwhile cleared `me`: nothing to revert).
                if (me != null && me.hiddenFromBoard == hidden) _profile.value = cur.copy(me = me.copy(hiddenFromBoard = before))
            }
        }
        return r
    }

    /** GET /v1/me (after flushing a pending change). */
    suspend fun refresh(): ApiResult<MeDto> {
        flushPending()
        val r = api.getMe()
        if (r is ApiResult.Ok) applyServer(r.value, clearPending = null)
        return r
    }

    /** After sign-in: adopt the account's anon name and profile, then send any name chosen while signed out. */
    suspend fun onSignedIn(me: MeDto) {
        applyServer(me, clearPending = null)
        flushPending()
    }

    /**
     * Signed out: forget the server profile; keep the device's anon name. A pending name change stays (sent after
     * the next sign-in) unless the account was deleted / this phone wiped ([wipe]).
     */
    suspend fun onSignedOut(wipe: Boolean) = mutex.withLock {
        val cur = loadLocked()
        persist(cur.copy(me = null, pending = if (wipe) null else cur.pending))
    }

    private suspend fun applyServer(me: MeDto, clearPending: PendingName?) = mutex.withLock {
        val cur = loadLocked()
        val pending = if (clearPending != null && cur.pending == clearPending) null else cur.pending
        val anon = if (AnonNames.isAnonName(me.anonName)) me.anonName else cur.anonName
        persist(cur.copy(anonName = pending?.anonName ?: anon, me = me, pending = pending))
    }

    private suspend fun dropPending(p: PendingName) = mutex.withLock {
        val cur = loadLocked()
        if (cur.pending == p) persist(cur.copy(pending = null, anonName = cur.me?.anonName ?: cur.anonName))
    }

    private suspend fun persist(next: ProfileState) {
        _profile.value = next
        withContext(io) {
            try {
                file.write(ProfileFile(next.anonName, next.pending))
            } catch (e: Exception) {
                AppLog.e("profile", "write failed: ${e.message}")
            }
        }
    }
}
