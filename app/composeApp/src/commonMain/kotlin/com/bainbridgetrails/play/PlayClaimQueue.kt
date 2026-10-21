package com.bainbridgetrails.play

import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.net.PlayClaimDto
import com.bainbridgetrails.net.PlayWalkClaimsDto
import com.bainbridgetrails.play.claim.Claim
import com.bainbridgetrails.play.claim.ClaimKind
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.sync.PlayOutcome
import com.bainbridgetrails.sync.PlayPart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * One finished walk's Play element waiting for its coverage upload (product §3.10, PD-3), keyed by the local
 * [sessionId] (never sent: the sync layer maps it to the walk's random id). [claims] carry only `{id, kind, weekId,
 * band?}`; [dayKey] is the week id when the walk earned W-DAY; [hints] riddle item id → hints used. [playConfig] is the
 * pack version the claims were evaluated against. No marks, no times, no days, no coordinates (PF15-4).
 */
@Serializable
data class QueuedPlay(
    val sessionId: String,
    val playConfig: String,
    val claims: List<PlayClaimDto> = emptyList(),
    val dayKey: String? = null,
    val hints: Map<String, Int>? = null,
)

@Serializable
internal data class PlayClaimFile(val v: Int = 1, val walks: List<QueuedPlay> = emptyList())

/**
 * What one upload decided for one queued walk: [accepted] claim ids ([capped] among them: accepted with 0 marks over the
 * server's weekly cap), [localOnly] claim id → code (`unknown_item`, `stale_config`, `implausible`, [PlayClaimQueue.OVER_CAP]
 * …), and [day] for the walk's W-DAY (`dayKey`), null when it had none.
 */
data class PlaySettled(
    val sessionId: String,
    val accepted: Set<String> = emptySet(),
    val capped: Set<String> = emptySet(),
    val localOnly: Map<String, String> = emptyMap(),
    val day: BoardState? = null,
)

/**
 * Play's claim queue (docs/play/plan.md L2-9): per-walk elements keyed by session id, in a file beside the sync queue
 * (atomic writes through [JsonFile]). [part] builds the upload's `playConfig` + `play[]` for the walks the upload
 * carries; the returned [PlayPart.settle] applies the outcome:
 * - an element sent with the upload: accepted / capped / rejected per the response (rejected → `local_only`), all
 *   `local_only` when the upload was refused (422, PF15-2) or the server sent no `play` answer;
 * - an element whose walk is still in sync's queue (retryable failure, or not credited yet): stays queued;
 * - an element whose walk left sync's queue without carrying it (dropped over the per-upload walk cap, plan RISKS 4, or
 *   over the server's 50-claims-per-upload limit): `local_only` ([OVER_CAP]);
 * - an element whose session has no walk id yet: stays queued.
 * Settled elements leave the queue after `onSettled` has recorded them (so a crash between the two resends, which the
 * server treats as an idempotent repeat).
 */
class PlayClaimQueue(path: String, private val io: CoroutineDispatcher = Dispatchers.Default) {
    private val file = JsonFile(path, PlayClaimFile.serializer())
    private val mutex = Mutex()

    /** Queues [element]; the same session again merges (claims by id), so a re-run hook never duplicates a claim. */
    suspend fun add(element: QueuedPlay) = mutex.withLock {
        val f = read()
        val i = f.walks.indexOfFirst { it.sessionId == element.sessionId }
        val walks = if (i < 0) {
            f.walks + element
        } else {
            val old = f.walks[i]
            val ids = old.claims.mapTo(HashSet()) { it.id }
            val merged = old.copy(
                playConfig = element.playConfig,
                claims = old.claims + element.claims.filter { it.id !in ids },
                dayKey = old.dayKey ?: element.dayKey,
                hints = ((old.hints ?: emptyMap()) + (element.hints ?: emptyMap())).ifEmpty { null },
            )
            f.walks.toMutableList().also { it[i] = merged }
        }
        write(f.copy(walks = walks))
    }

    /** Everything still queued, oldest first. */
    suspend fun queued(): List<QueuedPlay> = mutex.withLock { read().walks }

    /** Forgets every queued element (account deleted with "also clear this phone"). */
    suspend fun clear() = mutex.withLock { withContext(io) { file.delete() } }

    /**
     * The Play part of one upload (read only; nothing changes until [PlayPart.settle]): elements whose walk id
     * ([walkIdBySession]) is in [walksInThisUpload], at most [MAX_CLAIMS] claims in all. Null when nothing is queued.
     * [onSettled] gets the outcome before the settled elements leave the queue.
     */
    suspend fun part(
        walkIdBySession: Map<String, String>,
        walksInThisUpload: Set<String>,
        onSettled: suspend (List<PlaySettled>) -> Unit = {},
    ): PlayPart? {
        val queued = queued()
        if (queued.isEmpty()) return null
        val included = LinkedHashMap<String, PlayWalkClaimsDto>()
        var claims = 0
        for (e in queued) {
            val walkId = walkIdBySession[e.sessionId] ?: continue
            if (walkId !in walksInThisUpload || (e.claims.isEmpty() && e.dayKey == null)) continue
            if (claims + e.claims.size > MAX_CLAIMS) continue
            claims += e.claims.size
            included[e.sessionId] = PlayWalkClaimsDto(walkId, e.claims, e.dayKey, e.hints?.ifEmpty { null })
        }
        // One pack version per upload: the newest element's (the Worker keeps the last 8, product §3.5).
        val config = queued.lastOrNull { it.sessionId in included }?.playConfig ?: queued.last().playConfig
        return PlayPart(config, included.values.toList()) { outcome -> settle(walkIdBySession, included.keys, outcome, onSettled) }
    }

    private suspend fun settle(
        walkIdBySession: Map<String, String>,
        included: Set<String>,
        outcome: PlayOutcome,
        onSettled: suspend (List<PlaySettled>) -> Unit,
    ) = mutex.withLock {
        val f = read()
        val out = ArrayList<PlaySettled>()
        val keep = ArrayList<QueuedPlay>()
        for (e in f.walks) {
            val walkId = walkIdBySession[e.sessionId]
            when {
                walkId == null -> keep += e
                e.sessionId in included -> out += resolve(e, outcome)
                walkId in outcome.stillPending -> keep += e
                else -> out += allLocalOnly(e, OVER_CAP)
            }
        }
        if (out.isEmpty()) return@withLock
        onSettled(out)
        write(f.copy(walks = keep))
        out.forEach { s ->
            if (s.localOnly.isNotEmpty()) AppLog.w(TAG, "${s.localOnly.size} claims local_only: ${s.localOnly.values.toSet()}")
        }
    }

    private fun resolve(e: QueuedPlay, outcome: PlayOutcome): PlaySettled = when (outcome) {
        is PlayOutcome.Refused -> allLocalOnly(e, outcome.code)
        is PlayOutcome.Answered -> {
            val r = outcome.result
            if (r == null) {
                allLocalOnly(e, NO_ANSWER)
            } else {
                val accepted = r.accepted.toSet()
                val rejected = r.rejected.associate { it.id to it.code }
                val ids = e.claims.map { it.id }
                PlaySettled(
                    sessionId = e.sessionId,
                    accepted = ids.filterTo(LinkedHashSet()) { it in accepted && it !in rejected },
                    capped = ids.filterTo(LinkedHashSet()) { it in r.capped },
                    localOnly = ids.filter { it in rejected || it !in accepted }.associateWith { rejected[it] ?: NO_ANSWER },
                    day = e.dayKey?.let { BoardState.accepted },
                )
            }
        }
    }

    private fun allLocalOnly(e: QueuedPlay, code: String) = PlaySettled(
        sessionId = e.sessionId,
        localOnly = e.claims.associate { it.id to code },
        day = e.dayKey?.let { BoardState.local_only },
    )

    private suspend fun read(): PlayClaimFile = withContext(io) { file.read() ?: PlayClaimFile() }

    private suspend fun write(f: PlayClaimFile) = withContext(io) { if (f.walks.isEmpty()) file.delete() else file.write(f) }

    companion object {
        private const val TAG = "play"

        /** Server limit (`backend/src/play/claims.ts` MAX_CLAIMS_PER_UPLOAD): more is a 400 for the whole upload. */
        const val MAX_CLAIMS = 50

        /** The walk left sync's queue without its Play element (per-upload walk cap or claim limit). */
        const val OVER_CAP = "over_cap"

        /** A 200 without a `play` answer (an older server, or a claim it did not mention). */
        const val NO_ANSWER = "no_answer"
    }
}

/**
 * The pure steps between the claim rules and the queue (product §3.6.2–§3.6.3, §3.8): a walk's claims become
 * provisional (`pending`) Logbook entries + found keys + one queued element; an upload's outcome confirms, caps or
 * rejects (`local_only`) them. Marks shown are local until the server answers; the server derives its own (PD-3).
 */
object PlayClaims {
    /** The wire element of one walk: claims as `{id, kind, weekId, band?}` (never marks or [Claim.at]). */
    fun element(
        sessionId: String,
        playConfig: String,
        claims: List<Claim>,
        dayKey: LocalDate? = null,
        hints: Map<String, Int>? = null,
    ): QueuedPlay = QueuedPlay(
        sessionId = sessionId,
        playConfig = playConfig,
        claims = claims.map { PlayClaimDto(it.key, it.kind.name, it.weekId.toString(), if (it.kind == ClaimKind.ink) it.band else null) },
        dayKey = dayKey?.toString(),
        hints = hints?.ifEmpty { null },
    )

    /**
     * Adds [claims] of walk [sessionId] to [state] as `pending` entries (daily found cap [foundPerDay] applied, PF5-7)
     * and their keys to `found`; [walkingDay] (the walk's island date when it earned W-DAY) adds the day entry.
     * Idempotent: keys already in the Logbook add nothing.
     */
    fun record(
        state: PlayState,
        sessionId: String,
        claims: List<Claim>,
        walkingDay: LocalDate?,
        foundPerDay: Int,
        title: (itemId: String) -> String,
        newId: () -> String,
    ): PlayState {
        var lb = state.logbook
        for (c in claims) {
            val date = com.bainbridgetrails.play.time.IslandTime.dateOf(c.at)
            lb = lb.addFind(
                LogEntry(newId(), c.kind.logKind, title(c.itemId), why(c.kind), date, c.marks, walkId = sessionId, contentId = c.key, band = c.band),
                foundPerDay,
            )
        }
        if (walkingDay != null && !lb.hasDay(walkingDay)) {
            lb = lb.add(LogEntry(newId(), LogKind.day, DAY_WHAT, DAY_WHY, walkingDay, Marks.DAY, walkId = sessionId))
        }
        return state.copy(found = state.found + claims.map { it.key }, logbook = lb)
    }

    /** Applies upload outcomes to the walk's `pending` entries (never touches other walks or non-pending entries). */
    fun settle(logbook: Logbook, settled: List<PlaySettled>): Logbook {
        if (settled.isEmpty()) return logbook
        val bySession = settled.associateBy { it.sessionId }
        return Logbook(
            logbook.entries.map { e ->
                val s = bySession[e.walkId] ?: return@map e
                if (e.board != BoardState.pending) return@map e
                when {
                    e.kind == LogKind.day -> s.day?.let { e.copy(board = it) } ?: e
                    e.contentId == null -> e
                    e.contentId in s.capped -> e.copy(board = BoardState.accepted, marks = 0, capped = true)
                    e.contentId in s.accepted -> e.copy(board = BoardState.accepted)
                    e.contentId in s.localOnly -> e.copy(board = BoardState.local_only)
                    else -> e
                }
            },
        )
    }

    const val DAY_WHAT = "Walking day"
    const val DAY_WHY = "First walk of the day, 10 minutes or more"

    private fun why(kind: ClaimKind): String = when (kind) {
        ClaimKind.treasure -> "Weekly treasure"
        ClaimKind.egg -> "Hidden find"
        ClaimKind.riddle -> "Riddle walked to"
        ClaimKind.ink -> "Kraken stretch repelled"
        ClaimKind.lore -> "Island story"
        ClaimKind.challenge -> "Challenge complete"
    }
}
