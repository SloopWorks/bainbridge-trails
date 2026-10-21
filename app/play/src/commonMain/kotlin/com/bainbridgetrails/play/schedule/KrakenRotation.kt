package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Placement
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * `{ "rotate": [PoolRef], "epoch": "YYYY-MM-DD" }` placements (schedule.md §4.5): no RNG, occurrence n (whole weeks
 * since the epoch) attacks pool n mod len. The kraken's coasts (product §4.3.2, PD-8): roads K5, K2, K6, K4, K7, K3;
 * trails T4, T2, T3 from the first attack's Thursday 2026-10-29, so every coast comes round once every 6 (roads) or
 * 3 (trails) weeks and nobody's side of the island is favoured.
 */
object KrakenRotation {
    /** Rotation slot of [date] (any day of its week counts as its week): `floor(days since epoch / 7) mod size`. */
    fun index(epoch: LocalDate, date: LocalDate, size: Int): Int {
        require(size > 0) { "empty rotation" }
        return epoch.daysUntil(date).floorDiv(7).mod(size)
    }

    /** The pool ref (`pool:coast-k5`) [placement] rotates to for the occurrence starting [occDate]; null if not a rotation. */
    fun pool(placement: Placement, occDate: LocalDate): String? {
        val rotate = placement.rotate?.takeIf { it.isNotEmpty() } ?: return null
        val epoch = placement.epoch ?: return null
        return rotate[index(epoch, occDate, rotate.size)]
    }
}
