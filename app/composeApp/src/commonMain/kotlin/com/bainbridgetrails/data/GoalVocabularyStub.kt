package com.bainbridgetrails.data

import androidx.compose.runtime.staticCompositionLocalOf
import com.bainbridgetrails.resources.*
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * STUB of the vocabulary work's `GoalVocabulary` (docs/roads/product.md §2), only so the road screens compile and read
 * right on this branch. Same surface the screens code to: `LocalGoal.current.s(Res.string.x)`, `.p(...)`, `.goal`.
 * At merge: delete this file (and values/strings_roads_stub.xml); the real class supplies the full table.
 */
class GoalVocabulary(
    val goal: DatasetProfile,
    private val strings: Map<StringResource, StringResource> = emptyMap(),
    private val plurals: Map<PluralStringResource, PluralStringResource> = emptyMap(),
) {
    fun s(r: StringResource): StringResource = strings[r] ?: r
    fun p(r: PluralStringResource): PluralStringResource = plurals[r] ?: r

    companion object {
        val Trails = GoalVocabulary(DatasetProfile.TRAILS)
        val Roads = GoalVocabulary(
            DatasetProfile.ROADS,
            strings = mapOf(
                Res.string.trails_title to Res.string.roads_trails_title,
                Res.string.nav_trails to Res.string.roads_nav_trails,
                Res.string.map_search_hint to Res.string.roads_map_search_hint,
                Res.string.map_a11y to Res.string.roads_map_a11y,
                Res.string.map_legend_footnote to Res.string.roads_map_legend_footnote,
                Res.string.map_preview_open to Res.string.roads_map_preview_open,
                Res.string.map_preview_multi_title to Res.string.roads_map_preview_multi_title,
                Res.string.trails_search_hint to Res.string.roads_trails_search_hint,
                Res.string.trails_count to Res.string.roads_trails_count,
                Res.string.trails_empty_title to Res.string.roads_trails_empty_title,
                Res.string.trails_empty_search_title to Res.string.roads_trails_empty_search_title,
                Res.string.trails_first_walk_banner to Res.string.roads_trails_first_walk_banner,
                Res.string.trails_sort_length to Res.string.roads_trails_sort_length,
                Res.string.detail_partly_counted to Res.string.roads_detail_partly_counted,
                Res.string.detail_not_found to Res.string.roads_detail_not_found,
                Res.string.detail_community_few to Res.string.roads_detail_community_few,
                Res.string.me_hero_caption to Res.string.roads_me_hero_caption,
                Res.string.me_hero_a11y to Res.string.roads_me_hero_a11y,
                Res.string.me_stat_trails to Res.string.roads_me_stat_trails,
                Res.string.me_stat_miles_phone to Res.string.roads_me_stat_miles_phone,
                Res.string.me_stat_km_phone to Res.string.roads_me_stat_km_phone,
                Res.string.me_in_progress_empty to Res.string.roads_me_in_progress_empty,
                Res.string.results_trails_header to Res.string.roads_results_trails_header,
                Res.string.results_no_progress to Res.string.roads_results_no_progress,
                Res.string.results_no_progress_hint to Res.string.roads_results_no_progress_hint,
                Res.string.celebrate_eyebrow to Res.string.roads_celebrate_eyebrow,
                Res.string.celebrate_view_trail to Res.string.roads_celebrate_view_trail,
                Res.string.celebrate_trails_count to Res.string.roads_celebrate_trails_count,
                Res.string.celebrate_a11y_announcement to Res.string.roads_celebrate_a11y_announcement,
                Res.string.celebrate_a11y_announcement_multi to Res.string.roads_celebrate_a11y_announcement_multi,
                Res.string.onboarding_1_title to Res.string.roads_onboarding_1_title,
                Res.string.onboarding_2_body to Res.string.roads_onboarding_2_body,
            ),
            plurals = mapOf(Res.plurals.celebrate_eyebrow_multi to Res.plurals.roads_celebrate_eyebrow_multi),
        )

        fun of(p: DatasetProfile): GoalVocabulary = if (p == DatasetProfile.ROADS) Roads else Trails
    }
}

val LocalGoal = staticCompositionLocalOf { GoalVocabulary.of(DatasetProfile.current) }
