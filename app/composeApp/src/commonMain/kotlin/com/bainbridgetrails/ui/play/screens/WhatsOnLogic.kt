package com.bainbridgetrails.ui.play.screens

import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.play.schedule.Gate
import com.bainbridgetrails.play.schedule.Occurrence
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.ui.play.components.PlayFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** What a What's On row is about (ux.md § 3.15). */
enum class WhatsOnKind { Kraken, Treasures, Riddle, Challenge, Memorial, Other }

/** The trailing glyph of a WhatsOnRow (ux.md § 2.8): wave = waits for the tide, moon = asleep until daylight. */
enum class WhatsOnGate { Chevron, Wave, Moon, None }

/**
 * One row of Today. [key] is the occurrence key (the tap target); [start] / [end] the window (island instants; null
 * end = permanent, e.g. a riddle). [count] / [found]: a treasure set's size and how many you found. [stars]: riddle
 * difficulty. [marks]: the reward, never for remembrance.
 */
data class WhatsOnRowModel(
    val key: String,
    val itemId: String,
    val kind: WhatsOnKind,
    val title: String,
    val start: Instant?,
    val end: Instant?,
    val gate: WhatsOnGate,
    val count: Int? = null,
    val found: Int? = null,
    val stars: Int? = null,
    val marks: Int? = null,
)

/** A Coming up row: the day it starts and the item's pack title. */
data class ComingRowModel(val key: String, val itemId: String, val kind: WhatsOnKind, val title: String, val start: Instant)

data class WhatsOnModel(
    val weekOf: LocalDate,
    val dawn: Instant?,
    val dusk: Instant?,
    val nextFerry: Instant?,
    val today: List<WhatsOnRowModel>,
    val coming: List<ComingRowModel>,
    val quietDay: Boolean,
)

/**
 * What's On (ux.md § 3.15; schedule SD-10, § 5.2): a direct rendering of `PlayScheduler.agenda` — Today =
 * `agenda.active`, Coming up = `agenda.upcoming`, the day line = `agenda.day`. It adds no item, rule or window of its
 * own; lore, eggs and the ferry sprite are never rows (eggs are never listed); a weekly treasure set is one row. On the
 * quiet day (Mar 30) there is one row, the memorial card, and nothing else. Times are island time; [windowText]
 * appends `play_island_time` when the phone's zone differs.
 */
object WhatsOnLogic {
    private val LISTED = setOf(ItemType.treasure, ItemType.obstacle, ItemType.riddle, ItemType.challenge)

    fun model(agenda: PlayAgenda, config: PlayConfig, goal: Goal, now: Instant): WhatsOnModel {
        val items = config.items.associateBy { it.id }
        val weekOf = IslandTime.weekId(agenda.day.date)
        if (agenda.day.quietDay) {
            val memorial = config.items.firstOrNull { it.enabled && it.type == ItemType.lore && it.lore == Marks.REMEMBRANCE && goal in it.goals }
            val rows = listOfNotNull(
                memorial?.let { WhatsOnRowModel(it.id, it.id, WhatsOnKind.Memorial, it.copy.title, null, null, WhatsOnGate.None) },
            )
            return WhatsOnModel(weekOf, null, null, null, rows, emptyList(), quietDay = true)
        }
        val rows = ArrayList<WhatsOnRowModel>()
        val seen = HashSet<String>()
        for (a in agenda.active) {
            val o = a.occurrence
            if (o.type !in LISTED || o.hidden) continue
            val item = items[o.itemId] ?: continue
            if (!seen.add(o.itemId)) continue
            val kind = kindOf(item)
            rows += WhatsOnRowModel(
                key = o.key,
                itemId = o.itemId,
                kind = kind,
                title = item.copy.title,
                start = o.start,
                end = o.end,
                gate = if (kind == WhatsOnKind.Challenge) WhatsOnGate.Chevron else gateOf(a.gate),
                count = if (kind == WhatsOnKind.Treasures) o.placements.size.takeIf { it > 0 } ?: item.tiers.sumOf { it.count } else null,
                found = if (kind == WhatsOnKind.Treasures) a.found.size else null,
                stars = item.stars,
                marks = marksOf(item, o),
            )
        }
        val coming = agenda.upcoming.filter { it.type in LISTED }.mapNotNull { u ->
            val item = items[u.itemId] ?: return@mapNotNull null
            ComingRowModel(u.key, u.itemId, kindOf(item), item.copy.title, u.start)
        }
        return WhatsOnModel(weekOf, agenda.day.sun.dawn, agenda.day.sun.dusk, agenda.day.nextFerryArrival, rows, coming, quietDay = false)
    }

    private fun kindOf(item: Item): WhatsOnKind = when (item.type) {
        ItemType.treasure -> WhatsOnKind.Treasures
        ItemType.obstacle -> if (item.lore == "kraken") WhatsOnKind.Kraken else WhatsOnKind.Other
        ItemType.riddle -> WhatsOnKind.Riddle
        ItemType.challenge -> WhatsOnKind.Challenge
        ItemType.lore -> if (item.lore == Marks.REMEMBRANCE) WhatsOnKind.Memorial else WhatsOnKind.Other
        else -> WhatsOnKind.Other
    }

    private fun marksOf(item: Item, o: Occurrence): Int? = when {
        item.lore == Marks.REMEMBRANCE -> null
        o.marks > 0 -> o.marks
        item.marks > 0 -> item.marks
        else -> null
    }

    fun gateOf(g: Gate): WhatsOnGate = when (g) {
        Gate.TIDE_HIGH, Gate.TIDE_UNKNOWN -> WhatsOnGate.Wave
        Gate.SLEEPING -> WhatsOnGate.Moon
        Gate.OPEN, Gate.VIEW -> WhatsOnGate.Chevron
    }

    /** A riddle's difficulty as drawn ("★★"); never spoken: [spoken] uses `play_riddle_stars_a11y` instead. */
    fun starsText(stars: Int): String = "★".repeat(stars.coerceAtLeast(0))

    /**
     * A row's merged sentence: title, then the window, or for a riddle the difficulty in words ([starsSpoken] =
     * `play_riddle_stars_a11y`, "Difficulty 2 stars") so a screen reader never reads "black star, black star".
     */
    fun spoken(title: String, window: String?, starsSpoken: String?): String = listOfNotNull(title, starsSpoken ?: window).joinToString(", ")

    /** Island clock time ("6 am", "7:31 am"). */
    fun timeText(at: Instant): String = PlayFormat.time(at)

    /** Island day ("Sat"). */
    fun dayText(at: Instant): String = PlayFormat.day(at)

    /** [text] + `play_island_time` ([template]) when [phone]'s clock differs from the island's at [now]. */
    fun withIslandSuffix(text: String, phone: TimeZone, now: Instant, template: String): String =
        PlayFormat.islandSuffix(text, phone, now, template)

    /**
     * A row's window (`play_challenge_window`): "Thu 6 am – Sun 8 pm"; whole-day windows as days only ("Sat – Sun",
     * a window ending at midnight ends the day before); null when there is no window (riddles are permanent).
     */
    fun windowText(start: Instant?, end: Instant?, phone: TimeZone, now: Instant, islandTemplate: String): String? {
        if (start == null || end == null) return null
        val s = start.toLocalDateTime(IslandTime.zone)
        val e = end.toLocalDateTime(IslandTime.zone)
        val wholeDays = s.hour == 0 && s.minute == 0 && e.hour == 0 && e.minute == 0
        val text = if (wholeDays) {
            "${PlayFormat.day(start)} – ${PlayFormat.day(end - 1.milliseconds)}"
        } else {
            "${PlayFormat.day(start)} ${PlayFormat.time(start)} – ${PlayFormat.day(end)} ${PlayFormat.time(end)}"
        }
        return if (wholeDays) text else withIslandSuffix(text, phone, now, islandTemplate)
    }
}
