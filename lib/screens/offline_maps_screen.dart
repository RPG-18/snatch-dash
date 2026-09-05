import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/map_pack_downloader.dart' show PackProgress;
import '../data/map_region_names.dart';
import '../l10n/app_localizations.dart';
import '../models/offline_map.dart';
import '../state/dash_engine_state.dart';
import '../state/offline_maps_controller.dart';
import '../util/byte_size.dart';

/// `/more/offline-maps` — download, update and delete map packs.
///
/// The screen has to keep three independent things apart: what is installed
/// (disk), what is available (the local `index.json`) and whether the server
/// answered. "No network" and "nothing downloaded" are different states with
/// different actions, and conflating them is the mistake this layout is shaped
/// to avoid — see spec/offline_maps_screen.md.
class OfflineMapsScreen extends ConsumerWidget {
  const OfflineMapsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    final state = ref.watch(offlineMapsControllerProvider);
    final installed = ref.watch(installedPacksProvider) ?? const <InstalledPack>[];
    final navigating = ref.watch(dashEngineStateProvider).navigating;

    // Selects the nonce too, so the same failure twice in a row still fires.
    ref.listen(offlineMapsControllerProvider.select((s) => (s.lastError, s.errorNonce)), (_, next) {
      final error = next.$1;
      if (error == null) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(_errorText(l10n, error))));
      ref.read(offlineMapsControllerProvider.notifier).errorShown();
    });

    // ref.listen only fires on changes *after* it subscribes, and the poller
    // deliberately outlives this screen: a download that failed while the rider
    // was elsewhere left its error sitting in the state, shown to nobody and
    // never cleared. Post-frame because a snackbar cannot be shown during build.
    final pending = state.lastError;
    if (pending != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!context.mounted) return;
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(_errorText(l10n, pending))));
        ref.read(offlineMapsControllerProvider.notifier).errorShown();
      });
    }

    return Scaffold(
      appBar: AppBar(title: Text(l10n.offlineMapsTitle)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (state.status == ManifestStatus.appTooOld)
            _Banner(icon: Icons.system_update, text: l10n.offlineMapsAppTooOld)
          else if (state.status == ManifestStatus.unavailable)
            _ServerUnavailable(onRetry: () => ref.read(offlineMapsControllerProvider.notifier).refresh())
          else ...[
            if (state.status == ManifestStatus.staleCache)
              _Banner(icon: Icons.cloud_off, text: l10n.offlineMapsStaleCache),
            // The search field stays disabled until the manifest lands. Active,
            // it opened a picker over an empty region list and answered
            // «Ничего не найдено» to everything — a lie the rider has no way to
            // tell from a genuinely missing region on a slow connection.
            if (state.status == ManifestStatus.loading)
              const _ManifestLoading()
            else
              _SearchField(
                // rootNavigator: the picker is a full-screen popup by spec, and
                // the branch navigator of the StatefulShellRoute would leave the
                // bottom bar on top of it — five tabs offering to leave a screen
                // whose whole job is one choice.
                onTap: () => Navigator.of(context, rootNavigator: true).push(
                  MaterialPageRoute<void>(builder: (_) => const _PackPickerScreen()),
                ),
              ),
          ],
          const SizedBox(height: 16),
          Text(l10n.offlineMapsDownloadedSection, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 8),
          if (installed.isEmpty)
            Card(
              child: ListTile(
                leading: const Icon(Icons.map_outlined),
                title: Text(l10n.offlineMapsNothingDownloaded),
                // The "why" line matters: without it the disabled navigation on
                // Home looks like a broken app rather than a missing download.
                subtitle: Text(l10n.offlineMapsNothingDownloadedSub),
              ),
            )
          else
            Card(
              child: Column(
                children: [
                  for (final pack in installed)
                    _InstalledTile(pack: pack, navigating: navigating),
                ],
              ),
            ),
        ],
      ),
    );
  }

  static String _errorText(AppLocalizations l10n, String code) => switch (code) {
        'noSpace' => l10n.offlineMapsNoSpace,
        'enqueueFailed' => l10n.offlineMapsEnqueueFailed,
        // Its own line rather than the generic failure: this one will not fix
        // itself by trying again, and the rider needs to know not to burn
        // another few hundred megabytes on it.
        'packCorrupt' => l10n.offlineMapsPackCorrupt,
        'deleteFailed' => l10n.offlineMapsDeleteFailed,
        _ => l10n.offlineMapsDownloadFailed,
      };
}

/// Stand-in for the search field while the manifest is still on its way.
///
/// Shaped like the field it replaces so the layout does not jump when the list
/// of regions arrives.
class _ManifestLoading extends StatelessWidget {
  const _ManifestLoading();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return InputDecorator(
      decoration: InputDecoration(
        prefixIcon: const Padding(
          padding: EdgeInsets.all(12),
          child: SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
        border: const OutlineInputBorder(),
      ),
      child: Text(
        l10n.offlineMapsLoadingRegions,
        style: TextStyle(color: Theme.of(context).hintColor),
      ),
    );
  }
}

class _SearchField extends StatelessWidget {
  const _SearchField({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return TextField(
      readOnly: true,
      onTap: onTap,
      decoration: InputDecoration(
        prefixIcon: const Icon(Icons.search),
        hintText: l10n.offlineMapsSearchHint,
        border: const OutlineInputBorder(),
      ),
    );
  }
}

class _Banner extends StatelessWidget {
  const _Banner({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Card(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        child: ListTile(leading: Icon(icon), title: Text(text)),
      );
}

/// No local manifest and no server — the only state where the list of
/// available packs cannot be shown at all. Installed packs stay visible below:
/// they live on disk and do not depend on the network.
class _ServerUnavailable extends StatelessWidget {
  const _ServerUnavailable({required this.onRetry});
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              const Icon(Icons.cloud_off),
              const SizedBox(width: 12),
              Expanded(child: Text(l10n.offlineMapsServerUnavailable)),
            ]),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(onPressed: onRetry, child: Text(l10n.offlineMapsRetry)),
            ),
          ],
        ),
      ),
    );
  }
}

class _InstalledTile extends ConsumerWidget {
  const _InstalledTile({required this.pack, required this.navigating});

  final InstalledPack pack;
  final bool navigating;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    final name = mapRegionName(pack.code, Localizations.localeOf(context).languageCode);
    // `select` on the state, not a read through the notifier: this is what makes
    // the icon flip when the manifest lands rather than by luck of the parent
    // happening to rebuild.
    final hasUpdate = ref.watch(offlineMapsControllerProvider.select((s) => s.hasUpdateFor(pack)));
    final progress = ref.watch(offlineMapsControllerProvider.select((s) => s.progress[pack.code]));
    final updating = progress != null;

    return ListTile(
      // The icon carries the state: a map when current, a download arrow when
      // the server has a newer build, a stop button while that update runs.
      leading: updating
          ? _StopWithProgress(fraction: progress.fraction)
          : Icon(hasUpdate ? Icons.download_outlined : Icons.map_outlined),
      title: Text(name),
      subtitle: Text(hasUpdate && !updating
          ? '${formatByteSize(l10n, pack.sizeBytes)} · ${l10n.offlineMapsUpdateAvailable}'
          : formatByteSize(l10n, pack.sizeBytes)),
      // Tapping an out-of-date pack re-downloads it. Without this the download
      // icon above would promise something nothing delivers — the picker hides
      // installed packs, so there would be no way to update at all.
      onTap: updating
          ? () => _confirmCancel(context, ref, l10n)
          : (hasUpdate ? () => _startUpdate(context, ref, l10n, name) : null),
      // No delete while an update is downloading: it would remove the file and
      // the registry row without stopping the transfer, and the next harvest
      // would put the pack straight back — the "deleted" pack reappearing on
      // its own. The invariant is enforced in the controller too; this only
      // keeps the interface from offering it.
      trailing: updating
          ? null
          : PopupMenuButton<String>(
              onSelected: (_) => _confirmDelete(context, ref, name),
              itemBuilder: (context) => [
                PopupMenuItem(value: 'delete', child: Text(l10n.offlineMapsDeleteAction)),
              ],
            ),
    );
  }

  Future<void> _startUpdate(
      BuildContext context, WidgetRef ref, AppLocalizations l10n, String name) async {
    // Same reason as deletion: installing a pack rebuilds the whole style.
    if (navigating) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(l10n.offlineMapsExitNavigationFirst)));
      return;
    }
    final region = ref.read(offlineMapsControllerProvider).regionFor(pack.code);
    if (region == null) return; // dropped from the corpus since the last refresh
    await ref.read(offlineMapsControllerProvider.notifier).download(region, name);
  }

  Future<void> _confirmCancel(BuildContext context, WidgetRef ref, AppLocalizations l10n) async {
    // Held before the await: while the dialog is open the poller can install the
    // pack, the list rebuilds and this tile is unmounted — and `ref.read` on a
    // defunct WidgetRef throws a StateError nobody catches. The provider is not
    // autoDispose, so a reference taken now stays valid either way.
    final controller = ref.read(offlineMapsControllerProvider.notifier);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.offlineMapsCancelTitle),
        // "Yes"/"No", not "Cancel"/"Delete": in a dialog whose own title is
        // «Отменить загрузку», a button reading «Отмена» is ambiguous about
        // which thing it cancels, and «Удалить» names an action this dialog
        // does not perform.
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false), child: Text(l10n.actionNo)),
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, true), child: Text(l10n.actionYes)),
        ],
      ),
    );
    if (confirmed ?? false) await controller.cancel(pack.code);
  }

  Future<void> _confirmDelete(BuildContext context, WidgetRef ref, String name) async {
    final l10n = AppLocalizations.of(context)!;
    // Changing the set of packs rebuilds the whole style, which is a visible
    // break in the frame — not something to do mid-ride.
    if (navigating) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(l10n.offlineMapsExitNavigationFirst)));
      return;
    }
    final controller = ref.read(offlineMapsControllerProvider.notifier); // see _confirmCancel
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.offlineMapsDeleteTitle(name)),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false), child: Text(l10n.actionCancel)),
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, true), child: Text(l10n.actionDelete)),
        ],
      ),
    );
    if (confirmed ?? false) await controller.delete(pack.code);
  }
}

/// Full-screen picker: type to filter, tap to download.
///
/// Separate route rather than an inline list because the corpus is 80 entries —
/// scrolling that under the installed section would bury it.
class _PackPickerScreen extends ConsumerStatefulWidget {
  const _PackPickerScreen();

  @override
  ConsumerState<_PackPickerScreen> createState() => _PackPickerScreenState();
}

class _PackPickerScreenState extends ConsumerState<_PackPickerScreen> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final language = Localizations.localeOf(context).languageCode;
    final state = ref.watch(offlineMapsControllerProvider);
    final installed = {
      for (final p in ref.watch(installedPacksProvider) ?? const <InstalledPack>[]) p.code,
    };
    final navigating = ref.watch(dashEngineStateProvider).navigating;

    final matches = state.regions
        .where((r) => !installed.contains(r.code))
        .map((r) => (region: r, name: mapRegionName(r.code, language)))
        .where((e) => _matches(e.name, e.region.code, _query))
        .toList()
      ..sort((a, b) => a.name.compareTo(b.name));

    return Scaffold(
      appBar: AppBar(
        title: TextField(
          autofocus: true,
          decoration: InputDecoration(
            hintText: l10n.offlineMapsSearchHint,
            border: InputBorder.none,
          ),
          onChanged: (value) => setState(() => _query = value),
        ),
      ),
      body: matches.isEmpty
          ? Center(child: Text(l10n.offlineMapsNoResults))
          : ListView.builder(
              itemCount: matches.length,
              itemBuilder: (context, index) {
                final entry = matches[index];
                return _AvailableTile(
                  region: entry.region,
                  name: entry.name,
                  progress: state.progress[entry.region.code],
                  navigating: navigating,
                );
              },
            ),
    );
  }

  /// Matches the localised name and the raw code — the code is what a pack
  /// without a translation shows, and it must stay findable.
  static bool _matches(String name, String code, String query) {
    if (query.isEmpty) return true;
    final needle = query.trim().toLowerCase();
    return name.toLowerCase().contains(needle) || code.toLowerCase().contains(needle);
  }
}

class _AvailableTile extends ConsumerWidget {
  const _AvailableTile({
    required this.region,
    required this.name,
    required this.progress,
    required this.navigating,
  });

  final MapRegion region;
  final String name;
  final PackProgress? progress;
  final bool navigating;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context)!;
    final downloading = progress != null;

    return ListTile(
      leading: downloading
          ? _StopWithProgress(fraction: progress!.fraction)
          : const Icon(Icons.download_outlined),
      title: Text(name),
      subtitle: Text(formatByteSize(l10n, region.sizeBytes)),
      onTap: () => downloading ? _confirmCancel(context, ref, l10n) : _start(context, ref, l10n),
    );
  }

  Future<void> _start(BuildContext context, WidgetRef ref, AppLocalizations l10n) async {
    if (navigating) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(l10n.offlineMapsExitNavigationFirst)));
      return;
    }
    await ref.read(offlineMapsControllerProvider.notifier).download(region, name);
  }

  Future<void> _confirmCancel(BuildContext context, WidgetRef ref, AppLocalizations l10n) async {
    // Cancelling is allowed while navigating: an in-flight download hasn't
    // changed the active pack set yet, so stopping it breaks nothing.
    final controller = ref.read(offlineMapsControllerProvider.notifier); // see _InstalledTile
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.offlineMapsCancelTitle),
        // "Yes"/"No", not "Cancel"/"Delete": in a dialog whose own title is
        // «Отменить загрузку», a button reading «Отмена» is ambiguous about
        // which thing it cancels, and «Удалить» names an action this dialog
        // does not perform.
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false), child: Text(l10n.actionNo)),
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, true), child: Text(l10n.actionYes)),
        ],
      ),
    );
    if (confirmed ?? false) await controller.cancel(region.code);
  }
}

/// Stop icon inside a ring showing progress — indeterminate until the server's
/// `Content-Length` arrives, so the ring never lies about how far along we are.
class _StopWithProgress extends StatelessWidget {
  const _StopWithProgress({required this.fraction});
  final double? fraction;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: 40,
        height: 40,
        child: Stack(
          alignment: Alignment.center,
          children: [
            CircularProgressIndicator(value: fraction, strokeWidth: 2),
            const Icon(Icons.stop, size: 18),
          ],
        ),
      );
}
