package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.coverage.SegmentGeom
import com.bainbridgetrails.coverage.TrailMeta
import com.bainbridgetrails.data.DataFixtures
import com.bainbridgetrails.net.FakeServer
import com.bainbridgetrails.net.Reply
import com.bainbridgetrails.play.claim.GatedSummary
import com.bainbridgetrails.play.config.ChallengeMetric
import com.bainbridgetrails.play.config.ChallengeScope
import com.bainbridgetrails.play.config.ChallengeSpec
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.Scope
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogCopy
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.StartBalance
import com.bainbridgetrails.play.rules.TrailLedger
import com.bainbridgetrails.play.rules.TrailRewardInput
import com.bainbridgetrails.play.rules.TrailRewards
import com.bainbridgetrails.play.rules.TrailStart
import com.bainbridgetrails.play.rules.TrailTables
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.DerivedGoal
import com.bainbridgetrails.play.schedule.Occurrence
import kotlinx.coroutines.flow.MutableStateFlow
import com.bainbridgetrails.tracking.SessionSummary
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Play trails v1.1 plan L2-2 (D1, D4, review P1-8): a finished trails walk writes one `pending` Logbook row per newly
 * qualifying trails key, settled with the walk's upload; while the trails start has not run, the walk runs it inside the
 * same update so every key is paid exactly once.
 */
class WalkMarksTrailsTest {
    private val date = LocalDate(2026, 10, 31)
    private var n = 0
    private val newId = { "id-${n++}" }

    private fun apply(st: PlayState, input: TrailRewardInput?, completed: List<String>, session: String = "s1", after: WalkDerived = WalkDerived(500, 100, 25)) =
        WalkMarks.apply(
            st, Goal.trails, session, date, after, completed, { TrailFixtures.names[it] ?: it }, emptySet(), newId,
            trails = input?.let { TrailWalk(TrailFixtures.tables, it) },
        )

    /** Opted in and started: start balance written, trails start ran (nothing was owed then). */
    private fun started(): PlayState {
        val sb = StartBalance.onEnable(Logbook(), WalkDerived(0, 0, 0), date, newId)
        val ledger = TrailStart.onEnable(Goal.trails, TrailLedger(sb), emptySet(), date, newId)
        return PlayState(derivedBase = DerivedBase(), logbook = ledger.logbook, trailPaid = ledger.trailPaid, trailsStarted = ledger.trailsStarted)
    }

    @Test
    fun `trails derived parses trail tables, roads has none`() {
        val t = TrailFixtures.tables
        assertEquals(19, t.parks.size, "the Play parks, Pritchard Park excluded")
        assertTrue("Pritchard Park" !in t.parks)
        assertEquals(TrailFixtures.MORITANI, t.featurePark[TrailFixtures.HAYANO])
        assertEquals(921, t.gainDm[TrailFixtures.PETERS])
        assertEquals(setOf("seg-h1", "seg-h2", "seg-p1"), t.segmentLengthM.keys, "counted segments of the dataset")
        assertEquals(listOf("seg-h1", "seg-h2"), t.trailSegments[TrailFixtures.HAYANO])
        assertEquals(TrailFixtures.derived.quietSegments, t.quietSegments)
        assertNull(PlayDerived.parse(PlayFixtures.read("derived-roads.json")).trailTables(TrailFixtures.dataset), "roads: no trails tables")
        assertNull(PlayDerived.parse("{}").trailTables(TrailFixtures.dataset))
    }

    @Test
    fun `Hayano walk writes complete, park_first, park_complete pending`() {
        val st = apply(started(), TrailFixtures.hayanoDone, completed = listOf(TrailFixtures.HAYANO))
        val walk = st.logbook.entries.filter { it.walkId == "s1" }
        assertEquals(listOf(LogKind.complete, LogKind.coverage, LogKind.square, LogKind.park_first, LogKind.park_complete), walk.map { it.kind })
        val trails = walk.filter { it.kind in TrailRewards.KINDS }
        assertEquals(listOf("park1:Moritani Preserve", "parkc:Moritani Preserve"), trails.map { it.contentId })
        assertEquals(listOf(50, 150), trails.map { it.marks })
        assertEquals(listOf(TrailFixtures.MORITANI, TrailFixtures.MORITANI), trails.map { it.what })
        assertTrue(walk.all { it.board == BoardState.pending }, "provisional until the upload's play block")
        assertTrue(WalkMarks.hasPending(st.logbook, "s1"))
        assertEquals(emptySet(), st.trailPaid, "per-key rows are paid through their contentId, not the ledger")
        assertTrue(st.trailsStarted)
        // The same walk again (a re-run hook): nothing more.
        assertEquals(st.logbook, apply(st, TrailFixtures.hayanoDone, completed = listOf(TrailFixtures.HAYANO)).logbook)
        // A later walk with nothing new adds no trails row.
        val next = apply(st, TrailFixtures.hayanoDone, completed = emptyList(), session = "s2", after = WalkDerived(500, 100, 25))
        assertTrue(next.logbook.entries.none { it.walkId == "s2" && it.kind in TrailRewards.KINDS })
    }

    @Test
    fun `rows settle with the walk`() {
        val st = apply(started(), TrailFixtures.bothDoneIsland, completed = listOf(TrailFixtures.HAYANO, TrailFixtures.PETERS))
        val kinds = st.logbook.entries.filter { it.walkId == "s1" && it.kind in TrailRewards.KINDS }.map { it.kind }.toSet()
        assertEquals(setOf(LogKind.climb, LogKind.high_ground, LogKind.park_first, LogKind.park_complete, LogKind.island), kinds)
        assertEquals(TrailRewards.KINDS, WalkMarks.TRAILS_SETTLED_WITH_WALK)

        val accepted = WalkMarks.settle(st.logbook, listOf(PlaySettled("s1", walk = BoardState.accepted)))
        assertTrue(accepted.entries.filter { it.walkId == "s1" }.all { it.board == BoardState.accepted }, "${accepted.entries}")
        assertFalse(WalkMarks.hasPending(accepted, "s1"))

        val refused = WalkMarks.settle(st.logbook, listOf(PlaySettled("s1", walk = BoardState.local_only)))
        assertTrue(refused.entries.filter { it.walkId == "s1" && it.kind in TrailRewards.KINDS }.all { it.board == BoardState.local_only })

        // An answer for another walk, or one without the walk's outcome, leaves the rows pending.
        val other = WalkMarks.settle(st.logbook, listOf(PlaySettled("s9", walk = BoardState.accepted), PlaySettled("s1")))
        assertTrue(WalkMarks.hasPending(other, "s1"))
    }

    @Test
    fun `walk finalized during turn-on restore pays every key once`() {
        // Opted in (start balance written), the trails start not yet run: the walk's hook wins the race.
        val sb = StartBalance.onEnable(Logbook(), WalkDerived(0, 0, 0), date, newId)
        val before = PlayState(derivedBase = DerivedBase(), logbook = sb)
        val input = TrailFixtures.bothDoneIsland
        val qualifying = TrailRewards.qualifying(input, TrailFixtures.tables)
        val walked = apply(before, input, completed = listOf(TrailFixtures.HAYANO, TrailFixtures.PETERS))
        assertTrue(walked.trailsStarted)
        val kinds = walked.logbook.entries.map { it.kind }
        assertEquals(listOf(LogKind.start_balance, LogKind.start_trails, LogKind.island), kinds.take(3), "the start's entries first")
        assertTrue(walked.logbook.entries.none { it.walkId == "s1" && it.kind in TrailRewards.KINDS }, "the walk owes nothing more")
        val trailMarks = walked.logbook.entries.filter { it.kind == LogKind.start_trails || it.kind in TrailRewards.KINDS }.sumOf { it.marks }
        assertEquals(qualifying.sumOf { TrailRewards.marksOf(it) }, trailMarks, "every key paid once")

        // The turn-on's own TrailStart then runs on the same snapshot: nothing.
        val ledger = TrailStart.onEnable(Goal.trails, walked.trailLedger(), qualifying, date, newId)
        assertEquals(walked.logbook, ledger.logbook)

        // The other order: the start first, then the walk: the walk adds no trails row either.
        val startedFirst = before.with(TrailStart.onEnable(Goal.trails, before.trailLedger(), qualifying, date, newId))
        val after = apply(startedFirst, input, completed = listOf(TrailFixtures.HAYANO, TrailFixtures.PETERS), session = "s2")
        assertTrue(after.logbook.entries.none { it.walkId == "s2" && it.kind in TrailRewards.KINDS })
        assertEquals(startedFirst.trailPaid, after.trailPaid)
    }

    @Test
    fun `no trails input or roads writes no trails row and leaves the ledger`() {
        val before = PlayState(derivedBase = DerivedBase())
        val deferred = apply(before, null, completed = listOf(TrailFixtures.HAYANO))
        assertTrue(deferred.logbook.entries.none { it.kind in TrailRewards.KINDS || it.kind == LogKind.start_trails })
        assertFalse(deferred.trailsStarted, "the start stays due")
        val roads = WalkMarks.apply(
            before, Goal.roads, "s1", date, WalkDerived(10, 0, 0), emptyList(), { it }, emptySet(), newId,
            trails = TrailWalk(TrailFixtures.tables, TrailFixtures.bothDoneIsland),
        )
        assertTrue(roads.logbook.entries.none { it.kind in TrailRewards.KINDS || it.kind == LogKind.start_trails })
        assertFalse(roads.trailsStarted)
        assertNotNull(roads.derivedBase)
    }

    // --- climb / parks challenge inputs (plan D5) ------------------------------------------------------------------------

    /** The bundled pack with `ch-climb` (target [climbM]) and `ch-parkhop` (target 3) replaced by test copies. */
    private fun packWithTrailChallenges(climbM: Int): String {
        val root = Json.parseToJsonElement(PlayFixtures.pack).jsonObject
        val extra = Json.parseToJsonElement(
            """[{"id":"ch-climb","type":"challenge","lore":"challenge","goals":["trails"],"when":{"every":"week","on":["mon"],"from":"00:00","to":"24:00","days":7},""" +
                """"marks":100,"repeat":"once","resolve":{"by":"challenge"},"challenge":{"scope":"solo","metric":"climb","target":{"trails":$climbM}},""" +
                """"copy":{"title":"Uphill week","body":"Finish trails that climb {target} between them this week."},"a11y":{"label":"Uphill week","announce":"Uphill week."}},""" +
                """{"id":"ch-parkhop","type":"challenge","lore":"challenge","goals":["trails"],"when":{"every":"week","on":["sat"],"from":"00:00","to":"24:00","days":2},""" +
                """"marks":100,"repeat":"once","resolve":{"by":"challenge"},"challenge":{"scope":"solo","metric":"parks","target":{"trails":3}},""" +
                """"copy":{"title":"Park hop","body":"Finish a trail in different parks this weekend."},"a11y":{"label":"Park hop","announce":"Park hop."}}]""",
        ).jsonArray
        // Pack 2026.11.3 already ships both items (L4-2); drop them first so this test controls the climb target.
        val base = root.getValue("items").jsonArray.filterNot { it.jsonObject["id"]?.jsonPrimitive?.content in setOf("ch-climb", "ch-parkhop") }
        return JsonObject(root + ("items" to JsonArray(base + extra))).toString()
    }

    @Test
    fun `trails walk claims ch-climb from the sticky set and the card status follows`() = runTest {
        val clock = DebugPlayClock(system = { Instant.parse("2026-10-31T18:00:00Z") })
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-31T11:00")))
        val d = StandardTestDispatcher(testScheduler)
        val dir = DataFixtures.tmpDir("play-trail-challenges")
        val server = FakeServer().apply { on("GET /v1/play/content", Reply(304, "")) }
        var input: TrailRewardInput = TrailFixtures.hayanoDone
        val content = PlayContentRepository(
            api = server.client(), dir = dir, bundled = { packWithTrailChallenges(climbM = 100) },
            bundledTides = { PlayFixtures.tideText(it) }, playOn = { true }, now = { 0L }, io = d, compute = d,
        )
        val r = PlayRepository(
            scope = backgroundScope, goal = Goal.trails, clock = { clock }, content = content,
            places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-trails.json")) },
            store = PlayStateStore("$dir/trails-state.json", d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
            claims = PlayClaimQueue("$dir/claims.json", d), newId = newId,
            trailTables = { TrailFixtures.tables }, trailInput = { input },
        )
        // Hayano (8.4 m) on Tuesday, logged as a completion inside the Mon→Mon window.
        r.store.update { it.copy(logbook = it.logbook.add(LogEntry("c1", LogKind.complete, "Hayano Trail", "why", LocalDate(2026, 10, 27), 100, walkId = "w0", contentId = TrailFixtures.HAYANO))) }
        val climbOcc = assertNotNull(r.refresh()).active.map { it.occurrence }.single { it.itemId == "ch-climb" }
        val before = r.trailChallenges(listOf(climbOcc)).getValue(climbOcc.key)
        assertEquals(TrailChallengeStatus(ChallengeMetric.climb, progress = 84, target = 1_000), before, "dm: 8.4 m of 100 m")
        assertFalse(before.hidden || TrailRewards.claims(before.target, before.progress))

        // Saturday: Peter's (92.1 m) → P = 100.5 m ≥ 100 m: the walk claims ch-climb.
        input = TrailFixtures.bothDoneIsland
        val start = PlayFixtures.island("2026-10-31T10:00").toEpochMilliseconds()
        val summary = GatedSummary(
            sessionId = "s1", startedAtMs = start, endedAtMs = start + 3_600_000, distanceM = 1_110.0, matchedM = 1_110.0,
            newlyCompleted = listOf(TrailFixtures.PETERS), verified = true, droppedVehicle = 0,
        )
        val result = assertNotNull(r.claimWalk("s1", emptyList(), summary))
        assertEquals(listOf(climbOcc.key), result.claims.filter { it.itemId == "ch-climb" }.map { it.key })
        assertTrue(result.claims.none { it.itemId == "ch-parkhop" }, "2 parks of 3")
        assertTrue(climbOcc.key in r.store.state.value.found)
        // The walk's `complete` row (WalkMarks) lands: the card reads 100.5 of 100 m.
        r.store.update { it.copy(logbook = it.logbook.add(LogEntry("c2", LogKind.complete, "Peter's Trail", "why", LocalDate(2026, 10, 31), 100, walkId = "s1", contentId = TrailFixtures.PETERS))) }
        val after = r.trailChallenges(listOf(climbOcc)).getValue(climbOcc.key)
        assertEquals(TrailChallengeStatus(ChallengeMetric.climb, progress = 1_005, target = 1_000), after)
        assertTrue(TrailRewards.claims(after.target, after.progress))
        assertEquals(emptyMap(), r.trailChallenges(emptyList()))
    }

    @Test
    fun `card status hides a challenge with nothing left`() {
        val spec = ChallengeSpec(ChallengeScope.solo, ChallengeMetric.parks, target = mapOf(Goal.trails to 3))
        val o = Occurrence(
            key = "ch-parkhop@2026-10-31", itemId = "ch-parkhop", type = ItemType.challenge, scope = Scope.island,
            start = PlayFixtures.island("2026-10-31T00:00"), end = PlayFixtures.island("2026-11-02T00:00"), hero = false,
            placements = emptyList(), marks = 100, variant = null, sensable = false, daySpans = emptyList(), resolvable = emptyList(), tideGated = false,
        )
        val everyTrail = TrailFixtures.tables.parkMembers.values.flatten().toSet()
        val done = TrailChallengeStatus.of(spec, o, TrailFixtures.tables, everyTrail, Logbook())!!
        assertTrue(done.hidden, "every Play park finished before the window: target 0")
        val open = TrailChallengeStatus.of(spec, o, TrailFixtures.tables, emptySet(), Logbook())!!
        assertEquals(TrailChallengeStatus(ChallengeMetric.parks, 0, 3), open)
        assertNull(TrailChallengeStatus.of(spec.copy(metric = ChallengeMetric.completed), o, TrailFixtures.tables, emptySet(), Logbook()))
    }

    @Test
    fun `probe leaves trailStartDeferred untouched`() = runTest {
        // Final review P2-4: recordWalkMarks' `hasPending` probe runs the walk's next state outside the store update; the
        // deferral it computes there must not set the flag. Here the probe sees "signed in" (defer) and the update does
        // not, so the walk runs the trails start itself and nothing is left deferred for the next restore.
        val clock = DebugPlayClock(system = { Instant.parse("2026-10-31T18:00:00Z") })
        clock.set(PlayClockMode.Fixed(PlayFixtures.island("2026-10-31T11:00")))
        val d = StandardTestDispatcher(testScheduler)
        val dir = DataFixtures.tmpDir("play-walk-probe")
        val server = FakeServer().apply { on("GET /v1/play/content", Reply(304, "")) }
        val content = PlayContentRepository(
            api = server.client(), dir = dir, bundled = { PlayFixtures.pack },
            bundledTides = { PlayFixtures.tideText(it) }, playOn = { true }, now = { 0L }, io = d, compute = d,
        )
        val signedInAnswers = ArrayDeque<Boolean>()
        var input: TrailRewardInput? = null
        val r = PlayRepository(
            scope = backgroundScope, goal = Goal.trails, clock = { clock }, content = content,
            places = { PlayFixtures.EmptyPlaces }, derived = { DerivedGoal.decode(PlayFixtures.read("derived-trails.json")) },
            store = PlayStateStore("$dir/trails-state.json", d), track = MutableStateFlow<List<Fix>>(emptyList()), compute = d,
            claims = PlayClaimQueue("$dir/claims.json", d), signedIn = { signedInAnswers.removeFirstOrNull() ?: false },
            walkDerived = { WalkDerived(500, 100, 25) }, walkContext = { WalkContext(names = TrailFixtures.names) },
            newId = newId, trailTables = { TrailFixtures.tables }, trailInput = { input },
        )
        // A v1-restored Logbook whose trails start has not run (the deferral's precondition); the trail input is
        // unavailable during the turn-on, so the start waits for the walk.
        r.store.update {
            PlayState(logbook = Logbook(listOf(LogEntry("r", LogKind.restored, LogCopy.what(LogKind.restored), LogCopy.why(LogKind.restored), LocalDate(2026, 10, 20), 3_000, board = BoardState.accepted))))
        }
        r.start()
        runCurrent()
        assertFalse(r.store.state.value.trailsStarted)
        assertFalse(r.trailStartDeferred)

        input = TrailFixtures.bothDoneIsland
        signedInAnswers += true // the probe's read only; the update reads signed out
        val start = PlayFixtures.island("2026-10-31T10:00").toEpochMilliseconds()
        val summary = SessionSummary(
            sessionId = "s1", islandBefore = 0.0, islandAfter = 1.0, trails = emptyList(), verified = true, droppedVehicle = 0,
            droppedSteps = 0, newlyCompleted = listOf(TrailFixtures.PETERS), startedAt = start, endedAt = start + 3_600_000, distanceM = 1_110.0, matchedM = 1_110.0,
        )
        r.gatedHook("s1", emptyList(), summary)
        runCurrent()
        assertTrue(signedInAnswers.isEmpty(), "the probe read signed in")
        assertTrue(r.store.state.value.trailsStarted, "the update did not defer: the walk ran the trails start")
        assertFalse(r.trailStartDeferred, "the probe's deferral never reaches the flag")
        r.stop()
    }
}

/**
 * Trails tables for the composeApp Play tests (plan L2-2): the bundled `derived-trails.json` (real `featurePark`,
 * `featureGainM`, `parks`, quiet sets) over a small hand-built dataset (CI has no `data/dist`). Hayano Trail is Moritani
 * Preserve's only trail (8.4 m); Peter's Trail is one of Gazzam Lake Preserve's eleven (92.1 m).
 */
internal object TrailFixtures {
    const val HAYANO = "trail-hayano-trail"
    const val PETERS = "trail-peters-trail"
    const val MORITANI = "Moritani Preserve"
    const val GAZZAM = "Gazzam Lake Preserve"

    val derived: PlayDerived by lazy { PlayDerived.parse(PlayFixtures.read("derived-trails.json")) }

    val dataset = Dataset(
        trails = listOf(
            TrailMeta(HAYANO, "Hayano Trail", listOf("seg-h1", "seg-h2"), 0.25, park = MORITANI),
            TrailMeta(PETERS, "Peter's Trail", listOf("seg-p1"), 0.69, park = GAZZAM),
        ),
        islandTotalM = 1_512.0,
        segments = listOf(
            SegmentGeom("seg-h1", 201.0, true, listOf(HAYANO), emptyList()),
            SegmentGeom("seg-h2", 201.0, true, listOf(HAYANO), emptyList()),
            SegmentGeom("seg-p1", 1_110.0, true, listOf(PETERS), emptyList()),
        ),
    )

    val tables: TrailTables by lazy { derived.trailTables(dataset)!! }

    val names: Map<String, String> = dataset.trails.associate { it.id to it.name }

    fun input(coverage: Map<String, Double>, completed: Set<String>, islandWhole: Boolean = false): TrailRewardInput =
        TrailRewards.inputs(coverage, completed, islandWhole, tables)

    /** Hayano walked end to end: park1 + parkc Moritani (+50, +150). */
    val hayanoDone: TrailRewardInput get() = input(mapOf("seg-h1" to 201.0, "seg-h2" to 201.0), setOf(HAYANO))

    /** Hayano and Peter's done (Σ 100.5 m → climb:100, high Peter's, two park1, parkc Moritani) and the island whole. */
    val bothDoneIsland: TrailRewardInput
        get() = input(mapOf("seg-h1" to 201.0, "seg-h2" to 201.0, "seg-p1" to 1_110.0), setOf(HAYANO, PETERS), islandWhole = true)
}
