import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../l10n/app_localizations.dart';
import '../state/app_update_controller.dart';
import '../state/auto_update_settings.dart';
import '../state/currency_settings.dart';
import '../state/dash_engine_state.dart';
import '../state/dash_wallpaper_store.dart';
import '../state/update_channel_settings.dart';
import '../util/app_logger.dart';
import '../util/github_release.dart';

/// "More" tab: dash connection/pairing, idle wallpaper, and currency. Ports
/// `SettingsScreen.kt`'s connection section (`DashConfig` via the native
/// engine's `getConfig`/`setSsid`/`setWifiPassword`/`forgetDash`) and the
/// wallpaper gallery (`DashWallpaperStore`). No account/sign-in section —
/// Firebase auth was dropped from this port. Voice guidance lives on the
/// Route tab (matches the original's per-trip toggle placement); media/call
/// notification access has a status tile here that deep-links to system
/// settings, since Android grants it outside the app's own permission flow.
class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> with WidgetsBindingObserver {
  Map<String, dynamic>? _config;
  bool _notificationAccessGranted = false;

  // `_loadConfig` is called from `initState` and from three separate onTap
  // handlers below (forget/ssid/password) — without this guard, whichever
  // call happens to resolve last wins, which can flash a stale config back
  // over a fresher one if two of them race.
  int _configGeneration = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadConfig();
    _loadNotificationAccess();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The user grants notification access in system settings, outside the
    // app; re-check when they come back so the status stays current.
    if (state == AppLifecycleState.resumed) {
      _loadNotificationAccess();
      // Same story for "install unknown apps": granted in system settings,
      // outside the app. There's no install-time callback, so re-drive the
      // flow on return — it'll just re-check and proceed if now granted.
      if (ref.read(appUpdateControllerProvider).status == AppUpdateStatus.needsInstallPermission) {
        ref.read(appUpdateControllerProvider.notifier).downloadAndInstall();
      }
    }
  }

  Future<void> _loadConfig() async {
    final generation = ++_configGeneration;
    final config = await DashEngine.instance.getConfig();
    if (!mounted) return;
    if (generation != _configGeneration) return; // superseded by a newer load
    setState(() => _config = config);
  }

  Future<void> _loadNotificationAccess() async {
    final granted = await DashEngine.instance.isNotificationAccessGranted();
    if (mounted) setState(() => _notificationAccessGranted = granted);
  }

  @override
  Widget build(BuildContext context) {
    final engine = ref.watch(dashEngineStateProvider);
    final currency = ref.watch(currencySettingsProvider);
    final l10n = AppLocalizations.of(context)!;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.settingsTitle)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(l10n.settingsDashConnection, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          Card(
            child: Column(children: [
              ListTile(
                leading: const Icon(Icons.wifi),
                title: Text(_config?['ssid']?.toString().isNotEmpty == true
                    ? _config!['ssid'] as String
                    : l10n.settingsNotPaired(_config?['ssidPrefix']?.toString() ?? 'RE_')),
                subtitle: Text(l10n.settingsStageWifi(engine.stage.name, engine.wifiStatus ?? '—')),
              ),
              const Divider(height: 1),
              ListTile(
                leading: const Icon(Icons.edit_outlined),
                title: Text(l10n.settingsSetExactSsid),
                onTap: () => _showSsidDialog(context, l10n),
              ),
              const Divider(height: 1),
              ListTile(
                leading: const Icon(Icons.password_outlined),
                title: Text(l10n.settingsSetWifiPassword),
                subtitle: Text(l10n.settingsDefaultPasswordSub),
                onTap: () => _showPasswordDialog(context, l10n),
              ),
              const Divider(height: 1),
              ListTile(
                leading: const Icon(Icons.link_off),
                title: Text(l10n.settingsForgetDash),
                subtitle: Text(l10n.settingsForgetDashSub),
                onTap: () async {
                  await DashEngine.instance.forgetDash();
                  await _loadConfig();
                },
              ),
            ]),
          ),
          const SizedBox(height: 16),
          Text(l10n.settingsDashWallpaper, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          const _WallpaperGallery(),
          const SizedBox(height: 16),
          Text(l10n.settingsCurrency, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          Card(
            child: RadioGroup<OpenDashCurrency>(
              groupValue: currency,
              onChanged: (v) {
                if (v != null) ref.read(currencySettingsProvider.notifier).select(v);
              },
              child: Column(
                children: [
                  for (final c in OpenDashCurrency.values)
                    RadioListTile<OpenDashCurrency>(
                      value: c,
                      title: Text('${currencyDisplayName(l10n, c)} (${c.symbol})'),
                    ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: ListTile(
              leading: Icon(_notificationAccessGranted ? Icons.notifications_active_outlined : Icons.notifications_off_outlined),
              title: Text(l10n.settingsMediaAccessTitle),
              subtitle: Text(_notificationAccessGranted
                  ? l10n.settingsMediaAccessGranted
                  : l10n.settingsMediaAccessNotGranted),
              trailing: _notificationAccessGranted ? const Icon(Icons.check_circle_outline) : null,
              onTap: _notificationAccessGranted ? null : () => DashEngine.instance.openNotificationAccessSettings(),
            ),
          ),
          const SizedBox(height: 16),
          Card(
            child: ListTile(
              leading: const Icon(Icons.bug_report_outlined),
              title: Text(l10n.settingsLogsTitle),
              subtitle: Text(l10n.settingsLogsSubtitle),
              onTap: () => context.push('/more/logs'),
              trailing: IconButton(
                icon: const Icon(Icons.ios_share),
                tooltip: l10n.settingsLogsShareFile,
                onPressed: _shareLogFile,
              ),
            ),
          ),
          const SizedBox(height: 16),
          Text(l10n.settingsUpdatesTitle, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          const _UpdatesCard(),
          const SizedBox(height: 16),
          Card(
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.info_outline),
                  title: Text(l10n.settingsAboutTitle),
                  subtitle: Text(l10n.settingsAboutSubtitle),
                ),
                ListTile(
                  dense: true,
                  // Empty leading matches the Icon above's footprint, so this
                  // title lines up with the title/subtitle text instead of
                  // starting flush with the card edge.
                  leading: const SizedBox(width: 24, height: 24),
                  title: Text(
                    l10n.settingsAboutYandexTermsLink,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.primary,
                      decoration: TextDecoration.underline,
                    ),
                  ),
                  onTap: () => launchUrl(
                    Uri.parse('https://yandex.ru/legal/maps_api'),
                    mode: LaunchMode.externalApplication,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _shareLogFile() async {
    final files = await persistedLogFiles();
    if (files.isEmpty) return;
    await SharePlus.instance.share(
      ShareParams(files: files.map((f) => XFile(f.path)).toList(), subject: 'SnatchDash logs'),
    );
  }

  void _showSsidDialog(BuildContext context, AppLocalizations l10n) {
    final controller = TextEditingController(text: _config?['ssid'] as String? ?? '');
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.settingsSsidDialogTitle),
        content: TextField(controller: controller, decoration: InputDecoration(labelText: l10n.settingsExactSsidLabel)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogContext), child: Text(l10n.actionCancel)),
          TextButton(
            onPressed: () async {
              await DashEngine.instance.setSsid(controller.text.trim());
              if (!dialogContext.mounted) return;
              Navigator.pop(dialogContext);
              await _loadConfig();
            },
            child: Text(l10n.actionSave),
          ),
        ],
      ),
    );
  }

  void _showPasswordDialog(BuildContext context, AppLocalizations l10n) {
    final controller = TextEditingController(text: _config?['password'] as String? ?? '');
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.settingsWifiPasswordDialogTitle),
        content: TextField(controller: controller, decoration: InputDecoration(labelText: l10n.settingsPasswordLabel)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogContext), child: Text(l10n.actionCancel)),
          TextButton(
            onPressed: () async {
              await DashEngine.instance.setWifiPassword(controller.text);
              if (!dialogContext.mounted) return;
              Navigator.pop(dialogContext);
              await _loadConfig();
            },
            child: Text(l10n.actionSave),
          ),
        ],
      ),
    );
  }
}

/// Up to 5 wallpaper slots shown on the dash while idle (no active
/// destination). Tap a thumbnail to make it active, long-press to remove it.
/// Cropping is centered by default — the original's drag-to-reposition bias
/// editor isn't ported yet; `updateCurrentOptions` on the store already
/// supports it for whenever that lands.
class _WallpaperGallery extends ConsumerWidget {
  const _WallpaperGallery();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final infos = ref.watch(dashWallpaperStoreProvider);
    final notifier = ref.read(dashWallpaperStoreProvider.notifier);
    final l10n = AppLocalizations.of(context)!;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              height: 72,
              child: ListView(
                scrollDirection: Axis.horizontal,
                children: [
                  for (final info in infos)
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: GestureDetector(
                        onTap: () => notifier.selectSlot(info.slot),
                        onLongPress: () => notifier.clearSlot(info.slot),
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(8),
                          child: info.kind.name == 'image'
                              ? Image.file(File(info.path), width: 96, height: 72, fit: BoxFit.cover)
                              : Container(
                                  width: 96,
                                  height: 72,
                                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                                  alignment: Alignment.center,
                                  child: const Icon(Icons.gif_box_outlined),
                                ),
                        ),
                      ),
                    ),
                  GestureDetector(
                    onTap: () => _pick(notifier),
                    child: Container(
                      width: 96,
                      height: 72,
                      decoration: BoxDecoration(
                        border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      alignment: Alignment.center,
                      child: const Icon(Icons.add_photo_alternate_outlined),
                    ),
                  ),
                ],
              ),
            ),
            if (infos.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: TextButton.icon(
                  onPressed: notifier.clear,
                  icon: const Icon(Icons.delete_outline),
                  label: Text(l10n.settingsClearAll),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _pick(DashWallpaperStore notifier) async {
    final files = await ImagePicker().pickMultiImage(limit: 5);
    if (files.isNotEmpty) await notifier.saveManyFromXFiles(files);
  }
}

/// Self-update card: current version, stable/nightly channel toggle, and the
/// check → download → install flow driven by `AppUpdateController`. See
/// `github_release.dart` (what "newer" means per channel) and
/// `apk_installer.dart`/`MainActivity.kt` (how the downloaded APK actually
/// gets installed) for the rest of the pieces.
class _UpdatesCard extends ConsumerWidget {
  const _UpdatesCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    final packageInfo = ref.watch(packageInfoProvider);
    final autoUpdate = ref.watch(autoUpdateSettingsProvider);
    final channel = ref.watch(updateChannelSettingsProvider);
    final update = ref.watch(appUpdateControllerProvider);
    final notifier = ref.read(appUpdateControllerProvider.notifier);

    return Card(
      child: Column(
        children: [
          ListTile(
            leading: const Icon(Icons.system_update_outlined),
            title: packageInfo.when(
              data: (info) => Text(l10n.settingsUpdatesCurrentVersion(info.version)),
              loading: () => null,
              error: (_, _) => null,
            ),
          ),
          ListTile(
            // Empty leading matches the Icon above's footprint (same trick as
            // the About card below), so the button's left edge lines up with
            // "Текущая версия" instead of starting flush with the card edge.
            leading: const SizedBox(width: 24, height: 24),
            title: Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                // Strip the button's own padding/min-size so its label text
                // starts exactly where the title text above it does, rather
                // than a button-sized indent further in.
                style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: Size.zero, tapTargetSize: MaterialTapTargetSize.shrinkWrap),
                onPressed: update.status == AppUpdateStatus.checking ? null : notifier.checkForUpdate,
                child: Text(l10n.settingsUpdatesCheckButton),
              ),
            ),
            trailing: update.status == AppUpdateStatus.checking
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : null,
          ),
          const Divider(height: 1),
          SwitchListTile(
            secondary: const Icon(Icons.autorenew),
            title: Text(l10n.settingsUpdatesAutoUpdate),
            value: autoUpdate,
            onChanged: (enabled) => ref.read(autoUpdateSettingsProvider.notifier).setEnabled(enabled),
          ),
          // Channel switch + check status only matter once auto-update is on —
          // collapsed (and their state along with them) while it's off.
          AnimatedSize(
            duration: const Duration(milliseconds: 200),
            child: !autoUpdate
                ? const SizedBox(width: double.infinity)
                : Column(
                    children: [
                      const Divider(height: 1),
                      SwitchListTile(
                        secondary: const Icon(Icons.science_outlined),
                        title: Text(l10n.settingsUpdatesChannelNightly),
                        subtitle: Text(l10n.settingsUpdatesChannelNightlySub),
                        value: channel == UpdateChannel.nightly,
                        onChanged: (nightly) {
                          ref.read(updateChannelSettingsProvider.notifier).select(nightly ? UpdateChannel.nightly : UpdateChannel.stable);
                          // Old status/release referred to the previous channel — drop it
                          // rather than show it against the new one.
                          ref.invalidate(appUpdateControllerProvider);
                        },
                      ),
                      if (update.status != AppUpdateStatus.idle && update.status != AppUpdateStatus.checking) ...[
                        const Divider(height: 1),
                        _UpdateStatusTile(update: update, onDownload: notifier.downloadAndInstall, onGrantPermission: notifier.openInstallPermissionSettings),
                      ],
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}

class _UpdateStatusTile extends StatelessWidget {
  const _UpdateStatusTile({required this.update, required this.onDownload, required this.onGrantPermission});

  final AppUpdateState update;
  final VoidCallback onDownload;
  final VoidCallback onGrantPermission;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return switch (update.status) {
      AppUpdateStatus.upToDate => ListTile(
          leading: const Icon(Icons.check_circle_outline),
          title: Text(l10n.settingsUpdatesStatusUpToDate),
        ),
      AppUpdateStatus.available => ListTile(
          leading: const Icon(Icons.new_releases_outlined),
          title: Text(l10n.settingsUpdatesStatusAvailable(update.release!.name)),
          trailing: FilledButton(onPressed: onDownload, child: Text(l10n.settingsUpdatesDownloadButton)),
        ),
      AppUpdateStatus.downloading => ListTile(
          leading: const SizedBox(
            width: 24,
            height: 24,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
          title: LinearProgressIndicator(value: update.downloadProgress),
          subtitle: Text(
            update.downloadProgress != null
                ? l10n.settingsUpdatesDownloading((update.downloadProgress! * 100).toStringAsFixed(0))
                : l10n.settingsUpdatesDownloadingIndeterminate,
          ),
        ),
      AppUpdateStatus.needsInstallPermission => ListTile(
          leading: const Icon(Icons.lock_outline),
          title: Text(l10n.settingsUpdatesNeedsPermission),
          trailing: FilledButton(onPressed: onGrantPermission, child: Text(l10n.settingsUpdatesGrantPermissionButton)),
        ),
      AppUpdateStatus.error => ListTile(
          leading: Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
          title: Text(l10n.settingsUpdatesStatusError(update.errorMessage ?? '')),
        ),
      AppUpdateStatus.idle || AppUpdateStatus.checking => const SizedBox.shrink(),
    };
  }
}
