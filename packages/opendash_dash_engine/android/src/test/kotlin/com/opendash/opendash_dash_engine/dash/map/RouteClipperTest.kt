package com.opendash.opendash_dash_engine.dash.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteClipperTest {
    private val clipper = RouteClipper()
    private val pitch = Math.toRadians(48.89616740845569)
    private val worldPerRadian = 512.0 * (1 shl 15) / (2.0 * PI)
    private val centerY = ln(tan(PI / 4.0 + Math.toRadians(60.0) / 2.0))
    private var bearing = 0.0

    @Test
    fun `camera-plane crossing behind rider does not draw a line through top of frame`() {
        viewport()
        val a = ground(100.0, -560.0)
        val b = ground(100.0, -620.0)
        val screenA = screen(a)
        val screenB = screen(b)
        // The old screen-space segment crosses (311, 50), although its ground
        // segment is wholly behind the rider. All screen coordinates are finite.
        val t = (50.0 - screenA.second) / (screenB.second - screenA.second)
        val falseX = screenA.first + (screenB.first - screenA.first) * t
        assertTrue(t in 0.0..1.0 && falseX in 0.0..526.0)
        assertFalse(clip(a, b))
    }

    @Test
    fun `both endpoints outside still preserve the visible crossing and stroke margin`() {
        viewport()
        assertTrue(clip(fromScreen(-100.0, 130.0), fromScreen(626.0, 130.0)))
        assertEndpoint(-6.5, 130.0, fromScreen(-100.0, 130.0), fromScreen(626.0, 130.0), clipper.startFraction)
        assertEndpoint(532.5, 130.0, fromScreen(-100.0, 130.0), fromScreen(626.0, 130.0), clipper.endFraction)
    }

    @Test
    fun `return from behind camera starts at bottom boundary not at the mirrored endpoint`() {
        viewport()
        val a = ground(100.0, -620.0)
        val b = ground(100.0, 200.0)
        assertTrue(clip(a, b))
        val start = endpoint(a, b, clipper.startFraction)
        assertEquals(306.5, screen(start).second, 0.00001)
        assertEquals(1.0, clipper.endFraction)
        assertTrue(clipper.startFraction > 0.0)
    }

    @Test
    fun `rotated perspective clips against quadrilateral edges not a latitude longitude bbox`() {
        bearing = Math.toRadians(37.0)
        viewport()
        assertFalse(clip(fromScreen(-100.0, 100.0), fromScreen(-20.0, 200.0)))
        val a = fromScreen(200.0, -100.0)
        val b = fromScreen(200.0, 400.0)
        assertTrue(clip(a, b))
        assertEndpoint(200.0, -6.5, a, b, clipper.startFraction)
        assertEndpoint(200.0, 306.5, a, b, clipper.endFraction)
    }

    @Test
    fun `viewport crossing antimeridian keeps local segments continuous`() {
        clipper.setViewport(
            GeoPoint(60.01, 179.99), GeoPoint(60.01, -179.99),
            GeoPoint(59.99, -179.99), GeoPoint(59.99, 179.99),
        )
        val a = GeoPoint(60.0, 179.98)
        val b = GeoPoint(60.0, -179.98)
        assertTrue(clip(a, b))
        assertEquals(0.25, clipper.startFraction, 0.000001)
        assertEquals(0.75, clipper.endFraction, 0.000001)
    }

    @Test
    fun `segment crossing the opposite meridian does not become a visible world-spanning chord`() {
        clipper.setViewport(
            GeoPoint(0.01, -0.01), GeoPoint(0.01, 0.01),
            GeoPoint(-0.01, 0.01), GeoPoint(-0.01, -0.01),
        )
        val a = GeoPoint(0.0, 179.98)
        val b = GeoPoint(0.0, -180.0)
        assertFalse(clip(a, b))
        assertFalse(clip(b, a))
    }

    @Test
    fun `fully visible and boundary-parallel segments retain their endpoints`() {
        viewport()
        val a = fromScreen(100.0, 100.0)
        val b = fromScreen(400.0, 100.0)
        assertTrue(clip(a, b))
        assertEquals(0.0, clipper.startFraction)
        assertEquals(1.0, clipper.endFraction)
        assertFalse(clip(fromScreen(100.0, 400.0), fromScreen(400.0, 400.0)))
    }

    private fun viewport() {
        clipper.setViewport(
            fromScreen(-6.5, -6.5), fromScreen(532.5, -6.5),
            fromScreen(532.5, 306.5), fromScreen(-6.5, 306.5),
        )
    }

    private fun clip(a: GeoPoint, b: GeoPoint): Boolean {
        val ax = clipper.x(a.lng, 0.0)
        return clipper.clip(ax, clipper.y(a.lat), clipper.x(b.lng, ax), clipper.y(b.lat))
    }

    private fun ground(right: Double, forward: Double): GeoPoint {
        val east = right * cos(bearing) + forward * sin(bearing)
        val north = -right * sin(bearing) + forward * cos(bearing)
        return GeoPoint(
            Math.toDegrees(2.0 * atan(exp(centerY + north / worldPerRadian)) - PI / 2.0),
            30.0 + Math.toDegrees(east / worldPerRadian),
        )
    }

    private fun fromScreen(x: Double, y: Double): GeoPoint {
        val forward = (255.0 - y) * 450.0 / (450.0 * cos(pitch) - (255.0 - y) * sin(pitch))
        val right = (x - 263.0) * (450.0 + forward * sin(pitch)) / 450.0
        return ground(right, forward)
    }

    private fun screen(point: GeoPoint): Pair<Double, Double> {
        val east = Math.toRadians(point.lng - 30.0) * worldPerRadian
        val north = (ln(tan(PI / 4.0 + Math.toRadians(point.lat) / 2.0)) - centerY) * worldPerRadian
        val right = east * cos(bearing) - north * sin(bearing)
        val forward = east * sin(bearing) + north * cos(bearing)
        val depth = 450.0 + forward * sin(pitch)
        return (263.0 + 450.0 * right / depth) to (255.0 - 450.0 * cos(pitch) * forward / depth)
    }

    private fun endpoint(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        val ax = clipper.x(a.lng, 0.0)
        val bx = clipper.x(b.lng, ax)
        return GeoPoint(
            clipper.latitude(clipper.y(a.lat) + (clipper.y(b.lat) - clipper.y(a.lat)) * fraction),
            clipper.longitude(ax + (bx - ax) * fraction),
        )
    }

    private fun assertEndpoint(x: Double, y: Double, a: GeoPoint, b: GeoPoint, fraction: Double) {
        val actual = screen(endpoint(a, b, fraction))
        assertEquals(x, actual.first, 0.00001)
        assertEquals(y, actual.second, 0.00001)
    }
}
