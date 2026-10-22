package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.config.TestPack.base
import com.bainbridgetrails.play.config.TestPack.index
import com.bainbridgetrails.play.config.TestPack.plus
import com.bainbridgetrails.play.config.TestPack.replace
import com.bainbridgetrails.play.config.TestPack.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * schedule.md §4.3: one failing fixture per validation rule (names as in schedule §5.4). Each fixture must fail with
 * exactly the rule it targets, so a fixture can never pass by tripping some other rule.
 */
class PlayConfigValidatorTest {
    private fun errors(config: PlayConfig, previous: PlayConfig? = null) =
        PlayConfigValidator.validate(config, index, previous)

    private fun assertOnly(rule: Rule, config: PlayConfig, previous: PlayConfig? = null) {
        val errs = errors(config, previous)
        assertTrue(errs.isNotEmpty(), "expected ${rule.id}, got no errors")
        assertEquals(setOf(rule), errs.map { it.rule }.toSet(), errs.joinToString("\n"))
    }

    @Test
    fun baselinePackIsClean() {
        val errs = errors(base)
        assertTrue(errs.isEmpty(), errs.joinToString("\n"))
    }

    // --- §5.4 named fixtures -----------------------------------------------------------------------------------

    @Test
    fun badRef() {
        assertOnly(Rule.BAD_REF, base.replace(TestPack.egg.copy(where = mapOf(Goal.roads to Placement(at = "place:nowhere")))))
        assertOnly(Rule.BAD_REF, base.withPool("coast-k1", "road:road-does-not-exist", goal = Goal.roads))
        assertOnly(Rule.BAD_REF, base.replace(TestPack.challenge.copy(`when` = Recurrence(window = "no-such-window"))))
    }

    @Test
    fun thirtyMphRoad() = assertOnly(Rule.ROAD_SAFETY, base.withPool("coast-k1", "stretch:road-fast-30", goal = Goal.roads))

    @Test
    fun nullSpeedCollector() =
        assertOnly(Rule.ROAD_SAFETY, base.withPool("coast-k1", "road:road-collector-null", goal = Goal.roads))

    @Test
    fun privateRoad() = assertOnly(Rule.ROAD_SAFETY, base.withPool("coast-k1", "road:road-private", goal = Goal.roads))

    @Test
    fun stateRouteAndUncountedRoad() {
        assertOnly(Rule.ROAD_SAFETY, base.withPool("coast-k1", "road:road-state", goal = Goal.roads))
        assertOnly(Rule.ROAD_SAFETY, base.withPool("coast-k1", "road:road-uncounted", goal = Goal.roads))
    }

    @Test
    fun quietZonePoint() = assertOnly(
        Rule.QUIET_ZONE_ANCHOR,
        base.replace(TestPack.egg.copy(where = mapOf(Goal.trails to Placement(at = "place:in-memorial-zone")))),
    )

    @Test
    fun poolMemberTouchingAQuietZone() {
        assertOnly(Rule.QUIET_ZONE_TOUCH, base.withPool("coast-k1", "road:road-taylor", goal = Goal.roads))
        assertOnly(Rule.QUIET_ZONE_TOUCH, base.withPool("west", "trail:trail-blakely-harbor-trail", goal = Goal.trails))
    }

    @Test
    fun pritchardParkMember() {
        assertOnly(Rule.EXCLUDED_PARK, base.withPool("west", "trail:trail-pritchard-far", goal = Goal.trails))
        // the whole park: also touches the memorial zone (its trails do), but the park rule must fire on its own
        val rules = errors(base.withPool("west", "park:Pritchard Park", goal = Goal.trails)).map { it.rule }.toSet()
        assertTrue(Rule.EXCLUDED_PARK in rules, rules.toString())
    }

    @Test
    fun duskOnlyFind() = assertOnly(
        Rule.DAYLIGHT,
        base.replace(TestPack.lore.copy(enabled = false, `when` = Recurrence(every = Every.day, from = "dusk", to = "dusk+30m"))),
    )

    @Test
    fun clockFindOutsideWinterDaylight() = assertOnly(
        Rule.DAYLIGHT,
        base.replace(TestPack.lore.copy(enabled = false, `when` = Recurrence(every = Every.day, from = "07:00", to = "18:00"))),
    )

    @Test
    fun creatureAtSunset() = assertOnly(
        Rule.CREATURE_HOURS,
        base.replace(TestPack.owl.copy(`when` = Recurrence(every = Every.day, from = "sunrise+30m", to = "sunset"))),
    )

    @Test
    fun tideAnchoredObstacle() = assertOnly(
        Rule.TIDE_ANCHORED_OBSTACLE,
        base.replace(
            TestPack.kraken.copy(
                hero = false, // a hero window is also outside the server subset when anchored; isolate the tide rule
                `when` = TestPack.weekly("thu", "06:00", "20:00", 4)
                    .copy(anchor = Anchor(AnchorAt.tide_high, before = "2h", after = "2h", pick = AnchorPick.highest)),
            ),
        ),
    )

    @Test
    fun rewardNotProduct() {
        assertOnly(Rule.REWARD, base.replace(TestPack.egg.copy(marks = 25)))
        assertOnly(Rule.REWARD, base.replace(TestPack.kraken.copy(bands = listOf(TestPack.kraken.bands[0].copy(marks = 65)) + TestPack.kraken.bands.drop(1))))
        assertOnly(Rule.REWARD, base.replace(TestPack.treasure.copy(tiers = listOf(Tier(TierName.sea_glass, 30, 1), Tier(TierName.agate, 40, 1)))))
        assertOnly(Rule.REWARD, base.replace(TestPack.lore.copy(marks = 20)))
        // a remembrance story off the map (like lore-yama): marks are the only fault
        assertOnly(Rule.REWARD, base.replace(TestPack.memorial.copy(marks = 15, where = emptyMap())))
        assertOnly(Rule.REWARD, base.replace(TestPack.challenge.copy(marks = 120)))
        assertOnly(Rule.REWARD, base.replace(TestPack.riddle.copy(stars = 4)))
    }

    @Test
    fun capsNotProduct() = assertOnly(Rule.CAPS, base.copy(caps = Caps(foundPerDay = 500)))

    @Test
    fun inPlaceEditOfAPublishedId() {
        assertOnly(Rule.IMMUTABLE, base.replace(TestPack.egg.copy(marks = 20, count = 2)), previous = base)
        assertTrue(errors(base, previous = base).isEmpty(), "an unchanged pack is not an edit")
        // copy-only edits are allowed
        assertTrue(errors(base.replace(TestPack.egg.copy(copy = Copy("Frog egg, renamed", "New body."))), previous = base).isEmpty())
    }

    @Test
    fun removedIdNeedsExpiresFirst() {
        val without = base.copy(items = base.items.filterNot { it.id == "lore-frog" })
        assertOnly(Rule.REMOVED_WITHOUT_EXPIRES, without, previous = base)
        val expired = base.replace(TestPack.lore.copy(expires = kotlinx.datetime.LocalDate(2027, 2, 1)))
        assertTrue(errors(without, previous = expired).isEmpty())
    }

    @Test
    fun blankA11y() {
        assertOnly(Rule.A11Y, base.replace(TestPack.egg.copy(a11y = A11y("Easter egg", "  "))))
        assertOnly(Rule.A11Y, base.replace(TestPack.egg.copy(a11y = A11y("x".repeat(141), "Found."))))
    }

    @Test
    fun heroConflict() {
        val second = TestPack.kraken.copy(id = "kraken-roads-halloween", priority = 50, `when` = TestPack.weekly("sat", "10:00", "15:00", 4))
        assertOnly(Rule.HERO_CONFLICT, base.plus(second))
        assertTrue(errors(base.plus(second.copy(yields = true))).isEmpty(), "a loser that yields is not a conflict")
        val otherGoal = TestPack.kraken.copy(id = "kraken-trails", goals = listOf(Goal.trails),
            where = mapOf(Goal.trails to Placement(rotate = listOf("pool:west"), epoch = TestPack.kraken.where[Goal.roads]!!.epoch)))
        assertTrue(errors(base.plus(otherGoal)).isEmpty(), "heroes of different goals never conflict")
    }

    @Test
    fun claimableDailyItemRejected() {
        // privacy review 2026-10-20 P2-7: an occurrence key names its start date; the policy says "never the day"
        assertOnly(Rule.CLAIM_SPAN, base.replace(TestPack.lore.copy(`when` = Recurrence(every = Every.day, from = "10:00", to = "14:00"))))
        assertOnly(Rule.CLAIM_SPAN, base.replace(TestPack.treasure.copy(`when` = TestPack.weekly("mon", "05:00", "24:00", 3))))
        assertOnly(Rule.CLAIM_SPAN, base.replace(TestPack.challenge.copy(`when` = TestPack.weekly("sat", "10:00", "15:00", 1))))
        assertOnly(
            Rule.CLAIM_SPAN,
            base.replace(TestPack.egg.copy(notBefore = kotlinx.datetime.LocalDate(2027, 1, 4), expires = kotlinx.datetime.LocalDate(2027, 1, 6))),
        )
        assertEquals(4, PlayConfigValidator.claimSpanDays(base, TestPack.kraken, TestPack.kraken.`when`))
        assertEquals(4, PlayConfigValidator.claimSpanDays(base, TestPack.challenge, TestPack.challenge.`when`), "2 days + 2 grace days")
        // a draft is not claimable
        val draft = TestPack.lore.copy(enabled = false, `when` = Recurrence(every = Every.day, from = "10:00", to = "14:00"))
        assertTrue(errors(base.replace(draft)).none { it.rule == Rule.CLAIM_SPAN })
    }

    // --- the rest of §4.3 --------------------------------------------------------------------------------------

    @Test
    fun schemaAndTz() {
        assertOnly(Rule.SCHEMA, base.copy(schema = 2))
        assertOnly(Rule.TZ, base.copy(tz = "UTC"))
    }

    @Test
    fun idsWellFormedAndUnique() {
        assertOnly(Rule.ID_FORMAT, base.copy(items = base.items.map { if (it.id == "egg-frog") it.copy(id = "Egg_Frog") else it }))
        assertOnly(Rule.DUPLICATE_ID, base.plus(TestPack.egg))
    }

    @Test
    fun unknownFx() = assertOnly(Rule.UNKNOWN_FX, base.replace(TestPack.lore.copy(fx = "frog_hop_v9")))

    @Test
    fun copyVocabulary() {
        assertOnly(Rule.COPY, base.replace(TestPack.lore.copy(copy = Copy("Frog Rock", "Run to the rock!"))))
        assertOnly(Rule.COPY, base.replace(TestPack.lore.copy(copy = Copy("Frog Rock", "Earn points here."))))
        assertTrue(errors(base.replace(TestPack.lore.copy(copy = Copy("Frog Rock", "A rock by the running brook.")))).isEmpty())
    }

    @Test
    fun countRange() = assertOnly(Rule.COUNT, base.replace(TestPack.egg.copy(count = 21)))

    @Test
    fun missingWhere() = assertOnly(Rule.MISSING_WHERE, base.replace(TestPack.egg.copy(where = emptyMap())))

    @Test
    fun goalMismatch() =
        assertOnly(Rule.GOAL_MISMATCH, base.replace(TestPack.treasure.copy(where = mapOf(Goal.roads to Placement(pick = 2, from = "pool:west")))))

    @Test
    fun serverSubsetNeedsClockTimes() = assertOnly(
        Rule.SERVER_SUBSET,
        base.replace(TestPack.challenge.copy(`when` = TestPack.weekly("sat", "sunrise", "24:00", 2))),
    )

    @Test
    fun badTimeExprAndRecurrence() {
        assertOnly(Rule.TIME_EXPR, base.replace(TestPack.lore.copy(enabled = false, `when` = Recurrence(every = Every.day, from = "9am", to = "15:00"))))
        assertOnly(Rule.RECURRENCE, base.replace(TestPack.challenge.copy(`when` = TestPack.weekly("someday", "00:00", "24:00", 2))))
    }

    @Test
    fun emptyWindow() = assertOnly(
        Rule.EMPTY_WINDOW,
        base.replace(TestPack.lore.copy(enabled = false, `when` = Recurrence(every = Every.day, from = "12:00", to = "11:00"))),
    )

    @Test
    fun ambientHours() = assertOnly(
        Rule.AMBIENT_HOURS,
        base.replace(TestPack.ferry.copy(`when` = TestPack.ferry.`when`.copy(from = "04:00"))),
    )

    @Test
    fun roadSafetyRuleCannotBeRelaxed() =
        assertOnly(Rule.ROAD_SAFETY, base.copy(rules = Rules(roadSafety = RoadSafety(maxSpeedMph = 30))))

    @Test
    fun noDatasetSkipsDatasetRules() {
        val cfg = base.withPool("coast-k1", "road:road-fast-30", goal = Goal.roads)
        assertTrue(PlayConfigValidator.validate(cfg, places = null).isEmpty(), "dataset refs skipped without an index")
    }

    // --- Play trails v1.1 (plan L1-1, D5): climb / parks are solo, trails-only, whole island days ----------------

    private val climb = TestPack.challenge.copy(
        id = "ch-climb", goals = listOf(Goal.trails), `when` = TestPack.weekly("mon", "00:00", "24:00", 7),
        challenge = ChallengeSpec(ChallengeScope.solo, ChallengeMetric.climb, target = mapOf(Goal.trails to 150)),
        copy = Copy("Uphill week", "Finish trails that climb {target} between them this week."), a11y = A11y("Uphill week", "Uphill week."),
    )
    private val parkhop = TestPack.challenge.copy(
        id = "ch-parkhop", goals = listOf(Goal.trails), `when` = TestPack.weekly("sat", "00:00", "24:00", 2),
        challenge = ChallengeSpec(ChallengeScope.solo, ChallengeMetric.parks, target = mapOf(Goal.trails to 3)),
        copy = Copy("Park hop", "Finish a trail in different parks this weekend. Each park counts once."), a11y = A11y("Park hop", "Park hop."),
    )

    @Test
    fun `climb and parks challenges are valid on trails`() {
        val errs = errors(base.plus(climb).plus(parkhop))
        assertTrue(errs.isEmpty(), errs.joinToString("\n"))
    }

    @Test
    fun `climb on roads rejected`() {
        assertOnly(Rule.GOAL_MISMATCH, base.plus(climb.copy(goals = listOf(Goal.trails, Goal.roads))))
        assertOnly(Rule.GOAL_MISMATCH, base.plus(parkhop.copy(goals = listOf(Goal.roads))))
        // never an island challenge
        assertOnly(Rule.PLACEMENT_SHAPE, base.plus(climb.copy(challenge = climb.challenge!!.copy(scope = ChallengeScope.island), marks = 75)))
    }

    @Test
    fun `parks window not whole days rejected`() {
        assertOnly(Rule.SERVER_SUBSET, base.plus(parkhop.copy(`when` = TestPack.weekly("sat", "10:00", "24:00", 2))))
        assertOnly(Rule.SERVER_SUBSET, base.plus(climb.copy(`when` = TestPack.weekly("mon", "00:00", "20:00", 7))))
    }

    @Test
    fun `climb every always rejected`() {
        // review P2-B: one never-ending occurrence; the phone and the Worker claim per occurrence, so climb / parks recur
        val errs = errors(base.plus(climb.copy(`when` = Recurrence(every = Every.always))))
        assertEquals(listOf(Rule.SERVER_SUBSET), errs.map { it.rule }.distinct(), errs.joinToString("\n"))
        assertTrue(errs.any { "a climb challenge recurs (day, week, month, year or dates), never every: always" in it.message }, errs.joinToString("\n"))
    }

    @Test
    fun `parks days over period rejected`() {
        // review P2-B: `every: day, days: 7` → 7 live occurrences, each claimable on the phone, the Worker credits one
        val daily = Recurrence(every = Every.day, from = "00:00", to = "24:00", days = 7)
        val errs = errors(base.plus(parkhop.copy(`when` = daily)))
        assertEquals(listOf(Rule.SERVER_SUBSET), errs.map { it.rule }.distinct(), errs.joinToString("\n"))
        assertTrue(errs.any { "a parks challenge's occurrences overlap: days 7 > the 1-day gap between starts (days ≤ the recurrence period)" in it.message }, errs.joinToString("\n"))
        // two starts a week, two days each: Sat → Sun overlaps; a week's single start with days 7 is fine (ch-climb)
        assertOnly(Rule.SERVER_SUBSET, base.plus(parkhop.copy(`when` = TestPack.weekly("sat", "00:00", "24:00", 2).copy(on = TestPack.on("sat", "sun")))))
        assertOnly(Rule.SERVER_SUBSET, base.plus(climb.copy(`when` = TestPack.weekly("mon", "00:00", "24:00", 8))))
        assertTrue(errors(base.plus(parkhop.copy(`when` = daily.copy(days = 1)))).none { it.rule == Rule.SERVER_SUBSET })
    }

    @Test
    fun `roads target on climb rejected`() {
        assertOnly(Rule.GOAL_MISMATCH, base.plus(climb.copy(challenge = climb.challenge!!.copy(target = mapOf(Goal.trails to 150, Goal.roads to 100)))))
        assertOnly(Rule.GOAL_MISMATCH, base.plus(climb.copy(challenge = climb.challenge!!.copy(target = null))))
        assertOnly(Rule.REWARD, base.plus(parkhop.copy(challenge = parkhop.challenge!!.copy(target = mapOf(Goal.trails to 0)))))
    }
}
