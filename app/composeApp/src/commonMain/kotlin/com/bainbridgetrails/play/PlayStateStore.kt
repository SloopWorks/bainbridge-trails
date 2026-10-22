package com.bainbridgetrails.play

import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.play.claim.WalkDone
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.TrailLedger
import com.bainbridgetrails.play.schedule.PlayUserState
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Play's per-goal user state (`<privateDir>/play/<goal>-state.json`, [PlayFiles]): [found] occurrence / placement keys (claimed or
 * restored), [solved] riddle item id → island date of the armchair solve, [hints] riddle item id → hints used, and
 * the [logbook] — `:play`'s rules model ([Logbook] of `LogEntry`, product § 3.8), the one Logbook model: island dates
 * only, never a time of day. The opt-in starting balance is credited once ever (`Logbook.hasStartBalance`, PF1-2).
 * Kept when Play is turned off (product § 5.5: nothing is deleted); cleared by "Also delete … on this phone".
 */
@Serializable
data class PlayState(
    val schema: Int = 2,
    val found: Set<String> = emptySet(),
    val solved: Map<String, String> = emptyMap(),
    val hints: Map<String, Int> = emptyMap(),
    val logbook: Logbook = Logbook(),
    /** The walk-derived snapshot the Logbook was last brought up to (start balance, each finished walk); see [WalkMarks]. */
    val derivedBase: DerivedBase? = null,
    /**
     * Recent finished Play walks that completed counted, non-quiet features (branch review round 1 P0-1): the `earlier`
     * input of solo `completed` challenges (`ch-sweep`). Kept [WalkDone.KEEP_MS]; the walk id is opaque on disk.
     */
    val walks: List<WalkDone> = emptyList(),
    /**
     * Play trails v1.1 device ledger (plan D1): trails rule keys (`climb:<m>`, `mile:<mi>`, `high:<trailId>`,
     * `park1:<park>`, `parkc:<park>`, `island`) paid outside a per-key Logbook row — folded into a `start_trails`
     * entry, or seeded from the account's `paid` on a restore. Paid = this ∪ the trails-kind Logbook `contentId`s
     * ([com.bainbridgetrails.play.rules.TrailRewards.paid]). Keys only: no time, no place beyond a park name.
     */
    val trailPaid: Set<String> = emptySet(),
    /** The trails start ([com.bainbridgetrails.play.rules.TrailStart]) ran on this phone (plan D4). */
    val trailsStarted: Boolean = false,
) {
    /** The trails ledger view ([TrailLedger]) of this state. */
    fun trailLedger(): TrailLedger = TrailLedger(logbook, trailPaid, trailsStarted)

    /** This state with [ledger]'s Logbook, paid keys and start flag. */
    fun with(ledger: TrailLedger): PlayState = copy(logbook = ledger.logbook, trailPaid = ledger.trailPaid, trailsStarted = ledger.trailsStarted)

    /** The scheduler's view. A solve counts from the start of its island day. */
    fun userState(): PlayUserState = PlayUserState(
        resolved = solved.mapNotNull { (id, d) ->
            runCatching { id to IslandTime.instantOf(LocalDate.parse(d), LocalTime(0, 0)) }.getOrNull()
        }.toMap(),
        foundOcc = found,
    )
}

/** `<privateDir>/play/<goal>-walk-ids.json`: session id → opaque walk id of the Logbook's walks (never backed up). */
@Serializable
internal data class PlayWalkIds(val v: Int = 1, val bySession: Map<String, String> = emptyMap())

/**
 * Loads once (off Main) and writes atomically (tmp + move, like SettingsRepository) through [JsonFile]. An unreadable
 * file reads as an empty state (logged), never a crash.
 *
 * Privacy review 2026-10-21 P1-2: the file lives in the no-backup dir ([PlayFiles]); a file at [legacyPath] (the
 * backed-up app dir of earlier builds) is moved here once. With [walkIdsPath], `LogEntry.walkId` is stored as an opaque
 * per-walk id ([OPAQUE_PREFIX] + random): the local session id (`s<epochMs>-hex`, a walk's exact start) never reaches
 * this file. The session ↔ opaque map lives beside it, also no-backup, and holds only walks the Logbook still names;
 * in memory ([state]) entries carry the session id again, so the results sheet and Logbook links work unchanged. A
 * walk with no map entry (a restored or migrated file) stays opaque.
 */
@OptIn(ExperimentalUuidApi::class)
class PlayStateStore(
    path: String,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    walkIdsPath: String? = null,
    legacyPath: String? = null,
    private val newWalkId: () -> String = { OPAQUE_PREFIX + Uuid.random().toHexString() },
) {
    private val file = JsonFile(path, PlayState.serializer())
    private val walkIdsFile = walkIdsPath?.let { JsonFile(it, PlayWalkIds.serializer()) }
    private val legacy = legacyPath?.takeIf { it != path }?.let { JsonFile(it, PlayState.serializer()) }
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false

    /** Session id → opaque walk id (loaded with the state): only walks with a `pending` Logbook entry (P2-11). */
    private var walkIds: Map<String, String> = emptyMap()

    /** Walks pruned from the map in this process: the same opaque id on every later write (memory only). */
    private val retired = HashMap<String, String>()

    suspend fun load(): PlayState = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): PlayState {
        if (!loaded) {
            loaded = true
            withContext(io) {
                walkIds = walkIdsFile?.read()?.bySession.orEmpty()
                val stored = file.read()
                val old = if (stored == null) legacy?.read() else null
                when {
                    stored != null -> _state.value = decode(stored)
                    old != null -> {
                        // One-time move out of the backed-up dir; the old file goes once the new one is written.
                        _state.value = old
                        persist(old)
                        legacy?.delete()
                    }
                }
                if (stored != null) legacy?.delete()
            }
        }
        return _state.value
    }

    suspend fun update(change: (PlayState) -> PlayState): PlayState = mutex.withLock {
        val next = change(loadLocked())
        if (next != _state.value) {
            _state.value = next
            withContext(io) { persist(next) }
        }
        next
    }

    /**
     * Forgets everything on this phone ("Also delete … on this phone", product § 3.10): the in-memory state is reset
     * (so no later write brings the old Logbook back) and the state file, its walk-id map and any pre-move file are
     * deleted.
     */
    suspend fun clear() = mutex.withLock {
        loaded = true
        walkIds = emptyMap()
        retired.clear()
        _state.value = PlayState()
        withContext(io) {
            file.delete()
            walkIdsFile?.delete()
            legacy?.delete()
        }
    }

    /** Debug "simulate user state": forget finds, solves and hints (the Logbook and start-balance flag stay). */
    suspend fun clearFinds() = update { it.copy(found = emptySet(), solved = emptyMap(), hints = emptyMap()) }

    /**
     * Writes [s] with opaque walk ids (map first, so a crash between the two never leaves an unmapped id). The map keeps
     * only walks that still have a `pending` Logbook entry (branch review round 1 P2-11: the settle needs the session;
     * nothing else does), so a session id (a walk's start time) leaves the no-backup map once its walk is settled.
     */
    private fun persist(s: PlayState) {
        val ids = walkIdsFile ?: return file.write(s)
        val opaque = walkIds.values.toHashSet()
        val pendingWalks = s.logbook.entries.mapNotNullTo(HashSet()) { e -> e.walkId?.takeIf { e.board == BoardState.pending } }
        val keep = LinkedHashMap<String, String>()
        fun opaqueOf(w: String): String {
            if (w in opaque || w.startsWith(OPAQUE_PREFIX)) return w
            val id = keep[w] ?: walkIds[w] ?: retired[w] ?: newWalkId()
            if (w in pendingWalks) keep[w] = id else retired[w] = id
            return id
        }
        val mapped = s.mapWalkIds(::opaqueOf)
        if (keep != walkIds) {
            walkIds = keep
            if (keep.isEmpty()) ids.delete() else ids.write(PlayWalkIds(bySession = keep))
        }
        file.write(mapped)
    }

    private fun decode(s: PlayState): PlayState {
        if (walkIds.isEmpty()) return s
        val bySecret = walkIds.entries.associate { (session, opaque) -> opaque to session }
        return s.mapWalkIds { w -> bySecret[w] ?: w }
    }

    private fun PlayState.mapWalkIds(f: (String) -> String): PlayState =
        if (logbook.entries.none { it.walkId != null } && walks.isEmpty()) this
        else copy(
            logbook = Logbook(logbook.entries.map { e -> e.walkId?.let { e.copy(walkId = f(it)) } ?: e }),
            walks = walks.map { it.copy(walkId = f(it.walkId)) },
        )

    companion object {
        /** Opaque walk ids on disk start with this (never a session id, which starts with `s`). */
        const val OPAQUE_PREFIX = "w-"
    }
}

/**
 * Where Play's files live (privacy review 2026-10-21 P1-2). User state and its walk-id map: `<privateDir>/play`
 * (Android `noBackupFilesDir`, iOS Application Support/private excluded from backup). The pack and tide caches stay in
 * the app dir's `play/` (excluded from Android backup too; re-downloadable). Claim queues: per dataset profile beside
 * the sync queue.
 */
object PlayFiles {
    fun stateDir(privateDir: String): String = "$privateDir/play"

    fun statePath(privateDir: String, goal: Goal): String = "${stateDir(privateDir)}/${goal.name}-state.json"

    fun walkIdsPath(privateDir: String, goal: Goal): String = "${stateDir(privateDir)}/${goal.name}-walk-ids.json"

    /** The state file of earlier builds, in the backed-up app dir's `play/` ([legacyDir]); moved on first load. */
    fun legacyStatePath(legacyDir: String, goal: Goal): String = "$legacyDir/${goal.name}-state.json"

    fun claimsPath(privateDir: String, profile: DatasetProfile): String = "$privateDir/${profile.storeName("play-claims.json")}"

    fun store(privateDir: String, legacyDir: String, goal: Goal, io: CoroutineDispatcher = Dispatchers.Default): PlayStateStore =
        PlayStateStore(statePath(privateDir, goal), io, walkIdsPath(privateDir, goal), legacyStatePath(legacyDir, goal))

    /**
     * "Also delete … on this phone" (privacy review 2026-10-21 P0-1, product § 3.10): every goal's state (in memory
     * through [repo] when it exists, Play on or off), the claim queue of every dataset profile ([currentClaims] through
     * its lock), and this process's claim results. With no repository (Play never on in this process) the files go
     * directly.
     */
    suspend fun wipe(
        privateDir: String,
        legacyDir: String,
        repo: PlayRepository?,
        currentClaims: PlayClaimQueue?,
        io: CoroutineDispatcher = Dispatchers.Default,
    ) {
        for (goal in Goal.entries) {
            val store = repo?.store?.takeIf { repo.goal == goal } ?: store(privateDir, legacyDir, goal, io)
            store.clear()
        }
        for (profile in DatasetProfile.entries) {
            val path = claimsPath(privateDir, profile)
            val queue = listOfNotNull(currentClaims, repo?.claims).firstOrNull { it.path == path } ?: PlayClaimQueue(path, io)
            queue.clear()
        }
        repo?.forgetClaimResults()
    }
}
