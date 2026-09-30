import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'opendash_dash_engine_method_channel.dart';
import 'src/messages.g.dart';

abstract class OpendashDashEnginePlatform extends PlatformInterface {
  OpendashDashEnginePlatform() : super(token: _token);

  static final Object _token = Object();

  static OpendashDashEnginePlatform _instance =
      MethodChannelOpendashDashEngine();

  static OpendashDashEnginePlatform get instance => _instance;

  static set instance(OpendashDashEnginePlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  /// Live engine state — connection stage, Wi-Fi, GPS, camera. One
  /// [DashEngineState] per update; the schema is `pigeons/dash_engine.dart`.
  Stream<DashEngineState> get stateStream {
    throw UnimplementedError('stateStream has not been implemented.');
  }

  /// Presses on the dash's own joystick.
  ///
  /// Its own stream since 2026-09-30. It used to ride the state channel as a
  /// map with a single `button` key, so every reader of the state had to know
  /// about buttons in order to skip them — and `NavLoop`, which did not, read
  /// each press as a state update in which every field was null.
  Stream<DashButtonEvent> get buttonStream {
    throw UnimplementedError('buttonStream has not been implemented.');
  }

  /// Mirrors every native `DebugLog` line. Debug builds only (`DebugLog`
  /// itself no-ops in release).
  Stream<DashLogEntry> get logStream {
    throw UnimplementedError('logStream has not been implemented.');
  }

  Future<void> connect() => throw UnimplementedError();
  Future<void> disconnect() => throw UnimplementedError();

  Future<void> setDestination({String? name, double? lat, double? lng}) =>
      throw UnimplementedError();
  Future<void> clearDestination() => throw UnimplementedError();

  /// [jamSegments] is one traffic-level code per geometry segment (length
  /// `points.length - 1`), so index `i` covers `points[i]` to `points[i+1]`
  /// — same convention as `nav.Route.jamSegments`/`JamLevel.index` on the
  /// Dart app side. Only meaningful when [points] is non-empty; ignored
  /// (native side falls back to a solid line) if its length doesn't match.
  Future<void> setNavState({
    double? remainingMeters,
    double? nextTurnMeters,
    int maneuver = 0x09,
    String? etaHHMM,
    bool offRoute = false,
    List<NavPoint> points = const [],
    List<int> jamSegments = const [],
  }) => throw UnimplementedError();

  Future<void> setFollowMode(bool enabled) => throw UnimplementedError();
  Future<void> panBy(double dx, double dy) => throw UnimplementedError();
  Future<void> zoomIn() => throw UnimplementedError();
  Future<void> zoomOut() => throw UnimplementedError();
  Future<void> toggleHeadingUp() => throw UnimplementedError();
  Future<void> recenter() => throw UnimplementedError();

  Future<void> forgetDash() => throw UnimplementedError();
  Future<void> setSsid(String ssid) => throw UnimplementedError();
  Future<void> setWifiPassword(String password) => throw UnimplementedError();
  Future<DashConfigView> getConfig() => throw UnimplementedError();

  /// Whether the system will let the app keep working with the screen off.
  Future<BatteryOptimisationStatus> batteryOptimisationStatus() =>
      throw UnimplementedError();

  /// Shows the system prompt. Returns false when there was nothing to ask.
  Future<bool> requestIgnoreBatteryOptimisations() => throw UnimplementedError();

  /// One Flutter-side error into the ride file, rate-limited natively.
  Future<void> rideError(String message) => throw UnimplementedError();

  Future<void> updateNowPlaying({
    String? title,
    String album = '',
    String artist = '',
  }) => throw UnimplementedError();
  Future<void> updateCall(String? caller) => throw UnimplementedError();

  /// Turn-guidance chime for [VoiceMode.chime] — no Dart/Flutter equivalent
  /// to `ToneGenerator`, so this stays behind the plugin.
  Future<void> playChime() => throw UnimplementedError();

  /// Media/call bridge — native `NotificationListenerService` +
  /// `MediaSessionManager` forwarding (see the plugin's `media/` package).
  /// Now-playing/incoming-caller info itself arrives via [stateStream]
  /// (`nowPlayingTitle`/`incomingCaller`); these are the control actions.
  Future<bool> answerCall() => throw UnimplementedError();
  Future<bool> hangupCall() => throw UnimplementedError();
  Future<bool> skipNext() => throw UnimplementedError();
  Future<bool> skipPrevious() => throw UnimplementedError();
  Future<bool> isNotificationAccessGranted() => throw UnimplementedError();
  Future<void> openNotificationAccessSettings() => throw UnimplementedError();
}
