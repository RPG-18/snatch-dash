package com.opendash.opendash_dash_engine.dash

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The one decision in the Wi-Fi layer a test can reach.
 *
 * Everything else about reconnecting needs a live `ConnectivityManager`; this class was
 * extracted precisely so the arithmetic behind "wait this long, then give up" stops being
 * checkable only by reading. The jitter is supplied, never global — a test that cannot fix
 * it could only assert a range, which says nothing about the rule.
 */
class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    /** A [Random] that always returns the top of the range, exposing the ceiling itself. */
    private fun maxed() = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextLong(until: Long) = until - 1
    }

    /** …and one that always returns the bottom. */
    private fun zeroed() = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextLong(until: Long) = 0L
    }

    private fun delayAt(attempt: Int, random: Random) = assertIs<Decision.RetryIn>(
        policy.next(attempt, everConnected = true, elapsedMs = 0, random = random),
    ).delayMs

    @Test
    fun `the ceiling doubles per attempt until it hits the cap`() {
        assertEquals(2_000, delayAt(0, maxed()))
        assertEquals(4_000, delayAt(1, maxed()))
        assertEquals(8_000, delayAt(2, maxed()))
        assertEquals(16_000, delayAt(3, maxed()))
        assertEquals(30_000, delayAt(4, maxed()), "32s would exceed the 30s cap")
        assertEquals(30_000, delayAt(9, maxed()), "and it stays there")
    }

    @Test
    fun `the wait is spread over the whole range, not fixed at its top`() {
        // Full jitter, and this is what it buys: a phone that just missed the window does
        // not keep missing it by exactly the same offset. The flat 8 s this replaced had
        // every retry after a dead zone land in lockstep with the one before.
        assertEquals(0, delayAt(3, zeroed()))
        assertEquals(16_000, delayAt(3, maxed()))
    }

    @Test
    fun `the same seed gives the same sequence`() {
        fun run() = (0..5).map { delayAt(it, Random(20260919)) }
        assertEquals(run(), run())
    }

    @Test
    fun `a huge attempt count cannot wrap the shift into a bad bound`() {
        // `Long.shl` takes its count modulo 64, so an unclamped shift WRAPS rather than
        // saturating: `2000 shl 62` keeps only the two low bits of 2000, which are zero, so
        // the ceiling collapses to 0 and `2000 shl 64` is 2000 again. Both land inside any
        // plausible range, which is why the ceiling is asserted exactly — a range check
        // here passes on the broken version.
        for (attempt in listOf(31, 62, 63, 64, 1_000, Int.MAX_VALUE)) {
            assertEquals(30_000, delayAt(attempt, maxed()), "ceiling at attempt=$attempt")
        }
        // And with a real Random, which unlike the fakes above refuses a negative bound:
        // the other half of the same wrap is a ceiling that goes below zero.
        for (attempt in listOf(31, 62, 63, 64, 1_000, Int.MAX_VALUE)) {
            val delay = assertIs<Decision.RetryIn>(
                policy.next(attempt, everConnected = true, elapsedMs = 0, random = Random(7)),
            ).delayMs
            assertTrue(delay in 0..30_000, "attempt=$attempt gave ${delay}ms")
        }
    }

    // ── Giving up ─────────────────────────────────────────────────────────

    @Test
    fun `a link that never worked gets two attempts in total, and no more`() {
        // Two ATTEMPTS, so one retry: the first attempt already failed by the time the
        // policy is asked at all. At a 30 s CONNECT_TIMEOUT each that is ~66 s before the
        // rider is told something is wrong, which is the point of the limit.
        assertIs<Decision.RetryIn>(policy.next(0, everConnected = false, elapsedMs = 0, random = maxed()))

        val second = assertIs<Decision.GiveUp>(
            policy.next(1, everConnected = false, elapsedMs = 0, random = maxed()),
        )
        assertTrue(second.reason.contains("2 attempts and never connected"), second.reason)
    }

    @Test
    fun `a link that worked once keeps being retried past that limit`() {
        // The likely cause there is distance, and distance ends when the rider comes back.
        assertIs<Decision.RetryIn>(
            policy.next(9, everConnected = true, elapsedMs = 60_000, random = maxed()),
        )
    }

    @Test
    fun `two minutes without the dash ends it whatever the history`() {
        for (connected in listOf(true, false)) {
            val out = assertIs<Decision.GiveUp>(
                policy.next(0, everConnected = connected, elapsedMs = 120_000, random = maxed()),
            )
            assertTrue(out.reason.contains("120s"), out.reason)
        }
    }

    @Test
    fun `the deadline wins over an attempt count that would still retry`() {
        assertIs<Decision.GiveUp>(
            policy.next(0, everConnected = true, elapsedMs = 119_999 + 1, random = maxed()),
        )
        assertIs<Decision.RetryIn>(
            policy.next(0, everConnected = true, elapsedMs = 119_999, random = maxed()),
        )
    }

    @Test
    fun `a negative attempt is a programming error, not a delay`() {
        assertFailsWith<IllegalArgumentException> {
            policy.next(-1, everConnected = true, elapsedMs = 0, random = maxed())
        }
    }
}
