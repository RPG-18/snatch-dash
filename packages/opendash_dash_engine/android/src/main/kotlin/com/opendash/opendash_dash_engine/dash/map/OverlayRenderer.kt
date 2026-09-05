package com.opendash.opendash_dash_engine.dash.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.cos

/**
 * Where a coordinate lands on the frame.
 *
 * Backed by the very `MapSnapshot` the overlays are drawn onto, so there is
 * exactly one projection in the pipeline. The old renderer kept its own Mercator
 * arithmetic alongside the tile layout; two sources of projection stay in step
 * only until the first disagreement about a camera parameter, and then the route
 * slides off the roads.
 */
fun interface MapProjection {
    fun project(lat: Double, lng: Double): PointF
}

/**
 * Draws everything the dash frame (526 × 300) carries **on top of** the map:
 * the route with its traffic colours, the destination pin, the rider arrow, and
 * the ETA/GPS pills.
 *
 * The map itself is a MapLibre snapshot already drawn into the frame before this
 * runs (spec/drawing_from_local_tiles.md, «Конвейер кадра»). That is why nothing
 * here decides *where* anything goes any more: no tile loop, no Mercator, no
 * `canvas.rotate`, no perspective warp. Heading-up rotation and tilt are the
 * camera's job — rotating a finished raster would rotate the labels with it,
 * which MapLibre keeps upright itself.
 *
 * Paint/Path objects are reused across frames to avoid per-frame allocation
 * churn; the projected route shares one growable FloatArray for the same reason.
 */
class OverlayRenderer {

    data class Frame(
        /**
         * Whether the map turns with the rider. Kept even though the rotation
         * itself moved to the camera: the rider arrow is drawn in screen space
         * and has to point differently in the two modes — straight up when the
         * map is already turned, at the true bearing when it is north-up.
         */
        val headingUp: Boolean = false,
        val heading: Float = 0f,           // travel bearing, degrees
        val riderLat: Double? = null,
        val riderLng: Double? = null,
        val destLat: Double? = null,
        val destLng: Double? = null,
        val route: List<GeoPoint> = emptyList(),
        // Traffic-level code per route segment (index i = route[i]..route[i+1]),
        // 0=unknown..5=veryHard — see jamColors below. Empty/mismatched length
        // (no traffic data for this route) falls back to a solid [routeBlue] line.
        val routeJam: List<Int> = emptyList(),
        val gpsWeak: Boolean = false,
        val gpsLost: Boolean = false,
    )

    /**
     * Which of the two styles is under the overlays. Only the standby text
     * needs it — everything else is drawn in colours chosen to read against
     * both. Set once per stream, alongside the style itself.
     */
    var darkMap: Boolean = false

    private val routeBlue = Color.rgb(66, 133, 244)  // Google Maps directions blue (#4285F4) — fallback when no jam data
    private val googleRed = Color.rgb(234, 67, 53)   // Google destination pin red (#EA4335)

    // Traffic palette, indexed by the jam-level codes in [Frame.routeJam] — mirrors
    // the app-side `JamLevel`/`jamColors` table 1:1, see spec/yande_ruote.md.
    private val jamColors = intArrayOf(
        Color.rgb(0x90, 0x90, 0x90), // 0 unknown
        Color.rgb(0x00, 0x00, 0x00), // 1 blocked
        Color.rgb(0x00, 0xFF, 0x00), // 2 free
        Color.rgb(0xFF, 0xFF, 0x00), // 3 light
        Color.rgb(0xFF, 0x00, 0x00), // 4 hard
        Color.rgb(0xA0, 0x00, 0x00), // 5 veryHard
    )

    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 11f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = routeBlue; style = Paint.Style.STROKE
        strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val riderCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 3f; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val standbyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; isFakeBoldText = true }

    // ETA pill (drawn in screen space, bottom-centre, inside the round safe zone)
    private val pillBgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 20, 22, 26) }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val gpsPillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    // Reused across frames
    private val routePath = Path()
    private val riderPath = Path()
    private val pillRect = RectF()
    private val textBounds = Rect()
    // Projected route, x/y interleaved. Grown, never shrunk: a route is a few
    // hundred points and this is the one hot-loop buffer worth keeping.
    private var projected = FloatArray(1024)

    /**
     * Nearest point on the route polyline to (lat, lng), as (segment index,
     * projected point on that segment). Local equirectangular approximation
     * referenced to the route's first point — fine at road/city scale, same
     * idea as Dart's `GeoPoint.projectOnSegment` (nav_engine.dart), just not
     * shared code since the route geometry crosses the platform channel as
     * plain coordinate pairs (see [GeoPoint]'s doc). Null for <2 points.
     */
    private fun nearestOnRoute(route: List<GeoPoint>, lat: Double, lng: Double): Pair<Int, GeoPoint>? {
        if (route.size < 2) return null
        val cosRef = cos(Math.toRadians(route[0].lat))
        fun x(g: GeoPoint) = Math.toRadians(g.lng) * cosRef
        fun y(g: GeoPoint) = Math.toRadians(g.lat)
        val px = x(GeoPoint(lat, lng)); val py = y(GeoPoint(lat, lng))

        var bestIdx = 0
        var bestT = 0.0
        var bestDist2 = Double.MAX_VALUE
        for (i in 0 until route.size - 1) {
            val a = route[i]; val b = route[i + 1]
            val ax = x(a); val ay = y(a); val bx = x(b); val by = y(b)
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0
                else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val ddx = px - (ax + dx * t); val ddy = py - (ay + dy * t)
            val dist2 = ddx * ddx + ddy * ddy
            if (dist2 < bestDist2) { bestDist2 = dist2; bestIdx = i; bestT = t }
        }
        val a = route[bestIdx]; val b = route[bestIdx + 1]
        val proj = GeoPoint(a.lat + (b.lat - a.lat) * bestT, a.lng + (b.lng - a.lng) * bestT)
        return bestIdx to proj
    }

    /**
     * Projects the whole polyline once into [projected].
     *
     * Once, not per use: `pixelForLatLng` is an `external` call returning a
     * fresh `PointF`, so every point is a JNI crossing plus an object. Drawing
     * the casing and then each traffic-coloured segment used to re-derive the
     * same coordinates four times over.
     */
    private fun projectRoute(route: List<GeoPoint>, projection: MapProjection) {
        if (projected.size < route.size * 2) projected = FloatArray(route.size * 2)
        for (i in route.indices) {
            val p = projection.project(route[i].lat, route[i].lng)
            projected[i * 2] = p.x
            projected[i * 2 + 1] = p.y
        }
    }

    /**
     * Draws the overlays over whatever [canvas] already holds — the map
     * snapshot. Nothing is cleared here: on a frame where the snapshot failed,
     * the caller keeps the previous complete frame rather than handing us an
     * empty one.
     */
    fun draw(canvas: Canvas, f: Frame, projection: MapProjection) {
        val w = canvas.width
        val h = canvas.height

        // ── Road route polyline (casing, then traffic-coloured fill) ──
        // Trimmed to the road AHEAD: find the segment nearest the rider and start
        // the line at the projected point on it, dropping everything behind — so
        // the travelled part disappears from under the arrow as it advances,
        // instead of leaving the whole route drawn start-to-finish.
        val (drawRoute, drawJam) = run {
            val rLat = f.riderLat; val rLng = f.riderLng
            val near = if (rLat != null && rLng != null) nearestOnRoute(f.route, rLat, rLng) else null
            if (near == null) return@run f.route to f.routeJam
            val (segIdx, proj) = near
            val trimmed = listOf(proj) + f.route.subList(segIdx + 1, f.route.size)
            val trimmedJam =
                if (f.routeJam.size == f.route.size - 1) f.routeJam.subList(segIdx, f.routeJam.size)
                else f.routeJam
            trimmed to trimmedJam
        }
        if (drawRoute.size >= 2) {
            projectRoute(drawRoute, projection)
            routePath.reset()
            routePath.moveTo(projected[0], projected[1])
            for (i in 1 until drawRoute.size) routePath.lineTo(projected[i * 2], projected[i * 2 + 1])
            canvas.drawPath(routePath, routeCasing)

            val segCount = drawRoute.size - 1
            if (drawJam.size == segCount) {
                // Traffic data available for every segment — colour each one, same
                // per-segment approach as the in-app map's `applyJamColors`.
                for (i in 0 until segCount) {
                    routePaint.color = jamColors.getOrElse(drawJam[i]) { routeBlue }
                    canvas.drawLine(
                        projected[i * 2], projected[i * 2 + 1],
                        projected[i * 2 + 2], projected[i * 2 + 3],
                        routePaint,
                    )
                }
            } else {
                // No/partial traffic data for this route — solid fallback.
                routePaint.color = routeBlue
                canvas.drawPath(routePath, routePaint)
            }
        }

        // ── Destination pin ──
        if (f.destLat != null && f.destLng != null) {
            val p = projection.project(f.destLat, f.destLng)
            // Google-style red destination pin (white ring + red fill).
            dotPaint.color = Color.WHITE; canvas.drawCircle(p.x, p.y, 12f, dotPaint)
            dotPaint.color = googleRed; canvas.drawCircle(p.x, p.y, 9f, dotPaint)
            dotPaint.color = Color.WHITE; canvas.drawCircle(p.x, p.y, 3.5f, dotPaint)
        }

        // ── Rider marker: navigation arrow, oriented to travel heading ──
        // Same kite/dart silhouette as the app's `Icons.navigation_outlined` (Home
        // screen's "Начать навигацию" tile) instead of a plain dot. Drawn
        // "pointing up" and then rotated only in north-up mode, where up is north
        // and the arrow has to show the true compass bearing itself. In heading-up
        // mode the camera has already turned the map, so the arrow stays pointing
        // at the top of the frame — "forward" — with no rotation of its own.
        if (f.riderLat != null && f.riderLng != null) {
            val p = projection.project(f.riderLat, f.riderLng)
            val rx = p.x; val ry = p.y
            val markerColor = when {
                f.gpsLost -> Color.rgb(150, 154, 160)
                f.gpsWeak -> Color.rgb(251, 188, 5)
                else -> routeBlue
            }
            dotPaint.color = Color.argb(
                60,
                Color.red(markerColor),
                Color.green(markerColor),
                Color.blue(markerColor),
            )
            canvas.drawCircle(rx, ry, 17f, dotPaint)

            canvas.save(); canvas.rotate(if (f.headingUp) 0f else f.heading, rx, ry)
            riderPath.reset()
            riderPath.moveTo(rx, ry - 13f)      // tip (front)
            riderPath.lineTo(rx + 9f, ry + 9f)  // right wing
            riderPath.lineTo(rx, ry + 3f)       // back notch (concave)
            riderPath.lineTo(rx - 9f, ry + 9f)  // left wing
            riderPath.close()
            canvas.drawPath(riderPath, riderCasing)
            dotPaint.color = markerColor; canvas.drawPath(riderPath, dotPaint)
            canvas.restore()
        }

        if (f.gpsLost || f.gpsWeak) {
            val label = if (f.gpsLost) "GPS lost" else "GPS weak"
            gpsPillText.color = if (f.gpsLost) googleRed else Color.rgb(251, 188, 5)
            val font = gpsPillText.fontMetrics
            val textHeight = font.descent - font.ascent
            val pillWidth = gpsPillText.measureText(label) + 28f
            val center = w / 2f
            val top = 14f
            val bottom = top + 12f + textHeight
            pillRect.set(center - pillWidth / 2f, top, center + pillWidth / 2f, bottom)
            val radius = (bottom - top) / 2f
            canvas.drawRoundRect(pillRect, radius, radius, pillBgPaint)
            canvas.drawRoundRect(pillRect, radius, radius, pillBorderPaint)
            canvas.drawText(label, center, top + 6f - font.ascent, gpsPillText)
        }

        // No other on-map text overlays — the dash's own widgets show name/turn, and the
        // round bezel clips anything near the top edge.

        // ── Standby when nothing to show ──
        // Colour follows the style: the light map's dark grey vanishes on Dark
        // Matter's near-black background.
        if (f.riderLat == null && f.destLat == null) {
            val msg = "OpenDash · waiting for GPS"
            standbyPaint.color = if (darkMap) Color.rgb(170, 174, 180) else Color.rgb(60, 64, 67)
            standbyPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (w - textBounds.width()) / 2f, h / 2f, standbyPaint)
        }
    }
}
