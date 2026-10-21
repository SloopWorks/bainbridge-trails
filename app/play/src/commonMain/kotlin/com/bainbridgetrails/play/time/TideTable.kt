package com.bainbridgetrails.play.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Instant

/** One predicted extremum. [heightM] is metres above MLLW. */
data class TideEvent(val at: Instant, val heightM: Double, val high: Boolean)

/**
 * The bundled NOAA high/low table (schedule.md §1.6, SD-2): `data/play/tides/<station>-<year>.json` from
 * scripts/build-tides.mjs. Events live in sorted primitive arrays (binary search, no per-lookup allocation beyond
 * the [events] result). Outside the loaded years everything returns null — tide-gated content fails closed.
 * Predictions: NOAA CO-OPS, not for navigation.
 */
class TideTable private constructor(
    val station: String,
    /** True when any loaded year is a placeholder, not NOAA predictions. */
    val isPlaceholder: Boolean,
    private val minutes: IntArray,   // epoch minutes (UTC)
    private val cm: IntArray,
    private val high: BooleanArray,
    private val coverStartMin: Int,
    private val coverEndMin: Int,    // exclusive
) {
    /** That island day's extrema in time order, or null when the day is not fully covered by the loaded years. */
    fun events(day: LocalDate, zone: TimeZone = IslandTime.zone): List<TideEvent>? {
        val start = day.atStartOfDayIn(zone).epochSeconds / 60
        val end = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds / 60
        if (start < coverStartMin || end > coverEndMin) return null
        var i = lowerBound(start.toInt())
        val out = ArrayList<TideEvent>(4)
        while (i < minutes.size && minutes[i] < end) {
            out += event(i)
            i++
        }
        return out
    }

    /** Predicted height (m, MLLW) at [t]: standard cosine interpolation between the bracketing extrema. */
    fun heightAt(t: Instant): Double? {
        val m = t.epochSeconds / 60.0 + t.nanosecondsOfSecond / 60e9
        if (minutes.isEmpty() || m < minutes[0] || m > minutes[minutes.size - 1]) return null
        var i = lowerBound(kotlin.math.floor(m).toInt() + 1) - 1  // last extremum at or before m
        if (i < 0) i = 0
        if (i >= minutes.size - 1) return cm[minutes.size - 1] / 100.0
        val a = minutes[i].toDouble()
        val b = minutes[i + 1].toDouble()
        val f = ((m - a) / (b - a)).coerceIn(0.0, 1.0)
        val ha = cm[i] / 100.0
        val hb = cm[i + 1] / 100.0
        return ha + (hb - ha) * (1 - cos(PI * f)) / 2
    }

    private fun event(i: Int) = TideEvent(Instant.fromEpochSeconds(minutes[i] * 60L), cm[i] / 100.0, high[i])

    /** First index with minutes[i] ≥ [m]. */
    private fun lowerBound(m: Int): Int {
        var lo = 0
        var hi = minutes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (minutes[mid] < m) lo = mid + 1 else hi = mid
        }
        return lo
    }

    @Serializable
    private class TideFile(
        val station: String,
        val datum: String,
        val units: String = "cm",
        val year: Int,
        val t0: String,
        val source: String = "",
        val e: List<JsonArray>,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses one or more consecutive years of one station (any order). */
        fun parse(vararg years: String): TideTable {
            require(years.isNotEmpty()) { "no tide years" }
            val files = years.map { json.decodeFromString(TideFile.serializer(), it) }.sortedBy { it.year }
            val station = files.first().station
            files.forEachIndexed { i, f ->
                require(f.station == station) { "mixed stations ${f.station} / $station" }
                require(f.datum == "MLLW" && f.units == "cm") { "${f.year}: datum ${f.datum} units ${f.units}" }
                require(f.t0 == "${f.year}-01-01T00:00Z") { "${f.year}: t0 ${f.t0}" }
                if (i > 0) require(f.year == files[i - 1].year + 1) { "tide years not consecutive: ${files.map { it.year }}" }
            }
            val n = files.sumOf { it.e.size }
            val minutes = IntArray(n)
            val cm = IntArray(n)
            val high = BooleanArray(n)
            var k = 0
            for (f in files) {
                var at = yearStartMin(f.year)
                for (ev in f.e) {
                    at += ev[0].jsonPrimitive.int
                    minutes[k] = at
                    cm[k] = ev[1].jsonPrimitive.int
                    high[k] = when (val type = ev[2].jsonPrimitive.content) {
                        "H" -> true
                        "L" -> false
                        else -> error("${f.year}: event type $type")
                    }
                    if (k > 0) require(minutes[k] > minutes[k - 1]) { "${f.year}: events out of order" }
                    k++
                }
            }
            return TideTable(
                station = station,
                isPlaceholder = files.any { it.source == "placeholder" },
                minutes = minutes, cm = cm, high = high,
                coverStartMin = yearStartMin(files.first().year),
                coverEndMin = yearStartMin(files.last().year + 1),
            )
        }

        private fun yearStartMin(year: Int): Int = (LocalDate(year, 1, 1).toEpochDays() * 1440L).toInt()
    }
}
