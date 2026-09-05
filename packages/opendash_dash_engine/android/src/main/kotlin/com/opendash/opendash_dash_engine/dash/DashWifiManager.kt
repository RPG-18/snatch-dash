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
        // Android returns this sentinel from WifiInfo.getSsid() when it can't read the SSID.
        private const val WifiManagerUnknownSsid = "<unknown ssid>"
        // Frequent enough to see a signal degrading before it actually drops, without
        // drowning the persisted app log — see [logSignalInfo].
        private const val RSSI_POLL_INTERVAL_MS = 5_000L
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
    private var rssiPollJob: Job? = null
    private var wantConnected = false
    private var pendingSsid = ""
    private var pendingPassword = ""
    private var pendingPrefix = false
    private var resolvedSsid: String? = null

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
        wantConnected    = true
        pendingSsid      = ssid
        pendingPassword  = password
        pendingPrefix    = prefixMatch
        resolvedSsid     = null
        hasConnectedOnce = false
        reconnectCount   = 0
        downtimeAccumMs  = 0L
        downSinceMs      = System.currentTimeMillis() // "down" until the first markConnected
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
        wifi.scanResults
            .mapNotNull { it.SSID?.trim('"')?.takeIf { s -> s.isNotBlank() } }
            .firstOrNull { it.startsWith(prefix) }
            .also { DebugLog.i(TAG) { "Scan lookup for prefix '${maskSsid(prefix)}*' -> ${it?.let(::maskSsid) ?: "not found"}" } }
    } catch (e: Exception) {
        DebugLog.w(TAG) { "Scan lookup failed: ${e.message}" }; null
    }

    fun disconnect() {
        DebugLog.i(TAG) { "Disconnect requested" }
        if (downSinceMs != 0L) {
            downtimeAccumMs += System.currentTimeMillis() - downSinceMs
            downSinceMs = 0L
        }
        // See the field session this closed the loop on — spec/wifi_retry_policy.md's
        // 2026-08-28 log analysis, where reconstructing these two numbers by hand from raw
        // timestamps was most of the work. Logged unconditionally (even reconnectCount=0 is
        // useful — it says the WiFi link never dropped once this whole time).
        DebugLog.i(TAG) { "Session summary: reconnects=$reconnectCount downtime=${downtimeAccumMs}ms" }
        wantConnected = false
        hasConnectedOnce = false
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
                DebugLog.i(TAG) { "Cellular available — binding process default to it (Yandex MapKit needs a real default network)" }
                cm.bindProcessToNetwork(network)
            }

            override fun onLost(network: Network) {
                DebugLog.w(TAG) { "Cellular lost — releasing process bind (falling back to OS default network)" }
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
            DebugLog.w(TAG) { "Cellular requestNetwork threw: ${e.message}" }
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
        release()
        DebugLog.i(TAG) {
            "Requesting WiFi: '${maskSsid(pendingSsid)}' " +
                "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"})"
        }
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        findAlreadyConnectedDashNetwork()?.let { (activeNetwork, activeSsid) ->
            network = activeNetwork
            resolvedSsid = activeSsid
            DebugLog.i(TAG) { "Using already-connected matching WiFi '${maskSsid(activeSsid)}'" }
            onSsidResolved?.invoke(activeSsid)
            markConnected(activeSsid)
            return
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
                        DebugLog.w(TAG) { "WiFi callback available but SSID is redacted; waiting for fallback resolution" }
                        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)
                    }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Canonical place to read the connected SSID — REQUIRED for auth, since the
                // dash validates the SSID inside the encrypted handshake (DashAuth). Without
                // the real SSID (prefix-discovery), every auth is rejected.
                val info = caps.transportInfo as? WifiInfo ?: run {
                    DebugLog.w(TAG) { "Capabilities changed without readable WiFi info; fallback polling remains active" }
                    return
                }
                val ssid = info.ssid.orEmpty().trim('"')
                if (ssid.isBlank() || ssid == WifiManagerUnknownSsid) {
                    DebugLog.w(TAG) { "Capabilities changed with redacted WiFi SSID; fallback polling remains active" }
                    return
                }
                if (ssid == resolvedSsid) return
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
                    DebugLog.w(TAG) { "WiFi still unavailable — reconnecting in ${RECONNECT_DELAY}ms" }
                    reconnectCount++
                    if (downSinceMs == 0L) downSinceMs = System.currentTimeMillis()
                    _state.value = WifiState(
                        status = WifiConnStatus.REQUESTING,
                        ssid   = pendingSsid,
                        error  = "Link lost — reconnecting…",
                    )
                    scheduleReconnect()
                } else {
                    // Never connected this session — likely wrong SSID/password. Don't
                    // spin forever on that; user must try again.
                    DebugLog.w(TAG) { "WiFi unavailable — SSID not found or user declined" }
                    _state.value = WifiState(
                        status = WifiConnStatus.ERROR,
                        ssid   = pendingSsid,
                        error  = "Could not connect to '$pendingSsid' — network not found or wrong password",
                    )
                    wantConnected = false
                }
            }

            override fun onLost(network: Network) {
                DebugLog.w(TAG) { "WiFi link lost — reconnecting in ${RECONNECT_DELAY}ms" }
                reconnectCount++
                if (downSinceMs == 0L) downSinceMs = System.currentTimeMillis()
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
            DebugLog.e(TAG, { "requestNetwork threw: ${e.message}" }, e)
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
        hasConnectedOnce = true
        if (downSinceMs != 0L) {
            downtimeAccumMs += System.currentTimeMillis() - downSinceMs
            downSinceMs = 0L
        }
        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
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
        if (info == null) {
            DebugLog.i(TAG) { "Signal ($context): unavailable" }
            return
        }
        DebugLog.i(TAG) {
            "Signal ($context): bssid=${info.bssid} rssi=${info.rssi}dBm " +
                "linkSpeed=${info.linkSpeed}Mbps freq=${info.frequency}MHz"
        }
    }

    private fun startRssiPolling() {
        rssiPollJob?.cancel()
        rssiPollJob = scope.launch {
            while (isActive) {
                delay(RSSI_POLL_INTERVAL_MS)
                logSignalInfo("poll", network)
            }
        }
    }

    private fun stopRssiPolling() {
        rssiPollJob?.cancel()
        rssiPollJob = null
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
        stopRssiPolling()
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        network = null
    }
}
