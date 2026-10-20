package com.bainbridgetrails.data

import com.bainbridgetrails.coverage.GoalRules
import com.bainbridgetrails.log.AppLog

/**
 * Which bundled dataset the app runs on — the roads prototype (docs/roads/plan.md P1).
 *
 * The v1 app had the trail dataset's file names as constants in two places (MapAssets, AppGraph). This enum is the
 * descriptor those two now read instead, so a debug build can run the whole app on `data/roads/dist` without a second
 * model, a second engine or a product flavour. Both datasets' assets can be installed side by side
 * (scripts/sync-app-assets.sh), so switching needs no rebuild.
 *
 * RD-1: each Android flavour fixes its profile at process start ([fix]); the trails debug build keeps the drawer switch
 * ([select]). iOS runs [Default].
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
    /** How progress counts for this goal (RP-18: roads count only counted roads / segments). */
    val rules: GoalRules,
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
        rules = GoalRules.TRAILS,
    ),
    ROADS(
        key = "roads",
        metadataFile = "roads.json",
        coverageFile = "roads_coverage_segments.json",
        tileFile = "roads.pmtiles",
        datasetBinFile = "roads_dataset.bin",
        segmentIdPrefix = "rseg-",
        thing = "road", things = "roads",
        goal = "Walk or run every public road on Bainbridge Island (162.5 mi shippable of 164.2 mi)",
        rules = GoalRules.ROADS,
    ),
    ;

    /**
     * Local store name for this goal: trails keeps today's names ("sync.json", "tracking"); any other goal prefixes
     * its key ("roads_sync.json", "roads_tracking"). The flavours already have separate app data dirs; this keeps the
     * trail debug build's dataset switch (one install, two goals) from mixing coverage, walks, the sync queue and the
     * leaderboard cache.
     */
    fun storeName(name: String): String = if (this == TRAILS) name else "${key}_$name"

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
            if (fixed) {
                AppLog.w(TAG, "dataset fixed to ${selected.key} by the build flavour; ignoring ${profile.key}")
                return false
            }
            if (read) {
                AppLog.w(TAG, "dataset already in use (${selected.key}); relaunch to run on ${profile.key}")
                return false
            }
            selected = profile
            AppLog.i(TAG, "dataset profile: ${profile.key}")
            return true
        }

        private var fixed = false

        /**
         * RD-1: the build flavour's goal. Call once at process start (Application.onCreate), before anything reads
         * [current]. Trails leaves the debug switch ([select]) open; any other goal locks the profile for the process.
         */
        fun fix(profile: DatasetProfile) {
            select(profile)
            if (profile != TRAILS) fixed = true
        }

        /** True when the build flavour locked the profile (the debug drawer hides its switch). */
        val isFixed: Boolean get() = fixed

        /** The profile named by a debug launch option / stored preference, or null. */
        fun byKey(key: String?): DatasetProfile? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }

        private const val TAG = "dataset"
    }
}
