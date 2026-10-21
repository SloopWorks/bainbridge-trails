package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.play.time.TideTable
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.math.PI
import kotlin.math.acos
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The kraken's tide rule (PD-8, product T-2, schedule.md §1.6): a stretch is active only while the predicted height at
 * Eagle Harbor (NOAA 9445882) is ≤ `rules.krakenMaxTideM` (2.44 m = 8 ft MLLW). No table, or a date the bundled
 * years do not cover, keeps every stretch inactive (fail closed). Heights are the table's cosine interpolation.
 */
object TideGate {
    enum class State { LOW, HIGH, UNKNOWN }

    fun state(tides: TideTable?, at: Instant, maxM: Double): State {
        val h = tides?.heightAt(at) ?: return State.UNKNOWN
        return if (h <= maxM) State.LOW else State.HIGH
    }

    /**
     * The parts of [span] where the tide is ≤ [maxM], solved exactly between adjacent extrema (the cosine is monotonic
     * there). Empty when the table is missing or does not cover the whole span.
     */
    fun lowSpans(tides: TideTable?, span: Span, maxM: Double): List<Span> {
        if (tides == null || span.isEmpty) return emptyList()
        if (tides.heightAt(span.start) == null || tides.heightAt(span.end) == null) return emptyList()
        val first = IslandTime.dateOf(span.start)
        val last = IslandTime.dateOf(span.end)
        val ext = ArrayList<Pair<Instant, Double>>()
        var d = first.plus(-1, DateTimeUnit.DAY)
        val stop = last.plus(1, DateTimeUnit.DAY)
        while (d <= stop) {
            tides.events(d)?.forEach { ext += it.at to it.heightM }
            d = d.plus(1, DateTimeUnit.DAY)
        }
        if (ext.size < 2 || ext.first().first > span.start || ext.last().first < span.end) return emptyList()
        val out = ArrayList<Span>()
        fun add(s: Instant, e: Instant) {
            val cs = maxOf(s, span.start)
            val ce = minOf(e, span.end)
            if (ce <= cs) return
            val prev = out.lastOrNull()
            if (prev != null && prev.end >= cs) out[out.lastIndex] = Span(prev.start, maxOf(prev.end, ce)) else out += Span(cs, ce)
        }
        for (i in 0 until ext.lastIndex) {
            val (ta, ha) = ext[i]
            val (tb, hb) = ext[i + 1]
            if (tb <= span.start || ta >= span.end) continue
            when {
                ha <= maxM && hb <= maxM -> add(ta, tb)
                ha > maxM && hb > maxM -> Unit
                else -> {
                    // h(f) = ha + (hb − ha)(1 − cos πf)/2 = maxM
                    val c = (1 - 2 * (maxM - ha) / (hb - ha)).coerceIn(-1.0, 1.0)
                    val f = acos(c) / PI
                    val cross = ta + ((tb - ta).inWholeMilliseconds * f).toLong().milliseconds
                    if (ha <= maxM) add(ta, cross) else add(cross, tb)
                }
            }
        }
        return out
    }
}
