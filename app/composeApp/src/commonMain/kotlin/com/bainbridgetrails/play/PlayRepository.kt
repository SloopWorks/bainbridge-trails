package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.log.AppLog
import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.ApiResult
import com.bainbridgetrails.net.PlayChallengesDto
import com.bainbridgetrails.net.PlayMeDto
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.fx.FxSpec
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
import com.bainbridgetrails.tracking.SessionSummary
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Play while it is on (schedule.md § 5.3, product § 3.6.1, PD-1): the running pack ([content]), the scheduler over
 * the running goal's dataset, the memoized day plans, today's [agenda] (recomputed on a minute ticker, on a debug clock
 * or tide change, on a pack change and on user-state changes, so an island-day rollover rebuilds the plan), the user
 * state ([store]), live sensing ([PlaySensing] over the recording's fixes) and the Finalizer's [gatedHook].
 *
 * Constructed only through [PlayGate] (AppGraph `playOrNull`), the first time Play is on; [start] / [stop] follow the
 * toggle. Nothing here runs, observes or requests while stopped. All scheduler work runs on one background worker
 * (the scheduler is not thread-safe).
 */
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
    private val sink: SensingSink = SensingSink.None,
    compute: CoroutineDispatcher = Dispatchers.Default,
    private val api: ApiClient? = null,
    private val tickMs: Long = 60_000L,
) {
    private val worker = compute.limitedParallelism(1)

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
     * The Finalizer's `onGated` provider target (plan.md L2-3; wired in `RecordingGraph` by L2-9): the gated, kept
     * fixes of a finished walk go to the claim logic. Does nothing while stopped or under `kill.all`.
     */
    val gatedHook: suspend (sessionId: String, kept: List<Fix>, summary: SessionSummary) -> Unit = { id, kept, summary ->
        if (running && content.state.value?.config?.kill?.all != true) sink.onGated(id, kept, summary)
    }

    /** Starts the ticker, the pack check and live sensing. Idempotent. */
    fun start() {
        if (running) return
        job = scope.launch {
            content.load()
            store.load()
            launch { content.refreshIfDue() }
            launch { PlaySensing(track, ::sensingTargets, sink, { clock().now() }, ::onFind).run() }
            launch {
                merge(content.state, clock().overrides, store.state).distinctUntilChanged().conflate().collect { refresh() }
            }
            launch {
                while (true) {
                    delay(tickMs)
                    refresh()
                }
            }
        }
    }

    /** Stops everything [start] started (Play turned off). State and files are kept. */
    fun stop() {
        job?.cancel()
        job = null
        _sensed.value = emptySet()
    }

    /** App foreground with Play on: the hourly pack check, then a fresh agenda. */
    suspend fun onForeground() {
        if (!running) return
        content.refreshIfDue()
        refresh()
    }

    /** [refresh] without a result (callers that only trigger it, e.g. the debug drawer). */
    suspend fun reload() {
        refresh()
    }

    /** Recomputes [today] and [agenda] at the Play clock's now. Null when no pack is usable. */
    suspend fun refresh(): PlayAgenda? = withContext(worker) {
        val state = content.load() ?: return@withContext null
        val c = clock()
        val now = c.now()
        val date = IslandTime.dateOf(now)
        val sched = schedulerFor(state.config, tidesFor(date, c.overrides.value.tide))
        val user = store.state.value.userState()
        val agenda = sched.agenda(now, PlayContext(goal), user)
        _today.value = sched.dayPlan(date, goal, user.salt ?: 0uL)
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

    /** `GET /v1/play/me` (signed in); null while stopped or without a client. */
    suspend fun me(): ApiResult<PlayMeDto>? = if (running) api?.playMe() else null

    /** `GET /v1/play/challenges`; null while stopped or without a client. */
    suspend fun challenges(): ApiResult<PlayChallengesDto>? = if (running) api?.playChallenges() else null

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
        val f = config.ferry?.let { fr ->
            runCatching {
                val validUntil = fr.timetables.mapNotNull { (it["to"] as? JsonPrimitive)?.content }.maxOrNull() ?: return@runCatching null
                val doc = JsonObject(
                    mapOf(
                        "route" to JsonPrimitive(fr.route),
                        "crossingMin" to JsonPrimitive(fr.crossingMin),
                        "validUntil" to JsonPrimitive(validUntil),
                        "timetables" to JsonArray(fr.timetables),
                    ),
                )
                FerryTimetable.parse(doc.toString())
            }.getOrNull()
        }
        ferryFor = config to f
        return f
    }

    companion object {
        private const val TAG = "play"

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

/**
 * The Play gate for the app graph (PD-1): [current] is null while Play is off; the repository is created the first
 * time [enabled] is true (never while off), started while on and stopped when turned off (kept for the next on).
 */
class PlayGate(
    private val scope: CoroutineScope,
    private val enabled: Flow<com.bainbridgetrails.data.PlaySettings?>,
    private val create: () -> PlayRepository,
) {
    private var repo: PlayRepository? = null
    private var on = false
    private var job: Job? = null

    /** The running repository, or null while Play is off. */
    val current: PlayRepository? get() = repo.takeIf { on }

    fun start() {
        if (job != null) return
        job = scope.launch {
            enabled.collect { s ->
                val next = s?.enabled == true
                if (next == on) return@collect
                on = next
                if (next) {
                    (repo ?: create().also { repo = it }).start()
                } else {
                    repo?.stop()
                }
            }
        }
    }
}
