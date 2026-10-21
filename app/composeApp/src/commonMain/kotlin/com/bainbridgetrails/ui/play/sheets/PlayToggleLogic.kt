package com.bainbridgetrails.ui.play.sheets

import com.bainbridgetrails.data.PlaySettings
import com.bainbridgetrails.play.PlayRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * The opt-in start balance (product § 3.6.4) the repository credits on start, once ever: the marks of the entry
     * this turn-on wrote (the `play_start_balance` snackbar), else null. Only waits; the credit itself does not depend
     * on this call (PF1-1).
     */
    suspend fun awaitStartBalance(): Int?
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

    /** Start: Play on, then waits for the start balance; returns the marks for the `play_start_balance` snackbar (null: none). */
    suspend fun onStart(): Int? {
        if (_state.value.starting) return null
        _state.update { it.copy(switchOn = true, starting = true) }
        return try {
            optIn.setEnabled(true)
            optIn.awaitStartBalance()
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
 * it (null while off). The start balance is credited by the repository itself when it starts ([PlayRepository.start],
 * `:play`'s [com.bainbridgetrails.play.rules.StartBalance] rule: once ever, PF1-2; later turn-ons add only the
 * walk-derived marks earned while off, PF14-2), so a slow snapshot can delay the snackbar but never lose the balance
 * (PF1-1). [timeoutMs] bounds only how long Start shows its spinner.
 */
class PlayOptInGraph(
    private val updatePlay: suspend ((PlaySettings) -> PlaySettings) -> Unit,
    private val repo: () -> PlayRepository?,
    private val timeoutMs: Long = 5_000L,
) : PlayOptIn {
    override suspend fun setEnabled(on: Boolean) = updatePlay { it.copy(enabled = on, everToggled = true) }

    override suspend fun awaitStartBalance(): Int? = withTimeoutOrNull(timeoutMs) {
        var current = repo()
        while (current == null) {
            delay(POLL_MS)
            current = repo()
        }
        current.awaitStartBalance()
    }

    private companion object {
        const val POLL_MS = 20L
    }
}
