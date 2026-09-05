package com.opendash.opendash_dash_engine.dash

import com.opendash.opendash_dash_engine.dash.protocol.DashCommands
import com.opendash.opendash_dash_engine.dash.protocol.K1GPacket
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

enum class DashState { IDLE, CONNECTING, AUTHENTICATING, READY, STREAMING, ERROR }

/**
 * Tripper Dash session, sequenced to match better-dash (tripper_app_like_nav.py):
 *   1. Open sockets (RX :2002 bound first).
 *   2. Send initial burst on :2000 (includes q3c.e request-auth).
 *   3. RX loop ingests 07 00 / 07 03 → sends q3c.d → waits for 07 01 01.
 *   4. Nav entry: route-card ×4 → projectionFrame → z2 (once) → route-card.
 *   5. Start RTP + 4 Hz projection heartbeat + 1 Hz route-card keep-alive.
 * The RX loop runs the WHOLE time, answering auth, 09 06 IDR-decoded acks,
 * and 09 00 button events.
 */
class DashSession(private val scope: CoroutineScope) {
    companion object {
        private const val TAG           = "DashSession"
        private const val AUTH_TIMEOUT  = 15_000L
        // While STREAMING a healthy dash keeps sending SOMETHING on :2002 (heartbeat replies,
        // 0C/0F telemetry, button events, ...) — silence this long means it's gone even if the
        // socket/WiFi link still looks fine locally (e.g. still "associated" but out of range,
        // or the dash itself powered off/hung). DashWifiManager's NetworkCallback never fires
        // for that case, so this is the only place that notices. NOT specifically about frame-
        // decode acks (09 06/04 55) — see idrAckCount's doc: a 2026-08-29 field session found
        // the map updating fine on a physical dash despite those acks going silent for most of
        // the ride, so whatever "silence" means for 09 06/04 55 isn't the same as "dash is gone".
        private const val RX_IDLE_TIMEOUT_MS = 10_000L
        private const val BURST_PAUSE   = 20L
        private const val PROJ_HB_MS     = 250L   // 4 Hz
        private const val ROUTE_CARD_MS  = 1_000L // 1 Hz keep-alive
        private const val HOSTNAME       = "OpenDash"
        // How often [launchAckCounterLog] reports the frame-decode-ack delta. Short enough to
        // catch a stall within a couple of minutes on a live ride, long enough not to add to
        // the per-frame log spam dispatchIncoming already avoids (see its "onlyAcks" filter).
        private const val ACK_LOG_INTERVAL_MS = 60_000L
    }

    private val _state = MutableStateFlow(DashState.IDLE)
    val state = _state.asStateFlow()

    /** Every transition goes through here so the log has one authoritative timeline. */
    private fun setState(next: DashState) {
        val prev = _state.value
        if (prev == next) return
        DebugLog.i(TAG) { "state $prev -> $next" }
        _state.value = next
    }

    // Touched from Main (disconnect), IO (runSession/rxJob) and Default (sendRtp off the
    // stream loop), so the write that clears it on teardown has to be visible to the others.
    @Volatile private var socket: DashSocket? = null
    private var auth: DashAuth? = null
    @Volatile private var authConfirmed = false

    /**
     * Retries of the `authRequest` handshake step after the dash answers `07 01` with a
     * rejection — a session-local counter, distinct from [DashEngineController]'s own
     * `authRetries`, which counts whole re-handshakes driven from its sessionWatchJob.
     */
    @Volatile private var authRejectRetries = 0

    var onButton: ((Byte) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null

    @Volatile var destinationName: String = "OpenDash"

    /**
     * Which session [runSession] — and everything it launched — is still allowed to speak for.
     * Bumped by every [connect] and [disconnect].
     *
     * A `runSession` cannot be stopped at an arbitrary point: its auth wait is a `delay(100)`
     * loop that runs for up to [AUTH_TIMEOUT], so a superseded one stays alive and can reach
     * [fail] long after a newer session has taken over. Without this token that stale [fail]
     * closes the LIVE session's socket and cancels its jobs — which is exactly what the
     * bounded auth retry in `DashEngineController` produces: a retry 1.5s after ERROR, against
     * a 15s timeout still counting down on the session it replaced.
     *
     * Checked by both teardown paths, [fail] and [endLink]. Socket identity — which [endLink]
     * also checks — is not a substitute: it protects the [socket] field alone, while both
     * methods additionally cancel the shared job fields, drive the state flow and fire
     * [onError]. It also cannot speak for the paths that have no socket to compare against, such
     * as a failed bind.
     */
    private val sessionSeq = AtomicInteger(0)

    // All of these are written on one thread and cancelled from another with no
    // happens-before edge between them — [rxJob]/[heartbeatJob]/[ackCounterJob] are written by
    // [runSession] on Dispatchers.IO and cancelled by [disconnect] on Main; the remaining four
    // are written by [startStreaming] on Main and cancelled by [endLink] on the RX coroutine's
    // IO thread. Plain fields let that cancel read a stale null and simply not fire, and two of
    // these loops do not check the session state at all ([launchStatusHeartbeat],
    // [launchAckCounterLog]), so an uncancelled one keeps sending at 1 Hz until the whole scope
    // goes away at plugin detach.
    @Volatile private var sessionJob: Job? = null
    @Volatile private var rxJob: Job? = null
    @Volatile private var projHbJob: Job? = null
    @Volatile private var routeCardJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var navInfoJob: Job? = null
    @Volatile private var mediaInfoJob: Job? = null

    /**
     * Counts the dash's own "I decoded a frame" notifies (09 06 55 IDR / 09 04 55 P-frame,
     * see [dispatchIncoming]). Added after a 2026-08-28 field session where the nav bubble
     * (glyph/distance, a separate TLV channel — see [launchNavInfo]) kept updating correctly for
     * tens of minutes while these acks went quiet, and confirming that required grepping raw TX
     * hex for `06 11`/`06 12` by hand — see [launchAckCounterLog] for the periodic log this feeds.
     *
     * CORRECTION (2026-08-30): originally documented here as "the only signal that the live map
     * video is actually landing on screen" — i.e. zero acks == frozen map. A 2026-08-29 field
     * session directly falsified that: the map updated fine on the physical dash for a whole
     * ride despite acks going to zero after the very first frame (see spec/video.md's "09 06/04
     * 55 — НЕ ack на каждый кадр"). So `09 06/04 55` is most likely a ONE-TIME "decoder opened"
     * milestone, not a per-frame heartbeat the way better-dash's naming implied — this counter
     * still tracks something real (whether/when the dash first confirms it's decoding at all),
     * just not "is the map frozen right now".
     */
    private val idrAckCount = AtomicInteger(0)
    private val pFrameAckCount = AtomicInteger(0)
    private var lastLoggedIdrAcks = 0
    private var lastLoggedPFrameAcks = 0
    @Volatile private var ackCounterJob: Job? = null
    // One-shot per session — pairs with DashEngineController's own "first video frame sent"
    // line; the gap between the two is this session's dash-side decode latency.
    @Volatile private var loggedFirstIdrAck = false

    @Volatile private var mediaTitle: String? = null
    @Volatile private var mediaAlbum = ""
    @Volatile private var mediaArtist = ""
    @Volatile private var callerName: String? = null

    fun updateNowPlaying(title: String?, album: String, artist: String) {
        mediaTitle = title?.takeIf { it.isNotBlank() }
        mediaAlbum = album
        mediaArtist = artist
    }

    fun updateCall(caller: String?) {
        callerName = caller?.takeIf { it.isNotBlank() }
    }

    // Live nav-info pushed to the dash bubble at ~1 Hz (set by NavEngine output).
    @Volatile private var navManeuver = DashCommands.NAV_MANEUVER_STRAIGHT
    @Volatile private var navPrimaryDist = 0
    @Volatile private var navPrimaryUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navTotalDist = 0
    @Volatile private var navTotalUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navEta: String? = null
    @Volatile private var navActive = false

    /** Push the latest turn-by-turn figures; sent to the dash at 1 Hz. */
    fun updateNavInfo(
        maneuver: Int, primaryDist: Int, primaryUnit: Int,
        totalDist: Int, totalUnit: Int, etaHHMM: String? = null,
    ) {
        navManeuver = maneuver
        navPrimaryDist = primaryDist
        navPrimaryUnit = primaryUnit
        navTotalDist = totalDist
        navTotalUnit = totalUnit
        navEta = etaHHMM
        navActive = true
    }

    /**
     * Route card with the LIVE nav figures patched in. The template's captured
     * values (7.9 km / glyph 0x3C / ETA 03:03) must never reach the dash once
     * real guidance is running — the card repeats at 1 Hz and would stomp the
     * activeNavPacket numbers every second.
     */
    private fun liveRouteCard(projectionOn: Boolean): ByteArray =
        if (navActive) DashCommands.routeCard(
            destinationName, projectionOn,
            maneuver = navManeuver,
            primaryUnit = navPrimaryUnit,
            totalDist = navTotalDist,
            totalUnit = navTotalUnit,
            etaHHMM = navEta,
        )
        else DashCommands.routeCard(destinationName, projectionOn)

    // ── Public API ────────────────────────────────────────────────────────

    fun connect(ssid: String, network: android.net.Network? = null) {
        if (_state.value != DashState.IDLE && _state.value != DashState.ERROR) return
        DebugLog.i(TAG) { "connect() — ssid='$ssid' network=$network" }
        // ERROR is a legal state to reconnect from, and `fail()` can reach it from the RX
        // coroutine while `runSession` is still sitting in its auth wait — so the previous
        // session is not necessarily finished just because the state says we may start a new
        // one. Cancel it, and hand the new one a token so that whatever the old one still does
        // between here and its next suspension point cannot tear this one down (see
        // [sessionSeq]). Cancel rather than cancelAndJoin: this is not a suspend function, and
        // the token makes the leftover harmless either way.
        sessionJob?.cancel()
        val seq = sessionSeq.incrementAndGet()
        sessionJob = scope.launch(Dispatchers.IO) { runSession(seq, ssid, network) }
    }

    fun startStreaming() {
        if (_state.value != DashState.READY) return
        // A live socket is as much a precondition as the state is: READY only means the
        // handshake finished, and a teardown racing the tail of runSession can leave the
        // state saying READY with nothing underneath it.
        if (socket == null) {
            DebugLog.w(TAG) { "startStreaming() with no socket — session was torn down" }
            return
        }
        setState(DashState.STREAMING)
        launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
        launchNavInfo()
        launchMediaInfo()
    }

    fun sendRtp(packet: ByteArray) { socket?.sendRtp(packet) }

    /**
     * Chrome (the route-card) always stays on, matching [enterNavMode] being the entry
     * sequence regardless of destination: the dash only opens its video decoder as part of
     * nav mode. Idle just means `name` is blank, so the card shows the "OpenDash"
     * placeholder with no live nav figures (`navActive` stays false), and the video plane
     * carries a live map with no route. See spec/fsm.md.
     */
    fun updateRouteCard(name: String) {
        destinationName = name.ifBlank { "OpenDash" }
        navActive = false   // new destination — old figures are stale until the next updateNavInfo
        if (_state.value == DashState.READY || _state.value == DashState.STREAMING) {
            scope.launch(Dispatchers.IO) {
                socket?.send(liveRouteCard(projectionOn = true))
            }
        }
    }

    fun disconnect() {
        // Cancel the session coroutine FIRST so it can't race past auth and flip state to
        // READY after we tear down (which would re-trigger streaming on a dead socket).
        // Bumping the token along with it closes the other half of the same window: cancel is
        // cooperative, so that coroutine still runs up to its next suspension point, and a
        // [fail] from there would put the session back into ERROR after this method has
        // deliberately left it IDLE.
        sessionSeq.incrementAndGet()
        sessionJob?.cancel(); sessionJob = null
        rxJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel(); heartbeatJob?.cancel()
        navInfoJob?.cancel(); mediaInfoJob?.cancel(); ackCounterJob?.cancel()
        navActive = false
        socket?.let {
            runCatching { it.send(DashCommands.projectionStop()) }
            runCatching { it.send(DashCommands.projectionOff()) }
            it.close()
        }
        socket = null
        setState(DashState.IDLE)
        DebugLog.i(TAG) { "Disconnected" }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private suspend fun runSession(seq: Int, ssid: String, network: android.net.Network? = null) {
        try {
            setState(DashState.CONNECTING)
            val sock = try {
                DashSocket(network).also { socket = it }
            } catch (e: java.net.BindException) {
                fail(seq, "Port ${DashSocket.RX_PORT}/${DashSocket.CTRL_PORT} in use (${e.message})")
                return
            }

            auth = DashAuth(ssid)
            authConfirmed = false
            authRejectRetries = 0
            idrAckCount.set(0); pFrameAckCount.set(0)
            lastLoggedIdrAcks = 0; lastLoggedPFrameAcks = 0
            loggedFirstIdrAck = false

            // RX loop MUST be running before the burst (early pubkey + no ICMP).
            launchReceiveLoop(seq, sock)
            // 1 Hz status heartbeat throughout the session.
            launchStatusHeartbeat(sock)
            // Periodic "is the dash actually decoding video" sanity check (see the field
            // reasoning on idrAckCount's doc above).
            launchAckCounterLog()

            setState(DashState.AUTHENTICATING)
            DebugLog.i(TAG) { "Sending initial burst…" }
            RideDiagnostics.log("auth", "initial burst sent — waiting up to ${AUTH_TIMEOUT}ms for 07 01 01")
            for (pkt in DashCommands.initialBurst(HOSTNAME)) {
                sock.send(pkt)
                delay(BURST_PAUSE)
            }

            DebugLog.i(TAG) { "Waiting up to ${AUTH_TIMEOUT}ms for auth (07 01 01)…" }
            val deadline = System.currentTimeMillis() + AUTH_TIMEOUT
            while (!authConfirmed && System.currentTimeMillis() < deadline) delay(100)

            if (!authConfirmed) {
                fail(seq, "Auth timed out — no 07 01 01 from dash. Check SSID matches '$ssid'.")
                return
            }
            DebugLog.i(TAG) { "Authenticated ✓" }
            RideDiagnostics.log("auth", "authenticated (07 01 01) — entering nav mode")

            // Always nav-mode entry, idle or not: the dash opens its video decoder only as
            // part of nav mode, so there is no separate idle mode (see spec/fsm.md).
            enterNavMode(sock)
            // Cancellation is cooperative and [enterNavMode]'s last suspension point is a
            // delay() several synchronous sends before this line, so a disconnect() landing in
            // that window would otherwise still flip the state to READY over an already
            // torn-down session. That matters because it is not merely a stale flag: the
            // sessionWatchJob collector (alive whenever the teardown came from wifiWatchJob
            // rather than the user) answers READY with startStream() -> STREAMING, which
            // cancels the give-up timer AND makes the guard in [connect] reject every later
            // reconnect, wedging the session for the rest of the ride.
            currentCoroutineContext().ensureActive()
            if (socket !== sock) {
                DebugLog.w(TAG) { "Session torn down during nav-mode entry — not signalling READY" }
                return
            }
            setState(DashState.READY)

        } catch (e: CancellationException) {
            // A cancelled session is a deliberate teardown, not a failure. CancellationException
            // is an ordinary Exception in Kotlin, so the catch below used to swallow it and call
            // fail() — which put the session back into ERROR, and fired onError, moments after
            // disconnect() had settled it at IDLE. The rider saw "CancellationException: …" on
            // the Dash screen after a clean, deliberate disconnect.
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, { "Session error" }, e)
            fail(seq, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Nav entry in the exact phone order (nav_open_ok.pcap):
     *   route-card ×4 (establishes destination) → projectionFrame
     *   → z2 once → route-card confirmation.
     */
    private suspend fun enterNavMode(sock: DashSocket) {
        sock.send(DashCommands.navContext()); delay(40)
        sock.send(DashCommands.emptyLists()); delay(40)

        repeat(4) {
            sock.send(DashCommands.routeCard(destinationName, projectionOn = false))
            delay(if (it < 1) 100 else 500)
        }
        sock.send(DashCommands.projectionFrame()); delay(60)
        sock.send(DashCommands.navPlaceholder()); delay(10)
        sock.send(DashCommands.navStart()); delay(40)                 // z2, ONCE
        sock.send(DashCommands.routeCard(destinationName, projectionOn = true))
        DebugLog.i(TAG) { "Nav mode kick sent" }
    }

    private fun launchReceiveLoop(seq: Int, sock: DashSocket) {
        var lastRxAtMs = System.currentTimeMillis()
        // Like every other launch* here — this was the one that only overwrote the field. A
        // previous RX loop is not a child of [sessionJob] (it is launched on the plugin scope),
        // so nothing else stops it, and it would sit in `receive()` on the old socket until
        // that socket errored — then run [endLink] against whatever session is current by then.
        rxJob?.cancel()
        rxJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pkt = try {
                    sock.receive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Link dropped (EBADF/ENETUNREACH) — end the loop cleanly instead of
                    // crashing the app; DashWifiManager handles reconnect.
                    DebugLog.w(TAG) { "RX loop stopped — socket error: ${e.message}" }
                    RideDiagnostics.log("error", "RX loop stopped — socket error: ${e.message}")
                    // Full teardown, exactly as the watchdog path below: reporting the error
                    // alone would leave the session in STREAMING with a dead socket, and the
                    // state guard in connect() would then refuse every reconnect until the
                    // rider disconnected by hand.
                    endLink(seq, sock)
                    break
                }
                if (pkt == null) {
                    // Timeout — not itself a problem (see DashSocket.RECV_TIMEOUT_MS), but
                    // repeated timeouts during STREAMING with no real packet in between mean
                    // the dash has gone silent (see RX_IDLE_TIMEOUT_MS's doc). Same teardown as
                    // the socket-error path above: tear down the session so a fresh `connect()`
                    // isn't blocked by the stale-socket state guard.
                    if (_state.value == DashState.STREAMING &&
                        System.currentTimeMillis() - lastRxAtMs > RX_IDLE_TIMEOUT_MS
                    ) {
                        DebugLog.w(TAG) { "RX loop stopped — no data from dash for over ${RX_IDLE_TIMEOUT_MS}ms" }
                        val silentS = (System.currentTimeMillis() - lastRxAtMs) / 1000
                        RideDiagnostics.log("error", "RX watchdog: dash silent ${silentS}s → link lost")
                        endLink(seq, sock)
                        break
                    }
                    continue
                }
                lastRxAtMs = System.currentTimeMillis()
                // Nothing downstream of here may escape: this coroutine's parent is the
                // plugin-wide scope, so an uncaught throw would cancel every other dash
                // coroutine with it and reach Android's default handler. Malformed or
                // hostile input (e.g. an over-long SSID overflowing the RSA block in
                // DashAuth.buildKeyPacket) must fail this session, not the whole engine.
                try {
                    dispatchIncoming(pkt, sock)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e(TAG, { "Error handling incoming packet" }, e)
                    fail(seq, "${e.javaClass.simpleName}: ${e.message}")
                    break
                }
            }
        }
    }

    /**
     * Ends the link the RX loop was serving: the periodic senders that write to
     * [sock], the socket itself, the session state, and the error report.
     *
     * Both ways the loop can end need every one of these. Reporting without the
     * teardown — which is what the socket-error path used to do — leaves the
     * session in STREAMING holding a dead socket: [onError] only publishes an
     * error message, it does not touch session state, and the give-up timer is
     * already cancelled by the time streaming starts. The stream loop then keeps
     * pushing RTP into a closed socket, and the `!= IDLE && != ERROR` guard in
     * [connect] refuses every reconnect until the rider disconnects by hand.
     *
     * [rxJob] is deliberately not cancelled here: the only callers are inside it,
     * and they break out of their own loop on return.
     */
    private fun endLink(seq: Int, sock: DashSocket) {
        // Unconditional, and first: [sock] belongs to this RX loop whether or not the session
        // it served is still the current one, and nothing else will close it.
        runCatching { sock.close() }
        // Everything past this point is state the LIVE session owns — the periodic senders, the
        // socket field, the state flow, the error callback — so it needs the same token guard as
        // [fail]. Socket identity alone (the check further down) covers only one of the four.
        if (seq != sessionSeq.get()) {
            DebugLog.w(TAG) { "RX loop of superseded session #$seq ended (now #${sessionSeq.get()}) — not reporting" }
            return
        }
        // Read before the teardown below sets IDLE itself: a deliberate disconnect()
        // closes the socket out from under the blocking receive(), so an exception
        // there is the EXPECTED way this loop ends and must not republish state with
        // explicitDisconnect back to false — that would leave a "Lost connection"
        // error hanging after a clean stop.
        val deliberate = _state.value == DashState.IDLE
        heartbeatJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel()
        navInfoJob?.cancel(); mediaInfoJob?.cancel(); ackCounterJob?.cancel()
        // Only if it is still ours: a reconnect that already replaced the field must
        // not have its live socket nulled out by the previous session's loop.
        if (socket === sock) socket = null
        setState(DashState.IDLE)
        if (deliberate) {
            DebugLog.i(TAG) { "RX loop ended after an explicit disconnect — not reporting" }
        } else {
            onError?.invoke("Lost connection to dash")
        }
    }

    private fun dispatchIncoming(pkt: ByteArray, sock: DashSocket) {
        val tlvs = K1GPacket.parseIncoming(pkt)
        // Dump the full raw packet for anything that ISN'T just the per-frame decode
        // acks (09 06 55 / 09 04 55) — those fire ~8×/s and would drown the log. This
        // captures joystick events, telemetry, and any unknown TLV in full hex so a
        // single `adb logcat -s DashSession` session is enough to reverse the protocol.
        val onlyAcks = tlvs.isNotEmpty() && tlvs.all {
            it.type == 0x09 && (it.sub == 0x06 || it.sub == 0x04) &&
                it.value.firstOrNull()?.toInt() == 0x55
        }
        if (!onlyAcks) DebugLog.i(TAG) { "RX RAW (${pkt.size}B): ${pkt.toHexFull()}" }
        for (tlv in tlvs) {
            // ── Auth (07 xx) ──
            if (tlv.type == 0x07) {
                when (val ev = auth?.ingest(tlv)) {
                    is AuthEvent.SendKey -> {
                        DebugLog.i(TAG) { "Got RSA pubkey — sending q3c.d" }
                        sock.send(ev.packet)
                    }
                    AuthEvent.Confirmed -> { authConfirmed = true }
                    AuthEvent.Rejected -> {
                        authRejectRetries++
                        DebugLog.w(TAG) { "Auth rejected — retry #$authRejectRetries" }
                        RideDiagnostics.log("auth", "REJECTED — retry #$authRejectRetries")
                        auth?.reset()
                        if (authRejectRetries <= 5) sock.send(DashCommands.authRequest())
                    }
                    else -> {}
                }
                continue
            }
            // ── 09 06 55: per-IDR frame-decoded notify → mandatory q3c.L2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x06 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                sock.send(DashCommands.frameDecodedIdr())
                idrAckCount.incrementAndGet()
                if (!loggedFirstIdrAck) {
                    loggedFirstIdrAck = true
                    RideDiagnostics.log("dash", "dash DECODED first IDR (09 06 55) — video accepted ✓")
                }
                continue
            }
            // ── 09 04 55: P-frame decoded → q3c.K2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x04 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                sock.send(DashCommands.frameDecodedP())
                pFrameAckCount.incrementAndGet()
                continue
            }
            // ── 09 00: button / joystick event → echo ack + notify UI ──
            if (tlv.type == 0x09 && tlv.sub == 0x00 && tlv.value.isNotEmpty()) {
                val btn = tlv.value.last()  // 0900 0001 <code>
                DebugLog.i(TAG) { "JOYSTICK 09 00 → code 0x${(btn.toInt() and 0xFF).toString(16).uppercase()}  full=${tlv.value.toHexFull()}" }
                sock.send(DashCommands.buttonAck(btn))
                scope.launch(Dispatchers.Main) { onButton?.invoke(btn) }
                continue
            }
            // ── 0F: vehicle-secure telemetry (AES-256-CBC under the session key,
            //    IV = first 16 bytes). The better-dash reference only logs these as
            //    ciphertext — we actually DECRYPT with our session key and log the
            //    plaintext for field-mapping (P1b). It arrives over our own session,
            //    so plain `adb logcat -s DashSession` captures it — no root, no
            //    monitor mode. ──
            //
            //    Working hypothesis for sub, from independent RE against the same
            //    Royal Enfield Tripper/K-Dash hardware (behavioural inference on
            //    the OFFICIAL app, no hex dump, no mention of the RSA/AES handshake
            //    we already have — so it doesn't confirm our crypto, only the field
            //    layout): 0F carries DEVICE IDENTITY, not trip telemetry —
            //      0F01 chassis number, 0F02 serial number, 0F05 BSSID (own WiFi
            //      AP's, 6B), 0F06 manufacturing date, 0F07 hardware version,
            //      0F08 part number/variant, 0F0A FOTA version. 0F03/0F04/0F09
            //      not covered by that source. 0x03 (separately, unmapped here
            //      too) is hypothesised as phone→dash SETTINGS SYNC: clock format,
            //      temp/distance/fuel units, theme, language, notification/POI
            //      toggles. Source: https://www.mihaiblaga.dev/reverse-engineering-royal-enfields-connected-bike-stack
            //      — treat as a lead to verify against our decrypted plaintext
            //      (chassis/serial should look ASCII-ish, 0F05 should be 6 raw
            //      bytes matching the dash's own BSSID), not as ground truth.
            if (tlv.type == 0x0F) {
                val key = auth?.sessionKey
                val plain = key?.let { aesDecryptCbc(tlv.value, it) }
                DebugLog.i(TAG) { "DASH TELEMETRY 0F sub=0x%02X enc(%dB)=%s  dec=%s".format(
                    tlv.sub, tlv.value.size, tlv.value.toHexFull(),
                    plain?.toHexFull() ?: "<key=${key != null}; decrypt failed>") }
                continue
            }
            // ── 0C xx: dash → app telemetry (trip/odo/fuel/temp — P1b). Still
            //    unmapped even by the independent RE above (0x0B/0x0C listed there
            //    as "present but not fully mapped" too) — no external lead here,
            //    this needs our own sweep. ──
            if (tlv.type == 0x0C) {
                DebugLog.i(TAG) { "DASH TELEMETRY 0C sub=0x%02X (%dB) val=%s"
                    .format(tlv.sub, tlv.value.size, tlv.value.toHexFull()) }
                continue
            }
            // Log every OTHER incoming event (e.g. joystick in nav view, or the dash's
            // 'exit navigation' selection) in FULL so its TLV can be identified + mapped.
            DebugLog.i(TAG) { "DASH EVENT type=0x%02X sub=0x%02X (%dB) val=%s"
                .format(tlv.type, tlv.sub, tlv.value.size, tlv.value.toHexFull()) }
        }
    }

    private fun launchStatusHeartbeat(sock: DashSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                runCatching { sock.send(DashCommands.heartbeat()) }
                // Keep the dash clock correct — it only shows what the phone feeds it.
                if (n++ % 30 == 0) runCatching { sock.send(DashCommands.timeSync()) }
                delay(1_000)
            }
        }
    }

    /**
     * Every [ACK_LOG_INTERVAL_MS], reports how many frame-decode acks (see [idrAckCount]'s doc)
     * went out since the last report — raw data only, logged at INFO regardless of the count.
     *
     * Used to be logged at WARNING with "dash has stopped decoding video (map likely frozen
     * on-screen)" when zero — a 2026-08-29 field session falsified that (map updated fine on a
     * physical dash for a whole ride with acks at zero the entire time past the first frame).
     * Zero here is apparently the NORMAL steady state, not a problem — see [idrAckCount]'s doc
     * and spec/video.md for the correction. Kept as plain info in case the pattern (0 vs nonzero,
     * or a session with literally none EVER) turns out to matter for something else later.
     */
    private fun launchAckCounterLog() {
        ackCounterJob?.cancel()
        ackCounterJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(ACK_LOG_INTERVAL_MS)
                if (_state.value != DashState.STREAMING) continue
                val idr = idrAckCount.get()
                val p = pFrameAckCount.get()
                val idrDelta = idr - lastLoggedIdrAcks
                val pDelta = p - lastLoggedPFrameAcks
                lastLoggedIdrAcks = idr
                lastLoggedPFrameAcks = p
                val intervalS = ACK_LOG_INTERVAL_MS / 1_000
                DebugLog.i(TAG) { "Frame decode acks: IDR=$idrDelta P=$pDelta in the last ${intervalS}s" }
            }
        }
    }

    private fun launchProjectionHeartbeat() {
        projHbJob?.cancel()
        projHbJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(DashCommands.projectionFrame())
                delay(PROJ_HB_MS)
            }
        }
    }

    private fun launchRouteCardKeepAlive() {
        routeCardJob?.cancel()
        routeCardJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(liveRouteCard(projectionOn = true))
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun launchNavInfo() {
        navInfoJob?.cancel()
        navInfoJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (navActive) {
                    socket?.send(
                        DashCommands.activeNavPacket(
                            maneuver = navManeuver,
                            primaryDist = navPrimaryDist,
                            primaryUnit = navPrimaryUnit,
                            totalDist = navTotalDist,
                            totalUnit = navTotalUnit,
                        )
                    )
                }
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun launchMediaInfo() {
        mediaInfoJob?.cancel()
        mediaInfoJob = scope.launch(Dispatchers.IO) {
            var previousCaller: String? = null
            while (isActive && _state.value == DashState.STREAMING) {
                val caller = callerName
                when {
                    caller != null -> runCatching { socket?.send(DashCommands.callNotify(caller)) }
                    previousCaller != null -> runCatching { socket?.send(DashCommands.callClear()) }
                }
                previousCaller = caller
                mediaTitle?.let { title ->
                    runCatching {
                        socket?.send(DashCommands.nowPlaying(title, mediaAlbum, mediaArtist))
                    }
                }
                delay(ROUTE_CARD_MS)
            }
        }
    }

    /**
     * Ends the session [seq] belongs to with an error — unless it is not the current one any
     * more, in which case this call is the residue of a session that has already been replaced
     * and must not touch anything.
     *
     * Every line below acts on shared state that the *live* session owns: the job fields, the
     * socket, the state flow, the error callback. Running them for a superseded session is the
     * bug this guard exists for — see [sessionSeq] for the retry timing that makes it routine
     * rather than theoretical.
     */
    private fun fail(seq: Int, msg: String) {
        if (seq != sessionSeq.get()) {
            DebugLog.w(TAG) { "ignoring fail from superseded session #$seq (now #${sessionSeq.get()}): $msg" }
            return
        }
        DebugLog.e(TAG, { "ERROR — $msg" })
        RideDiagnostics.log("error", "session fail: $msg")
        rxJob?.cancel(); heartbeatJob?.cancel(); mediaInfoJob?.cancel(); ackCounterJob?.cancel()
        socket?.close(); socket = null
        setState(DashState.ERROR)
        onError?.invoke(msg)
    }

    /** Full hex dump (no truncation) — used for protocol-capture logging. */
    private fun ByteArray.toHexFull(): String =
        joinToString(" ") { "%02X".format(it) }

    /** AES-256-CBC/PKCS5 decrypt of an [iv(16) ‖ ciphertext] blob under the session key. */
    private fun aesDecryptCbc(ivAndCt: ByteArray, key: ByteArray): ByteArray? = runCatching {
        if (ivAndCt.size <= 16) return null
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ivAndCt.copyOfRange(0, 16)),
        )
        cipher.doFinal(ivAndCt.copyOfRange(16, ivAndCt.size))
    }.getOrNull()
}
