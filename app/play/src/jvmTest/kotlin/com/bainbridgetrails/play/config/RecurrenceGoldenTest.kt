package com.bainbridgetrails.play.config

import com.bainbridgetrails.play.schedule.RealPack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * schedule.md §4.4 server subset: the Kotlin recurrence reproduces `data/play/golden/date-rules-2027.json` byte for
 * byte, the file the Worker's `test/play-recurrence.test.ts` also reproduces (backend/src/play/recurrence.ts).
 * `-Pplay.updateGolden=true` rewrites it (then the Worker test must still pass: the two implementations agree).
 */
class RecurrenceGoldenTest {
    private val golden = File(RealPack.ROOT, "data/play/golden/date-rules-2027.json")

    @Test
    fun `reproduces date-rules-2027 golden`() {
        val text = Recurrences.dateRulesGolden(RealPack.config, 2027)
        if (RealPack.update) golden.writeText(text)
        assertTrue(golden.exists(), "${golden.path} missing")
        assertEquals(golden.readText(), text)
    }

    @Test
    fun `52 occurrences per weekly server item and none for anchored or permanent items`() {
        val text = Recurrences.dateRulesGolden(RealPack.config, 2027)
        val occs = Regex("\"occ\": \"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
        val perItem = occs.groupingBy { it.substringBefore('@') }.eachCount()
        assertEquals(
            mapOf(
                "ch-climb" to 52, "ch-krakenweek" to 52, "ch-parkhop" to 52, "ch-sweep" to 52, "kraken-roads" to 52,
                "kraken-trails" to 52, "lore-kraken" to 52, "treasure-weekly-roads" to 52, "treasure-weekly-trails" to 52,
            ),
            perItem,
        )
        assertEquals(occs.sorted(), occs)
    }
}
