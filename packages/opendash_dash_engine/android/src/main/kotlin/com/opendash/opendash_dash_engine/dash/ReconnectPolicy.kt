package com.opendash.opendash_dash_engine.dash

import kotlin.random.Random

/**
 * What to do after a failed attempt to reach the dash's Wi-Fi.
 *
 * Two outcomes and nothing in between: wait this long and try again, or stop. "Stop" is a
 * real answer — a rider whose dash is off should get an error rather than a phone retrying
 * into an empty road for the rest of the day.
 */
internal sealed interface Decision {
    /** Try again after [delayMs]. */
    data class RetryIn(val delayMs: Long) : Decision

    /** Stop trying and report the failure. [reason] goes into the ride file verbatim. */
    data class GiveUp(val reason: String) : Decision
}

/**
 * When to retry the Wi-Fi request, as arithmetic rather than as a constant.
 *
 * Pure and free of Android by design: this is the one decision in the Wi-Fi layer that can
 * be checked without a live `ConnectivityManager`, and the layer around it has none — nine
 * review findings in one evening (2026-09-19, §0.1) landed on code no test could reach.
 *
 * **Full jitter, not a fixed delay.** The previous behaviour was a flat 8 s, which has the
 * property nobody wants on a bike: every retry after a dead zone lands at the same offset
 * from the one before, so a phone that just missed the window keeps missing it. Spreading
 * each wait uniformly over `0..backoff` breaks that lockstep. The shape is AWS's "full
 * jitter": `sleep = random(0, min(cap, base * 2^attempt))`, chosen over "equal jitter"
 * because the whole point here is that the FIRST retry can be quick — the commonest failure
 * is a link that dropped for two seconds.
 *
 * @param baseMs first backoff ceiling, doubling per attempt.
 * @param capMs ceiling the doubling stops at. 30 s because the rider is on the bike and
 *   the dash is a metre away: beyond that a retry is indistinguishable from giving up.
 * @param giveUpAfterMs total time from the first attempt after which retrying stops,
 *   whatever the attempt count. Moved here from `DashEngineController.RECONNECT_GIVEUP_MS`.
 * @param coldRetries how many RETRIES a connection that has never succeeded gets — so the
 *   total is `1 + coldRetries` attempts, and at a 30 s `CONNECT_TIMEOUT` each that is about
 *   66 s to the error. One, because "never connected" almost always means the wrong SSID, a
 *   wrong password or a dash that is off, and none of those change with a third attempt; the one
 *   case that does — a dash whose AP is slow to come up — is covered by the second.
 *
 *   The plan's задача 5.1 writes this as `attempt >= 2`, which is one more attempt than its
 *   own scenario B ("GiveUp после второй попытки") describes. The prose wins: the formula
 *   would spend 96 s before telling the rider anything. Deviation recorded in §0.1.
 *
 *   A link that HAS worked once is not counted at all — it is retried until
 *   [giveUpAfterMs], because there the likely cause is distance, and distance ends when the
 *   rider comes back.
 */
internal class ReconnectPolicy(
    private val baseMs: Long = 2_000L,
    private val capMs: Long = 30_000L,
    private val giveUpAfterMs: Long = 120_000L,
    private val coldRetries: Int = 1,
) {
    /**
     * @param attempt how many attempts have already failed; 0 for the first decision.
     * @param everConnected whether this connection reached the dash at least once.
     * @param elapsedMs since the first attempt of this connection.
     * @param random passed in, never taken from a global: a test that cannot fix the jitter
     *   can only assert that the delay is "somewhere in a range", which is not an assertion
     *   about this class's behaviour at all.
     */
    fun next(
        attempt: Int,
        everConnected: Boolean,
        elapsedMs: Long,
        random: Random,
    ): Decision {
        require(attempt >= 0) { "attempt must not be negative, was $attempt" }
        if (elapsedMs >= giveUpAfterMs) {
            return Decision.GiveUp("${giveUpAfterMs / 1_000}s without reaching the dash")
        }
        if (!everConnected && attempt >= coldRetries) {
            return Decision.GiveUp("${coldRetries + 1} attempts and never connected")
        }
        return Decision.RetryIn(random.nextLong(ceilingFor(attempt) + 1))
    }

    /**
     * The backoff ceiling for [attempt] — `base * 2^attempt`, clamped to [capMs].
     *
     * [MAX_SHIFT] is the whole of the overflow guard, and clamping the EXPONENT is what
     * does it — a `raw <= 0` check afterwards would not. `Long.shl` takes its count modulo
     * 64, so a large enough attempt does not saturate, it wraps: `2000 shl 64` is 2000
     * again, which is not merely wrong but non-monotonic, and the cap below assumes
     * monotonic. A count that lands on a negative additionally hands `nextLong` a negative
     * bound to throw on.
     *
     * Not theoretical: a connection that has worked once is bounded by time, not by attempt
     * count, so nothing else stops `attempt` from growing.
     */
    private fun ceilingFor(attempt: Int): Long =
        (baseMs shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(capMs)

    private companion object {
        /** `2 s shl 20` is already 24 days — past this the cap decides anyway. */
        const val MAX_SHIFT = 20
    }
}
