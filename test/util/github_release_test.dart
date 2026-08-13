import 'package:flutter_test/flutter_test.dart';
import 'package:snatch_dash/util/github_release.dart';

/// `AppRelease.fromJson`'s asset picking doesn't depend on the host OS
/// ABI (that's only relevant on-device — `Abi.current()` is never
/// arm64-v8a/armeabi-v7a in the test runner), so these only cover the
/// paths that are ABI-independent: no APK at all, and the single-asset
/// fallback. See github_release.dart's `_currentAbiTag` for the on-device
/// matching this doesn't exercise.
void main() {
  Map<String, dynamic> releaseJson({required List<Map<String, String>> assets}) => {
        'tag_name': 'v1.2.3',
        'name': 'v1.2.3',
        'html_url': 'https://example.com',
        'body': null,
        'published_at': '2026-01-01T00:00:00Z',
        'assets': [
          for (final a in assets)
            {'name': a['name'], 'browser_download_url': 'https://example.com/${a['name']}'},
        ],
      };

  test('no assets at all -> null (nothing to offer)', () {
    expect(AppRelease.fromJson(releaseJson(assets: const [])), isNull);
  });

  test('no .apk assets -> null (e.g. only source archives attached)', () {
    final json = releaseJson(assets: const [
      {'name': 'source.zip'},
      {'name': 'notes.txt'},
    ]);
    expect(AppRelease.fromJson(json), isNull);
  });

  test('exactly one .apk asset -> used regardless of ABI tag in its name', () {
    final json = releaseJson(assets: const [
      {'name': 'snatch-dash-1.2.3-universal.apk'},
    ]);
    final release = AppRelease.fromJson(json);
    expect(release, isNotNull);
    expect(release!.apkName, 'snatch-dash-1.2.3-universal.apk');
  });

  test('multiple .apk assets, none matching this (non-Android) host -> null', () {
    final json = releaseJson(assets: const [
      {'name': 'snatch-dash-1.2.3-arm64-v8a.apk'},
      {'name': 'snatch-dash-1.2.3-armeabi-v7a.apk'},
    ]);
    expect(AppRelease.fromJson(json), isNull);
  });
}
