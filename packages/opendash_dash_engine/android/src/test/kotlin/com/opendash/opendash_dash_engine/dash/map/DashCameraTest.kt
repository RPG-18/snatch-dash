package com.opendash.opendash_dash_engine.dash.map

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Padding is the only thing standing between the rider's expectations and the
 * camera: it decides where the arrow sits and which way the joystick moves the
 * map. Both are sign errors waiting to happen, and neither shows up as a crash —
 * just a map that scrolls the wrong way.
 */
class DashCameraTest {

    // The dash panel, 526 × 300.
    private val w = 526
    private val h = 300

    @Test
    fun `north-up centres the rider`() {
        assertContentEquals(
            intArrayOf(0, 0, 0, 0),
            DashCamera.padding(w, h, headingUp = false, panX = 0f, panY = 0f),
        )
    }

    @Test
    fun `heading-up pushes the target near the bottom`() {
        val padding = DashCamera.padding(w, h, headingUp = true, panX = 0f, panY = 0f)

        // Only the top is inset, and by twice the distance the target moves down
        // — 15% above the bottom edge at NAV_PIVOT_Y = 0.85.
        val expectedShift = h * DashCamera.NAV_PIVOT_Y - h / 2f
        assertContentEquals(intArrayOf(0, (2 * expectedShift).toInt(), 0, 0), padding)
        // Sanity on the direction: padding at the TOP moves the target DOWN,
        // which is what "the road ahead fills the frame" means.
        assertTrue(padding[1] > 0)
    }

    @Test
    fun `panning right moves the map left, as dragging it would`() {
        // Positive panX used to be added to the canvas origin, i.e. the map slid
        // left under a stationary target. Same here: the target moves left, so
        // the padding goes on the right.
        val padding = DashCamera.padding(w, h, headingUp = false, panX = 40f, panY = 0f)

        assertContentEquals(intArrayOf(0, 0, 80, 0), padding)
    }

    @Test
    fun `panning down and up pad opposite sides`() {
        val down = DashCamera.padding(w, h, headingUp = false, panX = 0f, panY = 40f)
        val up = DashCamera.padding(w, h, headingUp = false, panX = 0f, panY = -40f)

        assertContentEquals(intArrayOf(0, 0, 0, 80), down)
        assertContentEquals(intArrayOf(0, 80, 0, 0), up)
    }

    @Test
    fun `pan and pivot combine instead of cancelling`() {
        // Panning up in nav view pushes the target further down still — the two
        // effects are on the same axis and must not quietly overwrite each other.
        // (At the FULL pan bound the clamp takes over instead — separate test.)
        val pivotOnly = DashCamera.padding(w, h, headingUp = true, panX = 0f, panY = 0f)
        val both = DashCamera.padding(w, h, headingUp = true, panX = 0f, panY = -30f)

        assertTrue(both[1] > pivotOnly[1])
        assertTrue(both[3] == 0)
    }

    @Test
    fun `at the pan limit the target stays on the frame`() {
        // The pivot's inset and the pan's stack on the same axis: the core
        // moves the perspective center by half the inset, so padding at or
        // beyond the frame dimension would push the camera target (the rider)
        // off the frame entirely. Two pair contracts hold here. The first:
        // padding stays below the frame dimension — the width half is bounded
        // by MAX_PAN_FRACTION = 0.12w alone (there is no horizontal pivot), so
        // it is a tripwire for a future horizontal bias rather than a live
        // constraint. The second: the pivot sits at or below the clamp — a
        // pivot above MAX_TARGET_Y_FRACTION is silently overridden by it, a
        // tuning bug no sweep of pan values can see. (The historical "third of
        // the frame left to render" margin was built on a false viewport model
        // — padding does not crop the render, it only moves the target; see
        // the dedicated clamp test below.)
        assertTrue(
            DashCamera.NAV_PIVOT_Y <= DashCamera.MAX_TARGET_Y_FRACTION,
            "pivot ${DashCamera.NAV_PIVOT_Y} above the clamp ${DashCamera.MAX_TARGET_Y_FRACTION} — the pivot is silently overridden",
        )
        val maxPanX = w * DashCamera.MAX_PAN_FRACTION
        val maxPanY = h * DashCamera.MAX_PAN_FRACTION
        for (headingUp in listOf(false, true)) {
            for (px in listOf(-maxPanX, 0f, maxPanX)) {
                for (py in listOf(-maxPanY, 0f, maxPanY)) {
                    val padding = DashCamera.padding(w, h, headingUp, px, py)
                    assertTrue(padding.all { it >= 0 }, "negative padding at $px/$py")
                    assertTrue(padding[0] + padding[2] < w, "target off-frame in width at $px/$py")
                    assertTrue(padding[1] + padding[3] < h, "target off-frame in height at $px/$py")
                }
            }
        }
    }

    @Test
    fun `full pan-up in nav is clamped to keep the marker on the frame`() {
        // Pivot 0.85 + full pan-up would put the target at 0.97h; the clamp
        // caps it at MAX_TARGET_Y_FRACTION, insetting 2·(0.90h − h/2) = 0.80h
        // on top so the rider's 17 px halo stays on the 300 px frame.
        val maxPanY = h * DashCamera.MAX_PAN_FRACTION
        val padding = DashCamera.padding(w, h, headingUp = true, panX = 0f, panY = -maxPanY)

        val expectedTop = (2 * (h * DashCamera.MAX_TARGET_Y_FRACTION - h / 2f)).toInt()
        assertContentEquals(intArrayOf(0, expectedTop, 0, 0), padding)
    }
}
