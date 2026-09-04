import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:snatch_dash/data/installed_packs_repository.dart';
import 'package:snatch_dash/data/map_manifest_api.dart';
import 'package:snatch_dash/data/map_pack_downloader.dart';
import 'package:snatch_dash/models/offline_map.dart';
import 'package:snatch_dash/state/offline_maps_controller.dart';

const _adygea = MapRegion(code: 'ru-ad', path: 'ru/ru-ad.pmtiles', sizeBytes: 100, sha256: 'aaa');

MapManifest _manifest({String generatedAt = 'T1', String sha = 'aaa'}) => MapManifest(
      schemaVersion: 1,
      generatedAt: generatedAt,
      fileCount: 1,
      totalSizeBytes: 100,
      regions: [MapRegion(code: 'ru-ad', path: 'ru/ru-ad.pmtiles', sizeBytes: 100, sha256: sha)],
    );

class _FakeApi extends MapManifestApi {
  _FakeApi({this.remote, this.local, this.remoteThrows});

  MapManifest? remote;
  MapManifest? local;
  Object? remoteThrows;
  int fetchCount = 0;

  @override
  Future<MapManifest> fetchRemote() async {
    fetchCount++;
    final failure = remoteThrows;
    if (failure != null) throw failure;
    final manifest = remote;
    if (manifest == null) throw Exception('offline');
    return manifest;
  }

  @override
  Future<MapManifest?> readLocal() async => local;
}

class _FakeDownloader extends MapPackDownloader {
  bool room = true;
  Object? startThrows;
  final List<String> started = [];
  final List<String> cancelled = [];
  final List<String> deleted = [];

  /// Queued so a test can hand back one batch of outcomes and then nothing.
  final List<List<PackDownloadResult>> harvests = [];
  List<PackProgress> live = const [];

  @override
  Future<bool> hasRoomFor(int bytes) async => room;

  @override
  Future<void> start(MapRegion region,
      {required Uri url, required String generatedAt, required String title}) async {
    final failure = startThrows;
    if (failure != null) throw failure;
    started.add(region.code);
  }

  @override
  Future<void> cancel(String code) async => cancelled.add(code);

  @override
  Future<bool> delete(String code) async {
    deleted.add(code);
    return true;
  }

  @override
  Future<List<PackProgress>> progress() async => live;

  /// What the render engine would see on disk. Defaults to "everything the
  /// registry claims", so the drift check is a no-op unless a test says otherwise.
  Map<String, int>? filesOnDisk;

  @override
  Future<Map<String, int>> installedFiles() async => filesOnDisk ?? _registryEcho;

  Map<String, int> _registryEcho = const {};

  @override
  Future<List<PackDownloadResult>> reconcile() async =>
      harvests.isEmpty ? const [] : harvests.removeAt(0);
}

ProviderContainer _container({
  required _FakeApi api,
  required _FakeDownloader downloader,
  InstalledPacksRepository? repo,
  List<InstalledPack> onDisk = const [],
}) {
  downloader._registryEcho = {for (final p in onDisk) p.code: p.sizeBytes};
  final container = ProviderContainer(overrides: [
    mapManifestApiProvider.overrideWithValue(api),
    mapPackDownloaderProvider.overrideWithValue(downloader),
    installedPacksRepositoryProvider
        .overrideWithValue(repo ?? InMemoryInstalledPacksRepository()),
  ]);
  addTearDown(container.dispose);
  return container;
}

/// Lets the controller's `Future.microtask` bootstrap run to completion.
Future<void> _settle() => Future<void>.delayed(Duration.zero);

void main() {
  group('manifest status — the three sources fail independently', () {
    test('server answers → ready', () async {
      final api = _FakeApi(remote: _manifest());
      final container = _container(api: api, downloader: _FakeDownloader());

      container.read(offlineMapsControllerProvider);
      await _settle();

      final state = container.read(offlineMapsControllerProvider);
      expect(state.status, ManifestStatus.ready);
      expect(state.regions.single.code, 'ru-ad');
    });

    test('server silent but a local copy exists → staleCache, list still works', () async {
      final api = _FakeApi(local: _manifest());
      final container = _container(api: api, downloader: _FakeDownloader());

      container.read(offlineMapsControllerProvider);
      await _settle();

      final state = container.read(offlineMapsControllerProvider);
      expect(state.status, ManifestStatus.staleCache);
      // The point of the fallback: packs stay browsable without a network.
      expect(state.regions, hasLength(1));
    });

    test('nothing anywhere → unavailable', () async {
      final container = _container(api: _FakeApi(), downloader: _FakeDownloader());

      container.read(offlineMapsControllerProvider);
      await _settle();

      expect(container.read(offlineMapsControllerProvider).status, ManifestStatus.unavailable);
    });

    test('a schema we cannot read is refused outright, installed packs untouched', () async {
      final repo = InMemoryInstalledPacksRepository([
        const InstalledPack(
            code: 'ru-ad', sha256: 'aaa', generatedAt: 'T1', sizeBytes: 100, installedAtMs: 1),
      ]);
      final api = _FakeApi(remoteThrows: const ManifestVersionUnsupported(99, 1));
      final container = _container(
          api: api, downloader: _FakeDownloader(), repo: repo, onDisk: await repo.list());

      container.read(offlineMapsControllerProvider);
      container.read(installedPacksProvider);
      await _settle();

      expect(container.read(offlineMapsControllerProvider).status, ManifestStatus.appTooOld);
      expect(container.read(hasInstalledPacksProvider), isTrue,
          reason: 'a too-new manifest must never cost the rider their maps');
    });
  });

  group('downloading', () {
    test('refuses before starting when the volume is full', () async {
      final downloader = _FakeDownloader()..room = false;
      final container = _container(api: _FakeApi(remote: _manifest()), downloader: downloader);

      container.read(offlineMapsControllerProvider);
      await _settle();
      await container.read(offlineMapsControllerProvider.notifier).download(_adygea, 'Адыгея');

      expect(downloader.started, isEmpty, reason: 'failing at 90% of 356 MB is avoidable');
      expect(container.read(offlineMapsControllerProvider).lastError, 'noSpace');
    });

    test('a disabled system downloader becomes an error, not an exception', () async {
      final downloader = _FakeDownloader()..startThrows = Exception('disabled');
      final container = _container(api: _FakeApi(remote: _manifest()), downloader: downloader);

      container.read(offlineMapsControllerProvider);
      await _settle();
      await container.read(offlineMapsControllerProvider.notifier).download(_adygea, 'Адыгея');

      expect(container.read(offlineMapsControllerProvider).lastError, 'enqueueFailed');
    });

    test('an installed pack reaches the registry and lifts the navigation gate', () async {
      final downloader = _FakeDownloader()
        ..harvests.add([
          const PackDownloadResult(
            code: 'ru-ad',
            outcome: PackOutcome.installed,
            sha256: 'aaa',
            generatedAt: 'T1',
            sizeBytes: 100,
          ),
        ]);
      final container = _container(api: _FakeApi(remote: _manifest()), downloader: downloader);

      container.read(offlineMapsControllerProvider);
      container.read(installedPacksProvider);
      expect(container.read(hasInstalledPacksProvider), isFalse);

      await _settle();
      await _settle();

      expect(container.read(installedPacksProvider).single.code, 'ru-ad');
      expect(container.read(hasInstalledPacksProvider), isTrue);
    });
  });

  group('conflict handling', () {
    test('a mid-download rebuild re-reads the manifest and retries', () async {
      final api = _FakeApi(remote: _manifest(generatedAt: 'T2', sha: 'bbb'));
      final downloader = _FakeDownloader()
        ..harvests.add([
          const PackDownloadResult(
              code: 'ru-ad', outcome: PackOutcome.conflict, detail: 'reason=1008'),
        ]);
      final container = _container(api: api, downloader: downloader);

      container.read(offlineMapsControllerProvider);
      await _settle();
      await _settle();

      // Re-read (bootstrap + retry) and started again with the new expectation.
      expect(api.fetchCount, greaterThan(1));
      expect(downloader.started, ['ru-ad']);
      expect(container.read(offlineMapsControllerProvider).lastError, isNull,
          reason: 'a conflict is routine after a weekly rebuild, not a failure to report');
    });

    test('gives up after the retry budget instead of looping forever', () async {
      final api = _FakeApi(remote: _manifest());
      final downloader = _FakeDownloader();
      // Every harvest reports the same conflict — a server republishing in a loop.
      for (var i = 0; i < 5; i++) {
        downloader.harvests.add([
          const PackDownloadResult(code: 'ru-ad', outcome: PackOutcome.conflict),
        ]);
      }
      final container = _container(api: api, downloader: downloader);
      final controller = container.read(offlineMapsControllerProvider.notifier);

      await _settle();
      for (var i = 0; i < 5; i++) {
        await controller.refresh();
        await _settle();
      }

      expect(downloader.started.length, lessThanOrEqualTo(2),
          reason: 'two retries is the budget');
    });

    test('a pack that vanished from the corpus is not an error', () async {
      final api = _FakeApi(
        remote: const MapManifest(
            schemaVersion: 1, generatedAt: 'T2', fileCount: 0, totalSizeBytes: 0, regions: []),
      );
      final downloader = _FakeDownloader()
        ..harvests.add([
          const PackDownloadResult(code: 'ru-ad', outcome: PackOutcome.conflict),
        ]);
      final container = _container(api: api, downloader: downloader);

      container.read(offlineMapsControllerProvider);
      await _settle();
      await _settle();

      expect(downloader.started, isEmpty);
      expect(container.read(offlineMapsControllerProvider).lastError, isNull);
    });
  });

  group('regressions fixed after review', () {
    test('a download that outlived the app is picked up at startup', () async {
      // The whole reason for using the system downloader: the transfer survives
      // the process. Before the fix nothing resumed watching it, so a pack that
      // finished while the app was closed was never installed.
      final downloader = _FakeDownloader()
        ..live = const [PackProgress(code: 'ru-ad', bytesSoFar: 50, totalBytes: 100)];
      final container = _container(api: _FakeApi(remote: _manifest()), downloader: downloader);

      container.read(offlineMapsControllerProvider);
      await _settle();
      await _settle();

      expect(container.read(offlineMapsControllerProvider).progress.containsKey('ru-ad'), isTrue,
          reason: 'progress must be visible without starting a new download');
    });

    test('overlapping ticks do not pile up on the same reconcile', () async {
      var reconciles = 0;
      final downloader = _SlowReconcileDownloader(() => reconciles++);
      final container = _container(api: _FakeApi(remote: _manifest()), downloader: downloader);
      final controller = container.read(offlineMapsControllerProvider.notifier);
      // The bootstrap tick is itself "slow" here — wait it out, or it would be
      // the thing holding the guard and the measurement would prove nothing.
      await Future<void>.delayed(const Duration(milliseconds: 60));

      final before = reconciles;
      // Three polls fired while the first is still hashing, as `Timer.periodic`
      // would do — it does not wait for its async callback.
      await Future.wait(
          [controller.pollForTest(), controller.pollForTest(), controller.pollForTest()]);

      expect(reconciles - before, 1, reason: 'the re-entrancy guard drops the overlapping ticks');
    });

    test('the same failure twice in a row is reported twice', () async {
      final downloader = _FakeDownloader()..room = false;
      final container = _container(api: _FakeApi(remote: _manifest()), downloader: downloader);
      final controller = container.read(offlineMapsControllerProvider.notifier);
      await _settle();

      await controller.download(_adygea, 'Адыгея');
      final first = container.read(offlineMapsControllerProvider).errorNonce;
      controller.errorShown();
      await controller.download(_adygea, 'Адыгея');
      final second = container.read(offlineMapsControllerProvider).errorNonce;

      expect(second, greaterThan(first),
          reason: 'a plain string compares equal and the listener would stay silent');
    });

    test('a registry row whose file vanished is dropped, not trusted', () async {
      final repo = InMemoryInstalledPacksRepository([
        const InstalledPack(
            code: 'ru-ad', sha256: 'aaa', generatedAt: 'T1', sizeBytes: 100, installedAtMs: 1),
      ]);
      // Registry says installed, disk says otherwise — the gate would otherwise
      // claim maps the engine cannot draw.
      final downloader = _FakeDownloader()..filesOnDisk = const {};
      final container = _container(
          api: _FakeApi(remote: _manifest()), downloader: downloader, repo: repo);

      container.read(offlineMapsControllerProvider);
      container.read(installedPacksProvider);
      await _settle();
      await _settle();

      expect(container.read(hasInstalledPacksProvider), isFalse);
    });

    test('a retry keeps the localised name for the system notification', () async {
      final api = _FakeApi(remote: _manifest(sha: 'bbb'));
      final downloader = _TitleCapturingDownloader();
      final container = _container(api: api, downloader: downloader);
      final controller = container.read(offlineMapsControllerProvider.notifier);
      await _settle();

      // Order matters: the conflict has to follow a download we started, which
      // is the only way it can happen in life.
      await controller.download(_adygea, 'Адыгея');
      downloader.harvests.add([
        const PackDownloadResult(code: 'ru-ad', outcome: PackOutcome.conflict),
      ]);
      await controller.pollForTest();
      await _settle();

      expect(downloader.titles, ['Адыгея', 'Адыгея'],
          reason: 'the raw code would otherwise show up in the notification shade');
    });
  });

  group('updates and deletion', () {
    test('hasUpdate compares the installed hash against the manifest', () async {
      final repo = InMemoryInstalledPacksRepository([
        const InstalledPack(
            code: 'ru-ad', sha256: 'old', generatedAt: 'T1', sizeBytes: 100, installedAtMs: 1),
      ]);
      final container = _container(
        api: _FakeApi(remote: _manifest(sha: 'new')),
        downloader: _FakeDownloader(),
        repo: repo,
        onDisk: await repo.list(),
      );
      final controller = container.read(offlineMapsControllerProvider.notifier);
      container.read(installedPacksProvider);
      await _settle();

      expect(controller.hasUpdate('ru-ad'), isTrue);
      expect(controller.hasUpdate('ru-nope'), isFalse);
    });

    test('delete removes both the file and the registry row', () async {
      final repo = InMemoryInstalledPacksRepository([
        const InstalledPack(
            code: 'ru-ad', sha256: 'aaa', generatedAt: 'T1', sizeBytes: 100, installedAtMs: 1),
      ]);
      final downloader = _FakeDownloader();
      final container = _container(
          api: _FakeApi(remote: _manifest()),
          downloader: downloader,
          repo: repo,
          onDisk: await repo.list());

      container.read(installedPacksProvider);
      await _settle();
      await container.read(offlineMapsControllerProvider.notifier).delete('ru-ad');

      expect(downloader.deleted, ['ru-ad']);
      expect(container.read(installedPacksProvider), isEmpty);
      expect(container.read(hasInstalledPacksProvider), isFalse);
    });
  });
}


/// Reconcile that takes a turn of the event loop, so overlapping polls can be
/// observed the way `Timer.periodic` would produce them.
class _SlowReconcileDownloader extends _FakeDownloader {
  _SlowReconcileDownloader(this.onReconcile);
  final void Function() onReconcile;

  @override
  Future<List<PackDownloadResult>> reconcile() async {
    onReconcile();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    return const [];
  }
}

class _TitleCapturingDownloader extends _FakeDownloader {
  final List<String> titles = [];

  @override
  Future<void> start(MapRegion region,
      {required Uri url, required String generatedAt, required String title}) async {
    titles.add(title);
    return super.start(region, url: url, generatedAt: generatedAt, title: title);
  }
}
