package com.opendash.opendash_dash_engine.dash.map

import java.util.concurrent.atomic.AtomicLong

/**
 * How much trouble the snapshotter had, per window and per session.
 *
 * **Why two horizons and not one.** The `[map]` line is a window: every number on it
 * answers "in the last 30 seconds". Five of them used to be lifetime totals instead,
 * printed flush against the per-window ones with nothing to tell them apart — so on the
 * 2026-09-29 ride a single wedge at 10:08 made all thirteen later windows read
 * `timeouts=7 skipped=22 wedged=1 rebuilds=1`, word for word, and the only thing that
 * stopped it being written up as a recurring fault was that the numbers were suspiciously
 * identical.
 *
 * But the totals are not useless, they are a different question: [abandoned] counts
 * bitmaps MapLibre leaked where we cannot reach them, so "what did the whole session
 * leave behind" is a statement about native memory that no single window makes. Hence
 * [drain] for the line and [session] for the summary.
 *
 * **Extracted from `MapSnapshotProvider`** because that class builds a MapLibre
 * snapshotter in its constructor and so cannot exist in a JVM test — which is why the
 * counters went a year without one, and why the defect above shipped.
 *
 * **Threading, corrected after review.** Every increment happens on the main thread —
 * `MapSnapshotProvider` says so of itself, and MapLibre's callbacks arrive on the main
 * looper — so the plain `++` this replaced was never contended, and the first version of
 * this comment was wrong to claim it could lose one. What IS cross-thread is [drain]:
 * `MapFrameRenderer` calls it from `dash-frame` while increments keep arriving on Main.
 * That is what the atomics are for, and why [session] is `@Volatile` — it is written by
 * [drain] on the frame thread and read by [sessionSoFar] and [reset] on Main.
 */
internal class SnapshotCounters {

    private val _timeouts = AtomicLong()
    private val _skipped = AtomicLong()
    private val _abandoned = AtomicLong()
    private val _errors = AtomicLong()
    private val _rebuilds = AtomicLong()

    /** Snapshots that missed their deadline. The frame loop moved on without them. */
    fun timeout() = _timeouts.incrementAndGet()

    /** A frame that never asked, because the previous request was still out. */
    fun skip() = _skipped.incrementAndGet()

    /** A request torn off by the wedge timer. Its bitmap is leaked inside MapLibre. */
    fun abandon() = _abandoned.incrementAndGet()

    /** A failure from MapLibre's own `ErrorHandler`; that path is otherwise silent. */
    fun error() = _errors.incrementAndGet()

    /** The snapshotter was thrown away and built again. */
    fun rebuild() = _rebuilds.incrementAndGet()

    /** One window's counts, and what the session has accumulated. */
    data class Tally(
        val timeouts: Long = 0,
        val skipped: Long = 0,
        val abandoned: Long = 0,
        val errors: Long = 0,
        val rebuilds: Long = 0,
    ) {
        val total: Long get() = timeouts + skipped + abandoned + errors + rebuilds

        operator fun plus(o: Tally) = Tally(
            timeouts + o.timeouts,
            skipped + o.skipped,
            abandoned + o.abandoned,
            errors + o.errors,
            rebuilds + o.rebuilds,
        )
    }

    /**
     * What the session has accumulated so far, drained windows included.
     *
     * `@Volatile` for the cross-thread read described in the class doc. Only [drain]
     * writes it, and only from the frame loop, so there is no read-modify-write race to
     * lose — just a visibility one.
     */
    @Volatile
    var session = Tally()
        private set

    /**
     * Take this window's counts and zero them, banking them into [session].
     *
     * Draining is what makes each `[map]` line cover its own 30 seconds. The ride total
     * is still recoverable — it is the sum down the column, or [session] — and the delta
     * says the thing a total never can: *when*.
     */
    fun drain(): Tally {
        val window = Tally(
            timeouts = _timeouts.getAndSet(0),
            skipped = _skipped.getAndSet(0),
            abandoned = _abandoned.getAndSet(0),
            errors = _errors.getAndSet(0),
            rebuilds = _rebuilds.getAndSet(0),
        )
        session += window
        return window
    }

    /**
     * Everything the session has seen, including counts not yet drained.
     *
     * For messages that ask "how bad has it been" mid-window — the rebuild notice does,
     * and reading only the undrained window would have it say "after 1 snapshot
     * failures" every thirty seconds.
     */
    fun sessionSoFar(): Tally = session + Tally(
        timeouts = _timeouts.get(),
        skipped = _skipped.get(),
        abandoned = _abandoned.get(),
        errors = _errors.get(),
        rebuilds = _rebuilds.get(),
    )

    /** A new session starts from nothing, window and totals alike. */
    fun reset() {
        _timeouts.set(0)
        _skipped.set(0)
        _abandoned.set(0)
        _errors.set(0)
        _rebuilds.set(0)
        session = Tally()
    }
}
