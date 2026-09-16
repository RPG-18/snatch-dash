package com.opendash.opendash_dash_engine.dash.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The camera the joystick drives, on the JVM.
 *
 * It had no tests until 2026-09-16 — it could not have any, because it read `SystemClock`
 * directly — and that is exactly how `setFollowMode(false)` spent its whole life as a silent
 * no-op: the flag was set and the very next frame put it back. Nothing in the app calls that
 * method yet, so nobody saw it either.
 */
class DashCameraStateTest {

    private var now = 10_000L
    private fun camera() = DashCameraState(frameWidth = 526, frameHeight = 300, clock = { now })

    // ── Follow mode and the manual window ────────────────────────────────

    @Test
    fun `turning follow off holds, and is not undone by the next frame`() {
        val cam = camera()
        cam.setFollowMode(false)

        assertFalse(cam.releasePanIfIdle(now), "the frame right after the press changes nothing")
        assertFalse(cam.followMode)
        now += DashCameraState.MANUAL_IDLE_MS
        assertFalse(cam.releasePanIfIdle(now), "and not on the boundary either")
        now += 1
        assertTrue(cam.releasePanIfIdle(now), "only once the window has really passed")
        assertTrue(cam.followMode)
    }

    @Test
    fun `a pan starts the same window`() {
        val cam = camera()
        cam.panBy(20f, 0f)

        assertFalse(cam.followMode)
        assertEquals(20f, cam.panX)
        now += DashCameraState.MANUAL_IDLE_MS + 1
        assertTrue(cam.releasePanIfIdle(now))
        // Follow means following: the pan goes with it, or the map would keep the rider's
        // offset while claiming to be centred on them.
        assertEquals(0f, cam.panX)
        assertEquals(0f, cam.panY)
    }

    @Test
    fun `follow on clears the pan immediately`() {
        val cam = camera()
        cam.panBy(20f, 30f)
        cam.setFollowMode(true)

        assertEquals(0f, cam.panX)
        assertEquals(0f, cam.panY)
        assertFalse(cam.releasePanIfIdle(now + DashCameraState.MANUAL_IDLE_MS + 1), "nothing left to release")
    }

    // ── Bounds ───────────────────────────────────────────────────────────

    @Test
    fun `pan saturates at the bound instead of running away`() {
        val cam = camera()
        val max = 526 * DashCamera.MAX_PAN_FRACTION
        repeat(50) { cam.panBy(100f, 100f) }

        assertEquals(max, cam.panX)
        assertEquals(300 * DashCamera.MAX_PAN_FRACTION, cam.panY)
    }

    @Test
    fun `zoom stops at both ends of the ladder`() {
        val cam = camera()
        assertEquals(DashCameraState.ZOOM_DEFAULT, cam.zoom)
        // The default IS the ceiling, so zooming in is inert until the rider zooms out —
        // by construction, not by fault. See ZOOM_DEFAULT.
        cam.zoomIn()
        assertEquals(DashCameraState.ZOOM_MAX, cam.zoom)

        repeat(50) { cam.zoomOut() }
        assertEquals(DashCameraState.ZOOM_MIN, cam.zoom, "the floor holds")
    }

    @Test
    fun `packs raise the floor and pull the current zoom up with it`() {
        val cam = camera()
        repeat(50) { cam.zoomOut() }
        assertEquals(DashCameraState.ZOOM_MIN, cam.zoom)

        // A rider still holding z11-14 packs: below 11.00 MapLibre has no tile to draw and
        // the dash goes blank rather than coarse.
        assertEquals(1100, cam.applyPackFloor(11))
        assertEquals(1100, cam.zoom, "a zoom below the new floor is lifted, not left to render nothing")
        cam.zoomOut()
        assertEquals(1100, cam.zoom, "and the floor holds afterwards")
    }

    @Test
    fun `a corrupt pack header cannot take the zoom buttons out`() {
        val cam = camera()
        // packMinZoom comes out of a PMTiles header, so its range is 0..255, not "sensible".
        // A floor above the ceiling would make the next coerceIn throw and kill both buttons
        // for the whole ride.
        assertEquals(DashCameraState.ZOOM_MAX, cam.applyPackFloor(20))
        cam.zoomOut()
        assertEquals(DashCameraState.ZOOM_MAX, cam.zoom)
        cam.zoomIn()
        assertEquals(DashCameraState.ZOOM_MAX, cam.zoom)
    }

    @Test
    fun `a stream forgets where the camera was pointing but not where it is`() {
        val cam = camera()
        cam.initialiseAt(60.0, 30.0, 90f)
        assertTrue(cam.initialised)

        cam.beginStream()

        assertFalse(cam.initialised, "the next tick snaps to the rider instead of smoothing")
        assertEquals(60.0, cam.lat, "but the centre survives: a reconnect must not swing the map")
        assertEquals(30.0, cam.lng)
    }
}
