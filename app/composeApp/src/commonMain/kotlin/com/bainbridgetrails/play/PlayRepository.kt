package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.PlayChallengesDto
import com.bainbridgetrails.net.PlayLogDto
import com.bainbridgetrails.play.claim.Claim
import com.bainbridgetrails.play.claim.ClaimEvaluator
import com.bainbridgetrails.play.claim.ClaimKind
import com.bainbridgetrails.play.claim.ClaimResult
import com.bainbridgetrails.play.claim.GatedFix
import com.bainbridgetrails.play.claim.GatedSummary
import com.bainbridgetrails.play.claim.WalkDone
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogCopy
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.play.rules.StartBalance
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.ActiveItem
import com.bainbridgetrails.play.schedule.DayPlan
import com.bainbridgetrails.play.schedule.DerivedGoal
import com.bainbridgetrails.play.schedule.Gate
import com.bainbridgetrails.play.schedule.PlaceIndex
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.play.schedule.PlayContext
import com.bainbridgetrails.play.schedule.PlayScheduler
import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.TideTable
import com.bainbridgetrails.sync.PlayPart
import com.bainbridgetrails.tracking.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Play while it is on (schedule.md § 5.3, product § 3.6.1, PD-1): the running pack ([content]), the scheduler over
 * the running goal's dataset, the memoized day plans, today's [agenda] (recomputed on a minute ticker, on a debug clock
 * or tide change, on a pack change and on user-state changes, so an island-day rollover rebuilds the plan), the user
 * state ([store]), live sensing ([PlaySensing] over the recording's fixes) and the Finalizer's [gatedHook].
 *
 * Claims (docs/play/plan.md L2-9, product § 3.6, § 3.10, PD-3, PD-12): [gatedHook] → the claim pass over the walk's
 * kept fixes ([ClaimEvaluator]) → `pending` Logbook entries + one element in [claims] → [claimPart] on the next coverage
 * upload → confirmed / capped / `local_only`. On every turn-on: [restore] from `GET /v1/play/me` when signed in (a
 * reinstall gets one "Restored from your account" entry), then the opt-in starting balance ([StartBalance], once ever).
 *
 * Constructed only through [PlayGate] (AppGraph `playOrNull`), the first time Play is on; [start] / [stop] follow the
 * toggle. Nothing here runs, observes or requests while stopped. All scheduler work runs on one background worker
 * (the scheduler is not thread-safe).
 */
@OptIn(ExperimentalUuidApi::class)
class PlayRepository(
    private val scope: CoroutineScope,
    val goal: Goal,
    /** Read per use, so a debug clock swapped in later applies. */
    private val clock: () -> PlayClock,
    val content: PlayContentRepository,
    private val places: suspend () -> PlaceIndex,
    private val derived: suspend () -> DerivedGoal?,
    val store: PlayStateStore,
    /** The recording's fixes (`RecordingController.track`): empty when not recording, restarts per session. */
    private val track: Flow<List<Fix>>,
    /** Live sensing; null = [TrackerSensingSink] over this repository's pack and day plan (production). */
    sink: SensingSink? = null,
    compute: CoroutineDispatcher = Dispatchers.Default,
    private val api: ApiClient? = null,
    private val tickMs: Long = 60_000L,
    /** The walks' Play elements waiting for their coverage upload; null = no claim pipeline (screens-only tests). */
    val claims: PlayClaimQueue? = null,
    /** Signed in now (restore needs the account). */
    private val signedIn: () -> Boolean = { false },
    /** W-COV + W-DONE + W-SQUARE of the current coverage snapshot (the opt-in starting balance); null = unknown. */
    private val walkDerived: suspend () -> WalkDerived? = { null },
    /** Quiet zones, quiet features and feature names for a finished walk's walk-derived entries ([WalkMarks]). */
    private val walkContext: suspend () -> WalkContext = { WalkContext() },
    /** The goal's derived table (map fog, kraken stretches); read once. */
    private val derivedSource: suspend () -> PlayDerived = { PlayDerived.Empty },
    private val newId: () -> String = { Uuid.random().toString() },
) {
    private val worker = compute.limitedParallelism(1)

    /** The live claim rules (rebuilt with the pack on the worker); read by [TrackerSensingSink] on the sensing collector. */
    @Volatile private var liveEvaluator: ClaimEvaluator? = null

    /** Finish's own instance (worker only: [ClaimEvaluator] is not thread-safe). */
    private var finishEvaluator: ClaimEvaluator? = null

    private val liveSink: SensingSink = sink ?: TrackerSensingSink(
        evaluator = { liveEvaluator },
        plan = { _today.value },
        found = { store.state.value.found },
        hints = { store.state.value.hints },
        offsetMs = { clock().offsetMs },
    )

    private val _agenda = MutableStateFlow<PlayAgenda?>(null)

    /** What is on now (schedule.md § 5.2): What's On, map layers, sheets. Null until the first computation. */
    val agenda: StateFlow<PlayAgenda?> = _agenda.asStateFlow()

    private val _today = MutableStateFlow<DayPlan?>(null)

    /** Today's island [DayPlan] for [goal]. */
    val today: StateFlow<DayPlan?> = _today.asStateFlow()

    /** The running pack and where it came from. */
    val config: StateFlow<PlayConfigState?> get() = content.state

    private val _sensed = MutableStateFlow<Set<String>>(emptySet())

    /** Keys sensed live during this process (provisional until Finish; never persisted here). */
    val sensed: StateFlow<Set<String>> = _sensed.asStateFlow()

    private val _finds = MutableSharedFlow<SensedFind>(extraBufferCapacity = 16)

    /** Live finds, for the Record surface's "Nearby" strip and find copy. */
    val finds: SharedFlow<SensedFind> = _finds.asSharedFlow()

    private val fxSpawners = mutableListOf<(FxSpec) -> Unit>()

    private var job: Job? = null

    /** True between [start] and [stop]. */
    val running: Boolean get() = job?.isActive == true

    /** This start's turn-on work (restore, then the start balance): the marks of a newly written start balance. */
    private var turnedOn: CompletableDeferred<Int?>? = null

    /** The start balance of [turnedOn] was handed to a caller (the snackbar shows once). */
    @Volatile private var startBalanceReported = false

    // --- scheduler memo (worker only) -------------------------------------------------------------------------------
    private var placeIndex: PlaceIndex? = null
    private var derivedGoal: DerivedGoal? = null
    private var derivedLoaded = false
    private var scheduler: PlayScheduler? = null
    private var schedConfig: PlayConfig? = null
    private var schedTides: TideTable? = null
    private var ferryFor: Pair<PlayConfig, FerryTimetable?>? = null
    private var tideKey: Pair<PlayTideOverride, Pair<Int, Int>>? = null
    private var tideTable: TideTable? = null

    /**
     * The Finalizer's `onGated` provider target (PD-12; wired in `RecordingGraph`): the gated, kept fixes of a finished
     * walk go to the claim pass ([claimWalk], in `:play`'s types). Does nothing while stopped or under `kill.all`.
     */
    val gatedHook: suspend (sessionId: String, kept: List<Fix>, summary: SessionSummary) -> Unit = { id, kept, summary ->
        if (running && content.state.value?.config?.kill?.all != true) {
            liveSink.onGated(id, kept, summary)
            // Play time (F1): one offset for the whole walk, so its fixes, day plans and Logbook date agree.
            val offset = clock().offsetMs
            val gated = summary.toGated(offset)
            claimWalk(id, kept.map { it.toGated(offset) }, gated)
            recordWalkMarks(id, kept.map { GeoPoint(lat = it.lat, lon = it.lon) }, summary.newlyCompleted, gated.endedAtMs)
        }
    }

    /**
     * The finished walk's walk-derived Logbook entries (W-COV, W-DONE, W-SQUARE) and remembrance visit stamps
     * ([WalkMarks]): `pending` until its coverage upload is answered (a queued element with `walkMarks` makes the
     * upload carry the walk even without claims, so the server derives the same marks, PD-3). True when it wrote any.
     */
    private suspend fun recordWalkMarks(sessionId: String, kept: List<GeoPoint>, newlyCompleted: List<String>, endedAtMs: Long): Boolean {
        val queue = claims ?: return false
        val version = content.load()?.config?.version ?: return false
        val after = try {
            walkDerived()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "walk marks unavailable: ${e.message}")
            null
        } ?: return false
        val ctx = walkContext()
        val date = IslandTime.dateOf(if (endedAtMs > 0) Instant.fromEpochMilliseconds(endedAtMs) else clock().now())
        val visited = WalkMarks.visitedZones(kept, ctx.quietZones)
        val completed = newlyCompleted.filter { it !in ctx.quietFeatures }
        fun next(st: PlayState) = WalkMarks.apply(st, goal, sessionId, date, after, completed, { ctx.names[it] ?: it }, visited, newId)
        val before = store.load()
        // Queue first (as the claim pass does): a crash before the Logbook write leaves the walk carried, not lost.
        val expired = if (WalkMarks.hasPending(next(before).logbook, sessionId)) queue.add(QueuedPlay(sessionId, version, walkMarks = true)) else emptyList()
        var wrote = false
        store.update { st -> next(st).let { n -> n.copy(logbook = PlayClaims.settle(n.logbook, expired)) }.also { wrote = it.logbook != st.logbook } }
        return wrote
    }

    private val _claimResults = MutableStateFlow<Map<String, ClaimResult>>(emptyMap())

    /** This process's claim passes by session id (claims, unclaimed reasons, stretch shares) for the results sheet. */
    val claimResults: StateFlow<Map<String, ClaimResult>> = _claimResults.asStateFlow()

    /** "Also delete … on this phone" ([PlayFiles.wipe]): forget this process's claim passes and live senses. */
    fun forgetClaimResults() {
        _claimResults.value = emptyMap()
        _sensed.value = emptySet()
    }

    /** Starts the ticker, the pack check and live sensing. Idempotent. */
    fun start() {
        if (running) return
        val done = CompletableDeferred<Int?>().also { turnedOn = it }
        startBalanceReported = false
        job = scope.launch {
            content.load()
            store.load()
            launch { done.complete(onTurnedOn()) }
            launch { content.refreshIfDue() }
            launch { PlaySensing(track, ::sensingTargets, liveSink, { clock().now() }, ::onFind).run() }
            launch {
                merge(content.state, clock().overrides, store.state).distinctUntilChanged().conflate().collect { refresh() }
            }
            launch {
                while (true) {
                    delay(tickMs)
                    refresh()
                }
            }
        }.also { j -> j.invokeOnCompletion { done.complete(null) } }
    }

    /**
     * The marks of the start balance this start credited ("Your walks so far", the `play_start_balance` snackbar), or
     * null: none was due (credited once ever, or restored), the input never became ready, Play is off, or it was
     * already reported (an off/on faster than the gate keeps the same start: no second snackbar).
     */
    suspend fun awaitStartBalance(): Int? {
        val d = turnedOn ?: return null
        val marks = d.await() ?: return null
        if (turnedOn !== d || startBalanceReported) return null
        startBalanceReported = true
        return marks
    }

    /** Stops everything [start] started (Play turned off). State and files are kept. */
    fun stop() {
        job?.cancel()
        job = null
        turnedOn = null
        _sensed.value = emptySet()
    }

    /**
     * App foreground with Play on: the hourly pack check, then a fresh agenda, then (signed in, at most every
     * [ACCOUNT_SYNC_MS]) the account summary: server-only credits into the Logbook (P0-2) and found keys.
     */
    suspend fun onForeground() {
        if (!running) return
        content.refreshIfDue()
        refresh()
        val last = lastAccountSync
        if (last == null || Clock.System.now() - last >= ACCOUNT_SYNC_MS.milliseconds) restore()
    }

    private var derivedMemo: PlayDerived? = null

    /** The goal's derived table ([PlayDerived]), parsed once (the map's fog and kraken geometry). */
    suspend fun derivedTable(): PlayDerived = withContext(worker) { derivedMemo ?: derivedSource().also { derivedMemo = it } }

    /** The running pack's ferry timetable, or null (none, or expired). */
    suspend fun ferryTimetable(): FerryTimetable? = withContext(worker) { content.load()?.config?.let(::ferry) }

    /** Recomputes [today] and [agenda] at the Play clock's now. Null when no pack is usable. */
    suspend fun refresh(): PlayAgenda? = withContext(worker) {
        val state = content.load() ?: return@withContext null
        val c = clock()
        val now = c.now()
        val date = IslandTime.dateOf(now)
        val sched = schedulerFor(state.config, tidesFor(date, c.overrides.value.tide))
        val user = store.state.value.userState()
        if (liveEvaluator?.config !== state.config) liveEvaluator = ClaimEvaluator(state.config, ensurePlaces())
        val agenda = sched.agenda(now, PlayContext(goal), user)
        _today.value = sched.dayPlan(date, goal)
        _agenda.value = agenda
        agenda
    }

    /**
     * What live sensing may sense now: active occurrences whose gate is OPEN, that are sensable and not yet found or
     * sensed. A killed item is not in the plan at all (PF16-1); a tide-gated stretch with no table is TIDE_UNKNOWN
     * (PF16-3); `kill.all` empties the plan.
     */
    fun sensingTargets(): List<ActiveItem> {
        val a = _agenda.value ?: return emptyList()
        if (content.state.value?.config?.kill?.all == true) return emptyList()
        val sensed = _sensed.value
        return a.active.filter { item ->
            val o = item.occurrence
            item.gate == Gate.OPEN && o.sensable && run {
                val done = item.found + sensed
                if (o.placements.isEmpty()) o.key !in done else o.placements.any { it.key !in done }
            }
        }
    }

    /**
     * Registers a map's FxHost spawner for live find moments (the gated MapFx call sites, L2-8); returns the detach
     * function. Called on Main.
     */
    fun attachFx(spawn: (FxSpec) -> Unit): () -> Unit {
        fxSpawners += spawn
        return { fxSpawners -= spawn }
    }

    private fun onFind(f: SensedFind) {
        _sensed.update { it + f.key }
        _finds.tryEmit(f)
        PlaySensing.findMoment(f)?.let { spec -> fxSpawners.toList().forEach { it(spec) } }
        AppLog.i(TAG, "sensed ${f.occurrence.type} (provisional)")
    }

    // --- server summaries (Play on only) -----------------------------------------------------------------------------

    /** `GET /v1/play/challenges`; null while stopped or without a client. */
    suspend fun challenges(): ApiResult<PlayChallengesDto>? = if (running) api?.playChallenges() else null

    // --- claims, restore, starting balance (L2-9) --------------------------------------------------------------------

    /**
     * The claim pass at Finish (product § 3.6.2): every item active during the walk is re-checked against the [kept]
     * fixes over the day plans of each island date the walk touched. Claims become `pending` Logbook entries (daily
     * found cap applied; capped finds are sent `capped: true`, PD-23), their keys are found, and one element is queued
     * for the walk's coverage upload, with the week id as `dayKey` when the walk earned W-DAY. Solo `completed`
     * challenges (`ch-sweep`, branch review round 1 P0-1) are counted over this walk's newly completed, non-quiet
     * features plus the earlier walks of [PlayState.walks]. Idempotent per (session, content): found keys never claim
     * again, and a re-run walk never counts itself as an earlier one.
     */
    suspend fun claimWalk(sessionId: String, kept: List<GatedFix>, summary: GatedSummary): ClaimResult? {
        val queue = claims ?: return null
        val pass = withContext(worker) {
            val state = content.load() ?: return@withContext null
            val config = state.config
            if (config.kill.all) return@withContext null
            val from = summary.startedAtMs.takeIf { it > 0 } ?: kept.minOfOrNull { it.timeMs } ?: return@withContext null
            val to = maxOf(from, summary.endedAtMs.takeIf { it > 0 } ?: kept.maxOfOrNull { it.timeMs } ?: from)
            val dates = ClaimEvaluator.islandDates(from, to).takeLast(MAX_WALK_DAYS)
            val user = store.load()
            val sched = schedulerFor(config, tidesFor(dates.first(), clock().overrides.value.tide))
            val plans = dates.map { sched.dayPlan(it, goal) }
            val ev = finishEvaluator?.takeIf { it.config === config } ?: ClaimEvaluator(config, ensurePlaces()).also { finishEvaluator = it }
            // Solo `completed` challenges (P0-1): this walk's completions minus quiet features, over the earlier walks.
            val quiet = walkContext().quietFeatures
            val completed = summary.newlyCompleted.distinct().count { it !in quiet }
            val walkEnd = Instant.fromEpochMilliseconds(to)
            val earlier = user.walks.filter { it.walkId != sessionId }
            val result = ev.evaluate(kept, plans, user.found, user.hints, _sensed.value, summary.droppedVehicle, walkEnd, completed, earlier)
            val endDate = IslandTime.dateOf(walkEnd)
            val day = endDate.takeIf { Marks.isWalkingDay(to - from, summary.matchedM) && !user.logbook.hasDay(it) }
            Pass(config, result, day, user.hints, WalkDone(sessionId, to, completed).takeIf { completed > 0 }, plans)
        } ?: return null
        val claimed = pass.result.claims
        if (claimed.isNotEmpty() || pass.day != null) {
            val hints = claimed.filter { it.kind == ClaimKind.riddle }.mapNotNull { c -> pass.hints[c.itemId]?.takeIf { it > 0 }?.let { c.itemId to it } }.toMap()
            val foundPerDay = pass.config.caps.foundPerDay
            // The daily found cap pays these 0 here; the server books them at 0 too (PD-23, P1-4).
            val capped = PlayClaims.cappedKeys(store.load(), sessionId, claimed, foundPerDay)
            // Queue first: a crash before the Logbook write re-runs the pass, which merges into the same element.
            val expired = queue.add(PlayClaims.element(sessionId, pass.config.version, claimed, pass.day?.let { IslandTime.weekId(it) }, hints, capped))
            val items = pass.config.items.associateBy { it.id }
            // F11: a treasure title is a template ("{tier} on {place}"); fill it from the placement before persisting.
            val placements = pass.plans.flatMap { p -> p.occurrences.flatMap { o -> o.placements } }.associateBy { it.key }
            val names = walkContext().names
            fun title(c: Claim): String {
                val template = items[c.itemId]?.copy?.title ?: return c.itemId
                if (!template.contains('{')) return template
                val pl = placements[c.key]
                return LogCopy.fillTitle(template, pl?.tier?.let { LogCopy.tier(it.name) }, pl?.featureId?.let { names[it] })
            }
            store.update { st ->
                val next = PlayClaims.record(st, sessionId, claimed, pass.day, foundPerDay, ::title, newId)
                next.copy(logbook = PlayClaims.settle(next.logbook, expired), walks = withWalk(next.walks, pass.walk))
            }
            AppLog.i(TAG, "walk claimed ${claimed.size} items${if (pass.day != null) " + walking day" else ""} (pending)")
        } else if (pass.walk != null) {
            store.update { st -> st.copy(walks = withWalk(st.walks, pass.walk)) }
        }
        _claimResults.update { it + (sessionId to pass.result) }
        return pass.result
    }

    private class Pass(val config: PlayConfig, val result: ClaimResult, val day: LocalDate?, val hints: Map<String, Int>, val walk: WalkDone?, val plans: List<DayPlan> = emptyList())

    /** [walks] with [walk] (replacing the same walk's record), minus records older than [WalkDone.KEEP_MS] before it. */
    private fun withWalk(walks: List<WalkDone>, walk: WalkDone?): List<WalkDone> {
        if (walk == null) return walks
        return (walks.filter { it.walkId != walk.walkId && walk.endedAt - it.endedAt <= WalkDone.KEEP_MS } + walk).sortedBy { it.endedAt }
    }

    /**
     * This upload's Play part (SyncRepository `playPart`): the queued elements of the walks it carries; the outcome
     * confirms, caps or turns `local_only` their Logbook entries.
     */
    suspend fun claimPart(walkIdBySession: Map<String, String>, walksInThisUpload: Set<String>): PlayPart? =
        claims?.part(walkIdBySession, walksInThisUpload) { settled ->
            store.update { it.copy(logbook = PlayClaims.settle(it.logbook, settled)) }
        }

    /**
     * Logbook restore (product § 3.6.5, PF15-7), on turn-on and on sign-in while on: `GET /v1/play/me` re-marks every
     * claimed occurrence key found and, when this phone's Logbook holds nothing but (at most) the starting balance —
     * a reinstall, a new device, or an opt-in made while signed out — writes one "Restored from your account" entry
     * that brings the Logbook total to the server total. Never twice. True when it wrote the entry.
     */
    suspend fun restore(): Boolean {
        if (!running || !signedIn()) return false
        val client = api ?: return false
        val me = (client.playMe() as? ApiResult.Ok)?.value ?: return false
        lastAccountSync = Clock.System.now()
        val today = IslandTime.dateOf(clock().now())
        var wrote = false
        store.update { st ->
            val lb = st.logbook
            val restorable = !lb.hasRestore && lb.entries.all { it.kind == LogKind.start_balance }
            // Server-only credits first (P0-2): a restore then owes only what the account holds beyond them.
            val credited = serverCredits(lb, me.log)
            val owed = me.total - credited.total
            wrote = restorable && owed > 0
            st.copy(
                found = st.found + me.occ,
                logbook = if (!wrote) credited else credited.add(LogEntry(newId(), LogKind.restored, LogCopy.what(LogKind.restored), LogCopy.why(LogKind.restored), today, owed, board = BoardState.accepted)),
            )
        }
        if (wrote) AppLog.i(TAG, "Logbook restored from the account")
        return wrote
    }

    /** When the account summary was last applied ([restore]); the foreground re-fetch waits [ACCOUNT_SYNC_MS]. */
    @Volatile private var lastAccountSync: Instant? = null

    /**
     * Branch review round 1 P0-2: the server-only credits of closed weeks (W-WEEK, the kraken's retreat, the island
     * challenge; `GET /v1/play/me` `log`) as `accepted` entries keyed `"<kind>@<week>"` (idempotent: a row already in
     * the Logbook adds nothing), logged on the week's Sunday (product § 3.8 "week close entries … on Sunday").
     */
    private fun serverCredits(lb: Logbook, log: List<PlayLogDto>): Logbook {
        var out = lb
        for (row in log) {
            val kind = SERVER_KINDS[row.kind] ?: continue
            val week = runCatching { LocalDate.parse(row.week) }.getOrNull() ?: continue
            if (row.marks < 0) continue
            out = out.add(LogEntry(newId(), kind, LogCopy.what(kind), LogCopy.why(kind), IslandTime.weekId(week).plus(6, DateTimeUnit.DAY), row.marks, contentId = "${row.kind}@${row.week}", board = BoardState.accepted))
        }
        return out
    }

    private class Credit(val marks: Int?)

    /**
     * The opt-in starting balance (product § 3.6.4, PF1-2), one attempt: "Your walks so far" once ever, from the current
     * snapshot; later turn-ons add only walk-derived marks earned while Play was off; nothing after a restore
     * ([StartBalance]). Idempotent. [Credit.marks] = a start balance entry this call wrote; null = the walk-derived
     * input is not available (yet).
     */
    private suspend fun creditOnce(): Credit? {
        val derived = try {
            walkDerived()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "start balance unavailable: ${e.message}")
            null
        } ?: return null
        val today = IslandTime.dateOf(clock().now())
        var credited: Int? = null
        store.update { st ->
            val after = StartBalance.onEnable(st.logbook, derived, today, newId)
            credited = after.entries.drop(st.logbook.entries.size).firstOrNull { it.kind == LogKind.start_balance }?.marks
            st.copy(logbook = after, derivedBase = DerivedBase.of(derived))
        }
        return Credit(credited)
    }

    /**
     * Every turn-on: restore first (so a reinstall never gets a start balance on top), then the starting balance. While
     * the Logbook still has none (PF1-1), an unavailable input is retried a few times on this start (the dataset or the
     * coverage snapshot can still be loading right after opt-in); the next start tries again.
     */
    private suspend fun onTurnedOn(): Int? {
        restore()
        var attempt = 0
        while (true) {
            creditOnce()?.let { return it.marks }
            val lb = store.state.value.logbook
            if (lb.hasStartBalance || lb.hasRestore || attempt >= START_BALANCE_RETRY_MS.size) return null
            delay(START_BALANCE_RETRY_MS[attempt++])
        }
    }

    // --- debug drawer --------------------------------------------------------------------------------------------------

    /** Today's plan plus the next [days] days' occurrences, as text (debug "agenda dump"). */
    suspend fun agendaDump(days: Int = 14): String = withContext(worker) {
        val state = content.load() ?: return@withContext "no pack"
        val c = clock()
        val now = c.now()
        val date = IslandTime.dateOf(now)
        val sched = schedulerFor(state.config, tidesFor(date, c.overrides.value.tide))
        val agenda = sched.agenda(now, PlayContext(goal), store.state.value.userState())
        buildString {
            appendLine("pack ${state.config.version} (${state.source.name.lowercase()}) · $goal · ${DebugPlayClock.describe(now)}")
            appendLine("tide ${c.overrides.value.tide.name.lowercase()} · quiet day ${agenda.day.quietDay}")
            appendLine("ACTIVE")
            for (a in agenda.active) appendLine("  ${a.occurrence.key}  ${a.gate}  placements=${a.occurrence.placements.size}  found=${a.found.size}")
            for (i in 0..days) {
                val d = date.plus(i, DateTimeUnit.DAY)
                val plan = sched.dayPlan(d, goal)
                appendLine("$d ${d.dayOfWeek.name.take(3)}${if (plan.quietDay) " QUIET" else ""}")
                for (o in plan.occurrences) appendLine("  ${o.key}  ${o.type}  ${o.start} → ${o.end ?: "∞"}  p=${o.placements.size}")
            }
        }
    }

    /** schedule.md § 5.3 on-device timing: 365 day plans (fresh scheduler) and 10,000 agendas. */
    suspend fun benchmark(): String = withContext(worker) {
        val state = content.load() ?: return@withContext "no pack"
        val c = clock()
        val now = c.now()
        val date = IslandTime.dateOf(now)
        val tides = tidesFor(date, c.overrides.value.tide)
        val fresh = PlayScheduler(state.config, ensurePlaces(), tides, ferry(state.config), derivedMap())
        val t0 = Clock.System.now()
        for (i in 0 until 365) fresh.dayPlan(date.plus(i, DateTimeUnit.DAY), goal)
        val t1 = Clock.System.now()
        val user = store.state.value.userState()
        repeat(10_000) { fresh.agenda(now, PlayContext(goal), user) }
        val t2 = Clock.System.now()
        "365 day plans: ${(t1 - t0).inWholeMilliseconds} ms · 10,000 agendas: ${(t2 - t1).inWholeMilliseconds} ms"
            .also { AppLog.i(TAG, "benchmark $it") }
    }

    // --- internals (worker) --------------------------------------------------------------------------------------------

    private suspend fun ensurePlaces(): PlaceIndex = placeIndex ?: places().also { placeIndex = it }

    private suspend fun derivedMap(): Map<Goal, DerivedGoal> {
        if (!derivedLoaded) {
            derivedLoaded = true
            derivedGoal = runCatching { derived() }.getOrNull()
        }
        return derivedGoal?.let { mapOf(goal to it) }.orEmpty()
    }

    private suspend fun schedulerFor(config: PlayConfig, tides: TideTable?): PlayScheduler {
        val s = scheduler
        if (s != null && schedConfig === config && schedTides === tides) return s
        return PlayScheduler(config, ensurePlaces(), tides, ferry(config), derivedMap()).also {
            scheduler = it
            schedConfig = config
            schedTides = tides
        }
    }

    private suspend fun tidesFor(date: LocalDate, override: PlayTideOverride): TideTable? {
        if (override == PlayTideOverride.EXPIRED) return null
        val span = date.year to date.plus(60, DateTimeUnit.DAY).year
        val key = override to span
        if (tideKey == key) return tideTable
        val table = when (override) {
            PlayTideOverride.LOW -> syntheticTides(span.first - 1..span.second + 1, heightCm = 50)
            PlayTideOverride.HIGH -> syntheticTides(span.first - 1..span.second + 1, heightCm = 400)
            else -> content.tides(date)
        }
        tideKey = key
        tideTable = table
        return table
    }

    private fun ferry(config: PlayConfig): FerryTimetable? {
        ferryFor?.let { (c, f) -> if (c === config) return f }
        val f = FerryTimetable.of(config.ferry)
        ferryFor = config to f
        return f
    }

    companion object {
        private const val TAG = "play"

        /** A walk is claimed over at most this many island days (a recording left running for days stays bounded). */
        private const val MAX_WALK_DAYS = 3

        /** Start-balance retries on one start while its input is unavailable (PF1-1). */
        private val START_BALANCE_RETRY_MS = longArrayOf(2_000L, 5_000L, 15_000L)

        /** The foreground account re-fetch's minimum interval (P0-2). */
        const val ACCOUNT_SYNC_MS = 10 * 60_000L

        /** `GET /v1/play/me` `log` kinds the Logbook mirrors (server-only credits, P0-2). */
        private val SERVER_KINDS = mapOf("week" to LogKind.week, "retreat" to LogKind.retreat, "island_challenge" to LogKind.island_challenge)

        /**
         * A debug tide table at a constant [heightCm] (events every 6 h) over [years]: LOW (0.5 m) opens and HIGH
         * (4 m) shuts the kraken's gate at every instant (`-btPlayTide`, drawer).
         */
        internal fun syntheticTides(years: IntRange, heightCm: Int): TideTable {
            val texts = years.map { y ->
                val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
                val n = (if (leap) 366 else 365) * 4
                val events = (0 until n).joinToString(",") { i -> "[${if (i == 0) 0 else 360},$heightCm,\"${if (i % 2 == 0) "L" else "H"}\"]" }
                """{"station":"${PlayContentRepository.STATION}","datum":"MLLW","units":"cm","year":$y,"t0":"$y-01-01T00:00Z","source":"debug-override","e":[$events]}"""
            }
            return TideTable.parse(*texts.toTypedArray())
        }
    }
}

/** What [WalkMarks] needs beyond the snapshot: quiet zone outlines, quiet feature ids, feature display names. */
class WalkContext(
    val quietZones: List<QuietZoneOutline> = emptyList(),
    val quietFeatures: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
)

/**
 * The Play gate for the app graph (PD-1): [current] is null while Play is off; the repository is created the first
 * time [enabled] is true (never while off), started while on and stopped when turned off (kept for the next on).
 */
class PlayGate(
    private val scope: CoroutineScope,
    private val enabled: Flow<com.bainbridgetrails.data.PlaySettings?>,
    private val create: () -> PlayRepository,
) {
    // Written on the gate's collector, read from any dispatcher (Finalizer, sync, UI): P2-12.
    @Volatile private var repo: PlayRepository? = null
    @Volatile private var on = false
    private var job: Job? = null

    /** Completes once the first loaded settings were applied (Play built and started when on). */
    private val settled = CompletableDeferred<Unit>()

    /**
     * Waits (at most [timeoutMs]) until the loaded settings were applied, so launch-time finalization sees the running
     * repository's hook when Play is on (branch review round 1 P1-6). Returns at once once settled.
     */
    suspend fun awaitSettled(timeoutMs: Long = 10_000L) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { settled.await() }
    }

    /** The running repository, or null while Play is off. */
    val current: PlayRepository? get() = repo.takeIf { on }

    /** The repository if one was ever created in this process (Play on or off): the phone wipe clears its state. */
    val created: PlayRepository? get() = repo

    fun start() {
        if (job != null) return
        job = scope.launch {
            enabled.collect { s ->
                val next = s?.enabled == true
                if (next != on) {
                    if (next) {
                        (repo ?: create().also { repo = it }).start()
                    } else {
                        repo?.stop()
                    }
                    on = next
                }
                if (s != null) settled.complete(Unit)
            }
        }
    }
}

/**
 * Walk-derived marks of a whole coverage snapshot (product § 3.2, § 3.6.4; mirrors `backend/src/play/marks.ts`
 * `walkDerivedMarks`): W-COV over counted, non-quiet metres (each segment clamped to its length), W-DONE per completed
 * counted, non-quiet feature (coverage or sticky, TL-7), W-SQUARE per chart square of a covered counted, non-quiet
 * segment. [derived] is the goal's derived table (`segmentSquares` = the counted segments, `quietSegments`,
 * `quietFeatures`).
 */
object SnapshotMarks {
    fun of(
        goal: Goal,
        dataset: com.bainbridgetrails.coverage.Dataset,
        index: com.bainbridgetrails.coverage.SegmentIndex,
        state: com.bainbridgetrails.coverage.CoverageState,
        sticky: Set<String>,
        derived: PlayDerived,
    ): WalkDerived {
        val quietFeatures = derived.quietFeatures
        var metres = 0.0
        val squares = HashSet<String>()
        for ((id, sq) in derived.squaresBySegment) {
            if (id in derived.quietSegments) continue
            val len = index.segments[id]?.lengthM ?: continue
            val c = state.coveredM(index, id).coerceIn(0.0, len)
            if (c <= 0.0) continue
            metres += c
            squares.addAll(sq)
        }
        val progress = com.bainbridgetrails.coverage.Progress(dataset, index, com.bainbridgetrails.coverage.CoverageParams())
        val completed = progress.snapshot(state, sticky).trails
            .count { it.status == com.bainbridgetrails.coverage.TrailStatus.COMPLETE && progress.counts(it.trailId) && it.trailId !in quietFeatures }
        return Marks.walkDerived(goal, metres, completed, squares.size)
    }
}
