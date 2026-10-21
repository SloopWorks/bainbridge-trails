package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.Recurrences
import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * schedule.md §5.4: the agenda of the v1 pack over the real datasets, 2026-10-26 → 2027-12-31, one row per island day
 * per dated occurrence (`src/jvmTest/resources/golden/agenda-<goal>.tsv`). Permanent occurrences never change and are
 * listed once in a header block; the ferry sprite (ambient decor on a placeholder timetable) is left out. The PR diff
 * is the review artifact: regenerate with `cd app && ./gradlew :play:jvmTest -Pplay.updateGolden=true`.
 * Skipped without the (gitignored) datasets.
 */
class PlaySchedulerGoldenTest {
    @Test
    fun `agenda trails golden`() = check(Goal.trails)

    @Test
    fun `agenda roads golden`() = check(Goal.roads)

    private fun check(goal: Goal) {
        val s = RealPack.withData("PlaySchedulerGoldenTest")
        val text = agenda(s, goal, FIRST, LAST)
        val file = File("src/jvmTest/resources/golden/agenda-${goal.name}.tsv")
        if (RealPack.update) file.writeText(text)
        assertTrue(file.exists(), "${file.path} missing: run with -Pplay.updateGolden=true")
        assertEquals(file.readText(), text, "agenda-${goal.name}.tsv drifted: review and regenerate with -Pplay.updateGolden=true")
    }

    companion object {
        val FIRST = LocalDate(2026, 10, 26)
        val LAST = LocalDate(2027, 12, 31)

        fun agenda(s: PlayScheduler, goal: Goal, first: LocalDate, last: LocalDate): String {
            val sb = StringBuilder()
            sb.append("# agenda-${goal.name}.tsv: PlayScheduler over pack ${s.config.version}, $first → $last, island time (PlaySchedulerGoldenTest)\n")
            sb.append("# regenerate: cd app && ./gradlew :play:jvmTest -Pplay.updateGolden=true\n")
            sb.append("# resolvable = window ∩ civil daylight ∩ playHours (∩ tide ≤ ${s.config.rules.krakenMaxTideM} m for the kraken); view = never sensed\n")
            sb.append("# permanent\tocc\tstart\tresolvable\tplacements\n")
            val firstPlan = s.dayPlan(first, goal)
            for (o in firstPlan.occurrences.filter { it.end == null }.sortedBy { it.key }) {
                sb.append("permanent\t${o.key}\t${Recurrences.islandIso(o.start)}\t${spans(o)}\t${placements(o)}\n")
            }
            sb.append("# date\tocc\tstart\tend\tresolvable\tplacements (= as on the previous row of this occurrence)\n")
            val last1 = HashMap<String, String>()
            var d = first
            while (d <= last) {
                val plan = s.dayPlan(d, goal)
                if (plan.quietDay) sb.append("$d\tquiet-day\t\t\t\t${plan.occurrences.joinToString(" ") { it.key }}\n")
                if (plan.dropped.isNotEmpty()) sb.append("$d\tdensity-dropped\t\t\t\t${plan.dropped.joinToString(" ")}\n")
                for (o in plan.occurrences.filter { it.end != null && it.type != ItemType.ambient }.sortedWith(compareBy({ it.start }, { it.key }))) {
                    val p = placements(o)
                    val shown = if (last1[o.key] == p) "=" else p
                    last1[o.key] = p
                    sb.append("$d\t${o.key}\t${Recurrences.islandIso(o.start)}\t${Recurrences.islandIso(o.end!!)}\t${spans(o)}\t$shown\n")
                }
                d = d.plus(1, DateTimeUnit.DAY)
            }
            return sb.toString()
        }

        private fun hhmm(t: kotlin.time.Instant): String {
            val l = t.toLocalDateTime(IslandTime.zone)
            return "${l.hour.toString().padStart(2, '0')}:${l.minute.toString().padStart(2, '0')}"
        }

        private fun spans(o: Occurrence): String = when {
            !o.sensable -> "view"
            o.resolvable.isEmpty() -> "-"
            else -> o.resolvable.joinToString(",") { "${hhmm(it.start)}-${hhmm(it.end)}" }
        }

        fun placement(p: ResolvedPlacement): String = buildString {
            if ('#' in p.key) append('#').append(p.key.substringAfter('#')).append(' ')
            if (p.featureId == null || p.ref.startsWith("stretch:")) append(p.ref) else append(p.featureId)
            if (p.featureId != null && !p.ref.startsWith("stretch:") && p.segmentIds.size == 1) append('/').append(p.segmentIds[0])
            p.tier?.let { append(' ').append(it.name) }
            if (p.point != null && !p.ref.startsWith("place:")) append(" %.5f,%.5f".format(p.point.lat, p.point.lon))
            p.square?.let { append(" [").append(it).append(']') }
        }

        private fun placements(o: Occurrence): String = if (o.placements.isEmpty()) "-" else o.placements.joinToString("; ") { placement(it) }
    }
}
