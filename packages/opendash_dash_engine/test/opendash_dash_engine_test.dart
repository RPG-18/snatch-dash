import 'package:flutter_test/flutter_test.dart';
import 'package:opendash_dash_engine/opendash_dash_engine.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_platform_interface.dart';
import 'package:opendash_dash_engine/opendash_dash_engine_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockOpendashDashEnginePlatform
    with MockPlatformInterfaceMixin
    implements OpendashDashEnginePlatform {
  @override
  Future<String?> getPlatformVersion() => Future.value('42');

  @override
  Stream<Map<String, dynamic>> get stateStream => const Stream.empty();

  @override
  Stream<Map<String, dynamic>> get logStream => const Stream.empty();

  @override
  Future<void> connect() async {}

  @override
  Future<void> disconnect() async {}

  @override
  Future<void> setDestination({String? name, double? lat, double? lng}) async {}

  @override
  Future<void> clearDestination() async {}

  @override
  Future<void> setNavState({
    double? remainingMeters,
    double? nextTurnMeters,
    int maneuver = 0x0B,
    String? etaHHMM,
    bool offRoute = false,
    List<List<double>> points = const [],
    List<int> jamSegments = const [],
  }) async {}

  @override
  Future<void> setFollowMode(bool enabled) async {}

  @override
  Future<void> panBy(double dx, double dy) async {}

  @override
  Future<void> zoomIn() async {}

  @override
  Future<void> zoomOut() async {}

  @override
  Future<void> toggleHeadingUp() async {}

  @override
  Future<void> recenter() async {}

  @override
  Future<void> forgetDash() async {}

  @override
  Future<void> setSsid(String ssid) async {}

  @override
  Future<void> setWifiPassword(String password) async {}

  @override
  Future<Map<String, dynamic>> getConfig() async => {};

  @override
  Future<void> updateNowPlaying({String? title, String album = '', String artist = ''}) async {}

  @override
  Future<void> updateCall(String? caller) async {}

  @override
  Future<void> setWallpaper({
    String? path,
    String? kind,
    String? fit,
    double biasX = 0,
    double biasY = 0,
  }) async {}

  @override
  Future<void> playChime() async {}

  @override
  Future<bool> answerCall() async => false;

  @override
  Future<bool> hangupCall() async => false;

  @override
  Future<bool> skipNext() async => false;

  @override
  Future<bool> skipPrevious() async => false;

  @override
  Future<bool> isNotificationAccessGranted() async => false;

  @override
  Future<void> openNotificationAccessSettings() async {}
}

void main() {
  final OpendashDashEnginePlatform initialPlatform = OpendashDashEnginePlatform.instance;

  test('$MethodChannelOpendashDashEngine is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelOpendashDashEngine>());
  });

  test('getPlatformVersion', () async {
    final engine = DashEngine.instance;
    MockOpendashDashEnginePlatform fakePlatform = MockOpendashDashEnginePlatform();
    OpendashDashEnginePlatform.instance = fakePlatform;

    expect(await engine.getPlatformVersion(), '42');
  });
}
