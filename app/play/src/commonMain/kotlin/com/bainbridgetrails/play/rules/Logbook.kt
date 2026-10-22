@file:Suppress("EnumEntryName", "EnumNaming") // enum constants are the stored keywords (product §3.8)

package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.time.IslandTime
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * What a Logbook entry records (product §3.8 `kind`). [restored] (§3.6.5) and [visit] (a remembrance visit, §4.6.3,
 * no number at all) complete the product list; [bramble], [horn] and [wave] are declared ahead of the content that
 * writes them (none yet; they have no [LogCopy] line), so a Logbook holding one still decodes.
 *
 * Play trails v1.1 (plan D1, D7) appends [climb] (W-CLIMB), [high_ground] (W-STEEP) and [start_trails] (the trails
 * rules' start-balance sibling, ux § 6); W-MILE / W-PARK1 / W-PARKC / W-ISLAND use [milestone] / [park_first] /
 * [park_complete] / [island]. New kinds only ever append: the names are the stored keywords, and a v1 build cannot
 * decode a Logbook holding one (local Play state is lost on downgrade, D7).
 */
@Serializable
enum class LogKind {
    coverage, complete, park_first, park_complete, milestone, island, square, day, week,
    treasure, egg, riddle, ink, retreat, bramble, lore, horn, challenge, island_challenge, wave,
    start_balance, restored, stamp, visit,
    climb, high_ground, start_trails,
    ;

    /** Found marks (F-*, C-*): what the daily cap (§3.5) sums. */
    val found: Boolean get() = this in FOUND

    /** Walk-derived marks a snapshot recomputes (W-COV, W-DONE, W-SQUARE, the start balance). */
    val walkDerived: Boolean get() = this == coverage || this == complete || this == square || this == start_balance

    private companion object {
        val FOUND = setOf(treasure, egg, riddle, ink, retreat, bramble, lore, horn, challenge, island_challenge, wave)
    }
}

/** Sync state of an entry (§3.8 `board`); only [local_only] shows a note. */
@Serializable
enum class BoardState { pending, accepted, local_only }

/**
 * One Logbook entry (product §3.8): what, why, when and how many. [date] is the island date only: Play never stores a
 * time of day (PF11-3; the week id derives from it). [what] / [why] are resolved display text; [walkId] links the
 * walk (local); [contentId] is the occurrence key (or a feature id for `complete`), never sent with text or marks.
 * [capped]: found, but the daily cap paid 0 (`play_log_cap`). [band]: the F-INK bucket.
 */
@Serializable
data class LogEntry(
    val id: String,
    val kind: LogKind,
    val what: String,
    val why: String,
    val date: LocalDate,
    val marks: Int,
    val walkId: String? = null,
    val contentId: String? = null,
    val board: BoardState = BoardState.pending,
    val capped: Boolean = false,
    val band: Int? = null,
) {
    init {
        require(marks >= 0) { "marks never decrease (product §3.1): $marks" }
    }

    /** That week's Monday (README glossary). */
    val weekId: LocalDate get() = IslandTime.weekId(date)

    /** The note shown under the line, if any. */
    val noteKey: String?
        get() = when {
            capped -> FoundCaps.CAP_COPY_KEY
            board == BoardState.local_only -> "play_log_local_only"
            else -> null
        }
}

/**
 * The append-only Logbook of one goal (product §3.8). Adding is idempotent per (walk, content): an entry whose [LogEntry.id]
 * exists, or whose `(kind, contentId)` exists, or (no content) whose `(kind, walkId)` exists, is ignored, so re-running
 * a walk's claims never adds marks twice. Totals are always the sum of the entries (PF11-4).
 */
@Serializable
data class Logbook(val entries: List<LogEntry> = emptyList()) {
    val total: Int get() = entries.sumOf { it.marks }

    fun weekTotal(weekId: LocalDate): Int = entries.filter { it.weekId == weekId }.sumOf { it.marks }

    /** Found marks (F-*, C-*) logged on island [date]: the daily cap's running total. */
    fun foundOn(date: LocalDate): Int = entries.filter { it.date == date && it.kind.found }.sumOf { it.marks }

    /** W-DAY was already paid on [date]. */
    fun hasDay(date: LocalDate): Boolean = entries.any { it.kind == LogKind.day && it.date == date }

    val hasStartBalance: Boolean get() = entries.any { it.kind == LogKind.start_balance }

    val hasRestore: Boolean get() = entries.any { it.kind == LogKind.restored }

    fun contains(entry: LogEntry): Boolean = entries.any { e ->
        e.id == entry.id ||
            (entry.contentId != null && e.kind == entry.kind && e.contentId == entry.contentId) ||
            (entry.contentId == null && entry.walkId != null && e.contentId == null && e.kind == entry.kind && e.walkId == entry.walkId)
    }

    fun add(entry: LogEntry): Logbook = if (contains(entry)) this else Logbook(entries + entry)

    /** Adds a found-marks entry after the daily cap (PF5-7): over the cap it is logged with 0 marks and `capped`. */
    fun addFind(entry: LogEntry, cap: Int = com.bainbridgetrails.play.config.Caps().foundPerDay): Logbook {
        if (contains(entry)) return this
        val c = if (entry.kind.found) FoundCaps.apply(foundOn(entry.date), entry.marks, cap) else Capped(entry.marks, false)
        return Logbook(entries + entry.copy(marks = c.marks, capped = c.capped))
    }
}
