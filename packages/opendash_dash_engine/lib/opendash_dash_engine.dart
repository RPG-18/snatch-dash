import 'opendash_dash_engine_platform_interface.dart';

/// Dart-side façade over the native dash engine (WiFi pairing, K1G protocol
/// session, GPS, and the off-screen render → H.264 → RTP pipeline). See
/// `DashEngineController` (Kotlin) for the implementation this talks to.
///
/// Navigation math (routing, off-route detection, ETA) is NOT here — that
/// lives in pure Dart (`NavEngine`/`Router`, Phase 2) and its output is fed
/// down via [setNavState]. This class only wraps the platform channel.
class DashEngine {
  DashEngine._();

  static final DashEngine instance = DashEngine._();

  Future<String?> getPlatformVersion() =>
      OpendashDashEnginePlatform.instance.getPlatformVersion();

  /// One map per state update. Keys: stage, wifiStatus, wifiSsid, wifiError,
  /// hasGps, riderLat, riderLng, riderBearing, remainingKm, offRoute,
  /// gpsLost, gpsWeak, errorMessage, followMode, headingUp, zoom, and
  /// (on button events) button.
  Stream<Map<String, dynamic>> get stateStream =>
      OpendashDashEnginePlatform.instance.stateStream;

  /// Mirrors every native `DebugLog` line — keys `tag`, `level` (D/I/W/E),
  /// `message`. Meant to be piped into the app's own logger (see
  /// `util/app_logger.dart`), not read directly by feature code.
  Stream<Map<String, dynamic>> get logStream =>
      OpendashDashEnginePlatform.instance.logStream;

  Future<void> connect() => OpendashDashEnginePlatform.instance.connect();

  Future<void> disconnect() => OpendashDashEnginePlatform.instance.disconnect();

  Future<void> setDestination({String? name, double? lat, double? lng}) =>
      OpendashDashEnginePlatform.instance.setDestination(name: name, lat: lat, lng: lng);

  Future<void> clearDestination() =>
      OpendashDashEnginePlatform.instance.clearDestination();

  Future<void> setNavState({
    double? remainingMeters,
    double? nextTurnMeters,
    int maneuver = 0x0B,
    String? etaHHMM,
    bool offRoute = false,
    List<List<double>> points = const [],
    List<int> jamSegments = const [],
  }) =>
      OpendashDashEnginePlatform.instance.setNavState(
        remainingMeters: remainingMeters,
        nextTurnMeters: nextTurnMeters,
        maneuver: maneuver,
        etaHHMM: etaHHMM,
        offRoute: offRoute,
        points: points,
        jamSegments: jamSegments,
      );

  Future<void> setFollowMode(bool enabled) =>
      OpendashDashEnginePlatform.instance.setFollowMode(enabled);

  Future<void> panBy(double dx, double dy) =>
      OpendashDashEnginePlatform.instance.panBy(dx, dy);

  Future<void> zoomIn() => OpendashDashEnginePlatform.instance.zoomIn();

  Future<void> zoomOut() => OpendashDashEnginePlatform.instance.zoomOut();

  Future<void> toggleHeadingUp() =>
      OpendashDashEnginePlatform.instance.toggleHeadingUp();

  Future<void> recenter() => OpendashDashEnginePlatform.instance.recenter();

  Future<void> forgetDash() => OpendashDashEnginePlatform.instance.forgetDash();

  Future<void> setSsid(String ssid) =>
      OpendashDashEnginePlatform.instance.setSsid(ssid);

  Future<void> setWifiPassword(String password) =>
      OpendashDashEnginePlatform.instance.setWifiPassword(password);

  Future<Map<String, dynamic>> getConfig() =>
      OpendashDashEnginePlatform.instance.getConfig();

  Future<void> updateNowPlaying({String? title, String album = '', String artist = ''}) =>
      OpendashDashEnginePlatform.instance
          .updateNowPlaying(title: title, album: album, artist: artist);

  Future<void> updateCall(String? caller) =>
      OpendashDashEnginePlatform.instance.updateCall(caller);

  Future<void> setWallpaper({
    String? path,
    String? kind,
    String? fit,
    double biasX = 0,
    double biasY = 0,
  }) =>
      OpendashDashEnginePlatform.instance
          .setWallpaper(path: path, kind: kind, fit: fit, biasX: biasX, biasY: biasY);

  Future<void> playChime() => OpendashDashEnginePlatform.instance.playChime();

  Future<bool> answerCall() => OpendashDashEnginePlatform.instance.answerCall();
  Future<bool> hangupCall() => OpendashDashEnginePlatform.instance.hangupCall();
  Future<bool> skipNext() => OpendashDashEnginePlatform.instance.skipNext();
  Future<bool> skipPrevious() => OpendashDashEnginePlatform.instance.skipPrevious();
  Future<bool> isNotificationAccessGranted() =>
      OpendashDashEnginePlatform.instance.isNotificationAccessGranted();
  Future<void> openNotificationAccessSettings() =>
      OpendashDashEnginePlatform.instance.openNotificationAccessSettings();
}
