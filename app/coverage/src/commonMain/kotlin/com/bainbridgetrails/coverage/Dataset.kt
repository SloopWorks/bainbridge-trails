package com.bainbridgetrails.coverage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrailMeta(
    val id: String,
    val name: String,
    @SerialName("segment_ids") val segmentIds: List<String>,
    @SerialName("computed_length_mi") val computedLengthMi: Double,
    @SerialName("official_length_mi") val officialLengthMi: Double? = null,
) {
    val displayLengthMi: Double get() = officialLengthMi ?: computedLengthMi
}

@Serializable
private data class TrailsFile(
    val version: String,
    @SerialName("island_total_shippable_m") val islandTotalShippableM: Double,
    val trails: List<TrailMeta>,
)

@Serializable
private data class VersionOnly(val version: String)

@Serializable
private data class SegmentsFile(val version: String, val segments: List<SegmentGeom>)

/** Parsed trails.json + coverage_segments.json. [islandTotalM] is the shippable total. */
class Dataset(
    val trails: List<TrailMeta>,
    val islandTotalM: Double,
    val segments: List<SegmentGeom>,
    /** Dataset build version (trails.json "version"); empty for hand-built test datasets. */
    val version: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(trailsJson: String, coverageSegmentsJson: String): Dataset {
            val s = parseSegments(coverageSegmentsJson)
            return build(trailsJson, s.version, s.segments)
        }

        /** coverage_segments.json alone (version + segments), e.g. to write [SegmentsBinary]. */
        fun parseSegments(coverageSegmentsJson: String): SegmentsBinary.Decoded {
            val s = json.decodeFromString(SegmentsFile.serializer(), coverageSegmentsJson)
            return SegmentsBinary.Decoded(s.version, s.segments)
        }

        /** As [parse], with coverage_segments.bin ([SegmentsBinary]) in place of the JSON; same result, much faster. */
        fun parse(trailsJson: String, coverageSegmentsBin: ByteArray): Dataset {
            val s = SegmentsBinary.decode(coverageSegmentsBin)
            return build(trailsJson, s.version, s.segments)
        }

        /** The `version` of a trails.json document (cheap: no full parse of the trails). */
        fun trailsVersion(trailsJson: String): String = json.decodeFromString(VersionOnly.serializer(), trailsJson).version

        private fun build(trailsJson: String, segmentsVersion: String, segments: List<SegmentGeom>): Dataset {
            val t = json.decodeFromString(TrailsFile.serializer(), trailsJson)
            require(t.version == segmentsVersion) { "dataset version mismatch: trails=${t.version} segments=$segmentsVersion" }
            val segIds = segments.mapTo(HashSet()) { it.id }
            for (tr in t.trails) {
                val missing = tr.segmentIds.firstOrNull { it !in segIds }
                require(missing == null) { "trail ${tr.id} segment $missing missing from coverage segments" }
            }
            return Dataset(t.trails, t.islandTotalShippableM, segments, t.version)
        }
    }
}
