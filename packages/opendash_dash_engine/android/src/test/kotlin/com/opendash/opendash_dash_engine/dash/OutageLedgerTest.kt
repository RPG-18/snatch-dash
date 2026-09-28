package com.opendash.opendash_dash_engine.dash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bookkeeping behind `downtime=` and the 120-second give-up deadline.
 *
 * These three numbers lived on `DashWifiManager`, which needs a `Context`, so nothing
 * here could be tested — and two defects went in on 2026-09-28 because of it: a restart
 * that discarded the whole accrued outage, and a roll that opened a window on a link
 * that was up. The second is the dangerous one. `downSinceMs` feeds `elapsedMs` into
 * `ReconnectPolicy`, so a window left open across twenty minutes of healthy streaming
 * makes the first genuine drop exceed the 120 s deadline and give up on attempt one —
 * a dash that dies with zero retries, from a counter.
 */
class OutageLedgerTest {

    private var now = 0L
    private val ledger = OutageLedger { now }

    private fun advance(ms: Long) { now += ms }

    @Test
    fun `a closed window is banked, not lost`() {
        ledger.reset(startDown = true)
        advance(55_000)
        assertEquals(55_000L, ledger.closeWindow())
        assertEquals(55_000L, ledger.accumMs)
        assertFalse(ledger.isDown)
    }

    @Test
    fun `the total includes the window still running`() {
        // A summary is a report. Reading accumMs alone made a session that was down its
        // whole life print downtime=0ms, because nothing had banked it yet.
        ledger.reset(startDown = true)
        advance(30_000)
        assertEquals(30_000L, ledger.totalMs())
        assertEquals(0L, ledger.accumMs, "reading must not bank")
        assertTrue(ledger.isDown, "reading must not close the window")
    }

    @Test
    fun `windows accumulate across reconnects`() {
        ledger.reset(startDown = true)
        advance(10_000); ledger.closeWindow()
        advance(60_000) // up
        ledger.openWindow()
        advance(5_000)
        assertEquals(15_000L, ledger.totalMs())
    }

    @Test
    fun `opening and closing are both idempotent`() {
        ledger.reset(startDown = false)
        ledger.openWindow()
        advance(1_000)
        ledger.openWindow() // must not restart the clock
        assertEquals(1_000L, ledger.totalMs())
        advance(1_000)
        assertEquals(2_000L, ledger.closeWindow())
        assertEquals(0L, ledger.closeWindow(), "a second close adds nothing")
        assertEquals(2_000L, ledger.accumMs)
    }

    @Test
    fun `roll banks the old period and starts the next one still down`() {
        ledger.reset(startDown = true)
        ledger.countReconnect()
        advance(48_000)

        assertEquals(48_000L, ledger.totalMs(), "the closing file reports the whole outage")
        ledger.roll()

        assertEquals(0, ledger.reconnects)
        assertEquals(0L, ledger.accumMs)
        assertTrue(ledger.isDown, "still down — the new period's window starts at the boundary")
        advance(7_000)
        assertEquals(7_000L, ledger.totalMs(), "the new period counts only its own time")
    }

    @Test
    fun `roll on a link that is UP leaves no window open`() {
        // The regression review caught. Reopening unconditionally left downSinceMs set
        // for the rest of the session; twenty minutes later the first real drop computed
        // elapsedMs ≈ 20 min, blew past ReconnectPolicy's 120 s deadline and gave up on
        // the first attempt.
        ledger.reset(startDown = true)
        advance(3_000)
        ledger.closeWindow() // the link came up
        assertFalse(ledger.isDown)

        ledger.roll()

        assertFalse(ledger.isDown, "a tap on a live link must not invent an outage")
        advance(20 * 60_000)
        assertEquals(0L, ledger.totalMs(), "twenty minutes of streaming is not downtime")
    }

    @Test
    fun `a window that ran for zero milliseconds is still a window`() {
        // `roll` must decide from isDown, not from closeWindow's return: same-millisecond
        // taps are exactly when a returned 0 means "nothing ran", not "nothing was open".
        ledger.reset(startDown = true)
        ledger.roll()
        assertTrue(ledger.isDown, "the link was down before the roll and still is after it")
    }

    @Test
    fun `reset clears everything, in both directions`() {
        ledger.reset(startDown = true)
        ledger.countReconnect()
        advance(9_000)
        ledger.closeWindow()

        ledger.reset(startDown = false)
        assertEquals(0, ledger.reconnects)
        assertEquals(0L, ledger.totalMs())
        assertFalse(ledger.isDown)

        ledger.reset(startDown = true)
        assertTrue(ledger.isDown)
    }

    @Test
    fun `reconnects count independently of the clock`() {
        ledger.reset(startDown = true)
        repeat(3) { ledger.countReconnect() }
        assertEquals(3, ledger.reconnects)
        ledger.roll()
        assertEquals(0, ledger.reconnects, "the next file counts its own reconnects")
    }
}
