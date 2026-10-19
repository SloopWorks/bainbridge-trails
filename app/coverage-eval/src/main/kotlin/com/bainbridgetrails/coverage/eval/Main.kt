package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.filterFixes
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = """usage:
  sweep --data DIR --out FILE.csv [--buffers 10,15,20,25,30] [--headings off,30,45,60] [--margins 0,1,3]
        [--smooth 0,10,20] [--sigmas 5,10,20] [--intervals 1,5,10] [--seeds 3] [--threshold 0.90]
  gpx --data DIR FILE.gpx..."""

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val flags = HashMap<String, String>()
    val positional = ArrayList<String>()
    var i = 1
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            if (i + 1 >= args.size) usage("missing value for $a")
            flags[a.removePrefix("--")] = args[i + 1]
            i += 2
        } else {
            positional += a
            i++
        }
    }
    val allowed = when (args[0]) {
        "sweep" -> setOf("data", "out", "buffers", "headings", "margins", "smooth", "sigmas", "intervals", "seeds", "threshold")
        "gpx" -> setOf("data")
        else -> usage("unknown command ${args[0]}")
    }
    (flags.keys - allowed).firstOrNull()?.let { usage("unknown flag --$it for ${args[0]}") }
    if (args[0] == "sweep" && positional.isNotEmpty()) usage("unexpected argument ${positional.first()}")
    val dataDir = File(flags["data"] ?: usage("--data is required"))
    val dataset = Dataset.parse(File(dataDir, "trails.json").readText(), File(dataDir, "coverage_segments.json").readText())
    when (args[0]) {
        "sweep" -> runSweep(dataset, flags)
        "gpx" -> runGpx(dataset, positional)
        else -> usage("unknown command ${args[0]}")
    }
}

private fun usage(msg: String? = null): Nothing {
    msg?.let { System.err.println(it) }
    System.err.println(USAGE)
    exitProcess(2)
}

private fun doubles(s: String) = s.split(",").map { it.trim().toDouble() }

private fun runSweep(dataset: Dataset, flags: Map<String, String>) {
    val out = File(flags["out"] ?: usage("--out is required"))
    val headings = (flags["headings"] ?: "off,30,45,60").split(",").map {
        val v = it.trim()
        if (v == "off") null else v.toDoubleOrNull()?.takeIf { d -> d in 0.0..90.0 } ?: usage("--headings takes off or degrees 0..90")
    }
    val start = System.nanoTime()
    val rows = sweep(
        dataset,
        buffers = doubles(flags["buffers"] ?: "10,15,20,25,30"),
        sigmas = doubles(flags["sigmas"] ?: "5,10,20"),
        intervals = doubles(flags["intervals"] ?: "1,5,10"),
        seeds = (flags["seeds"] ?: "3").toInt(),
        headingTolerances = headings,
        margins = doubles(flags["margins"] ?: "0,1,3"),
        smoothWindows = doubles(flags["smooth"] ?: "0,10,20").onEach { if (it < 0.0) usage("--smooth takes seconds >= 0") },
        threshold = (flags["threshold"] ?: "0.90").toDouble(),
        log = { System.err.println(it) },
    )
    out.absoluteFile.parentFile?.mkdirs()
    out.writeText((listOf(SweepRow.CSV_HEADER) + rows.map { it.csv() }).joinToString("\n", postfix = "\n"))
    System.err.println(String.format(Locale.ROOT, "wrote %d rows to %s in %.1fs", rows.size, out, (System.nanoTime() - start) / 1e9))
}

private fun runGpx(dataset: Dataset, files: List<String>) {
    if (files.isEmpty()) usage("gpx needs at least one file")
    val params = CoverageParams()
    val proj = LocalProjection()
    val index = SegmentIndex(dataset.segments, params, proj)
    val progress = Progress(dataset, index, params)
    var state = CoverageState.EMPTY
    for (path in files) {
        val fixes = readGpx(File(path).readText())
        // each file is its own recording (the speed check's reference never spans files)
        state = state.union(index.coverFixes(fixes))
        val dedup = fixes.distinctBy { it.timeMs }
        val inaccurate = dedup.count { !it.isMock && (it.accuracyM ?: 0.0) > params.maxAccuracyM }
        val mock = dedup.count { it.isMock }
        val kept = filterFixes(fixes, params, proj).sumOf { it.size }
        val other = dedup.size - mock - inaccurate - kept
        println("$path: fixes=${fixes.size} kept=$kept dropped: duplicateTime=${fixes.size - dedup.size} mock=$mock " +
            "inaccurate=$inaccurate speedOrShortPiece=$other")
    }
    println(String.format(Locale.ROOT, "island: %.2f%%  trails complete: %d", progress.islandFraction(state) * 100, progress.trailsCompleted(state)))
    dataset.trails.map { it to progress.trailFraction(state, it.id) }
        .filter { it.second > 0.0 }
        .sortedByDescending { it.second }
        .forEach { (t, f) -> println(String.format(Locale.ROOT, "  %-40s %6.1f%%  %s", t.name, f * 100, progress.status(f))) }
}
