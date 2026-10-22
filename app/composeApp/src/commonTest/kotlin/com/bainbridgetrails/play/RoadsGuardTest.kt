package com.bainbridgetrails.play

import com.bainbridgetrails.net.ApiClient
import com.bainbridgetrails.net.CoverageUpload
import com.bainbridgetrails.net.PlayClaimDto
import com.bainbridgetrails.net.PlayWalkClaimsDto
import com.bainbridgetrails.net.WalkDto
import com.bainbridgetrails.play.claim.WalkDone
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.play.rules.StartBalance
import com.bainbridgetrails.play.rules.WalkDerived
import kotlinx.datetime.LocalDate
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Play trails v1.1 G0 (plan § 3 G0, brief § 4 AC 1, AC 7, D7, D8): the roads client path does what `main` (2aee859)
 * did. The `client` section of `../../data/play/golden/roads-guard.json` (read by path from the Gradle test working
 * directory, like [PlayFixtures]) was captured on a worktree of 2aee859: this test writes the section when the file
 * has none (no Gradle flag reaches commonTest), and asserts against it otherwise. The other sections belong to
 * `backend/test/play-roads-guard.test.ts` and `:play`'s `RoadsGuardTest`.
 *
 * Pinned: roads `WalkMarks.apply` entries after a start balance (W-COV / W-DONE / W-SQUARE differences plus a visit
 * stamp), the v1 `PUT /v1/coverage` body with every `walks[]` / `play[]` field set (its key sets and exact JSON: AC 7),
 * and a v1 `PlayState` file carrying one entry of every v1 [LogKind] (D7: a v1.1 build must still decode it).
 */
class RoadsGuardTest {
    private val golden = Path("../../data/play/golden/roads-guard.json")
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** `JsonFile`'s configuration (PlayStateStore): what a v1 build wrote to disk. */
    private val stateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val date = LocalDate(2026, 10, 29)
    private val walkUuid = "3f2a1b4c-5d6e-4f70-8192-a3b4c5d6e7f8"

    private fun read(): JsonObject = SystemFileSystem.source(golden).buffered().use { json.parseToJsonElement(it.readString()).jsonObject }

    @Test
    fun `roads walk marks, upload key sets and v1 state equal main`() {
        assertTrue(SystemFileSystem.exists(golden), "$golden missing: capture the server and :play sections on main first")
        var stored = read()
        if ("client" !in stored) {
            SystemFileSystem.sink(golden).buffered().use { it.writeString(json.encodeToString(JsonObject.serializer(), JsonObject(stored + ("client" to section()))) + "\n") }
            stored = read()
        }
        val client = stored["client"]!!.jsonObject
        val now = section()
        assertEquals(client["walkMarks"], now["walkMarks"], "roads WalkMarks drifted from main (roads-guard.json `client.walkMarks`)")
        assertEquals(client["upload"], now["upload"], "the v1 coverage upload body drifted from main (AC 7; roads-guard.json `client.upload`)")
        // D7: the v1 state file still decodes, every v1 kind by name, into the same values
        val v1Kinds = client["v1LogKinds"]!!.jsonArray.map { it.jsonPrimitive.content }
        val current = LogKind.entries.map { it.name }
        assertTrue(current.containsAll(v1Kinds), "v1 LogKind names missing: ${v1Kinds - current.toSet()}")
        assertEquals(v1Kinds, current.take(v1Kinds.size), "v1.1 kinds must be appended after the v1 ones (D7)")
        val decoded = stateJson.decodeFromString(PlayState.serializer(), client["v1State"]!!.jsonPrimitive.content)
        assertEquals(v1Kinds, decoded.logbook.entries.map { it.kind.name })
        assertEquals(state(), decoded, "the v1 PlayState JSON decodes to the state that wrote it")
    }

    private fun section(): JsonObject = buildJsonObject {
        var n = 0
        val newId = { "id-${n++}" }
        val before = Marks.walkDerived(Goal.roads, 12_345.6, 7, 11)
        val start = PlayState(logbook = StartBalance.onEnable(Logbook(), before, date, newId), derivedBase = DerivedBase.of(before))
        val after = Marks.walkDerived(Goal.roads, 13_000.0, 9, 13)
        val names = mapOf("road-first" to "First Road", "road-second" to "Second Road")
        val walked = WalkMarks.apply(start, Goal.roads, "s1761591600000-ab12", date, after, listOf("road-first", "road-second"), { names[it] ?: it }, setOf("blakely-cemetery"), newId)
        val again = WalkMarks.apply(walked, Goal.roads, "s1761591600000-ab12", date, after, listOf("road-first", "road-second"), { names[it] ?: it }, setOf("blakely-cemetery"), newId)
        put(
            "walkMarks",
            buildJsonObject {
                put("before", derived(before))
                put("after", derived(after))
                put("startEntries", entries(start.logbook.entries))
                put("walkEntries", entries(walked.logbook.entries.drop(start.logbook.entries.size)))
                put("derivedBase", "${walked.derivedBase!!.coverage}/${walked.derivedBase!!.complete}/${walked.derivedBase!!.squares}")
                put("hasPending", WalkMarks.hasPending(walked.logbook, "s1761591600000-ab12"))
                put("againAddsNothing", again == walked)
                put("settledWithWalk", JsonArray(WalkMarks.SETTLED_WITH_WALK.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
                put("total", walked.logbook.total)
            },
        )
        val upload = CoverageUpload(
            datasetVersion = "2026-10-20+86788962",
            tickM = 5,
            segments = mapOf("rseg-000011" to "Aw=="),
            walks = listOf(WalkDto(walkUuid, 3600)),
            dataset = "roads",
            playConfig = "2026.11.1",
            play = listOf(
                PlayWalkClaimsDto(
                    walkId = walkUuid,
                    claims = listOf(PlayClaimDto("egg-madrone@2026-10-26", "egg", "2026-10-26", band = 95, capped = true)),
                    dayKey = "2026-10-26",
                    hints = mapOf("riddle-frog" to 1),
                ),
            ),
        )
        val body = ApiClient.json.encodeToJsonElement(CoverageUpload.serializer(), upload).jsonObject
        val keys = { o: JsonObject -> JsonArray(o.keys.sorted().map { JsonPrimitive(it) }) }
        put(
            "upload",
            buildJsonObject {
                put("keys", keys(body))
                put("walkKeys", keys(body["walks"]!!.jsonArray[0].jsonObject))
                put("playKeys", keys(body["play"]!!.jsonArray[0].jsonObject))
                put("claimKeys", keys(body["play"]!!.jsonArray[0].jsonObject["claims"]!!.jsonArray[0].jsonObject))
                put("json", ApiClient.json.encodeToString(CoverageUpload.serializer(), upload))
            },
        )
        put("v1LogKinds", JsonArray(LogKind.entries.map { JsonPrimitive(it.name) }))
        put("v1State", stateJson.encodeToString(PlayState.serializer(), state()))
    }

    /** One entry of every kind, plus every other v1 field set (walk ids opaque, as on disk). */
    private fun state(): PlayState = PlayState(
        found = setOf("egg-madrone@2026-10-26", "treasure-weekly-roads@2026-10-26#00"),
        solved = mapOf("riddle-frog" to "2026-10-27"),
        hints = mapOf("riddle-frog" to 1),
        logbook = Logbook(
            LogKind.entries.mapIndexed { i, kind ->
                LogEntry(
                    id = "e$i",
                    kind = kind,
                    what = "what $kind",
                    why = "why $kind",
                    date = date,
                    marks = if (kind == LogKind.visit || kind == LogKind.stamp) 0 else 10 + i,
                    walkId = if (i % 2 == 0) "w-0123456789abcdef0123456789abcdef" else null,
                    contentId = if (i % 3 == 0) "content:$kind" else null,
                    capped = kind == LogKind.egg,
                    band = if (kind == LogKind.ink) 80 else null,
                )
            },
        ),
        derivedBase = DerivedBase(493, 175, 275),
        walks = listOf(WalkDone("w-0123456789abcdef0123456789abcdef", 1_761_700_000_000, 2)),
    )

    private fun derived(d: WalkDerived) = "${d.coverage}+${d.complete}+${d.squares}=${d.total}"

    private fun entries(list: List<LogEntry>) = JsonArray(
        list.map { e ->
            buildJsonObject {
                put("id", e.id)
                put("kind", e.kind.name)
                put("what", e.what)
                put("why", e.why)
                put("date", e.date.toString())
                put("marks", e.marks)
                put("walkId", e.walkId)
                put("contentId", e.contentId)
                put("board", e.board.name)
                put("capped", e.capped)
                put("band", e.band)
            }
        },
    )
}
