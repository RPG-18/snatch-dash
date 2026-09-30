package com.opendash.opendash_dash_engine.util

/**
 * A rate limit for a log source that can repeat itself faster than the ride file can
 * usefully hold.
 *
 * At most [budget] distinct messages per [windowMs], each written once, with its repeats
 * counted and reported when the window closes.
 *
 * **Why both a collapse and a budget.** Collapsing by message alone is not enough,
 * because nothing says the failures are one message — several missing glyph ranges name
 * several files, and alternating messages walk straight through a "same as the previous
 * line?" check. The budget makes the cost to the file bounded whatever the source does,
 * and says so when it drops the rest.
 *
 * **Two known gaps, both counts and never messages.** The tail of a window is only
 * reported when the next line arrives — a burst that simply stops leaves its last repeats
 * uncounted. And a window interrupted by a new ride file is dropped outright rather than
 * written into a file its lines were never headed for (see [RideDiagnostics.session]).
 *
 * **Extracted from `MapLibreLogBridge` on 2026-09-29**, when Flutter's own errors looked
 * like a second source for it. They turned out not to be: they have TWO sinks, and this
 * class gates only the ride file, so their limit has to run upstream of both — in Dart,
 * at the source (`lib/util/error_reporting.dart`). Running it here as well would only
 * make these counters dead, since a repeat swallowed upstream never arrives. The
 * extraction stays because it made the rule readable and testable apart from MapLibre's
 * `Logger` plumbing, which needs a device.
 *
 * `@Synchronized` throughout: MapLibre logs from the snapshotter and the tile workers.
 */
internal class CollapsingRideLog(
    private val tag: String,
    private val windowMs: Long,
    private val budget: Int,
    private val clockMs: () -> Long = ::monotonicMs,
) {
    private var windowStartMs = 0L
    private var windowSession = -1L

    /**
     * Messages written in the current window, each with the number of identical ones
     * swallowed after it. Never larger than [budget]: a message only gets an entry by
     * being written, and past the budget nothing is written.
     */
    private val collapsed = LinkedHashMap<String, Int>()

    /** Distinct messages this window had no budget left to write. */
    private var dropped = 0

    /** Write [text] unless it is a repeat or over budget. */
    @Synchronized
    fun write(text: String) {
        val now = clockMs()
        val session = RideDiagnostics.session
        if (session != windowSession) {
            // A new ride file: whatever the previous window was still counting was
            // headed for a file that is now closed.
            collapsed.clear()
            dropped = 0
            windowSession = session
            windowStartMs = now
        } else if (now - windowStartMs >= windowMs) {
            closeWindow()
            windowStartMs = now
        }
        val repeats = collapsed[text]
        if (repeats != null) {
            collapsed[text] = repeats + 1
            return
        }
        if (collapsed.size >= budget) {
            dropped++
            return
        }
        collapsed[text] = 0
        RideDiagnostics.warn(tag, text)
    }

    /** Report what the closing window swallowed, then start counting again. */
    private fun closeWindow() {
        val seconds = windowMs / 1000
        for ((text, repeats) in collapsed) {
            if (repeats > 0) {
                RideDiagnostics.warn(tag, "repeated $repeats more time(s) in the last ${seconds}s: $text")
            }
        }
        if (dropped > 0) {
            RideDiagnostics.warn(
                tag,
                "$dropped further line(s) not written — over the $budget-per-${seconds}s budget",
            )
        }
        collapsed.clear()
        dropped = 0
    }
}
