package com.bainbridgetrails.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Play copy tone (docs/play/product.md §7.3, ux.md §6, PD-4, PD-16): no race words, no speed, no "points", no
 * exclamation mark, in any `play_` string or its `roads_play_` twin. Parses values/strings*.xml from the source tree
 * (host tests run with the module as working directory), as GoalVocabularyGuardTest does.
 */
class PlayCopyTest {
    private val play: Map<String, List<String>> by lazy {
        val dir = listOf("src/commonMain/composeResources/values", "composeApp/src/commonMain/composeResources/values")
            .map(::File).first { it.isDirectory }
        val out = LinkedHashMap<String, List<String>>()
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { f ->
            val xml = f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                out[it.groupValues[1]] = listOf(it.groupValues[2])
            }
            Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                out[m.groupValues[1]] = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            }
        }
        out.filterKeys { it.startsWith("play_") || it.startsWith("roads_play_") }
    }

    @Test
    fun everyV1PlayKeyIsPresentAndNoBoardKeyShipsInV1() {
        assertTrue(play.size >= 240, "parsed only ${play.size} play_ entries")
        REQUIRED.forEach { assertTrue(it in play, "missing $it") }
        val board = play.keys.filter { it.startsWith("play_board_") || it.startsWith("play_settings_board") || it.startsWith("play_me_board") }
        assertEquals(emptyList(), board, "Play board keys are v2 (PD-15)")
    }

    @Test
    fun noPlayStringContainsRaceRunFastSpeedSprintBeatPoints() {
        val problems = play.flatMap { (k, texts) -> texts.mapNotNull { t -> FORBIDDEN.find(t)?.let { "$k: \"${it.value}\" in \"$t\"" } } }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun noExclamationMark() {
        val problems = play.flatMap { (k, texts) -> texts.filter { '!' in it || '¡' in it }.map { "$k: \"$it\"" } }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    /** docs/play/trails/ux.md § 8, verbatim (plurals as [one, other]); the blocklist tests above cover them too. */
    @Test
    fun trailsEditionCopyIsVerbatim() {
        val problems = TRAILS_V1_1.mapNotNull { (k, texts) -> if (play[k] == texts) null else "$k: ${play[k]} != $texts" }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private companion object {
        val TRAILS_V1_1: Map<String, List<String>> = mapOf(
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
            "play_challenge_progress_climb" to listOf("%1\$s of %2\$s climbed"),
            "play_challenge_progress_parks" to listOf("%1\$d of %2\$d park", "%1\$d of %2\$d parks"),
            "play_unit_feet_a11y" to listOf("%1\$s foot", "%1\$s feet"),
            "play_unit_metres_a11y" to listOf("%1\$s metre", "%1\$s metres"),
            "play_unit_miles_a11y" to listOf("%1\$s mile", "%1\$s miles"),
            "play_unit_km_a11y" to listOf("%1\$s kilometre", "%1\$s kilometres"),
        )

        val FORBIDDEN = Regex(
            """\b(race[sd]?|racing|run|runs|running|ran|fast|faster|fastest|speed\w*|sprint\w*|beat|beats|beaten|beating|points?)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** One key per product §7.3 / ux §6.2 group: the table landed whole (L2-1 owns every v1 play_ key). */
        val REQUIRED = listOf(
            "play_settings_toggle_body", "play_intro_1", "play_offer_title", "play_off_confirm", "play_about_privacy",
            "play_map_chip", "play_challenges_chip", "play_legend_fog", "play_quiet_banner", "play_detail_section",
            "play_walk_there", "play_tier_ships_bell", "play_gate_tide_unknown", "play_ferry_timetable_note",
            "play_kraken_body", "play_kraken_stretch_progress", "play_riddle_stars_a11y", "play_riddle_count_near",
            "play_challenge_sweep_body", "play_challenge_band_starting", "play_nearby_row", "play_notif_finds",
            "play_results_none", "play_unclaimed_tide", "play_log_empty", "play_kind_week_run", "play_kind_visit",
            "play_rank_11", "play_rank_11_flavour", "play_stories_count", "play_me_stories",
            "play_settings_ambient", "play_wake_a11y", "play_marker_cluster_a11y", "play_record_finds", "play_found_on",
            "play_content_gone", "play_kraken_reward", "play_claim_pop_a11y", "play_whats_on_treasures", "play_me_whats_on",
            "roads_play_intro_1", "roads_play_kind_complete",
        )
    }
}
