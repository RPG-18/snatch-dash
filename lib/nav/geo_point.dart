import 'dart:math';

/// A WGS84 coordinate plus geo helpers used by the routing/nav engine.
/// Ported from the original app's `dash/nav/GeoPoint.kt`.
class GeoPoint {
  const GeoPoint(this.lat, this.lng);

  final double lat;
  final double lng;

  static const _earthKm = 6371.0;

  /// Great-circle distance in metres.
  static double distMeters(GeoPoint a, GeoPoint b) {
    final dLat = _toRad(b.lat - a.lat);
    final dLng = _toRad(b.lng - a.lng);
    final s = sin(dLat / 2) * sin(dLat / 2) +
        cos(_toRad(a.lat)) * cos(_toRad(b.lat)) * sin(dLng / 2) * sin(dLng / 2);
    return 2 * _earthKm * 1000.0 * atan2(sqrt(s), sqrt(1 - s));
  }

  /// Initial bearing a→b in degrees [0,360).
  static double bearing(GeoPoint a, GeoPoint b) {
    final lat1 = _toRad(a.lat);
    final lat2 = _toRad(b.lat);
    final dLng = _toRad(b.lng - a.lng);
    final y = sin(dLng) * cos(lat2);
    final x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng);
    return (_toDeg(atan2(y, x)) + 360.0) % 360.0;
  }

  /// Nearest point to [p] on the segment [a]→[b], plus the fraction t∈[0,1]
  /// along the segment. Uses a local equirectangular approximation — fine at
  /// the segment scale of a road.
  static (GeoPoint, double) projectOnSegment(GeoPoint p, GeoPoint a, GeoPoint b) {
    final latRef = _toRad(a.lat);
    double x(GeoPoint g) => _toRad(g.lng) * cos(latRef);
    double y(GeoPoint g) => _toRad(g.lat);
    final ax = x(a), ay = y(a);
    final bx = x(b), by = y(b);
    final px = x(p), py = y(p);
    final dx = bx - ax, dy = by - ay;
    final len2 = dx * dx + dy * dy;
    final t = len2 == 0.0
        ? 0.0
        : (((px - ax) * dx + (py - ay) * dy) / len2).clamp(0.0, 1.0);
    final proj = GeoPoint(
      a.lat + (b.lat - a.lat) * t,
      a.lng + (b.lng - a.lng) * t,
    );
    return (proj, t);
  }

  static double _toRad(double deg) => deg * pi / 180.0;
  static double _toDeg(double rad) => rad * 180.0 / pi;

  @override
  String toString() => 'GeoPoint($lat, $lng)';

  @override
  bool operator ==(Object other) =>
      other is GeoPoint && other.lat == lat && other.lng == lng;

  @override
  int get hashCode => Object.hash(lat, lng);
}
