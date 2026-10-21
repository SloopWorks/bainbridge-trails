package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.config.PlayConfigResult
import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.TideTable
import java.io.File

/**
 * The committed v1 pack and its committed companions (tides 2026–2029, ferry timetable, derived per-goal tables), read
 * by relative path from the Gradle test working directory (`app/play`). The island datasets are gitignored:
 * [withData] skips the calling test without them.
 */
internal object RealPack {
    val ROOT = File("../..")
    val config: PlayConfig by lazy {
        when (val r = PlayConfig.decode(File(ROOT, "data/play/play-content.json").readText())) {
            is PlayConfigResult.Ok -> r.config
            is PlayConfigResult.Rejected -> error(r.errors.joinToString("\n"))
        }
    }
    val tides: TideTable by lazy {
        TideTable.parse(*(2026..2029).map { File(ROOT, "data/play/tides/9445882-$it.json").readText() }.toTypedArray())
    }
    val ferry: FerryTimetable by lazy { FerryTimetable.parse(File(ROOT, "data/play/ferry-timetable.json").readText()) }
    val derived: Map<Goal, DerivedGoal> by lazy {
        Goal.entries.associateWith { DerivedGoal.decode(File(ROOT, "data/play/derived/${it.name}.json").readText()) }
    }

    /** `-Pplay.updateGolden=true`: tests rewrite their golden files instead of comparing. */
    val update: Boolean get() = System.getProperty("play.updateGolden") == "true"

    fun scheduler(
        index: PlaceIndex = FakePlaceIndex(emptyList()),
        tides: TideTable? = this.tides,
        config: PlayConfig = this.config,
    ): PlayScheduler = PlayScheduler(config, index, tides, ferry, derived)

    /** A scheduler over the real datasets, or a skipped test. */
    fun withData(test: String): PlayScheduler = scheduler(JvmDatasetPlaceIndex.loadOrSkip(test))
}
