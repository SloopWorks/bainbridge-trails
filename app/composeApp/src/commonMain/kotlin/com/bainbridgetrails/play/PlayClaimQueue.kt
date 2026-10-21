package com.bainbridgetrails.play

import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.JsonFile
import com.bainbridgetrails.net.PlayClaimDto
import com.bainbridgetrails.net.PlayWalkClaimsDto
import com.bainbridgetrails.play.claim.Claim
import com.bainbridgetrails.play.claim.ClaimKind
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogCopy
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
    /** The walk has walk-derived Logbook entries ([WalkMarks]): sent even with no claims, so the server derives them. */
    val walkMarks: Boolean = false,
    /** When it was queued (epoch ms, this phone only; 0 = an older file, stamped on the next write): retention. */
    val addedAt: Long = 0L,
)

@Serializable
internal data class PlayClaimFile(val v: Int = 1, val walks: List<QueuedPlay> = emptyList())

/**
 * What one upload decided for one queued walk: [accepted] claim ids ([capped] among them: accepted with 0 marks over the
 * server's weekly cap, sent `capped`, or a repeat whose stored marks are 0), [localOnly] claim id → code
 * (`unknown_item`, `stale_config`, `implausible`, [PlayClaimQueue.OVER_CAP], [PlayClaimQueue.TOO_MANY_CLAIMS] …), and
 * [day] for the walk's W-DAY (`dayKey`): accepted only when the server lists the walk in `dayAccepted`, else
 * `local_only`; null when it had none.
 */
data class PlaySettled(
    val sessionId: String,
    val accepted: Set<String> = emptySet(),
    val capped: Set<String> = emptySet(),
    val localOnly: Map<String, String> = emptyMap(),
    val day: BoardState? = null,
    /** The walk's walk-derived entries ([WalkMarks.SETTLED_WITH_WALK]): accepted once the upload is answered with `play`. */
    val walk: BoardState? = null,
)

/**
 * Play's claim queue (docs/play/plan.md L2-9): per-walk elements keyed by session id, in a file beside the sync queue
 * (atomic writes through [JsonFile]). [part] builds the upload's `playConfig` + `play[]` for the walks the upload
 * carries; the returned [PlayPart.settle] applies the outcome:
 * - an element sent with the upload: accepted / capped / rejected per the response (rejected → `local_only`), all
 *   `local_only` when the upload was refused (422, PF15-2) or the server sent no `play` answer;
 * - an element whose walk is still in sync's queue (retryable failure, or not credited yet): stays queued;
 * - an element whose walk left sync's queue without carrying it (dropped over the per-upload walk cap, plan RISKS 4):
 *   `local_only` ([OVER_CAP]);
 * - an element left out of an upload that carried its walk because the upload already held [MAX_CLAIMS] claims:
 *   `local_only` ([TOO_MANY_CLAIMS]; known limitation, PD-24: the walk is credited, so a later upload cannot carry it);
 * - an element whose session has no walk id yet: stays queued.
 * Settled elements leave the queue after `onSettled` has recorded them (so a crash between the two resends, which the
 * server treats as an idempotent repeat).
 *
 * Retention (privacy review 2026-10-21 P2-4): an element whose session still has no walk id [EXPIRE_MS] after it was
 * queued settles `local_only` ([EXPIRED]) at the next upload, and the queue never holds more than [MAX_QUEUED]
 * elements ([add] expires the oldest).
 */
class PlayClaimQueue(
    val path: String,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val file = JsonFile(path, PlayClaimFile.serializer())
    private val mutex = Mutex()

    /**
     * Queues [element]; the same session again merges (claims by id), so a re-run hook never duplicates a claim.
     * Returns the oldest elements expired over [MAX_QUEUED] (their entries turn `local_only`; usually empty).
     */
    suspend fun add(element: QueuedPlay): List<PlaySettled> = mutex.withLock {
        val f = read()
        val t = now()
        val i = f.walks.indexOfFirst { it.sessionId == element.sessionId }
        val walks = if (i < 0) {
            f.walks + element.copy(addedAt = t)
        } else {
            val old = f.walks[i]
            val ids = old.claims.mapTo(HashSet()) { it.id }
            val merged = old.copy(
                playConfig = element.playConfig,
                claims = old.claims + element.claims.filter { it.id !in ids },
                dayKey = old.dayKey ?: element.dayKey,
                hints = ((old.hints ?: emptyMap()) + (element.hints ?: emptyMap())).ifEmpty { null },
                walkMarks = old.walkMarks || element.walkMarks,
            )
            f.walks.toMutableList().also { it[i] = merged }
        }
        val over = (walks.size - MAX_QUEUED).coerceAtLeast(0)
        write(f.copy(walks = walks.drop(over).stamped(t)))
        walks.take(over).map { allLocalOnly(it, EXPIRED) }.onEach {
            AppLog.w(TAG, "queue over $MAX_QUEUED: an old element expired")
        }
    }

    private fun List<QueuedPlay>.stamped(t: Long) = map { if (it.addedAt == 0L) it.copy(addedAt = t) else it }

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
        val overClaims = HashSet<String>()
        var claims = 0
        for (e in queued) {
            val walkId = walkIdBySession[e.sessionId] ?: continue
            if (walkId !in walksInThisUpload || (e.claims.isEmpty() && e.dayKey == null && !e.walkMarks)) continue
            if (claims + e.claims.size > MAX_CLAIMS) {
                overClaims += e.sessionId
                continue
            }
            claims += e.claims.size
            included[e.sessionId] = PlayWalkClaimsDto(walkId, e.claims, e.dayKey, e.hints?.ifEmpty { null })
        }
        // One pack version per upload: the newest element's (the Worker keeps the last 8, product §3.5).
        val config = queued.lastOrNull { it.sessionId in included }?.playConfig ?: queued.last().playConfig
        return PlayPart(config, included.values.toList()) { outcome -> settle(walkIdBySession, included.keys, overClaims, outcome, onSettled) }
    }

    private suspend fun settle(
        walkIdBySession: Map<String, String>,
        included: Set<String>,
        overClaims: Set<String>,
        outcome: PlayOutcome,
        onSettled: suspend (List<PlaySettled>) -> Unit,
    ) = mutex.withLock {
        val f = read()
        val t = now()
        val out = ArrayList<PlaySettled>()
        val keep = ArrayList<QueuedPlay>()
        for (e in f.walks) {
            val walkId = walkIdBySession[e.sessionId]
            when {
                walkId == null && e.addedAt != 0L && t - e.addedAt > EXPIRE_MS -> out += allLocalOnly(e, EXPIRED)
                walkId == null -> keep += e
                e.sessionId in included -> out += resolve(e, walkId, outcome)
                walkId in outcome.stillPending -> keep += e
                // The upload carried the walk but not its element (claim limit): it can never ride a later upload.
                e.sessionId in overClaims && outcome is PlayOutcome.Answered -> out += allLocalOnly(e, TOO_MANY_CLAIMS)
                else -> out += allLocalOnly(e, OVER_CAP)
            }
        }
        if (out.isEmpty()) return@withLock
        onSettled(out)
        write(f.copy(walks = keep.stamped(t)))
        out.forEach { s ->
            if (s.localOnly.isNotEmpty()) AppLog.w(TAG, "${s.localOnly.size} claims local_only: ${s.localOnly.values.toSet()}")
        }
    }

    private fun resolve(e: QueuedPlay, walkId: String, outcome: PlayOutcome): PlaySettled = when (outcome) {
        is PlayOutcome.Refused -> allLocalOnly(e, outcome.code)
        is PlayOutcome.Answered -> {
            val r = outcome.result
            if (r == null) {
                allLocalOnly(e, NO_ANSWER)
            } else {
                val rejected = r.rejected.associate { it.id to it.code }
                // `capped` is accepted with 0 marks (weekly cap, a `capped` claim, or a repeat stored at 0: P1-9).
                val capped = r.capped.toSet() - rejected.keys
                val accepted = r.accepted.toSet() - rejected.keys + capped
                val ids = e.claims.map { it.id }
                PlaySettled(
                    sessionId = e.sessionId,
                    accepted = ids.filterTo(LinkedHashSet()) { it in accepted },
                    capped = ids.filterTo(LinkedHashSet()) { it in capped },
                    localOnly = ids.filter { it !in accepted }.associateWith { rejected[it] ?: NO_ANSWER },
                    // W-DAY only when the server says so (P1-3); an ignored dayKey (short walk, older server) is local.
                    day = e.dayKey?.let { if (walkId in r.dayAccepted) BoardState.accepted else BoardState.local_only },
                    walk = BoardState.accepted.takeIf { e.walkMarks },
                )
            }
        }
    }

    private fun allLocalOnly(e: QueuedPlay, code: String) = PlaySettled(
        sessionId = e.sessionId,
        localOnly = e.claims.associate { it.id to code },
        day = e.dayKey?.let { BoardState.local_only },
        walk = BoardState.local_only.takeIf { e.walkMarks },
    )

    private suspend fun read(): PlayClaimFile = withContext(io) { file.read() ?: PlayClaimFile() }

    private suspend fun write(f: PlayClaimFile) = withContext(io) { if (f.walks.isEmpty()) file.delete() else file.write(f) }

    companion object {
        private const val TAG = "play"

        /** Server limit (`backend/src/play/claims.ts` MAX_CLAIMS_PER_UPLOAD): more is a 400 for the whole upload. */
        const val MAX_CLAIMS = 50

        /** The walk left sync's queue without its Play element (per-upload walk cap). */
        const val OVER_CAP = "over_cap"

        /** The upload carried the walk but its element did not fit under [MAX_CLAIMS] (PD-24 limitation). */
        const val TOO_MANY_CLAIMS = "too_many_claims"

        /** A 200 without a `play` answer (an older server, or a claim it did not mention). */
        const val NO_ANSWER = "no_answer"

        /** Retention: the element's walk never got a walk id in time, or the queue was full. */
        const val EXPIRED = "expired"

        /** Most elements kept (one per finished Play walk not yet uploaded). */
        const val MAX_QUEUED = 500

        /** A walk-less element is kept this long (30 days). */
        const val EXPIRE_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * The pure steps between the claim rules and the queue (product §3.6.2–§3.6.3, §3.8): a walk's claims become
 * provisional (`pending`) Logbook entries + found keys + one queued element; an upload's outcome confirms, caps or
 * rejects (`local_only`) them. Marks shown are local until the server answers; the server derives its own (PD-3).
 */
object PlayClaims {
    /**
     * The wire element of one walk: claims as `{id, kind, weekId, band?, capped?}` (never marks or [Claim.at]);
     * [capped] = keys the daily found cap pays 0 ([cappedKeys]), sent `capped: true` so the server books 0 (PD-23).
     */
    fun element(
        sessionId: String,
        playConfig: String,
        claims: List<Claim>,
        dayKey: LocalDate? = null,
        hints: Map<String, Int>? = null,
        capped: Set<String> = emptySet(),
    ): QueuedPlay = QueuedPlay(
        sessionId = sessionId,
        playConfig = playConfig,
        claims = claims.map {
            PlayClaimDto(it.key, it.kind.name, it.weekId.toString(), if (it.kind == ClaimKind.ink) it.band else null, capped = if (it.key in capped) true else null)
        },
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
                LogEntry(newId(), c.kind.logKind, title(c.itemId), LogCopy.why(c.kind.logKind), date, c.marks, walkId = sessionId, contentId = c.key, band = c.band),
                foundPerDay,
            )
        }
        if (walkingDay != null && !lb.hasDay(walkingDay)) {
            lb = lb.add(LogEntry(newId(), LogKind.day, LogCopy.what(LogKind.day), LogCopy.why(LogKind.day), walkingDay, Marks.DAY, walkId = sessionId))
        }
        return state.copy(found = state.found + claims.map { it.key }, logbook = lb)
    }

    /**
     * The keys of [claims] that [record] would log `capped` (the daily found cap, PF5-7) on top of [state]: the claims
     * the element sends with `capped: true` (PD-23). Keys already in the Logbook are not new and never capped here.
     */
    fun cappedKeys(state: PlayState, sessionId: String, claims: List<Claim>, foundPerDay: Int): Set<String> {
        val before = state.logbook.entries.size
        var n = 0
        val after = record(state, sessionId, claims, null, foundPerDay, { it }, { "capped-probe-${n++}" }).logbook.entries
        val keys = claims.mapTo(HashSet()) { it.key }
        return after.drop(before).filter { it.capped && it.contentId in keys }.mapNotNullTo(LinkedHashSet()) { it.contentId }
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
                    e.kind in WalkMarks.SETTLED_WITH_WALK -> s.walk?.let { e.copy(board = it) } ?: e
                    e.contentId == null -> e
                    e.contentId in s.capped -> e.copy(board = BoardState.accepted, marks = 0, capped = true)
                    e.contentId in s.accepted -> e.copy(board = BoardState.accepted)
                    e.contentId in s.localOnly -> e.copy(board = BoardState.local_only)
                    else -> e
                }
            },
        )
    }

}
