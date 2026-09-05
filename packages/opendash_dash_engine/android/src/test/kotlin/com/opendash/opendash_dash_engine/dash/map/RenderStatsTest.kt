package com.opendash.opendash_dash_engine.dash.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The frame budget is the evidence behind "the loop waits for the snapshot".
 * If the summary silently reported the wrong window — carrying samples over, or
 * losing the late count — the decision it exists to test would be argued from a
 * lie rather than from impressions, which is worse.
 */
class RenderStatsTest {

    @Test
    fun `percentiles come out of the window, which then starts over`() {
        val p = Percentiles(capacity = 8)
        (1L..8L).forEach { p.add(it * 10) }

        assertEquals("50/80/80", p.drain())
        assertTrue(p.isEmpty, "the window must not carry into the next summary")
        assertEquals("-", p.drain())
    }

    @Test
    fun `the window keeps the newest samples, not the first ones`() {
        val p = Percentiles(capacity = 4)
        // Four cheap frames long past, then four expensive ones just now: the
        // summary has to describe the recent stretch of the ride.
        listOf(1L, 1L, 1L, 1L, 90L, 100L, 110L, 120L).forEach { p.add(it) }

        assertEquals("110/120/120", p.drain())
    }

    @Test
    fun `a snapshot over the frame interval counts as late`() {
        val stats = RenderStats()
        stats.mapDrawn(snapshotMs = 300, overlayMs = 4, budgetMs = 250, blank = false)
        stats.mapDrawn(snapshotMs = 120, overlayMs = 4, budgetMs = 250, blank = false)

        assertTrue(stats.drain(periodMs = 30_000, timeouts = 0, skipped = 0, abandoned = 0, errors = 0, rebuilds = 0).contains("late=1"))
    }

    @Test
    fun `frames that reused the previous map are visible as such`() {
        val stats = RenderStats()
        repeat(10) { stats.frameSent(intervalMs = 250, encodeMs = 6, intendedIntervalMs = 250) }
        repeat(3) { stats.mapDrawn(snapshotMs = 90, overlayMs = 4, budgetMs = 250, blank = false) }

        val line = stats.drain(periodMs = 30_000, timeouts = 0, skipped = 0, abandoned = 0, errors = 0, rebuilds = 0)

        // Ten frames left the phone; only three of them redrew the map. That gap
        // IS the frame-signature cache doing its job, and losing sight of it is
        // how the redraw-only-on-change saving gets "optimised" away.
        assertTrue(line.contains("frames=10/120"), line)
        assertTrue(line.contains("redraws=3"), line)
        assertTrue(line.contains("reused=7"), line)
    }

    @Test
    fun `a window of riding and standing is measured against both, not the last one`() {
        // Half the window at 2 fps, half at 4 fps: 60 frames of each is exactly on
        // target for 30 s. Reading the target once at summary time — as this used
        // to — would have called the same window either 120 or 60 expected frames
        // depending on whether the rider happened to be moving when the log fired,
        // and this is the one number the "wait for the snapshot" decision rests on.
        val stats = RenderStats()
        repeat(60) { stats.frameSent(intervalMs = 500, encodeMs = 6, intendedIntervalMs = 500) }
        repeat(60) { stats.frameSent(intervalMs = 250, encodeMs = 6, intendedIntervalMs = 250) }

        val line = stats.drain(periodMs = 30_000, timeouts = 0, skipped = 0, abandoned = 0, errors = 0, rebuilds = 0)

        // 120 frames intending 45 s of pacing, squeezed into a 30 s window → 80.
        assertTrue(line.contains("frames=120/80"), line)
    }

    @Test
    fun `an empty window admits it rather than inventing a target`() {
        val stats = RenderStats()

        assertTrue(stats.drain(periodMs = 30_000, timeouts = 0, skipped = 0, abandoned = 0, errors = 0, rebuilds = 0).contains("frames=0/?"))
    }

    @Test
    fun `counters reset with the window but the cumulative ones do not`() {
        val stats = RenderStats()
        stats.frameSent(intervalMs = 250, encodeMs = 6, intendedIntervalMs = 250)
        stats.mapDrawn(snapshotMs = 400, overlayMs = 4, budgetMs = 250, blank = true)
        stats.drain(periodMs = 30_000, timeouts = 2, skipped = 3, abandoned = 1, errors = 1, rebuilds = 0)

        val second = stats.drain(periodMs = 30_000, timeouts = 2, skipped = 3, abandoned = 1, errors = 1, rebuilds = 0)
        assertTrue(second.contains("redraws=0"), second)
        assertTrue(second.contains("blank=0"), second)
        assertFalse(second.contains("late=1"), second)
        // The intended-interval accumulator has to reset too, or the next window
        // is measured against the previous one's pacing.
        assertTrue(second.contains("frames=0/?"), second)
        // Owned by MapSnapshotProvider and passed straight through: they are
        // cumulative over the session, and it is the provider — not the window —
        // that zeroes them for the next one.
        assertTrue(second.contains("timeouts=2 skipped=3 wedged=1 snapErr=1"), second)
    }

    @Test
    fun `frames skipped because the snapshotter was busy are reported apart from timeouts`() {
        val stats = RenderStats()
        stats.frameSent(intervalMs = 250, encodeMs = 6, intendedIntervalMs = 250)
        // What a wedged-but-not-yet-abandoned snapshotter looks like from the log:
        // the frame loop never waited (so nothing timed out) and never redrew.
        val line = stats.drain(periodMs = 30_000, timeouts = 0, skipped = 42, abandoned = 0, errors = 0, rebuilds = 0)
        assertTrue(line.contains("skipped=42"), line)
        assertTrue(line.contains("timeouts=0"), line)
    }

    @Test
    fun `a rebuilt snapshotter is visible in the line`() {
        val stats = RenderStats()
        stats.frameSent(intervalMs = 250, encodeMs = 6, intendedIntervalMs = 250)
        val line = stats.drain(periodMs = 30_000, timeouts = 0, skipped = 0, abandoned = 1, errors = 5, rebuilds = 1)
        // "wedged=1 rebuilds=1" is the whole difference between a map that came
        // back and one that stayed blank for the rest of the ride.
        assertTrue(line.contains("wedged=1"), line)
        assertTrue(line.contains("rebuilds=1"), line)
    }

    @Test
    fun `a new stream does not inherit the previous session's frames`() {
        val stats = RenderStats()
        // A session that ended before its window closed: 40 frames counted, never
        // drained. Without reset() they landed in the next session's first window,
        // which is measured from zero — that is how frames=195/120 was logged on a
        // loop that was pacing exactly to 250 ms.
        repeat(40) { stats.frameSent(intervalMs = 250, encodeMs = 6, intendedIntervalMs = 250) }
        stats.mapDrawn(snapshotMs = 40, overlayMs = 10, budgetMs = 250, blank = true)

        stats.reset()

        repeat(120) { stats.frameSent(intervalMs = 250, encodeMs = 6, intendedIntervalMs = 250) }
        val line = stats.drain(periodMs = 30_000, timeouts = 0, skipped = 0, abandoned = 0, errors = 0, rebuilds = 0)
        assertTrue(line.contains("frames=120/120"), line)
        assertTrue(line.contains("redraws=0 "), line)
        assertTrue(line.contains("blank=0"), line)
    }
}
