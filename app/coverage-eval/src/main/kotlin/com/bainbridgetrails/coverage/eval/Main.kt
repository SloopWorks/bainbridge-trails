package com.bainbridgetrails.coverage.eval

import com.bainbridgetrails.coverage.CoverageParams
import com.bainbridgetrails.coverage.CoverageState
import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.LocalProjection
import com.bainbridgetrails.coverage.Matcher
import com.bainbridgetrails.coverage.Progress
import com.bainbridgetrails.coverage.SegmentIndex
import com.bainbridgetrails.coverage.DatasetBinary
import com.bainbridgetrails.coverage.filterFixes
import com.bainbridgetrails.tracking.Gating
import com.bainbridgetrails.tracking.SessionStore
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = """usage:
  sweep --data DIR --out FILE.csv [--buffers 10,15,20,25,30] [--headings off,30,45,60] [--margins 0,1,3]
        [--smooth 0,10,20] [--sigmas 5,10,20] [--intervals 1,5,10] [--seeds 3] [--threshold 0.90]
        [--matchers competitive,hmm] [--hmm-sigmas 10] [--hmm-betas 5] [--off-network 25]  (HMM rows only)
  gpx --data DIR FILE.gpx...
  session --data DIR FILE.jsonl   (re-gate a recorded session; prints counts and per-trail %, no coordinates)
  cross --data DIR --walk-data DIR [--seeds 3] [--sigmas 5,10] [--intervals 1,5] [--buffers 30] [--limit 0]
        (walk every trail of --walk-data, score against --data; prints false credit with and without the walked
         dataset injected as `included: false` competitors)
  pack-dataset DIR OUT.bin        (DIR's metadata + coverage segments -> the app's dataset.bin, verified by re-reading)

any command: --meta trails.json --segments coverage_segments.json name the documents inside --data
             (the roads dataset uses roads.json / coverage_segments.json; --walk-meta / --walk-segments for --walk-data)"""

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
    // The roads dataset (data/roads/dist) ships the same contract under different file names, so every command takes
    // --meta / --segments instead of assuming the trail dataset's (docs/roads/data-spec.md).
    val names = setOf("meta", "segments")
    val allowed = names + when (args[0]) {
        "sweep" -> setOf("data", "out", "buffers", "headings", "margins", "smooth", "sigmas", "intervals", "seeds", "threshold",
            "matchers", "hmm-sigmas", "hmm-betas", "off-network")
        "gpx" -> setOf("data")
        "session" -> setOf("data")
        "cross" -> setOf("data", "walk-data", "walk-meta", "walk-segments", "seeds", "sigmas", "intervals", "buffers", "limit")
        "pack-dataset" -> emptySet()
        else -> usage("unknown command ${args[0]}")
    }
    (flags.keys - allowed).firstOrNull()?.let { usage("unknown flag --$it for ${args[0]}") }
    if (args[0] == "pack-dataset") {
        if (positional.size != 2) usage("pack-dataset takes DIR OUT.bin")
        packDataset(File(positional[0]), File(positional[1]), flags["meta"], flags["segments"])
        return
    }
    if (args[0] in setOf("sweep", "cross") && positional.isNotEmpty()) usage("unexpected argument ${positional.first()}")
    val dataDir = File(flags["data"] ?: usage("--data is required"))
    val dataset = readDataset(dataDir, flags["meta"], flags["segments"])
    when (args[0]) {
        "sweep" -> runSweep(dataset, flags)
        "gpx" -> runGpx(dataset, positional)
        "session" -> runSession(dataset, positional)
        "cross" -> runCross(dataset, flags)
        else -> usage("unknown command ${args[0]}")
    }
}

/** Metadata document in [dir]: [meta] if given, else trails.json, else the roads dataset's roads.json. */
internal fun metaFile(dir: File, meta: String?): File {
    meta?.let { return File(dir, it) }
    val trails = File(dir, "trails.json")
    return if (trails.isFile) trails else File(dir, "roads.json")
}

internal fun readDataset(dir: File, meta: String?, segments: String?): Dataset =
    Dataset.parse(metaFile(dir, meta).readText(), File(dir, segments ?: "coverage_segments.json").readText())

private fun usage(msg: String? = null): Nothing {
    msg?.let { System.err.println(it) }
    System.err.println(USAGE)
    exitProcess(2)
}

/** Writes [out] (atomically) from [dir]'s metadata + coverage segments; fails unless it decodes back equal. */
internal fun packDataset(dir: File, out: File, meta: String? = null, segments: String? = null) {
    val trails = metaFile(dir, meta).readBytes()
    val dataset = Dataset.parse(trails.decodeToString(), File(dir, segments ?: "coverage_segments.json").readText())
    val bytes = DatasetBinary.encode(dataset, DatasetBinary.hash(trails))
    val back = Dataset.fromBinary(bytes)
    check(
        back.version == dataset.version && back.islandTotalM == dataset.islandTotalM && back.trails == dataset.trails &&
            back.segments == dataset.segments,
    ) { "pack-dataset: round trip differs" }
    val tmp = File(out.parentFile, out.name + ".part")
    tmp.writeBytes(bytes)
    check(tmp.renameTo(out)) { "pack-dataset: cannot write $out" }
    println("pack-dataset: ${dataset.trails.size} trails, ${dataset.segments.size} segments -> ${bytes.size / 1024} KiB (${dataset.version})")
}

private fun doubles(s: String) = s.split(",").map { it.trim().toDouble() }

private fun runSweep(dataset: Dataset, flags: Map<String, String>) {
    val out = File(flags["out"] ?: usage("--out is required"))
    val headings = (flags["headings"] ?: "off,30,45,60").split(",").map {
        val v = it.trim()
        if (v == "off") null else v.toDoubleOrNull()?.takeIf { d -> d in 0.0..90.0 } ?: usage("--headings takes off or degrees 0..90")
    }
    val matchers = (flags["matchers"] ?: "competitive").split(",").map {
        when (it.trim()) {
            "competitive" -> Matcher.COMPETITIVE
            "hmm" -> Matcher.HMM
            else -> usage("--matchers takes competitive,hmm")
        }
    }
    val hmm = CoverageParams()
    fun positive(flag: String, default: Double) =
        doubles(flags[flag] ?: default.toString()).onEach { if (it <= 0.0) usage("--$flag takes metres > 0") }
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
        matchers = matchers,
        hmmSigmas = positive("hmm-sigmas", hmm.emissionSigmaM),
        hmmBetas = positive("hmm-betas", hmm.transitionBetaM),
        offNetworkMs = positive("off-network", hmm.offNetworkM),
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
    printTrails(dataset, progress, state)
}

/** Re-gates a pulled session JSONL with the app's [Gating] and coverage. Never prints coordinates (raw tracks are private). */
private fun runSession(dataset: Dataset, files: List<String>) {
    if (files.size != 1) usage("session takes exactly one FILE.jsonl")
    val file = File(files.single())
    val session = SessionStore.parse(file.nameWithoutExtension, file.readText())
    val params = CoverageParams()
    val proj = LocalProjection()
    val gated = Gating.gate(session, proj)
    val index = SegmentIndex(dataset.segments, params, proj)
    val progress = Progress(dataset, index, params)
    val state = index.coverFixes(gated.fixes)
    val kept = filterFixes(gated.fixes, params, proj).sumOf { it.size }
    println("${file.name}: fixes=${session.fixes.size} steps=${session.steps.size} activities=${session.activities.size} " +
        "finished=${session.finished} verified=${gated.verified}")
    println("gating: droppedVehicle=${gated.droppedVehicle} droppedSteps=${gated.droppedSteps} kept=${gated.fixes.size}")
    println("coverage filter: kept=$kept dropped=${gated.fixes.size - kept}")
    printTrails(dataset, progress, state)
}

private fun printTrails(dataset: Dataset, progress: Progress, state: CoverageState) {
    println(String.format(Locale.ROOT, "island: %.2f%%  trails complete: %d", progress.islandFraction(state) * 100, progress.trailsCompleted(state)))
    dataset.trails.map { it to progress.trailFraction(state, it.id) }
        .filter { it.second > 0.0 }
        .sortedByDescending { it.second }
        .forEach { (t, f) -> println(String.format(Locale.ROOT, "  %-40s %6.1f%%  %s", t.name, f * 100, progress.status(f))) }
}
