import 'dart:convert';
import 'dart:ffi' show Abi;

import 'package:http/http.dart' as http;

/// Which GitHub Releases track to watch — see `.github/workflows/`:
/// `release.yml` publishes real (non-prerelease) semver-tagged releases,
/// `nightly.yml` publishes a prerelease per day tagged `nightly-YYYYMMDD`.
enum UpdateChannel { stable, nightly }

/// A GitHub release, trimmed to what the update checker/downloader need.
class AppRelease {
  const AppRelease({
    required this.tag,
    required this.name,
    required this.htmlUrl,
    required this.apkUrl,
    required this.apkName,
    required this.body,
    required this.publishedAt,
  });

  /// Git tag, e.g. `v1.2.0` (stable) or `nightly-20260814`.
  final String tag;
  final String name;
  final String htmlUrl;
  final String apkUrl;
  final String apkName;
  final String? body;
  final DateTime? publishedAt;

  static AppRelease? fromJson(Map<String, dynamic> json) {
    final assets = (json['assets'] as List?)?.cast<Map<String, dynamic>>() ?? const [];
    final apkAssets = assets.where((a) => (a['name'] as String?)?.toLowerCase().endsWith('.apk') ?? false).toList();
    if (apkAssets.isEmpty) return null; // release with no APK asset attached — nothing to offer
    // CI publishes one split APK per ABI (`flutter build apk --split-per-abi
    // --target-platform android-arm,android-arm64` in .github/workflows/
    // release.yml — there is no `splits {}` block in Gradle, the Flutter tool
    // drives it), filenames tagged e.g. `-arm64-v8a-`. Pick
    // the one matching this device; on an ABI CI doesn't publish for
    // (x86/x86_64 emulators) there's nothing installable to offer.
    final abiTag = _currentAbiTag();
    final apk = abiTag == null
        ? null
        : apkAssets.cast<Map<String, dynamic>?>().firstWhere(
            (a) => (a?['name'] as String).contains(abiTag),
            orElse: () => null,
          );
    // Falls back to a lone asset (e.g. a hand-published release with a
    // single universal APK) so that path still works.
    final chosen = apk ?? (apkAssets.length == 1 ? apkAssets.first : null);
    if (chosen == null) return null;
    return AppRelease(
      tag: json['tag_name'] as String,
      name: json['name'] as String? ?? json['tag_name'] as String,
      htmlUrl: json['html_url'] as String,
      apkUrl: chosen['browser_download_url'] as String,
      apkName: chosen['name'] as String,
      body: json['body'] as String?,
      publishedAt: DateTime.tryParse(json['published_at'] as String? ?? ''),
    );
  }
}

/// This device's ABI as the tag it appears under in split-APK filenames
/// (CI's `--target-platform android-arm,android-arm64`), or null for ABIs CI
/// doesn't publish an APK for (x86/x86_64 — emulators only).
String? _currentAbiTag() => switch (Abi.current()) {
      Abi.androidArm64 => 'arm64-v8a',
      Abi.androidArm => 'armeabi-v7a',
      _ => null,
    };

/// Reads the update-relevant bits of GitHub's Releases API for
/// `RPG-18/snatch-dash`. Unauthenticated (public repo) — GitHub's
/// 60-req/hour-per-IP limit is far more than an occasional per-launch check
/// needs.
class GitHubReleases {
  GitHubReleases._();

  static const _repo = 'RPG-18/snatch-dash';
  static const _apiBase = 'https://api.github.com/repos/$_repo';

  /// Latest stable release, or null if none has been published yet (a fresh
  /// repo/pre-v1 project) — `/releases/latest` 404s in that case rather than
  /// erroring, so that's treated as "nothing to offer" not a failure.
  static Future<AppRelease?> latestStable() async {
    final res = await http.get(Uri.parse('$_apiBase/releases/latest'));
    if (res.statusCode == 404) return null;
    if (res.statusCode != 200) throw GitHubReleasesException(res.statusCode);
    return AppRelease.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  /// Most recent nightly prerelease (tag prefixed `nightly-`). Nightlies
  /// publish daily, so the newest is always well within the first page —
  /// no pagination needed.
  static Future<AppRelease?> latestNightly() async {
    final res = await http.get(Uri.parse('$_apiBase/releases?per_page=30'));
    if (res.statusCode != 200) throw GitHubReleasesException(res.statusCode);
    final list = (jsonDecode(res.body) as List).cast<Map<String, dynamic>>();
    for (final json in list) {
      if ((json['tag_name'] as String?)?.startsWith('nightly-') ?? false) {
        return AppRelease.fromJson(json);
      }
    }
    return null;
  }

  static Future<AppRelease?> latest(UpdateChannel channel) => switch (channel) {
        UpdateChannel.stable => latestStable(),
        UpdateChannel.nightly => latestNightly(),
      };
}

class GitHubReleasesException implements Exception {
  GitHubReleasesException(this.statusCode);
  final int statusCode;

  @override
  String toString() => 'GitHub Releases request failed: HTTP $statusCode';
}

/// `v1.2.10` → `[1, 2, 10]`; a bare `1.2.10` (no `v` prefix) also parses.
/// Non-numeric/malformed tags parse as `[0, 0, 0]` so they never look newer.
List<int> _parseSemver(String tag) {
  final stripped = tag.startsWith('v') ? tag.substring(1) : tag;
  final parts = stripped.split('.');
  if (parts.length < 3) return const [0, 0, 0];
  return [
    for (final p in parts.take(3)) int.tryParse(p) ?? 0,
  ];
}

/// True if stable release [remote] is newer than the running app's
/// `PackageInfo.version` ([currentVersion], e.g. `"1.0.0"`).
bool isStableReleaseNewer(AppRelease remote, String currentVersion) {
  final a = _parseSemver(remote.tag);
  final b = _parseSemver(currentVersion);
  for (var i = 0; i < 3; i++) {
    if (a[i] != b[i]) return a[i] > b[i];
  }
  return false;
}

/// True if nightly release [remote] is newer than [installedTag] (the tag
/// this app last self-installed via the nightly channel, persisted by
/// `AppUpdateController`; null if never installed one this way — any
/// nightly counts as newer then). `nightly-YYYYMMDD`'s fixed-width date
/// suffix sorts correctly with a plain string compare.
bool isNightlyReleaseNewer(AppRelease remote, String? installedTag) {
  if (installedTag == null) return true;
  return remote.tag.compareTo(installedTag) > 0;
}
