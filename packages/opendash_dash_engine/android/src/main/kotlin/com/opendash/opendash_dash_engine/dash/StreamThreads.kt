package com.opendash.opendash_dash_engine.dash

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * The two threads one stream owns: the one that draws and encodes, and the one that sends.
 *
 * **Why not the shared pools.** The frame loop used to run on `Dispatchers.Default` and the
 * RTP sender on `Dispatchers.IO`, which the coroutines documentation says shares its threads
 * with `Default`. So a pipeline with a 250 ms deadline, running with the screen off, competed
 * with the style assembler, five keep-alive senders, the RX loop and the ack logger — at
 * ordinary priority, on whichever worker happened to pick the continuation up after each
 * suspension. Two consequences, and only one of them is about speed:
 *
 *  - **identity.** In a systrace, an ANR trace or a tombstone the frame loop appeared as
 *    `DefaultDispatcher-worker-3`, a name that says nothing. Now it is `dash-frame`.
 *  - **priority.** A pool thread cannot be prioritised; a thread of our own can ask.
 *
 * `limitedParallelism(1)` was considered and does not help: its own documentation says it
 * does NOT guarantee the coroutines always run on the same subset of threads, so it buys
 * sequencing, which the loop already had, and not a thread.
 *
 * **Asking is not getting.** `Process` documents THREAD_PRIORITY_DISPLAY (-4) as something
 * applications "can not normally change to". If the request is refused this class becomes a
 * silent no-op indistinguishable from a working one, which is exactly the failure mode this
 * project keeps writing telemetry to avoid — hence [report], which reads the priority back
 * from INSIDE each thread and states what was asked next to what was granted.
 *
 * One instance per stream, closed with it: a HandlerThread that outlives its stream is a
 * leaked thread per reconnect.
 */
internal class StreamThreads {
    private val frameThread = HandlerThread(FRAME_NAME).apply { start() }

    /**
     * Where the frame loop runs — snapshot, overlays, `renderFrame`, `drain`.
     *
     * A Handler dispatcher rather than `newSingleThreadContext`, which is marked both
     * `@DelicateCoroutinesApi` and `@ExperimentalCoroutinesApi`, and rather than
     * `looper.asHandler()`, which is an internal extension of kotlinx.coroutines.
     */
    val frame: CoroutineContext = Handler(frameThread.looper).asCoroutineDispatcher(FRAME_NAME)

    init {
        // The priority is asked for from a task ON the thread, not through
        // `HandlerThread(name, priority)` as pipeline.md §4.3 sketched it.
        //
        // The difference is what happens when the request is REFUSED. `HandlerThread.run()`
        // publishes the looper, notifies whoever is waiting for it, and only then calls
        // `Process.setThreadPriority` — uncaught. A throw there kills the thread while
        // `frameThread.looper` has already been handed out and still looks perfectly valid,
        // so every frame posted to it would be swallowed: a dash that stops receiving video
        // with nothing in the log and no exception anywhere near the pipeline. Asking from
        // inside a posted task keeps the refusal in a `runCatching` where it belongs — and
        // [report] below is what says the priority was refused.
        Handler(frameThread.looper).post {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        }
    }

    private val rtpExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        // Priority set from INSIDE the thread, like HandlerThread does for its own: the
        // Android API sets the priority of the calling tid, so doing it from the factory
        // would prioritise whoever happened to create the thread instead.
        Thread({ runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }; r.run() }, RTP_NAME)
    }

    /** Where the RTP sender runs. `DatagramSocket.send` blocks, so it gets a thread to block on. */
    val rtp: ExecutorCoroutineDispatcher = rtpExecutor.asCoroutineDispatcher()

    /**
     * One line for the ride file: what each thread is called and what priority it actually got.
     *
     * Read from inside the threads themselves, because `getThreadPriority` answers for the tid
     * it is given and the only tid a caller knows for certain is its own.
     */
    suspend fun report(): String {
        val framePrio = withContext(frame) { runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull() }
        val rtpPrio = withContext(rtp) { runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull() }
        fun say(prio: Int?): String = when {
            prio == null -> "n/a"
            prio == Process.THREAD_PRIORITY_DISPLAY -> "$prio (granted)"
            else -> "$prio (asked ${Process.THREAD_PRIORITY_DISPLAY}, REFUSED)"
        }
        return "threads: $FRAME_NAME prio=${say(framePrio)} $RTP_NAME prio=${say(rtpPrio)}"
    }

    /**
     * Give both threads back.
     *
     * `quitSafely`, not `quit`: the loop's own teardown — releasing the encoder, cancelling the
     * outbox — is the last thing posted to this looper, and it has to run. Called after the
     * stream's job completes, never from inside it.
     */
    fun close() {
        frameThread.quitSafely()
        rtp.close()
    }

    private companion object {
        const val FRAME_NAME = "dash-frame"
        const val RTP_NAME = "dash-rtp"
    }
}
