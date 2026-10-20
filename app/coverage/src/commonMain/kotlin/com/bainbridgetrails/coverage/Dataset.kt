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
    /** Park or preserve the trail is in; null = not in a park (Trails list "Not in a park" filter). */
    val park: String? = null,
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
            val t = json.decodeFromString(TrailsFile.serializer(), trailsJson)
            val s = json.decodeFromString(SegmentsFile.serializer(), coverageSegmentsJson)
            require(t.version == s.version) { "dataset version mismatch: trails=${t.version} segments=${s.version}" }
            return Dataset(t.trails, t.islandTotalShippableM, s.segments, t.version).validated()
        }

        /** As [parse], from dataset.bin ([DatasetBinary]); same result, much faster. */
        fun fromBinary(datasetBin: ByteArray): Dataset = DatasetBinary.decode(datasetBin).validated()

        /** The `version` of a trails.json document (no trail objects built). */
        fun trailsVersion(trailsJson: String): String = json.decodeFromString(VersionOnly.serializer(), trailsJson).version

        private fun Dataset.validated(): Dataset {
            val segIds = segments.mapTo(HashSet()) { it.id }
            for (tr in trails) {
                val missing = tr.segmentIds.firstOrNull { it !in segIds }
                require(missing == null) { "trail ${tr.id} segment $missing missing from coverage segments" }
            }
            return this
        }
    }
}
