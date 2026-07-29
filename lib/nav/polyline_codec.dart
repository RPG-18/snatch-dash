import 'geo_point.dart';

/// Decoder/encoder for the Google/OSRM encoded-polyline format (precision 5
/// by default). Ported from `dash/nav/PolylineCodec.kt` — used to store ride
/// tracks compactly (Phase 4) as well as decoding router geometry.
class PolylineCodec {
  PolylineCodec._();

  static List<GeoPoint> decode(String encoded, {int precision = 5}) {
    final factor = pow10(precision);
    final points = <GeoPoint>[];
    var index = 0;
    var lat = 0;
    var lng = 0;
    final len = encoded.length;

    while (index < len) {
      var shift = 0;
      var result = 0;
      int b;
      do {
        b = encoded.codeUnitAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20 && index < len);
      lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

      if (index >= len) break;

      shift = 0;
      result = 0;
      do {
        b = encoded.codeUnitAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20 && index < len);
      lng += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

      points.add(GeoPoint(lat / factor, lng / factor));
    }
    return points;
  }

  static String encode(List<GeoPoint> points, {int precision = 5}) {
    final factor = pow10(precision);
    final sb = StringBuffer();
    var lastLat = 0;
    var lastLng = 0;
    for (final p in points) {
      final lat = (p.lat * factor).round();
      final lng = (p.lng * factor).round();
      _encodeDelta(lat - lastLat, sb);
      _encodeDelta(lng - lastLng, sb);
      lastLat = lat;
      lastLng = lng;
    }
    return sb.toString();
  }

  static void _encodeDelta(int v, StringBuffer sb) {
    var value = v < 0 ? ~(v << 1) : (v << 1);
    while (value >= 0x20) {
      sb.writeCharCode((0x20 | (value & 0x1f)) + 63);
      value >>= 5;
    }
    sb.writeCharCode(value + 63);
  }

  static double pow10(int precision) {
    var f = 1.0;
    for (var i = 0; i < precision; i++) {
      f *= 10.0;
    }
    return f;
  }
}
