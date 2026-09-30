package com.opendash.opendash_dash_engine.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only per-ride log, separate from the shared [DebugLog]/app_log.txt stream. Ported from
 * OpenMotoDash/NorthStar's RideDiagnostics.kt (see spec/wifi_retry_policy.md's "Из живого
 * форка") — its own independent use already confirmed the exact signal this port's own frame-
 * decode-ack counter (2026-08-28, DashSession.kt) was built to surface: "dash DECODED first IDR"
 * is what actually matters, not just "we sent a frame".
 *
 * Two things app_log.txt doesn't give directly:
 *   - RELATIVE offsets (`+523ms` since [start]) instead of wall-clock only — turns "how long did
 *     the handshake take" from timestamp arithmetic (done by hand more than once during the
 *     2026-08-28 field session) into a single glance.
 *   - one file PER RIDE (`<externalFilesDir>/diag/ride-*.log`, rotated to the last [KEEP_FILES]),
 *     instead of slicing a shared, size-rotated log by time range by hand.
 *
 * [log] ALSO mirrors every line into [DebugLog] so call sites only need one call and nothing
 * about the existing Talker/app_log.txt workflow changes — this is a SUPPLEMENT, not a
 * replacement. On external storage specifically so it's `adb pull`-able without `run-as`/root
 * (`adb pull /sdcard/Android/data/<pkg>/files/diag`), unlike app_log.txt.
 */
object RideDiagnostics {
    private const val TAG = "RideDiagnostics"
    private const val KEEP_FILES = 12
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    @Volatile private var sessionStartMs = 0L

    /**
     * Bumped by every [start], so a writer that batches or collapses its lines can
     * notice that the file underneath it has been replaced.
     *
     * A count of swallowed repeats belongs to the file the swallowed lines were
     * headed for. Carried across a [start] it would be written into the NEXT ride's
     * file, describing a line that file never contained — which is what
     * [com.opendash.opendash_dash_engine.dash.map.MapLibreLogBridge] did until it
     * started reading this. Reconnects make that the normal case, not a corner one:
     * the WiFi retry delay is shorter than that bridge's collapse window.
     */
    @Volatile var session = 0L
        private set
    @Volatile private var deviceLabel = "unknown device"
    @Volatile private var buildLabel = "unknown build"

    /**
     * Point the logger at [d] directly, bypassing [Context].
     *
     * A seam, not a backdoor: everything this object does to a file — opening, the
     * session header, rotation, and since task 12 closing one file before opening the
     * next — is ordinary logic that needed a directory and therefore a device to reach.
     * With this it needs a `@TempDir`. The production path still goes through [init].
     */
    internal fun useDirectory(d: File?) {
        // Closes first, for the same reason [start] does: this is `internal`, not
        // test-only, and handing anything the one operation start() was just taught
        // never to perform would put an abandoned file back on the table.
        synchronized(lock) {
            if (file != null) stop("directory changed")
            dir = d
        }
    }

    /**
     * The monotonic clock behind the `+NNNms` column, replaceable for tests.
     *
     * `SystemClock.elapsedRealtime` is one of the Android methods that throws rather than
     * returning a default in a plain JVM unit test, so without this seam nothing in this
     * object can be exercised off a device — including the file-lifecycle logic, which
     * has nothing to do with Android at all.
     */
    @Volatile internal var clockMs: () -> Long = ::monotonicMs

    /**
     * Wall clock, for the one thing that is a POINT IN TIME rather than a duration:
     * when a buffered line actually happened. Separate from [clockMs], which is
     * monotonic precisely because it measures intervals — `elapsedRealtime` cannot be
     * formatted as a time of day, and the wall clock cannot measure a window.
     */
    @Volatile internal var wallMs: () -> Long = System::currentTimeMillis

    /**
     * Wait for appends handed out by [fromFlutterDeferred] to land. Set by whoever
     * runs them; null when nobody does.
     *
     * **Because a deferred append can otherwise outlive its file's end marker.**
     * Every ride file must end with `==== session end ====`, and a file that simply
     * stops is the signal for "the process was killed mid-ride" — the one thing this
     * whole mechanism exists to catch. A line still sitting in a worker queue when
     * [stop] runs would be appended after that marker and forge exactly that signal,
     * in reverse. So [stop] drains first.
     *
     * Safe against the lock it is called under: the runnables from
     * [fromFlutterDeferred] took their decision already and touch no state of this
     * object, so a barrier that blocks while holding [lock] cannot wait on a worker
     * that wants it.
     */
    @Volatile internal var appendBarrier: (() -> Unit)? = null

    /** Point the logger at <externalFilesDir>/diag. Safe to call every time; only does work once. */
    fun init(context: Context) {
        if (dir != null) return
        runCatching { File(context.getExternalFilesDir(null), "diag").apply { mkdirs() } }
            .onSuccess { dir = it }
            .onFailure { DebugLog.w(TAG) { "init failed: ${it.message}" } }
        // The packet capture lives in the same directory and under the same per-ride name,
        // and is driven from here for exactly that reason: two files of one ride must not be
        // able to disagree about which ride they are. It is a no-op outside a debug build.
        PacketCapture.init(context)
        deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        buildLabel = "build ${BuildId.sha12(context)} @${BuildId.gitSha}, app ${BuildId.versionLabel(context)}"
        // Also into app_log.txt, which the session header never reaches: [start]
        // writes that header with [raw], straight to the ride file. So a pulled
        // app_log.txt carried no build identity at all — and it is the half of the
        // pair that gets pulled alone, because it is where the native MapLibre and
        // socket chatter lands. One line per process start.
        DebugLog.i(TAG) { "$deviceLabel, $buildLabel" }
    }

    /**
     * Open a fresh session file and rotate old ones. No-op if [init] was never called.
     *
     * **Any file still open is closed first**, with [SUPERSEDED] as its reason. Before
     * 2026-09-28 it was simply abandoned: a second `connect()` while the first was still
     * retrying left a ride file ending mid-line, with no `session summary` and no
     * `==== session end ====`. That is indistinguishable from the process having been
     * killed — the one case the whole file exists to record. Task 12.
     *
     * The caller writes the closing lines it owns (the Wi-Fi totals) BEFORE calling
     * this, unconditionally — whether a file is open is this object's business, and a
     * caller that checked would tie its own bookkeeping to whether storage was writable.
     */
    fun start(reason: String) {
        // ONE lock for the whole thing, close included. With the close outside it, a
        // write() from any of the engine's threads could land between `file = null` and
        // the reassignment and be dropped by [write]'s null guard; and two concurrent
        // starts could both see a file to close, no-op the second, and leave two headers
        // against one end marker — the shape RideDiagnosticsTest asserts cannot happen.
        // The monitor is reentrant, so [stop]'s own `synchronized` nests fine.
        synchronized(lock) {
            if (file != null) stop(SUPERSEDED)
            // Bumped before the early return: a ride began either way, and a collapsing
            // writer has to notice that even when no file could be opened for it.
            session++
            val d = dir ?: return
            // Monotonic: this is the origin of the "+NNNms" column on every line below, i.e.
            // a duration. On the wall clock an NTP step mid-ride shifts every subsequent
            // offset — and those offsets are what a post-mortem measures intervals with.
            sessionStartMs = clockMs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file = File(d, "ride-$stamp.log")
            PacketCapture.start(stamp)
            rotate(d)
            raw("==== session start: $reason — $deviceLabel, $buildLabel ====")
            flushPending()
        }
    }

    /** Record one event, stamped with wall clock + offset from session start. Also reaches
     *  DebugLog/app_log.txt regardless of whether a session file is currently open. */
    fun log(tag: String, msg: String) {
        DebugLog.i(tag) { msg }
        write(tag, msg)
    }

    /**
     * [log] at warning level: the same ride-file line, prefixed `WARN` so a
     * post-mortem can grep for trouble, mirrored into [DebugLog] at `W`.
     *
     * The level is why this exists rather than being one more [log] call. The
     * ride file is the only copy that survives a release build, but app_log.txt
     * and `/more/logs` are read through a level filter — a failure logged at `I`
     * is a failure nobody sees there. Call sites used to write both by hand (a
     * [DebugLog.w] next to a [log] whose text started with "WARN"); this is that
     * pair, once.
     */
    fun warn(tag: String, msg: String) {
        DebugLog.w(tag) { msg }
        write(tag, "WARN $msg")
    }

    /**
     * One error from the Dart side, into the ride file — and nowhere else.
     *
     * **No [DebugLog] mirror, unlike [warn].** Dart has already put this line in
     * Talker; mirroring it here sends it back up the log EventChannel to
     * `attachNativeLogBridge`, which logs it to Talker a second time at a second
     * level. Two entries per error in `app_log.txt` and `/more/logs`, both eating
     * the 10 MB cap that the Dart-side limiter exists to defend.
     *
     * The only caller of the pre-session buffer ([head]/[tail]), because it is the
     * only source whose failures are worth keeping when no ride is running: a
     * settings screen throwing with no dash connected is the case this was all built
     * for.
     *
     * Not rate-limited here. The rule runs once, in Dart, upstream of both sinks —
     * see `lib/util/error_reporting.dart`.
     */
    fun fromFlutter(msg: String) {
        fromFlutterDeferred(msg)?.run()
    }

    /**
     * [fromFlutter] split in two: **route now, append later.**
     *
     * The plugin calls this on the platform thread and runs what comes back on a
     * worker, so the FUSE-backed file write stays off the thread MapLibre's snapshot
     * callbacks share. Deferring the whole call instead was wrong and review caught
     * it: `start`/`stop` run on that same thread, so a mid-session error whose worker
     * task lost the race with a disconnect would find no file, land in the buffer and
     * be replayed into the NEXT ride file as "outside a session" — the cross-file
     * misfiling [session] exists to prevent, reintroduced by the fix for a different
     * problem.
     *
     * Everything order-dependent therefore happens here, under [lock] and in the
     * caller's order: which file the line belongs to, whether there is one at all,
     * and the `+NNNms` offset. The returned [Runnable] holds that decision — it
     * appends to the [File] this call chose, not to whatever is current when it runs,
     * so even a session that ends in between cannot take the line with it.
     *
     * What the caller still pays is the lock, not the write: it waits only if another
     * thread is mid-append, where before it performed the append itself.
     */
    fun fromFlutterDeferred(msg: String): Runnable? = synchronized(lock) {
        val text = "[flutter] WARN $msg"
        val f = file ?: run {
            buffer(text)
            return@synchronized null
        }
        val rel = if (sessionStartMs > 0) "+%6dms".format(clockMs() - sessionStartMs) else "         "
        val line = "${clock.format(Date())}  $rel  $text\n"
        Runnable { runCatching { f.appendText(line) } }
    }

    /**
     * Lines that arrived with no ride file open, kept for the next one.
     *
     * **Because the errors most worth keeping happen outside a session.** The Flutter
     * error this whole path exists for (28.09, the settings screen) fired with no dash
     * connected, so `write` dropped it and a release build — where `DebugLog` is
     * compiled out — recorded nothing at all. Review caught the feature failing in its
     * own motivating case, 2026-09-29.
     *
     * **Opt-in per call, not the default for everything.** The first version buffered
     * every write that found no file, which also caught the TAIL of a session — the RX
     * loop's "socket error", `FrameStreamer`'s finally, the `[mem]` job on
     * `Dispatchers.IO`, all of which keep writing after [stop] has run on Main. Those
     * were then replayed at the head of the NEXT ride file, which is exactly the
     * misfiling [session] exists to prevent and that `MapFrameRenderer` and
     * `DashWifiManager` are written around. Only a caller with nowhere else to go asks
     * for the buffer.
     *
     * Bounded and lossy on purpose, from the middle outwards — see [PENDING_MAX].
     * A ride file is a post-mortem, not a spool.
     */
    private class Pending(val atMs: Long, val text: String)

    /**
     * Hold a line no file has claimed. Under [lock] already.
     *
     * Stamped with [wallMs] here and not at the flush, because this is the only
     * moment that knows when it happened — there is no `+NNNms` to give it, since
     * there is no session to measure from.
     */
    private fun buffer(text: String) {
        val line = Pending(wallMs(), text)
        if (head.size < PENDING_MAX / 2) {
            head.addLast(line)
        } else {
            tail.addLast(line)
            while (tail.size > PENDING_MAX - PENDING_MAX / 2) {
                tail.removeFirst()
                pendingDropped++
            }
        }
    }

    /**
     * The FIRST few, kept whatever comes after. A flood's root cause is its first
     * line — the 28.09 screen threw one real error and then knock-on assertions once
     * per frame — and a buffer that only evicts the oldest throws away exactly that
     * and keeps the consequences.
     */
    private val head = ArrayDeque<Pending>()

    /**
     * ...and the NEWEST few, evicting within themselves. An error still in flight
     * when the rider connects is the other thing worth having, and it is the one a
     * head-only buffer loses.
     */
    private val tail = ArrayDeque<Pending>()
    private var pendingDropped = 0

    /**
     * Throw away what no file has claimed yet.
     *
     * Test-only, and needed precisely because the buffer outliving a session is the
     * feature: this is an `object`, so lines left pending by one test are flushed into
     * the next one's file. Nothing in production drops them — that is [flushPending]'s
     * job, once.
     */
    internal fun clearPending() {
        synchronized(lock) {
            head.clear()
            tail.clear()
            pendingDropped = 0
        }
    }

    /** Write the lines that arrived before this file existed. Under [lock] already. */
    private fun flushPending() {
        if (head.isEmpty() && tail.isEmpty() && pendingDropped == 0) return
        val first = head.toList()
        val newest = tail.toList()
        val dropped = pendingDropped
        head.clear()
        tail.clear()
        pendingDropped = 0
        fun write(lines: List<Pending>) {
        for (line in lines) {
            // Stamped with when it was LOGGED, not when it is being flushed. [raw]
            // prepends the flush time to every line it writes, so without this the
            // whole buffer arrives wearing one identical clock — an error from before
            // breakfast reads as having happened two seconds before the ride started.
            // [DebugLog]'s own pre-attach buffer carries the same stamp for the same
            // reason, and says so.
            raw("           [${clock.format(Date(line.atMs))} outside a session] ${line.text}")
        }
        }
        write(first)
        // Between the halves, not after them: what was lost happened between the
        // first lines and the newest, and printed at the end it reads as a gap after
        // the most recent entry — the one place it certainly is not.
        if (dropped > 0) {
            raw("           [diag] $dropped line(s) in between dropped — over the $PENDING_MAX-line buffer")
        }
        write(newest)
    }

    private fun write(tag: String, msg: String, keepWhenIdle: Boolean = false) {
        // The test is inside the lock, not before it: unlocked, a line could find
        // `file == null`, be preempted by a [start] that opens the file and flushes the
        // buffer, and only then be appended — left waiting for the session after next,
        // or lost with the process. Which is the loss the buffer exists to prevent.
        synchronized(lock) {
            if (file == null) {
                if (keepWhenIdle) buffer("[$tag] $msg")
                return
            }
            val rel = if (sessionStartMs > 0) "+%6dms".format(clockMs() - sessionStartMs) else "         "
            raw("$rel  [$tag] $msg")
        }
    }

    /** Reason [start] closes a file that was still open. */
    const val SUPERSEDED = "superseded by a new connect()"

    /**
     * How many pre-session lines are kept for the next ride file.
     *
     * Small deliberately, and split down the middle: the first half of the budget is
     * the first lines seen and is never evicted, the second half is a ring of the
     * newest. Both ends matter and they are different lines — the root cause is
     * first, the error still in flight when the rider connects is last — so keeping
     * only one end loses a case. What falls between them is counted, not kept.
     *
     * "Outside", not "before": a buffered line can equally have arrived after the
     * previous ride ended, and the label it carries must not claim otherwise.
     */
    private const val PENDING_MAX = 20

    fun stop(reason: String) {
        if (file == null) return
        // Before the marker, and before the lock: see [appendBarrier].
        runCatching { appendBarrier?.invoke() }
        synchronized(lock) {
            raw("==== session end: $reason ====")
            file = null
            PacketCapture.stop()
        }
    }

    private fun raw(line: String) {
        val f = file ?: return
        runCatching { f.appendText("${clock.format(Date())}  $line\n") }
    }

    private fun rotate(d: File) {
        val logs = d.listFiles { x -> x.name.startsWith("ride-") && x.name.endsWith(".log") } ?: return
        if (logs.size <= KEEP_FILES) return
        logs.sortedBy { it.lastModified() }.dropLast(KEEP_FILES).forEach { runCatching { it.delete() } }
    }
}
