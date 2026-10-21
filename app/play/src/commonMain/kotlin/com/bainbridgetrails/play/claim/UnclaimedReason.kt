package com.bainbridgetrails.play.claim

/**
 * Why an item the walk reached was not claimed (product PF6-2, PF7-7), with its `play_unclaimed_*` copy key. Declared
 * in check order: when several fixes reached an item, the reason is the one of the fix that got furthest.
 */
enum class UnclaimedReason(val copyKey: String) {
    /** The occurrence was not active then (already washed away). */
    WINDOW("play_unclaimed_window"),

    /** Outside daylight (civil dawn → civil dusk ∩ play hours, PD-5). */
    DARK("play_unclaimed_dark"),

    /** Kraken stretch above 2.44 m MLLW, or no tide table (fail closed, PD-8). */
    TIDE("play_unclaimed_tide"),

    /** Trailing-60 s speed above 4.0 m/s, or the Finalizer dropped the fixes as a ride. */
    VEHICLE("play_unclaimed_vehicle"),

    /** No fix within the radius was accurate enough. */
    ACCURACY("play_unclaimed_accuracy"),
}
