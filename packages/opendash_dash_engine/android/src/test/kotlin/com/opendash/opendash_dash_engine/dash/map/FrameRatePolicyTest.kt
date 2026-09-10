package com.opendash.opendash_dash_engine.dash.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The oscillation this policy exists to prevent, pinned as a test.
 *
 * [aSteadyCreepDoesNotDither] is the one that matters: 0.6 m/s is inside the band where
 * the old predicate flipped on every frame, because its entry threshold (0.5 m/s at
 * 2 fps) sat below its exit threshold (0.8 m/s). Every flip re-targeted the live encoder
 * between the 200 and 100 kbps profiles, mid-GOP.
 */
class FrameRatePolicyTest {

    private fun policy() = FrameRatePolicy(enterMps = 0.9, exitMps = 0.5, dwellMs = 2_000L)

    @Test
    fun aSteadyCreepDoesNotDither() {
        val p = policy()
        var t = 10_000L
        // Walking pace, held for a minute of 4 fps ticks.
        repeat(240) {
            p.update(speedMps = 0.6, nowMs = t)
            t += 250
        }
        assertFalse(p.moving, "0.6 m/s is under the entry threshold — it must stay idle")
        assertEquals(0, p.drainFlips(), "and it must not have flipped even once")
    }

    @Test
    fun itEntersAboveEnterAndLeavesBelowExit() {
        val p = policy()
        assertFalse(p.update(0.8, 10_000L), "0.8 is below entry")
        assertTrue(p.update(1.0, 20_000L), "1.0 is above entry")
        assertTrue(p.update(0.6, 30_000L), "0.6 is between the two — hold what we have")
        assertFalse(p.update(0.4, 40_000L), "0.4 is below exit")
        assertEquals(2, p.drainFlips())
    }

    @Test
    fun theBandBetweenTheThresholdsHoldsEitherState() {
        // Same speed, opposite verdicts, decided by where each one came from. That is
        // what hysteresis IS, and what the old predicate got backwards.
        val fromIdle = policy().apply { update(0.0, 0L); update(0.7, 10_000L) }
        val fromMoving = policy().apply { update(2.0, 0L); update(0.7, 10_000L) }
        assertFalse(fromIdle.moving)
        assertTrue(fromMoving.moving)
    }

    @Test
    fun aFlipIsHeldUntilTheDwellHasPassed() {
        val p = policy()
        assertTrue(p.update(2.0, 10_000L))
        assertTrue(p.update(0.0, 11_900L), "1.9 s later — still inside the dwell, hold")
        assertFalse(p.update(0.0, 12_000L), "2.0 s later — now it may fall back")
    }

    @Test
    fun flipsAreCountedAndDrained() {
        val p = policy()
        p.update(2.0, 0L)
        p.update(0.0, 10_000L)
        p.update(2.0, 20_000L)
        assertEquals(3, p.drainFlips())
        assertEquals(0, p.drainFlips(), "the window starts clean")
    }

    @Test
    fun invertedThresholdsAreRefusedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            FrameRatePolicy(enterMps = 0.5, exitMps = 0.8, dwellMs = 0L)
        }
    }
}
