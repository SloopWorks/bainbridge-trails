package com.bainbridgetrails.data

import com.bainbridgetrails.log.AppLog

/**
 * Which bundled dataset the app runs on — the roads prototype (docs/roads/plan.md P1).
 *
 * The v1 app had the trail dataset's file names as constants in two places (MapAssets, AppGraph). This enum is the
 * descriptor those two now read instead, so a debug build can run the whole app on `data/roads/dist` without a second
 * model, a second engine or a product flavour. Both datasets' assets can be installed side by side
 * (scripts/sync-app-assets.sh), so switching needs no rebuild.
 *
 * Throwaway-grade on purpose: this exists to *feel* the difference between the two goals before deciding whether roads
 * ship as a flavour or a second tab. Release always runs [Default]; [select] is debug-only.
 */
enum class DatasetProfile(
    /** Stable key: the debug switch's value, and the install sub-directory under <data>/map/. */
    val key: String,
    /** The dataset metadata document in composeResources/files/ (trails.json shape; roads.json is the same contract). */
    val metadataFile: String,
    /** Per-segment geometry for coverage and map matching. */
    val coverageFile: String,
    /** The vector tile set drawn as the style's `{{trails}}` source (layer "segments" in both datasets). */
    val tileFile: String,
    /** Packed [metadataFile] + [coverageFile] ([com.bainbridgetrails.coverage.DatasetBinary]); null = parse the JSON. */
    val datasetBinFile: String?,
    /** Segment id namespace; the backend keys coverage by segment id, so the two datasets never collide. */
    val segmentIdPrefix: String,
    /** Display strings. "trail" / "road": the one word the UI copy turns on. */
    val thing: String,
    val things: String,
    /** The goal, for the debug drawer read-out and the docs. */
    val goal: String,
) {
    TRAILS(
        key = "trails",
        metadataFile = "trails.json",
        coverageFile = "coverage_segments.json",
        tileFile = "trails.pmtiles",
        datasetBinFile = "dataset.bin",
        segmentIdPrefix = "seg-",
        thing = "trail", things = "trails",
        goal = "Walk every public trail on Bainbridge Island (65.5 mi)",
    ),
    ROADS(
        key = "roads",
        metadataFile = "roads.json",
        coverageFile = "roads_coverage_segments.json",
        tileFile = "roads.pmtiles",
        datasetBinFile = "roads_dataset.bin",
        segmentIdPrefix = "rseg-",
        thing = "road", things = "roads",
        goal = "Walk, hike or bike every public road on Bainbridge Island (162.5 mi shippable of 164.2 mi)",
    ),
    ;

    companion object {
        val Default = TRAILS

        private var selected: DatasetProfile = Default
        private var read = false

        /**
         * The profile this process runs on. First read fixes it: the dataset is parsed and the map style rendered once
         * per process (ruling R1), so a later [select] cannot take effect without a relaunch.
         */
        val current: DatasetProfile
            get() {
                read = true
                return selected
            }

        /**
         * Debug builds only: pick the dataset for this process. Must run before the first [current] — i.e. before
         * `AppGraph.warmUp()` and before any map composes. A later call logs and is ignored; the caller (the debug
         * drawer) tells the user to relaunch.
         */
        fun select(profile: DatasetProfile): Boolean {
            if (profile == selected) return true
            if (read) {
                AppLog.w(TAG, "dataset already in use (${selected.key}); relaunch to run on ${profile.key}")
                return false
            }
            selected = profile
            AppLog.i(TAG, "dataset profile: ${profile.key}")
            return true
        }

        /** The profile named by a debug launch option / stored preference, or null. */
        fun byKey(key: String?): DatasetProfile? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }

        private const val TAG = "dataset"
    }
}
