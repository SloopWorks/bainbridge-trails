package com.bainbridgetrails.play.rules

/**
 * One Logbook line's fixed text (product §3.8): [what] is null where the entry names its subject instead (an item's
 * title, a feature's name, a quiet zone's visit wording).
 */
data class LogLine(val what: String?, val why: String)

/**
 * Every Logbook entry's fixed English text, keyed by [LogKind] (the one table; product §3.8). The strings are stored
 * in each [LogEntry] as written, so they must never change for an existing kind: a Logbook written by an older build
 * reads the same (`LogCopyTest` pins them). [catchUp] is the second `coverage` line (walks while Play was off).
 */
object LogCopy {
    /** Treasure tier labels for persisted Logbook titles (same words as `play_tier_*`). */
    fun tier(name: String): String = when (name) {
        "sea_glass" -> "Sea glass"
        "agate" -> "Agate"
        "glass_float" -> "Glass float"
        "ships_bell" -> "Ship’s bell"
        else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Fills a pack title template (`{tier} on {place}`) for a persisted entry; unknown parts fall back to the template's words. */
    fun fillTitle(template: String, tier: String?, place: String?): String = template
        .replace("{tier}", tier ?: "Treasure")
        .replace("{place}", place ?: "the island")
        .trim()

    private val BY_KIND: Map<LogKind, LogLine> = mapOf(
        // Walk-derived (W-*), written on the phone.
        LogKind.coverage to LogLine("New ground", "New coverage on this walk"),
        LogKind.complete to LogLine(null, "Completed on this walk"),
        LogKind.square to LogLine("Fog lifted", "First coverage in a chart square"),
        LogKind.day to LogLine("Walking day", "First walk of the day, 10 minutes or more"),
        // Found (F-*, C-*): the item's title is the what.
        LogKind.treasure to LogLine(null, "Weekly treasure"),
        LogKind.egg to LogLine(null, "Hidden find"),
        LogKind.riddle to LogLine(null, "Riddle walked to"),
        LogKind.ink to LogLine(null, "Kraken stretch repelled"),
        LogKind.lore to LogLine(null, "Island story"),
        LogKind.challenge to LogLine(null, "Challenge complete"),
        // Server-only credits of a closed week (`GET /v1/play/me` `log`).
        LogKind.week to LogLine("Kept week", "Walking days on 2 or more days this week"),
        LogKind.retreat to LogLine("The kraken retreats", "Islanders sent it back"),
        LogKind.island_challenge to LogLine("Kraken week", "Island challenge complete"),
        // Balances.
        LogKind.start_balance to LogLine("Your walks so far", "Coverage, completions and squares from before Play"),
        LogKind.restored to LogLine("Restored from your account", "Your marks so far; earlier detail is not on this phone"),
        // No marks.
        LogKind.stamp to LogLine(null, "Riddle solved"),
        LogKind.visit to LogLine(null, "A quiet place. No marks here."),
    )

    /** The start balance's later catch-up: walk-derived marks earned while Play was off (a `coverage` entry). */
    val catchUp = LogLine("Walks while Play was off", "New coverage, completions and squares")

    /** Remembrance visit wording per quiet zone id (product §4.6.3 "Visited the Exclusion Memorial"); null = no stamp. */
    private val VISIT_WHAT = mapOf(
        "exclusion-memorial" to "Visited the Exclusion Memorial",
        "blakely-cemetery" to "Visited Port Blakely Cemetery",
    )

    /** The kinds with a line (v2 kinds have none yet). */
    val kinds: Set<LogKind> get() = BY_KIND.keys

    operator fun get(kind: LogKind): LogLine = BY_KIND[kind] ?: error("no Logbook copy for $kind")

    /** [kind]'s fixed what; for kinds that name their subject use [why] with the subject instead. */
    fun what(kind: LogKind): String = requireNotNull(get(kind).what) { "$kind names its subject" }

    fun why(kind: LogKind): String = get(kind).why

    fun visitWhat(zone: String): String? = VISIT_WHAT[zone]
}
