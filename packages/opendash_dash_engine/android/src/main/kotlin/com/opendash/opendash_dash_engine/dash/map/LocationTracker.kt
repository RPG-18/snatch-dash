package com.opendash.opendash_dash_engine.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.opendash.opendash_dash_engine.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** GPS position via LocationManager (no Play Services dependency). */
class LocationTracker(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "LocationTracker"
        // While a GPS fix is younger than this, ignore coarse NETWORK fixes entirely.
        private const val GPS_STALE_MS = 10_000L
        // Same threshold DashEngineController.tick() uses for its own gpsWeak flag — reused
        // here so this log and that live UI signal agree on what "degraded" means.
        private const val DEGRADED_ACCURACY_M = 25f
        private const val QUALITY_LOG_INTERVAL_MS = 60_000L
        // Silence longer than this while "running" gets its own warning line, independent of
        // the interval above — a genuine gap (GPS provider died, location permission yanked
        // mid-ride, etc.) shouldn't have to wait a full minute to show up.
        private const val FIX_GAP_WARN_MS = 10_000L
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    // Quality counters for [launchQualityLog] — accumulate between reports, reset there.
    // Atomic because the two ends are different threads: the [listener] increments them on the
    // main looper (that is where requestLocationUpdates delivers), while [launchQualityLog]
    // reads and zeroes them on Dispatchers.Default. As plain Ints the increments were a
    // non-atomic read-modify-write and the reads could see arbitrarily stale values — which
    // matters more than it looks, because these numbers exist purely to answer "was GPS healthy
    // at this point in the ride" from a diag file after the fact.
    private val acceptedCount = AtomicInteger(0)
    private val rejectedCount = AtomicInteger(0)
    private val degradedCount = AtomicInteger(0)
    @Volatile private var lastFixAtMs = 0L
    private var qualityLogJob: Job? = null

    private val listener = LocationListener { loc ->
        val cur = _location.value
        if (acceptFix(cur, loc)) {
            _location.value = loc
            acceptedCount.incrementAndGet()
            if (loc.accuracy > DEGRADED_ACCURACY_M) degradedCount.incrementAndGet()
            lastFixAtMs = System.currentTimeMillis()
            DebugLog.d(TAG) { "fix ${loc.provider} acc=${loc.accuracy} (${loc.latitude},${loc.longitude})" }
        } else {
            rejectedCount.incrementAndGet()
            DebugLog.d(TAG) { "REJECT ${loc.provider} acc=${loc.accuracy} dt=${loc.time - (cur?.time ?: 0)}ms" }
        }
    }

    private var rejectStreak = 0

    private fun acceptFix(cur: Location?, loc: Location): Boolean {
        if (cur == null) {
            rejectStreak = 0
            return true
        }
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (!isGps && cur.provider == LocationManager.GPS_PROVIDER &&
            loc.time - cur.time < GPS_STALE_MS
        ) return false
        if (loc.time < cur.time) return false
        val dt = (loc.time - cur.time) / 1000.0
        val jump = cur.distanceTo(loc)
        if (dt > 0 && jump > 200f && jump / dt > 85.0) return reject()
        if (dt in 0.0..6.0 && rejectStreak < 3) {
            val plausibleSpeed = maxOf(cur.speed, loc.speed).coerceAtLeast(1f)
            val expected = plausibleSpeed * dt
            val noise = loc.accuracy + cur.accuracy
            val gate = expected + noise * 1.5 + 12f
            if (jump > gate && jump > 25f) return reject()
        }
        rejectStreak = 0
        return true
    }

    private fun reject(): Boolean {
        rejectStreak++
        return false
    }

    private var running = false

    /** Requires ACCESS_FINE_LOCATION at runtime; no-ops without it. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        try {
            _location.value = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            // GPS for accuracy + heading; NETWORK as a fallback while GPS warms up.
            // minDistance=0: keep GPS fixes flowing every second even when parked.
            // With a minimum distance, GPS goes quiet while stationary, its last fix
            // ages out, and a coarse NETWORK fix takes over → the marker drifts.
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 500L, 0f, listener, Looper.getMainLooper())
                }
            }
            running = true
            lastFixAtMs = System.currentTimeMillis()
            launchQualityLog()
            DebugLog.i(TAG) { "Location updates started" }
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "Location permission missing — GPS disabled" }
        } catch (e: Exception) {
            DebugLog.w(TAG) { "GPS start failed: ${e.message}" }
        }
    }

    fun stop() {
        if (!running) return
        lm.removeUpdates(listener)
        qualityLogJob?.cancel(); qualityLogJob = null
        running = false
    }

    /**
     * Periodic fix-quality report — added after a 2026-08-28 field session where working out
     * "was GPS actually healthy at this point in the ride" meant eyeballing raw `fix`/`REJECT`
     * lines by hand. One job, ticking every second, driving two independent signals so a slow
     * 60s summary cadence never delays a real gap warning:
     *   - a standalone warning the FIRST second a gap exceeds [FIX_GAP_WARN_MS] (a dead GPS
     *     provider mid-ride is worth seeing immediately, not up to a minute late);
     *   - a rolling accepted/rejected/degraded count flushed every [QUALITY_LOG_INTERVAL_MS].
     */
    private fun launchQualityLog() {
        qualityLogJob?.cancel()
        acceptedCount.set(0); rejectedCount.set(0); degradedCount.set(0)
        var warnedForThisGap = false
        var msSinceLastSummary = 0L
        qualityLogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1_000)
                val gapMs = System.currentTimeMillis() - lastFixAtMs
                if (gapMs > FIX_GAP_WARN_MS) {
                    if (!warnedForThisGap) {
                        warnedForThisGap = true
                        DebugLog.w(TAG) { "GPS quality: no accepted fix for ${gapMs}ms — location may be stale/frozen" }
                    }
                } else {
                    warnedForThisGap = false
                }

                msSinceLastSummary += 1_000
                if (msSinceLastSummary >= QUALITY_LOG_INTERVAL_MS) {
                    msSinceLastSummary = 0L
                    // getAndSet, not read-then-zero: a fix landing between the two would
                    // otherwise be counted into this window and then thrown away.
                    val accepted = acceptedCount.getAndSet(0)
                    val rejected = rejectedCount.getAndSet(0)
                    val degraded = degradedCount.getAndSet(0)
                    DebugLog.i(TAG) {
                        "GPS quality: accepted=$accepted rejected=$rejected " +
                            "degraded(acc>${DEGRADED_ACCURACY_M.toInt()}m)=$degraded in the last " +
                            "${QUALITY_LOG_INTERVAL_MS / 1_000}s"
                    }
                }
            }
        }
    }

    /** Best last-known fix without starting updates (for routing before connecting). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): android.location.Location? = try {
        _location.value
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    } catch (e: Exception) {
        null
    }
}
