package com.bainbridgetrails.play.config

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** schedule.md §4.3 strictness: unknown keys are ignored, wrong enums and types are errors, schema ≠ 1 is rejected. */
class PlayConfigParseTest {
    private val example = javaClass.getResource("/play/example-pack.json")!!.readText()

    private fun ok(text: String): PlayConfig = when (val r = PlayConfig.decode(text)) {
        is PlayConfigResult.Ok -> r.config
        is PlayConfigResult.Rejected -> fail(r.errors.joinToString("\n"))
    }

    private fun rejected(text: String): List<PlayConfigError> =
        assertIs<PlayConfigResult.Rejected>(PlayConfig.decode(text)).errors

    @Test
    fun v1PackParses() {
        val c = ok(example)
        assertEquals(1, c.schema)
        assertEquals("2026.11.0", c.version)
        assertEquals(16, c.items.size)
        val kraken = c.items.single { it.id == "kraken-roads" }
        assertEquals(ItemType.obstacle, kraken.type)
        assertTrue(kraken.hero)
        assertEquals(6, kraken.where.getValue(Goal.roads).rotate!!.size)
        assertEquals(listOf(DayRule.Weekday(kotlinx.datetime.DayOfWeek.THURSDAY)), kraken.`when`.dayRules())
        assertEquals(listOf(DayRule.MonthDay(30)), c.windows.getValue("exclusion-remembrance").dayRules())
        assertEquals(listOf(Goal.trails, Goal.roads), c.items.single { it.id == "egg-ladybug" }.goals)
        assertEquals(Caps(), c.caps)
        assertEquals(RoadSafety(), c.rules.roadSafety)
        assertEquals(AnchorAt.ferry_depart_bainbridge, c.items.single { it.id == "ferry-sprite" }.`when`.anchor!!.at)
        assertEquals(TierName.glass_float, c.items.first().tiers.last().elseTier)
        // the structural rules hold without a dataset
        val errs = PlayConfigValidator.validate(c, places = null)
        assertTrue(errs.isEmpty(), errs.joinToString("\n"))
    }

    @Test
    fun unknownKeysIgnored() {
        val withExtras = example.replaceFirst("{", """{ "futureTopLevel": {"a": [1, 2]}, "_note": "x", """)
            .replace("\"id\": \"egg-ladybug\",", "\"id\": \"egg-ladybug\", \"sparkle\": 3,")
        assertEquals(ok(example), ok(withExtras))
    }

    @Test
    fun wrongEnumIsAnError() {
        val errs = rejected(example.replace("\"type\": \"egg\"", "\"type\": \"eggs\""))
        assertEquals(setOf(Rule.PARSE), errs.map { it.rule }.toSet())
        assertEquals(Rule.PARSE, rejected(example.replace("\"reducedMotion\": \"static_icon\"", "\"reducedMotion\": \"spin\"")).single().rule)
        assertEquals(Rule.PARSE, rejected(example.replace("\"in\": [\"mar\"]", "\"in\": [\"march\"]")).single().rule)
        // a wrong type is an error too, never coerced to a default
        assertEquals(Rule.PARSE, rejected(example.replace("\"marks\": 20,", "\"marks\": \"twenty\",")).single().rule)
        assertEquals(Rule.PARSE, rejected("not json").single().rule)
    }

    @Test
    fun schema2Rejected() {
        val errs = rejected(example.replaceFirst("\"schema\": 1", "\"schema\": 2"))
        assertEquals(Rule.SCHEMA, errs.single().rule)
        assertEquals(Rule.SCHEMA, rejected("""{"version": "x"}""").single().rule)
        // schema 2 is rejected before its (unknown) shape is decoded
        assertEquals(Rule.SCHEMA, rejected("""{"schema": 2, "items": "a new shape"}""").single().rule)
    }

    @Test
    fun dayRuleTokens() {
        assertEquals(DayRule.NthWeekday(-1, kotlinx.datetime.DayOfWeek.SUNDAY), DayRule.parse(JsonPrimitive("last-sun")))
        assertEquals(DayRule.NthWeekday(3, kotlinx.datetime.DayOfWeek.SATURDAY), DayRule.parse(JsonPrimitive("3sat")))
        assertEquals(DayRule.MonthDay(-1), DayRule.parse(JsonPrimitive(-1)))
        for (bad in listOf(JsonPrimitive("6sat"), JsonPrimitive("someday"), JsonPrimitive(0), JsonPrimitive(32), JsonPrimitive("30"))) {
            assertEquals(null, DayRule.parse(bad), bad.toString())
        }
    }
}
