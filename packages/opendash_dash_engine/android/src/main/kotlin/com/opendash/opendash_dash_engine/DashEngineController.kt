package com.opendash.opendash_dash_engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import com.opendash.opendash_dash_engine.dash.DashConfig
import com.opendash.opendash_dash_engine.dash.DashKeepAliveService
import com.opendash.opendash_dash_engine.dash.DashSession
import com.opendash.opendash_dash_engine.dash.DashState
import com.opendash.opendash_dash_engine.dash.DashWifiManager
import com.opendash.opendash_dash_engine.dash.WifiConnStatus
import com.opendash.opendash_dash_engine.dash.map.DashCamera
import com.opendash.opendash_dash_engine.dash.map.GeoPoint
import com.opendash.opendash_dash_engine.dash.map.LocationTracker
import com.opendash.opendash_dash_engine.dash.map.MapProjection
import com.opendash.opendash_dash_engine.dash.map.MapSnapshotProvider
import com.opendash.opendash_dash_engine.dash.map.MapStyleAssembler
import com.opendash.opendash_dash_engine.dash.map.MapTheme
import com.opendash.opendash_dash_engine.dash.map.OverlayRenderer
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
        // to be wrong instead of three. 16 here is the old default of 17.
        //
        // The floor is the pack corpus's `minzoom`: MapLibre asks for tile
        // `floor(camera zoom)`, and below 11 that tile does not exist. Rendering
        // does not build downwards, so a lower step is a blank screen, not a
        // coarse map.
        private const val ZOOM_MIN = 11
        private const val ZOOM_MAX = 19
        private const val ZOOM_DEFAULT = 16
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
    private val snapshots = MapSnapshotProvider(context)
    private val overlays = OverlayRenderer()
    private val renderStats = RenderStats()
    private var toneGenerator: ToneGenerator? = null
    private val mediaInfo = MediaInfoProvider(context)
    private val callController = CallController(context)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /**
     * OK/Warm/Hot from [PowerManager.currentThermalStatus] (API 29+; "n/a" below that). Folded
     * into the encoder health log below — a hardware encoder throttling under heat is a
     * plausible, previously-uninstrumented explanation for the exact silent stall (0 frames,
     * no exception) the 2026-08-28 field session found. Ported from OpenMotoDash/NorthStar's
     * `updateThermal()` (see spec/wifi_retry_policy.md's "Из живого форка").
     */
    private fun thermalLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
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
    // Armed whenever session.state isn't STREAMING, cancelled once it is — see
    // RECONNECT_GIVEUP_MS's doc.
    private var giveupJob: Job? = null

    // ── Destination / nav state, pushed from Dart ──
    @Volatile private var destName: String? = null
    @Volatile private var destLat: Double? = null
    @Volatile private var destLng: Double? = null
    @Volatile private var navigating = false
    @Volatile private var routePoints: List<GeoPoint> = emptyList()
    // Traffic-level code per geometry segment (index i covers routePoints[i]..[i+1]),
    // same encoding as Dart's `JamLevel.index` — see setNavState's doc. Empty means
    // "no traffic data for this route", OverlayRenderer then falls back to a solid line.
    @Volatile private var routeJam: List<Int> = emptyList()
    @Volatile private var remainingM: Double? = null
    @Volatile private var offRoute = false

    // ── Media/call info forwarded to the dash (and surfaced to Dart) ──
    @Volatile private var nowPlayingTitle: String? = null
    @Volatile private var incomingCaller: String? = null
    // True for ANY call (ringing or already answered/outgoing) — unlike
    // [incomingCaller], which only ever carries ringing calls. Dart's button
    // dispatcher needs this to let the dash's reject/hangup button end an
    // already-answered call too, same as the original DashViewModel.onButton
    // (`call != null`, vs `call.incoming == true` for the answer button).
    @Volatile private var hasActiveCall: Boolean = false

    // ── Camera state ──
    // The first six are written by the MethodChannel handlers on the main
    // thread (joystick zoom/pan/recenter) and read by [tick] on
    // Dispatchers.Default, so they need the same @Volatile treatment as the
    // nav fields above — otherwise a button press can go unseen by the frame
    // loop. The rest are touched only by the frame loop and by [startStream]
    // (which launches it, establishing happens-before), so plain fields.
    @Volatile private var zoom = ZOOM_DEFAULT
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var headingUp = true
    @Volatile private var followMode = true
    @Volatile private var lastManualPanAt = 0L
    private var camLat = 0.0
    private var camLng = 0.0
    private var camHdg = 0f
    private var camInit = false
    private var camMoving = false
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

    // ── Public API (invoked by the plugin's MethodChannel handler) ────────

    fun connect() {
        RideDiagnostics.init(context)
        RideDiagnostics.start("connect")
        RideDiagnostics.log(
            "connect",
            "ssid='${dashConfig.ssid}' needsDiscovery=${dashConfig.needsDiscovery} dest=$destName",
        )
        DashKeepAliveService.start(context)
        session.onButton = { code -> onButton?.invoke(code.toInt() and 0xFF) }
        session.onError = { msg -> publishState(errorMessage = msg) }
        locationTracker.start()
        authRetries = 0

        val ssid = dashConfig.ssid
        wifiWatchJob?.cancel()
        wifiWatchJob = scope.launch {
            var sessionStarted = false
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
                    DashState.READY -> { authRetries = 0; startStream() }
                    // The K1G handshake failed but the WiFi link itself is still up — no need
                    // to tear down and re-request WiFi (which risks the system dialog, see
                    // spec/wifi_retry_policy.md's "Из живого форка"). Just retry the handshake
                    // directly, same network, bounded so a genuinely dead dash still ends in
                    // ERROR rather than retrying forever.
                    DashState.ERROR -> {
                        val wifi = wifiManager.state.value
                        if (wifi.status == WifiConnStatus.CONNECTED && authRetries < MAX_AUTH_RETRIES) {
                            authRetries++
                            delay(AUTH_RETRY_DELAY_MS)
                            if (wifiManager.state.value.status == WifiConnStatus.CONNECTED) {
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
        giveupJob?.cancel(); giveupJob = null
        streamJob?.cancel(); streamJob = null
        sessionWatchJob?.cancel(); sessionWatchJob = null
        wifiWatchJob?.cancel(); wifiWatchJob = null
        stopMediaForwarding()
        session.disconnect()
        wifiManager.disconnect()
        locationTracker.stop()
        encoder?.release(); encoder = null
        // The style — theme and set of packs — is read once per stream, so the
        // snapshotter is per-stream too and goes away with it. By generation, not
        // "whatever is current": this does not wait, and a fast reconnect could
        // otherwise have it free the snapshotter the NEXT stream just prepared —
        // after which every frame silently returns null and the map freezes.
        val generation = snapshotGeneration
        scope.launch { snapshots.release(generation) }
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
                nowPlayingTitle = np?.title
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
                incomingCaller = incoming?.caller
                hasActiveCall = call != null
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
        destName = name
        destLat = lat
        destLng = lng
        navigating = lat != null && lng != null
        session.updateRouteCard(name ?: "OpenDash")
        // DashSession reads [navigating] off the state stream for its own
        // chrome/nav-info decisions — push immediately instead of waiting for
        // the next frame-loop tick() so "Send to Dash" takes effect at once.
        publishState()
    }

    fun clearDestination() {
        destName = null; destLat = null; destLng = null
        navigating = false; routePoints = emptyList(); routeJam = emptyList()
        session.updateRouteCard("OpenDash")
        publishState()
    }

    /**
     * Compact nav-state push from Dart's NavEngine — see class doc. [points]
     * is the route geometry; Dart only needs to send it once when the route
     * is (re)computed — an empty list here means "keep the geometry already
     * held", so the 1 Hz progress tick doesn't have to resend it every call.
     * [jamSegments] rides along with [points] under the same rule (only
     * applied when [points] is non-empty) — see [routeJam]'s doc.
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
        remainingM = remainingMeters
        offRoute = isOffRoute
        if (points.isNotEmpty()) {
            routePoints = points
            // Mismatched length means stale/missing traffic data for this route —
            // OverlayRenderer's solid-line fallback then kicks in (spec/yande_ruote.md).
            routeJam = if (jamSegments.size == points.size - 1) jamSegments else emptyList()
        }
        if (remainingMeters != null && nextTurnMeters != null) {
            val (pv, pu) = toDashDistance(nextTurnMeters)
            val (tv, tu) = toDashDistance(remainingMeters)
            session.updateNavInfo(maneuver, pv, pu, tv, tu, etaHHMM)
        }
    }

    fun setFollowMode(enabled: Boolean) {
        followMode = enabled
        if (enabled) { panX = 0f; panY = 0f }
    }

    /**
     * Joystick pan, in frame pixels.
     *
     * Bounded, unlike before: pan reaches the camera as padding, and padding is
     * taken out of the viewport it shifts within — see [DashCamera.MAX_PAN_FRACTION].
     */
    fun panBy(dx: Float, dy: Float) {
        followMode = false
        lastManualPanAt = System.currentTimeMillis()
        val maxX = DashEncoder.WIDTH * DashCamera.MAX_PAN_FRACTION
        val maxY = DashEncoder.HEIGHT * DashCamera.MAX_PAN_FRACTION
        panX = (panX + dx).coerceIn(-maxX, maxX)
        panY = (panY + dy).coerceIn(-maxY, maxY)
    }

    fun zoomIn() { zoom = (zoom + 1).coerceAtMost(ZOOM_MAX) }
    fun zoomOut() { zoom = (zoom - 1).coerceAtLeast(ZOOM_MIN) }
    fun toggleHeadingUp() { headingUp = !headingUp }
    fun recenter() { followMode = true; panX = 0f; panY = 0f }

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
     * Suspends rather than fire-and-forget so the previous frame loop can be
     * *joined* before the encoder it is drawing into is released — cancelling
     * it afterwards (as this used to) left the old loop calling `renderFrame`/
     * `drain` on a dead MediaCodec for one more frame, which surfaced as an
     * IllegalStateException storm and a pointless encoder rebuild. Only called
     * from the [sessionWatchJob] collector, which is already a suspend context.
     */
    private suspend fun startStream() {
        RideDiagnostics.log("stream", "startStream — encoder up, RTP→dash beginning")
        // Encoder/RTP output counters for the periodic health log below — reset per
        // startStream() call, same lifetime as everything else here (streamJob/encoder).
        var framesEncoded = 0
        var idrFramesEncoded = 0
        var rtpPacketsSent = 0
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

        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt); rtpPacketsSent++ }
        // endOfAU comes from NalProcessor, which knows which NAL closes the access unit —
        // this used to be hardcoded `true`, marking every packet. Harmless while each AU
        // was exactly one NAL, but wrong the moment an IDR goes out as separate
        // SPS/PPS/IDR packets: the marker bit has to land on the last one only.
        val nalProc = NalProcessor { nal, endOfAU ->
            packetizer.packetize(nal, endOfAU = endOfAU, ptsMs = videoPtsMs)
        }
        val onEncoded: (ByteArray, Boolean) -> Unit = { annexB, isKey ->
            framesEncoded++
            if (isKey) idrFramesEncoded++
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                RideDiagnostics.log("stream", "first video frame sent (key=$isKey, ${annexB.size}B)")
            }
            nalProc.process(annexB)
        }

        streamJob?.cancelAndJoin()
        streamJob = null

        encoder?.release()
        encoder = DashEncoder(onEncoded).also { it.prepare() }

        frameBitmap = Bitmap.createBitmap(DashEncoder.WIDTH, DashEncoder.HEIGHT, Bitmap.Config.ARGB_8888)
        lastSignature = ""
        camInit = false; lastTickNs = 0L
        haveFrame = false

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
        snapshots.prepare(style.json, DashEncoder.WIDTH, DashEncoder.HEIGHT)
        // Captured so [disconnect]'s release can only ever free THIS snapshotter,
        // never one a later connection has since prepared.
        snapshotGeneration = snapshots.currentGeneration()
        RideDiagnostics.log(
            "map",
            "style ${style.theme} from ${style.packs} pack(s), ${style.json.length / 1024} KiB",
        )

        session.startStreaming()

        streamJob = scope.launch(Dispatchers.Default) {
            var failures = 0
            var lastEncoderLogAt = System.currentTimeMillis()
            var lastRenderLogAt = lastEncoderLogAt
            var lastFrameSentAt = 0L
            var loggedFrames = 0; var loggedIdr = 0; var loggedRtp = 0
            // Declared outside the try/catch and assigned fresh once per iteration, so the
            // trailing delay() below can reuse the SAME value the PTS advance used — both must
            // agree on "how long is this frame", or the RTP timeline and the actual send cadence
            // drift apart from each other. Starts at the conservative (idle) interval; only
            // matters for a hypothetical exception inside tick() itself, before the real value
            // below gets assigned.
            var frameIntervalMs = 1000L / FPS_IDLE
            while (isActive && session.state.value == DashState.STREAMING) {
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
                        val encodeStart = System.currentTimeMillis()
                        enc.renderFrame { canvas -> canvas.drawBitmap(bmp, 0f, 0f, null) }
                        // Advance the presentation clock by THIS frame's interval BEFORE
                        // draining, so the frame(s) pulled this iteration carry an
                        // evenly-spaced RTP timestamp — see videoPtsMs's own doc above.
                        videoPtsMs += frameIntervalMs
                        enc.drain()
                        val sentAt = System.currentTimeMillis()
                        renderStats.frameSent(
                            intervalMs = if (lastFrameSentAt == 0L) 0L else sentAt - lastFrameSentAt,
                            encodeMs = sentAt - encodeStart,
                            intendedIntervalMs = frameIntervalMs,
                        )
                        lastFrameSentAt = sentAt
                    }
                    failures = 0
                    val now = System.currentTimeMillis()
                    if (now - lastRenderLogAt > RENDER_LOG_INTERVAL_MS) {
                        val elapsed = now - lastRenderLogAt
                        lastRenderLogAt = now
                        RideDiagnostics.log(
                            "map",
                            renderStats.drain(
                                periodMs = elapsed,
                                timeouts = snapshots.timeouts,
                                abandoned = snapshots.abandoned,
                                errors = snapshots.errors,
                            ),
                        )
                    }
                    if (now - lastEncoderLogAt > ENCODER_LOG_INTERVAL_MS) {
                        val dFrames = framesEncoded - loggedFrames
                        val dIdr = idrFramesEncoded - loggedIdr
                        val dRtp = rtpPacketsSent - loggedRtp
                        loggedFrames = framesEncoded; loggedIdr = idrFramesEncoded; loggedRtp = rtpPacketsSent
                        lastEncoderLogAt = now
                        val intervalS = ENCODER_LOG_INTERVAL_MS / 1_000
                        val thermal = thermalLabel()
                        if (dFrames == 0) {
                            DebugLog.w(TAG) {
                                "Encoder output: 0 frames in the last ${intervalS}s while STREAMING " +
                                    "— render/encode loop itself stalled (nothing to even send) — thermal=$thermal"
                            }
                        } else {
                            DebugLog.i(TAG) {
                                "Encoder output: frames=$dFrames (idr=$dIdr) rtp=$dRtp thermal=$thermal in the last ${intervalS}s"
                            }
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
                        lastSignature = ""
                        failures = 0
                    }
                }
                delay(frameIntervalMs)
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
        if (!followMode && System.currentTimeMillis() - lastManualPanAt > MANUAL_IDLE_MS) {
            panX = 0f; panY = 0f; followMode = true
        }

        val loc = locationTracker.location.value
        val riderLat = loc?.latitude
        val riderLng = loc?.longitude
        val heading = loc?.bearing ?: (if (camInit) camHdg else 0f)

        val fixAgeMs = loc?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        val gpsLost = loc == null || fixAgeMs > GPS_FIX_STALE_MS
        val gpsWeak = !gpsLost && (loc?.accuracy ?: 0f) > GPS_WEAK_ACCURACY_M

        // GPS/progress fields come from publishState's own read of the live sources now —
        // it is no longer this function's job to be their only supplier.
        publishState()

        // The frame is always a live map — idle is just the map with no route or
        // destination, never a separate mode (see spec/fsm.md). [navigating] still gates
        // what isn't frame content: DashSession's chrome/nav-info decisions.

        val haveTarget = riderLat != null || (destLat != null && destLng != null)
        val targetLat = riderLat ?: destLat ?: camLat
        val targetLng = riderLng ?: destLng ?: camLng

        val nowNs = System.nanoTime()
        val dt = if (lastTickNs == 0L) 0.042 else ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.5)
        lastTickNs = nowNs
        val a = if (camInit) (1.0 - exp(-dt / SMOOTH_TAU)) else 1.0

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
        val movedM = if (camInit) distMeters(prevLat, prevLng, camLat, camLng) else 0.0
        camMoving = movedM > 0.25 || (loc?.speed ?: 0f) > 0.8f

        val centerLat = if (haveTarget) camLat else 0.0
        val centerLng = if (haveTarget) camLng else 0.0
        val camHeading = if (haveTarget) camHdg else heading

        val sig = buildString {
            append("nav")
            append("%.6f".format(centerLat)); append("%.6f".format(centerLng))
            append(zoom); append(panX.toInt()); append(panY.toInt())
            append(if (headingUp) (camHeading * 10).toInt() else 0)
            append(remainingM?.let { (it / 100).toInt() } ?: -1)
            append(routePoints.size)
            append(gpsLost); append(gpsWeak)
        }
        val now = System.currentTimeMillis()
        if (sig != lastSignature || now - lastRedrawAt > FORCE_REDRAW_MS) {
            // Committed only if the frame was actually redrawn. Recording the
            // signature up front made a failed snapshot look like a drawn frame:
            // the same signature would not be retried, so a parked rider — whose
            // signature is stable — kept a stale frame until FORCE_REDRAW_MS, and
            // the telemetry counted it as a deliberate reuse.
            if (redrawFrame(
                    centerLat, centerLng, camHeading, riderLat != null, gpsWeak, gpsLost,
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
    private fun cameraFor(centerLat: Double, centerLng: Double, heading: Float): CameraPosition =
        CameraPosition.Builder()
            .target(LatLng(centerLat, centerLng))
            .zoom(zoom.toDouble())
            .bearing(if (headingUp) heading.toDouble() else 0.0)
            .tilt(if (headingUp) NAV_TILT_DEG else 0.0)
            .build()

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
        centerLat: Double, centerLng: Double, heading: Float,
        haveRider: Boolean, gpsWeak: Boolean, gpsLost: Boolean,
        frameIntervalMs: Long,
    ): Boolean {
        val bmp = frameBitmap ?: return false

        val snapshotStart = System.currentTimeMillis()
        val snapshot = snapshots.capture(
            cameraFor(centerLat, centerLng, heading),
            DashCamera.padding(DashEncoder.WIDTH, DashEncoder.HEIGHT, headingUp, panX, panY),
            if (haveFrame) SNAPSHOT_DEADLINE_MS else FIRST_SNAPSHOT_DEADLINE_MS,
        ) ?: return false
        val snapshotMs = System.currentTimeMillis() - snapshotStart

        val overlayStart = System.currentTimeMillis()
        val map = snapshot.bitmap
        val blank = MapSnapshotProvider.isBlank(map)
        val canvas = Canvas(bmp)
        canvas.drawBitmap(map, 0f, 0f, null)
        // Snapshot bitmaps are allocated natively and arrive one per redraw. Since
        // API 26 their pixels live outside the Java heap, so the GC feels no
        // pressure from them and would leave the free to a finalizer — at 631 KB a
        // frame that is gigabytes an hour growing without ever raising a Java OOM.
        // Safe to do here: the pipeline holds exactly one snapshot at a time and
        // its contents are already copied above.
        map.recycle()

        val frame = OverlayRenderer.Frame(
            headingUp = headingUp,
            heading = heading,
            riderLat = if (haveRider) camLat else null,
            riderLng = if (haveRider) camLng else null,
            destLat = destLat,
            destLng = destLng,
            destName = destName,
            route = routePoints,
            routeJam = routeJam,
            gpsWeak = gpsWeak,
            gpsLost = gpsLost,
        )
        // The projection comes off the snapshot that was just drawn, so overlays
        // and map can never disagree about where a coordinate is.
        overlays.draw(canvas, frame, MapProjection { lat, lng -> snapshot.pixelForLatLng(LatLng(lat, lng)) })

        renderStats.mapDrawn(
            snapshotMs = snapshotMs,
            overlayMs = System.currentTimeMillis() - overlayStart,
            budgetMs = frameIntervalMs,
            blank = blank,
        )
        if (!haveFrame) {
            haveFrame = true
            RideDiagnostics.log("map", "first map frame ready in ${snapshotMs}ms (blank=$blank)")
        }
        return true
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
        val loc = locationTracker.location.value
        val fixAgeMs = loc?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        val gpsLost = loc == null || fixAgeMs > GPS_FIX_STALE_MS
        val gpsWeak = !gpsLost && (loc?.accuracy ?: 0f) > GPS_WEAK_ACCURACY_M
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
                "navigating" to navigating,
                "hasGps" to (loc != null),
                "riderLat" to loc?.latitude,
                "riderLng" to loc?.longitude,
                "riderBearing" to (loc?.bearing ?: (if (camInit) camHdg else 0f)),
                // Ground speed straight from the fix, m/s. Published so Dart's NavEngine can
                // compute a real ETA — NavLoop used to pass a hardcoded 0, which made
                // NavEngine fall back to its 11 m/s constant for every estimate.
                "riderSpeed" to loc?.speed,
                "remainingKm" to remainingM?.let { it / 1000.0 },
                "offRoute" to offRoute,
                "gpsLost" to gpsLost,
                "gpsWeak" to gpsWeak,
                "errorMessage" to errorMessage,
                "followMode" to followMode,
                "headingUp" to headingUp,
                "zoom" to zoom,
                "nowPlayingTitle" to nowPlayingTitle,
                "incomingCaller" to incomingCaller,
                "hasActiveCall" to hasActiveCall,
            )
        )
    }
}
