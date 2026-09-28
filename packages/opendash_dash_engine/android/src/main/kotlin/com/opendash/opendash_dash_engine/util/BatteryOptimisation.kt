package com.opendash.opendash_dash_engine.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether the system will let this app keep working with the screen off, and how to ask.
 *
 * **Why the app cares.** The whole product runs with the phone's screen dark in a jacket
 * pocket: `DashKeepAliveService` holds the Wi-Fi lock and the frame loop feeds the dash
 * four times a second. Doze and the OEM equivalents stop exactly that, and the symptom is
 * the one the ride files keep showing — the map freezes, the dash keeps its last frame,
 * and nothing in the app logs an error because nothing in the app failed.
 *
 * **Asking is allowed here.** Google's Play policy forbids the
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt for most apps and lists exceptions; one is
 * a "peripheral device companion" — an app whose core function is communicating with a
 * device the user wears or rides. That is this app's only function. The prompt is still a
 * prompt: the rider can refuse, and can revoke it later in Settings.
 *
 * **What this does NOT fix.** On EMUI the exemption is not enough by itself — see
 * [emuiWorkaroundNeeded].
 */
internal object BatteryOptimisation {

    /** True when the system has already exempted us, so there is nothing to ask for. */
    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        // Guarded rather than assumed: `isIgnoringBatteryOptimizations` throws on some
        // OEM builds that ship the API without the underlying service, and a settings
        // screen that crashes is worse than one that shows the button needlessly.
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * The intent that opens the system prompt, or null when it cannot be shown.
     *
     * Null when nothing on the device can handle the action — some stripped builds.
     *
     * Does NOT check [isIgnoring]: every caller has just computed it, and doing it again
     * here was a second binder round trip to `DeviceIdleController` per call, on the
     * platform thread the frame pipeline shares.
     */
    // `BatteryLife` is Lint telling us Play restricts this prompt. It does, and this app
    // is on the documented exception list — see the class doc. Suppressed here AND on the
    // manifest declaration (`tools:ignore`), because Lint reports both and the manifest
    // hit is the one a Play reviewer or a new contributor meets first. The module keeps no
    // baseline (CLAUDE.md), so a warning left standing is a warning that stays.
    @android.annotation.SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent? {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        // `resolveActivity` rather than catching ActivityNotFoundException after the
        // fact: the caller wants to know BEFORE it tells the rider a button will work.
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    /**
     * True on the phones where the exemption above is necessary but not sufficient.
     *
     * EMUI 9 and later run `PowerGenie`/`HwPFWService`, which kills background processes
     * by wakelock and network activity against a whitelist third-party apps cannot join —
     * independently of Doze and therefore independently of this exemption. The documented
     * way round it is manual and lives in the UI text, because only the rider can do it:
     * **Settings → Battery → App launch → snatch-dash → turn off "Manage automatically"**,
     * then enable auto-launch, secondary launch and run in background.
     *
     * Matched on manufacturer rather than a feature probe because there is nothing to
     * probe: `PowerGenie` exposes no API, and its absence looks exactly like its presence
     * until something has already been killed. Sources are in
     * `spec/wifi_retry_policy.md`.
     */
    fun emuiWorkaroundNeeded(manufacturer: String = Build.MANUFACTURER): Boolean =
        EMUI_MAKERS.any { manufacturer.trim().equals(it, ignoreCase = true) }

    /**
     * Honor as well as Huawei: the sub-brand shipped EMUI with the same `PowerGenie`
     * before the 2020 split, and Magic UI after it kept the behaviour.
     *
     * A parameter on [emuiWorkaroundNeeded] rather than a direct read of the static, so
     * the matching itself can be tested — `Build.MANUFACTURER` is empty in a JVM test,
     * which would make every case pass for the wrong reason.
     */
    private val EMUI_MAKERS = listOf("HUAWEI", "HONOR")
}
