package com.opendash.opendash_dash_engine.dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.opendash.opendash_dash_engine.util.DebugLog
import com.opendash.opendash_dash_engine.util.RideDiagnostics

/**
 * Foreground service that keeps OpenDash streaming to the dash while the
 * phone screen is OFF — the whole reason this app exists.
 *
 * It does NOT own the streaming pipeline (that lives in [DashEngineController],
 * driven from Dart). Its job is purely to stop Android from freezing the
 * process:
 *   • PARTIAL_WAKE_LOCK  — CPU keeps running the 4 Hz encoder/RTP loop with
 *     the screen off (otherwise Doze suspends it → dash times out).
 *   • WifiLock (HIGH_PERF) — stops WiFi power-save from tearing down the
 *     WifiNetworkSpecifier link to the Tripper hotspot.
 *   • Ongoing notification — required for a foreground service; also lets the
 *     rider re-open the app.
 *
 * Ported from the original native app's `DashKeepAliveService`. Unlike the
 * original, this lives in a Flutter plugin module, so it can't reference the
 * host app's `MainActivity`/`R.mipmap` directly — it resolves the launch
 * intent and notification icon generically via `PackageManager` instead.
 */
class DashKeepAliveService : Service() {
    companion object {
        private const val TAG          = "DashKeepAlive"
        private const val CHANNEL_ID   = "opendash_dash"
        private const val NOTIF_ID     = 4701
        const val ACTION_START = "ru.snatchdash.app.DASH_START"
        const val ACTION_STOP  = "ru.snatchdash.app.DASH_STOP"

        fun start(context: Context) {
            val i = Intent(context, DashKeepAliveService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DashKeepAliveService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    /** LOW_LATENCY alongside HIGH_PERF — see acquireLocks for why both. */
    private var lowLatencyLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> startForegroundLocks()
            // A null intent means the OS restarted us after killing the process
            // (START_STICKY re-delivers no intent). The streaming pipeline lives
            // in [DashEngineController] and is driven from Dart — it did NOT
            // come back with us, so holding the wake and WiFi locks here would
            // drain the battery behind an ongoing "streaming to dash"
            // notification with nothing behind it. Dart restarts this service
            // itself when it reconnects.
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        // NOT sticky, for the same reason: a kill under memory pressure takes the
        // Flutter engine and the streaming pipeline with it, so a service the OS
        // brings back alone has nothing to keep alive. It used to be START_STICKY,
        // which only ever produced the zombie above.
        return START_NOT_STICKY
    }

    private fun startForegroundLocks() {
        createChannel()
        // The LOCATION type is what lets GPS keep updating with the screen off —
        // without it Android 14+ freezes location for backgrounded apps and the
        // rider marker sticks at its first fix.
        startForeground(
            NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        acquireLocks()
        DebugLog.i(TAG) { "Foreground service up — wake+wifi locks held" }
    }

    /**
     * Idempotent, which it was not.
     *
     * `ACTION_START` reaches an already-running service on a reachable path — `connect()`'s
     * "live session, dead link" branch calls `DashKeepAliveService.start()` again — and each
     * call used to overwrite the three lock fields while the old locks were still held. Nothing
     * could reach them afterwards, so [releaseLocks] freed the new ones and the previous set
     * stayed acquired for the life of the process. With the wake lock and HIGH_PERF that cost
     * battery; with LOW_LATENCY added it also pins the Wi-Fi chip out of power save long after
     * the ride is over. Found by review, 2026-09-13.
     */
    private fun acquireLocks() {
        if (wakeLock != null || wifiLock != null || lowLatencyLock != null) {
            DebugLog.i(TAG) { "Locks already held — not acquiring a second set" }
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opendash:dash").apply {
            setReferenceCounted(false)
            // No timeout, and Lint's WakelockTimeout is wrong about this one: a ride has no
            // known length, and a lock that expires mid-ride is the failure this service
            // exists to prevent. Its lifetime is bounded by the service instead — released
            // in onDestroy, and the service is stopped by disconnect().
            @Suppress("WakelockTimeout")
            acquire()
        }

        // TWO Wi-Fi locks, and the reason is that neither one covers the whole SDK range.
        //
        // The old comment here said HIGH_PERF "keeps the link awake even with the screen off,
        // at lower power than LOW_LATENCY". True through Android 13. From 14 it is not: with
        // `high_perf_lock_deprecated` on (its default), WifiLockManager.acquireWifiLock
        // silently substitutes LOW_LATENCY — and LOW_LATENCY is active only while the screen
        // is ON and the UID sits at IMPORTANCE_FOREGROUND (100). A process holding one
        // foreground service is 125 and does not qualify. So on 14+ with the screen off, which
        // is exactly how this app is meant to be ridden, our lock did nothing and the chip sat
        // in 802.11 power save. Charging does not change it: WifiLockManager never looks at the
        // battery. Sources: WifiManager javadoc, WifiLockManager.java, DeviceConfigFacade.java
        // in packages/modules/Wifi.
        //
        // Holding both is the scheme the javadoc itself describes. On 10-13 HIGH_PERF does the
        // work; on 14+ neither lock helps with the screen off, and the honest fix there is the
        // "on the handlebars, charging" mode (a foreground activity with a black screen at
        // minimum brightness) — not implemented, and not worth implementing until the `rx gap`
        // numbers from DashSession say power save is actually costing us something.
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "opendash:dash").apply {
            setReferenceCounted(false)
            acquire()
        }
        lowLatencyLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            "opendash:dash-lowlatency",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        // What we know, not what we infer. The substitution is gated on the runtime
        // `wifi_high_perf_lock_deprecated` DeviceConfig flag, not on SDK_INT — the flag merely
        // defaults to on from 34 — and an app cannot read it. So the line says which locks are
        // held and that a remap is LIKELY above 34, and leaves the conclusion to whoever reads
        // it next to the `rx gap` numbers. Writing "neither lock is doing anything" into the
        // file that exists to carry the evidence would be the same mistake the `sockets: tos`
        // line had to unlearn: the OS accepting a request is not the request taking effect.
        RideDiagnostics.log(
            TAG,
            "wifi lock: sdk=${Build.VERSION.SDK_INT} modes=high_perf+low_latency — " +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    "high_perf is likely remapped to low_latency here (device flag, not " +
                        "readable), and low_latency needs the screen on — compare rx gap " +
                        "with the screen off against the 144/489ms baseline of 2026-09-13"
                } else {
                    "high_perf applies on this SDK regardless of screen state"
                },
        )
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (lowLatencyLock?.isHeld == true) lowLatencyLock?.release() }
        wakeLock = null
        wifiLock = null
        lowLatencyLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        DebugLog.i(TAG) { "Foreground service stopped — locks released" }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Dash streaming", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Keeps the map streaming to the Tripper Dash" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = Notification.Builder(this, CHANNEL_ID)

        val appIcon = applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_menu_compass

        return builder
            .setContentTitle("OpenDash — streaming to dash")
            .setContentText("Map is live on the Tripper. Screen can stay off.")
            .setSmallIcon(appIcon)
            .setOngoing(true)
            .apply { open?.let { setContentIntent(it) } }
            .build()
    }
}
