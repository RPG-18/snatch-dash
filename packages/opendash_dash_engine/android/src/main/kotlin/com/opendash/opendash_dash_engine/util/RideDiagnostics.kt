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
    @Volatile private var deviceLabel = "unknown device"
    @Volatile private var buildLabel = "unknown build"

    /** Point the logger at <externalFilesDir>/diag. Safe to call every time; only does work once. */
    fun init(context: Context) {
        if (dir != null) return
        runCatching { File(context.getExternalFilesDir(null), "diag").apply { mkdirs() } }
            .onSuccess { dir = it }
            .onFailure { DebugLog.w(TAG) { "init failed: ${it.message}" } }
        deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        buildLabel = "build ${BuildId.sha12(context)}, app ${BuildId.versionLabel(context)}"
    }

    /** Open a fresh session file and rotate old ones. No-op if [init] was never called. */
    fun start(reason: String) {
        val d = dir ?: return
        synchronized(lock) {
            sessionStartMs = System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file = File(d, "ride-$stamp.log")
            rotate(d)
            raw("==== session start: $reason — $deviceLabel, $buildLabel ====")
        }
    }

    /** Record one event, stamped with wall clock + offset from session start. Also reaches
     *  DebugLog/app_log.txt regardless of whether a session file is currently open. */
    fun log(tag: String, msg: String) {
        DebugLog.i(tag) { msg }
        if (file == null) return
        synchronized(lock) {
            val rel = if (sessionStartMs > 0) "+%6dms".format(System.currentTimeMillis() - sessionStartMs) else "         "
            raw("$rel  [$tag] $msg")
        }
    }

    fun stop(reason: String) {
        if (file == null) return
        synchronized(lock) {
            raw("==== session end: $reason ====")
            file = null
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
