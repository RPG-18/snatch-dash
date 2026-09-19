package com.opendash.opendash_dash_engine.dash

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.monotonicMs
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class WifiConnStatus { IDLE, REQUESTING, CONNECTED, ERROR }

data class WifiState(
    val status: WifiConnStatus = WifiConnStatus.IDLE,
    val ssid: String = "",
    val error: String? = null,
)

/**
 * Programmatically connects to the Tripper Dash WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * The dash's own UDP sockets reach it via [network] + `Network.bindSocket` —
 * see that property's doc for why the process is deliberately NOT bound to it.
 * The process IS instead bound to cellular (see [requestCellularDefault]) so the
 * rest of the app's networking — notably the closed-source Yandex MapKit SDK,
 * which has no API to target a specific network — reliably gets real internet
 * while the dash's no-internet WiFi is connected.
 *
 * Auto-reconnects on link loss until disconnect() is called.
 *
 * Android 11 / OEM fallback:
 * - never authenticate with a prefix-only SSID such as RE_
 * - poll the active Wi-Fi SSID when NetworkCallback transportInfo is redacted
 * - reuse an already-connected matching Wi-Fi network when the system dialog is suppressed
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG              = "DashWifiManager"
        private const val CONNECT_TIMEOUT  = 30_000  // ms — Android shows system dialog within this
        private const val RECONNECT_DELAY  = 8_000L
        /**
         * How long a prefix-discovery connection may sit with an unreadable SSID before it
         * is called a failure.
         *
         * The wait exists because we must not authenticate with a prefix: the dash checks
         * the SSID inside the encrypted handshake (see [DashAuth]), so `RE_` would be
         * rejected. But an unbounded wait is worse than a failure — on Huawei/Android 12,
         * 2026-09-19, the phone really was on the dash's Wi-Fi while this class sat in
         * REQUESTING for as long as the rider was willing to watch it, with no error
         * anywhere and no stream.
         *
         * 15 s because resolution, when it comes, is fast: `onCapabilitiesChanged` follows
         * `onAvailable` within a second or two, and the pre-S poller's own budget is 17 s
         * (5 s + 6 × 2 s). It is also half of [CONNECT_TIMEOUT], so a link that never
         * arrives still fails by its own route first.
         */
        private const val SSID_RESOLVE_TIMEOUT = 15_000L
        // Android returns this sentinel from WifiInfo.getSsid() when it can't read the SSID.
        private const val WifiManagerUnknownSsid = "<unknown ssid>"
        // Frequent enough to see a signal degrading before it actually drops, without
        // drowning the persisted app log — see [logSignalInfo].
        private const val RSSI_POLL_INTERVAL_MS = 5_000L
        /** The one [logSignalInfo] context that stays out of the ride file. */
        private const val POLL_CONTEXT = "poll"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(WifiState())
    val state = _state.asStateFlow()

    /**
     * The dash WiFi network, exposed so the dash UDP sockets can be bound to it
     * INDIVIDUALLY (Network.bindSocket). We deliberately do NOT bindProcessToNetwork
     * to this one: that routed the WHOLE app through the dash's no-internet WiFi, so
     * routing, geocoding and shared-link resolution all failed while connected
     * ("can't share").
     */
    @Volatile var network: Network? = null
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    private var ssidPollJob: Job? = null
    private var ssidResolveJob: Job? = null
    private var rssiPollJob: Job? = null
    private var wantConnected = false
    private var pendingSsid = ""
    private var pendingPassword = ""
    private var pendingPrefix = false
    private var resolvedSsid: String? = null

    /**
     * The name [markConnected] has already announced for the CURRENT request.
     *
     * Split out of [resolvedSsid] because the two want different lifetimes and sharing one
     * field broke whichever of them was cleared last. This one only suppresses a second
     * "link up" for a link already announced, so it belongs to one request and is cleared
     * by [release]. [resolvedSsid] answers "what did we last successfully resolve", which
     * [connect]'s `sameTarget` reads to tell a reconnect to the same dash from a new one —
     * so it must outlive a request, and clearing it in [release] turned every "Send to
     * Dash" during a reconnect into a new target: `hasConnectedOnce` reset, and the next
     * `onUnavailable` taking the "never connected" exit.
     */
    private var announcedSsid: String? = null

    /**
     * A dash name taken from a scan, together with the prefix it came from.
     *
     * A scan entry is a hypothesis, not a fact: the cache can be minutes old (six, on the
     * phone this was written for) and can hold a network that is not this rider's dash. The
     * guess is therefore never written to the config — [DashEngineController] does that only
     * once the dash has accepted the name inside the handshake — and it has exactly one
     * lifecycle, kept in this one place:
     *
     *   born in [resolvePrefixFromScan], withdrawn by [revertScanGuess] when the attempt
     *   fails at the Wi-Fi layer, withdrawn by [rejectScanGuess] when the link came up but
     *   the dash never authenticated, and forgotten in [connect] and [disconnect].
     *
     * Both halves live together because both are needed: the ssid is what we request, the
     * prefix is what we go back to, and what the caller of [connect] still asks for.
     */
    private data class ScanGuess(val prefix: String, val ssid: String)

    @Volatile private var scanGuess: ScanGuess? = null

    /**
     * Names a scan offered that turned out not to be the dash, for this [connect] only.
     *
     * Without this, withdrawing a guess is a loop: the next request reads the same stale
     * scan, finds the same wrong network and tries it again. Cleared whenever the rider
     * starts a new connection, because "not the dash" was a fact about one attempt, not
     * about the network for ever.
     */
    private val rejectedGuesses = mutableSetOf<String>()

    /**
     * Session-level connection-quality counters, reset in [connect] and reported once in
     * [disconnect] — see spec/wifi_retry_policy.md's 2026-08-28 log analysis, where both
     * numbers had to be reconstructed by hand from timestamps across dozens of log lines.
     * [downSinceMs] is nonzero for exactly as long as we're NOT [WifiConnStatus.CONNECTED]
     * while [wantConnected] — zero means "currently connected" or "never started counting".
     */
    private var reconnectCount = 0
    private var downtimeAccumMs = 0L
    private var downSinceMs = 0L

    /**
     * Set once [connect] reaches CONNECTED for the first time this session, cleared on
     * [connect]/[disconnect]. Distinguishes "never found this dash" (bad SSID/password —
     * see [onUnavailable]) from "found it before, lost it now" (rider briefly out of
     * range) so only the former gives up after one failed retry.
     */
    private var hasConnectedOnce = false

    /**
     * When we connect by prefix (any RE_* dash), the exact SSID is only known once the
     * link is up. This callback reports it so the caller can persist it for direct
     * reconnects next time. Null/blank if it can't be resolved.
     */
    var onSsidResolved: ((String) -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Request a WiFi network. Shows a one-time system confirmation dialog.
     *
     * @param prefixMatch when true, [ssid] is treated as a PREFIX and Android offers any
     *   matching network (e.g. every `RE_*` dash) — this is what makes OpenDash work on
     *   any rider's Tripper without hardcoding their SSID. When false, exact-match.
     */
    fun connect(ssid: String, password: String = "", prefixMatch: Boolean = false) {
        // Already holding exactly this network: keep the request that holds it and return.
        // [requestNetwork] starts with an unconditional [release], and for a link brought up by
        // WifiNetworkSpecifier that release IS the disconnect — the platform tears the network
        // down the moment the request holding it goes away (spec/wifi_retry_policy.md, scenario
        // D). Worse than the drop itself: the teardown is asynchronous, so
        // [findAlreadyConnectedDashNetwork] a few lines later still sees the SSID, returns
        // early and calls [markConnected] with NO callback registered — a link that is already
        // dying and can now never report onLost. Field log 2026-09-07 21:38:29: "Requesting
        // WiFi" → "Using already-connected matching WiFi" → CONNECTED → ENETUNREACH on the very
        // next TX. Reached whenever `connect()` runs on a live dash — "Send to Dash" mid-ride.
        //
        // Conditioned on [networkCallback], not on CONNECTED alone: that status can also mean
        // "we found the dash already connected and took the shortcut in [requestNetwork]",
        // which registers no callback and can therefore never learn that the link has died.
        // Keeping the request in THAT case would invert this guard's intent — there is no
        // request to keep, and a re-request is the one thing that can still recover the link.
        val live = _state.value
        if (live.status == WifiConnStatus.CONNECTED &&
            networkCallback != null &&
            network != null &&
            (if (prefixMatch) live.ssid.startsWith(ssid) else live.ssid == ssid)
        ) {
            // The session counters (hasConnectedOnce, reconnectCount, downtime) deliberately
            // keep running: this is the same connection, not a new one.
            wantConnected   = true
            pendingSsid     = ssid
            pendingPassword = password
            pendingPrefix   = prefixMatch
            DebugLog.i(TAG) { "connect() — already on '${maskSsid(live.ssid)}', keeping the live request" }
            return
        }
        // Whether this is a new target or the same one we are already chasing. The
        // second case is the rider tapping "Send to Dash" while the link is down: the
        // callbacks are live, [scheduleReconnect] is already retrying every
        // RECONNECT_DELAY, and [hasConnectedOnce] is the flag that keeps those retries
        // endless instead of one 30s attempt ending in ERROR with wantConnected=false
        // (see onUnavailable). Clearing the session counters there turns a tap meant to
        // help into "stop trying" — and the rider gets a worse outcome than by waiting.
        // A genuinely different SSID/prefix is a different dash, and starts clean.
        //
        // The second arm is prefix discovery, and without it this guard misses precisely the
        // flow it was written for. We connect by prefix ('RE_'), resolve the exact SSID,
        // [onSsidResolved] stores it in the config — and the NEXT connect() therefore arrives
        // with the exact name and prefixMatch=false, while [pendingSsid] still holds the
        // prefix. Comparing only the arguments calls that a different dash and clears the
        // counters, which is the mid-ride "Send to Dash" this branch exists to protect.
        val sameTarget = wantConnected && when {
            ssid == pendingSsid && prefixMatch == pendingPrefix -> true
            !prefixMatch && ssid == resolvedSsid -> true
            // The mirror of the arm above, for a discovery that has not finished yet: we
            // are chasing this very prefix under a name a scan handed us, so a caller still
            // asking for the prefix means the same dash. Without it a "Send to Dash" during
            // a reconnect reads as a different target, clears [hasConnectedOnce], and the
            // next onUnavailable takes the "never connected" exit — turning endless retries
            // into one 30-second attempt ending in ERROR.
            prefixMatch && ssid == scanGuess?.prefix -> true
            else -> false
        }
        wantConnected    = true
        pendingSsid      = ssid
        pendingPassword  = password
        pendingPrefix    = prefixMatch
        resolvedSsid     = null
        // A new connection starts with no hypotheses and no grudges: the guess belongs to
        // the attempt that made it, and "this network is not the dash" was a fact about one
        // attempt rather than about the network.
        scanGuess        = null
        rejectedGuesses.clear()
        if (!sameTarget) {
            hasConnectedOnce = false
            reconnectCount   = 0
            downtimeAccumMs  = 0L
            // Monotonic: this is the origin of `downtime=Xms`, a duration measured across
            // exactly the dead-zone-then-reconnect window where an NTP correction lands.
            // Missed by the first sweep of 2026-09-14; found by review.
            downSinceMs      = monotonicMs() // "down" until the first markConnected
        } else if (downSinceMs == 0L) {
            // Same target, and the counters stand — but a re-request means the link is
            // down from here until [markConnected], and nobody else opened this window:
            // reaching this line with downSinceMs still zero is the shortcut-CONNECTED
            // case, where the last markConnected closed it.
            downSinceMs = monotonicMs()
        }
        requestNetwork()
        requestCellularDefault()
    }

    /**
     * Find a dash SSID from the latest WiFi scan results (any network whose name starts
     * with [prefix], e.g. "RE_"). We need the EXACT SSID string up front because the dash
     * validates it inside the encrypted auth handshake — and Android 13+ redacts the SSID
     * of the connected network, so we can't read it back after connecting.
     */
    @SuppressLint("MissingPermission")
    fun findDashSsid(prefix: String): String? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val candidates = dashSsidCandidates(wifi.scanResults.map { it.SSID }, prefix, rejectedGuesses)
        val pick = when (candidates.size) {
            0 -> null
            1 -> candidates.single()
            // Two dashes in range is not a situation to resolve by picking the first one:
            // the name we choose here is fed into the encrypted handshake AND persisted by
            // [onSsidResolved], so a wrong guess is remembered. Refusing leaves the prefix
            // path, which fails with a message telling the rider to name it themselves.
            else -> null.also {
                RideDiagnostics.warn(
                    TAG,
                    "${candidates.size} networks match '${maskSsid(prefix)}*' — refusing to guess which is the dash",
                )
            }
        }
        pick.also {
            DebugLog.i(TAG) {
                "Scan lookup for prefix '${maskSsid(prefix)}*' -> ${it?.let(::maskSsid) ?: "no single match"}"
            }
        }
    } catch (e: Exception) {
        RideDiagnostics.warn(TAG, "scan lookup failed: ${e.javaClass.simpleName}: ${e.message}"); null
    }

    /**
     * Turn the prefix into the dash's exact SSID before asking for the network.
     *
     * This is the fix for "connects to the bike but streams nothing". Requesting by prefix
     * makes Android join the network and then refuse to tell us its name (API 31+ redacts
     * `WifiInfo.getSsid()` from `NetworkCapabilities`), and the exact name is not a nicety:
     * the dash checks it inside the encrypted handshake, so `RE_` is rejected. Without a
     * name we never reach [markConnected], the controller never opens a session, and the
     * phone sits associated to the dash sending nothing at all — measured 2026-09-19 on
     * Huawei: zero datagrams across two attempts, against 61 in eight seconds once the SSID
     * was filled in by hand.
     *
     * Scan results are the way out because they are NOT redacted the way `WifiInfo` is.
     * When they come up empty — a stale cache, no recent scan — nothing is lost: the prefix
     * request proceeds exactly as before, and [armSsidResolveTimeout] bounds its failure.
     */
    private fun resolvePrefixFromScan() {
        if (!pendingPrefix) return
        val exact = findDashSsid(pendingSsid) ?: return
        RideDiagnostics.log(
            TAG,
            "prefix '${maskSsid(pendingSsid)}*' resolved from scan results to " +
                "'${maskSsid(exact)}' — requesting it by name",
        )
        scanGuess = ScanGuess(prefix = pendingSsid, ssid = exact)
        pendingSsid = exact
        pendingPrefix = false
        // Deliberately NOT [onSsidResolved] and NOT [resolvedSsid]: both mean "this is the
        // dash's name", and nothing has confirmed that yet. See [ScanGuess].
    }

    /** Put the prefix back after an attempt on a guessed name got nowhere. */
    private fun revertScanGuess() {
        val guess = scanGuess ?: return
        scanGuess = null
        RideDiagnostics.warn(
            TAG,
            "the scanned name '${maskSsid(guess.ssid)}' led nowhere — back to discovery by " +
                "'${maskSsid(guess.prefix)}*'",
        )
        pendingSsid = guess.prefix
        pendingPrefix = true
    }

    /** Whether the name currently being requested came from a scan rather than the rider. */
    val usingScanGuess: Boolean get() = scanGuess != null

    /**
     * The link came up on a guessed name and the dash never authenticated on it — so that
     * network is not this dash. Withdraw the guess, remember it, and start over.
     *
     * The Wi-Fi layer cannot notice this on its own: association succeeded, the link is
     * healthy, and nothing below the K1G handshake can tell "the wrong Royal Enfield" from
     * "the right one having a bad day". Only the session knows, so only the session's owner
     * can call this — see DashEngineController's Failed branch.
     */
    fun rejectScanGuess() {
        val guess = scanGuess ?: return
        rejectedGuesses += guess.ssid
        RideDiagnostics.warn(
            TAG,
            "'${maskSsid(guess.ssid)}' associated but never authenticated — not this dash; " +
                "ignoring it for the rest of this connection",
        )
        revertScanGuess()
        if (wantConnected) requestNetwork()
    }

    fun disconnect() {
        DebugLog.i(TAG) { "Disconnect requested" }
        if (downSinceMs != 0L) {
            downtimeAccumMs += monotonicMs() - downSinceMs
            downSinceMs = 0L
        }
        // See the field session this closed the loop on — spec/wifi_retry_policy.md's
        // 2026-08-28 log analysis, where reconstructing these two numbers by hand from raw
        // timestamps was most of the work. Logged unconditionally (even reconnectCount=0 is
        // useful — it says the WiFi link never dropped once this whole time).
        RideDiagnostics.log(TAG, "session summary: reconnects=$reconnectCount downtime=${downtimeAccumMs}ms")
        wantConnected = false
        hasConnectedOnce = false
        scanGuess = null
        reconnectJob?.cancel()
        release()
        releaseCellularDefault()
        _state.value = WifiState()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Yandex MapKit (routing + map tiles) is a closed-source SDK with no API to bind
     * its own sockets to a specific [Network] — it just uses whatever the OS considers
     * the process's default network. Without this, that can end up being the dash's
     * no-internet WiFi (e.g. if the rider joined it manually via system WiFi settings
     * rather than through [connect]'s scoped request, some OEMs keep a no-internet WiFi
     * as "default" instead of falling back to cellular) — map tiles/routing then fail
     * while connected to the dash. Requesting cellular explicitly and binding the
     * process to it sidesteps that: it's independent of [network] above, and per
     * `Network.bindSocket` docs a per-socket bind (used for the dash's own UDP sockets)
     * always overrides this process-level default, so dash traffic is unaffected.
     *
     * No-op if the phone has no cellular radio/SIM/data — the request just waits
     * quietly and the app keeps behaving as it did before this existed (OS default
     * network resolution) until/unless cellular becomes available.
     */
    private fun requestCellularDefault() {
        releaseCellularDefault()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                RideDiagnostics.log(TAG, "cellular available — binding the process default to it (Yandex MapKit needs a real default network)")
                cm.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                // The line that explains a report of "search stopped working mid-ride":
                // without the cellular bind the whole app is left on the dash's
                // no-internet WiFi. One event per link, not per capability change.
                RideDiagnostics.warn(TAG, "cellular lost — releasing the process bind (falling back to the OS default network)")
                cm.bindProcessToNetwork(null)
            }
        }
        cellularCallback = cb
        try {
            // With the main looper's Handler, like the WiFi request below — the two-argument
            // overload delivers on ConnectivityManager's own internal thread instead, and this
            // class keeps all of its state main-thread-confined (which is why none of its
            // fields need @Volatile). Concretely: [releaseCellularDefault] unregisters and then
            // clears the process binding, and an onAvailable already in flight on that other
            // thread could re-bind afterwards, leaving the process pinned to a network whose
            // request we have just given up — including after the plugin detaches.
            cm.requestNetwork(request, cb, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            RideDiagnostics.warn(TAG, "cellular requestNetwork threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun releaseCellularDefault() {
        cellularCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cellularCallback = null
        runCatching { cm.bindProcessToNetwork(null) }
    }

    private fun requestNetwork() {
        // Cancel any auto-retry scheduled by an earlier onUnavailable/onLost BEFORE doing
        // anything else — otherwise a manual connect() racing a pending scheduleReconnect()
        // leaves both timers alive, and the stale one fires its own requestNetwork() a few
        // seconds later (reproduced in spec/wifi_retry_policy.md's 2026-08-28 log analysis,
        // episode 2 steps 4–5: a manual reconnect followed ~4.6s later by an unrequested
        // second "Requesting WiFi", whose own 30s CONNECT_TIMEOUT is what actually decided
        // the outcome). Symmetric to the same cancel already done in onAvailable below.
        reconnectJob?.cancel()
        // Whether the link we are about to drop was OURS decides whether the shortcut below is
        // allowed to believe what it sees — read before [release] clears the field.
        val hadOwnRequest = networkCallback != null
        release()
        // Before the request is built, so everything below — the specifier, the log line,
        // the published state — deals with a name rather than a prefix.
        resolvePrefixFromScan()
        DebugLog.i(TAG) {
            "Requesting WiFi: '${maskSsid(pendingSsid)}' " +
                "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"})"
        }
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        // Only when the dash network is held by something OTHER than a request we just
        // dropped — a link left over from a previous process, or one the rider joined from
        // system settings. That is the case this shortcut was written for, and there the
        // missing NetworkCallback is a limitation we accept.
        //
        // Straight after our own [release] it is a trap instead: tearing down a
        // WifiNetworkSpecifier request disconnects asynchronously (binder → ConnectivityService
        // → WifiNetworkFactory), so WifiManager still reports the SSID for a moment. Believing
        // it here published CONNECTED for a link already on its way out, with no callback to
        // ever report onLost — the session then sat on dead sockets until the RX watchdog, and
        // WifiManager itself never noticed anything. Reached from [scheduleReconnect] as much as
        // from a manual reconnect, which is why this belongs here and not only in [connect].
        if (!hadOwnRequest) {
            findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
                network = activeNetwork
                resolvedSsid = activeSsid
                DebugLog.i(TAG) { "Using already-connected matching WiFi '${maskSsid(activeSsid)}'" }
                onSsidResolved?.invoke(activeSsid)
                markConnected(activeSsid)
                return
            }
        }

        val specBuilder = WifiNetworkSpecifier.Builder()
        if (pendingPrefix) specBuilder.setSsidPattern(PatternMatcher(pendingSsid, PatternMatcher.PATTERN_PREFIX))
        else specBuilder.setSsid(pendingSsid)
        if (pendingPassword.isNotBlank()) specBuilder.setWpa2Passphrase(pendingPassword)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@DashWifiManager.network = network
                reconnectJob?.cancel()
                val resolved = resolveSsid(network)
                when {
                    resolved.isNotBlank() -> {
                        resolvedSsid = resolved
                        DebugLog.i(TAG) { "WiFi callback available; resolved SSID '${maskSsid(resolved)}'" }
                        onSsidResolved?.invoke(resolved)
                        markConnected(resolved)
                    }
                    !pendingPrefix -> {
                        DebugLog.i(TAG) { "WiFi callback available for exact SSID '${maskSsid(pendingSsid)}'" }
                        markConnected(pendingSsid)
                    }
                    else -> {
                        // Associated, but unnamed. One more look at the scan results: we are
                        // ON this network, so its AP is certainly in range now, which is
                        // exactly what the pre-request lookup could not count on — the cached
                        // scan on this phone was six minutes old when it failed.
                        //
                        // The name is still only a guess — `WifiInfo.getBSSID()` is redacted
                        // on these API levels too, so there is nothing to cross-check it
                        // against. Using it beats the alternative, which is joining the dash
                        // and sending it nothing at all; and being wrong costs one auth
                        // timeout whose message names the SSID it tried. It is NOT persisted
                        // here for the same reason — only a completed handshake earns that.
                        val scanned = findDashSsid(pendingSsid)
                        if (scanned != null) {
                            DebugLog.i(TAG) { "SSID redacted, but the scan names it: '${maskSsid(scanned)}'" }
                            // Recorded as a guess like any other, so [rejectScanGuess] can
                            // withdraw it. Without this the pick made here was unfalsifiable:
                            // `usingScanGuess` stayed false, nothing could blacklist it, and
                            // every retry read the same scan and chose the same wrong network.
                            scanGuess = ScanGuess(prefix = pendingSsid, ssid = scanned)
                            markConnected(scanned)
                            return
                        }
                        RideDiagnostics.warn(
                            TAG,
                            "callback available but the SSID is redacted; waiting up to " +
                                "${SSID_RESOLVE_TIMEOUT}ms — " +
                                if (ssidFallbackActive) "fallback polling is running"
                                else "only a capabilities update can still name it on this API level",
                        )
                        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)
                        armSsidResolveTimeout()
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Canonical place to read the connected SSID — REQUIRED for auth, since the
                // dash validates the SSID inside the encrypted handshake (DashAuth). Without
                // the real SSID (prefix-discovery), every auth is rejected.
                val info = caps.transportInfo as? WifiInfo ?: run {
                    DebugLog.w(TAG) { "Capabilities changed without readable WiFi info${fallbackNote()}" }
                    return
                }
                val ssid = info.ssid.orEmpty().trim('"')
                if (ssid.isBlank() || ssid == WifiManagerUnknownSsid) {
                    DebugLog.w(TAG) { "Capabilities changed with redacted WiFi SSID${fallbackNote()}" }
                    return
                }
                if (ssid == announcedSsid) return
                resolvedSsid = ssid
                this@DashWifiManager.network = network
                DebugLog.i(TAG) { "Resolved dash SSID via capabilities: '${maskSsid(ssid)}'" }
                onSsidResolved?.invoke(ssid)
                markConnected(ssid)
            }

            override fun onUnavailable() {
                this@DashWifiManager.network = null
                if (wantConnected && hasConnectedOnce) {
                    // We reached CONNECTED for this dash earlier in this session, so a
                    // timed-out re-request is just another transient drop (rider briefly
                    // out of range) — same as onLost below. Keep retrying instead of
                    // giving up, matching this class's own "auto-reconnects on link loss
                    // until disconnect()" contract.
                    RideDiagnostics.warn(TAG, "still unavailable after ${CONNECT_TIMEOUT}ms — reconnect #${reconnectCount + 1} in ${RECONNECT_DELAY}ms")
                    reconnectCount++
                    if (downSinceMs == 0L) downSinceMs = monotonicMs()
                    _state.value = WifiState(
                        status = WifiConnStatus.REQUESTING,
                        ssid   = pendingSsid,
                        error  = "Link lost — reconnecting…",
                    )
                    scheduleReconnect()
                } else {
                    // Never connected this session — likely wrong SSID/password. Don't
                    // spin forever on that; user must try again.
                    RideDiagnostics.warn(TAG, "unavailable and never connected this session — SSID not found or user declined; giving up")
                    revertScanGuess()
                    _state.value = WifiState(
                        status = WifiConnStatus.ERROR,
                        ssid   = pendingSsid,
                        error  = "Could not connect to '$pendingSsid' — network not found or wrong password",
                    )
                    wantConnected = false
                }
            }

            override fun onLost(network: Network) {
                RideDiagnostics.warn(TAG, "link lost — reconnect #${reconnectCount + 1} in ${RECONNECT_DELAY}ms")
                reconnectCount++
                if (downSinceMs == 0L) downSinceMs = monotonicMs()
                // Last-known signal before the Network object goes stale — shows whether
                // this was a fading signal (see the periodic "poll" samples leading up to
                // it) or a clean step down (dash powered off, radio toggled, etc.).
                logSignalInfo("last before loss", network)
                stopRssiPolling()
                this@DashWifiManager.network = null
                _state.value = WifiState(
                    status = WifiConnStatus.REQUESTING,
                    ssid   = pendingSsid,
                    error  = "Link lost — reconnecting…",
                )
                if (wantConnected) scheduleReconnect()
            }
        }

        networkCallback = cb
        try {
            cm.requestNetwork(request, cb, Handler(Looper.getMainLooper()), CONNECT_TIMEOUT)
            startAndroid11SsidPolling()
        } catch (e: Exception) {
            // The exception type and message, not a stack trace: this throw comes from
            // our own one-line call into ConnectivityManager, so the frames above it say
            // nothing the message does not — and the ride file is the copy that survives
            // a release build, where DebugLog.e writes nothing at all.
            RideDiagnostics.warn(TAG, "requestNetwork threw: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            if (wantConnected) requestNetwork()
        }
    }

    /** Publish CONNECTED and record that this dash has answered at least once this session. */
    private fun markConnected(ssid: String) {
        // The name arrived, whichever of the five routes brought it — stand the timeout down.
        ssidResolveJob?.cancel()
        ssidResolveJob = null
        announcedSsid = ssid
        hasConnectedOnce = true
        if (downSinceMs != 0L) {
            downtimeAccumMs += monotonicMs() - downSinceMs
            downSinceMs = 0L
        }
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
        // Into the ride file, and from here rather than from each of the five call
        // sites that reach CONNECTED (callback, exact SSID, capabilities, the
        // Android 11 poller, the already-connected shortcut). Without a line for
        // "the link came UP" the file holds only drops, and neither the downtime
        // between them nor "did it ever recover" can be read off it.
        RideDiagnostics.log(
            TAG,
            "link up on '${maskSsid(ssid)}' — reconnects=$reconnectCount downtime=${downtimeAccumMs}ms",
        )
        // BSSID at the moment of connecting, not just from the first 5s-later poll tick —
        // see [logSignalInfo]'s doc for why this matters (BSSID-drift theory, spec/wifi_retry_policy.md).
        logSignalInfo("connected", network)
        startRssiPolling()
    }

    /**
     * RSSI/link-speed/BSSID samples logged into the same persisted [DebugLog] as everything
     * else — no `adb`/OS-level "Wi-Fi verbose logging" needed to see whether a drop was a
     * signal fading out over several samples (rider drifting out of range) or a clean
     * step down to nothing (dash powered off, phone Wi-Fi radio toggled, etc.).
     *
     * BSSID specifically is here to test the theory in spec/wifi_retry_policy.md's "Внешние
     * находки": Android's WifiNetworkSpecifier approval cache is keyed on (SSID, BSSID,
     * security type), not SSID alone — if the dash's radio hands out a different BSSID on
     * every full re-associate, that alone re-triggers the system dialog even for an
     * already-approved exact SSID. Comparing "last before loss" vs the next "connected"
     * BSSID across a full disconnect/reconnect cycle confirms or rules this out.
     */
    private fun logSignalInfo(context: String, net: Network?) {
        val info = net?.let { cm.getNetworkCapabilities(it)?.transportInfo as? WifiInfo }
        val line = if (info == null) {
            "signal ($context): unavailable"
        } else {
            "signal ($context): bssid=${info.bssid} rssi=${info.rssi}dBm " +
                "linkSpeed=${info.linkSpeed}Mbps freq=${info.frequency}MHz"
        }
        // The samples around a transition go into the ride file; the 5-second poll
        // does not. That poll is 12 lines a minute — 700 an hour of a file whose
        // point is that a `[map]`/`[stream]` line can be found in it — and the
        // question it answers ("was the signal fading or did it stop dead") is
        // answered by the pair this DOES keep: the last sample before the loss and
        // the first one after the link comes back. The full curve stays in
        // app_log.txt, which is a debug-build luxury either way.
        if (context == POLL_CONTEXT) DebugLog.i(TAG) { line } else RideDiagnostics.log(TAG, line)
    }

    private fun startRssiPolling() {
        rssiPollJob?.cancel()
        rssiPollJob = scope.launch {
            while (isActive) {
                delay(RSSI_POLL_INTERVAL_MS)
                logSignalInfo(POLL_CONTEXT, network)
            }
        }
    }

    private fun stopRssiPolling() {
        rssiPollJob?.cancel()
        rssiPollJob = null
    }

    /**
     * Whether anything is actually working on a redacted SSID in the background.
     *
     * [startAndroid11SsidPolling] returns immediately from API 31 up, so on those levels
     * the only thing that can still name the network is `onCapabilitiesChanged`. Two log
     * lines used to say "fallback polling remains active" regardless — false on exactly
     * the devices where the wait never ended, and the first thing that misled the reading
     * of the 2026-09-19 Huawei logs.
     */
    private val ssidFallbackActive: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    /**
     * Give up on naming this network after [SSID_RESOLVE_TIMEOUT] and say so.
     *
     * Reports ERROR rather than retrying, and the message names the fix: entering the exact
     * SSID by hand turns this case into the [pendingPrefix] == false path, which works —
     * verified on the same phone the same day (17:39, `ssid='RE_9CP9_250218'`, auth in
     * 253 ms). A retry would change nothing, because nothing about the next attempt makes
     * the platform any more willing to hand over the name.
     */
    private fun armSsidResolveTimeout() {
        if (ssidResolveJob?.isActive == true) return
        ssidResolveJob = scope.launch {
            delay(SSID_RESOLVE_TIMEOUT)
            // Status only. [resolvedSsid] is NOT a safe second condition: it is cleared
            // in [connect] alone, so it survives [release] and every [scheduleReconnect]
            // — a reconnect after one successful prefix resolve would find it set and
            // skip the timeout entirely, restoring the hang this exists to end. Worse,
            // `onCapabilitiesChanged` returns early on `ssid == resolvedSsid`, so that
            // reconnect never reaches [markConnected] either. The status is the honest
            // question: did this attempt get anywhere?
            if (_state.value.status == WifiConnStatus.CONNECTED) return@launch
            RideDiagnostics.warn(
                TAG,
                "joined a '${maskSsid(pendingSsid)}*' network but its SSID stayed unreadable for " +
                    "${SSID_RESOLVE_TIMEOUT}ms (fallback polling ${if (ssidFallbackActive) "ran" else "not available above API 30"}) — " +
                    "cannot authenticate with a prefix, giving up",
            )
            revertScanGuess()
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "Joined a '$pendingSsid' network but Android hid its name — " +
                    "enter the exact dash SSID in Settings",
            )
            // No reconnect: the next attempt would hit the same platform refusal, and a
            // loop of them is what hid this for a day.
            wantConnected = false
            reconnectJob?.cancel()
            // And drop the request itself. Leaving it registered keeps the phone on the
            // dash's no-internet Wi-Fi with nothing using it, and the `onLost` that
            // eventually follows would replace the message above — the one that tells the
            // rider what to do — with "Link lost — reconnecting…" from a reconnect that
            // [wantConnected] has just forbidden. The state is deliberately left at ERROR;
            // [release] does not touch it.
            release()
        }
    }

    /** Read the connected network's SSID (strips the surrounding quotes Android adds). */
    private fun resolveSsid(network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return ""
        val info = caps.transportInfo as? WifiInfo ?: return ""
        return info.ssid.orEmpty().trim('"').let { if (it == WifiManagerUnknownSsid) "" else it }
    }

    /**
     * Tracked and replaced, not fire-and-forget: [requestNetwork] runs again on
     * every reconnect, and untracked pollers piled up — each one still able to
     * publish CONNECTED with the SSID it happened to read, which is the string
     * the dash validates inside the encrypted handshake.
     */
    private fun startAndroid11SsidPolling() {
        ssidPollJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        ssidPollJob = scope.launch {
            delay(5_000)
            repeat(6) { attempt ->
                if (_state.value.status == WifiConnStatus.CONNECTED && resolvedSsid != null) return@launch
                findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
                    network = activeNetwork
                    resolvedSsid = activeSsid
                    DebugLog.i(TAG) { "Android 11 SSID fallback #${attempt + 1} resolved '${maskSsid(activeSsid)}'" }
                    onSsidResolved?.invoke(activeSsid)
                    markConnected(activeSsid)
                    return@launch
                }
                delay(2_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findAlreadyConnectedDashNetwork(): Pair<Network, String>? {
        val activeSsid = readActiveWifiSsid()?.takeIf(::matchesPendingSsid) ?: return null
        val wifiNetwork = cm.allNetworks.firstOrNull { candidate ->
            val caps = cm.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            val candidateSsid = (caps.transportInfo as? WifiInfo)
                ?.ssid
                .orEmpty()
                .trim('"')
                .takeIf { it.isNotBlank() && it != WifiManagerUnknownSsid }
            candidateSsid == null || candidateSsid == activeSsid
        } ?: return null
        return wifiNetwork to activeSsid
    }

    @SuppressLint("MissingPermission")
    private fun readActiveWifiSsid(): String? = runCatching {
        @Suppress("DEPRECATION")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi.connectionInfo
            ?.ssid
            .orEmpty()
            .trim('"')
            .takeIf { it.isNotBlank() && it != WifiManagerUnknownSsid }
    }.getOrNull()

    /** What, if anything, is still expected to resolve the name — see [ssidFallbackActive]. */
    private fun fallbackNote(): String =
        if (ssidFallbackActive) "; fallback polling remains active"
        else "; no fallback polling above API 30 — waiting on a later capabilities update"

    private fun matchesPendingSsid(ssid: String): Boolean =
        if (pendingPrefix) ssid.startsWith(pendingSsid) else ssid == pendingSsid

    private fun maskSsid(ssid: String): String {
        if (ssid.isBlank()) return ""
        if (ssid.length <= 4) return ssid.take(2) + "**"
        return ssid.take(3) + "****" + ssid.takeLast(2)
    }

    private fun release() {
        // Also covers [requestNetwork]'s already-connected early return, which
        // never reaches [startAndroid11SsidPolling]'s own cancel.
        ssidPollJob?.cancel()
        ssidPollJob = null
        ssidResolveJob?.cancel()
        ssidResolveJob = null
        // Per request, not per connect(). [onCapabilitiesChanged] skips an SSID equal to
        // this field to avoid re-announcing the same link, and while it was only cleared in
        // connect() that dedupe outlived the connection it belonged to: a reconnect to the
        // same network was silently dropped there and never reached [markConnected], so the
        // request sat in REQUESTING until the resolve timeout called it "Android hid the
        // name" — with the name in plain sight.
        announcedSsid = null
        stopRssiPolling()
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        network = null
    }
}

/**
 * Which scanned networks could be the dash.
 *
 * Pure, and separate from [DashWifiManager] so it can be tested: the class itself needs a
 * live `WifiManager` and `ConnectivityManager`, which a JVM test has no way to provide.
 *
 * Quotes are stripped because `ScanResult.SSID` sometimes carries them and sometimes does
 * not, duplicates are dropped because one AP appears once per band, and a blank prefix
 * matches NOTHING rather than everything — a rider who clears the prefix field must not
 * have the app join whichever network happens to be nearest.
 *
 * @param exclude names already tried and found not to be the dash. Filtering here rather
 *   than at the call site is what makes the rule checkable: everything else about choosing
 *   a network needs a live `WifiManager`, and a rejection that is not applied turns
 *   withdrawing a guess into a loop over the same stale scan.
 */
internal fun dashSsidCandidates(
    scanned: List<String?>,
    prefix: String,
    exclude: Set<String> = emptySet(),
): List<String> {
    if (prefix.isBlank()) return emptyList()
    return scanned.asSequence()
        .mapNotNull { it?.trim('"')?.takeIf { s -> s.isNotBlank() } }
        .filter { it.startsWith(prefix) && it !in exclude }
        .distinct()
        .toList()
}
