package com.bainbridgetrails.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.app_name
import com.bainbridgetrails.resources.board_col_trails
import com.bainbridgetrails.resources.board_empty_body
import com.bainbridgetrails.resources.board_not_ranked
import com.bainbridgetrails.resources.board_row_a11y
import com.bainbridgetrails.resources.board_row_you_a11y
import com.bainbridgetrails.resources.board_signed_out_body
import com.bainbridgetrails.resources.board_signed_out_prompt
import com.bainbridgetrails.resources.board_subtitle
import com.bainbridgetrails.resources.celebrate_a11y_announcement
import com.bainbridgetrails.resources.celebrate_a11y_announcement_multi
import com.bainbridgetrails.resources.celebrate_body
import com.bainbridgetrails.resources.celebrate_eyebrow
import com.bainbridgetrails.resources.celebrate_eyebrow_multi
import com.bainbridgetrails.resources.celebrate_island_a11y
import com.bainbridgetrails.resources.celebrate_island_body
import com.bainbridgetrails.resources.celebrate_island_eyebrow
import com.bainbridgetrails.resources.celebrate_island_title
import com.bainbridgetrails.resources.celebrate_title_multi
import com.bainbridgetrails.resources.celebrate_trails_count
import com.bainbridgetrails.resources.celebrate_view_trail
import com.bainbridgetrails.resources.common_loading_trails
import com.bainbridgetrails.resources.detail_community_completed
import com.bainbridgetrails.resources.detail_community_few
import com.bainbridgetrails.resources.detail_managed_by
import com.bainbridgetrails.resources.detail_not_found
import com.bainbridgetrails.resources.detail_partly_counted
import com.bainbridgetrails.resources.error_dataset_body
import com.bainbridgetrails.resources.error_dataset_title
import com.bainbridgetrails.resources.goal_distance_km
import com.bainbridgetrails.resources.goal_distance_mi
import com.bainbridgetrails.resources.map_a11y
import com.bainbridgetrails.resources.map_legend_footnote
import com.bainbridgetrails.resources.map_legend_not_counted_body
import com.bainbridgetrails.resources.map_loading_trails
import com.bainbridgetrails.resources.map_preview_multi_title
import com.bainbridgetrails.resources.map_preview_open
import com.bainbridgetrails.resources.map_search_hint
import com.bainbridgetrails.resources.me_hero_a11y
import com.bainbridgetrails.resources.me_hero_caption
import com.bainbridgetrails.resources.me_history_trails
import com.bainbridgetrails.resources.me_in_progress_empty
import com.bainbridgetrails.resources.me_leaderboard_row_unranked
import com.bainbridgetrails.resources.me_stat_km
import com.bainbridgetrails.resources.me_stat_km_phone
import com.bainbridgetrails.resources.me_stat_miles
import com.bainbridgetrails.resources.me_stat_miles_phone
import com.bainbridgetrails.resources.me_stat_trails
import com.bainbridgetrails.resources.nav_trails
import com.bainbridgetrails.resources.onboarding_1_body
import com.bainbridgetrails.resources.onboarding_1_title
import com.bainbridgetrails.resources.onboarding_2_body
import com.bainbridgetrails.resources.onboarding_3_body
import com.bainbridgetrails.resources.onboarding_privacy_body
import com.bainbridgetrails.resources.perm_location_body
import com.bainbridgetrails.resources.perm_precise_body
import com.bainbridgetrails.resources.privacy_account_body
import com.bainbridgetrails.resources.privacy_board_body
import com.bainbridgetrails.resources.privacy_lead
import com.bainbridgetrails.resources.profile_privacy_note
import com.bainbridgetrails.resources.record_finish_body
import com.bainbridgetrails.resources.record_idle_what
import com.bainbridgetrails.resources.record_live_collapse_a11y
import com.bainbridgetrails.resources.record_live_empty
import com.bainbridgetrails.resources.record_live_expand_a11y
import com.bainbridgetrails.resources.record_live_header
import com.bainbridgetrails.resources.record_live_header_count
import com.bainbridgetrails.resources.record_loading_failed
import com.bainbridgetrails.resources.record_matching
import com.bainbridgetrails.resources.results_approx_warning
import com.bainbridgetrails.resources.results_no_progress
import com.bainbridgetrails.resources.results_no_progress_hint
import com.bainbridgetrails.resources.results_trails_header
import com.bainbridgetrails.resources.play_intro_1
import com.bainbridgetrails.resources.roads_play_intro_1
import com.bainbridgetrails.resources.play_about_marks
import com.bainbridgetrails.resources.roads_play_about_marks
import com.bainbridgetrails.resources.play_detail_section
import com.bainbridgetrails.resources.roads_play_detail_section
import com.bainbridgetrails.resources.play_kraken_body
import com.bainbridgetrails.resources.roads_play_kraken_body
import com.bainbridgetrails.resources.play_challenge_sweep_body
import com.bainbridgetrails.resources.roads_play_challenge_sweep_body
import com.bainbridgetrails.resources.play_challenge_crossing_body
import com.bainbridgetrails.resources.roads_play_challenge_crossing_body
import com.bainbridgetrails.resources.play_kind_complete
import com.bainbridgetrails.resources.roads_play_kind_complete
import com.bainbridgetrails.resources.roads_app_name
import com.bainbridgetrails.resources.roads_board_col_trails
import com.bainbridgetrails.resources.roads_board_empty_body
import com.bainbridgetrails.resources.roads_board_not_ranked
import com.bainbridgetrails.resources.roads_board_row_a11y
import com.bainbridgetrails.resources.roads_board_row_you_a11y
import com.bainbridgetrails.resources.roads_board_signed_out_body
import com.bainbridgetrails.resources.roads_board_signed_out_prompt
import com.bainbridgetrails.resources.roads_board_subtitle
import com.bainbridgetrails.resources.roads_celebrate_a11y_announcement
import com.bainbridgetrails.resources.roads_celebrate_a11y_announcement_multi
import com.bainbridgetrails.resources.roads_celebrate_body
import com.bainbridgetrails.resources.roads_celebrate_eyebrow
import com.bainbridgetrails.resources.roads_celebrate_eyebrow_multi
import com.bainbridgetrails.resources.roads_celebrate_island_a11y
import com.bainbridgetrails.resources.roads_celebrate_island_body
import com.bainbridgetrails.resources.roads_celebrate_island_eyebrow
import com.bainbridgetrails.resources.roads_celebrate_island_title
import com.bainbridgetrails.resources.roads_celebrate_title_multi
import com.bainbridgetrails.resources.roads_celebrate_trails_count
import com.bainbridgetrails.resources.roads_celebrate_view_trail
import com.bainbridgetrails.resources.roads_common_loading_trails
import com.bainbridgetrails.resources.roads_detail_community_completed
import com.bainbridgetrails.resources.roads_detail_community_few
import com.bainbridgetrails.resources.roads_detail_managed_by
import com.bainbridgetrails.resources.roads_detail_not_found
import com.bainbridgetrails.resources.roads_detail_partly_counted
import com.bainbridgetrails.resources.roads_error_dataset_body
import com.bainbridgetrails.resources.roads_error_dataset_title
import com.bainbridgetrails.resources.roads_map_a11y
import com.bainbridgetrails.resources.roads_map_legend_footnote
import com.bainbridgetrails.resources.roads_map_legend_not_counted_body
import com.bainbridgetrails.resources.roads_map_loading_trails
import com.bainbridgetrails.resources.roads_map_preview_multi_title
import com.bainbridgetrails.resources.roads_map_preview_open
import com.bainbridgetrails.resources.roads_map_search_hint
import com.bainbridgetrails.resources.roads_me_hero_a11y
import com.bainbridgetrails.resources.roads_me_hero_caption
import com.bainbridgetrails.resources.roads_me_history_trails
import com.bainbridgetrails.resources.roads_me_in_progress_empty
import com.bainbridgetrails.resources.roads_me_leaderboard_row_unranked
import com.bainbridgetrails.resources.roads_me_stat_km
import com.bainbridgetrails.resources.roads_me_stat_km_phone
import com.bainbridgetrails.resources.roads_me_stat_miles
import com.bainbridgetrails.resources.roads_me_stat_miles_phone
import com.bainbridgetrails.resources.roads_me_stat_trails
import com.bainbridgetrails.resources.roads_nav_trails
import com.bainbridgetrails.resources.roads_onboarding_1_body
import com.bainbridgetrails.resources.roads_onboarding_1_title
import com.bainbridgetrails.resources.roads_onboarding_2_body
import com.bainbridgetrails.resources.roads_onboarding_3_body
import com.bainbridgetrails.resources.roads_onboarding_privacy_body
import com.bainbridgetrails.resources.roads_perm_location_body
import com.bainbridgetrails.resources.roads_perm_precise_body
import com.bainbridgetrails.resources.roads_privacy_account_body
import com.bainbridgetrails.resources.roads_privacy_board_body
import com.bainbridgetrails.resources.roads_privacy_lead
import com.bainbridgetrails.resources.roads_profile_privacy_note
import com.bainbridgetrails.resources.roads_record_finish_body
import com.bainbridgetrails.resources.roads_record_idle_what
import com.bainbridgetrails.resources.roads_record_live_collapse_a11y
import com.bainbridgetrails.resources.roads_record_live_empty
import com.bainbridgetrails.resources.roads_record_live_expand_a11y
import com.bainbridgetrails.resources.roads_record_live_header
import com.bainbridgetrails.resources.roads_record_live_header_count
import com.bainbridgetrails.resources.roads_record_loading_failed
import com.bainbridgetrails.resources.roads_record_matching
import com.bainbridgetrails.resources.roads_results_approx_warning
import com.bainbridgetrails.resources.roads_results_no_progress
import com.bainbridgetrails.resources.roads_results_no_progress_hint
import com.bainbridgetrails.resources.roads_results_trails_header
import com.bainbridgetrails.resources.roads_settings_attribution_dataset
import com.bainbridgetrails.resources.roads_stat_island_info
import com.bainbridgetrails.resources.roads_stat_miles_info
import com.bainbridgetrails.resources.roads_stat_trails_info
import com.bainbridgetrails.resources.roads_trails_count
import com.bainbridgetrails.resources.roads_trails_empty_search_title
import com.bainbridgetrails.resources.roads_trails_empty_title
import com.bainbridgetrails.resources.roads_trails_first_walk_banner
import com.bainbridgetrails.resources.roads_trails_search_hint
import com.bainbridgetrails.resources.roads_trails_sort_length
import com.bainbridgetrails.resources.roads_trails_title
import com.bainbridgetrails.resources.roads_walk_no_track_note
import com.bainbridgetrails.resources.settings_attribution_dataset
import com.bainbridgetrails.resources.stat_island_info
import com.bainbridgetrails.resources.stat_miles_info
import com.bainbridgetrails.resources.stat_trails_info
import com.bainbridgetrails.resources.trails_count
import com.bainbridgetrails.resources.trails_empty_search_title
import com.bainbridgetrails.resources.trails_empty_title
import com.bainbridgetrails.resources.trails_first_walk_banner
import com.bainbridgetrails.resources.trails_search_hint
import com.bainbridgetrails.resources.trails_sort_length
import com.bainbridgetrails.resources.trails_title
import com.bainbridgetrails.resources.walk_no_track_note
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

/**
 * The words of the goal (RD-4, docs/roads/product.md §2, RP-1): one instance per [DatasetProfile].
 *
 * Compose resources have no flavour qualifier, so the roads build swaps strings by explicit lookup: [s] / [p] map a
 * trail key to its `roads_` twin (strings_roads.xml, same arguments in the same order) and return the trail key itself
 * when there is no twin or the profile is trails. Call sites never branch on the profile:
 * ```
 * Text(stringResource(LocalGoal.current.s(Res.string.map_search_hint)))
 * Text(LocalGoal.current.string(Res.string.trails_count, done, total))   // same thing, shorter
 * ```
 * Non-composable callers (a11y announcements built in a coroutine) use [AppGraph.goal][com.bainbridgetrails.graph.AppGraph.goal]
 * with the suspend [text] / [plural].
 *
 * GoalVocabularyGuardTest keeps this honest: every /trail/i string needs a registered `roads_` twin or an allowlist entry.
 */
@Immutable
class GoalVocabulary private constructor(
    /** The goal this vocabulary speaks for. */
    val goal: DatasetProfile,
    private val strings: Map<StringResource, StringResource>,
    private val plurals: Map<PluralStringResource, PluralStringResource>,
) {
    val isRoads: Boolean get() = goal == DatasetProfile.ROADS

    /** [r], or its roads twin when this is the roads vocabulary and a twin exists. */
    fun s(r: StringResource): StringResource = strings[r] ?: r

    /** [r], or its roads twin when this is the roads vocabulary and a twin exists. */
    fun p(r: PluralStringResource): PluralStringResource = plurals[r] ?: r

    /** Every override pair (trail key -> roads key); empty for trails. For the guard test. */
    internal val stringOverrides: Map<StringResource, StringResource> get() = strings
    internal val pluralOverrides: Map<PluralStringResource, PluralStringResource> get() = plurals

    @Composable
    fun string(r: StringResource, vararg args: Any): String = stringResource(s(r), *args)

    @Composable
    fun plural(r: PluralStringResource, quantity: Int, vararg args: Any): String = pluralStringResource(p(r), quantity, *args)

    suspend fun text(r: StringResource, vararg args: Any): String = getString(s(r), *args)

    suspend fun pluralText(r: PluralStringResource, quantity: Int, vararg args: Any): String = getPluralString(p(r), quantity, *args)

    /**
     * The island total for copy that says "over …" (product.md conventions): rounded **down** to a whole unit,
     * as "162 miles" / "261 km" (`goal_distance_mi` / `goal_distance_km`). Pass `Dataset.islandTotalM`.
     * Onboarding runs before settings exist, so it passes the default [Units.MILES].
     */
    @Composable
    fun islandTotalOver(islandTotalM: Double, units: Units = Units.MILES): String =
        stringResource(if (units == Units.MILES) Res.string.goal_distance_mi else Res.string.goal_distance_km, wholeUnitsDown(islandTotalM, units))

    override fun toString(): String = "GoalVocabulary(${goal.key})"

    companion object {
        private const val METRES_PER_MILE = 1609.344

        /** floor(metres in [units]); the "over N miles" number. */
        fun wholeUnitsDown(metres: Double, units: Units): Int {
            val v = if (units == Units.MILES) metres / METRES_PER_MILE else metres / 1000.0
            return floor(v + 1e-9).toInt().coerceAtLeast(0)
        }

        /** Identity: the trail build's words, unchanged. */
        val Trails = GoalVocabulary(DatasetProfile.TRAILS, emptyMap(), emptyMap())

        /** One row per product.md §3 table row: trail key to its roads twin. */
        val Roads = GoalVocabulary(
            DatasetProfile.ROADS,
            strings = mapOf(
            Res.string.app_name to Res.string.roads_app_name,
            Res.string.common_loading_trails to Res.string.roads_common_loading_trails,
            Res.string.map_loading_trails to Res.string.roads_map_loading_trails,
            Res.string.nav_trails to Res.string.roads_nav_trails,
            Res.string.trails_title to Res.string.roads_trails_title,
            Res.string.error_dataset_title to Res.string.roads_error_dataset_title,
            Res.string.error_dataset_body to Res.string.roads_error_dataset_body,
            Res.string.settings_attribution_dataset to Res.string.roads_settings_attribution_dataset,
            Res.string.onboarding_1_title to Res.string.roads_onboarding_1_title,
            Res.string.onboarding_1_body to Res.string.roads_onboarding_1_body,
            Res.string.onboarding_2_body to Res.string.roads_onboarding_2_body,
            Res.string.onboarding_3_body to Res.string.roads_onboarding_3_body,
            Res.string.onboarding_privacy_body to Res.string.roads_onboarding_privacy_body,
            Res.string.map_search_hint to Res.string.roads_map_search_hint,
            Res.string.map_a11y to Res.string.roads_map_a11y,
            Res.string.map_legend_footnote to Res.string.roads_map_legend_footnote,
            Res.string.map_legend_not_counted_body to Res.string.roads_map_legend_not_counted_body,
            Res.string.map_preview_open to Res.string.roads_map_preview_open,
            Res.string.map_preview_multi_title to Res.string.roads_map_preview_multi_title,
            Res.string.trails_search_hint to Res.string.roads_trails_search_hint,
            Res.string.trails_count to Res.string.roads_trails_count,
            Res.string.trails_empty_title to Res.string.roads_trails_empty_title,
            Res.string.trails_empty_search_title to Res.string.roads_trails_empty_search_title,
            Res.string.trails_first_walk_banner to Res.string.roads_trails_first_walk_banner,
            Res.string.trails_sort_length to Res.string.roads_trails_sort_length,
            Res.string.detail_managed_by to Res.string.roads_detail_managed_by,
            Res.string.detail_community_few to Res.string.roads_detail_community_few,
            Res.string.detail_partly_counted to Res.string.roads_detail_partly_counted,
            Res.string.detail_not_found to Res.string.roads_detail_not_found,
            Res.string.record_idle_what to Res.string.roads_record_idle_what,
            Res.string.record_live_header to Res.string.roads_record_live_header,
            Res.string.record_live_header_count to Res.string.roads_record_live_header_count,
            Res.string.record_live_empty to Res.string.roads_record_live_empty,
            Res.string.record_live_expand_a11y to Res.string.roads_record_live_expand_a11y,
            Res.string.record_live_collapse_a11y to Res.string.roads_record_live_collapse_a11y,
            Res.string.record_finish_body to Res.string.roads_record_finish_body,
            Res.string.record_matching to Res.string.roads_record_matching,
            Res.string.record_loading_failed to Res.string.roads_record_loading_failed,
            Res.string.perm_location_body to Res.string.roads_perm_location_body,
            Res.string.perm_precise_body to Res.string.roads_perm_precise_body,
            Res.string.results_trails_header to Res.string.roads_results_trails_header,
            Res.string.results_no_progress to Res.string.roads_results_no_progress,
            Res.string.results_no_progress_hint to Res.string.roads_results_no_progress_hint,
            Res.string.results_approx_warning to Res.string.roads_results_approx_warning,
            Res.string.celebrate_body to Res.string.roads_celebrate_body,
            Res.string.celebrate_view_trail to Res.string.roads_celebrate_view_trail,
            Res.string.celebrate_eyebrow to Res.string.roads_celebrate_eyebrow,
            Res.string.celebrate_trails_count to Res.string.roads_celebrate_trails_count,
            Res.string.celebrate_a11y_announcement to Res.string.roads_celebrate_a11y_announcement,
            Res.string.celebrate_a11y_announcement_multi to Res.string.roads_celebrate_a11y_announcement_multi,
            Res.string.celebrate_island_title to Res.string.roads_celebrate_island_title,
            Res.string.celebrate_island_body to Res.string.roads_celebrate_island_body,
            Res.string.celebrate_island_eyebrow to Res.string.roads_celebrate_island_eyebrow,
            Res.string.celebrate_island_a11y to Res.string.roads_celebrate_island_a11y,
            Res.string.me_hero_caption to Res.string.roads_me_hero_caption,
            Res.string.me_hero_a11y to Res.string.roads_me_hero_a11y,
            Res.string.me_stat_trails to Res.string.roads_me_stat_trails,
            Res.string.me_stat_miles to Res.string.roads_me_stat_miles,
            Res.string.me_stat_km to Res.string.roads_me_stat_km,
            Res.string.me_stat_miles_phone to Res.string.roads_me_stat_miles_phone,
            Res.string.me_stat_km_phone to Res.string.roads_me_stat_km_phone,
            Res.string.stat_island_info to Res.string.roads_stat_island_info,
            Res.string.stat_trails_info to Res.string.roads_stat_trails_info,
            Res.string.stat_miles_info to Res.string.roads_stat_miles_info,
            Res.string.me_in_progress_empty to Res.string.roads_me_in_progress_empty,
            Res.string.me_leaderboard_row_unranked to Res.string.roads_me_leaderboard_row_unranked,
            Res.string.walk_no_track_note to Res.string.roads_walk_no_track_note,
            Res.string.board_subtitle to Res.string.roads_board_subtitle,
            Res.string.board_col_trails to Res.string.roads_board_col_trails,
            Res.string.board_empty_body to Res.string.roads_board_empty_body,
            Res.string.board_not_ranked to Res.string.roads_board_not_ranked,
            Res.string.board_signed_out_prompt to Res.string.roads_board_signed_out_prompt,
            Res.string.board_signed_out_body to Res.string.roads_board_signed_out_body,
            Res.string.profile_privacy_note to Res.string.roads_profile_privacy_note,
            Res.string.privacy_lead to Res.string.roads_privacy_lead,
            Res.string.privacy_account_body to Res.string.roads_privacy_account_body,
            Res.string.privacy_board_body to Res.string.roads_privacy_board_body,
            // Play (docs/play/product.md §7.3, keys tagged (goal))
            Res.string.play_intro_1 to Res.string.roads_play_intro_1,
            Res.string.play_about_marks to Res.string.roads_play_about_marks,
            Res.string.play_detail_section to Res.string.roads_play_detail_section,
            Res.string.play_kraken_body to Res.string.roads_play_kraken_body,
            Res.string.play_challenge_sweep_body to Res.string.roads_play_challenge_sweep_body,
            Res.string.play_challenge_crossing_body to Res.string.roads_play_challenge_crossing_body,
            Res.string.play_kind_complete to Res.string.roads_play_kind_complete,
            ),
            plurals = mapOf(
            Res.plurals.detail_community_completed to Res.plurals.roads_detail_community_completed,
            Res.plurals.celebrate_title_multi to Res.plurals.roads_celebrate_title_multi,
            Res.plurals.celebrate_eyebrow_multi to Res.plurals.roads_celebrate_eyebrow_multi,
            Res.plurals.me_history_trails to Res.plurals.roads_me_history_trails,
            Res.plurals.board_row_a11y to Res.plurals.roads_board_row_a11y,
            Res.plurals.board_row_you_a11y to Res.plurals.roads_board_row_you_a11y,
            ),
        )

        fun of(profile: DatasetProfile): GoalVocabulary = when (profile) {
            DatasetProfile.TRAILS -> Trails
            DatasetProfile.ROADS -> Roads
        }
    }
}

/** The running goal's vocabulary; App provides `GoalVocabulary.of(DatasetProfile.current)`. Defaults to trails. */
val LocalGoal = staticCompositionLocalOf { GoalVocabulary.Trails }
