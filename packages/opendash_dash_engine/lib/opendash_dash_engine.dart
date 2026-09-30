import 'opendash_dash_engine_platform_interface.dart';
import 'src/messages.g.dart';

/// The generated boundary types, re-exported so callers never import
/// `src/messages.g.dart` directly. The stream FUNCTIONS in that file are
/// deliberately left out: they each build a fresh channel, and the one
/// subscription per stream belongs to [MethodChannelOpendashDashEngine].
export 'src/messages.g.dart'
    show
        BatteryOptimisationStatus,
        DashButtonEvent,
        DashConfigView,
        DashEngineState,
        DashLogEntry,
        DashLogLevel,
        DashStage,
        NavPoint,
        WifiStatus;

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

  /// One [DashEngineState] per update. Described in `pigeons/dash_engine.dart`,
  /// which is also where the native side gets its definition — the two cannot
  /// drift.
  Stream<DashEngineState> get stateStream =>
      OpendashDashEnginePlatform.instance.stateStream;

  /// Presses on the dash's own joystick, on their own channel.
  Stream<DashButtonEvent> get buttonStream =>
      OpendashDashEnginePlatform.instance.buttonStream;

  /// Mirrors every native `DebugLog` line. Meant to be piped into the app's own
  /// logger (see `util/app_logger.dart`), not read directly by feature code.
  Stream<DashLogEntry> get logStream =>
      OpendashDashEnginePlatform.instance.logStream;

  Future<void> connect() => OpendashDashEnginePlatform.instance.connect();

  Future<void> disconnect() => OpendashDashEnginePlatform.instance.disconnect();

  Future<void> setDestination({String? name, double? lat, double? lng}) =>
      OpendashDashEnginePlatform.instance.setDestination(
        name: name,
        lat: lat,
        lng: lng,
      );

  Future<void> clearDestination() =>
      OpendashDashEnginePlatform.instance.clearDestination();

  Future<void> setNavState({
    double? remainingMeters,
    double? nextTurnMeters,
    int maneuver = 0x09,
    String? etaHHMM,
    bool offRoute = false,
    List<NavPoint> points = const [],
    List<int> jamSegments = const [],
  }) => OpendashDashEnginePlatform.instance.setNavState(
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

  Future<DashConfigView> getConfig() =>
      OpendashDashEnginePlatform.instance.getConfig();

  /// Whether the system will let the app keep feeding the dash with the screen
  /// off — see `BatteryOptimisation` (Kotlin) for why the app asks at all and
  /// why Play policy allows it here.
  Future<BatteryOptimisationStatus> batteryOptimisationStatus() =>
      OpendashDashEnginePlatform.instance.batteryOptimisationStatus();

  /// One Flutter-side error into `diag/`, the only log a release build keeps.
  ///
  /// Rate-limited on the native side: a Flutter build error repeats once per
  /// frame, and an unlimited path would bury the `[map]`/`[stream]` telemetry
  /// a post-mortem starts from.
  Future<void> rideError(String message) =>
      OpendashDashEnginePlatform.instance.rideError(message);

  /// Shows the system battery-optimisation prompt.
  ///
  /// Returns false when there was nothing to ask — already exempt. Throws a
  /// [PlatformException] when the prompt cannot be shown at all.
  Future<bool> requestIgnoreBatteryOptimisations() =>
      OpendashDashEnginePlatform.instance.requestIgnoreBatteryOptimisations();

  Future<void> updateNowPlaying({
    String? title,
    String album = '',
    String artist = '',
  }) => OpendashDashEnginePlatform.instance.updateNowPlaying(
    title: title,
    album: album,
    artist: artist,
  );

  Future<void> updateCall(String? caller) =>
      OpendashDashEnginePlatform.instance.updateCall(caller);

  Future<void> playChime() => OpendashDashEnginePlatform.instance.playChime();

  Future<bool> answerCall() => OpendashDashEnginePlatform.instance.answerCall();
  Future<bool> hangupCall() => OpendashDashEnginePlatform.instance.hangupCall();
  Future<bool> skipNext() => OpendashDashEnginePlatform.instance.skipNext();
  Future<bool> skipPrevious() =>
      OpendashDashEnginePlatform.instance.skipPrevious();
  Future<bool> isNotificationAccessGranted() =>
      OpendashDashEnginePlatform.instance.isNotificationAccessGranted();
  Future<void> openNotificationAccessSettings() =>
      OpendashDashEnginePlatform.instance.openNotificationAccessSettings();
}
