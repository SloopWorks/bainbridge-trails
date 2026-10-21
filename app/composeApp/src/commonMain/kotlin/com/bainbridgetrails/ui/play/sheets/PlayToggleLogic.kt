package com.bainbridgetrails.ui.play.sheets

import com.bainbridgetrails.coverage.Dataset
import com.bainbridgetrails.coverage.ProgressSnapshot
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.play.PlayRepository
import com.bainbridgetrails.play.config.Goal
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.geo.ChartSquare
import com.bainbridgetrails.play.rules.LogKind
import com.bainbridgetrails.play.rules.Marks
import com.bainbridgetrails.play.rules.StartBalance
import com.bainbridgetrails.play.rules.WalkDerived
import com.bainbridgetrails.play.schedule.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate

/** The modal sheet the Settings › Play switch opens (ux.md § 3.1). */
enum class PlaySheet { Intro, Off }

/**
 * What Settings › Play shows. [switchOn] is the switch's look: on while the intro is open (pending, ux § 3.1) and
 * while the off sheet is open (cancel keeps it on). [starting]: Start was pressed, the start balance computes
 * (PrimaryButton `loading`).
 */
data class PlayToggleState(val switchOn: Boolean, val sheet: PlaySheet? = null, val starting: Boolean = false)

/** The only effects the Play switch may have (PD-1: nothing else Play is touched from Settings). */
interface PlayOptIn {
    /** Persists `settings.play.enabled` (+ `everToggled`); the Play gate then creates/starts or stops Play. */
    suspend fun setEnabled(on: Boolean)

    /** Credits the opt-in start balance (product § 3.6.4) once ever; the marks of a newly written entry, else null. */
    suspend fun creditStartBalance(): Int?
}

/**
 * Settings › Play switch (ux.md § 3.1; product § 5.1, § 5.5; PF1, PF14). On → the intro sheet opens and nothing is
 * persisted until Start; dismissing the intro (scrim, swipe, back) returns the switch to off with no effect at all
 * (PF1-3). Start → Play on, then the start balance (PF1-1, PF1-2). Off → the off sheet; Confirm turns Play off and
 * deletes nothing (PF14-1), Cancel keeps it on.
 */
class PlayToggleFlow(private val optIn: PlayOptIn, enabled: Boolean) {
    private val _state = MutableStateFlow(PlayToggleState(enabled))
    val state: StateFlow<PlayToggleState> = _state.asStateFlow()

    /** Settings changed elsewhere (debug drawer, the offer): the switch follows unless a sheet is in the way. */
    fun sync(enabled: Boolean) = _state.update { s -> if (s.sheet == null && !s.starting) s.copy(switchOn = enabled) else s }

    fun onSwitch(checked: Boolean) = _state.update { s ->
        when {
            s.sheet != null || s.starting -> s
            checked && !s.switchOn -> PlayToggleState(switchOn = true, sheet = PlaySheet.Intro)
            !checked && s.switchOn -> s.copy(sheet = PlaySheet.Off)
            else -> s
        }
    }

    /** Intro closed without Start: the switch animates back off, nothing is written or requested. */
    fun onIntroDismissed() = _state.update { s -> if (s.sheet == PlaySheet.Intro && !s.starting) PlayToggleState(false) else s }

    /** Start: Play on, then the start balance; returns the marks for the `play_start_balance` snackbar (null: none). */
    suspend fun onStart(): Int? {
        if (_state.value.starting) return null
        _state.update { it.copy(switchOn = true, starting = true) }
        return try {
            optIn.setEnabled(true)
            optIn.creditStartBalance()
        } finally {
            _state.value = PlayToggleState(switchOn = true)
        }
    }

    fun onOffCancelled() = _state.update { s -> if (s.sheet == PlaySheet.Off) s.copy(sheet = null) else s }

    suspend fun onOffConfirmed() {
        if (_state.value.sheet != PlaySheet.Off) return
        optIn.setEnabled(false)
        _state.value = PlayToggleState(switchOn = false)
    }
}

/**
 * The app's [PlayOptIn]: [updatePlay] writes `AppSettings.play`; [repo] is the Play repository once the gate has made
 * it (null while off); [walkDerived] is the snapshot's walk-derived marks (W-COV + W-DONE + W-SQUARE); [today] the
 * island date on the Play clock; [newId] a fresh entry id. The start balance is written through the repository's
 * store with `:play`'s [StartBalance] rule, so it is credited once ever (PF1-2) and a later on only adds the
 * walk-derived marks earned while off (PF14-2).
 */
class PlayOptInGraph(
    private val updatePlay: suspend ((PlaySettings) -> PlaySettings) -> Unit,
    private val repo: () -> PlayRepository?,
    private val walkDerived: suspend (PlayRepository) -> WalkDerived?,
    private val today: () -> LocalDate,
    private val newId: () -> String,
    private val repoTimeoutMs: Long = 5_000L,
) : PlayOptIn {
    override suspend fun setEnabled(on: Boolean) = updatePlay { it.copy(enabled = on, everToggled = true) }

    override suspend fun creditStartBalance(): Int? {
        val r = withTimeoutOrNull(repoTimeoutMs) {
            var current = repo()
            while (current == null) {
                delay(POLL_MS)
                current = repo()
            }
            current
        } ?: return null
        val derived = walkDerived(r) ?: return null
        var credited: Int? = null
        r.store.update { state ->
            val before = state.logbook
            val after = StartBalance.onEnable(before, derived, today(), newId)
            credited = after.entries.drop(before.entries.size).firstOrNull { it.kind == LogKind.start_balance }?.marks
            state.copy(logbook = after)
        }
        return credited
    }

    private companion object {
        const val POLL_MS = 20L
    }
}

/**
 * Walk-derived marks of the current coverage snapshot (product § 3.6.4: W-COV + W-DONE + W-SQUARE, quiet features
 * excluded) for the opt-in start balance. Quiet = the pack's quiet-zone features (`trail:` / `road:` refs) and, for
 * trails, every trail of an excluded park. Covered metres on quiet features are taken off at their own share
 * (fraction × length), so a segment shared with a quiet feature can be under-counted, never over-counted. The
 * exact per-segment rule (quiet-zone buffers) is the claim pipeline's (plan.md L2-9).
 */
object SnapshotWalkDerived {
    private const val METRES_PER_MILE = 1609.344

    fun of(
        goal: Goal,
        dataset: Dataset,
        snapshot: ProgressSnapshot,
        config: PlayConfig?,
    ): WalkDerived {
        val quietRefs = config?.quietZones.orEmpty().map { it.feature }
            .filter { it.startsWith("trail:") || it.startsWith("road:") }.mapTo(HashSet()) { it.substringAfter(':') }
        val parks = config?.excludedParks.orEmpty().toSet()
        val quiet = dataset.trails.filter { it.id in quietRefs || (it.park != null && it.park in parks) }.mapTo(HashSet()) { it.id }
        val quietM = snapshot.trails.filter { it.trailId in quiet }.sumOf { it.fraction * it.lengthMi * METRES_PER_MILE }
        val counted = dataset.trails.associate { it.id to it.counted }
        val completed = snapshot.trails.count {
            it.status == TrailStatus.COMPLETE && it.trailId !in quiet && counted[it.trailId] != false
        }
        val quietSegs = dataset.trails.filter { it.id in quiet }.flatMapTo(HashSet()) { it.segmentIds }
        val covered = snapshot.segmentBuckets.values.flatten().filterTo(HashSet()) { it !in quietSegs }
        val lines = dataset.segments.filter { it.id in covered }.map { s ->
            s.coords.map { GeoPoint(lat = it[1], lon = it[0]) }
        }
        val squares = ChartSquare.covering(lines).size
        return Marks.walkDerived(goal, (snapshot.coveredM - quietM).coerceAtLeast(0.0), completed, squares)
    }
}
