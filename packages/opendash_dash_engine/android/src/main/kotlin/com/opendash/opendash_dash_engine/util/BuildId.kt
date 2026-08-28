package com.opendash.opendash_dash_engine.util

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 of the app's OWN installed APK — the ground-truth identity of the running build.
 *
 * Stamped into every ride-diagnostic session and crash/exit log ([RideDiagnostics],
 * [CrashGuard], [ExitInfoCollector]) so a pulled log is matched 1:1 against the build that
 * actually produced it, instead of trusting whatever happens to be installed when it's read.
 * Ported from OpenMotoDash/NorthStar's BuildId.kt — see spec/wifi_retry_policy.md's "Из живого
 * форка" for how that fork was found.
 */
object BuildId {
    @Volatile private var cached: String? = null

    /** First 12 hex of SHA-256 over the installed APK. "unknown" if it can't be read. */
    fun sha12(context: Context): String =
        cached ?: synchronized(this) {
            cached ?: runCatching { compute(context) }.getOrDefault("unknown").also { cached = it }
        }

    /** Warm the cache off the main thread (call once from Application.onCreate). */
    fun warm(context: Context) {
        val app = context.applicationContext
        Thread { sha12(app) }.apply { isDaemon = true }.start()
    }

    private fun compute(context: Context): String {
        val apk = File(context.applicationInfo.sourceDir)
        val md = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * "versionName (versionCode)" for the running app. Unlike [sha12] this can't just read
     * `BuildConfig` — this object lives in the `opendash_dash_engine` plugin module, whose own
     * generated `BuildConfig` carries the PLUGIN's version, not the app's — so it goes through
     * [android.content.pm.PackageInfo] instead, which works for whatever app embeds this module.
     */
    fun versionLabel(context: Context): String = runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION") pkg.versionCode.toLong()
        }
        "${pkg.versionName} ($code)"
    }.getOrDefault("unknown")
}
