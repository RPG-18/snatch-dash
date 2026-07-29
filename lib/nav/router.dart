import 'dart:async';

import 'package:yandex_maps_mapkit/directions.dart' as ymk;
import 'package:yandex_maps_mapkit/mapkit.dart' as ymk;

import 'geo_point.dart';
import 'route.dart';

/// Fetches a driving route via the official Yandex MapKit SDK's driving
/// router — replaces the original app's OSRM-demo-server `Router.kt`. This
/// is a genuine win from the switch to Yandex MapKit (the original TODO
/// wanted off the unsupported public OSRM demo server).
///
/// Known gap vs. the OSRM version: the driving route exposes only overall
/// geometry + aggregate weight (distance/time), not per-step turn
/// instructions — so [Route.maneuvers] here is just a single synthetic
/// ARRIVE at the destination. This is not a functional regression: the dash
/// glyph was already hardcoded to CONTINUE for every maneuver (unverified
/// glyph codes, see `Maneuver.dashCode`), so no turn-by-turn glyph fidelity
/// is lost — only the (already-unused) instruction text.
///
/// The official SDK's router is listener-based, not `Future`-based like the
/// old `yandex_mapkit` community plugin — [route] wraps
/// `DrivingSessionRouteListener` in a `Completer` so call sites keep using
/// plain `await`.
class Router {
  Router._();

  static final _router =
      ymk.DirectionsFactory.instance.createDrivingRouter(ymk.DrivingRouterType.Online);

  /// Keeps in-flight [ymk.DrivingSession]s reachable — see the comment in
  /// [routes] for why this is necessary.
  static final _pending = <ymk.DrivingSession>{};

  static const _jamLevelByType = {
    ymk.JamType.Unknown: JamLevel.unknown,
    ymk.JamType.Blocked: JamLevel.blocked,
    ymk.JamType.Free: JamLevel.free,
    ymk.JamType.Light: JamLevel.light,
    ymk.JamType.Hard: JamLevel.hard,
    ymk.JamType.VeryHard: JamLevel.veryHard,
  };

  static Future<Route?> route(GeoPoint from, GeoPoint to) async {
    final results = await routes(from, to);
    if (results == null || results.isEmpty) return null;
    return results.first;
  }

  /// Requests up to [routesCount] alternative routes — used by the route
  /// preview screen to let the rider pick among them. [route] above is a
  /// thin single-result wrapper around this.
  static Future<List<Route>?> routes(GeoPoint from, GeoPoint to, {int routesCount = 1}) async {
    final completer = Completer<List<ymk.DrivingRoute>>();
    // Kept alive in `_pending` for the request's duration: `DrivingSession`
    // implements `Finalizable`, so if nothing on the Dart side references it,
    // the GC can collect it (and cancel the native request) before either
    // callback fires — the request then hangs forever with no error.
    final session = _router.requestRoutes(
      ymk.DrivingOptions(routesCount: routesCount),
      const ymk.DrivingVehicleOptions(),
      ymk.DrivingSessionRouteListener(
        onDrivingRoutes: (routes) => completer.complete(routes),
        onDrivingRoutesError: (error) => completer.completeError(error),
      ),
      points: [
        ymk.RequestPoint(
          ymk.Point(latitude: from.lat, longitude: from.lng),
          ymk.RequestPointType.Waypoint,
          null,
          null,
          null,
        ),
        ymk.RequestPoint(
          ymk.Point(latitude: to.lat, longitude: to.lng),
          ymk.RequestPointType.Waypoint,
          null,
          null,
          null,
        ),
      ],
    );
    _pending.add(session);

    final List<ymk.DrivingRoute> raw;
    try {
      raw = await completer.future;
    } catch (_) {
      return null;
    } finally {
      _pending.remove(session);
    }
    if (raw.isEmpty) return null;
    return raw.map(_toRoute).whereType<Route>().toList();
  }

  static Route? _toRoute(ymk.DrivingRoute r) {
    final geometry = r.geometry.points.map((p) => GeoPoint(p.latitude, p.longitude)).toList();
    if (geometry.length < 2) return null;

    final cumulative = List<double>.filled(geometry.length, 0.0);
    for (var i = 1; i < geometry.length; i++) {
      cumulative[i] = cumulative[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i]);
    }

    final weight = r.metadata.weight;

    return Route(
      geometry: geometry,
      maneuvers: [
        Maneuver(
          type: ManeuverType.arrive,
          instruction: 'Arrive at destination',
          location: geometry.last,
          cumulativeMeters: weight.distance.value,
        ),
      ],
      totalMeters: weight.distance.value,
      totalSeconds: weight.timeWithTraffic.value,
      cumulative: cumulative,
      jamSegments: r.jamSegments
          .map((j) => _jamLevelByType[j.jamType] ?? JamLevel.unknown)
          .toList(),
    );
  }
}
