package com.opendash.opendash_dash_engine.dash.map

import com.opendash.opendash_dash_engine.util.RideDiagnostics
import com.opendash.opendash_dash_engine.util.monotonicMs

/**
 * Where the dash's map camera is pointing, and the controls that move it.
 *
 * **Why this is a class of its own.** Camera state is the one group the frame loop both READS
 * and WRITES: [releasePanIfIdle] returns to follow mode from the loop, while the joystick
 * handlers write zoom and pan from the platform thread. That two-way flow is exactly why it
 * was left out of [com.opendash.opendash_dash_engine.DashInputs] — an immutable snapshot
 * would have to be read, modified and written back, which is a read-modify-write across
 * threads. The note beside those fields said "revisit when the frame loop moves out of this
 * class, where the camera needs a home anyway"; this is that home (pipeline.md §4.8).
 *
 * Nothing about the concurrency changed in the move. The same six fields carry `@Volatile`
 * for the same reason — a button press has to be seen by a loop on `Dispatchers.Default` —
 * and [panAtBound] is still deliberately without it, because it gates a log line and nothing
 * else.
 *
 * @param frameWidth  frame width in pixels; with [frameHeight] it bounds the pan.
 */
internal class DashCameraState(
    private val frameWidth: Int,
    private val frameHeight: Int,
) {
    companion object {
        // ── Map camera (spec/drawing_from_local_tiles.md) ──
        //
        // Zoom is stored in MAPLIBRE units, not slippy ones: MapLibre renders a
        // tile at 512 px, so its zoom Z frames what slippy Z+1 did. The whole
        // ladder moved rather than being converted at the boundary — one place
        // to be wrong instead of three.
        //
        // **In hundredths, as an Int**, so 1175 is a camera zoom of 11.75.
        // MapLibre's zoom is continuous — the tile is `floor(zoom)` and everything
        // between is that tile scaled, with the style's `interpolate` expressions
        // following along — so the ladder need not land on integers, and it should
        // not: an integer step is ×2 apiece, which on 526×300 means a press either
        // barely helps or overshoots. [ZOOM_STEP] of 50 is ×1.41 — half a zoom
        // level, so two presses cover one. It was 25 (×1.19) until 2026-09-09;
        // that made the ladder 21 steps deep and a press too small to feel, which
        // reads on the joystick as a button that did nothing. Hundredths
        // rather than a Float because repeated stepping cannot drift, the bounds
        // stay exact, and the redraw signature keeps appending an Int.
        //
        // Both ends are the pack corpus's own `minzoom`/`maxzoom`, not preferences.
        //
        // The floor: MapLibre asks for tile `floor(camera zoom)`, and rendering does
        // not build downwards — a step below the corpus is a blank screen, not a
        // coarse map. It was 1100 while the corpus was z11-14; the corpus was rebuilt
        // to z10-15 on 2026-09-09, which is what buys this step. The reason it was
        // worth buying is in spec/drawing_from_local_tiles.md, «Лестница зумов»: the
        // move to MapLibre halved the widest view, because the same tile number
        // renders at 512 px instead of the 256 px `minzoom: 11` was calibrated for.
        // 10.00 gives that width back — roughly 20 km across the 526 px frame at
        // latitude 60, against 10.1 km at 11.00.
        //
        // The ceiling is unchanged at 15.00, but it is no longer overzoom: while the
        // corpus stopped at z14, everything above 14.00 was z14 scaled up, magnified
        // and no more detailed. z15 is now real data. (The old ladder ran to 19.00 —
        // five overzoomed steps — with the default sitting two steps into them.)
        const val ZOOM_MIN = 1000
        const val ZOOM_MAX = 1500
        const val ZOOM_STEP = 50

        // The one parameter the bounds do not settle. Set to the corpus's detail
        // ceiling on 2026-09-09, so a ride opens on the closest view that is real
        // data — which is what z15 became in the same rebuild. It was 14.00 while
        // the ceiling was z14.
        //
        // **This equals [ZOOM_MAX], so `zoomIn` is inert until the rider zooms out.**
        // The first press of that direction logs "zoomIn ignored — already at
        // ZOOM_MAX" and does nothing, by construction rather than by fault.
        //
        // Counter-evidence on the record, because it argues the other way and the
        // next ride is what settles it: on a default of 16.00 the 2026-09-05 log
        // counted 110 zoom-out presses against 63 zoom-in — a ride opening too
        // close. That was measured two corpora and one zoom unit ago (slippy zooms,
        // 256 px tiles, [ZOOM_STEP] 25), so it does not transfer directly; what it
        // does say is which direction to look. Read `[joystick] code=0x13` against
        // `0x14` in the next ride file: if zoom-out still leads by that much, the
        // number to move is this one.
        const val ZOOM_DEFAULT = 1500

        /** Hundredths → the units MapLibre's camera actually takes. */
        const val ZOOM_SCALE = 100.0

        /** How long a manual pan holds before the camera returns to following the rider. */
        const val MANUAL_IDLE_MS = 8_000L

        /**
         * Hundredths as a zoom a reader recognises: 1175 → "11.75".
         *
         * Assembled by hand rather than with `"%.2f".format`, which takes the default
         * locale and writes "11,75" on the Russian device this runs on — a decimal
         * comma inside log lines that separate other things with commas.
         */
        fun zoomText(hundredths: Int) =
            "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    // The first six are written by the MethodChannel handlers on the main
    // thread (joystick zoom/pan/recenter) and read by the frame loop on
    // Dispatchers.Default, so they need @Volatile — otherwise a button press
    // can go unseen by the frame loop.
    @Volatile var zoom = ZOOM_DEFAULT
        private set
    @Volatile var panX = 0f
        private set
    @Volatile var panY = 0f
        private set
    @Volatile var headingUp = true
        private set
    @Volatile var followMode = true
        private set
    @Volatile private var lastManualPanAt = 0L

    /**
     * Whether [panBy] is currently pushing against a bound, so it can log the transition
     * instead of every call.
     *
     * Written from BOTH threads — [panBy] and [setFollowMode] on the platform thread,
     * [releasePanIfIdle] on Dispatchers.Default when the manual-pan idle expires — and
     * deliberately not @Volatile. It gates a log line and nothing else, so the worst case is
     * one bound-hit message lost or repeated. Said out loud because a previous comment
     * claimed "MethodChannel thread only", which was wrong and would send the next reader
     * hunting.
     */
    private var panAtBound = false

    /**
     * The floor `zoomOut` may actually reach, in hundredths.
     *
     * [ZOOM_MIN] is what the CURRENT corpus supports; this is what the packs on
     * THIS phone support, and they are versioned separately — a rider who has not
     * re-downloaded since the 2026-09-09 rebuild still holds z11-14 packs, on
     * which the bottom steps of the 10.00 ladder render nothing at all. Raised to
     * whatever the installed packs actually carry, and reset from the style on
     * every stream so a fresh download takes effect on the next connection.
     */
    var floor = ZOOM_MIN
        private set

    /** Smoothed camera centre. Survives a stream: a reconnect resumes where the ride was. */
    var lat = 0.0
        private set
    var lng = 0.0
        private set

    // These two are the exception to the note above: the frame loop writes them on
    // Dispatchers.Default while publishState() reads them from Main for the Dash
    // screen's compass. Without @Volatile that read is a data race — in practice a
    // stale or torn heading on the phone's own preview, which is cosmetic, but
    // "cosmetic race" is not a thing the memory model promises.
    @Volatile var heading = 0f
        private set
    @Volatile var initialised = false
        private set

    // ── Controls, from the platform thread ──

    fun setFollowMode(enabled: Boolean) {
        followMode = enabled
        // Cleared with the pan itself, here and in the other two places pan resets:
        // a stale flag would swallow the log line for the next genuine bound-hit,
        // which is the one thing this flag exists to report.
        if (enabled) { panX = 0f; panY = 0f; panAtBound = false }
    }

    /**
     * Joystick pan, in frame pixels.
     *
     * Bounded, unlike before: pan reaches the camera as padding, and padding is
     * taken out of the viewport it shifts within — see [DashCamera.MAX_PAN_FRACTION].
     */
    fun panBy(dx: Float, dy: Float) {
        followMode = false
        lastManualPanAt = monotonicMs()
        val maxX = frameWidth * DashCamera.MAX_PAN_FRACTION
        val maxY = frameHeight * DashCamera.MAX_PAN_FRACTION
        val beforeX = panX
        val beforeY = panY
        panX = (panX + dx).coerceIn(-maxX, maxX)
        panY = (panY + dy).coerceIn(-maxY, maxY)
        // Same reason as [stepZoom] — pan saturates too, and a joystick held at the
        // bound looks exactly like one nobody is reading — but only the transition
        // into that state, not every call. Two differences from zoom justify it:
        // where the pan ended up is already in the "→ MapLibre" line as padding,
        // and zoom arrives at thumb rate from a button while [panBy] is the one
        // control a drag gesture could drive at frame rate. RideDiagnostics.log
        // appends to a file on external storage from the calling thread, and this
        // one is the Flutter platform thread.
        val stuck = panX == beforeX && panY == beforeY
        if (stuck != panAtBound) {
            panAtBound = stuck
            if (stuck) {
                RideDiagnostics.log("camera", "pan ignored — at the bound (${panX.toInt()},${panY.toInt()})")
            }
        }
    }

    fun zoomIn() = stepZoom(+ZOOM_STEP, "zoomIn")
    fun zoomOut() = stepZoom(-ZOOM_STEP, "zoomOut")

    /**
     * One zoom step, and a line saying whether it moved anything.
     *
     * The clamp is the point. `zoomOut` at [ZOOM_MIN] and `zoomIn` at [ZOOM_MAX]
     * are ordinary silent no-ops, and from the rider's seat a control that
     * bottomed out is indistinguishable from one that is broken — the 2026-09-05
     * ride sent 110 zoom-out presses against a floor of 11 and the log had nothing
     * to say about any of them. Saying "ignored, already at the floor" costs one
     * line per press at a rate a thumb sets.
     */
    private fun stepZoom(delta: Int, action: String) {
        // Logged on the calling (Flutter platform) thread, unlike [panBy], and that
        // is the deliberate half of the asymmetry: this arrives at thumb rate from a
        // physical button — 110 presses across the whole 2026-09-05 ride — where
        // panBy is exposed to a drag gesture that could call it at frame rate. One
        // small append per press is worth the line that told us the ceiling was too
        // low; a per-frame append would not be.
        val before = zoom
        zoom = (zoom + delta).coerceIn(floor, ZOOM_MAX)
        RideDiagnostics.log(
            "camera",
            if (zoom != before) "$action ${zoomText(before)}→${zoomText(zoom)}"
            else "$action ignored — already at ${if (delta > 0) "ZOOM_MAX" else "ZOOM_MIN"} (${zoomText(before)})",
        )
    }

    fun toggleHeadingUp() {
        headingUp = !headingUp
        RideDiagnostics.log("camera", "headingUp=$headingUp")
    }

    fun recenter() {
        followMode = true
        panX = 0f
        panY = 0f
        panAtBound = false
        RideDiagnostics.log("camera", "recenter — follow on, pan cleared")
    }

    // ── Stream lifecycle, from the thread that starts the stream ──

    /**
     * Raise the floor to what the installed packs can actually render, and pull the current
     * zoom up with it.
     *
     * Clamped to the ceiling as well as the floor: [MapStyleAssembler.packMinZoom] reads a
     * byte out of a PMTiles header, so its range is 0..255, not "something sensible". A pack
     * claiming minzoom 20 would make the floor exceed [ZOOM_MAX], and the next `coerceIn` in
     * [stepZoom] throws IllegalArgumentException — a corrupt header taking out the zoom
     * buttons for the whole ride.
     *
     * @param packMinZoom the corpus's own minzoom, or null when nothing readable said.
     * @return the floor now in force, in hundredths.
     */
    fun applyPackFloor(packMinZoom: Int?): Int {
        floor = packMinZoom
            ?.let { (it * ZOOM_SCALE.toInt()).coerceIn(ZOOM_MIN, ZOOM_MAX) }
            ?: ZOOM_MIN
        if (zoom < floor) zoom = floor
        return floor
    }

    /**
     * Forget that the camera has a position, without forgetting where it was.
     *
     * [lat]/[lng] deliberately survive — a reconnect mid-ride should not swing the map back
     * through the Gulf of Guinea — while [initialised] does not, so the first tick of the new
     * stream snaps to the rider instead of smoothing towards them from a stale centre.
     */
    fun beginStream() { initialised = false }

    // ── From the frame loop ──

    /** Snap to a target because there is nothing to smooth from yet. */
    fun initialiseAt(lat: Double, lng: Double, heading: Float) {
        this.lat = lat; this.lng = lng; this.heading = heading; initialised = true
    }

    /** One smoothed step towards the target. */
    fun moveTo(lat: Double, lng: Double, heading: Float) {
        this.lat = lat; this.lng = lng; this.heading = heading
    }

    /**
     * Give the camera back to the rider once a manual pan has gone quiet.
     *
     * @return true when this call ended the pan, i.e. the frame after it is a follow frame.
     */
    fun releasePanIfIdle(nowMs: Long): Boolean {
        if (followMode || nowMs - lastManualPanAt <= MANUAL_IDLE_MS) return false
        panX = 0f; panY = 0f; followMode = true; panAtBound = false
        return true
    }
}
