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
    fun `heading-up pushes the target into the lower third`() {
        val padding = DashCamera.padding(w, h, headingUp = true, panX = 0f, panY = 0f)

        // Only the top is inset, and by twice the distance the target moves down.
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
        val pivotOnly = DashCamera.padding(w, h, headingUp = true, panX = 0f, panY = 0f)
        val both = DashCamera.padding(w, h, headingUp = true, panX = 0f, panY = -30f)

        assertTrue(both[1] > pivotOnly[1])
        assertTrue(both[3] == 0)
    }

    @Test
    fun `at the pan limit there is still a frame to render into`() {
        // MapLibre renders into what padding leaves over, and the pivot's inset
        // and the pan's stack on the same axis. This is the pair — the bound on
        // pan and the pivot fraction — that has to stay compatible; changing
        // either without the other silently squeezes the map into a strip.
        val maxPanX = w * DashCamera.MAX_PAN_FRACTION
        val maxPanY = h * DashCamera.MAX_PAN_FRACTION
        for (headingUp in listOf(false, true)) {
            for (px in listOf(-maxPanX, 0f, maxPanX)) {
                for (py in listOf(-maxPanY, 0f, maxPanY)) {
                    val padding = DashCamera.padding(w, h, headingUp, px, py)
                    assertTrue(padding.all { it >= 0 }, "negative padding at $px/$py")
                    assertTrue(padding[0] + padding[2] < w * 2 / 3, "too little width at $px/$py")
                    assertTrue(padding[1] + padding[3] < h * 2 / 3, "too little height at $px/$py")
                }
            }
        }
    }
}
