package com.bainbridgetrails.play

import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.data.DatasetProfile
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.schedule.AllTrailheadsRef
import com.bainbridgetrails.play.schedule.FeatureInfo
import com.bainbridgetrails.play.schedule.FeatureRef
import com.bainbridgetrails.play.schedule.GeoPoint
import com.bainbridgetrails.play.schedule.ParkRef
import com.bainbridgetrails.play.schedule.PlaceIndex
import com.bainbridgetrails.play.schedule.RoadRef
import com.bainbridgetrails.play.schedule.SegRef
import com.bainbridgetrails.play.schedule.StretchRef
import com.bainbridgetrails.play.schedule.TrailRef
import com.bainbridgetrails.play.schedule.TrailheadRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The Play goal of a dataset profile (one goal per app run, RD-1). */
val DatasetProfile.playGoal: Goal get() = if (this == DatasetProfile.ROADS) Goal.roads else Goal.trails

/**
 * `:play`'s [PlaceIndex] over the running goal's bundled dataset (`DatasetRepository`'s parsed [Dataset]) and the pack
 * build's derived table for that goal (`files/play/derived-<goal>.json`): coastal stretches (`stretches.<featureId>
 * .segments[][0]`) and the `roadSafety` pass list (`roadSafety.ids`). The app's road rows carry no `public` field, so a
 * road on the pass list reads `public = true` and any other `null` (the rule then fails it, as the build did).
 *
 * Only [goal]'s dataset is on the phone: refs of the other goal resolve to nothing. Trailheads carry no ids in the app
 * data and no v1 pack uses `trailhead:` refs, so [point] is null. Built lazily on first use (≈ 2–3 k segments).
 */
class DatasetPlaceIndex(
    val goal: Goal,
    private val dataset: Dataset,
    /** `derived-<goal>.json` text; null = no derived table (stretches resolve to the whole feature). */
    derivedJson: String?,
) : PlaceIndex {
    private val derived: JsonObject? = derivedJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private val roadPass: Set<String> by lazy {
        (derived?.get("roadSafety") as? JsonObject)?.get("ids")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
    }

    private val stretches: Map<String, List<String>>? by lazy {
        (derived?.get("stretches") as? JsonObject)?.mapValues { (_, v) ->
            (v.jsonObject["segments"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content }
        }
    }

    private val features: Map<String, FeatureInfo> by lazy {
        dataset.trails.associate { t ->
            t.id to FeatureInfo(
                id = t.id, goal = goal, counted = t.counted, category = t.category,
                public = if (goal == Goal.roads && t.id in roadPass) true else null,
                ownership = t.ownership, speedMph = t.speedMph, park = t.park,
            )
        }
    }

    private val featureSegments: Map<String, List<String>> by lazy { dataset.trails.associate { it.id to it.segmentIds } }

    private val segments: Map<String, SegRef> by lazy {
        val speed = HashMap<String, Int?>()
        if (goal == Goal.roads) for (t in dataset.trails) for (s in t.segmentIds) speed[s] = t.speedMph
        dataset.segments.associate { s ->
            s.id to SegRef.fromLine(s.id, s.coords.map { GeoPoint(lat = it[1], lon = it[0]) }, speed[s.id], s.category)
        }
    }

    override fun feature(id: String): FeatureInfo? = features[id]

    override fun features(goal: Goal): List<FeatureInfo> = if (goal == this.goal) features.values.toList() else emptyList()

    override fun segments(ref: FeatureRef): List<SegRef> {
        if (ref.goal != null && ref.goal != goal) return emptyList()
        return when (ref) {
            is TrailRef -> segsOf(ref.id)
            is RoadRef -> segsOf(ref.id)
            is ParkRef -> features.values.filter { it.park == ref.name }.flatMap { segsOf(it.id) }
            is StretchRef -> {
                if (ref.featureId !in features) return emptyList()
                val s = stretches ?: return segsOf(ref.featureId) // no derived table: the whole feature (a superset)
                s[ref.featureId].orEmpty().mapNotNull { segments[it] }
            }
            is TrailheadRef, AllTrailheadsRef -> emptyList()
        }
    }

    override fun point(ref: FeatureRef): GeoPoint? = null

    private fun segsOf(featureId: String): List<SegRef> =
        if (featureId !in features) emptyList() else featureSegments[featureId].orEmpty().mapNotNull { segments[it] }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
