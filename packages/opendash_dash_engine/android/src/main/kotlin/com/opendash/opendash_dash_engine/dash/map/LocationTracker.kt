package com.opendash.opendash_dash_engine.dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics
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
        // Same threshold DashEngineController.tick() uses for its own gpsWeak flag — reused
        // here so this log and that live UI signal agree on what "degraded" means.
        //
        // Note what it does NOT catch, learned 2026-09-13: spoofed fixes came in at 10-20 m
        // and sailed through, so accuracy alone says nothing about truthfulness. That is
        // [PositionTrust]'s job, not this constant's.
        private const val DEGRADED_ACCURACY_M = 25f
        private const val QUALITY_LOG_INTERVAL_MS = 60_000L
        // Silence longer than this while "running" gets its own warning line, independent of
        // the interval above — a genuine gap (GPS provider died, location permission yanked
        // mid-ride, etc.) shouldn't have to wait a full minute to show up.
        private const val FIX_GAP_WARN_MS = 10_000L

        /**
         * A [getLastKnownLocation] seed older than this is not used at all.
         *
         * It used to be written straight into [_location] with no check whatsoever, which made
         * a cached fix of any age the anchor every plausibility guard then measured against.
         */
        private const val SEED_MAX_AGE_MS = 120_000L

        /** One doubt line per this interval, per kind — a spoofed stream doubts every second. */
        private const val DOUBT_LOG_INTERVAL_MS = 30_000L

    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()

    private val filter = FixFilter()
    private val trust = PositionTrust()

    /**
     * False while [PositionTrust] is objecting — read by the Dash screen's GPS chip through
     * [DashEngineController], so the rider sees "position unknown" instead of a confident map
     * somewhere else entirely.
     */
    private val _trusted = MutableStateFlow(true)
    val trusted = _trusted.asStateFlow()

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
    private val doubtCount = AtomicInteger(0)
    @Volatile private var lastFixAtMs = 0L
    private var qualityLogJob: Job? = null
    // Per KIND, not one shared timestamp: ProviderDisagreement is evaluated on every fix and
    // would otherwise permanently starve out the once-per-window ImplausibleSpeed line, which
    // is the rarer and more informative of the two. Review.
    private val lastDoubtLogAtMs = HashMap<String, Long>()

    private fun Location.toFix() = Fix(
        lat = latitude,
        lon = longitude,
        // getAccuracy() returns 0 when hasAccuracy() is false, and 0 read as a number means
        // "perfect" — the opposite of "unknown". See Fix.accuracyM.
        accuracyM = if (hasAccuracy()) accuracy else null,
        atMs = time,
        speedMps = speed,
        isGps = provider == LocationManager.GPS_PROVIDER,
    )

    private val listener = LocationListener { loc ->
        val cur = _location.value
        val fix = loc.toFix()
        val accepted = filter.accept(cur?.toFix(), fix)
        if (accepted) {
            _location.value = loc
            acceptedCount.incrementAndGet()
            // An unknown accuracy counts as degraded: we cannot say it is good.
            if (!loc.hasAccuracy() || loc.accuracy > DEGRADED_ACCURACY_M) {
                degradedCount.incrementAndGet()
            }
            lastFixAtMs = System.currentTimeMillis()
            DebugLog.d(TAG) { "fix ${loc.provider} acc=${loc.accuracy} (${loc.latitude},${loc.longitude})" }
        } else {
            rejectedCount.incrementAndGet()
            DebugLog.d(TAG) { "REJECT ${loc.provider} acc=${loc.accuracy} dt=${loc.time - (cur?.time ?: 0)}ms" }
        }
        // Every fix, accepted or not: a rejected one is still evidence about the receiver.
        reportDoubt(trust.observe(fix, accepted))
    }

    /**
     * Publishes a trust verdict and, at most every [DOUBT_LOG_INTERVAL_MS], a ride-file line.
     *
     * Rate-limited because the condition is not momentary: under jamming every single fix is
     * doubtful, and one line a second would bury the rest of the file. Occurrences are counted
     * instead and reported in the minute summary.
     */
    private fun reportDoubt(doubt: PositionTrust.Doubt?) {
        // The latch lives in PositionTrust, where it is testable — see its TRUST_HOLD_MS.
        _trusted.value = trust.trusted
        if (doubt == null) return
        doubtCount.incrementAndGet()
        val now = System.currentTimeMillis()

        val kind = doubt::class.simpleName ?: "doubt"
        if (now - (lastDoubtLogAtMs[kind] ?: 0L) < DOUBT_LOG_INTERVAL_MS) return
        lastDoubtLogAtMs[kind] = now
        when (doubt) {
            is PositionTrust.Doubt.ProviderDisagreement -> RideDiagnostics.warn(
                "gps",
                "providers disagree by %.1fkm — gps(acc=%s) vs network(acc=%s); ".format(
                    doubt.gapM / 1000.0,
                    doubt.gpsAccuracyM?.let { "%.0fm".format(it) } ?: "unknown",
                    doubt.networkAccuracyM?.let { "%.0fm".format(it) } ?: "unknown",
                ) + "both cannot be right, position NOT trustworthy",
            )
            is PositionTrust.Doubt.ImplausibleSpeed -> RideDiagnostics.warn(
                "gps",
                "implied speed %.0fkm/h over %ds — position stream not plausible".format(
                    doubt.impliedMps * 3.6, doubt.overMs / 1000,
                ),
            )
        }
    }

    private var running = false

    /** Requires ACCESS_FINE_LOCATION at runtime; no-ops without it. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        try {
            filter.reset()
            trust.reset()
            _trusted.value = true
            // Reset with the rest, or the limiter leaks across RideDiagnostics.start() and a
            // reconnect within the interval gives the new ride's file `doubted=N` and no line
            // explaining it. Review.
            lastDoubtLogAtMs.clear()
            _location.value = seedFix()
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

    /**
     * Best cached fix to start from, or none.
     *
     * Age-gated, which it was not before: whatever this returns becomes the anchor every
     * plausibility guard in [FixFilter] measures against, so a fix cached hours ago at a
     * different end of the city used to be able to reject the live stream on arrival.
     */
    @SuppressLint("MissingPermission")
    private fun seedFix(): Location? {
        // Age-gated PER CANDIDATE. Gating after the provider preference meant a stale cached
        // GPS fix won the elvis chain and was then discarded, throwing away a perfectly fresh
        // NETWORK fix behind it and leaving the dash on `gpsLost` until GPS cold-started.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val candidate = lm.getLastKnownLocation(provider) ?: continue
            val ageMs = System.currentTimeMillis() - candidate.time
            if (ageMs <= SEED_MAX_AGE_MS) return candidate
            DebugLog.i(TAG) { "seed $provider discarded — ${ageMs}ms old" }
        }
        return null
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
     *
     * **Both go to the ride file as of 2026-09-13, not only to DebugLog.** Diagnosing the
     * jammed ride of that date took a 4.6 MB debug `app_log.txt` and reading raw fix lines by
     * eye; on a release build `DebugLog` is compiled out entirely, so `diag/` would have held
     * nothing but `center=` and `moved=` and the whole story would have been invisible.
     */
    private fun launchQualityLog() {
        qualityLogJob?.cancel()
        acceptedCount.set(0); rejectedCount.set(0); degradedCount.set(0); doubtCount.set(0)
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
                        RideDiagnostics.warn("gps", "no accepted fix for ${gapMs}ms — location may be stale/frozen")
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
                    val doubts = doubtCount.getAndSet(0)
                    val summary = "quality: accepted=$accepted rejected=$rejected " +
                        "degraded(acc>${DEGRADED_ACCURACY_M.toInt()}m)=$degraded doubted=$doubts " +
                        "in the last ${QUALITY_LOG_INTERVAL_MS / 1_000}s"
                    DebugLog.i(TAG) { "GPS $summary" }
                    RideDiagnostics.log("gps", summary)
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
