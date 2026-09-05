package com.opendash.opendash_dash_engine.util

import android.util.Log
import com.opendash.opendash_dash_engine.BuildConfig

object DebugLog {
    /**
     * Set by [com.opendash.opendash_dash_engine.OpendashDashEnginePlugin] while attached, so
     * every logcat line also reaches the Dart-side Talker log (`/more/logs`) — the phone
     * screen is usually off mid-ride, so logcat alone isn't reachable for a post-mortem.
     *
     * @Volatile because it is written once on the main thread at attach and read from every
     * thread the engine uses — the RX loop and heartbeats on IO, the frame loop on Default,
     * ConnectivityManager's callbacks, MapLibre's native log bridge. Without it a thread may
     * never observe the sink at all, and its lines quietly never reach `app_log.txt` or Talker
     * — the two files that ARE the post-mortem this field exists for.
     */
    @Volatile
    var sink: ((tag: String, level: String, message: String) -> Unit)? = null

    fun d(tag: String, message: () -> String) = emit(tag, "D", message)
    fun i(tag: String, message: () -> String) = emit(tag, "I", message)
    fun w(tag: String, message: () -> String) = emit(tag, "W", message)

    fun e(tag: String, message: () -> String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val msg = message()
        runCatching { if (error == null) Log.e(tag, msg) else Log.e(tag, msg, error) }
        val full = if (error == null) msg else "$msg — ${error.javaClass.simpleName}: ${error.message}"
        runCatching { sink?.invoke(tag, "E", full) }
    }

    private fun emit(tag: String, level: String, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        val msg = message()
        runCatching {
            when (level) {
                "D" -> Log.d(tag, msg)
                "I" -> Log.i(tag, msg)
                "W" -> Log.w(tag, msg)
            }
        }
        runCatching { sink?.invoke(tag, level, msg) }
    }
}
