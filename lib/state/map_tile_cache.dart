import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yandex_maps_mapkit/mapkit.dart' as ymk;
import 'package:yandex_maps_mapkit/mapkit_factory.dart' show mapkit;

enum MapTileCacheStatus { idle, computing, clearing, error }

class MapTileCacheState {
  const MapTileCacheState({this.status = MapTileCacheStatus.idle, this.sizeBytes});

  final MapTileCacheStatus status;

  /// Bytes on disk as of the last successful [MapTileCacheController.refresh]
  /// — stale (not yet cleared to null/0) while a fresh [refresh]/[clear] is
  /// in flight, so a re-render mid-request doesn't flash the size away.
  final int? sizeBytes;

  MapTileCacheState copyWith({MapTileCacheStatus? status, int? sizeBytes}) => MapTileCacheState(
        status: status ?? this.status,
        sizeBytes: sizeBytes ?? this.sizeBytes,
      );
}

/// Wraps `mapkit.storageManager` — Yandex MapKit's own on-disk cache of
/// previously-shown map tiles (Route/Route Preview/Dash all render through
/// it; nothing in this app configures it, it's purely the SDK's behavior).
/// Exposes just the size + a manual "clear everything" action, per
/// spec/settings_screen.md's "Кеш карты" card — no UI for
/// `setMaxTileStorageSize`'s cache-size limit (SDK supports it, not wired
/// up here). Not to be confused with `mapkit.offlineCacheManager`
/// (pre-downloaded offline map regions — a separate, paid-tier feature).
class MapTileCacheController extends Notifier<MapTileCacheState> {
  @override
  MapTileCacheState build() => const MapTileCacheState();

  /// Callers drive this explicitly (e.g. `SettingsScreen.initState`) rather
  /// than it firing from [build] — matches how `_SettingsScreenState`
  /// already reloads dash config/notification access on demand instead of
  /// baking the reload into provider construction.
  Future<void> refresh() async {
    state = state.copyWith(status: MapTileCacheStatus.computing);
    final bytes = await _computeSize();
    if (!ref.mounted) return;
    state = bytes == null
        ? state.copyWith(status: MapTileCacheStatus.error)
        : MapTileCacheState(status: MapTileCacheStatus.idle, sizeBytes: bytes);
  }

  Future<void> clear() async {
    state = state.copyWith(status: MapTileCacheStatus.clearing);
    final completer = Completer<void>();
    mapkit.storageManager.clear(ymk.StorageManagerClearListener(onClearCompleted: completer.complete));
    await completer.future;
    if (!ref.mounted) return;
    await refresh();
  }

  /// Null on either an explicit SDK error or a "successful" `null` byte
  /// count (the SDK's own signature allows that; treated the same as
  /// "couldn't determine size" — nothing in the UI needs to tell those
  /// apart).
  Future<int?> _computeSize() async {
    final completer = Completer<int?>();
    mapkit.storageManager.computeSize(ymk.StorageManagerSizeListener(
      onSuccess: completer.complete,
      onError: (_) => completer.complete(null),
    ));
    return completer.future;
  }
}

final mapTileCacheProvider = NotifierProvider<MapTileCacheController, MapTileCacheState>(MapTileCacheController.new);
