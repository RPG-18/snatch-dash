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
            "mapsDir" -> onMapsWorker(result, "MAPS_DIR_FAILED") { downloader.mapsDir().absolutePath }

            "hasRoomFor" -> {
                val bytes = call.argument<Number>("bytes")?.toLong()
                if (bytes == null) result.error("NO_BYTES", "Missing 'bytes'", null)
                else onMapsWorker(result, "ROOM_CHECK_FAILED") { downloader.hasRoomFor(bytes) }
            }

            "start" -> {
                val code = call.argument<String>("code")
                val url = call.argument<String>("url")
                val sha256 = call.argument<String>("sha256")
                if (code == null || url == null || sha256 == null) {
                    result.error("BAD_ARGS", "Missing code/url/sha256", null)
                    return
                }
                // Arguments off the MethodCall here, on the platform thread; only the
                // downloader call itself is handed to the worker.
                val sizeBytes = call.argument<Number>("sizeBytes")?.toLong() ?: 0L
                val generatedAt = call.argument<String>("generatedAt") ?: ""
                val title = call.argument<String>("title") ?: code
                // The system downloader can be disabled by the user, and then enqueue
                // throws — that's a state to show, not a crash.
                onMapsWorker(result, "ENQUEUE_FAILED") {
                    downloader.start(code, url, sha256, sizeBytes, generatedAt, title)
                }
            }

            "cancel" -> {
                val code = call.argument<String>("code")
                if (code == null) result.error("NO_CODE", "Missing 'code'", null)
                else onMapsWorker(result, "CANCEL_FAILED") { downloader.cancel(code); null }
            }

            "delete" -> {
                val code = call.argument<String>("code")
                if (code == null) result.error("NO_CODE", "Missing 'code'", null)
                else onMapsWorker(result, "DELETE_FAILED") { downloader.delete(code) }
            }

            "progress" -> onMapsWorker(result, "PROGRESS_FAILED") { downloader.progress() }

            "installedFiles" -> onMapsWorker(result, "LIST_FAILED") { downloader.installedFiles() }

            "reconcile" -> onMapsWorker(result, "RECONCILE_FAILED") { downloader.reconcile() }

            else -> result.notImplemented()
        }
    }

    /**
     * Runs one downloader call on [mapsWorker] and answers the channel from the
     * main thread.
     *
     * **Every** method goes through here, not just the slow one. `reconcile()`
     * hashes for seconds and then renames; `start`/`cancel` rewrite the same
     * pending prefs and touch the same `.part` files. With only `reconcile` on the
     * worker those two ran concurrently on unsynchronised state, and the losing
     * interleaving installs a **truncated pack under its final name**: the worker
     * finishes hashing the bytes of a download the rider has meanwhile restarted,
     * the hash matches the *old* expectation, and `Files.move` renames the new,
     * still-being-written `.part` into `<code>.pmtiles` — which the render engine
     * then enumerates and draws. The mirror image is just as bad: the worker reads
     * the expectation *after* it was overwritten, calls it a checksum mismatch and
     * deletes the file a live download is writing into.
     *
     * One single-threaded executor makes those sequences impossible by
     * construction, which no amount of per-method locking would do as clearly. It
     * also takes the disk and binder traffic off the platform thread, which is
     * where `progress()` (a DownloadManager query per pending pack, polled every
     * 700 ms) used to run.
     */
    private fun onMapsWorker(result: MethodChannel.Result, errorCode: String, work: () -> Any?) {
        mapsWorker.execute {
            val value = try {
                work()
            } catch (e: Exception) {
                runOnUiThread { result.error(errorCode, e.message, null) }
                return@execute
            }
            runOnUiThread { result.success(value) }
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
