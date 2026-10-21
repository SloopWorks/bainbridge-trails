package com.bainbridgetrails.ui.play.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ux.md § 5.3: the Play row's chips go compact at font scale ≥ 1.5 (and the row wraps); below that they are full. */
class PlayChipRowTest {
    @Test
    fun compactFromOnePointFive() {
        for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.49f)) assertFalse(PlayChipRow.compact(scale), "full chips at $scale")
        for (scale in listOf(1.5f, 1.8f, 2f)) assertTrue(PlayChipRow.compact(scale), "compact chips at $scale")
    }
}
