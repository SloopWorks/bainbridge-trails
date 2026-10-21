package com.bainbridgetrails.play.fx.showcase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FxShowcasePresetTest {
    /** effects.md §7: the v1 effect ids (AC 7). */
    private val v1 = listOf(
        "treasure_glint", "exploration_fog", "riddle_marker", "kraken_lurk", "ferry_crossing", "frog_rock",
        "kraken_attack", "kraken_recede", "treasure_found", "riddle_light", "claim_pop", "play_wake",
    )

    @Test
    fun `every v1 effect id has a preset`() {
        val missing = v1.filter { FxPresets.forEffect(it).isEmpty() }
        assertTrue(missing.isEmpty(), "no showcase preset for $missing")
        assertEquals(v1.toSet(), FxPresets.effects.toSet(), "the showcase hosts exactly the v1 effects")
    }

    @Test
    fun `plan §2 preset ids`() {
        val ids = FxPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "preset ids are unique")
        val expected = mapOf(
            "kraken_attack" to listOf("manitou", "rockaway"),
            "kraken_recede" to listOf("manitou-repelled"),
            "treasure_found" to listOf("fay-found"),
            "riddle_light" to listOf("winslow-solve", "winslow-dark-3lit"),
            "claim_pop" to listOf("map", "results"),
            "play_wake" to listOf("island-home"),
            "frog_rock" to listOf("frog-z17"),
        )
        for ((effect, presets) in expected) assertEquals(presets, FxPresets.forEffect(effect).map { it.id }, effect)
        assertTrue(FxPresets.all.all { it.states.isNotEmpty() })
        assertEquals("engaged", FxPresets.byId("frog-z17")!!.states.first(), "frog preset hops at t=0")
    }
}
