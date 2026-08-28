package ru.snatchdash.app

import android.app.Application
import com.opendash.opendash_dash_engine.util.BuildId
import com.opendash.opendash_dash_engine.util.CrashGuard
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
    }
}
