package ru.snatchdash.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.opendash.opendash_dash_engine.util.BuildId
import com.opendash.opendash_dash_engine.util.CrashGuard
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.ExitInfoCollector

/**
 * Installs crash/exit diagnostics as early as possible — before Flutter/the dash engine plugin
 * even attaches, so nothing that can crash the app runs unwatched. Ported from
 * OpenMotoDash/NorthStar's NorthstarApplication.kt (see spec/wifi_retry_policy.md's "Из живого
 * форка" for how that fork was found).
 */
class SnatchDashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Hash the installed APK off-thread so the build identity is ready to stamp into
        // ride/crash logs the moment anything needs it — see BuildId's own doc for why a
        // sideloaded test build needs this instead of trusting versionName/versionCode.
        BuildId.warm(this)
        // Install the uncaught-exception trace FIRST — before anything else has a chance to crash.
        CrashGuard.install(this)
        // Capture WHY we died last time — native crash / ANR / OEM low-memory kill — which
        // CrashGuard's JVM-only handler can't see at all. See ExitInfoCollector's own doc for
        // the field session (2026-08-28) this closes the loop on. arm() tags THIS process so a
        // LATER exit is attributed to the build that actually ran; collect() reads exits that
        // happened before now (i.e. the previous run).
        ExitInfoCollector.arm(this)
        ExitInfoCollector.collect(this)
        installStrictMode()
    }

    /**
     * StrictMode, and specifically `detectLeakedClosableObjects`.
     *
     * That one check is here because of a real bug: [com.opendash.opendash_dash_engine.dash
     * .DashSession]'s `runSession` used to leak its `DashSocket` on three of its four exits,
     * and finding it took reading the teardown paths against each other. StrictMode finds the
     * same thing by itself — `CloseGuard` prints a warning with the **stack of where the
     * object was created**, which is the half the reasoning had to reconstruct. A leaked
     * DatagramSocket still bound to :2002 is a socket competing for the dash's packets, so
     * this is not hygiene, it is the class of defect that reads as "the dash went quiet".
     *
     * Debug builds only. StrictMode is a development instrument; it costs allocation tracking
     * and its findings are for whoever is at the keyboard, not for a rider on a bike.
     *
     * **Not `detectAll()` on the thread policy.** Flutter reads assets and shared preferences
     * from the main thread during startup, so disk-read detection reports a dozen violations
     * before the app draws anything, and a report nobody can act on is a report nobody reads.
     * Network on the main thread is what matters here — `DashSession.disconnect` sends its
     * farewell packets through `runBlocking` for exactly that reason — and it is cheap and
     * quiet.
     */
    private fun installStrictMode() {
        // The debuggable flag rather than BuildConfig.DEBUG: this module does not generate a
        // BuildConfig (only the engine plugin enables that feature), and the flag says the
        // same thing — including for the `profile` variant, which Flutter builds as
        // debuggable (see CLAUDE.md).
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            // Seeded from the policy already in force, and `penaltyDeathOnNetwork` restated
            // explicitly. A Builder() started from scratch REPLACES the mask the platform
            // installs in ActivityThread.handleBindApplication — which is where
            // NetworkOnMainThreadException comes from — so a fresh builder would leave
            // main-thread network calls merely logged in a debug build while they still
            // threw in release. That is the guardrail inverted: the build you develop on
            // would be the forgiving one, and DashSession.disconnect()'s
            // runBlocking(Dispatchers.IO) exists precisely because that throw is real.
            StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())
                .detectNetwork()
                .penaltyLog()
                .penaltyDeathOnNetwork()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                // Into the app's own log as well as logcat: the ride is over by the time
                // anyone looks, and logcat's ring buffer is minutes long (see CLAUDE.md,
                // «Полевые логи с дэша»). penaltyListener needs API 28; the floor is 29.
                .penaltyListener(mainExecutor) { violation ->
                    DebugLog.w("StrictMode") {
                        "${violation.javaClass.simpleName}: ${violation.cause?.message ?: violation.message}"
                    }
                }
                .build(),
        )
        DebugLog.i("StrictMode") { "installed (debug build): closable leaks + network on main" }
    }
}
