package com.opendash.opendash_dash_engine.dash.map

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deciding which position reports to believe — and when to believe none of them.
 *
 * Split out of [LocationTracker] on 2026-09-13 so it can be tested without a device, the same
 * way [FrameRatePolicy] was: no Android types cross this file's boundary, and [Fix] carries
 * only the numbers the decisions actually use.
 *
 * **The ride that produced this file.** 2026-09-13 19:34-19:47, riding near 60.0491, 30.4308
 * under active GNSS jamming. What the app was told:
 *
 * | source    | claimed accuracy | actual error |
 * |-----------|------------------|--------------|
 * | `network` | 816 m            | **53.4 km**  |
 * | `gps`     | 10-20 m          | **69.6 km**  |
 *
 * Nothing in software recovers a true position from that. But the two sources disagreed with
 * EACH OTHER by 35.1 km while both claimed sub-kilometre accuracy, and that contradiction is
 * detectable without knowing where the rider actually was. Instead the app silently picked one
 * and drew a confident map 70 km away, with the "GPS weak" indicator green the whole time —
 * the spoofed fixes reported 10-20 m, well inside [LocationTracker.DEGRADED_ACCURACY_M].
 *
 * So the goal here is NOT spoofing detection, which is deep and unreliable. It is the same
 * distinction the `sockets: tos` line had to learn: "we were told this" is not "this is true".
 * A dash that admits it does not know where it is beats a dash showing the wrong lake.
 */
internal data class Fix(
    val lat: Double,
    val lon: Double,
    /**
     * Claimed horizontal accuracy, or null when the platform did not supply one.
     *
     * Null rather than 0f, because Android's `Location.getAccuracy()` returns 0 when
     * `hasAccuracy()` is false — and 0 read as a number means "perfect", which is the
     * opposite of what it says. Both users below widen their tolerance for an unknown
     * instead of tightening it: the failure to avoid is crying wolf on a fix that never
     * claimed to be precise.
     */
    val accuracyM: Float?,
    /** The fix's own timestamp, not arrival time — [FixFilter] reasons about fix age. */
    val atMs: Long,
    val speedMps: Float,
    val isGps: Boolean,
)

internal object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine. Metres. */
    fun distanceM(a: Fix, b: Fix): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }
}

/**
 * Accept or reject one incoming fix against the one currently held.
 *
 * Two guards, both about physical plausibility: an outright teleport, and a step too large for
 * the speed involved. Neither asks which provider is more trustworthy — that is [providerUpgrade]'s
 * job, and getting the interaction wrong is what broke the 2026-09-13 ride.
 */
internal class FixFilter {

    private companion object {
        /** Nothing on a road covers ground this fast; above it the fix is not a fix. */
        const val TELEPORT_SPEED_MPS = 85.0
        const val TELEPORT_MIN_JUMP_M = 200f

        /** Beyond this many consecutive rejections, the held fix is the suspect. */
        const val MAX_REJECT_STREAK = 3

        /** A seeded or held fix older than this has no authority over a fresh one. */
        const val ANCHOR_MAX_AGE_MS = 30_000L

        /**
         * Rejections must persist this long before the held fix is treated as the suspect.
         *
         * Without the time gate the streak alone made every fourth fix acceptable however
         * impossible, so a few seconds of multipath could re-anchor the map onto a bogus
         * position and then start rejecting the real stream — an oscillation worse than the
         * lockout it guards against. On 2026-09-13 the rejections ran for 100 s, so a
         * ten-second floor still catches the case this exists for. Found by review.
         */
        const val REJECT_STREAK_WINDOW_MS = 10_000L

        /** Stands in for an unknown accuracy, chosen to make the plausibility gate wider. */
        const val ASSUMED_ACCURACY_M = 1_000f

        /** While a GPS fix is younger than this, a coarse NETWORK fix is ignored entirely. */
        const val GPS_STALE_MS = 10_000L
    }

    private var rejectStreak = 0
    private var firstRejectAtMs = 0L

    /** Reset when the tracker restarts, so a new ride does not inherit a streak. */
    fun reset() {
        rejectStreak = 0
        firstRejectAtMs = 0L
    }

    /**
     * GPS supersedes NETWORK, always, and no plausibility guard may stand in the way.
     *
     * This is the 2026-09-13 fix. A NETWORK fix claiming 816 m arrived first, became the held
     * fix (the old code accepted the first of anything unconditionally), and every real GPS fix
     * that followed looked like a teleport RELATIVE TO THAT LIE — 53 rejected against 4
     * accepted in the first minute, with "location may be stale/frozen" logged four times while
     * a 14-metre fix was being turned away every second.
     *
     * The principle the old code was missing: **the guards compare like with like.** A jump
     * measured from a coarse fix to a precise one says nothing about whether the precise one is
     * real, so a provider upgrade is not a teleport and must not be judged as one. It does not
     * need an escape hatch either — it IS the escape hatch, and it fires on the first GPS fix
     * rather than after a streak.
     */
    private fun providerUpgrade(cur: Fix, loc: Fix): Boolean = loc.isGps && !cur.isGps

    fun accept(cur: Fix?, loc: Fix): Boolean {
        if (cur == null) return clearStreak()

        // Monotonicity comes FIRST, ahead of the provider preference. Otherwise a GPS fix
        // timestamped before the held one still wins on being GPS, and the held position
        // walks backwards in time — which reads as a stale fix to the 4 s gate in
        // DashEngineController.tick() and can pin the speed baseline in the future. Review.
        if (loc.atMs < cur.atMs) return false

        if (providerUpgrade(cur, loc)) return clearStreak()

        // A coarse fix cannot displace a fresh GPS one: GPS goes quiet for a moment while
        // parked, and letting NETWORK in there makes the marker wander (2026-08 field note).
        if (!loc.isGps && cur.isGps && loc.atMs - cur.atMs < GPS_STALE_MS) return false

        // A held fix this old cannot outvote a fresh one whatever the geometry says — the
        // usual way in is a stale getLastKnownLocation seed from hours ago.
        if (loc.atMs - cur.atMs > ANCHOR_MAX_AGE_MS) return clearStreak()

        val dt = (loc.atMs - cur.atMs) / 1000.0
        val jump = Geo.distanceM(cur, loc)

        // Both guards share the streak escape hatch now — only the second one had it before,
        // so a wrong anchor could reject through the teleport guard forever, which is what
        // did the damage on 2026-09-13. The hatch is gated on DURATION as well as count: see
        // REJECT_STREAK_WINDOW_MS for why a bare count is its own bug.
        if (rejectStreak >= MAX_REJECT_STREAK &&
            loc.atMs - firstRejectAtMs >= REJECT_STREAK_WINDOW_MS
        ) return clearStreak()

        if (dt > 0 && jump > TELEPORT_MIN_JUMP_M && jump / dt > TELEPORT_SPEED_MPS) return reject(loc)
        if (dt in 0.0..6.0) {
            val plausibleSpeed = maxOf(cur.speedMps, loc.speedMps).coerceAtLeast(1f)
            val expected = plausibleSpeed * dt
            val noise = (loc.accuracyM ?: ASSUMED_ACCURACY_M) + (cur.accuracyM ?: ASSUMED_ACCURACY_M)
            val gate = expected + noise * 1.5 + 12f
            if (jump > gate && jump > 25f) return reject(loc)
        }
        return clearStreak()
    }

    private fun clearStreak(): Boolean {
        rejectStreak = 0
        firstRejectAtMs = 0L
        return true
    }

    private fun reject(loc: Fix): Boolean {
        if (rejectStreak == 0) firstRejectAtMs = loc.atMs
        rejectStreak++
        return false
    }
}

/**
 * Whether the position stream as a whole can be believed.
 *
 * Separate from [FixFilter] on purpose: the filter picks between fixes, this one asks whether
 * any of them should be trusted at all. It therefore observes EVERY fix, including the ones the
 * filter threw away — a rejected fix is still evidence about the receiver.
 */
internal class PositionTrust(private val nowMs: () -> Long = System::currentTimeMillis) {

    companion object {
        /**
         * How far two providers may disagree before neither is believable, as a multiple of
         * their combined claimed accuracy, plus a floor.
         *
         * Deliberately generous: this is meant to catch the impossible, not the imprecise.
         * On 2026-09-13 the gap was 35.1 km against a combined claim of ~836 m — a factor of
         * 42. A real disagreement between a cell-tower fix and GPS is a few hundred metres.
         */
        const val DISAGREEMENT_FACTOR = 4.0
        const val DISAGREEMENT_FLOOR_M = 500.0

        /** Both fixes must be about the same moment for their disagreement to mean anything. */
        const val FRESHNESS_MS = 30_000L

        /**
         * Implied AVERAGE speed over [SPEED_WINDOW_MS] above which the stream is not a ride.
         *
         * 40 m/s is 144 km/h — sustained for thirty seconds, not glimpsed. A judgement call,
         * and the two numbers it sits between are these: the 2026-09-13 log implied 95-207
         * km/h between camera windows on a motorcycle doing none of it, and a legal Russian
         * motorway is 110 km/h. The first draft of this constant was 60 m/s (216 km/h), which
         * was ABOVE the 207 km/h case used to justify it — caught by the test, not by reading.
         *
         * A genuinely fast half-minute will trip this. The cost is one warning line in the
         * ride file and the GPS chip reading weak; nothing stops working.
         */
        const val IMPLAUSIBLE_SPEED_MPS = 40.0
        const val SPEED_WINDOW_MS = 30_000L
        /** Below this the implied speed is noise amplified by a short baseline. */
        const val SPEED_MIN_BASELINE_MS = 5_000L

        /** Stands in for an unknown accuracy, chosen to make every gate wider, not tighter. */
        const val ASSUMED_ACCURACY_M = 1_000f

        /**
         * How long one doubt keeps the position untrusted after the last objection.
         *
         * The verdict has to LATCH. Without this it was a pure function of the latest fix, and
         * [Doubt.ImplausibleSpeed] — which by construction fires at most once per
         * [SPEED_WINDOW_MS] — cleared on the next fix a second later. With no NETWORK provider
         * to raise the other kind of doubt, the rider's GPS chip would have shown weak for
         * about a second in every thirty and green the rest of the time: exactly the "green for
         * the whole ride" failure this class exists to prevent. Found by review, 2026-09-13.
         *
         * Slightly longer than the speed window so consecutive windows overlap instead of
         * leaving a gap of misplaced confidence between them.
         */
        const val TRUST_HOLD_MS = 35_000L
    }

    /** What is wrong, in the words the ride file will carry. Null means nothing is. */
    sealed interface Doubt {
        data class ProviderDisagreement(
            val gapM: Double,
            val gpsAccuracyM: Float?,
            val networkAccuracyM: Float?,
        ) : Doubt

        data class ImplausibleSpeed(val impliedMps: Double, val overMs: Long) : Doubt
    }

    private var lastGps: Fix? = null
    private var lastNetwork: Fix? = null

    // One speed baseline PER PROVIDER, not one shared. A shared baseline had to be discarded
    // on every handover (measuring across one gives the handover distance, not a speed), and
    // an alternating stream — precisely what an intermittent lock under jamming produces —
    // then never accumulated the 30 s the check needs and could never fire in the field.
    // Review found it; the tests only ever fed one provider at a time.
    private var windowStartGps: Fix? = null
    private var windowStartNetwork: Fix? = null
    private var doubtedUntilMs = 0L

    /**
     * Whether the position may be acted on, latched for [TRUST_HOLD_MS] past the last doubt.
     *
     * Wall clock, not fix timestamps, and deliberately: the reader is a render tick with no fix
     * in hand. Injectable so the hold is testable without sleeping — the mistake to avoid here
     * is FrameRatePolicy's, where a zero-initialised `lastFlipMs` was compared against an
     * absolute clock and blocked the first transition for the age of the epoch.
     */
    val trusted: Boolean get() = nowMs() >= doubtedUntilMs

    fun reset() {
        doubtedUntilMs = 0L
        lastGps = null
        lastNetwork = null
        windowStartGps = null
        windowStartNetwork = null
    }

    /**
     * Record a fix and say what, if anything, it makes doubtful.
     *
     * @param accepted whether [FixFilter] took it. Rejected fixes still update the
     *   per-provider record (they are evidence about the receiver) but never the speed
     *   baseline, which must follow the positions actually shown to the rider.
     */
    fun observe(fix: Fix, accepted: Boolean): Doubt? = recordDoubt(assess(fix, accepted))

    /** Latches [trusted] whenever there is something to doubt. */
    private fun recordDoubt(doubt: Doubt?): Doubt? {
        if (doubt != null) doubtedUntilMs = nowMs() + TRUST_HOLD_MS
        return doubt
    }

    private fun assess(fix: Fix, accepted: Boolean): Doubt? {
        if (fix.isGps) lastGps = fix else lastNetwork = fix
        if (accepted) {
            // Never across providers: measuring from a coarse fix to a precise one yields the
            // handover distance, which read as 585 m/s in the first version of this file.
            val start = if (fix.isGps) windowStartGps else windowStartNetwork
            if (start == null || fix.atMs - start.atMs > SPEED_WINDOW_MS) {
                val doubt = start?.let { impliedSpeedDoubt(it, fix) }
                if (fix.isGps) windowStartGps = fix else windowStartNetwork = fix
                if (doubt != null) return doubt
            }
        }
        return disagreementDoubt()
    }

    private fun impliedSpeedDoubt(from: Fix, to: Fix): Doubt? {
        val spanMs = to.atMs - from.atMs
        if (spanMs < SPEED_MIN_BASELINE_MS) return null
        val implied = Geo.distanceM(from, to) / (spanMs / 1000.0)
        return if (implied > IMPLAUSIBLE_SPEED_MPS) {
            Doubt.ImplausibleSpeed(implied, spanMs)
        } else {
            null
        }
    }

    private fun disagreementDoubt(): Doubt? {
        val gps = lastGps ?: return null
        val net = lastNetwork ?: return null
        // Same moment, or the comparison is meaningless — a rider moves between fixes.
        if (kotlin.math.abs(gps.atMs - net.atMs) > FRESHNESS_MS) return null
        val gap = Geo.distanceM(gps, net)
        // An unknown accuracy widens the allowance rather than narrowing it. Read as 0f the
        // way Android hands it over, an accuracy-less cell fix a kilometre out would latch
        // this doubt for the whole ride and pin the GPS chip on. Review.
        val claimed = (gps.accuracyM ?: ASSUMED_ACCURACY_M) + (net.accuracyM ?: ASSUMED_ACCURACY_M)
        val allowed = claimed * DISAGREEMENT_FACTOR + DISAGREEMENT_FLOOR_M
        return if (gap > allowed) {
            Doubt.ProviderDisagreement(gap, gps.accuracyM, net.accuracyM)
        } else {
            null
        }
    }
}
