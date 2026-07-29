import 'package:geolocator/geolocator.dart';

/// The request currently in flight, shared by every concurrent caller — see
/// [ensureLocationPermission].
Future<bool>? _inFlight;

/// Requests GPS permission if not already granted. Returns true once the
/// app can read location (foreground use only — the dash engine's foreground
/// service handles "while in use" background streaming on its own).
///
/// Concurrent callers share one request: `Geolocator.requestPermission()`
/// throws `PermissionRequestInProgressException` if a second request opens
/// while the system dialog is still up, and `currentPosition()` swallows that
/// into a null fix — so on a cold start the route screen's origin lookup and a
/// destination tap could race into a bogus "no GPS fix" error.
Future<bool> ensureLocationPermission() =>
    _inFlight ??= _request().whenComplete(() => _inFlight = null);

Future<bool> _request() async {
  if (!await Geolocator.isLocationServiceEnabled()) return false;

  var permission = await Geolocator.checkPermission();
  if (permission == LocationPermission.denied) {
    permission = await Geolocator.requestPermission();
  }
  return permission == LocationPermission.always ||
      permission == LocationPermission.whileInUse;
}
