package com.opendash.opendash_dash_engine.dash.map

/**
 * Decides whether the frame loop runs at the moving or the idle rate, from ground
 * speed alone.
 *
 * **The predicate this replaces had its two thresholds the wrong way round.** It read
 * `movedM > 0.25 || speed > 0.8`, where `movedM` is how far the smoothed camera stepped
 * in ONE tick — and the length of a tick is exactly what the verdict controls. At 2 fps
 * a tick is 0.5 s, so 0.25 m fires at 0.5 m/s; at 4 fps it is 0.25 s, so the same 0.25 m
 * needs 1.0 m/s. Combined with the speed term that made the ENTRY threshold 0.5 m/s and
 * the EXIT threshold 0.8 m/s — entry below exit, i.e. inverted hysteresis. Anything
 * between 1.8 and 2.9 km/h therefore flipped on every single frame and could never
 * settle: manoeuvring in the yard, walking the bike, stop-and-go traffic. Each flip also
 * re-targeted the live encoder (200↔100 kbps, deliberately without a key frame), so the
 * rate controller was whipsawed mid-GOP for as long as the rider crept along.
 *
 * Taking speed as the input removes the feedback — the verdict is about the rider, not
 * about the rate we happen to be at. Separated thresholds and [dwellMs] remove the
 * dither that is left. It lives in its own class, with no Android in it, because an
 * oscillator that nothing could test is what produced the bug in the first place.
 */
class FrameRatePolicy(
    private val enterMps: Double,
    private val exitMps: Double,
    private val dwellMs: Long,
) {
    init {
        // Entry above exit is the whole point; the old predicate's failure was exactly
        // this relation being inverted, so it is worth refusing to build rather than
        // rediscovering it in a ride log.
        require(enterMps > exitMps) { "enter ($enterMps) must be above exit ($exitMps)" }
    }

    var moving: Boolean = false
        private set

    private var flips = 0

    /**
     * Null until the first flip, rather than 0.
     *
     * With 0 the dwell check reads `nowMs - 0 >= dwellMs`, so whether the FIRST verdict
     * is allowed through depends on the absolute value of the clock handed in — true for
     * epoch milliseconds, false for a test that starts near zero. A policy whose opening
     * behaviour changes with what o'clock it is cannot be tested, and the whole reason
     * this class exists is that its predecessor could not be.
     */
    private var lastFlipMs: Long? = null

    /** The verdict for this tick. [nowMs] is wall clock; [speedMps] is ground speed. */
    fun update(speedMps: Double, nowMs: Long): Boolean {
        val want = when {
            !moving && speedMps > enterMps -> true
            moving && speedMps < exitMps -> false
            else -> moving
        }
        val since = lastFlipMs
        if (want != moving && (since == null || nowMs - since >= dwellMs)) {
            moving = want
            lastFlipMs = nowMs
            flips++
        }
        return moving
    }

    /**
     * Flips since the last drain, for the `[stream]` line.
     *
     * Without it the fix is unfalsifiable from a ride file: a window reporting
     * `bitrate=idle` alongside 213 frames (session 22:11 on 2026-09-09) is either a
     * rider who stopped halfway through it or a predicate dithering every frame, and
     * nothing in the log separated the two.
     */
    fun drainFlips(): Int {
        val n = flips
        flips = 0
        return n
    }
}
