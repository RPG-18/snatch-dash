package com.opendash.opendash_dash_engine.dash.map

import com.opendash.opendash_dash_engine.util.DebugLog
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

    /** Window bookkeeping for [ride]: when it opened, and which ride file it belongs to. */
    private var windowStartMs = 0L
    private var windowSession = -1L

    /**
     * Messages written in the current window, each with the number of identical ones
     * swallowed after it. Never larger than [WINDOW_BUDGET]: a message only gets an
     * entry by being written, and past the budget nothing is written.
     */
    private val collapsed = LinkedHashMap<String, Int>()

    /** Distinct messages this window had no budget left to write. */
    private var dropped = 0

    /** The wall clock, replaced in tests so a 10-second window need not be waited out. */
    internal var clockMs: () -> Long = System::currentTimeMillis

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
     * Rate-limits MapLibre's log into the ride file: at most [WINDOW_BUDGET] distinct
     * messages per [WINDOW_MS], each written once, with its repeats counted and
     * reported when the window closes.
     *
     * MapLibre repeats a resource failure once per snapshot, and the failure this
     * bridge was built for lasted a whole ride: the 2026-09-05 session would have
     * written the same `Could not read asset` line some 2600 times, burying the
     * `[map]`/`[stream]` telemetry a post-mortem starts from. Collapsing by message
     * alone is not enough, because nothing says the failures are one message —
     * several missing glyph ranges name several files, and alternating messages walk
     * straight through a "same as the previous line?" check. Hence a budget as well:
     * whatever MapLibre does, this bridge costs the file a bounded number of lines,
     * and says so when it drops the rest.
     *
     * Two known gaps, both counts and never messages. The tail of a window is only
     * reported when the next line arrives — a burst that simply stops leaves its last
     * repeats uncounted. And a window interrupted by a new ride file is dropped
     * outright rather than written into a file its lines were never headed for (see
     * [RideDiagnostics.session]).
     *
     * `@Synchronized` because MapLibre logs from its own threads — the snapshotter
     * and the tile workers both reach here.
     */
    @Synchronized
    internal fun ride(tag: String, msg: String, t: Throwable?) {
        val text = "[$tag] $msg" + if (t != null) " — ${t.javaClass.simpleName}: ${t.message}" else ""
        val now = clockMs()
        val session = RideDiagnostics.session
        if (session != windowSession) {
            // A new ride file: whatever the previous window was still counting was
            // headed for a file that is now closed.
            collapsed.clear()
            dropped = 0
            windowSession = session
            windowStartMs = now
        } else if (now - windowStartMs >= WINDOW_MS) {
            closeWindow()
            windowStartMs = now
        }
        val repeats = collapsed[text]
        if (repeats != null) {
            collapsed[text] = repeats + 1
            return
        }
        if (collapsed.size >= WINDOW_BUDGET) {
            dropped++
            return
        }
        collapsed[text] = 0
        RideDiagnostics.warn(TAG, text)
    }

    /** Report what the closing window swallowed, then start counting again. */
    private fun closeWindow() {
        val seconds = WINDOW_MS / 1000
        for ((text, repeats) in collapsed) {
            if (repeats > 0) RideDiagnostics.warn(TAG, "repeated $repeats more time(s) in the last ${seconds}s: $text")
        }
        if (dropped > 0) {
            RideDiagnostics.warn(TAG, "$dropped further line(s) not written — over the $WINDOW_BUDGET-per-${seconds}s budget")
        }
        collapsed.clear()
        dropped = 0
    }

    private const val TAG = "MapLibre"

    /** How long a message stays collapsed into the line already written for it. */
    private const val WINDOW_MS = 10_000L

    /** Distinct messages one window may write before the rest are only counted. */
    private const val WINDOW_BUDGET = 6
}
