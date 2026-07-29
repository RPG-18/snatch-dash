import 'geo_point.dart';

/// One destination candidate returned by [PlaceSearch].
class PlaceResult {
  const PlaceResult({
    required this.name,
    required this.address,
    required this.point,
    this.distanceMeters,
  });

  final String name;
  final String address;
  final GeoPoint point;

  /// Straight-line distance from the search origin, or null if no origin
  /// was available (no GPS fix yet).
  final double? distanceMeters;
}
