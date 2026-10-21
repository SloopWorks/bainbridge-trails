package com.bainbridgetrails.play.fx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.TimeSource

enum class FxPoliteness { POLITE, ASSERTIVE }

/** One spoken line, set at [atMs] (announcer ms); [seq] counts every line the live region has said. */
data class FxAnnouncement(val text: String, val politeness: FxPoliteness, val seq: Long, val atMs: Long = 0)

/** What the always-composed live-region node says: the current line, or "" (so the next line is a change). */
internal fun liveRegionText(a: FxAnnouncement?): String = a?.text ?: ""

/**
 * The queue rules (ux.md § 5.4, effects § 1.10): assertive speaks at once (only the opt-in moment and Finish claims
 * use it); polite speaks at most once per [politeGapMs], later ones wait in FIFO order; the same text again within
 * [dedupeMs] of being said or queued is dropped. Pure; times are monotonic ms.
 */
internal class FxAnnounceQueue(private val politeGapMs: Long = 4_000, private val dedupeMs: Long = 30_000) {
    private val lastSeen = HashMap<String, Long>()
    private val pending = ArrayDeque<String>()
    private var lastPoliteMs: Long? = null

    val hasPending: Boolean get() = pending.isNotEmpty()

    /** Offer [text]; returns the text to speak now, or null (queued or dropped). */
    fun offer(text: String, politeness: FxPoliteness, nowMs: Long): String? {
        val seen = lastSeen[text]
        if (seen != null && nowMs - seen < dedupeMs) return null
        lastSeen[text] = nowMs
        if (politeness == FxPoliteness.ASSERTIVE) return text
        pending.addLast(text)
        return poll(nowMs)
    }

    /** The next polite text if the 4 s gap has passed, else null. */
    fun poll(nowMs: Long): String? {
        if (pending.isEmpty()) return null
        val last = lastPoliteMs
        if (last != null && nowMs - last < politeGapMs) return null
        lastPoliteMs = nowMs
        return pending.removeFirst()
    }

    /** Ms until [poll] can release the next queued text (0 = now), or null when nothing waits. */
    fun msUntilNext(nowMs: Long): Long? {
        if (pending.isEmpty()) return null
        val last = lastPoliteMs ?: return 0
        return (last + politeGapMs - nowMs).coerceAtLeast(0)
    }

    /** Drop everything waiting (app background: nothing is announced from the background). */
    fun clearPending() = pending.clear()
}

/**
 * Play's one announcer (engine PF-5, effects § 1.10; ux.md calls it PlayAnnouncer): map effects never post their own
 * live regions, they call [say]. [FxLiveRegion] (composed by [FxOverlay]) is the single live-region node; it is always
 * composed and says [liveRegionText]. A line clears [CLEAR_AFTER_MS] after it is set (no stale focusable text, and a
 * repeat of the same line later is a fresh "" → text change that TalkBack / VoiceOver speak again). While the app is
 * not resumed nothing is announced or queued, and the current line clears. [nowMs] is monotonic ms (tests inject it).
 */
@Stable
class FxAnnouncer internal constructor(private val nowMs: () -> Long = monotonicMs()) {
    private val queue = FxAnnounceQueue()
    private var seq = 0L

    /** Bumped whenever the live region's wake-up schedule changes (a line set or queued); [FxLiveRegion] re-pumps. */
    internal var version by mutableIntStateOf(0)
        private set

    /** What the live region currently says (null = ""). */
    var current: FxAnnouncement? by mutableStateOf(null)
        private set

    internal var active: Boolean = false
        set(value) {
            field = value
            if (!value) {
                queue.clearPending()
                current = null
            }
        }

    fun say(text: String, politeness: FxPoliteness = FxPoliteness.POLITE) {
        if (!active || text.isBlank()) return
        val now = nowMs()
        val spoken = queue.offer(text, politeness, now)
        if (spoken != null) show(spoken, politeness, now)
        if (spoken != null || queue.hasPending) version++
    }

    /**
     * Clears the current line once [CLEAR_AFTER_MS] old and releases queued polite lines on their 4 s gap; returns ms
     * until the next of those, or null when nothing is due.
     */
    internal fun pump(): Long? {
        val now = nowMs()
        current?.let { if (now - it.atMs >= CLEAR_AFTER_MS) current = null }
        queue.poll(now)?.let { show(it, FxPoliteness.POLITE, now) }
        val clearIn = current?.let { (it.atMs + CLEAR_AFTER_MS - now).coerceAtLeast(0) }
        val nextIn = queue.msUntilNext(now)
        return when {
            clearIn == null -> nextIn
            nextIn == null -> clearIn
            else -> minOf(clearIn, nextIn)
        }
    }

    private fun show(text: String, politeness: FxPoliteness, now: Long) {
        current = FxAnnouncement(text, politeness, ++seq, now)
    }

    companion object {
        /** A line stays on the live-region node this long, then the node says "" again. */
        const val CLEAR_AFTER_MS = 5_000L

        private fun monotonicMs(): () -> Long {
            val mark = TimeSource.Monotonic.markNow()
            return { mark.elapsedNow().inWholeMilliseconds }
        }
    }
}

/**
 * The single invisible live-region node for [announcer]: always composed (a node that appears together with its first
 * text is not announced on Android), saying [liveRegionText]. Its loop wakes for queued lines and the 5 s clear.
 */
@Composable
internal fun FxLiveRegion(announcer: FxAnnouncer, modifier: Modifier = Modifier) {
    LaunchedEffect(announcer) {
        snapshotFlow { announcer.version }.collectLatest {
            while (true) {
                val wait = announcer.pump() ?: break
                delay(wait.coerceAtLeast(1))
            }
        }
    }
    val a = announcer.current
    Box(
        modifier.size(1.dp).semantics {
            liveRegion = if (a?.politeness == FxPoliteness.ASSERTIVE) LiveRegionMode.Assertive else LiveRegionMode.Polite
            contentDescription = liveRegionText(a)
        },
    )
}
