import 'package:flutter/services.dart';

import '../models/offline_map.dart';

/// How a download ended, mirroring `MapPackDownloader.Outcome` on the Kotlin
/// side. The names matter: they cross the channel as strings.
enum PackOutcome {
  /// Verified and renamed into place — the pack is usable.
  installed,

  /// sha256 didn't match. **Not necessarily corruption**: the corpus may have
  /// been rebuilt mid-download, so the caller re-reads the manifest before
  /// deciding (spec/remote_map_server.md, "Скачивание").
  checksumMismatch,

  /// `ERROR_CANNOT_RESUME` — resume hit a 412 because the pack changed on the
  /// server. A conflict, not a failure: re-read the manifest and start over.
  conflict,

  /// Transfer failed for an ordinary reason (network, storage, 404).
  failed,

  /// The download row vanished — cancelled here or cleared from the system UI.
  cancelled,
}

/// Terminal report for one pack, produced by [MapPackDownloader.reconcile].
class PackDownloadResult {
  const PackDownloadResult({
    required this.code,
    required this.outcome,
    this.sha256,
    this.generatedAt,
    this.sizeBytes,
    this.detail,
  });

  final String code;
  final PackOutcome outcome;

  /// Present only on [PackOutcome.installed] — the verified hash and the
  /// manifest it came from, ready to go straight into the registry.
  final String? sha256;
  final String? generatedAt;
  final int? sizeBytes;

  /// Free-form context for the failure paths (the DownloadManager reason code,
  /// the hash we actually got) — for logs, not for the interface.
  final String? detail;

  InstalledPack toInstalledPack() => InstalledPack(
        code: code,
        sha256: sha256 ?? '',
        generatedAt: generatedAt ?? '',
        sizeBytes: sizeBytes ?? 0,
        installedAtMs: DateTime.now().millisecondsSinceEpoch,
      );
}

/// Bytes transferred so far for a pack still in flight.
class PackProgress {
  const PackProgress({required this.code, required this.bytesSoFar, required this.totalBytes});

  final String code;
  final int bytesSoFar;

  /// Falls back to the manifest size until the server's `Content-Length`
  /// arrives, so a progress bar always has something to grow against.
  final int totalBytes;

  double? get fraction => totalBytes > 0 ? (bytesSoFar / totalBytes).clamp(0.0, 1.0) : null;
}

/// Dart side of the `ru.snatchdash.app/maps` channel — the system
/// `DownloadManager` wrapped by `MapPackDownloader.kt`.
///
/// Deliberately thin: everything that must survive the app being killed
/// (the transfer itself, resume, retries) lives on the platform side. This
/// class only starts, polls, and collects results.
class MapPackDownloader {
  MapPackDownloader({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('ru.snatchdash.app/maps');

  final MethodChannel _channel;

  /// `getExternalFilesDir(null)/maps` — the same directory the render engine
  /// enumerates. Exposed mainly so logs can name it.
  Future<String?> mapsDir() => _channel.invokeMethod<String>('mapsDir');

  /// Whether the volume can take [bytes]. Asked **before** starting: the
  /// manifest gives the size up front, so running out at 90% of 356 MB is a
  /// failure we can simply decline to have.
  Future<bool> hasRoomFor(int bytes) async =>
      await _channel.invokeMethod<bool>('hasRoomFor', {'bytes': bytes}) ?? false;

  /// Enqueues [region]. [title] is what the system download notification
  /// shows, so it should be the localised pack name rather than the code.
  ///
  /// Throws [PlatformException] with code `ENQUEUE_FAILED` when the system
  /// downloader is disabled on the device — a state to show, not a crash.
  Future<void> start(
    MapRegion region, {
    required Uri url,
    required String generatedAt,
    required String title,
  }) =>
      _channel.invokeMethod<int>('start', {
        'code': region.code,
        'url': url.toString(),
        'sha256': region.sha256,
        'sizeBytes': region.sizeBytes,
        'generatedAt': generatedAt,
        'title': title,
      });

  Future<void> cancel(String code) => _channel.invokeMethod<void>('cancel', {'code': code});

  /// Deletes an installed pack file. Returns false if there was nothing there.
  Future<bool> delete(String code) async =>
      await _channel.invokeMethod<bool>('delete', {'code': code}) ?? false;

  /// Progress of everything in flight. There are no callbacks from
  /// `DownloadManager`, so the screen polls this while it is open.
  Future<List<PackProgress>> progress() async {
    final rows = await _channel.invokeListMethod<Map<Object?, Object?>>('progress') ?? const [];
    return rows
        .map((row) => PackProgress(
              code: row['code'] as String,
              bytesSoFar: (row['bytesSoFar'] as num?)?.toInt() ?? 0,
              totalBytes: (row['totalBytes'] as num?)?.toInt() ?? 0,
            ))
        .toList();
  }

  /// Finishes every download the system has completed since the last call:
  /// verifies sha256 and renames into place.
  ///
  /// Slow by nature — hashing a large pack takes seconds — but it runs on a
  /// background thread over there. Call it when the screen opens and after a
  /// download completes; anything that finished while the app was closed is
  /// picked up then.
  Future<List<PackDownloadResult>> reconcile() async {
    final rows = await _channel.invokeListMethod<Map<Object?, Object?>>('reconcile') ?? const [];
    return rows.map(_resultFrom).toList();
  }

  /// Packs present on disk under their final name — what the engine will see.
  /// Used to spot a registry that has drifted from reality.
  Future<Map<String, int>> installedFiles() async {
    final rows = await _channel.invokeListMethod<Map<Object?, Object?>>('installedFiles') ?? const [];
    return {
      for (final row in rows) row['code'] as String: (row['sizeBytes'] as num?)?.toInt() ?? 0,
    };
  }

  static PackDownloadResult _resultFrom(Map<Object?, Object?> row) => PackDownloadResult(
        code: row['code'] as String,
        outcome: _outcomeFrom(row['outcome'] as String?),
        sha256: row['sha256'] as String?,
        generatedAt: row['generatedAt'] as String?,
        sizeBytes: (row['sizeBytes'] as num?)?.toInt(),
        detail: row['detail'] as String?,
      );

  static PackOutcome _outcomeFrom(String? name) => switch (name) {
        'INSTALLED' => PackOutcome.installed,
        'CHECKSUM_MISMATCH' => PackOutcome.checksumMismatch,
        'CONFLICT' => PackOutcome.conflict,
        'CANCELLED' => PackOutcome.cancelled,
        _ => PackOutcome.failed,
      };
}
