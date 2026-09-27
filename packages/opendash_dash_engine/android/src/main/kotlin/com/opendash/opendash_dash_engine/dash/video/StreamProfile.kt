package com.opendash.opendash_dash_engine.dash.video

/**
 * The dash's two encoding presets, and the one place that chooses between them.
 *
 * **Why this type exists.** fps and bitrate are one decision — the dash has exactly two
 * presets and they move together — but the code used to express it in three places: an
 * `if (moving) FPS_MOVING else FPS_IDLE` for the rate, a two-branch transition guard for
 * the bitrate, and a `Boolean idleBitrate` whose name is the negation of what the other
 * two read. Three expressions of one fact can disagree, and the third one already had to
 * be re-synced by hand after an encoder rebuild. Task 10 of `network-refactoring.md`.
 *
 * **Which signal decides, and why it is not the vendor's.** The original app switches on
 * **distance to the next manoeuvre** — high preset under 1000 m, low above, 200 m of
 * hysteresis (`spec/video.md`). We switch on **whether the camera is moving**
 * ([com.opendash.opendash_dash_engine.dash.map.FrameRatePolicy], speed with its own
 * hysteresis and dwell). Both signals are available to us, so this is a deliberate
 * divergence rather than a limitation, and the reason is what each one is actually a
 * proxy for:
 *
 *  - The picture only needs frames when it **changes**, and what changes it is the camera
 *    moving. Distance to a manoeuvre is a proxy for that and a poor one at the ends: a
 *    bike parked 200 m from a turn gets the expensive preset for a static image, and one
 *    cruising a motorway 5 km from the next exit gets the cheap one while the map scrolls
 *    under it at 100 km/h. Our signal has neither failure.
 *  - Ours works with no route at all. The dash runs a map without navigation
 *    (`spec/fsm.md`), and in that state `primaryDistanceInMeters` does not exist — the
 *    vendor's rule has nothing to say, and would have to fall back to a constant.
 *
 * **What we give up by not using distance.** Approaching a junction slowly — which is
 * exactly when the rider looks at the dash — the camera is barely moving, so we serve
 * 2 fps where the original serves 4. That is the one case the vendor's rule wins, and it
 * is not hypothetical. Nothing in the field has been attributed to it, but nothing has
 * looked for it either; if it ever shows up as "the dash lags at turns", this is the
 * first place to come back to, and the fix is to OR the two signals rather than to swap
 * them.
 *
 * **The numbers are the dash's own, not ours.** [DashEncoder.BITRATE] is the vendor's
 * high preset (204 800, we send 200 000) and [DashEncoder.BITRATE_IDLE] its low one
 * (102 400 → 100 000); see those KDocs for why staying under the profile is not an
 * efficiency nicety. The frame rates match the vendor's presets exactly.
 */
internal enum class StreamProfile(
    val fps: Int,
    val bitrateBps: Int,
    /**
     * How this profile appears in the ride file's `[stream] … bitrate=` field.
     *
     * Spelled out rather than derived from [name]: the ride-log format is append-only and
     * greppable (инвариант 11), and these two words have been in field logs since before
     * this type existed.
     */
    val logName: String,
) {
    /**
     * Camera in motion: the vendor's high preset.
     *
     * [DashEncoder.FPS] rather than a literal 4, because that constant is the
     * `KEY_FRAME_RATE` hint the codec is configured with and it has to mean the fastest
     * rate the loop will actually feed. Two 4s in two files was the last of the three
     * duplicated expressions this type was created to remove.
     */
    Moving(fps = DashEncoder.FPS, bitrateBps = DashEncoder.BITRATE, logName = "moving"),

    /** Camera at rest: the vendor's low preset. */
    Idle(fps = 2, bitrateBps = DashEncoder.BITRATE_IDLE, logName = "idle"),
    ;

    /**
     * Nominal spacing between frames, in milliseconds.
     *
     * The frame loop uses this for three things that must agree — the render deadline, the
     * trailing pace, and the RTP timestamp step. Computing it here once is what keeps them
     * from being three roundings of the same division.
     */
    val frameIntervalMs: Long get() = 1000L / fps

    companion object {
        /**
         * The whole rule, in one place.
         *
         * [moving] comes from [com.opendash.opendash_dash_engine.dash.FrameSource.moving],
         * which is the renderer's hysteretic verdict on ground speed — not a raw speed
         * sample, so this function deliberately has no thresholds of its own to get out of
         * step with it.
         */
        fun forMotion(moving: Boolean): StreamProfile = if (moving) Moving else Idle

        /**
         * What a freshly configured encoder is already running at.
         *
         * [DashEncoder.configure] sets `KEY_BIT_RATE` to [DashEncoder.BITRATE], so a new or
         * rebuilt codec starts on [Moving] whatever the previous one was last told. The
         * caller has to record that, because the bitrate is only pushed on a CHANGE: a
         * rebuild while parked used to leave the flag claiming "idle" over a codec running
         * at the moving target, and nothing corrected it until the rider set off and
         * stopped again.
         */
        val asConfigured: StreamProfile get() = Moving
    }
}
