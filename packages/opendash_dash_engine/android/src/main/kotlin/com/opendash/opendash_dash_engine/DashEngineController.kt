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
import com.opendash.opendash_dash_engine.dash.map.GeoPoint
import com.opendash.opendash_dash_engine.dash.map.LocationTracker
import com.opendash.opendash_dash_engine.dash.map.MapRenderer
import com.opendash.opendash_dash_engine.dash.map.TileProvider
import com.opendash.opendash_dash_engine.dash.protocol.DashCommands
import com.opendash.opendash_dash_engine.dash.video.DashEncoder
import com.opendash.opendash_dash_engine.dash.video.DashIdleRenderer
import com.opendash.opendash_dash_engine.dash.video.DashWallpaperFit
import com.opendash.opendash_dash_engine.dash.video.DashWallpaperKind
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
import java.io.File
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
    }

    private val dashConfig = DashConfig.get(context)
    private val wifiManager = DashWifiManager(context, scope)
    private val session = DashSession(scope)
    private val locationTracker = LocationTracker(context, scope)
    private val tileProvider = TileProvider(context, scope)
    private val mapRenderer = MapRenderer(tileProvider)
    private val idleRenderer = DashIdleRenderer()
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
    // "no traffic data for this route", MapRenderer then falls back to a solid line.
    @Volatile private var routeJam: List<Int> = emptyList()
    @Volatile private var remainingM: Double? = null
    @Volatile private var offRoute = false

    // ── Idle wallpaper (shown when not navigating), pushed from Dart ──
    @Volatile private var wallpaperPath: String? = null
    @Volatile private var wallpaperKind: DashWallpaperKind? = null
    @Volatile private var wallpaperFit: DashWallpaperFit = DashWallpaperFit.CROP
    @Volatile private var wallpaperBiasX = 0f
    @Volatile private var wallpaperBiasY = 0f
    @Volatile private var wallpaperRevision = 0

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
    @Volatile private var zoom = 17
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
        if (lat != null && lng != null) tileProvider.prefetch(lat, lng)
        // Dart's button dispatcher (idle-wallpaper vs nav-mode button mapping)
        // reads [navigating] off the state stream — push immediately instead of
        // waiting for the next frame-loop tick() so a button press right after
        // "Send to Dash" sees the right mode.
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
            // MapRenderer's solid-line fallback then kicks in (spec/yande_ruote.md).
            routeJam = if (jamSegments.size == points.size - 1) jamSegments else emptyList()
            tileProvider.prefetchRoute(points)
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

    fun panBy(dx: Float, dy: Float) {
        followMode = false
        lastManualPanAt = System.currentTimeMillis()
        panX += dx; panY += dy
    }

    fun zoomIn() { zoom = (zoom + 1).coerceAtMost(20) }
    fun zoomOut() { zoom = (zoom - 1).coerceAtLeast(11) }
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

    /**
     * Idle-mode dash background — rendered by [idleRenderer] whenever there's
     * no active destination. [path] is a pre-rendered PNG (images) or the raw
     * source file (GIF/video); Dart owns the picking/cropping (see
     * `DashWallpaperStore`), this just displays whatever it produced.
     */
    fun setWallpaper(path: String?, kind: String?, fit: String?, biasX: Float, biasY: Float) {
        wallpaperPath = path
        wallpaperKind = kind?.let { runCatching { DashWallpaperKind.valueOf(it) }.getOrNull() }
        wallpaperFit = fit?.let { runCatching { DashWallpaperFit.valueOf(it) }.getOrNull() }
            ?: DashWallpaperFit.CROP
        wallpaperBiasX = biasX
        wallpaperBiasY = biasY
        wallpaperRevision++
        // DashIdleRenderer fails silent on a bad path (blank/near-black idle frame,
        // no exception) — log what actually landed here so that failure mode shows
        // up somewhere instead of only being inferrable from tiny H.264 keyframes.
        val exists = path?.let { File(it).exists() }
        DebugLog.i(TAG) { "setWallpaper: path=$path kind=$wallpaperKind fit=$wallpaperFit exists=$exists" }
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
        idleRenderer.release()
        tileProvider // no explicit release needed; memory cache is GC'd
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

        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt); rtpPacketsSent++ }
        val nalProc = NalProcessor { nal, _ ->
            packetizer.packetize(nal, endOfAU = true, wallClockMs = System.currentTimeMillis())
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

        session.startStreaming()
        locationTracker.location.value?.let { tileProvider.prefetch(it.latitude, it.longitude) }

        streamJob = scope.launch(Dispatchers.Default) {
            var lastPrefetch = 0L
            var failures = 0
            var lastEncoderLogAt = System.currentTimeMillis()
            var loggedFrames = 0; var loggedIdr = 0; var loggedRtp = 0
            while (isActive && session.state.value == DashState.STREAMING) {
                try {
                    tick()
                    val bmp = frameBitmap
                    val enc = encoder
                    if (bmp != null && enc != null) {
                        enc.renderFrame { canvas -> canvas.drawBitmap(bmp, 0f, 0f, null) }
                        enc.drain()
                    }
                    failures = 0
                    val now = System.currentTimeMillis()
                    if (now - lastPrefetch > 20_000) {
                        lastPrefetch = now
                        locationTracker.location.value?.let { tileProvider.prefetch(it.latitude, it.longitude) }
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
                delay(1000L / (if (camMoving) FPS_MOVING else FPS_IDLE))
            }
        }
    }

    private fun tick() {
        if (!followMode && System.currentTimeMillis() - lastManualPanAt > MANUAL_IDLE_MS) {
            panX = 0f; panY = 0f; followMode = true
        }

        val loc = locationTracker.location.value
        val riderLat = loc?.latitude
        val riderLng = loc?.longitude
        val heading = loc?.bearing ?: (if (camInit) camHdg else 0f)

        val fixAgeMs = loc?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        val gpsLost = loc == null || fixAgeMs > 4_000L
        val gpsWeak = !gpsLost && (loc?.accuracy ?: 0f) > 25f

        publishState(
            hasGps = loc != null,
            riderLat = riderLat,
            riderLng = riderLng,
            riderBearing = heading,
            remainingKm = remainingM?.let { it / 1000.0 },
            offRoute = offRoute,
            gpsLost = gpsLost,
            gpsWeak = gpsWeak,
        )

        // EXPERIMENT (2026-08-28, unverified on hardware) — see tickIdle()'s doc for why this
        // no longer branches there. [navigating] still gates everything ELSE that isn't frame
        // content (DashButtonController's zoom-vs-wallpaper-cycle mapping, DashSession's own
        // chrome/nav-info decisions) — only the render path changed here.

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
            lastSignature = sig
            lastRedrawAt = now
            redrawFrame(centerLat, centerLng, camHeading, riderLat != null, gpsWeak, gpsLost)
        }
    }

    /**
     * SUPERSEDED, no longer called from [tick] (2026-08-28) — kept for now as the fallback to
     * revert to if the replacement doesn't pan out on hardware, not dead for its own sake.
     *
     * This rendered the wallpaper photo/GIF from [DashWallpaperStore] whenever idle. It always
     * ran into the same wall documented on [DashSession.enterIdleProjectionMode] (also
     * superseded, same date): the video plane never became visible without the dash's
     * route-card chrome, and adding that chrome back only showed the chrome, not the video
     * underneath. [tick] now takes the opposite approach — found in a sibling fork's source
     * (`OpenMotoDash/NorthStar`, see spec/wifi_retry_policy.md's "Из живого форка" for how that
     * fork was found): its dash session ALWAYS enters nav-mode chrome, idle or not, and idle
     * itself is just a plain map with an empty route/destination rather than a distinct
     * chrome-free mode. Adopted here as the same idea, but genuinely untested combination on
     * THIS dash — the two prior on-hardware rounds both still fed static/faked content (a
     * wallpaper bitmap, then a wallpaper bitmap + faked nav telemetry) through nav-mode chrome;
     * neither tried what this does, a real live self-consistent map (no route, matching what the
     * chrome now always says) as the video content. Whether that's what was actually missing, or
     * this dash's firmware just never shows video without genuine turn-by-turn data flowing, is
     * exactly what's unverified — needs an on-hardware ride to confirm either way.
     *
     * `DashWallpaperStore`/the Settings screen's wallpaper gallery still exist and still push
     * `setWallpaper()` down here — they're just not read by anything right now. Left alone
     * pending the hardware verification above, not a decision to remove the feature.
     */
    private fun tickIdle() {
        camMoving = false
        val sig = "idle:$wallpaperPath:$wallpaperKind:$wallpaperFit:$wallpaperBiasX:$wallpaperBiasY:$wallpaperRevision"
        val now = System.currentTimeMillis()
        if (sig != lastSignature || now - lastRedrawAt > FORCE_REDRAW_MS) {
            lastSignature = sig
            lastRedrawAt = now
            val bmp = frameBitmap ?: return
            idleRenderer.draw(
                Canvas(bmp), wallpaperPath, wallpaperKind, wallpaperBiasX, wallpaperBiasY, wallpaperFit,
            )
        }
    }

    private fun redrawFrame(
        centerLat: Double, centerLng: Double, heading: Float,
        haveRider: Boolean, gpsWeak: Boolean, gpsLost: Boolean,
    ) {
        val bmp = frameBitmap ?: return
        val canvas = Canvas(bmp)
        val frame = MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            panX = panX,
            panY = panY,
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
        mapRenderer.draw(canvas, frame)
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

    private fun publishState(
        hasGps: Boolean? = null,
        riderLat: Double? = null,
        riderLng: Double? = null,
        riderBearing: Float? = null,
        remainingKm: Double? = null,
        offRoute: Boolean? = null,
        gpsLost: Boolean? = null,
        gpsWeak: Boolean? = null,
        errorMessage: String? = null,
        explicitDisconnect: Boolean = false,
    ) {
        onState(
            mapOf(
                "stage" to session.state.value.name,
                "explicitDisconnect" to explicitDisconnect,
                "wifiStatus" to wifiManager.state.value.status.name,
                "wifiSsid" to wifiManager.state.value.ssid,
                "wifiError" to wifiManager.state.value.error,
                // Idle-wallpaper vs active-navigation, per [setDestination]/[clearDestination] —
                // the same source of truth the frame loop's tick()/tickIdle() branch on. Dart's
                // button dispatcher needs this to replicate DashViewModel.isIdleWallpaperMode().
                "navigating" to navigating,
                "hasGps" to hasGps,
                "riderLat" to riderLat,
                "riderLng" to riderLng,
                "riderBearing" to riderBearing,
                "remainingKm" to remainingKm,
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
