package com.bainbridgetrails.play.fx.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import com.bainbridgetrails.play.fx.FxAnnouncer
import com.bainbridgetrails.play.fx.FxEasing
import com.bainbridgetrails.play.fx.FxPoliteness
import com.bainbridgetrails.play.fx.rememberFxPalette
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.play_claim_pop_a11y
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PlayMotion
import org.jetbrains.compose.resources.getString
import kotlin.time.TimeSource

/**
 * `claim_pop` (effects §3.12, ux §4.1, NOTICE; chrome, screen-anchored, so PD-19's slip does not apply): only for
 * **claimed** marks (results at Finish, server-derived marks on sync), never mid-walk. A "+N" capsule appears at its
 * origin (the sheet's reward chip, or the HUD itself while a gesture moves the camera), rises, flies along a quadratic
 * Bézier into the Play chip and the chip bumps (`overshootSettle`) as its number rolls to the new total. Pops within
 * [COALESCE_MS] merge (the sum); at most [MAX_IN_FLIGHT] fly, a 4th joins the last. Reduced: no flight; the chip
 * crossfades to the new value and "+N" shows beside it for [REDUCED_LABEL_MS]. Pure state; times are monotonic ms.
 */
@Stable
class ClaimPopState(total: Int, private val clock: () -> Long = monotonicClock()) {
    /** One "+N" in flight from [origin] (root px; null = from the chip itself). */
    data class Pop(val id: Long, val amount: Int, val startedAtMs: Long, val origin: Offset?)

    /** The Play chip's marks after every pop (the target of the roll). */
    var total: Int by mutableIntStateOf(total)
        private set

    var inFlight: List<Pop> by mutableStateOf(emptyList())
        private set

    /** Reduced motion: the "+N" beside the chip and when it started (null = none). */
    private var label by mutableStateOf<Pop?>(null)

    /** The chip's bounds in root px ([claimPopTarget]); flights aim at its centre. */
    var target: Rect? by mutableStateOf(null)
        internal set

    /** Clock for drawing (monotonic ms), advanced by [ClaimPopOverlay] while something shows. */
    internal val frame = mutableLongStateOf(0L)
    private var nextId = 1L

    /** Now on this state's clock (monotonic ms). */
    fun now(): Long = clock()

    /** [pop] at [now]. */
    fun pop(amount: Int, origin: Offset?, reduced: Boolean): Pop = pop(amount, origin, now(), reduced)

    /** A sync or Finish brought [amount] claimed marks; returns the pop it joined or started. */
    fun pop(amount: Int, origin: Offset?, nowMs: Long, reduced: Boolean): Pop {
        require(amount > 0) { "claim_pop amount must be > 0" }
        total += amount
        if (reduced) {
            val l = label
            val merged = if (l != null && nowMs - l.startedAtMs < COALESCE_MS) l.copy(amount = l.amount + amount) else Pop(nextId++, amount, nowMs, null)
            label = merged
            return merged
        }
        val list = inFlight.filter { nowMs < it.startedAtMs + FLIGHT_MS }
        val last = list.lastOrNull()
        val merged = when {
            last != null && (nowMs - last.startedAtMs < COALESCE_MS || list.size >= MAX_IN_FLIGHT) -> last.copy(amount = last.amount + amount)
            else -> null
        }
        inFlight = if (merged != null) list.dropLast(1) + merged else list + Pop(nextId++, amount, nowMs, origin)
        return merged ?: inFlight.last()
    }

    /** Drop finished flights and an expired reduced label. */
    fun prune(nowMs: Long) {
        val keep = inFlight.filter { nowMs < it.startedAtMs + FLIGHT_MS }
        if (keep.size != inFlight.size) inFlight = keep
        if (label?.let { nowMs >= it.startedAtMs + REDUCED_LABEL_MS } == true) label = null
    }

    /** What the chip shows at [nowMs]: the total minus pops still in the air (the roll happens at landing). */
    fun chipValue(nowMs: Long): Int = total - inFlight.filter { nowMs < it.startedAtMs + LAND_MS }.sumOf { it.amount }

    /** Chip scale at [nowMs]: 1 → 1.08 → 1 over the 200 ms after a pop lands (`overshootSettle`, short4). */
    fun chipScale(nowMs: Long): Float {
        var s = 1f
        for (p in inFlight) {
            val f = ((nowMs - p.startedAtMs - LAND_MS).toFloat() / (FLIGHT_MS - LAND_MS)).coerceIn(0f, 1f)
            if (f <= 0f || f >= 1f) continue
            s = maxOf(s, 1f + (PlayMotion.OVERSHOOT - 1f) * bump(f))
        }
        return s
    }

    /** Reduced motion: the "+N" amount beside the chip at [nowMs], or null. */
    fun besideChip(nowMs: Long): Int? = label?.takeIf { nowMs >= it.startedAtMs && nowMs < it.startedAtMs + REDUCED_LABEL_MS }?.amount

    internal val active: Boolean get() = inFlight.isNotEmpty() || label != null

    internal val labelStartedAtMs: Long? get() = label?.startedAtMs

    companion object {
        const val COALESCE_MS = 400L
        const val MAX_IN_FLIGHT = 3
        const val FLIGHT_MS = 900L
        const val LAND_MS = 700L
        const val REDUCED_LABEL_MS = 1_500L

        private fun monotonicClock(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }

        /** 0 → 1 → 0 with the overshootSettle shape: up over 2/3 (emphasizedDecelerate), back over 1/3 (standard). */
        private fun bump(f: Float): Float =
            if (f < 2f / 3f) FxEasing.EmphasizedDecelerate.transform(f * 1.5f) else 1f - FxEasing.Standard.transform((f - 2f / 3f) * 3f)
    }
}

/** The Play chip side of [ClaimPopState]: records the chip's root bounds and applies the landing bump (layer only). */
fun Modifier.claimPopTarget(state: ClaimPopState): Modifier =
    this.onGloballyPositioned { state.target = it.boundsInRoot() }
        .graphicsLayer {
            val s = state.chipScale(state.frame.longValue)
            scaleX = s
            scaleY = s
        }

/**
 * Draws [state]'s flights (and the reduced "+N" beside the chip) above the screen it is composed over, filling it;
 * pointer-transparent and decorative (the [announcer] speaks "Plus {n} marks. {total} marks." politely, coalesced by
 * the announcer's 4 s rule). Runs its frame loop only while a pop or label shows.
 */
@Composable
fun ClaimPopOverlay(state: ClaimPopState, modifier: Modifier = Modifier, announcer: FxAnnouncer? = null, frozenAtMs: Long? = null) {
    val reduced = BtTheme.motion.reduced
    val chrome = BtTheme.colors.mapChrome
    val onChrome = BtTheme.colors.onMapChrome
    val plus = rememberFxPalette().seaGlass
    val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer(cacheSize = 8)
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(state, frozenAtMs) {
        if (frozenAtMs != null) {
            state.frame.longValue = frozenAtMs // showcase: a still frame, nothing pruned
            return@LaunchedEffect
        }
        snapshotFlow { state.active }.collect { active ->
            if (!active) return@collect
            while (state.active) {
                withFrameMillis { state.frame.longValue = state.now() }
                state.prune(state.frame.longValue)
            }
        }
    }
    if (announcer != null) {
        LaunchedEffect(state, announcer) {
            var said = state.total
            snapshotFlow { state.total }.collect { total ->
                if (total > said) {
                    val text = getString(Res.string.play_claim_pop_a11y, PlayFormat.marks(total - said), PlayFormat.marks(total))
                    announcer.say(text, FxPoliteness.POLITE)
                }
                said = total
            }
        }
    }
    Box(
        modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clearAndSetSemantics {}
            .drawWithCache {
                val texts = HashMap<Int, TextLayoutResult>()
                fun layout(n: Int): TextLayoutResult = texts.getOrPut(n) {
                    measurer.measure(capsuleText(n, plus), style.copy(color = onChrome))
                }
                onDrawBehind {
                    val now = state.frame.longValue
                    val chip = state.target ?: return@onDrawBehind
                    val dp = density
                    val toLocal = Offset(-origin.x, -origin.y)
                    val end = chip.center + toLocal
                    // Reduced: "+N" beside the chip, short3 crossfade in and out.
                    val n = state.besideChip(now)
                    val started = state.labelStartedAtMs
                    if (n != null && started != null) {
                        val e = now - started
                        val a = minOf(1f, e / 150f, (ClaimPopState.REDUCED_LABEL_MS - e) / 150f).coerceIn(0f, 1f)
                        val text = layout(n)
                        val at = Offset(chip.right + toLocal.x + 8f * dp, end.y - text.size.height / 2f)
                        capsule(text, at, chrome, a, dp)
                    }
                    if (reduced) return@onDrawBehind
                    for (p in state.inFlight) {
                        val e = (now - p.startedAtMs).toInt()
                        if (e < 0 || e >= ClaimPopState.LAND_MS + 50) continue
                        val text = layout(p.amount)
                        val start = (p.origin ?: chip.center) + toLocal
                        val appear = FxEasing.EmphasizedDecelerate.transform((e / 200f).coerceIn(0f, 1f))
                        val fly = FxEasing.EmphasizedAccelerate.transform(((e - 300) / 450f).coerceIn(0f, 1f))
                        val risen = Offset(start.x, start.y - 16f * dp * appear)
                        // Control point: 30 % up and toward the screen centre.
                        val ctrl = Offset(
                            risen.x + (size.width / 2f - risen.x) * 0.3f,
                            minOf(risen.y, end.y) - size.height * 0.3f * 0.5f,
                        )
                        val u = 1f - fly
                        val pos = Offset(
                            u * u * risen.x + 2 * u * fly * ctrl.x + fly * fly * end.x,
                            u * u * risen.y + 2 * u * fly * ctrl.y + fly * fly * end.y,
                        )
                        val s = (0.6f + 0.4f * appear) * (1f - 0.3f * fly)
                        val alpha = appear * (1f - ((e - ClaimPopState.LAND_MS + 50) / 50f).coerceIn(0f, 1f))
                        scale(s, s, pos) {
                            capsule(text, Offset(pos.x - text.size.width / 2f, pos.y - text.size.height / 2f), chrome, alpha, dp)
                        }
                    }
                }
            },
    )
}

/** "+1,240" with the "+" in sea glass (effects §3.12: never `positive` on the map). */
private fun capsuleText(n: Int, plus: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = plus)) { append("+") }
    append(PlayFormat.marks(n))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.capsule(
    text: TextLayoutResult, at: Offset, chrome: androidx.compose.ui.graphics.Color, alpha: Float, dp: Float,
) {
    if (alpha <= 0f) return
    val padH = 10f * dp
    val padV = 4f * dp
    val w = text.size.width + 2 * padH
    val h = text.size.height + 2 * padV
    drawRoundRect(chrome, Offset(at.x - padH, at.y - padV), Size(w, h), CornerRadius(h / 2f), alpha = alpha * chrome.alpha)
    translate(at.x, at.y) { drawText(text, alpha = alpha) }
}
