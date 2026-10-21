package com.bainbridgetrails.ui.play.screens

import com.bainbridgetrails.net.PlayChallengeDto
import com.bainbridgetrails.net.PlayChallengesDto
import com.bainbridgetrails.play.config.ChallengeMetric
import com.bainbridgetrails.play.config.ChallengeScope
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.Item
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.rules.LogEntry
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.ui.play.components.PlayFormat
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** ux.md § 2.5 states. */
enum class ChallengeState { Active, Done, Ended, Upcoming }

/** Your community standing on an island challenge: a band word and how many share it. Nothing else, ever (PF9-4, PD-4). */
data class ChallengeBand(val band: String, val count: Int)

/**
 * One ChallengeCard. [title] / [body] are the pack's copy (placeholders filled); [windowStart] / [windowEnd] island
 * dates, inclusive (a window ending Mon 00:00 ends on Sunday). [progress] / [target] are counts (solo: your completed
 * features; island: stretches repelled of stretches attacked), null when unknown. [reward] null for an ended card.
 */
data class ChallengeCardModel(
    val itemId: String,
    val occKey: String,
    val islandScope: Boolean,
    val title: String,
    val body: String,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val state: ChallengeState,
    val progress: Int?,
    val target: Int?,
    val reward: Int?,
    val band: ChallengeBand?,
)

data class ChallengesModel(val solo: List<ChallengeCardModel>, val island: List<ChallengeCardModel>, val ended: List<ChallengeCardModel>) {
    val isEmpty: Boolean get() = solo.isEmpty() && island.isEmpty() && ended.isEmpty()
}

/** The strings a card's lines are built from (values/strings.xml; production passes `stringResource`s). */
data class ChallengeCopy(
    /** `play_challenge_window` "%1$s – %2$s". */
    val window: String,
    /** `play_challenge_progress` "%1$s of %2$s". */
    val progress: String,
    /** `play_kraken_island_progress` "%1$d of %2$d stretches sent back by islanders". */
    val islandProgress: String,
    /** `play_challenge_band` "You were in the %1$s band, with %2$d others" + the three band words. */
    val band: String,
    val bandTop: String,
    val bandMiddle: String,
    val bandStarting: String,
)

/** A card's lines: [window] days only, never a clock or a countdown (PP-8, PD-4). */
data class ChallengeCardText(val window: String, val progress: String?, val band: String?)

/**
 * The Challenges screen and ChallengeCard (ux.md § 2.5, § 3.16; product § 4.5): this week's challenges from the agenda
 * (active, then upcoming), merged with the server's `{band, count}` standings (`GET /v1/play/challenges`) and, for
 * solo progress when the server has not answered, the Logbook's completions inside the window. Ended challenges of
 * the last 7 days come from the server list. Nothing timed: windows are island days.
 */
object ChallengesLogic {
    fun model(
        config: PlayConfig,
        goal: Goal,
        agenda: PlayAgenda?,
        server: PlayChallengesDto?,
        logbook: List<LogEntry>,
        now: Instant,
    ): ChallengesModel {
        val items = config.items.associateBy { it.id }
        val byOcc = server?.current.orEmpty().associateBy { it.occ }
        val cards = ArrayList<ChallengeCardModel>()
        val seen = HashSet<String>()
        agenda?.active.orEmpty().map { it.occurrence }.filter { it.type == ItemType.challenge && !it.hidden }.forEach { o ->
            val item = items[o.itemId] ?: return@forEach
            if (seen.add(o.key)) cards += card(item, goal, o.key, o.start, o.end, now, byOcc[o.key] ?: server.forItem(o.itemId), logbook)
        }
        val weekEnd = IslandTime.instantOf(IslandTime.weekId(IslandTime.dateOf(now)), kotlinx.datetime.LocalTime(0, 0)) + 7.days
        agenda?.upcoming.orEmpty().filter { it.type == ItemType.challenge && it.start < weekEnd }.forEach { u ->
            val item = items[u.itemId] ?: return@forEach
            if (seen.add(u.key)) cards += card(item, goal, u.key, u.start, u.end, now, null, logbook)
        }
        server?.current.orEmpty().filter { it.occ !in seen }.forEach { dto ->
            val item = items[dto.id] ?: return@forEach
            val start = parse(dto.windowStart) ?: return@forEach
            val end = parse(dto.windowEnd) ?: return@forEach
            if (end > now || end < now - 7.days) return@forEach
            seen += dto.occ
            cards += card(item, goal, dto.occ, start, end, now, dto, logbook)
        }
        val (ended, live) = cards.partition { it.state == ChallengeState.Ended }
        return ChallengesModel(live.filter { !it.islandScope }, live.filter { it.islandScope }, ended)
    }

    private fun PlayChallengesDto?.forItem(id: String): PlayChallengeDto? = this?.current?.firstOrNull { it.id == id && it.open }

    private fun card(
        item: Item,
        goal: Goal,
        occ: String,
        start: Instant,
        end: Instant?,
        now: Instant,
        dto: PlayChallengeDto?,
        logbook: List<LogEntry>,
    ): ChallengeCardModel {
        val spec = item.challenge
        val island = spec?.scope == ChallengeScope.island
        val startDate = IslandTime.dateOf(start)
        val endDate = end?.let { IslandTime.dateOf(it - 1.milliseconds) } ?: startDate
        val (progress, target) = if (island) {
            dto?.island?.let { it.repelled to it.stretches } ?: (null to null)
        } else {
            val t = spec?.target?.get(goal) ?: dto?.target?.takeIf { it > 0 }
            val local = if (spec?.metric == ChallengeMetric.completed) {
                logbook.count { it.kind == LogKind.complete && it.date in startDate..endDate }
            } else {
                null
            }
            (dto?.progress ?: local) to t
        }
        val done = progress != null && target != null && target > 0 && progress >= target
        val state = when {
            now < start -> ChallengeState.Upcoming
            done -> ChallengeState.Done
            end != null && now >= end -> ChallengeState.Ended
            else -> ChallengeState.Active
        }
        val band = if (island) {
            dto?.you?.band?.let { b -> dto.bands.firstOrNull { it.band == b }?.count?.let { ChallengeBand(b, it) } }
        } else {
            null
        }
        val body = item.copy.body
            .replace("{target}", target?.toString().orEmpty())
            .replace("{goal.items}", if (goal == Goal.roads) "roads" else "trails")
        return ChallengeCardModel(
            itemId = item.id,
            occKey = occ,
            islandScope = island,
            title = item.copy.title,
            body = body,
            windowStart = startDate,
            windowEnd = endDate,
            state = state,
            progress = if (state == ChallengeState.Upcoming) null else progress,
            target = target,
            reward = if (state == ChallengeState.Ended) null else item.marks.takeIf { it > 0 },
            band = band,
        )
    }

    /** The card's lines from [copy]: window days only, progress as text, the band line only when its count is known. */
    fun text(card: ChallengeCardModel, copy: ChallengeCopy): ChallengeCardText {
        val window = copy.window.replace("%1\$s", PlayFormat.day(card.windowStart)).replace("%2\$s", PlayFormat.day(card.windowEnd))
        val p = card.progress
        val t = card.target
        val progress = when {
            p == null || t == null -> null
            card.islandScope -> copy.islandProgress.replace("%1\$d", p.toString()).replace("%2\$d", t.toString())
            else -> copy.progress.replace("%1\$s", p.coerceAtMost(t).toString()).replace("%2\$s", t.toString())
        }
        val band = card.band?.let { b ->
            val word = when (b.band) {
                "top" -> copy.bandTop
                "middle" -> copy.bandMiddle
                "starting" -> copy.bandStarting
                else -> return@let null
            }
            copy.band.replace("%1\$s", word).replace("%2\$d", b.count.toString())
        }
        return ChallengeCardText(window, progress, band)
    }

    /** `2026-10-29T06:00-07:00` (seconds optional) → instant; null when unreadable. */
    internal fun parse(s: String): Instant? {
        if (s.isBlank()) return null
        return runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching {
                val i = s.indexOfAny(charArrayOf('+', '-', 'Z'), startIndex = 16)
                if (i < 0) null else Instant.parse(s.substring(0, i) + ":00" + s.substring(i))
            }.getOrNull()
    }
}
