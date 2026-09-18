package com.opendash.opendash_dash_engine.dash.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.location.Location
import android.os.SystemClock
import com.opendash.opendash_dash_engine.DashInputs
import com.opendash.opendash_dash_engine.RouteGeometry
import com.opendash.opendash_dash_engine.dash.FrameSource
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import com.opendash.opendash_dash_engine.util.monotonicMs
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The map half of one stream: camera smoothing, the redraw decision, MapLibre, the overlays.
 *
 * Everything here needs a device — a Bitmap, a Canvas, a snapshotter that insists on the main
 * thread — which is exactly why it is on this side of [FrameSource]. The pacing, the encoder
 * and the socket are on the other side, in
 * [com.opendash.opendash_dash_engine.dash.FrameStreamer], where they can be tested without
 * one (pipeline.md §4.8).
 *
 * **One per stream.** The bitmap, the redraw signature, the render window and the "has a
 * frame landed yet" flag all belong to a single connection; a new stream gets a new object,
 * and [release] frees the old one's bitmap. The camera does NOT belong here — it outlives the
 * stream and is written by the joystick — so it arrives as [DashCameraState].
 *
 * @param onTick called once per frame, before anything is drawn: the controller's
 *   `publishState`. It is here rather than in the loop because it belongs to the same
 *   cadence as the camera it reports.
 */
internal class MapFrameRenderer(
    private val inputs: StateFlow<DashInputs>,
    private val location: StateFlow<Location?>,
    private val trusted: StateFlow<Boolean>,
    private val camera: DashCameraState,
    private val snapshots: MapSnapshotProvider,
    private val overlays: OverlayRenderer,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val onTick: () -> Unit,
) : FrameSource {
    companion object {
        private const val FORCE_REDRAW_MS = 2_000L
        private const val SMOOTH_TAU = 0.35

        /**
         * Frame-rate hysteresis in m/s of ground speed, and the shortest time a verdict
         * may stand — one GOP at 4 fps, so a bitrate retarget can never land more
         * often than the key frames it lands between. Why these are two numbers and not
         * one threshold: [FrameRatePolicy].
         */
        private const val CAM_MOVING_ENTER_MPS = 0.9   // 3.2 km/h
        private const val CAM_MOVING_EXIT_MPS = 0.5    // 1.8 km/h
        private const val CAM_MOVING_DWELL_MS = 2_000L

        // Perspective tilt for the heading-up view, in degrees (MapLibre clamps
        // to 0..60). Left flat for now: the raster renderer's `setPolyToPoly`
        // trapezoid was a fake with no camera model behind it, so it gives no
        // starting angle, and a real tilt changes what falls off the edge of a
        // pack (the camera looks further, so the 2 km cut buffer is eaten
        // sooner). Picking the value is an MVP question to answer on the panel,
        // not at the keyboard — see «Камера, а не поворот растра».
        private const val NAV_TILT_DEG = 0.0

        // How long the frame loop waits for a snapshot before giving up on it and
        // keeping the previous frame. Two frame intervals at 4 fps; a snapshot
        // slower than this is not "the map is behind", it is "the dash froze".
        private const val SNAPSHOT_DEADLINE_MS = 500L

        // The FIRST snapshot of a stream gets its own, much larger budget. It is
        // not comparable to the rest: MapLibre loads the style on the first
        // `start()`, not when the snapshotter is built, so that one pays for
        // parsing ~45 layers times the number of packs, the sprite sheet, the
        // glyph ranges and a header read on every `.pmtiles` file. Holding it to
        // the steady-state deadline would fail it on principle and leave the dash
        // without a frame at all — there is no previous one to keep.
        private const val FIRST_SNAPSHOT_DEADLINE_MS = 8_000L

        /**
         * A coordinate for the log: five decimals, about a metre, and a decimal POINT.
         *
         * `Locale.ROOT` for the same reason [DashCameraState.zoomText] avoids `format`
         * entirely — the default locale on this device writes "60,24647", and a comma is
         * what separates the pair.
         */
        private fun geoText(v: Double) = String.format(Locale.ROOT, "%.5f", v)
    }

    /**
     * The render window, and it belongs to THIS stream — which is the whole reason this
     * class is built per stream rather than kept by the controller.
     *
     * There used to be a `reset()` called at the top of each stream, because the window and
     * the frame loop had different lifetimes. A session that ended before its 30-second
     * window closed carried its frames into the NEXT session's first window, which is
     * measured from zero — that is where `frames=195/120` came from in the 2026-09-04 logs
     * (plan.md 1.6): not a fast loop, two sessions added up. `frames=sent/expected` is the
     * number the whole "wait for the snapshot" decision rests on, so it has to mean one
     * session's worth of frames. A fresh object per stream makes that true by construction,
     * and the `reset()` was deleted with the lifetime mismatch that needed it.
     */
    private val renderStats = RenderStats()

    /** Owns the hysteresis and the flip counter; touched from the frame loop only. */
    private val frameRate =
        FrameRatePolicy(CAM_MOVING_ENTER_MPS, CAM_MOVING_EXIT_MPS, CAM_MOVING_DWELL_MS)

    /**
     * The frame the encoder draws from — one bitmap for the life of the stream.
     *
     * ~631 KB in the native heap (API 26+), where the Java GC feels no pressure from it and
     * would leave the free to a finalizer. Dropping it per stream ([release]) added up across
     * a ride of reconnects without ever raising a Java OOM.
     */
    private var frameBitmap: Bitmap? =
        Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)

    /** Destination rect for the snapshot upscale; reused, this runs 4 times a second. */
    private val frameRect = Rect(0, 0, frameWidth, frameHeight)

    /** See the note at its use — the bilinear filter is the whole reason it exists. */
    private val upscalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var lastTickNs = 0L
    private var lastSignature = ""
    private var lastRedrawAt = 0L

    /**
     * Metres the camera centre has travelled since the last `[map]` line.
     *
     * The camera's own movement, not the raw fix: after smoothing, and after the
     * `haveTarget` gate, this is what the snapshot was actually taken at. That is
     * the question a frozen-map report asks — "did the view follow me" — and
     * until 2026-09-06 nothing in a ride file could answer it. `redraws` could
     * not: the redraw signature carries heading at 0.1°, which jitters on its own,
     * so a full `redraws=120/120` window is consistent with a centre that never
     * moved a metre.
     */
    private var windowMovedM = 0.0

    /**
     * The controllable part of the last camera [logCameraSend] reported, so it
     * only speaks when one of those parts actually changed.
     *
     * Written by [logCameraSend] on the frame loop and nowhere else, so it needs no
     * @Volatile — a fresh stream gets a fresh renderer and therefore a fresh null, which is
     * what makes every session's file open with the camera it started on.
     */
    private var lastCameraLogKey: String? = null

    /** Whether [frameBitmap] holds a real frame yet, i.e. one snapshot has landed. */
    private var haveFrame = false

    @Volatile private var camMoving = false
    override val moving: Boolean get() = camMoving

    override fun drawInto(canvas: Canvas) {
        val bmp = frameBitmap ?: return
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    override fun invalidate() { lastSignature = "" }

    override fun drainFpsFlips(): Int = frameRate.drainFlips()

    override fun frameEncoded(intervalMs: Long, encodeMs: Long, intendedIntervalMs: Long) {
        renderStats.frameSent(
            intervalMs = intervalMs,
            encodeMs = encodeMs,
            intendedIntervalMs = intendedIntervalMs,
        )
    }

    override fun drainWindowLog(periodMs: Long): String {
        // Appended here rather than passed into drain(): RenderStats measures the frame
        // pipeline and has no business knowing what a camera is. It earns the space because
        // every other number on this line is read against it — `blank` especially, which
        // rises with zoom for reasons that are not a missing map.
        val line = renderStats.drain(
            periodMs = periodMs,
            timeouts = snapshots.timeouts,
            skipped = snapshots.skipped,
            abandoned = snapshots.abandoned,
            errors = snapshots.errors,
            rebuilds = snapshots.rebuilds,
        ) + " zoom=${DashCameraState.zoomText(camera.zoom)}" +
            " center=${geoText(camera.lat)},${geoText(camera.lng)}" +
            " moved=${windowMovedM.toInt()}m"
        windowMovedM = 0.0
        return line
    }

    /** Frees this stream's bitmap. The next stream gets a new renderer and a new one. */
    fun release() {
        runCatching { frameBitmap?.recycle() }
        frameBitmap = null
    }

    /**
     * One iteration of camera smoothing, plus a redraw when anything visible changed.
     *
     * Suspends because [redrawFrame] waits for MapLibre.
     *
     * [budgetMs] is this frame's budget — passed down so telemetry can say how often the
     * snapshot ate it, which is the number the "wait for the snapshot" decision stands or
     * falls on (see «Телеметрия»).
     */
    override suspend fun advance(budgetMs: Long): Boolean {
        // ONE read for the whole frame. Everything below — the camera target, the redraw
        // signature, the overlay — works from this copy, so a `setNavState` landing mid-tick
        // takes effect on the NEXT frame in full rather than on this one in part. That is the
        // entire point of DashInputs; see its doc for the two defects it retires.
        val inp = inputs.value

        camera.releasePanIfIdle(monotonicMs())

        val loc = location.value
        val riderLat = loc?.latitude
        val riderLng = loc?.longitude
        val heading = loc?.bearing ?: (if (camera.initialised) camera.heading else 0f)

        val gps = gpsFlags(loc, trusted.value)

        // GPS/progress fields come from publishState's own read of the live sources now —
        // it is no longer this function's job to be their only supplier.
        onTick()

        // The frame is always a live map — idle is just the map with no route or
        // destination, never a separate mode (see spec/fsm.md). [DashInputs.navigating] still
        // gates what isn't frame content: DashSession's chrome/nav-info decisions.

        val haveTarget = riderLat != null || inp.dest != null
        val targetLat = riderLat ?: inp.dest?.lat ?: camera.lat
        val targetLng = riderLng ?: inp.dest?.lng ?: camera.lng

        val nowNs = System.nanoTime()
        // Two readings of the same gap, deliberately. [dt] stays clamped because it drives
        // the camera smoothing, where a long stall must not produce one giant jump. The
        // speed below needs the UNclamped value: dividing a real 0.8 s of travel by a
        // clamped 0.5 s would report 1.6x the rider's speed and bias every late frame
        // towards "moving".
        val dtRaw = if (lastTickNs == 0L) 0.042 else (nowNs - lastTickNs) / 1e9
        val dt = dtRaw.coerceIn(0.0, 0.5)
        lastTickNs = nowNs
        val a = if (camera.initialised) (1.0 - exp(-dt / SMOOTH_TAU)) else 1.0

        // Read BEFORE the block below, which sets it. Afterwards it says "the
        // camera has a position", not "it had one to move from" — and the first
        // tick moves it from (0,0) to the rider, so a distance measured against
        // the post-block flag is a 6700 km teleport across the Gulf of Guinea.
        // That lands in `moved=` as the opening number of every fresh process,
        // and in [camMoving] as a spurious "riding" verdict that puts the first
        // frames at 4 fps. A new stream clears `initialised` without clearing the
        // centre, so a later stream jumps by less but jumps all the same.
        val wasInit = camera.initialised
        val prevLat = camera.lat; val prevLng = camera.lng
        if (haveTarget) {
            if (!camera.initialised) {
                camera.initialiseAt(targetLat, targetLng, heading)
            } else {
                val dh = (((heading - camera.heading) % 360f) + 540f) % 360f - 180f
                camera.moveTo(
                    camera.lat + (targetLat - camera.lat) * a,
                    camera.lng + (targetLng - camera.lng) * a,
                    camera.heading + dh * a.toFloat(),
                )
            }
        }
        val movedM = if (wasInit) distMeters(prevLat, prevLng, camera.lat, camera.lng) else 0.0
        windowMovedM += movedM
        // Ground speed, not distance-per-tick — see [CAM_MOVING_ENTER_MPS] for why the
        // difference is the whole point. The camera's own speed and the fix's agree while
        // riding steadily; the max of the two is what keeps a lagging camera from reading
        // as a stopped bike right after pulling away.
        val camSpeedMps = if (dtRaw > 0.0) movedM / dtRaw else 0.0
        // A lost fix contributes nothing. `loc` survives its own staleness — it is the last
        // fix, not a live one — so a bike that loses GPS at speed (tunnel, garage, underpass)
        // would otherwise keep feeding its final speed into the policy forever, and the dash
        // would sit at 4 fps and the moving bitrate for the whole stop. [GpsFlags.lost] is the
        // same staleness test the overlays use; below it the camera's own movement still
        // speaks, and a parked bike moves the camera not at all.
        val fixSpeedMps = if (gps.lost) 0.0 else (loc?.speed ?: 0f).toDouble()
        val speedMps = maxOf(camSpeedMps, fixSpeedMps)
        // elapsedRealtime, not currentTimeMillis: this is a duration, and wall clock
        // moves. An NTP correction backwards — routine the moment data comes back after a
        // dead zone — makes the dwell's `now - last` negative and freezes the frame rate
        // for the length of the jump; a forward one cancels the dwell entirely. The
        // smoothing above already takes its elapsed time from nanoTime for the same reason.
        camMoving = frameRate.update(speedMps, SystemClock.elapsedRealtime())

        val centerLat = if (haveTarget) camera.lat else 0.0
        val centerLng = if (haveTarget) camera.lng else 0.0
        val camHeading = if (haveTarget) camera.heading else heading

        // ONE read of the volatile [DashCameraState.headingUp] for the whole frame. It used
        // to be read three times per tick: twice inside `sig` below — where a toggle between
        // the two appends writes "heading-up" next to a north-up heading of 0, a combination
        // no real frame has — and a third time in [redrawFrame], so the signature committed
        // as drawn could describe an orientation the frame was never drawn in. Both are
        // self-correcting on the next tick and cost one wasted redraw; the reason to fix them
        // anyway is that [cameraFor] now states as fact that the caller holds a single read.
        // Review, 2026-09-16.
        val headingUpNow = camera.headingUp

        // Everything the frame is drawn FROM has to be in here, or the change is
        // invisible until FORCE_REDRAW_MS two seconds later. Three things used to
        // be missing, each with its own way of showing up on the panel:
        //   - `headingUp` itself. Only its *effect* was included, and at a heading
        //     near 0° the north-up and heading-up frames hash the same — so the
        //     one toggle a rider presses to reorient the map appeared to do
        //     nothing at exactly the moment it was most confusing.
        //   - the route's contents. `size` alone hides a reroute onto a different
        //     road with the same number of points, and hides every jam-colour
        //     change outright.
        //   - the destination. Picking a new one left the old pin on the dash.
        val sig = buildString {
            append("nav")
            append("%.6f".format(centerLat)); append("%.6f".format(centerLng))
            append(camera.zoom); append(camera.panX.toInt()); append(camera.panY.toInt())
            append(headingUpNow)
            append(if (headingUpNow) (camHeading * 10).toInt() else 0)
            append(inp.remainingM?.let { (it / 100).toInt() } ?: -1)
            append(routeSignature(inp.route))
            append(inp.dest?.let { "%.5f".format(it.lat) } ?: "-")
            append(inp.dest?.let { "%.5f".format(it.lng) } ?: "-")
            append(gps.lost); append(gps.weak)
        }
        val now = monotonicMs()
        if (sig != lastSignature || now - lastRedrawAt > FORCE_REDRAW_MS) {
            // Committed only if the frame was actually redrawn. Recording the
            // signature up front made a failed snapshot look like a drawn frame:
            // the same signature would not be retried, so a parked rider — whose
            // signature is stable — kept a stale frame until FORCE_REDRAW_MS, and
            // the telemetry counted it as a deliberate reuse.
            if (redrawFrame(
                    inp,
                    centerLat, centerLng, camHeading, headingUpNow,
                    riderLat != null, gps,
                    budgetMs,
                )
            ) {
                lastSignature = sig
                lastRedrawAt = now
            }
        }
        return haveFrame
    }

    /**
     * The camera for one frame.
     *
     * Rotation and tilt live here rather than in a transform over the finished
     * raster: turning a drawn map turns its labels with it, while MapLibre keeps
     * them upright itself (spec/drawing_from_local_tiles.md, «Камера, а не
     * поворот растра»).
     */
    private fun cameraFor(
        centerLat: Double,
        centerLng: Double,
        heading: Float,
        // Passed in, not read from the state. This used to read the volatile `headingUp`
        // TWICE — once for bearing, once for tilt — so a toggle landing between them produced
        // a north-up raster with a 45° tilt, or the reverse. The caller already holds one read
        // for the whole frame; taking it as a parameter makes that the only read there is.
        // Review, 2026-09-15.
        headingUp: Boolean,
    ): CameraPosition =
        CameraPosition.Builder()
            .target(LatLng(centerLat, centerLng))
            .zoom(camera.zoom / DashCameraState.ZOOM_SCALE)
            .bearing(if (headingUp) heading.toDouble() else 0.0)
            .tilt(if (headingUp) NAV_TILT_DEG else 0.0)
            .build()

    /**
     * A cheap stand-in for "the route the frame would be drawn from".
     *
     * Hashing every point is pointless at 4 fps and comparing the lists outright
     * would keep the previous one alive; the endpoints plus the count plus the
     * jam colours catch what actually changes on screen — a reroute (different
     * geometry, same length) and a traffic recolour (same geometry).
     *
     * Takes the route rather than reading it. The old version opened with "one read of each
     * @Volatile field, not four: re-reading between isEmpty() and first() would throw
     * NoSuchElementException on the frame loop" — correct then, unnecessary now that the
     * caller holds one immutable snapshot for the whole frame. That is the kind of defensive
     * reasoning [DashInputs] exists to delete.
     */
    private fun routeSignature(route: RouteGeometry): String {
        val points = route.points
        if (points.isEmpty()) return "r0"
        val first = points.first()
        val last = points.last()
        return "r${points.size}:${"%.5f".format(first.lat)},${"%.5f".format(first.lng)}" +
            ":${"%.5f".format(last.lat)},${"%.5f".format(last.lng)}:${route.jam.hashCode()}"
    }

    /**
     * Map first, overlays on top, into [frameBitmap]. True if the frame changed.
     *
     * A snapshot that failed or missed its deadline leaves the frame untouched —
     * the dash keeps showing the last complete one — and returns false, so the
     * caller knows not to record this frame as drawn. Redrawing overlays over a
     * stale map is not an option: the route and the rider arrow would be a frame
     * ahead of the roads under them.
     */
    private suspend fun redrawFrame(
        inp: DashInputs,
        centerLat: Double, centerLng: Double, heading: Float,
        // The caller's single read of the volatile `headingUp`, passed in rather than read
        // again here: the camera, the padding, the log and the overlays below must agree
        // with each other AND with the signature the caller hashed from the same value.
        headingUpNow: Boolean,
        haveRider: Boolean, gps: GpsFlags,
        budgetMs: Long,
    ): Boolean {
        val bmp = frameBitmap ?: return false

        val cam = cameraFor(centerLat, centerLng, heading, headingUpNow)
        val padding = DashCamera.padding(frameWidth, frameHeight, headingUpNow, camera.panX, camera.panY)
        logCameraSend(cam, padding, headingUpNow)

        val snapshotStart = monotonicMs()
        val snapshot = snapshots.capture(
            cam,
            padding,
            if (haveFrame) SNAPSHOT_DEADLINE_MS else FIRST_SNAPSHOT_DEADLINE_MS,
        ) ?: return false
        val snapshotMs = monotonicMs() - snapshotStart

        val overlayStart = monotonicMs()
        val map = snapshot.bitmap
        val blank = MapSnapshotProvider.isBlank(map)
        val canvas = Canvas(bmp)
        // Scaled up when MapLibre rendered smaller than the frame (see
        // MapSnapshotProvider.PIXEL_RATIO). FILTER_BITMAP_FLAG is the point, not a
        // detail: the bilinear smoothing is the low-pass that makes the frame
        // cheap to encode, and a nearest-neighbour upscale would put the hard
        // edges straight back while also looking worse.
        canvas.drawBitmap(map, null, frameRect, upscalePaint)
        // Snapshot bitmaps are allocated natively and arrive one per redraw. Since
        // API 26 their pixels live outside the Java heap, so the GC feels no
        // pressure from them and would leave the free to a finalizer — at 631 KB a
        // frame that is gigabytes an hour growing without ever raising a Java OOM.
        // Safe to do here: the pipeline holds exactly one snapshot at a time and
        // its contents are already copied above.
        map.recycle()

        val frame = OverlayRenderer.Frame(
            // [headingUpNow], not a fresh read: the snapshot above suspends for up to
            // SNAPSHOT_DEADLINE_MS, and a toggle landing inside that window would draw a
            // heading-up overlay — an unrotated rider arrow — over a north-up raster. Found
            // by review; it is also the counterexample to the note beside the camera fields,
            // so that note no longer claims they are only ever read one at a time.
            headingUp = headingUpNow,
            heading = heading,
            riderLat = if (haveRider) camera.lat else null,
            riderLng = if (haveRider) camera.lng else null,
            destLat = inp.dest?.lat,
            destLng = inp.dest?.lng,
            // From the SAME snapshot the signature above was built from, so the line the
            // rider sees and the colours on it always describe one route.
            route = inp.route.points,
            routeJam = inp.route.jam,
            gpsWeak = gps.weak,
            gpsLost = gps.lost,
        )
        // The projection comes off the snapshot that was just drawn, so overlays
        // and map can never disagree about where a coordinate is — but it speaks
        // in the SNAPSHOT's pixels, and the snapshot is smaller than the frame
        // whenever PIXEL_RATIO is below 1. Scaling here keeps that guarantee: the
        // same coordinate lands on the same road after the upscale above. Miss
        // this and the route floats off the map by a factor of two, on a screen
        // nobody is looking at while it happens.
        val projScale = 1f / MapSnapshotProvider.PIXEL_RATIO
        overlays.draw(
            canvas,
            frame,
            MapProjection { lat, lng ->
                val p = snapshot.pixelForLatLng(LatLng(lat, lng))
                if (projScale == 1f) p else PointF(p.x * projScale, p.y * projScale)
            },
        )

        renderStats.mapDrawn(
            snapshotMs = snapshotMs,
            overlayMs = monotonicMs() - overlayStart,
            budgetMs = budgetMs,
            blank = blank,
        )
        if (!haveFrame) {
            haveFrame = true
            RideDiagnostics.log("map", "first map frame ready in ${snapshotMs}ms (blank=$blank)")
        }
        return true
    }

    /**
     * What MapLibre was actually handed — the far end of a button press.
     *
     * **Only when it changes.** The centre moves with every GPS fix, so a line per
     * capture would be two to four a second: the ride file for one 11-minute
     * session would outgrow every other signal in it, which is the same reason the
     * frame stats are a periodic aggregate rather than a line per frame. What a
     * rider can change is keyed here instead; where the rider *is* is not what is
     * in question when a control looks dead.
     *
     * Pan is absent from [CameraPosition] on purpose — it reaches MapLibre as
     * padding, not as a shifted centre (see [DashCamera.padding]) — so the padding
     * is printed rather than the pan that produced it: this line is meant to say
     * what the renderer got, not what we meant by it. Padding is space-separated
     * because a comma next to a locale-formatted number reads as a decimal.
     *
     * **Bearing is named, not numbered.** In heading-up it tracks the rider and
     * changes with every fix, which the key deliberately does not — so printing
     * the degrees would freeze one arbitrary sample at the top of the session and
     * read, months later, as a map that stopped rotating. The mode is the part
     * that actually holds still, and it is the part a dead control would break.
     */
    private fun logCameraSend(cam: CameraPosition, padding: IntArray, headingUp: Boolean) {
        // Keyed and printed from the arguments, never from the live state. A press
        // landing between [cameraFor] and here would otherwise print a zoom MapLibre
        // was never given — and commit that key, so the frame that does use it says
        // nothing.
        val key = "${cam.zoom}/${cam.tilt}/$headingUp/${padding.joinToString(",")}"
        if (key == lastCameraLogKey) return
        lastCameraLogKey = key
        RideDiagnostics.log(
            "camera",
            "→ MapLibre zoom=${String.format(Locale.ROOT, "%.2f", cam.zoom)} " +
                "${if (headingUp) "heading-up" else "north-up"} " +
                "tilt=${cam.tilt.toInt()} padding=[${padding.joinToString(" ")}]",
        )
    }

    private fun distMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(s), sqrt(1 - s))
    }
}
