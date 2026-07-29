import 'package:flutter_test/flutter_test.dart';
import 'package:snatch_dash/nav/geo_point.dart';
import 'package:snatch_dash/nav/nav_engine.dart';
import 'package:snatch_dash/nav/polyline_codec.dart';
import 'package:snatch_dash/nav/route.dart';

void main() {
  group('GeoPoint', () {
    test('distMeters is ~0 for identical points', () {
      const p = GeoPoint(12.9716, 77.5946);
      expect(GeoPoint.distMeters(p, p), closeTo(0, 0.001));
    });

    test('distMeters ~111km for one degree of latitude at the equator', () {
      const a = GeoPoint(0.0, 0.0);
      const b = GeoPoint(1.0, 0.0);
      expect(GeoPoint.distMeters(a, b), closeTo(111195, 500));
    });

    test('bearing due north is ~0 degrees', () {
      const a = GeoPoint(0.0, 0.0);
      const b = GeoPoint(1.0, 0.0);
      expect(GeoPoint.bearing(a, b), closeTo(0, 0.5));
    });

    test('projectOnSegment clamps to segment endpoints', () {
      const a = GeoPoint(0.0, 0.0);
      const b = GeoPoint(0.0, 1.0);
      const beyond = GeoPoint(0.0, 2.0);
      final (proj, t) = GeoPoint.projectOnSegment(beyond, a, b);
      expect(t, 1.0);
      expect(proj, b);
    });
  });

  group('PolylineCodec', () {
    test('encode/decode round-trips within precision', () {
      const points = [
        GeoPoint(38.5, -120.2),
        GeoPoint(40.7, -120.95),
        GeoPoint(43.252, -126.453),
      ];
      final encoded = PolylineCodec.encode(points);
      final decoded = PolylineCodec.decode(encoded);
      expect(decoded.length, points.length);
      for (var i = 0; i < points.length; i++) {
        expect(decoded[i].lat, closeTo(points[i].lat, 0.00001));
        expect(decoded[i].lng, closeTo(points[i].lng, 0.00001));
      }
    });

    test('decodes the standard Google polyline algorithm example', () {
      final decoded = PolylineCodec.decode('_p~iF~ps|U_ulLnnqC_mqNvxq`@');
      expect(decoded.length, 3);
      expect(decoded[0].lat, closeTo(38.5, 0.00001));
      expect(decoded[0].lng, closeTo(-120.2, 0.00001));
    });
  });

  group('NavEngine', () {
    test('progress snaps onto a straight route and computes remaining distance', () {
      final geometry = [
        const GeoPoint(0.0, 0.0),
        const GeoPoint(0.0, 0.01),
        const GeoPoint(0.0, 0.02),
      ];
      final cumulative = <double>[0.0];
      for (var i = 1; i < geometry.length; i++) {
        cumulative.add(cumulative.last + GeoPoint.distMeters(geometry[i - 1], geometry[i]));
      }
      final route = Route(
        geometry: geometry,
        maneuvers: [
          Maneuver(
            type: ManeuverType.arrive,
            instruction: 'Arrive',
            location: geometry.last,
            cumulativeMeters: cumulative.last,
          ),
        ],
        totalMeters: cumulative.last,
        totalSeconds: 60,
        cumulative: cumulative,
      );

      // Rider halfway along the first of two equal segments — a quarter of
      // the way along the route, so 3/4 of the total distance remains.
      final progress = NavEngine.progress(route, const GeoPoint(0.0, 0.005), 10.0);

      expect(progress.offRoute, isFalse);
      expect(progress.remainingMeters, closeTo(cumulative.last * 0.75, 50));
      expect(progress.arrived, isFalse);
    });

    test('progress detects off-route when far from the line', () {
      final geometry = [const GeoPoint(0.0, 0.0), const GeoPoint(0.0, 0.02)];
      final route = Route(
        geometry: geometry,
        maneuvers: const [],
        totalMeters: GeoPoint.distMeters(geometry[0], geometry[1]),
        totalSeconds: 60,
        cumulative: [0.0, GeoPoint.distMeters(geometry[0], geometry[1])],
      );

      // 1 degree of longitude away — far off the line.
      final progress = NavEngine.progress(route, const GeoPoint(0.0, 1.0), 10.0);

      expect(progress.offRoute, isTrue);
    });
  });
}
