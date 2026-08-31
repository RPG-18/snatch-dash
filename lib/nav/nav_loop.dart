import 'dart:async';

import 'package:opendash_dash_engine/opendash_dash_engine.dart';

import 'geo_point.dart';
import 'nav_engine.dart';
import 'route.dart';
import 'router.dart';
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
///
/// Also owns off-route reroute: sustained deviation triggers a fresh
/// [Router.route] call to the same destination, throttled/backed off so a
/// long detour doesn't spam the routing API. See
/// `spec/route_restructuring.md` for the chosen thresholds and rationale.
class NavLoop {
  NavLoop(this._route, {this.onRerouted});

  Route _route;

  /// Notified with the new route whenever a reroute succeeds, so callers
  /// (`RouteController`) can keep their own copy — used for the in-app map
  /// preview — in sync with whatever actually went to the dash.
  final void Function(Route)? onRerouted;

  static const _offRouteDebounceTicks = 5; // ~5s at the 1Hz tick rate
  static const _rerouteCooldown = Duration(seconds: 15);
  static const _rerouteBackoffCap = Duration(seconds: 60);
  static const _maxBackoffShift = 4; // 15s * 2^4 already clears the cap

  StreamSubscription<Map<String, dynamic>>? _sub;
  Timer? _timer;
  double? _lat;
  double? _lng;

  /// Last known ground speed (m/s) from the engine's GPS fixes, fed into
  /// [NavEngine.progress] for the ETA. Held across events like [_lat]/[_lng]
  /// so a momentary null doesn't throw the estimate back to the default.
  double _speedMps = 0;
  bool _stopped = false;

  int _offRouteTicks = 0;
  bool _rerouting = false;
  DateTime? _lastRerouteAttempt;
  int _consecutiveFailures = 0;

  void start() {
    VoiceManager.instance.resetTrip();
    DashEngine.instance.setNavState(
      remainingMeters: _route.totalMeters,
      nextTurnMeters: _route.totalMeters,
      offRoute: false,
      points: _route.geometry.map((p) => [p.lat, p.lng]).toList(),
      jamSegments: _route.jamSegments.map((j) => j.index).toList(),
    );
    _sub = DashEngine.instance.stateStream.listen(_onEngineEvent);
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
  }

  void stop() {
    _stopped = true;
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
    final speed = (event['riderSpeed'] as num?)?.toDouble();
    if (speed != null) _speedMps = speed;
  }

  void _tick() {
    final lat = _lat;
    final lng = _lng;
    if (lat == null || lng == null) return;

    final pos = GeoPoint(lat, lng);
    final progress = NavEngine.progress(_route, pos, _speedMps);
    final eta = DateTime.now().add(Duration(seconds: progress.etaSeconds.round()));
    final etaHHMM =
        '${eta.hour.toString().padLeft(2, '0')}${eta.minute.toString().padLeft(2, '0')}';

    DashEngine.instance.setNavState(
      remainingMeters: progress.remainingMeters,
      nextTurnMeters: progress.distanceToManeuverM,
      // 0x09 = straight ahead, the dash's confirmed neutral glyph — see the note on
      // `_dashCodeByType` in nav/route.dart for why it is NOT 0x0B (which the firmware
      // renders as "roundabout, clockwise, exit 1").
      maneuver: progress.nextManeuver?.dashCode ?? 0x09,
      etaHHMM: etaHHMM,
      offRoute: progress.offRoute,
      points: const [],
    );

    unawaited(VoiceManager.instance.maybeAnnounce(
      progress.nextManeuver,
      progress.distanceToManeuverM,
      progress.remainingMeters,
    ));

    _handleOffRoute(progress.offRoute, pos);
  }

  void _handleOffRoute(bool offRoute, GeoPoint pos) {
    if (!offRoute) {
      _offRouteTicks = 0;
      return;
    }
    _offRouteTicks++;
    if (_offRouteTicks < _offRouteDebounceTicks || _rerouting) return;

    final last = _lastRerouteAttempt;
    if (last != null && DateTime.now().difference(last) < _nextAllowedWait()) return;

    unawaited(_reroute(pos));
  }

  Duration _nextAllowedWait() {
    final shift = _consecutiveFailures > _maxBackoffShift ? _maxBackoffShift : _consecutiveFailures;
    final wait = _rerouteCooldown * (1 << shift);
    return wait > _rerouteBackoffCap ? _rerouteBackoffCap : wait;
  }

  Future<void> _reroute(GeoPoint pos) async {
    final destination = _route.destination;
    if (destination == null) return;

    _rerouting = true;
    _lastRerouteAttempt = DateTime.now();
    try {
      final newRoute = await Router.route(pos, destination);
      if (_stopped) return;
      if (newRoute == null) {
        _consecutiveFailures++;
        return;
      }

      _route = newRoute;
      _consecutiveFailures = 0;
      _offRouteTicks = 0;
      onRerouted?.call(newRoute);
      VoiceManager.instance.resetTrip();
      DashEngine.instance.setNavState(
        remainingMeters: newRoute.totalMeters,
        nextTurnMeters: newRoute.totalMeters,
        offRoute: false,
        points: newRoute.geometry.map((p) => [p.lat, p.lng]).toList(),
        jamSegments: newRoute.jamSegments.map((j) => j.index).toList(),
      );
    } finally {
      _rerouting = false;
    }
  }
}
