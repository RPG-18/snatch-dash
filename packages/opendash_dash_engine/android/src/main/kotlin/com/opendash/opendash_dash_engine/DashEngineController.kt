package com.opendash.opendash_dash_engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.ToneGenerator
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
    }

    private val dashConfig = DashConfig.get(context)
    private val wifiManager = DashWifiManager(context, scope)
    private val session = DashSession(scope)
    private val locationTracker = LocationTracker(context)
    private val tileProvider = TileProvider(context, scope)
    private val mapRenderer = MapRenderer(tileProvider)
    private val idleRenderer = DashIdleRenderer()
    private var toneGenerator: ToneGenerator? = null
    private val mediaInfo = MediaInfoProvider(context)
    private val callController = CallController(context)

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
        DashKeepAliveService.start(context)
        session.onButton = { code -> onButton?.invoke(code.toInt() and 0xFF) }
        session.onError = { msg -> publishState(errorMessage = msg) }
        locationTracker.start()

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
                publishState()
                if (wifi.status == WifiConnStatus.CONNECTED && !sessionStarted) {
                    sessionStarted = true
                    val resolvedSsid = if (ssid.isNotBlank()) ssid else wifi.ssid
                    session.connect(resolvedSsid, wifiManager.network)
                }
                if (wifi.status != WifiConnStatus.CONNECTED) sessionStarted = false
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
                publishState()
                if (st == DashState.READY) startStream()
            }
        }

        startMediaForwarding()
    }

    fun disconnect() {
        streamJob?.cancel(); streamJob = null
        sessionWatchJob?.cancel(); sessionWatchJob = null
        wifiWatchJob?.cancel(); wifiWatchJob = null
        stopMediaForwarding()
        session.disconnect()
        wifiManager.disconnect()
        locationTracker.stop()
        encoder?.release(); encoder = null
        DashKeepAliveService.stop(context)
        publishState()
    }

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
                session.updateCall(call?.takeIf { it.incoming }?.caller)
                incomingCaller = call?.caller
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
    }

    fun clearDestination() {
        destName = null; destLat = null; destLng = null
        navigating = false; routePoints = emptyList(); routeJam = emptyList()
        session.updateRouteCard("OpenDash")
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
        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt) }
        val nalProc = NalProcessor { nal, _ ->
            packetizer.packetize(nal, endOfAU = true, wallClockMs = System.currentTimeMillis())
        }
        val onEncoded: (ByteArray, Boolean) -> Unit = { annexB, _ -> nalProc.process(annexB) }

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

        if (!navigating) {
            tickIdle()
            return
        }

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
     * Idle mode: no destination, so no camera math — just the wallpaper (or
     * the standby "waiting" frame when none is set). Signature is keyed off
     * the wallpaper fields instead of GPS, and [camMoving] is pinned false so
     * the frame loop settles at [FPS_IDLE].
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
    ) {
        onState(
            mapOf(
                "stage" to session.state.value.name,
                "wifiStatus" to wifiManager.state.value.status.name,
                "wifiSsid" to wifiManager.state.value.ssid,
                "wifiError" to wifiManager.state.value.error,
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
            )
        )
    }
}
