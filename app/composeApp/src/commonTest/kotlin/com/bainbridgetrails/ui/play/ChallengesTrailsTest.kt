package com.bainbridgetrails.ui.play

import com.bainbridgetrails.data.Units
import com.bainbridgetrails.net.PlayChallengeDto
import com.bainbridgetrails.net.PlayChallengesDto
import com.bainbridgetrails.play.PlayFixtures
import com.bainbridgetrails.play.TrailChallengeStatus
import com.bainbridgetrails.play.config.ChallengeMetric
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.rules.BoardState
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Logbook
import com.bainbridgetrails.play.schedule.PlayContext
import com.bainbridgetrails.play.schedule.PlayScheduler
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.play.map.ChallengesChipLogic
import com.bainbridgetrails.ui.play.map.PlayMarkerLogic
import com.bainbridgetrails.ui.play.screens.ChallengeCardModel
import com.bainbridgetrails.ui.play.screens.ChallengeCopy
import com.bainbridgetrails.ui.play.screens.ChallengeState
import com.bainbridgetrails.ui.play.screens.ChallengesLogic
import com.bainbridgetrails.ui.play.screens.GoalNouns
import com.bainbridgetrails.ui.play.screens.WhatsOnLogic
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ch-climb` / `ch-parkhop` cards (docs/play/trails/ux.md § 7, UX-6; plan D5): progress text in both units with the
 * § 1 clamp, `{target}` through `TrailRewardFormat`, the parks plural on the target, and a status with nothing left
 * (target 0) absent from the Challenges screen, the ChallengesChip count and What's On. Roads has neither.
 */
class ChallengesTrailsTest {
    private val config = (PlayConfig.decode(PlayFixtures.pack) as PlayConfigResult.Ok).config
    private val scheduler = PlayScheduler(config, PlayFixtures.EmptyPlaces, PlayFixtures.tides, null)

    /** Sat Oct 31 10:00 island time: `ch-climb@2026-10-26` (Mon → Mon) and `ch-parkhop@2026-10-31` (Sat → Mon) open. */
    private val now = PlayFixtures.island("2026-10-31T10:00")
    private val agenda = scheduler.agenda(now, PlayContext(Goal.trails))
    private val climbOcc = agenda.active.single { it.occurrence.itemId == "ch-climb" }.occurrence.key
    private val parksOcc = agenda.active.single { it.occurrence.itemId == "ch-parkhop" }.occurrence.key
    private val nouns = GoalNouns(PlayStringsXml["play_goal_item"], PlayStringsXml["play_goal_items"])

    /** The English strings, as `rememberChallengeCopy` resolves them (parks: the plural form for [parksTarget]). */
    private fun copy(parksTarget: Int = 3) = ChallengeCopy(
        window = PlayStringsXml["play_challenge_window"],
        progress = PlayStringsXml["play_challenge_progress"],
        islandProgress = PlayStringsXml["play_kraken_island_progress"],
        band = PlayStringsXml["play_challenge_band"],
        bandTop = "top",
        bandMiddle = "middle",
        bandStarting = "starting",
        progressClimb = PlayStringsXml["play_challenge_progress_climb"],
        progressParks = PlayStringsXml.pluralForm("play_challenge_progress_parks", parksTarget),
    )

    private fun cards(trail: Map<String, TrailChallengeStatus>, units: Units = Units.MILES, goal: Goal = Goal.trails): List<ChallengeCardModel> {
        val a = if (goal == Goal.trails) agenda else scheduler.agenda(now, PlayContext(goal))
        val m = ChallengesLogic.model(config, goal, a, null, emptyList(), now, if (goal == Goal.trails) nouns else GoalNouns("road", "roads"), trail, units)
        return m.solo + m.island + m.ended
    }

    private fun climb(progressDm: Int, targetDm: Int) = mapOf(climbOcc to TrailChallengeStatus(ChallengeMetric.climb, progressDm, targetDm))

    @Test
    fun `"95 of 150 m climbed"`() {
        val card = cards(climb(950, 1_500), Units.KILOMETRES).single { it.itemId == "ch-climb" }
        assertEquals("95 of 150 m climbed", ChallengesLogic.text(card, copy()).progress)
        assertEquals("Finish trails that climb 150 m between them this week.", card.body, "{target} through TrailRewardFormat, nearest, with unit")
        assertEquals(ChallengeState.Active, card.state)
        // 149.9 of 150 m stays below the target as shown; 171.3 m clamps to it once done.
        assertEquals("149 of 150 m climbed", ChallengesLogic.text(cards(climb(1_499, 1_500), Units.KILOMETRES).single { it.itemId == "ch-climb" }, copy()).progress)
        val done = cards(climb(1_713, 1_500), Units.KILOMETRES).single { it.itemId == "ch-climb" }
        assertEquals(ChallengeState.Done, done.state)
        assertEquals("150 of 150 m climbed", ChallengesLogic.text(done, copy()).progress)
        // A reduced personal target shows with no extra copy (120 m left → 120 m).
        assertEquals("Finish trails that climb 120 m between them this week.", cards(climb(0, 1_200), Units.KILOMETRES).single { it.itemId == "ch-climb" }.body)
    }

    @Test
    fun `"311 of 492 ft climbed"`() {
        val card = cards(climb(950, 1_500)).single { it.itemId == "ch-climb" }
        val text = ChallengesLogic.text(card, copy(), spokenTarget = PlayFormat.fill(PlayStringsXml.pluralForm("play_unit_feet_a11y", 2), "492"))
        assertEquals("311 of 492 ft climbed", text.progress)
        assertEquals("311 of 492 feet climbed", text.progressSpoken, "spoken units (ux § 7 card semantics)")
        assertEquals("Finish trails that climb 492 ft between them this week.", card.body)
        // Never "492 of 492 ft" on an unfinished challenge (ux § 1 clamp).
        assertEquals("491 of 492 ft climbed", ChallengesLogic.text(cards(climb(1_499, 1_500)).single { it.itemId == "ch-climb" }, copy()).progress)
        // No status yet (tables loading): the pack's default target, no local progress line.
        val unknown = cards(emptyMap()).single { it.itemId == "ch-climb" }
        assertEquals("Finish trails that climb 492 ft between them this week.", unknown.body)
        assertNull(ChallengesLogic.text(unknown, copy()).progress)
    }

    @Test
    fun `"2 of 3 parks"`() {
        val card = cards(mapOf(parksOcc to TrailChallengeStatus(ChallengeMetric.parks, 2, 3))).single { it.itemId == "ch-parkhop" }
        assertEquals("2 of 3 parks", ChallengesLogic.text(card, copy(card.target!!)).progress)
        assertEquals("Finish a trail in different parks this weekend. Each park counts once.", card.body, "no number in the parks body")
        // Plural on the target: a reduced target of 1 reads "0 of 1 park"; done clamps at the target.
        val one = cards(mapOf(parksOcc to TrailChallengeStatus(ChallengeMetric.parks, 0, 1))).single { it.itemId == "ch-parkhop" }
        assertEquals("0 of 1 park", ChallengesLogic.text(one, copy(one.target!!)).progress)
        val done = cards(mapOf(parksOcc to TrailChallengeStatus(ChallengeMetric.parks, 4, 3))).single { it.itemId == "ch-parkhop" }
        assertEquals(ChallengeState.Done, done.state)
        assertEquals("3 of 3 parks", ChallengesLogic.text(done, copy(3)).progress)
    }

    @Test
    fun `remaining 0 → absent from screen, chip count, What's On`() {
        val hidden = mapOf(
            climbOcc to TrailChallengeStatus(ChallengeMetric.climb, 0, 0),
            parksOcc to TrailChallengeStatus(ChallengeMetric.parks, 0, 0),
        )
        assertTrue(hidden.values.all { it.hidden })
        val ids = cards(hidden).map { it.itemId }
        assertTrue("ch-climb" !in ids && "ch-parkhop" !in ids, "$ids")
        assertTrue("ch-sweep" in ids, "other challenges stay")
        // Chip: the agenda's count minus the hidden ones; nothing hidden = the v1 count.
        val all = PlayMarkerLogic.challengesThisWeek(agenda)
        assertEquals(all, ChallengesChipLogic.count(agenda, emptyMap()))
        assertEquals(all - 2, ChallengesChipLogic.count(agenda, hidden))
        assertEquals(all - 1, ChallengesChipLogic.count(agenda, hidden - parksOcc))
        // What's On: no row for a hidden occurrence.
        val rows = WhatsOnLogic.model(agenda, config, Goal.trails, now, hidden).today.map { it.itemId }
        assertTrue("ch-climb" !in rows && "ch-parkhop" !in rows, "$rows")
        assertTrue("ch-climb" in WhatsOnLogic.model(agenda, config, Goal.trails, now).today.map { it.itemId })
    }

    @Test
    fun `chip is held until the trail statuses are read`() {
        // Trails ux § 7 / UI review P2-2: before the first read (null) a hidden ch-climb / ch-parkhop would be counted
        // and then dropped, so the chip shows nothing until the statuses arrive.
        assertEquals(0, ChallengesChipLogic.count(agenda, null))
        assertTrue(ChallengesChipLogic.count(agenda, emptyMap()) > 0)
    }

    @Test
    fun `statuses re-read on a new complete entry, not on every find`() {
        // UI review P3-9: rememberTrailChallenges is keyed on the Logbook's `complete` entries (what P reads), so a find
        // or a board change does not re-read coverage, and a newly completed trail still does.
        val d = LocalDate(2026, 10, 21)
        fun e(id: String, kind: LogKind, content: String?, board: BoardState = BoardState.pending) =
            LogEntry(id, kind, "w", "y", d, 10, contentId = content, board = board)
        val base = Logbook(listOf(e("a", LogKind.complete, "t1"), e("b", LogKind.coverage, null)))
        val key = ChallengesLogic.completionKey(base)
        assertEquals(key, ChallengesLogic.completionKey(Logbook(base.entries + e("c", LogKind.treasure, "occ-1"))))
        assertEquals(key, ChallengesLogic.completionKey(Logbook(base.entries.map { it.copy(board = BoardState.accepted) })))
        assertNotEquals(key, ChallengesLogic.completionKey(Logbook(base.entries + e("d", LogKind.complete, "t2"))))
        assertNull(ChallengesLogic.completionKey(null))
    }

    @Test
    fun `upcoming trail challenges are statused too`() {
        // Thu Oct 29: ch-parkhop (Sat → Mon) is upcoming; the status occurrences cover it so "nothing left" hides it.
        val thu = PlayFixtures.island("2026-10-29T14:00")
        val a = scheduler.agenda(thu, PlayContext(Goal.trails))
        val occs = ChallengesLogic.trailOccurrences(a)
        val upcoming = a.upcoming.single { it.itemId == "ch-parkhop" }
        val occ = occs.single { it.itemId == "ch-parkhop" }
        assertEquals(upcoming.key to (upcoming.start to upcoming.end), occ.key to (occ.start to occ.end))
        assertTrue(occs.all { it.type == ItemType.challenge })
        val hidden = mapOf(upcoming.key to TrailChallengeStatus(ChallengeMetric.parks, 0, 0))
        val m = ChallengesLogic.model(config, Goal.trails, a, null, emptyList(), thu, nouns, hidden)
        assertTrue((m.solo + m.island).none { it.itemId == "ch-parkhop" })
        assertTrue(WhatsOnLogic.model(a, config, Goal.trails, thu, hidden).coming.none { it.itemId == "ch-parkhop" })
        assertTrue(WhatsOnLogic.model(a, config, Goal.trails, thu).coming.any { it.itemId == "ch-parkhop" })
    }

    @Test
    fun `server progress never pairs with the local target`() {
        // Final review P2-1: a late-synced walk across a window boundary leaves the server's progress above the local
        // personal target; the card takes progress and target from one side (server pair, else local, else pack).
        fun card(dto: PlayChallengeDto, status: Map<String, TrailChallengeStatus>): ChallengeCardModel {
            val m = ChallengesLogic.model(config, Goal.trails, agenda, PlayChallengesDto(current = listOf(dto)), emptyList(), now, nouns, status, Units.KILOMETRES)
            return (m.solo + m.island + m.ended).single { it.itemId == "ch-climb" }
        }
        val dto = PlayChallengeDto(id = "ch-climb", occ = climbOcc, progress = 1_200, target = 1_500)
        val both = card(dto, climb(0, 1_000))
        assertEquals(1_200 to 1_500, both.progress to both.target, "the server's pair")
        assertEquals(ChallengeState.Active, both.state, "1,200 of the server's 1,500 dm: not done, whatever the local target")
        // No server progress (signed out): the local pair, never the server's target with local progress.
        val local = card(dto.copy(progress = null), climb(950, 1_000))
        assertEquals(950 to 1_000, local.progress to local.target)
        // Server progress without a server target: the local pair, so 1,200 never meets the local 1,000.
        val noTarget = card(dto.copy(target = 0), climb(950, 1_000))
        assertEquals(950 to 1_000, noTarget.progress to noTarget.target)
        assertEquals(ChallengeState.Active, noTarget.state)
        // Neither side: no progress, the pack default target.
        val none = card(dto.copy(progress = null, target = 0), emptyMap())
        assertNull(none.progress)
        assertEquals(cards(emptyMap()).single { it.itemId == "ch-climb" }.target, none.target)
    }

    @Test
    fun `found occurrence shows done with no in-window completions`() {
        // Final review P2-2: after a reinstall restore a claimed occurrence is in `found` while the Logbook has no
        // completion inside the window; its card shows Done (the found guard already blocks a second claim).
        val sweepOcc = agenda.active.single { it.occurrence.itemId == "ch-sweep" }.occurrence.key
        fun solo(found: Set<String>) = ChallengesLogic.model(config, Goal.trails, agenda, null, emptyList(), now, nouns, climb(0, 1_500), found = found).solo
        val before = solo(emptySet())
        assertEquals(ChallengeState.Active, before.single { it.itemId == "ch-sweep" }.state)
        assertEquals(ChallengeState.Active, before.single { it.itemId == "ch-climb" }.state)
        val after = solo(setOf(sweepOcc, climbOcc))
        assertEquals(ChallengeState.Done, after.single { it.itemId == "ch-sweep" }.state)
        assertEquals(ChallengeState.Done, after.single { it.itemId == "ch-climb" }.state)
        assertEquals(ChallengeState.Active, after.single { it.itemId == "ch-parkhop" }.state, "only the found occurrences")
    }

    @Test
    fun `roads model has neither`() {
        val roads = scheduler.agenda(now, PlayContext(Goal.roads))
        val ids = cards(emptyMap(), goal = Goal.roads).map { it.itemId }
        assertTrue("ch-climb" !in ids && "ch-parkhop" !in ids, "$ids")
        assertTrue(WhatsOnLogic.model(roads, config, Goal.roads, now).let { m -> m.today.map { it.itemId } + m.coming.map { it.itemId } }.none { it == "ch-climb" || it == "ch-parkhop" })
        assertTrue(ChallengesLogic.trailOccurrences(roads).none { it.itemId == "ch-climb" || it.itemId == "ch-parkhop" })
        assertEquals(PlayMarkerLogic.challengesThisWeek(roads), ChallengesChipLogic.count(roads, emptyMap()))
        // Roads cards never carry a climb value.
        assertTrue(cards(emptyMap(), goal = Goal.roads).all { it.climbTarget == null && it.climbProgress == null })
    }
}
