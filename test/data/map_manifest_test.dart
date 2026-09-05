import 'package:flutter_test/flutter_test.dart';

import 'package:snatch_dash/data/map_manifest_api.dart';
import 'package:snatch_dash/data/map_region_names.dart';

/// Trimmed to two regions; shape copied verbatim from the published
/// `index.json` (build 2026-09-03).
const _liveShape = '''
{
  "version": 1,
  "generated_at": "2026-09-03T08:27:35Z",
  "file_count": 80,
  "total_size": 4449888181,
  "countries": [
    {
      "iso": "ru",
      "mode": "subjects",
      "regions": [
        {"code": "ru-ad", "path": "ru/ru-ad.pmtiles", "size": 14202287,
         "sha256": "e0c64d233a68d8ac16178ff9cc6e87447aa819a8a3326fd1286ac6c67d029eb7"},
        {"code": "ru-len-spe", "path": "ru/ru-len-spe.pmtiles", "size": 118000000,
         "sha256": "aa11"}
      ]
    }
  ]
}
''';

void main() {
  group('MapManifestApi.parse', () {
    test('reads the published manifest shape', () {
      final manifest = MapManifestApi.parse(_liveShape);

      expect(manifest.schemaVersion, 1);
      expect(manifest.generatedAt, '2026-09-03T08:27:35Z');
      expect(manifest.fileCount, 80);
      expect(manifest.totalSizeBytes, 4449888181);
      expect(manifest.regions, hasLength(2));

      final first = manifest.regions.first;
      expect(first.code, 'ru-ad');
      expect(first.path, 'ru/ru-ad.pmtiles');
      expect(first.sizeBytes, 14202287);
      expect(first.sha256.length, 64);
    });

    test('ignores unknown fields — adding one must not break installed apps', () {
      final withExtras = _liveShape
          .replaceFirst('"version": 1,', '"version": 1, "new_top_level": {"a": 1},')
          .replaceFirst('"code": "ru-ad",', '"code": "ru-ad", "new_region_field": 42,');

      final manifest = MapManifestApi.parse(withExtras);

      expect(manifest.regions, hasLength(2));
      expect(manifest.regions.first.code, 'ru-ad');
    });

    test('refuses a schema newer than this build understands', () {
      final future = _liveShape.replaceFirst('"version": 1,', '"version": 99,');

      expect(
        () => MapManifestApi.parse(future),
        throwsA(isA<ManifestVersionUnsupported>()
            .having((e) => e.found, 'found', 99)
            .having((e) => e.supported, 'supported', kSupportedManifestVersion)),
      );
    });

    test('rejects malformed input rather than guessing', () {
      expect(() => MapManifestApi.parse('not json'), throwsA(isA<ManifestMalformed>()));
      expect(() => MapManifestApi.parse('[]'), throwsA(isA<ManifestMalformed>()));
      expect(() => MapManifestApi.parse('{"countries": []}'), throwsA(isA<ManifestMalformed>()));
      expect(
        () => MapManifestApi.parse(
            '{"version":1,"countries":[{"regions":[{"code":"x","path":"y"}]}]}'),
        throwsA(isA<ManifestMalformed>()),
      );
    });
  });

  group('pack URLs', () {
    test('relative manifest paths resolve against the server base', () {
      final api = MapManifestApi();

      expect(
        api.packUrl('ru/ru-ad.pmtiles').toString(),
        'https://storage.yandexcloud.net/snatch-dash-maps/ru/ru-ad.pmtiles',
      );
      expect(
        api.manifestUrl.toString(),
        'https://storage.yandexcloud.net/snatch-dash-maps/index.json',
      );
    });
  });

  group('mapRegionName', () {
    test('localises both languages', () {
      expect(mapRegionName('ru-ad', 'ru'), 'Адыгея');
      expect(mapRegionName('ru-ad', 'en'), 'Adygea');
    });

    test('merged agglomerations name both halves', () {
      expect(mapRegionName('ru-len-spe', 'ru'), 'Санкт-Петербург и Ленинградская область');
      expect(mapRegionName('ru-mos-mow', 'en'), 'Moscow and Moscow Oblast');
    });

    test('falls back to the code so a new pack stays downloadable', () {
      expect(mapRegionName('ru-brand-new', 'ru'), 'ru-brand-new');
      expect(hasMapRegionName('ru-brand-new'), isFalse);
    });

    test('covers every code of the published corpus', () {
      // Pinned against the 2026-09-03 build: 80 packs, Chukotka excluded.
      // A corpus rebuild that adds or renames a pack should fail here rather
      // than silently show raw codes in the list.
      expect(knownMapRegionCodes.length, 80);
      for (final code in const ['ru-ad', 'ru-zab', 'ru-sa', 'ru-len-spe', 'ru-mos-mow']) {
        expect(hasMapRegionName(code), isTrue, reason: code);
      }
      expect(hasMapRegionName('ru-chu'), isFalse, reason: 'Чукотки в корпусе нет');
    });
  });
}
