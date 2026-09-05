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
/// `null` until sqlite has been read once — see [hasInstalledPacksProvider].
class InstalledPacks extends Notifier<List<InstalledPack>?> {
  int _reloadGeneration = 0;

  @override
  List<InstalledPack>? build() {
    Future.microtask(reload);
    return null;
  }

  Future<void> reload() async {
    final generation = ++_reloadGeneration;
    List<InstalledPack> packs;
    try {
      packs = await ref.read(installedPacksRepositoryProvider).list();
    } catch (e, st) {
      // `null` means "not read yet", and readers treat that as "say nothing
      // yet" — which is right for the microtask at startup and very wrong
      // forever. A registry we cannot read is an answer: no packs we can
      // account for. The engine still draws whatever is on disk; the interface
      // stops claiming to know better.
      talker.error('[OfflineMaps] could not read the pack registry', e, st);
      if (!ref.mounted || generation != _reloadGeneration) return;
      state = const [];
      return;
    }
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
    NotifierProvider<InstalledPacks, List<InstalledPack>?>(InstalledPacks.new);

/// The one flag the rest of the app asks about — `null` while the registry has
/// not been read yet.
///
/// Three states, not two, because the first frames of a cold start have no
/// answer: the registry fills itself from sqlite in a microtask, and a plain
/// `false` in the meantime made Home show "no maps, navigation disabled" and
/// Dash its "no maps" chip for a moment on every launch of a perfectly stocked
/// app. Callers must treat `null` as "don't say anything yet", not as `false`.
///
/// Kept derived rather than duplicated so it can never disagree with the
/// registry.
final hasInstalledPacksProvider =
    Provider<bool?>((ref) => ref.watch(installedPacksProvider)?.isNotEmpty);

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

  /// Set by [download] whenever a transfer is started, cleared at the top of
  /// each [_tick].
  ///
  /// Closes the "last tick" race. A tick that finds `progress` empty clears the
  /// map and then awaits `_harvest()`; a download started inside that window
  /// reaches `_startPolling()` while the timer is still alive, so it is a no-op,
  /// and does not touch `state.progress` either. The tick then resumes, sees an
  /// empty `progress` and cancels the timer — leaving the fresh download with
  /// nobody watching it. The provider is not autoDispose, so nothing would
  /// rebuild and pick it up before the app restarts: the transfer completes and
  /// the `.part` file sits there uninstalled.
  bool _wantsPolling = false;

  static const _maxRetries = 2;
  static const _pollInterval = Duration(milliseconds: 700);

  @override
  OfflineMapsState build() {
    ref.onDispose(() => _poller?.cancel());
    Future.microtask(() async {
      // Nothing awaits this microtask, so anything thrown here is an unhandled
      // async error — and it would take the rest of the bootstrap with it, which
      // is the step that installs a download the app was killed in the middle
      // of. Each part is independent enough to be worth attempting even if an
      // earlier one failed.
      try {
        await refresh();
      } catch (e, st) {
        talker.error('[OfflineMaps] manifest bootstrap failed', e, st);
      }
      try {
        await _reconcileRegistryAgainstDisk();
      } catch (e, st) {
        talker.error('[OfflineMaps] registry/disk check failed', e, st);
      }
      // Picks up whatever finished while the app was closed, and — crucially —
      // resumes watching anything still in flight. Without this the whole point
      // of using the system downloader is lost: a transfer that outlived the
      // app would never be noticed, let alone installed.
      await _tick(); // has its own catch
    });
    return const OfflineMapsState();
  }

  /// Registry and disk are two sources of truth — the interface reads the first,
  /// the render engine the second — and they drift **both** ways.
  ///
  /// The install is a native rename followed by a sqlite write, two operations
  /// across a method channel: a process killed between them leaves a file with no
  /// row. The opposite happens when a file goes away under us. Neither is
  /// hypothetical after a mid-download kill, and both are cheap to check, so both
  /// are checked at startup:
  ///
  /// - **row without file** — the navigation gate would promise a map that
  ///   cannot be drawn. Drop the row.
  /// - **file without row** — the engine already draws that pack while the
  ///   interface says nothing is installed and the gate stays shut over a working
  ///   map. Adopt the file. Its hash is unknown (re-hashing hundreds of megabytes
  ///   at every launch is not on the table), and an empty hash reads as "differs
  ///   from the manifest", so the pack shows up as installed *with an update
  ///   available* — which is exactly the honest answer: it is there, and we
  ///   cannot vouch for which build it is.
  Future<void> _reconcileRegistryAgainstDisk() async {
    final onDisk = await ref.read(mapPackDownloaderProvider).installedFiles();
    if (!ref.mounted) return;
    // Straight from the repository, not from installedPacksProvider: on a cold
    // start that provider answers `const []` synchronously and fills itself in a
    // microtask of its own, so reading it here turned the whole check into a
    // silent no-op — at startup, the one moment it exists for. Forcing a
    // reload() instead would work but races anything the rider does meanwhile,
    // since reload() writes the provider's state from the repository.
    final rows = await ref.read(installedPacksRepositoryProvider).list();
    if (!ref.mounted) return;
    for (final pack in rows) {
      if (onDisk.containsKey(pack.code)) continue;
      talker.warning('[OfflineMaps] ${pack.code} is in the registry but not on disk — dropping');
      await ref.read(installedPacksProvider.notifier).remove(pack.code);
    }

    final known = {for (final pack in rows) pack.code};
    for (final entry in onDisk.entries) {
      if (known.contains(entry.key)) continue;
      talker.warning('[OfflineMaps] ${entry.key} is on disk but not in the registry — adopting');
      await ref.read(installedPacksProvider.notifier).put(
            InstalledPack(
              code: entry.key,
              // Unknown, deliberately: see the note above. Empty compares unequal
              // to every manifest hash, so the pack offers an update instead of
              // claiming to be current.
              sha256: '',
              generatedAt: '',
              sizeBytes: entry.value,
              installedAtMs: DateTime.now().millisecondsSinceEpoch,
            ),
          );
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
      _wantsPolling = true;
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
    // File first, download second. The reverse order threw away an in-flight
    // update *before* learning whether the delete would even work: on a failure
    // the rider then had neither the deletion they asked for nor the update they
    // had been waiting on, and nothing said so.
    final removed = await ref.read(mapPackDownloaderProvider).delete(code);
    if (!removed) {
      // The file is still there, and the engine enumerates files, not the
      // registry: dropping the row anyway would leave the dash drawing a map the
      // interface says is gone — and could shut the navigation gate over a live
      // one.
      talker.warning('[OfflineMaps] $code was not deleted from disk — keeping its registry row');
      _reportError('deleteFailed');
      return;
    }
    // Only now that the file is actually gone: a pack can be deleted while an
    // update for it is downloading — the screen hides the menu then, but the
    // interface's shape is not where an invariant should live, and the poller
    // may not have noticed the transfer yet either. Left running, it would
    // reinstall the pack the rider just deleted on the next harvest.
    //
    // Guarded, because the row must come off regardless. The file is already
    // gone, so a channel failure here would otherwise leave the registry
    // claiming a pack that does not exist — and unremovable, since every retry
    // now finds nothing to delete and reports deleteFailed.
    try {
      await cancel(code);
    } catch (e, st) {
      talker.error('[OfflineMaps] could not cancel a download for the deleted $code', e, st);
    }
    await ref.read(installedPacksProvider.notifier).remove(code);
  }

  /// Whether the server has a different build of an installed pack.
  ///
  /// After a weekly corpus rebuild this is true for *everything* — the build
  /// timestamp lands in the tileset metadata, so every sha256 changes even
  /// when the OSM data didn't. The interface must not promise incremental
  /// updates (spec/remote_map_server.md, "Проверка обновлений").
  bool hasUpdate(String code) {
    final installed =
        (ref.read(installedPacksProvider) ?? const []).where((p) => p.code == code).firstOrNull;
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
    // Cleared here, at the top, so that a download started during either await
    // below raises it again and is seen at the decision point. See the field
    // this belongs to.
    _wantsPolling = false;
    try {
      final live = await ref.read(mapPackDownloaderProvider).progress();
      if (!ref.mounted) return;
      state = state.copyWith(progress: {for (final p in live) p.code: p});
      await _harvest();
      if (!ref.mounted) return;
      if (state.progress.isEmpty && !_wantsPolling) {
        _poller?.cancel();
        _poller = null;
      } else {
        _startPolling();
      }
    } catch (e, st) {
      // A PlatformException out of the channel used to surface as an unhandled
      // async error with nothing to catch it, every 700 ms for as long as the
      // timer ran.
      //
      // Keep polling unconditionally, without consulting `progress` or
      // `_wantsPolling`: the very first statement in the try is the channel call
      // that fills them, so a failure there leaves both empty and says nothing
      // about whether a download exists. This tick is also the bootstrap one,
      // running before any timer — and giving up there means a transfer that
      // outlived the app is never harvested this session, which is the single
      // scenario the system downloader was chosen for. A timer that retries is
      // the cheap side of that trade; it stops itself on the first clean tick
      // that finds nothing in flight.
      talker.error('[OfflineMaps] poll tick failed', e, st);
      if (ref.mounted) _startPolling();
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
          // DownloadManager could not resume: the object moved under us, which
          // for this corpus means a rebuild. Re-read the manifest and try once
          // more with the new expected hash — bounded, or a server publishing in
          // a loop would keep us downloading forever.
          await _retryAfterConflict(result);

        case PackOutcome.checksumMismatch:
          await _handleChecksumMismatch(result);

        case PackOutcome.failed:
          talker.warning('[OfflineMaps] download failed ${result.code}: ${result.detail}');
          // Without this the stale count outlives the failure and the rider's
          // next manual download starts with part of its retry budget spent.
          _retries.remove(result.code);
          _reportError('downloadFailed');

        case PackOutcome.cancelled:
          _retries.remove(result.code);
      }
    }
  }

  /// A pack whose sha256 did not match what the manifest promised.
  ///
  /// Two very different things arrive here, and the protocol
  /// (spec/remote_map_server.md, «Порядок скачивания», п. 5) requires telling
  /// them apart before spending the rider's mobile data again:
  ///
  /// - the manifest has moved on since this download started — the corpus was
  ///   rebuilt under us, which is an ordinary conflict and worth another go;
  /// - the manifest still says exactly what it said when we started — then the
  ///   object in the bucket really is corrupt, and two more attempts would pull
  ///   hundreds of megabytes (Yakutia is 356 MB) to fail in exactly the same
  ///   way. Report it and stop.
  Future<void> _handleChecksumMismatch(PackDownloadResult result) async {
    final startedAgainst = result.generatedAt;
    await refresh();
    if (!ref.mounted) return;
    final now = state.manifest?.generatedAt;
    // Only a manifest that actually came from the server can settle this.
    // refresh() falls back to the cached copy when the network is down, and that
    // copy is very often the exact generation this download started against — so
    // without this guard a rider on a bad connection would get "the pack on the
    // server is corrupt" for what is really an ordinary stale download, and no
    // retry.
    final fromServer = state.status == ManifestStatus.ready;

    if (fromServer &&
        startedAgainst != null &&
        startedAgainst.isNotEmpty &&
        now != null &&
        startedAgainst == now) {
      talker.error(
        '[OfflineMaps] ${result.code} failed its checksum against an unchanged manifest '
        '($now) — the published pack is corrupt, not stale; got ${result.detail}',
      );
      _retries.remove(result.code);
      _reportError('packCorrupt');
      return;
    }

    talker.info(
      '[OfflineMaps] ${result.code} checksum mismatch across a manifest change '
      '($startedAgainst -> $now) — retrying',
    );
    await _retryAfterConflict(result, manifestIsFresh: true);
  }

  Future<void> _retryAfterConflict(PackDownloadResult result, {bool manifestIsFresh = false}) async {
    final attempts = _retries[result.code] ?? 0;
    if (attempts >= _maxRetries) {
      talker.warning('[OfflineMaps] giving up on ${result.code} after $attempts retries');
      _retries.remove(result.code);
      _reportError('downloadFailed');
      return;
    }
    _retries[result.code] = attempts + 1;

    // The checksum path has just re-read the manifest to decide whether to come
    // here at all; fetching it twice would be a second network round trip for
    // the same answer.
    if (!manifestIsFresh) await refresh();
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
