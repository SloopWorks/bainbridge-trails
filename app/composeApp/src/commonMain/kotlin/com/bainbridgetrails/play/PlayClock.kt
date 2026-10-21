package com.bainbridgetrails.play

import com.bainbridgetrails.play.time.IslandTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Debug time travel (schedule.md § 4.1): the real clock, a frozen instant, or the real clock shifted. */
sealed interface PlayClockMode {
    data object Real : PlayClockMode

    data class Fixed(val at: Instant) : PlayClockMode

    data class Offset(val ms: Long) : PlayClockMode
}

/**
 * Debug tide override (`-btPlayTide`, `--es playTide`, the drawer): [LOW] / [HIGH] force the kraken's tide gate open /
 * shut at every instant; [EXPIRED] behaves as a table that does not cover the date (fail closed, PF16-3).
 */
enum class PlayTideOverride { NONE, LOW, HIGH, EXPIRED }

/** Debug overrides a [PlayClock] carries. [bundledConfig]: run the bundled pack, never fetch (`-btPlayConfig bundled`). */
data class PlayOverrides(
    val mode: PlayClockMode = PlayClockMode.Real,
    val tide: PlayTideOverride = PlayTideOverride.NONE,
    val bundledConfig: Boolean = false,
) {
    val active: Boolean get() = this != PlayOverrides()
}

/**
 * The time Play runs on (schedule.md § 2.5, § 4.1). Every Play time rule reads [now], never the system clock directly,
 * so debug tools can time-travel. Release binds [SystemPlayClock] (no setter); debug tools bind a [DebugPlayClock]
 * through `AppGraph.playClock`.
 */
interface PlayClock {
    fun now(): Instant

    /**
     * Play time minus system time, ms: 0 for [SystemPlayClock] and a debug clock on [PlayClockMode.Real]. Recording fixes
     * and walk summaries carry system time; Play converts them (`timeMs + offsetMs`) before sensing and claiming, so a
     * time-travelled walk is judged at Play time (e2e run 1 F1).
     */
    val offsetMs: Long

    /** Changes when a debug override changes (time travel, tide, config source); constant for [SystemPlayClock]. */
    val overrides: StateFlow<PlayOverrides>

    companion object {
        /**
         * Island wall time `yyyy-MM-ddTHH:mm` (America/Los_Angeles, whatever the device zone) → instant; a time in the
         * spring-forward gap moves to the next valid instant. Null when unparseable.
         */
        fun parseIsland(text: String?): Instant? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val ldt = runCatching { LocalDateTime.parse(t) }.getOrNull() ?: return null
            return IslandTime.instantOf(ldt.date, ldt.time)
        }
    }
}

/** The system clock, no overrides. */
object SystemPlayClock : PlayClock {
    private val none = MutableStateFlow(PlayOverrides()).asStateFlow()
    override fun now(): Instant = Clock.System.now()
    override val offsetMs: Long get() = 0L
    override val overrides: StateFlow<PlayOverrides> get() = none
}

/**
 * A [PlayClock] with debug overrides (debug drawer `PlayClockPlugin`, launch args). Constructed only by debug code
 * paths (Android debug source set, iOS debug binaries); [system] is injectable for tests.
 */
class DebugPlayClock(
    private val system: () -> Instant = { Clock.System.now() },
    initial: PlayOverrides = PlayOverrides(),
) : PlayClock {
    private val _overrides = MutableStateFlow(initial)
    override val overrides: StateFlow<PlayOverrides> = _overrides.asStateFlow()

    override fun now(): Instant = when (val m = _overrides.value.mode) {
        PlayClockMode.Real -> system()
        is PlayClockMode.Fixed -> m.at
        is PlayClockMode.Offset -> system() + m.ms.milliseconds
    }

    override val offsetMs: Long
        get() = when (val m = _overrides.value.mode) {
            PlayClockMode.Real -> 0L
            is PlayClockMode.Fixed -> (m.at - system()).inWholeMilliseconds
            is PlayClockMode.Offset -> m.ms
        }

    fun set(mode: PlayClockMode) = _overrides.update { it.copy(mode = mode) }

    fun setTide(tide: PlayTideOverride) = _overrides.update { it.copy(tide = tide) }

    fun setBundledConfig(on: Boolean) = _overrides.update { it.copy(bundledConfig = on) }

    /** Moves Play time by [d]: shifts a fixed instant, else the offset. */
    fun shift(d: Duration) = _overrides.update {
        it.copy(
            mode = when (val m = it.mode) {
                is PlayClockMode.Fixed -> PlayClockMode.Fixed(m.at + d)
                is PlayClockMode.Offset -> PlayClockMode.Offset(m.ms + d.inWholeMilliseconds)
                PlayClockMode.Real -> PlayClockMode.Offset(d.inWholeMilliseconds)
            },
        )
    }

    /** Ticking from [at] on (an offset from the system clock), as the launch arguments do. */
    fun startAt(at: Instant) = set(PlayClockMode.Offset((at - system()).inWholeMilliseconds))

    /** Freezes at [at]. */
    fun fixAt(at: Instant) = set(PlayClockMode.Fixed(at))

    companion object {
        /** The next island Thursday [hour]:00 strictly after [from] (the kraken window opens Thu 06:00). */
        fun nextThursday(from: Instant, hour: Int = 6): Instant {
            var d = IslandTime.dateOf(from)
            while (true) {
                if (d.dayOfWeek == DayOfWeek.THURSDAY) {
                    val at = IslandTime.instantOf(d, LocalTime(hour, 0))
                    if (at > from) return at
                }
                d = d.plus(1, DateTimeUnit.DAY)
            }
        }

        /** The next Mar 30 (the island quiet day, PD-6) at [hour]:00 on or after [from]'s island date. */
        fun nextMar30(from: Instant, hour: Int = 12): Instant {
            val today = IslandTime.dateOf(from)
            var d = LocalDate(today.year, Month.MARCH, 30)
            if (d < today) d = LocalDate(today.year + 1, Month.MARCH, 30)
            return IslandTime.instantOf(d, LocalTime(hour, 0))
        }

        /** "Thu Oct 29 14:00 island time" (debug chip / drawer). */
        fun describe(at: Instant): String {
            val d = IslandTime.dateOf(at)
            val local = at.toLocalDateTime(IslandTime.zone)
            val day = d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            val mon = d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            val hh = local.hour.toString().padStart(2, '0')
            val mm = local.minute.toString().padStart(2, '0')
            return "$day $mon ${local.day} $hh:$mm island time"
        }
    }
}
