package com.bainbridgetrails.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** A two-trail dataset near the island: segment a (trail A, 200 m), segment b (trail B, 200 m). */
internal object DataFixtures {
    // ~200 m east-west lines at two latitudes.
    private const val A = "[[-122.535,47.645],[-122.53234,47.645]]"
    private const val B = "[[-122.535,47.655],[-122.53234,47.655]]"

    const val TRAILS = """{"version":"t1","island_total_shippable_m":400.0,"trails":[
        {"id":"A","name":"Trail A","segment_ids":["a"],"computed_length_mi":0.12},
        {"id":"B","name":"Trail B","segment_ids":["b"],"computed_length_mi":0.12}]}"""

    const val SEGMENTS = """{"version":"t1","segments":[
        {"id":"a","length_m":200.0,"included":true,"trail_ids":["A"],"coords":$A},
        {"id":"b","length_m":200.0,"included":true,"trail_ids":["B"],"coords":$B}]}"""

    fun tmpDir(tag: String): String {
        val d = Path(SystemFileSystem.resolve(Path(".")).toString(), "build", "tmp-$tag-${kotlin.random.Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(d)
        return d.toString()
    }
}
