package com.opendash.opendash_dash_engine.dash.map

import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.CollapsingRideLog
import com.opendash.opendash_dash_engine.util.monotonicMs
import com.opendash.opendash_dash_engine.util.RideDiagnostics
import org.maplibre.android.log.Logger
import org.maplibre.android.log.LoggerDefinition

/**
 * Routes MapLibre's own log — including its native core — into [RideDiagnostics],
 * so it lands in both the per-ride file and `app_log.txt`.
 *
 * **Through [RideDiagnostics], not [DebugLog] alone, since 2026-09-08.** DebugLog
 * is compiled out of a release build (`BuildConfig.DEBUG`), so what this bridge
 * exists to capture reached nothing at all on the build a rider actually carries —
 * the one case where nobody is watching logcat. The ride file has no such gate.
 *
 * Installed because of what the 2026-09-04 field session could NOT answer. Every
 * streaming session opened with `snapshot failed: Could not read asset`, and that
 * string is the whole of what `MapSnapshotter`'s `ErrorHandler` hands us: a
 * message, no URL. Which asset — a glyph range, a sprite, the style — decides
 * whether the fix is one file or a whole set, and the only place the failing
 * resource is named is MapLibre's native log. Reading it meant `adb logcat` at
 * the moment of the ride, on a bike, with the ring buffer overwriting itself in
 * minutes; by the time the phone was back on the cable it was gone.
 *
 * With this bridge the name arrives in the same file as everything else, after
 * the fact, from a ride nobody was watching. See plan.md 1.5.
 *
 * **Verbosity is deliberately WARN.** MapLibre logs per-tile and per-request
 * chatter at INFO and below; at 4 fps that would bury the ride log in the exact
 * situation it exists to explain. Failures — which is all we are after — come in
 * above that line.
 */
object MapLibreLogBridge {

    private var installed = false

    /** Shared with the Flutter error bridge — see [CollapsingRideLog]. */
    private var limiter = CollapsingRideLog(TAG, WINDOW_MS, WINDOW_BUDGET, ::monotonicMs)

    /**
     * The clock for the rate-limit window, replaced in tests so 10 s need not be waited
     * out. Setting it rebuilds the limiter, since the limiter captures it — so a test
     * that sets it also starts the window afresh, which is what a test wants.
     *
     * Declared after [limiter] on purpose: the setter assigns to it, and reading a
     * property before its initializer has run is the sort of thing that works until
     * someone reorders the file.
     *
     * Monotonic: the window is a duration, and the failure mode of the wall clock here is
     * that an NTP step mid-ride either silences the bridge for the length of the step or
     * empties the budget instantly. See util/Clock.kt.
     */
    internal var clockMs: () -> Long = ::monotonicMs
        set(value) {
            field = value
            limiter = CollapsingRideLog(TAG, WINDOW_MS, WINDOW_BUDGET, value)
        }

    /** Safe to call on every [MapSnapshotProvider.prepare]; only does work once. */
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            Logger.setVerbosity(Logger.WARN)
            Logger.setLoggerDefinition(
                object : LoggerDefinition {
                    // v/d/i are below the verbosity set above and should never fire;
                    // if one does, it is per-tile chatter and stays out of the ride file.
                    override fun v(tag: String, msg: String) = DebugLog.i(TAG) { "[$tag] $msg" }
                    override fun v(tag: String, msg: String, t: Throwable?) = ride(tag, msg, t)
                    override fun d(tag: String, msg: String) = DebugLog.i(TAG) { "[$tag] $msg" }
                    override fun d(tag: String, msg: String, t: Throwable?) = ride(tag, msg, t)
                    override fun i(tag: String, msg: String) = DebugLog.i(TAG) { "[$tag] $msg" }
                    override fun i(tag: String, msg: String, t: Throwable?) = ride(tag, msg, t)
                    override fun w(tag: String, msg: String) = ride(tag, msg, null)
                    override fun w(tag: String, msg: String, t: Throwable?) = ride(tag, msg, t)
                    override fun e(tag: String, msg: String) = ride(tag, msg, null)
                    override fun e(tag: String, msg: String, t: Throwable?) = ride(tag, msg, t)
                },
            )
        }.onFailure {
            // Into the ride file, not DebugLog.e: a bridge that failed to install is
            // exactly the state in which nothing else from MapLibre will be recorded,
            // and on a release build DebugLog writes nothing to say so.
            RideDiagnostics.warn(TAG, "could not install the MapLibre log bridge: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * Rate-limits MapLibre's log into the ride file — see [CollapsingRideLog] for the
     * shape of the limit and the two gaps it has.
     *
     * MapLibre repeats a resource failure once per snapshot, and the failure this bridge
     * was built for lasted a whole ride: the 2026-09-05 session would have written the
     * same `Could not read asset` line some 2600 times, burying the `[map]`/`[stream]`
     * telemetry a post-mortem starts from.
     */
    internal fun ride(tag: String, msg: String, t: Throwable?) {
        limiter.write(
            "[$tag] $msg" + if (t != null) " — ${t.javaClass.simpleName}: ${t.message}" else "",
        )
    }

    private const val TAG = "MapLibre"

    /** How long a message stays collapsed into the line already written for it. */
    private const val WINDOW_MS = 10_000L

    /** Distinct messages one window may write before the rest are only counted. */
    private const val WINDOW_BUDGET = 6
}
