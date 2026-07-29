import 'dart:async';

import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import 'geo_point.dart';
import 'nav_engine.dart';
import 'route.dart';
import 'voice_manager.dart';

/// Drives the native dash engine's live nav-info while a route is active —
/// the Dart-side counterpart to the original `DashViewModel.tick`'s
/// route-progress half (the camera-smoothing/redraw half stays native, see
/// `DashEngineController.tick` on the Kotlin side).
///
/// Listens to the engine's GPS fixes (published by the native
/// `LocationTracker`) and, at ~1 Hz, recomputes [NavEngine.progress] and
/// pushes the result down via `setNavState`. The route geometry itself is
/// sent once on [start] — see the native-side note in
/// `DashEngineController.setNavState`.
class NavLoop {
  NavLoop(this.route);

  final Route route;

  StreamSubscription<Map<String, dynamic>>? _sub;
  Timer? _timer;
  double? _lat;
  double? _lng;

  void start() {
    VoiceManager.instance.resetTrip();
    DashEngine.instance.setNavState(
      remainingMeters: route.totalMeters,
      nextTurnMeters: route.totalMeters,
      offRoute: false,
      points: route.geometry.map((p) => [p.lat, p.lng]).toList(),
    );
    _sub = DashEngine.instance.stateStream.listen(_onEngineEvent);
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
  }

  void stop() {
    _sub?.cancel();
    _sub = null;
    _timer?.cancel();
    _timer = null;
  }

  void _onEngineEvent(Map<String, dynamic> event) {
    final lat = (event['riderLat'] as num?)?.toDouble();
    final lng = (event['riderLng'] as num?)?.toDouble();
    if (lat != null && lng != null) {
      _lat = lat;
      _lng = lng;
    }
  }

  void _tick() {
    final lat = _lat;
    final lng = _lng;
    if (lat == null || lng == null) return;

    final progress = NavEngine.progress(route, GeoPoint(lat, lng), 0);
    final eta = DateTime.now().add(Duration(seconds: progress.etaSeconds.round()));
    final etaHHMM =
        '${eta.hour.toString().padLeft(2, '0')}${eta.minute.toString().padLeft(2, '0')}';

    DashEngine.instance.setNavState(
      remainingMeters: progress.remainingMeters,
      nextTurnMeters: progress.distanceToManeuverM,
      maneuver: progress.nextManeuver?.dashCode ?? 0x0B,
      etaHHMM: etaHHMM,
      offRoute: progress.offRoute,
      points: const [],
    );

    unawaited(VoiceManager.instance.maybeAnnounce(
      progress.nextManeuver,
      progress.distanceToManeuverM,
      progress.remainingMeters,
    ));
  }
}
