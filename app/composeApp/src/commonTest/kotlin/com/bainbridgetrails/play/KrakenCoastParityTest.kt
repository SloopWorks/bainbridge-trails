package com.bainbridgetrails.play

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Branch review round 1 P1-8: the app's kraken keys (`#n` = position in the pack's coast pool, `PlayScheduler`) and the
 * Worker's (`rotationStretches` → `DERIVED[dataset].coasts[pool]`) index the same list: the pack pool's stretch order
 * equals the derived coasts table of each goal (both bundled copies come from `scripts/build-play-content.mjs`).
 */
class KrakenCoastParityTest {
    @Test
    fun coastPoolsMatchTheDerivedCoastsInOrder() {
        val pools = Json.parseToJsonElement(PlayFixtures.pack).jsonObject["pools"]!!.jsonObject
        var checked = 0
        for (goal in listOf("trails", "roads")) {
            val coasts = Json.parseToJsonElement(PlayFixtures.read("derived-$goal.json")).jsonObject["coasts"]!!.jsonObject
            assertTrue(coasts.isNotEmpty(), "derived-$goal.json has coasts")
            for ((id, coast) in coasts) {
                val derived = coast.jsonObject["stretches"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                val pack = pools[id]?.jsonObject?.get("members")?.jsonArray?.map { it.jsonPrimitive.content.removePrefix("stretch:") }
                assertEquals(derived, pack, "$goal $id: pack pool order = derived coasts order")
                checked++
            }
        }
        assertTrue(checked >= 9, "every coast checked ($checked)")
    }
}
