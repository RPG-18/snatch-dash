import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../models/offline_map.dart';

/// Live tile server. Packs are addressed by the manifest's relative `path`
/// joined onto this — see spec/remote_map_server.md, "Раскладка на сервере".
const kMapServerBaseUrl = 'https://storage.yandexcloud.net/snatch-dash-maps/';

/// Manifest schema version this build understands.
const kSupportedManifestVersion = 1;

/// The manifest declares a schema newer than this build can read.
///
/// Deliberately fatal rather than best-effort: parsing an unknown schema "as
/// far as it goes" risks acting on fields that changed meaning. The rider is
/// told to update the app, and installed packs are left alone.
class ManifestVersionUnsupported implements Exception {
  const ManifestVersionUnsupported(this.found, this.supported);
  final int found;
  final int supported;

  @override
  String toString() => 'Manifest schema v$found is newer than supported v$supported';
}

/// The manifest could not be read as the shape we expect.
class ManifestMalformed implements Exception {
  const ManifestMalformed(this.reason);
  final String reason;

  @override
  String toString() => 'Malformed manifest: $reason';
}

/// Reads `index.json` from the tile server and from the on-disk copy kept
/// beside the packs.
///
/// The local copy is what makes the offline-maps screen usable without a
/// network: the list of available packs comes from it, and only the freshness
/// check needs the server (see spec/offline_maps_screen.md, "Пустые состояния").
class MapManifestApi {
  MapManifestApi({http.Client? client, Directory? mapsDir})
      : _client = client ?? http.Client(),
        _mapsDirOverride = mapsDir;

  final http.Client _client;
  final Directory? _mapsDirOverride;

  static const _fileName = 'index.json';

  /// `getExternalFilesDir(null)/maps` — the directory `DownloadManager` writes
  /// into and the engine enumerates. `path_provider`'s
  /// `getExternalStorageDirectory()` maps to `getExternalFilesDir(null)` on
  /// Android, so Dart and Kotlin agree on the location without a channel.
  Future<Directory> mapsDir() async {
    final override = _mapsDirOverride;
    if (override != null) return override;
    final external = await getExternalStorageDirectory();
    if (external == null) {
      throw const FileSystemException('External app storage is unavailable');
    }
    final dir = Directory(p.join(external.path, 'maps'));
    if (!dir.existsSync()) dir.createSync(recursive: true);
    return dir;
  }

  Uri packUrl(String relativePath) => Uri.parse(kMapServerBaseUrl).resolve(relativePath);

  Uri get manifestUrl => Uri.parse(kMapServerBaseUrl).resolve(_fileName);

  /// Downloads and parses the manifest, and — only once it parsed — replaces
  /// the local copy. A malformed or too-new manifest therefore never destroys
  /// a good one already on disk.
  Future<MapManifest> fetchRemote() async {
    final response = await _client.get(manifestUrl);
    if (response.statusCode != 200) {
      throw HttpException('HTTP ${response.statusCode}', uri: manifestUrl);
    }
    final raw = utf8.decode(response.bodyBytes);
    final manifest = parse(raw);
    await _saveLocal(raw);
    return manifest;
  }

  /// The on-disk copy, or null when there is none. A copy that fails to parse
  /// is treated as absent — it is a cache, and a corrupt cache should not be a
  /// dead end.
  Future<MapManifest?> readLocal() async {
    try {
      // Inside the try, not before it: resolving the directory is itself IO and
      // can throw (an unmounted volume; anything non-Android, where
      // getExternalStorageDirectory is unsupported). Outside, that throw left
      // the only caller — the controller's bootstrap microtask — with nothing to
      // catch it, and the startup reconcile died with it.
      final file = File(p.join((await mapsDir()).path, _fileName));
      if (!file.existsSync()) return null;
      return parse(await file.readAsString());
    } on ManifestVersionUnsupported {
      rethrow; // an app-too-old manifest is a real answer, not a broken cache
    } catch (_) {
      return null;
    }
  }

  /// Writes via a temporary file and renames.
  ///
  /// In place, a write cut short — process killed, storage full — would leave a
  /// truncated `index.json` where a perfectly good one used to be. Parsing
  /// failures are already survivable ([readLocal] treats them as "no cache"),
  /// but only because the file that fails to parse was never the last good copy.
  Future<void> _saveLocal(String raw) async {
    final dir = (await mapsDir()).path;
    // A name of its own per write. Two refreshes can overlap — the bootstrap one
    // against the retry button, or against the checksum-mismatch path that
    // re-reads the manifest — and with one fixed `.tmp` the second writer's
    // half-written file is what the first one renames into place, destroying the
    // very cache this method exists to protect.
    final tmp = File(p.join(dir, '$_fileName.${DateTime.now().microsecondsSinceEpoch}.tmp'));
    try {
      await tmp.writeAsString(raw, flush: true);
      await tmp.rename(p.join(dir, _fileName));
    } catch (_) {
      // Leaving a stray tmp behind would be picked up by nothing — `upload.sh`
      // excludes them on the server, but here they would just accumulate.
      await tmp.delete().catchError((Object _) => tmp);
      rethrow;
    }
  }

  /// Pure parse — no IO, so the contract below is directly testable.
  ///
  /// Unknown fields are ignored on purpose: the schema version only rises on
  /// breaking changes, so a new optional field must not break installed apps.
  static MapManifest parse(String raw) {
    final Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } on FormatException catch (e) {
      throw ManifestMalformed('not JSON: ${e.message}');
    }
    if (decoded is! Map<String, dynamic>) throw const ManifestMalformed('root is not an object');

    final version = decoded['version'];
    if (version is! int) throw const ManifestMalformed('missing "version"');
    if (version > kSupportedManifestVersion) {
      throw ManifestVersionUnsupported(version, kSupportedManifestVersion);
    }

    final countries = decoded['countries'];
    if (countries is! List) throw const ManifestMalformed('missing "countries"');

    final regions = <MapRegion>[];
    for (final country in countries) {
      if (country is! Map<String, dynamic>) continue;
      final list = country['regions'];
      if (list is! List) continue;
      for (final region in list) {
        if (region is! Map<String, dynamic>) continue;
        final code = region['code'];
        final path = region['path'];
        final size = region['size'];
        final sha256 = region['sha256'];
        if (code is! String || path is! String || size is! num || sha256 is! String) {
          throw ManifestMalformed('bad region entry: $region');
        }
        regions.add(MapRegion(
          code: code,
          path: path,
          sizeBytes: size.toInt(),
          sha256: sha256,
        ));
      }
    }

    return MapManifest(
      schemaVersion: version,
      generatedAt: decoded['generated_at'] as String? ?? '',
      fileCount: (decoded['file_count'] as num?)?.toInt() ?? regions.length,
      totalSizeBytes: (decoded['total_size'] as num?)?.toInt() ??
          regions.fold(0, (sum, r) => sum + r.sizeBytes),
      regions: regions,
    );
  }
}
