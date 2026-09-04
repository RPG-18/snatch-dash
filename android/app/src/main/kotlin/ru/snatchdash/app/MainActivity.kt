package ru.snatchdash.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/**
 * Self-update install flow (see `lib/util/apk_installer.dart` / settings_screen.dart's
 * update card): the APK is downloaded by Dart into `cache/updates/` (declared in
 * `res/xml/file_paths.xml`), then handed back here to turn into a `content://` URI via
 * [FileProvider] and hand off to the system package installer. Kept as a few plain
 * methods on the app's own channel rather than a pub package — this is the entire
 * surface we need, no reason to pull in a dependency for it.
 */
class MainActivity : FlutterActivity() {
    private val channelName = "ru.snatchdash.app/updater"
    private val mapsChannelName = "ru.snatchdash.app/maps"

    private val downloader by lazy { MapPackDownloader(applicationContext) }

    /** Single thread: sha256 over a large pack must never run on the main one,
     *  and two reconciles racing over the same `.part` file help nobody. */
    private val mapsWorker = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "canInstallPackages" -> result.success(canInstallPackages())
                "openInstallPermissionSettings" -> {
                    openInstallPermissionSettings()
                    result.success(null)
                }
                "installApk" -> {
                    val path = call.argument<String>("path")
                    if (path == null) {
                        result.error("NO_PATH", "Missing 'path' argument", null)
                    } else {
                        installApk(path)
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, mapsChannelName)
            .setMethodCallHandler { call, result -> handleMaps(call.method, call, result) }
    }

    override fun onDestroy() {
        mapsWorker.shutdown()
        super.onDestroy()
    }

    /**
     * Offline map packs (see spec/offline_maps_screen.md). Same reasoning as the
     * updater channel above: a handful of plain methods on the app's own channel.
     *
     * Downloading lives here rather than in `opendash_dash_engine` on purpose —
     * it has nothing to do with the dash. The two sides meet only at the
     * directory and the naming convention: Dart puts verified packs into
     * the `maps` directory under their `.pmtiles` name, and the engine
     * enumerates them there at stream start.
     */
    private fun handleMaps(method: String, call: io.flutter.plugin.common.MethodCall, result: MethodChannel.Result) {
        when (method) {
            "mapsDir" -> result.success(downloader.mapsDir().absolutePath)

            "hasRoomFor" -> {
                val bytes = call.argument<Number>("bytes")?.toLong()
                if (bytes == null) result.error("NO_BYTES", "Missing 'bytes'", null)
                else result.success(downloader.hasRoomFor(bytes))
            }

            "start" -> {
                val code = call.argument<String>("code")
                val url = call.argument<String>("url")
                val sha256 = call.argument<String>("sha256")
                if (code == null || url == null || sha256 == null) {
                    result.error("BAD_ARGS", "Missing code/url/sha256", null)
                    return
                }
                try {
                    val id = downloader.start(
                        code = code,
                        url = url,
                        sha256 = sha256,
                        sizeBytes = call.argument<Number>("sizeBytes")?.toLong() ?: 0L,
                        generatedAt = call.argument<String>("generatedAt") ?: "",
                        title = call.argument<String>("title") ?: code,
                    )
                    result.success(id)
                } catch (e: Exception) {
                    // The system downloader can be disabled by the user, and then
                    // enqueue throws. That's a state to show, not a crash.
                    result.error("ENQUEUE_FAILED", e.message, null)
                }
            }

            "cancel" -> {
                val code = call.argument<String>("code")
                if (code == null) result.error("NO_CODE", "Missing 'code'", null)
                else {
                    downloader.cancel(code)
                    result.success(null)
                }
            }

            "delete" -> {
                val code = call.argument<String>("code")
                if (code == null) result.error("NO_CODE", "Missing 'code'", null)
                else result.success(downloader.delete(code))
            }

            "progress" -> result.success(downloader.progress())

            "installedFiles" -> result.success(downloader.installedFiles())

            // Hashing hundreds of megabytes — off the main thread, answer posted back.
            "reconcile" -> mapsWorker.execute {
                val outcomes = try {
                    downloader.reconcile()
                } catch (e: Exception) {
                    runOnUiThread { result.error("RECONCILE_FAILED", e.message, null) }
                    return@execute
                }
                runOnUiThread { result.success(outcomes) }
            }

            else -> result.notImplemented()
        }
    }

    // Below API 26 this permission doesn't exist and installs from any source are
    // allowed unconditionally — moot here anyway since the app's minSdk is 26.
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun installApk(path: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}
