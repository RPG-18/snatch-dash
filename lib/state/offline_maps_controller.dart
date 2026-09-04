import 'dart:async';

import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/installed_packs_repository.dart';
import '../data/map_manifest_api.dart';
import '../data/map_pack_downloader.dart';
import '../models/offline_map.dart';
import '../util/app_logger.dart' show talker;

final mapManifestApiProvider = Provider<MapManifestApi>((ref) => MapManifestApi());

final installedPacksRepositoryProvider =
    Provider<InstalledPacksRepository>((ref) => SqliteInstalledPacksRepository());

final mapPackDownloaderProvider = Provider<MapPackDownloader>((ref) => MapPackDownloader());

/// Packs that passed verification and were renamed into place.
///
/// Read far beyond the offline-maps screen: Home gates navigation on it and
/// Dash shows a "no maps" chip from it, because without a pack the frame the
/// dash receives is an empty style background.
class InstalledPacks extends Notifier<List<InstalledPack>> {
  int _reloadGeneration = 0;

  @override
  List<InstalledPack> build() {
    Future.microtask(reload);
    return const [];
  }

  Future<void> reload() async {
    final generation = ++_reloadGeneration;
    final packs = await ref.read(installedPacksRepositoryProvider).list();
    if (!ref.mounted) return;
    if (generation != _reloadGeneration) return; // superseded by a newer reload
    state = packs;
  }

  Future<void> put(InstalledPack pack) async {
    await ref.read(installedPacksRepositoryProvider).put(pack);
    await reload();
  }

  Future<void> remove(String code) async {
    await ref.read(installedPacksRepositoryProvider).remove(code);
    await reload();
  }
}

final installedPacksProvider =
    NotifierProvider<InstalledPacks, List<InstalledPack>>(InstalledPacks.new);

/// The one flag the rest of the app asks about. Kept derived rather than
/// duplicated so it can never disagree with the registry.
final hasInstalledPacksProvider =
    Provider<bool>((ref) => ref.watch(installedPacksProvider).isNotEmpty);

/// Why the list of *available* packs looks the way it does. The three data
/// sources — registry, local `index.json`, network — fail independently, and
/// "no network" must not read as "nothing downloaded" (see
/// spec/offline_maps_screen.md, "Пустые состояния").
enum ManifestStatus {
  /// First read in flight.
  loading,

  /// Manifest came from the server just now.
  ready,

  /// Server didn't answer, but a local copy exists — the screen works from it,
  /// only the freshness check is missing.
  staleCache,

  /// No local copy and no server: "Сервер карт не доступен".
  unavailable,

  /// The manifest declares a schema this build can't read. Installed packs are
  /// left alone; the rider is told to update the app.
  appTooOld,
}

class OfflineMapsState {
  const OfflineMapsState({
    this.status = ManifestStatus.loading,
    this.manifest,
    this.progress = const {},
    this.lastError,
    this.errorNonce = 0,
  });

  final ManifestStatus status;
  final MapManifest? manifest;

  /// In-flight downloads by pack code.
  final Map<String, PackProgress> progress;

  /// Last thing worth showing in a snackbar. Cleared once shown.
  final String? lastError;

  /// Bumped on every error so that the *same* failure twice in a row still
  /// reaches the screen — a plain string compares equal and the listener would
  /// stay silent on the second one.
  final int errorNonce;

  List<MapRegion> get regions => manifest?.regions ?? const [];

  /// Whether the server carries a different build of [pack].
  ///
  /// Lives on the state rather than the notifier so a widget that needs it has
  /// an honest dependency: `select` on this rebuilds when the manifest lands.
  ///
  /// After a weekly corpus rebuild this is true for *everything* — the build
  /// timestamp goes into the tileset metadata, so every sha256 changes even
  /// when the OSM data didn't (spec/remote_map_server.md).
  bool hasUpdateFor(InstalledPack pack) {
    final region = regions.where((r) => r.code == pack.code).firstOrNull;
    return region != null && region.sha256 != pack.sha256;
  }

  MapRegion? regionFor(String code) => regions.where((r) => r.code == code).firstOrNull;

  OfflineMapsState copyWith({
    ManifestStatus? status,
    MapManifest? manifest,
    Map<String, PackProgress>? progress,
    String? lastError,
    int? errorNonce,
    bool clearError = false,
  }) =>
      OfflineMapsState(
        status: status ?? this.status,
        manifest: manifest ?? this.manifest,
        progress: progress ?? this.progress,
        lastError: clearError ? null : (lastError ?? this.lastError),
        errorNonce: errorNonce ?? this.errorNonce,
      );
}

/// Drives the offline-maps screen: manifest freshness, downloads, installs.
///
/// The transfer itself lives on the platform side ([MapPackDownloader]) so it
/// survives the app being killed; this class starts it, polls while the screen
/// is open, and turns finished downloads into registry rows.
class OfflineMapsController extends Notifier<OfflineMapsState> {
  Timer? _poller;

  /// Restart counters per pack, so a server that republishes in a loop can't
  /// make us download forever (spec/remote_map_server.md — "двух достаточно").
  final Map<String, int> _retries = {};

  /// Localised pack names by code, so a retry doesn't lose them.
  ///
  /// In-memory only: a conflict on a download started in a *previous* app
  /// session falls back to the raw code in the system notification. Persisting
  /// this would mean another store to keep in step for a cosmetic gain.
  final Map<String, String> _titles = {};

  /// Guards against overlapping ticks: `Timer.periodic` doesn't wait for an
  /// async callback, and one tick can spend seconds hashing a finished pack.
  bool _ticking = false;

  static const _maxRetries = 2;
  static const _pollInterval = Duration(milliseconds: 700);

  @override
  OfflineMapsState build() {
    ref.onDispose(() => _poller?.cancel());
    Future.microtask(() async {
      await refresh();
      await _dropRegistryRowsWithoutFiles();
      // Picks up whatever finished while the app was closed, and — crucially —
      // resumes watching anything still in flight. Without this the whole point
      // of using the system downloader is lost: a transfer that outlived the
      // app would never be noticed, let alone installed.
      await _tick();
    });
    return const OfflineMapsState();
  }

  /// Registry and disk are two sources of truth (the interface reads the first,
  /// the render engine the second). They can only drift one way — a file
  /// disappearing under us — and then the navigation gate would claim maps we
  /// cannot draw. Cheap to check, so check at startup.
  Future<void> _dropRegistryRowsWithoutFiles() async {
    final onDisk = await ref.read(mapPackDownloaderProvider).installedFiles();
    if (!ref.mounted) return;
    for (final pack in ref.read(installedPacksProvider)) {
      if (onDisk.containsKey(pack.code)) continue;
      talker.warning('[OfflineMaps] ${pack.code} is in the registry but not on disk — dropping');
      await ref.read(installedPacksProvider.notifier).remove(pack.code);
    }
  }

  /// Re-reads the manifest: server first, local copy as the fallback.
  Future<void> refresh() async {
    final api = ref.read(mapManifestApiProvider);
    try {
      final manifest = await api.fetchRemote();
      if (!ref.mounted) return;
      state = state.copyWith(status: ManifestStatus.ready, manifest: manifest, clearError: true);
      return;
    } on ManifestVersionUnsupported catch (e) {
      // Deliberately terminal: don't guess at an unknown schema, and don't
      // touch what's already installed.
      talker.warning('[OfflineMaps] $e');
      if (ref.mounted) state = state.copyWith(status: ManifestStatus.appTooOld);
      return;
    } catch (e) {
      talker.warning('[OfflineMaps] manifest fetch failed: $e');
    }

    try {
      final local = await api.readLocal();
      if (!ref.mounted) return;
      state = local == null
          ? state.copyWith(status: ManifestStatus.unavailable)
          : state.copyWith(status: ManifestStatus.staleCache, manifest: local);
    } on ManifestVersionUnsupported {
      if (ref.mounted) state = state.copyWith(status: ManifestStatus.appTooOld);
    }
  }

  /// Starts downloading [region]. [title] is the localised name — it becomes
  /// the system download notification's title.
  Future<void> download(MapRegion region, String title) async {
    final downloader = ref.read(mapPackDownloaderProvider);
    final api = ref.read(mapManifestApiProvider);

    if (!await downloader.hasRoomFor(region.sizeBytes)) {
      _reportError('noSpace');
      return;
    }

    try {
      await downloader.start(
        region,
        url: api.packUrl(region.path),
        generatedAt: state.manifest?.generatedAt ?? '',
        title: title,
      );
      // Remembered so an automatic retry keeps the localised name in the system
      // notification instead of falling back to the raw pack code.
      _titles[region.code] = title;
      _startPolling();
    } catch (e) {
      talker.error('[OfflineMaps] enqueue failed for ${region.code}', e);
      _reportError('enqueueFailed');
    }
  }

  Future<void> cancel(String code) async {
    await ref.read(mapPackDownloaderProvider).cancel(code);
    _retries.remove(code);
    if (!ref.mounted) return;
    state = state.copyWith(progress: {...state.progress}..remove(code));
  }

  /// Removes an installed pack — the file and its registry row.
  Future<void> delete(String code) async {
    await ref.read(mapPackDownloaderProvider).delete(code);
    await ref.read(installedPacksProvider.notifier).remove(code);
  }

  /// Whether the server has a different build of an installed pack.
  ///
  /// After a weekly corpus rebuild this is true for *everything* — the build
  /// timestamp lands in the tileset metadata, so every sha256 changes even
  /// when the OSM data didn't. The interface must not promise incremental
  /// updates (spec/remote_map_server.md, "Проверка обновлений").
  bool hasUpdate(String code) {
    final installed = ref.read(installedPacksProvider).where((p) => p.code == code).firstOrNull;
    return installed != null && state.hasUpdateFor(installed);
  }

  void _startPolling() {
    if (_poller != null) return;
    _poller = Timer.periodic(_pollInterval, (_) => _tick());
  }

  /// One poll: refresh progress, finish anything completed, and keep the timer
  /// running only while something is actually in flight.
  ///
  /// The poller deliberately outlives the screen. It is the mechanism that
  /// turns a finished download into an installed pack, and if it stopped when
  /// the rider navigated away, the navigation gate on Home would stay shut
  /// until they came back to this screen.
  Future<void> _tick() async {
    if (_ticking) return; // a previous tick is still hashing a finished pack
    _ticking = true;
    try {
      final live = await ref.read(mapPackDownloaderProvider).progress();
      if (!ref.mounted) return;
      state = state.copyWith(progress: {for (final p in live) p.code: p});
      await _harvest();
      if (!ref.mounted) return;
      if (state.progress.isEmpty) {
        _poller?.cancel();
        _poller = null;
      } else {
        _startPolling();
      }
    } finally {
      _ticking = false;
    }
  }

  /// Errors are one-shot but not deduplicated: two identical failures in a row
  /// must both reach the rider, hence the nonce.
  void _reportError(String code) {
    if (!ref.mounted) return;
    state = state.copyWith(lastError: code, errorNonce: state.errorNonce + 1);
  }

  /// Collects finished downloads and acts on each outcome.
  Future<void> _harvest() async {
    final results = await ref.read(mapPackDownloaderProvider).reconcile();
    for (final result in results) {
      switch (result.outcome) {
        case PackOutcome.installed:
          _retries.remove(result.code);
          await ref.read(installedPacksProvider.notifier).put(result.toInstalledPack());
          talker.info('[OfflineMaps] installed ${result.code}');

        case PackOutcome.conflict:
        case PackOutcome.checksumMismatch:
          // Both mean the same thing often enough: the corpus was rebuilt while
          // we were downloading. Re-read the manifest and try once more with
          // the new expected hash — bounded, or a server publishing in a loop
          // would keep us downloading forever.
          await _retryAfterConflict(result);

        case PackOutcome.failed:
          talker.warning('[OfflineMaps] download failed ${result.code}: ${result.detail}');
          _reportError('downloadFailed');

        case PackOutcome.cancelled:
          _retries.remove(result.code);
      }
    }
  }

  Future<void> _retryAfterConflict(PackDownloadResult result) async {
    final attempts = _retries[result.code] ?? 0;
    if (attempts >= _maxRetries) {
      talker.warning('[OfflineMaps] giving up on ${result.code} after $attempts retries');
      _retries.remove(result.code);
      _reportError('downloadFailed');
      return;
    }
    _retries[result.code] = attempts + 1;

    await refresh();
    if (!ref.mounted) return;
    final region = state.regions.where((r) => r.code == result.code).firstOrNull;
    if (region == null) {
      // The pack is gone from the corpus — a normal outcome, not an error.
      talker.info('[OfflineMaps] ${result.code} no longer in the manifest');
      _retries.remove(result.code);
      return;
    }
    talker.info('[OfflineMaps] retrying ${result.code} (attempt ${attempts + 1})');
    await download(region, _titles[result.code] ?? result.code);
  }

  /// Clears the one-shot error after the screen has shown it.
  void errorShown() => state = state.copyWith(clearError: true);

  /// One poll, exposed for tests: the re-entrancy guard is the kind of thing
  /// that silently stops working, and `Timer.periodic` can't be driven from a
  /// test without waiting in real time.
  @visibleForTesting
  Future<void> pollForTest() => _tick();
}

final offlineMapsControllerProvider =
    NotifierProvider<OfflineMapsController, OfflineMapsState>(OfflineMapsController.new);
