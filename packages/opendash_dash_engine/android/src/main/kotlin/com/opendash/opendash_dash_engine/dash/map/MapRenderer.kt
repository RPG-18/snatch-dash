package com.opendash.opendash_dash_engine.dash.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.cos

/**
 * Draws the navigation frame for the Tripper Dash (526 × 300).
 *
 * Layers: map tiles (already dark-filtered by TileProvider) → road route polyline
 * → destination pin → rider marker → top banner (name + remaining) → maneuver chip.
 * Optional heading-up rotation. Paint/Path/Rect objects are reused across frames
 * to avoid per-frame allocation churn.
 */
class MapRenderer(private val tiles: TileProvider) {

    data class Frame(
        val centerLat: Double,
        val centerLng: Double,
        val zoom: Int,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val headingUp: Boolean = false,
        val heading: Float = 0f,           // travel bearing, degrees
        val riderLat: Double? = null,
        val riderLng: Double? = null,
        val destLat: Double? = null,
        val destLng: Double? = null,
        val destName: String? = null,
        val route: List<GeoPoint> = emptyList(),
        // Traffic-level code per route segment (index i = route[i]..route[i+1]),
        // 0=unknown..5=veryHard — see jamColors below. Empty/mismatched length
        // (no traffic data for this route) falls back to a solid [routeBlue] line.
        val routeJam: List<Int> = emptyList(),
        val maneuverText: String? = null,  // e.g. "Turn left · 400 m"
        val remainingText: String? = null, // e.g. "186 km"
        val tilt3d: Boolean = false,       // perspective 3D view (nav heading-up only)
        val etaPrimary: String? = null,    // big glance value, e.g. "24 min" (nav only)
        val etaSecondary: String? = null,  // smaller line, e.g. "18 km · 13:32"
        val gpsWeak: Boolean = false,
        val gpsLost: Boolean = false,
    )

    private val bgColor   = Color.rgb(229, 227, 223) // Google Maps land colour, behind missing tiles
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

    private val tilePaint  = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Gentle saturation nudge to help against the dash TFT's daylight wash-out. No
        // brightness/contrast tricks (those flattened or clipped the map before).
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.2f) })
    }
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
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; isFakeBoldText = true }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = routeBlue; textSize = 19f; isFakeBoldText = true }
    private val bannerPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 13, 15, 17) }
    private val standbyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 64, 67); textSize = 22f; isFakeBoldText = true }

    // ETA pill (drawn in screen space, bottom-centre, inside the round safe zone)
    private val etaBgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 20, 22, 26) }
    private val etaBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val etaBigPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 217, 87); textSize = 20f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }   // Google-nav green
    private val etaSmallPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 201, 208); textSize = 12f; textAlign = Paint.Align.CENTER }
    private val gpsPillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    // Reused across frames
    private val routePath = Path()
    private val riderPath = Path()
    private val tmpRect = RectF()
    private val pillRect = RectF()
    private val textBounds = Rect()
    private val tiltMatrix = Matrix()
    private val tiltSrc = FloatArray(8)
    private val tiltDst = FloatArray(8)

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

    fun draw(canvas: Canvas, f: Frame) {
        val w = canvas.width
        val h = canvas.height
        canvas.drawColor(bgColor)

        val rotate = f.headingUp
        val tilt = rotate && f.tilt3d
        // Nav view: bias the rider toward the lower third so the road AHEAD fills the
        // screen (like Google Maps navigation). 3D pushes it lower still. North-up
        // view keeps the rider centred.
        val pivotY = if (rotate) (if (tilt) h * 0.74f else h * 0.66f) else h / 2f

        val ts = Mercator.TILE_SIZE
        val cx = Mercator.lngToTileX(f.centerLng, f.zoom) * ts + f.panX
        val cy = Mercator.latToTileY(f.centerLat, f.zoom) * ts + f.panY
        val left = cx - w / 2.0
        val top  = cy - pivotY

        fun sx(lng: Double) = (Mercator.lngToTileX(lng, f.zoom) * ts - left).toFloat()
        fun sy(lat: Double) = (Mercator.latToTileY(lat, f.zoom) * ts - top).toFloat()

        if (rotate) {
            canvas.save()
            if (tilt) {
                // Perspective tilt: warp the flat frame into a trapezoid that converges
                // toward the top, so the road ahead recedes into the distance (the
                // Google-Maps 3D look). Near things (rider, bottom) stay ~undistorted;
                // far things (dest, route ahead) shrink, which is exactly right.
                val inset = w * 0.18f
                tiltSrc[0] = 0f;          tiltSrc[1] = 0f
                tiltSrc[2] = w.toFloat(); tiltSrc[3] = 0f
                tiltSrc[4] = w.toFloat(); tiltSrc[5] = h.toFloat()
                tiltSrc[6] = 0f;          tiltSrc[7] = h.toFloat()
                tiltDst[0] = inset;          tiltDst[1] = 0f
                tiltDst[2] = w - inset;      tiltDst[3] = 0f
                tiltDst[4] = w.toFloat();    tiltDst[5] = h.toFloat()
                tiltDst[6] = 0f;             tiltDst[7] = h.toFloat()
                tiltMatrix.setPolyToPoly(tiltSrc, 0, tiltDst, 0, 4)
                canvas.concat(tiltMatrix)
            }
            canvas.rotate(-f.heading, w / 2f, pivotY)
        }

        // ── Tiles (padded when rotating so corners are covered) ──
        val pad = if (rotate) (maxOf(w, h) * 0.45).toInt() else 0
        val txMin = Math.floorDiv((left - pad).toInt(), ts)
        val tyMin = Math.floorDiv((top - pad).toInt(), ts)
        val txMax = Math.floorDiv((left + w + pad).toInt(), ts)
        val tyMax = Math.floorDiv((top + h + pad).toInt(), ts)
        for (tx in txMin..txMax) for (ty in tyMin..tyMax) {
            val bmp = tiles.get(f.zoom, tx, ty) ?: continue
            val dstL = (tx * ts - left).toFloat()
            val dstT = (ty * ts - top).toFloat()
            tmpRect.set(dstL, dstT, dstL + ts, dstT + ts)
            canvas.drawBitmap(bmp, null, tmpRect, tilePaint)
        }

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
            routePath.reset()
            routePath.moveTo(sx(drawRoute[0].lng), sy(drawRoute[0].lat))
            for (i in 1 until drawRoute.size) routePath.lineTo(sx(drawRoute[i].lng), sy(drawRoute[i].lat))
            canvas.drawPath(routePath, routeCasing)

            val segCount = drawRoute.size - 1
            if (drawJam.size == segCount) {
                // Traffic data available for every segment — colour each one, same
                // per-segment approach as the in-app map's `applyJamColors`.
                for (i in 0 until segCount) {
                    routePaint.color = jamColors.getOrElse(drawJam[i]) { routeBlue }
                    canvas.drawLine(
                        sx(drawRoute[i].lng), sy(drawRoute[i].lat),
                        sx(drawRoute[i + 1].lng), sy(drawRoute[i + 1].lat),
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
            val dx = sx(f.destLng); val dy = sy(f.destLat)
            // Google-style red destination pin (white ring + red fill).
            dotPaint.color = Color.WHITE; canvas.drawCircle(dx, dy, 12f, dotPaint)
            dotPaint.color = googleRed; canvas.drawCircle(dx, dy, 9f, dotPaint)
            dotPaint.color = Color.WHITE; canvas.drawCircle(dx, dy, 3.5f, dotPaint)
        }

        // ── Rider marker: navigation arrow, oriented to travel heading ──
        // Same kite/dart silhouette as the app's `Icons.navigation_outlined` (Home
        // screen's "Начать навигацию" tile) instead of a plain dot. Always drawn
        // "pointing up" then rotated by [f.heading]: in heading-up mode that
        // rotation cancels the map's own -heading rotation above, so the arrow
        // stays pointing at the top of the screen ("forward"); in north-up mode
        // there's no outer rotation, so it directly shows the true compass bearing.
        if (f.riderLat != null && f.riderLng != null) {
            val rx = sx(f.riderLng); val ry = sy(f.riderLat)
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

            canvas.save(); canvas.rotate(f.heading, rx, ry)
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

        if (rotate) canvas.restore()

        // ── ETA pill (screen-space so it stays upright; bottom-centre safe zone) ──
        // The dash is round, so it's kept narrow and centred. Shows ETA only (time +
        // arrival clock) — distance lives on the dash's own widget.
        f.etaPrimary?.let { primary ->
            val secondary = f.etaSecondary
            val padH = 18f; val padV = 8f; val gap = 1f
            val bigFm = etaBigPaint.fontMetrics
            val smallFm = etaSmallPaint.fontMetrics
            val bigH = bigFm.descent - bigFm.ascent
            val smallH = if (secondary != null) smallFm.descent - smallFm.ascent else 0f
            val contentW = maxOf(etaBigPaint.measureText(primary), secondary?.let { etaSmallPaint.measureText(it) } ?: 0f)
            val pillW = (contentW + padH * 2).coerceAtMost(w * 0.6f)
            val pillH = padV * 2 + bigH + (if (secondary != null) gap + smallH else 0f)
            val cxp = w / 2f
            val bottom = h - 26f
            val top = bottom - pillH
            pillRect.set(cxp - pillW / 2f, top, cxp + pillW / 2f, bottom)
            val r = pillH / 2f
            canvas.drawRoundRect(pillRect, r, r, etaBgPaint)
            canvas.drawRoundRect(pillRect, r, r, etaBorderPaint)
            var baseline = top + padV - bigFm.ascent
            canvas.drawText(primary, cxp, baseline, etaBigPaint)
            if (secondary != null) {
                baseline += bigFm.descent + gap - smallFm.ascent
                canvas.drawText(secondary, cxp, baseline, etaSmallPaint)
            }
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
            canvas.drawRoundRect(pillRect, radius, radius, etaBgPaint)
            canvas.drawRoundRect(pillRect, radius, radius, etaBorderPaint)
            canvas.drawText(label, center, top + 6f - font.ascent, gpsPillText)
        }

        // No other on-map text overlays — the dash's own widgets show name/turn, and the
        // round bezel clips anything near the top edge.

        // ── Standby when nothing to show (dark text on the light map bg) ──
        if (f.riderLat == null && f.destLat == null) {
            val msg = "OpenDash · waiting for GPS"
            standbyPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (w - textBounds.width()) / 2f, h / 2f, standbyPaint)
        }
    }
}
