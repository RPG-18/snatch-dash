package com.opendash.opendash_dash_engine.dash.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Clips ground segments to the snapshot's visible quadrilateral BEFORE perspective division.
 * Screen-space clipping cannot distinguish a segment from the false line joining its endpoints
 * across the camera plane. Mercator is used only for clipping; MapLibre still projects the result.
 * Coordinates are relative to the first corner to keep the half-plane arithmetic local.
 */
internal class RouteClipper {
    private val corners = DoubleArray(8)
    private val planes = DoubleArray(12)
    private var originLng = 0.0
    private var originY = 0.0

    var startFraction = 0.0
        private set
    var endFraction = 1.0
        private set

    fun setViewport(topLeft: GeoPoint, topRight: GeoPoint, bottomRight: GeoPoint, bottomLeft: GeoPoint) {
        originLng = topLeft.lng
        originY = mercatorY(topLeft.lat)
        corners[0] = 0.0
        corners[1] = 0.0
        corners[2] = x(topRight.lng, 0.0)
        corners[3] = y(topRight.lat)
        corners[4] = x(bottomRight.lng, corners[2])
        corners[5] = y(bottomRight.lat)
        corners[6] = x(bottomLeft.lng, corners[4])
        corners[7] = y(bottomLeft.lat)
        val area = corners[2] * corners[5] - corners[3] * corners[4] +
            corners[4] * corners[7] - corners[5] * corners[6]
        val orientation = if (area < 0.0) -1.0 else 1.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val nx = orientation * (corners[i * 2 + 1] - corners[j * 2 + 1])
            val ny = orientation * (corners[j * 2] - corners[i * 2])
            planes[i * 3] = nx
            planes[i * 3 + 1] = ny
            planes[i * 3 + 2] = -nx * corners[i * 2] - ny * corners[i * 2 + 1]
        }
    }

    /**
     * Unwrap around the preceding vertex, not independently around the viewport.
     * Use zero for the first vertex; otherwise a short segment at the opposite
     * meridian becomes a world-spanning chord through the visible quadrilateral.
     */
    fun x(lng: Double, referenceX: Double): Double {
        val delta = Math.toRadians(lng - originLng) - referenceX
        return referenceX + delta - 2.0 * PI * floor((delta + PI) / (2.0 * PI))
    }

    fun y(lat: Double): Double = mercatorY(lat) - originY

    fun longitude(x: Double): Double = originLng + Math.toDegrees(x)

    fun latitude(y: Double): Double = Math.toDegrees(2.0 * atan(exp(y + originY)) - PI / 2.0)

    /** Accepted fractions remain in source-segment order, including both-outside crossings. */
    fun clip(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) return false
        var start = 0.0
        var end = 1.0
        for (i in 0 until 4) {
            val nx = planes[i * 3]
            val ny = planes[i * 3 + 1]
            val distance = nx * ax + ny * ay + planes[i * 3 + 2]
            val change = nx * (bx - ax) + ny * (by - ay)
            if (change == 0.0) {
                if (distance < 0.0) return false
            } else {
                val crossing = -distance / change
                if (change > 0.0) start = maxOf(start, crossing) else end = minOf(end, crossing)
                if (start > end) return false
            }
        }
        startFraction = start
        endFraction = end
        return true
    }

    private fun mercatorY(lat: Double): Double = ln(tan(PI / 4.0 + Math.toRadians(lat) / 2.0))
}
