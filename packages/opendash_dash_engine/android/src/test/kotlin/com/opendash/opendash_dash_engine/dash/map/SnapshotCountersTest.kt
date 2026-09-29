package com.opendash.opendash_dash_engine.dash.map

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The five numbers that made a healthy ride look broken.
 *
 * They were lifetime totals printed inside a per-window line, so one wedge at 10:08 on
 * the 2026-09-29 ride made every later window read `timeouts=7 skipped=22 wedged=1
 * rebuilds=1`, identically, to the end. Nothing caught it because the class that owned
 * them builds a MapLibre snapshotter in its constructor and cannot exist in a JVM test.
 * Now the arithmetic lives here, where it can.
 */
class SnapshotCountersTest {

    private val c = SnapshotCounters()

    @Test
    fun `a drained window starts from zero`() {
        // The whole defect in one assertion: the second window must not repeat the
        // first one's counts.
        c.timeout(); c.timeout(); c.skip()
        assertEquals(2, c.drain().timeouts)
        assertEquals(0, c.drain().timeouts, "the second window saw no timeouts")
        assertEquals(0, c.drain().skipped)
    }

    @Test
    fun `each counter lands in its own field`() {
        // A transposition here would be invisible in a ride file — the names are the
        // only thing that says which number is which.
        c.timeout()
        repeat(2) { c.skip() }
        repeat(3) { c.abandon() }
        repeat(4) { c.error() }
        repeat(5) { c.rebuild() }

        val w = c.drain()
        assertEquals(1, w.timeouts)
        assertEquals(2, w.skipped)
        assertEquals(3, w.abandoned)
        assertEquals(4, w.errors)
        assertEquals(5, w.rebuilds)
        assertEquals(15, w.total)
    }

    @Test
    fun `the session total is the sum of the windows`() {
        // The ride total did not go away — it moved. A reader can still sum the column,
        // and the summary line reads it from here.
        c.timeout(); c.abandon()
        c.drain()
        c.timeout(); c.skip()
        c.drain()

        assertEquals(2, c.session.timeouts)
        assertEquals(1, c.session.skipped)
        assertEquals(1, c.session.abandoned)
        assertEquals(4, c.session.total)
    }

    @Test
    fun `sessionSoFar counts what has not been drained yet`() {
        // The rebuild notice fires mid-window and asks "how bad has it been". Reading
        // only the drained total would have it say "after 0 snapshot failures" right
        // after the failure that triggered it.
        c.abandon()
        c.drain()
        c.abandon()
        c.error()

        assertEquals(1, c.session.abandoned, "not banked yet")
        assertEquals(2, c.sessionSoFar().abandoned)
        assertEquals(1, c.sessionSoFar().errors)
    }

    @Test
    fun `reset clears both horizons`() {
        c.timeout()
        c.drain()
        c.skip()

        c.reset()

        assertEquals(0, c.session.total, "session totals gone")
        assertEquals(0, c.drain().total, "and the undrained window with them")
    }
}
