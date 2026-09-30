import 'opendash_dash_engine_platform_interface.dart';
import 'src/messages.g.dart' as pigeon;
import 'src/messages.g.dart';

/// The Pigeon-backed implementation of [OpendashDashEnginePlatform].
///
/// **Still a separate layer, on purpose.** Pigeon's generated `DashEngineApi` could
/// be called from the app directly, but then nothing could stand in for it: the
/// app's own tests swap [OpendashDashEnginePlatform.instance] for a fake — that is
/// how `error_reporting_test.dart` watches what reaches the ride file — and a
/// generated concrete class has no seam for that. This keeps the seam and adds
/// nothing else: every method below is one delegation.
class MethodChannelOpendashDashEngine extends OpendashDashEnginePlatform {
  final pigeon.DashEngineApi _api = pigeon.DashEngineApi();

  /// Each of the three is created ONCE and shared.
  ///
  /// Every call to Pigeon's stream function builds a new [EventChannel], and the
  /// native side keeps one sink per channel — so a second call would leave the
  /// first subscriber attached to a sink nothing writes to. There are three Dart
  /// readers of the state alone.
  Stream<DashEngineState>? _stateStream;
  Stream<DashButtonEvent>? _buttonStream;
  Stream<DashLogEntry>? _logStream;

  @override
  Future<String?> getPlatformVersion() => _api.getPlatformVersion();

  @override
  Stream<DashEngineState> get stateStream => _stateStream ??= pigeon.state();

  @override
  Stream<DashButtonEvent> get buttonStream => _buttonStream ??= pigeon.button();

  @override
  Stream<DashLogEntry> get logStream => _logStream ??= pigeon.log();

  @override
  Future<void> connect() => _api.connect();

  @override
  Future<void> disconnect() => _api.disconnect();

  @override
  Future<void> setDestination({String? name, double? lat, double? lng}) =>
      _api.setDestination(name, lat, lng);

  @override
  Future<void> clearDestination() => _api.clearDestination();

  @override
  Future<void> setNavState({
    double? remainingMeters,
    double? nextTurnMeters,
    int maneuver = 0x09,
    String? etaHHMM,
    bool offRoute = false,
    List<NavPoint> points = const [],
    List<int> jamSegments = const [],
  }) => _api.setNavState(
    remainingMeters,
    nextTurnMeters,
    maneuver,
    etaHHMM,
    offRoute,
    points,
    jamSegments,
  );

  @override
  Future<void> setFollowMode(bool enabled) => _api.setFollowMode(enabled);

  @override
  Future<void> panBy(double dx, double dy) => _api.panBy(dx, dy);

  @override
  Future<void> zoomIn() => _api.zoomIn();

  @override
  Future<void> zoomOut() => _api.zoomOut();

  @override
  Future<void> toggleHeadingUp() => _api.toggleHeadingUp();

  @override
  Future<void> recenter() => _api.recenter();

  @override
  Future<void> forgetDash() => _api.forgetDash();

  @override
  Future<void> setSsid(String ssid) => _api.setSsid(ssid);

  @override
  Future<void> setWifiPassword(String password) => _api.setWifiPassword(password);

  @override
  Future<DashConfigView> getConfig() => _api.getConfig();

  @override
  Future<BatteryOptimisationStatus> batteryOptimisationStatus() =>
      _api.batteryOptimisationStatus();

  @override
  Future<bool> requestIgnoreBatteryOptimisations() =>
      _api.requestIgnoreBatteryOptimisations();

  @override
  Future<void> rideError(String message) => _api.rideError(message);

  @override
  Future<void> updateNowPlaying({
    String? title,
    String album = '',
    String artist = '',
  }) => _api.updateNowPlaying(title, album, artist);

  @override
  Future<void> updateCall(String? caller) => _api.updateCall(caller);

  @override
  Future<void> playChime() => _api.playChime();

  @override
  Future<bool> answerCall() => _api.answerCall();

  @override
  Future<bool> hangupCall() => _api.hangupCall();

  @override
  Future<bool> skipNext() => _api.skipNext();

  @override
  Future<bool> skipPrevious() => _api.skipPrevious();

  @override
  Future<bool> isNotificationAccessGranted() => _api.isNotificationAccessGranted();

  @override
  Future<void> openNotificationAccessSettings() =>
      _api.openNotificationAccessSettings();
}
