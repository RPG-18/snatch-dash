package com.opendash.opendash_dash_engine.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures WHY the process last died — including the deaths [CrashGuard] can't see.
 *
 * CrashGuard only catches uncaught JVM exceptions. A native crash (MediaCodec/the hardware
 * H.264 encoder — see DashEncoder.kt), an ANR, or an OEM low-memory kill bypasses it entirely.
 * Ported from OpenMotoDash/NorthStar's ExitInfoCollector.kt (see spec/wifi_retry_policy.md's "Из
 * живого форка") — added specifically because of the 2026-08-28 field session where a 12-minute
 * gap in EVERY log tag between two rides could only be explained indirectly (LocationTracker's
 * `start()` not being a no-op meant something recreated the whole controller) instead of read
 * directly off the OS. This answers that "was it killed, and why" question straight from Android
 * instead of by inference.
 *
 * Android's [ApplicationExitInfo] (API 30+) records the reason for each recent process exit —
 * for native crashes/ANRs it carries the tombstone/trace too. [collect] reads any NEW abnormal
 * exits on the next launch and writes them to `<externalFilesDir>/diag/crash-exit-*.log` (same
 * directory/pull path as [CrashGuard]'s own crash-*.log — `adb pull
 * /sdcard/Android/data/<pkg>/files/diag`, no root/`run-as`). [arm] additionally tags the
 * CURRENTLY running process with the build that's running it, so the exit record — read back on
 * a LATER launch, possibly after a newer build was sideloaded — is attributed to the build that
 * actually died, not whatever happens to be installed when it's read.
 */
object ExitInfoCollector {
    private const val TAG = "ExitInfoCollector"
    private const val PREFS = "opendash_exitinfo"
    private const val KEY_LAST_TS = "last_exit_ts"

    private val INTERESTING = setOf(
        ApplicationExitInfo.REASON_CRASH,          // JVM crash (CrashGuard also catches; harmless dup)
        ApplicationExitInfo.REASON_CRASH_NATIVE,   // native crash — the one CrashGuard is blind to
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,     // OEM killed us under pressure
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    /**
     * Tag THIS live process with the build that's running, so when it dies its exit record
     * carries the build that actually crashed — not whatever build happens to be installed when
     * we read the record back on the next launch. Call once from Application.onCreate.
     */
    fun arm(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        // sha12 hashes the APK (blocks first time) — do it off the main thread.
        Thread {
            runCatching {
                val am = app.getSystemService(ActivityManager::class.java) ?: return@runCatching
                am.setProcessStateSummary(BuildId.sha12(app).toByteArray(Charsets.UTF_8))
            }.onFailure { Log.w(TAG, "arm failed: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    /** Read any exits since the last call and write the abnormal ones to disk. Call once from
     *  Application.onCreate, right after [arm]. */
    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_LAST_TS, 0L)
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            val dir = File(context.getExternalFilesDir(null), "diag").apply { mkdirs() }
            var newest = lastSeen

            for (info in infos) {
                if (info.timestamp <= lastSeen) continue
                newest = maxOf(newest, info.timestamp)
                // Only the abnormal exits — a clean user-initiated close isn't a crash.
                if (info.reason !in INTERESTING) continue
                runCatching { writeExit(context, dir, info) }
                    .onFailure { Log.w(TAG, "write exit failed: ${it.message}") }
                // Also surface it directly in the shared log — this runs from
                // Application.onCreate, BEFORE the plugin attaches and wires DebugLog.sink to
                // Dart, so on a true cold start this specific line only reaches native logcat;
                // the file above is the durable copy that lands in app_log.txt's world either
                // way (visible next time someone greps the diag folder).
                DebugLog.w(TAG) { "Last exit: ${reasonName(info.reason)} — ${info.description ?: ""}" }
            }
            prefs.edit().putLong(KEY_LAST_TS, newest).apply()
        }.onFailure { Log.w(TAG, "collect failed: ${it.message}") }
    }

    private fun writeExit(context: Context, dir: File, info: ApplicationExitInfo) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(info.timestamp))
        // ANR + native crashes expose a trace/tombstone; other reasons don't.
        val trace = runCatching {
            info.traceInputStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        // The build that ACTUALLY died — carried in the process state summary [arm] set.
        // "unknown" for exits recorded before arming (e.g. the very first run after this
        // feature ships) so we never mis-claim a build.
        val crashedBuild = runCatching {
            info.processStateSummary?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "unknown"

        val text = buildString {
            appendLine("=== snatch-dash process exit (ApplicationExitInfo) ===")
            appendLine("when=$stamp (${info.timestamp})")
            appendLine("reason=${reasonName(info.reason)} — ${info.description ?: ""}")
            appendLine("importance=${info.importance} status=${info.status}")
            appendLine("apk=$crashedBuild")
            appendLine("collected-by=${BuildId.sha12(context)}")
            appendLine("app=${BuildId.versionLabel(context)}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            if (trace != null) {
                appendLine()
                appendLine("--- trace / tombstone ---")
                append(trace)
            }
        }
        File(dir, "crash-exit-$stamp.log").writeText(text)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "JVM_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY_KILL"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "REASON_$reason"
    }
}
