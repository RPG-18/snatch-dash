import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

/// Connection stage mirrored from the native `DashState` enum
/// (`DashEngineController.publishState`'s "stage" key).
enum DashStage { idle, connecting, authenticating, ready, streaming, error }

DashStage _stageFrom(String? name) => switch (name) {
      'CONNECTING' => DashStage.connecting,
      'AUTHENTICATING' => DashStage.authenticating,
      'READY' => DashStage.ready,
      'STREAMING' => DashStage.streaming,
      'ERROR' => DashStage.error,
      _ => DashStage.idle,
    };

/// Typed snapshot of the native dash engine's live state stream.
class DashEngineState {
  const DashEngineState({
    this.stage = DashStage.idle,
    this.wifiStatus,
    this.wifiSsid,
    this.wifiError,
    this.hasGps = false,
    this.riderLat,
    this.riderLng,
    this.riderBearing,
    this.remainingKm,
    this.offRoute = false,
    this.gpsLost = false,
    this.gpsWeak = false,
    this.errorMessage,
    this.followMode = true,
    this.headingUp = false,
    this.nowPlayingTitle,
    this.incomingCaller,
  });

  final DashStage stage;
  final String? wifiStatus;
  final String? wifiSsid;
  final String? wifiError;
  final bool hasGps;
  final double? riderLat;
  final double? riderLng;
  final double? riderBearing;
  final double? remainingKm;
  final bool offRoute;
  final bool gpsLost;
  final bool gpsWeak;
  final String? errorMessage;
  final bool followMode;
  final bool headingUp;
  final String? nowPlayingTitle;
  final String? incomingCaller;

  factory DashEngineState.fromMap(Map<String, dynamic> map) => DashEngineState(
        stage: _stageFrom(map['stage'] as String?),
        wifiStatus: map['wifiStatus'] as String?,
        wifiSsid: map['wifiSsid'] as String?,
        wifiError: map['wifiError'] as String?,
        hasGps: map['hasGps'] as bool? ?? false,
        riderLat: (map['riderLat'] as num?)?.toDouble(),
        riderLng: (map['riderLng'] as num?)?.toDouble(),
        riderBearing: (map['riderBearing'] as num?)?.toDouble(),
        remainingKm: (map['remainingKm'] as num?)?.toDouble(),
        offRoute: map['offRoute'] as bool? ?? false,
        gpsLost: map['gpsLost'] as bool? ?? false,
        gpsWeak: map['gpsWeak'] as bool? ?? false,
        errorMessage: map['errorMessage'] as String?,
        followMode: map['followMode'] as bool? ?? true,
        headingUp: map['headingUp'] as bool? ?? false,
        nowPlayingTitle: map['nowPlayingTitle'] as String?,
        incomingCaller: map['incomingCaller'] as String?,
      );
}

/// Raw event map stream from the native engine (state updates + button events).
final dashEngineRawStreamProvider = StreamProvider<Map<String, dynamic>>((ref) {
  return DashEngine.instance.stateStream;
});

/// Typed, defaulted view of the latest engine state — safe to read before the
/// first event arrives (e.g. before `connect()` is ever called).
///
/// A plain `Provider` that both watched the raw stream *and* wrote its
/// derived value into a second provider used to live here, but Riverpod 3
/// forbids a provider from mutating another provider while it's still
/// building (`ref.watch` + `ref.read(...).state = ...` in the same `create`
/// callback) — it throws `Providers are not allowed to modify other
/// providers during their initialization`. A `Notifier` sidesteps that: it
/// keeps the last-known-good state as its own field and only updates via
/// `ref.listen`'s callback, which runs after the initial build, not during it.
class DashEngineStateNotifier extends Notifier<DashEngineState> {
  @override
  DashEngineState build() {
    ref.listen(dashEngineRawStreamProvider, (previous, next) {
      final raw = next.value;
      if (raw == null || raw.containsKey('button')) return;
      state = DashEngineState.fromMap(raw);
    });
    return const DashEngineState();
  }
}

final dashEngineStateProvider =
    NotifierProvider<DashEngineStateNotifier, DashEngineState>(DashEngineStateNotifier.new);
