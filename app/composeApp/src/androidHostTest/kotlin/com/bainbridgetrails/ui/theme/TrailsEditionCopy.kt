package com.bainbridgetrails.ui.theme

/**
 * Every copy key of the Play trails edition (docs/play/trails/ux.md § 8) with its verbatim English text (plurals as
 * [one, other]). The one list: [PlayCopyTest] checks the texts, `GoalVocabularyGuardTest` checks the [TEXTS] keys have
 * no roads twin.
 */
internal object TrailsEditionCopy {
    val TEXTS: Map<String, List<String>> = mapOf(
        "play_kind_climb" to listOf("Climb"),
        "play_kind_high_ground" to listOf("High ground"),
        "play_kind_start_trails" to listOf("Trail rewards so far"),
        "play_log_climb_what" to listOf("%1\$s climbed"),
        "play_log_mile_what" to listOf("%1\$s of trail walked"),
        "play_results_more" to listOf("+1 more in your Logbook", "+%1\$d more in your Logbook"),
        "play_trail_ms_title" to listOf("Trail milestones"),
        "play_trail_ms_all" to listOf("All"),
        "play_trail_ms_progress" to listOf("%1\$s / %2\$s"),
        "play_trail_ms_next_a11y" to listOf("Next: %1\$s, %2\$s of %3\$s"),
        "play_trail_ms_earned_a11y" to listOf("%1\$s, earned"),
        "play_trail_ms_locked_a11y" to listOf("%1\$s, not yet"),
        "play_trail_ms_so_far" to listOf("So far: %1\$s"),
        "play_trail_ms_climb" to listOf("Climb"),
        "play_trail_ms_miles" to listOf("Trail miles"),
        "play_trail_ms_parks" to listOf("Parks"),
        "play_trail_ms_parks_title" to listOf("Parks finished"),
        "play_trail_ms_parks_line" to listOf("%1\$d of %2\$d finished · %3\$d started"),
        "play_trail_ms_park_finished" to listOf("Finished"),
        "play_trail_ms_park_started" to listOf("Started"),
        "play_trail_ms_park_not_started" to listOf("Not yet"),
        "play_trail_ms_high" to listOf("High ground"),
        "play_trail_ms_high_title" to listOf("High-ground trails"),
        "play_trail_ms_high_line" to listOf("%1\$d of %2\$d trails"),
        "play_trail_ms_high_row_a11y" to listOf("%1\$s, %2\$s of climb"),
        "play_challenge_progress_climb" to listOf("%1\$s of %2\$s climbed"),
        "play_challenge_progress_parks" to listOf("%1\$d of %2\$d park", "%1\$d of %2\$d parks"),
        "play_unit_feet_a11y" to listOf("%1\$s foot", "%1\$s feet"),
        "play_unit_metres_a11y" to listOf("%1\$s metre", "%1\$s metres"),
        "play_unit_miles_a11y" to listOf("%1\$s mile", "%1\$s miles"),
        "play_unit_km_a11y" to listOf("%1\$s kilometre", "%1\$s kilometres"),
    )
}
