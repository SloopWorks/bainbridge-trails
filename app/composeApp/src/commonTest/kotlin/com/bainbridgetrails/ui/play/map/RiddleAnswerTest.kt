package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.play.PlayFixtures
import com.bainbridgetrails.play.PlayState
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.rules.LogKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RiddleSheet answers (ux.md §2.4; product §4.4, PF8-1, PF8-5, PF8-6). */
class RiddleAnswerTest {
    private val config: PlayConfig = (PlayConfig.decode(PlayFixtures.pack) as PlayConfigResult.Ok).config
    private val frog = config.items.single { it.id == "riddle-frog" }
    private val bond = config.items.single { it.id == "riddle-bond" }
    private val copy = RiddleCopy.english { EnglishPlayStrings[it] }
    private val today = LocalDate(2026, 10, 29)
    private val parks = mapOf("trail-grand-forest-west" to "Grand Forest", "trail-gazzam-lake" to "Gazzam Lake")
    private var ids = 0
    private val newId = { "id-${++ids}" }

    private fun answer(state: PlayState, item: com.bainbridgetrails.play.config.Item, pick: RiddlePick) =
        RiddleLogic.answer(state, item, pick, config, parkOf = { parks[it] }, today = today, newId = newId)

    @Test
    fun `wrong answer shows play_riddle_wrong only`() {
        val before = PlayState(found = setOf("x"), logbook = com.bainbridgetrails.play.rules.Logbook())
        val out = answer(before, frog, RiddlePick(featureIds = listOf("road-phelps-road-northeast")))
        val wrong = assertIs<RiddleOutcome.Wrong>(out)
        val ui = RiddleLogic.ui(RiddleStage.UNSOLVED, wrong = true, copy = copy)
        assertEquals("Not quite. Try again any time.", ui.message)
        assertNull(ui.stamp)
        assertTrue(ui.answerRow, "unlimited tries: the answer row stays")
        assertNull(ui.claimed)
        // Nothing else changes: no solve, no Logbook line, no marks (PF8-6).
        assertEquals(RiddleStage.UNSOLVED, RiddleLogic.stage(before, frog, claimed = false))
        val bondWrong = answer(before, bond, RiddlePick(featureIds = listOf("trail-gazzam-lake")))
        assertIs<RiddleOutcome.Wrong>(bondWrong)
    }

    @Test
    fun `armchair solve stamps 0 marks`() {
        val before = PlayState()
        // Tapping Frog Rock's map area (PF8-1): the landmark place, or a tap within reach of it.
        val solved = assertIs<RiddleOutcome.Solved>(answer(before, frog, RiddlePick(at = GeoPoint(lat = 47.69610, lon = -122.52350))))
        val st = solved.state
        assertEquals(today.toString(), st.solved["riddle-frog"])
        val stamp = st.logbook.entries.single()
        assertEquals(LogKind.stamp, stamp.kind)
        assertEquals(0, stamp.marks)
        assertEquals(0, st.logbook.total, "a solve is a stamp, never marks")
        assertEquals(RiddleStage.SOLVED, RiddleLogic.stage(st, frog, claimed = false))
        val ui = RiddleLogic.ui(RiddleStage.SOLVED, wrong = false, copy = copy)
        assertEquals("Solved. Walk there to claim it.", ui.stamp)
        assertTrue(ui.walkThere)
        assertNull(ui.message)
        // Solving again is idempotent (one stamp).
        val again = assertIs<RiddleOutcome.Solved>(answer(st, frog, RiddlePick(placeId = "frog-rock")))
        assertEquals(1, again.state.logbook.entries.size)
        // A pool answer (any Grand Forest trail).
        assertIs<RiddleOutcome.Solved>(answer(PlayState(), bond, RiddlePick(featureIds = listOf("trail-grand-forest-west"))))
    }

    @Test
    fun `list picker offered`() {
        val ui = RiddleLogic.ui(RiddleStage.UNSOLVED, wrong = false, copy = copy)
        assertTrue(ui.listPicker, "PF8-5: a searchable list in place of map tapping")
        assertTrue(ui.mapPicker)
        val options = RiddleLogic.options(
            features = listOf("trail-b" to "Battle Point Loop", "road-northeast-spargur-loop-road" to "Northeast Spargur Loop Road", "trail-a" to "Arrow Point"),
            config = config,
        )
        assertEquals(options.map { it.name }, options.map { it.name }.sortedBy { it.lowercase() }, "alphabetical")
        val frogRock = options.single { it.name == "Frog Rock" }
        assertEquals(RiddlePick(placeId = "frog-rock"), frogRock.pick)
        assertIs<RiddleOutcome.Solved>(answer(PlayState(), frog, frogRock.pick))
        assertEquals(listOf("Frog Rock"), RiddleLogic.filter(options, "frog").map { it.name })
        // Claimed: the line with marks and no answer row.
        val claimed = RiddleLogic.ui(RiddleStage.CLAIMED, wrong = false, copy = copy, claimedMarks = 40)
        assertEquals("Claimed. 40 marks.", claimed.claimed)
        assertTrue(!claimed.answerRow && !claimed.listPicker)
    }
}
