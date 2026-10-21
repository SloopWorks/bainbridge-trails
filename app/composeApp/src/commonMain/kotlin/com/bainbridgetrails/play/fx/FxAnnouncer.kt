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

/** One spoken line; [seq] makes a repeat of the same text after the dedupe window a new announcement. */
data class FxAnnouncement(val text: String, val politeness: FxPoliteness, val seq: Long)

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
 * live regions, they call [say]. [FxLiveRegion] (composed by [FxOverlay]) is the single live-region node. While the
 * app is not resumed nothing is announced or queued.
 */
@Stable
class FxAnnouncer internal constructor() {
    private val mark = TimeSource.Monotonic.markNow()
    private val queue = FxAnnounceQueue()
    private var seq = 0L
    internal var pendingVersion by mutableIntStateOf(0)
        private set

    /** What the live region currently says. */
    var current: FxAnnouncement? by mutableStateOf(null)
        private set

    internal var active: Boolean = false
        set(value) {
            field = value
            if (!value) queue.clearPending()
        }

    internal fun nowMs(): Long = mark.elapsedNow().inWholeMilliseconds

    fun say(text: String, politeness: FxPoliteness = FxPoliteness.POLITE) {
        if (!active || text.isBlank()) return
        val now = queue.offer(text, politeness, nowMs())
        if (now != null) current = FxAnnouncement(now, politeness, ++seq)
        if (queue.hasPending) pendingVersion++
    }

    /** Releases queued polite lines on their 4 s gap; returns ms until the next, or null when none waits. */
    internal fun pump(): Long? {
        val now = nowMs()
        queue.poll(now)?.let { current = FxAnnouncement(it, FxPoliteness.POLITE, ++seq) }
        return queue.msUntilNext(now)
    }
}

/** The single invisible live-region node for [announcer]. */
@Composable
internal fun FxLiveRegion(announcer: FxAnnouncer, modifier: Modifier = Modifier) {
    LaunchedEffect(announcer) {
        snapshotFlow { announcer.pendingVersion }.collectLatest {
            while (true) {
                val wait = announcer.pump() ?: break
                delay(wait.coerceAtLeast(1))
            }
        }
    }
    val a = announcer.current ?: return
    Box(
        modifier.size(1.dp).semantics {
            liveRegion = if (a.politeness == FxPoliteness.ASSERTIVE) LiveRegionMode.Assertive else LiveRegionMode.Polite
            contentDescription = a.text
        },
    )
}
