package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Caps

/** A find's marks after the daily cap; [capped] = logged as found with 0 marks and `play_log_cap`. */
data class Capped(val marks: Int, val capped: Boolean)

/**
 * The client half of product §3.5 (the only caps table): found marks (F-*, C-*) per island day are capped at
 * `caps.foundPerDay` (400). A find that would cross the cap still claims but pays 0 (PF5-7: 390 + 25 → +0); the
 * server never learns days. The weekly cap (2,800 per week id) is the server's.
 */
object FoundCaps {
    const val CAP_COPY_KEY = "play_log_cap"

    fun apply(foundToday: Int, marks: Int, cap: Int = Caps().foundPerDay): Capped =
        if (marks > 0 && foundToday + marks > cap) Capped(0, capped = true) else Capped(marks, capped = false)
}
