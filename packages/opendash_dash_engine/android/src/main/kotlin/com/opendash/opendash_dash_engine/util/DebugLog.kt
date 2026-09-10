package com.opendash.opendash_dash_engine.util

import android.util.Log
import com.opendash.opendash_dash_engine.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    /**
     * One line logged before [sink] existed. See [pending].
     */
    private class Pending(
        val tag: String,
        val level: String,
        val message: String,
        val atMs: Long,
    )

    /**
     * Lines logged before anything was listening, kept until it is.
     *
     * The gap this closes: [sink] appears only once Dart SUBSCRIBES to the log channel —
     * later than the plugin attaching, which is itself later than `Application.onCreate`. Everything logged in between — the
     * StrictMode install line, [CrashGuard]'s confirmation, and most importantly
     * [ExitInfoCollector]'s "last exit was an ANR / native crash", which is the whole point
     * of that class — reached logcat only. Logcat's ring buffer is minutes long and the
     * phone comes back to the desk hours later (CLAUDE.md, «Полевые логи с дэша»), so on a
     * true cold start those lines were written to nowhere that outlives the ride.
     *
     * Bounded, and the OLDEST is dropped when it overflows: this holds process-start lines,
     * which are few, and if something ever floods it the recent half is the useful half.
     * Nothing accumulates in a release build — [emit] returns before reaching here.
     */
    private val pending = ArrayDeque<Pending>()
    private const val PENDING_CAPACITY = 64

    @Volatile
    private var currentSink: ((tag: String, level: String, message: String) -> Unit)? = null

    /**
     * Set by [com.opendash.opendash_dash_engine.OpendashDashEnginePlugin] once Dart has
     * subscribed to the log channel, so every logcat line also reaches the Dart-side Talker
     * log (`/more/logs`) — the phone screen is usually off mid-ride, so logcat alone isn't
     * reachable for a post-mortem. Not at plugin attach: the sink installed there would
     * forward into an EventSink Dart has not asked for yet, and this property flushes on
     * assignment, so the backlog would go into it and be gone.
     *
     * Assigning a non-null sink flushes whatever [pending] holds, in order, before any new
     * line reaches it. Assigning null (plugin detach) keeps buffering rather than discarding:
     * a detach/attach cycle within one process is ordinary, and the lines in between are the
     * ones nobody would otherwise see.
     *
     * @Volatile because it is written on the main thread at attach and read from every thread
     * the engine uses — the RX loop and heartbeats on IO, the frame loop on Default,
     * ConnectivityManager's callbacks, MapLibre's native log bridge. Without it a thread may
     * never observe the sink at all, and its lines quietly never reach `app_log.txt` or Talker
     * — the two files that ARE the post-mortem this field exists for.
     */
    var sink: ((tag: String, level: String, message: String) -> Unit)?
        get() = currentSink
        set(value) {
            // Drain first, publish after, both under one lock — the order is what makes the
            // ordering claim true. [deliver]'s fast path reads [currentSink] WITHOUT this
            // lock, so a sink published before the drain could be found by another thread
            // mid-flush and let its line overtake the backlog it should follow. Published
            // last, a non-null sink means the backlog is already gone; a thread that reads
            // null instead falls into the lock and waits the drain out. A sink that logs
            // while being called re-enters on this thread, which `synchronized` allows.
            synchronized(pending) {
                if (value != null) {
                    for (line in pending) {
                        // Stamped with when it was LOGGED, not when it was flushed. Without
                        // this the whole buffer arrives wearing the attach time, and a
                        // cold-start crash report reads as if it happened mid-session.
                        val stamped = "[${clock.format(Date(line.atMs))} pre-attach] ${line.message}"
                        runCatching { value(line.tag, line.level, stamped) }
                    }
                    pending.clear()
                }
                currentSink = value
            }
        }

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: () -> String) = emit(tag, "D", message)
    fun i(tag: String, message: () -> String) = emit(tag, "I", message)
    fun w(tag: String, message: () -> String) = emit(tag, "W", message)

    fun e(tag: String, message: () -> String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val msg = message()
        runCatching { if (error == null) Log.e(tag, msg) else Log.e(tag, msg, error) }
        val full = if (error == null) msg else "$msg — ${error.javaClass.simpleName}: ${error.message}"
        deliver(tag, "E", full)
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
        deliver(tag, level, msg)
    }

    /** To the sink if there is one, to [pending] if there is not. */
    private fun deliver(tag: String, level: String, message: String) {
        currentSink?.let { runCatching { it(tag, level, message) }; return }
        synchronized(pending) {
            // Re-read inside the lock: the setter swaps and drains under it, so a sink that
            // appeared since the check above is used directly rather than being buffered
            // behind a queue that has already been flushed.
            currentSink?.let { runCatching { it(tag, level, message) }; return }
            if (pending.size >= PENDING_CAPACITY) pending.removeFirst()
            pending.addLast(Pending(tag, level, message, System.currentTimeMillis()))
        }
    }
}
