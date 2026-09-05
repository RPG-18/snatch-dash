package com.opendash.opendash_dash_engine.dash.map

import java.util.Arrays

/**
 * Distribution of one per-frame value over a rolling window.
 *
 * Deliberately not a log line per frame: at 4 fps that is fourteen thousand
 * lines an hour with nothing findable in them. [add] allocates nothing and
 * overwrites the oldest sample; the sort happens once per summary, i.e. twice a
 * minute.
 */
class Percentiles(private val capacity: Int = 256) {
    private val samples = LongArray(capacity)
    private val scratch = LongArray(capacity)
    private var count = 0
    private var next = 0

    fun add(value: Long) {
        samples[next] = value
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    val isEmpty: Boolean get() = count == 0

    /** `p50/p95/max` over the window; the window then starts over. */
    fun drain(): String {
        if (count == 0) return "-"
        System.arraycopy(samples, 0, scratch, 0, count)
        Arrays.sort(scratch, 0, count)
        val p50 = scratch[count / 2]
        val p95 = scratch[((count * 95) / 100).coerceAtMost(count - 1)]
        val max = scratch[count - 1]
        count = 0
        next = 0
        return "$p50/$p95/$max"
    }
}

/**
 * What the map render costs, summarised periodically into the ride log.
 *
 * The set of values is fixed by spec/drawing_from_local_tiles.md, «Телеметрия»:
 * without them the decision to make the frame loop *wait* for each snapshot is
 * unfalsifiable, and the fallback (keeping MapLibre rendering into a
 * SurfaceTexture instead) would have to be argued from impressions. Note in
 * particular that snapshot latency alone answers nothing — what matters is how
 * often it ate the frame interval, and how far apart frames actually left.
 */
class RenderStats {
    private val snapshotMs = Percentiles()
    private val overlayMs = Percentiles()
    private val encodeMs = Percentiles()
    private val sendIntervalMs = Percentiles()

    private var frames = 0
    private var redraws = 0
    private var lateSnapshots = 0
    private var blankMaps = 0
    // Sum of the intervals the frames in this window were AIMING for. The window
    // spans both 4 fps riding and 2 fps standing, so the target has to be
    // accumulated as the frames go by; reading it once at summary time (as this
    // did) made "frames=110/120" mean nothing — it could be a perfect idle window
    // or a moving one that dropped half its frames.
    private var intendedTotalMs = 0L

    /**
     * One frame that went out, whether or not the map under it was redrawn.
     *
     * [intendedIntervalMs] is the interval this frame was pacing to — 250 ms while
     * moving, 500 ms while stopped.
     */
    fun frameSent(intervalMs: Long, encodeMs: Long, intendedIntervalMs: Long) {
        frames++
        intendedTotalMs += intendedIntervalMs
        this.encodeMs.add(encodeMs)
        if (intervalMs > 0) sendIntervalMs.add(intervalMs)
    }

    /**
     * One map redraw. [budgetMs] is the frame interval this snapshot had to fit
     * into — over it, the wait itself is what cost the frame rate.
     */
    fun mapDrawn(snapshotMs: Long, overlayMs: Long, budgetMs: Long, blank: Boolean) {
        redraws++
        this.snapshotMs.add(snapshotMs)
        this.overlayMs.add(overlayMs)
        if (snapshotMs > budgetMs) lateSnapshots++
        if (blank) blankMaps++
    }

    /**
     * Starts a fresh window, throwing away whatever the previous stream left.
     *
     * Called at the top of each stream because the window and the frame loop have
     * different lifetimes: this object belongs to the controller, `lastRenderLogAt`
     * is a local of the loop. A session that ended before its 30-second window
     * closed used to carry its frames into the NEXT session's first window, which
     * was measured from zero — that is where `frames=195/120` came from in the
     * 2026-09-04 logs (plan.md 1.6). Not a fast loop: two sessions added up.
     * `frames=sent/expected` is the number the whole "wait for the snapshot"
     * decision rests on, so it has to mean one session's worth of frames.
     */
    fun reset() {
        snapshotMs.drain()
        overlayMs.drain()
        encodeMs.drain()
        sendIntervalMs.drain()
        frames = 0
        redraws = 0
        lateSnapshots = 0
        blankMaps = 0
        intendedTotalMs = 0
    }

    /**
     * One line for the ride log, then the window resets.
     *
     * The `frames=sent/expected` pair is the number the whole "the loop waits for
     * the snapshot" decision rests on, so `expected` is derived from what the
     * frames in THIS window were pacing to: the mean intended interval over the
     * window, divided into its length. A window of mixed riding and standing gets
     * an expectation in between, which is the honest answer.
     *
     * **Two different clocks share this line.** Everything computed here covers
     * one window, reset by [reset] at the start of each stream. [timeouts],
     * [skipped], [abandoned], [errors] and [rebuilds] pass straight through from
     * `MapSnapshotProvider` and are cumulative over the **session**, not the
     * window — they only ever go up within a ride, and the provider zeroes them
     * when it prepares the next one, so every figure on this line belongs to the
     * diagnostics file it is written into. (It was not always so: before
     * 2026-09-05 they ran for the life of the process, and a fresh session's
     * first line opened with the previous ride's totals against its own frame
     * count. A session that ends badly now says so once, on its successor's
     * carry-over line.)
     *
     * [skipped] is the one to read for "how often did the map stand still": the
     * deadline bounds the wait, not the freeze, so a run of skipped frames is
     * what a wedged-but-not-yet-abandoned snapshotter looks like from here.
     * [rebuilds] says the provider had to throw a snapshotter away and carry on.
     */
    fun drain(
        periodMs: Long,
        timeouts: Long,
        skipped: Long,
        abandoned: Long,
        errors: Long,
        rebuilds: Long,
    ): String {
        val expected =
            if (frames == 0 || intendedTotalMs == 0L) "?"
            else (periodMs * frames / intendedTotalMs).toString()
        val line = "frames=$frames/$expected redraws=$redraws " +
            "(reused=${frames - redraws}) snapshot=${snapshotMs.drain()} " +
            "overlay=${overlayMs.drain()} encode=${encodeMs.drain()} " +
            "interval=${sendIntervalMs.drain()} late=$lateSnapshots blank=$blankMaps " +
            "timeouts=$timeouts skipped=$skipped wedged=$abandoned snapErr=$errors " +
            "rebuilds=$rebuilds"
        frames = 0
        redraws = 0
        lateSnapshots = 0
        blankMaps = 0
        intendedTotalMs = 0
        return line
    }
}
