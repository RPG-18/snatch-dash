import 'package:geolocator/geolocator.dart';

import '../nav/geo_point.dart';
import 'location_permission.dart';

/// One-shot high-accuracy GPS fix, or null if permission/location fails.
Future<GeoPoint?> currentPosition() async {
  try {
    if (!await ensureLocationPermission()) return null;
    final pos = await Geolocator.getCurrentPosition(
      locationSettings: const LocationSettings(accuracy: LocationAccuracy.high),
    );
    return GeoPoint(pos.latitude, pos.longitude);
  } catch (_) {
    return null;
  }
}
