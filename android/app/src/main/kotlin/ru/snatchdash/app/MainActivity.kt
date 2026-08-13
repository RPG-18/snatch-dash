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
