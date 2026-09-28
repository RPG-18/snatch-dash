package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.util.monotonicMs

/**
 * How long this connection has been down, and how many times it came back.
 *
 * **Why these three numbers are their own type.** They were three fields on
 * [DashWifiManager], and every path that ends an outage window has to bank it into the
 * total before doing anything else. On 2026-09-28 a path forgot: the re-tap handler
 * restarted the clock without banking, which threw away the whole outage before the tap
 * — a 55-second one would have reported `downtime=5000ms`. The same commit then reopened
 * a window that had never been open, which is worse (see [roll]). Neither could be caught
 * by a test, because the fields lived on a class that needs a `Context` to exist at all.
 * Here they are arithmetic over an injectable clock, and `OutageLedgerTest` covers every
 * row of `spec/wifi_retry_policy.md`'s "что переносится через тап".
 *
 * **The invariant, stated once:** [downSinceMs] is nonzero for exactly as long as a window
 * is open, and a window is open for exactly as long as the link is not up. Everything
 * here either opens one, closes one, or reads the pair without touching either.
 */
internal class OutageLedger(private val clock: () -> Long = ::monotonicMs) {

    /** Reconnects counted since the last [reset] or [roll]. */
    var reconnects = 0
        private set

    /** Time already banked from windows that have closed. */
    var accumMs = 0L
        private set

    /**
     * When the open window started, or null when none is open.
     *
     * **Null, not zero.** The fields this replaced used 0 as "no window", which is also
     * a legitimate reading of `SystemClock.elapsedRealtime` — at boot, and in any test
     * whose fake clock starts where clocks start. The sentinel and the value overlapped,
     * so "we are down" and "we came up at time zero" were the same state.
     */
    private var downSince: Long? = null

    /** True while an outage window is running. */
    val isDown: Boolean get() = downSince != null

    /**
     * Total time down, **including** the window that is still open.
     *
     * A summary is a report, and taking it from [accumMs] alone made a session that was
     * down its entire life report `downtime=0ms`, because nothing had banked it yet.
     */
    fun totalMs(): Long = accumMs + (downSince?.let { clock() - it } ?: 0L)

    fun countReconnect() {
        reconnects++
    }

    /** Start a window if none is running. Idempotent, so callers need not check. */
    fun openWindow() {
        if (downSince == null) downSince = clock()
    }

    /**
     * Bank the open window, if any, and return how long it ran.
     *
     * Idempotent: a second call with nothing open adds nothing and returns 0.
     */
    fun closeWindow(): Long {
        val from = downSince ?: return 0L
        val ran = clock() - from
        accumMs += ran
        downSince = null
        return ran
    }

    /** A different dash, or a disconnect: everything starts over. */
    fun reset(startDown: Boolean) {
        reconnects = 0
        accumMs = 0L
        downSince = if (startDown) clock() else null
    }

    /**
     * End one accounting period and begin the next, keeping the link state as it is.
     *
     * Used when the ride file rotates under a connection that continues: the part of an
     * outage before the boundary belongs to the file being closed, the part after to the
     * next one.
     *
     * **A window is reopened only if one was running.** Reopening unconditionally is the
     * defect review caught on 2026-09-28: a tap while the link was UP left `downSinceMs`
     * set for the rest of the session, and the first genuine drop twenty minutes later
     * computed `elapsedMs ≈ 20 min`, sailed past `ReconnectPolicy`'s 120 s deadline and
     * gave up on the very first attempt — the exact opposite of the "a tap buys more
     * patience" this was written for.
     */
    fun roll() {
        // Read BEFORE closing, not from closeWindow's return: a window that ran for
        // exactly zero milliseconds is still a window, and treating "returned 0" as
        // "there was none" would drop the clock on the same-millisecond case.
        val wasDown = isDown
        closeWindow()
        reconnects = 0
        accumMs = 0L
        if (wasDown) downSince = clock()
    }
}
