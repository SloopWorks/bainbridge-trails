package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.Fix
import com.bainbridgetrails.tracking.WalkRecord
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.time.Instant

/** One trail row of a walk's detail: before → after as stored in the walk record (P1-6). */
data class WalkTrailRow(val trailId: String, val name: String, val before: Double, val after: Double)

/** Walk detail rows: trails this walk completed, then every other trail it added to (Finalizer order kept). */
data class WalkTrailSections(val completed: List<WalkTrailRow>, val rest: List<WalkTrailRow>) {
    val isEmpty: Boolean get() = completed.isEmpty() && rest.isEmpty()
}

/**
 * Walk detail (P1-6, read-only results). Everything comes from the device: the walk record (`walks.json`, kept for
 * good) and, while it still exists, the raw session file (deleted 30 days after the walk). Nothing is synced.
 */
object WalkDetailLogic {
    /** Fixes less accurate than this are left out of the drawn track (they jump around). */
    const val MAX_ACCURACY_M = 50.0

    /** A pause longer than this, or a jump longer than [MAX_JUMP_M], starts a new piece (no straight line across). */
    const val MAX_GAP_MS = 120_000L
    const val MAX_JUMP_M = 250.0

    fun sections(walk: WalkRecord, nameOf: (String) -> String?): WalkTrailSections {
        val rows = walk.trails.mapNotNull { t -> nameOf(t.trailId)?.let { WalkTrailRow(t.trailId, it, t.before, t.after) } }
        val done = walk.newlyCompleted.toSet()
        return WalkTrailSections(rows.filter { it.trailId in done }, rows.filter { it.trailId !in done })
    }

    /**
     * The raw track as drawable pieces of [lon, lat] (for [com.bainbridgetrails.map.multiLineGeoJson]): inaccurate
     * fixes dropped, split at long gaps and jumps, pieces under two points dropped. Only ever drawn on this device.
     */
    fun trackPieces(fixes: List<Fix>): List<List<List<Double>>> {
        val out = ArrayList<List<List<Double>>>()
        var cur = ArrayList<List<Double>>()
        var last: Fix? = null
        for (f in fixes) {
            val acc = f.accuracyM
            if (acc != null && acc > MAX_ACCURACY_M) continue
            val p = last
            if (p != null && (f.timeMs - p.timeMs > MAX_GAP_MS || metres(p, f) > MAX_JUMP_M)) {
                if (cur.size >= 2) out += cur
                cur = ArrayList()
            }
            cur += listOf(f.lon, f.lat)
            last = f
        }
        if (cur.size >= 2) out += cur
        return out
    }

    /** Start time of day for the detail subtitle: "8:05 AM". */
    fun startTime(epochMs: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
        return "$h12:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "AM" else "PM"}"
    }

    private fun metres(a: Fix, b: Fix): Double {
        val k = cos((a.lat + b.lat) / 2 * kotlin.math.PI / 180)
        return hypot((b.lon - a.lon) * 111_320.0 * k, (b.lat - a.lat) * 110_574.0)
    }
}
