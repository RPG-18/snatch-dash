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

    private var socket: DashSocket? = null
    private var auth: DashAuth? = null
    @Volatile private var authConfirmed = false
    @Volatile private var authRetries = 0

    var onButton: ((Byte) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null

    @Volatile var destinationName: String = "OpenDash"

    private var sessionJob: Job? = null
    private var rxJob: Job? = null
    private var projHbJob: Job? = null
    private var routeCardJob: Job? = null
    private var heartbeatJob: Job? = null
    private var navInfoJob: Job? = null
    private var mediaInfoJob: Job? = null

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
    private var ackCounterJob: Job? = null
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
        sessionJob = scope.launch(Dispatchers.IO) { runSession(ssid, network) }
    }

    fun startStreaming() {
        if (_state.value != DashState.READY) return
        setState(DashState.STREAMING)
        launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
        launchNavInfo()
        launchMediaInfo()
    }

    fun sendRtp(packet: ByteArray) { socket?.sendRtp(packet) }

    /**
     * EXPERIMENT (2026-08-28, unverified on hardware) — chrome (route-card) used to be turned
     * off entirely when idle (`name` blank/"OpenDash"), via a projectionStop/Off/Frame/On
     * sequence, in an attempt to get a chrome-free wallpaper showing underneath. That never
     * worked (see [enterIdleProjectionMode]'s doc) — chrome now always stays on, same as
     * [enterNavMode] always being the entry sequence regardless of destination. Idle just means
     * `name` is blank, so the card shows the "OpenDash" placeholder with no live nav figures
     * (`navActive` stays false) — matching a sibling fork's approach of never having a distinct
     * chrome-free mode at all (see [enterIdleProjectionMode]'s doc for where that was found).
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

    private suspend fun runSession(ssid: String, network: android.net.Network? = null) {
        try {
            setState(DashState.CONNECTING)
            val sock = try {
                DashSocket(network).also { socket = it }
            } catch (e: java.net.BindException) {
                fail("Port ${DashSocket.RX_PORT}/${DashSocket.CTRL_PORT} in use (${e.message})")
                return
            }

            auth = DashAuth(ssid)
            authConfirmed = false
            authRetries = 0
            idrAckCount.set(0); pFrameAckCount.set(0)
            lastLoggedIdrAcks = 0; lastLoggedPFrameAcks = 0
            loggedFirstIdrAck = false

            // RX loop MUST be running before the burst (early pubkey + no ICMP).
            launchReceiveLoop(sock)
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
                fail("Auth timed out — no 07 01 01 from dash. Check SSID matches '$ssid'.")
                return
            }
            DebugLog.i(TAG) { "Authenticated ✓" }
            RideDiagnostics.log("auth", "authenticated (07 01 01) — entering nav mode")

            // Always nav-mode entry now, idle or not — see enterIdleProjectionMode's doc.
            enterNavMode(sock)
            setState(DashState.READY)

        } catch (e: Exception) {
            DebugLog.e(TAG, { "Session error" }, e)
            fail("${e.javaClass.simpleName}: ${e.message}")
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

    /**
     * SUPERSEDED, no longer called (2026-08-28) — kept as the fallback to revert to if the
     * replacement below doesn't pan out on hardware.
     *
     * This used to be idle-wallpaper mode: open projection without route-card/nav-start chrome,
     * so a chrome-free wallpaper could show underneath while [enterNavMode] handled active
     * navigation separately.
     *
     * KNOWN BROKEN (2026-08-15) — three on-hardware rounds, all failed:
     *   1. projectionFrame/projectionOn only, no route-card/z2 — wallpaper
     *      frames encode/send fine (RTP has zero failures) but never appear;
     *      the dash just never opens its decoder.
     *   2. Added [enterNavMode]'s exact pcap-verified entry (route-card ×4 →
     *      projectionFrame → navPlaceholder → z2 → confirming route-card).
     *      Decoder opened, but the dash rendered ONLY its route-card chrome
     *      (title/glyph/distance bubble) full-screen on a blank background —
     *      video plane never became visible.
     *   3. Also faked `navActive = true` so `launchNavInfo` would stream
     *      `activeNavPacket` telemetry too, on the theory that a route-card
     *      with no live telemetry reads to the firmware as a static
     *      route-preview screen. No change — same chrome, still no video.
     *
     * Rather than a 4th on-hardware round of guessing, checked a sibling fork of the same
     * upstream (`OpenMotoDash/NorthStar` — a fork of the removed `adityadasika21/NorthStar`,
     * found by searching for forks of it; see spec/wifi_retry_policy.md's "Из живого форка" for
     * how it was found and its unrelated WiFi-retry findings). Its `DashSession` has no idle/nav
     * split at all — `runSession` always enters nav-mode chrome, and its own dev notes
     * (`context.md`) explicitly call the wallpaper idea a "fad" they deliberately skipped,
     * showing a plain map with an empty route instead when idle. `runSession` now does the same
     * (always [enterNavMode]), and [DashEngineController.tick] renders a live map with no
     * route/destination when idle instead of calling into [DashEngineController.tickIdle].
     *
     * Genuinely different from attempts 2/3 above, not just a retry: those still fed a static
     * wallpaper bitmap (attempt 3: + faked telemetry) through nav-mode chrome. This feeds a real,
     * self-consistent live map (matching what the now-permanent chrome says: no destination) —
     * untested whether THAT'S what was missing, or this firmware simply never shows video
     * without genuine turn-by-turn data flowing. Needs an on-hardware ride to confirm either way.
     */
    private suspend fun enterIdleProjectionMode(sock: DashSocket) {
        sock.send(DashCommands.projectionFrame()); delay(60)
        sock.send(DashCommands.projectionOn()); delay(40)
        DebugLog.i(TAG) { "Idle projection kick sent" }
    }

    private fun launchReceiveLoop(sock: DashSocket) {
        var lastRxAtMs = System.currentTimeMillis()
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
                    onError?.invoke("Lost connection to dash")
                    break
                }
                if (pkt == null) {
                    // Timeout — not itself a problem (see DashSocket.RECV_TIMEOUT_MS), but
                    // repeated timeouts during STREAMING with no real packet in between mean
                    // the dash has gone silent (see RX_IDLE_TIMEOUT_MS's doc). Mirrors the
                    // socket-error path above: tear down the session so a fresh `connect()`
                    // isn't blocked by the stale-socket state guard.
                    if (_state.value == DashState.STREAMING &&
                        System.currentTimeMillis() - lastRxAtMs > RX_IDLE_TIMEOUT_MS
                    ) {
                        DebugLog.w(TAG) { "RX loop stopped — no data from dash for over ${RX_IDLE_TIMEOUT_MS}ms" }
                        val silentS = (System.currentTimeMillis() - lastRxAtMs) / 1000
                        RideDiagnostics.log("error", "RX watchdog: dash silent ${silentS}s → link lost")
                        heartbeatJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel()
                        navInfoJob?.cancel(); mediaInfoJob?.cancel()
                        sock.close(); socket = null
                        setState(DashState.IDLE)
                        onError?.invoke("Lost connection to dash")
                        break
                    }
                    continue
                }
                lastRxAtMs = System.currentTimeMillis()
                dispatchIncoming(pkt, sock)
            }
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
                        authRetries++
                        DebugLog.w(TAG) { "Auth rejected — retry #$authRetries" }
                        RideDiagnostics.log("auth", "REJECTED — retry #$authRetries")
                        auth?.reset()
                        if (authRetries <= 5) sock.send(DashCommands.authRequest())
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

    private fun fail(msg: String) {
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
