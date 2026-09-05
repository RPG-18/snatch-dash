import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:snatch_dash/data/map_pack_downloader.dart';
import 'package:snatch_dash/models/offline_map.dart';

const _region = MapRegion(
  code: 'ru-ad',
  path: 'ru/ru-ad.pmtiles',
  sizeBytes: 14202287,
  sha256: 'e0c64d233a68d8ac16178ff9cc6e87447aa819a8a3326fd1286ac6c67d029eb7',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<MethodCall> calls;
  late Map<String, Object?> replies;
  late MapPackDownloader downloader;

  setUp(() {
    calls = [];
    replies = {};
    const channel = MethodChannel('ru.snatchdash.app/maps');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (replies.containsKey(call.method)) return replies[call.method];
      throw PlatformException(code: 'ENQUEUE_FAILED', message: 'downloader disabled');
    });
    downloader = MapPackDownloader(channel: channel);
  });

  test('start passes everything the platform needs to verify later', () async {
    replies['start'] = 42;

    await downloader.start(
      _region,
      url: Uri.parse('https://storage.yandexcloud.net/snatch-dash-maps/ru/ru-ad.pmtiles'),
      generatedAt: '2026-09-03T08:27:35Z',
      title: 'Адыгея',
    );

    expect(calls.single.method, 'start');
    final args = calls.single.arguments as Map;
    expect(args['code'], 'ru-ad');
    expect(args['sha256'], _region.sha256);
    expect(args['sizeBytes'], 14202287);
    expect(args['generatedAt'], '2026-09-03T08:27:35Z');
    // The system notification shows this — the localised name, not the code.
    expect(args['title'], 'Адыгея');
  });

  test('a disabled system downloader surfaces as an exception, not a crash', () async {
    expect(
      () => downloader.start(_region, url: Uri.parse('https://x/y'), generatedAt: '', title: 'x'),
      throwsA(isA<PlatformException>().having((e) => e.code, 'code', 'ENQUEUE_FAILED')),
    );
  });

  test('progress falls back to the manifest size until Content-Length lands', () async {
    replies['progress'] = [
      {'code': 'ru-ad', 'bytesSoFar': 7101143, 'totalBytes': 14202287},
      {'code': 'ru-sa', 'bytesSoFar': 0, 'totalBytes': 0},
    ];

    final progress = await downloader.progress();

    expect(progress.first.code, 'ru-ad');
    expect(progress.first.fraction, closeTo(0.5, 0.001));
    // Nothing known yet — no fraction rather than a fake zero-length bar.
    expect(progress.last.fraction, isNull);
  });

  test('reconcile maps an install straight into a registry row', () async {
    replies['reconcile'] = [
      {
        'code': 'ru-ad',
        'outcome': 'INSTALLED',
        'sha256': _region.sha256,
        'generatedAt': '2026-09-03T08:27:35Z',
        'sizeBytes': 14202287,
      },
    ];

    final results = await downloader.reconcile();
    expect(results.single.outcome, PackOutcome.installed);

    final pack = results.single.toInstalledPack();
    expect(pack.code, 'ru-ad');
    expect(pack.sha256, _region.sha256);
    expect(pack.generatedAt, '2026-09-03T08:27:35Z');
    expect(pack.sizeBytes, 14202287);
    expect(pack.installedAtMs, greaterThan(0));
  });

  test('ERROR_CANNOT_RESUME comes back as a conflict, not a failure', () async {
    replies['reconcile'] = [
      {'code': 'ru-ad', 'outcome': 'CONFLICT', 'detail': 'reason=1008'},
    ];

    final result = (await downloader.reconcile()).single;

    // The distinction is the whole point: a conflict means the corpus was
    // rebuilt mid-download and the manifest should be re-read, whereas a
    // failure is worth showing to the rider.
    expect(result.outcome, PackOutcome.conflict);
    expect(result.detail, 'reason=1008');
  });

  test('checksum mismatch is reported with what we actually got', () async {
    replies['reconcile'] = [
      {'code': 'ru-ad', 'outcome': 'CHECKSUM_MISMATCH', 'detail': 'deadbeef'},
    ];

    final result = (await downloader.reconcile()).single;
    expect(result.outcome, PackOutcome.checksumMismatch);
    expect(result.detail, 'deadbeef');
  });

  test('an outcome this build does not know degrades to failed', () async {
    replies['reconcile'] = [
      {'code': 'ru-ad', 'outcome': 'SOMETHING_NEW'},
    ];

    expect((await downloader.reconcile()).single.outcome, PackOutcome.failed);
  });

  test('installedFiles reports what the engine will actually see', () async {
    replies['installedFiles'] = [
      {'code': 'ru-ad', 'sizeBytes': 14202287},
      {'code': 'ru-len-spe', 'sizeBytes': 118000000},
    ];

    expect(await downloader.installedFiles(), {'ru-ad': 14202287, 'ru-len-spe': 118000000});
  });

  test('room check and delete forward their arguments', () async {
    replies['hasRoomFor'] = false;
    replies['delete'] = true;

    expect(await downloader.hasRoomFor(14202287), isFalse);
    expect((calls.first.arguments as Map)['bytes'], 14202287);

    expect(await downloader.delete('ru-ad'), isTrue);
    expect((calls.last.arguments as Map)['code'], 'ru-ad');
  });
}
