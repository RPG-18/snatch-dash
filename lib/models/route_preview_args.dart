import '../nav/geo_point.dart';

/// Handed from [RouteScreen] to `RoutePreviewScreen` via `GoRouterState.extra`
/// when a destination is picked: the chosen place plus the rider's current
/// position (if a GPS fix was available).
class RoutePreviewArgs {
  const RoutePreviewArgs({
    required this.destinationName,
    required this.destination,
    this.origin,
  });

  final String destinationName;
  final GeoPoint destination;
  final GeoPoint? origin;
}
