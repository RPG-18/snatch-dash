package com.opendash.opendash_dash_engine.dash.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2026-09-13 ride, as a test.
 *
 * That ride is the reason this logic exists, so the cases below are its actual numbers rather
 * than invented ones: a NETWORK fix claiming 816 m at 60.11682,31.38482, GPS claiming 10-20 m
 * at 60.4248..60.4316 / 31.405..31.430, and a rider who was at 60.049125,30.430826 the whole
 * time. Both sources were wrong — 53.4 km and 69.6 km out — under GNSS jamming.
 *
 * Two separate failures are pinned here, and only the first is fixable:
 *   - the filter rejected 53 real GPS fixes in a minute because a coarse fix got there first
 *     and every guard then measured against it;
 *   - no software can recover a true position from a spoofed constellation, but the two
 *     sources contradicted each other by 35.1 km while both claimed sub-kilometre accuracy,
 *     and that is detectable without knowing the truth.
 */
class PositionQualityTest {

    private companion object {
        /** The coarse NETWORK fix that anchored the real ride, 53.4 km from the rider. */
        const val NET_LAT = 60.11682
        const val NET_LON = 31.38482

        /** Where GPS said the bike was — 35.1 km further on, in Lake Ladoga. */
        const val GPS_LAT = 60.4315
        const val GPS_LON = 31.4301

        const val T0 = 1_757_000_000_000L
    }

    private fun gps(
        lat: Double = GPS_LAT,
        lon: Double = GPS_LON,
        acc: Float = 15f,
        atMs: Long = T0,
        speed: Float = 14f,
    ) = Fix(lat, lon, acc, atMs, speed, isGps = true)

    /**
     * A [PositionTrust] on a clock that does not move.
     *
     * The clock is a required constructor parameter — see PositionTrust's own doc for why it
     * has no default. Frozen is right for every test below that does not exercise the latch:
     * those reason about fix timestamps, which are their own argument, and a frozen `nowMs`
     * keeps the verdict from expiring underneath an assertion. The latch tests build their
     * own movable clock instead.
     */
    private fun trust() = PositionTrust(nowMs = { T0 })

    private fun network(
        lat: Double = NET_LAT,
        lon: Double = NET_LON,
        acc: Float = 816f,
        atMs: Long = T0,
        speed: Float = 0f,
    ) = Fix(lat, lon, acc, atMs, speed, isGps = false)

    // ── Geo ───────────────────────────────────────────────────────────────

    @Test
    fun `distance matches the field numbers to within a tenth of a kilometre`() {
        val rider = gps(lat = 60.049125, lon = 30.430826)

        // Computed independently from the ride log's coordinates; the point of stating them
        // here is that a change to the haversine cannot silently move the thresholds.
        assertEquals(53.4, Geo.distanceM(rider, network()) / 1000.0, 0.1)
        assertEquals(69.6, Geo.distanceM(rider, gps()) / 1000.0, 0.1)
        assertEquals(35.1, Geo.distanceM(network(), gps()) / 1000.0, 0.1)
    }

    @Test
    fun `distance is symmetric and zero for a point against itself`() {
        assertEquals(0.0, Geo.distanceM(gps(), gps()), 0.001)
        assertEquals(
            Geo.distanceM(network(), gps()),
            Geo.distanceM(gps(), network()),
            0.001,
        )
    }

    @Test
    fun `a degree of latitude is about 111 km`() {
        // Sanity anchor for the trig: if lat and lon were ever swapped, this catches it while
        // the field-number test above could still pass by coincidence.
        val a = gps(lat = 60.0, lon = 30.0)
        val b = gps(lat = 61.0, lon = 30.0)
        assertEquals(111.2, Geo.distanceM(a, b) / 1000.0, 0.5)

        // And a degree of longitude at 60°N is about half that — cos(60°) = 0.5.
        val c = gps(lat = 60.0, lon = 31.0)
        assertEquals(55.6, Geo.distanceM(a, c) / 1000.0, 0.5)
    }

    // ── FixFilter: the regression that started this ────────────────────────

    @Test
    fun `a real GPS fix supersedes a coarse network anchor however far it is`() {
        // THE 2026-09-13 BUG. Held fix: network, 816 m claimed, 35 km from the GPS fix that
        // follows 7.6 s later. The old teleport guard saw 35000 m / 7.6 s = 4600 m/s and
        // rejected it, then rejected every one after it for 100 seconds.
        val filter = FixFilter()
        assertTrue(filter.accept(null, network()), "the first fix of a session is all there is")

        val accepted = filter.accept(network(), gps(atMs = T0 + 7_581))

        assertTrue(accepted, "a provider upgrade is not a teleport and must not be judged as one")
    }

    @Test
    fun `the upgrade does not need a reject streak first — it fires on the first GPS fix`() {
        // The distinction matters: a streak-based escape hatch would still have thrown away
        // the first three real fixes, and at 1 Hz that is three seconds of the rider's
        // position being 35 km wrong.
        val filter = FixFilter()
        filter.accept(null, network())

        assertTrue(filter.accept(network(), gps(atMs = T0 + 1_000)))
    }

    @Test
    fun `a coarse fix still cannot displace a fresh GPS one`() {
        // The rule that existed before and must survive: GPS goes quiet for a moment while
        // parked, and letting NETWORK in there makes the marker wander.
        val filter = FixFilter()
        val held = gps()

        assertFalse(filter.accept(held, network(atMs = T0 + 2_000)))
    }

    @Test
    fun `a coarse fix is allowed in once GPS has gone quiet long enough`() {
        val filter = FixFilter()

        assertTrue(filter.accept(gps(), network(lat = GPS_LAT, lon = GPS_LON, atMs = T0 + 11_000)))
    }

    @Test
    fun `a teleport between two GPS fixes is still rejected`() {
        // The guard has to keep working where it was right: same provider, no upgrade excuse.
        val filter = FixFilter()

        assertFalse(
            filter.accept(gps(lat = 60.0, lon = 30.0), gps(lat = 60.5, lon = 30.0, atMs = T0 + 2_000)),
            "55 km in two seconds is not a position",
        )
    }

    @Test
    fun `rejections that persist for ten seconds re-anchor instead of going on forever`() {
        // The other half of the 2026-09-13 fix. Only the second guard had a streak escape
        // hatch, so a wrong anchor could reject through the teleport guard indefinitely — and
        // that is the guard that did all the damage.
        val filter = FixFilter()
        val anchor = gps(lat = 60.0, lon = 30.0, speed = 0f)
        for (atMs in listOf(T0 + 2_000, T0 + 4_000, T0 + 6_000)) {
            assertFalse(filter.accept(anchor, gps(lat = 60.5, lon = 30.0, atMs = atMs)), "at $atMs")
        }

        assertTrue(
            filter.accept(anchor, gps(lat = 60.5, lon = 30.0, atMs = T0 + 12_000)),
            "ten seconds of impossible jumps means the anchor is the suspect, not the stream",
        )
    }

    @Test
    fun `a short burst of impossible fixes does NOT re-anchor`() {
        // Found by review, and it is the more dangerous half. With the hatch on count alone,
        // every fourth fix was accepted however impossible — so a few seconds of multipath
        // would move the map onto a bogus position and then start rejecting the real stream,
        // oscillating between the two. Worse than the lockout the hatch exists to break.
        val filter = FixFilter()
        val anchor = gps(lat = 60.0, lon = 30.0, speed = 0f)

        for (step in 1..8) {
            assertFalse(
                filter.accept(anchor, gps(lat = 60.5, lon = 30.0, atMs = T0 + 500L * step)),
                "fix #$step of a four-second burst was accepted",
            )
        }
    }

    @Test
    fun `reset clears the streak so a new ride does not inherit one`() {
        val filter = FixFilter()
        val anchor = gps(lat = 60.0, lon = 30.0, speed = 0f)
        for (atMs in listOf(T0 + 2_000, T0 + 4_000, T0 + 6_000)) {
            filter.accept(anchor, gps(lat = 60.5, lon = 30.0, atMs = atMs))
        }

        filter.reset()

        assertFalse(
            filter.accept(anchor, gps(lat = 60.5, lon = 30.0, atMs = T0 + 12_000)),
            "the streak's clock restarts too, so the hatch must not fire on the next fix",
        )
    }

    // ── Unknown accuracy ──────────────────────────────────────────────────

    @Test
    fun `an accuracy-less fix widens the gates instead of reading as perfect`() {
        // Android returns 0 from getAccuracy() when hasAccuracy() is false, so a naive read
        // makes an accuracy-less cell fix look millimetre-perfect. That collapsed the
        // disagreement allowance to its floor and latched a doubt for the whole ride.
        val trust = trust()
        trust.observe(gps(lat = 60.0, lon = 30.0, acc = 15f), accepted = true)

        val doubt = trust.observe(
            Fix(60.009, 30.0, accuracyM = null, atMs = T0 + 1_000, speedMps = 0f, isGps = false),
            accepted = true,
        )

        assertNull(doubt, "a fix that claimed nothing cannot contradict one that did")
    }

    @Test
    fun `an unknown accuracy still cannot excuse a 35 km contradiction`() {
        val trust = trust()
        trust.observe(gps(acc = 15f), accepted = true)

        val doubt = trust.observe(
            Fix(NET_LAT, NET_LON, accuracyM = null, atMs = T0 + 1_000, speedMps = 0f, isGps = false),
            accepted = true,
        )

        val d = assertIs<PositionTrust.Doubt.ProviderDisagreement>(doubt)
        assertNull(d.networkAccuracyM, "the line must say \"unknown\", not invent a number")
    }

    @Test
    fun `a fix with no accuracy is judged on geometry alone, not rejected outright`() {
        val filter = FixFilter()
        val held = Fix(60.0, 30.0, accuracyM = null, atMs = T0, speedMps = 0f, isGps = true)

        assertTrue(
            filter.accept(held, gps(lat = 60.0001, lon = 30.0, acc = 10f, atMs = T0 + 1_000)),
            "an 11 m step is ordinary whatever either fix claimed about itself",
        )
    }

    @Test
    fun `a stale anchor loses to a fresh fix whatever the geometry says`() {
        // A getLastKnownLocation seed from the other end of the city used to be able to reject
        // the live stream on arrival, because it became the anchor with no age check at all.
        val filter = FixFilter()
        val stale = gps(lat = 60.0, lon = 30.0, atMs = T0)

        assertTrue(filter.accept(stale, gps(lat = 60.5, lon = 30.5, atMs = T0 + 60_000)))
    }

    @Test
    fun `a fix older than the one held is refused`() {
        val filter = FixFilter()

        assertFalse(filter.accept(gps(atMs = T0 + 5_000), gps(atMs = T0)))
    }

    @Test
    fun `ordinary riding is accepted`() {
        // 14 m/s for one second, 10 m of accuracy either side. Nothing here may trip a guard,
        // or the whole ride runs on a frozen marker.
        val filter = FixFilter()
        var held = gps(lat = 60.0, lon = 30.0, acc = 10f, atMs = T0, speed = 14f)
        for (step in 1..60) {
            val next = gps(
                lat = 60.0 + 0.000126 * step,   // ~14 m per step
                lon = 30.0,
                acc = 10f,
                atMs = T0 + 1_000L * step,
                speed = 14f,
            )
            assertTrue(filter.accept(held, next), "step $step of an ordinary ride was rejected")
            held = next
        }
    }

    // ── PositionTrust: provider disagreement ──────────────────────────────

    @Test
    fun `the two sources of the jammed ride are reported as contradictory`() {
        val trust = trust()

        assertNull(trust.observe(network(), accepted = true), "one source alone proves nothing")
        val doubt = trust.observe(gps(atMs = T0 + 1_000), accepted = true)

        val d = assertIs<PositionTrust.Doubt.ProviderDisagreement>(doubt)
        assertEquals(35.1, d.gapM / 1000.0, 0.1)
        assertEquals(15f, d.gpsAccuracyM)
        assertEquals(816f, d.networkAccuracyM)
    }

    @Test
    fun `an ordinary disagreement between a tower fix and GPS is not flagged`() {
        // The threshold exists to catch the impossible, not the imprecise. A cell fix claiming
        // 816 m that lands 800 m away is doing its job.
        val trust = trust()
        trust.observe(gps(lat = 60.0, lon = 30.0, acc = 15f), accepted = true)

        val doubt = trust.observe(
            network(lat = 60.0072, lon = 30.0, acc = 816f, atMs = T0 + 1_000),
            accepted = true,
        )

        assertNull(doubt, "800 m against a combined claim of 831 m is consistent")
    }

    @Test
    fun `disagreement needs both fixes to describe the same moment`() {
        // A rider moves. Comparing a GPS fix to a tower fix from a minute ago would report
        // every ride on a motorway as spoofed.
        //
        // The two fixes here are 35 km and 60 s apart, so this also pins that the SPEED check
        // stays out of it: measuring a baseline across the provider handover implied 585 m/s
        // and reported an ImplausibleSpeed where the honest answer is "no opinion".
        val trust = trust()
        trust.observe(network(), accepted = true)

        assertNull(trust.observe(gps(atMs = T0 + 60_000), accepted = true))
    }

    @Test
    fun `a provider handover is never reported as a speed`() {
        val trust = trust()
        trust.observe(network(lat = 60.0, lon = 30.0, atMs = T0), accepted = true)

        // 35 km away and 31 s later — implausible as motion, meaningless as a measurement.
        val doubt = trust.observe(gps(lat = 60.3, lon = 30.2, atMs = T0 + 31_000), accepted = true)

        assertTrue(
            doubt == null || doubt is PositionTrust.Doubt.ProviderDisagreement,
            "a handover may raise disagreement, never a speed: got $doubt",
        )
    }

    @Test
    fun `a rejected fix still counts as evidence about the receiver`() {
        // The filter throwing a fix away does not make it uninformative — under jamming the
        // rejected ones are most of what there is.
        val trust = trust()
        trust.observe(network(), accepted = true)

        val doubt = trust.observe(gps(atMs = T0 + 1_000), accepted = false)

        assertIs<PositionTrust.Doubt.ProviderDisagreement>(doubt)
    }

    @Test
    fun `agreement after a disagreement clears the doubt`() {
        val trust = trust()
        trust.observe(network(), accepted = true)
        assertIs<PositionTrust.Doubt.ProviderDisagreement>(
            trust.observe(gps(atMs = T0 + 1_000), accepted = true),
        )

        // The tower fix catches up to where GPS says we are.
        val agreed = trust.observe(
            network(lat = GPS_LAT, lon = GPS_LON, atMs = T0 + 2_000),
            accepted = true,
        )

        assertNull(agreed, "the verdict must be able to recover, not latch")
    }

    // ── PositionTrust: implausible speed ──────────────────────────────────

    @Test
    fun `a jump implying 200 km per hour over half a minute is flagged`() {
        // The ride log's own numbers: 1726 m of camera movement in a 30 s window is 207 km/h.
        // Those figures were already in the file and nothing was checking them.
        val trust = trust()
        trust.observe(gps(lat = 60.0, lon = 30.0, atMs = T0), accepted = true)

        val doubt = trust.observe(gps(lat = 60.0, lon = 30.0 + 0.031, atMs = T0 + 30_001), accepted = true)

        val d = assertIs<PositionTrust.Doubt.ImplausibleSpeed>(doubt)
        assertTrue(d.impliedMps * 3.6 > 180, "expected >180 km/h, got ${d.impliedMps * 3.6}")
        assertEquals(30_001L, d.overMs)
    }

    @Test
    fun `legal motorway speed over the same window is not flagged`() {
        // 110 km/h sustained for 30 s. The gate has to sit above anything a bike does legally,
        // or it cries wolf on every trunk road.
        val trust = trust()
        trust.observe(gps(lat = 60.0, lon = 30.0, atMs = T0), accepted = true)

        val doubt = trust.observe(gps(lat = 60.00825, lon = 30.0, atMs = T0 + 30_001), accepted = true)

        assertNull(doubt, "about 110 km/h must pass")
    }

    @Test
    fun `a short baseline is not used to imply a speed`() {
        // Two fixes a second apart with 20 m of noise each imply 144 km/h and mean nothing.
        val trust = trust()
        trust.observe(gps(lat = 60.0, lon = 30.0, atMs = T0), accepted = true)

        assertNull(trust.observe(gps(lat = 60.00036, lon = 30.0, atMs = T0 + 1_000), accepted = true))
    }

    @Test
    fun `rejected fixes do not move the speed baseline`() {
        // The baseline must follow the positions actually shown to the rider; a rejected
        // teleport feeding it would manufacture an implausible speed out of a fix we refused.
        val trust = trust()
        trust.observe(gps(lat = 60.0, lon = 30.0, atMs = T0), accepted = true)

        trust.observe(gps(lat = 61.0, lon = 30.0, atMs = T0 + 10_000), accepted = false)
        val doubt = trust.observe(gps(lat = 60.0, lon = 30.0, atMs = T0 + 31_000), accepted = true)

        assertNull(doubt, "a stationary rider must not inherit a rejected fix's distance")
    }

    @Test
    fun `reset drops both the provider record and the speed baseline`() {
        val trust = trust()
        trust.observe(network(), accepted = true)

        trust.reset()

        assertNull(
            trust.observe(gps(atMs = T0 + 1_000), accepted = true),
            "after a reset there is only one source again",
        )
    }

    // ── The trust latch ───────────────────────────────────────────────────

    @Test
    fun `a doubt keeps the position untrusted long after the objecting fix`() {
        // THE REVIEW'S FINDING, and the one that decided whether any of this reaches the rider.
        // ImplausibleSpeed can only fire once per 30 s window, so an unlatched verdict showed
        // weak for one second in thirty and green for the other twenty-nine.
        var clock = T0
        val trust = PositionTrust(nowMs = { clock })
        trust.observe(gps(lat = 60.0, lon = 30.0, atMs = T0), accepted = true)
        assertTrue(trust.trusted, "nothing is wrong yet")

        assertIs<PositionTrust.Doubt.ImplausibleSpeed>(
            trust.observe(gps(lat = 60.0, lon = 30.031, atMs = T0 + 30_001), accepted = true),
        )

        assertFalse(trust.trusted, "doubted now")
        clock = T0 + 30_000
        assertFalse(trust.trusted, "and still doubted thirty seconds later, with no new objection")
    }

    @Test
    fun `trust returns once the hold expires`() {
        var clock = T0
        val trust = PositionTrust(nowMs = { clock })
        trust.observe(gps(lat = 60.0, lon = 30.0, atMs = T0), accepted = true)
        trust.observe(gps(lat = 60.0, lon = 30.031, atMs = T0 + 30_001), accepted = true)
        assertFalse(trust.trusted)

        clock = T0 + PositionTrust.TRUST_HOLD_MS

        assertTrue(trust.trusted, "the latch is a hold, not a permanent verdict")
    }

    @Test
    fun `each new doubt extends the hold rather than restarting the clock from zero`() {
        var clock = T0
        val trust = PositionTrust(nowMs = { clock })
        trust.observe(network(), accepted = true)
        trust.observe(gps(atMs = T0 + 1_000), accepted = true)   // disagreement

        clock = T0 + 30_000
        trust.observe(gps(atMs = T0 + 30_000), accepted = true)  // still disagreeing
        clock = T0 + PositionTrust.TRUST_HOLD_MS + 1_000

        assertFalse(trust.trusted, "the second doubt must have pushed the hold out")
    }

    @Test
    fun `reset restores trust`() {
        var clock = T0
        val trust = PositionTrust(nowMs = { clock })
        trust.observe(network(), accepted = true)
        trust.observe(gps(atMs = T0 + 1_000), accepted = true)
        assertFalse(trust.trusted)

        trust.reset()

        assertTrue(trust.trusted, "a new ride starts without inheriting the last one's verdict")
    }

    @Test
    fun `the hold outlasts the speed window it has to cover`() {
        // The two constants are related, not independent: a hold shorter than the window
        // leaves a gap of misplaced confidence between consecutive checks.
        assertTrue(
            PositionTrust.TRUST_HOLD_MS > PositionTrust.SPEED_WINDOW_MS,
            "${PositionTrust.TRUST_HOLD_MS} must exceed ${PositionTrust.SPEED_WINDOW_MS}",
        )
    }

    @Test
    fun `the thresholds are the documented ones`() {
        // These numbers are quoted in PositionQuality's own doc comment, in
        // network-refactoring.md §0.1 and in CLAUDE.md; a silent change makes all three wrong.
        assertEquals(4.0, PositionTrust.DISAGREEMENT_FACTOR)
        assertEquals(500.0, PositionTrust.DISAGREEMENT_FLOOR_M)
        assertEquals(40.0, PositionTrust.IMPLAUSIBLE_SPEED_MPS)
        assertTrue(abs(PositionTrust.IMPLAUSIBLE_SPEED_MPS * 3.6 - 144.0) < 0.01, "144 km/h")
    }
}
