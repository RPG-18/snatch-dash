import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../util/apk_downloader.dart';
import '../util/apk_installer.dart';
import '../util/github_release.dart';
import 'auto_update_settings.dart';
import 'update_channel_settings.dart';

enum AppUpdateStatus { idle, checking, upToDate, available, downloading, needsInstallPermission, error }

class AppUpdateState {
  const AppUpdateState({
    this.status = AppUpdateStatus.idle,
    this.release,
    this.downloadProgress,
    this.errorMessage,
    this.promptOnLaunch = false,
  });

  final AppUpdateStatus status;
  final AppRelease? release;

  /// 0..1 while [status] is `downloading`; null before the server's sent a
  /// `Content-Length` (indeterminate).
  final double? downloadProgress;
  final String? errorMessage;

  /// Set by [AppUpdateController.checkOnLaunch] when a fresh, not-yet-seen
  /// update turns up so `AppShell` can pop the one-shot dialog; consumed via
  /// [AppUpdateController.acknowledgePrompt] right after.
  final bool promptOnLaunch;

  AppUpdateState copyWith({
    AppUpdateStatus? status,
    AppRelease? release,
    double? downloadProgress,
    String? errorMessage,
    bool? promptOnLaunch,
  }) =>
      AppUpdateState(
        status: status ?? this.status,
        release: release ?? this.release,
        downloadProgress: downloadProgress,
        errorMessage: errorMessage,
        promptOnLaunch: promptOnLaunch ?? this.promptOnLaunch,
      );
}

const _prefsKeyInstalledNightlyTag = 'installed_nightly_tag';

/// Orchestrates the self-update flow end to end: check GitHub Releases (via
/// [updateChannelSettingsProvider]'s channel) → compare against the running
/// build → download → hand off to [ApkInstaller]. See `github_release.dart`
/// and `apk_downloader.dart` for the pieces this wires together, and
/// `settings_screen.dart` / `app_shell.dart` for where it's driven from.
class AppUpdateController extends Notifier<AppUpdateState> {
  /// Guards [downloadAndInstall] against being entered twice.
  ///
  /// The status only becomes `downloading` — which is what swaps the button out
  /// of the UI — *after* the `canInstallPackages()` channel round trip, so until
  /// then the button is still live. And there are three call sites: the Settings
  /// button, the launch dialog (`app_shell.dart`) and the resume re-drive after
  /// granting install permission. Two overlapping runs share one destination
  /// directory, and the loser hands a half-written APK to the system installer.
  bool _installing = false;

  @override
  AppUpdateState build() => const AppUpdateState();

  /// Manual "Проверить обновления" — always reflects the result in [state],
  /// including errors, since the user explicitly asked.
  Future<void> checkForUpdate() => _check(silent: false);

  /// Called once from `AppShell` after the first frame — a no-op unless
  /// "Автоматическое обновление" is on. Reads that flag straight from
  /// `SharedPreferences` rather than via [autoUpdateSettingsProvider]:
  /// this runs right after the first frame, before that provider's own
  /// `build()` → `_load()` round-trip has necessarily settled, so its
  /// `state` could still be the default. A network error just leaves
  /// [state] at `idle` instead of surfacing — nobody asked for this one, so
  /// it shouldn't nag on a bad connection. Sets [AppUpdateState.promptOnLaunch]
  /// when there's a genuinely new build to show a one-time dialog for.
  Future<void> checkOnLaunch() async {
    final prefs = await SharedPreferences.getInstance();
    if (!ref.mounted) return;
    if (!(prefs.getBool(prefsKeyAutoUpdateEnabled) ?? true)) return;
    await _check(silent: true);
  }

  Future<void> _check({required bool silent}) async {
    state = state.copyWith(status: AppUpdateStatus.checking);
    final channel = ref.read(updateChannelSettingsProvider);
    try {
      final release = await GitHubReleases.latest(channel);
      if (!ref.mounted) return;
      if (release == null) {
        state = AppUpdateState(status: AppUpdateStatus.upToDate);
        return;
      }
      final isNewer = switch (channel) {
        UpdateChannel.stable => isStableReleaseNewer(release, (await PackageInfo.fromPlatform()).version),
        UpdateChannel.nightly => isNightlyReleaseNewer(release, await _installedNightlyTag()),
      };
      if (!ref.mounted) return;
      state = isNewer
          ? AppUpdateState(status: AppUpdateStatus.available, release: release, promptOnLaunch: silent)
          : const AppUpdateState(status: AppUpdateStatus.upToDate);
    } catch (e) {
      if (!ref.mounted) return;
      state = silent ? const AppUpdateState() : AppUpdateState(status: AppUpdateStatus.error, errorMessage: '$e');
    }
  }

  /// Consumed right after `AppShell` shows the launch-prompt dialog, so
  /// re-navigating tabs within the same session doesn't pop it again.
  void acknowledgePrompt() {
    if (state.promptOnLaunch) state = state.copyWith(promptOnLaunch: false);
  }

  /// Downloads the checked release and immediately hands it to the system
  /// installer. If "install unknown apps" isn't granted yet, stops at
  /// [AppUpdateStatus.needsInstallPermission] instead — call this again
  /// after the user grants it (there's no install-time callback to resume
  /// on automatically).
  Future<void> downloadAndInstall() async {
    final release = state.release;
    if (release == null) return;
    if (_installing) return; // see the field
    _installing = true;

    try {
      if (!await ApkInstaller.canInstallPackages()) {
        state = state.copyWith(status: AppUpdateStatus.needsInstallPermission);
        return;
      }

      state = state.copyWith(status: AppUpdateStatus.downloading, downloadProgress: 0);
      try {
        final file = await downloadApk(
          release.apkUrl,
          release.apkName,
          onProgress: (received, total) {
            if (!ref.mounted) return;
            state = state.copyWith(downloadProgress: total != null ? received / total : null);
          },
        );
        if (!ref.mounted) return;
        if (ref.read(updateChannelSettingsProvider) == UpdateChannel.nightly) {
          await _setInstalledNightlyTag(release.tag);
        }
        await ApkInstaller.installApk(file.path);
        if (!ref.mounted) return;
        state = state.copyWith(status: AppUpdateStatus.available); // installer took over; back to "available" if cancelled
      } catch (e) {
        if (!ref.mounted) return;
        state = AppUpdateState(status: AppUpdateStatus.error, release: release, errorMessage: '$e');
      }
    } finally {
      _installing = false;
    }
  }

  /// Opens the system "install unknown apps" settings screen for this app —
  /// call [downloadAndInstall] again once the user's back and granted it.
  Future<void> openInstallPermissionSettings() => ApkInstaller.openInstallPermissionSettings();

  Future<String?> _installedNightlyTag() async =>
      (await SharedPreferences.getInstance()).getString(_prefsKeyInstalledNightlyTag);

  Future<void> _setInstalledNightlyTag(String tag) async =>
      (await SharedPreferences.getInstance()).setString(_prefsKeyInstalledNightlyTag, tag);
}

final appUpdateControllerProvider = NotifierProvider<AppUpdateController, AppUpdateState>(AppUpdateController.new);

/// The running build's own version — read once per app session (Settings
/// screen's "Текущая версия" line, and `checkForUpdate`'s stable-channel
/// comparison).
final packageInfoProvider = FutureProvider<PackageInfo>((ref) => PackageInfo.fromPlatform());
