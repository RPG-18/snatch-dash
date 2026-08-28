package com.opendash.opendash_dash_engine.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash trace. Ported from OpenMotoDash/NorthStar's CrashGuard.kt (see
 * spec/wifi_retry_policy.md's "Из живого форка") — we have no Crashlytics, so this IS the whole
 * crash-reporting story for snatch-dash. Writes synchronously (the process is about to die,
 * nothing async can be trusted to flush — notably NOT [DebugLog], whose Dart-side forward is an
 * EventChannel call that needs a live, responsive Flutter engine) to
 * `<externalFilesDir>/diag/crash-*.log`.
 *
 * External storage specifically: `adb pull /sdcard/Android/data/<pkg>/files/diag` works without
 * root or `run-as`, unlike `app_log.txt` (internal storage — see spec/wifi_retry_policy.md's
 * 2026-08-28 field session for what pulling THAT cost in practice).
 *
 * Chains to whatever handler was already installed, so nothing else about crash behaviour
 * (system crash dialog, process death, Flutter's own Dart-side error reporting) changes.
 */
object CrashGuard {
    private const val TAG = "CrashGuard"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Best-effort, fully synchronous — the process is about to die.
            runCatching { writeCrash(app, thread, error) }
                .onFailure { Log.w(TAG, "failed to persist crash: ${it.message}") }
            previous?.uncaughtException(thread, error)
        }
        Log.i(TAG, "Uncaught-exception trace installed")
    }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.getExternalFilesDir(null), "diag").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))

        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("=== snatch-dash crash ===")
            appendLine("when=$stamp ($now)")
            appendLine("thread=${thread.name}")
            appendLine("exception=${error.javaClass.name}: ${error.message}")
            appendLine("apk=${BuildId.sha12(context)}")
            appendLine("app=${BuildId.versionLabel(context)}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine()
            append(stack)
        }
        File(dir, "crash-$stamp.log").writeText(text)
    }
}
