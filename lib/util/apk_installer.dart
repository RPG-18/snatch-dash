import 'package:flutter/services.dart';

/// Thin wrapper over `MainActivity.kt`'s `ru.snatchdash.app/updater` channel —
/// the native half of self-update (see `apk_downloader.dart` for the download
/// side, `state/app_update_controller.dart` for orchestration). A downloaded
/// APK can't be installed by handing Android a raw `file://` path (blocked by
/// `FileUriExposedException` since Android 7); the native side converts it to
/// a `content://` URI via `FileProvider` and starts the system installer.
class ApkInstaller {
  ApkInstaller._();

  static const _channel = MethodChannel('ru.snatchdash.app/updater');

  /// Whether this app is currently allowed to trigger package installs
  /// ("Install unknown apps" toggle in system settings, per-app since
  /// Android 8). False the first time, until the user grants it.
  static Future<bool> canInstallPackages() async =>
      (await _channel.invokeMethod<bool>('canInstallPackages')) ?? false;

  /// Deep-links to the system screen where the user grants "install unknown
  /// apps" for this app. There's no install-time callback — after granting,
  /// the caller has to re-check [canInstallPackages] (e.g. on next tap or
  /// `AppLifecycleState.resumed`).
  static Future<void> openInstallPermissionSettings() async =>
      _channel.invokeMethod<void>('openInstallPermissionSettings');

  /// Hands the downloaded APK at [path] to the system package installer.
  /// If [canInstallPackages] is false, Android shows its own blocking
  /// "for your security" screen instead of the installer — check first.
  static Future<void> installApk(String path) async =>
      _channel.invokeMethod<void>('installApk', {'path': path});
}
