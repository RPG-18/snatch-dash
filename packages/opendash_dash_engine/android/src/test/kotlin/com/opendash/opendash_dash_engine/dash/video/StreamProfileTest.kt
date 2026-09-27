package com.opendash.opendash_dash_engine.dash.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The preset table, pinned.
 *
 * `FrameStreamerTest` already drives the loop end to end and proves the flip happens —
 * 2 fps idle, 4 fps moving, bitrates `[100_000, 200_000]` in that order. What it cannot
 * see is whether the numbers being flipped between are the DASH's numbers, because it
 * asserts the same constants the production code reads. So what is checked here is the
 * table against the outside world: the vendor's two presets, the encoder's configured
 * bitrate, and the two words that have been in ride files since before this type existed.
 */
class StreamProfileTest {

    @Test
    fun `motion picks the profile, both ways`() {
        assertEquals(StreamProfile.Moving, StreamProfile.forMotion(true))
        assertEquals(StreamProfile.Idle, StreamProfile.forMotion(false))
    }

    @Test
    fun `the presets are the dash's own, and the moving one is the expensive one`() {
        // Literals on purpose. A constant on both sides would assert nothing — these are
        // the vendor's q2g values as ported (204 800 → 200 000, 102 400 → 100 000), and
        // this test exists to notice them changing, not to restate them.
        assertEquals(4, StreamProfile.Moving.fps)
        assertEquals(200_000, StreamProfile.Moving.bitrateBps)
        assertEquals(2, StreamProfile.Idle.fps)
        assertEquals(100_000, StreamProfile.Idle.bitrateBps)

        assertTrue(
            StreamProfile.Moving.bitrateBps > StreamProfile.Idle.bitrateBps &&
                StreamProfile.Moving.fps > StreamProfile.Idle.fps,
            "swapping the two presets has to fail here and not in the field",
        )
    }

    @Test
    fun `frame intervals divide exactly, because three things round them separately`() {
        // The render deadline, the trailing pace and the RTP timestamp step all take this
        // number. A rate that does not divide 1000 gives each of them a different answer
        // once they start rounding, and the RTP timeline drifts from the send cadence.
        for (p in StreamProfile.entries) {
            assertEquals(1000L, p.frameIntervalMs * p.fps, "${p.name} does not divide 1000")
        }
        assertEquals(250L, StreamProfile.Moving.frameIntervalMs)
        assertEquals(500L, StreamProfile.Idle.frameIntervalMs)
    }

    @Test
    fun `asConfigured is the moving preset`() {
        // The rebuild path stands on this: after `encoderFactory(...)` the codec runs at
        // whatever `DashEncoder.configure` wrote into the format, and FrameStreamer
        // records that WITHOUT asking the codec. Disagreement is silent, because the
        // bitrate is only pushed on a change — nothing corrects it until the rider sets
        // off and stops again, and no counter in the ride file can see a parked bike
        // streaming at the moving rate.
        //
        // **That link is not machine-checked here, and cannot be**: `configure()` needs a
        // real MediaCodec. What this pins is the half that is testable — which preset
        // `asConfigured` names. The value behind it is pinned by the literals above, and
        // `configure()` reads the same `DashEncoder.BITRATE` those literals track, so a
        // change on either side surfaces there. Changing `configure()` to write something
        // that is NEITHER constant is the hole; it needs an instrumented test.
        assertEquals(StreamProfile.Moving, StreamProfile.asConfigured)
    }

    @Test
    fun `the ride-log words are stable and distinct`() {
        // инвариант 11: the ride-log format only ever gains lines, and `bitrate=moving` /
        // `bitrate=idle` are already grepped out of field captures.
        assertEquals("moving", StreamProfile.Moving.logName)
        assertEquals("idle", StreamProfile.Idle.logName)
        assertNotEquals(StreamProfile.Moving.logName, StreamProfile.Idle.logName)
    }
}
