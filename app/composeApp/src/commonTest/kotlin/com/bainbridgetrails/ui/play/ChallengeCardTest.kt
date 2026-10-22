package com.bainbridgetrails.ui.play

import com.bainbridgetrails.net.PlayBandCountDto
import com.bainbridgetrails.net.PlayBandDto
import com.bainbridgetrails.net.PlayChallengeDto
import com.bainbridgetrails.net.PlayChallengesDto
import com.bainbridgetrails.net.PlayIslandProgressDto
import com.bainbridgetrails.play.PlayFixtures
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import kotlinx.datetime.LocalDate
import com.bainbridgetrails.play.schedule.PlayContext
import com.bainbridgetrails.play.schedule.PlayScheduler
import com.bainbridgetrails.ui.play.screens.ChallengeBand
import com.bainbridgetrails.ui.play.screens.ChallengeCopy
import com.bainbridgetrails.ui.play.screens.ChallengeState
import com.bainbridgetrails.ui.play.screens.ChallengesLogic
import com.bainbridgetrails.ui.play.screens.GoalNouns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ChallengeCard + Challenges screen (ux.md §2.5, §3.16; product §4.5, PF9-4, PD-4). */
class ChallengeCardTest {
    private val config = (PlayConfig.decode(PlayFixtures.pack) as PlayConfigResult.Ok).config
    private val scheduler = PlayScheduler(config, PlayFixtures.EmptyPlaces, PlayFixtures.tides, null)

    /** The English strings (values/strings.xml), so the text checks read what the screen shows. */
    private val copy = ChallengeCopy(
        window = "%1\$s – %2\$s",
        progress = "%1\$s of %2\$s",
        islandProgress = "%1\$d of %2\$d stretches sent back by islanders",
        band = "You were in the %1\$s band, with %2\$d others",
        bandTop = "top",
        bandMiddle = "middle",
        bandStarting = "starting",
    )

    /** Sat Oct 31 10:00 island time: the weekend sweep is open, the kraken week too (Thu 06:00 → Sun 20:00). */
    private val now = PlayFixtures.island("2026-10-31T10:00")
    private val agenda = scheduler.agenda(now, PlayContext(Goal.trails))

    private fun server(you: String?, bands: List<PlayBandCountDto>) = PlayChallengesDto(
        dataset = "trails",
        current = listOf(
            PlayChallengeDto(
                id = "ch-sweep", occ = "ch-sweep@2026-10-31", scope = "solo",
                windowStart = "2026-10-31T00:00-07:00", windowEnd = "2026-11-02T00:00-08:00", progress = 1, target = 3,
            ),
            PlayChallengeDto(
                id = "ch-krakenweek", occ = "ch-krakenweek@2026-10-29", scope = "island",
                windowStart = "2026-10-29T06:00-07:00", windowEnd = "2026-11-01T20:00-08:00", target = 2,
                you = you?.let { PlayBandDto(it) }, bands = bands, island = PlayIslandProgressDto(stretches = 2, repelled = 1),
            ),
        ),
    )

    /** `play_goal_item(s)` (trails build). */
    private val nouns = GoalNouns(PlayStringsXml["play_goal_item"], PlayStringsXml["play_goal_items"])

    private val bands = listOf(PlayBandCountDto("top", 12), PlayBandCountDto("middle", null), PlayBandCountDto("starting", 40))

    @Test
    fun noCountdownText() {
        val m = ChallengesLogic.model(config, Goal.trails, agenda, server("top", bands), emptyList(), now, nouns)
        val cards = m.solo + m.island
        assertEquals(setOf("ch-sweep", "ch-climb", "ch-parkhop", "ch-krakenweek"), cards.map { it.itemId }.toSet()) // pack 2026.11.3 (L4-2)
        val sweep = cards.single { it.itemId == "ch-sweep" }
        val texts = cards.flatMap { c -> ChallengesLogic.text(c, copy).let { listOfNotNull(it.window, it.progress, it.band) } + c.title + c.body }
        // Windows are days in island time (PP-8, PD-4): never a clock, a duration or "left".
        assertEquals("Sat – Sun", ChallengesLogic.text(sweep, copy).window)
        assertEquals("Thu – Sun", ChallengesLogic.text(cards.single { it.itemId == "ch-krakenweek" }, copy).window)
        val countdown = Regex("""\d{1,2}:\d{2}|\b\d+\s*(h|hr|hrs|hours?|m|min|mins|minutes?|d|days?)\b|\bleft\b|\bremaining\b|ends in|countdown""", RegexOption.IGNORE_CASE)
        for (t in texts) assertFalse(countdown.containsMatchIn(t), "countdown text in \"$t\"")
        // Progress as text; solo progress from the server when known.
        assertEquals("1 of 3", ChallengesLogic.text(sweep, copy).progress)
        assertEquals(ChallengeState.Active, sweep.state)
        assertEquals(100, sweep.reward)
    }

    @Test
    fun islandShowsBandAndCountOnly() {
        val m = ChallengesLogic.model(config, Goal.trails, agenda, server("top", bands), emptyList(), now, nouns)
        val kraken = m.island.single()
        assertEquals("ch-krakenweek", kraken.itemId)
        assertTrue(kraken.islandScope)
        assertEquals(ChallengeBand("top", 12), kraken.band, "your band and its count, nothing else (PF9-4)")
        val t = ChallengesLogic.text(kraken, copy)
        assertEquals("1 of 2 stretches sent back by islanders", t.progress, "the shared progress")
        assertEquals("You were in the top band, with 12 others", t.band)
        // The band line carries exactly two facts: a band word and a count (no names, ranks or times).
        assertEquals("ChallengeBand(band=top, count=12)", ChallengeBand("top", 12).toString())
        // Counts under 5 come back null: the line is left out (ux §7.2 #2).
        val few = ChallengesLogic.model(config, Goal.trails, agenda, server("middle", bands), emptyList(), now, nouns).island.single()
        assertNull(few.band)
        assertNull(ChallengesLogic.text(few, copy).band)
        // Signed out / offline: no band, progress row omitted, the card still shows.
        val offline = ChallengesLogic.model(config, Goal.trails, agenda, null, emptyList(), now, nouns).island.single()
        assertNull(offline.band)
        assertNull(ChallengesLogic.text(offline, copy).progress)
        assertNotNull(offline.title)
    }

    @Test
    fun soloProgressFallsBackToTheLogbook() {
        val logged = listOf(
            LogEntry("a", LogKind.complete, "Rose Loop", "Trail complete", LocalDate(2026, 10, 31), 100, walkId = "w1"),
            LogEntry("b", LogKind.complete, "Gazzam", "Trail complete", LocalDate(2026, 10, 30), 100, walkId = "w0"),
        )
        val sweep = ChallengesLogic.model(config, Goal.trails, agenda, null, logged, now, nouns).solo.single { it.itemId == "ch-sweep" }
        assertEquals(1, sweep.progress, "only completions inside the window count (PF9-2)")
        assertEquals(3, sweep.target)
    }

    /**
     * Goal vocabulary (review L2-7): the pack's `{goal.items}` comes from `play_goal_items` and its roads twin, never a
     * word hard-coded in the logic, so a roads build never says "trails".
     */
    @Test
    fun goalNounFromResources() {
        val roads = GoalNouns(PlayStringsXml["roads_play_goal_item"], PlayStringsXml["roads_play_goal_items"])
        assertEquals(GoalNouns("trail", "trails"), nouns)
        assertEquals(GoalNouns("road", "roads"), roads)
        assertEquals("Finish 5 roads; one road at a time.", roads.fill("Finish 5 {goal.items}; one {goal.item} at a time."))

        val roadsNow = scheduler.agenda(now, PlayContext(Goal.roads))
        val sweep = ChallengesLogic.model(config, Goal.roads, roadsNow, null, emptyList(), now, roads).solo.single { it.itemId == "ch-sweep" }
        assertTrue("roads" in sweep.body && "trail" !in sweep.body, sweep.body)
        assertTrue("{goal" !in sweep.body, "every token filled: ${sweep.body}")
        val trails = ChallengesLogic.model(config, Goal.trails, agenda, null, emptyList(), now, nouns).solo.single { it.itemId == "ch-sweep" }
        assertTrue("trails" in trails.body, trails.body)
    }
}
