package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.TrailProgress
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.tracking.WalkRecord
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/** Me numbers (product F7, §6). Island % and trails completed come from the snapshot; walks are this phone's. */
data class MeStats(
    val islandFraction: Double,
    val trailsCompleted: Int,
    /** N = named trails in the dataset (never hard-coded). */
    val trailsTotal: Int,
    val trailMetresWalked: Double,
    val walkCount: Int,
) {
    /** Nothing walked anywhere yet (new-user hero copy). */
    val isNew: Boolean get() = islandFraction <= 0.0 && trailsCompleted == 0
}

/** One walk-history row, already reduced to what the row shows. */
data class WalkRowData(
    val sessionId: String,
    val startedAt: Long,
    val durationMs: Long,
    val distanceM: Double,
    val trailCount: Int,
    /** First two touched trail names (highest resulting fraction first, as the Finalizer orders them). */
    val trailNames: List<String>,
    val verified: Boolean,
)

object MeLogic {
    /** In progress (F7 step 4): 0 < fraction and not Complete, closest to done first, ties by name. */
    fun inProgress(trails: List<TrailProgress>): List<TrailProgress> =
        trails.filter { it.fraction > 0.0 && it.status != TrailStatus.COMPLETE }
            .sortedWith(compareByDescending<TrailProgress> { it.fraction }.thenBy { TrailListLogic.fold(it.name) })

    fun stats(islandFraction: Double, trailsCompleted: Int, trailsTotal: Int, walks: List<WalkRecord>): MeStats =
        MeStats(islandFraction, trailsCompleted, trailsTotal, walks.sumOf { it.matchedM }, walks.size)

    /** History rows, newest first. [nameOf] maps a trail id to its name (unknown ids are skipped). */
    fun walkRows(walks: List<WalkRecord>, nameOf: (String) -> String?): List<WalkRowData> =
        walks.sortedByDescending { it.startedAt }.map { w ->
            WalkRowData(
                sessionId = w.sessionId,
                startedAt = w.startedAt,
                durationMs = w.durationMs,
                distanceM = w.distanceM,
                trailCount = w.trails.size,
                trailNames = w.trails.asSequence().mapNotNull { nameOf(it.trailId) }.take(2).toList(),
                verified = w.verified,
            )
        }
}

/** Formatting for the list screens (decisions.md P2: short lengths in feet/metres, no "~"). */
object ListFormat {
    private const val METRES_PER_MILE = 1609.344
    private const val FEET_PER_METRE = 3.28084

    /** A short length: feet under 0.1 mi (metres under 0.1 km), else [Format.distance]. */
    fun shortLength(metres: Double, units: Units): ShortLength = when (units) {
        Units.MILES -> if (metres / METRES_PER_MILE < 0.1) ShortLength.Feet(roundTo(metres * FEET_PER_METRE, 10)) else ShortLength.Regular(Format.distance(metres, units))
        Units.KILOMETRES -> if (metres < 100.0) ShortLength.Metres(roundTo(metres, 5)) else ShortLength.Regular(Format.distance(metres, units))
    }

    fun trailLength(miles: Double, units: Units): ShortLength = shortLength(miles * METRES_PER_MILE, units)

    private fun roundTo(v: Double, step: Int): Int = ((v / step).roundToInt() * step).coerceAtLeast(step)

    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "Sat, Oct 18" in [zone]; the year is added when it isn't the current one ("Sat, Oct 18, 2025"). */
    fun walkDate(epochMs: Long, nowMs: Long, zone: TimeZone): String {
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val base = "${DAYS[d.dayOfWeek.ordinal]}, ${MONTHS[d.month.ordinal]} ${d.day}"
        return if (d.year == now.year) base else "$base, ${d.year}"
    }

    /** Walk-history duration, minute precision: "40 min", "1 hr 6 min", "2 hr" ("<1 min" under a minute). */
    fun walkDuration(ms: Long): String {
        val totalMin = ms.coerceAtLeast(0) / 60_000
        if (totalMin == 0L) return "<1 min"
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h hr"
            else -> "$h hr $m min"
        }
    }

    /** Relative age of a timestamp, bucketed for "Updated …" lines. */
    fun age(thenMs: Long, nowMs: Long): Age {
        val min = ((nowMs - thenMs).coerceAtLeast(0) / 60_000).toInt()
        return when {
            min < 1 -> Age.JustNow
            min < 60 -> Age.Minutes(min)
            min < 24 * 60 -> Age.Hours(min / 60)
            else -> Age.Days(min / (24 * 60))
        }
    }

    sealed interface Age {
        data object JustNow : Age
        data class Minutes(val n: Int) : Age
        data class Hours(val n: Int) : Age
        data class Days(val n: Int) : Age
    }

    sealed interface ShortLength {
        data class Feet(val n: Int) : ShortLength
        data class Metres(val n: Int) : ShortLength
        data class Regular(val text: String) : ShortLength
    }
}
