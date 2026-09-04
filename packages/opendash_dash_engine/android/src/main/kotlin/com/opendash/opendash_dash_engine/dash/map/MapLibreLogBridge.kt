package com.opendash.opendash_dash_engine.dash.map

import com.opendash.opendash_dash_engine.util.DebugLog
import org.maplibre.android.log.Logger
import org.maplibre.android.log.LoggerDefinition

/**
 * Routes MapLibre's own log — including its native core — into [DebugLog], so it
 * lands in `app_log.txt` and the per-ride diagnostics file.
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

    /** Safe to call on every [MapSnapshotProvider.prepare]; only does work once. */
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            Logger.setVerbosity(Logger.WARN)
            Logger.setLoggerDefinition(
                object : LoggerDefinition {
                    override fun v(tag: String, msg: String) = DebugLog.i(TAG) { "[$tag] $msg" }
                    override fun v(tag: String, msg: String, t: Throwable?) = DebugLog.e(TAG, { "[$tag] $msg" }, t)
                    override fun d(tag: String, msg: String) = DebugLog.i(TAG) { "[$tag] $msg" }
                    override fun d(tag: String, msg: String, t: Throwable?) = DebugLog.e(TAG, { "[$tag] $msg" }, t)
                    override fun i(tag: String, msg: String) = DebugLog.i(TAG) { "[$tag] $msg" }
                    override fun i(tag: String, msg: String, t: Throwable?) = DebugLog.e(TAG, { "[$tag] $msg" }, t)
                    override fun w(tag: String, msg: String) = DebugLog.w(TAG) { "[$tag] $msg" }
                    override fun w(tag: String, msg: String, t: Throwable?) = DebugLog.e(TAG, { "[$tag] $msg" }, t)
                    override fun e(tag: String, msg: String) = DebugLog.w(TAG) { "[$tag] $msg" }
                    override fun e(tag: String, msg: String, t: Throwable?) = DebugLog.e(TAG, { "[$tag] $msg" }, t)
                },
            )
        }.onFailure {
            DebugLog.e(TAG, { "could not install MapLibre log bridge" }, it)
        }
    }

    private const val TAG = "MapLibre"
}
