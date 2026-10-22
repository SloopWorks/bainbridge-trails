package com.bainbridgetrails.play

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.schedule.FeatureInfo
import com.bainbridgetrails.play.schedule.FeatureRef
import com.bainbridgetrails.play.schedule.PlaceIndex
import com.bainbridgetrails.play.schedule.SegRef
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.TideTable
import kotlinx.datetime.LocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/**
 * The bundled Play files (`composeResources/files/play/`), read by path from the Gradle test working directory
 * (`app/composeApp`), and small edits of the bundled pack for the content and repository tests.
 */
internal object PlayFixtures {
    private const val DIR = "src/commonMain/composeResources/files/play"

    fun read(name: String): String = SystemFileSystem.source(Path("$DIR/$name")).buffered().use { it.readString() }

    val pack: String by lazy { read("play-content.json") }
    val bundledVersion: String get() = "2026.11.2"

    fun tideText(year: Int): String? = runCatching { read("tides/9445882-$year.json") }.getOrNull()

    val tides: TideTable by lazy { TideTable.parse(*(2026..2029).map { tideText(it)!! }.toTypedArray()) }

    /** The bundled pack with top-level fields replaced (`version`, `kill`, `tz`, …). */
    fun packWith(vararg fields: Pair<String, JsonElement>): String {
        val root = Json.parseToJsonElement(pack).jsonObject
        return JsonObject(root + fields.toMap()).toString()
    }

    fun version(v: String) = "version" to JsonPrimitive(v)

    fun killItems(vararg ids: String) =
        "kill" to JsonObject(mapOf("all" to JsonPrimitive(false), "types" to JsonArray(emptyList()), "items" to JsonArray(ids.map { JsonPrimitive(it) })))

    /** Island wall time → instant (`2026-10-29T14:00` = 21:00Z). */
    fun island(text: String): Instant {
        val ldt = LocalDateTime.parse(text)
        return IslandTime.instantOf(ldt.date, ldt.time)
    }

    /** No dataset: every ref resolves to nothing (placements empty, occurrences and gates still computed). */
    object EmptyPlaces : PlaceIndex {
        override fun segments(ref: FeatureRef): List<SegRef> = emptyList()
        override fun point(ref: FeatureRef): GeoPoint? = null
        override fun feature(id: String): FeatureInfo? = null
        override fun features(goal: Goal): List<FeatureInfo> = emptyList()
    }
}
