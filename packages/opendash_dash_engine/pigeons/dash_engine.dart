import 'package:pigeon/pigeon.dart';

/// The one description of the engine's platform boundary, from 2026-09-30.
///
/// **Why generate it.** The boundary used to be a `MethodChannel` on one side and
/// a hand-written mirror on the other, and the mirror was maintained by hand in two
/// places: `DashEngineController.publishState` built a 25-key map, and the app's
/// `DashEngineState.fromMap` unpicked it. Nothing checked that the two agreed. A key
/// renamed on one side, a `Long` where a `Double` was expected, a field added and
/// never read — all of it compiles, ships, and shows up as a value that is quietly
/// null on a dash mounted on a moving motorcycle.
///
/// Everything below is generated into `lib/src/messages.g.dart` and
/// `android/.../Messages.g.kt` by `dart run pigeon --input pigeons/dash_engine.dart`.
/// **Edit this file, never the generated ones.**
@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/src/messages.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/com/opendash/opendash_dash_engine/Messages.g.kt',
    kotlinOptions: KotlinOptions(
      package: 'com.opendash.opendash_dash_engine',
      errorClassName: 'DashPigeonError',
    ),
    dartPackageName: 'opendash_dash_engine',
  ),
)
/// Where the K1G session is, mirroring the native `DashState`.
enum DashStage { idle, connecting, authenticating, ready, streaming, error }

/// What the platform last said about the Wi-Fi link, mirroring `WifiConnStatus`.
enum WifiStatus { idle, requesting, connected, error }

/// `DebugLog`'s four levels. `debug` lines are per-packet hex dumps, which is why
/// the app routes them to `verbose` rather than to its own debug level.
enum DashLogLevel { debug, info, warning, error }

/// One position on the route line sent to the dash.
///
/// A class rather than the `List<List<double>>` this used to be: a pair of bare
/// lists has no way to say which element is the latitude, and the two are
/// interchangeable to the compiler right up to the point where the route draws
/// itself across the Indian Ocean.
class NavPoint {
  NavPoint({required this.lat, required this.lng});
  final double lat;
  final double lng;
}

/// Everything the engine publishes about itself, once per update.
///
/// Replaces the 25-key map. The non-null fields carry the defaults the app's
/// hand-written `fromMap` used to apply — `followMode` and `headingUp` true,
/// the flags false — so that a value's absence and its default are one decision
/// made here, not two made independently on each side.
class DashEngineState {
  DashEngineState({
    required this.stage,
    required this.explicitDisconnect,
    required this.wifiStatus,
    this.wifiSsid,
    this.wifiError,
    required this.navigating,
    required this.hasGps,
    this.riderLat,
    this.riderLng,
    this.riderBearing,
    this.riderSpeed,
    this.remainingKm,
    required this.offRoute,
    required this.gpsLost,
    required this.gpsWeak,
    this.errorMessage,
    required this.followMode,
    required this.headingUp,
    required this.zoom,
    this.nowPlayingTitle,
    this.incomingCaller,
    required this.hasActiveCall,
  });

  final DashStage stage;

  /// True only on the single update from inside the native `disconnect()`.
  /// Distinguishes "the rider asked" from "the session died", which otherwise
  /// both arrive as [DashStage.idle].
  final bool explicitDisconnect;

  final WifiStatus wifiStatus;
  final String? wifiSsid;
  final String? wifiError;

  /// A destination has actually been sent to the dash, not merely previewed.
  /// The dash draws a map either way; this drives the chrome. See spec/fsm.md.
  final bool navigating;

  final bool hasGps;
  final double? riderLat;
  final double? riderLng;
  final double? riderBearing;

  /// Ground speed straight from the fix, m/s, null without one. `NavEngine`
  /// falls back to a flat 11 m/s for every ETA when this is missing.
  final double? riderSpeed;

  final double? remainingKm;
  final bool offRoute;
  final bool gpsLost;
  final bool gpsWeak;
  final String? errorMessage;
  final bool followMode;
  final bool headingUp;

  /// A MapLibre camera zoom (13.50), not the hundredths the engine steps it in.
  final double zoom;

  final String? nowPlayingTitle;

  /// Only ever a RINGING call, unlike [hasActiveCall].
  final String? incomingCaller;

  /// Any call at all — ringing, answered or outgoing — so the dash's reject
  /// button can end one that has already been answered.
  final bool hasActiveCall;
}

/// A press on the dash's own joystick.
///
/// Its own event, not a field of [DashEngineState]. On the map-backed channel the
/// two shared one stream and were told apart by asking whether the map happened to
/// contain a `button` key, which meant every reader of the state had to know about
/// buttons in order to ignore them — and a reader that forgot, like `NavLoop`,
/// silently processed a button event as a state update in which every field was null.
class DashButtonEvent {
  DashButtonEvent({required this.code});
  final int code;
}

/// One line of the native log, mirrored for the app's own logger.
class DashLogEntry {
  DashLogEntry({required this.tag, required this.level, required this.message});
  final String tag;
  final DashLogLevel level;
  final String message;
}

/// The saved dash Wi-Fi configuration, as the settings screen reads it.
class DashConfigView {
  DashConfigView({
    required this.ssidPrefix,
    required this.ssid,
    required this.password,
    required this.needsDiscovery,
  });
  final String ssidPrefix;
  final String ssid;
  final String password;

  /// No specific dash identified yet, so the next connect discovers by prefix.
  final bool needsDiscovery;
}

/// Whether the system will let the app keep working with the screen off.
class BatteryOptimisationStatus {
  BatteryOptimisationStatus({
    required this.ignoring,
    required this.canAsk,
    required this.emuiWorkaroundNeeded,
  });

  /// Already exempt.
  final bool ignoring;

  /// A system prompt exists to open.
  final bool canAsk;

  /// The exemption is necessary but not sufficient on this phone — see
  /// `BatteryOptimisation`.
  final bool emuiWorkaroundNeeded;
}

@HostApi()
abstract class DashEngineApi {
  String? getPlatformVersion();

  void connect();

  /// Asynchronous because the farewell packets have to actually leave before the
  /// socket closes, and the rider's "Отключить" button waits for it — see
  /// `DashEngineController.disconnect`. Every path must answer: an unanswered
  /// call leaves the future pending for the life of the process, i.e. a button
  /// that spins forever.
  @async
  void disconnect();

  void setDestination(String? name, double? lat, double? lng);
  void clearDestination();

  /// [jamSegments] is one traffic-level code per geometry segment, so it has
  /// `points.length - 1` entries and index `i` covers `points[i]` to
  /// `points[i + 1]` — the same convention as `nav.Route.jamSegments` on the app
  /// side. Ignored, with a solid line drawn instead, if the length disagrees.
  void setNavState(
    double? remainingMeters,
    double? nextTurnMeters,
    int maneuver,
    String? etaHHMM,
    bool offRoute,
    List<NavPoint> points,
    List<int> jamSegments,
  );

  void setFollowMode(bool enabled);
  void panBy(double dx, double dy);
  void zoomIn();
  void zoomOut();
  void toggleHeadingUp();
  void recenter();

  void forgetDash();
  void setSsid(String ssid);
  void setWifiPassword(String password);
  DashConfigView getConfig();

  BatteryOptimisationStatus batteryOptimisationStatus();

  /// Shows the system prompt. False when there was nothing to ask.
  bool requestIgnoreBatteryOptimisations();

  /// One Flutter-side error into the ride file. Rate-limited in Dart, at the
  /// source — see `lib/util/error_reporting.dart`.
  void rideError(String message);

  void updateNowPlaying(String? title, String album, String artist);
  void updateCall(String? caller);

  /// Turn-guidance chime. No Dart equivalent to `ToneGenerator`, so it lives here.
  void playChime();

  bool answerCall();
  bool hangupCall();
  bool skipNext();
  bool skipPrevious();
  bool isNotificationAccessGranted();
  void openNotificationAccessSettings();
}

/// Native → Dart, one channel per stream.
@EventChannelApi()
abstract class DashEngineEvents {
  DashEngineState state();
  DashButtonEvent button();
  DashLogEntry log();
}
