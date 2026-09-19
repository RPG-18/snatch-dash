package com.opendash.opendash_dash_engine

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.PowerManager
import com.opendash.opendash_dash_engine.dash.DashConfig
import com.opendash.opendash_dash_engine.dash.FrameStreamer
import com.opendash.opendash_dash_engine.dash.StreamThreads
import com.opendash.opendash_dash_engine.dash.DashKeepAliveService
import com.opendash.opendash_dash_engine.dash.DashSession
import com.opendash.opendash_dash_engine.dash.DashState
import com.opendash.opendash_dash_engine.dash.DashWifiManager
import com.opendash.opendash_dash_engine.dash.WifiConnStatus
import com.opendash.opendash_dash_engine.dash.map.DashCameraState
import com.opendash.opendash_dash_engine.dash.map.GeoPoint
import com.opendash.opendash_dash_engine.dash.map.gpsFlags
import com.opendash.opendash_dash_engine.dash.map.LocationTracker
import com.opendash.opendash_dash_engine.dash.map.MapFrameRenderer
import com.opendash.opendash_dash_engine.dash.map.MapSnapshotProvider
import com.opendash.opendash_dash_engine.dash.map.MapStyleAssembler
import com.opendash.opendash_dash_engine.dash.map.MapTheme
import com.opendash.opendash_dash_engine.dash.map.OverlayRenderer
import com.opendash.opendash_dash_engine.dash.protocol.DashGlyphs
import com.opendash.opendash_dash_engine.dash.video.DashEncoder
import com.opendash.opendash_dash_engine.media.CallController
import com.opendash.opendash_dash_engine.media.CallInfoProvider
import com.opendash.opendash_dash_engine.media.MediaInfoProvider
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import com.opendash.opendash_dash_engine.util.memorySummary
import com.opendash.opendash_dash_engine.util.monotonicMs
import com.opendash.opendash_dash_engine.dash.DashChrome
import com.opendash.opendash_dash_engine.dash.DashSocket
import com.opendash.opendash_dash_engine.dash.NavFigures
import com.opendash.opendash_dash_engine.dash.NowPlaying
import com.opendash.opendash_dash_engine.dash.SessionEvent
import com.opendash.opendash_dash_engine.dash.protocol.DashCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.withContext

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

        /**
         * How often the `[mem]` line is written.
         *
         * A minute, the same period the stream's own health line uses, and for the same
         * reason: often enough to show a trend across a ride, rare enough that walking
         * /proc/self/smaps for it is free. Its own constant rather than a borrowed one —
         * this sampler spans the whole connection, while the stream's line belongs to the
         * loop that moved out to [FrameStreamer].
         */
        private const val MEM_SAMPLE_INTERVAL_MS = 60_000L

        /**
         * How often the FRAME TICK is allowed to publish engine state to Dart.
         *
         * Everything else — session transitions, WiFi status, media and call updates,
         * setDestination, errors — publishes the moment it happens and does not consult
         * this: a banner about a lost link that arrives a second late is a regress, and
         * those calls are rare by nature.
         *
         * The tick is the opposite: it fired at the frame rate, 4 Hz while moving, and each
         * call builds a ~25-key map and hands it to the platform channel on MAIN — the same
         * thread the loop then waits on for its MapLibre snapshot. So the loop was queueing
         * work in front of the thing it was about to block on, four times a second, for a
         * screen that is off for the whole ride (pipeline.md §4.6).
         *
         * 1 Hz is enough for what actually reads those fields. `NavLoop` caches rider
         * position and speed from the stream and runs its own `Timer.periodic(1s)`, so it
         * discarded three publishes out of four already; the Dash screen's marker and
         * compass are only looked at with the phone in hand. The cost is staleness: NavLoop's
         * tick can now work from a position up to a second old instead of 250 ms — about
         * 19 m at 90 km/h, against a 60 m off-route threshold (`NavEngine._offRouteM`) and a
         * 15 s reroute cooldown. Named here so the next reader does not have to re-derive it.
         */
        private const val TICK_PUBLISH_INTERVAL_MS = 1_000L

    }

    private val dashConfig = DashConfig.get(context)
    private val wifiManager = DashWifiManager(context, scope)
    /**
     * The connection the engine is speaking over right now, or null between connections.
     *
     * A [DashSession] is one-shot (see its own doc), so "reconnect" means a new object here.
     * A StateFlow rather than a plain field because the collectors below have to follow the
     * swap: `flatMapLatest` drops the old session's state and events the instant this
     * changes, which is what used to need a `sessionSeq` token checked inside the session.
     */
    private val current = MutableStateFlow<DashSession?>(null)

    /**
     * What the dash's repeating cards say — destination, turn figures, media, call.
     *
     * Owned here and not by the session, because Flutter pushes these at arbitrary times,
     * including while nothing is connected. When they lived on the session, every reconnect
     * started from defaults and the first cards of a new session showed "OpenDash" with no
     * guidance until Dart happened to push again.
     */
    private val chrome = MutableStateFlow(DashChrome())

    /** IDLE when there is no session — which is exactly what "no session" looks like. */
    private val sessionState: DashState
        get() = current.value?.state?.value ?: DashState.IDLE
    private val locationTracker = LocationTracker(context, scope)
    private val styleAssembler = MapStyleAssembler(context)

    private val snapshots = MapSnapshotProvider(context)
    private val overlays = OverlayRenderer()
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

    private var streamJob: Job? = null
    private var sessionWatchJob: Job? = null
    private var sessionEventJob: Job? = null

    /** Serialises open against close, so "one live session" is a property and not a hope. */
    private val sessionLock = Mutex()

    /**
     * Whether the session now current ever completed its handshake.
     *
     * Read when one fails: a failure after READY says something about the ride, a failure
     * before it can also say the SSID was wrong — see the Failed branch.
     */
    @Volatile private var sessionReachedReady = false

    /** The pending re-handshake, if one is due — see [scheduleAuthRetry]. */
    private var authRetryJob: Job? = null

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
     * one [Destination], points and jam inside one [RouteGeometry], and
     * [MapFrameRenderer.advance] reads `inputs.value` ONCE per frame. There is nothing left
     * to tear.
     *
     * Written with [MutableStateFlow.update] rather than by assignment so a read-modify-write
     * cannot lose a concurrent one. Camera state is deliberately NOT here — it flows both
     * ways, so it has a class of its own: [DashCameraState].
     */
    private val inputs = MutableStateFlow(DashInputs())

    /**
     * Where the map camera is pointing, and the joystick controls that move it.
     *
     * Outlives a stream on purpose — a reconnect mid-ride resumes on the zoom and the centre
     * the rider left — which is exactly why it is here and not inside [MapFrameRenderer].
     */
    private val cameraState = DashCameraState(DashEncoder.WIDTH, DashEncoder.HEIGHT, ::monotonicMs)

    /**
     * The map side of the CURRENT stream, or null between streams.
     *
     * Freed when the stream's job completes, not at the next [startStream] — see the
     * completion handler there. It used to be held until the next stream because the debug
     * screen could still be reading the last frame; that screen was deleted on 2026-09-18,
     * so holding a ~631 KB native bitmap through a disconnect now buys nothing, and a ride
     * that ends with a disconnect may never have a next stream at all.
     *
     * `@Volatile` because Main writes it ([startStream]) while the stream job's completion
     * handler reads and clears it, normally on `dash-frame`. What actually ORDERS the two is
     * the `cancelAndJoin()` at the top of [startStream]; the identity check in that handler
     * only picks the right renderer, it publishes nothing. Said out loud because the field
     * this replaced carried the same modifier with the same explanation, and losing it in a
     * move is how a race gets introduced by nobody. Found by review, 2026-09-18.
     */
    @Volatile private var frameRenderer: MapFrameRenderer? = null

    /**
     * True while a teardown we ASKED for is in flight — [disconnect] sets it before it
     * cancels anything.
     *
     * The stream job's completion handler reports a loop that ended on its own (see it), and
     * on the ordinary disconnect path the session has not left STREAMING yet by the time the
     * loop unwinds — so without this flag every deliberate «Отключить» would write a warning
     * about a stream nobody lost, and close the session a beat before the caller does. @Volatile: written on Main, read on `dash-frame`.
     */
    @Volatile private var stoppingDeliberately = false

    // Which snapshotter this stream prepared, so [disconnect] releases that one
    // and not whatever a later connection has since put in its place.
    private var snapshotGeneration = 0L

    var onButton: ((Int) -> Unit)? = null


    // ── Public API (invoked by the plugin's MethodChannel handler) ────────

    fun connect() {
        // Idempotent on a live connection, which spec/fsm.md already promises for the
        // `idleMap → navigating` transition ("connect() — no-op"). The guard used to live one
        // layer down, in DashSession.connect; a one-shot session has no such method, so this
        // is now the only place it can be. Without it the whole cycle ran again on a connected
        // dash. That re-request tore down the WiFi link the running session's sockets are bound
        // to (see DashWifiManager.connect's own guard), reset [hasConnectedOnce] and
        // [authRetries], opened a second ride file mid-ride and restarted media forwarding —
        // for a rider, "Send to Dash" killed the picture ~10s later. Nothing is lost by
        // returning here: [setDestination] pushes the new destination to the session itself.
        val sessionState = sessionState
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
        // branch needs sessionStarted; neither fires, so the wreck is never closed and the
        // guard above later reads it as a live connection. The old session then pushes RTP
        // into a dead socket until the RX watchdog notices
        // (RX_IDLE_TIMEOUT_MS = 10s) and the retry after it adds AUTH_RETRY_DELAY_MS: about
        // 11.5 seconds of frozen picture on the dash after a "Send to Dash".
        //
        // Reachable because both ends land on Main: DashWifiManager's onLost/onUnavailable
        // (requestNetwork is given a main-looper Handler) flip WifiState and return, while the
        // collector that would tear the session down is a separate coroutine resumed on the
        // next dispatch — and a method-channel call from Dart gets in between. Same treatment
        // the collector's own else-branch gives, just from the path that overtook it.
        // Not closed here: this method runs on Main and cannot wait. The close goes into
        // the WiFi collector below, ahead of its collect loop — see there for why it must
        // be that coroutine and not a `scope.launch` of its own. Held by reference, not as
        // a flag, because the session collector below also needs to know WHICH session is
        // the wreck: it must not act on that session's replayed state.
        val wreck: DashSession? = if (sessionLive) current.value else null
        if (wreck != null) {
            RideDiagnostics.warn(
                "connect",
                "connect() over a $sessionState session whose link is $linkStatus — " +
                    "it will be closed once the WiFi request is in, before any new session",
            )
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
                delay(MEM_SAMPLE_INTERVAL_MS)
            }
        }
        sessionEventJob?.cancel()
        sessionEventJob = scope.launch {
            current.flatMapLatest { it?.events ?: emptyFlow() }.collect { ev ->
                when (ev) {
                    is SessionEvent.Button -> onButton?.invoke(ev.code)
                    is SessionEvent.Failed -> {
                        publishState(errorMessage = ev.reason)
                        // A handshake that was refused over a working link, on a name no
                        // rider ever typed, is evidence about the name: that is what a
                        // different Royal Enfield in range looks like. Only this layer can
                        // tell — the Wi-Fi layer sees a healthy association either way.
                        // Narrowly [handshakeRefused], because a socket error or a taken
                        // port would otherwise blacklist the CORRECT SSID for the rest of
                        // the connection.
                        if (ev.handshakeRefused && !sessionReachedReady &&
                            wifiManager.usingScanGuess
                        ) {
                            wifiManager.rejectScanGuess()
                        }
                    }
                    // Not an error to show the rider: the link and our sockets are fine, the
                    // dash stopped talking. The IDLE it comes with is what drives the retry
                    // in the state collector below.
                    SessionEvent.DashSilent ->
                        RideDiagnostics.log("session", "dash went silent — reconnecting")
                    // Persisted HERE and not when the Wi-Fi link came up, because this is
                    // the first moment the name is known to be right: the dash checks the
                    // SSID inside the encrypted handshake, so a session that reaches Ready
                    // has had it accepted. A guess taken from scan results (see
                    // DashWifiManager.resolvePrefixFromScan) is never written before this
                    // point — otherwise one stale `RE_*` entry would be remembered across
                    // restarts and would disable discovery for good.
                    SessionEvent.Ready -> {
                        sessionReachedReady = true
                        // The handshake completed, so a name taken from a scan has just
                        // stopped being a hypothesis — see DashWifiManager.confirmScanGuess
                        // for what leaving it open costs later in the same connection.
                        wifiManager.confirmScanGuess()
                        val live = wifiManager.state.value.ssid
                        if (dashConfig.needsDiscovery && live.isNotBlank() &&
                            live != dashConfig.ssidPrefix
                        ) {
                            dashConfig.ssid = live
                            RideDiagnostics.log("connect", "dash accepted SSID '$live' — remembered")
                        }
                    }
                }
            }
        }
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
        // Including the pending re-handshake: a retry left over from the previous attempt
        // would otherwise open a session in the middle of this method, behind the back of
        // the teardown above and of [sessionStarted], which this method has just cleared.
        authRetryJob?.cancel(); authRetryJob = null
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
            // The wreck from `connect()` above is closed HERE, in the coroutine that will
            // open the replacement, and before that coroutine starts collecting. Sequential
            // code is the ordering: no collect step — and so no [openSession] — can run
            // until this returns. It used to be a separate `scope.launch`, on the theory
            // that [sessionLock] orders the close before the open; the lock serialises,
            // it does not order. Had the link come up before that launch reached the
            // lock, [openSession] would have found the wreck still READY/STREAMING,
            // refused it as a live session, and left [sessionStarted] false — then the
            // close would land, [current] would go null, and the IDLE that follows takes
            // the retry branch, which needs sessionStarted. Nothing opens; the connect
            // dies at the 120 s give-up timer.
            //
            // After the WiFi request, not before it: the request is fire-and-forget, so
            // the radio negotiates while the sockets close, and a CONNECTED that arrives
            // meanwhile is simply the StateFlow's current value when collect begins.
            if (wreck != null) closeSession(farewell = false)
            wifiManager.state.collect { wifi ->
                RideDiagnostics.log(
                    "wifi",
                    wifi.status.toString() +
                        (wifi.ssid.takeIf { it.isNotBlank() }?.let { " ssid=$it" } ?: "") +
                        (wifi.error?.let { " err=$it" } ?: ""),
                )
                publishState()
                if (wifi.status == WifiConnStatus.CONNECTED && !sessionStarted) {
                    val resolvedSsid = if (ssid.isNotBlank()) ssid else wifi.ssid
                    // From the result, not before it: [openSession] can refuse when a
                    // session is still running, and setting the flag regardless would claim
                    // a session that does not exist — leaving recovery to the retry branch
                    // for no reason.
                    sessionStarted = openSession(resolvedSsid)
                } else if (wifi.status != WifiConnStatus.CONNECTED && sessionStarted) {
                    // The network the running session's sockets are bound to (via
                    // Network.bindSocket) is gone — whether WifiManager is about to retry
                    // (REQUESTING) or has given up (ERROR), that session is now sending
                    // into a dead network and will never notice on its own (see
                    // DashSocket.send/sendRtp — failures there are swallowed, not fatal).
                    // Tear it down so the `connect()` branch above starts a fresh one,
                    // bound to whatever network reconnect eventually resolves.
                    sessionStarted = false
                    closeSession(farewell = false)
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
            // No distinctUntilChanged. It was here to suppress repeats, and a StateFlow
            // already does that within one session — so the only thing the operator added
            // was comparing ACROSS sessions, which is exactly wrong: a fresh session that
            // fails in open() (a BindException, now that SO_REUSEADDR is gone) publishes
            // ERROR while the collector's last value is the PREVIOUS session's ERROR, and
            // the transition is dropped. No retry fires, nothing is published to Dart, and
            // the connection dies silently at the 120 s give-up timer. The intermediate
            // `current.value = null` does not save it: closing an already-dead session
            // never suspends, so flatMapLatest is never given the chance to collect it.
            // The wreck reads as "no session". Its real state is READY or STREAMING — that
            // is what made it a wreck rather than a finished session — and a replay of
            // that READY into this fresh collector ran [startStream] in full (style parse,
            // snapshotter, MediaCodec, sender threads) on a session the WiFi collector was
            // closing at that very moment, then all of it again for the replacement. As
            // IDLE it takes the retry branch, which needs [sessionStarted] and so does
            // nothing; the WiFi collector closes it and [current] moves on.
            current
                .flatMapLatest { s ->
                    if (s == null || s === wreck) flowOf(DashState.IDLE) else s.state
                }
                .collect { st ->
                RideDiagnostics.log("session", "→ $st")
                publishState()
                if (st == DashState.STREAMING) cancelGiveupTimer() else armGiveupTimer()
                // A session that is getting somewhere makes a pending retry pointless, and
                // firing it anyway is what tore down three good handshakes on 2026-09-19:
                // each one had authenticated in ~200 ms and was still walking the 1790 ms
                // of nav-entry pauses when the next retry landed.
                if (st != DashState.IDLE && st != DashState.ERROR) {
                    authRetryJob?.cancel()
                    authRetryJob = null
                }
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
                    // IDLE alongside ERROR, and for the same reason: a link that died under a
                    // live WiFi network ends the session at IDLE, not ERROR — see
                    // [SessionEvent.DashSilent], which says why the two are different findings.
                    // That is spec/wifi_retry_policy.md's scenario C, the RX watchdog
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
                    // The window used to be wider: the session published CONNECTING from
                    // inside its own coroutine, so this collector could see the corpse of a
                    // session [wifiWatchJob] had already restarted, and cancel a handshake
                    // 1.5 s into its life every single time. A session is CONNECTING from the
                    // moment [DashSession.open] returns, and [current] swaps atomically, so
                    // this collector cannot be handed a corpse at all any more — with one
                    // exception, handled in the flatMapLatest above: on a `connect()` over a
                    // wreck, [current] still IS the wreck when this collector starts, and the
                    // replayed READY/STREAMING is the wreck's.
                    DashState.ERROR, DashState.IDLE -> scheduleAuthRetry()
                    else -> {}
                }
            }
        }

        startMediaForwarding()
    }

    /**
     * Start a connection, replacing whatever was running.
     *
     * The close comes first and is awaited, and that ordering IS the invariant: two live
     * sessions cannot exist, because a second one is not created until the first has stopped
     * its coroutines and closed its sockets. Without the wait it is not merely untidy — the
     * sockets bind fixed ports :2000 and :2002, and with SO_REUSEADDR gone a leftover one
     * makes this throw instead of quietly taking half the dash's traffic.
     *
     * No farewell: every caller here is reconnecting because the link or the handshake
     * failed, so the two farewell packets could only be written into a socket nobody reads.
     */
    private suspend fun openSession(ssid: String): Boolean = withContext(Dispatchers.IO) {
        // The whole thing on IO, and the lock INSIDE it — that order is what keeps
        // [dispose] from deadlocking. Every caller here is a collector on
        // Dispatchers.Main; if the lock were taken on Main, releasing it would need Main
        // to be free, and `dispose`'s runBlocking parks Main for exactly as long as it is
        // waiting for that lock. Acquired and released on IO, nothing in the chain needs
        // the main thread at all.
        //
        // It also puts the transport back where it used to live. [DashSession.open] runs
        // the factory inline — three socket binds, three `Network.bindSocket` binder calls
        // and the synchronous ride-file append in DashSocket.reportSocketOptions — and
        // before stage 4 all of that happened inside a coroutine already on IO.
        sessionLock.withLock {
            // Refuse to replace a session that is still getting somewhere, and do it HERE,
            // inside the lock, where the answer cannot go stale between the question and
            // the act. The retry in the state collector asks the same question before its
            // 1.5 s wait, and on 2026-09-19 that was not enough: three sessions in a row
            // authenticated in ~200 ms and were torn down by the next retry a second later,
            // because reaching READY also needs the 1790 ms of captured nav-entry pauses.
            // The dash had started answering; the app spent five seconds destroying its own
            // successful handshakes.
            val live = current.value
            if (live != null &&
                live.state.value != DashState.IDLE &&
                live.state.value != DashState.ERROR
            ) {
                RideDiagnostics.log(
                    "connect",
                    "not replacing a session that is still ${live.state.value} — letting it finish",
                )
                return@withLock false
            }
            closeCurrent(farewell = false)
            sessionReachedReady = false
            current.value = DashSession.open(
                ssid = ssid,
                chrome = chrome,
                clock = ::monotonicMs,
                parent = scope.coroutineContext.job,
                // The plugin's handler, carried over deliberately: the session's
                // SupervisorJob keeps one dying child from taking its siblings, but an
                // unhandled throw still reaches Android's default handler — a crash
                // mid-ride — unless the handler travels with the context.
                context = Dispatchers.IO +
                    (scope.coroutineContext[CoroutineExceptionHandler] ?: EmptyCoroutineContext),
            ) { DashSocket(wifiManager.network) }
            true
        }
    }

    /** Ends the current session, if any, and waits for it. Safe to call with none. */
    private suspend fun closeSession(farewell: Boolean) = withContext(Dispatchers.IO) {
        sessionLock.withLock { closeCurrent(farewell) }
    }

    /**
     * The close itself, without the lock — for [openSession], which already holds it.
     *
     * [current] is cleared AFTER the close returns, not before. Clearing first looks tidier
     * and breaks the invariant: a concurrent [openSession] would read null, skip the wait,
     * and build a [DashSocket] over ports the dying session still has bound — a
     * `BindException` now that SO_REUSEADDR is gone.
     */
    private suspend fun closeCurrent(farewell: Boolean) {
        val live = current.value ?: return
        live.close(farewell)
        current.value = null
    }

    /**
     * Stop everything and tell the dash we are going.
     *
     * Suspends now, and every caller had to change for it. The farewell —
     * `projectionStop` + `projectionOff` — is two datagrams that must actually leave before
     * the socket closes, or the dash sits in projection on its last frame until its own
     * timeout, which a rider reads as "the map froze". The previous version reached for
     * `runBlocking` on the main thread to get that; waiting properly is the same guarantee
     * without stalling the UI.
     */
    suspend fun disconnect() {
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
        // cooperative: the frame loop keeps running on its own `dash-frame` thread until its next
        // suspension point, and [FrameStreamer.run]'s finally releases the encoder. Dropping
        // the reference is what let the NEXT [startStream] skip its cancelAndJoin() — it would
        // see null, join nothing, and install a fresh encoder that the still-unwinding old loop
        // then released out from under it, leaving `encoder` null for the whole session: a
        // frozen dash with no error anywhere, since the session does reach STREAMING and the
        // give-up timer is cancelled. Keeping the reference costs nothing (joining an already
        // finished Job returns at once) and restores the invariant [startStream] documents.
        stoppingDeliberately = true
        streamJob?.cancel()
        sessionWatchJob?.cancel(); sessionWatchJob = null
        sessionEventJob?.cancel(); sessionEventJob = null
        authRetryJob?.cancel(); authRetryJob = null
        wifiWatchJob?.cancel(); wifiWatchJob = null
        sessionStarted = false
        stopMediaForwarding()
        // NonCancellable because this method is reachable FROM the collectors it cancels two
        // lines above — the READY branch's failure path and the give-up timer both call it —
        // and a farewell that is cancelled halfway is the exact failure it exists to prevent.
        withContext(NonCancellable) { closeSession(farewell = true) }
        wifiManager.disconnect()
        locationTracker.stop()
        // The encoder is NOT released here. cancel() above is cooperative — the frame
        // loop runs on `dash-frame` and only stops at its next suspension point,
        // so releasing from this thread raced with renderFrame/drain on a dead
        // MediaCodec: an IllegalStateException storm, and at failures >= 3 the loop
        // would rebuild an encoder nobody owns any more. [startStream] already fixed
        // its half of that race with cancelAndJoin(); this half is fixed by ownership
        // instead — [FrameStreamer] releases the encoder in its own finally as it unwinds,
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

    /**
     * Arrange one more handshake on the same Wi-Fi, [AUTH_RETRY_DELAY_MS] from now.
     *
     * In its own job rather than as a `delay` inside the state collector, and that is the
     * whole point. Collecting is sequential, so a `delay` there stops the collector: the
     * stale IDLEs a closing session leaves in `flatMapLatest`'s buffer each cost 1.5 s, and
     * the READY of the session that eventually works is read seconds after it happened. A
     * job also has the property the inline wait could not have — it can be cancelled the
     * moment the session starts getting somewhere, which is what the collector now does.
     *
     * One at a time: a second stale IDLE must not queue a second attempt behind the first.
     */
    private fun scheduleAuthRetry() {
        if (authRetryJob?.isActive == true) return
        val wifi = wifiManager.state.value
        if (!sessionStarted || wifi.status != WifiConnStatus.CONNECTED) return
        if (authRetries >= MAX_AUTH_RETRIES) return
        authRetryJob = scope.launch {
            delay(AUTH_RETRY_DELAY_MS)
            // Re-read rather than trust the values above: the wait is long enough for the
            // link to go away and for the session to have recovered on its own.
            val settled = wifiManager.state.value
            if (settled.status != WifiConnStatus.CONNECTED) return@launch
            if (sessionState != DashState.IDLE && sessionState != DashState.ERROR) return@launch
            // Counted only when a session was actually opened: [openSession] refuses while
            // the previous attempt is still alive, and a retry that cost nothing must not
            // spend one of the four.
            // [settled], not the name captured before the wait: prefix discovery can
            // resolve the exact SSID during those 1.5 s, and retrying with the stale prefix
            // would hand the handshake a name the dash refuses.
            if (openSession(settled.ssid)) authRetries++
        }
    }

    /** Start the give-up countdown if it isn't already running (idempotent) — see
     *  RECONNECT_GIVEUP_MS's doc. */
    private fun armGiveupTimer() {
        if (giveupJob?.isActive == true) return
        giveupJob = scope.launch {
            delay(RECONNECT_GIVEUP_MS)
            if (sessionState != DashState.STREAMING) {
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
                updateNowPlaying(np?.title, np?.album.orEmpty(), np?.artist.orEmpty())
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
                updateCall(incoming?.caller)
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
        setRouteCard(name ?: "OpenDash")
        // DashSession reads [DashInputs.navigating] off the state stream for its own
        // chrome/nav-info decisions — push immediately instead of waiting for
        // the next frame-loop tick() so "Send to Dash" takes effect at once.
        publishState()
    }

    fun clearDestination() {
        inputs.update {
            it.copy(destName = null, dest = null, navigating = false, route = RouteGeometry())
        }
        setRouteCard("OpenDash")
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
            chrome.update {
                it.copy(nav = NavFigures(maneuver, pv, pu, tv, tu, etaHHMM))
            }
        }
    }

    // The joystick: the state, the clamping and the lines they log all live in
    // [DashCameraState] now, because the frame loop mutates the same fields from the other
    // side (pipeline.md §4.8 — "the camera needs a home anyway").
    //
    // Each publishes straight after, and that is not decoration: `zoom`, `headingUp` and
    // `followMode` reach Dart ONLY through [publishState], and until 2026-09-19 they rode
    // out on the frame tick — which is now throttled to 1 Hz. Without these calls the
    // compass button on the Dash screen would sit on its old icon for up to a second after a
    // tap, because it renders from engine state with no optimistic update. Found by review.
    //
    // [panBy] is the exception, and deliberately: pan reaches MapLibre as padding and is not
    // a published field at all, while a drag gesture can call it at frame rate — publishing
    // there would hand back the cost this throttle just removed.
    fun setFollowMode(enabled: Boolean) {
        cameraState.setFollowMode(enabled)
        publishState()
    }

    fun panBy(dx: Float, dy: Float) = cameraState.panBy(dx, dy)

    fun zoomIn() {
        cameraState.zoomIn()
        publishState()
    }

    fun zoomOut() {
        cameraState.zoomOut()
        publishState()
    }

    fun toggleHeadingUp() {
        cameraState.toggleHeadingUp()
        publishState()
    }

    fun recenter() {
        cameraState.recenter()
        publishState()
    }

    fun forgetDash() { dashConfig.forgetDash() }
    fun setSsid(ssid: String) { dashConfig.ssid = ssid.trim() }
    fun setWifiPassword(password: String) { dashConfig.password = password }

    fun updateNowPlaying(title: String?, album: String, artist: String) {
        val playing = title?.takeIf { it.isNotBlank() }?.let { NowPlaying(it, album, artist) }
        chrome.update { it.copy(nowPlaying = playing) }
    }

    fun updateCall(caller: String?) {
        chrome.update { it.copy(caller = caller?.takeIf { c -> c.isNotBlank() }) }
    }

    /**
     * Point the dash's card at [name] and push one card immediately.
     *
     * Immediately, rather than waiting up to a second for the next tick, because this is what
     * "Send to Dash" looks like from the rider's seat. The nav figures are cleared with it:
     * a new destination makes the previous route's distances and glyph wrong, and the card
     * repeats at 1 Hz, so stale figures would be asserted every second until Dart pushed new
     * ones. Chrome stays on regardless of destination — the dash only opens its decoder as
     * part of nav mode, so there is no separate idle mode (spec/fsm.md).
     */
    private fun setRouteCard(name: String) {
        val title = name.ifBlank { "OpenDash" }
        chrome.update { it.copy(destinationName = title, nav = null) }
        val live = current.value ?: return
        if (live.state.value == DashState.READY || live.state.value == DashState.STREAMING) {
            live.send(DashCommand.RouteCard(title, projectionOn = true))
        }
    }

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

    /**
     * The plugin is detaching and the scope goes away on the next line, so this is the one
     * place `runBlocking` is the right tool: there is no coroutine left to hand the farewell
     * to.
     *
     * It blocks the main thread, so what it waits on has to be incapable of needing Main
     * back. That is a property of the code, not of a timer: [openSession] and
     * [closeSession] take [sessionLock] inside `withContext(Dispatchers.IO)`, so the lock
     * is acquired and released without a Main dispatch, and [DashSession] runs its own
     * coroutines on IO too.
     *
     * A `withTimeoutOrNull` stood here for exactly one review cycle and was removed as a
     * lie: [disconnect] does its teardown inside `withContext(NonCancellable)`, which
     * ignores cancellation by definition, so the timeout could not end the wait it claimed
     * to bound. What actually bounds this is one layer down — `DashSession.close` gives the
     * farewell `FAREWELL_TIMEOUT_MS` and then cancels regardless, and the transport closes
     * as the scope cancels rather than after it (see the closer child there), so the join
     * cannot sit on a blocking `receive`.
     */
    fun dispose() {
        runBlocking { disconnect() }
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
        stoppingDeliberately = false

        streamJob?.cancelAndJoin()
        streamJob = null

        // No release of the previous renderer here, deliberately: `cancelAndJoin` above
        // means its job has completed, so its completion handler has already freed the
        // bitmap and cleared the field. A renderer built for a stream that never launched is
        // freed by the guard further down. One owner per path, instead of five sites each
        // guarded differently. Found by review, 2026-09-18.

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
        // [DashCameraState.applyPackFloor], which also explains why the value from a
        // PMTiles header is clamped rather than trusted.
        val zoomFloor = cameraState.applyPackFloor(style.minZoom)
        snapshots.prepare(style.json, DashEncoder.WIDTH, DashEncoder.HEIGHT)
        // Captured so [disconnect]'s release can only ever free THIS snapshotter,
        // never one a later connection has since prepared.
        snapshotGeneration = snapshots.currentGeneration()
        RideDiagnostics.log(
            "map",
            "style ${style.theme} from ${style.packs} pack(s), ${style.json.length / 1024} KiB, " +
                "zoom ${DashCameraState.zoomText(zoomFloor)}-${DashCameraState.zoomText(DashCameraState.ZOOM_MAX)}" +
                (if (zoomFloor > DashCameraState.ZOOM_MIN) " (packs stop at z${style.minZoom}, floor raised)" else "") +
                ", render ${(DashEncoder.WIDTH * MapSnapshotProvider.PIXEL_RATIO).toInt()}×" +
                "${(DashEncoder.HEIGHT * MapSnapshotProvider.PIXEL_RATIO).toInt()}" +
                "@${MapSnapshotProvider.PIXEL_RATIO}",
        )

        // Cleared so the first tick snaps to the rider instead of smoothing towards them
        // from where the previous stream left the camera.
        cameraState.beginStream()
        val renderer = MapFrameRenderer(
            inputs = inputs,
            location = locationTracker.location,
            trusted = locationTracker.trusted,
            camera = cameraState,
            snapshots = snapshots,
            overlays = overlays,
            frameWidth = DashEncoder.WIDTH,
            frameHeight = DashEncoder.HEIGHT,
            onTick = ::publishStateFromTick,
        )
        frameRenderer = renderer

        // Two threads of this stream's own, instead of the shared pools — see
        // [StreamThreads] for what that buys and what it only ASKS for. Created here so the
        // frame loop below launches straight onto its own thread rather than migrating to it.
        //
        // Everything from here to `launch` is under one guard, and that is the point: the
        // renderer (with its 631 KB bitmap) is already built, `StreamThreads()` starts a
        // HandlerThread and an executor, `prepareEncoder()` configures a MediaCodec, and
        // `startStreaming()` launches four senders on a socket that may be torn down under
        // it. Any of them can throw — `HandlerThread.start()` under the memory pressure this
        // project instruments for, `configure()` on a codec the system will not give us —
        // and `startStream` then propagates to the session collector, which calls
        // `disconnect()`. That path deliberately releases NONE of this, and with no job
        // there is no completion handler either, so every failed attempt used to leak a
        // thread pair, a configured codec, a Surface and a bitmap. The guard used to cover
        // only `prepareEncoder()` while its own comment claimed it covered the gap.
        // Found by review, 2026-09-18.
        var threads: StreamThreads? = null
        var streamer: FrameStreamer? = null
        try {
            threads = StreamThreads()
            streamer = FrameStreamer(
                source = renderer,
                encoderFactory = { onEncoded -> DashEncoder(onEncoded).also { it.prepare() } },
                rtpSender = { current.value?.rtpSender() },
                streaming = { sessionState == DashState.STREAMING },
                thermal = ::thermalLabel,
                clock = ::monotonicMs,
                senderContext = threads.rtp,
                threadsReport = threads::report,
            )
            // Built here, not inside the loop, so a codec that refuses to configure still
            // throws on THIS path — where the session collector catches it and disconnects.
            // Inside the launched job the same failure would be an uncaught exception in a
            // coroutine and the dash would sit in READY behind a stream that never started.
            streamer.prepareEncoder()
            current.value?.startStreaming()
        } catch (e: Throwable) {
            streamer?.releaseEncoder()
            threads?.close()
            renderer.release()
            if (frameRenderer === renderer) frameRenderer = null
            throw e
        }

        val stream = streamer
        val streamThreads = threads
        streamJob = scope.launch(streamThreads.frame) { stream.run() }
        // From the completion handler, not from inside the job: quitting a looper from a
        // coroutine still running on it would be pulling the floor up as we walk off it.
        // This is the ONE place a finished stream is taken apart — the job's own `finally`
        // used to release the encoder here as well, and covered nothing this does not.
        //
        // It has to be here rather than in the job, because `launch` on a Handler dispatcher
        // merely POSTS: a job cancelled before its body is ever dispatched runs no code at
        // all, and `close()` below then quits the looper, guaranteeing it never will. That
        // path leaked a configured MediaCodec and its input Surface per stream.
        streamJob?.invokeOnCompletion {
            stream.releaseEncoder()
            // The renderer's bitmap too, and only from here: [disconnect] cancels the loop
            // cooperatively and cannot join it (it runs on Main, and the loop may be
            // suspended inside a snapshot that needs Main), so recycling from there would
            // race a live `Canvas(bmp)`. Here the loop is finished by definition.
            // Guarded by identity: a fast reconnect may already have installed the next
            // stream's renderer in the field, and freeing THAT one would blank the dash.
            renderer.release()
            if (frameRenderer === renderer) frameRenderer = null
            streamThreads.close()
            // The loop is gone; if the session still thinks it is STREAMING, nobody will
            // ever notice. `sessionWatchJob` arms the give-up timer on state CHANGES, and
            // the change to STREAMING is what cancelled it — so a loop that ends by itself
            // (a failed encoder rebuild, an Error that `catch (Exception)` does not catch)
            // leaves a dash frozen on its last frame while Dart keeps reporting "connected",
            // with heartbeats holding the link up and the RX watchdog quiet because the dash
            // is still answering. Handing the session to IDLE puts it back under the
            // collector's own retry path, which knows how to restart a dead session on a
            // live link. Found by review, 2026-09-18.
            if (!stoppingDeliberately && sessionState == DashState.STREAMING) {
                RideDiagnostics.warn(
                    "stream",
                    "frame loop ended while the session still reports STREAMING — " +
                        "ending the session so it can be retried",
                )
                scope.launch { closeSession(farewell = true) }
            }
        }
    }

    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters >= 1000) (((meters / 100).toInt())) to DashGlyphs.NAV_UNIT_KM_TENTHS
        else meters.toInt() to DashGlyphs.NAV_UNIT_METERS

    /**
     * When the frame tick last published — see [TICK_PUBLISH_INTERVAL_MS].
     *
     * Touched only from the frame thread (the tick is the sole caller), and deliberately NOT
     * reset per stream: between two streams the session goes through CONNECTING, READY and
     * STREAMING, and each of those publishes on its own, so the first tick of a new stream
     * has nothing fresh to say.
     */
    private var lastTickPublishAt = 0L

    /**
     * [publishState] on the frame's cadence, throttled — the tick's entry point, and only
     * the tick's.
     */
    private fun publishStateFromTick() {
        val now = monotonicMs()
        if (now - lastTickPublishAt < TICK_PUBLISH_INTERVAL_MS) return
        lastTickPublishAt = now
        publishState()
    }

    /**
     * Publishes one snapshot of engine state to Dart.
     *
     * The GPS/progress fields are read from the live sources here rather than taken as
     * parameters. They used to be nullable parameters that only the frame tick ever filled in, so
     * every other caller — WiFi status changes, session transitions, media/call forwarding,
     * setDestination — published them as null, which Dart's `fromMap` turns into
     * false/null and writes over the last good values (the state object is replaced
     * wholesale, not merged). The visible result was the Dash screen's rider marker and
     * GPS chips flickering, and no GPS status at all before STREAMING, since the frame tick
     * — the only source of those values — runs only while streaming.
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
        val gps = gpsFlags(loc, locationTracker.trusted.value)
        onState(
            mapOf(
                "stage" to sessionState.name,
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
                "riderBearing" to (loc?.bearing ?: (if (cameraState.initialised) cameraState.heading else 0f)),
                // Ground speed straight from the fix, m/s. Published so Dart's NavEngine can
                // compute a real ETA — NavLoop used to pass a hardcoded 0, which made
                // NavEngine fall back to its 11 m/s constant for every estimate.
                "riderSpeed" to loc?.speed,
                "remainingKm" to inp.remainingM?.let { it / 1000.0 },
                "offRoute" to inp.offRoute,
                "gpsLost" to gps.lost,
                "gpsWeak" to gps.weak,
                "errorMessage" to errorMessage,
                "followMode" to cameraState.followMode,
                "headingUp" to cameraState.headingUp,
                // In MapLibre's units, not the hundredths the field is stored
                // in: the key is named `zoom` and documented as such in
                // opendash_dash_engine.dart, and 1400 under that name would be
                // read as a zoom of 1400 by whoever first consumes it.
                "zoom" to cameraState.zoom / DashCameraState.ZOOM_SCALE,
                "nowPlayingTitle" to inp.nowPlayingTitle,
                "incomingCaller" to inp.incomingCaller,
                "hasActiveCall" to inp.hasActiveCall,
            )
        )
    }
}
