import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';

export 'package:opendash_dash_engine/opendash_dash_engine.dart'
    show DashEngineState, DashStage, WifiStatus;

/// What the engine looks like before it has said anything.
///
/// **Defaults live here and nowhere else now.** There used to be a hand-written
/// `DashEngineState` in this file with a `fromMap` that applied its own defaults
/// while `DashEngineController.publishState` built the map on the other side. Two
/// lists of field names, maintained independently, checked by nothing — a key
/// renamed on one side arrived as null on the other and read as "no GPS" or "not
/// navigating". Since 2026-09-30 both sides are generated from
/// `pigeons/dash_engine.dart`, so the only thing left to decide is what is true
/// before the first update.
DashEngineState get idleDashState => DashEngineState(
  stage: DashStage.idle,
  explicitDisconnect: false,
  wifiStatus: WifiStatus.idle,
  navigating: false,
  hasGps: false,
  offRoute: false,
  gpsLost: false,
  gpsWeak: false,
  // True, like the engine's own camera: the dash follows the rider and turns
  // with them until someone says otherwise.
  followMode: true,
  headingUp: true,
  zoom: 0,
  hasActiveCall: false,
);

/// The engine's live state, straight from the native side.
final dashEngineRawStreamProvider = StreamProvider<DashEngineState>((ref) {
  return DashEngine.instance.stateStream;
});

// Deliberately NO provider for the button stream. A `StreamProvider` compares
// the value it holds, and Pigeon gives its classes value equality, so two
// presses of the same button would arrive as one — see `DashButtonController`,
// which subscribes to `DashEngine.instance.buttonStream` itself.

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
      if (raw == null) return;
      state = raw;
    });
    return idleDashState;
  }
}

final dashEngineStateProvider =
    NotifierProvider<DashEngineStateNotifier, DashEngineState>(
      DashEngineStateNotifier.new,
    );
