@file:Suppress("EnumEntryName", "EnumNaming") // the v1 mirror's constants are the stored keywords

package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.schedule.RealPack
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan D7 (review P2-2): new `LogKind`s are appended, so a v1 Play state still decodes in v1.1; a v1 build cannot
 * decode a v1.1 state that holds a new kind (its file reads as null → empty Play state on that phone); and whatever
 * survives a v1.1 → v1 → v1.1 round trip, no trails key is paid twice, because paid = `trailPaid` ∪ the trail kinds'
 * Logbook `contentId`s (D1).
 *
 * The v1 side is modelled by [V1Kind] / [V1State]: the v1 enum by name (pinned to G0's captured `v1LogKinds`) and the
 * state file's Logbook, decoded with the composeApp state file's settings (`ignoreUnknownKeys`, `encodeDefaults`).
 * [V11State] is the v1.1 state's Play-ledger shape (D1: `trailPaid`, `trailsStarted`, both defaulted).
 */
class LogKindCompatTest {
    private val stateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val guard = Json.parseToJsonElement(File(RealPack.ROOT, "data/play/golden/roads-guard.json").readText())
        .jsonObject.getValue("client").jsonObject
    private val date = LocalDate(2026, 10, 31)

    /** LogKind as built on main 2aee859. */
    @Serializable
    enum class V1Kind {
        coverage, complete, park_first, park_complete, milestone, island, square, day, week,
        treasure, egg, riddle, ink, retreat, bramble, lore, horn, challenge, island_challenge, wave,
        start_balance, restored, stamp, visit,
    }

    @Serializable
    data class V1Entry(
        val id: String, val kind: V1Kind, val what: String, val why: String, val date: LocalDate, val marks: Int,
        val walkId: String? = null, val contentId: String? = null, val board: BoardState = BoardState.pending,
        val capped: Boolean = false, val band: Int? = null,
    )

    @Serializable
    data class V1State(val schema: Int = 2, val logbook: V1Logbook = V1Logbook())

    @Serializable
    data class V1Logbook(val entries: List<V1Entry> = emptyList())

    @Serializable
    data class V11State(val schema: Int = 2, val logbook: Logbook = Logbook(), val trailPaid: Set<String> = emptySet(), val trailsStarted: Boolean = false)

    /** What a v1 build reads from a state file: null when it does not decode (`JsonFile.read`). */
    private fun v1Read(text: String): V1State? = try {
        stateJson.decodeFromString(V1State.serializer(), text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun v11Read(text: String): V11State = stateJson.decodeFromString(V11State.serializer(), text)

    @Test
    fun `v1 JSON decodes`() {
        val v1Kinds = guard.getValue("v1LogKinds").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(v1Kinds, V1Kind.entries.map { it.name }, "the v1 mirror is the enum G0 captured on main")
        assertEquals(v1Kinds, LogKind.entries.take(v1Kinds.size).map { it.name }, "v1.1 appends after the v1 kinds")
        val text = guard.getValue("v1State").jsonPrimitive.content
        val state = v11Read(text)
        assertEquals(v1Kinds, state.logbook.entries.map { it.kind.name })
        assertEquals(emptySet(), state.trailPaid)
        assertEquals(false, state.trailsStarted)
        assertEquals(v1Read(text)!!.logbook.entries.sumOf { it.marks }, state.logbook.total)
    }

    @Test
    fun `every kind round-trips`() {
        val lb = Logbook(
            LogKind.entries.mapIndexed { i, k ->
                LogEntry("e$i", k, "what $k", "why $k", date, if (k == LogKind.visit || k == LogKind.stamp) 0 else i, contentId = "c:$k")
            },
        )
        val state = V11State(logbook = lb, trailPaid = setOf("climb:100", "island"), trailsStarted = true)
        val text = stateJson.encodeToString(V11State.serializer(), state)
        assertEquals(state, v11Read(text))
        for (k in LogKind.entries) assertTrue("\"kind\":\"${k.name}\"" in text, k.name)
        // a v1 build cannot read a Logbook that holds a v1.1 kind
        assertNull(v1Read(text))
        assertFailsWith<SerializationException> { stateJson.decodeFromString(V1Entry.serializer(), stateJson.encodeToString(LogEntry.serializer(), lb.entries.last())) }
    }

    @Test
    fun `v1_1 to v1 to v1_1 never pays a key twice`() {
        var n = 0
        val newId = { "e${n++}" }
        val owedKeys = setOf("climb:100", "high:trail-b", "park1:Alpha Park", "parkc:Alpha Park", "island")
        val worth = owedKeys.sumOf { TrailRewards.marksOf(it) }
        fun trailMarks(lb: Logbook) = lb.entries.filter { it.kind in TrailRewards.KINDS || it.kind == LogKind.start_trails }.sumOf { it.marks }

        // (a) v1.1 opt-in folds the keys into start_trails → a v1 build reads nothing → v1.1 again pays each key once
        val sb = StartBalance.onEnable(Logbook(), WalkDerived(100, 0, 0), date, newId)
        val on = TrailStart.onEnable(Goal.trails, TrailLedger(sb), owedKeys, date, newId)
        val text = stateJson.encodeToString(V11State.serializer(), V11State(logbook = on.logbook, trailPaid = on.trailPaid, trailsStarted = on.trailsStarted))
        assertNull(v1Read(text), "D7: local Play state lost on downgrade")
        val v1Fresh = stateJson.encodeToString(V1State.serializer(), V1State()) // the v1 build starts empty and writes
        val back = v11Read(v1Fresh)
        val again = TrailStart.onEnable(Goal.trails, TrailLedger(back.logbook, back.trailPaid, back.trailsStarted), owedKeys, date, newId)
        assertEquals(worth, trailMarks(again.logbook))
        assertEquals(again, TrailStart.onEnable(Goal.trails, again, owedKeys, date, newId), "and never again")

        // (b) v1.1 rows of v1 kinds only (walk-written park rows, the island) → v1 keeps them, drops trailPaid →
        //     v1.1 again: their contentIds are paid, nothing is written
        val walk = TrailRewards.entries(listOf("park1:Alpha Park", "parkc:Alpha Park", "island"), { null }, date, "w1", newId)
        val v11 = V11State(logbook = Logbook(sb.entries + walk), trailPaid = setOf("park1:Alpha Park", "parkc:Alpha Park", "island"), trailsStarted = true)
        val v1 = v1Read(stateJson.encodeToString(V11State.serializer(), v11))!!
        val rewritten = stateJson.encodeToString(V1State.serializer(), v1)
        assertTrue("trailPaid" !in rewritten)
        val restored = v11Read(rewritten)
        assertEquals(v11.logbook, restored.logbook)
        val keys = setOf("park1:Alpha Park", "parkc:Alpha Park", "island")
        val after = TrailStart.onEnable(Goal.trails, TrailLedger(restored.logbook, restored.trailPaid, restored.trailsStarted), keys, date, newId)
        assertEquals(restored.logbook, after.logbook)
        assertEquals(keys.sumOf { TrailRewards.marksOf(it) }, trailMarks(after.logbook))
    }
}
