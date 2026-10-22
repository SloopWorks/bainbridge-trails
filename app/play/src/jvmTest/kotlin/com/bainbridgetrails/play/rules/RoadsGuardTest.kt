package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.schedule.RealPack
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Play trails v1.1 G0 (plan § 3 G0, brief § 4 AC 1): the roads rules of `:play` do what `main` (2aee859) did. The
 * `play` section of `data/play/golden/roads-guard.json` was captured on a worktree of 2aee859 with
 * `-Pplay.updateGolden=true` (the other sections belong to `backend/test/play-roads-guard.test.ts` and the composeApp
 * `RoadsGuardTest`; each test rewrites only its own section); every later run asserts against it.
 *
 * Pinned: the roads `WalkDerived` of a fixture snapshot and `StartBalance.onEnable` entries (first turn-on, a later
 * catch-up, then nothing), the roads constants of [Marks], the bundled pack's roads challenge list, and the roads
 * agenda preview: sha256 of `src/jvmTest/resources/golden/agenda-roads.tsv` without its header line (line 1 names
 * the pack version, which moves; every dated row must not).
 */
class RoadsGuardTest {
    private val golden = File(RealPack.ROOT, "data/play/golden/roads-guard.json")
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val date = LocalDate(2026, 10, 29)
    private val agenda = File("src/jvmTest/resources/golden/agenda-roads.tsv")

    /** sha256 of agenda-roads.tsv minus line 1, on main 2aee859 (equal on the branch after the pack bump). */
    private val agendaSha = "93517aa593106aea00bab2cb408119652378a12816f77fd1288779c60c4c4603"

    @Test
    fun `roads start balance, walk marks, challenge list equal main`() {
        val play = section()
        if (RealPack.update) {
            val stored = if (golden.exists()) json.parseToJsonElement(golden.readText()).jsonObject else JsonObject(emptyMap())
            golden.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(stored + ("play" to play))) + "\n")
        }
        assertTrue(golden.exists(), "${golden.path} missing: capture it on main with -Pplay.updateGolden=true")
        val stored = json.parseToJsonElement(golden.readText()).jsonObject
        assertEquals(play, stored["play"], "roads Play rules drifted from main (roads-guard.json `play`)")
    }

    @Test
    fun `agenda-roads tsv minus the header line has the sha256 captured on main`() {
        assertTrue(agenda.exists(), "${agenda.path} missing")
        val lines = agenda.readText().split("\n")
        assertTrue(lines.first().startsWith("# agenda-roads.tsv: PlayScheduler over pack "), lines.first())
        assertEquals(agendaSha, sha256(lines.drop(1).joinToString("\n")), "the roads agenda preview drifted from main")
        if (RealPack.update) return // the other test writes the section in this same run
        val stored = json.parseToJsonElement(golden.readText()).jsonObject["play"]?.jsonObject
        assertEquals(JsonPrimitive(agendaSha), stored?.get("agendaRoadsSha256"), "roads-guard.json `play.agendaRoadsSha256`")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun section(): JsonObject = buildJsonObject {
        var n = 0
        val newId = { "id-${n++}" }
        // 12,345.6 m of counted road, 7 roads complete, 11 chart squares: W-COV floor(/25) + W-DONE 25 each + W-SQUARE 25 each
        val first = Marks.walkDerived(Goal.roads, 12_345.6, 7, 11)
        val later = Marks.walkDerived(Goal.roads, 20_000.0, 9, 14)
        val onEnable = StartBalance.onEnable(Logbook(), first, date, newId)
        val catchUp = StartBalance.onEnable(onEnable, later, date, newId)
        val again = StartBalance.onEnable(catchUp, later, date, newId)
        put(
            "startBalance",
            buildJsonObject {
                put("first", derived(first))
                put("later", derived(later))
                put("entries", entries(onEnable.entries))
                put("catchUpEntries", entries(catchUp.entries.drop(onEnable.entries.size)))
                put("againAddsNothing", again == catchUp)
                put("total", catchUp.total)
            },
        )
        put(
            "marks",
            buildJsonObject {
                put("covRateM", Marks.covRateM(Goal.roads))
                put("doneMarks", Marks.doneMarks(Goal.roads))
                put("square", Marks.SQUARE)
                put("day", Marks.DAY)
                put("coverageDelta_12345_6_to_20000", Marks.coverageDelta(Goal.roads, 12_345.6, 20_000.0))
                put("keptWeek_0_1_6_7", JsonArray(listOf(0, 1, 6, 7).map { JsonPrimitive(Marks.keptWeek(it)) }))
            },
        )
        put(
            "challenges",
            JsonArray(
                RealPack.config.items
                    .filter { it.type == ItemType.challenge && it.enabled && Goal.roads in it.goals }
                    .map { item ->
                        buildJsonObject {
                            put("id", item.id)
                            put("scope", item.challenge?.scope?.name)
                            put("metric", item.challenge?.metric?.name)
                            put("target", item.challenge?.target?.get(Goal.roads))
                            put("marks", item.marks)
                        }
                    },
            ),
        )
        put("walkDerivedKinds", JsonArray(LogKind.entries.filter { it.walkDerived }.map { JsonPrimitive(it.name) }))
        put("agendaRoadsSha256", agendaSha)
    }

    private fun derived(d: WalkDerived) = buildJsonObject {
        put("coverage", d.coverage)
        put("complete", d.complete)
        put("squares", d.squares)
        put("total", d.total)
    }

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
