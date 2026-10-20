package com.bainbridgetrails.coverage

import java.io.File
import kotlin.test.Test

/**
 * Startup data-load timings on the real dataset (prints only). `-Pcoverage.benchXint` runs the JVM interpreted,
 * a rough stand-in for a debuggable Android build (no JIT tiering).
 */
class LoadBench {
    @Test
    fun loadTimings() {
        val segs = File("../../data/dist/coverage_segments.json")
        val trails = File("../../data/dist/trails.json")
        if (!segs.exists() || !trails.exists()) {
            println("LoadBench: SKIPPED, ../../data/dist files missing")
            return
        }
        val t = trails.readText()
        val s = segs.readText()
        val bin = DatasetBinary.encode(Dataset.parse(t, s), DatasetBinary.hash(t.encodeToByteArray()))
        val params = CoverageParams()
        for ((name, parse) in listOf<Pair<String, () -> Dataset>>("json" to { Dataset.parse(t, s) }, "bin" to { DatasetBinary.hash(t.encodeToByteArray()); Dataset.fromBinary(bin) })) {
            repeat(3) { round ->
                val t0 = System.nanoTime()
                val ds = parse()
                val t1 = System.nanoTime()
                val idx = SegmentIndex(ds.segments, params, LocalProjection())
                val t2 = System.nanoTime()
                Progress(ds, idx, params).snapshot(CoverageState.EMPTY)
                val t3 = System.nanoTime()
                idx.prepare()
                val t4 = System.nanoTime()
                println(
                    "LoadBench[$name $round]: parse ${(t1 - t0) / 1_000_000} ms, index ${(t2 - t1) / 1_000_000} ms, " +
                        "progress ${(t3 - t2) / 1_000_000} ms, index.prepare ${(t4 - t3) / 1_000_000} ms",
                )
            }
        }
    }
}
