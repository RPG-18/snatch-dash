import 'geo_point.dart';

/// Maneuver glyphs the dash understands. Only CONTINUE (0x0B) is
/// hardware-verified against fw 11.63 in the original project — every other
/// glyph code is unmapped, so [Maneuver.dashCode] always returns CONTINUE.
/// Ported from `dash/nav/Route.kt`.
enum ManeuverType { continue_, arrive }

/// One routing instruction located at a point along the geometry.
class Maneuver {
  const Maneuver({
    required this.type,
    required this.instruction,
    required this.location,
    required this.cumulativeMeters,
  });

  final ManeuverType type;
  final String instruction;
  final GeoPoint location;

  /// Cumulative distance (m) from the route start to this maneuver's location.
  final double cumulativeMeters;

  /// Dash maneuver glyph byte. CONTINUE (0x0B) is the only verified value —
  /// see the project's on-hardware verification checklist.
  int get dashCode => 0x0B;
}

/// Traffic intensity for a route geometry segment. Mirrors the Yandex
/// MapKit SDK's `JamType` without depending on it, so this stays a plain
/// domain model — see `nav/router.dart` for the SDK-facing conversion and
/// `spec/yande_ruote.md` for how this is drawn.
enum JamLevel { unknown, blocked, free, light, hard, veryHard }

/// A computed road route from origin to destination.
class Route {
  const Route({
    required this.geometry,
    required this.maneuvers,
    required this.totalMeters,
    required this.totalSeconds,
    required this.cumulative,
    this.jamSegments = const [],
  });

  final List<GeoPoint> geometry;
  final List<Maneuver> maneuvers;
  final double totalMeters;
  final double totalSeconds;

  /// Cumulative distance (m) at each geometry vertex — same length as [geometry].
  final List<double> cumulative;

  /// Traffic intensity per geometry segment — index i covers geometry[i] to
  /// geometry[i+1], so this has `geometry.length - 1` entries when present.
  /// Empty when traffic data wasn't available for this route.
  final List<JamLevel> jamSegments;

  GeoPoint? get destination => geometry.isEmpty ? null : geometry.last;
}
