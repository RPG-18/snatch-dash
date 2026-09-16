package com.opendash.opendash_dash_engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.PowerManager
import android.os.SystemClock
import com.opendash.opendash_dash_engine.dash.DashConfig
import com.opendash.opendash_dash_engine.dash.DashKeepAliveService
import com.opendash.opendash_dash_engine.dash.DashSession
import com.opendash.opendash_dash_engine.dash.DashState
import com.opendash.opendash_dash_engine.dash.DashWifiManager
import com.opendash.opendash_dash_engine.dash.WifiConnStatus
import com.opendash.opendash_dash_engine.dash.map.DashCamera
import com.opendash.opendash_dash_engine.dash.map.FrameRatePolicy
import com.opendash.opendash_dash_engine.dash.map.GeoPoint
import com.opendash.opendash_dash_engine.dash.map.LocationTracker
import com.opendash.opendash_dash_engine.dash.map.MapProjection
import com.opendash.opendash_dash_engine.dash.map.MapSnapshotProvider
import com.opendash.opendash_dash_engine.dash.map.MapStyleAssembler
import com.opendash.opendash_dash_engine.dash.map.MapTheme
import com.opendash.opendash_dash_engine.dash.map.OverlayRenderer
import com.opendash.opendash_dash_engine.dash.map.Percentiles
import com.opendash.opendash_dash_engine.dash.map.RenderStats
import com.opendash.opendash_dash_engine.dash.protocol.DashCommands
import com.opendash.opendash_dash_engine.dash.video.DashEncoder
import com.opendash.opendash_dash_engine.dash.video.NalProcessor
import com.opendash.opendash_dash_engine.dash.video.RtpPacketizer
import com.opendash.opendash_dash_engine.media.CallController
import com.opendash.opendash_dash_engine.media.CallInfoProvider
import com.opendash.opendash_dash_engine.media.MediaInfoProvider
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import com.opendash.opendash_dash_engine.util.ageMs
import com.opendash.opendash_dash_engine.util.memorySummary
import com.opendash.opendash_dash_engine.util.monotonicMs
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Top-level orchestrator for the native dash engine — the Flutter-side
 * counterpart to the original app's `DashViewModel`, scoped to what has to
 * stay native: WiFi pairing, the K1G protocol session, GPS, and the
 * off-screen render → H.264 encode → RTP loop.
 *
 * Dart owns navigation math (routing, off-route detection, ETA) and pushes a
 * compact "nav state" down via [setNavState] whenever it changes — see
 * [MethodCallHandler]. This controller's own tick loop only does what has to
 * run at frame rate for power efficiency: GPS-driven camera smoothing,
 * frame-signature caching (redraw only on change), and dynamic fps.
 */
class DashEngineController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onState: (Map<String, Any?>) -> Unit,
) {
    companion object {
        private const val TAG = "DashEngineController"
        private const val FPS_MOVING = 4
        private const val FPS_IDLE = 2
        private const val FORCE_REDRAW_MS = 2_000L
        private const val SMOOTH_TAU = 0.35

        /**
         * Frame-rate hysteresis in m/s of ground speed, and the shortest time a verdict
         * may stand — one GOP at [FPS_MOVING], so a bitrate retarget can never land more
         * often than the key frames it lands between. Why these are two numbers and not
         * one threshold: [FrameRatePolicy].
         */
        private const val CAM_MOVING_ENTER_MPS = 0.9   // 3.2 km/h
        private const val CAM_MOVING_EXIT_MPS = 0.5    // 1.8 km/h
        private const val CAM_MOVING_DWELL_MS = 2_000L

        // RTP_AU_SPREAD_MS (30 ms) lived here and is GONE as of 2026-09-11 — the reasoning
        // that produced it is kept in the sender loop in startStream, together with the
        // measurements that overturned it. Short version: it spread a key frame's 17-18
        // datagrams over 30 ms to keep any one of them from being lost in the burst, and
        // the 2026-09-10 ride made artifacts MORE frequent, not less, while showing the
        // send path was never under pressure to begin with.
        private const val MANUAL_IDLE_MS = 8_000L
        /** A fix older than this counts as "GPS lost" for the Dash screen's chip. */
        private const val GPS_FIX_STALE_MS = 4_000L
        /** Horizontal accuracy (m) above which a fix counts as "GPS weak". */
        private const val GPS_WEAK_ACCURACY_M = 25f
        // The K1G auth handshake occasionally fails on its own (seen in the wild — see
        // spec/wifi_retry_policy.md) even while the WiFi link to the dash is fine. Retrying
        // `session.connect()` directly is far cheaper than tearing down and re-requesting WiFi
        // (skips the whole WifiNetworkSpecifier dance, no system dialog risk) — bounded so a
        // genuinely dead dash still surfaces as ERROR instead of retrying forever.
        private const val MAX_AUTH_RETRIES = 4
        private const val AUTH_RETRY_DELAY_MS = 1_500L
        // Battery/annoyance backstop (see spec/wifi_retry_policy.md's "Из живого форка" —
        // ported from NorthStar's armReconnectGiveup): the WiFi/auth retry loops above are
        // individually unbounded by design (a rider stuck in a dead zone should keep trying),
        // but the whole connection attempt — from the very first `connect()` through every
        // automatic retry — must not run forever if it never reaches STREAMING. Two minutes
        // chosen to comfortably outlast a normal reconnect cycle (~38s WiFi + a few auth
        // retries) while still cutting off before it meaningfully drains the battery.
        private const val RECONNECT_GIVEUP_MS = 120_000L
        // How often [startStream]'s loop reports encoder/RTP output — the OTHER half of the
        // frame-decode-ack counter in DashSession: that one proves the dash decoded a frame,
        // this one proves we actually produced/sent one. Zero here while STREAMING means the
        // encoder itself stalled (this side); nonzero here but zero acks on the DashSession side
        // means frames leave the phone but the dash never confirms them — two different bugs in
        // two different files, previously indistinguishable without hex-grepping raw TX dumps.
        private const val ENCODER_LOG_INTERVAL_MS = 60_000L

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
        private const val ZOOM_MIN = 1000
        private const val ZOOM_MAX = 1500
        private const val ZOOM_STEP = 50

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
        private const val ZOOM_DEFAULT = 1500

        /** Hundredths → the units MapLibre's camera actually takes. */
        private const val ZOOM_SCALE = 100.0
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
        // How often the render budget is summarised into the ride log. Frequent
        // enough to catch a stretch of the ride, rare enough that the sort behind
        // the percentiles is free.
        private const val RENDER_LOG_INTERVAL_MS = 30_000L
    }

    private val dashConfig = DashConfig.get(context)
    private val wifiManager = DashWifiManager(context, scope)
    private val session = DashSession(scope)
    private val locationTracker = LocationTracker(context, scope)
    private val styleAssembler = MapStyleAssembler(context)

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
    private var zoomFloor = ZOOM_MIN
    private val snapshots = MapSnapshotProvider(context)
    private val overlays = OverlayRenderer()
    private val renderStats = RenderStats()
    private var toneGenerator: ToneGenerator? = null
    private val mediaInfo = MediaInfoProvider(context)
    private val callController = CallController(context)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /**
     * OK/Warm/Hot from [PowerManager.currentThermalStatus] (API 29, i.e. the project floor). Folded
     * into the encoder health log below — a hardware encoder throttling under heat is a
     * plausible, previously-uninstrumented explanation for the exact silent stall (0 frames,
     * no exception) the 2026-08-28 field session found. Ported from OpenMotoDash/NorthStar's
     * `updateThermal()` (see spec/wifi_retry_policy.md's "Из живого форка").
     */
    private fun thermalLabel(): String {
        val status = runCatching { powerManager?.currentThermalStatus }.getOrNull() ?: return "?"
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "OK"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            else -> "Hot" // SEVERE/CRITICAL/EMERGENCY/SHUTDOWN, and any future status value
        }
    }

    // Written on the main thread by [startStream] and read by the frame loop on
    // Dispatchers.Default (and rewritten there when it rebuilds a wedged
    // encoder) — @Volatile so neither side works off a cached reference.
    // [startStream] additionally joins the old loop before touching either, so
    // a released MediaCodec/recycled bitmap can never be in use concurrently.
    // The encoder is released by the frame loop itself, in its finally: nothing
    // else can know that the last renderFrame/drain has returned.
    @Volatile private var encoder: DashEncoder? = null
    @Volatile private var frameBitmap: Bitmap? = null
    private var streamJob: Job? = null
    private var sessionWatchJob: Job? = null
    private var wifiWatchJob: Job? = null
    private var mediaForwardJob: Job? = null
    private var callForwardJob: Job? = null
    // Bounded retry count for the cheap re-auth path in [connect]'s sessionWatchJob — see
    // MAX_AUTH_RETRIES's doc. Reset on READY (auth actually succeeded) and on every fresh
    // top-level [connect] call.
    private var authRetries = 0
    // True once the WiFi link came up and a session was started on it, cleared when the link
    // goes away again (and on every fresh [connect]). Both watch jobs are main-confined, so no
    // @Volatile: [wifiWatchJob] writes it, the session watcher reads it to tell "the link never
    // came up" apart from "the link is up and the session on it died". It says nothing about
    // *when* that session was started — see the re-check in the retry branch.
    private var sessionStarted = false
    // Armed whenever session.state isn't STREAMING, cancelled once it is — see
    // RECONNECT_GIVEUP_MS's doc.
    private var giveupJob: Job? = null

    /**
     * Per-minute `[mem]` sampler, tied to the CONNECTION rather than to the stream.
     *
     * Stream-scoped was the first attempt and it missed the point: the two LOW_MEMORY_KILLs
     * of 2026-09-14 both landed at `importance=400` (CACHED), i.e. between sessions, and a
     * sampler that only lives while STREAMING has nothing to say about the run-up. Spanning
     * the connection covers CONNECTING/AUTHENTICATING/READY as well, which is where the short
     * 5-12 s sessions of that ride spent most of their time.
     *
     * What it still cannot cover, and this is a limit of the design rather than an oversight:
     * once the app is genuinely cached there is no session, no foreground service and no open
     * ride file, so nothing here runs. The trend up to teardown plus [ExitInfoCollector]'s
     * reading at the exit is the whole of what is observable from inside the process.
     */
    private var memJob: Job? = null

    /**
     * Everything Dart pushes in that the frame loop reads — as ONE immutable value.
     *
     * These were eleven `@Volatile` fields, and the reason they are not any more is that a
     * frame reads several of them together while the main thread writes them one at a time.
     * Two of those splits were confirmed defects (pipeline.md §4.4):
     *
     *  - `setNavState` assigned `routePoints` and then `routeJam`. A frame landing between the
     *    two drew the NEW route coloured by the OLD traffic array. It did not crash only
     *    because [OverlayRenderer] checks `jam.size == segCount` and falls back to a solid
     *    line — a guard put there for "no traffic data", not for this, so the rider saw the
     *    route blink colour for one frame.
     *  - `setDestination` assigned `destLat` and then `destLng`, with no fallback at all: a
     *    frame in between placed the pin at the new latitude and the old longitude.
     *
     * Both are gone by construction now, not by a check: latitude and longitude live inside
     * one [Destination], points and jam inside one [RouteGeometry], and [tick] reads
     * `inputs.value` ONCE per frame. There is nothing left to tear.
     *
     * Written with [MutableStateFlow.update] rather than by assignment so a read-modify-write
     * cannot lose a concurrent one. Camera state is deliberately NOT here — see [zoom].
     */
    private val inputs = MutableStateFlow(DashInputs())

    // ── Camera state ──
    // Still @Volatile fields rather than part of [DashInputs], and that is a decision, not an
    // omission. The nav group above flows one way — Dart writes, the loop reads — which is
    // what makes an immutable snapshot the whole answer. Camera state flows BOTH ways: [tick]
    // itself resets pan and follow when the manual-pan idle expires, and [startStream] clamps
    // zoom to the pack's floor. Folding that into one value would mean the loop reading a
    // snapshot and writing a modified copy back, which is a read-modify-write across threads —
    // a race class that does not exist today.
    //
    // Not because camera state is free of the problem — it is not. [tick] reads [headingUp]
    // exactly once per frame and hands that value down, because it has to survive both the
    // signature hash and the snapshot suspension unchanged, and [panAtBound] below is written
    // from both threads without @Volatile. The difference is
    // that those are one-field problems with local fixes, while the nav group needed several
    // fields to agree with each other. Revisit when the frame loop moves out of this class
    // (pipeline.md §4.8), where the camera needs a home anyway.
    //
    // The first six are written by the MethodChannel handlers on the main
    // thread (joystick zoom/pan/recenter) and read by [tick] on
    // Dispatchers.Default, so they need @Volatile — otherwise a button press
    // can go unseen by the frame loop. The rest are touched only by the frame loop and by [startStream]
    // (which launches it, establishing happens-before), so plain fields.
    @Volatile private var zoom = ZOOM_DEFAULT
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var headingUp = true
    @Volatile private var followMode = true
    @Volatile private var lastManualPanAt = 0L
    // Whether [panBy] is currently pushing against a bound, so it can log the transition
    // instead of every call.
    //
    // Written from BOTH threads — [panBy] and [setFollowMode] on the platform thread, [tick]
    // on Dispatchers.Default when the manual-pan idle expires — and deliberately not
    // @Volatile. It gates a log line and nothing else, so the worst case is one bound-hit
    // message lost or repeated. Said out loud because the previous comment claimed
    // "MethodChannel thread only", which was wrong and would send the next reader hunting.
    private var panAtBound = false
    private var camLat = 0.0
    private var camLng = 0.0
    // These two are the exception to the note above: the frame loop writes them on
    // Dispatchers.Default while publishState() reads them from Main for the Dash
    // screen's compass. Without @Volatile that read is a data race — in practice a
    // stale or torn heading on the phone's own preview, which is cosmetic, but
    // "cosmetic race" is not a thing the memory model promises.
    @Volatile private var camHdg = 0f
    @Volatile private var camInit = false
    private var camMoving = false
    /** Owns the hysteresis and the flip counter; touched from the frame loop only. */
    private val frameRate =
        FrameRatePolicy(CAM_MOVING_ENTER_MPS, CAM_MOVING_EXIT_MPS, CAM_MOVING_DWELL_MS)

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
     * Down here with the frame-loop-only fields, not up with the camera controls
     * it is built from: it is written by [logCameraSend] on Dispatchers.Default
     * and by [startStream] before the loop launches, so it needs no @Volatile.
     */
    private var lastCameraLogKey: String? = null

    /** Destination rect for the snapshot upscale; reused, this runs 4 times a second. */
    private val frameRect = Rect(0, 0, DashEncoder.WIDTH, DashEncoder.HEIGHT)

    /** See the note at its use — the bilinear filter is the whole reason it exists. */
    private val upscalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastTickNs = 0L
    private var lastSignature = ""
    private var lastRedrawAt = 0L
    // Whether [frameBitmap] holds a real frame yet, i.e. one snapshot has landed
    // since [startStream]. Until then there is nothing to encode — see the gate in
    // the frame loop.
    private var haveFrame = false
    // Which snapshotter this stream prepared, so [disconnect] releases that one
    // and not whatever a later connection has since put in its place.
    private var snapshotGeneration = 0L

    var onButton: ((Int) -> Unit)? = null

    /**
     * Set while the debug screen is on screen; null the rest of the time.
     *
     * **The null check is the feature.** Handing the composed frame to Dart means
     * compressing 526×300 to PNG on the frame thread, four times a second, and
     * a ride pays nothing for a screen nobody is looking at — the whole premise
     * of this app is that the phone rides with its display off. So the cost is
     * bought only by an attached listener, and detaching stops it dead.
     *
     * PNG, not JPEG: the screen exists to look at frame sharpness (see
     * spec/dash_screen_debug.md), and a lossy codec in the path would be
     * answering the question with its own artefacts.
     */
    @Volatile var onFramePreview: ((Map<String, Any?>) -> Unit)? = null

    /** Size the encoder produced for the most recent frame — one of the debug screen's numbers. */
    @Volatile private var lastEncodedBytes = 0

    /** Frames handed to the encoder since [startStream]; mirrors its local counter. */
    @Volatile private var framesSentTotal = 0

    // ── Public API (invoked by the plugin's MethodChannel handler) ────────

    fun connect() {
        // Idempotent on a live connection, which spec/fsm.md already promises for the
        // `idleMap → navigating` transition ("connect() — no-op"): only DashSession.connect()
        // had that guard, this method had none and ran the whole cycle again on a connected
        // dash. That re-request tore down the WiFi link the running session's sockets are bound
        // to (see DashWifiManager.connect's own guard), reset [hasConnectedOnce] and
        // [authRetries], opened a second ride file mid-ride and restarted media forwarding —
        // for a rider, "Send to Dash" killed the picture ~10s later. Nothing is lost by
        // returning here: [setDestination] pushes the new destination to the session itself.
        val sessionState = session.state.value
        val sessionLive = sessionState != DashState.IDLE && sessionState != DashState.ERROR
        val linkStatus = wifiManager.state.value.status
        if (sessionLive && linkStatus == WifiConnStatus.CONNECTED) {
            DebugLog.i(TAG) { "connect() ignored — session already $sessionState on a live WiFi link" }
            RideDiagnostics.log("connect", "connect() ignored — already $sessionState, link up")
            return
        }
        // A live session whose link is NOT up is the other half of the guard above, and it
        // needs the opposite treatment: not "we are already connected", but the wreck of the
        // previous session, still holding sockets bound to a network that is gone.
        //
        // Nothing below would clean it up. This method resets [sessionStarted] to false and
        // starts a fresh wifi collector, which StateFlow immediately hands its current value —
        // REQUESTING, not CONNECTED. The "link came up" branch needs CONNECTED; the teardown
        // branch needs sessionStarted; neither fires, so `session.disconnect()` is never
        // called and `session.connect()` later bounces off its own IDLE/ERROR guard. The old
        // session then pushes RTP into a dead socket until the RX watchdog notices
        // (RX_IDLE_TIMEOUT_MS = 10s) and the retry after it adds AUTH_RETRY_DELAY_MS: about
        // 11.5 seconds of frozen picture on the dash after a "Send to Dash".
        //
        // Reachable because both ends land on Main: DashWifiManager's onLost/onUnavailable
        // (requestNetwork is given a main-looper Handler) flip WifiState and return, while the
        // collector that would tear the session down is a separate coroutine resumed on the
        // next dispatch — and a method-channel call from Dart gets in between. Same treatment
        // the collector's own else-branch gives, just from the path that overtook it.
        if (sessionLive) {
            RideDiagnostics.warn(
                "connect",
                "connect() over a $sessionState session whose link is $linkStatus — tearing it down first",
            )
            session.disconnect()
        }
        RideDiagnostics.init(context)
        RideDiagnostics.start("connect")
        RideDiagnostics.log(
            "connect",
            "ssid='${dashConfig.ssid}' needsDiscovery=${dashConfig.needsDiscovery} " +
                "dest=${inputs.value.destName}",
        )
        DashKeepAliveService.start(context)
        // Started here, right after the ride file opens, so its lines cannot land in the
        // previous ride's file — and restarted with every connect(), so a reconnect gets a
        // fresh baseline rather than a continuation of the last one. On IO, never from the
        // frame loop: Debug.getMemoryInfo walks /proc/self/smaps and costs tens of
        // milliseconds against a 250 ms budget. See [memJob].
        memJob?.cancel()
        memJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Guarded, because an unguarded throw here is silent: the scope's
                // SupervisorJob plus its handler would swallow it and this job would simply
                // stop, taking [mem] out for the rest of the ride with nothing in the file
                // saying so. Every other periodic reporter on this path is wrapped; this one
                // was the exception. Review, 2026-09-15.
                runCatching { RideDiagnostics.log("mem", memorySummary(context)) }
                    .onFailure { DebugLog.w(TAG) { "memory probe failed: ${it.message}" } }
                delay(ENCODER_LOG_INTERVAL_MS)
            }
        }
        session.onButton = { code -> onButton?.invoke(code.toInt() and 0xFF) }
        session.onError = { msg -> publishState(errorMessage = msg) }
        locationTracker.start()
        authRetries = 0

        val ssid = dashConfig.ssid
        // A fresh attempt gets a fresh window. [armGiveupTimer] no-ops while a timer is
        // already running, and nothing between here and there cancels one — so without
        // this line a reconnect inherits whatever is left of the previous attempt's
        // countdown, and a tap at t=115s of a 120s window is killed five seconds in,
        // reported as "gave up — 120000ms without reaching STREAMING". The timer is
        // re-armed by the session collector below on the first non-STREAMING state.
        cancelGiveupTimer()
        wifiWatchJob?.cancel()
        sessionStarted = false
        wifiWatchJob = scope.launch {
            wifiManager.onSsidResolved = { resolved ->
                if (dashConfig.needsDiscovery) dashConfig.ssid = resolved
            }
            if (ssid.isNotBlank()) {
                wifiManager.connect(ssid, dashConfig.password, prefixMatch = false)
            } else {
                wifiManager.connect(dashConfig.ssidPrefix, dashConfig.password, prefixMatch = true)
            }
            wifiManager.state.collect { wifi ->
                RideDiagnostics.log(
                    "wifi",
                    wifi.status.toString() +
                        (wifi.ssid.takeIf { it.isNotBlank() }?.let { " ssid=$it" } ?: "") +
                        (wifi.error?.let { " err=$it" } ?: ""),
                )
                publishState()
                if (wifi.status == WifiConnStatus.CONNECTED && !sessionStarted) {
                    sessionStarted = true
                    val resolvedSsid = if (ssid.isNotBlank()) ssid else wifi.ssid
                    session.connect(resolvedSsid, wifiManager.network)
                } else if (wifi.status != WifiConnStatus.CONNECTED && sessionStarted) {
                    // The network the running session's sockets are bound to (via
                    // Network.bindSocket) is gone — whether WifiManager is about to retry
                    // (REQUESTING) or has given up (ERROR), that session is now sending
                    // into a dead network and will never notice on its own (see
                    // DashSocket.send/sendRtp — failures there are swallowed, not fatal).
                    // Tear it down so the `connect()` branch above starts a fresh one,
                    // bound to whatever network reconnect eventually resolves.
                    sessionStarted = false
                    session.disconnect()
                }
            }
        }

        // Tracked and replaced like [wifiWatchJob]: `connect()` is called again
        // on every "Send to Dash" and on the Dash screen's connect button, and
        // an untracked collector here survived both `disconnect()` and the next
        // `connect()` — so the Nth session hit READY with N live collectors and
        // ran [startStream] N times over.
        sessionWatchJob?.cancel()
        sessionWatchJob = scope.launch {
            session.state.collect { st ->
                RideDiagnostics.log("session", "→ $st")
                publishState()
                if (st == DashState.STREAMING) cancelGiveupTimer() else armGiveupTimer()
                when (st) {
                    // Guarded: assembleCurrent() reads the pack directory and
                    // prepare() parses the style, and either can throw. Uncaught,
                    // the exception kills this collector, and the session then sits
                    // in READY with no frame loop behind it until the 120-second
                    // give-up timer fires — a dash showing nothing while the app
                    // insists it is connected.
                    DashState.READY -> {
                        authRetries = 0
                        try {
                            startStream()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            DebugLog.e(TAG, { "startStream failed — disconnecting" }, e)
                            RideDiagnostics.log("stream", "startStream failed: ${e.message}")
                            disconnect()
                        }
                    }
                    // The K1G handshake failed but the WiFi link itself is still up — no need
                    // to tear down and re-request WiFi (which risks the system dialog, see
                    // spec/wifi_retry_policy.md's "Из живого форка"). Just retry the handshake
                    // directly, same network, bounded so a genuinely dead dash still ends in
                    // ERROR rather than retrying forever.
                    //
                    // IDLE alongside ERROR, and for the same reason: DashSession.endLink puts a
                    // link that died under a live WiFi network into IDLE, not ERROR (its own doc
                    // explains why — the state guard in DashSession.connect must let the retry
                    // through). That is spec/wifi_retry_policy.md's scenario C, the RX watchdog
                    // firing while WifiManager still reports CONNECTED, so [wifiWatchJob] never
                    // sees a state change and nothing at all reconnected: the ride ended at the
                    // 120-second give-up timer. A deliberate disconnect() never reaches here at
                    // all — it cancels this job before it touches the session.
                    //
                    // Both checks are made AFTER the wait, and the counter is spent only if the
                    // retry actually happens, because the value that opens this branch can be
                    // stale before the delay even starts: StateFlow replays its current value to
                    // a brand-new collector, and on a `connect()` over an already-live WiFi link
                    // that value is the PREVIOUS attempt's ERROR. Re-reading the state after the
                    // delay is what tells the two apart: a session that really is dead is still
                    // IDLE/ERROR, a replayed one has moved on.
                    //
                    // The window used to be wider: DashSession.connect() published CONNECTING
                    // from inside its coroutine on Dispatchers.IO, so this collector could see
                    // the corpse of a session [wifiWatchJob] had already restarted, and cancel a
                    // handshake 1.5 s into its life every single time. CONNECTING is published
                    // synchronously now, which closes that half of it.
                    DashState.ERROR, DashState.IDLE -> {
                        val wifi = wifiManager.state.value
                        if (sessionStarted && wifi.status == WifiConnStatus.CONNECTED && authRetries < MAX_AUTH_RETRIES) {
                            delay(AUTH_RETRY_DELAY_MS)
                            val settled = session.state.value
                            if (wifiManager.state.value.status == WifiConnStatus.CONNECTED &&
                                (settled == DashState.IDLE || settled == DashState.ERROR)
                            ) {
                                authRetries++
                                session.connect(wifi.ssid, wifiManager.network)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        startMediaForwarding()
    }

    fun disconnect() {
        RideDiagnostics.log("connect", "disconnect() called")
        // No farewell [mem] sample here, and that IS the decision — one stood here for a day
        // and came out. It cannot be taken on this thread (memorySummary walks
        // /proc/self/smaps: tens of milliseconds of UI stall, on the low-RAM phone the probe
        // exists for, against its own KDoc), and off this thread it cannot be ordered against
        // the file closing: [RideDiagnostics.stop] below is synchronous and nulls the file, so
        // the line was dropped in the common case, never ran at all from dispose() — the plugin
        // cancels the scope two statements after this returns — and on the give-up path could
        // land in the NEXT ride file carrying that session's +NNNms origin. Moving stop() into
        // the same coroutine would fix the ordering and lose the wifi session summary written
        // between here and there.
        //
        // Nothing is actually lost. The reading it was for — the footprint once the snapshotter
        // is released — is the first [mem] line of the next session, logged ~100 ms into
        // connect() before anything is allocated again. Review, 2026-09-16.
        memJob?.cancel(); memJob = null
        giveupJob?.cancel(); giveupJob = null
        // Cancelled but deliberately NOT nulled, unlike every other job here. cancel() is
        // cooperative: the frame loop keeps running on Dispatchers.Default until its next
        // suspension point, and its finally releases the encoder and nulls the field. Dropping
        // the reference is what let the NEXT [startStream] skip its cancelAndJoin() — it would
        // see null, join nothing, and install a fresh encoder that the still-unwinding old loop
        // then released out from under it, leaving `encoder` null for the whole session: a
        // frozen dash with no error anywhere, since the session does reach STREAMING and the
        // give-up timer is cancelled. Keeping the reference costs nothing (joining an already
        // finished Job returns at once) and restores the invariant [startStream] documents.
        streamJob?.cancel()
        sessionWatchJob?.cancel(); sessionWatchJob = null
        wifiWatchJob?.cancel(); wifiWatchJob = null
        sessionStarted = false
        stopMediaForwarding()
        session.disconnect()
        wifiManager.disconnect()
        locationTracker.stop()
        // The encoder is NOT released here. cancel() above is cooperative — the frame
        // loop runs on Dispatchers.Default and only stops at its next suspension point,
        // so releasing from this thread raced with renderFrame/drain on a dead
        // MediaCodec: an IllegalStateException storm, and at failures >= 3 the loop
        // would rebuild an encoder nobody owns any more. [startStream] already fixed
        // its half of that race with cancelAndJoin(); this half is fixed by ownership
        // instead — the loop releases the encoder in its own finally as it unwinds,
        // which is the one place where "nothing is drawing into it" is guaranteed.
        // Joining from here is not an option: this runs on the main thread and the
        // loop can be suspended inside a snapshot, which needs that same thread.
        // The style — theme and set of packs — is read once per stream, so the
        // snapshotter is per-stream too and goes away with it. By generation, not
        // "whatever is current": this does not wait, and a fast reconnect could
        // otherwise have it free the snapshotter the NEXT stream just prepared —
        // after which every frame silently returns null and the map freezes.
        val generation = snapshotGeneration
        // Synchronously, not through the scope: `dispose()` reaches this method and
        // the plugin cancels the scope on the line after it, so a launched
        // release would simply never run. This method is main-thread only, which
        // is what makes the direct call legal.
        snapshots.releaseNow(generation)
        DashKeepAliveService.stop(context)
        RideDiagnostics.stop("disconnect")
        // explicitDisconnect=true distinguishes this from every other publishState() call (all
        // of which leave it false/absent) — Dart's connection-lost voice alert uses it to tell
        // "rider asked to disconnect" apart from "session died on its own", which otherwise both
        // surface as the same DashStage.idle/error and would otherwise fire a spurious alert.
        publishState(explicitDisconnect = true)
    }

    /** Start the give-up countdown if it isn't already running (idempotent) — see
     *  RECONNECT_GIVEUP_MS's doc. */
    private fun armGiveupTimer() {
        if (giveupJob?.isActive == true) return
        giveupJob = scope.launch {
            delay(RECONNECT_GIVEUP_MS)
            if (session.state.value != DashState.STREAMING) {
                DebugLog.w(TAG) { "Giving up — ${RECONNECT_GIVEUP_MS}ms without reaching STREAMING" }
                RideDiagnostics.log("error", "gave up — ${RECONNECT_GIVEUP_MS}ms without reaching STREAMING")
                disconnect()
            }
        }
    }

    private fun cancelGiveupTimer() { giveupJob?.cancel(); giveupJob = null }

    /**
     * Forwards the phone's now-playing/incoming-call state to the dash via
     * `DashSession.updateNowPlaying`/`updateCall`, so it shows up in the
     * dash's own media/call cards. Ported from `DashViewModel.startMediaForwarding`.
     * No-ops silently if notification access hasn't been granted — see
     * [isNotificationAccessGranted].
     */
    private fun startMediaForwarding() {
        mediaInfo.start()
        mediaForwardJob?.cancel()
        mediaForwardJob = scope.launch {
            mediaInfo.nowPlaying.collect { np ->
                session.updateNowPlaying(np?.title, np?.album.orEmpty(), np?.artist.orEmpty())
                inputs.update { it.copy(nowPlayingTitle = np?.title) }
                publishState()
            }
        }
        callForwardJob?.cancel()
        callForwardJob = scope.launch {
            CallInfoProvider.incomingCall.collect { call ->
                // Filtered to ringing calls only, same as the line above — an
                // active/outgoing call has nothing to "answer", so surfacing it
                // here would show a nonsensical answer button in the dash UI.
                val incoming = call?.takeIf { it.incoming }
                session.updateCall(incoming?.caller)
                inputs.update {
                    it.copy(incomingCaller = incoming?.caller, hasActiveCall = call != null)
                }
                publishState()
            }
        }
    }

    private fun stopMediaForwarding() {
        mediaForwardJob?.cancel(); mediaForwardJob = null
        callForwardJob?.cancel(); callForwardJob = null
        mediaInfo.stop()
    }

    fun setDestination(name: String?, lat: Double?, lng: Double?) {
        // One update, so a frame can never see half a destination — see Destination's doc.
        inputs.update {
            it.copy(
                // [destName] separately from [dest], and not folded into it: the old field
                // was assigned unconditionally, so setDestination(name, null, null) kept the
                // name while clearing the coordinates. No Dart caller does that today, but
                // the method channel allows it and the connect diagnostic reads the name.
                // Dropping it would have been a silent behaviour change. Review, 2026-09-14.
                destName = name,
                dest = if (lat != null && lng != null) Destination(lat, lng) else null,
                navigating = lat != null && lng != null,
            )
        }
        session.updateRouteCard(name ?: "OpenDash")
        // DashSession reads [DashInputs.navigating] off the state stream for its own
        // chrome/nav-info decisions — push immediately instead of waiting for
        // the next frame-loop tick() so "Send to Dash" takes effect at once.
        publishState()
    }

    fun clearDestination() {
        inputs.update {
            it.copy(destName = null, dest = null, navigating = false, route = RouteGeometry())
        }
        session.updateRouteCard("OpenDash")
        publishState()
    }

    /**
     * Compact nav-state push from Dart's NavEngine — see class doc. [points]
     * is the route geometry; Dart only needs to send it once when the route
     * is (re)computed — an empty list here means "keep the geometry already
     * held", so the 1 Hz progress tick doesn't have to resend it every call.
     * [jamSegments] rides along with [points] under the same rule (only
     * applied when [points] is non-empty) — see [RouteGeometry.jam]'s doc.
     */
    fun setNavState(
        remainingMeters: Double?,
        nextTurnMeters: Double?,
        maneuver: Int,
        etaHHMM: String?,
        isOffRoute: Boolean,
        points: List<GeoPoint>,
        jamSegments: List<Int> = emptyList(),
    ) {
        inputs.update { cur ->
            cur.copy(
                remainingM = remainingMeters,
                offRoute = isOffRoute,
                // Points and jam land together or not at all. A mismatched length still
                // means stale/missing traffic data and OverlayRenderer's solid-line
                // fallback still catches it (spec/yande_ruote.md) — but that fallback is
                // now for missing data only, never for a race.
                route = if (points.isEmpty()) {
                    cur.route
                } else {
                    RouteGeometry(
                        points = points,
                        jam = if (jamSegments.size == points.size - 1) jamSegments else emptyList(),
                    )
                },
            )
        }
        if (remainingMeters != null && nextTurnMeters != null) {
            val (pv, pu) = toDashDistance(nextTurnMeters)
            val (tv, tu) = toDashDistance(remainingMeters)
            session.updateNavInfo(maneuver, pv, pu, tv, tu, etaHHMM)
        }
    }

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
        val maxX = DashEncoder.WIDTH * DashCamera.MAX_PAN_FRACTION
        val maxY = DashEncoder.HEIGHT * DashCamera.MAX_PAN_FRACTION
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
        zoom = (zoom + delta).coerceIn(zoomFloor, ZOOM_MAX)
        RideDiagnostics.log(
            "camera",
            if (zoom != before) "$action ${zoomText(before)}→${zoomText(zoom)}"
            else "$action ignored — already at ${if (delta > 0) "ZOOM_MAX" else "ZOOM_MIN"} (${zoomText(before)})",
        )
    }

    /**
     * Hundredths as a zoom a reader recognises: 1175 → "11.75".
     *
     * Assembled by hand rather than with `"%.2f".format`, which takes the default
     * locale and writes "11,75" on the Russian device this runs on — a decimal
     * comma inside log lines that separate other things with commas.
     */
    private fun zoomText(hundredths: Int) =
        "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"

    /**
     * A coordinate for the log: five decimals, about a metre, and a decimal POINT.
     *
     * `Locale.ROOT` for the same reason [zoomText] avoids `format` entirely — the
     * default locale on this device writes "60,24647", and a comma is what
     * separates the pair.
     */
    private fun geoText(v: Double) = String.format(Locale.ROOT, "%.5f", v)

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

    fun forgetDash() { dashConfig.forgetDash() }
    fun setSsid(ssid: String) { dashConfig.ssid = ssid.trim() }
    fun setWifiPassword(password: String) { dashConfig.password = password }

    fun updateNowPlaying(title: String?, album: String, artist: String) =
        session.updateNowPlaying(title, album, artist)

    fun updateCall(caller: String?) = session.updateCall(caller)

    /** Answer the current ringing call — requires ANSWER_PHONE_CALLS (API 26+). */
    fun answerCall(): Boolean = callController.answer()

    /** End the current call — requires ANSWER_PHONE_CALLS (API 28+). */
    fun hangupCall(): Boolean = callController.hangup()

    fun skipNext(): Boolean = mediaInfo.skipNext()
    fun skipPrevious(): Boolean = mediaInfo.skipPrevious()

    /** Whether the phone has granted OpenDash notification-listener access
     *  (required for [mediaInfo]/[CallInfoProvider] to see anything). */
    fun isNotificationAccessGranted(): Boolean = MediaInfoProvider.isAccessGranted(context)

    fun openNotificationAccessSettings() {
        context.startActivity(MediaInfoProvider.accessSettingsIntent())
    }

    /** Turn-guidance chime for [VoiceMode.CHIME] — `ToneGenerator` has no Dart/Flutter
     *  equivalent, so this stays behind the plugin; ported from `VoiceManager.chime()`. */
    fun playChime() {
        runCatching {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { toneGenerator = it }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
        }.onFailure { DebugLog.w(TAG) { "chime failed: ${it.message}" } }
    }

    fun currentConfig(): Map<String, Any?> = mapOf(
        "ssidPrefix" to dashConfig.ssidPrefix,
        "ssid" to dashConfig.ssid,
        "password" to dashConfig.password,
        "needsDiscovery" to dashConfig.needsDiscovery,
    )

    fun dispose() {
        disconnect()
        runCatching { toneGenerator?.release() }
    }

    // ── Streaming / render loop ─────────────────────────────────────────

    /**
     * Suspends rather than fire-and-forget so the previous frame loop is *joined*
     * before this one installs a new encoder and frame bitmap — cancelling it
     * afterwards (as this used to) left the old loop calling `renderFrame`/`drain`
     * on a dead MediaCodec for one more frame, which surfaced as an
     * IllegalStateException storm and a pointless encoder rebuild. The join also
     * guarantees the old loop's finally has already released and cleared the
     * encoder field by the time the assignment below runs. Only called from the
     * [sessionWatchJob] collector, which is already a suspend context.
     */
    private suspend fun startStream() {
        RideDiagnostics.log("stream", "startStream — encoder up, RTP→dash beginning")
        // Encoder/RTP output counters for the periodic health log below — reset per
        // startStream() call, same lifetime as everything else here (streamJob/encoder).
        var framesEncoded = 0
        var idrFramesEncoded = 0
        val rtpPacketsSent = AtomicLong(0)
        // Bytes, not just packets. The packet count alone cannot answer the only
        // question that matters about this stream — are we inside the dash's own
        // ~200 kbps profile (see DashEncoder.BITRATE) — because a packet is
        // anywhere from a few bytes to 1392. On 2026-09-06 that left the measured
        // rate somewhere between 168 and 470 kbps, which is the difference between
        // "fine" and "twice the dash's profile", and nothing in the log could
        // narrow it.
        val rtpBytesSent = AtomicLong(0)
        // Frame size and the datagram cost of a key frame — the two numbers the audit
        // (§4.6) asks for and the ride file has never carried: the only frame size
        // anywhere in it is "first video frame sent", once per session. They decide what
        // a rider sees after ONE lost datagram, because a key frame is undecodable
        // unless every one of its fragments arrives. Same [Percentiles] as the render
        // stats, drained into the same minute.
        val frameBytes = Percentiles()
        val idrDatagrams = Percentiles()
        // One access unit per element, not one packet. The original reason was pacing —
        // the sender had to know the burst size before its first packet — and the pacing
        // is gone as of 2026-09-11. The grouping stays because drop accounting needs it:
        // an overflow has to discard a WHOLE frame and know whether it was a key frame
        // ([rtpDroppedIdr]), and half an access unit on the wire is worth nothing to the
        // decoder anyway.
        //
        // FOUR, not the 64 this was first written with. A deep queue does not protect a
        // realtime stream, it hides the stall and converts it into latency: 64 AUs is
        // ~16 s of video at 4 fps, so a blocked socket would have kept `drop=0` in the
        // ride file while the dash showed a map from a quarter-minute ago — worse than a
        // gap, and invisible in exactly the file that exists to make it visible. One AU
        // is a few ms of airtime out of a 250 ms budget, so four is already a second
        // of slack for a GC pause; past that, dropping and SAYING so is the honest
        // failure. The render loop still never waits on the network — trySend, never send.
        val rtpOutbox = Channel<List<ByteArray>>(capacity = 4)
        val rtpDropped = AtomicInteger(0)

        /**
         * Dropped access units that were key frames, counted apart from [rtpDropped].
         *
         * The two cost different things and want different reactions. A dropped P-frame is
         * one frame the dash never sees — at 4 fps, a quarter second. A dropped key frame is
         * the whole GOP: every P-frame after it references a picture the decoder does not
         * have, so the dash shows garbage until the next IDR, which at a 2 s interval is
         * eight frames later. A single `drop=` number cannot tell "we lost a quarter second"
         * from "we lost two seconds and showed mush for them".
         */
        val rtpDroppedIdr = AtomicInteger(0)

        /**
         * Gate 0 of pipeline.md: what each `drain()` actually yielded.
         *
         * [drainMiss] counts iterations where the frame just rendered did not come out within
         * DRAIN_TIMEOUT_US and was left for a later one; [drainDouble] counts iterations that
         * yielded two or more frames, which is the same event seen from the other end.
         *
         * **What they measured, and what they measure now.** Until 2026-09-15 a double meant
         * two access units went out with the SAME RTP timestamp, because the clock advanced
         * per iteration rather than per frame — and that was the one telemetry difference
         * between a Huawei whose map kept freezing (drainMiss median 22 a minute) and a Xiaomi
         * whose map did not (0 in 73 of 92 windows). Since the clock moved into `onEncoded`
         * the stamps are distinct and evenly spaced whatever the codec does, so these two now
         * report a property of the ENCODER and nothing about what reaches the dash:
         * `OMX.hisi.video.encoder.avc` misses 5-20% of its windows, `c2.mtk.avc.encoder`
         * almost none. Still worth watching — a rise means the codec is falling behind — but
         * a nonzero is no longer a defect in the stream.
         *
         * This existed BEFORE any rework of the encode path, on purpose, and that paid off:
         * the reading redirected the fix from pipeline.md's stage 6 (async MediaCodec, a
         * rewrite of the live encoder) to three lines in the callback. The three attempts
         * before it — RTP pacing, DSCP, a 256 KiB SO_SNDBUF — were reasoned rather than
         * measured, all wrong in the field, and the two that shipped made the picture worse.
         */
        val drainMiss = AtomicInteger(0)
        val drainDouble = AtomicInteger(0)
        // Filled by the packetizer callback during one nalProc.process() call, then handed
        // to the outbox as a unit. Only ever touched from the frame-loop coroutine.
        val auPackets = ArrayList<ByteArray>(32)
        // Which profile the encoder is currently on, so it is poked only on a
        // transition rather than every frame.
        var idleBitrate = false
        // One-shot timing, paired with DashSession's own "dash DECODED first IDR" line — the
        // gap between the two is exactly the dash's own decode latency for this session. Ported
        // idea from OpenMotoDash/NorthStar's `loggedFirstFrame` (see
        // spec/wifi_retry_policy.md's "Из живого форка").
        var loggedFirstFrame = false
        // Monotonic RTP presentation clock — advanced by the INTENDED frame interval (see the
        // loop below), NOT System.currentTimeMillis(). Ported from OpenMotoDash/NorthStar's
        // `videoPtsMs` (see spec/wifi_retry_policy.md's "Из живого форка" for how that fork was
        // found). Originally motivated by a 2026-08-29 field session that read the ACK-counter
        // above sitting at 0 for 66 of 67 sampled minutes as "dash stopped decoding video" — a
        // reading a LATER 2026-08-29 field report (map updated fine that whole ride) falsified;
        // see spec/video.md's "09 06/04 55 — НЕ ack на каждый кадр" for the correction. Keeping
        // this change anyway: a monotonic PTS instead of one carrying render/encode/GC jitter is
        // more correct RTP practice regardless, just not proven to fix anything real here.
        var videoPtsMs = 0L

        /**
         * How far [videoPtsMs] moves per FRAME — set by the loop, consumed by `onEncoded`.
         *
         * It lives out here because the callback runs inside `enc.drain()` and cannot see the
         * loop's `frameIntervalMs`. Plain `var` for the same reason [videoPtsMs] is: `drain()`
         * invokes the callback synchronously on the frame loop's own coroutine, so both are
         * single-threaded.
         *
         * A frame drained late therefore carries the step in force when it came OUT, not when
         * it was rendered. Across an fps flip that shifts one frame's spacing once; the old
         * scheme mis-stamped that same frame more coarsely, and neither is worth carrying a
         * per-frame step around for.
         */
        var ptsStepMs = 1000L / FPS_IDLE

        // Collects rather than sends. The frame loop runs on Dispatchers.Default, and
        // DatagramSocket.send is a syscall that blocks when the Wi-Fi driver's queue is
        // full — so the old shape put the render loop's deadline at the mercy of the
        // radio, and gave the burst nowhere to be paced from (audit §4.1).
        val packetizer = RtpPacketizer { rtpPkt -> auPackets += rtpPkt }
        // endOfAU comes from NalProcessor, which knows which NAL closes the access unit —
        // this used to be hardcoded `true`, marking every packet. Harmless while each AU
        // was exactly one NAL, but wrong the moment an IDR goes out as separate
        // SPS/PPS/IDR packets: the marker bit has to land on the last one only.
        val nalProc = NalProcessor { nal, endOfAU ->
            packetizer.packetize(nal, endOfAU = endOfAU, ptsMs = videoPtsMs)
        }
        val onEncoded: (ByteArray, Boolean, Boolean) -> Unit = { annexB, isKey, isConfig ->
            // The SPS/PPS buffer goes to the packetizer like any other but is not a
            // frame: counting it would put 30 bytes of parameter sets in front of
            // anyone reading "size of the last frame", once per session, at exactly
            // the moment they start looking.
            if (!isConfig) {
                // Advanced HERE, per frame that actually came out of the codec, and not once
                // per loop iteration as it was until 2026-09-15. Before `nalProc.process`
                // below, which runs the packetizer synchronously and reads [videoPtsMs].
                //
                // The defect this fixes, measured on the ride of that date: when the encoder
                // misses its DRAIN_TIMEOUT_US window the frame comes out on the NEXT
                // iteration — by which time the old code had already advanced the clock — so
                // it carried that iteration's stamp, and if the next frame made it too, two
                // access units went out stamped identically. On the Huawei's
                // OMX.hisi.video.encoder.avc that was 5-20% of frames (drainMiss median 22 a
                // minute); on the Xiaomi's c2.mtk.avc.encoder it was 0 in 73 of 92 windows.
                // The Huawei is also the phone whose map kept freezing while the Xiaomi's kept
                // updating, with drop=, wedged=, timeouts= and link losses identical and clean
                // on both — this was the only telemetry difference between them.
                //
                // Counting frames instead of iterations makes the stamps monotonic and evenly
                // spaced by construction, which is the property videoPtsMs was introduced for
                // and did not actually have. The misses themselves remain; they belong to the
                // encoder, and RFC 6184's 90 kHz clock does not care when a frame was handed
                // over, only that distinct frames carry distinct instants.
                videoPtsMs += ptsStepMs
                framesEncoded++
                lastEncodedBytes = annexB.size
                framesSentTotal = framesEncoded
                frameBytes.add(annexB.size.toLong())
                if (isKey) idrFramesEncoded++
            }
            if (!loggedFirstFrame && !isConfig) {
                loggedFirstFrame = true
                RideDiagnostics.log("stream", "first video frame sent (key=$isKey, ${annexB.size}B)")
            }
            // process() runs the packetizer synchronously on this coroutine, so when it
            // returns [auPackets] holds exactly this access unit — which is both the
            // number the ride file wants and the group the sender has to pace as one.
            auPackets.clear()
            nalProc.process(annexB)
            if (auPackets.isNotEmpty()) {
                if (isKey && !isConfig) idrDatagrams.add(auPackets.size.toLong())
                // Never `send`: this runs inside the render loop, and a full outbox must
                // cost a dropped frame, not a late one. A codec-config buffer emits no
                // packets at all (SPS/PPS are cached, not sent), hence the guard.
                if (rtpOutbox.trySend(auPackets.toList()).isFailure) {
                    rtpDropped.incrementAndGet()
                    if (isKey && !isConfig) rtpDroppedIdr.incrementAndGet()
                }
            }
        }

        streamJob?.cancelAndJoin()
        streamJob = null

        // No release() needed first: the joined loop released its own encoder and
        // nulled the field on the way out (see its finally below).
        //
        // Between here and the launch below the encoder has no owner: the frame
        // loop's finally is what releases it, and the loop does not exist yet.
        // Anything in between can throw — assembleCurrent() reads external
        // storage, prepare() parses the style — and since the collector now
        // catches that and retries, each attempt would strand a configured
        // MediaCodec and its input Surface. So this stretch cleans up after
        // itself.
        encoder = DashEncoder(onEncoded).also { it.prepare() }
        try {

        // The previous stream's bitmap is ~631 KB in the native heap (API 26+), where
        // the Java GC feels no pressure from it and would leave the free to a
        // finalizer. Dropping the reference per stream added up across a ride of
        // reconnects without ever raising a Java OOM.
        runCatching { frameBitmap?.recycle() }
        frameBitmap = Bitmap.createBitmap(DashEncoder.WIDTH, DashEncoder.HEIGHT, Bitmap.Config.ARGB_8888)
        lastSignature = ""
        camInit = false; lastTickNs = 0L
        haveFrame = false
        windowMovedM = 0.0
        // Cleared so every session's file opens with the camera it started on.
        // The zoom a rider left behind survives the disconnect (the field does),
        // and without this the one line saying what it is would be in the PREVIOUS
        // session's file.
        lastCameraLogKey = null

        // Theme and the set of packs are read HERE and nowhere else for the rest
        // of the ride. That is what makes a mid-ride style reload impossible by
        // construction rather than by interface discipline: swapping the style
        // means a full reparse — every source and layer dropped and rebuilt —
        // and a blank map on the move (spec/drawing_from_local_tiles.md,
        // «Сменить набор паков = перезагрузить стиль»). A pack downloaded or
        // deleted mid-ride takes effect on the next connection.
        // On IO: this lists the pack directory on external storage and reads the
        // template out of the APK, and [startStream] is reached from the session
        // collector on the main thread.
        val style = withContext(Dispatchers.IO) { styleAssembler.assembleCurrent() }
        overlays.darkMap = style.theme == MapTheme.DARK
        // The packs decide how far out the camera may go, not the constant — see
        // [zoomFloor]. Null means nothing readable said otherwise, so ZOOM_MIN stands.
        // Clamped to the ceiling as well as the floor: [MapStyleAssembler.packMinZoom] reads
        // a byte out of a PMTiles header, so its range is 0..255, not "something sensible".
        // A pack claiming minzoom 20 would make the floor exceed ZOOM_MAX, and the next
        // `coerceIn(zoomFloor, ZOOM_MAX)` in [stepZoom] throws IllegalArgumentException —
        // a corrupt header taking out the zoom buttons for the whole ride.
        zoomFloor = style.minZoom
            ?.let { (it * ZOOM_SCALE.toInt()).coerceIn(ZOOM_MIN, ZOOM_MAX) }
            ?: ZOOM_MIN
        if (zoom < zoomFloor) zoom = zoomFloor
        snapshots.prepare(style.json, DashEncoder.WIDTH, DashEncoder.HEIGHT)
        // Captured so [disconnect]'s release can only ever free THIS snapshotter,
        // never one a later connection has since prepared.
        snapshotGeneration = snapshots.currentGeneration()
        RideDiagnostics.log(
            "map",
            "style ${style.theme} from ${style.packs} pack(s), ${style.json.length / 1024} KiB, " +
                "zoom ${zoomText(zoomFloor)}-${zoomText(ZOOM_MAX)}" +
                (if (zoomFloor > ZOOM_MIN) " (packs stop at z${style.minZoom}, floor raised)" else "") +
                ", render ${(DashEncoder.WIDTH * MapSnapshotProvider.PIXEL_RATIO).toInt()}×" +
                "${(DashEncoder.HEIGHT * MapSnapshotProvider.PIXEL_RATIO).toInt()}" +
                "@${MapSnapshotProvider.PIXEL_RATIO}",
        )

        } catch (e: Throwable) {
            runCatching { encoder?.release() }
            encoder = null
            runCatching { frameBitmap?.recycle() }
            frameBitmap = null
            throw e
        }

        session.startStreaming()

        // The window belongs to this stream, not to the controller that outlives it:
        // whatever the previous session did not live long enough to report would
        // otherwise be counted against this one's freshly-zeroed period — see
        // RenderStats.reset.
        renderStats.reset()

        streamJob = scope.launch(Dispatchers.Default) {
            // A child of this launch, so cancelling the stream cancels it — no separate
            // teardown path, which is the property the rest of this file keeps paying for.
            launch(Dispatchers.IO) {
                // Claimed once, for the life of this stream. session.rtpSender() captures the
                // socket and checks it is still the current one on every packet — the identity
                // guard the control senders have had all along and this path did not, see
                // DashSession.rtpSender. Null means there is no socket to stream over, which
                // is not an error worth a teardown: the frame loop's own `state != STREAMING`
                // condition ends things a moment later.
                val sendRtp = session.rtpSender() ?: return@launch
                for (au in rtpOutbox) {
                    // Back to back, on purpose. This loop used to pace the packets of one
                    // access unit across `min(30 ms, frameInterval / 4)`; the pacing was
                    // REMOVED on 2026-09-11 because the ride of 2026-09-10 21:00 says it
                    // made the picture worse, and the numbers say it was never needed.
                    //
                    // What it was for: keeping an 18-datagram key frame from overrunning
                    // the send path. That premise is now measured and false. The same ride
                    // printed `sndbuf=4096KiB` — the platform default on this phone is 4 MiB,
                    // twenty times the stock-Linux figure the plan assumed — over a 65 Mbps
                    // link carrying 200 kbps, and `drop=0 dropIdr=0` in all twenty windows.
                    // Nothing was ever congested here.
                    //
                    // What it cost: at 65 Mbps those 18 datagrams are about 3 ms of airtime
                    // sent as ONE A-MPDU — a single contention, a block ACK, and the driver
                    // retransmitting just the missing subframes inside the same TXOP.
                    // Stretching them over 30 ms is ten times longer than the burst needs
                    // and hands the radio 18 separate transmissions instead, each
                    // contending on its own in a 2.4 GHz band shared with a city. Losing
                    // one datagram of a key frame costs a whole GOP — eight frames, two
                    // seconds of mush — so trading aggregation for spacing is the wrong
                    // way round for this stream.
                    //
                    // The queue above stays: it is what turns a genuine stall into a
                    // counted drop instead of latency, and `drop=` is how we would find
                    // out if the premise above ever stops being true.
                    for (pkt in au) {
                        // The cancellation check the `delay()` used to provide for free.
                        // Iterating the channel suspends, so cancellation is seen BETWEEN
                        // access units either way — but without a suspension point inside
                        // this loop an AU that had already started would run to completion
                        // after `rtpOutbox.cancel()`. That is the leak `cancel()` rather
                        // than `close()` exists to prevent, and dropping the pacing must not
                        // quietly hand it back.
                        //
                        // It is now belt and braces rather than the only guard: [sendRtp] is
                        // bound to this stream's socket and refuses a stale one. Both stay —
                        // this one stops the work, that one stops the packet.
                        ensureActive()
                        sendRtp(pkt)
                        rtpPacketsSent.incrementAndGet()
                        rtpBytesSent.addAndGet(pkt.size.toLong())
                    }
                }
            }
            try {
                var failures = 0
                var lastEncoderLogAt = monotonicMs()
                var lastRenderLogAt = lastEncoderLogAt
                var lastFrameSentAt = 0L
                var loggedFrames = 0; var loggedIdr = 0; var loggedRtp = 0L; var loggedBytes = 0L
                // Declared outside the try/catch and assigned fresh once per iteration, so the
                // trailing delay() below can reuse the SAME value the PTS advance used — both must
                // agree on "how long is this frame", or the RTP timeline and the actual send
                // cadence drift apart from each other. Starts at the conservative (idle)
                // interval; only matters for a hypothetical exception inside tick() itself,
                // before the real value below gets assigned.
                var frameIntervalMs = 1000L / FPS_IDLE
                while (isActive && session.state.value == DashState.STREAMING) {
                    // Top of the iteration, so the trailing delay() can pace to a DEADLINE
                    // rather than sleep a whole interval on top of the work: the body waits
                    // for a snapshot (up to the interval itself), and adding the full
                    // interval after it made the real period `interval + latency`. At 4 fps
                    // with a 100 ms snapshot that is ~2.9 fps, and it sagged exactly under
                    // the load that makes snapshots slow. The spec asks for
                    // `max(interval, latency)` (drawing_from_local_tiles.md, «Цикл ждёт
                    // снапшот»), which is what the deadline gives.
                    val iterationStartMs = monotonicMs()
                    try {
                        // Assigned BEFORE tick() now, not after: the interval is also this frame's
                        // render budget, and tick() reports against it. The cost is that a
                        // stopped/started transition uses the previous iteration's [camMoving] for
                        // one frame — invisible next to the camera's own 350 ms smoothing.
                        frameIntervalMs = 1000L / (if (camMoving) FPS_MOVING else FPS_IDLE)
                        tick(frameIntervalMs)
                        val bmp = frameBitmap
                        val enc = encoder
                        // [haveFrame] gates the very first frames of a stream, and only
                        // those. A freshly created bitmap is fully transparent, and the
                        // overlay renderer no longer paints a background of its own (it
                        // comes from the style, inside the snapshot) — so until one
                        // snapshot has landed there is nothing in here worth encoding.
                        // Sending it anyway put garbage on the dash for as long as the
                        // first, most expensive snapshot took.
                        if (bmp != null && enc != null && haveFrame) {
                            // Match the encoder's target to the dash's own two profiles,
                            // on the transition only. [camMoving] is already what picks
                            // the frame rate, so reusing it keeps one notion of "the map
                            // is going somewhere" instead of introducing a second that
                            // could disagree with the first.
                            if (camMoving && idleBitrate) {
                                enc.requestBitrate(DashEncoder.BITRATE)
                                idleBitrate = false
                            } else if (!camMoving && !idleBitrate) {
                                enc.requestBitrate(DashEncoder.BITRATE_IDLE)
                                idleBitrate = true
                            }
                            val encodeStart = monotonicMs()
                            enc.renderFrame { canvas -> canvas.drawBitmap(bmp, 0f, 0f, null) }
                            // Publish the step; the clock itself advances per drained frame
                            // inside `onEncoded`. Advancing it here was what let two frames
                            // pulled in one drain share a timestamp — see that callback.
                            ptsStepMs = frameIntervalMs
                            when (enc.drain()) {
                                0 -> drainMiss.incrementAndGet()
                                1 -> Unit
                                else -> drainDouble.incrementAndGet()
                            }
                            val sentAt = monotonicMs()
                            renderStats.frameSent(
                                intervalMs = if (lastFrameSentAt == 0L) 0L else sentAt - lastFrameSentAt,
                                encodeMs = sentAt - encodeStart,
                                intendedIntervalMs = frameIntervalMs,
                            )
                            lastFrameSentAt = sentAt
                            emitFramePreview(bmp, frameIntervalMs)
                        }
                        failures = 0
                        val now = monotonicMs()
                        if (now - lastRenderLogAt > RENDER_LOG_INTERVAL_MS) {
                            val elapsed = now - lastRenderLogAt
                            lastRenderLogAt = now
                            RideDiagnostics.log(
                                "map",
                                // Appended here rather than passed into drain():
                                // RenderStats measures the frame pipeline and has
                                // no business knowing what a camera is. It earns
                                // the space because every other number on this
                                // line is read against it — `blank` especially,
                                // which rises with zoom for reasons that are not
                                // a missing map.
                                renderStats.drain(
                                    periodMs = elapsed,
                                    timeouts = snapshots.timeouts,
                                    skipped = snapshots.skipped,
                                    abandoned = snapshots.abandoned,
                                    errors = snapshots.errors,
                                    rebuilds = snapshots.rebuilds,
                                ) + " zoom=${zoomText(zoom)}" +
                                    " center=${geoText(camLat)},${geoText(camLng)}" +
                                    " moved=${windowMovedM.toInt()}m",
                            )
                            windowMovedM = 0.0
                        }
                        if (now - lastEncoderLogAt > ENCODER_LOG_INTERVAL_MS) {
                            val nowRtp = rtpPacketsSent.get()
                            val nowBytes = rtpBytesSent.get()
                            val dFrames = framesEncoded - loggedFrames
                            val dIdr = idrFramesEncoded - loggedIdr
                            val dRtp = nowRtp - loggedRtp
                            val dBytes = nowBytes - loggedBytes
                            val dFlips = frameRate.drainFlips()
                            loggedFrames = framesEncoded; loggedIdr = idrFramesEncoded; loggedRtp = nowRtp
                            loggedBytes = nowBytes
                            lastEncoderLogAt = now
                            val intervalS = ENCODER_LOG_INTERVAL_MS / 1_000
                            val thermal = thermalLabel()
                            // Into the ride file, not just app_log.txt. This is the
                            // only number that says whether anything reached the
                            // socket, and a frozen-map report is exactly when it is
                            // wanted — but app_log.txt is a ring buffer that a long
                            // ride overwrites, so on 2026-09-06 the question "did the
                            // stream keep flowing" had no answer left by the time the
                            // phone was back on the cable.
                            if (dFrames == 0) {
                                // Both files, one call: the ride file is where a post-mortem
                                // starts, and app_log/`/more/logs` need this at W or their
                                // level filters stop surfacing it. That pair used to be
                                // written by hand here — see RideDiagnostics.warn.
                                RideDiagnostics.warn(
                                    "stream",
                                    "encoder output: 0 frames in the last ${intervalS}s while STREAMING " +
                                        "— render/encode loop itself stalled (nothing to even send) — " +
                                        // fpsFlips, and only it: [dFlips] is drained above the
                                        // branch, so a stalled window that does not print it
                                        // loses the count rather than saving it for the next
                                        // one — and a window where the loop runs without
                                        // encoding is exactly when an oscillating rate policy
                                        // is worth seeing. The drop counters are NOT here on
                                        // purpose: no encoded frame means no trySend, so they
                                        // are structurally zero, and printing zeroes would
                                        // imply the queue was examined when it was not.
                                        //
                                        // The drain counters ARE here, and unlike the drop
                                        // counters they say something this branch cannot say
                                        // otherwise: drainMiss > 0 means the loop kept
                                        // iterating and the encoder gave back nothing.
                                        //
                                        // The converse does NOT hold, and the first draft of
                                        // this comment claimed it did: drain() is reached only
                                        // when `haveFrame`, so a loop spinning without a
                                        // snapshot — the commonest stall — also prints
                                        // drainMiss=0. Read it with `[map]`'s blank=/timeouts=
                                        // from the same minute, which say whether frames were
                                        // being produced at all. Review caught the overclaim.
                                        //
                                        // They also have to be reset here either way, or the
                                        // stalled window's count leaks into the next one.
                                        "drainMiss=${drainMiss.getAndSet(0)} " +
                                        "drainDouble=${drainDouble.getAndSet(0)} " +
                                        "fpsFlips=$dFlips thermal=$thermal",
                                )
                            } else {
                                RideDiagnostics.log(
                                    "stream",
                                    "frames=$dFrames (idr=$dIdr) rtp=$dRtp ${dBytes / 1024}KiB " +
                                        "${dBytes * 8 / 1000 / intervalS}kbps " +
                                        "frame=${frameBytes.drain()}B " +
                                        "idrPkts=${idrDatagrams.drain()} " +
                                        "idrShape=${nalProc.drainIdrShapes()} " +
                                        "drop=${rtpDropped.getAndSet(0)} " +
                                        "dropIdr=${rtpDroppedIdr.getAndSet(0)} " +
                                        "drainMiss=${drainMiss.getAndSet(0)} " +
                                        "drainDouble=${drainDouble.getAndSet(0)} " +
                                        "fpsFlips=$dFlips " +
                                        "bitrate=${if (idleBitrate) "idle" else "moving"} " +
                                        "thermal=$thermal in the last ${intervalS}s",
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures++
                        DebugLog.e(TAG, { "Frame loop error #$failures" }, e)
                        if (failures >= 3) {
                            runCatching { encoder?.release() }
                            encoder = runCatching { DashEncoder(onEncoded).also { it.prepare() } }
                                .onFailure { DebugLog.e(TAG, { "Encoder rebuild failed" }, it) }
                                .getOrNull()
                            // A fresh encoder is configured at DashEncoder.BITRATE, i.e. the
                            // moving profile, whatever the old one was last told. Without
                            // this the flag can claim "idle" over a codec running at the
                            // moving target, and since requestBitrate only fires on a
                            // TRANSITION, nothing corrects it until the rider stops and
                            // starts again — a rebuild while parked would stream at the
                            // moving rate for as long as the bike stands still.
                            idleBitrate = false
                            lastSignature = ""
                            failures = 0
                        }
                    }
                    // Whatever is left of this frame's budget. Overrunning it is not made up
                    // for by shortening the next frame: the loop falls behind by the overrun
                    // and shows up as `frames=X/expected` plus `late=` in the render log,
                    // which is the honest reading — videoPtsMs advances by the nominal
                    // interval, and that stays truthful for as long as the budget is kept.
                    delay((frameIntervalMs - (monotonicMs() - iterationStartMs)).coerceAtLeast(0L))
                }
            } finally {
                // The loop is the last thing that draws into this encoder, so it is the only
                // place that can free it without racing renderFrame/drain — see the note in
                // [disconnect]. Runs on cancellation too (nothing here suspends), which is
                // what makes "cancel the loop" a complete teardown on its own.
                runCatching { encoder?.release() }
                encoder = null
                // cancel(), NOT close(). close() lets the sender drain what is queued.
                // That used to be the only thing standing between a dead stream and the next
                // session's socket, because `DashSession.sendRtp` wrote to whatever `socket`
                // was live at that moment; since 2026-09-14 the sender is bound to this
                // stream's socket and refuses a stale one (DashSession.rtpSender), so this is
                // now the outer of two guards rather than the sole one. It still earns its
                // place: refusing the packet at the socket still leaves the sender doing the
                // work of an ended stream.
                // On the Wi-Fi-loss path this loop exits on its own rather than
                // being cancelled, so a drained queue could put the dead stream's packets,
                // carrying the old packetizer's SSRC and sequence numbers, onto the NEXT
                // session's freshly bound socket. Frames from a stream that has ended are
                // stale by definition; there is nothing here worth delivering.
                rtpOutbox.cancel()
            }
        }
    }

    /**
     * One iteration of camera smoothing, plus a redraw when anything visible
     * changed. Suspends because [redrawFrame] waits for MapLibre.
     *
     * [frameIntervalMs] is this frame's budget — passed down so telemetry can
     * say how often the snapshot ate it, which is the number the "wait for the
     * snapshot" decision stands or falls on (see «Телеметрия»).
     */
    private suspend fun tick(frameIntervalMs: Long) {
        // ONE read for the whole frame. Everything below — the camera target, the redraw
        // signature, the overlay — works from this copy, so a `setNavState` landing mid-tick
        // takes effect on the NEXT frame in full rather than on this one in part. That is the
        // entire point of DashInputs; see its doc for the two defects it retires.
        val inp = inputs.value

        if (!followMode && monotonicMs() - lastManualPanAt > MANUAL_IDLE_MS) {
            panX = 0f; panY = 0f; followMode = true; panAtBound = false
        }

        val loc = locationTracker.location.value
        val riderLat = loc?.latitude
        val riderLng = loc?.longitude
        val heading = loc?.bearing ?: (if (camInit) camHdg else 0f)

        val fixAgeMs = loc?.ageMs() ?: Long.MAX_VALUE
        val gpsLost = loc == null || fixAgeMs > GPS_FIX_STALE_MS
        // A contradictory position reads as "weak" rather than getting a flag of its own.
        // The distinction — imprecise versus untrustworthy — is real, but the rider's need
        // is identical ("do not act on this"), and `gpsWeak` already reaches both the frame
        // overlay and the Dart state, so folding it in warns them without a new field
        // through two layers. See PositionTrust: on 2026-09-13 accuracy said 10-20 m while
        // the position was 70 km out, and this chip stayed green for the whole ride.
        val gpsWeak = !gpsLost &&
            ((loc?.accuracy ?: 0f) > GPS_WEAK_ACCURACY_M || !locationTracker.trusted.value)

        // GPS/progress fields come from publishState's own read of the live sources now —
        // it is no longer this function's job to be their only supplier.
        publishState()

        // The frame is always a live map — idle is just the map with no route or
        // destination, never a separate mode (see spec/fsm.md). [DashInputs.navigating] still gates
        // what isn't frame content: DashSession's chrome/nav-info decisions.

        val haveTarget = riderLat != null || inp.dest != null
        val targetLat = riderLat ?: inp.dest?.lat ?: camLat
        val targetLng = riderLng ?: inp.dest?.lng ?: camLng

        val nowNs = System.nanoTime()
        // Two readings of the same gap, deliberately. [dt] stays clamped because it drives
        // the camera smoothing, where a long stall must not produce one giant jump. The
        // speed below needs the UNclamped value: dividing a real 0.8 s of travel by a
        // clamped 0.5 s would report 1.6x the rider's speed and bias every late frame
        // towards "moving".
        val dtRaw = if (lastTickNs == 0L) 0.042 else (nowNs - lastTickNs) / 1e9
        val dt = dtRaw.coerceIn(0.0, 0.5)
        lastTickNs = nowNs
        val a = if (camInit) (1.0 - exp(-dt / SMOOTH_TAU)) else 1.0

        // Read BEFORE the block below, which sets it. Afterwards it says "the
        // camera has a position", not "it had one to move from" — and the first
        // tick moves it from (0,0) to the rider, so a distance measured against
        // the post-block flag is a 6700 km teleport across the Gulf of Guinea.
        // That lands in `moved=` as the opening number of every fresh process,
        // and in [camMoving] as a spurious "riding" verdict that puts the first
        // frames at 4 fps. [startStream] clears camInit without clearing
        // camLat/camLng, so a later stream jumps by less but jumps all the same.
        val wasInit = camInit
        val prevLat = camLat; val prevLng = camLng
        if (haveTarget) {
            if (!camInit) { camLat = targetLat; camLng = targetLng; camHdg = heading; camInit = true }
            else {
                camLat += (targetLat - camLat) * a
                camLng += (targetLng - camLng) * a
                val dh = (((heading - camHdg) % 360f) + 540f) % 360f - 180f
                camHdg += dh * a.toFloat()
            }
        }
        val movedM = if (wasInit) distMeters(prevLat, prevLng, camLat, camLng) else 0.0
        windowMovedM += movedM
        // Ground speed, not distance-per-tick — see [CAM_MOVING_ENTER_MPS] for why the
        // difference is the whole point. The camera's own speed and the fix's agree while
        // riding steadily; the max of the two is what keeps a lagging camera from reading
        // as a stopped bike right after pulling away.
        val camSpeedMps = if (dtRaw > 0.0) movedM / dtRaw else 0.0
        // A lost fix contributes nothing. `loc` survives its own staleness — it is the last
        // fix, not a live one — so a bike that loses GPS at speed (tunnel, garage, underpass)
        // would otherwise keep feeding its final speed into the policy forever, and the dash
        // would sit at 4 fps and the moving bitrate for the whole stop. [gpsLost] is the same
        // staleness test the overlays use; below it the camera's own movement still speaks,
        // and a parked bike moves the camera not at all.
        val fixSpeedMps = if (gpsLost) 0.0 else (loc?.speed ?: 0f).toDouble()
        val speedMps = maxOf(camSpeedMps, fixSpeedMps)
        // elapsedRealtime, not currentTimeMillis: this is a duration, and wall clock
        // moves. An NTP correction backwards — routine the moment data comes back after a
        // dead zone — makes the dwell's `now - last` negative and freezes the frame rate
        // for the length of the jump; a forward one cancels the dwell entirely. The
        // smoothing above already takes its elapsed time from nanoTime for the same reason.
        camMoving = frameRate.update(speedMps, SystemClock.elapsedRealtime())

        val centerLat = if (haveTarget) camLat else 0.0
        val centerLng = if (haveTarget) camLng else 0.0
        val camHeading = if (haveTarget) camHdg else heading

        // ONE read of the volatile [headingUp] for the whole frame. It used to be read three
        // times per tick: twice inside `sig` below — where a toggle between the two appends
        // writes "heading-up" next to a north-up heading of 0, a combination no real frame
        // has — and a third time in [redrawFrame], so the signature committed as drawn could
        // describe an orientation the frame was never drawn in. Both are self-correcting on
        // the next tick and cost one wasted redraw; the reason to fix them anyway is that
        // [cameraFor] now states as fact that the caller holds a single read.
        // Review, 2026-09-16.
        val headingUpNow = headingUp

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
            append(zoom); append(panX.toInt()); append(panY.toInt())
            append(headingUpNow)
            append(if (headingUpNow) (camHeading * 10).toInt() else 0)
            append(inp.remainingM?.let { (it / 100).toInt() } ?: -1)
            append(routeSignature(inp.route))
            append(inp.dest?.let { "%.5f".format(it.lat) } ?: "-")
            append(inp.dest?.let { "%.5f".format(it.lng) } ?: "-")
            append(gpsLost); append(gpsWeak)
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
                    riderLat != null, gpsWeak, gpsLost,
                    frameIntervalMs,
                )
            ) {
                lastSignature = sig
                lastRedrawAt = now
            }
        }
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
        // Passed in, not read from the field. This used to read the volatile [headingUp]
        // TWICE — once for bearing, once for tilt — so a toggle landing between them produced
        // a north-up raster with a 45° tilt, or the reverse. The caller already holds one read
        // for the whole frame; taking it as a parameter makes that the only read there is.
        // Review, 2026-09-15.
        headingUp: Boolean,
    ): CameraPosition =
        CameraPosition.Builder()
            .target(LatLng(centerLat, centerLng))
            .zoom(zoom / ZOOM_SCALE)
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
        // The caller's single read of the volatile [headingUp], passed in rather than read
        // again here: the camera, the padding, the log and the overlays below must agree
        // with each other AND with the signature the caller hashed from the same value.
        headingUpNow: Boolean,
        haveRider: Boolean, gpsWeak: Boolean, gpsLost: Boolean,
        frameIntervalMs: Long,
    ): Boolean {
        val bmp = frameBitmap ?: return false

        val camera = cameraFor(centerLat, centerLng, heading, headingUpNow)
        val padding = DashCamera.padding(DashEncoder.WIDTH, DashEncoder.HEIGHT, headingUpNow, panX, panY)
        logCameraSend(camera, padding, headingUpNow)

        val snapshotStart = monotonicMs()
        val snapshot = snapshots.capture(
            camera,
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
            // by review; it is also the counterexample to the note beside [zoom], so that
            // note no longer claims camera fields are only ever read one at a time.
            headingUp = headingUpNow,
            heading = heading,
            riderLat = if (haveRider) camLat else null,
            riderLng = if (haveRider) camLng else null,
            destLat = inp.dest?.lat,
            destLng = inp.dest?.lng,
            // From the SAME snapshot the signature above was built from, so the line the
            // rider sees and the colours on it always describe one route.
            route = inp.route.points,
            routeJam = inp.route.jam,
            gpsWeak = gpsWeak,
            gpsLost = gpsLost,
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
            budgetMs = frameIntervalMs,
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
    private fun logCameraSend(camera: CameraPosition, padding: IntArray, headingUp: Boolean) {
        // Keyed and printed from the arguments, never from the live fields. A press
        // landing between [cameraFor] and here would otherwise print a zoom MapLibre
        // was never given — and commit that key, so the frame that does use it says
        // nothing. The parameter deliberately shadows the field for the same reason.
        val key = "${camera.zoom}/${camera.tilt}/$headingUp/${padding.joinToString(",")}"
        if (key == lastCameraLogKey) return
        lastCameraLogKey = key
        RideDiagnostics.log(
            "camera",
            "→ MapLibre zoom=${String.format(Locale.ROOT, "%.2f", camera.zoom)} " +
                "${if (headingUp) "heading-up" else "north-up"} " +
                "tilt=${camera.tilt.toInt()} padding=[${padding.joinToString(" ")}]",
        )
    }

    /**
     * One frame plus its numbers to the debug screen, or nothing at all.
     *
     * Compression happens here rather than in Dart because the bitmap never
     * crosses the boundary otherwise — and it happens only when someone is
     * listening, see [onFramePreview]. A failure is swallowed: a debug preview
     * that cannot compress must not take the ride down with it.
     */
    private fun emitFramePreview(bmp: Bitmap, frameIntervalMs: Long) {
        val sink = onFramePreview ?: return
        runCatching {
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            sink(
                mapOf(
                    "png" to out.toByteArray(),
                    "zoom" to zoom / ZOOM_SCALE,
                    "encodedBytes" to lastEncodedBytes,
                    "framesSent" to framesSentTotal,
                    // NOT "frames confirmed" — this is a one-shot "decoder opened"
                    // signal, 1-3 per session. See spec/video.md.
                    "decoderOpens" to session.decoderOpenCount,
                    "fps" to (1000L / frameIntervalMs).toInt(),
                    "renderScale" to MapSnapshotProvider.PIXEL_RATIO,
                ),
            )
        }.onFailure { DebugLog.w(TAG) { "frame preview failed: ${it.message}" } }
    }

    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters >= 1000) (((meters / 100).toInt())) to DashCommands.NAV_UNIT_KM_TENTHS
        else meters.toInt() to DashCommands.NAV_UNIT_METERS

    private fun distMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(s), sqrt(1 - s))
    }

    /**
     * Publishes one snapshot of engine state to Dart.
     *
     * The GPS/progress fields are read from the live sources here rather than taken as
     * parameters. They used to be nullable parameters that only [tick] ever filled in, so
     * every other caller — WiFi status changes, session transitions, media/call forwarding,
     * setDestination — published them as null, which Dart's `fromMap` turns into
     * false/null and writes over the last good values (the state object is replaced
     * wholesale, not merged). The visible result was the Dash screen's rider marker and
     * GPS chips flickering, and no GPS status at all before STREAMING, since [tick] — the
     * only source of those values — runs only while streaming.
     */
    private fun publishState(
        errorMessage: String? = null,
        explicitDisconnect: Boolean = false,
    ) {
        // Its own read, not the frame's. publishState answers "what is true now" for Dart
        // and is called from the main thread as well as from tick(); sharing the frame's
        // snapshot would publish a value one tick stale on the main-thread path.
        val inp = inputs.value
        val loc = locationTracker.location.value
        val fixAgeMs = loc?.ageMs() ?: Long.MAX_VALUE
        val gpsLost = loc == null || fixAgeMs > GPS_FIX_STALE_MS
        // A contradictory position reads as "weak" rather than getting a flag of its own.
        // The distinction — imprecise versus untrustworthy — is real, but the rider's need
        // is identical ("do not act on this"), and `gpsWeak` already reaches both the frame
        // overlay and the Dart state, so folding it in warns them without a new field
        // through two layers. See PositionTrust: on 2026-09-13 accuracy said 10-20 m while
        // the position was 70 km out, and this chip stayed green for the whole ride.
        val gpsWeak = !gpsLost &&
            ((loc?.accuracy ?: 0f) > GPS_WEAK_ACCURACY_M || !locationTracker.trusted.value)
        onState(
            mapOf(
                "stage" to session.state.value.name,
                "explicitDisconnect" to explicitDisconnect,
                "wifiStatus" to wifiManager.state.value.status.name,
                "wifiSsid" to wifiManager.state.value.ssid,
                "wifiError" to wifiManager.state.value.error,
                // Whether a destination is set, per [setDestination]/[clearDestination].
                // Drives DashSession's chrome and the Dash screen's "exit navigation" FAB;
                // the frame itself is a map either way.
                "navigating" to inp.navigating,
                "hasGps" to (loc != null),
                "riderLat" to loc?.latitude,
                "riderLng" to loc?.longitude,
                "riderBearing" to (loc?.bearing ?: (if (camInit) camHdg else 0f)),
                // Ground speed straight from the fix, m/s. Published so Dart's NavEngine can
                // compute a real ETA — NavLoop used to pass a hardcoded 0, which made
                // NavEngine fall back to its 11 m/s constant for every estimate.
                "riderSpeed" to loc?.speed,
                "remainingKm" to inp.remainingM?.let { it / 1000.0 },
                "offRoute" to inp.offRoute,
                "gpsLost" to gpsLost,
                "gpsWeak" to gpsWeak,
                "errorMessage" to errorMessage,
                "followMode" to followMode,
                "headingUp" to headingUp,
                // In MapLibre's units, not the hundredths the field is stored
                // in: the key is named `zoom` and documented as such in
                // opendash_dash_engine.dart, and 1400 under that name would be
                // read as a zoom of 1400 by whoever first consumes it.
                "zoom" to zoom / ZOOM_SCALE,
                "nowPlayingTitle" to inp.nowPlayingTitle,
                "incomingCaller" to inp.incomingCaller,
                "hasActiveCall" to inp.hasActiveCall,
            )
        )
    }
}
