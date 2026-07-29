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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DashKeepAliveService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else        -> startForegroundLocks()
        }
        // START_STICKY: if the OS kills us under memory pressure, restart so the
        // ride keeps streaming.
        return START_STICKY
    }

    private fun startForegroundLocks() {
        createChannel()
        // The LOCATION type is what lets GPS keep updating with the screen off —
        // without it Android 14+ freezes location for backgrounded apps and the
        // rider marker sticks at its first fix.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        acquireLocks()
        DebugLog.i(TAG) { "Foreground service up — wake+wifi locks held" }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opendash:dash").apply {
            setReferenceCounted(false)
            acquire()
        }

        // HIGH_PERF keeps the link awake (prevents WiFi power-save from dropping the
        // WifiNetworkSpecifier connection) at lower power than LOW_LATENCY. 4 fps /
        // ~200 kbps doesn't need low-latency mode's extra power draw.
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "opendash:dash").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        DebugLog.i(TAG) { "Foreground service stopped — locks released" }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

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
