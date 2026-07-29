import 'geo_point.dart';
import 'route.dart';

/// Rider's progress along a [Route]: where they are on the line, distance to
/// the next maneuver, remaining distance, ETA, and off-route state. Pure —
/// call [progress] with the latest GPS fix. Ported from `dash/nav/NavEngine.kt`.
class NavProgress {
  const NavProgress({
    required this.snapped,
    required this.routeBearing,
    required this.distanceToManeuverM,
    required this.nextManeuver,
    required this.remainingMeters,
    required this.etaSeconds,
    required this.offRoute,
    required this.arrived,
  });

  final GeoPoint snapped;
  final double routeBearing;
  final double distanceToManeuverM;
  final Maneuver? nextManeuver;
  final double remainingMeters;
  final double etaSeconds;
  final bool offRoute;
  final bool arrived;
}

class NavEngine {
  NavEngine._();

  static const _offRouteM = 60.0;
  static const _arriveM = 25.0;
  static const _defaultSpeedMps = 11.0; // ~40 km/h fallback

  /// [speedMps] recent GPS speed; <=0 falls back to a default for ETA.
  static NavProgress progress(Route route, GeoPoint pos, double speedMps) {
    final geom = route.geometry;

    var bestDist = double.maxFinite;
    var bestSnap = geom.first;
    var bestCum = 0.0;
    var bestBearing = 0.0;
    for (var i = 0; i < geom.length - 1; i++) {
      final a = geom[i];
      final b = geom[i + 1];
      final (proj, t) = GeoPoint.projectOnSegment(pos, a, b);
      final d = GeoPoint.distMeters(pos, proj);
      if (d < bestDist) {
        bestDist = d;
        bestSnap = proj;
        final segLen = GeoPoint.distMeters(a, b);
        bestCum = route.cumulative[i] + segLen * t;
        bestBearing = GeoPoint.bearing(a, b);
      }
    }

    final remaining = (route.totalMeters - bestCum).clamp(0.0, double.maxFinite);
    final arrived = remaining <= _arriveM;
    final offRoute = bestDist > _offRouteM;

    final next = route.maneuvers.cast<Maneuver?>().firstWhere(
          (m) => m != null && m.cumulativeMeters > bestCum + 1.0,
          orElse: () => null,
        );
    final distToManeuver =
        next != null ? (next.cumulativeMeters - bestCum).clamp(0.0, double.maxFinite) : remaining;

    final speed = speedMps > 0.5 ? speedMps : _defaultSpeedMps;
    final eta = remaining / speed;

    return NavProgress(
      snapped: bestSnap,
      routeBearing: bestBearing,
      distanceToManeuverM: distToManeuver,
      nextManeuver: next,
      remainingMeters: remaining,
      etaSeconds: eta,
      offRoute: offRoute,
      arrived: arrived,
    );
  }
}
